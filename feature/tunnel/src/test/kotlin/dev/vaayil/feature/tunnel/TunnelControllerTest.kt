package dev.vaayil.feature.tunnel

import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.FakeTunnelEngine
import dev.vaayil.core.tunnel.api.TunnelError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingStore : TunnelControllerStore {
    val set = MutableStateFlow<String?>(null)
    override suspend fun setActive(id: String?) { set.value = id }
}

/**
 * The active pointer is written by the service funnel (EngineProvider.connectById) on a STARTED
 * outcome, never by this controller: an eager write here would name the attempted profile as
 * connected while the previous tunnel is still the live one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelControllerTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun profile(id: String) = Profile(
        id, "Home",
        InterfaceConfig("k", listOf("10.0.0.2/32"), emptyList(), null, null),
        PeerConfig("K", null, "h:51820", listOf("0.0.0.0/0"), 25),
    )

    @Test
    fun `a failed connect never marks the attempted profile active`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTunnelEngine(backgroundScope)
        val store = RecordingStore()
        store.set.value = "p1" // the tunnel that is already live
        val controller = TunnelController(engine, store) { profile("p2") }
        engine.failNextStart = TunnelError.HandshakeTimeout
        controller.connect(profile("p2"))
        assertEquals("p1", store.set.value)
    }

    @Test
    fun `disconnect still clears the pointer eagerly`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTunnelEngine(backgroundScope)
        val store = RecordingStore()
        store.set.value = "p1"
        val controller = TunnelController(engine, store) { profile("p1") }
        controller.disconnect()
        assertEquals(null, store.set.value) // fail-closed teardown must not wait for the engine
    }
}
