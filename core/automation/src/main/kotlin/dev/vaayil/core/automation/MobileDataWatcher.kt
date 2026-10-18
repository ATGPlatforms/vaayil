package dev.vaayil.core.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Push-based watch on the DEFAULT network: fires [onMobileData] when the network the
 * device is actually using becomes (or stops being) cellular. A SIM being merely
 * present is not a trigger — registerDefaultNetworkCallback tracks the active default,
 * so being on Wi-Fi keeps reporting false. Event-driven: no polling, no idle work.
 */
class MobileDataWatcher(
    private val context: Context,
    private val onMobileData: (Boolean) -> Unit,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var last: Boolean? = null

    fun register() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                fire(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))

            override fun onLost(network: Network) = fire(false)
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (_: SecurityException) {
            fire(false)
        }
    }

    fun unregister() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        last = null
    }

    private fun fire(mobile: Boolean) {
        if (mobile != last) {
            last = mobile
            onMobileData(mobile)
        }
    }
}
