package dev.vaayil.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.config.WgConfigParser
import dev.vaayil.core.config.toProfile
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ProfileRepositoryTest {
    private lateinit var db: VaayilDatabase
    private lateinit var repo: ProfileRepository

    @Before fun setUp() {
        db = buildInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        repo = ProfileRepository(db.profileDao(), PlaintextAtRestCrypto)
    }

    private fun profile(name: String) = Profile(
        id = name,
        name = name,
        interfaceConfig = InterfaceConfig(
            privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            addresses = listOf("10.0.0.2/32"),
            dnsServers = listOf("1.1.1.1"),
            mtu = 1420,
            listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
            presharedKey = null,
            endpoint = "vpn.example.com:51820",
            allowedIps = listOf("0.0.0.0/0"),
            persistentKeepalive = 25,
        ),
    )

    private fun Profile.toEntityAt(timestamp: Long): ProfileEntity =
        toEntity().copy(createdAt = timestamp, updatedAt = timestamp)

    /** A plain JVM AES key stands in for the Keystore's: the same class, an injected source. */
    private fun jvmKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @After fun tearDown() { db.close() }

    @Test fun `a saved profile reads back by id`() = runTest {
        repo.save(profile("I"))
        assertEquals("I", repo.get("I")?.name)
    }

    @Test fun `export round-trips to identical config`() = runTest {
        val original = profile("I")
        val reparsed = WgConfigParser().parse(repo.export(original)).toProfile(name = "I2")
        assertEquals(original.interfaceConfig, reparsed.interfaceConfig)
        assertEquals(original.peer, reparsed.peer)
    }

    @Test fun `save upserts and observeProfiles emits`() = runTest {
        val saved = profile("I")
        repo.save(saved)
        repo.save(saved.copy(name = "Renamed"))
        assertEquals("Renamed", repo.observeProfiles().first().single().name)
    }

    @Test fun `delete removes`() = runTest {
        repo.save(profile("I"))
        repo.delete("I")
        assertEquals(0, repo.observeProfiles().first().size)
    }

    @Test
    fun `save preserves createdAt on update`() = runBlocking {
        val repo = ProfileRepository(db.profileDao(), PlaintextAtRestCrypto)
        val p = profile("p1")
        repo.save(p)
        val first = db.profileDao().getById("p1")!!
        Thread.sleep(5)
        repo.save(first.toModel().copy(name = "renamed"))
        val second = db.profileDao().getById("p1")!!
        assertEquals(first.createdAt, second.createdAt)
        assertTrue(second.updatedAt >= first.updatedAt)
    }

    @Test
    fun `observeAll breaks updatedAt ties by name`() = runBlocking {
        val repo = ProfileRepository(db.profileDao(), PlaintextAtRestCrypto)
        val fixed = 1000L
        db.profileDao().upsert(profile("b").toEntityAt(fixed))
        db.profileDao().upsert(profile("a").toEntityAt(fixed))
        val names = repo.observeProfiles().first().map { it.name }
        assertEquals(listOf("a", "b"), names)
    }

    @Test
    fun `save refuses an invalid profile and stores nothing`() = runBlocking {
        val invalid = profile("bad").copy(name = "")
        val e = assertThrows(InvalidProfileException::class.java) { runBlocking { repo.save(invalid) } }
        assertTrue(e.errors.first().startsWith("Profile name"))
        assertNull(repo.get(invalid.id))
    }

    @Test
    fun `a key lost after it was held reports refused writes`() = runBlocking {
        // R6: once a key was held, a lost key refuses writes instead of storing plaintext (F13),
        // so the Profiles banner must call profiles read-only, not "stored unencrypted".
        val crypto = KeystoreAtRestCrypto(
            retryBackoffMs = 30_000,
            clock = { 1_000L },
            sleep = {},
            newCipher = { throw java.security.ProviderException("pruned") },
        ) { jvmKey() }
        val repo = ProfileRepository(db.profileDao(), crypto)
        crypto.warm() // a key is held
        val refused = runCatching { repo.save(profile("p1")) }.exceptionOrNull()
        assertTrue(refused is AtRestUnavailableException, "the save was not refused: $refused")
        val health = repo.observeHealth().first()
        assertFalse(health.atRestActive)
        assertTrue(health.writesRefused)
    }

    @Test
    fun `a keystore that never served a key reports plaintext storage, not refused writes`() = runBlocking {
        // S-3: no key was ever held, so a write is stored as plaintext and nothing is refused.
        val crypto = KeystoreAtRestCrypto(retryBackoffMs = 30_000, clock = { 1_000L }) { null }
        val repo = ProfileRepository(db.profileDao(), crypto)
        crypto.warm()
        val health = repo.observeHealth().first()
        assertFalse(health.atRestActive)
        assertFalse(health.writesRefused)
    }
}
