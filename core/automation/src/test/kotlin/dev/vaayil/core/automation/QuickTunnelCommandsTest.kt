package dev.vaayil.core.automation

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.isLive
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.TunnelNotifications
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowVpnService

/**
 * The quick-controls entry: one CONNECT admission over B1, a duplicate guard armed by each send,
 * and the entry's own DISCONNECT. Robolectric's [ShadowVpnService] reports "needs consent" by
 * default, so consent is granted per test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickTunnelCommandsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** The entry's clock: tests move it instead of sleeping. */
    private var now = 1_000L

    /** The tally of settled connects the entry reads: tests move it instead of running the engine. */
    private var settled = 0L

    private val nm: ShadowNotificationManager
        get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Before
    fun grantPostNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Robolectric can share the application between methods: no stale start may leak in.
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @After
    fun resetRegistry() {
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    private fun entry(context: Context): QuickTunnelCommands = QuickTunnelCommands(
        BackgroundTunnelCommands(context),
        ConnectionRegistry.status,
        { ConnectionRegistry.status.value.phase.isLive },
        { now },
        { settled },
    )

    private fun nextStart(): Intent? = shadowOf(app).nextStartedService

    @Test
    fun `without consent the tile's connect reports it and posts nothing`() {
        ShadowVpnService.setPrepareResult(Intent()) // missing consent

        val result = entry(app).connect("p1", noticeWithoutConsent = false)

        assertEquals(QuickConnectResult.NEEDS_CONSENT, result)
        assertNull(nextStart())
        assertNull(nm.getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `without consent the widget's connect posts the consent notice`() {
        ShadowVpnService.setPrepareResult(Intent()) // missing consent

        val result = entry(app).connect("p1", noticeWithoutConsent = true)

        assertEquals(QuickConnectResult.NEEDS_CONSENT, result)
        assertNull(nextStart())
        val posted = nm.getNotification(TunnelNotifications.NOTIFICATION_ID)
        assertNotNull("no consent notification in slot 42", posted)
        assertEquals("Vaayil couldn't connect", posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `with consent a connect sends one CONNECT for the profile`() {
        ShadowVpnService.setPrepareResult(null)

        assertEquals(QuickConnectResult.SENT, entry(app).connect("p1", noticeWithoutConsent = true))

        val started = nextStart()
        assertEquals(VpnTunnelService::class.java.name, started?.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
        assertNull("the connect started more than one service", nextStart())
    }

    @Test
    fun `a second tap before the status moves is skipped`() {
        ShadowVpnService.setPrepareResult(null)
        val commands = entry(app)

        assertEquals(QuickConnectResult.SENT, commands.connect("p1", noticeWithoutConsent = true))
        assertEquals(QuickConnectResult.SKIPPED, commands.connect("p1", noticeWithoutConsent = true))

        assertNotNull(nextStart())
        assertNull("the skipped tap started a service", nextStart())
    }

    @Test
    fun `a status change lifts the guard`() {
        ShadowVpnService.setPrepareResult(null)
        val commands = entry(app)
        assertEquals(QuickConnectResult.SENT, commands.connect("p1", noticeWithoutConsent = true))

        ConnectionRegistry.status.value =
            TunnelStatus(ConnectionPhase.FAILED, "p1", TunnelError.HandshakeTimeout)

        assertEquals(QuickConnectResult.SENT, commands.connect("p1", noticeWithoutConsent = true))
    }

    @Test
    fun `the guard lifts after 10 s`() {
        ShadowVpnService.setPrepareResult(null)
        val commands = entry(app)
        assertEquals(QuickConnectResult.SENT, commands.connect("p1", noticeWithoutConsent = true))

        now += 10_000

        assertEquals(QuickConnectResult.SENT, commands.connect("p1", noticeWithoutConsent = true))
    }

    @Test
    fun `a retry after a settled connect is sent even when the status reads the same`() {
        ShadowVpnService.setPrepareResult(null)
        ConnectionRegistry.status.value =
            TunnelStatus(ConnectionPhase.FAILED, "p1", TunnelError.HandshakeTimeout)
        val commands = entry(app)

        assertEquals(QuickConnectResult.SENT, commands.connect("p1", noticeWithoutConsent = true))
        settled++
        assertEquals(QuickConnectResult.SENT, commands.connect("p1", noticeWithoutConsent = true))

        assertNotNull(nextStart())
        assertNotNull(nextStart())
        assertNull("the retry started more than one service", nextStart())
    }

    @Test
    fun `a tap while the sent connect is still in flight is skipped, even after a failure`() {
        ShadowVpnService.setPrepareResult(null)
        ConnectionRegistry.status.value =
            TunnelStatus(ConnectionPhase.FAILED, "p1", TunnelError.HandshakeTimeout)
        val commands = entry(app)

        assertEquals(QuickConnectResult.SENT, commands.connect("p1", noticeWithoutConsent = true))
        assertEquals(QuickConnectResult.SKIPPED, commands.connect("p1", noticeWithoutConsent = true))

        assertNotNull(nextStart())
        assertNull("the skipped tap started a service", nextStart())
    }

    @Test
    fun `a live tunnel skips without asking for consent`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p2", null)
        ShadowVpnService.setPrepareResult(Intent()) // missing consent: a live tunnel never asks

        assertEquals(QuickConnectResult.SKIPPED, entry(app).connect("p1", noticeWithoutConsent = true))

        assertNull(nextStart())
        assertNull(nm.getNotification(TunnelNotifications.NOTIFICATION_ID))
    }

    @Test
    fun `the entry's disconnect lifts the guard`() = runBlocking {
        ShadowVpnService.setPrepareResult(null)
        val commands = entry(app)
        assertEquals(QuickConnectResult.SENT, commands.connect("p1", noticeWithoutConsent = true))

        commands.disconnect()

        assertEquals(QuickConnectResult.SENT, commands.connect("p1", noticeWithoutConsent = true))
    }

    @Test
    fun `a live disconnect sends DISCONNECT in the foreground`() = runBlocking {
        ShadowVpnService.setPrepareResult(Intent()) // missing consent: a disconnect must not care
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        ActiveProfileStore(app).setActive("p1")
        val recording = RecordingStarts(app)

        val commands = QuickTunnelCommands(
            BackgroundTunnelCommands(recording),
            ConnectionRegistry.status,
            { ConnectionRegistry.status.value.phase.isLive },
            { now },
            { settled },
        )
        commands.disconnect()

        assertEquals(
            listOf(VpnTunnelService.ACTION_DISCONNECT),
            recording.disconnectIntents.map { it.action },
        )
        assertEquals(1, recording.foregroundStarts)
        assertEquals(0, recording.plainStarts)
        assertNull(ActiveProfileStore(app).activeId.first())
    }

    @Test
    fun `an idle disconnect clears ACTIVE, starts nothing and bars a late resume`() = runBlocking {
        val fixture = installResumeFixture()
        var primary: Throwable? = null
        try {
            ShadowVpnService.setPrepareResult(Intent()) // missing consent: a disconnect must not care
            ActiveProfileStore(app).apply { setActive("p1"); setLastConnected("p1") }
            val commands = entry(app)

            commands.disconnect()

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
            restoreResumeFixture(fixture, null, primary) { shadowOf(Looper.getMainLooper()).idle() }
        }
    }
}
