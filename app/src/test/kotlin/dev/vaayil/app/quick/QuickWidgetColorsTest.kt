package dev.vaayil.app.quick

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.app.R
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.ui.VellumColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The widget is drawn by the launcher from XML resources, so its colours cannot read
 * [VellumColors] at runtime. These resources mirror the Compose tokens and must stay equal
 * to them: a palette change that skips colors.xml would otherwise drift silently.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickWidgetColorsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun drainStartedServices() {
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @After
    fun resetRegistry() {
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    @Test
    fun `each widget colour equals its Vellum token`() {
        val pairs = listOf(
            R.color.vellum_canvas to VellumColors.Canvas,
            R.color.vellum_seam to VellumColors.Seam,
            R.color.vellum_on_surface to VellumColors.OnSurface,
            R.color.vellum_on_surface_variant to VellumColors.OnSurfaceVariant,
            R.color.vellum_primary to VellumColors.Primary,
            R.color.vellum_sage_mark to VellumColors.SageMark,
            R.color.vellum_on_field_active to VellumColors.OnFieldActive,
            R.color.vellum_idle_dot to VellumColors.IdleDot,
            R.color.vellum_warning_mark to VellumColors.WarningMark,
            R.color.vellum_on_warning to VellumColors.OnWarning,
            R.color.vellum_error_mark to VellumColors.ErrorMark,
            R.color.vellum_on_error to VellumColors.OnError,
        )
        pairs.forEach { (resource, token) ->
            assertEquals(
                "widget colour ${app.resources.getResourceName(resource)}",
                token.toArgb(),
                app.getColor(resource),
            )
        }
    }
}
