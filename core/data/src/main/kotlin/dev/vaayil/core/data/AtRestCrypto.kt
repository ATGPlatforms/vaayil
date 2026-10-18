package dev.vaayil.core.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AtRestUnavailableException : IllegalStateException("at-rest key unavailable; an encrypted value cannot be read")

class AtRestFormatException(version: Int) : IllegalStateException("unsupported at-rest format enc$version")

/** The value does not authenticate under the held key: sealed under another key, or a damaged row (S-4). */
class AtRestCorruptException(cause: Throwable) :
    IllegalStateException("at-rest value does not authenticate under the held key", cause)

/**
 * Encryption for secrets at rest (private key, preshared key). Everything above the data
 * layer sees plaintext; the repository encrypts on write and decrypts on read. Stored shape:
 * `enc<version>:` + base64(iv ‖ ciphertext) inside the existing TEXT columns. Version 1 is
 * the only format this build writes or reads; any other version fails loud instead of being
 * mistaken for plaintext.
 */
interface AtRestCrypto {
    /** False while no key is held. Writes stay plaintext only while nothing encrypted exists (S-3);
        with encrypted values present and no key — never acquired, or dropped after an outage — they
        are refused until the key is back (F13, DATA-5). */
    val active: StateFlow<Boolean>

    /** Forces a key re-acquisition now, ignoring the retry backoff (ProfileRepository's
        one-shot save retry after a transient [AtRestUnavailableException]). */
    fun warm() {}

    fun isEncrypted(stored: String): Boolean = MARKED.containsMatchIn(stored)

    fun encrypt(plain: String): String

    fun decrypt(stored: String): String

    companion object {
        const val MARKER = "enc1:"
        val MARKED = Regex("^enc\\d+:")

        /** Format version of a marked value; null for plaintext. */
        fun versionOf(stored: String): Int? =
            MARKED.find(stored)?.value?.removePrefix("enc")?.removeSuffix(":")?.toIntOrNull()
    }
}

/** Identity implementation for unit tests; [active] is false so callers can surface the posture. */
object PlaintextAtRestCrypto : AtRestCrypto {
    override val active: StateFlow<Boolean> = MutableStateFlow(false)
    override fun encrypt(plain: String) = plain
    override fun decrypt(stored: String) = stored
}

/**
 * AndroidKeyStore AES-256-GCM. The wrapping key is obtained LAZILY on first use and asked for
 * again while absent (with a backoff), so a Keystore that was not ready at process start does
 * not condemn the whole session to plaintext (S-3). Every Keystore call — acquisition and
 * cipher alike — runs behind one lock: StrongBox has a handful of operation slots, and
 * keystore2 prunes the oldest handle when collectors decode in parallel (F13). An operation
 * that hits a transient Keystore error is retried twice before the key is dropped, a dropped
 * key is re-acquired on a timer ([AtRestRearm]), and decrypted values are cached in memory so
 * a row read once never reaches the Keystore again.
 *
 * The key source is the guarded acquisition ([AtRestKeyRecovery], wired by DataGraph): a failed
 * read is never an absence, and the source reports [AtRestUnavailableException] while encrypted
 * values exist without a usable key. Refusal is STICKY (F13, DATA-5): once a key was held in this
 * process, or the source reported such an outage, a missing key never degrades to plaintext, and
 * encrypt refuses instead of writing plaintext. Failures are classified here, where the platform
 * exceptions are known: only an authentication failure is [AtRestCorruptException]. Nothing above
 * this class sees a raw Keystore exception.
 */
