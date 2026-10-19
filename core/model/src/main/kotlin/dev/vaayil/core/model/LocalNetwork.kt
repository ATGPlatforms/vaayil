package dev.vaayil.core.model

import java.math.BigInteger
import java.net.InetAddress

/**
 * The blocks a local-network switch keeps out of the tunnel: the private, link-local and
 * multicast ranges of both families, in the order the routes are listed. `100.64.0.0/10` (carrier
 * NAT) is not local: it is routable and belongs in the tunnel.
 */
val LOCAL_NETWORK_RANGES: List<String> = listOf(
    "10.0.0.0/8",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "169.254.0.0/16",
    "224.0.0.0/3",
    "fc00::/7",
    "fe80::/10",
    "ff00::/8",
)

/** The same ranges, parsed once. A typo here fails loudly at class load, never silently. */
private val LOCAL_BLOCKS: List<CidrBlock> = LOCAL_NETWORK_RANGES.map {
    requireNotNull(parseBlock(it)) { "Unparseable local range: $it" }
}

/**
 * The routes to hand the system when the local-network switch is on: [allowedIps] with the local
 * ranges cut out, so the device reaches local networks through its own interfaces instead of the
 * tunnel. The Allowed IPs themselves never change — only the routes built from them do.
 *
 * An entry that is not a strict literal, or that holds no local range of its family, is routed as
 * it is; one inside a local range is routed as it is too, because the profile asks for that
 * network over the tunnel even with the switch on. An entry that contains local ranges is split
 * into the fewest blocks that cover it minus those ranges, in ascending address order.
 *
 * The tunnel's own endpoints are then restored, because trimming the routes is not meant to cut the
 * device off from its own interface or name server: each interface address keeps a route to its
 * subnet, and each DNS address a host route (`/32`, or `/128` for a literal with a colon). A
 * candidate is added only when it lies inside a local range that a trimmed entry covered and no
 * route already in the list covers it — a network the profile never routed stays outside the
 * tunnel, and a family the Allowed IPs don't route gains no route.
 *
 * Every returned route has its host bits cleared, as the route builder requires.
 */
fun localNetworkRoutes(
    allowedIps: List<String>,
    interfaceAddresses: List<String>,
    dnsAddresses: List<String>,
): List<String> {
    val routes = mutableListOf<String>()
    val covered = mutableListOf<CidrBlock>()
    val trimmed = mutableListOf<CidrBlock>()

    fun add(block: CidrBlock) {
        val text = block.text()
        if (text in routes) return
        routes += text
        covered += block
    }

    for (entry in allowedIps) {
        val block = parseBlock(entry)
        if (block == null) {
            // Not a strict literal: it names nothing we can reason about, so it passes through.
            if (entry !in routes) routes += entry
            continue
        }
        val family = LOCAL_BLOCKS.filter { it.bits == block.bits }
        when {
            family.any { it.contains(block) } -> add(block)
            family.any { block.contains(it) } -> {
                trimmed += block
                trim(block, family).forEach(::add)
            }
            else -> add(block)
        }
    }

    val candidates = interfaceAddresses.mapNotNull(::parseBlock)
        .sortedWith(compareBy({ it.bits }, { it.network })) + dnsAddresses.mapNotNull(::dnsHostBlock)
    for (candidate in candidates) {
        val local = LOCAL_BLOCKS.any { it.bits == candidate.bits && it.contains(candidate) }
        val wasTrimmed = trimmed.any { it.bits == candidate.bits && it.contains(candidate) }
        val alreadyRouted = covered.any { it.bits == candidate.bits && it.contains(candidate) }
        if (local && wasTrimmed && !alreadyRouted) add(candidate)
    }
    return routes
}

/** A DNS server as the host route it needs: a single-address block of its family. */
private fun dnsHostBlock(address: String): CidrBlock? =
    if (isIpLiteral(address)) parseBlock("$address/${if (address.contains(':')) 128 else 32}") else null

/**
 * The [block] minus the local [ranges] of its family, as whole CIDR blocks in ascending address
 * order: halve until a half holds no range (route it) or is wholly local (drop it).
 */
private fun trim(block: CidrBlock, ranges: List<CidrBlock>): List<CidrBlock> {
    val held = ranges.filter { block.contains(it) }
    return when {
        held.isEmpty() -> listOf(block)
        held.any { it.contains(block) } -> emptyList()
        else -> {
            val half = block.network.add(BigInteger.ONE.shiftLeft(block.bits - block.prefix - 1))
            val prefix = block.prefix + 1
            trim(CidrBlock(block.network, block.bits, prefix), ranges) +
                trim(CidrBlock(half, block.bits, prefix), ranges)
        }
    }
}

/** The block as a route: an address with its host bits cleared, then the prefix length. */
private fun CidrBlock.text(): String = InetAddress.getByAddress(addressBytes()).hostAddress + "/" + prefix

/** The network address as exactly [CidrBlock.bits] bits, left-padded past BigInteger's sign byte. */
private fun CidrBlock.addressBytes(): ByteArray {
    val length = bits / 8
    val raw = network.toByteArray()
    val start = (raw.size - length).coerceAtLeast(0)
    val bytes = ByteArray(length)
    raw.copyInto(bytes, length - (raw.size - start), start)
    return bytes
}
