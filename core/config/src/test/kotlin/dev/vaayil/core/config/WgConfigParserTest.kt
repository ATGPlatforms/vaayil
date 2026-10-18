package dev.vaayil.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assert.assertThrows

class WgConfigParserTest {
    private val parser = WgConfigParser()

    @Test
    fun `parses full config`() {
        val cfg = parser.parse(javaClass.getResource("/golden/realworld1.conf")!!.readText())
        assertEquals("eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=", cfg.interfaceSection.privateKey)
        assertEquals(listOf("10.66.66.2/32", "fd42:42:42::2/128"), cfg.interfaceSection.addresses)
        // The hostname entry is a wg-quick search domain, which Vaayil does not support: dropped.
        assertEquals(listOf("10.66.66.1"), cfg.interfaceSection.dnsServers)
        assertEquals(listOf("dns.example.com"), cfg.ignoredDnsEntries)
        assertEquals(1420, cfg.interfaceSection.mtu)
        assertNull(cfg.interfaceSection.listenPort)
        assertEquals("KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=", cfg.peer.publicKey)
        assertEquals("PggggggggggggggggggggggggggggggggggggggggggggggA=", cfg.peer.presharedKey)
        assertEquals(listOf("0.0.0.0/0", "::/0"), cfg.peer.allowedIps)
        assertEquals("203.0.113.7:51820", cfg.peer.endpoint)
        assertEquals(25, cfg.peer.persistentKeepalive)
    }

    @Test
    fun `parses minimal config with defaults`() {
        val cfg = parser.parse(javaClass.getResource("/golden/realworld2.conf")!!.readText())
        assertEquals(emptyList(), cfg.interfaceSection.dnsServers)
        assertNull(cfg.interfaceSection.mtu)
        assertNull(cfg.peer.presharedKey)
        assertNull(cfg.peer.persistentKeepalive)
        assertEquals("vpn.example.com:443", cfg.peer.endpoint)
    }

    @Test
    fun `missing interface section fails with line 0`() {
        val e = assertFailsWith<WgConfigParser.ParseException> { parser.parse("[Peer]\nPublicKey = A\n") }
        assertEquals(0, e.lineNo)
        assertEquals("That doesn't look like a WireGuard config.", e.message)
    }

    @Test
    fun `missing peer section fails`() {
        val e = assertFailsWith<WgConfigParser.ParseException> { parser.parse("[Interface]\nPrivateKey = A\n") }
        assertTrue(e.message!!.contains("Peer"))
    }

    @Test
    fun `unsupported directive names its line`() {
        val e = assertFailsWith<WgConfigParser.ParseException> {
            parser.parse(javaClass.getResource("/golden/unsupported-directives.conf")!!.readText())
        }
        assertTrue(e.message!!.contains("preup", ignoreCase = true))
        assertFalse(e.message!!.contains("echo hello")) // the offending line is never echoed back
        assertEquals(4, e.lineNo)
    }

    @Test
    fun `duplicate keys fail`() {
        val text = "[Interface]\nPrivateKey = A\nPrivateKey = B\n\n[Peer]\nPublicKey = K\nAllowedIPs = 0.0.0.0/0\nEndpoint = h:1\n"
        assertFailsWith<WgConfigParser.ParseException> { parser.parse(text) }
    }

    @Test
    fun `comments and whitespace are tolerated`() {
        val text = "# comment\n\n[Interface]\n  PrivateKey = A\n\tAddress = 10.0.0.2/24\n\n[Peer]\nPublicKey = K\nAllowedIPs = 0.0.0.0/0\nEndpoint = h:1\n"
        val cfg = parser.parse(text)
        assertEquals(listOf("10.0.0.2/24"), cfg.interfaceSection.addresses)
    }

    @Test
    fun `empty value fails`() {
        val text = "[Interface]\nPrivateKey =\n\n[Peer]\nPublicKey = K\nAllowedIPs = 0.0.0.0/0\nEndpoint = h:1\n"
        assertFailsWith<WgConfigParser.ParseException> { parser.parse(text) }
    }

    @Test
    fun `non-ip DNS entries are ignored as unsupported search domains`() {
        val text = """
            [Interface]
            PrivateKey = ${"A".repeat(43)}=
            Address = 10.0.0.2/32
            DNS = 10.0.0.1, corp.example.com, 2606:4700:4700::1111
            [Peer]
            PublicKey = ${"B".repeat(43)}=
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
        """.trimIndent()
        assertEquals(listOf("10.0.0.1", "2606:4700:4700::1111"), WgConfigParser().parse(text).interfaceSection.dnsServers)
    }

