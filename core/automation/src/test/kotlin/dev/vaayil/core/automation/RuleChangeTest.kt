package dev.vaayil.core.automation

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.AutomationRulesStore
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.SsidDecision
import dev.vaayil.core.model.WifiSecurity
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowVpnService

/**
 * AUTO-2 (spec §4.6): a stored-rule edit acts on the current network only when the answer for
 * that network changes. The helpers compare the same decisions the watchers make — J3's policy
 * disconnect, then the phase — under the previous and the new rules, with one tunnel phase; an
 * edit that leaves the answer alone leaves the user's own Connect or Disconnect standing.
 */
class RuleChangeTest {
    private val guard = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"))

    @Test
    fun `turning on Disconnect on trusted networks on a trusted secured network disconnects`() =
        assertEquals(
            SsidDecision.DISCONNECT,
            wifiRuleChange(
                guard,
                guard.copy(disconnectOnTrusted = true),
                "Home",
                WifiSecurity.SECURED,
                ConnectionPhase.CONNECTED,
            ),
        )

    @Test
    fun `adding the current secured network to the list with the rule on disconnects`() =
        assertEquals(
            SsidDecision.DISCONNECT,
            wifiRuleChange(
                guard.copy(trustedSsids = emptySet(), disconnectOnTrusted = true),
                guard.copy(disconnectOnTrusted = true),
                "Home",
                WifiSecurity.SECURED,
                ConnectionPhase.CONNECTED,
            ),
        )

    @Test
    fun `removing the current network from the list while disconnected connects`() =
        assertEquals(
            SsidDecision.CONNECT,
            wifiRuleChange(
                guard,
                guard.copy(trustedSsids = emptySet()),
                "Home",
                WifiSecurity.SECURED,
                ConnectionPhase.DISCONNECTED,
            ),
        )

    @Test
    fun `turning on the mobile rule while on cellular and idle connects`() =
        assertEquals(
            SsidDecision.CONNECT,
            mobileRuleChange(
                AutoConnectRules(),
                AutoConnectRules(autoConnectOnMobileData = true),
                mobile = true,
                busy = false,
            ),
        )

    @Test
    fun `J3's policy disconnect stands while the registry still reads idle`() =
        assertEquals(
            SsidDecision.DISCONNECT,
            guard.copy(disconnectOnTrusted = true).wifiAnswer("Home", WifiSecurity.SECURED, ConnectionPhase.DISCONNECTED),
        )

    @Test
    fun `another network added gives nothing`() =
        assertEquals(
            SsidDecision.NONE,
            wifiRuleChange(
                guard,
                guard.copy(trustedSsids = setOf("Home", "Other")),
                "Home",
                WifiSecurity.SECURED,
                ConnectionPhase.CONNECTED,
            ),
        )

    @Test
    fun `toggling the mobile rule while on Wi-Fi gives nothing`() {
        val onWifi = AutoConnectRules(wifiGuardEnabled = true)
        val edited = onWifi.copy(autoConnectOnMobileData = true)
        assertEquals(
            SsidDecision.NONE,
            wifiRuleChange(onWifi, edited, "Cafe", WifiSecurity.SECURED, ConnectionPhase.DISCONNECTED),
        )
        assertEquals(SsidDecision.NONE, mobileRuleChange(onWifi, edited, mobile = false, busy = false))
    }

    @Test
    fun `Disconnect on trusted networks turned on while on an untrusted network gives nothing`() =
        assertEquals(
            SsidDecision.NONE,
            wifiRuleChange(
                guard,
                guard.copy(disconnectOnTrusted = true),
                "Cafe",
                WifiSecurity.SECURED,
                ConnectionPhase.CONNECTED,
            ),
        )

    @Test
    fun `a Wi-Fi edit while on cellular gives nothing`() =
        assertEquals(
            SsidDecision.NONE,
            wifiRuleChange(guard, guard.copy(trustedSsids = emptySet()), null, WifiSecurity.UNKNOWN, ConnectionPhase.DISCONNECTED),
        )

