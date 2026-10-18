package dev.vaayil.core.data

import app.cash.turbine.test
import dev.vaayil.core.model.InterfaceConfig
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.runner.RunWith
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * At-rest crypto contract tests: keys are ciphertext in the stored entity, plaintext to
 * every reader above the repository, and the startup migration re-encrypts legacy
 * plaintext rows without churning timestamps. Robolectric has no AndroidKeyStore, so the
 * Keystore-backed implementation is exercised through a deterministic prefix fake.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class ProfileRepositoryAtRestTest {
    private val context: android.content.Context
        get() = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    /** Deterministic stand-in for KeystoreAtRestCrypto: same marker/shape, no hardware. */
    private class PrefixAtRestCrypto : AtRestCrypto {
        override val active: StateFlow<Boolean> = MutableStateFlow(true)
        override fun encrypt(plain: String): String =
            AtRestCrypto.MARKER + java.util.Base64.getEncoder().encodeToString("E$plain".toByteArray())

        override fun decrypt(stored: String): String =
            if (!stored.startsWith(AtRestCrypto.MARKER)) stored // legacy plaintext passes through
            else java.util.Base64.getDecoder().decode(stored.removePrefix(AtRestCrypto.MARKER))
                .toString(Charsets.UTF_8).removePrefix("E")
    }

    /**
     * Decrypts every marked value except one poisoned secret, which fails the way a value
     * sealed under a replaced key does (GCM tag mismatch). [AtRestUnavailableException] would
     * be the wrong stand-in: it means the key is absent, not wrong (S-4).
     */
    private class BrokenRowCrypto(private val poison: String) : AtRestCrypto {
        override val active: StateFlow<Boolean> = MutableStateFlow(true)
        override fun encrypt(plain: String) = "enc1:$plain"
        override fun decrypt(stored: String): String {
            val plain = stored.removePrefix("enc1:")
            if (plain == poison) throw AtRestCorruptException(javax.crypto.AEADBadTagException("sealed under another key"))
            return plain
        }
    }

    /** No key held: every marked value reports "key unavailable" — a posture, not corruption. */
    private class UnavailableKeyCrypto : AtRestCrypto {
        override val active: StateFlow<Boolean> = MutableStateFlow(false)
        override fun encrypt(plain: String) = plain
        override fun decrypt(stored: String): String =
            if (stored.startsWith(AtRestCrypto.MARKER)) throw AtRestUnavailableException() else stored
    }

    private fun profile(id: String = "p1", privateKey: String = KEY44) = Profile(
        id, "Home",
        InterfaceConfig(privateKey, listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        PeerConfig(KEY44, PSK44, "vpn.example.com:51820", listOf("0.0.0.0/0"), 25),
    )

    /** A plain JVM AES key stands in for the Keystore's: the same class, an injected source. */
    private fun jvmKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `saved rows hold ciphertext for both secrets`() = runTest {
        val db = buildInMemoryDatabase(context)
        val repo = ProfileRepository(db.profileDao(), PrefixAtRestCrypto())
        repo.save(profile())
        val raw = db.profileDao().getById("p1")!!
        assertTrue(raw.privateKey.startsWith(AtRestCrypto.MARKER))
        assertTrue(raw.presharedKey!!.startsWith(AtRestCrypto.MARKER))
        db.close()
    }

    @Test
    fun `reads return plaintext above the repository`() = runTest {
        val db = buildInMemoryDatabase(context)
        val repo = ProfileRepository(db.profileDao(), PrefixAtRestCrypto())
        repo.save(profile())
        assertEquals(KEY44, repo.get("p1")!!.interfaceConfig.privateKey)
        assertEquals(PSK44, repo.get("p1")!!.peer.presharedKey)
        assertEquals(KEY44, repo.observeProfiles().first().single().interfaceConfig.privateKey)
        db.close()
    }

    @Test
    fun `the profile snapshot decodes off the collecting thread`() = runTest {
        val db = buildInMemoryDatabase(context)
        val delegate = PrefixAtRestCrypto()
        val decryptThreads = mutableListOf<Thread>()
        val crypto = object : AtRestCrypto by delegate {
            override fun decrypt(stored: String): String {
                decryptThreads += Thread.currentThread()
                return delegate.decrypt(stored)
            }
        }
        val repo = ProfileRepository(db.profileDao(), crypto)
        repo.save(profile())
        val collector = Thread.currentThread()
        assertEquals(listOf("p1"), repo.observeProfiles().first().map { it.id })
        assertTrue(decryptThreads.isNotEmpty())
        assertTrue(decryptThreads.none { it === collector }, "the snapshot decoded on the collecting thread")
        db.close()
    }

    @Test
    fun `an empty database reports the at-rest posture once the crypto is warmed`() = runTest {
        val db = buildInMemoryDatabase(context)
        val crypto = KeystoreAtRestCrypto { jvmKey() }
        val repo = ProfileRepository(db.profileDao(), crypto)
        // A fresh install has no row to encrypt, so only the startup warm-up can flip the
        // posture: without it the Profiles tab calls the Keystore unavailable (S-3).
        crypto.warm()
        val health = repo.observeHealth().first()
        assertTrue(health.atRestActive)
        assertEquals(emptySet<String>(), health.unreadableIds)
        db.close()
    }

    @Test
    fun `rows that only lack the key are neither unreadable nor deletable`() = runBlocking {
        val db = buildInMemoryDatabase(context)
        val repo = ProfileRepository(db.profileDao(), UnavailableKeyCrypto())
        // Two rows sealed under a key this process does not hold yet (Keystore not ready):
        // the at-rest banner covers this, they are not evidence of a replaced key (S-4).
        listOf("p1", "p2").forEach { id ->
            db.profileDao().upsert(
                ProfileEntity(
                    id = id, name = "Home", privateKey = AtRestCrypto.MARKER + KEY44,
                    addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
                    mtu = 1420, listenPort = null, peerPublicKey = KEY44, presharedKey = null,
                    endpoint = "vpn.example.com:51820", allowedIps = listOf("0.0.0.0/0"),
                    keepalive = 25, createdAt = 111L, updatedAt = 222L,
                ),
            )
        }
        val health = repo.observeHealth().first()
        assertFalse(health.atRestActive)
        assertEquals(emptySet<String>(), health.unreadableIds)
        repo.deleteUnreadable()
        assertEquals(2, db.profileDao().observeAll().first().size)
        db.close()
    }

    @Test
    fun `reEncryptAtRest migrates legacy plaintext rows`() = runTest {
        val db = buildInMemoryDatabase(context)
        val repo = ProfileRepository(db.profileDao(), PrefixAtRestCrypto())
        // Seed a legacy row by writing a raw entity with plaintext secrets (pre-crypto era).
        db.profileDao().upsert(
            ProfileEntity(
                id = "p1", name = "Home", privateKey = KEY44,
                addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
                mtu = 1420, listenPort = null, peerPublicKey = KEY44, presharedKey = PSK44,
                endpoint = "vpn.example.com:51820", allowedIps = listOf("0.0.0.0/0"),
                keepalive = 25, createdAt = 111L, updatedAt = 222L,
            ),
        )
        repo.reEncryptAtRest()
        val raw = db.profileDao().getById("p1")!!
        assertTrue(raw.privateKey.startsWith(AtRestCrypto.MARKER))
        assertTrue(raw.presharedKey!!.startsWith(AtRestCrypto.MARKER))
        // Timestamps survive the migration untouched.
        assertEquals(111L, raw.createdAt)
        assertEquals(222L, raw.updatedAt)
        // And the model still reads plaintext.
        assertEquals(KEY44, repo.get("p1")!!.interfaceConfig.privateKey)
        db.close()
    }

    @Test
    fun `reEncryptAtRest is idempotent`() = runTest {
        val db = buildInMemoryDatabase(context)
        val repo = ProfileRepository(db.profileDao(), PrefixAtRestCrypto())
        repo.save(profile())
        repo.reEncryptAtRest()
        repo.reEncryptAtRest()
        assertEquals(KEY44, repo.get("p1")!!.interfaceConfig.privateKey)
        db.close()
    }

    @Test
    fun `an undecryptable row is excluded, reported and deletable`() = runBlocking {
        val db = buildInMemoryDatabase(context)
        val goodKey = "A".repeat(43) + "="
        val badKey = "B".repeat(42) + "Q="
        val repo = ProfileRepository(db.profileDao(), BrokenRowCrypto(badKey))
        repo.save(profile(id = "good", privateKey = goodKey))
        repo.save(profile(id = "bad", privateKey = badKey))
        assertEquals(listOf("good"), repo.observeProfiles().first().map { it.id })
        val health = repo.observeHealth().first()
        assertTrue(health.atRestActive)
        assertEquals(setOf("bad"), health.unreadableIds)
        assertNull(repo.get("bad"))
        repo.deleteUnreadable()
        assertEquals(emptySet<String>(), repo.observeHealth().first().unreadableIds)
        assertEquals(1, db.profileDao().observeAll().first().size)
        db.close()
    }

    @Test
    fun `reEncryptAtRest skips rows it cannot read instead of throwing`() = runBlocking {
        val db = buildInMemoryDatabase(context)
        val repo = ProfileRepository(db.profileDao(), BrokenRowCrypto(PSK44))
        // A legacy row still needing migration (plaintext private key) whose preshared key was
        // sealed under a key this process no longer has: the startup pass must not throw.
        db.profileDao().upsert(
            ProfileEntity(
                id = "p1", name = "Home", privateKey = KEY44,
                addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
                mtu = 1420, listenPort = null, peerPublicKey = KEY44,
                presharedKey = AtRestCrypto.MARKER + PSK44,
                endpoint = "vpn.example.com:51820", allowedIps = listOf("0.0.0.0/0"),
                keepalive = 25, createdAt = 111L, updatedAt = 222L,
            ),
        )
        repo.reEncryptAtRest() // must not throw
        assertEquals(1, db.profileDao().observeAll().first().size)
        db.close()
    }

    /** Key absent at first, delivered later: the S-3 "Keystore not ready at start" posture. */
    private class LateKeyCrypto : AtRestCrypto {
        private val _active = MutableStateFlow(false)
        override val active: StateFlow<Boolean> = _active
        fun holdKey() { _active.value = true }
        override fun encrypt(plain: String) = AtRestCrypto.MARKER + plain
        override fun decrypt(stored: String): String {
            if (!stored.startsWith(AtRestCrypto.MARKER)) return stored
            if (!_active.value) throw AtRestUnavailableException()
            return stored.removePrefix(AtRestCrypto.MARKER)
        }
    }

    /** Every marked value is a newer format this build cannot read (a downgrade). */
    private class NewerFormatCrypto : AtRestCrypto {
        override val active: StateFlow<Boolean> = MutableStateFlow(true)
        override fun encrypt(plain: String) = "enc2:$plain"
        override fun decrypt(stored: String): String =
            if (stored.startsWith("enc2:")) throw AtRestFormatException(2) else stored
    }

    @Test
    fun `rows hidden by an absent key reappear when the key arrives`() = runTest {
        val db = buildInMemoryDatabase(context)
        val crypto = LateKeyCrypto()
        val repo = ProfileRepository(db.profileDao(), crypto)
        repo.save(profile())
        repo.observeProfiles().test {
            assertEquals(emptyList(), awaitItem())
            crypto.holdKey()
            assertEquals(listOf("p1"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        db.close()
    }

    @Test
    fun `a newer at-rest format is neither unreadable nor deletable`() = runBlocking {
        val db = buildInMemoryDatabase(context)
        val repo = ProfileRepository(db.profileDao(), NewerFormatCrypto())
        repo.save(profile())
        val health = repo.observeHealth().first()
        assertTrue(health.atRestActive)
        assertEquals(emptySet<String>(), health.unreadableIds)
        repo.deleteUnreadable()
        assertEquals(1, db.profileDao().observeAll().first().size)
        db.close()
    }

    @Test
    fun `storedIds counts rows the key cannot read`() = runBlocking {
        val db = buildInMemoryDatabase(context)
        val repo = ProfileRepository(db.profileDao(), UnavailableKeyCrypto())
        db.profileDao().upsert(
            ProfileEntity(
                id = "hidden", name = "Home", privateKey = AtRestCrypto.MARKER + KEY44,
                addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
                mtu = 1420, listenPort = null, peerPublicKey = KEY44, presharedKey = null,
                endpoint = "vpn.example.com:51820", allowedIps = listOf("0.0.0.0/0"),
                keepalive = 25, createdAt = 111L, updatedAt = 222L,
            ),
        )
        assertEquals(emptyList(), repo.observeProfiles().first())
        assertEquals(setOf("hidden"), repo.storedIds())
        db.close()
    }

    @Test
    fun `saveAll refuses the whole batch before writing anything`() = runBlocking {
        val db = buildInMemoryDatabase(context)
        val repo = ProfileRepository(db.profileDao(), PrefixAtRestCrypto())
        val batch = listOf(profile(id = "ok"), profile(id = "bad").copy(name = ""))
        assertTrue(runCatching { repo.saveAll(batch) }.exceptionOrNull() is InvalidProfileException)
        assertEquals(emptySet<String>(), repo.storedIds())
        repo.saveAll(listOf(profile(id = "a"), profile(id = "b")))
        assertEquals(setOf("a", "b"), repo.storedIds())
        db.close()
    }

    companion object {
        private const val KEY44 = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0="
        private const val PSK44 = "cHJlllllllllllllllllllllllllllllllllllllll0="
    }

    /**
     * Fails the first [failTimes] encrypt calls (a transient Keystore/Provider failure), then
     * healthy. warm() is counted but does not change the schedule — the tests decide what a
     * warm means by how many failures remain.
     */
    private class TransientFailureCrypto(private var failTimes: Int) : AtRestCrypto {
        var warmed = 0
            private set
        override val active: StateFlow<Boolean> = MutableStateFlow(true)
        override fun warm() {
            warmed++
        }
        override fun encrypt(plain: String): String {
            if (failTimes > 0) {
                failTimes--
                throw AtRestUnavailableException()
            }
            return AtRestCrypto.MARKER + java.util.Base64.getEncoder().encodeToString("E$plain".toByteArray())
        }
        override fun decrypt(stored: String): String =
            if (!stored.startsWith(AtRestCrypto.MARKER)) stored
            else java.util.Base64.getDecoder().decode(stored.removePrefix(AtRestCrypto.MARKER))
                .toString(Charsets.UTF_8).removePrefix("E")
    }

    @Test
    fun `a save that hits a transient keystore failure is retried once after warm and succeeds`() = runTest {
        val db = buildInMemoryDatabase(context)
        val crypto = TransientFailureCrypto(failTimes = 1) // one failure: the first seal attempt
        val repo = ProfileRepository(db.profileDao(), crypto)
        repo.saveAll(listOf(profile(id = "p1")))
        assertEquals(1, crypto.warmed)
        val raw = db.profileDao().getById("p1")!!
        assertTrue(raw.privateKey.startsWith(AtRestCrypto.MARKER))
        db.close()
    }

    @Test
    fun `a save that keeps failing reports unavailable and writes nothing`() = runTest {
        val db = buildInMemoryDatabase(context)
        val crypto = TransientFailureCrypto(failTimes = 5) // both attempts fail
        val repo = ProfileRepository(db.profileDao(), crypto)
        val thrown = runCatching { repo.saveAll(listOf(profile(id = "p1"))) }.exceptionOrNull()
        assertTrue(thrown is AtRestUnavailableException, "expected unavailable, got $thrown")
        assertEquals(1, crypto.warmed) // warm fired exactly once between the two attempts
        assertEquals(0, db.profileDao().observeAll().first().size)
        db.close()
    }

    @Test
    fun `stored count counts rows that are temporarily unreadable`() = runTest {
        val db = buildInMemoryDatabase(context)
        val crypto = TransientFailureCrypto(failTimes = 2) // exactly the first save's two attempts
        val repo = ProfileRepository(db.profileDao(), crypto)
        runCatching { repo.saveAll(listOf(profile(id = "p1"))) } // fails; nothing written
        val row = db.profileDao().getById("p1")
        assertNull(row) // the failed save wrote nothing
        crypto.warm() // the key comes back (the 30 s backoff would expire in the field)
        repo.saveAll(listOf(profile(id = "p1"))) // retry lands
        assertEquals(1, repo.observeStoredCount().first()) // stored, even if reads degrade again
        db.close()
    }

    @Test
    fun `no repository call runs the crypto on its caller's thread`() = runTest {
        val db = buildInMemoryDatabase(context)
        val delegate = PrefixAtRestCrypto()
        val cryptoThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val crypto = object : AtRestCrypto by delegate {
            override fun encrypt(plain: String): String { cryptoThreads += Thread.currentThread(); return delegate.encrypt(plain) }
            override fun decrypt(stored: String): String { cryptoThreads += Thread.currentThread(); return delegate.decrypt(stored) }
        }
        val repo = ProfileRepository(db.profileDao(), crypto)
        val caller = Thread.currentThread()
        fun assertOffCaller(call: String) {
            assertTrue(cryptoThreads.isNotEmpty(), "$call never reached the crypto")
            assertTrue(cryptoThreads.none { it === caller }, "$call ran the crypto on the caller's thread")
            cryptoThreads.clear()
        }
        repo.save(profile())
        assertOffCaller("save")
        assertEquals(KEY44, repo.get("p1")!!.interfaceConfig.privateKey)
        assertOffCaller("get")
        repo.deleteUnreadable()
        assertOffCaller("deleteUnreadable")
        db.profileDao().upsert( // a legacy plaintext row for the startup migration
            ProfileEntity(
                id = "p2", name = "Home", privateKey = KEY44,
                addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
                mtu = 1420, listenPort = null, peerPublicKey = KEY44, presharedKey = PSK44,
                endpoint = "vpn.example.com:51820", allowedIps = listOf("0.0.0.0/0"),
                keepalive = 25, createdAt = 111L, updatedAt = 222L,
            ),
        )
        repo.reEncryptAtRest()
        assertOffCaller("reEncryptAtRest")
        db.close()
    }

    @Test
    fun `a read during the key's retry backoff forces one re-acquisition instead of refusing the profile`() = runBlocking {
        val db = buildInMemoryDatabase(context)
        val key = jvmKey()
        ProfileRepository(db.profileDao(), KeystoreAtRestCrypto { key }).save(profile())
        var sourceCalls = 0
        // Boot during an outage: the first acquisition fails and arms a 30 s backoff on a frozen clock.
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 30_000, clock = { 1_000L }) { sourceCalls++; if (sourceCalls == 1) null else key }
        crypto.warm()
        assertFalse(crypto.active.value)
        val repo = ProfileRepository(db.profileDao(), crypto)
        assertEquals(KEY44, repo.get("p1")?.interfaceConfig?.privateKey) // the connect path is not refused
        assertEquals(2, sourceCalls)
        db.close()
    }

    @Test
    fun `a read the key still cannot serve returns null after one forced attempt`() = runBlocking {
        val db = buildInMemoryDatabase(context)
        ProfileRepository(db.profileDao(), KeystoreAtRestCrypto { jvmKey() }).save(profile())
        var sourceCalls = 0
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 30_000, clock = { 1_000L }) { sourceCalls++; null }
        crypto.warm()
        val repo = ProfileRepository(db.profileDao(), crypto)
        assertNull(repo.get("p1"))
        assertEquals(2, sourceCalls) // the startup warm plus exactly one forced re-acquisition: no loop
        db.close()
    }
}
