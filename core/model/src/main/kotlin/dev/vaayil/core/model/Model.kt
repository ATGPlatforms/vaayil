package dev.vaayil.core.model

import kotlinx.serialization.Serializable

data class Profile(
    val id: String,
    val name: String,
    val interfaceConfig: InterfaceConfig,
    val peer: PeerConfig,
    val dns: DnsPolicy = DnsPolicy(),
)

data class InterfaceConfig(
    val privateKey: String,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val mtu: Int?,
    val listenPort: Int?,
)

data class PeerConfig(
    val publicKey: String,
    val presharedKey: String?,
    val endpoint: String,
    val allowedIps: List<String>,
    val persistentKeepalive: Int?,
)

enum class SplitTunnelMode { EXCLUDE, INCLUDE }

data class SplitTunnelPolicy(val mode: SplitTunnelMode, val packages: Set<String>)

enum class DnsMode { TUNNEL, SECURE }

enum class SecureDnsType { DOH, DOT }

@Serializable
data class DnsSplitRule(val domain: String, val bypass: Boolean)

data class DnsPolicy(
    val mode: DnsMode = DnsMode.TUNNEL,
    val secureType: SecureDnsType? = null,
    val secureEndpoint: String? = null,   // DoH: absolute https URL. DoT: host, host:port, bare/bracketed IPv6 literal
    val rules: List<DnsSplitRule> = emptyList(),
)

const val RESOLVER_V4 = "10.111.222.3"    // in-tunnel resolver address, never a tunnel address
const val RESOLVER_V6 = "fdcb:b743:7f0b::3" // its IPv6 twin for profiles that route only IPv6; a ULA (random global ID)

/**
 * Whether Secure DNS actually carries this policy's queries: SECURE mode with a type and a non-blank endpoint. The
 * tunnel's DNS planner, [dnsSummary] and the editor's split-DNS hint all ask this one question.
 */
val DnsPolicy.secureInEffect: Boolean
    get() = mode == DnsMode.SECURE && secureType != null && !secureEndpoint.isNullOrBlank()

private val BASE64_44 = Regex("^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$") // canonical final char: what Key.fromBase64 accepts
private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9\\-.]*[A-Za-z0-9])?$")
private val PROFILE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

/** Strict dotted quad: four decimal octets 0..255. Purely syntactic. */
private fun isIpv4Literal(s: String): Boolean {
    val parts = s.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } }) return false
    return parts.all { it.toInt() in 0..255 }
}

private fun isValidCidr(entry: String): Boolean {
    val addr = entry.substringBefore('/')
    val prefix = entry.substringAfter('/').toIntOrNull() ?: return false
    return when {
        isIpv4Literal(addr) -> prefix in 0..32
        isIpv6Literal(addr) -> prefix in 0..128
        else -> false
    }
}

/** Default DoT port (RFC 7858) applied when an endpoint carries none. */
const val DOT_DEFAULT_PORT = 853

/** Longest profile name accepted at any write boundary (duplicate() truncates to fit). */
const val MAX_PROFILE_NAME_LENGTH = 48

/** Strict IP-literal check, purely syntactic: no name-service lookup can happen here. */
fun isIpLiteral(host: String): Boolean = isIpv4Literal(host) || isIpv6Literal(host)

/** Strips control characters, trims, and caps at [MAX_PROFILE_NAME_LENGTH]; blank stays blank. */
fun normalizeProfileName(raw: String): String =
    raw.filterNot { it.isISOControl() }.trim().take(MAX_PROFILE_NAME_LENGTH)

/** A name for an imported config: normalized and never blank. */
fun importedProfileName(raw: String): String = normalizeProfileName(raw).ifBlank { "Imported" }

/** Null when [endpoint] is an acceptable DoH URL; otherwise the user-facing reason. */
fun dohEndpointProblem(endpoint: String): String? {
    val url = try { java.net.URI(endpoint) } catch (_: Exception) { return "DoH endpoint must be an absolute https URL." }
    if (!url.scheme.equals("https", ignoreCase = true) || url.host.isNullOrBlank()) return "DoH endpoint must be an absolute https URL."
    if (url.rawUserInfo != null) return "DoH endpoint must not contain a username or password."
    val host = url.host.removePrefix("[").removeSuffix("]")
    // A hostname never contains ':', so a colon-bearing host that is not a strict literal is a
    // zone-scoped, IPv4-mapped or malformed IPv6 form: refuse it before it reaches the dialer.
    if (host.contains(':') && !isIpLiteral(host)) return "DoH endpoint host must be a hostname or a plain IP literal."
    if (isLocalOrLinkLocalHost(host)) return "DoH endpoint must not point at this device or a link-local address."
    return null
}

