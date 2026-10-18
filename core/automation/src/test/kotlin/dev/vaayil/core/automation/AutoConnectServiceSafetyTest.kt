package dev.vaayil.core.automation

import android.Manifest
import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.AutomationRulesStore
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.WifiSecurity
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * B1 (AUTO-8): nothing an evaluation does may kill the sticky service. A store or target read
 * that throws is logged and skips that one evaluation; a refused tunnel start is logged and the
 * next evaluation still runs; anything the ordinary catch cannot hold reaches the scope's
 * handler, which logs it while the remaining evaluations keep working.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AutoConnectServiceSafetyTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var controller: ServiceController<AutoConnectService>

    /**
     * The automation rules live in a process-wide DataStore; pin them so the create-time copy
     * refresh (which still reads the store before the seams are injected) is deterministic.
     */
    @Before
    fun seedRules() = runBlocking {
        AutomationRulesStore(app).mutate { AutoConnectRules(wifiGuardEnabled = true) }
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.destroy()
        ConnectionRegistry.status.value = TunnelStatus.idle
    }

    /** Service fixture: location grants on, consent granted, and the rules/target seams injected. */
    private fun createdService(
        rules: AutoConnectRules = AutoConnectRules(wifiGuardEnabled = true, autoConnectOnMobileData = true),
        backgroundLocation: Boolean = true,
    ): AutoConnectService {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        if (backgroundLocation) shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        ShadowVpnService.setPrepareResult(null)
        controller = Robolectric.buildService(AutoConnectService::class.java).create()
        return controller.get().apply {
            readRules = { rules }
            readTarget = { "p1" }
        }
    }

    private fun throwingCommands(): BackgroundTunnelCommands = BackgroundTunnelCommands(
        object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName =
                throw IllegalStateException("scripted: foreground service start not allowed")

            override fun startService(service: Intent): ComponentName =
                throw IllegalStateException("scripted: service start not allowed")
        },
    )

    private fun warnings(tag: String = "AutoConnectService") =
        ShadowLog.getLogsForTag(tag).filter { it.type == Log.WARN }.map { it.msg }

    @Test
    fun `a rules read that throws skips the wifi evaluation and logs`() {
        val service = createdService()
        service.readRules = { throw IOException("scripted: rules store unreadable") }

        runBlocking { service.evaluateWifi("Home", WifiSecurity.SECURED).join() }

        assertNull(shadowOf(app).nextStartedService)
        assertTrue(warnings().any { it.contains("IOException") })
        shadowOf(Looper.getMainLooper()).idle() // whatever was queued must drain without an uncaught failure
    }

    @Test
    fun `a rules read that throws skips the mobile evaluation and logs`() {
        val service = createdService()
        service.readRules = { throw IOException("scripted: rules store unreadable") }

        runBlocking { service.evaluateMobile(true).join() }

        assertNull(shadowOf(app).nextStartedService)
        assertTrue(warnings().any { it.contains("IOException") })
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a target read that throws skips the wifi connect and logs`() {
        val service = createdService()
        service.readTarget = { throw IOException("scripted: active store unreadable") }

        runBlocking { service.evaluateWifi("Home", WifiSecurity.SECURED).join() }

        assertNull(shadowOf(app).nextStartedService)
        assertTrue(warnings().any { it.contains("IOException") })
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a target read that throws skips the mobile connect and logs`() {
        val service = createdService()
        service.readTarget = { throw IOException("scripted: active store unreadable") }

        runBlocking { service.evaluateMobile(true).join() }

        assertNull(shadowOf(app).nextStartedService)
        assertTrue(warnings().any { it.contains("IOException") })
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a failed refresh keeps the last computed notification copy`() {
        val service = createdService(backgroundLocation = false) // the pinned rules make this the paused copy
        awaitNotificationTitle("Vaayil auto-connect is paused")

        service.readRules = { throw IOException("scripted: rules store unreadable") }
        controller.startCommand(0, 2)

        assertEquals("Vaayil auto-connect is paused", lastForegroundTitle())
        assertTrue(warnings().any { it.contains("IOException") })
    }

    @Test
    fun `refused starts are logged without killing later evaluations`() = runBlocking {
        val service = createdService(
            AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"), disconnectOnTrusted = true, autoConnectOnMobileData = true),
        )
        service.tunnelCommands = throwingCommands()
        shadowOf(app).clearStartedServices()

        service.evaluateWifi("Hotel", WifiSecurity.SECURED).join() // untrusted → CONNECT
        service.evaluateMobile(true).join() // mobile data → CONNECT
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        // The trusted-network disconnect now suspends in the helper's ACTIVE clear (J3): settle it
        // on the paused main looper, bounded, before asserting.
        ActiveProfileStore(app).apply { setActive("p1"); setLastConnected("p1") }
        service.wifiGate.forget()
        service.evaluateWifi("Home", WifiSecurity.SECURED) // trusted → DISCONNECT
        val deadline = System.currentTimeMillis() + 5_000
        while (ActiveProfileStore(app).activeId.first() != null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(20)
        }
        assertNull("the trusted-network evaluation never settled", ActiveProfileStore(app).activeId.first())

        assertNull(shadowOf(app).nextStartedService)
        assertTrue(warnings("BackgroundTunnelCommands").any { it.contains("IllegalStateException") })

        // A working sender again: the next changed network must still reach it.
        service.tunnelCommands = BackgroundTunnelCommands(app)
        ConnectionRegistry.status.value = TunnelStatus.idle
        service.wifiGate.forget()
        service.evaluateWifi("Hotel", WifiSecurity.SECURED).join()

        val started = shadowOf(app).nextStartedService
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `a failure outside the ordinary catch is logged by the scope handler`() {
        val service = createdService()
        service.readRules = { throw AssertionError("scripted") }
        shadowOf(app).clearStartedServices()

        runBlocking { service.evaluateWifi("Home", WifiSecurity.SECURED).join() }

        assertTrue(warnings().any { it.contains("AssertionError") })
        shadowOf(Looper.getMainLooper()).idle()

        // The failed sibling did not take the scope down: the next evaluation still starts.
        service.readRules = { AutoConnectRules(wifiGuardEnabled = true, autoConnectOnMobileData = true) }
        runBlocking { service.evaluateMobile(true).join() }
        val started = shadowOf(app).nextStartedService
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    private fun lastForegroundTitle(): String? =
        shadowOf(controller.get()).lastForegroundNotification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    /** The copy is computed behind a store read on the service's scope: drain until it lands. */
    private fun awaitNotificationTitle(expected: String, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (lastForegroundTitle() == expected) return
            Thread.sleep(20)
        }
        fail("the notification copy '$expected' was never promoted")
    }
}
