package dev.vaayil.app.quick

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.app.R
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The widget's RemoteViews carry exactly the presenter's state: name, state text, the
 * tone's colour, the disc's description and the row that holds the dot and the state.
 * Applying every fixture state also proves the layout inflates through RemoteViews'
 * restricted class set.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickWidgetViewsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val home = QuickProfile("p1", "Home")

    @Before
    fun drainStartedServices() {
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @After
    fun resetRegistry() {
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

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

    private fun states(): List<QuickState> = listOf(
        presentQuickState(inputs(ConnectionPhase.CONNECTED, HandshakeState.VERIFIED)),
        presentQuickState(inputs(ConnectionPhase.CONNECTING)),
        presentQuickState(inputs(ConnectionPhase.CONNECTED, HandshakeState.WAITING)),
        presentQuickState(inputs(ConnectionPhase.DISCONNECTED, lastConnectedId = "p1")),
        presentQuickState(inputs(ConnectionPhase.FAILED)),
        presentQuickState(
            inputs(ConnectionPhase.DISCONNECTED, readable = emptyList(), storedCount = 0),
        ),
    )

    private fun apply(state: QuickState): View =
        quickWidgetViews(app, state).apply(app, FrameLayout(app))

    @Test
    fun `the views show the presenter's state`() {
        val root = apply(presentQuickState(inputs(ConnectionPhase.CONNECTED, HandshakeState.VERIFIED)))

        val name = root.findViewById<TextView>(R.id.quick_widget_name)
        val state = root.findViewById<TextView>(R.id.quick_widget_state)
        val row = root.findViewById<View>(R.id.quick_widget_state_row)
        val disc = root.findViewById<View>(R.id.quick_widget_disc)

        assertEquals("Home", name.text.toString())
        assertEquals("Connected", state.text.toString())
        assertEquals(app.getColor(R.color.vellum_on_field_active), state.currentTextColor)
        assertEquals("Disconnect Home", disc.contentDescription.toString())
        assertEquals(View.VISIBLE, row.visibility)
    }

    @Test
    fun `every state applies with RemoteViews' allowed classes`() {
        states().forEach { state ->
            quickWidgetViews(app, state).apply(app, FrameLayout(app))
        }
    }
}
