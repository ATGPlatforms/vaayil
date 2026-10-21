package dev.vaayil.app.quick

import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.vaayil.app.R
import dev.vaayil.core.model.MAX_PROFILE_NAME_LENGTH
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The home-screen widget's five visible faces (plus the no-profile one), rendered through
 * the same RemoteViews the launcher receives. Font scale 2.0 is checked at the default
 * portrait size (203×102dp); the one-row landscape size (51dp tall) at 1.0 only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickWidgetScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val home = QuickProfile("p1", "Home")

    private fun inputs(
        phase: ConnectionPhase,
        handshake: HandshakeState = HandshakeState.NONE,
        readable: List<QuickProfile> = listOf(home),
        storedCount: Int = 1,
        lastConnectedId: String? = null,
    ) = QuickInputs(
        status = TunnelStatus(phase, if (phase == ConnectionPhase.DISCONNECTED) null else "p1"),
        handshake = handshake,
        readable = readable,
        storedCount = storedCount,
        activeId = null,
        lastConnectedId = lastConnectedId,
    )

    private val verified = presentQuickState(inputs(ConnectionPhase.CONNECTED, HandshakeState.VERIFIED))
    private val connecting = presentQuickState(inputs(ConnectionPhase.CONNECTING))
    private val waiting = presentQuickState(inputs(ConnectionPhase.CONNECTED, HandshakeState.WAITING))
    private val disconnected = presentQuickState(inputs(ConnectionPhase.DISCONNECTED, lastConnectedId = "p1"))
    private val failed = presentQuickState(inputs(ConnectionPhase.FAILED))
    private val noProfile = presentQuickState(
        inputs(ConnectionPhase.DISCONNECTED, readable = emptyList(), storedCount = 0),
    )

    private val fixtures = listOf(
        "verified" to verified,
        "connecting" to connecting,
        "waiting" to waiting,
        "disconnected" to disconnected,
        "failed" to failed,
        "no-profile" to noProfile,
    )

    private var current by mutableStateOf(verified)
    private var widgetWidth by mutableStateOf(203)
    private var widgetHeight by mutableStateOf(102)
    private var widgetFontScale by mutableStateOf(1f)
    private var started = false
    private var hosted: View? = null

    @Before
    fun drainStartedServices() {
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @After
    fun resetRegistry() {
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    /**
     * Hosts [state] in a widget-sized box and returns the inflated widget root. Re-renders
     * reuse one composition, so a test can walk every fixture and size.
     */
    private fun render(
        state: QuickState,
        width: Int = 203,
        height: Int = 102,
        fontScale: Float = 1f,
    ): View {
        current = state
        widgetWidth = width
        widgetHeight = height
        widgetFontScale = fontScale
        if (!started) {
            started = true
            compose.setContent {
                Box(Modifier.size(widgetWidth.dp, widgetHeight.dp).testTag("widget")) {
                    key(current, widgetWidth, widgetHeight, widgetFontScale) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                val inflated = if (widgetFontScale == 1f) {
                                    context
                                } else {
                                    context.createConfigurationContext(
                                        Configuration(context.resources.configuration).apply {
                                            this.fontScale = widgetFontScale
                                        },
                                    )
                                }
                                quickWidgetViews(inflated, current)
                                    .apply(inflated, FrameLayout(inflated))
                                    .also { hosted = it }
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return checkNotNull(hosted) { "the widget never inflated" }
    }

    @Test
    fun `widget fixtures`() {
        fixtures.forEach { (name, state) ->
            render(state)
            compose.onNodeWithTag("widget")
                .captureRoboImage("src/testDebug/snapshots/quick-widget-$name.png")
        }
    }

    @Test
    fun `no text is clipped at the default, minimum and landscape sizes`() {
        fixtures.forEach { (name, state) ->
            val atFont2 = render(state, width = 203, height = 102, fontScale = 2f)
            assertNoTextViewClipped(atFont2, "on the $name widget at 203x102dp, font 2.0")

            val atMinimum = render(state, width = 130, height = 102)
            assertNoTextViewClipped(atMinimum, "on the $name widget at 130x102dp")

            val atLandscape = render(state, width = 269, height = 51)
            assertNoTextViewClipped(atLandscape, "on the $name widget at 269x51dp")
        }
    }

    @Test
    fun `a long name ends in an ellipsis`() {
        val longName = presentQuickState(
            inputs(
                ConnectionPhase.DISCONNECTED,
                readable = listOf(home.copy(name = "W".repeat(MAX_PROFILE_NAME_LENGTH))),
                lastConnectedId = "p1",
            ),
        )
        val root = render(longName, width = 203, height = 102, fontScale = 2f)

        val layout = root.findViewById<TextView>(R.id.quick_widget_name).layout
        assertEquals("the long name's line count", 1, layout.lineCount)
        assertTrue("the long name is not cut with an ellipsis", layout.getEllipsisCount(0) > 0)
    }
}
