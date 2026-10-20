package dev.vaayil.app.quick

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Looper
import android.widget.TextView
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.app.AppContainer
import dev.vaayil.app.R
import dev.vaayil.app.settingsDataStore
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The widget provider asks the hub for the current state on every placement (through
 * `onUpdate`, whose refresh pushes even when the updater last pushed the same state), and the
 * system's placed widgets are exactly the ids the pusher reaches.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickWidgetProviderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private lateinit var originalAccess: (Context) -> QuickControls

    @Before
    fun setUp() {
        // Robolectric shares the application between methods: drop a start an earlier test made.
        while (shadowOf(app).nextStartedService != null) Unit
        runBlocking {
            DataGraph.reset()
            app.settingsDataStore.edit { it.clear() }
            ActiveProfileStore(app).apply {
                setActive(null)
                setLastConnected(null)
            }
            container = AppContainer(app, { _ -> }, { _ -> })
            container.profiles.storedIds().forEach { container.profiles.delete(it) }
            container.profiles.save(profile("p1", "Home"))
            ActiveProfileStore(app).setLastConnected("p1")
        }
        originalAccess = QuickControlsAccess.forContext
        QuickControlsAccess.forContext = { container.quickControls }
    }

    @After
    fun tearDown() {
        QuickControlsAccess.forContext = originalAccess
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    @Test
    fun `with no widget placed nothing is pushed`() = runBlocking {
        assertFalse(AppWidgetPusher(app).push(container.quickControls.currentState()))
    }

    @Test
    fun `placing a widget pushes the current state through onUpdate`() {
        val id = shadowOf(AppWidgetManager.getInstance(app))
            .createWidget(QuickWidgetProvider::class.java, R.layout.quick_widget)

        awaitText(id, R.id.quick_widget_name, "Home")
        awaitText(id, R.id.quick_widget_state, "Disconnected")
    }

    @Test
    fun `every placed widget gets the views`() {
        val ids = shadowOf(AppWidgetManager.getInstance(app))
            .createWidgets(QuickWidgetProvider::class.java, R.layout.quick_widget, 2)

        assertEquals(2, ids.size)
        ids.forEach { id -> awaitText(id, R.id.quick_widget_name, "Home") }
    }

    /**
     * The refresh runs on Dispatchers.IO, so the pushed views arrive asynchronously. Drain the
     * main looper and poll the placed widget's view on a bounded deadline until the text lands.
     */
    private fun awaitText(id: Int, viewId: Int, expected: String, timeoutMs: Long = 5_000) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (textOf(id, viewId) != expected && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(expected, textOf(id, viewId))
    }

    private fun textOf(id: Int, viewId: Int): String? =
        shadowOf(AppWidgetManager.getInstance(app)).getViewFor(id)
            .findViewById<TextView>(viewId)
            ?.text
            ?.toString()

    /** A 32-byte key (base64) that passes the repository's write validation. */
    private val key = "A".repeat(43) + "="

    private fun profile(id: String, name: String) = Profile(
        id, name,
        InterfaceConfig(key, listOf("10.0.0.2/32"), emptyList(), 1280, null),
        PeerConfig(key, null, "h:51820", listOf("0.0.0.0/0"), 25),
    )
}
