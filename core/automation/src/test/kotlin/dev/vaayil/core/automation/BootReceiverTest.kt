package dev.vaayil.core.automation

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.AutomationRulesStore
import dev.vaayil.core.data.BootClaim
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.data.ProfileEntity
import dev.vaayil.core.tunnel.wg.TunnelNotifications
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowVpnService

/**
 * Spec §7 Phase 2: BOOT_COMPLETED honors connectOnBoot (fires VpnTunnelService
 * ACTION_CONNECT, only with an active profile) and re-arms the wifi guard
 * (starts AutoConnectService). Every tunnel decision is a VpnTunnelService intent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class BootReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Robolectric's ShadowVpnService reports "needs consent" by default (an Intent), so every test
    // that expects a CONNECT start grants it explicitly; the consent tests override this per test.
    @Before
    fun grantVpnConsent() = ShadowVpnService.setPrepareResult(null)

    /**
     * B3: the receiver resolves through DataGraph's process-wide repository and the pointers live
     * in a process-wide DataStore, so every test starts with a fresh graph, no rows and no
     * pointers. Robolectric hands each test method its own Application, so the cached binding and
     * database from the previous method are stale.
     */
    @Before
    fun resetStoredState() {
        runBlocking {
            DataGraph.reset()
            val repo = DataGraph.repository(context) // binds the graph before Room opens the file (B4)
            repo.storedIds().forEach { repo.delete(it) }
            ActiveProfileStore(context).apply { setActive(null); setLastConnected(null) }
        }
    }

    /**
     * B3: a pointer only survives while a stored row carries its id, so seeding one stores its
     * profile too — a test that wants a stale pointer sets the store directly.
     */
    private fun seed(
        connectOnBoot: Boolean,
        wifiGuard: Boolean,
        activeId: String?,
        lastConnectedId: String? = null,
        mobileData: Boolean = false,
    ) = runBlocking {
        val rules = AutomationRulesStore(context)
        rules.setConnectOnBoot(connectOnBoot)
        rules.setWifiGuard(wifiGuard)
        rules.setAutoConnectOnMobileData(mobileData)
        val store = ActiveProfileStore(context)
        store.setActive(activeId)
        store.setLastConnected(lastConnectedId)
        listOfNotNull(activeId, lastConnectedId).forEach { storeProfile(it) }
    }

    /** Writes one stored row through the DAO, with the DAO's own list order ([ProfileEntity.updatedAt]). */
    private fun storeProfile(id: String, updatedAt: Long = 1L) = runBlocking {
        DataGraph.database(context).profileDao().upsert(row(id, updatedAt))
    }

    private fun row(id: String, updatedAt: Long = 1L) = ProfileEntity(
        id = id, name = id, privateKey = "k",
        addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
        mtu = null, listenPort = null, peerPublicKey = "K", presharedKey = null,
        endpoint = "vpn.example.com:51820", allowedIps = listOf("0.0.0.0/0"), keepalive = 25,
        createdAt = 1L, updatedAt = updatedAt,
    )

    private fun receiveBoot() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    private fun receiveUpdate() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    // The receiver reads DataStore on Dispatchers.IO behind goAsync, so the shadow's
    // started-service queue is filled asynchronously: poll instead of asserting inline.
    private fun awaitNextService(timeoutMs: Long = 5_000): Intent? {
        val app = shadowOf(context as Application)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            app.nextStartedService?.let { return it }
            Thread.sleep(20)
        }
        return app.nextStartedService
    }

    @Test
    fun `connect-on-boot without VPN consent starts no tunnel and posts the consent notification`() = runBlocking {
        seed(connectOnBoot = true, wifiGuard = false, activeId = "p1")
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(Intent())
        BootReceiver().handle(context, boot = true) {}.join()
        assertNull(shadowOf(context as Application).nextStartedService)
        assertNotNull(notificationManager().getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `connect-on-boot with VPN consent preserves the connect intent`() = runBlocking {
        seed(connectOnBoot = true, wifiGuard = false, activeId = "p1")
        BootReceiver().handle(context, boot = true) {}.join()
        val started = shadowOf(context as Application).allStartedServices
            .first { it.component?.className == VpnTunnelService::class.java.name }
        assertEquals(VpnTunnelService.ACTION_CONNECT, started.action)
        assertEquals("p1", started.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `boot receiver starts tunnel when on-boot enabled and a profile is active`() {
        seed(connectOnBoot = true, wifiGuard = false, activeId = "p1")
        receiveBoot()
        val started = awaitNextService()
        assertEquals(VpnTunnelService::class.java.name, started?.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        // Guard disabled: no AutoConnectService re-arm behind the connect intent.
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `boot receiver no-ops when on-boot disabled`() {
        seed(connectOnBoot = false, wifiGuard = false, activeId = "p1")
        receiveBoot()
        assertNull(awaitNextService(timeoutMs = 1_000))
    }

    @Test
    fun `boot receiver re-arms wifi guard when enabled`() {
        seed(connectOnBoot = true, wifiGuard = true, activeId = "p1")
        receiveBoot()
        val connect = awaitNextService()
        assertEquals(VpnTunnelService.ACTION_CONNECT, connect?.action)
        val rearm = awaitNextService()
        assertEquals(AutoConnectService::class.java.name, rearm?.component?.className)
    }

    @Test
    fun `boot receiver with no active profile does not start the tunnel`() {
        seed(connectOnBoot = true, wifiGuard = false, activeId = null)
        receiveBoot()
        assertNull(awaitNextService(timeoutMs = 1_000))
    }

    @Test
    fun `boot receiver falls back to last connected profile when active is empty`() {
        seed(connectOnBoot = true, wifiGuard = false, activeId = null, lastConnectedId = "p9")
        receiveBoot()
        val started = awaitNextService()
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p9", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `boot receiver connects the newest readable profile when no pointer survives`() {
        seed(connectOnBoot = true, wifiGuard = false, activeId = null)
        storeProfile("old", updatedAt = 1)
        storeProfile("new", updatedAt = 2)
        receiveBoot()
        val started = awaitNextService()
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("new", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `boot receiver re-arms the automation service for mobile-data auto-connect alone`() {
        seed(connectOnBoot = false, wifiGuard = false, activeId = null, mobileData = true)
        receiveBoot()
        val rearm = awaitNextService()
        assertEquals(AutoConnectService::class.java.name, rearm?.component?.className)
    }

    @Test
    fun `an app update re-arms the guard service and never connects`() {
        seed(connectOnBoot = true, wifiGuard = true, activeId = "p1")
        receiveUpdate()
        val rearm = awaitNextService()
        assertEquals(AutoConnectService::class.java.name, rearm?.component?.className)
        // connect-on-boot is a boot rule: an update must not start the tunnel
        assertNull(awaitNextService(timeoutMs = 1_000))
    }

    @Test
    fun `an app update re-arms the service for mobile-data auto-connect alone`() {
        seed(connectOnBoot = false, wifiGuard = false, activeId = null, mobileData = true)
        receiveUpdate()
        assertEquals(AutoConnectService::class.java.name, awaitNextService()?.component?.className)
    }

    @Test
    fun `an app update with neither rule on starts nothing`() {
        seed(connectOnBoot = true, wifiGuard = false, activeId = "p1")
        receiveUpdate()
        assertNull(awaitNextService(timeoutMs = 1_000))
    }

    @Test
    fun `the manifest routes the update broadcast to the boot receiver`() {
        val hits = context.packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName), 0,
        )
        assertTrue(hits.any { it.activityInfo.name == BootReceiver::class.java.name })
    }

    @Test
    fun `an unrelated broadcast starts nothing`() {
        seed(connectOnBoot = true, wifiGuard = true, activeId = "p1")
        BootReceiver().onReceive(context, Intent(Intent.ACTION_PACKAGE_REPLACED)) // another app's update, not ours
        assertNull(awaitNextService(timeoutMs = 1_000))
    }

    @Test
    fun `a refused connect start still re-arms the guard`() {
        seed(connectOnBoot = true, wifiGuard = true, activeId = "p1")
        val refusing = object : android.content.ContextWrapper(context) {
            override fun startForegroundService(service: Intent): android.content.ComponentName? {
                if (service.component?.className == VpnTunnelService::class.java.name) {
                    throw IllegalStateException("scripted: foreground service start not allowed")
                }
                return super.startForegroundService(service)
            }
        }
        BootReceiver().onReceive(refusing, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(AutoConnectService::class.java.name, awaitNextService()?.component?.className)
        assertTrue(warnings("BackgroundTunnelCommands").any { it.contains("IllegalStateException") })
    }

    // J1: Android 15+ also delivers BOOT_COMPLETED when user action takes a force-stopped app out
    // of its stopped state, so a second boot broadcast in one boot must not reconnect. The durable
    // once-per-boot claim stamps the first broadcast whatever happens after it.
    private fun tunnelStarts() =
        shadowOf(context as Application).allStartedServices
            .filter { it.component?.className == VpnTunnelService::class.java.name }

    private fun guardStarts() =
        shadowOf(context as Application).allStartedServices
            .filter { it.component?.className == AutoConnectService::class.java.name }

    private fun infos(tag: String = "BootReceiver") =
        ShadowLog.getLogsForTag(tag).filter { it.type == Log.INFO }.map { it.msg }

    @Test
    fun `two boot broadcasts after a manual disconnect issue only one boot connect`() = runBlocking {
        seed(connectOnBoot = true, wifiGuard = false, activeId = "p1")
        // The existing tests never set BOOT_COUNT; this test runs a real, readable boot.
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 101)
        BootReceiver().handle(context, boot = true) {}.join()
        // The manual disconnect: ACTIVE cleared, LAST kept, the tunnel down.
        ActiveProfileStore(context).apply { setActive(null); setLastConnected("p1") }
        BootReceiver().handle(context, boot = true) {}.join() // a new receiver at the same boot
        assertEquals(1, tunnelStarts().size)
    }

    @Test
    fun `a boot handled with the rule off stays taken`() = runBlocking {
        seed(connectOnBoot = false, wifiGuard = false, activeId = "p1")
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 201)
        BootReceiver().handle(context, boot = true) {}.join() // stamped whatever the rules say
        assertEquals(0, tunnelStarts().size)
        AutomationRulesStore(context).setConnectOnBoot(true) // enabled later in the same boot
        BootReceiver().handle(context, boot = true) {}.join()
        assertEquals(0, tunnelStarts().size) // the boot is taken; enabling the rule does not re-arm it
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 202) // the next boot
        BootReceiver().handle(context, boot = true) {}.join()
        assertEquals(1, tunnelStarts().size)
    }

    @Test
    fun `an unknown boot or a failing claim connects as before`() = runBlocking {
        seed(connectOnBoot = true, wifiGuard = true, activeId = "p1")
        var finished = 0
        BootReceiver().handle(context, boot = true, claimBoot = { BootClaim.UNKNOWN }) { finished++ }.join()
        assertEquals(1, tunnelStarts().size)
        assertEquals(1, guardStarts().size)
        assertEquals(1, finished)
        assertEquals(listOf("boot: boot number unknown; connect-on-boot runs as before"), infos())

        BootReceiver().handle(context, boot = true, claimBoot = { throw IOException("scripted: claim unreadable") }) {
            finished++
        }.join()
        assertEquals(2, tunnelStarts().size)
        assertEquals(2, guardStarts().size)
        assertEquals(2, finished)
        // The WARN names only the exception class — never a stored value — and the claim
        // proceeds exactly as UNKNOWN, INFO line included.
        assertEquals(
            listOf(
                "boot: boot claim unreadable (IOException)",
                "boot: boot number unknown; connect-on-boot runs as before",
                "boot: boot number unknown; connect-on-boot runs as before",
            ),
            warnings() + infos(),
        )
    }

    @Test
    fun `a target or consent failure uses up the boot`() = runBlocking {
        seed(connectOnBoot = true, wifiGuard = true, activeId = "p1")
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // The claim admits, but the target is null: nothing connects and the boot is still taken.
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 301)
        BootReceiver().handle(context, boot = true, readTarget = { null }) {}.join()
        assertEquals(0, tunnelStarts().size)
        BootReceiver().handle(context, boot = true) {}.join() // a second broadcast at the same boot
        assertEquals(0, tunnelStarts().size)

        // The target read throws on a NEW_BOOT claim: the callback runs and warns once, nothing
        // connects, and the boot is still taken — the duplicate never retries the read.
        var targetReads = 0
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 302)
        BootReceiver().handle(context, boot = true, readTarget = {
            targetReads++
            throw IOException("scripted: target unreadable")
        }) {}.join()
        assertEquals(1, targetReads)
        assertEquals(0, tunnelStarts().size)
        assertEquals(listOf("boot: connect target unreadable (IOException); not connecting"), warnings())
        BootReceiver().handle(context, boot = true) {}.join() // a second broadcast at the same boot
        assertEquals(1, targetReads) // SAME_BOOT: the target read is never reached again
        assertEquals(0, tunnelStarts().size)

        // Missing VPN consent: the notice posts once, as today…
        ShadowVpnService.setPrepareResult(Intent())
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 303)
        BootReceiver().handle(context, boot = true) {}.join()
        assertEquals(0, tunnelStarts().size)
        assertNotNull(notificationManager().getNotification(TunnelNotifications.NOTIFICATION_ID))
        context.getSystemService(NotificationManager::class.java).cancel(TunnelNotifications.NOTIFICATION_ID)
        // …and the second broadcast at that boot neither starts nor posts again.
        BootReceiver().handle(context, boot = true) {}.join()
        assertEquals(0, tunnelStarts().size)
        assertNull(notificationManager().getNotification(TunnelNotifications.NOTIFICATION_ID))

        // Whatever admission decided, the guard re-arms: six broadcasts, six re-arms.
        assertEquals(6, guardStarts().size)
    }

    @Test
    fun `an update never claims`() = runBlocking {
        var claims = 0
        val counting: suspend () -> BootClaim = { claims++; BootClaim.NEW_BOOT }

        seed(connectOnBoot = false, wifiGuard = true, activeId = null)
        BootReceiver().handle(context, boot = false, claimBoot = counting) {}.join()
        assertEquals(0, tunnelStarts().size)
        assertEquals(1, guardStarts().size) // Wi-Fi-only re-arms once

        seed(connectOnBoot = false, wifiGuard = false, activeId = null, mobileData = true)
        BootReceiver().handle(context, boot = false, claimBoot = counting) {}.join()
        assertEquals(0, tunnelStarts().size)
        assertEquals(0, claims) // MY_PACKAGE_REPLACED is not a boot and never claims one
        assertEquals(2, guardStarts().size) // mobile-only re-arms once
    }

    private fun warnings(tag: String = "BootReceiver") =
        ShadowLog.getLogsForTag(tag).filter { it.type == Log.WARN }.map { it.msg }

    private fun notificationManager() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Test
    fun `rules that cannot be read at boot are logged and the broadcast still finishes`() {
        seed(connectOnBoot = true, wifiGuard = true, activeId = "p1")
        var finished = 0
        val job = BootReceiver().handle(
            context, boot = true,
            readRules = { throw IOException("scripted: rules store unreadable") },
        ) { finished++ }
        runBlocking { job.join() }
        assertFalse("an exception escaped the receiver's coroutine", job.isCancelled)
        assertEquals(1, finished)
        assertEquals(listOf("boot: automation rules unreadable (IOException); nothing started"), warnings())
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `rules that cannot be read after an update are logged and the broadcast still finishes`() {
        seed(connectOnBoot = false, wifiGuard = true, activeId = null)
        var finished = 0
        val job = BootReceiver().handle(
            context, boot = false,
            readRules = { throw IllegalStateException("scripted: rules store unreadable") },
        ) { finished++ }
        runBlocking { job.join() }
        assertFalse("an exception escaped the receiver's coroutine", job.isCancelled)
        assertEquals(1, finished)
        assertEquals(
            listOf("update: automation rules unreadable (IllegalStateException); nothing started"),
            warnings(),
        )
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `a connect target that cannot be read is logged and the guard is still re-armed`() {
        seed(connectOnBoot = true, wifiGuard = true, activeId = "p1")
        var finished = 0
        val job = BootReceiver().handle(
            context, boot = true,
            readTarget = { throw IOException("scripted: active store unreadable") },
        ) { finished++ }
        runBlocking { job.join() }
        assertFalse("an exception escaped the receiver's coroutine", job.isCancelled)
        assertEquals(1, finished)
        assertEquals(listOf("boot: connect target unreadable (IOException); not connecting"), warnings())
        val app = shadowOf(context as Application)
        assertEquals(AutoConnectService::class.java.name, app.nextStartedService?.component?.className)
        assertNull(app.nextStartedService) // the guard only: no connect intent
    }
}
