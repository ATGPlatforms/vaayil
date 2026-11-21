package dev.vaayil.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * The Vellum dialog scaffold (spec §3.3.1): a full-bleed token scrim — tap outside
 * dismisses — with the platform dim zeroed so the scrim renders exactly once, and a
 * tap-absorbing [LiftedSheet] floating on it, capped at 560dp. Every floating
 * dialog composes this; nobody re-derives the scaffold.
 *
 * Pass a no-op [onDismissRequest] for consent dialogs where outside taps must not
 * count as either consent or refusal (both prominent disclosures: the Wi-Fi guard's
 * and the VPN connection's).
 */
@Composable
fun ScrimDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // w8: the window must not fit the system windows, or the platform pans it under
            // the keyboard and the sheet never shrinks. With this off, the insets below carry
            // the keyboard (and the bars) into the layout.
            decorFitsSystemWindows = false,
        ),
    ) {
        // The token scrim renders exactly once: the platform dim is zeroed so the
        // lifted sheet floats over `Scrim`, not scrim-over-platform-dim (DESIGN.md §3).
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VellumColors.Scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                )
                // The scrim stays full-bleed; the sheet lays out clear of the bars and the
                // keyboard, so a dialog's scroll area shrinks and its buttons stay visible (w8).
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            LiftedSheet(
                modifier = modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    // Absorb taps so touching the sheet doesn't dismiss through the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                content = content,
            )
        }
    }
}
