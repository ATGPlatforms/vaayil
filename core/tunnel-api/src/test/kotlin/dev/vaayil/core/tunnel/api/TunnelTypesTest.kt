package dev.vaayil.core.tunnel.api

import kotlin.test.Test
import kotlin.test.assertEquals

class TunnelTypesTest {
    @Test
    fun `only connecting and connected count as live`() {
        assertEquals(
            listOf(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED),
            ConnectionPhase.entries.filter { it.isLive },
        )
    }
}
