package dev.vaayil.core.tunnel.wg

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config as WgConfig
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.dns.DnsUpstream
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.tunnel.api.TunnelStatus
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowVpnService

/**
 * Wiring of the service intents onto the process-scope [TunnelControl]: the connect/disconnect
 * must be delegated (never run on the instance scope — see TunnelControlTest for the survival
 * invariant) and the service lifecycle decisions must still fire from the settle callbacks.
 * Most tests leave EngineProvider engine-less: REFUSED keeps the whole path free of GoBackend's
 * native load (the C1 environment limit). The revoke test installs a real engine over a fake
 * backend, which loads no native code either.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class VpnTunnelServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val connected = mutableListOf<String>()
    private val controlJob = SupervisorJob()
    private var stopCalls = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        stopCalls = 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.stats.value = TunnelStats.empty
        ConnectionRegistry.handshake.value = HandshakeState.NONE
        VpnTunnelService.controlOverride = null
        EngineProvider.crashResumeGate = CrashResumeGate()
        runBlocking {
            withTimeout(5_000) {
                ActiveProfileStore(context).apply { setActive(null); setLastConnected(null) }
            }
        }
    }

    /** Installs a scripted orchestrator whose connect settles synchronously (Unconfined). */
    private fun installControl(
        outcome: StartOutcome,
        status: TunnelStatus,
        failedSwitch: (String) -> SwitchFailure? = { null },
        restartRefusal: (String) -> RestartRefusal? = { null },
        stopOp: suspend () -> Unit = { stopCalls += 1 },
    ) {
        VpnTunnelService.controlOverride = TunnelControl(
            scope = CoroutineScope(controlJob + Dispatchers.Unconfined),
            connectOp = { _, id, _ -> connected += id; outcome },
            statusNow = { status },
            stopOp = stopOp,
            failedSwitchOp = failedSwitch,
            restartRefusalOp = restartRefusal,
        )
    }

    private fun builtService(): VpnTunnelService =
        Robolectric.buildService(VpnTunnelService::class.java).create().get()

    @Test
    fun `the notification follows the handshake while connected`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p", null, generation = 1)
        ConnectionRegistry.handshake.value = HandshakeState.PENDING
        val service = builtService()

        val pending = shadowOf(service).lastForegroundNotification
        assertNotNull(pending)
        assertEquals("Vaayil is connecting", pending.extras.getCharSequence(Notification.EXTRA_TITLE).toString())

        ConnectionRegistry.handshake.value = HandshakeState.VERIFIED
        shadowOf(Looper.getMainLooper()).idle()

        val verified = shadowOf(service).lastForegroundNotification
        assertNotNull(verified)
        assertEquals("Vaayil is connected", verified.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    /** Joins every coroutine the scripted control launched, so assertions observe their settled state. */
    private fun awaitControl() = runBlocking { withTimeout(5_000) { controlJob.children.toList().joinAll() } }

    private fun connectIntent(profileId: String? = null): Intent =
        Intent(context, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_CONNECT)
            .apply { if (profileId != null) putExtra(VpnTunnelService.EXTRA_PROFILE_ID, profileId) }

    private fun resumeIntent(id: String): Intent =
        connectIntent(id).putExtra(VpnTunnelService.EXTRA_RESUME, true)

    private fun startWith(action: String): VpnTunnelService {
        val intent = Intent(ApplicationProvider.getApplicationContext(), VpnTunnelService::class.java)
            .setAction(action)
        val controller = Robolectric.buildService(VpnTunnelService::class.java, intent).create()
        controller.startCommand(0, 0)
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }

    /**
     * The engine the revoke path reaches the resolver through: a real [WireGuardEngine] over a
     * fake backend, so `onRevoke`'s synchronous deactivation is observable without native code.
     */
    private class FakeBackend : Backend {
        private var live: Tunnel? = null

        /** Every setState, in order, as (tunnel name, state). Copy-on-write: the engine appends from its IO thread while the test thread polls. */
        val calls: MutableList<Pair<String, Tunnel.State>> = CopyOnWriteArrayList()

        /** When set, invoked before an UP is recorded; may block (the establish window holds the engine lock). */
        var onUp: (() -> Unit)? = null

        override fun getState(tunnel: Tunnel): Tunnel.State =
            if (live === tunnel) Tunnel.State.UP else Tunnel.State.DOWN

        override fun getStatistics(tunnel: Tunnel): Statistics =
            throw UnsupportedOperationException("no statistics in tests")

        override fun getVersion(): String = "fake"

        override fun getRunningTunnelNames(): Set<String> = emptySet()

        override fun isAlwaysOn(): Boolean = false

        override fun isLockdownEnabled(): Boolean = false

        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: WgConfig?): Tunnel.State {
            if (state == Tunnel.State.UP) onUp?.invoke()
            calls += tunnel.name to state
            live = if (state == Tunnel.State.UP) tunnel else null
            return if (live != null) Tunnel.State.UP else Tunnel.State.DOWN
        }
    }

    /** An upstream that only counts how often its owner closed it. */
    private class CloseCountingUpstream : DnsUpstream {
        var closes = 0
        override suspend fun query(queryWire: ByteArray): ByteArray? = null
        override fun close() {
            closes++
        }
    }

    /** A profile whose secure DNS makes the start build and commit a resolver (an intercepting plan). */
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

    /** A real engine over [backend] for [profile], owning the resolver [dns] under test. */
    private fun engineOver(backend: Backend, dns: DnsResolverController, profile: Profile) =
        WireGuardEngine(
            context = context,
            backend = backend,
            scope = CoroutineScope(SupervisorJob()),
            installedApps = { emptySet() },
            profileLookup = { id -> profile.takeIf { it.id == id } },
            splitTunnelRules = { SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, emptySet()) },
            dns = dns,
            openChannel = { FakePipe() },
            vpnPermitted = { true },
        )

    /** A minimal non-intercepting profile: plain DNS and an IPv4-only interface, so no resolver is staged. */
    private fun plainProfile(id: String, name: String) = Profile(
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
            endpoint = "vpn.example.com:51820",
            allowedIps = listOf("0.0.0.0/0"),
            persistentKeepalive = null,
        ),
        dns = DnsPolicy(),
    )

    /** A resolver controller for an engine under test; a plain profile never asks it for an upstream. */
    private fun resolver() = DnsResolverController(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        protect = { null },
        protectDatagram = { null },
        underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
        ok = OkHttpClient(),
    )

    /** Swaps EngineProvider's engine (its setter is private) for [subject] and always restores the previous. */
    private suspend fun withProviderEngine(subject: WireGuardEngine?, block: suspend () -> Unit) {
        val holder = EngineProvider::class.java.getDeclaredField("engine").apply { isAccessible = true }
        val previous = holder.get(null)
        holder.set(null, subject)
        try {
            block()
        } finally {
            holder.set(null, previous)
        }
    }

    private fun defaultControlParent(): Job {
        val scopeField = TunnelControl.Default::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = scopeField.get(TunnelControl.Default) as CoroutineScope
        return scope.coroutineContext[Job] ?: error("TunnelControl.Default has no parent job")
    }

    /**
     * Boundedly drains the real [TunnelControl.Default] process scope. A started connect's
     * pointer writes and settle callback all run before its Job completes, so joining the
     * scope's children before the engine and the gate are restored keeps a mid-flight start
     * from writing records or invoking callbacks behind the restore. Used only by the tests
     * that drive the real provider.
     */
    private suspend fun drainDefaultControl() {
        val parent = defaultControlParent()
        withTimeout(5_000) {
            while (true) {
                val children = parent.children.toList()
                if (children.isEmpty()) break
                children.joinAll()
            }
        }
    }

    /**
     * `WireGuardEngine.stop` enters `NonCancellable` before taking the engine mutex, so a
     * timeout on the calling coroutine can never interrupt a stop parked on a deadlocked
     * mutex — a `withTimeout` around it is not a bound. The stop therefore runs on a scope
     * this cleanup never joins, and only the waiter is bounded: a timed-out stop is
     * abandoned (it cannot be cancelled; R9 accepts that in an already-red suite).
     */
    private suspend fun stopEngineBounded(engine: WireGuardEngine) {
        val stop = CoroutineScope(SupervisorJob() + Dispatchers.IO).async { engine.stop() }
        withTimeout(5_000) { stop.await() }
    }

    /**
     * Shared cleanup for the real-provider tests, run from each one's `finally`: bars the
     * resume, releases the test's barriers, boundedly drains the process-scope control work,
     * and stops the engine. A drain that times out cancels the scope's outstanding children
     * (its children only — never the scope itself) and joins them again with a 15 s bound —
     * longer than every fixture's self-release (the fake backend's UP latch releases at 5 s,
     * with the engine's NonCancellable failure handling running behind it), so that join can
     * time out only on a genuine deadlock. `engine.stop()` is ALWAYS attempted, whatever the
     * drain did, boundedly through [stopEngineBounded] — a bound the uncancellable stop
     * cannot ignore. Every error is collected into one aggregate and
     * surfaced at the end — attached to the body's failure with [Throwable.addSuppressed]
     * when the body already threw, thrown otherwise; no step's failure skips a later step.
     */
    private suspend fun cleanupResumeFixture(
        engine: WireGuardEngine,
        releaseLookup: CompletableDeferred<Unit>,
        releaseUp: CountDownLatch?,
        primary: Throwable?,
    ) {
        val errors = mutableListOf<Throwable>()
        runCatching {
            EngineProvider.crashResumeGate.suppress()
            releaseUp?.countDown()
            releaseLookup.complete(Unit)
        }.onFailure(errors::add)
        runCatching { drainDefaultControl() }.onFailure { drainFailure ->
            errors.add(drainFailure)
            runCatching {
                val remaining = defaultControlParent().children.toList()
                remaining.forEach { it.cancel() }
                // 15 s exceeds every fixture self-release these tests use: the fake backend's
                // UP latch releases at 5 s and the engine's NonCancellable failure handling
                // runs behind it — the join may only time out on a genuine deadlock.
                withTimeout(15_000) { remaining.joinAll() }
            }.onFailure(errors::add)
        }
        runCatching { stopEngineBounded(engine) }.onFailure(errors::add)
        val failure = errors.reduceOrNull { head, next -> head.apply { addSuppressed(next) } }
        if (failure != null) {
            val p = primary
            if (p != null) p.addSuppressed(failure) else throw failure
        }
    }

    @Test
    fun `a connect with no engine is refused and stops the service`() {
        val service = startWith(VpnTunnelService.ACTION_CONNECT)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(TunnelStatus.idle, ConnectionRegistry.status.value)
    }

    @Test
    fun `a disconnect resets the registry and stops the service even with no engine`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p", null)
        val service = startWith(VpnTunnelService.ACTION_DISCONNECT)
        assertTrue(shadowOf(service).isStoppedBySelf)
        // The registry update trails the active-record write (a real DataStore edit on IO).
        val deadline = System.currentTimeMillis() + 5_000
        while (ConnectionRegistry.status.value.phase != ConnectionPhase.DISCONNECTED &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(20)
        assertEquals(ConnectionPhase.DISCONNECTED, ConnectionRegistry.status.value.phase)
    }

    @Test
    fun `destroying the service with no engine clears a stale live claim`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p", null)
        val controller = Robolectric.buildService(VpnTunnelService::class.java).create()
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ConnectionPhase.DISCONNECTED, ConnectionRegistry.status.value.phase)
    }

    @Test
    fun `a connect that leaves a live tunnel releases its own start request`() {
        installControl(StartOutcome.STARTED, TunnelStatus(ConnectionPhase.CONNECTED, "p", null))
        val service = builtService()

        val result = service.onStartCommand(connectIntent("p"), 0, 7)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(7, shadowOf(service).stopSelfId)
        assertTrue(shadowOf(service).isStoppedBySelf)
        awaitControl()
        assertEquals(0, stopCalls)
    }

    @Test
    fun `a failed switch that keeps the previous tunnel live releases its own start request`() {
        installControl(StartOutcome.FAILED, TunnelStatus(ConnectionPhase.CONNECTED, "work", null))
        val service = builtService()

        val result = service.onStartCommand(connectIntent("home"), 0, 8)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(8, shadowOf(service).stopSelfId)
        assertTrue(shadowOf(service).isStoppedBySelf)
        awaitControl()
        assertEquals(0, stopCalls)
    }

    @Test
    fun `a connect that settles with nothing live releases its own start request`() {
        installControl(StartOutcome.REFUSED, TunnelStatus.idle)
        val service = builtService()

        val result = service.onStartCommand(connectIntent("p"), 0, 9)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(9, shadowOf(service).stopSelfId)
    }

    @Test
    fun `an always-on or null-intent start is not sticky and still falls back to the last connected profile`() = runBlocking {
        ActiveProfileStore(context).setActive(null)
        ActiveProfileStore(context).setLastConnected("last")
        installControl(StartOutcome.REFUSED, TunnelStatus.idle)
        val service = builtService()

        val alwaysOn = Intent(context, VpnTunnelService::class.java)
            .setAction(android.net.VpnService.SERVICE_INTERFACE)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(alwaysOn, 0, 3))
        assertEquals(3, shadowOf(service).stopSelfId)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 4))
        assertEquals(4, shadowOf(service).stopSelfId)

        assertEquals(listOf("last", "last"), connected)
    }

    @Test
    fun `a start with no profile to connect releases only its own start request`() {
        installControl(StartOutcome.REFUSED, TunnelStatus.idle)
        val service = builtService()

        val result = service.onStartCommand(connectIntent(), 0, 5)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(5, shadowOf(service).stopSelfId)
        assertTrue(connected.isEmpty())
    }

    @Test
    fun `a disconnect clears the active record before the engine stops`() = runBlocking {
        var seenAtStop: String? = "unset"
        installControl(StartOutcome.REFUSED, TunnelStatus.idle) {
            seenAtStop = ActiveProfileStore(context).activeId.first()
        }
        ActiveProfileStore(context).setActive("p")
        val service = builtService()

        val intent = Intent(context, VpnTunnelService::class.java)
            .setAction(VpnTunnelService.ACTION_DISCONNECT)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 6))

        val deadline = System.currentTimeMillis() + 5_000
        while (seenAtStop == "unset" && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertNull(seenAtStop)
        assertNull(ActiveProfileStore(context).activeId.first())
    }

    @Test
    fun `a resume after a disconnect at the same process start is dropped`() {
        installControl(StartOutcome.REFUSED, TunnelStatus.idle)
        val service = builtService()

        val disconnect = Intent(context, VpnTunnelService::class.java)
            .setAction(VpnTunnelService.ACTION_DISCONNECT)
        service.onStartCommand(disconnect, 0, 1)

        val result = service.onStartCommand(resumeIntent("a"), 0, 2)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(2, shadowOf(service).stopSelfId)
        assertTrue(connected.isEmpty())
    }

    @Test
    fun `a resume after a connect to another profile is dropped`() {
        installControl(StartOutcome.STARTED, TunnelStatus(ConnectionPhase.CONNECTED, "b", null))
        val service = builtService()

        service.onStartCommand(connectIntent("b"), 0, 1)

        service.onStartCommand(resumeIntent("a"), 0, 2)

        assertEquals(listOf("b"), connected)
        assertEquals(2, shadowOf(service).stopSelfId)
    }

    @Test
    fun `a resume that arrives first connects and a later disconnect still stops the engine`() = runBlocking {
        var stopped = false
        installControl(StartOutcome.REFUSED, TunnelStatus.idle) { stopped = true }
        val service = builtService()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(resumeIntent("a"), 0, 1))
        assertEquals(listOf("a"), connected)

        val disconnect = Intent(context, VpnTunnelService::class.java)
            .setAction(VpnTunnelService.ACTION_DISCONNECT)
        service.onStartCommand(disconnect, 0, 2)

        val deadline = System.currentTimeMillis() + 5_000
        while (!stopped && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(stopped)
    }

    /** An engine over [backend] whose lookup of [parkId] parks until [releaseLookup] completes; other ids resolve at once. */
    private fun parkedLookupEngine(
        backend: Backend,
        profiles: Map<String, Profile>,
        parkId: String,
        lookupEntered: CompletableDeferred<Unit>,
        releaseLookup: CompletableDeferred<Unit>,
    ) = WireGuardEngine(
        context = context,
        backend = backend,
        scope = CoroutineScope(SupervisorJob()),
        installedApps = { emptySet() },
        profileLookup = { id ->
            if (id == parkId) {
                lookupEntered.complete(Unit)
                releaseLookup.await()
            }
            profiles[id]
        },
        splitTunnelRules = { SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, emptySet()) },
        dns = resolver(),
        openChannel = { FakePipe() },
        vpnPermitted = { true },
    )

    @Test
    fun `disconnect during resume profile lookup cannot be followed by UP`() = runBlocking {
        val backend = FakeBackend()
        val lookupEntered = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val profile = plainProfile("p1", "Alpha")
        val engine = parkedLookupEngine(backend, mapOf("p1" to profile), "p1", lookupEntered, releaseLookup)
        withProviderEngine(engine) {
            var primary: Throwable? = null
            try {
                // The crash leftovers: the record the resume revives, and the LAST pointer a disconnect keeps.
                ActiveProfileStore(context).apply { setActive("p1"); setLastConnected("p1") }
                // No control override: the resume runs through the real EngineProvider.connectById.
                val service = builtService()

                service.onStartCommand(resumeIntent("p1"), 0, 1)

                // The resume is parked in its profile lookup; nothing has started yet.
                withTimeout(5_000) { lookupEntered.await() }
                assertEquals(ConnectionPhase.DISCONNECTED, engine.status.value.phase)

                val disconnect = Intent(context, VpnTunnelService::class.java)
                    .setAction(VpnTunnelService.ACTION_DISCONNECT)
                assertEquals(Service.START_NOT_STICKY, service.onStartCommand(disconnect, 0, 2))

                releaseLookup.complete(Unit)

                // The teardown joins the refused resume, then clears ACTIVE and resets the
                // registry; give that settle time before asserting the end state.
                val deadline = System.currentTimeMillis() + 5_000
                while ((ActiveProfileStore(context).activeId.first() != null ||
                        ConnectionRegistry.status.value.phase != ConnectionPhase.DISCONNECTED) &&
                    System.currentTimeMillis() < deadline
                ) Thread.sleep(20)

                assertTrue(backend.calls.none { it.second == Tunnel.State.UP })
                assertEquals(ConnectionPhase.DISCONNECTED, engine.status.value.phase)
                assertNull(ActiveProfileStore(context).activeId.first())
                assertEquals("p1", ActiveProfileStore(context).lastConnectedId.first())
                assertEquals(1, shadowOf(service).stopSelfId)
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                cleanupResumeFixture(engine, releaseLookup, null, primary)
            }
        }
    }

    @Test
    fun `an issued resume loses to a prior suppression`() = runBlocking {
        // Capture the real tagged intent the resume path issues. The stamped store mirrors the
        // crash record: ACTIVE names p1 in THIS boot, so takeResumableActive revives it.
        val store = ActiveProfileStore(context) { 7 }
        store.apply { setActive("p1"); setLastConnected("p1") }
        ShadowVpnService.setPrepareResult(null)
        val issued = mutableListOf<Intent>()
        val recording = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? {
                issued += service
                return service.component
            }
        }
        EngineProvider.resumeInterruptedSession(recording, store)
        val resume = issued.single()
        assertTrue(resume.getBooleanExtra(VpnTunnelService.EXTRA_RESUME, false))

        // A disconnect barred the resume before its intent is delivered.
        EngineProvider.crashResumeGate.suppress()

        installControl(StartOutcome.STARTED, TunnelStatus(ConnectionPhase.CONNECTED, "p1", null))
        val service = builtService()

        val result = service.onStartCommand(resume, 0, 21)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(21, shadowOf(service).stopSelfId)
        assertTrue(connected.isEmpty())
    }

    @Test
    fun `a disconnect past the resume's admit check stops it after it comes up`() = runBlocking {
        val backend = FakeBackend()
        val upEntered = CountDownLatch(1)
        val releaseUp = CountDownLatch(1)
        backend.onUp = {
            upEntered.countDown()
            releaseUp.await(5, TimeUnit.SECONDS)
        }
        val lookupEntered = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val profile = plainProfile("p1", "Alpha")
        val engine = parkedLookupEngine(backend, mapOf("p1" to profile), "p1", lookupEntered, releaseLookup)
        withProviderEngine(engine) {
            var primary: Throwable? = null
            try {
                ActiveProfileStore(context).apply { setActive("p1"); setLastConnected("p1") }
                val service = builtService()

                service.onStartCommand(resumeIntent("p1"), 0, 1)
                withTimeout(5_000) { lookupEntered.await() }
                // The lookup releases into an admit check that still passes; the UP parks in the backend.
                releaseLookup.complete(Unit)
                assertTrue("the resume never reached the backend's UP", upEntered.await(5, TimeUnit.SECONDS))

                val disconnect = Intent(context, VpnTunnelService::class.java)
                    .setAction(VpnTunnelService.ACTION_DISCONNECT)
                assertEquals(Service.START_NOT_STICKY, service.onStartCommand(disconnect, 0, 2))

                releaseUp.countDown()

                // The teardown joined the resume: once it settled UP, the stop brings it down.
                val deadline = System.currentTimeMillis() + 5_000
                while ((engine.status.value.phase != ConnectionPhase.DISCONNECTED ||
                        backend.calls.lastOrNull()?.second != Tunnel.State.DOWN ||
                        ConnectionRegistry.status.value.phase != ConnectionPhase.DISCONNECTED) &&
                    System.currentTimeMillis() < deadline
                ) Thread.sleep(20)

                assertEquals(listOf("Alpha" to Tunnel.State.UP, "Alpha" to Tunnel.State.DOWN), backend.calls)
                assertEquals(ConnectionPhase.DISCONNECTED, ConnectionRegistry.status.value.phase)
                assertEquals(ConnectionPhase.DISCONNECTED, engine.status.value.phase)
                assertNull(ActiveProfileStore(context).activeId.first())
                assertEquals("p1", ActiveProfileStore(context).lastConnectedId.first())
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                cleanupResumeFixture(engine, releaseLookup, releaseUp, primary)
            }
        }
    }

    @Test
    fun `a revoke during an accepted resume ends down`() = runBlocking {
        val backend = FakeBackend()
        val upEntered = CountDownLatch(1)
        val releaseUp = CountDownLatch(1)
        backend.onUp = {
            upEntered.countDown()
            releaseUp.await(5, TimeUnit.SECONDS)
        }
        val lookupEntered = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val profile = plainProfile("p1", "Alpha")
        val engine = parkedLookupEngine(backend, mapOf("p1" to profile), "p1", lookupEntered, releaseLookup)
        withProviderEngine(engine) {
            var primary: Throwable? = null
            try {
                ActiveProfileStore(context).apply { setActive("p1"); setLastConnected("p1") }
                val service = builtService()

                service.onStartCommand(resumeIntent("p1"), 0, 1)
                withTimeout(5_000) { lookupEntered.await() }
                releaseLookup.complete(Unit)
                assertTrue("the resume never reached the backend's UP", upEntered.await(5, TimeUnit.SECONDS))

                service.onRevoke()

                releaseUp.countDown()

                val deadline = System.currentTimeMillis() + 5_000
                while ((engine.status.value.phase != ConnectionPhase.DISCONNECTED ||
                        backend.calls.lastOrNull()?.second != Tunnel.State.DOWN ||
                        ConnectionRegistry.status.value.phase != ConnectionPhase.DISCONNECTED) &&
                    System.currentTimeMillis() < deadline
                ) Thread.sleep(20)

                assertEquals(listOf("Alpha" to Tunnel.State.UP, "Alpha" to Tunnel.State.DOWN), backend.calls)
                assertEquals(ConnectionPhase.DISCONNECTED, ConnectionRegistry.status.value.phase)
                assertEquals(ConnectionPhase.DISCONNECTED, engine.status.value.phase)
                assertNull(ActiveProfileStore(context).activeId.first())
                assertEquals("p1", ActiveProfileStore(context).lastConnectedId.first())
                assertTrue(shadowOf(service).isStoppedBySelf)
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                cleanupResumeFixture(engine, releaseLookup, releaseUp, primary)
            }
        }
    }

    @Test
    fun `an explicit connect during the resume's profile lookup wins`() = runBlocking {
        val backend = FakeBackend()
        val lookupEntered = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val engine = parkedLookupEngine(
            backend,
            mapOf("p1" to plainProfile("p1", "Alpha"), "p2" to plainProfile("p2", "Beta")),
            "p1", lookupEntered, releaseLookup,
        )
        withProviderEngine(engine) {
            var primary: Throwable? = null
            try {
                ActiveProfileStore(context).apply { setActive("p1"); setLastConnected("p1") }
                val service = builtService()

                service.onStartCommand(resumeIntent("p1"), 0, 1)
                withTimeout(5_000) { lookupEntered.await() }

                service.onStartCommand(connectIntent("p2"), 0, 2)

                // The explicit start wins the engine and settles first.
                val deadline = System.currentTimeMillis() + 5_000
                while (shadowOf(service).stopSelfId != 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
                assertEquals(2, shadowOf(service).stopSelfId)
                assertEquals(ConnectionPhase.CONNECTED, engine.status.value.phase)

                // The released lookup ends the resume in a refused admit, releasing its own id too.
                releaseLookup.complete(Unit)
                val settleDeadline = System.currentTimeMillis() + 5_000
                while (shadowOf(service).stopSelfId != 1 && System.currentTimeMillis() < settleDeadline) Thread.sleep(20)
                assertEquals(1, shadowOf(service).stopSelfId)

                assertTrue(backend.calls.none { it.first == "Alpha" })
                assertTrue(backend.calls.any { it.first == "Beta" && it.second == Tunnel.State.UP })
                assertEquals("p2", ActiveProfileStore(context).activeId.first())
                assertEquals("p2", ActiveProfileStore(context).lastConnectedId.first())
                assertEquals(ConnectionPhase.CONNECTED, engine.status.value.phase)
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                cleanupResumeFixture(engine, releaseLookup, null, primary)
            }
        }
    }

    @Test
    fun `a connect releases its start request only once it settles`() {
        val gate = CompletableDeferred<StartOutcome>()
        VpnTunnelService.controlOverride = TunnelControl(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectOp = { _, id, _ -> connected += id; gate.await() },
            statusNow = { TunnelStatus(ConnectionPhase.CONNECTED, "p", null) },
            stopOp = {},
        )
        val service = builtService()

        service.onStartCommand(connectIntent("p"), 0, 11)

        assertTrue(shadowOf(service).stopSelfId != 11)
        assertTrue(!shadowOf(service).isStoppedBySelf)

        gate.complete(StartOutcome.STARTED)

        assertEquals(11, shadowOf(service).stopSelfId)
    }

    @Test
    fun `an unknown action holds no start request`() {
        installControl(StartOutcome.STARTED, TunnelStatus(ConnectionPhase.CONNECTED, "p", null))
        val service = builtService()

        val intent = Intent(context, VpnTunnelService::class.java)
            .setAction("dev.vaayil.action.UNKNOWN")
            .putExtra(VpnTunnelService.EXTRA_PROFILE_ID, "p")
        val result = service.onStartCommand(intent, 0, 12)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(12, shadowOf(service).stopSelfId)
        assertTrue(connected.isEmpty())
    }

    @Test
    fun `a disconnect clears an active record written back while the engine stops`() = runBlocking {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p", null)
        installControl(StartOutcome.REFUSED, TunnelStatus.idle) {
            ActiveProfileStore(context).setActive("p")
        }
        val service = builtService()

        val disconnect = Intent(context, VpnTunnelService::class.java)
            .setAction(VpnTunnelService.ACTION_DISCONNECT)
        service.onStartCommand(disconnect, 0, 13)

        val deadline = System.currentTimeMillis() + 5_000
        while (ConnectionRegistry.status.value.phase != ConnectionPhase.DISCONNECTED &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(20)
        assertNull(ActiveProfileStore(context).activeId.first())
    }

    @Test
    fun `a failed switch hands the engine's recorded failure to the registry`() {
        val failure = SwitchFailure(targetName = "Home", liveName = "Work", unresolved = true)
        val asked = mutableListOf<String>()
        installControl(
            StartOutcome.FAILED,
            TunnelStatus(ConnectionPhase.CONNECTED, "work", null),
            failedSwitch = { id -> asked += id; failure },
        )
        val seen = mutableListOf<SwitchFailure>()
        val listener = CoroutineScope(Dispatchers.Unconfined).launch {
            ConnectionRegistry.switchFailures.collect { seen += it }
        }
        try {
            builtService().onStartCommand(connectIntent("home"), 0, 8)
            awaitControl()
            assertEquals(listOf("home"), asked)
            assertEquals(listOf(failure), seen)
        } finally {
            listener.cancel()
        }
    }

    @Test
    fun `a restart refusal reaches the registry even though the start settled STARTED`() {
        val refusal = RestartRefusal("Alpha", "its DNS server name didn't resolve")
        val asked = mutableListOf<String>()
        installControl(
            StartOutcome.STARTED,
            TunnelStatus(ConnectionPhase.CONNECTED, "a", null),
            restartRefusal = { id -> asked += id; refusal },
        )
        val seen = mutableListOf<RestartRefusal>()
        val listener = CoroutineScope(Dispatchers.Unconfined).launch {
            ConnectionRegistry.restartRefusals.collect { seen += it }
        }
        try {
            builtService().onStartCommand(connectIntent("a"), 0, 9)
            awaitControl()
            assertEquals(listOf("a"), asked)
            assertEquals(listOf(refusal), seen)
        } finally {
            listener.cancel()
        }
    }

    @Test
    fun `a started or refused connect never reports a switch failure`() {
        val asked = mutableListOf<String>()
        val seen = mutableListOf<SwitchFailure>()
        val listener = CoroutineScope(Dispatchers.Unconfined).launch {
            ConnectionRegistry.switchFailures.collect { seen += it }
        }
        try {
            installControl(
                StartOutcome.STARTED,
                TunnelStatus(ConnectionPhase.CONNECTED, "home", null),
                failedSwitch = { id -> asked += id; SwitchFailure("Home", "Work", unresolved = false) },
            )
            builtService().onStartCommand(connectIntent("home"), 0, 1)
            installControl(
                StartOutcome.REFUSED,
                TunnelStatus(ConnectionPhase.CONNECTED, "work", null),
                failedSwitch = { id -> asked += id; SwitchFailure("Home", "Work", unresolved = false) },
            )
            builtService().onStartCommand(connectIntent("home"), 0, 2)
            awaitControl()
            assertTrue(asked.isEmpty())
            assertTrue(seen.isEmpty())
        } finally {
            listener.cancel()
        }
    }

    @Test
    fun `onRevoke deactivates DNS before teardown runs`() = runBlocking {
        val profile = secureProfile("a", "Alpha")
        val records = ActiveProfileStore(context)
        records.setActive(profile.id)
        records.setLastConnected(profile.id)
        val upstream = CloseCountingUpstream()
        val dns = DnsResolverController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            protect = { null },
            protectDatagram = { null },
            underlying = { UnderlyingRoute(listOf(InetAddress.getByName("127.0.0.1"))) },
            ok = OkHttpClient(),
            secureUpstream = { upstream },
        )
        val engine = engineOver(FakeBackend(), dns, profile)

        withProviderEngine(engine) {
            engine.start(profile) // what a live connect leaves behind: a committed, answering resolver
            val session = checkNotNull(dns.current) { "the connect committed no resolver" }
            assertFalse(session.answerJob.isCancelled)
            assertEquals(0, upstream.closes)

            val stopEntered = CountDownLatch(1)
            val releaseStop = CountDownLatch(1)
            installControl(StartOutcome.REFUSED, TunnelStatus.idle, stopOp = {
                stopEntered.countDown()
                releaseStop.await(5, TimeUnit.SECONDS)
            })
            val service = builtService()

            service.onRevoke()

            // Synchronous: the resolver is retired while the posted teardown is still parked in its stop, so
            // nothing answers from a VPN the platform has already revoked.
            assertNull(dns.current)
            assertTrue(session.answerJob.isCancelled)
            assertEquals(1, upstream.closes)

            // The teardown still runs, and still clears ACTIVE while LAST keeps its pointer.
            assertTrue("the teardown never reached the engine stop", stopEntered.await(5, TimeUnit.SECONDS))
            releaseStop.countDown()
            awaitControl()
            assertNull(records.activeId.first())
            assertEquals(profile.id, records.lastConnectedId.first())

            stopEngineBounded(engine)
        }
    }
}
