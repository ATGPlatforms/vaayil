package dev.vaayil.feature.tunnel

import java.util.Locale

/** Human byte totals for telemetry ("1.4 GiB"). Tests: FormatBytesTest. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val pattern = if (value >= 100.0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[unit])
}

/** Age of the last handshake ("42s ago"), null before the first one. */
fun formatHandshakeAge(handshakeAt: Long?): String? {
    handshakeAt ?: return null
    val ageSeconds = ((System.currentTimeMillis() - handshakeAt) / 1000L).coerceAtLeast(0L)
    return when {
        ageSeconds < 60L -> "${ageSeconds}s ago"
        ageSeconds < 3600L -> "${ageSeconds / 60L}m ago"
        ageSeconds < 86400L -> "${ageSeconds / 3600L}h ago"
        else -> "${ageSeconds / 86400L}d ago"
    }
}
