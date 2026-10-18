package dev.vaayil.feature.tunnel

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscFaceTest {
    @Test
    fun `the disc is active only for the connected profile and reads switch for another one`() {
        val connectedToA = TunnelStatus(ConnectionPhase.CONNECTED, "a", null)
        assertEquals(DiscFace.ACTIVE, discFace(connectedToA, "a"))
        assertEquals(DiscFace.SWITCH, discFace(connectedToA, "b"))
        assertEquals(DiscFace.SWITCH, discFace(connectedToA, null))
    }

    @Test
    fun `busy failed and idle follow the phase`() {
        assertEquals(DiscFace.BUSY, discFace(TunnelStatus(ConnectionPhase.CONNECTING, "a", null), "a"))
        assertEquals(DiscFace.BUSY, discFace(TunnelStatus(ConnectionPhase.DISCONNECTING, "a", null), "a"))
        assertEquals(DiscFace.FAILED, discFace(TunnelStatus(ConnectionPhase.FAILED, "a", TunnelError.HandshakeTimeout), "a"))
        assertEquals(DiscFace.IDLE, discFace(TunnelStatus.idle, "a"))
    }

    @Test
    fun `a failure shows only on the profile that failed`() {
        val failedA = TunnelStatus(ConnectionPhase.FAILED, "a", TunnelError.HandshakeTimeout)
        assertEquals(DiscFace.FAILED, discFace(failedA, "a"))
        // F17b: A failed, then the user picked B. B never wears A's failure.
        assertEquals(DiscFace.IDLE, discFace(failedA, "b"))
        assertEquals(DiscFace.IDLE, discFace(failedA, null))
        // A failure that names no profile belongs to none.
        assertEquals(DiscFace.IDLE, discFace(TunnelStatus(ConnectionPhase.FAILED, null, null), null))
    }

    @Test
    fun `the disc wears sage only for a verified live tunnel`() {
        assertTrue(discWearsSage(DiscFace.ACTIVE, verified = true))
        assertTrue(discWearsSage(DiscFace.SWITCH, verified = true))
        // F21/G4: sage means this tunnel's own handshake. PENDING and WAITING keep the canvas window.
        assertFalse(discWearsSage(DiscFace.ACTIVE, verified = false))
        assertFalse(discWearsSage(DiscFace.SWITCH, verified = false))
        listOf(DiscFace.BUSY, DiscFace.FAILED, DiscFace.IDLE).forEach {
            assertFalse(discWearsSage(it, verified = true))
        }
    }

    @Test
    fun `pillPhase speaks a foreign failure as disconnected and keeps every other phase`() {
        // F17b's rule lives in discFace; the pill asks it instead of re-encoding it (T2).
        val foreign = TunnelStatus(ConnectionPhase.FAILED, "other", null)
        assertEquals(
            ConnectionPhase.DISCONNECTED,
            pillPhase(discFace(foreign, "selected"), ConnectionPhase.FAILED),
        )
        val own = TunnelStatus(ConnectionPhase.FAILED, "selected", null)
        assertEquals(
            ConnectionPhase.FAILED,
            pillPhase(discFace(own, "selected"), ConnectionPhase.FAILED),
        )
        ConnectionPhase.entries
            .filter { it != ConnectionPhase.FAILED }
            .forEach { phase ->
                assertEquals(phase, pillPhase(DiscFace.IDLE, phase))
                assertEquals(phase, pillPhase(DiscFace.FAILED, phase))
            }
    }
}
