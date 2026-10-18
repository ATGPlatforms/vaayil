package dev.vaayil.core.tunnel.api

import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeTunnelEngineTest {
    private fun profile(id: String = "p1") = Profile(
        id, "Home",
        InterfaceConfig("k", listOf("10.0.0.2/32"), emptyList(), 1420, null),
        PeerConfig("K", null, "h:51820", listOf("0.0.0.0/0"), 25),
    )

    @Test
    fun `start moves to connected with profile id`() = runTest {
        val engine = FakeTunnelEngine(backgroundScope)
        engine.start(profile())
        assertEquals(ConnectionPhase.CONNECTED, engine.status.value.phase)
        assertEquals("p1", engine.status.value.profileId)
    }

    @Test
    fun `stop returns to idle`() = runTest {
        val engine = FakeTunnelEngine(backgroundScope)
        engine.start(profile())
        engine.stop()
        assertEquals(TunnelStatus.idle, engine.status.value)
    }
}