    @Test
    fun `parse errors never echo the offending line`() {
        val secret = "S".repeat(43) + "="
        val e = assertThrows(WgConfigParser.ParseException::class.java) {
            WgConfigParser().parse("PrivateKey = $secret\n[Interface]\n")
        }
        assertFalse(e.message!!.contains(secret))
        assertEquals("Key outside any [Section] (line 1)", e.message)
        val e2 = assertThrows(WgConfigParser.ParseException::class.java) {
            WgConfigParser().parse("[Interface]\nPrivateKey $secret\n")
        }
        assertFalse(e2.message!!.contains(secret))
        assertEquals("Expected \"Key = Value\" (line 2)", e2.message)
    }

    @Test
    fun `a key pasted into the key slot is never echoed by an error`() {
        // A key pasted WITHOUT its "Key = " prefix has no whitespace, so it reaches the key
        // slot and every message that names the key would echo it back to the import screen.
        val bare = "A".repeat(43) + "="
        val e = assertThrows(WgConfigParser.ParseException::class.java) {
            WgConfigParser().parse("[Interface]\n$bare\n")
        }
        assertEquals("Empty value (line 2)", e.message)
        assertFalse(e.message!!.contains(bare.dropLast(1)))
        assertFalse(e.message!!.contains(bare.dropLast(1).lowercase()))

        val secret = "S".repeat(43) + "="
        val dup = assertThrows(WgConfigParser.ParseException::class.java) {
            WgConfigParser().parse("[Interface]\nPrivateKey = $secret\nPrivateKey = ${"B".repeat(43)}=\n")
        }
        assertEquals("Duplicate key \"privatekey\" (line 3)", dup.message)
        assertFalse(dup.message!!.contains(secret))

        val unsupported = assertThrows(WgConfigParser.ParseException::class.java) {
            WgConfigParser().parse("[Interface]\n$bare x\n")
        }
        assertEquals("Unsupported [Interface] directive (line 2)", unsupported.message)
        assertFalse(unsupported.message!!.contains(bare.dropLast(1)))
    }

    @Test
    fun `inline comments are stripped from values`() {
        val text = """
            [Interface]
            PrivateKey = ${"A".repeat(43)}= # laptop
            Address = 10.0.0.2/32 # office
            DNS = 10.8.0.1 # internal resolver
            MTU = 1420 # per provider
            [Peer]
            PublicKey = ${"B".repeat(43)}=
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
            PersistentKeepalive = 25 # keep the NAT mapping open
        """.trimIndent()
        val cfg = WgConfigParser().parse(text)
        assertEquals("A".repeat(43) + "=", cfg.interfaceSection.privateKey)
        assertEquals(listOf("10.0.0.2/32"), cfg.interfaceSection.addresses)
        assertEquals(listOf("10.8.0.1"), cfg.interfaceSection.dnsServers)
        assertEquals(1420, cfg.interfaceSection.mtu)
        assertEquals(25, cfg.peer.persistentKeepalive)
    }

    @Test
    fun `a numeric directive that does not parse fails loud with its line`() {
        val text = "[Interface]\nPrivateKey = A\nMTU = 1420abc\n\n[Peer]\nPublicKey = K\nAllowedIPs = 0.0.0.0/0\nEndpoint = h:1\n"
        val e = assertFailsWith<WgConfigParser.ParseException> { WgConfigParser().parse(text) }
        assertEquals(3, e.lineNo)
        assertEquals("Invalid number \"mtu\" (line 3)", e.message)
    }

    private val minimal = """
        [Interface]
        PrivateKey = ${"A".repeat(43)}=
        Address = 10.0.0.2/32
        [Peer]
        PublicKey = ${"B".repeat(43)}=
        AllowedIPs = 0.0.0.0/0
        Endpoint = vpn.example.com:51820
    """.trimIndent()

    @Test
    fun `a utf-8 byte-order mark is ignored`() {
        val cfg = parser.parse("\uFEFF" + minimal)
        assertEquals("vpn.example.com:51820", cfg.peer.endpoint)
    }

    @Test
    fun `a second peer is refused in plain words`() {
        val text = minimal + "\n[Peer]\nPublicKey = ${"C".repeat(43)}=\nAllowedIPs = 10.0.0.0/8\nEndpoint = b.example.com:51820\n"
        val e = assertFailsWith<WgConfigParser.ParseException> { parser.parse(text) }
        assertEquals("Vaayil supports one [Peer] per profile; this config has another at line 8.", e.message)
        assertEquals(8, e.lineNo)
    }

    @Test
    fun `a second interface section is refused`() {
        val text = minimal + "\n[Interface]\nMTU = 1280\n"
        val e = assertFailsWith<WgConfigParser.ParseException> { parser.parse(text) }
        assertEquals("Duplicate [Interface] section (line 8)", e.message)
    }

    @Test
    fun `a peer without an endpoint names the missing key`() {
        val e = assertFailsWith<WgConfigParser.ParseException> { parser.parse(minimal.lines().dropLast(1).joinToString("\n")) }
        assertEquals("Missing Endpoint in [Peer]", e.message)
    }

