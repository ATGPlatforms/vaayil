package dev.vaayil.core.model

import java.net.URI

/**
 * One line about where a profile's DNS goes (F6), for the dashboard, the Profiles hero and the
 * saved rows. Mirrors the tunnel's DNS planner: Secure DNS (a type and an endpoint) names its
 * transport and host, because queries leave through it and not the plain servers. Otherwise the
 * plain servers, or null when the profile sets none (callers print their own fallback). Split-DNS
 * rules add " + split" whenever a resolver runs them.
 */
fun Profile.dnsSummary(allServers: Boolean): String? {
    val endpoint = dns.secureEndpoint.orEmpty()
    val base = when {
        dns.secureInEffect ->
            if (dns.secureType == SecureDnsType.DOT) {
                "DoT ${parseDotEndpoint(endpoint)?.first ?: endpoint}"
            } else {
                val host = runCatching { URI(endpoint).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]")
                "DoH ${host ?: endpoint}"
            }
        interfaceConfig.dnsServers.isNotEmpty() ->
            if (allServers) interfaceConfig.dnsServers.joinToString(", ") else interfaceConfig.dnsServers.first()
        else -> return null
    }
    return if (dns.rules.isNotEmpty()) "$base + split" else base
}
