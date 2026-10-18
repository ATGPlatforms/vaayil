package dev.vaayil.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries
import dev.vaayil.core.ui.Films
import dev.vaayil.core.ui.ScrimDialog
import dev.vaayil.core.ui.Sheet
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumQuietAction
import dev.vaayil.core.ui.monoTextStyle

/**
 * Third-party notices: every Maven dependency plus the bundled fonts and
 * wireguard-go (B-3). Rows are one-film sheets grouped in a single sheet with
 * hairline dividers inset past the names (spec §3.5); tapping a row lifts the
 * license text over the scrim. The plugin's generated resource is untouched.
 */
@Composable
fun LicensesScreen() {
    val libraries by rememberLibraries(R.raw.aboutlibraries)
    var openLibrary by remember { mutableStateOf<Library?>(null) }
    val libs = remember(libraries) {
        libraries?.libraries.orEmpty().sortedBy { it.name?.lowercase() ?: it.uniqueId }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Open-source licences", style = MaterialTheme.typography.headlineSmall)
        // The one-film sheet IS the list's container (the split-tunnel pattern): it
        // draws the group's fill, seam and boundary once, while rows stay keyed, lazy
        // items — the plugin's resource carries ~200 transitive dependencies.
        Sheet(
            films = Films.One,
            modifier = Modifier.fillMaxSize(),
            contentPadding = 0.dp,
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(4.dp),
            ) {
                itemsIndexed(
                    libs,
                    key = { index, library -> "$index:${library.uniqueId}" },
                ) { index, library ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(VellumColors.Hairline),
                        )
                    }
                    LibraryRow(library = library, onClick = { openLibrary = library })
                }
            }
        }
    }
    openLibrary?.let { library ->
        LibraryLicenseDialog(library = library, onDismiss = { openLibrary = null })
    }
}

/** One notice row: library name over the artifact coordinate and version in mono. */
@Composable
private fun LibraryRow(library: Library, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                library.name.ifBlank { library.artifactId },
                style = MaterialTheme.typography.titleSmall,
                color = VellumColors.OnSurface,
                maxLines = 1,
            )
            // `artifactId` is the computed uniqueId:version coordinate.
            Text(
                library.artifactId,
                style = monoTextStyle(),
                color = VellumColors.OnSurfaceVariant,
                maxLines = 1,
            )
            val licenseNames = library.licenses.mapNotNull { it.name }.ifEmpty { null }
            if (licenseNames != null) {
                Text(
                    licenseNames.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = VellumColors.OnSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The license text over the token scrim; long-form copy scrolls inside the sheet. */
@Composable
private fun LibraryLicenseDialog(library: Library, onDismiss: () -> Unit) {
    ScrimDialog(onDismissRequest = onDismiss) {
        Text(
            library.name.ifBlank { library.artifactId },
            style = MaterialTheme.typography.headlineLarge,
            color = VellumColors.OnSurface,
        )
        // A full license (Apache-2.0 ≈ 10k chars) is thousands of dp tall: bound and
        // scroll the body so Done stays on screen — 55% of the viewport (480dp cap)
        // leaves room for the title, the action row and the scrim gutter.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = minOf(LocalConfiguration.current.screenHeightDp.dp * 0.55f, 480.dp))
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            library.licenses.forEach { license ->
                val content = license.licenseContent?.takeIf { it.isNotBlank() }
                if (content != null) {
                    Text(
                        content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = VellumColors.OnSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val url = license.url
                    if (!url.isNullOrBlank()) {
                        Text(
                            url,
                            style = monoTextStyle(),
                            color = VellumColors.OnSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            VellumQuietAction("Done", onDismiss)
        }
    }
}
