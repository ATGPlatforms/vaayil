package dev.vaayil.core.tunnel.wg

import dev.vaayil.core.model.isIpLiteral

/**
 * F33: the hostname a switch must resolve before it touches the live tunnel, or null when there is nothing
 * to look up. That covers an IP literal (`198.51.100.7:51820`, `[2001:db8::1]:51820`) and an endpoint with
 * no `host:port` shape (no port, unbracketed IPv6), which the backend refuses on its own in
 * [WgConfigMapper]. One trailing root dot is dropped.
 */
internal fun endpointHostname(endpoint: String): String? {
    val trimmed = endpoint.trim()
    if (trimmed.startsWith("[")) return null // brackets only ever hold an IPv6 literal
    val colon = trimmed.lastIndexOf(':')
    if (colon <= 0) return null
    val host = trimmed.substring(0, colon).removeSuffix(".")
    return host.takeIf { it.isNotEmpty() && ':' !in it && !isIpLiteral(it) }
}
