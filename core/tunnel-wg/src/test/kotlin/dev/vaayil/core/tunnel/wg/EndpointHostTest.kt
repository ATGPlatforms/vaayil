package dev.vaayil.core.tunnel.wg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointHostTest {
    @Test
    fun `a hostname endpoint yields its host`() {
        assertEquals("vpn.example.com", endpointHostname("vpn.example.com:51820"))
        assertEquals("nx.invalid", endpointHostname("nx.invalid:51820"))
        assertEquals("vpn.example.com", endpointHostname("vpn.example.com.:51820")) // one root dot dropped
        assertEquals("vpn.example.com", endpointHostname(" vpn.example.com:51820 "))
    }

    @Test
    fun `IP literals have nothing to look up`() {
        assertNull(endpointHostname("198.51.100.7:51820"))
        assertNull(endpointHostname("[2001:db8::7]:51820"))
    }

    @Test
    fun `endpoints the backend refuses on its own are left to it`() {
        assertNull(endpointHostname("2001:db8::1:51820")) // unbracketed IPv6 (F17)
        assertNull(endpointHostname("vpn.example.com")) // no port
        assertNull(endpointHostname(":51820"))
        assertNull(endpointHostname(""))
    }
}
