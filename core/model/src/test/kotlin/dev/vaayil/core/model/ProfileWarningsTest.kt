package dev.vaayil.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** T-1 (spec U9) and A4: the address-family warnings are advice, never validation errors. */
class ProfileWarningsTest {
    private val mismatch = "This profile's addresses don't match its Allowed IPs, so it won't carry traffic."

    private val bypassV6 = "Some IPv6 traffic will bypass this tunnel. Add ::/0 to Allowed IPs to keep it in."
    private val bypassV4 = "Some IPv4 traffic will bypass this tunnel. Add 0.0.0.0/0 to Allowed IPs to keep it in."
    private val dnsBypass = "DNS lookups will bypass this tunnel. Add a DNS server to keep them in."

    /**
     * Public IPv4 ranges only, 150 /8-equivalents (just over half of IPv4, no `/0`), plus a
     * redundant DNS host route: `9.9.9.9/32` already sits inside `8.0.0.0/7`.
     */
    private val publicV4 = listOf(
        "1.0.0.0/8", "2.0.0.0/7", "4.0.0.0/6", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4",
        "32.0.0.0/3", "64.0.0.0/3", "128.0.0.0/3", "160.0.0.0/5", "176.0.0.0/4", "9.9.9.9/32",
    )

    private fun profile(
        addresses: List<String>,
        allowedIps: List<String>,
        dnsServers: List<String> = listOf("10.0.0.53", "fd00::53"),
        dns: DnsPolicy = DnsPolicy(),
    ) = Profile(
        id = "p1", name = "Home",
        interfaceConfig = InterfaceConfig(
            privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            addresses = addresses, dnsServers = dnsServers,
            mtu = null, listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
            presharedKey = null, endpoint = "vpn.example.com:51820",
            allowedIps = allowedIps, persistentKeepalive = null,
        ),
        dns = dns,
    )

    @Test
    fun `IPv6 addresses under IPv4-only Allowed IPs warn`() {
        assertEquals(listOf(mismatch), profile(listOf("fd00::2/128"), listOf("0.0.0.0/0")).warnings())
    }

    @Test
    fun `IPv4 addresses under IPv6-only Allowed IPs warn`() {
        assertEquals(listOf(mismatch), profile(listOf("10.0.0.2/32"), listOf("::/0", "fd00::/8")).warnings())
    }

    @Test
    fun `one address in a routed family is enough`() {
        assertEquals(emptyList<String>(), profile(listOf("10.0.0.2/32", "fd00::2/128"), listOf("0.0.0.0/0")).warnings())
        assertEquals(listOf(bypassV4), profile(listOf("fd00::2/128"), listOf("10.0.0.0/8", "::/0")).warnings())
    }

    @Test
    fun `matching families do not warn`() {
        assertEquals(emptyList<String>(), profile(listOf("10.0.0.2/32"), listOf("0.0.0.0/0")).warnings())
    }

    @Test
    fun `an empty list on either side does not warn`() {
        assertEquals(emptyList<String>(), profile(emptyList(), listOf("0.0.0.0/0")).warnings())
        assertEquals(emptyList<String>(), profile(listOf("10.0.0.2/32"), emptyList()).warnings())
    }

    @Test
    fun `a warned profile still validates, so a restore keeps it`() {
        val p = profile(listOf("fd00::2/128"), listOf("0.0.0.0/0"))
        assertEquals(emptyList<String>(), p.validate())
        assertEquals(listOf(mismatch), p.warnings())
    }

    @Test
    fun `IPv4 default fails absent IPv6 closed`() {
        assertEquals(emptyList<String>(), profile(listOf("10.0.0.2/32"), listOf("0.0.0.0/0")).warnings())
    }

    @Test
    fun `dual defaults cover both families`() {
        assertEquals(
            emptyList<String>(),
            profile(listOf("10.0.0.2/32", "fd00::2/128"), listOf("0.0.0.0/0", "::/0")).warnings(),
        )
    }

    @Test
    fun `IPv4 halves warn about absent IPv6`() {
        val p = profile(listOf("10.0.0.2/32"), listOf("0.0.0.0/1", "128.0.0.0/1"))
        assertEquals(listOf(bypassV6), p.warnings())
        assertEquals(emptyList<String>(), p.validate())
    }

    @Test
    fun `public IPv4 export warns about absent IPv6`() {
        val p = profile(listOf("10.0.0.2/32"), publicV4)
        assertEquals(listOf(bypassV6), p.warnings())
        assertEquals(emptyList<String>(), p.validate())
    }

    @Test
    fun `IPv4 default plus partial IPv6 warns`() {
        val p = profile(listOf("10.0.0.2/32", "fd00::2/128"), listOf("0.0.0.0/0", "fd00::/8"))
        assertEquals(listOf(bypassV6), p.warnings())
        assertEquals(emptyList<String>(), p.validate())
    }

    @Test
    fun `IPv6 halves cover all IPv6`() {
        assertEquals(
            emptyList<String>(),
            profile(listOf("10.0.0.2/32", "fd00::2/128"), listOf("0.0.0.0/0", "::/1", "8000::/1")).warnings(),
        )
    }

    @Test
    fun `small split routes are intentional`() {
        assertEquals(emptyList<String>(), profile(listOf("10.0.0.2/32"), listOf("10.0.0.0/8")).warnings())
    }

    @Test
    fun `IPv6 default plus partial IPv4 warns`() {
        val p = profile(listOf("10.0.0.2/32", "fd00::2/128"), listOf("::/0", "10.0.0.0/8"))
        assertEquals(listOf(bypassV4), p.warnings())
        assertEquals(emptyList<String>(), p.validate())
    }

