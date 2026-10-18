package dev.vaayil.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** F6/F20b: the one-line DNS summary mirrors the tunnel's DNS planner (DnsResolver). */
class DnsSummaryTest {
    private fun profile(dnsServers: List<String>, dns: DnsPolicy = DnsPolicy()) = Profile(
        id = "p1", name = "Home",
        interfaceConfig = InterfaceConfig(
            privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            addresses = listOf("10.0.0.2/32"), dnsServers = dnsServers,
            mtu = 1420, listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
            presharedKey = null, endpoint = "vpn.example.com:51820",
            allowedIps = listOf("0.0.0.0/0"), persistentKeepalive = 25,
        ),
        dns = dns,
    )

    @Test
    fun `plain servers show first or all`() {
        val p = profile(listOf("1.1.1.1", "9.9.9.9"))
        assertEquals("1.1.1.1", p.dnsSummary(false))
        assertEquals("1.1.1.1, 9.9.9.9", p.dnsSummary(true))
    }

    @Test
    fun `doh in effect names transport and host`() {
        val p = profile(
            listOf("1.1.1.1", "9.9.9.9"),
            DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOH, secureEndpoint = "https://dns.quad9.net/dns-query"),
        )
        assertEquals("DoH dns.quad9.net", p.dnsSummary(false))
        assertEquals("DoH dns.quad9.net", p.dnsSummary(true))
    }

    @Test
    fun `doh ipv6 host is unbracketed`() {
        val p = profile(
            listOf("1.1.1.1"),
            DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOH, secureEndpoint = "https://[2620:fe::fe]/dns-query"),
        )
        assertEquals("DoH 2620:fe::fe", p.dnsSummary(false))
    }

    @Test
    fun `dot bracketed ipv6 endpoint is unbracketed`() {
        val p = profile(
            listOf("1.1.1.1"),
            DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = "[2620:fe::fe]:853"),
        )
        assertEquals("DoT 2620:fe::fe", p.dnsSummary(false))
    }

    @Test
    fun `dot hostname endpoint unchanged`() {
        val p = profile(
            listOf("1.1.1.1"),
            DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = "dns.quad9.net"),
        )
        assertEquals("DoT dns.quad9.net", p.dnsSummary(false))
    }

    @Test
    fun `secure without a type does not intercept`() {
        val p = profile(
            listOf("1.1.1.1"),
            DnsPolicy(mode = DnsMode.SECURE, secureType = null, secureEndpoint = "https://dns.quad9.net/dns-query"),
        )
        assertEquals("1.1.1.1", p.dnsSummary(false))
    }

    @Test
    fun `tunnel mode with a stale secure endpoint and no servers is null`() {
        val p = profile(
            emptyList(),
            DnsPolicy(mode = DnsMode.TUNNEL, secureType = SecureDnsType.DOH, secureEndpoint = "https://dns.quad9.net/dns-query"),
        )
        assertNull(p.dnsSummary(false))
        assertNull(p.dnsSummary(true))
    }

    @Test
    fun `split rules append plus split when a resolver runs them`() {
        val rules = listOf(DnsSplitRule("corp.example", true))
        val plain = profile(listOf("10.0.0.1"), DnsPolicy(rules = rules))
        assertEquals("10.0.0.1 + split", plain.dnsSummary(false))
        val doh = profile(
            listOf("1.1.1.1"),
            DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOH, secureEndpoint = "https://dns.quad9.net/dns-query", rules = rules),
        )
        assertEquals("DoH dns.quad9.net + split", doh.dnsSummary(false))
    }

    @Test
    fun `split rules without a resolver change nothing`() {
        val p = profile(emptyList(), DnsPolicy(rules = listOf(DnsSplitRule("corp.example", true))))
        assertNull(p.dnsSummary(false))
        assertNull(p.dnsSummary(true))
    }
}
