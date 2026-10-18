package dev.vaayil.core.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BackupCryptoException(message: String) : Exception(message)

/**
 * Passphrase encryption for .vylconf snapshots (format version 5). The sealed envelope wraps
 * the plain v4 JSON: PBKDF2-HMAC-SHA256 ([ITERATIONS] rounds, 16-byte salt) derives an
 * AES-256 key; AES-GCM with a 12-byte IV and 128-bit tag seals the payload, binding the
 * format version and salt as associated data. Wrong passphrase and tampering are
 * indistinguishable by design.
 */
object BackupCrypto {
    const val VERSION = 5
    const val ITERATIONS = 600_000
    private const val KDF = "pbkdf2-hmac-sha256"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    /** Bounds on an iteration count we are willing to derive: a hostile file must not be
     *  able to demand hours of key derivation, and tests pass a fast count. */
    private const val MIN_ITERATIONS = 1_000
    private const val MAX_ITERATIONS = 10_000_000

    @Serializable
    data class Sealed(
        val version: Int,
        val kdf: String,
        val iterations: Int,
        val salt: String,
        val iv: String,
        val ciphertext: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    /** The parsed v5 envelope, or null when [text] is not a sealed snapshot. Parse once; hand the result to [open]. */
    fun parseSealed(text: String): Sealed? =
        runCatching { json.decodeFromString(Sealed.serializer(), text) }.getOrNull()
            ?.takeIf { it.version == VERSION && it.ciphertext.isNotEmpty() }

    fun isSealed(text: String): Boolean = parseSealed(text) != null

    fun seal(plain: String, passphrase: CharArray, iterations: Int = ITERATIONS): String {
        require(iterations >= MIN_ITERATIONS) { "iterations out of range" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val saltB64 = b64(salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(saltB64))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return json.encodeToString(Sealed.serializer(), Sealed(VERSION, KDF, iterations, saltB64, b64(iv), b64(sealed)))
    }

    fun open(text: String, passphrase: CharArray): String =
        open(parseSealed(text) ?: throw BackupCryptoException("not a sealed snapshot"), passphrase)

    fun open(env: Sealed, passphrase: CharArray): String {
        if (env.version != VERSION || env.kdf != KDF) throw BackupCryptoException("unsupported sealed snapshot")
        // A hostile file must not be able to demand hours of key derivation.
        if (env.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw BackupCryptoException("unsupported sealed snapshot")
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(passphrase, unb64(env.salt), env.iterations),
                GCMParameterSpec(TAG_BITS, unb64(env.iv)),
            )
            cipher.updateAAD(aad(env.salt))
            String(cipher.doFinal(unb64(env.ciphertext)), Charsets.UTF_8)
        } catch (_: Exception) {
            throw BackupCryptoException("wrong passphrase or damaged file")
        }
    }

    private fun aad(saltB64: String) = "vylconf:$VERSION:$saltB64".toByteArray(Charsets.UTF_8)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}
