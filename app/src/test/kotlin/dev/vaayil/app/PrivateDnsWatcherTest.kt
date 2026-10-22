package dev.vaayil.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * T-8 (spec §3.1): the hostname comes only from the link properties of non-VPN networks that
 * carry INTERNET, and the VPN flag needs a default VPN network that lists a DNS server. Every
 * unknown or failed read leaves both flows off.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PrivateDnsWatcherTest {

    private val wifi = ShadowNetwork.newInstance(100)
    private val cell = ShadowNetwork.newInstance(101)
    private val vpn = ShadowNetwork.newInstance(102)
    private val resolver = "10.111.222.3"

    private lateinit var registrar: RecordingRegistrar
    private lateinit var watcher: PrivateDnsWatcher

    @Before
    fun setUp() {
        registrar = RecordingRegistrar()
        watcher = PrivateDnsWatcher(registrar)
    }

    @Test
    @Config(sdk = [27])
    fun `below API 28 nothing registers and the host stays null`() {
        watcher.start()

        assertTrue(registrar.requests.isEmpty())
        assertNull(registrar.hostCallback)
        assertNull(registrar.defaultCallback)
        assertNull(watcher.host.value)
    }

    @Test
    fun `the host request asks for internet networks that are not VPNs`() {
        watcher.start()

        val request = registrar.requests.single()
        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }

    @Test
    fun `the host follows a network's link properties`() {
        watcher.start()

        registrar.hostCallback!!.onLinkPropertiesChanged(wifi, lp("dns.google"))
        assertEquals("dns.google", watcher.host.value)

        registrar.hostCallback!!.onLinkPropertiesChanged(wifi, lp(null))
        assertNull(watcher.host.value)

        registrar.hostCallback!!.onLinkPropertiesChanged(wifi, lp(""))
        assertNull(watcher.host.value)
    }

    @Test
    fun `the host stays while another network carries it and clears once none does`() {
        watcher.start()

        registrar.hostCallback!!.onLinkPropertiesChanged(wifi, lp("dns.google"))
        registrar.hostCallback!!.onLinkPropertiesChanged(cell, lp("dns.google"))
        assertEquals("dns.google", watcher.host.value)

        registrar.hostCallback!!.onLost(wifi)
        assertEquals("dns.google", watcher.host.value)

        registrar.hostCallback!!.onLost(cell)
        assertNull(watcher.host.value)
    }

    @Test
    fun `the most recent name wins while two networks differ`() {
        watcher.start()

        registrar.hostCallback!!.onLinkPropertiesChanged(wifi, lp("dns.google"))
        registrar.hostCallback!!.onLinkPropertiesChanged(cell, lp("one.one.one.one"))
        assertEquals("one.one.one.one", watcher.host.value)

        registrar.hostCallback!!.onLost(cell)
        assertEquals("dns.google", watcher.host.value)
    }

    @Test
    fun `a VPN network's name never counts and neither does its absence`() {
        watcher.start()

        registrar.defaultCallback!!.onLinkPropertiesChanged(vpn, lp("dns.google", resolver))
        assertNull(watcher.host.value)

        registrar.hostCallback!!.onLinkPropertiesChanged(wifi, lp("one.one.one.one"))
        assertEquals("one.one.one.one", watcher.host.value)

        registrar.defaultCallback!!.onLinkPropertiesChanged(vpn, lp(null, resolver))
        assertEquals("one.one.one.one", watcher.host.value)
    }

    @Test
    fun `vpnHasDnsServer needs a VPN default network that lists a DNS server`() {
        watcher.start()

        registrar.defaultCallback!!.onAvailable(vpn)
        registrar.defaultCallback!!.onCapabilitiesChanged(vpn, vpnCaps())
        registrar.defaultCallback!!.onLinkPropertiesChanged(vpn, lp(null, resolver))
        assertTrue(watcher.vpnHasDnsServer.value)

        registrar.defaultCallback!!.onLinkPropertiesChanged(vpn, lp(null))
        assertFalse(watcher.vpnHasDnsServer.value)
    }

    @Test
    fun `a default network that is not a VPN never counts`() {
        watcher.start()

        registrar.defaultCallback!!.onAvailable(wifi)
        registrar.defaultCallback!!.onCapabilitiesChanged(wifi, wifiCaps())
        registrar.defaultCallback!!.onLinkPropertiesChanged(wifi, lp(null, "192.0.2.53"))

        assertFalse(watcher.vpnHasDnsServer.value)
    }

    @Test
    fun `a new default network starts unknown`() {
        watcher.start()

        registrar.defaultCallback!!.onAvailable(vpn)
        registrar.defaultCallback!!.onCapabilitiesChanged(vpn, vpnCaps())
        registrar.defaultCallback!!.onLinkPropertiesChanged(vpn, lp(null, resolver))
        assertTrue(watcher.vpnHasDnsServer.value)

        registrar.defaultCallback!!.onAvailable(wifi)
        assertFalse(watcher.vpnHasDnsServer.value)
    }

    @Test
    fun `losing the default network clears it`() {
        watcher.start()

        registrar.defaultCallback!!.onAvailable(vpn)
        registrar.defaultCallback!!.onCapabilitiesChanged(vpn, vpnCaps())
        registrar.defaultCallback!!.onLinkPropertiesChanged(vpn, lp(null, resolver))
        assertTrue(watcher.vpnHasDnsServer.value)

        registrar.defaultCallback!!.onLost(vpn)
        assertFalse(watcher.vpnHasDnsServer.value)
    }

    @Test
    fun `a registration that throws leaves both off`() {
        val throwing = PrivateDnsWatcher(RecordingRegistrar(SecurityException()))

        throwing.start()

        assertNull(throwing.host.value)
        assertFalse(throwing.vpnHasDnsServer.value)
    }

    /** [lp] with [dns] as its servers; [name] is the hidden Private DNS field. */
    private fun lp(name: String?, vararg dns: String): LinkProperties = LinkProperties().apply {
        setDnsServers(dns.map(InetAddress::getByName))
        LinkProperties::class.java
            .getMethod("setPrivateDnsServerName", String::class.java)
            .invoke(this, name)
    }

    private fun vpnCaps(): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().apply {
        shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        shadowOf(this).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun wifiCaps(): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().apply {
        shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Records what [PrivateDnsWatcher.start] registers; [failure] makes both calls throw it. */
    private class RecordingRegistrar(
        private val failure: RuntimeException? = null,
    ) : NetworkCallbackRegistrar {
        val requests = mutableListOf<NetworkRequest>()
        var hostCallback: ConnectivityManager.NetworkCallback? = null
            private set
        var defaultCallback: ConnectivityManager.NetworkCallback? = null
            private set

        override fun registerNetworkCallback(
            request: NetworkRequest,
            callback: ConnectivityManager.NetworkCallback,
        ) {
            failure?.let { throw it }
            requests += request
            hostCallback = callback
        }

        override fun registerDefaultNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            failure?.let { throw it }
            defaultCallback = callback
        }
    }
}
