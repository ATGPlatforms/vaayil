package dev.vaayil.core.tunnel.wg

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

private const val TAG = "UnderlyingNetwork"

/** What the S10 selection reads from one network's capabilities. */
internal data class NetworkTraits(
    val isVpn: Boolean,
    val hasInternet: Boolean,
    val validated: Boolean,
    val foreground: Boolean,
    val notRestricted: Boolean,
    val transport: Transport,
) {
    /** Declared in preference order: Ethernet > Wi-Fi > cellular > other. */
    enum class Transport { ETHERNET, WIFI, CELLULAR, OTHER }
}

/**
 * S10: the one underlying network that the bootstrap lookup, the split-DNS bypass and the F33 switch
 * pre-check use. Candidates are the non-VPN networks with INTERNET that are in the foreground and not
 * restricted: binding to a background network needs `CHANGE_NETWORK_STATE`, which Vaayil does not hold,
 * and a restricted network needs system permission (below API 28 every network counts as foreground).
 * A VALIDATED network beats an unvalidated one, then the transport ranks Ethernet > Wi-Fi > cellular >
 * other, and a tie keeps the first in snapshot order (the sort is stable). Null when nothing qualifies.
 */
internal fun <N> selectUnderlying(networks: List<Pair<N, NetworkTraits>>): N? =
    networks
        .filter { (_, traits) -> !traits.isVpn && traits.hasInternet && traits.foreground && traits.notRestricted }
        .sortedWith(
            compareBy<Pair<N, NetworkTraits>>(
                { if (it.second.validated) 0 else 1 },
                { it.second.transport.ordinal },
            ),
        )
        .firstOrNull()
        ?.first

internal fun traitsOf(caps: NetworkCapabilities): NetworkTraits = NetworkTraits(
    isVpn = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
    hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
    validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    // FOREGROUND is public from API 28; below that every network counts as foreground (spec K2).
    foreground = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND),
    notRestricted = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
    transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTraits.Transport.ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTraits.Transport.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTraits.Transport.CELLULAR
        else -> NetworkTraits.Transport.OTHER
    },
)

/**
 * The selected underlying network as the resolver uses it (S10): its DNS servers, and binders that pin a
 * socket to that network (`Network.bindSocket`). A binder returns false when the bind failed; the socket
 * then sends nothing and that attempt is skipped, exactly like a refused protect. [NONE]: no network
 * qualifies, which callers treat as "no underlying DNS servers".
 */
class UnderlyingRoute(
    val dnsServers: List<InetAddress>,
    val bindDatagram: (DatagramSocket) -> Boolean = { true },
    val bindStream: (Socket) -> Boolean = { true },
) {
    companion object {
        val NONE = UnderlyingRoute(emptyList())
    }
}

/** A bind that throws fails that attempt only (S10): logged with the exception class, never its message. */
internal fun <S> skipOnBindFailure(bind: (S) -> Unit): (S) -> Boolean = { socket ->
    try {
        bind(socket)
        true
    } catch (e: Exception) {
        Log.w(TAG, "a resolver socket could not be bound to the underlying network (${e.javaClass.simpleName}); attempt skipped")
        false
    }
}

/** The bootstrap and bypass UDP callback: protect out of the tunnel, then bind to the selected network. */
internal fun protectAndBindDatagram(
    protect: (DatagramSocket) -> Boolean,
    route: UnderlyingRoute,
): (DatagramSocket) -> Boolean = { socket -> protect(socket) && route.bindDatagram(socket) }

/** TCP counterpart of [protectAndBindDatagram]; VpnService.protect has disjoint UDP and TCP overloads. */
internal fun protectAndBindStream(
    protect: (Socket) -> Boolean,
    route: UnderlyingRoute,
): (Socket) -> Boolean = { socket -> protect(socket) && route.bindStream(socket) }

/** Production source of [UnderlyingRoute]: one snapshot of the device's networks, one selection (S10). */
object UnderlyingNetworkProbe {
    @Suppress("DEPRECATION") // allNetworks: a one-shot snapshot is exactly what the selection needs
    fun route(context: Context): UnderlyingRoute {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return UnderlyingRoute.NONE
        val snapshot = cm.allNetworks.mapNotNull { network ->
            cm.getNetworkCapabilities(network)?.let { caps -> network to traitsOf(caps) }
        }
        val network = selectUnderlying(snapshot) ?: return UnderlyingRoute.NONE
        val servers = cm.getLinkProperties(network)?.dnsServers.orEmpty().distinct()
        return UnderlyingRoute(
            dnsServers = servers,
            bindDatagram = skipOnBindFailure<DatagramSocket> { network.bindSocket(it) },
            bindStream = skipOnBindFailure<Socket> { network.bindSocket(it) },
        )
    }
}
