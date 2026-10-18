package dev.vaayil.core.automation

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.AutomationRulesStore
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.WifiSecurity
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowVpnService

/**
 * M5: a location grant changed outside the app reaches the Wi-Fi rules on the next start command.
 * Also pins (sweep 2) that a grant change re-registers only the SSID watcher.
 *
 * B2: the platform's Location switch (API 27+) reaches the service the same way while it runs —
 * no app start needed. The re-registration forgets the Wi-Fi gate, so the current network,
 * delivered to the new callback at once, is evaluated again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AutoConnectServicePermissionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var controller: ServiceController<AutoConnectService>

    private val callbacks: Set<ConnectivityManager.NetworkCallback>
        get() = shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks.toSet()

    private fun created(): AutoConnectService {
        // The service's field initializer opens the automation DataStore before the service has a context; the
        // DataStore delegate needs one on first use. In the app, Application.onCreate always opens it first.
        AutomationRulesStore(app)
        controller = Robolectric.buildService(AutoConnectService::class.java).create()
        return controller.get()
    }

    /**
     * B2 fixture: location grants in place, Location on, VPN consent granted and the rules/target
     * seams injected, so a delivered Wi-Fi network may actually connect.
     */
    private fun createdWithLocation(
        rules: AutoConnectRules = AutoConnectRules(wifiGuardEnabled = true),
    ): AutoConnectService {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        ShadowVpnService.setPrepareResult(null) // consent granted: a CONNECT may start the tunnel
        AutomationRulesStore(app) // open the DataStore in the app context first, as the service expects
        ConnectionRegistry.status.value = TunnelStatus.idle
        controller = Robolectric.buildService(AutoConnectService::class.java).create()
        return controller.get().apply {
            readRules = { rules }
            readTarget = { "p1" }
        }
    }

    private fun setLocationEnabled(enabled: Boolean) =
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(enabled)

    /** Delivers one Wi-Fi network through [callback] the way the platform's callback would. */
    private fun deliverWifi(callback: ConnectivityManager.NetworkCallback, ssid: String) {
        val info = WifiInfo.Builder()
            .setSsid(ssid.toByteArray())
            .setCurrentSecurityType(WifiInfo.SECURITY_TYPE_PSK)
            .build()
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).setTransportInfo(info)
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun modeChangedReceivers() = shadowOf(app).registeredReceivers.filter {
        it.intentFilter.hasAction(LocationManager.MODE_CHANGED_ACTION)
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.destroy()
        // A granted consent must not leak into the next test: a non-null prepare result is the
        // "consent missing" default ShadowVpnService itself starts and resets with.
        ShadowVpnService.setPrepareResult(Intent())
    }

    @Test
    fun `Location on reevaluates unchanged WiFi`() {
        val service = createdWithLocation()
        // The rules already saw this network once...
        assertTrue(service.wifiGate.changed("Home", WifiSecurity.SECURED))
        val before = callbacks

        // Location off: the watcher re-registers (only Wi-Fi) so the current network is delivered again.
        setLocationEnabled(false)
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
        idle()
        val off = callbacks
        assertEquals(before.size, off.size)
        assertNotEquals(before, off)
        assertTrue(before.intersect(off).isNotEmpty()) // the mobile watcher's callback stays
        assertTrue(service.wifiGate.changed("Home", WifiSecurity.SECURED)) // and the gate was forgotten

        // Location back on: the same re-registration.
        setLocationEnabled(true)
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
        idle()
        val on = callbacks
        assertEquals(off.size, on.size)
        assertNotEquals(off, on)
        assertTrue(off.intersect(on).isNotEmpty())

        // The same network, delivered through the newly registered callback, is evaluated at once:
        // only a forgotten gate lets this delivery through, and the CONNECT needs no start command.
        shadowOf(app).clearStartedServices()
        deliverWifi((on - off).single(), "Home")
        idle()

        val started = shadowOf(app).nextStartedService
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
        assertNull(shadowOf(app).nextStartedService) // exactly one start
    }

    @Test
    fun `duplicate broadcasts and destruction do not leak observers`() {
        createdWithLocation()
        val before = callbacks

        // An unchanged state must not re-register anything.
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
        idle()
        assertEquals(before, callbacks)

        // A real switch re-registers Wi-Fi only: same count, a new Wi-Fi callback, the mobile one kept.
        setLocationEnabled(false)
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
        idle()
        val changed = callbacks
        assertEquals(before.size, changed.size)
        assertNotEquals(before, changed)
        assertTrue(before.intersect(changed).isNotEmpty())

        // Destruction removes both watchers and the receiver. A later switch must find no listener:
        // a leaked receiver would re-register the Wi-Fi watcher, showing up as a new callback.
        controller.destroy()
        idle()
        assertTrue(callbacks.isEmpty())
        assertTrue(modeChangedReceivers().isEmpty())

        setLocationEnabled(true)
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
        idle()
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun `a grant made outside the app re-registers the Wi-Fi watcher and forgets the last network`() {
        val service = created() // no location yet: the watcher registered while the rules were paused
        assertTrue(service.wifiGate.changed("Home", WifiSecurity.SECURED)) // the rules already saw Home once
        val before = callbacks

        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        controller.startCommand(0, 2)

        val after = callbacks
        assertEquals(before.size, after.size)
        assertNotEquals(before, after) // a new Wi-Fi callback: Android delivers the current network to it at once
        // Sweep-2 pin: ONLY the SSID watcher re-registered — the mobile-data watcher's callback is
        // unchanged, so the two sets still share a callback.
        assertTrue(before.intersect(after).isNotEmpty())
        assertTrue(service.wifiGate.changed("Home", WifiSecurity.SECURED)) // and that delivery is evaluated
    }

    @Test
    fun `a revoked grant is picked up the same way`() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        val service = created()
        assertTrue(service.wifiGate.changed("Home", WifiSecurity.SECURED))
        val before = callbacks

        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        controller.startCommand(0, 2)

        assertNotEquals(before, callbacks)
        assertTrue(service.wifiGate.changed("Home", WifiSecurity.SECURED))
    }

    @Test
    fun `a start command with no permission change keeps the watcher and the last network`() {
        val service = created()
        assertTrue(service.wifiGate.changed("Home", WifiSecurity.SECURED))
        val before = callbacks

        controller.startCommand(0, 2)

        assertEquals(before, callbacks)
        assertFalse(service.wifiGate.changed("Home", WifiSecurity.SECURED))
    }
}
