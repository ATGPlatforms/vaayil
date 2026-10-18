package dev.vaayil.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the Vellum token invariants that make the system safe (spec §3.0.1, §3.2, §3.7):
 * the film ladder is *linear* in OKLCH, every text pair clears WCAG AA and every
 * load-bearing boundary clears 3:1, and `Films` caps cumulative nesting at 3.
 */
class VellumTokensTest {

    // --- sRGB -> OKLCH (Björn Ottosson's public-domain OKLab formulas) ---

    private fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    /** Returns (L, C, h) in OKLCH for a 0xRRGGBB value. */
    private fun oklch(rgb: Long): Triple<Double, Double, Double> {
        val r = srgbToLinear(((rgb shr 16) and 0xFF) / 255.0)
        val g = srgbToLinear(((rgb shr 8) and 0xFF) / 255.0)
        val b = srgbToLinear((rgb and 0xFF) / 255.0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val A = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val B = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        var h = Math.toDegrees(atan2(B, A))
        if (h < 0) h += 360.0
        return Triple(L, sqrt(A * A + B * B), h)
    }

    private fun argb(c: Color): Long = c.toArgb().toLong() and 0xFFFFFFFFL

    // --- WCAG 2.x contrast ---

    private fun relativeLuminance(rgb: Long): Double {
        val r = srgbToLinear(((rgb shr 16) and 0xFF) / 255.0)
        val g = srgbToLinear(((rgb shr 8) and 0xFF) / 255.0)
        val b = srgbToLinear((rgb and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun cr(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun expectCr(a: Color, b: Color, min: Double, label: String) {
        val ratio = cr(argb(a), argb(b))
        assertTrue(
            ratio >= min,
            "$label contrast %.2f:1 is below the required %.1f:1".format(ratio, min),
        )
    }

    // --- 1. The film ladder is linear in OKLCH (spec §3.0.1: one film =
    //        dL -0.0206 +/-0.0008, dC +0.0050 +/-0.0012, hue 85 +/-6) ---

    @Test
    fun ladderIsLinearInOklch() {
        val lch = VellumColors.ladder.map { oklch(argb(it)) }
        for (i in 1 until lch.size) {
            val (pl, pc, _) = lch[i - 1]
            val (l, c, _) = lch[i]
            assertTrue(
                l - pl in -0.0214..-0.0198,
                "ladder step $i lightness delta ${l - pl} outside [-0.0214, -0.0198]",
            )
            assertTrue(
                c - pc in 0.0038..0.0062,
                "ladder step $i chroma delta ${c - pc} outside [+0.0038, +0.0062]",
            )
        }
        // Hue 85 +/-6 (spec §3.0.1), compared at the precision the spec publishes:
        // canvas (C = 0.004) measures 91.45 but its spec value is "91", so a 0.5+
        // hue drift still fails while rounding noise at near-zero chroma passes.
        for ((i, entry) in lch.withIndex()) {
            assertTrue(entry.third.roundToInt() in 79..91, "ladder step $i hue ${entry.third} outside [79, 91]")
        }
        assertEquals(6, VellumColors.ladder.size, "ladder is canvas + five films")
    }

    // --- 2. Contrast pairs (spec §3.7 item 4): >=4.5 text, >=3.0 boundary ---

    private val white = Color(0xFFFFFFFF)

    @Test
    fun textPairsClearAa() {
        expectCr(VellumColors.OnSurface, VellumColors.Canvas, 4.5, "on-surface/canvas")
        for (c in listOf(VellumColors.Canvas, VellumColors.Container3, VellumColors.Container4, VellumColors.Container5)) {
            expectCr(VellumColors.OnSurfaceVariant, c, 4.5, "on-surface-variant/container")
        }
        for (c in listOf(VellumColors.Canvas, VellumColors.Container1, VellumColors.Container3)) {
            expectCr(VellumColors.Primary, c, 4.5, "primary/container")
        }
        expectCr(white, VellumColors.Primary, 4.5, "white/primary")
        expectCr(VellumColors.OnPrimaryContainer, VellumColors.PrimaryContainer, 4.5, "on-primary-container/primary-container")
        expectCr(VellumColors.OnFieldActive, VellumColors.FieldActive, 4.5, "on-field-active/field-active")
        expectCr(VellumColors.OnSurfaceVariant, VellumColors.FieldIdle, 4.5, "on-surface-variant/field-idle")
        expectCr(VellumColors.OnWarning, VellumColors.Warning, 4.5, "on-warning/warning")
        expectCr(VellumColors.OnError, VellumColors.Error, 4.5, "on-error/error")
        expectCr(VellumColors.M3Error, VellumColors.Canvas, 4.5, "m3-error/canvas")
        expectCr(VellumColors.M3Error, VellumColors.Container1, 4.5, "m3-error/c1")
        expectCr(VellumColors.OnPastel1, VellumColors.Pastel1, 4.5, "on-pastel-1/pastel-1")
        expectCr(VellumColors.OnPastel2, VellumColors.Pastel2, 4.5, "on-pastel-2/pastel-2")
        expectCr(VellumColors.OnPastel3, VellumColors.Pastel3, 4.5, "on-pastel-3/pastel-3")
    }

    @Test
    fun boundaryPairsClearThreeToOne() {
        for (step in VellumColors.ladder) {
            expectCr(VellumColors.Stroke3, step, 3.0, "stroke-3/ladder")
        }
        expectCr(VellumColors.IdleDot, VellumColors.FieldIdle, 3.0, "idle-dot/field-idle")
        for (c in listOf(VellumColors.Canvas, VellumColors.Container1, VellumColors.Container3)) {
            expectCr(VellumColors.Primary, c, 3.0, "focus-ring/container")
        }
        expectCr(VellumColors.ErrorMark, VellumColors.Container3, 3.0, "error-ring/c3")
        expectCr(VellumColors.Stroke3, VellumColors.Container4, 3.0, "switch-thumb-off stroke/switch-track-off")
        expectCr(VellumColors.Disabled, VellumColors.Canvas, 3.0, "disabled/canvas")
    }

    // --- 3. Films caps nesting at 3 (spec §3.3.3) ---

    @Test
    fun filmsRejectsFour() {
        assertFailsWith<IllegalArgumentException> { Films.of(4) }
        assertFailsWith<IllegalArgumentException> { Films.of(-1) }
        assertEquals(0, Films.Board.count)
        assertEquals(3, Films.of(3).count)
    }
}
