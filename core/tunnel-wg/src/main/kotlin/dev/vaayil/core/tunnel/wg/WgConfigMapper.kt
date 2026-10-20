package dev.vaayil.core.tunnel.wg

import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.localNetworkRoutes

object WgConfigMapper {
    fun toBackendConfig(profile: Profile): Config = toBackendConfig(profile, null)

    /**
     * Fills the resolved split-tunnel lists into the interface config; GoBackend.setStateInternal
     * applies them to the VpnService.Builder as addDisallowedApplication/addAllowedApplication.
     *
     * When [dnsPlan] intercepts, the interface's DNS swaps to the plan's one resolver address. No
     * address is added for it (B1): the resolver address is routed, not local. GoBackend adds its
     * host route (GoBackend.setStateInternal, via DnsIntercept.resolverRoutePrefix) and libwg-go
     * answers it. The default pass-through plan leaves the DNS untouched (existing two-arg callers
     * unchanged).
     */
    fun toBackendConfig(profile: Profile, splitTunnel: ResolvedSplitTunnel?, dnsPlan: DnsPlan = DnsPlan()): Config {
        val iface = Interface.Builder()
            .parsePrivateKey(profile.interfaceConfig.privateKey)
        // Fail closed per address family (T-1): Android installs an "unreachable" default route
        // for a family only when the interface carries NO address, route or DNS server of that
        // family AND GoBackend did not call allowFamily for it. GoBackend calls allowFamily for
        // both families unless a single peer has a prefix-0 Allowed IP (its setStateInternal kill-switch rule),
        // so dropping a family's own addresses and servers here closes that family only in that
        // shape; a family that keeps a partial route stays open for what its AllowedIPs do not
        // carry either way, and Profile.warnings() warns about that bypass. A profile whose
        // AllowedIPs has no entry for a family must therefore not put that family's own addresses
        // or servers on the interface, or that family's traffic leaks around the tunnel.
        // AllowedIPs are never modified: they are the peer's cryptokey routing. The planner picks
        // a resolver address in a family that survives this rule (DnsPlanner), so interception
        // never opens a family either: AOSP Vpn.java makeLinkProperties counts the resolver's DNS
        // server and host route like addresses.
        val routedFamilies = routedFamilies(profile)
        iface.addAddresses(routedAddresses(profile).map { InetNetwork.parse(it) })
        val dnsServers = if (dnsPlan.intercept) {
            dnsPlan.vpnDnsServers
        } else {
            profile.interfaceConfig.dnsServers.filter { familyOf(it) in routedFamilies }
        }
        if (dnsServers.isNotEmpty()) {
            iface.addDnsServers(dnsServers.map { java.net.InetAddress.getByName(it) })
        }
        profile.interfaceConfig.mtu?.let { iface.setMtu(it) }
        profile.interfaceConfig.listenPort?.let { iface.setListenPort(it) }
        splitTunnel?.excluded?.takeIf { it.isNotEmpty() }?.let { iface.excludeApplications(it) }
        splitTunnel?.included?.takeIf { it.isNotEmpty() }?.let { iface.includeApplications(it) }

        val peerBuilder = Peer.Builder()
            .parsePublicKey(profile.peer.publicKey)
            .addAllowedIps(profile.peer.allowedIps.map { InetNetwork.parse(it) })
            .parseEndpoint(profile.peer.endpoint)
        profile.peer.presharedKey?.takeIf { it.isNotBlank() }?.let { peerBuilder.parsePreSharedKey(it) }
        profile.peer.persistentKeepalive?.let { peerBuilder.setPersistentKeepalive(it) }

        return Config.Builder().setInterface(iface.build()).addPeer(peerBuilder.build()).build()
    }

    /**
     * The routes for the tunnel's interface with the local-network switch on: the Allowed IPs with the local ranges
     * cut out, plus the parts of the interface subnets and the DNS servers' host routes that the cut took out
     * ([localNetworkRoutes]). Null when the switch is off, so GoBackend routes the peers' Allowed IPs as it
     * always has.
     *
     * The Allowed IPs themselves never change: both per-family rules read them ([routedFamilies] and GoBackend's
     * kill-switch rule, which needs their `/0`), and they are the peer's cryptokey routing.
     *
     * The profile's DNS servers keep their host route whatever its DNS mode, because an extra route can only keep
     * more in the tunnel; [secureEndpointAddresses] adds a secure resolver's literal or pinned addresses.
     */
    fun vpnRoutes(
        profile: Profile,
        allowLocalNetwork: Boolean,
        secureEndpointAddresses: List<String>,
    ): List<InetNetwork>? =
        if (!allowLocalNetwork) {
            null
        } else {
            localNetworkRoutes(
                allowedIps = profile.peer.allowedIps,
                interfaceAddresses = profile.interfaceConfig.addresses,
                dnsAddresses = profile.interfaceConfig.dnsServers + secureEndpointAddresses,
            ).map { InetNetwork.parse(it) }
        }

    /** The address families the peer's AllowedIPs route: the T-1 per-family rule's input. */
    fun routedFamilies(profile: Profile): Set<Family> = profile.peer.allowedIps.map(::familyOf).toSet()

    /**
     * The interface addresses that survive the T-1 per-family rule, in profile order. The mapper puts exactly these
     * on the interface, and DnsPlanner picks the resolver address from their families.
     */
    fun routedAddresses(profile: Profile): List<String> {
        val families = routedFamilies(profile)
        return profile.interfaceConfig.addresses.filter { familyOf(it) in families }
    }

    enum class Family { V4, V6 }

    /**
     * Address family of a CIDR entry or bare address, from the PARSED address: the JVM
     * collapses IPv4-mapped IPv6 ("::ffff:10.0.0.1") to IPv4 and so does the kernel, so the
     * spelling must not decide which family's fail-closed route survives.
     */
    fun familyOf(entry: String): Family =
        runCatching { InetNetwork.parse(entry).address is java.net.Inet6Address }
            .getOrElse { entry.substringBefore('/').contains(':') }
            .let { v6 -> if (v6) Family.V6 else Family.V4 }
}
