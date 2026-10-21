package dev.vaayil.app.quick

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import android.service.quicksettings.Tile
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.app.AppContainer
import dev.vaayil.app.settingsDataStore
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The tile's copy and its API gates: the subtitle exists from API 29, the state description from
 * API 30, and below API 30 the content description carries the state itself. A secure lock
 * screen hides the profile's name.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 29, 35], application = Application::class)
class QuickTileRenderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private lateinit var originalAccess: (Context) -> QuickControls
    private lateinit var service: QuickTileService
    private lateinit var host: FakeHost

    @Before
    fun setUp() {
        runBlocking {
            DataGraph.reset()
            app.settingsDataStore.edit { it.clear() }
            ActiveProfileStore(app).apply {
                setActive(null)
                setLastConnected(null)
            }
            container = AppContainer(app, { _ -> }, { _ -> })
        }
        originalAccess = QuickControlsAccess.forContext
        QuickControlsAccess.forContext = { container.quickControls }
        service = Robolectric.buildService(QuickTileService::class.java).create().get()
        host = FakeHost()
        service.host = host
    }

    @After
    fun tearDown() {
        QuickControlsAccess.forContext = originalAccess
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    @Test
    fun `a connected tile shows the name, the state and its descriptions`() {
        service.render(connected())

        val tile = service.qsTile
        assertEquals("Home", tile.label?.toString())
        assertEquals(Tile.STATE_ACTIVE, tile.state)
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals("Connected", tile.subtitle?.toString())
        }
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals("Connected", tile.stateDescription?.toString())
            assertEquals("Home", tile.contentDescription?.toString())
        } else {
            assertEquals("Home, Connected", tile.contentDescription?.toString())
        }
    }

    @Test
    fun `a disconnected tile is inactive`() {
        service.render(disconnected())

        val tile = service.qsTile
        assertEquals("Home", tile.label?.toString())
        assertEquals(Tile.STATE_INACTIVE, tile.state)
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals("Disconnected", tile.subtitle?.toString())
        }
    }

    @Test
    fun `on a secure lock screen the tile hides the profile name`() {
        host.secure = true
        service.render(connected())

        val tile = service.qsTile
        assertEquals("Vaayil", tile.label?.toString())
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals("Vaayil", tile.contentDescription?.toString())
        } else {
            assertEquals("Vaayil, Connected", tile.contentDescription?.toString())
        }
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals("Connected", tile.subtitle?.toString())
        }
    }

    @Test
    @Config(sdk = [35])
    fun `listening renders the hub's state`() {
        seedHomeLastConnected()

        service.onStartListening()

        awaitTile("Home", "Disconnected")

        service.onStopListening()
    }

    @Test
    @Config(sdk = [35])
    fun `a repeated listen leaves one collector`() {
        seedHomeLastConnected()
        service.onStartListening()
        service.onStartListening()
        awaitTile("Home", "Disconnected")

        service.onStopListening()

        val sawConnecting = AtomicBoolean(false)
        val watcherScope = CoroutineScope(Dispatchers.Default)
        watcherScope.launch {
            container.quickControls.state.filterNotNull().collect { state ->
                if (state.stateText == "Connecting") sawConnecting.set(true)
            }
        }
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTING, "p1", null)
        try {
            runBlocking {
                val deadline = System.currentTimeMillis() + 5_000
                while (!sawConnecting.get() && System.currentTimeMillis() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(20)
                }
                assertTrue("the hub never presented CONNECTING", sawConnecting.get())
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals("Home", service.qsTile.label?.toString())
            assertEquals("Disconnected", service.qsTile.subtitle?.toString())
        } finally {
            watcherScope.cancel()
            ConnectionRegistry.status.value = TunnelStatus.idle
        }
    }

    private fun connected(): QuickState = presentQuickState(
        QuickInputs(
            status = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null),
            handshake = HandshakeState.VERIFIED,
            readable = listOf(QuickProfile("p1", "Home")),
            storedCount = 1,
            activeId = null,
            lastConnectedId = null,
        ),
    )

    private fun disconnected(): QuickState = presentQuickState(
        QuickInputs(
            status = TunnelStatus.idle,
            handshake = HandshakeState.NONE,
            readable = listOf(QuickProfile("p1", "Home")),
            storedCount = 1,
            activeId = null,
            lastConnectedId = null,
        ),
    )

    private fun seedHomeLastConnected() {
        runBlocking {
            container.profiles.save(profile("p1", "Home"))
            ActiveProfileStore(app).setLastConnected("p1")
        }
    }

    /** Idles the main looper until the tile shows [label] and [subtitle]; fails after 5 s. */
    private fun awaitTile(label: String, subtitle: String) {
        runBlocking {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline &&
                (service.qsTile.label?.toString() != label ||
                    service.qsTile.subtitle?.toString() != subtitle)
            ) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(20)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(label, service.qsTile.label?.toString())
            assertEquals(subtitle, service.qsTile.subtitle?.toString())
        }
    }

    /** A 32-byte key (base64) that passes the repository's write validation. */
    private val key = "A".repeat(43) + "="

    private fun profile(id: String, name: String) = Profile(
        id, name,
        InterfaceConfig(key, listOf("10.0.0.2/32"), emptyList(), 1280, null),
        PeerConfig(key, null, "h:51820", listOf("0.0.0.0/0"), 25),
    )

    private class FakeHost(var secure: Boolean = false) : QuickTileHost {
        override fun isSecure(): Boolean = secure

        override fun unlockAndRun(action: Runnable) = Unit

        override fun openApp() = Unit
    }
}
