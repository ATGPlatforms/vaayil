package dev.vaayil.core.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dev.vaayil.core.model.WifiSecurity
import dev.vaayil.core.model.normalizeSsid
import kotlinx.coroutines.CoroutineScope

/**
 * Location denial surfaces as a redacted "<unknown ssid>" (pre-31 connectionInfo
 * and 31+ sanitized transportInfo alike), not an exception — it must read as
 * "no SSID" or decide() would treat every network as untrusted and auto-connect
 * (inverting spec §15.7's graceful pause).
 */
internal fun redactSsid(raw: String?): String? =
    if (raw == null || raw == "<unknown ssid>") null else normalizeSsid(raw)

/**
 * Push-based Wi-Fi SSID watcher: one NetworkCallback, no polling. The callback
 * [onSsid] receives the normalized SSID (null = unknown / not Wi-Fi) and the network's
 * [WifiSecurity] (S4: anything Vaayil cannot read is UNKNOWN, which is never trusted);
 * location denial funnels to null in every path so rules pause gracefully instead of
 * acting on a guessed network (spec §15.7).
 */
class SsidWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSsid: (ssid: String?, security: WifiSecurity) -> Unit,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val legacySecurity = BssidSecurityMemory()

    fun register() {
        legacySecurity.clear()
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            // Without FLAG_INCLUDE_LOCATION_INFO the system redacts transportInfo's
            // SSID to "<unknown ssid>" on API 31+ even when location is granted.
            object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
            ) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = report(caps)

                override fun onLost(network: Network) {
                    legacySecurity.clear()
                    onSsid(null, WifiSecurity.UNKNOWN)
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = report(caps)

                override fun onLost(network: Network) {
                    legacySecurity.clear()
                    onSsid(null, WifiSecurity.UNKNOWN)
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
            // Store only on success: a failed registration must not leave a dead
            // callback for unregister() to no-op against.
            callback = cb
        } catch (_: SecurityException) {
            onSsid(null, WifiSecurity.UNKNOWN)
        }
    }

    fun unregister() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    /** Normalized SSID from capabilities; location denial funnels to null (spec §15.7). */
    private fun ssidFrom(caps: NetworkCapabilities): String? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            redactSsid((caps.transportInfo as? WifiInfo)?.ssid)
        } else {
            @Suppress("DEPRECATION")
            redactSsid(
                context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid,
            )
        }
    } catch (_: SecurityException) {
        null
    }

    private fun report(caps: NetworkCapabilities) {
        val ssid = ssidFrom(caps)
        // A null SSID decides nothing, so its security is never read.
        onSsid(ssid, if (ssid == null) WifiSecurity.UNKNOWN else securityFrom(caps))
    }

    /**
     * S4: how the connected network authenticates. API 31+ reads the security type the system reports for it;
     * below 31, the capabilities of the scan result with the connected BSSID, and an UNKNOWN read of the same
     * BSSID reports its last definite reading ([BssidSecurityMemory]). Anything unreadable is UNKNOWN.
     */
    private fun securityFrom(caps: NetworkCapabilities): WifiSecurity = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (caps.transportInfo as? WifiInfo)?.let { securityOfType(it.currentSecurityType) } ?: WifiSecurity.UNKNOWN
        } else {
            val wifi = context.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val bssid = wifi?.connectionInfo?.bssid
            legacySecurity.resolve(
                bssid,
                securityOfScan(bssid, wifi?.scanResults.orEmpty().map { it.BSSID to it.capabilities }),
            )
        }
    } catch (_: SecurityException) {
        WifiSecurity.UNKNOWN
    }
}
