package dev.vaayil.core.tunnel.wg

import com.wireguard.config.InetNetwork
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.RESOLVER_V4
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.model.localNetworkRoutes
import java.math.BigInteger
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Field-by-field Profile → com.wireguard.config.Config translation, plus the split-tunnel
 * exclude/include application sets round-tripping through Config.getInterface()/getPeers().
 * Pure JVM: com.wireguard.config touches no Android APIs and numeric InetAddress/InetEndpoint
 * parsing does no I/O.
 */
class WgConfigMapperTest {
    private val privateKey = "A".repeat(43) + "=" // base64 of 32 zero bytes
    private val publicKey = "E".repeat(43) + "=" // 32 bytes; last char's low bits are zero

    private fun profile(
        keepalive: Int? = 25,
        presharedKey: String? = "I".repeat(43) + "=",
        mtu: Int? = 1420,
        listenPort: Int? = 51820,
        dnsServers: List<String> = listOf("1.1.1.1", "9.9.9.9"),
        addresses: List<String> = listOf("10.0.0.2/32", "fd00::2/128"),
        allowedIps: List<String> = listOf("0.0.0.0/0", "::/0"),
    ) = Profile(
        id = "p1",
        name = "Test",
        interfaceConfig = InterfaceConfig(
            privateKey = privateKey,
            addresses = addresses,
            dnsServers = dnsServers,
            mtu = mtu,
            listenPort = listenPort,
        ),
        peer = PeerConfig(
            publicKey = publicKey,
            presharedKey = presharedKey,
            endpoint = "vpn.example.com:51820",
            allowedIps = allowedIps,
            persistentKeepalive = keepalive,
        ),
    )

    private fun Profile.config(splitTunnel: ResolvedSplitTunnel? = null) =
        WgConfigMapper.toBackendConfig(this, splitTunnel)

    @Test
    fun `interface fields translate verbatim`() {
        val iface = profile().config().getInterface()
        assertEquals(privateKey, iface.getKeyPair().getPrivateKey().toBase64())
        assertEquals(setOf("10.0.0.2/32", "fd00:0:0:0:0:0:0:2/128"), iface.getAddresses().map { it.toString() }.toSet())
        assertEquals(setOf("1.1.1.1", "9.9.9.9"), iface.getDnsServers().map { requireNotNull(it.hostAddress) }.toSet())
        assertEquals(Optional.of(1420), iface.getMtu())
        assertEquals(Optional.of(51820), iface.getListenPort())
    }

    @Test
    fun `peer fields translate verbatim`() {
        val peer = profile().config().peers.single()
        assertEquals(publicKey, peer.getPublicKey().toBase64())
        assertEquals("I".repeat(43) + "=", peer.getPreSharedKey().get().toBase64())
        assertEquals("vpn.example.com:51820", peer.getEndpoint().get().toString())
        assertEquals(setOf("0.0.0.0/0", "0:0:0:0:0:0:0:0/0"), peer.getAllowedIps().map { it.toString() }.toSet())
        assertEquals(Optional.of(25), peer.getPersistentKeepalive())
    }

    @Test
    fun `omitted optionals stay unset`() {
        val config = profile(
            presharedKey = null, keepalive = null, mtu = null, listenPort = null, dnsServers = emptyList(),
        ).config()
        val iface = config.getInterface()
        val peer = config.peers.single()
        assertFalse(peer.getPreSharedKey().isPresent)
        assertFalse(peer.getPersistentKeepalive().isPresent)
        assertFalse(iface.getMtu().isPresent)
        assertFalse(iface.getListenPort().isPresent)
        assertTrue(iface.getDnsServers().isEmpty())
    }

    @Test
    fun `exclude mode fills only the excluded application set`() {
        val iface = profile().config(
            ResolvedSplitTunnel(excluded = listOf("com.a", "com.b"), included = emptyList()),
        ).getInterface()
        assertEquals(setOf("com.a", "com.b"), iface.getExcludedApplications())
        assertTrue(iface.getIncludedApplications().isEmpty())
    }

    @Test
    fun `include mode fills only the included application set`() {
        val iface = profile().config(
            ResolvedSplitTunnel(excluded = emptyList(), included = listOf("com.c")),
        ).getInterface()
        assertEquals(setOf("com.c"), iface.getIncludedApplications())
        assertTrue(iface.getExcludedApplications().isEmpty())
    }

