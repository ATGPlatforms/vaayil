// The shadow's addNetwork still takes a NetworkInfo, which Android deprecated; the whole
// test file suppresses that one deprecation.
@file:Suppress("DEPRECATION")

package dev.vaayil.core.tunnel.wg

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/** S10: the probe reads one snapshot, uses only the selected network's DNS servers and binds to it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UnderlyingNetworkProbeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cm: ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)

    @Before
    fun noNetworks() {
        shadowOf(cm).clearAllNetworks()
    }

    private fun network(
        netId: Int,
        transport: Int,
        vpn: Boolean,
        vararg dns: String,
        validated: Boolean = true,
        foreground: Boolean = true,
        restricted: Boolean = false,
    ): Network {
        val network = ShadowNetwork.newInstance(netId)
        shadowOf(cm).addNetwork(
            network,
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED,
            ),
        )
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(transport)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) {
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        if (foreground) {
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        }
        // A fresh NetworkCapabilities carries NOT_RESTRICTED; set it explicitly both ways.
        if (restricted) {
            shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        } else {
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        }
        if (vpn) {
            shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } else {
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        shadowOf(cm).setNetworkCapabilities(network, caps)
        shadowOf(cm).setLinkProperties(
            network,
            LinkProperties().apply { setDnsServers(dns.map { InetAddress.getByName(it) }) },
        )
        return network
    }

    @Test
    fun `only the selected network's DNS servers are used and sockets bind to that network`() {
        network(100, NetworkCapabilities.TRANSPORT_VPN, vpn = true, "10.0.0.53")
        val cell = network(101, NetworkCapabilities.TRANSPORT_CELLULAR, vpn = false, "198.51.100.53")
        val wifi = network(102, NetworkCapabilities.TRANSPORT_WIFI, vpn = false, "192.0.2.53", "192.0.2.54")

        val route = UnderlyingNetworkProbe.route(context)

        assertEquals(listOf("192.0.2.53", "192.0.2.54"), route.dnsServers.map { it.hostAddress })
        DatagramSocket().use { udp ->
            assertTrue(route.bindDatagram(udp))
            assertTrue(shadowOf(wifi).isSocketBound(udp))
            assertFalse(shadowOf(cell).isSocketBound(udp))
        }
        Socket().use { tcp ->
            assertTrue(route.bindStream(tcp))
            assertTrue(shadowOf(wifi).isSocketBound(tcp))
        }
    }

    @Test
    fun `no qualifying network gives no servers`() {
        network(100, NetworkCapabilities.TRANSPORT_VPN, vpn = true, "10.0.0.53")
        assertTrue(UnderlyingNetworkProbe.route(context).dnsServers.isEmpty())
    }

    @Test
    fun `a validated background network loses to the foreground network that carries the tunnel`() {
        val wifi = network(102, NetworkCapabilities.TRANSPORT_WIFI, vpn = false, "192.0.2.53", validated = false)
        val cell = network(101, NetworkCapabilities.TRANSPORT_CELLULAR, vpn = false, "198.51.100.53", foreground = false)

        val route = UnderlyingNetworkProbe.route(context)

        assertEquals(listOf("192.0.2.53"), route.dnsServers.map { it.hostAddress })
        // The foreground network takes the bind; the background one is never selected.
        DatagramSocket().use { udp ->
            assertTrue(route.bindDatagram(udp))
            assertTrue(shadowOf(wifi).isSocketBound(udp))
            assertFalse(shadowOf(cell).isSocketBound(udp))
        }
    }

    @Test
    fun `a validated network beats a better transport at probe level`() {
        network(101, NetworkCapabilities.TRANSPORT_CELLULAR, vpn = false, "198.51.100.53")
        network(102, NetworkCapabilities.TRANSPORT_WIFI, vpn = false, "192.0.2.53", validated = false)

        val route = UnderlyingNetworkProbe.route(context)

        assertEquals(listOf("198.51.100.53"), route.dnsServers.map { it.hostAddress })
    }

    @Test
    fun `a restricted network is never selected`() {
        network(102, NetworkCapabilities.TRANSPORT_WIFI, vpn = false, "192.0.2.53", restricted = true)
        network(101, NetworkCapabilities.TRANSPORT_CELLULAR, vpn = false, "198.51.100.53")

        val route = UnderlyingNetworkProbe.route(context)

        assertEquals(listOf("198.51.100.53"), route.dnsServers.map { it.hostAddress })
    }

    @Test
    @Config(sdk = [26])
    fun `below API 28 every network counts as foreground`() {
        network(101, NetworkCapabilities.TRANSPORT_CELLULAR, vpn = false, "198.51.100.53", foreground = false)

        val route = UnderlyingNetworkProbe.route(context)

        assertEquals(listOf("198.51.100.53"), route.dnsServers.map { it.hostAddress })
    }
}
