package dev.vaayil.core.config

import dev.vaayil.core.model.isIpLiteral

/** Longest key-slot token the parser will name in an error; anything longer is key material. */
private const val MAX_DIRECTIVE_NAME_LENGTH = 24

class WgConfigParser {
    class ParseException(val lineNo: Int, message: String) : Exception(message)

    fun parse(text: String): WgQuickConfig {
        // Windows editors (Notepad) write a UTF-8 BOM; trim() does not remove U+FEFF, so a
        // config that starts with one would otherwise fail as "Key outside any [Section]" (F19).
        val body = text.removePrefix("\uFEFF")
        // F19: a binary or mis-encoded file (a NUL anywhere, or bytes that did not decode as
        // UTF-8 and became U+FFFD in text the parser reads; a UTF-16 file has both) or text
        // without an [Interface] header is not a config with one bad line. Refuse it before any
        // line is parsed instead of naming line 1. Comments are never read, so a comment saved
        // in Latin-1 (a city name with an accent, say) imports as it did before this check.
        if ('\u0000' in body ||
            body.lines().any { '\uFFFD' in uncommented(it) } ||
            body.lines().none { isInterfaceHeader(it) }
        ) {
            throw ParseException(0, "That doesn't look like a WireGuard config.")
        }
        val iface = mutableMapOf<String, String>()
        val peer = mutableMapOf<String, String>()
        val lineOf = mutableMapOf<String, Int>()
        var section: String? = null
        var seenInterface = false
        var seenPeer = false
        body.lines().forEachIndexed { idx, raw ->
            val lineNo = idx + 1
            val line = uncommented(raw).trim()
            if (line.isEmpty()) return@forEachIndexed
            val header = sectionNameOf(raw)
            if (header != null) {
                section = header
                if (section !in setOf("interface", "peer")) {
                    throw ParseException(lineNo, "Unknown section [$section]")
                }
                if (section == "peer" && seenPeer) {
                    throw ParseException(lineNo, "Vaayil supports one [Peer] per profile; this config has another at line $lineNo.")
                }
                if (section == "interface" && seenInterface) {
                    throw ParseException(lineNo, "Duplicate [Interface] section (line $lineNo)")
                }
                if (section == "interface") seenInterface = true else seenPeer = true
                return@forEachIndexed
            }
            val current = section ?: throw ParseException(lineNo, "Key outside any [Section] (line $lineNo)")
            val sep = line.indexOf('=')
            if (sep <= 0) throw ParseException(lineNo, "Expected \"Key = Value\" (line $lineNo)")
            val key = line.substring(0, sep).trim().lowercase()
            // A directive name is a bare token: anything else means the line was never a
            // "Key = Value" pair (its "value" was swallowed into the key), so refuse it
            // WITHOUT echoing the payload — configs carry private keys.
            if (key.isEmpty() || key.any { it.isWhitespace() }) {
                throw ParseException(lineNo, "Expected \"Key = Value\" (line $lineNo)")
            }
            val value = line.substring(sep + 1).trim()
            if (value.isEmpty()) throw ParseException(lineNo, "Empty value${named(key)} (line $lineNo)")
            val target = if (current == "interface") iface else peer
            if (target.containsKey(key)) throw ParseException(lineNo, "Duplicate key${named(key)} (line $lineNo)")
            val supportedIface = setOf("privatekey", "address", "dns", "mtu", "listenport")
            val supportedPeer = setOf("publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive")
            if (current == "interface" && key !in supportedIface) {
                throw ParseException(lineNo, "Unsupported [Interface] directive${named(key)} (line $lineNo)")
            }
            if (current == "peer" && key !in supportedPeer) {
                throw ParseException(lineNo, "Unsupported [Peer] directive${named(key)} (line $lineNo)")
            }
            target[key] = value
            lineOf[key] = lineNo
        }
        // No "missing [Interface]" check here: the refusal above already guarantees the header.
        if (!seenPeer) throw ParseException(body.lines().size, "Missing [Peer] section")
        if (!iface.containsKey("privatekey")) throw ParseException(0, "Missing PrivateKey in [Interface]")
        if (!peer.containsKey("publickey")) throw ParseException(body.lines().size, "Missing PublicKey in [Peer]")
        if (!peer.containsKey("endpoint")) throw ParseException(body.lines().size, "Missing Endpoint in [Peer]")
        if (!peer.containsKey("allowedips")) throw ParseException(body.lines().size, "Missing AllowedIPs in [Peer]")
        val dnsEntries = splitCsv(iface["dns"])
        return WgQuickConfig(
            interfaceSection = WgInterfaceSection(
                privateKey = iface.getValue("privatekey"),
                addresses = splitCsv(iface["address"]),
                dnsServers = dnsEntries.filter(::isIpLiteral),
                mtu = number("mtu", iface, lineOf),
                listenPort = number("listenport", iface, lineOf),
            ),
            peer = WgPeerSection(
                publicKey = peer.getValue("publickey"),
                presharedKey = peer["presharedkey"],
                endpoint = peer.getValue("endpoint"),
                allowedIps = splitCsv(peer.getValue("allowedips")),
                persistentKeepalive = number("persistentkeepalive", peer, lineOf),
            ),
            ignoredDnsEntries = dnsEntries.filterNot(::isIpLiteral),
        )
    }

    /** A numeric directive parses strictly or fails loud naming its line: "1420abc" must never become null. */
    private fun number(key: String, section: Map<String, String>, lineOf: Map<String, Int>): Int? {
        val raw = section[key] ?: return null
        val lineNo = lineOf.getValue(key)
        return raw.toIntOrNull() ?: throw ParseException(lineNo, "Invalid number${named(key)} (line $lineNo)")
    }

    private fun splitCsv(value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /**
     * The part of a line the parser reads: wg-quick and wireguard-android's config parser
     * (`com.wireguard.config.Config`) strip everything after '#', anywhere on the line. The
     * loop, the header check and the U+FFFD refusal share this one rule.
     */
    private fun uncommented(raw: String): String = raw.substringBefore('#')

    /**
     * A header line's section name by the parser's one rule (T11, sweep 2): comment
     * stripped, trimmed, "[Name]" reduced to its lower-cased name — or null when the line is
     * not a section header. The loop and [isInterfaceHeader] share this, so they can never
     * disagree about what a header is again.
     */
    private fun sectionNameOf(raw: String): String? {
        val line = uncommented(raw).trim()
        if (!line.startsWith("[")) return null
        return line.substringBefore(']').removePrefix("[").trim().lowercase()
    }

    /** An "[Interface]" header by the loop's own rule (T11): [sectionNameOf] says "interface". */
    private fun isInterfaceHeader(raw: String): Boolean = sectionNameOf(raw) == "interface"

    /**
     * `` ` "name"` `` for a directive-shaped key, empty for anything else: the key slot holds
     * whatever preceded the first '=', so a key pasted without its "Key = " prefix arrives
     * here as one long token and the error must name the line, never that token.
     */
    private fun named(key: String): String =
        if (key.length <= MAX_DIRECTIVE_NAME_LENGTH && key.all { it in 'a'..'z' || it in '0'..'9' }) {
            " \"$key\""
        } else {
            ""
        }
}
