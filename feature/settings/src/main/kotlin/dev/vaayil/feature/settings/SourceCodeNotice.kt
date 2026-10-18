package dev.vaayil.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumQuietAction

/** Vaayil's source repository, opened from the About sheet's Source code row. */
const val VAAYIL_SOURCE_URL = "https://github.com/ATGPlatforms/vaayil"

/**
 * Vaayil's own legal notice in the About sheet (GPL §0): the copyright line, the
 * free-software grant — share and change it under GPL-3.0-or-later — and the missing
 * warranty, with the separate actions that open the source and open the licence
 * bundled in the app. The third-party notices row above stays; this notice is the
 * app's own licence.
 */
@Composable
internal fun SourceCodeNotice(
    onOpenSourceCode: () -> Unit,
    onOpenAppLicense: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SettingsRow(
            icon = {
                Icon(
                    Icons.Rounded.Code,
                    contentDescription = null,
                    tint = VellumColors.OnSurfaceVariant,
                )
            },
            title = "Source code",
            supporting = "The complete source on GitHub",
            onClick = onOpenSourceCode,
        )
        Text(
            "Copyright © 2026 Ajay R. Vaayil is free software: you may share and change it " +
                "under the GNU General Public License, version 3 or later. It comes with no warranty.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VellumQuietAction("View licence", onOpenAppLicense)
    }
}
