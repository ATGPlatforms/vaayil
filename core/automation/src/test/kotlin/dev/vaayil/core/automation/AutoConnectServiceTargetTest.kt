package dev.vaayil.core.automation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.AtRestCrypto
import dev.vaayil.core.data.AtRestUnavailableException
import dev.vaayil.core.data.PlaintextAtRestCrypto
import dev.vaayil.core.data.ProfileEntity
import dev.vaayil.core.data.ProfileRepository
import dev.vaayil.core.data.VaayilDatabase
import dev.vaayil.core.data.buildInMemoryDatabase
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B3: the automation target order — ACTIVE, then last-connected, each only while a stored row
 * still has that id, else the newest readable profile: the dashboard disc's order (F23) minus
 * its on-screen pick, so automation connects what the disc shows. A pointer whose profile is
 * gone is cleared — ACTIVE is fail-closed and last-connected survives disconnects, so without
 * this a deleted (or re-imported) profile left every guard, boot and hintless Tasker connect
 * silently refused — while a row that merely cannot be decrypted right now keeps its pointer
 * and stays the target: connectById re-acquires the key itself (F13).
 *
 * The tests drive [resolveAutomationTarget] against an in-memory repository and a fake
 * [AtRestCrypto], so rows can be made readable or locked without replacing DataGraph's
 * process-wide singletons. ACTIVE is a process-wide DataStore, so every test starts clear.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AutoConnectServiceTargetTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: VaayilDatabase
    private lateinit var store: ActiveProfileStore

    /** No key held: every marked secret reports "unavailable" — a posture, not corruption. */
    private class UnavailableKeyCrypto : AtRestCrypto {
        override val active: StateFlow<Boolean> = MutableStateFlow(false)
        override fun encrypt(plain: String) = plain
        override fun decrypt(stored: String): String =
            if (stored.startsWith(AtRestCrypto.MARKER)) throw AtRestUnavailableException() else stored
    }

    @Before
    fun setUp() = runBlocking {
        db = buildInMemoryDatabase(context)
        store = ActiveProfileStore(context)
        store.setActive(null)
        store.setLastConnected(null)
    }

    @After
    fun tearDown() = runBlocking {
        store.setActive(null)
        store.setLastConnected(null)
        db.close()
    }

    private fun repository(crypto: AtRestCrypto = PlaintextAtRestCrypto) = ProfileRepository(db.profileDao(), crypto)

    /** A stored row; a marked [privateKey] is unreadable under [UnavailableKeyCrypto]. */
    private suspend fun seed(id: String, updatedAt: Long, privateKey: String = "k") {
        db.profileDao().upsert(
            ProfileEntity(
                id = id, name = id, privateKey = privateKey,
                addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
                mtu = null, listenPort = null, peerPublicKey = "K", presharedKey = null,
                endpoint = "vpn.example.com:51820", allowedIps = listOf("0.0.0.0/0"), keepalive = 25,
                createdAt = 1L, updatedAt = updatedAt,
            ),
        )
    }

    private val lockedKey = AtRestCrypto.MARKER + "sealed-under-a-replaced-key"

    @Test
    fun `stored active wins`() = runBlocking {
        seed("active", updatedAt = 1)
        seed("last", updatedAt = 2)
        seed("new", updatedAt = 3)
        store.setActive("active")
        store.setLastConnected("last")

        assertEquals("active", resolveAutomationTarget(store, repository()))
    }

    @Test
    fun `stored last connected is second`() = runBlocking {
        seed("last", updatedAt = 1)
        seed("new", updatedAt = 2)
        store.setActive(null) // every disconnect clears ACTIVE (fail-closed)
        store.setLastConnected("last")

        assertEquals("last", resolveAutomationTarget(store, repository()))
    }

    @Test
    fun `missing pointers fall back to newest readable`() = runBlocking {
        seed("old", updatedAt = 1)
        seed("new", updatedAt = 2)
        store.setActive("deleted-active")
        store.setLastConnected("deleted-last")

        assertEquals("new", resolveAutomationTarget(store, repository()))
        assertNull(store.activeId.first())
        assertNull(store.lastConnectedId.first())
    }

    @Test
    fun `newest unreadable row is skipped without a pointer`() = runBlocking {
        seed("older-readable", updatedAt = 1)
        seed("newest-locked", updatedAt = 2, privateKey = lockedKey)

        assertEquals("older-readable", resolveAutomationTarget(store, repository(UnavailableKeyCrypto())))
    }

    @Test
    fun `no stored target is a no-op`() = runBlocking {
        assertNull(resolveAutomationTarget(store, repository()))
    }

    @Test
    fun `undecryptable active pointer remains the target`() = runBlocking {
        seed("locked", updatedAt = 1, privateKey = lockedKey)
        seed("readable", updatedAt = 2)
        store.setActive("locked")

        assertEquals("locked", resolveAutomationTarget(store, repository(UnavailableKeyCrypto())))
        assertEquals("locked", store.activeId.first())
    }

    @Test
    fun `undecryptable last-connected pointer remains the target`() = runBlocking {
        seed("locked", updatedAt = 1, privateKey = lockedKey)
        seed("readable", updatedAt = 2)
        store.setActive(null)
        store.setLastConnected("locked")

        assertEquals("locked", resolveAutomationTarget(store, repository(UnavailableKeyCrypto())))
        assertEquals("locked", store.lastConnectedId.first())
    }

    @Test
    fun `unreadable active and deleted last reconcile independently`() = runBlocking {
        seed("locked", updatedAt = 1, privateKey = lockedKey)
        store.setActive("locked")
        store.setLastConnected("deleted")

        assertEquals("locked", resolveAutomationTarget(store, repository(UnavailableKeyCrypto())))
        assertEquals("locked", store.activeId.first())
        assertNull(store.lastConnectedId.first())
    }

    @Test
    fun `resolved target is carried as EXTRA_PROFILE_ID on the connect intent`() {
        val intent = AutoConnectService.connectIntent(context, "p9")
        assertEquals(VpnTunnelService.ACTION_CONNECT, intent.action)
        assertEquals("p9", intent.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `connect intent without a target keeps the active-profile fallback`() {
        val intent = AutoConnectService.connectIntent(context, null)
        assertEquals(VpnTunnelService.ACTION_CONNECT, intent.action)
        assertFalse(intent.hasExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }
}