private fun isLocalOrLinkLocalHost(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true)) return true
    if (!isIpLiteral(host)) return false
    // Literal only (checked above), so getByName performs no DNS lookup.
    val addr = java.net.InetAddress.getByName(host)
    return addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress
}

/** "999.1.1.1" and "1.2.3" look numeric but are not addresses; HOSTNAME alone would admit them. */
private fun isNumericButNotIpv4(s: String): Boolean = s.all { it in '0'..'9' || it == '.' } && !isIpv4Literal(s)

/**
 * Null when [endpoint] is `host:port`, `IPv4:port` or `[IPv6]:port` with a port in 1..65535;
 * otherwise the user-facing reason (F17). Every message starts with "Endpoint" so the editor
 * shows it under the Endpoint field. Purely syntactic: no name-service lookup.
 */
fun peerEndpointProblem(endpoint: String): String? {
    val malformed = "Endpoint must be host:port (got \"$endpoint\")."
    val host: String
    val portText: String
    if (endpoint.startsWith("[")) {
        val close = endpoint.indexOf(']')
        if (close < 0 || endpoint.getOrNull(close + 1) != ':') return malformed
        host = endpoint.substring(1, close)
        if (!isIpv6Literal(host)) return malformed
        portText = endpoint.substring(close + 2)
    } else {
        if (endpoint.count { it == ':' } > 1) {
            return "Endpoint must put an IPv6 address in brackets, e.g. [2001:db8::1]:51820."
        }
        host = endpoint.substringBefore(':', missingDelimiterValue = "")
        portText = endpoint.substringAfter(':', missingDelimiterValue = "")
        val name = host.removeSuffix(".") // a fully qualified name may end in a dot
        val hostOk = isIpv4Literal(host) || (HOSTNAME.matches(name) && !isNumericButNotIpv4(name))
        if (!hostOk) return malformed
    }
    if (portText.isEmpty() || portText.length > 5 || portText.any { it !in '0'..'9' }) return malformed
    if (portText.toInt() !in 1..65535) return "Endpoint port must be 1-65535."
    return null
}

/**
 * Snapshot restore only (R7): an older build stored IPv6 endpoints unbracketed. Rewrites `addr:port` to
 * `[addr]:port` when the port is unambiguous: the text after the last ':' is exactly five ASCII digits (no hex group
 * has five, and 51820 is WireGuard's default) and the text before it is a strict IPv6 literal. Anything else comes
 * back unchanged, so [validate] still refuses it with its bracket hint. `.conf` import does not use this.
 */
fun bracketIpv6Endpoint(endpoint: String): String {
    if (endpoint.startsWith("[") || endpoint.count { it == ':' } < 2) return endpoint
    val head = endpoint.substringBeforeLast(':')
    val tail = endpoint.substringAfterLast(':')
    if (tail.length != 5 || tail.any { it !in '0'..'9' } || !isIpv6Literal(head)) return endpoint
    return "[$head]:$tail"
}

/**
 * Splits a DoT endpoint into (host, port) with the default port resolved (D7). Accepts
 * hostname, hostname:port, IPv4, and IPv6 literals bare ("2001:db8::1"), bracketed without a
 * port ("[2001:db8::1]"), or bracketed with one ("[2001:db8::1]:853"). A bare colon-bearing
 * string is accepted only when it parses as exactly one IPv6 address. Strictly syntactic:
 * never touches the name service.
 */
fun parseDotEndpoint(endpoint: String): Pair<String, Int>? {
    if (endpoint.isBlank()) return null
    if (endpoint.startsWith("[")) {
        val close = endpoint.indexOf(']')
        if (close < 0) return null // unclosed bracket
        val host = endpoint.substring(1, close)
        if (!isIpv6Literal(host)) return null
        val rest = endpoint.substring(close + 1) // "" or ":port"
        val port = when {
            rest.isEmpty() -> DOT_DEFAULT_PORT
            rest.startsWith(":") -> rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            else -> return null // trailing garbage after ']'
        }
        return host to port
    }
    if (!endpoint.contains(':')) {
        return if (HOSTNAME.matches(endpoint) && !isNumericButNotIpv4(endpoint)) endpoint to DOT_DEFAULT_PORT else null
    }
    val head = endpoint.substringBeforeLast(':')
    val tail = endpoint.substringAfterLast(':')
    val tailPort = tail.toIntOrNull()?.takeIf { it in 1..65535 }
    if (tailPort != null && isIpv6Literal(head) && isIpv6Literal(endpoint)) return null // ambiguous
    if (tailPort != null && HOSTNAME.matches(head) && !isNumericButNotIpv4(head)) return head to tailPort
    if (isIpv6Literal(endpoint)) return endpoint to DOT_DEFAULT_PORT
    return null
}

