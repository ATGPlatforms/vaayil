package dev.vaayil.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.vaayil.core.ui.vellumSeam

/**
 * The Play prominent disclosure for the Wi-Fi guard's background location use (spec §3.4,
 * copy verbatim): names the data (network name via location), the purpose (trusted vs
 * untrusted), the background use, and takes an explicit tap before any system prompt.
 * The scaffold is the shared [ScrimDialog]; the copy rides the mandated `warning`
 * surface with a 1dp `warning-mark` seam. Outside taps call nothing — they must not
 * count as consent or refusal.
 * At large text the copy scrolls inside that surface, so the seam stays closed and Not now
 * and Continue stay in view, which matters because they are the dialog's only way out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationDisclosureDialog(onContinue: () -> Unit, onNotNow: () -> Unit) {
    ScrimDialog(onDismissRequest = {}) {
        val shape = RoundedCornerShape(10.dp)
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .background(VellumColors.Warning, shape)
                .vellumSeam(shape, VellumColors.WarningMark)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Wi-Fi network access", style = MaterialTheme.typography.headlineLarge, color = VellumColors.OnWarning)
            Text(
                "To tell trusted Wi-Fi from untrusted, Vaayil reads the current network's " +
                    "name. Android only exposes it through location access, and the guard " +
                    "runs in the background, so Vaayil needs location set to 'Allow all the " +
                    "time'. The name is compared on this device only; Vaayil never stores " +
                    "your location or sends anything anywhere.",
                style = MaterialTheme.typography.bodyLarge,
                color = VellumColors.OnWarning,
            )
        }
        // Ends at the right like Vaayil's other dialogs; at large font scales Continue drops
        // below Not now instead of breaking inside the word (F29).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            VellumQuietAction("Not now", onNotNow)
            VellumButton("Continue", onContinue)
        }
    }
}
