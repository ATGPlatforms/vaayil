package dev.vaayil.core.data

import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelSelections
import dev.vaayil.core.model.validate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCodecTest {
    private fun profile(id: String = "p1") = Profile(
        id, "Home",
        InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        PeerConfig("K", null, "h:51820", listOf("0.0.0.0/0"), 25),
    )

    private fun modelProfile() = Profile(
        id = "p1", name = "Home",
        interfaceConfig = InterfaceConfig(
            KEY44, listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig(KEY44, null, "vpn.example.com:51820", listOf("0.0.0.0/0"), 25),
    )

    @Test
    fun `backup v1 payload still decodes and carries no global rules`() {
        val v1Json = """{"version":1,"profiles":[{"id":"p1","name":"home","privateKey":"$KEY44",
            "addresses":["10.0.0.2/32"],"dnsServers":["1.1.1.1"],"peerPublicKey":"$KEY44",
            "endpoint":"vpn.example:51820","allowedIps":["0.0.0.0/0"],"splitMode":"EXCLUDE",
            "splitPackages":[]}]}"""
        val content = BackupCodec.decode(v1Json.replace("\n", ""))
        assertEquals(DnsMode.TUNNEL, content.profiles.single().dns.mode)
        assertTrue(content.profiles.single().dns.rules.isEmpty())
        // v1/v2 stored rules per profile; a v3 restore must not touch device rules.
        assertNull(content.splitTunnel)
    }

    @Test
    fun `encode produces v4 and round-trips both mode sets`() {
        val p = modelProfile().copy(dns = DnsPolicy(
            mode = DnsMode.SECURE, secureType = SecureDnsType.DOT,
            secureEndpoint = "one.one.one.one:853",
            rules = listOf(DnsSplitRule("corp.lan", bypass = true)),
        ))
        val selections = SplitTunnelSelections(
            SplitTunnelMode.INCLUDE, excluded = setOf("com.a"), included = setOf("com.b", "com.c"))
        val json = BackupCodec.encode(listOf(p), selections)
        // Version regression guard: the emitted envelope version was never asserted, so a
        // silent regression to emitting v1 (dropping the DNS fields on decode) stayed green.
        assertTrue(json.contains("\"version\":4"), "encoded backup must declare version 4: $json")
        assertTrue(
            json.contains("\"excluded\"") && json.contains("\"included\""),
            "encoded backup must carry both mode sets: $json")
        val content = BackupCodec.decode(json)
        assertEquals(p.dns, content.profiles.single().dns)
        assertEquals(selections, content.splitTunnel)
    }

    @Test
    fun `a v3 envelope maps its single set to the stored mode's slot`() {
        val includeJson = """{"version":3,"profiles":[],"splitTunnel":{"mode":"INCLUDE","packages":["com.x"]}}"""
        assertEquals(
            SplitTunnelSelections(SplitTunnelMode.INCLUDE, included = setOf("com.x")),
            BackupCodec.decode(includeJson).splitTunnel,
        )
        val excludeJson = """{"version":3,"profiles":[],"splitTunnel":{"mode":"EXCLUDE","packages":["com.y"]}}"""
        assertEquals(
            SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.y")),
            BackupCodec.decode(excludeJson).splitTunnel,
        )
    }

    @Test fun `encode then decode preserves profiles`() {
        val content = BackupCodec.decode(
            BackupCodec.encode(listOf(profile(), profile("p2")), SplitTunnelSelections.INITIAL))
        assertEquals(2, content.profiles.size)
        assertEquals(profile(), content.profiles[0])
    }

    @Test fun `decode rejects unknown version`() {
        val text = """{"version":999,"profiles":[]}"""
        assertFailsWith<IllegalStateException> { BackupCodec.decode(text) }
    }

    @Test
    fun `more than MAX_PROFILES profiles is refused`() {
        val many = (1..BackupCodec.MAX_PROFILES + 1).map { profile("p$it") }
        assertFailsWith<IllegalStateException> {
            BackupCodec.decode(BackupCodec.encode(many, SplitTunnelSelections.INITIAL))
        }
        val atCap = many.take(BackupCodec.MAX_PROFILES)
        assertEquals(
            BackupCodec.MAX_PROFILES,
            BackupCodec.decode(BackupCodec.encode(atCap, SplitTunnelSelections.INITIAL)).profiles.size,
        )
    }

    @Test
    fun `sealed snapshot round-trips and plain v4 still decodes without a passphrase`() {
        val pass = "snapshot passphrase".toCharArray()
        val sealed = BackupCodec.encodeSealed(listOf(profile()), SplitTunnelSelections.INITIAL, pass)
        assertTrue(BackupCodec.isSealed(sealed))
        assertEquals(listOf(profile()), BackupCodec.decode(sealed, pass).profiles)
        val plain = BackupCodec.encode(listOf(profile()), SplitTunnelSelections.INITIAL)
        assertEquals(listOf(profile()), BackupCodec.decode(plain).profiles)
    }

    @Test
    fun `sealed snapshot without a passphrase asks for one`() {
        val sealed = BackupCodec.encodeSealed(
            listOf(profile()), SplitTunnelSelections.INITIAL, "snapshot passphrase".toCharArray())
        assertFailsWith<BackupCodec.PassphraseRequired> { BackupCodec.decode(sealed, null) }
    }

    @Test
    fun `legacy fields are normalized on decode instead of refusing the snapshot`() {
        val name = "x".repeat(60)
        val key = "A".repeat(43) + "="
        val text = """{"version":4,"profiles":[{"id":"p1","name":"$name","privateKey":"$key","addresses":["10.0.0.2/32"],"dnsServers":["10.0.0.1","dns.example.com"],"peerPublicKey":"$key","presharedKey":"  ","endpoint":"vpn.example.com:51820","allowedIps":["0.0.0.0/0"]}]}"""
        val p = BackupCodec.decode(text).profiles.single()
        assertEquals(listOf("10.0.0.1"), p.interfaceConfig.dnsServers)
        assertEquals(48, p.name.length)
        assertNull(p.peer.presharedKey)
        assertTrue(p.validate().isEmpty())
    }

    private fun v4WithEndpoint(endpoint: String) =
        """{"version":4,"profiles":[{"id":"p1","name":"Home","privateKey":"$KEY44","addresses":["10.0.0.2/32"],"dnsServers":["1.1.1.1"],"peerPublicKey":"$KEY44","endpoint":"$endpoint","allowedIps":["0.0.0.0/0"]}]}"""

    /** v1/v2 kept split-tunnel rules per profile, in splitMode/splitPackages. */
    private fun perProfileRules(version: Int, packages: String) =
        """{"version":$version,"profiles":[{"id":"p1","name":"Home","privateKey":"$KEY44","addresses":["10.0.0.2/32"],"dnsServers":["1.1.1.1"],"peerPublicKey":"$KEY44","endpoint":"vpn.example.com:51820","allowedIps":["0.0.0.0/0"],"splitMode":"EXCLUDE","splitPackages":$packages}]}"""

    @Test
    fun `an unbracketed IPv6 endpoint with a five-digit port is bracketed on decode`() {
        val p = BackupCodec.decode(v4WithEndpoint("2001:db8::1:51820")).profiles.single()
        assertEquals("[2001:db8::1]:51820", p.peer.endpoint)
        assertTrue(p.validate().isEmpty())
    }

    @Test
    fun `a shorter port tail stays as written and validation still refuses it`() {
        val p = BackupCodec.decode(v4WithEndpoint("2001:db8::1:853")).profiles.single()
        assertEquals("2001:db8::1:853", p.peer.endpoint)
        assertEquals(
            listOf("Endpoint must put an IPv6 address in brackets, e.g. [2001:db8::1]:51820."),
            p.validate(),
        )
    }

    @Test
    fun `a v1 or v2 snapshot that carried per-app rules is flagged`() {
        assertTrue(BackupCodec.decode(perProfileRules(version = 1, packages = """["com.browser"]""")).legacyRulesDropped)
        assertTrue(BackupCodec.decode(perProfileRules(version = 2, packages = """["com.browser"]""")).legacyRulesDropped)
        assertFalse(BackupCodec.decode(perProfileRules(version = 2, packages = "[]")).legacyRulesDropped)
    }

    @Test
    fun `v3 and later snapshots are never flagged`() {
        assertFalse(BackupCodec.decode(perProfileRules(version = 3, packages = """["com.browser"]""")).legacyRulesDropped)
        assertFalse(
            BackupCodec.decode(BackupCodec.encode(listOf(profile()), SplitTunnelSelections.INITIAL)).legacyRulesDropped,
        )
    }

    @Test
    fun `the legacy-rules probe reports dropped rules only for v2 snapshots that carry them`() {
        val base = """{"version":2,"profiles":[{"id":"p1","name":"home","privateKey":"$KEY44","addresses":["10.0.0.2/32"],"dnsServers":[],"peerPublicKey":"$KEY44","endpoint":"vpn.example:51820","allowedIps":["0.0.0.0/0"],"splitMode":"EXCLUDE","splitPackages":PACKAGES}]}"""
        assertTrue(BackupCodec.decode(base.replace("PACKAGES", """["com.browser"]""")).legacyRulesDropped)
        assertFalse(BackupCodec.decode(base.replace("PACKAGES", "[]")).legacyRulesDropped)
        // A v2 body whose legacy view cannot be parsed must not refuse the file (runCatching, :123-125).
        assertFalse(BackupCodec.decode(base.replace("PACKAGES", "[1,2]")).legacyRulesDropped)
    }
}

private const val KEY44 = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0="
