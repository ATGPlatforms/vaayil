package dev.vaayil.core.tunnel.wg

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.TestStatistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config as WgConfig
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.dns.DatagramPipe
import dev.vaayil.core.dns.DnsUpstream
import dev.vaayil.core.dns.SecureDnsBootstrapException
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.RESOLVER_V4
import dev.vaayil.core.model.RESOLVER_V6
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelNotice
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.tunnel.api.TunnelStatus
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class WireGuardEngineTest {

    @Test
    @Config(sdk = [34], application = Application::class)
    fun `an expired legacy install connects through EngineProvider and updates its active records`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val legacyFile = app.preferencesDataStoreFile("vaayil_trial")
        // This test owns the obsolete file. There is no production reader after the free-app upgrade.
        if (legacyFile.exists()) check(legacyFile.delete())
        val expiredAt = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1_000
        val writer = SupervisorJob()
        val preferences = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(writer + Dispatchers.IO),
            produceFile = { legacyFile },
        )
        try {
            val stored = preferences.edit {
                it[longPreferencesKey("trial_first_launch_at")] = expiredAt
                it[booleanPreferencesKey("trial_purchased")] = false
            }
            // The fixture is what it claims to be: an expired first launch that was never purchased.
            assertEquals(expiredAt, stored[longPreferencesKey("trial_first_launch_at")])
            assertEquals(false, stored[booleanPreferencesKey("trial_purchased")])
        } finally {
            // One active DataStore per file: fully close this fixture writer before starting the app path.
            writer.cancelAndJoin()
        }

        val profile = secureProfile("free", "Free")
        val backend = FakeBackend()
        val subject = engine(backend, controller(backgroundScope) { RecordingUpstream() }, profile)
        withProviderEngine(subject) {
            assertEquals(StartOutcome.STARTED, EngineProvider.connectById(app, profile.id))
            assertEquals(listOf("Free" to Tunnel.State.UP), backend.calls)
            assertEquals(profile.id, ActiveProfileStore(app).activeId.first())
            assertEquals(profile.id, ActiveProfileStore(app).lastConnectedId.first())
        }
    }

    @Test
    fun `EngineProvider refuses a connect without an engine and preserves the active records`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val records = ActiveProfileStore(app)
        withProviderEngine(null) {
            records.setActive("previous")
            records.setLastConnected("previous")

            assertEquals(StartOutcome.REFUSED, EngineProvider.connectById(app, "target"))
            assertEquals("previous", records.activeId.first())
            assertEquals("previous", records.lastConnectedId.first())
        }
    }

    @Test
    fun `EngineProvider refuses an unknown profile and clears only its stale active record`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val records = ActiveProfileStore(app)
        val profile = secureProfile("known", "Known")
        val backend = FakeBackend()
        val subject = engine(backend, controller(backgroundScope) { RecordingUpstream() }, profile)
        withProviderEngine(subject) {
            records.setActive("missing")
            records.setLastConnected(profile.id)

            assertEquals(StartOutcome.REFUSED, EngineProvider.connectById(app, "missing"))
            assertNull(records.activeId.first())
            assertEquals(profile.id, records.lastConnectedId.first())
            assertTrue(backend.calls.isEmpty())

            // An unrelated active record is kept when the missing id was only the requested target.
            records.setActive(profile.id)
            assertEquals(StartOutcome.REFUSED, EngineProvider.connectById(app, "missing"))
            assertEquals(profile.id, records.activeId.first())
            assertEquals(profile.id, records.lastConnectedId.first())
            assertTrue(backend.calls.isEmpty())
        }
    }

    @Test
    fun `EngineProvider reports a failed switch and retains the previous active records`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val records = ActiveProfileStore(app)
        val previous = secureProfile("previous", "Previous")
        val target = secureProfile("target", "Target")
        val backend = FakeBackend()
        val subject = engine(backend, controller(backgroundScope) { RecordingUpstream() }, previous, target)
        withProviderEngine(subject) {
            assertEquals(StartOutcome.STARTED, EngineProvider.connectById(app, previous.id))
            backend.failNextUp = true

            assertEquals(StartOutcome.FAILED, EngineProvider.connectById(app, target.id))
            assertEquals(previous.id, records.activeId.first())
            assertEquals(previous.id, records.lastConnectedId.first())
            assertEquals(ConnectionPhase.CONNECTED, subject.status.value.phase)
            assertEquals(previous.id, subject.status.value.profileId)
            assertTrue(backend.up)
        }
    }

    // A6: A's start holds the engine lock across the backend's establish window while B's connect queues
    // behind it on the same main thread. Each request must report the outcome of its own start.
    @Test
    fun `queued literal success preserves both STARTED outcomes`() =
        assertQueuedStart(bEndpoint = "198.51.100.9:51820", bFails = false)

    @Test
    fun `queued literal failure preserves the first STARTED outcome`() =
        assertQueuedStart(bEndpoint = "198.51.100.9:51820", bFails = true)

    @Test
    fun `queued hostname failure preserves the first STARTED outcome`() =
        assertQueuedStart(bEndpoint = "vpn.example.net:51820", bFails = true)

    /**
     * A6's race, end to end through production's control plane: A's connect is latched inside the backend's
     * establish window, B's connect queues behind A's lock on the same main thread, then A is released. Both
     * requests must report the outcome of their own start, and the active/last-connected pointers must follow
     * what actually settled.
     */
    private fun assertQueuedStart(bEndpoint: String, bFails: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val backend = QueuedStartBackend().apply {
            gateFor = "Alpha"
            if (bFails) failFor = "Beta"
        }
        val a = plainProfile("a", "Alpha", "198.51.100.7:51820")
        val b = plainProfile("b", "Beta", bEndpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pipes = CopyOnWriteArrayList<FakePipe>()
        val subject = WireGuardEngine(
            context = app,
            backend = backend,
            scope = scope,
            installedApps = { emptySet() },
            profileLookup = { id -> listOf(a, b).firstOrNull { it.id == id } },
            splitTunnelRules = { SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, emptySet()) },
            dns = controller(scope) { RecordingUpstream() },
            openChannel = { FakePipe().also { pipe -> pipes.add(pipe) } },
            vpnPermitted = { true },
            endpointLookupProblem = { null },
        )
        val holder = EngineProvider::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val previous = holder.get(null)
        holder.set(null, subject)
        val store = ActiveProfileStore(app)
        try {
            runBlocking {
                store.setActive(null)
                store.setLastConnected("x")
            }
            val outcomes = ConcurrentHashMap<String, StartOutcome>()
            TunnelControl.Default.connect(app, a.id) { outcome, _ -> outcomes[a.id] = outcome }
            pumpUntil("A inside the backend's establish window") { backend.inUp.count == 0L }
            // B arrives while A holds the lock: it queues behind A's start.
            TunnelControl.Default.connect(app, b.id) { outcome, _ -> outcomes[b.id] = outcome }
            backend.release.countDown()
            pumpUntil("both starts settled") { outcomes.size == 2 }

            val active = runBlocking { store.activeId.first() }
            val last = runBlocking { store.lastConnectedId.first() }
            if (bFails) {
                assertEquals("A settled, ACTIVE=$active LAST=$last", StartOutcome.STARTED, outcomes[a.id])
                assertEquals(StartOutcome.FAILED, outcomes[b.id])
                assertEquals(ConnectionPhase.CONNECTED, subject.status.value.phase)
                assertEquals(a.id, subject.status.value.profileId)
                assertEquals(a.id, active)
                assertEquals(a.id, last)
            } else {
                assertEquals(StartOutcome.STARTED, outcomes[a.id])
                assertEquals(StartOutcome.STARTED, outcomes[b.id])
                assertEquals(ConnectionPhase.CONNECTED, subject.status.value.phase)
                assertEquals(b.id, subject.status.value.profileId)
            }
        } finally {
            backend.release.countDown() // a failure before the release must never leave A's establish blocked
            holder.set(null, previous)
            pipes.forEach { it.endOfFile() }
            scope.cancel()
            runBlocking {
                store.setActive(null)
                store.setLastConnected(null)
            }
        }
    }

    /** Bounded main-looper pumping: production's connect coroutines keep resuming on the paused looper. */
    private fun pumpUntil(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) error("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private suspend fun withProviderEngine(subject: WireGuardEngine?, block: suspend () -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val records = ActiveProfileStore(app)
        val holder = EngineProvider::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val previous = holder.get(null)
        holder.set(null, subject)
        try {
            block()
        } finally {
            subject?.stop()
            holder.set(null, previous)
            records.setActive(null)
            records.setLastConnected(null)
        }
    }

    /**
     * Records every setState and can be scripted to throw on the next UP (a failed switch).
     * Emulates GoBackend's one-tunnel-at-a-time rule: an UP while a *different* tunnel is up
     * revokes that tunnel first, reporting DOWN on its own callback from inside setState.
     */
    private class FakeBackend : Backend {
        var up = false
        var failNextUp = false
        /** false = the failed UP does not restore the previous tunnel (the platform-blocked
            re-establish case): everything is down after the failure. */
        var restoreOnFail = true
        val calls = mutableListOf<Pair<String, Tunnel.State>>()
        var onUp: (() -> Unit)? = null      // blocks/observes the UP call (the establish window)
        var lastTunnel: Tunnel? = null
        var upTunnel: Tunnel? = null
        val revocations = mutableListOf<String>()
        val statsCalls = java.util.concurrent.atomic.AtomicInteger(0)
        /** When set, getStatistics answers from it; otherwise it throws (the historical default). */
        var statisticsProvider: (() -> Statistics)? = null
        /** true = every UP fails the way GoBackend's does without the VPN permission: setStateInternal throws
            BackendException(VPN_NOT_AUTHORIZED). */
        var notAuthorized = false
        /** C1: when set, the next UP throws this BackendException, as GoBackend's setStateInternal does. */
        var nextBackendException: BackendException? = null
        /** C4: when true, an UP request applies no state and returns DOWN without throwing. */
        var stayDown = false
        /** The config of every UP request, in order, failed ones included. */
        val upConfigs = mutableListOf<WgConfig?>()

        // Per handle, like GoBackend: only the tunnel that is up reports UP; a revoked or stale handle reports DOWN.
        override fun getState(tunnel: Tunnel): Tunnel.State = if (up && upTunnel === tunnel) Tunnel.State.UP else Tunnel.State.DOWN
        override fun getStatistics(tunnel: Tunnel): Statistics {
            statsCalls.incrementAndGet()
            return statisticsProvider?.invoke() ?: throw UnsupportedOperationException("no statistics in tests")
        }
        override fun getVersion(): String = "fake"
        override fun getRunningTunnelNames(): Set<String> = emptySet()
        // Added by the wireguard-android 1.0.20260102 bump; the engine never calls these.
        override fun isAlwaysOn(): Boolean = false
        override fun isLockdownEnabled(): Boolean = false
        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: WgConfig?): Tunnel.State {
            if (state == Tunnel.State.UP) {
                upConfigs += config
                if (notAuthorized) throw BackendException(BackendException.Reason.VPN_NOT_AUTHORIZED)
                nextBackendException?.let { throw it.also { nextBackendException = null } }
                if (stayDown) {
                    lastTunnel = tunnel
                    calls += tunnel.name to state
                    return Tunnel.State.DOWN
                }
                val revoked = upTunnel?.takeIf { it !== tunnel }
                if (revoked != null) {
                    revocations += revoked.getName()
                    revoked.onStateChange(Tunnel.State.DOWN)
                }
            }
            if (failNextUp && state == Tunnel.State.UP) {
                failNextUp = false
                if (!restoreOnFail) {
                    up = false
                    upTunnel = null
                }
                throw IOException("scripted backend failure")
            }
            if (state == Tunnel.State.UP) onUp?.invoke()
            lastTunnel = tunnel
            calls += tunnel.name to state
            up = when (state) {
                Tunnel.State.UP -> true
                Tunnel.State.DOWN -> false
                Tunnel.State.TOGGLE -> !up
            }
            upTunnel = if (up) tunnel else null
            return if (up) Tunnel.State.UP else Tunnel.State.DOWN
        }
    }

    /**
     * A6's race needs a thread-safe backend that can block the UP call: [gateFor]'s establish is held until
     * [release], and [failFor]'s UP throws without restoring, as GoBackend does when its own UP fails.
     */
    private class QueuedStartBackend : Backend {
        @Volatile private var upTunnel: Tunnel? = null
        @Volatile var gateFor: String? = null
        @Volatile var failFor: String? = null
        val inUp = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun getState(tunnel: Tunnel): Tunnel.State = if (upTunnel === tunnel) Tunnel.State.UP else Tunnel.State.DOWN
        override fun getStatistics(tunnel: Tunnel): Statistics = throw UnsupportedOperationException("no statistics in tests")
        override fun getVersion(): String = "fake"
        override fun getRunningTunnelNames(): Set<String> = emptySet()
        override fun isAlwaysOn(): Boolean = false
        override fun isLockdownEnabled(): Boolean = false
        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: WgConfig?): Tunnel.State {
            if (state == Tunnel.State.UP) {
                if (tunnel.name == gateFor) {
                    inUp.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                if (tunnel.name == failFor) throw IOException("scripted UP failure")
                upTunnel?.takeIf { it !== tunnel }?.onStateChange(Tunnel.State.DOWN)
                upTunnel = tunnel
                return Tunnel.State.UP
            }
            if (upTunnel === tunnel) upTunnel = null
            return Tunnel.State.DOWN
        }
    }

    private class RecordingUpstream : DnsUpstream {
        var queries = 0
        override suspend fun query(queryWire: ByteArray): ByteArray? {
            queries++
            return queryWire.copyOf().also { it[0] = queryWire[0]; it[1] = queryWire[1] }
        }
        override fun close() = Unit
    }

    /** An upstream that only counts how often its owner closed it. */
    private class CloseCountingUpstream : DnsUpstream {
        var closes = 0
        override suspend fun query(queryWire: ByteArray): ByteArray? = null
        override fun close() {
            closes++
        }
    }

    private class RecordingHold : ServiceHold {
        var held = 0
        val events = mutableListOf<String>()
        override fun acquire(): AutoCloseable {
            held++; events += "acquire"
            return AutoCloseable { held--; events += "release" }
        }
    }

    private fun secureProfile(id: String, name: String) = Profile(
        id = id,
        name = name,
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
        dns = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = "9.9.9.9"),
    )

    private fun profileAt(id: String, name: String, endpoint: String) =
        secureProfile(id, name).let { it.copy(peer = it.peer.copy(endpoint = endpoint)) }

    /** A minimal non-intercepting profile: plain DNS and an IPv4-only interface, so no resolver is staged. */
    private fun plainProfile(id: String, name: String, endpoint: String) = Profile(
        id = id,
        name = name,
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
            endpoint = endpoint,
            allowedIps = listOf("0.0.0.0/0"),
            persistentKeepalive = null,
        ),
        dns = DnsPolicy(),
    )

    /** Real controller; `secure` replaces the secure upstream factory, which runs once per build and may throw. */
    private fun controller(scope: CoroutineScope, secure: suspend (DnsPolicy) -> DnsUpstream) =
        DnsResolverController(
            scope = scope,
            protect = { null },
            protectDatagram = { null },
            underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
            ok = OkHttpClient(),
            secureUpstream = secure,
        )

    private fun engine(
        backend: Backend,
        dns: DnsResolverController,
        vararg profiles: Profile,
        statsIntervalMs: Long = 10_000,
        statsFastIntervalMs: Long = 1_000,
        serviceHold: ServiceHold = ServiceHold.None,
        endpointLookupProblem: suspend (String) -> String? = { null },
        openChannel: (List<InetAddress>) -> DatagramPipe = { FakePipe() },
        vpnPermitted: () -> Boolean = { true },
        nativeStats: () -> String? = { null },
    ) = WireGuardEngine(
        context = ApplicationProvider.getApplicationContext(),
        backend = backend,
        scope = CoroutineScope(SupervisorJob()),
        installedApps = { emptySet() },
        profileLookup = { id -> profiles.firstOrNull { it.id == id } },
        splitTunnelRules = { SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, emptySet()) },
        dns = dns,
        openChannel = openChannel,
        vpnPermitted = vpnPermitted,
        statsIntervalMs = statsIntervalMs,
        statsFastIntervalMs = statsFastIntervalMs,
        serviceHold = serviceHold,
        endpointLookupProblem = endpointLookupProblem,
        nativeStats = nativeStats,
    )

    @Test
    fun `a switch whose resolver build fails keeps the live tunnel and its resolver`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        var failNextBuild = false
        var statusDuringBuild: TunnelStatus? = null
        val backend = FakeBackend()
        lateinit var subject: WireGuardEngine // not `engine`: that name is the helper
        val dns = controller(backgroundScope) {
            if (failNextBuild) {
                statusDuringBuild = subject.status.value
                throw SecureDnsBootstrapException("resolver build boom")
            }
            RecordingUpstream()
        }
        subject = engine(backend, dns, a, b)
        subject.start(a)
        val alphaSession = dns.current
        assertEquals("a", alphaSession?.profileId)

        failNextBuild = true
        subject.start(b)

        // B1 §6.5 step 2: refused before anything moved, and reported the way F33's refusal is.
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), statusDuringBuild)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), subject.status.value)
        assertEquals(StartOutcome.FAILED, startOutcome(subject.status.value, "b"))
        assertEquals(listOf("Alpha" to Tunnel.State.UP), backend.calls)
        assertTrue(backend.revocations.isEmpty())
        assertSame(alphaSession, dns.current) // Alpha's resolver still answers
        assertEquals(
            SwitchFailure(targetName = "Beta", liveName = "Alpha", unresolved = false),
            subject.takeFailedSwitch("b"),
        )
        val logged = org.robolectric.shadows.ShadowLog.getLogsForTag("WireGuardEngine").map { it.msg }
        assertEquals("one refusal line: $logged", 1, logged.count { "switch refused" in it && "resolver could not start" in it })
        assertTrue("the class name only, never the message: $logged", logged.none { "boom" in it })
        subject.stop()
    }

    @Test
    fun `a fresh connect whose resolver build fails reports DnsResolverFailure without touching the backend`() = runTest {
        val a = secureProfile("a", "Alpha")
        var statusDuringBuild: TunnelStatus? = null
        val backend = FakeBackend()
        lateinit var subject: WireGuardEngine // not `engine`: that name is the helper
        val dns = controller(backgroundScope) {
            statusDuringBuild = subject.status.value
            throw SecureDnsBootstrapException("resolver build boom")
        }
        subject = engine(backend, dns, a)
        subject.start(a)
        // Spec §6.6, Plan ruling 20: a fresh connect shows CONNECTING while its resolver builds, as before B1, so
        // the dashboard's tap-to-cancel works during a slow bootstrap.
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTING, "a", null), statusDuringBuild)
        assertEquals(
            TunnelStatus(ConnectionPhase.FAILED, "a", TunnelError.DnsResolverFailure("resolver build boom")),
            subject.status.value,
        )
        assertTrue(backend.calls.isEmpty())
        assertTrue(backend.upConfigs.isEmpty())
        assertNull(dns.current)
    }

    @Test
    fun `a resolver build that times out fails a fresh connect instead of cancelling it`() = runTest {
        val a = secureProfile("a", "Alpha")
        val backend = FakeBackend()
        val dns = controller(backgroundScope) {
            kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.awaitCancellation() }
        }
        val subject = engine(backend, dns, a)
        subject.start(a)
        // Plan ruling 20: a TimeoutCancellationException from inside the build is a build failure, not the
        // caller's cancellation, so start() returns and the connect ends FAILED with DnsResolverFailure.
        val status = subject.status.value
        assertEquals(ConnectionPhase.FAILED, status.phase)
        assertEquals("a", status.profileId)
        assertTrue("$status", status.error is TunnelError.DnsResolverFailure)
        assertTrue(backend.calls.isEmpty())
        assertNull(dns.current)
    }

    @Test
    fun `a setState failure on a switch keeps the previous tunnel, status and resolver`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val builds = mutableListOf<CloseCountingUpstream>()
        val backend = FakeBackend()
        val dns = controller(backgroundScope) { CloseCountingUpstream().also { builds += it } }
        val engine = engine(backend, dns, a, b)
        engine.start(a)
        assertEquals(ConnectionPhase.CONNECTED, engine.status.value.phase)
        val alphaSession = dns.current

        backend.failNextUp = true
        engine.start(b) // GoBackend revokes Alpha, fails to bring Beta up, then restores Alpha

        // The engine is still inside start() holding its mutex when Alpha's revocation lands, with
        // its tunnel field not yet reassigned: only the mutex-held check tells that callback apart
        // from the backend's own VpnService.onDestroy teardown. Without it the engine drops the
        // surviving handle and reports FAILED for the profile that never came up.
        assertEquals(listOf("Alpha"), backend.revocations) // the guard is actually exercised
        assertEquals(ConnectionPhase.CONNECTED, engine.status.value.phase)
        assertEquals("a", engine.status.value.profileId) // still the surviving tunnel
        assertSame(alphaSession, dns.current) // its resolver answers again
        assertEquals(listOf(0, 1), builds.map { it.closes }) // Alpha's upstream kept, Beta's retired
        assertTrue(backend.up) // Alpha never dropped
        engine.stop()
    }

    @Test
    fun `a setState that returns down without throwing fails the fresh start`() = runTest {
        val a = secureProfile("a", "Alpha")
        val backend = FakeBackend().apply { stayDown = true }
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a)
        engine.start(a)
        assertEquals(
            TunnelStatus(ConnectionPhase.FAILED, "a", TunnelError.BackendError("The tunnel did not start.")),
            engine.status.value,
        )
        assertNull(engine.liveTunnelName)
        engine.stop()
    }

    @Test
    fun `a setState that returns down on a restart keeps the previous tunnel connected`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a, b)
        engine.start(a)
        backend.stayDown = true
        engine.start(b) // the new tunnel never came up; GoBackend restored Alpha
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), engine.status.value)
        assertEquals("Alpha", engine.liveTunnelName)
        engine.stop()
    }

    @Test
    fun `a successful switch survives the backend revoking the previous tunnel`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a, b)
        engine.start(a)

        engine.start(b)

        assertEquals(listOf("Alpha"), backend.revocations) // Alpha was revoked mid-switch
        assertEquals(ConnectionPhase.CONNECTED, engine.status.value.phase)
        assertEquals("b", engine.status.value.profileId)
        assertEquals(listOf("Alpha" to Tunnel.State.UP, "Beta" to Tunnel.State.UP), backend.calls)
        assertTrue(backend.up)
        engine.stop()
    }

    @Test
    fun `revoke during staged UP makes late commit a no-op`() = runTest {
        val a = secureProfile("a", "Alpha")
        val builds = mutableListOf<CloseCountingUpstream>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = FakeBackend().apply {
            onUp = { entered.countDown(); release.await(5, TimeUnit.SECONDS) } // the blocking establish window
        }
        val dns = controller(backgroundScope) { CloseCountingUpstream().also { builds += it } }
        val subject = engine(backend, dns, a)
        val starter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = starter.launch { subject.start(a) }
        assertTrue(entered.await(5, TimeUnit.SECONDS)) // setState is blocked: the candidate is staged, nothing committed
        assertEquals("a", checkNotNull(dns.current) { "the candidate was never staged" }.profileId)

        subject.deactivateResolver() // VpnTunnelService.onRevoke, before the posted teardown
        assertNull(dns.current)

        release.countDown() // the establish completes and the start commits a session the revoke retired
        job.join()
        assertNull(dns.current) // the late commit was a no-op
        assertEquals(1, builds.single().closes) // the revoke retired the staged candidate exactly once
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), subject.status.value)
        subject.stop()
        starter.cancel()
    }

    @Test
    fun `revoke during failed UP makes late rollback a no-op`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val builds = mutableListOf<CloseCountingUpstream>()
        val backend = FakeBackend()
        val dns = controller(backgroundScope) { CloseCountingUpstream().also { builds += it } }
        val subject = engine(backend, dns, a, b)
        subject.start(a)
        val alphaSession = checkNotNull(dns.current) { "Alpha's resolver was never committed" }
        assertFalse(alphaSession.answerJob.isCancelled)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.onUp = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("scripted backend failure") // GoBackend restores Alpha: upTunnel never moved
        }
        val starter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = starter.launch { subject.start(b) }
        assertTrue(entered.await(5, TimeUnit.SECONDS)) // Beta is staged inside setState; Alpha waits on standby
        assertEquals("b", dns.current?.profileId)

        subject.deactivateResolver()
        assertNull(dns.current)
        assertTrue(alphaSession.answerJob.isCancelled)

        release.countDown() // the UP throws: Alpha's tunnel survived, so the start rolls its resolver back
        job.join()
        assertNull(dns.current) // the late rollback was a no-op
        assertEquals(listOf(1, 1), builds.map { it.closes }) // the revoke retired both sessions, each exactly once
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), subject.status.value)
        subject.stop()
        starter.cancel()
    }

    @Test
    fun `revoke during resolver build cannot stage a late session`() = runTest {
        val a = secureProfile("a", "Alpha")
        val builds = mutableListOf<CloseCountingUpstream>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = FakeBackend()
        val dns = controller(backgroundScope) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            CloseCountingUpstream().also { builds += it }
        }
        val subject = engine(backend, dns, a)
        val starter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = starter.launch { subject.start(a) }
        assertTrue(entered.await(5, TimeUnit.SECONDS)) // the build is in flight; nothing is staged yet
        assertNull(dns.current)

        subject.deactivateResolver() // the revoke lands while the start is still building its session
        release.countDown()
        job.join()
        assertNull(dns.current) // the stale ticket refused the late stage
        assertEquals(1, builds.single().closes) // and its candidate was closed, exactly once
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), subject.status.value)
        subject.stop()
        starter.cancel()
    }

    @Test
    fun `a failed first connect reports FAILED and retires the resolver it built`() = runTest {
        val a = secureProfile("a", "Alpha")
        val builds = mutableListOf<CloseCountingUpstream>()
        val backend = FakeBackend().apply { failNextUp = true }
        val dns = controller(backgroundScope) { CloseCountingUpstream().also { builds += it } }
        val engine = engine(backend, dns, a)
        engine.start(a)
        assertEquals(ConnectionPhase.FAILED, engine.status.value.phase)
        assertEquals("a", engine.status.value.profileId)
        assertTrue(engine.status.value.error is TunnelError.BackendError)
        assertEquals(1, builds.size) // built before setState (B1 §6.5 step 2)
        assertEquals(1, builds.single().closes) // and retired when nothing came up
        assertNull(dns.current)
        assertEquals(0, backend.calls.size)
        engine.stop()
    }

    @Test
    fun `a disconnect that cancels a start in flight still brings the published tunnel down`() = runTest {
        val a = secureProfile("a", "Alpha")
        val backend = FakeBackend()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.onUp = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a)
        val starter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = starter.launch { engine.start(a) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel()          // VpnTunnelService.onDestroy -> scope.cancel() while establish() blocks
        release.countDown()   // establish() completes and publishes the tunnel
        job.join()
        assertEquals(listOf("Alpha" to Tunnel.State.UP, "Alpha" to Tunnel.State.DOWN), backend.calls)
        assertFalse(backend.up)
        assertEquals(ConnectionPhase.FAILED, engine.status.value.phase)
        starter.cancel()
    }

    @Test
    fun `a switch holds the service for the whole setState including the revoke of the previous tunnel`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val hold = RecordingHold()
        val backend = FakeBackend()
        val heldDuringUp = mutableListOf<Int>()
        backend.onUp = { heldDuringUp += hold.held }
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a, b, serviceHold = hold)
        engine.start(a)
        engine.start(b) // FakeBackend revokes Alpha inside this setState, like GoBackend's DOWN-then-UP
        assertEquals(listOf("Alpha"), backend.revocations)
        assertEquals(listOf(1, 1), heldDuringUp)
        assertEquals(0, hold.held)
        assertEquals(listOf("acquire", "release", "acquire", "release"), hold.events)
        engine.stop()
    }

    @Test
    fun `a failed start still releases the hold`() = runTest {
        val a = secureProfile("a", "Alpha")
        val hold = RecordingHold()
        val backend = FakeBackend().apply { failNextUp = true }
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a, serviceHold = hold)
        engine.start(a)
        assertEquals(ConnectionPhase.FAILED, engine.status.value.phase)
        assertEquals(0, hold.held)
        assertEquals(listOf("acquire", "release"), hold.events)
    }

    @Test
    fun `reconcile keeps a tunnel the backend still reports up`() = runTest {
        val a = secureProfile("a", "Alpha")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a)
        engine.start(a)
        val callsBefore = backend.calls.size
        engine.reconcile()
        assertEquals(ConnectionPhase.CONNECTED, engine.status.value.phase)
        assertEquals("a", engine.status.value.profileId)
        assertEquals(callsBefore, backend.calls.size)
        engine.stop()
    }

    @Test
    fun `reconcile drops a handle the backend no longer reports up`() = runTest {
        val a = secureProfile("a", "Alpha")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a)
        engine.start(a)
        backend.up = false // the service died and took the tunnel, but the DOWN callback was swallowed
        engine.reconcile()
        assertEquals(TunnelStatus.idle, engine.status.value)
        val callsBefore = backend.calls.size
        engine.stop()
        assertEquals(callsBefore, backend.calls.size) // nothing left to bring down
    }

    @Test
    fun `reconcile during a start waits for it and keeps the new tunnel`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a, b)
        engine.start(a)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.onUp = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        val starter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val startJob = starter.launch { engine.start(b) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val reconciler = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reconcileJob = reconciler.launch { engine.reconcile() }
        Thread.sleep(100) // a real window: reconcile must still be waiting on the start's mutex
        assertTrue(reconcileJob.isActive) // the start in flight holds the mutex
        release.countDown()
        startJob.join()
        reconcileJob.join()
        assertEquals(ConnectionPhase.CONNECTED, engine.status.value.phase)
        assertEquals("b", engine.status.value.profileId)
        assertTrue(backend.up)
        starter.cancel()
        reconciler.cancel()
        engine.stop()
    }

    @Test
    fun `a backend-side DOWN outside start or stop publishes DISCONNECTED and forgets the handle`() = runTest {
        val a = secureProfile("a", "Alpha")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a)
        engine.start(a)
        assertEquals(ConnectionPhase.CONNECTED, engine.status.value.phase)
        backend.up = false
        backend.lastTunnel!!.onStateChange(Tunnel.State.DOWN) // GoBackend.VpnService.onDestroy behind our back
        assertEquals(ConnectionPhase.DISCONNECTED, engine.status.value.phase)
        val callsBefore = backend.calls.size
        engine.stop()
        assertEquals(callsBefore, backend.calls.size) // nothing left to bring down
    }

    @Test
    fun `stop asks for DOWN never TOGGLE`() = runTest {
        val a = secureProfile("a", "Alpha")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a)
        engine.start(a)
        engine.stop()
        assertEquals("Alpha" to Tunnel.State.DOWN, backend.calls.last())
    }

    @Test
    fun `the live tunnel name follows the published tunnel`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a, b)
        assertNull(engine.liveTunnelName)
        engine.start(a)
        assertEquals("Alpha", engine.liveTunnelName)
        backend.failNextUp = true
        engine.start(b) // a failed switch: Alpha survives
        assertEquals("Alpha", engine.liveTunnelName)
        engine.start(b) // a successful switch
        assertEquals("Beta", engine.liveTunnelName)
        engine.stop()
        assertNull(engine.liveTunnelName)
    }

    @Test
    fun `a published-tunnel failure stops the previous sampler`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val backend = FakeBackend()
        val dns = controller(backgroundScope) { RecordingUpstream() }
        val engine = engine(backend, dns, a, b, statsIntervalMs = 20, statsFastIntervalMs = 20) // fast too: a leaked sampler shows up inside the window below
        engine.start(a)
        Thread.sleep(100)
        assertTrue(backend.statsCalls.get() > 0) // the sampler is polling
        // The resolver is built before setState now, so a cancellation after Beta came up is the failure left
        // that hits a published tunnel.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.onUp = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        val starter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = starter.launch { engine.start(b) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel()          // the caller dies while Beta's establish blocks
        release.countDown()   // Beta comes up and is published, then the cancellation lands
        job.join()
        assertEquals(ConnectionPhase.FAILED, engine.status.value.phase)
        val after = backend.statsCalls.get()
        Thread.sleep(150)
        assertEquals(after, backend.statsCalls.get()) // no sampler left polling a dead tunnel
        assertEquals(TunnelStats.empty, engine.stats.value)
        assertNull(dns.current) // and no resolver left answering for it
        starter.cancel()
    }

    @Test
    fun `a failed switch with no surviving tunnel reports FAILED, not a stale CONNECTED`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val backend = FakeBackend().apply { restoreOnFail = false }
        val dns = controller(backgroundScope) { RecordingUpstream() }
        val engine = engine(backend, dns, a, b)
        engine.start(a)
        assertEquals(ConnectionPhase.CONNECTED, engine.status.value.phase)

        backend.failNextUp = true
        engine.start(b) // the platform blocked the fresh VpnService start: nothing survives

        assertEquals(ConnectionPhase.FAILED, engine.status.value.phase)
        assertTrue(backend.up.not())
        assertNull(dns.current) // both resolvers retired
        engine.stop()
    }

    @Test
    fun `a profile the backend cannot build fails with a plain message, not the library exception`() = runTest {
        // Saved before the endpoint rule existed (F17): validation now refuses it, the engine must still cope.
        val bad = secureProfile("x", "Broken").let { it.copy(dns = DnsPolicy(), peer = it.peer.copy(endpoint = "2001:db8::1:51820")) }
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, bad)
        engine.start(bad)
        assertEquals(ConnectionPhase.FAILED, engine.status.value.phase)
        assertEquals(TunnelError.ParseError(UNUSABLE_PROFILE_MESSAGE), engine.status.value.error)
        assertTrue(backend.calls.isEmpty()) // the backend was never asked to bring anything up
        val logged = org.robolectric.shadows.ShadowLog.getLogsForTag("WireGuardEngine").map { it.msg }
        assertTrue("the refusal names the section and field: $logged", logged.any { "PEER ENDPOINT" in it })
        assertTrue("the log carries no config text: $logged", logged.none { "2001:db8" in it || "A".repeat(43) in it })
    }

    @Test
    fun `an address the backend cannot parse fails with the same plain message`() = runTest {
        // WgConfigMapper parses addresses with InetNetwork.parse, whose ParseException arrives unwrapped (F17).
        val bad = secureProfile("x", "Broken").let {
            it.copy(dns = DnsPolicy(), interfaceConfig = it.interfaceConfig.copy(addresses = listOf("10.0.0.2/abc")))
        }
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, bad)
        engine.start(bad)
        assertEquals(ConnectionPhase.FAILED, engine.status.value.phase)
        assertEquals(TunnelError.ParseError(UNUSABLE_PROFILE_MESSAGE), engine.status.value.error)
        assertTrue(backend.calls.isEmpty())
        val logged = org.robolectric.shadows.ShadowLog.getLogsForTag("WireGuardEngine").map { it.msg }
        assertTrue("the refusal names the parsed type: $logged", logged.any { "Integer" in it })
        assertTrue("the log carries no config text: $logged", logged.none { "abc" in it })
    }

    @Test
    fun `a switch to a profile the backend cannot build keeps the previous tunnel`() = runTest {
        val a = secureProfile("a", "Alpha")
        val bad = secureProfile("b", "Broken").let { it.copy(dns = DnsPolicy(), peer = it.peer.copy(endpoint = "2001:db8::1:51820")) }
        val hold = RecordingHold()
        var secureActivations = 0
        val backend = FakeBackend()
        val dns = controller(backgroundScope) { secureActivations++; RecordingUpstream() }
        val engine = engine(backend, dns, a, bad, serviceHold = hold)
        engine.start(a)
        val alphaSession = dns.current
        engine.start(bad)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), engine.status.value)
        assertEquals(listOf("Alpha" to Tunnel.State.UP), backend.calls) // Broken never reached the backend
        assertEquals("Alpha", engine.liveTunnelName)
        assertEquals(1, secureActivations) // Broken is plain DNS: nothing new was built
        assertSame(alphaSession, dns.current) // Alpha's resolver answers again
        assertEquals(0, hold.held)
        assertEquals(listOf("acquire", "release", "acquire", "release"), hold.events)
        engine.stop()
    }

    @Test
    fun `a switch whose endpoint host does not resolve leaves the live tunnel untouched`() = runTest {
        val a = secureProfile("a", "Alpha")
        val nx = profileAt("b", "Scratch", "nx.invalid:51820")
        val lookups = mutableListOf<String>()
        var statusDuringLookup: TunnelStatus? = null
        var secureActivations = 0
        var secureCloses = 0
        val hold = RecordingHold()
        val backend = FakeBackend()
        lateinit var subject: WireGuardEngine // not `engine`: that name is the helper
        subject = engine(
            backend,
            controller(backgroundScope) {
                secureActivations++
                object : DnsUpstream by RecordingUpstream() {
                    override fun close() { secureCloses++ }
                }
            },
            a, nx,
            serviceHold = hold,
            endpointLookupProblem = { host ->
                lookups += host
                statusDuringLookup = subject.status.value
                "no address returned"
            },
        )
        subject.start(a) // a fresh connect: no lookup
        assertTrue(lookups.isEmpty())

        subject.start(nx)

        assertEquals(listOf("nx.invalid"), lookups)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), statusDuringLookup) // asked before anything moved
        assertEquals(listOf("Alpha" to Tunnel.State.UP), backend.calls) // the backend never saw DOWN or UP for the switch
        assertTrue(backend.revocations.isEmpty())
        assertTrue(backend.up)
        assertEquals("Alpha", subject.liveTunnelName)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), subject.status.value)
        assertEquals(StartOutcome.FAILED, startOutcome(subject.status.value, "b")) // the pointers stay on Alpha
        assertEquals(1, secureActivations) // Alpha's resolver was never rebuilt
        assertEquals(0, secureCloses) // nor torn down
        assertEquals(0, hold.held)
        val logged = org.robolectric.shadows.ShadowLog.getLogsForTag("WireGuardEngine").map { it.msg }
        assertEquals("one refusal line: $logged", 1, logged.count { "nx.invalid" in it })
        assertTrue("no config text: $logged", logged.none { "51820" in it || "A".repeat(43) in it || "E".repeat(43) in it })
        subject.stop()
    }

    @Test
    fun `a switch whose endpoint host resolves proceeds as before`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = profileAt("b", "Beta", "beta.example.com:51820")
        val lookups = mutableListOf<String>()
        val backend = FakeBackend()
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, a, b,
            endpointLookupProblem = { host -> lookups += host; null },
        )
        engine.start(a)
        engine.start(b)
        assertEquals(listOf("beta.example.com"), lookups)
        assertEquals(listOf("Alpha" to Tunnel.State.UP, "Beta" to Tunnel.State.UP), backend.calls)
        assertEquals(listOf("Alpha"), backend.revocations)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "b", null, generation = 2), engine.status.value)
        assertEquals(StartOutcome.STARTED, startOutcome(engine.status.value, "b"))
        engine.stop()
    }

    @Test
    fun `a switch to an IP-literal endpoint never calls the resolver`() = runTest {
        val a = secureProfile("a", "Alpha")
        val v4 = profileAt("c", "Four", "198.51.100.7:51820")
        val v6 = profileAt("d", "Six", "[2001:db8::7]:51820")
        val lookups = mutableListOf<String>()
        val backend = FakeBackend()
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, a, v4, v6,
            endpointLookupProblem = { host -> lookups += host; "must not be asked" },
        )
        engine.start(a)
        engine.start(v4)
        engine.start(v6)
        assertTrue(lookups.isEmpty())
        assertEquals(
            listOf("Alpha" to Tunnel.State.UP, "Four" to Tunnel.State.UP, "Six" to Tunnel.State.UP),
            backend.calls,
        )
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "d", null, generation = 3), engine.status.value)
        engine.stop()
    }

    @Test
    fun `a fresh connect never calls the pre-check`() = runTest {
        val nx = profileAt("b", "Scratch", "nx.invalid:51820")
        val lookups = mutableListOf<String>()
        val backend = FakeBackend()
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, nx,
            endpointLookupProblem = { host -> lookups += host; "no address returned" },
        )
        engine.start(nx) // nothing live: GoBackend's own lookup and retries decide
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "b", null, generation = 1), engine.status.value)
        engine.stop()
        engine.start(nx) // after a disconnect nothing is live either
        assertTrue(lookups.isEmpty())
        assertEquals(
            listOf("Scratch" to Tunnel.State.UP, "Scratch" to Tunnel.State.DOWN, "Scratch" to Tunnel.State.UP),
            backend.calls,
        )
        engine.stop()
    }

    @Test
    fun `a refused switch whose live tunnel died during the lookup reports FAILED, not a stale CONNECTED`() = runTest {
        val a = secureProfile("a", "Alpha")
        val nx = profileAt("b", "Scratch", "nx.invalid:51820")
        val backend = FakeBackend()
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, a, nx,
            endpointLookupProblem = {
                // The backend lost Alpha meanwhile; its DOWN callback was ignored because start() holds the mutex.
                backend.up = false
                backend.upTunnel = null
                "no address returned"
            },
        )
        engine.start(a)
        engine.start(nx)
        assertEquals(
            TunnelStatus(ConnectionPhase.FAILED, "b", TunnelError.DnsFailure("nx.invalid:51820")),
            engine.status.value,
        )
        assertNull(engine.liveTunnelName)
        assertEquals(listOf("Alpha" to Tunnel.State.UP), backend.calls) // still nothing asked of the backend
        engine.stop()
    }

    @Test
    fun `a same-profile restart whose host no longer resolves keeps the live tunnel and says so`() = runTest {
        val a = profileAt("a", "Alpha", "vpn.example.com:51820")
        var resolves = true
        val backend = FakeBackend()
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, a,
            endpointLookupProblem = { if (resolves) null else "no answer from the underlying resolvers" },
        )
        engine.start(a)
        resolves = false
        engine.start(a) // the row-24 split-tunnel restart
        assertEquals(listOf("Alpha" to Tunnel.State.UP), backend.calls)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, TunnelNotice.SettingsNotApplied, generation = 1), engine.status.value)
        assertEquals("Alpha", engine.liveTunnelName)
        assertEquals(RestartRefusal("Alpha", "its server name didn't resolve"), engine.takeRestartRefusal("a"))
        assertNull(engine.takeRestartRefusal("a")) // emitted once
        engine.stop()
    }

    @Test
    fun `a same-profile restart whose resolver build fails says which settings it still runs`() = runTest {
        val a = secureProfile("a", "Alpha")
        var failNextBuild = false
        val backend = FakeBackend()
        val engine = engine(
            backend,
            controller(backgroundScope) {
                if (failNextBuild) throw SecureDnsBootstrapException("could not bootstrap \"nx.invalid\"")
                RecordingUpstream()
            },
            a,
        )
        engine.start(a)
        failNextBuild = true
        engine.start(a) // an edit restart: the DoH bootstrap of nx.invalid fails, Alpha's session survives
        assertEquals(listOf("Alpha" to Tunnel.State.UP), backend.calls)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, TunnelNotice.SettingsNotApplied, generation = 1), engine.status.value)
        assertEquals(
            RestartRefusal("Alpha", "its DNS server name didn't resolve"),
            engine.takeRestartRefusal("a"),
        )
        engine.stop()
    }

    @Test
    fun `a refused restart's notice clears at the next successful start and at a disconnect`() = runTest {
        val a = profileAt("a", "Alpha", "vpn.example.com:51820")
        var resolves = true
        val backend = FakeBackend()
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, a,
            endpointLookupProblem = { if (resolves) null else "no answer from the underlying resolvers" },
        )
        engine.start(a)
        resolves = false
        engine.start(a) // refused: the notice is set
        assertEquals(TunnelNotice.SettingsNotApplied, engine.status.value.notice)
        resolves = true
        engine.start(a) // a successful restart: the notice is gone
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 2), engine.status.value)
        assertNull(engine.takeRestartRefusal("a"))
        resolves = false
        engine.start(a) // refused again
        assertEquals(TunnelNotice.SettingsNotApplied, engine.status.value.notice)
        engine.stop() // a disconnect clears it too
        assertEquals(TunnelStatus.idle, engine.status.value)
        assertNull(engine.takeRestartRefusal("a"))
    }

    @Test
    fun `a different-profile refusal gets the K3 record and never a notice`() = runTest {
        val a = secureProfile("a", "Alpha")
        val nx = profileAt("n", "Scratch", "nx.invalid:51820")
        val backend = FakeBackend()
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, a, nx,
            endpointLookupProblem = { "no answer from the underlying resolvers" },
        )
        engine.start(a)
        engine.start(nx)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), engine.status.value)
        assertNull(engine.takeRestartRefusal("n"))
        assertEquals(
            SwitchFailure(targetName = "Scratch", liveName = "Alpha", unresolved = true),
            engine.takeFailedSwitch("n"),
        )
        engine.stop()
    }

    @Test
    fun `a refused switch records the live profile's current name, once and only for its target`() = runTest {
        val a = secureProfile("a", "Alpha")
        val renamedA = a.copy(name = "Renamed") // the row was renamed after Alpha connected
        val nx = profileAt("n", "Scratch", "nx.invalid:51820")
        val backend = FakeBackend()
        // Only renamedA is passed for id "a": the engine() helper looks profiles up with
        // `profiles.firstOrNull { it.id == id }`, so a plain `a` earlier in the vararg would shadow it.
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, renamedA, nx,
            endpointLookupProblem = { "no answer from the underlying resolvers" },
        )
        engine.start(a) // connects as Alpha (the tunnel takes the passed Profile's name)
        engine.start(nx) // refused: the record must use the row's CURRENT name, from profileLookup
        assertNull(engine.takeFailedSwitch("a"))
        assertEquals(
            SwitchFailure(targetName = "Scratch", liveName = "Renamed", unresolved = true),
            engine.takeFailedSwitch("n"),
        )
        assertNull(engine.takeFailedSwitch("n")) // consumed
        engine.stop()
    }

    @Test
    fun `a backend failure on a switch records a failure without the lookup wording`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a, b)
        engine.start(a)
        backend.failNextUp = true
        engine.start(b) // GoBackend restores Alpha
        assertEquals(
            SwitchFailure(targetName = "Beta", liveName = "Alpha", unresolved = false),
            engine.takeFailedSwitch("b"),
        )
        engine.stop()
    }
    @Test
    fun `backend exceptions map to readable errors, one per reason`() = runTest {
        // C1: GoBackend reports its failures as BackendException, whose message is null; the old
        // generic catch showed "BackendException". Each reason now maps to pinned copy with no
        // config text. DNS_RESOLUTION_FAILURE reuses the endpoint wording; VPN_NOT_AUTHORIZED the
        // permission error.
        val cases: List<Pair<BackendException.Reason, TunnelError>> = listOf(
            BackendException.Reason.DNS_RESOLUTION_FAILURE to TunnelError.DnsFailure("vpn.example.com:51820"),
            BackendException.Reason.VPN_NOT_AUTHORIZED to TunnelError.PermissionDenied,
            BackendException.Reason.TUNNEL_MISSING_CONFIG to TunnelError.BackendError("The tunnel's configuration went missing."),
            BackendException.Reason.UNABLE_TO_START_VPN to TunnelError.BackendError("Android refused to start the VPN."),
            BackendException.Reason.TUN_CREATION_ERROR to TunnelError.BackendError("The tunnel interface could not be created."),
            BackendException.Reason.GO_ACTIVATION_ERROR_CODE to TunnelError.BackendError("WireGuard couldn't activate the tunnel."),
            BackendException.Reason.UNKNOWN_KERNEL_MODULE_NAME to TunnelError.BackendError("This device's kernel WireGuard support is unavailable."),
            BackendException.Reason.WG_QUICK_CONFIG_ERROR_CODE to TunnelError.BackendError("The profile's wg-quick configuration could not be applied."),
        )
        for ((reason, expected) in cases) {
            val a = secureProfile("a", "Alpha")
            val backend = FakeBackend().apply { nextBackendException = BackendException(reason) }
            val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a)
            engine.start(a)
            assertEquals("reason $reason", TunnelStatus(ConnectionPhase.FAILED, "a", expected), engine.status.value)
            engine.stop()
        }
    }

    @Test
    fun `nothing is recorded when no other profile's tunnel stays live`() = runTest {
        val a = profileAt("a", "Alpha", "vpn.example.com:51820")
        val b = secureProfile("b", "Beta")
        var resolves = true
        val backend = FakeBackend().apply { failNextUp = true }
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, a, b,
            endpointLookupProblem = { if (resolves) null else "no address returned" },
        )
        engine.start(b) // a failed fresh connect: nothing live, the dashboard shows FAILED
        assertNull(engine.takeFailedSwitch("b"))
        engine.start(a)
        resolves = false
        engine.start(a) // a same-profile restart the pre-check refused: Alpha stays, but it is Alpha's own start
        assertNull(engine.takeFailedSwitch("a"))
        engine.stop()
    }

    @Test
    fun `a queued later start keeps an unconsumed switch failure until it is reported`() = runTest {
        val a = secureProfile("a", "Alpha")
        val nx = profileAt("n", "Scratch", "nx.invalid:51820")
        val c = profileAt("c", "Gamma", "gamma.example.com:51820")
        val backend = FakeBackend()
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, a, nx, c,
            endpointLookupProblem = { host -> if (host == "nx.invalid") "no answer from the underlying resolvers" else null },
        )
        engine.start(a)
        engine.start(nx) // refused and recorded; its settle callback has not taken it yet
        engine.start(c) // a queued second connect succeeds — it must not wipe nx's record
        // The record still names ALPHA: it was written during start(nx), while Alpha's tunnel was
        // the live one; Gamma only became live afterwards and the record is not rewritten.
        assertEquals(
            SwitchFailure(targetName = "Scratch", liveName = "Alpha", unresolved = true),
            engine.takeFailedSwitch("n"),
        )
        assertNull(engine.takeFailedSwitch("n"))
        engine.stop()
    }

    @Test
    fun `a switch through the production pre-check asks the resolver controller`() = runTest {
        // Pins the default wiring EngineProvider relies on: no lookup fake is injected, the real controller decides.
        val a = secureProfile("a", "Alpha")
        val b = profileAt("b", "Beta", "vpn.example.com:51820")
        val backend = FakeBackend()
        val engine = WireGuardEngine(
            context = ApplicationProvider.getApplicationContext(),
            backend = backend,
            scope = CoroutineScope(SupervisorJob()),
            installedApps = { emptySet() },
            profileLookup = { id -> listOf(a, b).firstOrNull { it.id == id } },
            splitTunnelRules = { SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, emptySet()) },
            dns = controller(backgroundScope) { RecordingUpstream() },
            openChannel = { FakePipe() },
            vpnPermitted = { true },
            statsIntervalMs = 10_000,
            serviceHold = ServiceHold.None,
        )
        engine.start(a)
        engine.start(b) // the controller's protectDatagram is null: the default lookup refuses without sending anything
        assertEquals(listOf("Alpha" to Tunnel.State.UP), backend.calls)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), engine.status.value)
        assertEquals("Alpha", engine.liveTunnelName)
        engine.stop()
    }

    @Test
    fun `a connect without VPN permission reports the backend's permission error`() = runTest {
        // Review Focus 2: first run, a withdrawn consent, or another app set as the always-on VPN (AOSP Vpn.java
        // prepare; an app the user consented to is otherwise prepared again silently). GoBackend's own error
        // (setStateInternal: VPN_NOT_AUTHORIZED) is what the user sees, never a DNS one, and nothing of the
        // resolver runs.
        val a = secureProfile("a", "Alpha")
        var builds = 0
        var opens = 0
        val backend = FakeBackend().apply { notAuthorized = true }
        val engine = engine(
            backend, controller(backgroundScope) { builds++; RecordingUpstream() }, a,
            openChannel = { opens++; FakePipe() },
            vpnPermitted = { false },
        )
        engine.start(a)
        // C1: GoBackend's BackendException carries no message, so the engine maps its reason:
        // VPN_NOT_AUTHORIZED becomes the permission error.
        assertEquals(
            TunnelStatus(ConnectionPhase.FAILED, "a", TunnelError.PermissionDenied),
            engine.status.value,
        )
        assertEquals(1, backend.upConfigs.size) // setState was asked, and refused
        assertEquals(0, builds) // no resolver was built
        assertEquals(0, opens) // and no channel was opened
    }

    @Test
    fun `a connect whose VPN permission arrives after the check fails closed`() = runTest {
        // Plan ruling 10's race: vpnPermitted() said no, so nothing was staged, but the permission arrived before
        // setState and the tunnel came up. It must not stay up dropping every DNS query in silence.
        val a = secureProfile("a", "Alpha")
        var builds = 0
        var opens = 0
        val backend = FakeBackend() // setState succeeds: the permission is there by now
        val dns = controller(backgroundScope) { builds++; RecordingUpstream() }
        val engine = engine(backend, dns, a, openChannel = { opens++; FakePipe() }, vpnPermitted = { false })
        engine.start(a)
        assertEquals(
            TunnelStatus(
                ConnectionPhase.FAILED, "a",
                TunnelError.DnsResolverFailure("the VPN permission changed while connecting"),
            ),
            engine.status.value,
        )
        assertEquals(listOf("Alpha" to Tunnel.State.UP, "Alpha" to Tunnel.State.DOWN), backend.calls)
        assertNull(engine.liveTunnelName)
        assertEquals(0, builds)
        assertEquals(0, opens)
        assertNull(dns.current)
    }

    @Test
    fun `a failed switch from an ipv4 resolver to an ipv6 one puts the ipv4 resolver back`() = runTest {
        // Review Focus 4: GoBackend restores Alpha by itself after Beta fails (GoBackend.setState brings the
        // previous tunnel up again when the new UP throws). Alpha's config still names 10.111.222.3, and
        // Alpha's session must answer again.
        val a = secureProfile("a", "Alpha") // dual-stack: RESOLVER_V4
        val b = secureProfile("b", "Beta").let {
            it.copy(
                interfaceConfig = it.interfaceConfig.copy(addresses = listOf("fd00::2/128")),
                peer = it.peer.copy(allowedIps = listOf("::/0")),
            )
        } // routes only IPv6: RESOLVER_V6
        val builds = mutableListOf<CloseCountingUpstream>()
        val backend = FakeBackend()
        val dns = controller(backgroundScope) { CloseCountingUpstream().also { builds += it } }
        val engine = engine(backend, dns, a, b)
        engine.start(a)
        val alphaSession = dns.current

        backend.failNextUp = true
        engine.start(b) // GoBackend.setState brings Alpha back after Beta fails

        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), engine.status.value)
        assertSame(alphaSession, dns.current)
        assertEquals(listOf(0, 1), builds.map { it.closes }) // Alpha's upstream kept, Beta's retired
        assertEquals(
            listOf(listOf(InetAddress.getByName(RESOLVER_V4)), listOf(InetAddress.getByName(RESOLVER_V6))),
            backend.upConfigs.map { config -> config!!.`interface`.dnsServers.toList() },
        )
        engine.stop()
    }

    @Test
    fun `a dead channel is reopened at the next start`() = runTest {
        // Review Focus 5: the channel's reader saw end-of-file (or a pipe error) mid-tunnel. The next start opens a
        // fresh channel, because libwg-go refuses to start a tunnel without one.
        val a = secureProfile("a", "Alpha")
        val pipes = mutableListOf<FakePipe>()
        val opened = mutableListOf<List<InetAddress>>()
        val engine = engine(
            FakeBackend(), controller(backgroundScope) { RecordingUpstream() }, a,
            openChannel = { addresses -> opened += addresses; FakePipe().also { pipes += it } },
        )
        engine.start(a)
        engine.stop()
        engine.start(a) // the channel is still live: not opened again
        assertEquals(1, pipes.size)
        assertEquals(listOf(InetAddress.getByName(RESOLVER_V4), InetAddress.getByName(RESOLVER_V6)), opened.single())

        pipes.single().endOfFile() // libwg-go's end went away
        pipes.single().awaitClosed() // the reader saw end-of-file and released the channel
        engine.stop()
        engine.start(a)
        assertEquals(2, pipes.size)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 3), engine.status.value)
        engine.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a dns channel that cannot reopen marks the live session DnsNotAnswering`() = runTest {
        val a = secureProfile("a", "Alpha")
        val backend = FakeBackend()
        val pipes = mutableListOf<FakePipe>()
        var opens = 0
        val engine = engine(
            backend, controller(backgroundScope) { RecordingUpstream() }, a,
            openChannel = { opens++; if (opens == 1) FakePipe().also { pipes += it } else throw IOException("gone") },
        )
        engine.start(a)
        val connected = engine.status.value
        assertEquals(ConnectionPhase.CONNECTED, connected.phase)
        assertTrue("the session is stamped: ${connected.generation}", connected.generation > 0)
        pipes[0].endOfFile()
        pipes[0].awaitClosed()
        advanceTimeBy(3_000); runCurrent()
        val republished = engine.status.value
        assertEquals(
            TunnelStatus(ConnectionPhase.CONNECTED, "a", null, TunnelNotice.DnsNotAnswering, connected.generation),
            republished,
        )
        assertEquals(connected.generation, republished.generation) // markDnsNotAnswering's copy keeps it
        engine.stop() // a disconnect clears the notice
        assertEquals(TunnelStatus.idle, engine.status.value)
    }

    @Test
    fun `a plain profile keeps its servers and addresses, builds no resolver and still opens the channel`() = runTest {
        // Review Focus 1: most profiles are plain DNS. The interface gets exactly the profile's own DNS servers and
        // addresses, no resolver runs, and the channel is still opened, once, because libwg-go refuses to start a
        // tunnel without one.
        val plain = secureProfile("p", "Plain").let {
            it.copy(dns = DnsPolicy(), interfaceConfig = it.interfaceConfig.copy(dnsServers = listOf("10.64.0.1", "fd00::1")))
        }
        var builds = 0
        var opens = 0
        val backend = FakeBackend()
        val dns = controller(backgroundScope) { builds++; RecordingUpstream() }
        val engine = engine(backend, dns, plain, openChannel = { opens++; FakePipe() })
        engine.start(plain)
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "p", null, generation = 1), engine.status.value)
        val iface = backend.upConfigs.single()!!.`interface`
        assertEquals(listOf("10.64.0.1", "fd00:0:0:0:0:0:0:1"), iface.dnsServers.map { it.hostAddress })
        assertEquals(setOf("10.0.0.2/32", "fd00:0:0:0:0:0:0:2/128"), iface.addresses.map { it.toString() }.toSet())
        assertEquals(0, builds)
        assertNull(dns.current)
        assertEquals(1, opens)
        engine.stop()
        engine.start(plain) // a reconnect reuses the live channel
        assertEquals(1, opens)
        engine.stop()
    }

    @Test
    fun `a switch to a plain profile retires the previous resolver and a stop retires the current one`() = runTest {
        val a = secureProfile("a", "Alpha")
        val plain = secureProfile("p", "Plain").let {
            it.copy(dns = DnsPolicy(), interfaceConfig = it.interfaceConfig.copy(dnsServers = listOf("10.64.0.1")))
        }
        val builds = mutableListOf<CloseCountingUpstream>()
        val dns = controller(backgroundScope) { CloseCountingUpstream().also { builds += it } }
        val engine = engine(FakeBackend(), dns, a, plain)
        engine.start(a)
        engine.start(plain) // B1 §6.5 step 6: the switch succeeded, so Alpha's resolver retires
        assertNull(dns.current)
        assertEquals(listOf(1), builds.map { it.closes })
        engine.start(a)
        assertEquals("a", dns.current?.profileId)
        engine.stop() // a stop retires the current resolver
        assertNull(dns.current)
        assertEquals(listOf(1, 1), builds.map { it.closes })
    }

    @Test
    fun `a start whose dns channel cannot open reports DnsResolverFailure and retires the resolver it built`() = runTest {
        // B1 §6.5 step 3: libwg-go refuses to start without a channel, so a channel that cannot open fails the
        // start before the backend is touched.
        val a = secureProfile("a", "Alpha")
        val builds = mutableListOf<CloseCountingUpstream>()
        val backend = FakeBackend()
        val dns = controller(backgroundScope) { CloseCountingUpstream().also { builds += it } }
        val engine = engine(backend, dns, a, openChannel = { throw IOException("socketpair failed") })
        engine.start(a)
        assertEquals(
            TunnelStatus(ConnectionPhase.FAILED, "a", TunnelError.DnsResolverFailure("socketpair failed")),
            engine.status.value,
        )
        assertTrue(backend.upConfigs.isEmpty())
        assertEquals(listOf(1), builds.map { it.closes })
        assertNull(dns.current)
    }

    @Test
    fun `a fresh connect cancelled during its resolver build reports FAILED, not a stuck CONNECTING`() = runTest {
        // Plan ruling 20: a fresh connect shows CONNECTING before its build, so a caller that dies during a slow
        // bootstrap must not leave it showing. It ends the way a cancellation during setState does.
        val a = secureProfile("a", "Alpha")
        val entered = CountDownLatch(1)
        val backend = FakeBackend()
        val dns = controller(backgroundScope) { entered.countDown(); kotlinx.coroutines.awaitCancellation() }
        val engine = engine(backend, dns, a)
        val starter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = starter.launch { engine.start(a) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTING, "a", null), engine.status.value)
        job.cancel() // the caller dies while the resolver bootstraps
        job.join()
        assertEquals(
            TunnelStatus(ConnectionPhase.FAILED, "a", TunnelError.BackendError("connect cancelled")),
            engine.status.value,
        )
        assertTrue(backend.calls.isEmpty())
        assertNull(dns.current)
        starter.cancel()
    }

    @Test
    fun `a switch cancelled during its resolver build leaves the live tunnel as it was`() = runTest {
        // Plan ruling 20: a switch never left CONNECTED for the live profile, so its cancellation changes nothing, as
        // a cancelled F33 lookup does: no status change, no K3 record, and Alpha's resolver still answers.
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val entered = CountDownLatch(1)
        var blockNextBuild = false
        val backend = FakeBackend()
        val dns = controller(backgroundScope) {
            if (blockNextBuild) {
                entered.countDown()
                kotlinx.coroutines.awaitCancellation()
            }
            RecordingUpstream()
        }
        val engine = engine(backend, dns, a, b)
        engine.start(a)
        val alphaSession = dns.current
        blockNextBuild = true
        val starter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = starter.launch { engine.start(b) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel() // the caller dies while Beta's resolver bootstraps
        job.join()
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = 1), engine.status.value)
        assertEquals(listOf("Alpha" to Tunnel.State.UP), backend.calls)
        assertSame(alphaSession, dns.current)
        assertNull(engine.takeFailedSwitch("b"))
        engine.stop()
        starter.cancel()
    }

    @Test
    fun `libwg-go runtime numbers are logged after every turn-on and turn-off`() = runTest {
        val a = secureProfile("a", "Alpha")
        val engine = engine(
            FakeBackend(), controller(backgroundScope) { RecordingUpstream() }, a,
            nativeStats = { "goroutines=7 heapInuse=1 heapReleased=2" },
        )
        engine.start(a)
        engine.stop()
        val logged = org.robolectric.shadows.ShadowLog.getLogsForTag("WireGuardEngine").map { it.msg }
        assertEquals(
            listOf(
                "libwg-go after turn-on: goroutines=7 heapInuse=1 heapReleased=2",
                "libwg-go after turn-off: goroutines=7 heapInuse=1 heapReleased=2",
            ),
            logged.filter { it.startsWith("libwg-go after") },
        )
    }

    @Test
    fun `generations increase per start`() = runTest {
        val a = secureProfile("a", "Alpha")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a)
        engine.start(a)
        val first = engine.status.value
        assertEquals(ConnectionPhase.CONNECTED, first.phase)
        assertTrue("a start stamps a non-zero generation: ${first.generation}", first.generation > 0)
        engine.stop()
        engine.start(a) // a full connect/disconnect cycle: the new tunnel is a new session
        val second = engine.status.value
        assertEquals(ConnectionPhase.CONNECTED, second.phase)
        assertTrue("generations increase: ${first.generation} -> ${second.generation}", second.generation > first.generation)
        assertEquals(first.generation + 1, second.generation) // nothing else started in between
        engine.stop()
    }

    @Test
    fun `a surviving republish keeps the live generation`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a, b)
        engine.start(a)
        val session = engine.status.value
        assertEquals(ConnectionPhase.CONNECTED, session.phase)
        assertTrue(session.generation > 0)

        backend.failNextUp = true
        engine.start(b) // GoBackend restores Alpha: the republish describes the live tunnel, not a new one

        val republished = engine.status.value
        assertEquals(TunnelStatus(ConnectionPhase.CONNECTED, "a", null, generation = session.generation), republished)
        engine.stop()
    }

    @Test
    fun `a failed switch then a successful restart of A stamps a new generation`() = runTest {
        val a = secureProfile("a", "Alpha")
        val b = secureProfile("b", "Beta")
        val backend = FakeBackend()
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a, b)
        engine.start(a)
        val g = engine.status.value.generation
        assertTrue(g > 0)

        backend.failNextUp = true
        engine.start(b) // a failed switch: A survives with its own generation
        assertEquals(g, engine.status.value.generation)

        engine.start(a) // a new successful start of A: a new session, a new generation
        val restarted = engine.status.value
        assertEquals(ConnectionPhase.CONNECTED, restarted.phase)
        assertEquals("a", restarted.profileId)
        assertEquals(g + 1, restarted.generation)
        engine.stop()
    }

    @Test
    fun `samples carry their start's generation`() = runTest {
        // Spec §4.4.5 test 5, third bullet: the engine starts the sampler with the start's
        // generation, so its samples match the CONNECTED status the UI pairs them with.
        val a = secureProfile("a", "Alpha")
        val backend = FakeBackend().apply { statisticsProvider = { TestStatistics.withHandshake(1_234) } }
        val engine = engine(backend, controller(backgroundScope) { RecordingUpstream() }, a)
        engine.start(a)
        val connected = engine.status.value
        assertEquals(ConnectionPhase.CONNECTED, connected.phase)
        assertTrue("a start stamps a non-zero generation: ${connected.generation}", connected.generation > 0)
        // The sampler runs on the engine's own (real) scope: await its first sample in real
        // time so a broken stamp fails here instead of riding the virtual clock.
        val sample = withContext(Dispatchers.Default) {
            withTimeout(5_000) { engine.stats.first { it.generation > 0 } }
        }
        assertEquals(connected.generation, sample.generation)
        engine.stop()
    }
}
