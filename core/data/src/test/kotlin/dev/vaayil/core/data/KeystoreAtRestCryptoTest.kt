package dev.vaayil.core.data

import android.app.Application
import android.security.keystore.StrongBoxUnavailableException
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class KeystoreAtRestCryptoTest {
    private fun jvmKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `key is acquired lazily and retried until the source delivers`() {
        var attempts = 0
        val key = jvmKey()
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 0) { attempts++; if (attempts < 3) null else key }
        assertFalse(crypto.active.value)
        assertEquals("plain", crypto.encrypt("plain"))
        assertEquals("plain", crypto.encrypt("plain"))
        val sealed = crypto.encrypt("plain")
        assertTrue(sealed.startsWith("enc1:"))
        assertTrue(crypto.active.value)
        assertEquals("plain", crypto.decrypt(sealed))
        assertEquals(3, attempts)
    }

    @Test
    fun `warm flips active without any encrypt call`() {
        var attempts = 0
        val crypto = KeystoreAtRestCrypto { attempts++; jvmKey() }
        assertFalse(crypto.active.value)
        crypto.warm()
        assertTrue(crypto.active.value)
        assertEquals(1, attempts)
    }

    @Test
    fun `a marked value with no key throws instead of leaking the blob`() {
        val crypto = KeystoreAtRestCrypto { null }
        assertThrows(AtRestUnavailableException::class.java) { crypto.decrypt("enc1:AAAA") }
    }

    @Test
    fun `an unknown format version fails loud and still counts as encrypted`() {
        val crypto = KeystoreAtRestCrypto { jvmKey() }
        assertThrows(AtRestFormatException::class.java) { crypto.decrypt("enc2:AAAA") }
        assertTrue(crypto.isEncrypted("enc2:AAAA"))
        assertEquals(2, AtRestCrypto.versionOf("enc2:AAAA"))
        assertEquals(null, AtRestCrypto.versionOf("plain"))
    }

    @Test
    fun `a strongbox failure falls back to a tee attempt with its own spec`() {
        val attempts = mutableListOf<Boolean>()
        val key = generateWithStrongBoxFallback(strongBoxFirst = true) { strongBox ->
            attempts += strongBox
            if (strongBox) throw StrongBoxUnavailableException("no StrongBox on this device") else "tee-key"
        }
        assertEquals("tee-key", key)
        assertEquals(listOf(true, false), attempts)
    }

    @Test
    fun `a strongbox success never tries the tee`() {
        val attempts = mutableListOf<Boolean>()
        val key = generateWithStrongBoxFallback(strongBoxFirst = true) { strongBox -> attempts += strongBox; "sb-key" }
        assertEquals("sb-key", key)
        assertEquals(listOf(true), attempts)
    }

    @Test
    fun `both attempts failing yields null and a pre-28 device goes straight to the tee`() {
        assertNull(generateWithStrongBoxFallback(strongBoxFirst = true) { throw IllegalStateException("keystore down") })
        val attempts = mutableListOf<Boolean>()
        assertEquals("tee-key", generateWithStrongBoxFallback(strongBoxFirst = false) { attempts += it; "tee-key" })
        assertEquals(listOf(false), attempts)
    }

    @Test
    fun `each spec carries its own strongbox flag`() {
        assertTrue(keySpec(strongBox = true).isStrongBoxBacked)
        assertFalse(keySpec(strongBox = false).isStrongBoxBacked)
    }

    @Test
    fun `a value sealed under another key is corruption not unavailability`() {
        val sealed = KeystoreAtRestCrypto(retryBackoffMs = 0) { jvmKey() }.encrypt("secret")
        val other = KeystoreAtRestCrypto(retryBackoffMs = 0) { jvmKey() }
        assertThrows(AtRestCorruptException::class.java) { other.decrypt(sealed) }
        assertTrue(other.active.value) // the key is fine; the row is not
    }

    @Test
    fun `a blob too short to hold an iv and a tag is corruption and never reaches the keystore`() {
        var ciphers = 0
        val key = jvmKey()
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 0, newCipher = { ciphers++; realCipher() }) { key }.also { it.warm() }
        val short = AtRestCrypto.MARKER + java.util.Base64.getEncoder().encodeToString(ByteArray(20))
        assertThrows(AtRestCorruptException::class.java) { crypto.decrypt(short) }
        assertEquals(0, ciphers) // classified from its length alone: AndroidKeyStore's error for short input is not an AEAD failure
        assertTrue(crypto.active.value) // the key is fine; the row is not
        // The shortest real value (an empty plaintext: IV + tag, 28 bytes) still reads, through a reader with an empty cache.
        val sealedEmpty = KeystoreAtRestCrypto(retryBackoffMs = 0) { key }.encrypt("")
        assertEquals("", crypto.decrypt(sealedEmpty))
    }

    @Test
    fun `a keystore-level failure drops the key and reports unavailable until the source delivers again`() {
        var attempts = 0
        val good = jvmKey()
        // A DES key cannot initialise AES/GCM: Cipher.init throws InvalidKeyException, the shape of a Keystore that stopped cooperating.
        val broken = javax.crypto.spec.SecretKeySpec(ByteArray(8), "DES")
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 0, sleep = {}) { attempts++; if (attempts == 1) broken else good }
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("secret") }
        assertFalse(crypto.active.value)
        val sealed = crypto.encrypt("secret") // re-acquired from the source
        assertTrue(sealed.startsWith("enc1:"))
        assertTrue(crypto.active.value)
        assertEquals("secret", crypto.decrypt(sealed))
        assertEquals(2, attempts)
    }

    @Test
    fun `failed acquisitions back off instead of hammering the source`() {
        var attempts = 0
        var now = 1_000L
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 30_000, clock = { now }) { attempts++; null }
        assertEquals("plain", crypto.encrypt("plain"))
        assertEquals("plain", crypto.encrypt("plain"))
        assertEquals(1, attempts) // the second call inside the window never asked
        now += 30_000
        assertEquals("plain", crypto.encrypt("plain"))
        assertEquals(2, attempts)
    }

    @Test
    fun `a cipher-level failure arms the backoff like a failed acquisition`() {
        var attempts = 0
        var now = 1_000L
        // A DES key cannot initialise AES/GCM: the source delivers cleanly and the cipher fails.
        val broken = javax.crypto.spec.SecretKeySpec(ByteArray(8), "DES")
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 30_000, clock = { now }, sleep = {}) { attempts++; broken }
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("secret") }
        assertFalse(crypto.active.value)
        assertEquals(1, attempts)
        // The key was held once, so the value must never degrade to plaintext (F13): inside
        // the window the source is left alone and encrypt refuses rather than writing plaintext.
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("secret") }
        assertEquals(1, attempts)
        now += 30_000
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("secret") }
        assertEquals(2, attempts) // the window expired: the source is asked again
    }

    private fun realCipher() = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")

    @Test
    fun `a transient keystore error is retried and keeps the key`() {
        var ciphers = 0
        val sleeps = mutableListOf<Long>()
        val key = jvmKey()
        var sourceCalls = 0
        val crypto = KeystoreAtRestCrypto(
            retryBackoffMs = 30_000,
            sleep = { sleeps += it },
            newCipher = { if (ciphers++ == 0) throw java.security.ProviderException("pruned") else realCipher() },
        ) { sourceCalls++; key }
        val sealed = crypto.encrypt("secret")
        assertTrue(sealed.startsWith("enc1:"))
        assertTrue(crypto.active.value)
        assertEquals(listOf(50L), sleeps)
        assertEquals(1, sourceCalls)
    }

    @Test
    fun `a keystore error that outlasts the retries drops the key and refuses plaintext`() {
        val sleeps = mutableListOf<Long>()
        val crypto = KeystoreAtRestCrypto(
            retryBackoffMs = 30_000,
            clock = { 1_000L },
            sleep = { sleeps += it },
            newCipher = { throw java.security.ProviderException("pruned") },
        ) { jvmKey() }
        crypto.warm() // a key is held
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("secret") }
        assertEquals(listOf(50L, 150L), sleeps) // three attempts in all
        assertFalse(crypto.active.value)
        // Inside the backoff window, with a key held earlier in this process: never plaintext.
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("secret") }
    }

    @Test
    fun `a repeated decrypt is served from memory`() {
        var ciphers = 0
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 0, newCipher = { ciphers++; realCipher() }) { jvmKey() }.also { it.warm() }
        val sealed = crypto.encrypt("secret")
        assertEquals("secret", crypto.decrypt(sealed))
        assertEquals("secret", crypto.decrypt(sealed))
        assertEquals(1, ciphers) // the encrypt; both reads hit the cache it filled
    }

    @Test
    fun `a value decrypted once is served from memory afterwards`() {
        val key = jvmKey()
        val sealed = KeystoreAtRestCrypto { key }.encrypt("secret") // sealed by another instance: the reader's cache starts empty
        var ciphers = 0
        val reader = KeystoreAtRestCrypto(newCipher = { ciphers++; realCipher() }) { key }
        assertEquals("secret", reader.decrypt(sealed))
        assertEquals("secret", reader.decrypt(sealed))
        assertEquals(1, ciphers) // the first read filled the cache; the second never reached the Keystore
    }

    @Test
    fun `a cached value still reads while the key is dropped`() {
        var failing = false
        val crypto = KeystoreAtRestCrypto(
            retryBackoffMs = 30_000,
            clock = { 1_000L },
            sleep = {},
            newCipher = { if (failing) throw java.security.ProviderException("pruned") else realCipher() },
        ) { jvmKey() }
        crypto.warm()
        val sealed = crypto.encrypt("secret")
        failing = true
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("other") } // drops the key
        assertEquals("secret", crypto.decrypt(sealed)) // the profile list does not vanish (F13)
    }

    @Test
    fun `a reader that retried takes the value another reader decrypted meanwhile`() {
        val key = jvmKey()
        val sealed = KeystoreAtRestCrypto(retryBackoffMs = 0) { key }.encrypt("secret")
        val ciphers = java.util.concurrent.atomic.AtomicInteger()
        val firstAttemptFailed = java.util.concurrent.CountDownLatch(1)
        val otherReaderDone = java.util.concurrent.CountDownLatch(1)
        val reader = KeystoreAtRestCrypto(
            retryBackoffMs = 0,
            // The pause between attempts runs outside the lock: hold the first reader there while a second one reads.
            sleep = { firstAttemptFailed.countDown(); otherReaderDone.await(5, java.util.concurrent.TimeUnit.SECONDS) },
            newCipher = { if (ciphers.getAndIncrement() == 0) throw java.security.ProviderException("pruned") else realCipher() },
        ) { key }.also { it.warm() }
        var first: String? = null
        val slow = Thread { first = reader.decrypt(sealed) }.apply { start() }
        assertTrue(firstAttemptFailed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("secret", reader.decrypt(sealed)) // the second reader: one cipher, fills the cache
        otherReaderDone.countDown()
        slow.join(5_000)
        assertEquals("secret", first)
        assertEquals(2, ciphers.get()) // the first reader's failed attempt + the second reader's; the retry found the cached value
    }

    @Test
    fun `keystore operations never overlap`() {
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        val maxInFlight = java.util.concurrent.atomic.AtomicInteger()
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 0, newCipher = {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(1)
            inFlight.decrementAndGet()
            realCipher()
        }) { jvmKey() }.also { it.warm() }
        val threads = (1..8).map { t ->
            Thread { repeat(25) { i -> crypto.decrypt(crypto.encrypt("s$t-$i")) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, maxInFlight.get())
    }

    @Test
    fun `a failed acquisition schedules a re-arm that brings the key back`() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        var deliver = false
        val key = jvmKey()
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 30_000, clock = { 1_000L },
            scheduleRearm = { delay, action -> scheduled += delay to action }) { if (deliver) key else null }
        assertEquals("plain", crypto.encrypt("plain")) // never held: the S-3 degraded posture is unchanged
        assertEquals(1, scheduled.size)
        assertEquals(30_000L, scheduled.single().first)
        deliver = true
        scheduled.single().second.invoke()
        assertTrue(crypto.active.value) // no read or write was needed to bring the key back
    }

    @Test
    fun `a dropped key is re-armed without any further read`() {
        val scheduled = mutableListOf<() -> Unit>()
        var failing = false
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 30_000, clock = { 1_000L }, sleep = {},
            newCipher = { if (failing) throw java.security.ProviderException("pruned") else realCipher() },
            scheduleRearm = { _, action -> scheduled += action }) { jvmKey() }
        crypto.warm()
        failing = true
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("x") }
        assertFalse(crypto.active.value)
        failing = false
        scheduled.last().invoke()
        assertTrue(crypto.active.value)
    }

    @Test
    fun `re-arming gives up after 20 failed attempts`() {
        val scheduled = ArrayDeque<() -> Unit>()
        var total = 0
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 30_000, clock = { 1_000L },
            scheduleRearm = { _, action -> total++; scheduled.addLast(action) }) { null }
        crypto.warm()
        while (scheduled.isNotEmpty()) scheduled.removeFirst().invoke()
        assertEquals(20, total)
    }

    @Test
    fun `outage backoff counts acquisitions not row reads`() {
        val scheduled = mutableListOf<() -> Unit>()
        var reads = 0
        var now = 1_000L
        val key = jvmKey()
        // The absence policy refuses while encrypted values exist and no key can be handed out:
        // its exception is the outage posture, not a source that simply has no key right now.
        val crypto = KeystoreAtRestCrypto(
            retryBackoffMs = 30_000,
            clock = { now },
            scheduleRearm = { _, action -> scheduled += action },
        ) { reads++; if (reads == 1) throw AtRestUnavailableException() else key }
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("secret") }
        assertEquals(1, reads)
        // Inside the window the source is left alone, and the refusal is already sticky: no plaintext.
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("other") }
        assertEquals(1, reads)
        now += 30_000
        scheduled.single().invoke() // the re-arm performs the next acquisition, without a row read
        assertEquals(2, reads)
        assertTrue(crypto.active.value)
    }
}
