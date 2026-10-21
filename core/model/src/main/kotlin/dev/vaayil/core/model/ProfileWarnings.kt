package dev.vaayil.core.model

import java.math.BigInteger
import java.net.InetAddress

/** IPv4's and IPv6's address spaces (2^32, 2^128) and their halves: the A4 rule's thresholds. */
private const val V4_BITS = 32
private const val V6_BITS = 128
private val V4_SIZE = BigInteger.ONE.shiftLeft(V4_BITS)
private val V4_HALF = BigInteger.ONE.shiftLeft(V4_BITS - 1)
private val V6_SIZE = BigInteger.ONE.shiftLeft(V6_BITS)
private val V6_HALF = BigInteger.ONE.shiftLeft(V6_BITS - 1)

/**
 * Advice about a profile that is valid but will not work the way its owner likely expects. Unlike
 * [validate], a warning never blocks Save, an import or a restore: the restore writes every
 * profile or none, so a warning there would refuse a whole snapshot. Empty when there is nothing
 * to say.
 */
fun Profile.warnings(): List<String> = buildList {
    if (addressesMissRoutedFamilies()) {
        add("This profile's addresses don't match its Allowed IPs, so it won't carry connections.")
    }
    val allowedIps = peer.allowedIps
    val v4 = coveredAddressCount(allowedIps, ipv6 = false)
    val v6 = coveredAddressCount(allowedIps, ipv6 = true)
    val defaultRoute = allowedIps.any { parseBlock(it)?.prefix == 0 }
    // A near-full tunnel can still leave a family open (A4): GoBackend calls allowFamily for both
    // families unless a single peer has a prefix-0 Allowed IP (its setStateInternal kill-switch rule), so without
    // a `/0` entry Android's unreachable route never holds the missing family, and a family that
    // keeps a partial route stays open for the rest either way. Only a valid `/0` plus a family
    // with no entries of its own fails that family closed. Warn when one family's union reaches
    // half its space (a profile that reads as a tunnel, unlike the intentional small splits), the
    // other family is not fully covered, and it is not failed closed that way.
    if (v4 >= V4_HALF && v6 < V6_SIZE && !(defaultRoute && v6 == BigInteger.ZERO)) {
        add("Some IPv6 connections will bypass this tunnel. Add ::/0 to Allowed IPs to keep them in.")
    }
    if (v6 >= V6_HALF && v4 < V4_SIZE && !(defaultRoute && v4 == BigInteger.ZERO)) {
        add("Some IPv4 connections will bypass this tunnel. Add 0.0.0.0/0 to Allowed IPs to keep them in.")
    }
    // J12: with no DNS server on the VPN network Android resolves every app's lookups on the underlying network
    // while connected; WgConfigMapper adds DNS servers only in a routed family. An intercepting profile gets the
    // in-tunnel resolver when Secure DNS is in effect or split-DNS rules have any DNS server (DnsPlanner); a profile
    // that does not read as a tunnel is an intentional split, and T-1 covers the address mismatch.
    val routed = allowedIps.map(::isIpv6Entry).toSet()
    if (
        (v4 >= V4_HALF || v6 >= V6_HALF) &&
        !(dns.secureInEffect || (dns.rules.isNotEmpty() && interfaceConfig.dnsServers.isNotEmpty())) &&
        interfaceConfig.dnsServers.none { isIpv6Entry(it) in routed } &&
        !addressesMissRoutedFamilies()
    ) {
        add("DNS lookups will bypass this tunnel. Add a DNS server to keep them in.")
    }
}

/**
 * T-1: the tunnel keeps an interface address only in a family the Allowed IPs route: WgConfigMapper
 * drops a family's own addresses and DNS servers when Allowed IPs has no entry for it. When no
 * address is in a routed family the interface comes up without one of the profile's own and no
 * traffic is carried. Dropping them does not by itself close a family: Android's unreachable route
 * also needs GoBackend to omit allowFamily, which it does only when some Allowed IP has prefix 0,
 * and a family that keeps a partial route stays open (the A4 warnings above report that). Silent
 * while either list is empty: an empty Allowed IPs is already a [validate] error, and a profile
 * with no address at all is a different problem that this copy would misdescribe.
 */
private fun Profile.addressesMissRoutedFamilies(): Boolean {
    val routed = peer.allowedIps.map(::isIpv6Entry).toSet()
    val addresses = interfaceConfig.addresses
    return routed.isNotEmpty() && addresses.isNotEmpty() && addresses.none { isIpv6Entry(it) in routed }
}

/**
 * A CIDR entry's family by its spelling. [validate] refuses the IPv4-mapped forms, the only ones
 * whose spelling and parsed family disagree, so for a valid profile this matches the tunnel.
 */
private fun isIpv6Entry(entry: String): Boolean = entry.substringBefore('/').contains(':')

/** A CIDR block with host bits cleared, so equal entries share one block and containment is exact. */
internal class CidrBlock(val network: BigInteger, val bits: Int, val prefix: Int) {
    val size: BigInteger = BigInteger.ONE.shiftLeft(bits - prefix)

    fun contains(other: CidrBlock): Boolean =
        prefix <= other.prefix &&
            network.shiftRight(bits - prefix) == other.network.shiftRight(bits - prefix)
}

/**
 * A strict-literal Allowed IPs entry as a [CidrBlock], or null when it is not one: a draft's
 * invalid entries never throw and never reach the name service. The family comes from the parsed
 * bytes, which agree with the spelling because [isIpLiteral] refuses the mapped and dotted-tail
 * forms.
 */
internal fun parseBlock(entry: String): CidrBlock? {
    val addr = entry.substringBefore('/')
    if (!isIpLiteral(addr)) return null
    val prefix = entry.substringAfter('/').toIntOrNull() ?: return null
    val bytes = runCatching { InetAddress.getByName(addr).address }.getOrNull() ?: return null
    val bits = bytes.size * 8
    if (prefix !in 0..bits) return null
    val value = BigInteger(1, bytes)
    return CidrBlock(value.shiftRight(bits - prefix).shiftLeft(bits - prefix), bits, prefix)
}

/**
 * How many addresses of the [ipv6] family the [entries]' union covers: blocks contained in another
 * are counted once, and [BigInteger] keeps the union exact at 2^128.
 */
private fun coveredAddressCount(entries: List<String>, ipv6: Boolean): BigInteger {
    val blocks = entries.mapNotNull(::parseBlock).filter { (it.bits == V6_BITS) == ipv6 }
    val kept = mutableListOf<CidrBlock>()
    for (block in blocks.sortedBy { it.prefix }) {
        if (kept.none { it.contains(block) }) kept += block
    }
    return kept.fold(BigInteger.ZERO) { sum, block -> sum + block.size }
}
