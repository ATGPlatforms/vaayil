package dev.vaayil.core.data

import java.io.File
import java.security.KeyStoreException
import java.util.Properties
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B4's acquisition policy on the JVM: the class takes every platform input as a seam — key
 * source, generator, clock, encrypted-values predicate, record directory — so the absence
 * evidence, the thresholds and the outage-versus-absence split run without Robolectric.
 */
class AtRestKeyRecoveryTest {
    @get:Rule
    val directory = TemporaryFolder()

    private val originalKey: SecretKey = jvmKey()
    private val generatedKey: SecretKey = jvmKey()

    /** Movable clock: only the span between reads matters, never elapsed wall time. */
    private var now = 1_000L
    private var reads = 0
    private var generations = 0
    private var probes = 0
    private var encryptedValues = false

    /** What one read does, replaced per case; counts itself so each case can assert its reads. */
    private var read: () -> SecretKey? = { reads++; null }

    private fun recovery(dir: File = directory.root) = AtRestKeyRecovery(
        readKey = { read() },
        generateKey = { generations++; generatedKey },
        clock = { now },
        encryptedValuesExist = { probes++; encryptedValues },
        recordDirectory = dir,
    )

    private fun jvmKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun record(): File = File(directory.root, AT_REST_ABSENCE_RECORD)

    private fun persisted(): Properties = Properties().apply { record().inputStream().use { load(it) } }

    @Test
    fun `nothing encrypted generates immediately`() {
        encryptedValues = false
        val recovery = recovery()
        assertSame(generatedKey, recovery.acquire())
        assertEquals(1, generations)
    }

    @Test
    fun `encrypted absence refuses a new process plaintext write`() {
        encryptedValues = true
        val recovery = recovery()
        // A fresh process: no key was held here, yet the encrypted rows make this an outage (DATA-5).
        val crypto = KeystoreAtRestCrypto { recovery.acquire() }
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("secret") }
        assertFalse(crypto.active.value)
        assertEquals(0, generations)
    }

    @Test
    fun `twenty reads before ten minutes do not replace`() {
        encryptedValues = true
        val recovery = recovery()
        val first = now
        repeat(19) { assertThrows(AtRestUnavailableException::class.java) { recovery.acquire() } }
        now = first + 599_999 // the twentieth read: the count is met, the ten minutes are not
        assertThrows(AtRestUnavailableException::class.java) { recovery.acquire() }
        assertEquals(0, generations)
    }

    @Test
    fun `two reads hours apart do not replace`() {
        encryptedValues = true
        val recovery = recovery()
        assertThrows(AtRestUnavailableException::class.java) { recovery.acquire() }
        now += 3_600_000
        assertThrows(AtRestUnavailableException::class.java) { recovery.acquire() }
        assertEquals(0, generations)
    }

    @Test
    fun `twentieth absence spanning ten minutes replaces`() {
        encryptedValues = true
        val recovery = recovery()
        val first = now
        repeat(19) { assertThrows(AtRestUnavailableException::class.java) { recovery.acquire() } }
        assertEquals(0, generations)
        now = first + 600_000
        assertSame(generatedKey, recovery.acquire())
        assertEquals(1, generations)
        assertFalse(record().exists()) // the replacement clears the evidence

        // Old ciphertext does not authenticate under the new key: the unreadable/Remove path stands.
        val sealed = KeystoreAtRestCrypto(retryBackoffMs = 0) { originalKey }.encrypt("secret")
        val reader = KeystoreAtRestCrypto(retryBackoffMs = 0) { generatedKey }
        assertThrows(AtRestCorruptException::class.java) { reader.decrypt(sealed) }
    }

    @Test
    fun `successful read deletes evidence`() {
        encryptedValues = true
        val beforeRestart = recovery()
        repeat(3) { assertThrows(AtRestUnavailableException::class.java) { beforeRestart.acquire() } }
        assertEquals("3", persisted().getProperty("count"))

        // A fresh instance (a process restart) performs the successful read: the record on disk
        // is what drives the policy, and clearing it must survive that restart too.
        val afterRestart = recovery()
        read = { reads++; originalKey }
        probes = 0
        assertSame(originalKey, afterRestart.acquire())
        assertFalse(record().exists())
        assertEquals(0, probes) // the encrypted-values predicate is never asked on a successful read

        // A later fresh absence begins at count 1, whichever instance sees it.
        read = { reads++; null }
        val laterProcess = recovery()
        assertThrows(AtRestUnavailableException::class.java) { laterProcess.acquire() }
        assertEquals("1", persisted().getProperty("count"))
    }

    @Test
    fun `new instance continues persisted count`() {
        encryptedValues = true
        val first = now
        val beforeRestart = recovery()
        repeat(19) { assertThrows(AtRestUnavailableException::class.java) { beforeRestart.acquire() } }

        val afterRestart = recovery() // the same directory: the count spans the restart
        now = first + 600_000
        assertSame(generatedKey, afterRestart.acquire())
        assertEquals(1, generations)
    }

    @Test
    fun `damaged or regressed absence evidence starts over`() {
        encryptedValues = true
        val recovery = recovery()

        record().writeText("version=1\nfirstAbsentAtMs=not-a-number\ncount=99\n")
        assertThrows(AtRestUnavailableException::class.java) { recovery.acquire() }
        assertEquals(0, generations)
        assertEquals("1", persisted().getProperty("count"))

        // A clock that moved backwards is not evidence of a persistent absence either.
        record().writeText("version=1\nfirstAbsentAtMs=${now + 60_000}\ncount=99\n")
        assertThrows(AtRestUnavailableException::class.java) { recovery.acquire() }
        assertEquals(0, generations)
        assertEquals("1", persisted().getProperty("count"))
    }

    @Test
    fun `unpersistable evidence refuses replacement`() {
        encryptedValues = true
        val notADirectory = directory.newFile("records")
        val recovery = recovery(notADirectory)
        assertThrows(AtRestUnavailableException::class.java) { recovery.acquire() }
        assertEquals(0, generations)
    }

    @Test
    fun `a failing read is an outage, never an absence`() {
        encryptedValues = true
        read = { reads++; throw KeyStoreException("keystore2 is down") }
        val recovery = recovery()
        val first = now
        repeat(25) { i ->
            now = first + i * 27_500 // 25 reads spanning 660 000 ms
            assertThrows(AtRestUnavailableException::class.java) { recovery.acquire() }
        }
        assertEquals(0, generations)
        assertFalse(record().exists()) // an outage records nothing: it can never count toward replacement

        read = { reads++; null } // and once the alias is genuinely absent, the count starts at 1
        assertThrows(AtRestUnavailableException::class.java) { recovery.acquire() }
        assertEquals("1", persisted().getProperty("count"))
    }

    @Test
    fun `a failing read with nothing encrypted keeps the plaintext posture`() {
        encryptedValues = false
        read = { reads++; throw KeyStoreException("keystore2 is down") }
        assertNull(recovery().acquire())
        assertEquals(0, generations)
    }
}