/**
 * Strict IPv6 literal check, purely syntactic: hex groups and at most one "::". Dotted tails
 * ("::ffff:1.2.3.4"), IPv4-mapped forms ("::ffff:7f00:1") and zone ids ("fe80::1%25eth0") are
 * refused: the JVM collapses the first two to an IPv4 address, so every family decision
 * downstream would disagree with the kernel, and a zone id is meaningless in a profile.
 */
private fun isIpv6Literal(s: String): Boolean {
    if (s.none { it == ':' }) return false
    if (s.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' && it != ':' }) return false
    val dc = s.indexOf("::")
    val wellFormed = if (dc < 0) {
        groupCount(s) == 8
    } else {
        if (s.indexOf("::", dc + 1) >= 0) return false // at most one "::"
        if (s == "::") return true // unspecified address
        val head = groupCount(s.substring(0, dc)) ?: return false
        val tail = groupCount(s.substring(dc + 2)) ?: return false
        head + tail in 1..7 // "::" must stand for at least one group
    }
    return wellFormed && !isIpv4Mapped(ipv6Groups(s))
}

/** Colon-separated group count of a "::"-free chunk, or null when the chunk is malformed. */
private fun groupCount(chunk: String): Int? {
    if (chunk.isEmpty()) return 0
    if (chunk.first() == ':' || chunk.last() == ':') return null // stray colon ("a:::b", ":a")
    var n = 0
    for (g in chunk.split(':')) {
        if (g.isEmpty() || g.length > 4 || g.any { Character.digit(it, 16) < 0 }) return null
        n++
    }
    return n
}

/** The eight 16-bit groups of a well-formed IPv6 literal (zeros filled in for "::"). */
private fun ipv6Groups(s: String): List<Int> {
    val dc = s.indexOf("::")
    if (dc < 0) return s.split(':').map { it.toInt(16) }
    val head = s.substring(0, dc).split(':').filter { it.isNotEmpty() }
    val tail = s.substring(dc + 2).split(':').filter { it.isNotEmpty() }
    return (head + List(8 - head.size - tail.size) { "0" } + tail).map { it.toInt(16) }
}

/** ::ffff:a.b.c.d in hex form: groups 1-5 zero, group 6 ffff. */
private fun isIpv4Mapped(groups: List<Int>): Boolean =
    groups.size == 8 && groups.subList(0, 5).all { it == 0 } && groups[5] == 0xFFFF

/**
 * Whether two IP literals name the same address: IPv4 by octet value, IPv6 by group value, so every spelling of one
 * address matches ("010.111.222.003", "FDCB:B743:7F0B:0:0:0:0:3"). Purely syntactic, like [isIpLiteral]: no
 * name-service lookup can happen here. A string that is not a literal matches nothing.
 */
private fun sameAddress(a: String, b: String): Boolean = when {
    isIpv4Literal(a) && isIpv4Literal(b) -> a.split('.').map { it.toInt() } == b.split('.').map { it.toInt() }
    isIpv6Literal(a) && isIpv6Literal(b) -> ipv6Groups(a) == ipv6Groups(b)
    else -> false
}

fun Profile.validate(): List<String> {
    val errors = mutableListOf<String>()
    if (!PROFILE_ID.matches(id)) errors += "Profile id must be 1-64 letters, digits, '-' or '_'."
    if (name.isBlank()) errors += "Profile name must not be empty."
    if (name.length > MAX_PROFILE_NAME_LENGTH) errors += "Profile name must be at most $MAX_PROFILE_NAME_LENGTH characters."
    if (name.any { it.isISOControl() }) errors += "Profile name must not contain control characters."
    if (!BASE64_44.matches(interfaceConfig.privateKey)) errors += "Private key must be 32 bytes base64-encoded (44 chars)."
    if (!BASE64_44.matches(peer.publicKey)) errors += "Peer public key must be 32 bytes base64-encoded (44 chars)."
    // Non-null means "present": a blank or whitespace-only value is an error, not an absence
    // (the editor and the parser map absence to null; only a foreign snapshot can carry "").
    peer.presharedKey?.let {
        if (!BASE64_44.matches(it)) errors += "Preshared key must be 32 bytes base64-encoded (44 chars)."
    }
    peerEndpointProblem(peer.endpoint)?.let { errors += it }
    if (peer.allowedIps.isEmpty()) errors += "AllowedIPs must not be empty."
    if (peer.allowedIps.any { !isValidCidr(it) }) {
        errors += "AllowedIPs entries must be CIDR (e.g. 0.0.0.0/0)."
    }
    peer.persistentKeepalive?.let { if (it !in 0..65535) errors += "Persistent keepalive must be 0-65535." }
    interfaceConfig.mtu?.let { if (it < 576 || it > 65535) errors += "MTU must be between 576 and 65535." }
    interfaceConfig.listenPort?.let { if (it !in 1..65535) errors += "Listen port must be 1-65535." }
    interfaceConfig.dnsServers.filter { !isIpLiteral(it) }
        .forEach { errors += "DNS server \"$it\" must be an IP address." }
    interfaceConfig.addresses.forEach {
        if (!isValidCidr(it)) errors += "Interface addresses must be CIDR (e.g. 10.0.0.2/32)."
    }
    when (dns.mode) {
        DnsMode.SECURE -> {
            if (dns.secureType == null) errors += "Secure DNS type must be DoH or DoT."
            val ep = dns.secureEndpoint
            if (ep.isNullOrBlank()) {
                errors += "Secure DNS endpoint must not be empty."
            } else when (dns.secureType) {
                SecureDnsType.DOH -> dohEndpointProblem(ep)?.let { errors += it }
                SecureDnsType.DOT -> {
                    if (parseDotEndpoint(ep) == null) errors += "DoT endpoint must be host or host:port."
                }
                null -> Unit
            }
        }
        DnsMode.TUNNEL -> Unit
    }
    val seenDomains = mutableSetOf<String>()
    dns.rules.forEach { rule ->
        if (!HOSTNAME.matches(rule.domain)) {
            errors += "Split-DNS domain \"${rule.domain}\" is not a valid hostname."
        }
        if (!seenDomains.add(rule.domain.lowercase())) {
            errors += "Duplicate split-DNS domain \"${rule.domain}\"."
        }
    }
    // Both resolver addresses are Vaayil's own (B1): never an interface address, never a DNS server. Parsed
    // literals are compared, so no spelling slips past.
    for (resolver in listOf(RESOLVER_V4, RESOLVER_V6)) {
        if (interfaceConfig.addresses.any { sameAddress(it.substringBefore('/'), resolver) }) {
            errors += "Interface address $resolver is reserved for Vaayil's DNS resolver."
        }
        if (interfaceConfig.dnsServers.any { sameAddress(it, resolver) }) {
            errors += "DNS server $resolver is reserved for Vaayil's resolver."
        }
    }
    return errors
}

enum class SsidDecision { CONNECT, DISCONNECT, NONE }

/**
 * S4: how the Wi-Fi network the device is on authenticates its clients. Only SECURED can be trusted. OPEN (no
 * password, OWE Enhanced Open, OSEN, WEP) and UNKNOWN (the type could not be read) never count as trusted.
 */
enum class WifiSecurity { SECURED, OPEN, UNKNOWN }

data class AutoConnectRules(
    val wifiGuardEnabled: Boolean = false,
    val trustedSsids: Set<String> = emptySet(),
    val disconnectOnTrusted: Boolean = false,
    val connectOnBoot: Boolean = false,
    val autoConnectOnMobileData: Boolean = false,
    val externalControlEnabled: Boolean = false,
    val externalControlToken: String? = null,
)

/** The automation foreground service hosts both watchers: it must run while either rule is on. */
val AutoConnectRules.needsGuardService: Boolean
    get() = wifiGuardEnabled || autoConnectOnMobileData

/**
 * A network is trusted only when its name is on the list AND it is [WifiSecurity.SECURED] (S4): anyone can name an
 * open hotspot after a trusted network, so an OPEN or UNKNOWN network is untrusted whatever it is called (fail safe:
 * the tunnel connects or stays up).
 */
fun AutoConnectRules.decide(ssid: String?, security: WifiSecurity, tunnelConnected: Boolean): SsidDecision = when {
    !wifiGuardEnabled || ssid.isNullOrBlank() -> SsidDecision.NONE
    ssid in trustedSsids && security == WifiSecurity.SECURED ->
        if (disconnectOnTrusted && tunnelConnected) SsidDecision.DISCONNECT else SsidDecision.NONE
    else -> if (!tunnelConnected) SsidDecision.CONNECT else SsidDecision.NONE
}

/**
 * The cellular half of automation: the DEFAULT network being mobile data is the
 * trigger (a SIM being merely present is not). Connect-only — returning to Wi-Fi
 * hands the decision back to [decide] (including disconnect-on-trusted). A busy
 * tunnel (connecting or connected) is never interrupted.
 */
fun AutoConnectRules.mobileDecision(isMobileData: Boolean, tunnelBusy: Boolean): SsidDecision =
    if (autoConnectOnMobileData && isMobileData && !tunnelBusy) SsidDecision.CONNECT
    else SsidDecision.NONE

fun normalizeSsid(raw: String?): String? =
    raw?.removeSurrounding("\"")?.trim()?.takeIf { it.isNotBlank() }
