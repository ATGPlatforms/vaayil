package dev.vaayil.core.automation

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.AutomationRulesStore
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.data.ProfileEntity
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.isLive
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.StartOutcome
import dev.vaayil.core.tunnel.wg.TunnelNotifications
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVpnService

/**
 * Spec §7 Phase 2: the Tasker entry point resolves CONNECT hints (exact id →
 * exact name → no-op) and fires DISCONNECT directly; TOGGLE maps the live
 * registry phase onto the same two paths. Every transition is a VpnTunnelService
 * intent — never the engine directly. Since T-3/A-1 the receiver is token-gated
 * and opt-in, so every test opts in and carries the token (except the ones
 * asserting the gate itself).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AutomationReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A stored row: explicit timestamps keep the resolver's updatedAt order off the clock (B3). */
    private fun row(id: String, name: String, updatedAt: Long = 1L) = ProfileEntity(
        id = id, name = name, privateKey = "k",
        addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
        mtu = null, listenPort = null, peerPublicKey = "K", presharedKey = null,
        endpoint = "vpn.example.com:51820", allowedIps = listOf("0.0.0.0/0"), keepalive = 25,
        createdAt = 1L, updatedAt = updatedAt,
    )

    // The receiver resolves through DataGraph's process-wide repository, so rows go into the
    // graph's own database. The graph is reset per test: Robolectric hands every test method a
    // fresh Application, so a cached database from the previous test is stale.
    private fun seedProfile(id: String, name: String, updatedAt: Long = 1L) = runBlocking {
        DataGraph.database(context).profileDao().upsert(row(id, name, updatedAt))
    }

    private fun seedActive(activeId: String?) = runBlocking {
        ActiveProfileStore(context).setActive(activeId)
    }

    private fun notificationManager() = shadowOf(context.getSystemService(NotificationManager::class.java))

    /** Rules live in a process-wide DataStore: reset per test so order cannot leak opt-ins. */
    @Before
    fun resetRules() = runBlocking { AutomationRulesStore(context).mutate { AutoConnectRules() } }

    @Before
    fun freshGraph() = DataGraph.reset()

    /**
     * The profile pointers live in that same process-wide DataStore and the receiver now falls
     * back to last-connected, so both must start clear: otherwise a method that seeds the
     * last-connected pointer decides whether `connect with nothing resolvable` passes.
     */
    @Before
    fun resetProfilePointers(): Unit = runBlocking {
        ActiveProfileStore(context).apply {
            setActive(null)
            setLastConnected(null)
        }
    }

    // Robolectric's ShadowVpnService reports "needs consent" by default (an Intent), so every test
    // that expects a CONNECT start grants it explicitly; the consent tests override this per test.
    @Before
    fun grantVpnConsent() = ShadowVpnService.setPrepareResult(null)

    /** The registry is process-wide: a live phase set by one test must not decide the next one. */
    @After
    fun resetRegistry() {
        ConnectionRegistry.status.value = TunnelStatus.idle
    }

    private val token = "test-token"

    private fun enableExternalControl() = runBlocking {
        AutomationRulesStore(context).mutate { it.copy(externalControlEnabled = true, externalControlToken = token) }
    }

    private fun Intent.withToken(): Intent = putExtra(AutomationReceiver.EXTRA_TOKEN, token)

    private fun connectIntent(profileHint: String? = null): Intent =
        Intent(AutomationReceiver.ACTION_CONNECT).apply {
            profileHint?.let { putExtra(AutomationReceiver.EXTRA_PROFILE, it) }
        }

    // The receiver resolves behind goAsync on Dispatchers.IO, so the shadow's
    // started-service queue is filled asynchronously: poll instead of asserting inline.
    private fun awaitNextService(timeoutMs: Long = 5_000): Intent? {
        val app = shadowOf(context as Application)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            app.nextStartedService?.let { return it }
            Thread.sleep(20)
        }
        return app.nextStartedService
    }

    @Test
    fun `connect with matching profile name fires tunnel connect with its id`() {
        enableExternalControl()
        seedProfile("p1", "Home")
        seedActive(null)
        AutomationReceiver().onReceive(context, connectIntent("Home").withToken())
        val started = awaitNextService()!!
        assertEquals(VpnTunnelService::class.java.name, started.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, started.action)
        assertEquals("p1", started.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `connect without VPN consent starts no tunnel and posts the consent notification`() {
        enableExternalControl()
        seedProfile("p1", "Home")
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(Intent())
        AutomationReceiver().onReceive(context, connectIntent("Home").withToken())
        assertNull(awaitNextService(timeoutMs = 1_000))
        assertNotNull(notificationManager().getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `an unauthenticated connect posts no failure notification and starts nothing`() {
        enableExternalControl()
        seedProfile("p1", "Home")
        seedActive("p1")
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(Intent()) // no consent either: nothing may even try
        AutomationReceiver().onReceive(context, connectIntent("Home"))
        AutomationReceiver().onReceive(context, connectIntent("Home").putExtra(AutomationReceiver.EXTRA_TOKEN, "wrong"))
        assertNull(awaitNextService(timeoutMs = 1_000))
        assertNull(notificationManager().getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `an unmatched profile hint is a no-op instead of connecting the active profile`() {
        enableExternalControl()
        seedProfile("p1", "Home")
        seedActive("p1")
        AutomationReceiver().onReceive(context, connectIntent("Sweden").withToken())
        assertNull(awaitNextService(timeoutMs = 1_000))
    }

    @Test
    fun `connect with nothing resolvable does not start the service`() {
        enableExternalControl()
        seedActive(null)
        AutomationReceiver().onReceive(context, connectIntent().withToken())
        assertNull(awaitNextService(timeoutMs = 1_000))
    }

    @Test
    fun `disconnect fires disconnect intent`() {
        enableExternalControl()
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        AutomationReceiver().onReceive(context, Intent(AutomationReceiver.ACTION_DISCONNECT).withToken())
        val started = awaitNextService()!!
        assertEquals(VpnTunnelService::class.java.name, started.component?.className)
        assertEquals(VpnTunnelService.ACTION_DISCONNECT, started.action)
        assertFalse(started.hasExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `toggle connected state disconnects`() {
        enableExternalControl()
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        AutomationReceiver().onReceive(context, Intent(AutomationReceiver.ACTION_TOGGLE).withToken())
        val started = awaitNextService()!!
        assertEquals(VpnTunnelService.ACTION_DISCONNECT, started.action)
    }

    @Test
    fun `toggle disconnected state connects active`() {
        enableExternalControl()
        ConnectionRegistry.status.value = TunnelStatus.idle
        seedActive("p9")
        seedProfile("p9", "Nine")
        AutomationReceiver().onReceive(context, Intent(AutomationReceiver.ACTION_TOGGLE).withToken())
        val started = awaitNextService()!!
        assertEquals(VpnTunnelService.ACTION_CONNECT, started.action)
        assertEquals("p9", started.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `disconnect starts only for live phases`() {
        enableExternalControl()
        ShadowVpnService.setPrepareResult(Intent()) // no consent: a live disconnect must not care
        for (phase in ConnectionPhase.entries) {
            ConnectionRegistry.status.value = TunnelStatus(phase, "p1", null)
            shadowOf(context as Application).clearStartedServices()
            AutomationReceiver().onReceive(context, Intent(AutomationReceiver.ACTION_DISCONNECT).withToken())
            val started = awaitNextService(timeoutMs = 1_000)
            if (phase.isLive) {
                assertEquals("phase $phase", VpnTunnelService.ACTION_DISCONNECT, started?.action)
            } else {
                assertNull("phase $phase started a tunnel command", started)
            }
            assertNull("phase $phase posted the consent notification", notificationManager().getNotification(TunnelNotifications.NOTIFICATION_ID))
        }
    }

    @Test
    fun `toggle maps the live phase onto connect or disconnect`() {
        enableExternalControl()
        seedProfile("p1", "Home")
        seedActive("p1")
        for (phase in ConnectionPhase.entries) {
            ConnectionRegistry.status.value = TunnelStatus(phase, "p1", null)
            shadowOf(context as Application).clearStartedServices()
            AutomationReceiver().onReceive(context, Intent(AutomationReceiver.ACTION_TOGGLE).withToken())
            val started = awaitNextService()
            val expected = if (phase.isLive) VpnTunnelService.ACTION_DISCONNECT else VpnTunnelService.ACTION_CONNECT
            assertEquals("phase $phase", expected, started?.action)
        }
    }

    @Test
    fun `a disconnected toggle without VPN consent starts no tunnel and posts the consent notification`() {
        enableExternalControl()
        seedProfile("p1", "Home")
        seedActive("p1")
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(Intent())
        ConnectionRegistry.status.value = TunnelStatus.idle
        AutomationReceiver().onReceive(context, Intent(AutomationReceiver.ACTION_TOGGLE).withToken())
        assertNull(awaitNextService(timeoutMs = 1_000))
        assertNotNull(notificationManager().getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `broadcast without a token is ignored`() {
        enableExternalControl(); seedProfile("p1", "Home"); seedActive("p1")
        AutomationReceiver().onReceive(context, Intent(AutomationReceiver.ACTION_DISCONNECT))
        assertNull(awaitNextService(timeoutMs = 1_000))
    }

    @Test
    fun `broadcast with a wrong token is ignored`() {
        enableExternalControl(); seedProfile("p1", "Home"); seedActive("p1")
        AutomationReceiver().onReceive(
            context,
            Intent(AutomationReceiver.ACTION_DISCONNECT).putExtra(AutomationReceiver.EXTRA_TOKEN, "nope"),
        )
        assertNull(awaitNextService(timeoutMs = 1_000))
    }

    @Test
    fun `broadcast is ignored while external control is off`() {
        seedProfile("p1", "Home"); seedActive("p1")
        AutomationReceiver().onReceive(context, connectIntent().withToken())
        assertNull(awaitNextService(timeoutMs = 1_000))
    }

    @Test
    fun `receiver ships disabled in the manifest`() {
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, AutomationReceiver::class.java),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertFalse(info.enabled)
    }

    @Test
    fun `toggle after a disconnect connects the last connected profile`() {
        enableExternalControl()
        ConnectionRegistry.status.value = TunnelStatus.idle
        seedActive(null) // every disconnect clears ACTIVE (fail-closed)
        seedProfile("p9", "Nine")
        runBlocking { ActiveProfileStore(context).setLastConnected("p9") }
        AutomationReceiver().onReceive(context, Intent(AutomationReceiver.ACTION_TOGGLE).withToken())
        val started = awaitNextService()!!
        assertEquals(VpnTunnelService.ACTION_CONNECT, started.action)
        assertEquals("p9", started.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `connect without a hint falls back to the last connected profile`() {
        enableExternalControl()
        seedActive(null)
        seedProfile("p9", "Nine")
        runBlocking { ActiveProfileStore(context).setLastConnected("p9") }
        AutomationReceiver().onReceive(context, connectIntent().withToken())
        assertEquals("p9", awaitNextService()!!.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `connect without a hint falls back to the newest readable profile`() {
        enableExternalControl()
        seedActive(null)
        seedProfile("old", "Old", updatedAt = 1)
        seedProfile("new", "New", updatedAt = 2)
        AutomationReceiver().onReceive(context, connectIntent().withToken())
        assertEquals("new", awaitNextService()!!.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    // ----------------------------------------------------- J3 crash-resume fixtures
    // The shared fixtures of CrashResumeFixture.kt (R2): reflection on the tunnel-wg statics,
    // a fresh gate per variant, a scripted control; both restored in finally.

    @Test
    fun `authenticated disconnect before a late resume wins while idle`() = runBlocking {
        val fixture = installResumeFixture()
        var primary: Throwable? = null
        try {
            enableExternalControl()
            ActiveProfileStore(context).apply { setActive("p1"); setLastConnected("p1") }
            shadowOf(context as Application).clearStartedServices()

            AutomationReceiver().handle(context, Intent(AutomationReceiver.ACTION_DISCONNECT).withToken())

            assertNull("ACTIVE was kept", ActiveProfileStore(context).activeId.first())
            assertEquals("p1", ActiveProfileStore(context).lastConnectedId.first())
            assertNull(
                "an idle authenticated disconnect started a tunnel command",
                shadowOf(context as Application).nextStartedService,
            )

            deliverLateResume(fixture, context, "p1")

            assertTrue("the barred resume reached the connect", fixture.connectCalls.isEmpty())
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            restoreResumeFixture(fixture, null, primary)
        }
    }

    @Test
    fun `unauthenticated automation cannot bar a resume`() = runBlocking {
        seedProfile("p1", "Home")
        val variants = listOf("external control off", "missing token", "wrong token", "unrelated action")
        for (variant in variants) {
            // Fresh process state per variant: the rules, the gate, the script and the records.
            AutomationRulesStore(context).mutate { AutoConnectRules() }
            if (variant != "external control off") enableExternalControl()
            val intent = when (variant) {
                "external control off" -> Intent(AutomationReceiver.ACTION_DISCONNECT).withToken()
                "missing token" -> Intent(AutomationReceiver.ACTION_DISCONNECT)
                "wrong token" -> Intent(AutomationReceiver.ACTION_DISCONNECT).putExtra(AutomationReceiver.EXTRA_TOKEN, "nope")
                else -> Intent("dev.vaayil.automation.UNRELATED").withToken()
            }
            val fixture = installResumeFixture()
            var primary: Throwable? = null
            try {
                ActiveProfileStore(context).apply { setActive("p1"); setLastConnected("p1") }
                // The admitted resume must settle on its own: no start stays in flight.
                fixture.lookup.complete(Unit)
                fixture.blocked.complete(Unit)

                AutomationReceiver().handle(context, intent)

                assertEquals("$variant: ACTIVE was not left in place", "p1", ActiveProfileStore(context).activeId.first())
                deliverLateResume(fixture, context, "p1")
                assertEquals("$variant: the resume did not connect exactly once", listOf("p1"), fixture.connectCalls)
                assertEquals("$variant: the resume was not admitted", listOf(StartOutcome.STARTED), fixture.outcomes)
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                restoreResumeFixture(fixture, null, primary)
            }
        }
    }
}
