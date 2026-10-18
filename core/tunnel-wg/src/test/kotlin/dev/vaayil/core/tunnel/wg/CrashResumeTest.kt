// The shadow's addNetwork still takes a NetworkInfo, which Android deprecated; the whole
// test file suppresses that one deprecation.
@file:Suppress("DEPRECATION")

package dev.vaayil.core.tunnel.wg

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config as WgConfig
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowVpnService

/**
 * The F32 same-boot resume: a process death with a tunnel up leaves a boot-stamped ACTIVE
 * record; the fresh process resumes it exactly once through the control plane
 * (ACTION_CONNECT to VpnTunnelService), clearing the record before the start is issued.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class CrashResumeTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val started = mutableListOf<Intent>()
    private val activeAtIssue = mutableListOf<String?>()
    private var refusing = false

    private fun store(boot: Int?) = ActiveProfileStore(app) { boot }

    @Before
    fun setUp() = runBlocking {
        store(null).setActive(null)
        store(null).setLastConnected(null)
    }

    @After
    fun tearDown() = runBlocking {
        ShadowVpnService.setPrepareResult(null)
        EngineProvider.crashResumeGate = CrashResumeGate()
        ConnectionRegistry.status.value = TunnelStatus.idle
        withTimeout(5_000) {
            store(null).setActive(null)
            store(null).setLastConnected(null)
        }
    }

    /** Records every foreground start: the ACTIVE record at issue time, then the intent —
        or a scripted refusal, mirroring a system that will not start the service. */
    private inner class RecordingContext : ContextWrapper(app) {
        override fun startForegroundService(service: Intent): ComponentName? {
            activeAtIssue += runBlocking { store(7).activeId.first() }
            if (refusing) throw IllegalStateException("scripted: foreground service start not allowed")
            started += service
            return service.component
        }
    }

    private fun resume(vpnUp: () -> Boolean = { false }): Unit = runBlocking {
        EngineProvider.resumeInterruptedSession(RecordingContext(), store(7), vpnUp)
    }

    @Test
    fun `a session that died in this boot is resumed once through the control plane`() = runBlocking {
        store(7).setActive("p1")
        store(7).setLastConnected("p1")
        ShadowVpnService.setPrepareResult(null)

        resume()

        assertEquals(1, started.size)
        val intent = started.single()
        assertEquals(VpnTunnelService::class.java.name, intent.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, intent.action)
        assertEquals("p1", intent.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
        assertTrue(intent.getBooleanExtra(VpnTunnelService.EXTRA_RESUME, false))
        // One-shot: the record was already cleared when the start went out.
        assertEquals(listOf<String?>(null), activeAtIssue)

        resume()
        assertEquals(1, started.size)

        assertEquals("p1", store(7).lastConnectedId.first())
    }

    @Test
    fun `a session from an earlier boot is cleared and never resumed`() = runBlocking {
        store(6).setActive("p1")
        store(6).setLastConnected("p1")

        resume()

        assertTrue(started.isEmpty())
        assertNull(store(7).activeId.first())
        assertEquals("p1", store(7).lastConnectedId.first())
    }

    @Test
    fun `no active record starts nothing`() = runBlocking {
        store(7).setLastConnected("p9")

        resume()

        assertTrue(started.isEmpty())
        assertEquals("p9", store(7).lastConnectedId.first())
    }

    @Test
    fun `a refused start is logged and leaves the record cleared`() = runBlocking {
        store(7).setActive("p1")
        ShadowVpnService.setPrepareResult(null)
        refusing = true

        resume()

        assertTrue(started.isEmpty())
        assertNull(store(7).activeId.first())
        assertTrue(
            ShadowLog.getLogsForTag("EngineProvider").any { it.type == Log.WARN },
        )
    }

    @Test
    fun `a resume is skipped while another VPN is up`() = runBlocking {
        store(7).setActive("p1")
        // prepare() still returns an Intent here: the VPN-up check must win (prepare would take that VPN over).
        ShadowVpnService.setPrepareResult(Intent())

        resume(vpnUp = { true })

        assertTrue(started.isEmpty())
        assertNull(store(7).activeId.first())
        assertTrue(
            ShadowLog.getLogsForTag("EngineProvider").any { it.msg?.contains("VPN is already up") == true },
        )
    }

    @Test
    fun `crash resume without consent consumes ACTIVE without starting a service`() = runBlocking {
        store(7).setActive("p1")
        store(7).setLastConnected("p1")
        ShadowVpnService.setPrepareResult(Intent())

        resume()
        resume()

        assertTrue(started.isEmpty())
        assertNull(store(7).activeId.first())
        assertEquals("p1", store(7).lastConnectedId.first())
        assertTrue(
            ShadowLog.getLogsForTag("EngineProvider").any { it.msg?.contains("VPN permission is off") == true },
        )
    }

    @Test
    fun `a suppression before the intent issues no resume`() = runBlocking {
        store(7).apply { setActive("p1"); setLastConnected("p1") }
        ShadowVpnService.setPrepareResult(null)

        EngineProvider.suppressCrashResume()
        resume()

        assertTrue(started.isEmpty())
        assertNull(store(7).activeId.first())
        assertEquals("p1", store(7).lastConnectedId.first())
    }

    @Test
    fun `a suppression after the consume issues no resume`() {
        runBlocking { store(7).apply { setActive("p1"); setLastConnected("p1") } }
        ShadowVpnService.setPrepareResult(null)
        val vpnUpEntered = CountDownLatch(1)
        val releaseVpnUp = CountDownLatch(1)
        val worker = Thread {
            runBlocking {
                EngineProvider.resumeInterruptedSession(
                    RecordingContext(),
                    store(7),
                    vpnUp = {
                        vpnUpEntered.countDown()
                        releaseVpnUp.await(5, TimeUnit.SECONDS)
                        false
                    },
                )
            }
        }
        try {
            worker.start()
            assertTrue("the resume never reached the vpn-up check", vpnUpEntered.await(5, TimeUnit.SECONDS))
            // The record is consumed before the vpn-up gate, as today: no check before the consume.
            assertNull(runBlocking { store(7).activeId.first() })

            // A disconnect lands while the resume is past the consume and parked on the vpn-up check.
            EngineProvider.suppressCrashResume()
            releaseVpnUp.countDown()
            worker.join(5_000)
            assertFalse(worker.isAlive)

            assertTrue(started.isEmpty())
            assertNull(runBlocking { store(7).activeId.first() })
            assertEquals("p1", runBlocking { store(7).lastConnectedId.first() })
            assertTrue(
                ShadowLog.getLogsForTag("EngineProvider")
                    .any { it.msg?.contains("a disconnect or another start came first") == true },
            )
        } finally {
            releaseVpnUp.countDown()
            worker.join(5_000)
        }
    }

    @Test
    fun `an explicit start before the resume still consumes ACTIVE`() = runBlocking {
        store(7).setActive("p1")
        store(7).setLastConnected("p1")
        ShadowVpnService.setPrepareResult(null)

        EngineProvider.crashResumeGate.noteExplicitStart()
        resume()

        assertTrue(started.isEmpty())
        assertNull(store(7).activeId.first())
        assertEquals("p1", store(7).lastConnectedId.first())
    }

    @Test
    fun `engine liveness wins over a lagging registry`() = runBlocking {
        // No engine: the registry's phase decides.
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.DISCONNECTED, null, null)
        assertFalse(EngineProvider.isTunnelLive())
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTING, "p1", null)
        assertTrue(EngineProvider.isTunnelLive())
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.DISCONNECTING, "p1", null)
        assertFalse(EngineProvider.isTunnelLive())
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.FAILED, "p1", null)
        assertFalse(EngineProvider.isTunnelLive())

        // A real engine CONNECTED while the registry still shows the stale DISCONNECTED.
        val profile = plainProfile("p1")
        val engine = WireGuardEngine(
            context = app,
            backend = RecordingBackend(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            installedApps = { emptySet() },
            profileLookup = { id -> profile.takeIf { it.id == id } },
            splitTunnelRules = { SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, emptySet()) },
            dns = resolver(),
            openChannel = { FakePipe() },
            vpnPermitted = { true },
        )
        val holder = EngineProvider::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val previous = holder.get(null)
        holder.set(null, engine)
        var primary: Throwable? = null
        try {
            engine.start(profile)
            ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.DISCONNECTED, null, null)
            assertTrue(EngineProvider.isTunnelLive())
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            holder.set(null, previous)
            // Bounded on the waiter side only: the stop is NonCancellable around the engine
            // mutex, so it runs on a scope this cleanup never joins; a timed-out stop is
            // abandoned (R9) and surfaced after the restores below.
            var stopFailure: Throwable? = null
            runCatching { stopEngineBounded(engine) }.onFailure { stopFailure = it }
            ConnectionRegistry.status.value = TunnelStatus.idle
            stopFailure?.let { failure ->
                val p = primary
                if (p != null) p.addSuppressed(failure) else throw failure
            }
        }
    }

    /**
     * `WireGuardEngine.stop` is NonCancellable around the engine mutex, so the bound lives
     * on the waiter only: the stop runs on a scope this cleanup never joins, and a
     * timed-out stop is abandoned — it cannot be cancelled (R9).
     */
    private suspend fun stopEngineBounded(engine: WireGuardEngine) {
        val stop = CoroutineScope(SupervisorJob() + Dispatchers.IO).async { engine.stop() }
        withTimeout(5_000) { stop.await() }
    }

    /** A minimal non-intercepting profile: plain DNS and an IPv4-only interface, so no resolver is staged. */
    private fun plainProfile(id: String) = Profile(
        id = id,
        name = id,
        interfaceConfig = InterfaceConfig(
            privateKey = "A".repeat(43) + "=",
            addresses = listOf("10.0.0.2/32"),
            dnsServers = listOf("10.0.0.1"),
            mtu = null,
            listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "E".repeat(43) + "=",
            presharedKey = null,
            endpoint = "vpn.example.com:51820",
            allowedIps = listOf("0.0.0.0/0"),
            persistentKeepalive = null,
        ),
        dns = DnsPolicy(),
    )

    private fun resolver() = DnsResolverController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        protect = { null },
        protectDatagram = { null },
        underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
        ok = OkHttpClient(),
    )

    /** A backend that records every setState, so an engine start's UP is observable without natives. */
    private class RecordingBackend : Backend {
        private var live: Tunnel? = null

        override fun getState(tunnel: Tunnel): Tunnel.State =
            if (live === tunnel) Tunnel.State.UP else Tunnel.State.DOWN

        override fun getStatistics(tunnel: Tunnel): Statistics =
            throw UnsupportedOperationException("no statistics in tests")

        override fun getVersion(): String = "fake"

        override fun getRunningTunnelNames(): Set<String> = emptySet()

        override fun isAlwaysOn(): Boolean = false

        override fun isLockdownEnabled(): Boolean = false

        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: WgConfig?): Tunnel.State {
            live = if (state == Tunnel.State.UP) tunnel else null
            return if (live != null) Tunnel.State.UP else Tunnel.State.DOWN
        }
    }

    @Test
    fun `anyVpnNetworkUp sees a VPN network`() {
        assertFalse(anyVpnNetworkUp(app))
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val vpn = ShadowNetwork.newInstance(4242)
        shadowOf(cm).addNetwork(
            vpn,
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_VPN,
                0,
                true,
                NetworkInfo.State.CONNECTED,
            ),
        )
        shadowOf(cm).setNetworkCapabilities(
            vpn,
            ShadowNetworkCapabilities.newInstance().also {
                shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            },
        )
        assertTrue(anyVpnNetworkUp(app))
    }
}