    @Test
    fun `coverage handles duplicates overlaps and host bits`() {
        assertEquals(
            emptyList<String>(),
            profile(listOf("10.0.0.2/32"), listOf("0.0.0.0/2", "0.0.0.0/2", "16.0.0.0/4")).warnings(),
        )
        assertEquals(listOf(bypassV6), profile(listOf("10.0.0.2/32"), listOf("127.1.2.3/1")).warnings())
        assertEquals(
            emptyList<String>(),
            profile(listOf("fd00::2/128"), listOf("::/2", "::/2", "1000::/4")).warnings(),
        )
        assertEquals(listOf(bypassV4), profile(listOf("fd00::2/128"), listOf("8000::1/1")).warnings())
        assertEquals(
            emptyList<String>(),
            profile(listOf("10.0.0.2/32", "fd00::2/128"), listOf("0.0.0.0/0", "8000::/1", "::/1")).warnings(),
        )
        assertEquals(
            listOf(bypassV6, bypassV4),
            profile(listOf("10.0.0.2/32", "fd00::2/128"), listOf("0.0.0.0/1", "::/1")).warnings(),
        )
    }

    @Test
    fun `invalid draft CIDRs never throw or resolve names`() {
        val junk = listOf("", "   ", "vpn.example.com/8", "example.com/0", "10.0.0.0/33", "10.0.0.0/", "::/129")
        assertEquals(emptyList<String>(), profile(listOf("10.0.0.2/32"), junk).warnings())
    }

    @Test
    fun `a full tunnel with no DNS server warns that lookups bypass it`() {
        assertEquals(
            listOf(dnsBypass),
            profile(
                listOf("10.0.0.2/32", "fd00::2/128"),
                listOf("0.0.0.0/0", "::/0"),
                dnsServers = emptyList(),
            ).warnings(),
        )
    }

    @Test
    fun `a DNS server only in an unrouted family still warns`() {
        assertEquals(
            listOf(dnsBypass),
            profile(listOf("10.0.0.2/32"), listOf("0.0.0.0/0"), dnsServers = listOf("fd00::53")).warnings(),
        )
        assertEquals(
            listOf(dnsBypass),
            profile(listOf("fd00::2/128"), listOf("::/0"), dnsServers = listOf("10.0.0.53")).warnings(),
        )
    }

    @Test
    fun `a DNS server in a routed family does not warn`() {
        val addresses = listOf("10.0.0.2/32", "fd00::2/128")
        val allowedIps = listOf("0.0.0.0/0", "::/0")
        assertEquals(
            emptyList<String>(),
            profile(addresses, allowedIps, dnsServers = listOf("10.0.0.53")).warnings(),
        )
        assertEquals(
            emptyList<String>(),
            profile(addresses, allowedIps, dnsServers = listOf("fd00::53")).warnings(),
        )
    }

    @Test
    fun `secure DNS in effect needs no plain server`() {
        assertEquals(
            emptyList<String>(),
            profile(
                listOf("10.0.0.2/32", "fd00::2/128"),
                listOf("0.0.0.0/0", "::/0"),
                dnsServers = emptyList(),
                dns = DnsPolicy(
                    mode = DnsMode.SECURE,
                    secureType = SecureDnsType.DOH,
                    secureEndpoint = "https://9.9.9.9/dns-query",
                ),
            ).warnings(),
        )
    }

    @Test
    fun `secure DNS that is not in effect still warns`() {
        assertEquals(
            listOf(dnsBypass),
            profile(
                listOf("10.0.0.2/32", "fd00::2/128"),
                listOf("0.0.0.0/0", "::/0"),
                dnsServers = emptyList(),
                dns = DnsPolicy(
                    mode = DnsMode.SECURE,
                    secureType = SecureDnsType.DOH,
                    secureEndpoint = " ",
                ),
            ).warnings(),
        )
    }

    @Test
    fun `split-DNS rules without a DNS server still warn`() {
        assertEquals(
            listOf(dnsBypass),
            profile(
                listOf("10.0.0.2/32", "fd00::2/128"),
                listOf("0.0.0.0/0", "::/0"),
                dnsServers = emptyList(),
                dns = DnsPolicy(rules = listOf(DnsSplitRule("corp.example", bypass = true))),
            ).warnings(),
        )
    }

    @Test
    fun `split-DNS rules with any DNS server intercept and do not warn`() {
        assertEquals(
            emptyList<String>(),
            profile(
                listOf("10.0.0.2/32"),
                listOf("0.0.0.0/0"),
                dnsServers = listOf("fd00::53"),
                dns = DnsPolicy(rules = listOf(DnsSplitRule("corp.example", bypass = true))),
            ).warnings(),
        )
    }

    @Test
    fun `a split route with no DNS server does not warn`() {
        assertEquals(
            emptyList<String>(),
            profile(listOf("10.0.0.2/32"), listOf("10.0.0.0/8"), dnsServers = emptyList()).warnings(),
        )
    }

    @Test
    fun `addresses that miss the routes get only the mismatch warning`() {
        assertEquals(
            listOf(mismatch),
            profile(listOf("fd00::2/128"), listOf("0.0.0.0/0"), dnsServers = emptyList()).warnings(),
        )
    }

    @Test
    fun `the DNS warning follows an A4 warning`() {
        assertEquals(
            listOf(bypassV6, dnsBypass),
            profile(
                listOf("10.0.0.2/32"),
                listOf("0.0.0.0/1", "128.0.0.0/1"),
                dnsServers = emptyList(),
            ).warnings(),
        )
    }

    @Test
    fun `a draft with no Allowed IPs does not warn about DNS`() {
        assertEquals(
            emptyList<String>(),
            profile(listOf("10.0.0.2/32"), emptyList(), dnsServers = emptyList()).warnings(),
        )
    }
}
