package dev.vaayil.core.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    private val pass = "correct horse battery".toCharArray()
    private val plain = """{"version":4,"profiles":[]}"""
    private val fast = 1_000 // PBKDF2 rounds for tests; one test below uses the production count

    @Test
    fun `seal and open round-trip`() {
        val sealed = BackupCrypto.seal(plain, pass, fast)
        assertTrue(BackupCrypto.isSealed(sealed))
        assertEquals(plain, BackupCrypto.open(sealed, pass))
    }

    @Test
    fun `wrong passphrase fails without detail`() {
        val sealed = BackupCrypto.seal(plain, pass, fast)
        val e = assertThrows(BackupCryptoException::class.java) { BackupCrypto.open(sealed, "wrong passphrase".toCharArray()) }
        assertEquals("wrong passphrase or damaged file", e.message)
    }

    @Test
    fun `a flipped ciphertext byte fails`() {
        val sealed = BackupCrypto.seal(plain, pass, fast)
        val env = Json.decodeFromString(BackupCrypto.Sealed.serializer(), sealed)
        val bytes = java.util.Base64.getDecoder().decode(env.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = Json.encodeToString(BackupCrypto.Sealed.serializer(), env.copy(ciphertext = java.util.Base64.getEncoder().encodeToString(bytes)))
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.open(tampered, pass) }
    }

    @Test
    fun `two seals of the same input differ in salt and iv`() {
        val a = Json.decodeFromString(BackupCrypto.Sealed.serializer(), BackupCrypto.seal(plain, pass, fast))
        val b = Json.decodeFromString(BackupCrypto.Sealed.serializer(), BackupCrypto.seal(plain, pass, fast))
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.iv, b.iv)
        assertNotEquals(a.ciphertext, b.ciphertext)
    }

    @Test
    fun `plain v4 json is not sealed`() {
        assertFalse(BackupCrypto.isSealed(plain))
        assertFalse(BackupCrypto.isSealed("not json"))
    }

    @Test
    fun `absurd iteration counts are refused before deriving`() {
        val env = Json.decodeFromString(BackupCrypto.Sealed.serializer(), BackupCrypto.seal(plain, pass, fast))
        val hostile = Json.encodeToString(BackupCrypto.Sealed.serializer(), env.copy(iterations = 100_000_000))
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.open(hostile, pass) }
    }

    @Test
    fun `production iteration count seals and opens`() {
        val sealed = BackupCrypto.seal(plain, pass)
        assertEquals(BackupCrypto.ITERATIONS, Json.decodeFromString(BackupCrypto.Sealed.serializer(), sealed).iterations)
        assertEquals(plain, BackupCrypto.open(sealed, pass))
    }

    @Test
    fun `parseSealed parses a sealed envelope once and rejects plain text`() {
        val pass = "snapshot passphrase".toCharArray()
        val sealed = BackupCrypto.seal("{\"version\":4,\"profiles\":[]}", pass, iterations = 1_000)
        val env = BackupCrypto.parseSealed(sealed)
        assertTrue(env != null && env.version == BackupCrypto.VERSION)
        assertEquals("{\"version\":4,\"profiles\":[]}", BackupCrypto.open(env!!, pass))
        assertNull(BackupCrypto.parseSealed("{\"version\":4,\"profiles\":[]}"))
        assertNull(BackupCrypto.parseSealed("not json"))
    }
}
