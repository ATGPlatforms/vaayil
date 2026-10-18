package dev.vaayil.core.tunnel.wg

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartResolutionTest {
    private val connect = VpnTunnelService.ACTION_CONNECT
    private val alwaysOn = "android.net.VpnService"

    @Test
    fun `explicit id wins and reads nothing`() {
        var reads = 0
        assertEquals("x", startProfileId(connect, "x", { reads++; "a" }, { reads++; "l" }))
        assertEquals(0, reads)
    }

    @Test
    fun `active record is used when no id is given`() = assertEquals("a", startProfileId(null, null, { "a" }, { "l" }))

    @Test
    fun `system always-on start falls back to the last connected profile`() {
        assertEquals("l", startProfileId(null, null, { null }, { "l" }))
        assertEquals("l", startProfileId(alwaysOn, null, { null }, { "l" }))
    }

    @Test
    fun `in-app connect without an id never reads last connected`() {
        var lastReads = 0
        assertNull(startProfileId(connect, null, { null }, { lastReads++; "l" }))
        assertEquals(0, lastReads)
    }

    @Test
    fun `a start is STARTED only when the engine reports CONNECTED for that profile`() {
        assertEquals(StartOutcome.STARTED, startOutcome(TunnelStatus(ConnectionPhase.CONNECTED, "home", null), "home"))
        // A failed switch: the previous tunnel survived, so the engine says CONNECTED for the OTHER profile.
        assertEquals(StartOutcome.FAILED, startOutcome(TunnelStatus(ConnectionPhase.CONNECTED, "work", null), "home"))
        assertEquals(StartOutcome.FAILED, startOutcome(TunnelStatus(ConnectionPhase.FAILED, "home", TunnelError.HandshakeTimeout), "home"))
    }
}
