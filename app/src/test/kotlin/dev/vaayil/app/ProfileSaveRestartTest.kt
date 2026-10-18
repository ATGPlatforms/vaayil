package dev.vaayil.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F22 + C4: saving an edit to the CONNECTED profile must restart the tunnel through the service
 * (ACTION_CONNECT for that id) so the change applies at once, and an edit saved while that profile
 * is CONNECTING must apply as the start settles CONNECTED. A rename alone, an edit to another
 * profile, or a save from any non-live phase must not start anything.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ProfileSaveRestartTest {
    // The coordinator's own scope, as in the app: the restart is never run on the save's caller.
    // Unconfined settles a status emission inline, so these tests need no checkpoint.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun profile(id: String, name: String = "Home", mtu: Int? = 1280) = Profile(
        id, name,
        InterfaceConfig("k", listOf("10.0.0.2/32"), emptyList(), mtu, null),
        PeerConfig("K", null, "h:51820", listOf("0.0.0.0/0"), 25),
    )

    @Before
    fun drainStartedServices() {
        // Robolectric shares the application between test methods: drop intents an earlier
        // test started so no stale service intent leaks into these assertions.
        val app = ApplicationProvider.getApplicationContext<Application>()
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @After
    fun resetStatusAndRequests() {
        scope.cancel() // a pending request must not outlive its test and fire into the next one
        ConnectionRegistry.status.value = TunnelStatus.idle
    }

    /** The production restart callback (C4); the assertions below check the intent it builds. */
    private fun restarts() = SettingsRestartCoordinator(
        scope,
        ConnectionRegistry.status,
    ) { id -> restartTunnel(ApplicationProvider.getApplicationContext(), id) }

    @Test
    fun `saving a changed connected profile restarts it`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        restartIfConnectedProfileChanged(profile("p1"), profile("p1", mtu = 1300), restarts())
        val intent = shadowOf(context as Application).nextStartedService
        assertEquals(VpnTunnelService::class.java.name, intent.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, intent.action)
        assertEquals("p1", intent.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `saving a changed profile while it connects restarts once it is connected`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTING, "p1", null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        restartIfConnectedProfileChanged(profile("p1"), profile("p1", mtu = 1300), restarts())
        assertNull(shadowOf(context as Application).nextStartedService) // still connecting
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        val intent = shadowOf(context as Application).nextStartedService
        assertEquals(VpnTunnelService::class.java.name, intent.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, intent.action)
        assertEquals("p1", intent.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `a rename alone does not restart`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        restartIfConnectedProfileChanged(profile("p1"), profile("p1", name = "Renamed"), restarts())
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `a rename alone while connecting restarts nothing`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTING, "p1", null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        restartIfConnectedProfileChanged(profile("p1"), profile("p1", name = "Renamed"), restarts())
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `saving another profile does not restart`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p2", null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        restartIfConnectedProfileChanged(profile("p1"), profile("p1", mtu = 1300), restarts())
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `an edit to another profile while it connects restarts nothing`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTING, "p2", null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        restartIfConnectedProfileChanged(profile("p1"), profile("p1", mtu = 1300), restarts())
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p2", null)
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `nothing restarts unless connected`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTING, "p1", null)
        restartIfConnectedProfileChanged(profile("p1"), profile("p1", mtu = 1300), restarts())
        assertNull(shadowOf(context as Application).nextStartedService)
        ConnectionRegistry.status.value =
            TunnelStatus(ConnectionPhase.FAILED, "p1", TunnelError.HandshakeTimeout)
        restartIfConnectedProfileChanged(profile("p1"), profile("p1", mtu = 1300), restarts())
        assertNull(shadowOf(context as Application).nextStartedService)
        // The failed start consumed the pending request: a later session gets nothing from it.
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `an unknown previous version restarts a connected profile`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        restartIfConnectedProfileChanged(null, profile("p1", mtu = 1300), restarts())
        val intent = shadowOf(context as Application).nextStartedService
        assertEquals(VpnTunnelService.ACTION_CONNECT, intent.action)
        assertEquals("p1", intent.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }
}