    @Test
    fun `an open or unknown network named on the list gives nothing`() {
        val onList = guard.copy(disconnectOnTrusted = true)
        assertEquals(
            SsidDecision.NONE,
            wifiRuleChange(
                guard.copy(trustedSsids = emptySet(), disconnectOnTrusted = true),
                onList,
                "Home",
                WifiSecurity.OPEN,
                ConnectionPhase.CONNECTED,
            ),
        )
        assertEquals(
            SsidDecision.NONE,
            wifiRuleChange(
                guard.copy(trustedSsids = emptySet(), disconnectOnTrusted = true),
                onList,
                "Home",
                WifiSecurity.UNKNOWN,
                ConnectionPhase.CONNECTED,
            ),
        )
    }

    @Test
    fun `a guard on or off transition gives nothing`() {
        val off = AutoConnectRules(trustedSsids = emptySet())
        val on = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = emptySet())
        assertEquals(
            SsidDecision.NONE,
            wifiRuleChange(off, on, "Cafe", WifiSecurity.SECURED, ConnectionPhase.DISCONNECTED),
        )
        assertEquals(
            SsidDecision.NONE,
            wifiRuleChange(on, off, "Cafe", WifiSecurity.SECURED, ConnectionPhase.DISCONNECTED),
        )
    }

    @Test
    fun `a hidden name gives nothing`() =
        assertEquals(
            SsidDecision.NONE,
            wifiRuleChange(
                guard.copy(trustedSsids = emptySet()),
                guard.copy(disconnectOnTrusted = true),
                null,
                WifiSecurity.UNKNOWN,
                ConnectionPhase.DISCONNECTED,
            ),
        )
}

