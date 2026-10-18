package dev.vaayil.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.vaayil.core.ui.Films
import dev.vaayil.core.ui.Sheet
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumEyebrow

private val guideSteps: List<Pair<ImageVector, String>> = listOf(
    Icons.Rounded.Settings to "Open Android Settings → Network & internet → VPN",
    Icons.Rounded.Home to "Tap the gear next to \"Vaayil\"",
    Icons.Rounded.Lock to "Enable \"Always-on VPN\"",
    // GppGood: this step ENABLES a blocking switch; the old Clear glyph read as cancel.
    Icons.Rounded.GppGood to "Enable \"Block connections without VPN\"",
)

/**
 * The kill-switch guide (spec §3.5): each step is a one-film sheet; `Step N` is an
 * uppercase labelSmall eyebrow and the instruction body reads at bodyLarge. The
 * deep link into Android's VPN settings is the one 52dp primary.
 */
@Composable
fun KillSwitchGuideScreen(onOpenVpnSettings: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Always-on VPN (kill switch)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Android can block apps' connections whenever Vaayil isn't connected. Turn these on in Android settings:",
            style = MaterialTheme.typography.bodyMedium,
            color = VellumColors.OnSurfaceVariant,
        )
        guideSteps.forEachIndexed { index, (icon, text) ->
            GuideStepSheet(step = index + 1, icon = icon, text = text)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = VellumColors.OnSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "With these on, Android blocks apps' connections if the tunnel drops, and Vaayil reconnects after a reboot. " +
                    "Apps you exclude in Split Tunnel can't connect at all. Android doesn't block DNS lookups, though: " +
                    "while the tunnel is down, and for excluded apps, the network you're on can still see the names apps look up.",
                style = MaterialTheme.typography.bodySmall,
                color = VellumColors.OnSurfaceVariant,
            )
        }
        // Deep link: the guide used to make users find Android's VPN settings by hand.
        VellumButton(
            label = "Open VPN settings",
            onClick = onOpenVpnSettings,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.padding(bottom = 16.dp))
    }
}

/** One guide step: 24dp functional icon, uppercase eyebrow, bodyLarge instruction. */
@Composable
private fun GuideStepSheet(step: Int, icon: ImageVector, text: String) {
    Sheet(
        films = Films.One,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = VellumColors.OnSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column {
                VellumEyebrow("Step $step")
                Text(text, style = MaterialTheme.typography.bodyLarge, color = VellumColors.OnSurface)
            }
        }
    }
}
