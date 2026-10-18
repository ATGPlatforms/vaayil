package dev.vaayil.app

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the QR ink to the reviewed sage: the mat is pure white and the ink must stay
 * dark-on-light for scanners while holding WCAG AAA (>= 7.0:1) on-screen.
 */
class QrInkTest {

    @Test
    fun `ink is the pinned sage`() {
        assertEquals(0xFF3E5A42.toInt(), QR_INK)
    }

    @Test
    fun `ink meets WCAG AAA contrast on the pure-white mat`() {
        val ratio = contrastRatio(QR_INK, 0xFFFFFFFF.toInt())
        assertTrue("QR ink contrast was $ratio, needs >= 7.0", ratio >= 7.0)
    }
}

/** WCAG 2.x contrast ratio between two ARGB colors. */
private fun contrastRatio(a: Int, b: Int): Double {
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = (argb shr shift and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
    val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (light + 0.05) / (dark + 0.05)
}
