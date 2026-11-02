package dev.vaayil.feature.settings

import android.os.Build
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

internal const val LOCATION_DISCLOSURE_TITLE = "Location access"

/**
 * The disclosure's body. [readsNearbyNetworks] (API 30 and below) adds the nearby-networks sentence before the
 * last one; [offersAllowAllTheTime] (API 29 and up) names Android's 'Allow all the time' option, which API 28 and
 * below don't offer because a location grant there already covers background use.
 */
internal fun locationDisclosureText(readsNearbyNetworks: Boolean, offersAllowAllTheTime: Boolean): String {
    val nearbyNetworks = if (readsNearbyNetworks) {
        "To tell whether the network has a password, it also reads the list of nearby Wi-Fi networks. "
    } else {
        ""
    }
    val opening = "Vaayil accesses location data to enable Auto-connect on untrusted Wi-Fi and " +
        "Disconnect on trusted networks, even when the app is closed or not in use. "
    val location = "Android counts the name of the Wi-Fi network you're on as location data, so " +
        if (offersAllowAllTheTime) {
            "these rules need location set to 'Allow all the time'. "
        } else {
            "these rules need location access. "
        }
    val onDevice = "Vaayil checks that name against your trusted list on this device. "
    val neverLeaves = "It never stores your location data or transmits it anywhere, and keeps " +
        "no history of networks."
    return opening + location + onDevice + nearbyNetworks + neverLeaves
}

/**
 * Play's prominent disclosure for the Wi-Fi guard's background location use (spec §3.4,
 * worded for Y1). Its first sentence follows Play's recommended form and names every feature
 * that uses location in the background, so a new such feature must be added to it. It says
 * "accesses", not "collects", because no location data leaves the device. On API 30 and
 * below it adds the nearby-networks sentence ([readsNearbyNetworks]). On API 28 and below,
 * where a location grant already covers background use and Android offers no
 * 'Allow all the time', the second sentence asks for location access instead
 * ([offersAllowAllTheTime]).
 * The scaffold is the shared [ScrimDialog]; the copy rides the mandated `warning`
 * surface with a 1dp `warning-mark` seam. Outside taps call nothing — they must not
 * count as consent or refusal.
 * At large text the copy scrolls inside that surface, so the seam stays closed and Not now
 * and Continue stay in view, which matters because they are the dialog's only way out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationDisclosureDialog(
    onContinue: () -> Unit,
    onNotNow: () -> Unit,
    readsNearbyNetworks: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
    offersAllowAllTheTime: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
) {
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
            Text(LOCATION_DISCLOSURE_TITLE, style = MaterialTheme.typography.headlineLarge, color = VellumColors.OnWarning)
            Text(
                locationDisclosureText(readsNearbyNetworks, offersAllowAllTheTime),
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
