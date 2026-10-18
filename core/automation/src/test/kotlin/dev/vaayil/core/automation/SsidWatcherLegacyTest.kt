// ShadowScanResult.newInstance(SSID, BSSID, caps, level, frequency) is deprecated in Robolectric 4.14;
// the non-deprecated overload takes an InformationElement list this test has no use for.
@file:Suppress("DEPRECATION")

package dev.vaayil.core.automation

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.WifiSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowScanResult
import org.robolectric.shadows.ShadowWifiInfo

/** S4 below API 31: the security comes from the scan result whose BSSID is the connected one. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26], application = Application::class)
class SsidWatcherLegacyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `below API 31 the connected BSSID's scan result decides the security`() {
        val wifi = context.getSystemService(WifiManager::class.java)!!
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setSSID("Home")
        shadowOf(info).setBSSID("aa:bb:cc:00:00:02")
        shadowOf(wifi).setConnectionInfo(info)
        shadowOf(wifi).setScanResults(
            listOf(
                ShadowScanResult.newInstance("Home", "aa:bb:cc:00:00:01", "[ESS]", -50, 2412),
                ShadowScanResult.newInstance("Home", "aa:bb:cc:00:00:02", "[WPA2-PSK-CCMP][ESS]", -50, 2412),
            ),
        )
        val cm = context.getSystemService(ConnectivityManager::class.java)!!
        val before = shadowOf(cm).networkCallbacks.toSet()
        val seen = mutableListOf<Pair<String?, WifiSecurity>>()
        val watcher = SsidWatcher(context, CoroutineScope(Job())) { ssid, security -> seen += ssid to security }
        watcher.register()
        val callback = (shadowOf(cm).networkCallbacks - before).single()
        val caps = ShadowNetworkCapabilities.newInstance()

        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
        assertEquals(listOf("Home" to WifiSecurity.SECURED), seen)

        shadowOf(wifi).setScanResults(emptyList())
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
        assertEquals("Home" to WifiSecurity.SECURED, seen.last()) // a gap in the scan list keeps the BSSID's last definite reading
        watcher.unregister()
    }

    @Test
    fun `a roam to a BSSID with no scan result reads unknown`() {
        val wifi = context.getSystemService(WifiManager::class.java)!!
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setSSID("Home")
        shadowOf(info).setBSSID("aa:bb:cc:00:00:02")
        shadowOf(wifi).setConnectionInfo(info)
        shadowOf(wifi).setScanResults(
            listOf(ShadowScanResult.newInstance("Home", "aa:bb:cc:00:00:02", "[WPA2-PSK-CCMP][ESS]", -50, 2412)),
        )
        val cm = context.getSystemService(ConnectivityManager::class.java)!!
        val before = shadowOf(cm).networkCallbacks.toSet()
        val seen = mutableListOf<Pair<String?, WifiSecurity>>()
        val watcher = SsidWatcher(context, CoroutineScope(Job())) { ssid, security -> seen += ssid to security }
        watcher.register()
        val callback = (shadowOf(cm).networkCallbacks - before).single()
        val caps = ShadowNetworkCapabilities.newInstance()

        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
        assertEquals(listOf("Home" to WifiSecurity.SECURED), seen)

        // The roamed-to BSSID is not in the scan list: it inherits nothing.
        shadowOf(info).setBSSID("aa:bb:cc:00:00:03")
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
        assertEquals("Home" to WifiSecurity.UNKNOWN, seen.last())
        watcher.unregister()
    }

    @Test
    fun `onLost clears the remembered reading`() {
        val wifi = context.getSystemService(WifiManager::class.java)!!
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setSSID("Home")
        shadowOf(info).setBSSID("aa:bb:cc:00:00:02")
        shadowOf(wifi).setConnectionInfo(info)
        shadowOf(wifi).setScanResults(
            listOf(ShadowScanResult.newInstance("Home", "aa:bb:cc:00:00:02", "[WPA2-PSK-CCMP][ESS]", -50, 2412)),
        )
        val cm = context.getSystemService(ConnectivityManager::class.java)!!
        val before = shadowOf(cm).networkCallbacks.toSet()
        val seen = mutableListOf<Pair<String?, WifiSecurity>>()
        val watcher = SsidWatcher(context, CoroutineScope(Job())) { ssid, security -> seen += ssid to security }
        watcher.register()
        val callback = (shadowOf(cm).networkCallbacks - before).single()
        val caps = ShadowNetworkCapabilities.newInstance()

        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
        assertEquals(listOf("Home" to WifiSecurity.SECURED), seen)

        callback.onLost(ShadowNetwork.newInstance(1))
        assertEquals(null to WifiSecurity.UNKNOWN, seen.last())

        shadowOf(wifi).setScanResults(emptyList())
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
        assertEquals("Home" to WifiSecurity.UNKNOWN, seen.last())
        watcher.unregister()
    }

    @Test
    fun `re-registering clears the remembered reading`() {
        val wifi = context.getSystemService(WifiManager::class.java)!!
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setSSID("Home")
        shadowOf(info).setBSSID("aa:bb:cc:00:00:02")
        shadowOf(wifi).setConnectionInfo(info)
        shadowOf(wifi).setScanResults(
            listOf(ShadowScanResult.newInstance("Home", "aa:bb:cc:00:00:02", "[WPA2-PSK-CCMP][ESS]", -50, 2412)),
        )
        val cm = context.getSystemService(ConnectivityManager::class.java)!!
        val before = shadowOf(cm).networkCallbacks.toSet()
        val seen = mutableListOf<Pair<String?, WifiSecurity>>()
        val watcher = SsidWatcher(context, CoroutineScope(Job())) { ssid, security -> seen += ssid to security }
        watcher.register()
        val callback = (shadowOf(cm).networkCallbacks - before).single()
        val caps = ShadowNetworkCapabilities.newInstance()

        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
        assertEquals(listOf("Home" to WifiSecurity.SECURED), seen)

        watcher.unregister()
        watcher.register()
        val reregistered = (shadowOf(cm).networkCallbacks - setOf(callback)).single()

        shadowOf(wifi).setScanResults(emptyList())
        reregistered.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
        assertEquals("Home" to WifiSecurity.UNKNOWN, seen.last())
        watcher.unregister()
    }

    @Test
    fun `a null SSID skips the security read`() {
        val wifi = context.getSystemService(WifiManager::class.java)!!
        // Location denial: the connection info reports the placeholder SSID ("<unknown ssid>",
        // WifiSsid.NONE on a WifiInfo with no SSID set), which redactSsid turns into null.
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setBSSID("aa:bb:cc:00:00:02")
        shadowOf(wifi).setConnectionInfo(info)
        shadowOf(wifi).setScanResults(
            listOf(ShadowScanResult.newInstance("Home", "aa:bb:cc:00:00:02", "[WPA2-PSK-CCMP][ESS]", -50, 2412)),
        )
        val cm = context.getSystemService(ConnectivityManager::class.java)!!
        val before = shadowOf(cm).networkCallbacks.toSet()
        val seen = mutableListOf<Pair<String?, WifiSecurity>>()
        val watcher = SsidWatcher(context, CoroutineScope(Job())) { ssid, security -> seen += ssid to security }
        watcher.register()
        val callback = (shadowOf(cm).networkCallbacks - before).single()
        val caps = ShadowNetworkCapabilities.newInstance()

        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
        assertEquals(listOf(null to WifiSecurity.UNKNOWN), seen)
        watcher.unregister()
    }
}
