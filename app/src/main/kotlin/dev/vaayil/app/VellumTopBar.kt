package dev.vaayil.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vaayil.core.ui.VaayilLogoMark
import dev.vaayil.core.ui.VellumColors

/**
 * The Vellum top bar (DESIGN.md §3): sits on the bare board — 24dp logo in a
 * `container-2` square (fill only: sub-48dp decorative tiles carry no seam,
 * device-fix amendment), wordmark at titleLarge, quiet gear (spec §3.4.3
 * VellumTopBar). Shown on every tab; the settings gear is the single entry to
 * Settings. DEVICE-FIX 2026-09-21 (review finding 13): vertical padding 12→8dp
 * (72→64dp) and a 1dp hairline under the bar only while the tab's content is
 * scrolled, so scrolled content no longer slices against an edgeless bar.
 */
@Composable
fun VellumTopBar(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    scrolled: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(VellumColors.Container2, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                VaayilLogoMark(
                    modifier = Modifier.size(24.dp),
                    // Sage glyph on the container-2 square (spec §3.4.3 VellumTopBar row).
                    container = VellumColors.Primary,
                    glyph = VellumColors.Primary,
                    notch = VellumColors.Container2,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Vaayil",
                style = MaterialTheme.typography.titleLarge,
                color = VellumColors.OnSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = VellumColors.OnSurfaceVariant,
                )
            }
        }
        if (scrolled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(VellumColors.Hairline),
            )
        }
    }
}

/** Centers and caps tab content width so phone layouts don't stretch on tablets. Narrower
 *  children (the privacy column, 520dp) centre inside the band instead of hugging its edge. */
@Composable
fun TabContainer(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            content()
        }
    }
}
