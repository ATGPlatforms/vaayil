package dev.vaayil.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Caps the font scale [content] sees at [maxScale] (F29, spec U6). A smaller system scale
 * passes through untouched, and the pixel density never changes. Only for the few slots that
 * cannot grow with their text: the hero disc label, the bottom-bar labels, an app icon's
 * initial. Everything else wraps or grows instead.
 */
@Composable
fun CappedFontScale(maxScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = if (density.fontScale <= maxScale) density else Density(density.density, maxScale)
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}
