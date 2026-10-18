package dev.vaayil.feature.tunnel

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelNotice

/**
 * U4 (sweep 2): the live session's notice, under the disc's hint line while CONNECTED, in the
 * error colour, centred like the error line above it. Renders nothing otherwise, so no existing
 * dashboard fixture changes (spec §3.1: the core plan adds no screenshot fixture).
 */
@Composable
fun DashboardNotice(ui: HomeUiState) {
    val notice = ui.status.notice
    if (ui.status.phase != ConnectionPhase.CONNECTED || notice == null) return
    Text(
        when (notice) {
            TunnelNotice.SettingsNotApplied -> "New settings aren't applied yet. Reconnect to apply them."
            TunnelNotice.DnsNotAnswering -> "DNS isn't answering. Reconnect to fix it."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}