    @Test
    fun `a peer without allowed ips names the missing key`() {
        val e = assertFailsWith<WgConfigParser.ParseException> {
            parser.parse(minimal.lines().filterNot { it.startsWith("AllowedIPs") }.joinToString("\n"))
        }
        assertEquals("Missing AllowedIPs in [Peer]", e.message)
    }

    @Test
    fun `search domains are reported, not silently dropped`() {
        val text = minimal.replace("Address = 10.0.0.2/32", "Address = 10.0.0.2/32\nDNS = 1.1.1.1, dns.example.com, internal.corp")
        val cfg = parser.parse(text)
        assertEquals(listOf("1.1.1.1"), cfg.interfaceSection.dnsServers)
        assertEquals(listOf("dns.example.com", "internal.corp"), cfg.ignoredDnsEntries)
    }

    private val notAConfig = "That doesn't look like a WireGuard config."

    @Test
    fun `a file with a NUL byte is not a config`() {
        val e = assertFailsWith<WgConfigParser.ParseException> { parser.parse(minimal + "\n\u0000\u0000") }
        assertEquals(notAConfig, e.message)
        assertEquals(0, e.lineNo)
    }

    @Test
    fun `a UTF-16 file read as UTF-8 is not a config`() {
        // Its byte-order mark decodes to U+FFFD, and every other byte is a NUL.
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + minimal.toByteArray(Charsets.UTF_16LE)
        val e = assertFailsWith<WgConfigParser.ParseException> { parser.parse(String(utf16, Charsets.UTF_8)) }
        assertEquals(notAConfig, e.message)
    }

    @Test
    fun `binary junk after a valid interface is not a config`() {
        val junk = String(byteArrayOf(0xC3.toByte(), 0x28, 0xA0.toByte(), 0xA1.toByte()), Charsets.UTF_8)
        val text = minimal.replace("Address = 10.0.0.2/32", "Address = 10.0.0.2/32\n$junk")
        val e = assertFailsWith<WgConfigParser.ParseException> { parser.parse(text) }
        assertEquals(notAConfig, e.message)
    }

    @Test
    fun `text without an interface header is not a config`() {
        val e = assertFailsWith<WgConfigParser.ParseException> { parser.parse("Hello,\nthis is a shopping list.\n") }
        assertEquals(notAConfig, e.message)
        // A header inside a comment does not count: the parser strips everything after '#'.
        val commented = assertFailsWith<WgConfigParser.ParseException> {
            parser.parse(minimal.replace("[Interface]", "# [Interface]"))
        }
        assertEquals(notAConfig, commented.message)
    }

    /** [text] with its one '@' replaced by the single byte [byte], read as UTF-8 the way an import reads a file. */
    private fun withByte(text: String, byte: Int): String =
        String(text.substringBefore('@').toByteArray() + byte.toByte() + text.substringAfter('@').toByteArray(), Charsets.UTF_8)

    @Test
    fun `a Latin-1 comment line still imports`() {
        // A comment saved in Latin-1 ("Zurich" with its u-umlaut, byte 0xFC) is not UTF-8: it reads as U+FFFD.
        val cfg = parser.parse(withByte("# Z@rich exit\n$minimal", 0xFC))
        assertEquals("vpn.example.com:51820", cfg.peer.endpoint)
    }

    @Test
    fun `a Latin-1 comment after a value still imports`() {
        // "cafe" with its e-acute saved in Latin-1 (byte 0xE9), in a comment after the value.
        val text = minimal.replace("Endpoint = vpn.example.com:51820", "Endpoint = vpn.example.com:51820 # caf@")
        val cfg = parser.parse(withByte(text, 0xE9))
        assertEquals("vpn.example.com:51820", cfg.peer.endpoint)
    }

    @Test
    fun `a byte that is not UTF-8 outside a comment is still not a config`() {
        // Only comments are exempt: in a value or a key it is text the parser would read.
        val inValue = minimal.replace("Endpoint = vpn.example.com:51820", "Endpoint = vpn.example.com:5182@")
        val value = assertFailsWith<WgConfigParser.ParseException> { parser.parse(withByte(inValue, 0xFC)) }
        assertEquals(notAConfig, value.message)
        val key = assertFailsWith<WgConfigParser.ParseException> {
            parser.parse(withByte(minimal.replace("Address = ", "Addr@ss = "), 0xE9))
        }
        assertEquals(notAConfig, key.message)
    }

    @Test
    fun `a bad line in a real config still names its line`() {
        val e = assertFailsWith<WgConfigParser.ParseException> { parser.parse("garbage\n$minimal") }
        assertEquals("Key outside any [Section] (line 1)", e.message)
    }
}
