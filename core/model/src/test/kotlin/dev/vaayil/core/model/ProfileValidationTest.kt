package dev.vaayil.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileValidationTest {
    private fun validProfile() = Profile(
        id = "p1", name = "Home",
        interfaceConfig = InterfaceConfig(
            privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
            mtu = 1420, listenPort = null
        ),
        peer = PeerConfig(
            publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
            presharedKey = null, endpoint = "vpn.example.com:51820",
            allowedIps = listOf("0.0.0.0/0"), persistentKeepalive = 25
        ),
    )

    @Test
    fun `valid profile has no errors`() = assertTrue(validProfile().validate().isEmpty())

    @Test
    fun `blank name is an error`() {
        val errors = validProfile().copy(name = "  ").validate()
        assertTrue(errors.any { it.contains("name", ignoreCase = true) })
    }

    @Test
    fun `endpoint without host or port is an error`() {
        val p = validProfile().copy(peer = validProfile().peer.copy(endpoint = "nope"))
        assertTrue(p.validate().any { it.contains("endpoint", ignoreCase = true) })
    }

    @Test
    fun `mtu below 576 is an error`() {
        val p = validProfile().copy(interfaceConfig = validProfile().interfaceConfig.copy(mtu = 100))
        assertTrue(p.validate().any { it.contains("MTU", ignoreCase = true) })
    }

    @Test
    fun `empty allowedIps is an error`() {
        val p = validProfile().copy(peer = validProfile().peer.copy(allowedIps = emptyList()))
        assertTrue(p.validate().any { it.contains("AllowedIPs") })
    }

    @Test
    fun `key length must be 44 base64 chars`() {
        val p = validProfile().copy(interfaceConfig = validProfile().interfaceConfig.copy(privateKey = "short"))
        assertTrue(p.validate().any { it.contains("private key", ignoreCase = true) })
    }


    @Test
    fun `dns entry must parse as hostname or ip`() {
        val p = validProfile().copy(interfaceConfig = validProfile().interfaceConfig.copy(dnsServers = listOf("1 1 1 1")))
        assertTrue(p.validate().any { it.contains("DNS") })
    }

    @Test
    fun `ipv6 allowedIps accepted`() {
        val p = validProfile().copy(peer = validProfile().peer.copy(allowedIps = listOf("0.0.0.0/0", "::/0")))
        assertTrue(p.validate().none { it.contains("AllowedIPs") })
    }

    @Test
    fun `ipv6 interface address accepted`() {
        val p = validProfile().copy(interfaceConfig = validProfile().interfaceConfig.copy(addresses = listOf("fd42:42:42::2/128")))
        assertTrue(p.validate().none { it.contains("address", ignoreCase = true) })
    }

    @Test
    fun `allowedIps hostname entries fail fast without DNS`() {
        val p = validProfile().copy(
            peer = validProfile().peer.copy(allowedIps = listOf("not-an-ip-host.example/24")),
        )
        val errors = p.validate()
        assertTrue(errors.any { it.contains("CIDR") })
    }

    @Test
    fun `malformed dotted quads are rejected as CIDR without a name-service fallback`() {
        // "10.0.0.999" passes a loose shape gate but is not a literal; validation used to
        // hand it to InetAddress.getByName, whose numeric fallback performed DNS (V1).
        listOf("10.0.0.999/24", "1.2.3.4.5/32", "300.1.1.1/32", "10.0.0/24", "10.0.0..1/24").forEach { bad ->
            val p = validProfile().copy(
                peer = validProfile().peer.copy(allowedIps = listOf(bad)),
            )
            assertTrue(
                p.validate().any { it.contains("CIDR") },
                "$bad must be rejected as CIDR",
            )
        }
    }

    @Test
    fun `secure dns requires type and endpoint`() {
        val p = validProfile().copy(dns = DnsPolicy(mode = DnsMode.SECURE))
        assertTrue(p.validate().any { it.contains("Secure DNS type") })
        assertTrue(p.validate().any { it.contains("Secure DNS endpoint") })
    }

    @Test
    fun `doh endpoint must be absolute https`() {
        val p = validProfile().copy(
            dns = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOH, secureEndpoint = "http://dns.example/dns-query"),
        )
        assertTrue(p.validate().any { it.contains("DoH endpoint must be an absolute https URL") })
    }

    @Test
    fun `dot endpoint accepts host or host with port`() {
        listOf("one.one.one.one", "one.one.one.one:853").forEach { ep ->
            val p = validProfile().copy(
                dns = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = ep),
            )
            assertFalse(p.validate().any { it.contains("DoT endpoint") })
        }
        val bad = validProfile().copy(
            dns = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = "one.one.one.one:99999"),
        )
        assertTrue(bad.validate().any { it.contains("DoT endpoint") })
    }

    @Test
    fun `dot endpoint accepts bracketed and bare ipv6 literals`() {
        listOf("[2001:db8::1]:853", "[2001:db8::1]", "2001:db8::1").forEach { ep ->
            val p = validProfile().copy(
                dns = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = ep),
            )
            assertFalse(p.validate().any { it.contains("DoT endpoint") })
        }
    }

    @Test
    fun `dot endpoint rejects ambiguous or malformed ipv6 forms`() {
        listOf("2001:db8::1:853", "[2001:db8::1", "2001:db8::1:89:", "[2001:db8::1]:99999", "[]:853").forEach { ep ->
            val p = validProfile().copy(
                dns = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = ep),
            )
            assertTrue(p.validate().any { it.contains("DoT endpoint") })
        }
    }

    @Test
    fun `split rules reject malformed or duplicate domains`() {
        val p = validProfile().copy(dns = DnsPolicy(rules = listOf(
            DnsSplitRule("not a domain", bypass = true),
            DnsSplitRule("internal.corp", bypass = true),
            DnsSplitRule("INTERNAL.CORP", bypass = false),
        )))
        val errors = p.validate()
        assertTrue(errors.any { it.contains("Split-DNS domain \"not a domain\"") })
        assertTrue(errors.any { it.contains("Duplicate split-DNS domain") })
    }

    @Test
    fun `resolver address is rejected as interface address`() {
        val p = validProfile().copy(
            interfaceConfig = validProfile().interfaceConfig.copy(addresses = listOf("$RESOLVER_V4/32")),
        )
        assertTrue(p.validate().any { it.contains("reserved") })
    }

    @Test
    fun `the resolver address is refused as a DNS server`() {
        val p = validProfile().copy(
            interfaceConfig = validProfile().interfaceConfig.copy(dnsServers = listOf("1.1.1.1", RESOLVER_V4)),
        )
        assertEquals(listOf("DNS server 10.111.222.3 is reserved for Vaayil's resolver."), p.validate())
    }

    @Test
    fun `name longer than 48 characters is rejected`() {
        val p = validProfile().copy(name = "n".repeat(49))
        assertTrue(p.validate().any { it.startsWith("Profile name must be at most") })
        assertTrue(validProfile().copy(name = "n".repeat(48)).validate().isEmpty())
    }

    @Test
    fun `name with control characters is rejected`() {
        val p = validProfile().copy(name = "Home\nPostUp = evil")
        assertTrue(p.validate().any { it.contains("control characters") })
    }

    @Test
    fun `dns servers must be ip literals`() {
        val p = validProfile().copy(interfaceConfig = validProfile().interfaceConfig.copy(dnsServers = listOf("10.0.0.1", "dns.example.com")))
        val errors = p.validate()
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("dns.example.com"))
        assertTrue(validProfile().copy(interfaceConfig = validProfile().interfaceConfig.copy(dnsServers = listOf("10.0.0.1", "2606:4700:4700::1111"))).validate().isEmpty())
    }

    @Test
    fun `doh endpoint rejects userinfo loopback link-local and non-https`() {
        assertEquals("DoH endpoint must not contain a username or password.", dohEndpointProblem("https://dns.google@evil.example/dns-query"))
        assertEquals("DoH endpoint must not point at this device or a link-local address.", dohEndpointProblem("https://127.0.0.1:8443/dns-query"))
        assertEquals("DoH endpoint must not point at this device or a link-local address.", dohEndpointProblem("https://169.254.169.254/dns-query"))
        assertEquals("DoH endpoint must not point at this device or a link-local address.", dohEndpointProblem("https://[::1]/dns-query"))
        assertEquals("DoH endpoint must be an absolute https URL.", dohEndpointProblem("http://dns.example/dns-query"))
        assertNull(dohEndpointProblem("https://dns.example/dns-query"))
        assertNull(dohEndpointProblem("https://1.1.1.1/dns-query"))
    }

    @Test
    fun `isIpLiteral never treats a hostname as an address`() {
        assertTrue(isIpLiteral("9.9.9.9"))
        assertTrue(isIpLiteral("2001:db8::1"))
        assertFalse(isIpLiteral("dns.example.com"))
        assertFalse(isIpLiteral("10.0.0.999"))
        assertFalse(isIpLiteral(""))
    }

    @Test
    fun `ipv4-mapped and zone-scoped ipv6 forms are never literals`() {
        listOf("::ffff:127.0.0.1", "::ffff:7f00:1", "0:0:0:0:0:ffff:7f00:1", "fe80::1%25eth0", "::1.2.3.4").forEach {
            assertFalse(isIpLiteral(it), "$it must not be a literal")
        }
        listOf("2001:db8::1", "::1", "::", "fe80::1", "1.2.3.4").forEach {
            assertTrue(isIpLiteral(it), "$it must be a literal")
        }
    }

    @Test
    fun `doh endpoint refuses ipv4-mapped and zone-scoped hosts`() {
        listOf(
            "https://[::ffff:127.0.0.1]/dns-query",
            "https://[::ffff:7f00:1]/dns-query",
            "https://[fe80::1%25eth0]/dns-query",
        ).forEach { assertTrue(dohEndpointProblem(it) != null, "$it must be refused") }
        assertNull(dohEndpointProblem("https://[2001:db8::1]/dns-query"))
    }

    @Test
    fun `dot endpoint refuses digits-and-dots strings that are not ipv4`() {
        assertNull(parseDotEndpoint("999.1.1.1"))
        assertNull(parseDotEndpoint("1.2.3"))
        assertNull(parseDotEndpoint("999.1.1.1:853"))
        assertEquals("1.2.3.4" to 853, parseDotEndpoint("1.2.3.4"))
    }

    @Test
    fun `keys must end in a canonical base64 character`() {
        val bad = validProfile().copy(interfaceConfig = validProfile().interfaceConfig.copy(privateKey = "A".repeat(42) + "B="))
        assertTrue(bad.validate().any { it.contains("Private key") })
        val good = validProfile().copy(interfaceConfig = validProfile().interfaceConfig.copy(privateKey = "A".repeat(43) + "="))
        assertTrue(good.validate().none { it.contains("Private key") })
    }

    @Test
    fun `endpoint port keepalive preshared key and id are bounded`() {
        val base = validProfile()
        assertTrue(base.copy(peer = base.peer.copy(endpoint = "vpn.example.com:99999")).validate().any { it.contains("Endpoint port") })
        assertTrue(base.copy(peer = base.peer.copy(persistentKeepalive = 70000)).validate().any { it.contains("keepalive") })
        assertTrue(base.copy(peer = base.peer.copy(persistentKeepalive = -1)).validate().any { it.contains("keepalive") })
        assertTrue(base.copy(peer = base.peer.copy(presharedKey = "\n")).validate().any { it.contains("Preshared key") })
        assertTrue(base.copy(id = "a#b").validate().any { it.contains("Profile id") })
        assertTrue(base.copy(id = "").validate().any { it.contains("Profile id") })
        assertTrue(base.copy(id = "3f2b7c1e-0d4a-4f6b-9a1c-2e5d8b7a6c0f").validate().none { it.contains("Profile id") })
    }

    private fun withEndpoint(e: String) = validProfile().let { it.copy(peer = it.peer.copy(endpoint = e)) }
    private fun endpointErrors(e: String) = withEndpoint(e).validate().filter { it.startsWith("Endpoint") }

    @Test
    fun `accepted endpoint forms`() {
        for (ok in listOf(
            "vpn.example.com:51820", "vpn.example.com.:51820", "203.0.113.7:51820",
            "[2001:db8::1]:51820", "[::1]:1", "e:65535",
        )) assertEquals(emptyList(), endpointErrors(ok), ok)
    }

    @Test
    fun `an unbracketed ipv6 endpoint is refused with a bracket hint`() {
        for (bad in listOf("2001:db8::1:51820", "2001:db8::1", "fd00::1:443")) {
            assertEquals(
                listOf("Endpoint must put an IPv6 address in brackets, e.g. [2001:db8::1]:51820."),
                endpointErrors(bad), bad,
            )
        }
    }

    @Test
    fun `only an unambiguous unbracketed IPv6 endpoint is bracketed`() {
        assertEquals("[2001:db8::1]:51820", bracketIpv6Endpoint("2001:db8::1:51820"))
        assertEquals("[::1]:51820", bracketIpv6Endpoint("::1:51820"))
        assertEquals("[2001:db8:0:0:0:0:0:1]:51820", bracketIpv6Endpoint("2001:db8:0:0:0:0:0:1:51820"))
        for (unchanged in listOf(
            "2001:db8::1:853",        // four digits or fewer: could be the address's own last group
            "2001:db8::1:518200",     // six digits
            "2001:db8::1",            // no port at all
            "[2001:db8::1]:51820",    // already bracketed
            "vpn.example.com:51820",
            "203.0.113.7:51820",
            "fe80::1%wlan0:51820",    // a zone id is never a literal
            "::ffff:7f00:1:51820",    // an IPv4-mapped head is never a literal
            "2001:db8::1:5182\u0660", // an Arabic-Indic digit in the port
        )) {
            assertEquals(unchanged, bracketIpv6Endpoint(unchanged), unchanged)
        }
    }

    @Test
    fun `malformed endpoint hosts are refused`() {
        for (bad in listOf("999.1.1.1:51820", "[vpn.example.com]:51820", "[2001:db8::1]51820", "-vpn.example.com:51820", ":51820", "nope", "vpn.example.com:\u0665\u0661\u0668\u0662\u0660", // Arabic-Indic port digits: isDigit() says yes, the backend says no
            "\u0661.\u0661.\u0661.\u0661:51820",               // Arabic-Indic IPv4 digits
            "[2001:db8::\u0661]:51820",                        // Arabic-Indic digit in an IPv6 group
        )) {
            assertEquals(listOf("Endpoint must be host:port (got \"$bad\")."), endpointErrors(bad), bad)
        }
    }

    @Test
    fun `endpoint ports are still bounded`() {
        assertEquals(listOf("Endpoint port must be 1-65535."), endpointErrors("vpn.example.com:0"))
        assertEquals(listOf("Endpoint port must be 1-65535."), endpointErrors("[2001:db8::1]:70000"))
    }

    @Test
    fun `a DoH scheme is case-insensitive`() {
        assertNull(dohEndpointProblem("HTTPS://dns.example/dns-query"))
        assertNull(dohEndpointProblem("Https://1.1.1.1/dns-query"))
        assertEquals("DoH endpoint must be an absolute https URL.", dohEndpointProblem("http://dns.example/dns-query"))
    }

    @Test
    fun `imported names are normalized and never blank`() {
        assertEquals("x".repeat(48), importedProfileName("x".repeat(60)))
        assertEquals("Office", importedProfileName("  Off\u0007ice \n"))
        assertEquals("Imported", importedProfileName("\u0000  "))
        assertEquals("", normalizeProfileName("   "))
    }

    @Test
    fun `the ipv6 resolver address is rejected as an interface address in any spelling`() {
        val iface = validProfile().interfaceConfig
        listOf("fdcb:b743:7f0b::3/128", "FDCB:B743:7F0B:0:0:0:0:3/128", "fdcb:b743:7f0b:0000::0003/64").forEach { address ->
            val p = validProfile().copy(interfaceConfig = iface.copy(addresses = listOf("10.0.0.2/32", address)))
            assertEquals(listOf("Interface address fdcb:b743:7f0b::3 is reserved for Vaayil's DNS resolver."), p.validate(), address)
        }
    }

    @Test
    fun `the ipv6 resolver address is rejected as a dns server in any spelling`() {
        val iface = validProfile().interfaceConfig
        listOf("fdcb:b743:7f0b::3", "FDCB:B743:7F0B::0003", "fdcb:b743:7f0b:0:0:0:0:3").forEach { server ->
            val p = validProfile().copy(interfaceConfig = iface.copy(dnsServers = listOf("1.1.1.1", server)))
            assertEquals(listOf("DNS server fdcb:b743:7f0b::3 is reserved for Vaayil's resolver."), p.validate(), server)
        }
    }

    @Test
    fun `the ipv4 resolver address stays rejected in any spelling`() {
        val iface = validProfile().interfaceConfig
        val asAddress = validProfile().copy(interfaceConfig = iface.copy(addresses = listOf("010.111.222.003/32")))
        assertEquals(listOf("Interface address 10.111.222.3 is reserved for Vaayil's DNS resolver."), asAddress.validate())
        val asServer = validProfile().copy(interfaceConfig = iface.copy(dnsServers = listOf("10.111.222.03")))
        assertEquals(listOf("DNS server 10.111.222.3 is reserved for Vaayil's resolver."), asServer.validate())
    }

    @Test
    fun `addresses next to the resolver addresses are not reserved`() {
        val iface = validProfile().interfaceConfig
        val p = validProfile().copy(
            interfaceConfig = iface.copy(
                addresses = listOf("10.111.222.4/32", "fdcb:b743:7f0b::4/128"),
                dnsServers = listOf("10.111.222.2", "fdcb:b743:7f0b::2"),
            ),
        )
        assertEquals(emptyList(), p.validate())
    }
}
