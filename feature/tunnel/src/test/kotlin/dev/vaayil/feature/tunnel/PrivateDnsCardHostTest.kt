package dev.vaayil.feature.tunnel

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T-8 (spec §3.2): the dashboard shows the Private DNS card only for a live TLS hostname. */
class PrivateDnsCardHostTest {
    private fun ui(
        phase: ConnectionPhase = ConnectionPhase.CONNECTED,
        host: String? = "dns.google",
        vpnDns: Boolean = true,
        dismissed: String? = null,
    ) = HomeUiState(
        status = TunnelStatus(phase, "p1"),
        privateDnsHost = host,
        vpnHasDnsServer = vpnDns,
        privateDnsDismissedHost = dismissed,
    )

    @Test
    fun `the card names the host while connected with a VPN DNS server`() =
        assertEquals("dns.google", privateDnsCardHost(ui()))

    @Test
    fun `no card without a host`() = assertNull(privateDnsCardHost(ui(host = null)))

    @Test
    fun `no card when the VPN network lists no DNS server`() = assertNull(privateDnsCardHost(ui(vpnDns = false)))

    @Test
    fun `no card outside CONNECTED`() {
        for (p in listOf(
            ConnectionPhase.CONNECTING,
            ConnectionPhase.DISCONNECTING,
            ConnectionPhase.FAILED,
            ConnectionPhase.DISCONNECTED,
        )) {
            assertNull(privateDnsCardHost(ui(phase = p)), "$p")
        }
    }

    @Test
    fun `Got it hides the card for that host in any letter case`() {
        assertNull(privateDnsCardHost(ui(dismissed = "dns.google")))
        assertNull(privateDnsCardHost(ui(dismissed = "DNS.Google")))
        assertNull(privateDnsCardHost(ui(host = "DNS.Google", dismissed = "dns.google")))
    }

    @Test
    fun `a different host shows the card again`() =
        assertEquals("dns.google", privateDnsCardHost(ui(dismissed = "one.one.one.one")))
}
