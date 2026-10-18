package dev.vaayil.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The shared tab header (device review 2026-09-21, C9/X1): ONE header construction
 * so every tab's title sits at the same offset — 12dp below the top bar — instead
 * of the three hand-built headers that dropped the Profiles title 10.5dp relative
 * to Split Tunnel's and split the title-to-intro gap 23px vs 68px.
 *
 * Title in `headlineSmall` (18sp/600); 4dp below it, the [intro] in `bodyMedium`
 * `on-surface-variant`. The trailing [action] slot aligns to the title's FIRST
 * BASELINE (not its vertical centre), so a tall button beside the title never
 * drags the title off its grid. The header ends 16dp before the tab's content —
 * that gap is baked in, so callers never pad after the header.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabHeader(
    title: String,
    intro: String?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 12.dp)) {
        // U3 (sweep 2): the title is measured against the whole line first, so it wraps at
        // its spaces — never inside a word — and the action, which cannot share the line,
        // moves under it. While they do share it, the C9 first-baseline alignment holds.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            // A dropped action keeps an 8dp air gap under the title instead of sitting
            // flush against it (final review minor, the 2.0 profiles fixture).
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = VellumColors.OnSurface,
                modifier = Modifier.alignByBaseline(),
            )
            action?.let { content ->
                Box(modifier = Modifier.alignByBaseline()) { content() }
            }
        }
        if (intro != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                intro,
                style = MaterialTheme.typography.bodyMedium,
                color = VellumColors.OnSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
