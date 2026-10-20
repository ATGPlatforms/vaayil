package dev.vaayil.app.quick

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelNotice
import dev.vaayil.core.tunnel.api.TunnelStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The quick controls' presenter: every surface renders exactly what this returns. */
class QuickStateTest {
    private val home = QuickProfile("p1", "Home")
    private val work = QuickProfile("p2", "Work")
    private val readable = listOf(work, home)

    private fun inputs(
        phase: ConnectionPhase,
        profileId: String? = null,
        handshake: HandshakeState = HandshakeState.NONE,
        readable: List<QuickProfile> = this.readable,
        storedCount: Int = 2,
        activeId: String? = null,
        lastConnectedId: String? = null,
        error: TunnelError? = null,
        notice: TunnelNotice? = null,
    ) = QuickInputs(
        status = TunnelStatus(phase, profileId, error, notice),
        handshake = handshake,
        readable = readable,
        storedCount = storedCount,
        activeId = activeId,
        lastConnectedId = lastConnectedId,
    )

    @Test
    fun `connected with a verified handshake reads Connected`() {
        assertEquals(
            QuickState("Home", "Connected", QuickTone.ACTIVE, live = true, action = QuickAction.Disconnect),
            presentQuickState(inputs(ConnectionPhase.CONNECTED, "p1", HandshakeState.VERIFIED)),
        )
    }

    @Test
    fun `connected while waiting for a handshake reads Waiting for handshake`() {
        assertEquals(
            QuickState("Home", "Waiting for handshake", QuickTone.WARNING, live = true, action = QuickAction.Disconnect),
            presentQuickState(inputs(ConnectionPhase.CONNECTED, "p1", HandshakeState.WAITING)),
        )
    }

    @Test
    fun `connected before a handshake reads Connecting`() {
        val expected =
            QuickState("Home", "Connecting", QuickTone.WARNING, live = true, action = QuickAction.Disconnect)
        assertEquals(
            expected,
            presentQuickState(inputs(ConnectionPhase.CONNECTED, "p1", HandshakeState.PENDING)),
        )
        assertEquals(
            expected,
            presentQuickState(inputs(ConnectionPhase.CONNECTED, "p1", HandshakeState.NONE)),
        )
    }

    @Test
    fun `connecting reads Connecting`() {
        assertEquals(
            QuickState("Home", "Connecting", QuickTone.WARNING, live = true, action = QuickAction.Disconnect),
            presentQuickState(inputs(ConnectionPhase.CONNECTING, "p1")),
        )
    }

    @Test
    fun `disconnecting reads Disconnecting and offers the target again`() {
        assertEquals(
            QuickState("Home", "Disconnecting", QuickTone.IDLE, live = false, action = QuickAction.Connect("p1")),
            presentQuickState(inputs(ConnectionPhase.DISCONNECTING, "p1", lastConnectedId = "p1")),
        )
    }

    @Test
    fun `idle with a last-connected profile reads Disconnected`() {
        assertEquals(
            QuickState("Home", "Disconnected", QuickTone.IDLE, live = false, action = QuickAction.Connect("p1")),
            presentQuickState(inputs(ConnectionPhase.DISCONNECTED, lastConnectedId = "p1")),
        )
    }

    @Test
    fun `idle with no pointers picks the first readable profile`() {
        assertEquals(
            QuickState("Work", "Disconnected", QuickTone.IDLE, live = false, action = QuickAction.Connect("p2")),
            presentQuickState(inputs(ConnectionPhase.DISCONNECTED)),
        )
    }

    @Test
    fun `idle prefers the active profile over the last-connected one`() {
        assertEquals(
            QuickState("Home", "Disconnected", QuickTone.IDLE, live = false, action = QuickAction.Connect("p1")),
            presentQuickState(
                inputs(ConnectionPhase.DISCONNECTED, activeId = "p1", lastConnectedId = "p2"),
            ),
        )
    }

    @Test
    fun `idle skips an active pointer that isn't readable`() {
        assertEquals(
            QuickState("Home", "Disconnected", QuickTone.IDLE, live = false, action = QuickAction.Connect("p1")),
            presentQuickState(
                inputs(ConnectionPhase.DISCONNECTED, activeId = "gone", lastConnectedId = "p1"),
            ),
        )
    }

    @Test
    fun `failed with a readable profile reads Couldn't connect`() {
        assertEquals(
            QuickState("Home", "Couldn't connect", QuickTone.ERROR, live = false, action = QuickAction.Connect("p1")),
            presentQuickState(
                inputs(
                    ConnectionPhase.FAILED,
                    "p1",
                    lastConnectedId = "p2",
                    error = TunnelError.HandshakeTimeout,
                ),
            ),
        )
    }

    @Test
    fun `failed for a profile that isn't readable reads Disconnected`() {
        assertEquals(
            QuickState("Home", "Disconnected", QuickTone.IDLE, live = false, action = QuickAction.Connect("p1")),
            presentQuickState(
                inputs(ConnectionPhase.FAILED, "gone", lastConnectedId = "p1"),
            ),
        )
    }

    @Test
    fun `no readable profile with nothing stored reads Add a profile`() {
        assertEquals(
            QuickState("Vaayil", "Add a profile", QuickTone.IDLE, live = false, action = QuickAction.OpenApp),
            presentQuickState(
                inputs(ConnectionPhase.DISCONNECTED, readable = emptyList(), storedCount = 0),
            ),
        )
    }

    @Test
    fun `no readable profile with stored rows reads Open Vaayil`() {
        assertEquals(
            QuickState("Vaayil", "Open Vaayil", QuickTone.IDLE, live = false, action = QuickAction.OpenApp),
            presentQuickState(
                inputs(ConnectionPhase.DISCONNECTED, readable = emptyList(), storedCount = 2),
            ),
        )
    }

    @Test
    fun `live without a readable profile falls back to the app name`() {
        assertEquals(
            QuickState("Vaayil", "Connected", QuickTone.ACTIVE, live = true, action = QuickAction.Disconnect),
            presentQuickState(
                inputs(
                    ConnectionPhase.CONNECTED,
                    "p1",
                    HandshakeState.VERIFIED,
                    readable = emptyList(),
                    storedCount = 2,
                ),
            ),
        )
    }

    @Test
    fun `a notice doesn't change a live state`() {
        assertEquals(
            QuickState("Home", "Connected", QuickTone.ACTIVE, live = true, action = QuickAction.Disconnect),
            presentQuickState(
                inputs(
                    ConnectionPhase.CONNECTED,
                    "p1",
                    HandshakeState.VERIFIED,
                    notice = TunnelNotice.DnsNotAnswering,
                ),
            ),
        )
    }

    @Test
    fun `failed without a readable profile reads Open Vaayil`() {
        assertEquals(
            QuickState("Vaayil", "Open Vaayil", QuickTone.IDLE, live = false, action = QuickAction.OpenApp),
            presentQuickState(
                inputs(ConnectionPhase.FAILED, "p1", readable = emptyList(), storedCount = 2),
            ),
        )
    }

    @Test
    fun `tileLook hides the name on a secure lock screen`() {
        val state = QuickState("Home", "Connected", QuickTone.ACTIVE, live = true, action = QuickAction.Disconnect)
        assertEquals(TileLook("Vaayil", "Connected", true), tileLook(state, secureLockScreen = true))
        assertEquals(TileLook("Home", "Connected", true), tileLook(state, secureLockScreen = false))
    }
}
