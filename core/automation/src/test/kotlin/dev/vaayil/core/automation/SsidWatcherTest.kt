package dev.vaayil.core.automation
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.WifiSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Regression: register() must store the registered callback so unregister()
 * actually removes it (a wifi-guard toggle otherwise leaks the service's
 * watcher, its context reference, and duplicate onSsid deliveries).
 * Mechanism: ShadowConnectivityManager's registered-callback tracking.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SsidWatcherTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `unregister removes the registered network callback`() {
        val cm = context.getSystemService(ConnectivityManager::class.java)!!
        val shadow = shadowOf(cm)
        val watcher = SsidWatcher(context, CoroutineScope(Job())) { _, _ -> }
        val before = shadow.networkCallbacks.size

        watcher.register()
        assertEquals(before + 1, shadow.networkCallbacks.size)

        watcher.unregister()
        assertEquals(before, shadow.networkCallbacks.size)
    }

    @Test
    fun `the network's security type arrives with its name`() {
        val cm = context.getSystemService(ConnectivityManager::class.java)!!
        val before = shadowOf(cm).networkCallbacks.toSet()
        val seen = mutableListOf<Pair<String?, WifiSecurity>>()
        val watcher = SsidWatcher(context, CoroutineScope(Job())) { ssid, security -> seen += ssid to security }
        watcher.register()
        val callback = (shadowOf(cm).networkCallbacks - before).single()
        val info = WifiInfo.Builder()
            .setSsid("Cafe".toByteArray())
            .setCurrentSecurityType(WifiInfo.SECURITY_TYPE_OWE)
            .build()
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).setTransportInfo(info)

        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(1), caps)
        callback.onLost(ShadowNetwork.newInstance(1))

        assertEquals(listOf("Cafe" to WifiSecurity.OPEN, null to WifiSecurity.UNKNOWN), seen)
        watcher.unregister()
    }

    @Test
    fun `the manifest asks for Wi-Fi state, which every Wi-Fi read below API 31 needs`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.ACCESS_WIFI_STATE in requested)
    }
}
