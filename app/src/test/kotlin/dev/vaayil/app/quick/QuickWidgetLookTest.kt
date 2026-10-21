package dev.vaayil.app.quick

import dev.vaayil.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget's four tones and the tap each state offers. Sage appears only for a live
 * tunnel with a verified handshake (ACTIVE); every other tone stays off it.
 */
class QuickWidgetLookTest {
    private fun state(
        tone: QuickTone,
        action: QuickAction,
        name: String = "Home",
        live: Boolean = false,
    ) = QuickState(
        name = name,
        stateText = "Connected",
        tone = tone,
        live = live,
        action = action,
    )

    @Test
    fun `each tone maps to its disc, glyph, dot and text colour`() {
        val rows = listOf(
            Triple(
                QuickTone.ACTIVE,
                state(QuickTone.ACTIVE, QuickAction.Disconnect, live = true),
                listOf(
                    R.drawable.quick_widget_disc_verified,
                    R.drawable.ic_quick_power_on_primary,
                    R.drawable.quick_widget_dot_active,
                    R.color.vellum_on_field_active,
                ),
            ),
            Triple(
                QuickTone.WARNING,
                state(QuickTone.WARNING, QuickAction.Disconnect, live = true),
                listOf(
                    R.drawable.quick_widget_disc_idle,
                    R.drawable.ic_quick_power_idle,
                    R.drawable.quick_widget_dot_warning,
                    R.color.vellum_on_warning,
                ),
            ),
            Triple(
                QuickTone.IDLE,
                state(QuickTone.IDLE, QuickAction.Connect("p1")),
                listOf(
                    R.drawable.quick_widget_disc_idle,
                    R.drawable.ic_quick_power_idle,
                    R.drawable.quick_widget_dot_idle,
                    R.color.vellum_on_surface_variant,
                ),
            ),
            Triple(
                QuickTone.ERROR,
                state(QuickTone.ERROR, QuickAction.Connect("p1")),
                listOf(
                    R.drawable.quick_widget_disc_failed,
                    R.drawable.ic_quick_power_idle,
                    R.drawable.quick_widget_dot_error,
                    R.color.vellum_on_error,
                ),
            ),
        )
        rows.forEach { (tone, quickState, expected) ->
            val look = widgetLook(quickState)
            assertEquals("$tone disc", expected[0], look.disc)
            assertEquals("$tone glyph", expected[1], look.glyph)
            assertEquals("$tone dot", expected[2], look.dot)
            assertEquals("$tone state colour", expected[3], look.stateColor)
        }
    }

    @Test
    fun `the disc describes the tap`() {
        assertEquals(
            "Connect Home",
            widgetLook(state(QuickTone.IDLE, QuickAction.Connect("p1"))).discDescription,
        )
        assertEquals(
            "Disconnect Home",
            widgetLook(state(QuickTone.ACTIVE, QuickAction.Disconnect, live = true)).discDescription,
        )
        assertEquals(
            "Open Vaayil",
            widgetLook(state(QuickTone.IDLE, QuickAction.OpenApp, name = "Vaayil")).discDescription,
        )
    }
}
