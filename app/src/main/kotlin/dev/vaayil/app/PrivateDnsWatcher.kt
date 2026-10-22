package dev.vaayil.app

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the watcher registers with: [ConnectivityManager] in production, a recorder in tests. */
internal interface NetworkCallbackRegistrar {
    fun registerNetworkCallback(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback)
    fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback)
}

internal fun ConnectivityManager.asNetworkCallbackRegistrar(): NetworkCallbackRegistrar {
    val manager = this
    return object : NetworkCallbackRegistrar {
        override fun registerNetworkCallback(
            request: NetworkRequest,
            callback: ConnectivityManager.NetworkCallback,
        ) {
            manager.registerNetworkCallback(request, callback)
        }

        override fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            manager.registerDefaultNetworkCallback(callback)
        }
    }
}

/**
 * T-8 (spec §3.1): Android's Private DNS can send every app's lookups to a provider the tunnel
 * cannot reach, so the UI needs to know when a strict-mode hostname is in effect.
 *
 * The hostname is read only from the link properties of the non-VPN networks that carry INTERNET.
 * The VPN network's own private-DNS field is not read: it lags the underlying network's value or
 * stays empty. A VPN network's name therefore reaches only the default-network callback, which
 * ignores names. Below API 28 the platform exposes no name and nothing registers.
 *
 * Production builds one instance for the process and calls [start] once.
 */
internal class PrivateDnsWatcher(registrar: NetworkCallbackRegistrar?) {
    private val registrar = registrar
    private val lock = Any()

    private val hostState = MutableStateFlow<String?>(null)
    private val vpnHasDnsServerState = MutableStateFlow(false)

    /** The hostnames still carried, by network, insertion order; the last is the most recent. [lock] */
    private val hostNames = LinkedHashMap<Network, String>()

    /** The default callback's network and what it last said. [lock] */
    private var trackedNetwork: Network? = null
    private var trackedIsVpn = false
    private var trackedHasDns = false

    /** The strict-mode hostname; null for Off, Automatic, no network, or below API 28. */
    val host: StateFlow<String?> = hostState.asStateFlow()

    /** Vaayil's default network is a VPN network that lists at least one DNS server. */
    val vpnHasDnsServer: StateFlow<Boolean> = vpnHasDnsServerState.asStateFlow()

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val registrar = registrar ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            registrar.registerNetworkCallback(request, HostCallback())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Private DNS host watch failed (${e.javaClass.simpleName})")
        }
        try {
            registrar.registerDefaultNetworkCallback(DefaultNetworkCallback())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Private DNS default-network watch failed (${e.javaClass.simpleName})")
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private inner class HostCallback : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val name: String? = linkProperties.privateDnsServerName
            synchronized(lock) {
                hostNames.remove(network)
                if (!name.isNullOrBlank()) hostNames[network] = name
                hostState.value = hostNames.values.lastOrNull()
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                hostNames.remove(network)
                hostState.value = hostNames.values.lastOrNull()
            }
        }
    }

    private inner class DefaultNetworkCallback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                startOverIfDifferent(network)
                publish()
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            synchronized(lock) {
                startOverIfDifferent(network)
                trackedIsVpn = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                publish()
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            synchronized(lock) {
                startOverIfDifferent(network)
                trackedHasDns = linkProperties.dnsServers.isNotEmpty()
                publish()
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (network != trackedNetwork) return
                track(null)
                publish()
            }
        }

        private fun startOverIfDifferent(network: Network) {
            if (network != trackedNetwork) track(network)
        }

        private fun track(network: Network?) {
            trackedNetwork = network
            trackedIsVpn = false
            trackedHasDns = false
        }

        private fun publish() {
            vpnHasDnsServerState.value = trackedIsVpn && trackedHasDns
        }
    }
}

private const val TAG = "PrivateDnsWatcher"
