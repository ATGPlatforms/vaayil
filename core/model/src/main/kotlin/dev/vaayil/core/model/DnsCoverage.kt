package dev.vaayil.core.model

import java.net.URI

private const val DNS_SERVER_OUTSIDE =
    "DNS lookups will bypass this tunnel. Its DNS server isn't in Allowed IPs."
private const val DNS_SERVERS_OUTSIDE =
    "DNS lookups will bypass this tunnel. Its DNS servers aren't in Allowed IPs."
private const val FIRST_DNS_SERVER_OUTSIDE =
    "DNS lookups will bypass this tunnel. Its first DNS server isn't in Allowed IPs."
private const val LATER_DNS_SERVER_OUTSIDE =
    "Some DNS lookups may bypass this tunnel. One of its DNS servers isn't in Allowed IPs."
private const val SECURE_SERVER_OUTSIDE =
    "Secure DNS will bypass this tunnel. Its server isn't in Allowed IPs."
private const val SECURE_HOSTNAME_UNCOVERED =
    "Secure DNS will bypass this tunnel unless its server's address is in Allowed IPs."

/**
 * #15, silent unless a DNS server will be reached outside the tunnel: the resolver's plain
 * servers ride the tunnel-path routing, and a secure server's socket is deliberately
 * unprotected, so either one outside the profile's Allowed IPs leaves the device in plaintext
 * (plain) or encrypted but outside the tunnel (secure) instead of failing.
 *
 * Which servers are checked mirrors DnsPlanner and WgConfigMapper: Secure DNS in effect checks
 * only its endpoint; otherwise an intercepting profile (split-DNS rules with a plain server to
 * forward to) checks every literal plain server; any other profile checks the literal servers of
 * a family its Allowed IPs route, because WgConfigMapper hands the VPN only those.
 *
 * A literal is outside when no parseable Allowed IPs entry of its family contains it, its family
 * is not closed, and it is not inside an interface address's subnet. A closed family has a
 * valid prefix-0 entry somewhere but none of its own: Android's unreachable route makes its
 * lookups fail rather than bypass, so it never warns. An interface address's subnet is directly
 * connected, so a server there reaches WireGuard, which drops it: lookups fail, never bypass.
 *
 * A hostname secure endpoint has no addresses until the tunnel starts, so it warns unless every
 * family that is not closed is covered completely, e.g. by `0.0.0.0/0` and `::/0`.
 *
 * Silent under T-1, whose own warning already says this profile carries nothing. Never resolves
 * a name: only [isIpLiteral]-gated strings reach [parseBlock], and [dnsHostBlock] gates the same
 * way. Advice only, like every warning: it never blocks Save.
 */
internal fun Profile.dnsCoverageWarning(): String? {
    if (addressesMissRoutedFamilies()) return null
    val allowedIps = peer.allowedIps
    val blocks = allowedIps.mapNotNull(::parseBlock)
    val defaultRoute = blocks.any { it.prefix == 0 }
    val v4Entries = blocks.filter { it.bits != V6_BITS }
    val v6Entries = blocks.filter { it.bits == V6_BITS }
    val v4Closed = defaultRoute && v4Entries.isEmpty()
    val v6Closed = defaultRoute && v6Entries.isEmpty()

    if (dns.secureInEffect) {
        val host = secureHost() ?: return null
        if (isIpLiteral(host)) {
            return if (outsideAllowedIps(host, blocks, v4Closed, v6Closed)) SECURE_SERVER_OUTSIDE else null
        }
        val v4 = coveredAddressCount(allowedIps, ipv6 = false)
        val v6 = coveredAddressCount(allowedIps, ipv6 = true)
        val fullyCovered = (v4Closed || v4 == V4_SIZE) && (v6Closed || v6 == V6_SIZE)
        return if (fullyCovered) null else SECURE_HOSTNAME_UNCOVERED
    }

    val literalServers = interfaceConfig.dnsServers.filter(::isIpLiteral)
    val checked = if (dns.rules.isNotEmpty() && interfaceConfig.dnsServers.isNotEmpty()) {
        literalServers
    } else {
        val routed = allowedIps.map(::isIpv6Entry).toSet()
        literalServers.filter { isIpv6Entry(it) in routed }
    }
    val outside = checked.map { outsideAllowedIps(it, blocks, v4Closed, v6Closed) }
    return when {
        outside.none { it } -> null
        outside.all { it } -> if (checked.size == 1) DNS_SERVER_OUTSIDE else DNS_SERVERS_OUTSIDE
        outside.first() -> FIRST_DNS_SERVER_OUTSIDE
        else -> LATER_DNS_SERVER_OUTSIDE
    }
}

/** The secure endpoint's host with IPv6 brackets removed; null when a draft's endpoint won't parse. */
private fun Profile.secureHost(): String? {
    val endpoint = dns.secureEndpoint ?: return null
    if (dns.secureType == SecureDnsType.DOT) return parseDotEndpoint(endpoint)?.first
    val host = runCatching { URI(endpoint).host }.getOrNull() ?: return null
    return host.removePrefix("[").removeSuffix("]")
}

/**
 * Whether [server], a strict literal, is reached outside the tunnel: no Allowed IPs entry of its
 * family contains it, that family is not closed, and no interface subnet holds it.
 */
private fun Profile.outsideAllowedIps(
    server: String,
    blocks: List<CidrBlock>,
    v4Closed: Boolean,
    v6Closed: Boolean,
): Boolean {
    val host = dnsHostBlock(server) ?: return false
    if (if (host.bits == V6_BITS) v6Closed else v4Closed) return false
    if (blocks.any { it.bits == host.bits && it.contains(host) }) return false
    val interfaceSubnets = interfaceConfig.addresses.mapNotNull(::parseBlock)
    return interfaceSubnets.none { it.bits == host.bits && it.contains(host) }
}
