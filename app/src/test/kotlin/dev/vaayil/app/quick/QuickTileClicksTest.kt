package dev.vaayil.app.quick

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.app.AppContainer
import dev.vaayil.app.settingsDataStore
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.TunnelNotifications
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowVpnService

/**
 * A tap acts on the state the tile last showed: nothing before the first state arrives, a
 * disconnect while live, a connect for the shown target, and the app when it cannot connect.
 * A disconnect waits for the unlock on a secure lock screen; a connect does not.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickTileClicksTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private lateinit var originalAccess: (Context) -> QuickControls
    private lateinit var service: QuickTileService
    private lateinit var host: FakeHost

    @Before
    fun setUp() {
        // Robolectric shares the application between methods: no stale start may leak in.
        while (shadowOf(app).nextStartedService != null) Unit
        ShadowVpnService.setPrepareResult(null)
        runBlocking {
            DataGraph.reset()
            app.settingsDataStore.edit { it.clear() }
            ActiveProfileStore(app).apply {
                setActive(null)
                setLastConnected(null)
            }
            container = AppContainer(app, { _ -> }, { _ -> })
        }
        originalAccess = QuickControlsAccess.forContext
        QuickControlsAccess.forContext = { container.quickControls }
        service = Robolectric.buildService(QuickTileService::class.java).create().get()
        host = FakeHost()
        service.host = host
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
    }

    @After
    fun tearDown() {
        QuickControlsAccess.forContext = originalAccess
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    @Test
    fun `a tap before the first render does nothing`() {
        service.onClick()

        assertNull(shadowOf(app).nextStartedService)
        assertNull(container.quickControls.requests.pending.value)
        assertEquals(0, host.opened)
    }

    @Test
    fun `without a PIN a tap while connected disconnects at once`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        runBlocking { ActiveProfileStore(app).setActive("p1") }
        service.render(state(ConnectionRegistry.status.value, handshake = HandshakeState.VERIFIED))

        service.onClick()

        assertTrue(host.unlockActions.isEmpty())
        awaitDisconnect()
    }

    @Test
    fun `a tap while disconnected connects the shown profile`() {
        service.render(state(TunnelStatus.idle))

        service.onClick()

        val started = shadowOf(app).nextStartedService
        assertEquals(VpnTunnelService::class.java.name, started?.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
        assertNull(container.quickControls.requests.pending.value)
        assertEquals(0, host.opened)
    }

    @Test
    fun `a tap after a failure retries the failed profile`() {
        val shown = state(
            TunnelStatus(ConnectionPhase.FAILED, "p1", null),
            readable = listOf(QuickProfile("p1", "Home"), QuickProfile("p2", "Work")),
            lastConnectedId = "p2",
        )
        assertEquals(QuickAction.Connect("p1"), shown.action)
        service.render(shown)

        service.onClick()

        assertEquals(
            "p1",
            shadowOf(app).nextStartedService?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID),
        )
    }

    @Test
    fun `without consent a tap opens the app with a request and no notice`() {
        ShadowVpnService.setPrepareResult(Intent()) // missing consent
        service.render(state(TunnelStatus.idle))

        service.onClick()

        assertEquals("p1", container.quickControls.requests.pending.value?.profileId)
        assertEquals(1, host.opened)
        assertNull("a missing consent started the service", shadowOf(app).nextStartedService)
        val nm = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        assertNull("the tile posted the consent notice", nm.getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `with no readable profile a tap opens the app`() {
        service.render(state(TunnelStatus.idle, readable = emptyList(), storedCount = 0))

        service.onClick()

        assertEquals(1, host.opened)
        assertNull(container.quickControls.requests.pending.value)
    }

    @Test
    fun `on a secure lock screen a disconnect waits for the unlock`() {
        host.secure = true
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        runBlocking { ActiveProfileStore(app).setActive("p1") }
        service.render(state(ConnectionRegistry.status.value, handshake = HandshakeState.VERIFIED))

        service.onClick()

        assertNull("the locked tile started the disconnect", shadowOf(app).nextStartedService)
        assertEquals(1, host.unlockActions.size)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("a cancelled unlock still ran the disconnect", shadowOf(app).nextStartedService)

        host.unlockActions.single().run()
        awaitDisconnect()
    }

    @Test
    fun `on a secure lock screen a connect runs at once`() {
        host.secure = true
        service.render(state(TunnelStatus.idle))

        service.onClick()

        val started = shadowOf(app).nextStartedService
        assertEquals(VpnTunnelService::class.java.name, started?.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
        assertTrue(host.unlockActions.isEmpty())
    }

    private fun state(
        status: TunnelStatus,
        handshake: HandshakeState = HandshakeState.NONE,
        readable: List<QuickProfile> = listOf(QuickProfile("p1", "Home")),
        storedCount: Int = readable.size,
        activeId: String? = null,
        lastConnectedId: String? = null,
    ): QuickState = presentQuickState(
        QuickInputs(status, handshake, readable, storedCount, activeId, lastConnectedId),
    )

    /** The disconnect runs on the hub's process scope, so its start and ACTIVE clear are polled. */
    private fun awaitDisconnect() {
        runBlocking {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && ActiveProfileStore(app).activeId.first() != null) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(20)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("the tap did not clear ACTIVE", ActiveProfileStore(app).activeId.first())
            val started = shadowOf(app).nextStartedService
            assertEquals(VpnTunnelService::class.java.name, started?.component?.className)
            assertEquals(VpnTunnelService.ACTION_DISCONNECT, started?.action)
        }
    }

    private class FakeHost(var secure: Boolean = false) : QuickTileHost {
        val unlockActions = mutableListOf<Runnable>()
        var opened = 0

        override fun isSecure(): Boolean = secure

        override fun unlockAndRun(action: Runnable) {
            unlockActions += action
        }

        override fun openApp() {
            opened++
        }
    }
}