class KeystoreAtRestCrypto(
    /** After a failed acquisition the source is left alone for this long (no Binder storm per row). */
    private val retryBackoffMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Pauses between the attempts of ONE operation that hit a transient Keystore error (F13). */
    private val transientRetryDelaysMs: List<Long> = listOf(50L, 150L),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** Builds each cipher; tests script Keystore failures here. */
    private val newCipher: () -> Cipher = { Cipher.getInstance(TRANSFORMATION) },
    /** Runs [action] after [delayMs]; production passes [AtRestRearm.schedule], tests capture it. */
    private val scheduleRearm: (delayMs: Long, action: () -> Unit) -> Unit = { _, _ -> },
    /** The guarded key source (B4); DataGraph passes [AtRestKeyRecovery.acquire]. Last, so
        `KeystoreAtRestCrypto { key }` keeps working as a trailing lambda. */
    private val keySource: () -> SecretKey?,
) : AtRestCrypto {
    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Every Keystore call (acquisition and cipher) runs under this lock: StrongBox has a handful of
        operation slots, and keystore2 prunes the oldest when collectors decode in parallel (F13). */
    private val opLock = Any()

    @Volatile private var key: SecretKey? = null
    /** Refusal posture: a key was held at some point in this process, or the key source reported
        an outage over encrypted values (DATA-5). From then on a missing key is an outage, never
        the S-3 "no Keystore" posture, and encrypt refuses rather than writing plaintext (F13). */
    @Volatile private var plaintextRefused = false
    private var nextAttemptAt = 0L
    private var rearmPending = false
    private var rearmAttempts = 0

    /** Decrypted values by stored string (F13): a row read once never reaches the Keystore again. */
    private val plainCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_ENTRIES
    }

    private fun keyOrNull(force: Boolean = false): SecretKey? {
        key?.let { return it }
        synchronized(opLock) {
            key?.let { return it }
            if (!force && clock() < nextAttemptAt) return null
            val fresh = try {
                keySource()
            } catch (_: AtRestUnavailableException) {
                // Encrypted values exist and no key can be handed out: an outage, never the S-3
                // posture, and the refusal outlives this backoff window (DATA-5).
                plaintextRefused = true
                null
            } catch (_: Exception) {
                null // nothing above this class sees a raw Keystore exception
            }
            if (fresh == null) {
                armBackoff()
                return null
            }
            key = fresh
            plaintextRefused = true
            rearmAttempts = 0
            _active.value = true
            return fresh
        }
    }

    /** Caller holds [opLock]. Arms the backoff and schedules one re-acquisition when it expires, so
        rows hidden by an absent key come back without waiting for a DB change (F13). */
    private fun armBackoff() {
        nextAttemptAt = clock() + retryBackoffMs
        if (rearmPending || rearmAttempts >= MAX_REARM_ATTEMPTS) return
        rearmPending = true
        rearmAttempts++
        scheduleRearm(retryBackoffMs) {
            synchronized(opLock) { rearmPending = false }
            keyOrNull(force = true)
        }
    }

    /**
     * A Keystore-level failure that outlasted the retries: forget the handle so a later call
     * re-acquires (S-3). The retry window is armed here too — a wedged Keystore that fails
     * inside the cipher is the same hazard as one that refuses the acquisition, and with
     * several collectors observing the profile snapshot an un-armed drop is a binder call per row.
     */
    private fun dropKey() {
        synchronized(opLock) {
            key = null
            _active.value = false
            armBackoff()
        }
    }

    /**
     * Acquires the key now if the Keystore can supply one, ignoring the backoff, so a fresh
     * install does not report the plaintext posture for the whole session (S-3).
     */
    override fun warm() {
        keyOrNull(force = true)
    }

    override fun encrypt(plain: String): String {
        val k = keyOrNull() ?: if (plaintextRefused) throw AtRestUnavailableException() else return plain
        val blob = keystoreOp {
            val cipher = newCipher()
            cipher.init(Cipher.ENCRYPT_MODE, k)
            cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        }
        val stored = AtRestCrypto.MARKER + java.util.Base64.getEncoder().encodeToString(blob)
        synchronized(opLock) { plainCache[stored] = plain }
        return stored
    }

    override fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        val version = AtRestCrypto.versionOf(stored)
        if (version != 1) throw AtRestFormatException(version ?: -1)
        synchronized(opLock) { plainCache[stored] }?.let { return it }
        val k = keyOrNull() ?: throw AtRestUnavailableException()
        val blob = try {
            java.util.Base64.getDecoder().decode(stored.removePrefix(AtRestCrypto.MARKER))
        } catch (e: IllegalArgumentException) {
            throw AtRestCorruptException(e) // not even base64: the row itself is damaged
        }
        // Shorter than IV + tag cannot be a sealed value. The JVM provider calls that a bad tag, but AndroidKeyStore
        // throws a non-AEAD error, which would count as an outage and drop the key every re-arm cycle while the
        // row never shows as unreadable.
        if (blob.size < GCM_IV_BYTES + GCM_TAG_BITS / 8) throw AtRestCorruptException(IllegalArgumentException("blob shorter than an IV and a tag"))
        val plain = try {
            keystoreOp {
                // Re-checked under the lock: a reader that queued behind another reader of the same row takes its value
                // instead of paying a second Keystore operation (F13). Filled under the same lock.
                plainCache[stored] ?: run {
                    val cipher = newCipher()
                    cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES))
                    String(cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES), Charsets.UTF_8)
                        .also { plainCache[stored] = it }
                }
            }
        } catch (e: AEADBadTagException) {
            throw AtRestCorruptException(e) // sealed under another key: the one genuine "unreadable" (S-4)
        }
        return plain
    }

    /**
     * One Keystore operation under [opLock], with the transient retries (F13). An authentication
     * failure is never transient: it propagates untouched for [decrypt] to classify as corruption.
     * Anything else that outlasts the retries drops the key and reports unavailable. Each attempt
     * locks on its own, so the pause between attempts never blocks the other callers.
     */
    private fun <T> keystoreOp(op: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return synchronized(opLock) { op() }
            } catch (e: AEADBadTagException) {
                throw e
            } catch (e: GeneralSecurityException) {
                if (attempt >= transientRetryDelaysMs.size) { dropKey(); throw AtRestUnavailableException() }
            } catch (e: ProviderException) {
                if (attempt >= transientRetryDelaysMs.size) { dropKey(); throw AtRestUnavailableException() }
            }
            sleep(transientRetryDelaysMs[attempt++])
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val CACHE_ENTRIES = 128
        const val MAX_REARM_ATTEMPTS = 20
    }
}

