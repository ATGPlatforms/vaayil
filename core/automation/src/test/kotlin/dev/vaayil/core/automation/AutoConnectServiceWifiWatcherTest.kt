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
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowVpnService

/**
 * Y3: the automation service runs while either rule is on, but its Wi-Fi watcher, the only reader of the Wi-Fi
 * name (and, below API 31, of the scan list), runs only while Auto-connect on untrusted Wi-Fi is on. With only
 * Auto-connect on mobile data on, location grants left from earlier must not let Vaayil read the Wi-Fi name.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AutoConnectServiceWifiWatcherTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var controller: ServiceController<AutoConnectService>

    private val callbacks: Set<ConnectivityManager.NetworkCallback>
        get() = shadowOf(app.getSystemService(ConnectivityManager::class.java)).networkCallbacks.toSet()

    @Before
    fun grantEverything() {
        // Every grant held and Location on: a registered Wi-Fi watcher would read the network's name.
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        ShadowVpnService.setPrepareResult(null) // consent granted: a CONNECT may start the tunnel
        ConnectionRegistry.status.value = TunnelStatus.idle
        AutomationRulesStore(app) // open the DataStore in the app context first, as the service expects
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.destroy()
        // A granted consent must not leak into the next test.
        ShadowVpnService.setPrepareResult(Intent())
    }

    /**
     * The service, following [wifiGuard] as the stored Auto-connect on untrusted Wi-Fi rule, or the store itself
     * when it is null. Evaluations read [rules] and connect "p1".
     */
    private fun created(
        wifiGuard: Flow<Boolean>?,
        rules: () -> AutoConnectRules = { AutoConnectRules(autoConnectOnMobileData = true) },
    ): AutoConnectService {
        controller = Robolectric.buildService(AutoConnectService::class.java)
        if (wifiGuard != null) controller.get().observeWifiGuard = { wifiGuard }
        controller.get().readRules = { rules() }
        controller.get().readTarget = { "p1" }
        controller.create()
        idle()
        return controller.get()
    }

    /** Delivers one secured Wi-Fi network through [callback] the way the platform's callback would. */
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

    /** The store's flow reaches the main looper some time after a write: poll on a bounded deadline. */
    private fun awaitCallbackCount(expected: Int, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            idle()
            if (callbacks.size == expected) return
            Thread.sleep(20)
        }
        fail("expected $expected network callbacks, found ${callbacks.size}")
    }

    @Test
    fun `with only Auto-connect on mobile data on, nothing watches Wi-Fi`() {
        val service = created(MutableStateFlow(false))

        // The mobile-data watcher's default-network callback is the only one...
        assertEquals(1, callbacks.size)
        // ...and a Wi-Fi network delivered to it never reaches the Wi-Fi rules or starts anything.
        deliverWifi(callbacks.single(), "Cafe")
        idle()
        assertTrue(service.wifiGate.changed("Cafe", WifiSecurity.SECURED))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `turning Auto-connect on untrusted Wi-Fi on starts the Wi-Fi watcher and turning it off stops it`() {
        val guard = MutableStateFlow(false)
        val service = created(guard)
        val mobileOnly = callbacks

        guard.value = true
        idle()
        val withWifi = callbacks
        assertEquals(2, withWifi.size)
        assertTrue(withWifi.containsAll(mobileOnly)) // the mobile-data watcher is untouched

        // The new callback feeds the Wi-Fi rules: the gate has seen the network.
        deliverWifi((withWifi - mobileOnly).single(), "Cafe")
        idle()
        assertFalse(service.wifiGate.changed("Cafe", WifiSecurity.SECURED))

        guard.value = false
        idle()
        assertEquals(mobileOnly, callbacks) // only the Wi-Fi watcher stopped
        assertTrue(service.wifiGate.changed("Cafe", WifiSecurity.SECURED)) // and its last network was forgotten
    }

    @Test
    fun `turning the rule on evaluates the current network at once, even one a late evaluation saw while it was off`() {
        val guard = MutableStateFlow(false)
        val service = created(guard, rules = { AutoConnectRules(wifiGuardEnabled = guard.value) })
        val mobileOnly = callbacks

        // A delivery still queued when the rule went off is evaluated late: the gate records the network and the
        // rule, off, decides nothing.
        runBlocking { service.evaluateWifi("Cafe", WifiSecurity.SECURED).join() }
        assertNull(shadowOf(app).nextStartedService)

        // Turning the rule on registers the watcher, and Android delivers the current network to it at once.
        guard.value = true
        idle()
        deliverWifi((callbacks - mobileOnly).single(), "Cafe")
        idle()

        val started = shadowOf(app).nextStartedService
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
        assertNull(shadowOf(app).nextStartedService) // exactly one start
    }

    @Test
    fun `rules that cannot be read leave the Wi-Fi watcher off`() {
        created(
            flow {
                emit(true)
                throw IOException("unreadable")
            },
        )

        assertEquals(1, callbacks.size) // registered for the readable value, then stopped by the failure
        assertTrue(
            ShadowLog.getLogsForTag("AutoConnectService").any {
                it.msg == "rules unreadable (IOException); Wi-Fi watcher off"
            },
        )
    }

    @Test
    fun `location changes and start commands with the rule off register no Wi-Fi watcher`() {
        created(MutableStateFlow(false))
        val mobileOnly = callbacks

        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
        idle()
        assertEquals(mobileOnly, callbacks)

        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        controller.startCommand(0, 2)
        idle()
        assertEquals(mobileOnly, callbacks)
    }

    @Test
    fun `by default the Wi-Fi watcher follows the stored rule`() {
        val store = AutomationRulesStore(app)
        runBlocking { store.mutate { AutoConnectRules(wifiGuardEnabled = true, autoConnectOnMobileData = true) } }
        created(wifiGuard = null)
        awaitCallbackCount(2)

        runBlocking { store.setWifiGuard(false) }
        awaitCallbackCount(1)

        runBlocking { store.setWifiGuard(true) }
        awaitCallbackCount(2)
    }
}
