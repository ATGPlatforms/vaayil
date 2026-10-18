package dev.vaayil.app

import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.core.ui.Schibsted
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.rememberReducedMotion

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Tokens from DESIGN.md ("Vellum"): a warm paper board — canvas deepened by film
// steps — with sage/slate/lavender accents. The palette is Vaayil's on every
// device, day or night — dynamic color and dark variants are banned (DESIGN.md,
// The Daylight Is the Brand Rule), so this file maps exactly one light scheme.
// Canonical values live in `VellumColors` (:core:ui); this file is the single M3
// mapping of DESIGN.md and changes only in the same commit as it.

private val LightScheme = lightColorScheme(
    primary = VellumColors.Primary,
    onPrimary = Color.White,
    primaryContainer = VellumColors.PrimaryContainer,
    onPrimaryContainer = VellumColors.OnPrimaryContainer,
    secondary = VellumColors.Slate,
    onSecondary = Color.White,
    secondaryContainer = VellumColors.Container2,
    onSecondaryContainer = VellumColors.OnSurface,
    tertiary = VellumColors.Lavender,
    onTertiary = Color.White,
    tertiaryContainer = VellumColors.Container3,
    onTertiaryContainer = VellumColors.OnSurface,
    error = VellumColors.M3Error,
    onError = Color.White,
    errorContainer = VellumColors.Error,
    onErrorContainer = VellumColors.OnError,
    background = VellumColors.Canvas,
    onBackground = VellumColors.OnSurface,
    surface = VellumColors.Canvas,
    onSurface = VellumColors.OnSurface,
    surfaceVariant = VellumColors.Container4,
    onSurfaceVariant = VellumColors.OnSurfaceVariant,
    // Load-bearing boundaries carry stroke-3; the decorative hairline rides
    // outlineVariant (dividers).
    outline = VellumColors.Stroke3,
    outlineVariant = VellumColors.Hairline,
    scrim = VellumColors.Scrim,
    // Vellum's depth is tonal (films), not tinted: no surface tint.
    surfaceTint = VellumColors.Canvas,
    // The film ladder, laid over the M3 container slots.
    surfaceContainerLowest = VellumColors.Canvas,
    surfaceContainerLow = VellumColors.Container1,
    surfaceContainer = VellumColors.Container2,
    surfaceContainerHigh = VellumColors.Container3,
    surfaceContainerHighest = VellumColors.Container4,
    inverseSurface = VellumColors.OnSurface,
    inverseOnSurface = VellumColors.Canvas,
    inversePrimary = VellumColors.SageMark,
)

// The Vellum type scale (DESIGN.md §3), Schibsted Grotesk everywhere: 600 is the
// workhorse weight, the 11sp uppercase label runs 700, tracking in em per role.
internal val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.006).em),
    headlineLarge = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.004).em),
    headlineMedium = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.004).em),
    headlineSmall = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.002).em),
    titleLarge = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.002.em),
    titleMedium = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.004.em),
    titleSmall = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.010.em),
    bodyLarge = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.004.em),
    bodyMedium = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.004.em),
    bodySmall = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.014.em),
    labelLarge = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.010.em),
    labelMedium = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.060.em),
    labelSmall = TextStyle(fontFamily = Schibsted, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.115.em),
)

// Vellum radii (DESIGN.md): inputs 8dp, cards/sheets 10dp, hero 14dp; pills draw
// 999dp explicitly at call sites.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

object VaayilTheme {
    /** Light-only by design (The Daylight Is the Brand Rule) — no dark parameter exists. */
    @Composable
    fun Content(content: @Composable () -> Unit) {
        val reducedMotion = rememberReducedMotion()
        CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
            MaterialTheme(
                colorScheme = LightScheme,
                typography = AppTypography,
                shapes = AppShapes,
                content = content,
            )
        }
    }
}
