package dev.vaayil.core.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F29 (spec U6): [CappedFontScale] clamps the font scale and leaves the pixel density alone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FontScaleTest {
    @get:Rule val compose = createComposeRule()

    /** The density outside and inside a [cap] when the system font scale is [systemScale]. */
    private fun densities(systemScale: Float, cap: Float): Pair<Density, Density> {
        var outside: Density? = null
        var inside: Density? = null
        compose.setContent {
            val system = Density(LocalDensity.current.density, systemScale)
            outside = system
            CompositionLocalProvider(LocalDensity provides system) {
                CappedFontScale(cap) { inside = LocalDensity.current }
            }
        }
        compose.waitForIdle()
        return outside!! to inside!!
    }

    @Test
    fun aFontScaleAboveTheCapIsClampedToTheCap() {
        val (_, inside) = densities(systemScale = 2f, cap = 1.3f)
        assertEquals(1.3f, inside.fontScale, 0.0001f)
    }

    @Test
    fun aFontScaleBelowTheCapPassesThrough() {
        val (_, inside) = densities(systemScale = 1.15f, cap = 1.3f)
        assertEquals(1.15f, inside.fontScale, 0.0001f)
    }

    @Test
    fun theCapKeepsThePixelDensity() {
        val (outside, inside) = densities(systemScale = 2f, cap = 1.3f)
        assertEquals(outside.density, inside.density, 0.0001f)
    }
}
