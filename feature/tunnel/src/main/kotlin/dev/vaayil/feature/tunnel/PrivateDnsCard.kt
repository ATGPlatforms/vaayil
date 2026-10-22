package dev.vaayil.feature.tunnel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumQuietAction
import dev.vaayil.core.ui.vellumSeam

/**
 * T-8 (spec §3.2): the host Android's Private DNS is set to, while a tunnel is up and the VPN
 * network itself lists a DNS server — the case where every app's lookups go to that provider
 * instead of the profile's DNS. Null everywhere else: no host, no live tunnel, a VPN network
 * without a DNS server, or a host the user already dismissed ("Got it", case-insensitive).
 * It never reads the dashboard's pick or the handshake state: the warning is about Android's
 * setting, not about which profile the disc would act on.
 */
internal fun privateDnsCardHost(ui: HomeUiState): String? {
    val host = ui.privateDnsHost ?: return null
    if (ui.status.phase != ConnectionPhase.CONNECTED) return null
    if (!ui.vpnHasDnsServer) return null
    if (host.equals(ui.privateDnsDismissedHost, ignoreCase = true)) return null
    return host
}

/**
 * T-8 (spec §3.2): the dashboard's Private DNS warning, the Settings `WarningNote` surface
 * (warning fill, 1dp warning-mark seam, body in on-warning) carrying the two actions. The
 * hostname is one monospace span inside the single body [Text], so TalkBack reads the whole
 * sentence at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrivateDnsCard(
    host: String,
    onOpenNetworkSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VellumColors.Warning, shape)
            .vellumSeam(shape, VellumColors.WarningMark)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            buildAnnotatedString {
                append("Android's Private DNS is set to ")
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(host) }
                append(", so apps send their DNS lookups there, not to the connected profile's DNS. ")
                append("If the tunnel can't reach that provider, they fail. To use the profile's DNS, set Private DNS to Automatic or Off.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = VellumColors.OnWarning,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VellumQuietAction("Open network settings", onOpenNetworkSettings)
            VellumQuietAction("Got it", onDismiss)
        }
    }
}