    @Test
    fun `no split tunnel leaves both application sets empty`() {
        val iface = profile().config().getInterface()
        assertTrue(iface.getExcludedApplications().isEmpty())
        assertTrue(iface.getIncludedApplications().isEmpty())
    }

    @Test
    fun `mapper swaps the vpn dns to the resolver and adds no address when intercepting`() {
        val p = profile()
        val plan = DnsPlan(intercept = true, vpnDnsServers = listOf(RESOLVER_V4))
        val iface = WgConfigMapper.toBackendConfig(p, null, plan).getInterface()
        assertEquals(setOf("10.0.0.2/32", "fd00:0:0:0:0:0:0:2/128"), iface.getAddresses().map { it.toString() }.toSet())
        assertEquals(listOf(RESOLVER_V4), iface.getDnsServers().map { requireNotNull(it.hostAddress) })
    }

    @Test
    fun `a plain profile maps exactly as before`() {
        // Review Focus 1: no Secure DNS and no split rules, so nothing is intercepted. The interface carries the
        // profile's own addresses and DNS servers, as the two-argument mapping always did, and nothing of the resolver.
        val p = profile()
        val plan = DnsPlanner.plan(p)
        assertEquals(DnsPlan(), plan)
        val iface = WgConfigMapper.toBackendConfig(p, null, plan).getInterface()
        assertEquals(setOf("10.0.0.2/32", "fd00:0:0:0:0:0:0:2/128"), iface.getAddresses().map { it.toString() }.toSet())
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), iface.getDnsServers().map { requireNotNull(it.hostAddress) })
        val before = p.config().getInterface() // the two-argument mapping, which ignores DNS plans
        assertEquals(before.getAddresses(), iface.getAddresses())
        assertEquals(before.getDnsServers(), iface.getDnsServers())
    }

    @Test
    fun `mapper keeps profile dns when not intercepting`() {
        val p = profile()
        val iface = WgConfigMapper.toBackendConfig(p, null, DnsPlan(false, emptyList())).getInterface()
        assertEquals(p.interfaceConfig.dnsServers, iface.getDnsServers().map { requireNotNull(it.hostAddress) })
    }

    @Test
    fun `a family with no AllowedIPs entry loses its interface addresses and dns servers`() {
        val profile = profile(
            addresses = listOf("10.66.0.2/32", "fc00:bbbb::2/128"),
            dnsServers = listOf("10.64.0.1", "fc00:bbbb::1"),
            allowedIps = listOf("0.0.0.0/0"),
        )
        val config = WgConfigMapper.toBackendConfig(profile, null)
        assertEquals(listOf("10.66.0.2/32"), config.`interface`.addresses.map { it.toString() })
        assertEquals(listOf("10.64.0.1"), config.`interface`.dnsServers.map { requireNotNull(it.hostAddress) })
    }

    @Test
    fun `a partially routed family keeps its addresses`() {
        val profile = profile(
            addresses = listOf("10.66.0.2/32", "fc00:bbbb::2/128"),
            dnsServers = emptyList(),
            allowedIps = listOf("0.0.0.0/0", "fd00::/64"),
        )
        val config = WgConfigMapper.toBackendConfig(profile, null)
        assertEquals(2, config.`interface`.addresses.size)
    }

    @Test
    fun `an ipv6-only profile that intercepts carries no ipv4 address or dns server`() {
        val profile = profile(addresses = listOf("fc00:bbbb::2/128"), dnsServers = emptyList(), allowedIps = listOf("::/0"))
            .copy(dns = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = "9.9.9.9"))
        val config = WgConfigMapper.toBackendConfig(profile, null, DnsPlanner.plan(profile))
        assertEquals(listOf("fc00:bbbb:0:0:0:0:0:2/128"), config.`interface`.addresses.map { it.toString() })
        assertEquals(listOf("fdcb:b743:7f0b:0:0:0:0:3"), config.`interface`.dnsServers.map { requireNotNull(it.hostAddress) })
    }

    @Test
    fun `the family rule holds under interception`() {
        val profile = profile(
            addresses = listOf("10.66.0.2/32", "fc00:bbbb::2/128"),
            dnsServers = listOf("10.64.0.1", "fc00:bbbb::1"),
            allowedIps = listOf("0.0.0.0/0"),
        ).copy(dns = DnsPolicy(rules = listOf(DnsSplitRule("corp.lan", bypass = true))))
        val config = WgConfigMapper.toBackendConfig(profile, null, DnsPlanner.plan(profile))
        assertEquals(listOf("10.66.0.2/32"), config.`interface`.addresses.map { it.toString() })
        assertEquals(listOf(RESOLVER_V4), config.`interface`.dnsServers.map { requireNotNull(it.hostAddress) })
    }

    @Test
    fun `family follows the parsed address not the spelling`() {
        assertEquals(WgConfigMapper.Family.V4, WgConfigMapper.familyOf("::ffff:10.0.0.1/32"))
        assertEquals(WgConfigMapper.Family.V6, WgConfigMapper.familyOf("fd00::1/128"))
        assertEquals(WgConfigMapper.Family.V4, WgConfigMapper.familyOf("1.1.1.1"))
    }

    @Test
    fun `an entry that fails to parse keeps the spelling-based answer`() {
        // The classifier runs on user-supplied strings: an entry the library refuses must not
        // throw, it falls back to the colon check it used before.
        assertEquals(WgConfigMapper.Family.V6, WgConfigMapper.familyOf("fe80::1%25wlan0/64"))
        assertEquals(WgConfigMapper.Family.V4, WgConfigMapper.familyOf("1.2.3/24"))
    }

    @Test
    fun `vpnRoutes is null with the switch off`() {
        assertNull(WgConfigMapper.vpnRoutes(profile(), false, listOf("10.64.0.53")))
    }

    @Test
    fun `vpnRoutes trims the routes and leaves the AllowedIPs alone`() {
        // The profile loses the local ranges from its routes, but the peer's cryptokey routing keeps them: both
        // family rules and GoBackend's kill-switch rule still read the AllowedIPs.
        assertEquals(
            localNetworkRoutes(
                listOf("0.0.0.0/0", "::/0"),
                listOf("10.0.0.2/32", "fd00::2/128"),
                listOf("1.1.1.1", "9.9.9.9"),
            ).map(InetNetwork::parse),
            WgConfigMapper.vpnRoutes(profile(), true, emptyList()),
        )
        val peerAllowedIps: Set<InetNetwork> = WgConfigMapper.toBackendConfig(profile(), null).peers.single().allowedIps
        assertEquals(listOf("0.0.0.0/0", "::/0").map(InetNetwork::parse).toSet(), peerAllowedIps)
    }

    @Test
    fun `every route has its host bits cleared`() {
        // Builder.addRoute throws "Bad address" for a route that keeps host bits.
        val routes = requireNotNull(
            WgConfigMapper.vpnRoutes(
                profile(allowedIps = listOf("0.0.0.0/0", "192.168.1.7/24", "::/0")),
                true,
                emptyList(),
            ),
        )
        assertTrue(routes.isNotEmpty())
        for (route in routes) {
            val bits = route.address.address.size * 8
            assertTrue(
                BigInteger(1, route.address.address).mod(BigInteger.ONE.shiftLeft(bits - route.mask)) ==
                    BigInteger.ZERO,
                "$route keeps host bits",
            )
        }
    }

    @Test
    fun `a secure server's address gets a host route`() {
        val routes = requireNotNull(
            WgConfigMapper.vpnRoutes(profile(dnsServers = emptyList()), true, listOf("10.64.0.53")),
        )
        assertContains(routes, InetNetwork.parse("10.64.0.53/32"))
    }

    @Test
    fun `the profile's own local dns servers keep their host routes`() {
        // With the switch on, a private DNS server's host route is what keeps its lookups inside the tunnel.
        val routes = requireNotNull(
            WgConfigMapper.vpnRoutes(profile(dnsServers = listOf("10.64.0.1", "fc00:bbbb::1")), true, emptyList()),
        )
        assertContains(routes, InetNetwork.parse("10.64.0.1/32"))
        assertContains(routes, InetNetwork.parse("fc00:bbbb::1/128"))
    }
}
