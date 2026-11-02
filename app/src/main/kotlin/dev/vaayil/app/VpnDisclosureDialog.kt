package dev.vaayil.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vaayil.core.ui.ScrimDialog
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumQuietAction

internal const val VPN_DISCLOSURE_TITLE = "VPN connection"

/**
 * Y2: Play's VpnService prominent disclosure, approved word for word. Every piece but the
 * last ends in the space before the next, so the pieces join into one paragraph;
 * `VpnDisclosureCopyTest` pins the whole text.
 */
internal const val VPN_DISCLOSURE_TEXT =
    "Vaayil uses Android's VpnService to send your device's connections through an encrypted WireGuard " +
        "tunnel to the server in your profile. Those connections and your DNS lookups pass through Vaayil " +
        "only on their way to that server and to the DNS servers you set. Vaayil keeps no logs of them, " +
        "shares them with no one and sends nothing to its developer. Next, Android asks you to allow the " +
        "VPN connection."

/**
 * Y2: the VPN prominent disclosure Play's VpnService policy wants before Android's consent
 * dialog — what passes through the VPN, and a tap to accept. A neutral lifted sheet on the
 * shared [ScrimDialog]: the copy sits on the sheet itself, with no `warning` surface or
 * `warning-mark` seam, and the dismiss is a no-op, because outside taps and Back must not
 * count as either answer. Not now and Accept are the dialog's only way out. At large text
 * the copy scrolls and the buttons stay in view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VpnDisclosureDialog(onAccept: () -> Unit, onNotNow: () -> Unit) {
    ScrimDialog(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(VPN_DISCLOSURE_TITLE, style = MaterialTheme.typography.headlineLarge, color = VellumColors.OnSurface)
            Text(
                VPN_DISCLOSURE_TEXT,
                style = MaterialTheme.typography.bodyLarge,
                color = VellumColors.OnSurfaceVariant,
            )
        }
        // Ends at the right like the location disclosure; at large font scales Accept drops
        // below Not now instead of breaking inside the word (F29).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            VellumQuietAction("Not now", onNotNow)
            VellumButton("Accept", onAccept)
        }
    }
}
