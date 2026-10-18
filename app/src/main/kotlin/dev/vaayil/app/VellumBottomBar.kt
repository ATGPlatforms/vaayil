package dev.vaayil.app

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vaayil.core.ui.CappedFontScale
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumNavLabelStyle

private data class TabSpec(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabSpec("dashboard", "Dashboard", Icons.Rounded.Shield),
    TabSpec("profiles", "Profiles", Icons.Rounded.VpnKey),
    TabSpec("split-tunnel", "Split Tunnel", Icons.Rounded.AltRoute),
    TabSpec("backup", "Backup", Icons.Rounded.SaveAlt),
)

/**
 * The Vellum bottom bar (DESIGN.md §5): the docked floor of every tab — full-width
 * `container-1` fill with a 1dp `hairline` rule along its top edge, no radius, no
 * shadow — carrying icon+label destinations; the selected node wears a 56×32dp
 * `primary-container` capsule behind the 24dp icon only (spec §3.4.3
 * VellumBottomBar, ruling B1). Rendered on tab destinations only — pushed routes
 * (editor, import, settings, onboarding) hide it. 68dp of content at normal text
 * sizes; the label line grows with the font scale, and the bar grows with it (F29).
 */
@Composable
fun VellumBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    // Scaffold does not inset the bottomBar slot: the bar clears the gesture area
    // itself, its paper body running under it.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VellumColors.Container1)
            .drawBehind {
                // The bar's one rule: 1dp hairline across the top edge.
                drawRect(VellumColors.Hairline, size = Size(size.width, 1.dp.toPx()))
            }
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 68dp is a floor: every cell takes the tallest cell's content height
                // (8 + 32 + 4 + label + 8), so a taller label line grows the bar (F29).
                .heightIn(min = 68.dp)
                .height(IntrinsicSize.Min)
                .selectableGroup(),
        ) {
            TABS.forEach { tab ->
                val selected = currentRoute == tab.route
                val contentTint =
                    if (selected) VellumColors.OnPrimaryContainer else VellumColors.OnSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("vellum_nav_cell_${tab.route}")
                        .selectable(
                            selected = selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null, // the capsule is the state feedback — never a ripple
                            role = Role.Tab,
                            onClick = { onSelect(tab.route) },
                        )
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .testTag("vellum_nav_capsule_${tab.route}")
                            .size(width = 56.dp, height = 32.dp)
                            .background(
                                if (selected) VellumColors.PrimaryContainer else Color.Transparent,
                                RoundedCornerShape(16.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = null, // the label below names the tab
                            tint = contentTint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // One line, font scale capped at 1.15: the largest at which all four labels fit
                    // side by side at 360dp (F29: "Dashbo" at 2.0). The ellipsis only guards
                    // windows narrower than 320dp.
                    CappedFontScale(1.15f) {
                        Text(
                            tab.label,
                            style = VellumNavLabelStyle.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = contentTint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
