package dev.vaayil.feature.profiles

import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.validate

/**
 * Validator-message prefix → the field label the editor shows (F14). Mirrors the messages
 * `Profile.validate()` emits; the core's wording is the contract (every endpoint message
 * keeps the "Endpoint" prefix), and anything unmatched is surfaced verbatim rather than
 * dropped.
 */
private val PREFIX_TO_LABEL: List<Pair<String, String>> = listOf(
    "Profile name" to "Profile name",
    "Private key" to "Private key",
    "Interface address" to "Addresses", // singular prefix also covers the plural CIDR message
    "DNS server" to "DNS servers",
    "Endpoint" to "Endpoint", // also "Endpoint port …"
    "Peer public key" to "Server public key",
    "AllowedIPs" to "Allowed IPs",
    "Secure DNS" to "DNS",
    "DoH endpoint" to "DNS",
    "DoT endpoint" to "DNS",
    "Split-DNS domain" to "DNS",
    "Duplicate split-DNS domain" to "DNS",
    "MTU" to "MTU (Advanced)",
    "Listen port" to "Listen port (Advanced)",
    "Preshared key" to "Preshared key (Advanced)",
    "Persistent keepalive" to "Persistent keepalive (Advanced)",
)

/** Field labels in the order the editor lays them out (ruling R3). */
private val DISPLAY_ORDER: List<String> = listOf(
    "Profile name",
    "Private key",
    "Addresses",
    "DNS servers",
    "Endpoint",
    "Server public key",
    "Allowed IPs",
    "DNS",
    "MTU (Advanced)",
    "Listen port (Advanced)",
    "Preshared key (Advanced)",
    "Persistent keepalive (Advanced)",
)

/**
 * Why Save is off: [errors] (validator messages) and [formatErrors] (the labels
 * [NumericField] reports, e.g. "MTU") mapped to display labels, de-duplicated and sorted
 * into the editor's display order. Messages that match no prefix come back verbatim,
 * after the mapped ones, in first-seen order.
 */
internal fun saveBlockers(errors: List<String>, formatErrors: List<String>): List<String> {
    val mapped = (errors + formatErrors).map { message ->
        PREFIX_TO_LABEL.firstOrNull { (prefix, _) -> message.startsWith(prefix) }?.second ?: message
    }
    return LinkedHashSet(mapped)
        .sortedBy { label -> DISPLAY_ORDER.indexOf(label).takeIf { it >= 0 } ?: Int.MAX_VALUE }
}

/**
 * Null when [draft] may be added to [profile]'s split-DNS rules; otherwise the validator
 * message the draft would trigger (an invalid hostname or a duplicate of an existing
 * rule). Validation stays in :core:model — this only asks it about the draft's
 * incremental effect, so messages the profile already carries are excluded.
 */
internal fun splitDnsDraftError(profile: Profile, draft: String): String? {
    val candidate = profile.copy(
        dns = profile.dns.copy(rules = profile.dns.rules + DnsSplitRule(draft.trim(), bypass = true)),
    )
    return (candidate.validate() - profile.validate().toSet())
        .firstOrNull { it.startsWith("Split-DNS domain") || it.startsWith("Duplicate split-DNS domain") }
}
