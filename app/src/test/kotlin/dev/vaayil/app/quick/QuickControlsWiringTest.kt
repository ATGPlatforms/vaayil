package dev.vaayil.app.quick

import android.app.Application
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The hub reads the same pointers, status and handshake every surface renders: ACTIVE first, then
 * last-connected, and a live session over both. The container keeps the flows unwired until a
 * surface subscribes, so each test asks it for the current snapshot directly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickControlsWiringTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var active: ActiveProfileStore
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        // Another class in the suite may leave the process-wide registry live.
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
        runBlocking {
            DataGraph.reset()
            app.settingsDataStore.edit { it.clear() }
            active = ActiveProfileStore(app)
            active.setActive(null)
            active.setLastConnected(null)
            container = AppContainer(app, { _ -> }, { _ -> })
            // A fresh Robolectric data dir is not guaranteed if an earlier method left rows.
            container.profiles.storedIds().forEach { container.profiles.delete(it) }
        }
    }

    @After
    fun resetRegistry() {
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    @Test
    fun `the hub reads the pointers, the status and the handshake`() = runBlocking {
        container.profiles.save(profile("p1", "Home"))
        container.profiles.save(profile("p2", "Work"))
        active.setActive("p1")
        active.setLastConnected("p2")

        val activePick = container.quickControls.currentState()
        assertEquals("Home", activePick.name)
        assertEquals(QuickAction.Connect("p1"), activePick.action)

        active.setActive(null)
        val lastConnectedPick = container.quickControls.currentState()
        assertEquals("Work", lastConnectedPick.name)
        assertEquals(QuickAction.Connect("p2"), lastConnectedPick.action)

        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p2", null)
        ConnectionRegistry.handshake.value = HandshakeState.VERIFIED
        assertEquals(
            QuickState("Work", "Connected", QuickTone.ACTIVE, live = true, action = QuickAction.Disconnect),
            container.quickControls.currentState(),
        )
    }

    @Test
    fun `with no stored profile the hub says Add a profile`() = runBlocking {
        assertEquals("Add a profile", container.quickControls.currentState().stateText)
    }

    /** A 32-byte key (base64) that passes the repository's write validation. */
    private val key = "A".repeat(43) + "="

    private fun profile(id: String, name: String) = Profile(
        id, name,
        InterfaceConfig(key, listOf("10.0.0.2/32"), emptyList(), 1280, null),
        PeerConfig(key, null, "h:51820", listOf("0.0.0.0/0"), 25),
    )
}
