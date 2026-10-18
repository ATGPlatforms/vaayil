package dev.vaayil.app

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Pins the Vellum type scale where it is most fragile (spec §3.1, §3.7 R6):
 * the 11sp uppercase label must stay W700 in Schibsted Grotesk, and the two
 * roles that grew in the refresh (display, bodySmall) hold their new sizes.
 */
class ThemeTypographyTest {

    @Test
    fun typographyPins() {
        assertEquals(FontWeight.W700, AppTypography.labelSmall.fontWeight)
        assertEquals(11.sp, AppTypography.labelSmall.fontSize)
        assertEquals(FontWeight.SemiBold, AppTypography.displaySmall.fontWeight)
        assertEquals(30.sp, AppTypography.displaySmall.fontSize)
        assertEquals(FontWeight.Normal, AppTypography.bodySmall.fontWeight)
        assertEquals(13.sp, AppTypography.bodySmall.fontSize)
    }
}
