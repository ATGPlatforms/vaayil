package dev.vaayil.app

import android.app.Application
import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C1 device fix: the app window must be edge-to-edge on the Vellum canvas, not the solid
 * dark system bars the dark `Theme.Material` parent paints. The theme must resolve against
 * `android:Theme.Material.Light.NoActionBar` (dark glyphs on light bars), paint
 * `vellum_canvas` #FBFAF7 as the window background, and keep both system bars transparent
 * so `enableEdgeToEdge` in MainActivity owns the bar styling.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ThemeEdgeToEdgeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `vellum canvas colour token is the paper cream`() {
        assertEquals(0xFFFBFAF7.toInt(), context.getColor(R.color.vellum_canvas))
    }

    @Test
    fun `app theme resolves against the Material Light parent on the canvas`() {
        // Resolve the way a window does: apply the style to a Theme, then read attributes
        // through it — colour references dereference against the resolved configuration.
        val theme = context.theme
        theme.applyStyle(R.style.Theme_Vaayil, true)
        val a = theme.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.colorBackground,
                android.R.attr.windowBackground,
                android.R.attr.statusBarColor,
                android.R.attr.navigationBarColor,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar,
            ),
        )
        try {
            // Inherited from the parent: the dark Theme.Material parent carries #FF303030,
            // the Light parent its material background #FFFAFAFA. This is the assertion
            // that pins the parent.
            assertEquals(
                "Theme.Vaayil must resolve against the Material Light parent",
                0xFFFAFAFA.toInt(),
                a.getColor(0, 0),
            )
            assertEquals(
                "windowBackground must be the vellum canvas",
                0xFFFBFAF7.toInt(),
                a.getColor(1, 0),
            )
            assertEquals(
                "status bar must be transparent for edge-to-edge",
                Color.TRANSPARENT,
                a.getColor(2, 0),
            )
            assertEquals(
                "navigation bar must be transparent for edge-to-edge",
                Color.TRANSPARENT,
                a.getColor(3, 0),
            )
            assertTrue(
                "status bar glyphs must be dark-on-light",
                a.getBoolean(4, false),
            )
            assertTrue(
                "navigation bar glyphs must be dark-on-light (v27 restatement)",
                a.getBoolean(5, false),
            )
        } finally {
            a.recycle()
        }
    }

}
