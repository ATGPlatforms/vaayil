package dev.vaayil.core.automation

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.WifiSecurity
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.isLive
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.StartOutcome
import dev.vaayil.core.tunnel.wg.TunnelNotifications
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowVpnService

/**
 * B1: the background senders (the Wi-Fi/mobile guard and connect-on-boot) may only start
 * VpnTunnelService with VPN consent; without it they post the consent notification in the
 * tunnel's own slot (42), and a DISCONNECT only ever starts for a live phase. Robolectric's
 * ShadowVpnService reports "needs consent" by default, so consent is granted per test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class BackgroundTunnelCommandsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var controller: ServiceController<AutoConnectService>

    private val nm: ShadowNotificationManager
        get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Before
    fun grantPostNotifications() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.destroy()
        ConnectionRegistry.status.value = TunnelStatus.idle
    }

    /** Service fixture: location grants on, watchers registered, and the rules/target seams injected. */
    private fun guardedService(
        rules: AutoConnectRules = AutoConnectRules(wifiGuardEnabled = true, autoConnectOnMobileData = true),
        backgroundLocation: Boolean = true,
    ): AutoConnectService {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        if (backgroundLocation) shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        controller = Robolectric.buildService(AutoConnectService::class.java).create()
        return controller.get().apply {
            readRules = { rules }
            readTarget = { "p1" }
        }
    }

    private fun nextStart(): Intent? = shadowOf(app).nextStartedService

    /**
     * Settles a service-scope evaluation whose helper suspends in its ACTIVE clear (J3): the
     * resumption lands on Robolectric's paused main looper, which a blocking join would never
     * drain. Drains the looper on a bounded deadline until [condition] holds — the helper's
     * ACTIVE clear, whose remaining work (the live check and the start) follows inline in the
     * same resumption — then requires it.
     */
    private suspend fun awaitEvaluationSettled(condition: suspend () -> Boolean, description: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(description, condition())
    }

    // ----------------------------------------------------- J3 crash-resume fixtures

    /** Counts the tunnel starts by method, so the caller's foreground choice is observable. */
    private class RecordingStarts(context: Context) : ContextWrapper(context) {
        val disconnectIntents = mutableListOf<Intent>()
        var foregroundStarts = 0
        var plainStarts = 0

        override fun startForegroundService(service: Intent): ComponentName? {
            if (service.action == VpnTunnelService.ACTION_DISCONNECT) disconnectIntents += service
            foregroundStarts++
            return super.startForegroundService(service)
        }

        override fun startService(service: Intent): ComponentName? {
            if (service.action == VpnTunnelService.ACTION_DISCONNECT) disconnectIntents += service
            plainStarts++
            return super.startService(service)
        }
    }

    /**
     * This class's own extra cleanup, passed to the shared [restoreResumeFixture]: destroy the
     * guarded service this test built (the @After tears down only the last one) and drain the
     * parked create-time refresh, so nothing a test built outlives it.
     */
    private fun destroyGuardedServiceAndIdle() {
        if (::controller.isInitialized) controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a refused connect posts the consent notification in the tunnel slot`() {
        val launcher = ComponentName(app, "dev.vaayil.test.Launcher")
        shadowOf(app.packageManager).addActivityIfNotPresent(launcher)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            launcher, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        ShadowVpnService.setPrepareResult(Intent()) // missing consent

        BackgroundTunnelCommands(app).connect("p1")

        assertNull(nextStart())
        assertEquals(42, TunnelNotifications.NOTIFICATION_ID)
        val posted = nm.getNotification(TunnelNotifications.NOTIFICATION_ID)
        assertNotNull("no consent notification in slot 42", posted)
        assertEquals("tunnel", posted.channelId)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            app.getSystemService(NotificationManager::class.java).getNotificationChannel("tunnel").importance,
        )
        assertEquals("Vaayil couldn't connect", posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(
            "VPN permission is off. Open Vaayil and connect once to allow it.",
            posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertTrue("setOnlyAlertOnce", posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue("setAutoCancel", posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(0, posted.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(shadowOf(posted.contentIntent).isActivity)
        assertEquals(launcher, shadowOf(posted.contentIntent).savedIntent.component)
    }

    @Test
    fun `repeated refusals reuse the one tunnel slot`() {
        ShadowVpnService.setPrepareResult(Intent()) // missing consent
        val commands = BackgroundTunnelCommands(app)
        commands.connect("p1")
        commands.connect("p1")
        commands.connect("p2")
        assertEquals(1, nm.size())
    }

    @Test
    fun `a refused connect without the notification grant starts nothing and never throws`() {
        ShadowVpnService.setPrepareResult(Intent()) // missing consent
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BackgroundTunnelCommands(app).connect("p1")
        assertNull(nextStart())
        assertNull(nm.getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `a refused connect without a launcher still posts and never throws`() {
        ShadowVpnService.setPrepareResult(Intent()) // missing consent
        BackgroundTunnelCommands(app).connect("p1")
        val posted = nm.getNotification(TunnelNotifications.NOTIFICATION_ID)
        assertNotNull(posted)
        assertNull(posted.contentIntent) // the library has no launcher: the tap-through is simply absent
    }

    @Test
    fun `a consented connect starts the tunnel for the resolved target`() {
        ShadowVpnService.setPrepareResult(null)
        BackgroundTunnelCommands(app).connect("p1")
        val started = nextStart()
        assertEquals(VpnTunnelService::class.java.name, started?.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `a helper disconnect starts only for live phases and ignores consent`() {
        ShadowVpnService.setPrepareResult(Intent()) // missing consent: a live disconnect must not care
        val commands = BackgroundTunnelCommands(app)
        for (phase in ConnectionPhase.entries) {
            ConnectionRegistry.status.value = TunnelStatus(phase, "p1", null)
            for (foreground in listOf(false, true)) {
                shadowOf(app).clearStartedServices()
                runBlocking { commands.disconnect(foreground) }
                val started = nextStart()
                if (phase.isLive) {
                    assertEquals("phase $phase", VpnTunnelService.ACTION_DISCONNECT, started?.action)
                } else {
                    assertNull("phase $phase started a tunnel command", started)
                }
            }
        }
    }

    @Test
    fun `an idle disconnect posts no consent notification and starts nothing`() {
        ShadowVpnService.setPrepareResult(Intent())
        runBlocking { BackgroundTunnelCommands(app).disconnect(foreground = true) }
        assertNull(nextStart())
        assertNull(nm.getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `a guard wifi connect without consent posts the notification and starts nothing`() {
        val service = guardedService()
        ShadowVpnService.setPrepareResult(Intent())
        shadowOf(app).clearStartedServices()

        runBlocking { service.evaluateWifi("Home", WifiSecurity.SECURED).join() }

        assertNull(nextStart())
        assertNotNull(nm.getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `a guard wifi connect with consent starts the connect intent`() {
        val service = guardedService()
        ShadowVpnService.setPrepareResult(null)
        shadowOf(app).clearStartedServices()

        runBlocking { service.evaluateWifi("Home", WifiSecurity.SECURED).join() }

        val started = nextStart()
        assertEquals(VpnTunnelService::class.java.name, started?.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `a guard mobile connect without consent posts the notification and starts nothing`() {
        val service = guardedService()
        ShadowVpnService.setPrepareResult(Intent())
        shadowOf(app).clearStartedServices()

        runBlocking { service.evaluateMobile(true).join() }

        assertNull(nextStart())
        assertNotNull(nm.getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `a guard mobile connect with consent starts the connect intent`() {
        val service = guardedService()
        ShadowVpnService.setPrepareResult(null)
        shadowOf(app).clearStartedServices()

        runBlocking { service.evaluateMobile(true).join() }

        val started = nextStart()
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `a guard disconnect starts only for live phases and ignores consent`() = runBlocking {
        val service = guardedService(
            AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"), disconnectOnTrusted = true),
        )
        ShadowVpnService.setPrepareResult(Intent()) // missing consent: a live disconnect must not care
        for (phase in ConnectionPhase.entries) {
            ConnectionRegistry.status.value = TunnelStatus(phase, "p1", null)
            shadowOf(app).clearStartedServices()
            // The trusted-network disconnect runs whatever the phase (J3): its helper settles
            // when it clears ACTIVE, seeded fresh per phase so the marker is observable.
            ActiveProfileStore(app).apply { setActive("p1"); setLastConnected("p1") }
            service.wifiGate.forget() // the same network must be evaluated once per phase

            service.evaluateWifi("Home", WifiSecurity.SECURED)
            awaitEvaluationSettled(
                { ActiveProfileStore(app).activeId.first() == null },
                "phase $phase: the evaluation never settled",
            )

            val started = nextStart()
            if (phase.isLive) {
                assertEquals("phase $phase", VpnTunnelService.ACTION_DISCONNECT, started?.action)
            } else {
                assertNull("phase $phase started a tunnel command", started)
            }
            assertNull("phase $phase posted the consent notification", nm.getNotification(TunnelNotifications.NOTIFICATION_ID))
        }
    }

    @Test
    fun `idle disconnect clears ACTIVE and bars a late resume without a service start`() = runBlocking {
        val fixture = installResumeFixture()
        var primary: Throwable? = null
        try {
            ActiveProfileStore(app).apply { setActive("p1"); setLastConnected("p1") }
            val commands = BackgroundTunnelCommands(app)

            commands.disconnect(foreground = true)
            commands.disconnect(foreground = false)

            assertNull("the idle disconnect left ACTIVE in place", ActiveProfileStore(app).activeId.first())
            assertEquals("p1", ActiveProfileStore(app).lastConnectedId.first())
            assertNull("an idle disconnect started a tunnel command", nextStart())
            assertNull("an idle disconnect posted the consent notification", nm.getNotification(TunnelNotifications.NOTIFICATION_ID))

            deliverLateResume(fixture, app, "p1")

            assertTrue("the barred resume reached the connect", fixture.connectCalls.isEmpty())
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            restoreResumeFixture(fixture, null, primary) { destroyGuardedServiceAndIdle() }
        }
    }

    @Test
    @Config(sdk = [34])
    fun `an idle disconnect without consent on API 34 starts nothing and never crashes`() = runBlocking {
        val fixture = installResumeFixture()
        var primary: Throwable? = null
        try {
            ShadowVpnService.setPrepareResult(Intent()) // missing consent: a disconnect must not care
            ActiveProfileStore(app).apply { setActive("p1"); setLastConnected("p1") }

            BackgroundTunnelCommands(app).disconnect(foreground = true)

            assertNull("the idle disconnect left ACTIVE in place", ActiveProfileStore(app).activeId.first())
            assertNull("an idle disconnect started a tunnel command", nextStart())
            assertNull("an idle disconnect posted the consent notification", nm.getNotification(TunnelNotifications.NOTIFICATION_ID))
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            restoreResumeFixture(fixture, null, primary) { destroyGuardedServiceAndIdle() }
        }
    }

    @Test
    fun `idle disconnect during an accepted resume's lookup refuses it`() = runBlocking {
        val fixture = installResumeFixture()
        var primary: Throwable? = null
        var helper: Job? = null
        // R9: the helper runs in its own scope — never a child of this runBlocking — so a
        // helper stuck in its production NonCancellable section cannot hold the test open
        // past the bounded join; the scope is cancelled in the finally.
        val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            ActiveProfileStore(app).apply { setActive("p1"); setLastConnected("p1") }
            deliverLateResume(fixture, app, "p1", joinSettled = false) // parked in its profile lookup
            shadowOf(app).clearStartedServices()

            helper = helperScope.launch { BackgroundTunnelCommands(app).disconnect(foreground = true) }
            // Ordered production step: the helper bars the resume and clears ACTIVE before it
            // joins the accepted resume's Job; only then may the lookup end.
            val deadline = System.currentTimeMillis() + 5_000
            while (ActiveProfileStore(app).activeId.first() != null && System.currentTimeMillis() < deadline) delay(20)
            assertNull("the helper never cleared ACTIVE before joining the resume", ActiveProfileStore(app).activeId.first())
            fixture.lookup.complete(Unit)
            withTimeout(5_000) { helper.join() }

            assertEquals("the resume never reached its lookup", listOf("p1"), fixture.connectCalls)
            assertEquals("the resume was not refused by the admit check", listOf(StartOutcome.REFUSED), fixture.outcomes)
            assertEquals("a refused resume still published an UP", ConnectionPhase.DISCONNECTED, ConnectionRegistry.status.value.phase)
            assertNull("a live-less disconnect started a tunnel command", nextStart())
            assertEquals("p1", ActiveProfileStore(app).lastConnectedId.first())
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            try {
                restoreResumeFixture(fixture, helper, primary) { destroyGuardedServiceAndIdle() }
            } finally {
                helperScope.cancel()
            }
        }
    }

    @Test
    fun `idle disconnect past the admit check brings the resume down`() = runBlocking {
        for (foreground in listOf(true, false)) {
            val fixture = installResumeFixture()
            var primary: Throwable? = null
            var helper: Job? = null
            // R9: own scope, so a helper parked in its NonCancellable section can never hold
            // this runBlocking open past the bounded join.
            val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                ActiveProfileStore(app).apply { setActive("p1"); setLastConnected("p1") }
                fixture.lookup.complete(Unit) // the resume sails through its lookup
                deliverLateResume(fixture, app, "p1", joinSettled = false)
                assertEquals(
                    "foreground=$foreground: the resume never passed the admit check",
                    ConnectionPhase.CONNECTED,
                    ConnectionRegistry.status.value.phase,
                )
                shadowOf(app).clearStartedServices()

                val recording = RecordingStarts(app)
                helper = helperScope.launch { BackgroundTunnelCommands(recording).disconnect(foreground) }
                // The helper cleared ACTIVE and is parked joining the accepted resume's start.
                val deadline = System.currentTimeMillis() + 5_000
                while (ActiveProfileStore(app).activeId.first() != null && System.currentTimeMillis() < deadline) delay(20)
                assertNull("foreground=$foreground: the helper never cleared ACTIVE", ActiveProfileStore(app).activeId.first())
                fixture.blocked.complete(Unit)
                withTimeout(5_000) { helper.join() }

                assertEquals("foreground=$foreground: the helper did not bring the resume down",
                    listOf(VpnTunnelService.ACTION_DISCONNECT),
                    recording.disconnectIntents.map { it.action },
                )
                assertEquals("foreground=$foreground", if (foreground) 1 else 0, recording.foregroundStarts)
                assertEquals("foreground=$foreground", if (foreground) 0 else 1, recording.plainStarts)
                assertNull(ActiveProfileStore(app).activeId.first())
                assertEquals("p1", ActiveProfileStore(app).lastConnectedId.first())
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                try {
                    restoreResumeFixture(fixture, helper, primary) { destroyGuardedServiceAndIdle() }
                } finally {
                    helperScope.cancel()
                }
            }
        }
    }

    @Test
    fun `idle disconnect past the admit check joins the resume before it disconnects`() = runBlocking {
        for (foreground in listOf(true, false)) {
            // R10/R11: the resume is a LAZY probe registered on the fresh gate. join() starts a
            // Job still in the New state, so the probe's body — completing `joined` as its first
            // act — can only ever run from the helper's resume?.join(): the bounded
            // joined.await() below fails on every schedule when the join is missing, whatever
            // the descheduling.
            val fixture = installResumeFixture()
            var primary: Throwable? = null
            var helper: Job? = null
            var helperScope: CoroutineScope? = null
            val joined = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val probe = probeScope.launch(start = CoroutineStart.LAZY) {
                joined.complete(Unit)
                release.await()
                ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
            }
            try {
                val gate = crashResumeGateField().get(null)
                gate.javaClass.getDeclaredMethod("register", Job::class.java)
                    .apply { isAccessible = true }
                    .invoke(gate, probe)
                ActiveProfileStore(app).apply { setActive("p1"); setLastConnected("p1") }
                assertEquals(
                    "foreground=$foreground: the gate starts with a live registry",
                    ConnectionPhase.DISCONNECTED,
                    ConnectionRegistry.status.value.phase,
                )

                val recording = RecordingStarts(app)
                helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                helper = helperScope.launch { BackgroundTunnelCommands(recording).disconnect(foreground) }

                // R9: own scope, so a helper parked in its NonCancellable section can never hold
                // this runBlocking open past the bounded join.
                withTimeout(5_000) { joined.await() } // only the helper's join can start the New probe
                assertNull(
                    "foreground=$foreground: the probe ran before the helper cleared ACTIVE",
                    ActiveProfileStore(app).activeId.first(),
                )
                assertTrue(
                    "foreground=$foreground: a DISCONNECT started before the resume was up",
                    recording.disconnectIntents.isEmpty(),
                )

                release.complete(Unit) // the UP
                withTimeout(5_000) { helper.join() }

                assertEquals(
                    "foreground=$foreground: the helper did not bring the resume down once up",
                    listOf(VpnTunnelService.ACTION_DISCONNECT),
                    recording.disconnectIntents.map { it.action },
                )
                assertEquals("foreground=$foreground", if (foreground) 1 else 0, recording.foregroundStarts)
                assertEquals("foreground=$foreground", if (foreground) 0 else 1, recording.plainStarts)
                assertNull(ActiveProfileStore(app).activeId.first())
                assertEquals("p1", ActiveProfileStore(app).lastConnectedId.first())
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                var probeFailure: Throwable? = null
                runCatching {
                    release.complete(Unit)
                    withTimeout(5_000) { probe.join() } // bounded even when the probe is still New
                }.onFailure { probeFailure = it }
                try {
                    restoreResumeFixture(fixture, helper, primary) { destroyGuardedServiceAndIdle() }
                } finally {
                    probeScope.cancel()
                    helperScope?.cancel()
                }
                probeFailure?.let { e ->
                    val body = primary
                    if (body != null) body.addSuppressed(e) else throw e
                }
            }
        }
    }

    @Test
    fun `trusted wifi bars a pending resume while idle`() = runBlocking {
        val fixture = installResumeFixture()
        var primary: Throwable? = null
        try {
            val service = guardedService(
                AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"), disconnectOnTrusted = true),
            )
            ActiveProfileStore(app).apply { setActive("p1"); setLastConnected("p1") }
            shadowOf(app).clearStartedServices()

            service.evaluateWifi("Home", WifiSecurity.SECURED)
            awaitEvaluationSettled(
                { ActiveProfileStore(app).activeId.first() == null },
                "the guard's evaluation never settled",
            )

            assertNull("the guard started a tunnel command while idle", nextStart())
            assertNull("the trusted network did not clear ACTIVE", ActiveProfileStore(app).activeId.first())
            assertEquals("p1", ActiveProfileStore(app).lastConnectedId.first())

            deliverLateResume(fixture, app, "p1")

            assertTrue("the barred resume reached the connect", fixture.connectCalls.isEmpty())
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            restoreResumeFixture(fixture, null, primary) { destroyGuardedServiceAndIdle() }
        }
    }

    private data class Variant(
        val rules: AutoConnectRules,
        val backgroundLocation: Boolean,
        val ssid: String?,
        val security: WifiSecurity,
    )

    @Test
    fun `hidden or untrusted wifi never bars a pending resume`() = runBlocking {
        val trusted = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"), disconnectOnTrusted = true)
        val variants = listOf(
            "a hidden ssid" to Variant(trusted, true, null, WifiSecurity.SECURED),
            "missing location access" to Variant(trusted, backgroundLocation = false, "Home", WifiSecurity.SECURED),
            "an open network" to Variant(trusted, true, "Home", WifiSecurity.OPEN),
            "an unknown-security network" to Variant(trusted, true, "Home", WifiSecurity.UNKNOWN),
            "the guard off" to Variant(
                AutoConnectRules(wifiGuardEnabled = false, trustedSsids = setOf("Home"), disconnectOnTrusted = true),
                true, "Home", WifiSecurity.SECURED,
            ),
            "disconnect-on-trusted off" to Variant(
                AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"), disconnectOnTrusted = false),
                true, "Home", WifiSecurity.SECURED,
            ),
        )
        for ((name, variant) in variants) {
            val fixture = installResumeFixture()
            var primary: Throwable? = null
            try {
                ConnectionRegistry.status.value = TunnelStatus.idle
                ShadowVpnService.setPrepareResult(null)
                // Robolectric grants persist within a test method: a previous variant's location
                // grants must not leak into a variant that intends them missing (the hidden-ssid
                // variant grants background location; without the revocation the no-location
                // variant sees the SSID and runs the trusted-network disconnect).
                shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
                shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                ActiveProfileStore(app).apply { setActive("p1"); setLastConnected("p1") }
                val service = guardedService(variant.rules, variant.backgroundLocation)
                shadowOf(app).clearStartedServices()

                val job = service.evaluateWifi(variant.ssid, variant.security)
                awaitEvaluationSettled({ job.isCompleted }, "$name: the evaluation never settled")

                assertEquals("$name: the guard barred the resume", "p1", ActiveProfileStore(app).activeId.first())
                // The admitted resume must settle on its own: no start stays in flight.
                fixture.lookup.complete(Unit)
                fixture.blocked.complete(Unit)
                deliverLateResume(fixture, app, "p1")
                // An untrusted network may queue the guard's own CONNECT; the resume still connects.
                assertEquals("$name: the resume did not connect exactly once", listOf("p1"), fixture.connectCalls)
                assertEquals("$name: the resume was not admitted", listOf(StartOutcome.STARTED), fixture.outcomes)
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                restoreResumeFixture(fixture, null, primary) { destroyGuardedServiceAndIdle() }
            }
        }
    }
}
