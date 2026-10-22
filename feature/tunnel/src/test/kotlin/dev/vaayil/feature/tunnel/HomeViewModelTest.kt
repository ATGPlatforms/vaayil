package dev.vaayil.feature.tunnel

import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.FakeTunnelEngine
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeStore : TunnelControllerStore {
    val set = MutableStateFlow<String?>(null)
    override suspend fun setActive(id: String?) { set.value = id }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
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
    fun `toggle connects active profile and updates state`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTunnelEngine(backgroundScope)
        val store = FakeStore()
        val controller = TunnelController(engine, store) { profile("p1") }
        val profiles = MutableStateFlow(listOf(profile("p1")))
        val vm = HomeViewModel(
            engine.status,
            MutableStateFlow(TunnelStats.empty),
            store.set,
            profiles,
            controller,
            lastConnected = flowOf(null),
        )
        vm.toggle()
        assertEquals(ConnectionPhase.CONNECTED, vm.ui.value.status.phase)
        // The pointer is written by the service funnel on a STARTED outcome, not by the
        // controller (EngineProvider.connectById); the fake has no funnel, so the test applies
        // that write itself and then asserts HomeViewModel maps the pointer to the profile.
        store.set.value = "p1"
        assertEquals("p1", vm.ui.value.activeProfile?.id)
    }

    @Test
    fun `toggle while connected disconnects`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTunnelEngine(backgroundScope)
        val store = FakeStore()
        val controller = TunnelController(engine, store) { profile("p1") }
        val profiles = MutableStateFlow(listOf(profile("p1")))
        val vm = HomeViewModel(
            engine.status,
            MutableStateFlow(TunnelStats.empty),
            store.set,
            profiles,
            controller,
            lastConnected = flowOf(null),
        )
        vm.toggle()
        vm.toggle()
        assertEquals(ConnectionPhase.DISCONNECTED, vm.ui.value.status.phase)
        assertNull(vm.ui.value.activeProfile)
    }

    @Test
    fun `toggle with nothing active connects the last connected profile, not the newest`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = FakeTunnelEngine(backgroundScope)
            val store = FakeStore()
            val controller = TunnelController(engine, store) { id -> profile(id) }
            val profiles = MutableStateFlow(listOf(profile("p2"), profile("p1")))
            val vm = HomeViewModel(
                engine.status,
                MutableStateFlow(TunnelStats.empty),
                store.set,
                profiles,
                controller,
                lastConnected = MutableStateFlow<String?>("p1"),
            )
            vm.toggle()
            // FakeTunnelEngine reports the started profile's id.
            assertEquals("p1", engine.status.value.profileId)
        }

    @Test
    fun `the handshake state reaches the ui state`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTunnelEngine(backgroundScope)
        val store = FakeStore()
        val handshake = MutableStateFlow(HandshakeState.NONE)
        val vm = HomeViewModel(
            engine.status,
            MutableStateFlow(TunnelStats.empty),
            store.set,
            MutableStateFlow(listOf(profile("p1"))),
            TunnelController(engine, store) { profile("p1") },
            lastConnected = flowOf(null),
            handshakeState = handshake,
        )
        assertEquals(HandshakeState.NONE, vm.ui.value.handshakeState)
        handshake.value = HandshakeState.WAITING
        assertEquals(HandshakeState.WAITING, vm.ui.value.handshakeState)
        handshake.value = HandshakeState.VERIFIED
        assertEquals(HandshakeState.VERIFIED, vm.ui.value.handshakeState)
    }

    @Test
    fun `the ui follows the Private DNS inputs live`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTunnelEngine(backgroundScope)
        val store = FakeStore()
        val host = MutableStateFlow<String?>("dns.google")
        val vm = HomeViewModel(
            engine.status,
            MutableStateFlow(TunnelStats.empty),
            store.set,
            MutableStateFlow(listOf(profile("p1"))),
            TunnelController(engine, store) { profile("p1") },
            lastConnected = flowOf(null),
            privateDnsHost = host,
            vpnHasDnsServer = MutableStateFlow(true),
            privateDnsDismissedHost = MutableStateFlow("one.one.one.one"),
        )
        assertEquals("dns.google", vm.ui.value.privateDnsHost)
        assertTrue(vm.ui.value.vpnHasDnsServer)
        assertEquals("one.one.one.one", vm.ui.value.privateDnsDismissedHost)
        host.value = null
        assertNull(vm.ui.value.privateDnsHost)
    }

    @Test
    fun `dismissPrivateDns hands the host over once`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTunnelEngine(backgroundScope)
        val store = FakeStore()
        val saved = mutableListOf<String>()
        val vm = HomeViewModel(
            engine.status,
            MutableStateFlow(TunnelStats.empty),
            store.set,
            MutableStateFlow(listOf(profile("p1"))),
            TunnelController(engine, store) { profile("p1") },
            lastConnected = flowOf(null),
            saveDismissedPrivateDns = { saved += it },
        )
        vm.dismissPrivateDns("dns.google")
        assertEquals(listOf("dns.google"), saved)
    }
}
