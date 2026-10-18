package dev.vaayil.core.dns

import dev.vaayil.core.model.DnsSplitRule

enum class SplitVia { TUNNEL, BYPASS }

/** Pure per-domain policy: bypass rules match their domain and every subdomain (label-wise). */
class DnsRuleMatcher(rules: List<DnsSplitRule>) {
    private val bypass = rules.filter { it.bypass }.map { it.domain.lowercase().trimEnd('.') }

    fun classify(qname: String): SplitVia {
        val name = qname.lowercase().trimEnd('.')
        if (bypass.any { name == it || name.endsWith(".$it") }) return SplitVia.BYPASS
        return SplitVia.TUNNEL
    }
}