/** The at-rest key's re-arm timer (F13): one daemon thread, nothing else runs on it. */
internal object AtRestRearm {
    private val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "vaayil-atrest-rearm").apply { isDaemon = true }
    }

    fun schedule(delayMs: Long, action: () -> Unit) {
        executor.schedule({ runCatching(action) }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}

/**
 * Production key READ (B4): the AndroidKeyStore AES-256-GCM key. Null only when the alias is
 * absent — `getEntry` returned null, or a non-secret-key entry. A Keystore call that fails
 * (`getInstance`, `load`, `getEntry`) THROWS: a transient keystore2 error must never read as an
 * absent alias, or the absence policy would replace the real key and unseal every stored secret.
 * Generation is [generateAndroidKeystoreKey]'s job, and the policy decides when it may run.
 */
internal fun androidKeystoreKey(): SecretKey? {
    val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
}

/**
 * Runs [generate] for StrongBox first and, if that throws for any reason, once more for the
 * TEE. Each call builds its OWN spec: KeyGenParameterSpec.Builder is mutable, and reusing one
 * that already asked for StrongBox re-requests StrongBox (the bug that left every device
 * without a StrongBox chip on the plaintext fallback). Null only when both attempts fail.
 */
internal fun <K> generateWithStrongBoxFallback(strongBoxFirst: Boolean, generate: (strongBox: Boolean) -> K): K? =
    try {
        if (strongBoxFirst) {
            try {
                generate(true)
            } catch (_: Exception) {
                generate(false)
            }
        } else {
            generate(false)
        }
    } catch (_: Exception) {
        null
    }

/**
 * Production key GENERATION (B4), TEE-backed with a StrongBox attempt first; only the absence
 * policy ([AtRestKeyRecovery]) calls it. Null when neither attempt can produce a key.
 */
internal fun generateAndroidKeystoreKey(): SecretKey? =
    generateWithStrongBoxFallback(strongBoxFirst = Build.VERSION.SDK_INT >= 28) { strongBox ->
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(keySpec(strongBox))
        generator.generateKey()
    }

/** A fresh spec per attempt; the StrongBox flag is the only difference between the two. */
internal fun keySpec(strongBox: Boolean): KeyGenParameterSpec =
    KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
        .build()

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val KEY_ALIAS = "vaayil_at_rest"
