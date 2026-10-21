package dev.vaayil.core.tunnel.wg

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.FakeTunnelEngine
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelEngine
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class ConnectionRegistryTest {
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun cancelBridge() {
        bridgeScope.cancel()
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.stats.value = TunnelStats.empty
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }
    @Test
    fun `registry starts idle`() {
        assertEquals(TunnelStatus.idle, ConnectionRegistry.status.value)
    }

    @Test
    fun `connectsSettled counts every noteConnectSettled call`() {
        val before = ConnectionRegistry.connectsSettled
        ConnectionRegistry.noteConnectSettled()
        ConnectionRegistry.noteConnectSettled()
        assertEquals(before + 2, ConnectionRegistry.connectsSettled)
    }

    @Test
    fun `ensureChannel creates tunnel channel`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TunnelNotifications.ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(nm.getNotificationChannel("tunnel"))
    }

    @Test
    fun `resetIfLive clears a live phase and leaves others alone`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        ConnectionRegistry.resetIfLive()
        assertEquals(ConnectionPhase.DISCONNECTED, ConnectionRegistry.status.value.phase)
        val failed = TunnelStatus(ConnectionPhase.FAILED, "p1", TunnelError.BackendError("x"))
        ConnectionRegistry.status.value = failed
        ConnectionRegistry.resetIfLive()
        assertEquals(failed, ConnectionRegistry.status.value)
    }

    @Test
    fun `the bridge mirrors the engine for the process lifetime`() = runTest {
        val engine = FakeTunnelEngine(backgroundScope)
        ConnectionRegistry.bridge(engine, bridgeScope)
        engine.start(profile("p"))
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "p", null), ConnectionRegistry.status.value)
        engine.stop()
        assertEquals(TunnelStatus.idle, ConnectionRegistry.status.value)
    }

    @Test
    fun `a published start failure reaches the registry`() = runTest {
        val engine = FakeTunnelEngine(backgroundScope).apply { failNextStart = TunnelError.DnsResolverFailure("bind: EACCES") }
        ConnectionRegistry.bridge(engine, bridgeScope)
        engine.start(profile("p"))
        assertEquals(ConnectionPhase.FAILED, ConnectionRegistry.status.value.phase)
        assertEquals(TunnelError.DnsResolverFailure("bind: EACCES"), ConnectionRegistry.status.value.error)
    }

    @Test
    fun `bridging again replaces the previous bridge`() = runTest {
        val first = FakeTunnelEngine(backgroundScope)
        val second = FakeTunnelEngine(backgroundScope)
        ConnectionRegistry.bridge(first, bridgeScope)
        ConnectionRegistry.bridge(second, bridgeScope)
        first.start(profile("old"))
        assertEquals(TunnelStatus.idle, ConnectionRegistry.status.value)
        second.start(profile("new"))
        assertEquals("new", ConnectionRegistry.status.value.profileId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the bridge publishes this tunnel's handshake state`() = runTest {
        val engine = MutableTunnelEngine()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            ConnectionRegistry.bridge(engine, scope)
            engine.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p", null, generation = 1)
            runCurrent()
            assertEquals(HandshakeState.PENDING, ConnectionRegistry.handshake.value)

            engine.stats.value = TunnelStats(handshakeAt = 1_000L, rxBytes = 0, txBytes = 0, generation = 1)
            runCurrent()
            assertEquals(HandshakeState.VERIFIED, ConnectionRegistry.handshake.value)

            engine.status.value = TunnelStatus.idle
            runCurrent()
            assertEquals(HandshakeState.NONE, ConnectionRegistry.handshake.value)
        } finally {
            scope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the bridge's handshake reads waiting when the peer never answers`() = runTest {
        val engine = MutableTunnelEngine()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            ConnectionRegistry.bridge(engine, scope)
            engine.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p", null, generation = 1)
            runCurrent()
            assertEquals(HandshakeState.PENDING, ConnectionRegistry.handshake.value)

            advanceTimeBy(12_000)
            runCurrent()
            assertEquals(HandshakeState.WAITING, ConnectionRegistry.handshake.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the switch failure message names both profiles`() {
        assertEquals(
            "Couldn't switch to Beta. Still connected to Alpha.",
            SwitchFailure(targetName = "Beta", liveName = "Alpha", unresolved = false).message,
        )
        assertEquals(
            "Couldn't switch to Beta: its server name didn't resolve. Still connected to Alpha.",
            SwitchFailure(targetName = "Beta", liveName = "Alpha", unresolved = true).message,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a switch failure reaches the listening collector and is never replayed`() = runTest {
        val failure = SwitchFailure(targetName = "Beta", liveName = "Alpha", unresolved = false)
        val seen = mutableListOf<SwitchFailure>()
        val listening = launch(UnconfinedTestDispatcher(testScheduler)) {
            ConnectionRegistry.switchFailures.collect { seen += it }
        }
        ConnectionRegistry.reportSwitchFailure(failure)
        assertEquals(listOf(failure), seen)
        listening.cancel()

        val late = mutableListOf<SwitchFailure>()
        val lateJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            ConnectionRegistry.switchFailures.collect { late += it }
        }
        assertEquals(emptyList<SwitchFailure>(), late) // a screen that starts collecting later never sees an old failure
        lateJob.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a switch failure with nobody listening is dropped`() = runTest {
        ConnectionRegistry.reportSwitchFailure(SwitchFailure(targetName = "Beta", liveName = "Alpha", unresolved = true))
        val seen = mutableListOf<SwitchFailure>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            ConnectionRegistry.switchFailures.collect { seen += it }
        }
        assertEquals(emptyList<SwitchFailure>(), seen)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a restart refusal reaches the listening collector, is never replayed, and has the spec's wording`() = runTest {
        val refusal = RestartRefusal("Alpha", "its DNS server name didn't resolve")
        assertEquals(
            "Couldn't apply the new settings to Alpha: its DNS server name didn't resolve. It's still running the previous ones.",
            refusal.message,
        )
        val seen = mutableListOf<RestartRefusal>()
        val listener = launch(UnconfinedTestDispatcher(testScheduler)) { ConnectionRegistry.restartRefusals.collect { seen += it } }
        try {
            ConnectionRegistry.reportRestartRefusal(refusal)
            assertEquals(listOf(refusal), seen)
        } finally {
            listener.cancel()
        }
        val late = launch(UnconfinedTestDispatcher(testScheduler)) { ConnectionRegistry.restartRefusals.collect { seen += it } }
        late.cancel()
        assertEquals(listOf(refusal), seen) // never replayed
    }

    private fun profile(id: String) = Profile(
        id = id,
        name = id,
        interfaceConfig = InterfaceConfig(
            privateKey = "A".repeat(43) + "=",
            addresses = listOf("10.0.0.2/32", "fd00::2/128"),
            dnsServers = emptyList(),
            mtu = null,
            listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "E".repeat(43) + "=",
            presharedKey = null,
            endpoint = "vpn.example.com:51820",
            allowedIps = listOf("0.0.0.0/0", "::/0"),
            persistentKeepalive = 25,
        ),
        dns = DnsPolicy(),
    )

    private class MutableTunnelEngine : TunnelEngine {
        override val status = MutableStateFlow(TunnelStatus.idle)
        override val stats = MutableStateFlow(TunnelStats.empty)

        override suspend fun start(profile: Profile) {
            status.value = TunnelStatus(ConnectionPhase.CONNECTED, profile.id, null, generation = 1)
        }

        override suspend fun stop() {
            status.value = TunnelStatus.idle
        }
    }
}