/**
 * AUTO-2: one collection follows the stored rules (Y3's watcher switch included), and only two
 * successfully read rule sets are compared — the first read after the service starts and the
 * first good read after a failed one never act (R7). The command a changed answer gives runs
 * off the collector, so a disconnect waiting on a resume can never stop the collection.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AutoConnectServiceRuleChangeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var controller: ServiceController<AutoConnectService>
    private lateinit var starts: RecordingStarts

    /** The rules the watchers read ([AutoConnectService.readRules]); the test keeps it in step. */
    private var current = AutoConnectRules()

    /** The observed rules stream; emitted after create, so the first read's timing is the test's. */
    private var observed = MutableSharedFlow<AutoConnectRules>(replay = 1)

    @Before
    fun grantEverything() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        ShadowVpnService.setPrepareResult(null) // consent granted: a CONNECT may start the tunnel
        AutomationRulesStore(app) // open the DataStore in the app context first, as the service expects
        ConnectionRegistry.status.value = TunnelStatus.idle
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.destroy()
        ShadowVpnService.setPrepareResult(Intent())
        ConnectionRegistry.status.value = TunnelStatus.idle
        shadowOf(app).clearStartedServices()
    }

    /** The service, following [observed] as the stored rules and reading [current] on evaluation. */
    private fun created(): AutoConnectService {
        controller = Robolectric.buildService(AutoConnectService::class.java)
        starts = RecordingStarts(app)
        controller.get().apply {
            observeRules = { observed }
            readRules = { current }
            readTarget = { "p1" }
            tunnelCommands = BackgroundTunnelCommands(starts)
        }
        controller.create()
        idle()
        return controller.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The command counters at a point in the test; only new commands may be asserted on. */
    private class Commands(val connects: Int, val disconnects: Int)

    private fun recording() = Commands(starts.foregroundStarts, starts.disconnectIntents.size)

    private fun assertNoNewCommands(before: Commands) {
        assertEquals("connect starts", before.connects, starts.foregroundStarts)
        assertEquals("disconnect starts", before.disconnects, starts.disconnectIntents.size)
    }

    /** The disconnect suspends in its ACTIVE clear: settle it on the paused main looper, bounded. */
    private fun awaitDisconnects(expected: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (starts.disconnectIntents.size < expected && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(20)
        }
        idle()
        assertEquals("disconnect starts", expected, starts.disconnectIntents.size)
    }

    private fun awaitConnects(expected: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (starts.foregroundStarts < expected && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(20)
        }
        idle()
        assertEquals("connect starts", expected, starts.foregroundStarts)
    }

    @Test
    fun `the first rules read acts on nothing`() {
        val service = created() // the stream has not emitted yet
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        current = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"))
        runBlocking { service.evaluateWifi("Home", WifiSecurity.SECURED).join() } // trusted: NONE
        val before = recording()

        // The first read would disconnect if it were compared with a default: never compared.
        observed.tryEmit(current.copy(disconnectOnTrusted = true))
        idle()

        assertNoNewCommands(before)
    }

    @Test
    fun `the first rules read acts on nothing on cellular too`() {
        val rules = AutoConnectRules(autoConnectOnMobileData = false)
        val service = created() // the stream has not emitted yet
        current = rules
        // lastMobile survives watcher transitions, so a first read would reach a mobile comparison.
        runBlocking { service.evaluateMobile(true).join() } // the mobile rule is off: NONE
        val before = recording()

        // The first read turns the mobile rule on: a comparison against defaults would connect.
        observed.tryEmit(rules.copy(autoConnectOnMobileData = true))
        idle()

        assertNoNewCommands(before)
    }

    @Test
    fun `an edit that leaves the answer alone acts on nothing`() {
        val rules = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"))
        val service = created()
        observed.tryEmit(rules)
        idle()
        current = rules
        runBlocking { service.evaluateWifi("Cafe", WifiSecurity.SECURED).join() } // untrusted, idle: CONNECT
        val before = recording()

        observed.tryEmit(rules) // a re-emission of equal rules
        idle()
        observed.tryEmit(rules.copy(connectOnBoot = true))
        idle()
        observed.tryEmit(rules.copy(externalControlEnabled = true, externalControlToken = "token"))
        idle()

        assertNoNewCommands(before)
    }

    @Test
    fun `a failed read then a good read acts on nothing`() {
        val good = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"))
        val actionable = good.copy(disconnectOnTrusted = true)
        var subscriptions = 0
        controller = Robolectric.buildService(AutoConnectService::class.java)
        starts = RecordingStarts(app)
        controller.get().apply {
            observeRules = {
                flow {
                    subscriptions++
                    emit(if (subscriptions == 1) good else actionable)
                    if (subscriptions == 1) throw IOException("unreadable")
                }
            }
            readRules = { good }
            readTarget = { "p1" }
            tunnelCommands = BackgroundTunnelCommands(starts)
        }
        controller.create()
        idle()

        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        runBlocking { controller.get().evaluateWifi("Home", WifiSecurity.SECURED).join() } // trusted: NONE
        val before = recording()

        // The failure turns the watcher off and forgets the previous rules; 1 s later the good read
        // arrives, and it is not compared (it would disconnect against the first read).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(2, subscriptions)
        assertNoNewCommands(before)
    }

    @Test
    fun `a failed read then a good read acts on nothing on cellular too`() {
        val good = AutoConnectRules(autoConnectOnMobileData = false)
        val actionable = good.copy(autoConnectOnMobileData = true)
        var subscriptions = 0
        controller = Robolectric.buildService(AutoConnectService::class.java)
        starts = RecordingStarts(app)
        controller.get().apply {
            observeRules = {
                flow {
                    subscriptions++
                    emit(if (subscriptions == 1) good else actionable)
                    if (subscriptions == 1) throw IOException("unreadable")
                }
            }
            readRules = { good }
            readTarget = { "p1" }
            tunnelCommands = BackgroundTunnelCommands(starts)
        }
        controller.create()
        idle()
        runBlocking { controller.get().evaluateMobile(true).join() } // the mobile rule is off: NONE
        val before = recording()

        // The failure forgets the previous rules; 1 s later the good read arrives with the rule on,
        // and is not compared (it would connect against the first read).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(2, subscriptions)
        assertNoNewCommands(before)
    }

    @Test
    fun `an edit that changes the answer disconnects`() {
        val rules = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"))
        val service = created()
        observed.tryEmit(rules)
        idle()
        current = rules
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        runBlocking { service.evaluateWifi("Home", WifiSecurity.SECURED).join() } // trusted: NONE
        val before = recording()

        observed.tryEmit(rules.copy(disconnectOnTrusted = true))
        awaitDisconnects(before.disconnects + 1)

        assertEquals(
            listOf(VpnTunnelService.ACTION_DISCONNECT),
            starts.disconnectIntents.map { it.action },
        )
        assertEquals("a plain (not foreground) start", 1, starts.plainStarts)
        assertEquals("no connect", before.connects, starts.foregroundStarts)
    }

    @Test
    fun `an edit that turns the mobile rule on while on cellular connects`() {
        val rules = AutoConnectRules(wifiGuardEnabled = true, autoConnectOnMobileData = false)
        val service = created()
        observed.tryEmit(rules)
        idle()
        current = rules
        runBlocking { service.evaluateMobile(true).join() } // the rule is off: NONE
        val before = recording()

        observed.tryEmit(rules.copy(autoConnectOnMobileData = true))
        awaitConnects(before.connects + 1)

        val started = shadowOf(app).nextStartedService
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `adding the current secured network to the list with the rule on disconnects`() {
        val rules = AutoConnectRules(
            wifiGuardEnabled = true,
            trustedSsids = emptySet(),
            disconnectOnTrusted = true,
        )
        val service = created()
        observed.tryEmit(rules)
        idle()
        current = rules
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        runBlocking { service.evaluateWifi("Home", WifiSecurity.SECURED).join() } // untrusted: NONE
        val before = recording()

        observed.tryEmit(rules.copy(trustedSsids = setOf("Home")))
        awaitDisconnects(before.disconnects + 1)

        assertEquals(
            listOf(VpnTunnelService.ACTION_DISCONNECT),
            starts.disconnectIntents.map { it.action },
        )
        assertEquals("no connect", before.connects, starts.foregroundStarts)
    }

    @Test
    fun `removing the current network from the list while disconnected connects`() {
        val rules = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"))
        val service = created()
        observed.tryEmit(rules)
        idle()
        current = rules
        ConnectionRegistry.status.value = TunnelStatus.idle
        runBlocking { service.evaluateWifi("Home", WifiSecurity.SECURED).join() } // trusted: NONE
        val before = recording()

        observed.tryEmit(rules.copy(trustedSsids = emptySet()))
        awaitConnects(before.connects + 1)

        val started = shadowOf(app).nextStartedService
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
        assertTrue(
            ShadowLog.getLogsForTag("BackgroundTunnelCommands").any {
                it.msg == "connect sent (Wi-Fi rule, KEEP_ANY)"
            },
        )
    }

    @Test
    fun `a manual Disconnect on an untrusted network stands when another network is added`() {
        val rules = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"))
        val service = created()
        observed.tryEmit(rules)
        idle()
        current = rules
        runBlocking { service.evaluateWifi("Cafe", WifiSecurity.SECURED).join() } // untrusted, idle: CONNECT
        ConnectionRegistry.status.value = TunnelStatus.idle // the user's manual Disconnect
        val before = recording()

        observed.tryEmit(rules.copy(trustedSsids = setOf("Home", "Other")))
        idle()

        assertNoNewCommands(before)
    }

    @Test
    fun `a hand-connected tunnel on a trusted network stands when an unrelated network is added`() {
        val rules = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"))
        val service = created()
        observed.tryEmit(rules)
        idle()
        current = rules
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        runBlocking { service.evaluateWifi("Home", WifiSecurity.SECURED).join() } // trusted: NONE
        val before = recording()

        observed.tryEmit(rules.copy(trustedSsids = setOf("Home", "Other")))
        idle()

        assertNoNewCommands(before)
    }

    @Test
    fun `a Wi-Fi edit while on cellular stands after a manual Disconnect`() {
        val rules = AutoConnectRules(wifiGuardEnabled = true, autoConnectOnMobileData = false)
        val service = created()
        observed.tryEmit(rules)
        idle()
        current = rules
        // The Wi-Fi watcher reports the loss of Wi-Fi, then the mobile watcher reports cellular.
        runBlocking { service.evaluateWifi(null, WifiSecurity.UNKNOWN).join() }
        runBlocking { service.evaluateMobile(true).join() }
        ConnectionRegistry.status.value = TunnelStatus.idle // the user's manual Disconnect
        val before = recording()

        observed.tryEmit(rules.copy(trustedSsids = setOf("Home")))
        idle()

        assertNoNewCommands(before)
    }

    @Test
    fun `no report yet acts on nothing`() {
        val rules = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"))
        created()
        observed.tryEmit(rules)
        idle()
        current = rules
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        val before = recording()

        observed.tryEmit(rules.copy(disconnectOnTrusted = true))
        idle()

        assertNoNewCommands(before)
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(starts.disconnectIntents.isEmpty())
    }
}
