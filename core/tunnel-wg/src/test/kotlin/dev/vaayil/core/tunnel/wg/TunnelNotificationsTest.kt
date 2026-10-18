package dev.vaayil.core.tunnel.wg

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class TunnelNotificationsTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        TunnelNotifications.ensureChannel(app)
    }

    @Test
    fun `a connected notification names the profile and shows no byte counts`() {
        val n = TunnelNotifications.buildNotification(
            app, TunnelStatus(ConnectionPhase.CONNECTED, "p", null), HandshakeState.VERIFIED, "Home", null,
        )!!
        assertEquals("Vaayil is connected", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Home", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `a connecting notification says so without a stale name`() {
        val n = TunnelNotifications.buildNotification(
            app, TunnelStatus(ConnectionPhase.CONNECTING, "p", null), HandshakeState.NONE, "Old", null,
        )!!
        assertEquals("Vaayil is connecting", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(n.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun `tapping the notification opens the app`() {
        val open = PendingIntent.getActivity(app, 0, Intent(Intent.ACTION_MAIN), PendingIntent.FLAG_IMMUTABLE)
        val n = TunnelNotifications.buildNotification(
            app, TunnelStatus(ConnectionPhase.CONNECTED, "p", null), HandshakeState.VERIFIED, "Home", open,
        )!!
        assertSame(open, n.contentIntent)
    }

    @Test
    fun `disconnected and failed states build no notification`() {
        assertNull(TunnelNotifications.buildNotification(app, TunnelStatus.idle, HandshakeState.VERIFIED, null, null))
        assertNull(
            TunnelNotifications.buildNotification(
                app, TunnelStatus(ConnectionPhase.FAILED, "p", TunnelError.HandshakeTimeout),
                HandshakeState.VERIFIED, null, null,
            ),
        )
    }

    @Test
    fun `a connected tunnel without its first handshake reads connecting`() {
        val n = TunnelNotifications.buildNotification(
            app, TunnelStatus(ConnectionPhase.CONNECTED, "p", null), HandshakeState.PENDING, "Home", null,
        )!!
        assertEquals("Vaayil is connecting", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Home", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `a connected tunnel whose handshake is not known yet reads connecting`() {
        val n = TunnelNotifications.buildNotification(
            app, TunnelStatus(ConnectionPhase.CONNECTED, "p", null), HandshakeState.NONE, "Home", null,
        )!!
        assertEquals("Vaayil is connecting", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Home", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `a connected tunnel past the handshake wait reads waiting for a handshake`() {
        val n = TunnelNotifications.buildNotification(
            app, TunnelStatus(ConnectionPhase.CONNECTED, "p", null), HandshakeState.WAITING, "Home", null,
        )!!
        assertEquals("Vaayil is waiting for a handshake", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Home", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `a disconnecting notification ignores the handshake`() {
        val n = TunnelNotifications.buildNotification(
            app, TunnelStatus(ConnectionPhase.DISCONNECTING, "p", null), HandshakeState.VERIFIED, "Home", null,
        )!!
        assertEquals("Vaayil is disconnecting", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `openAppIntent resolves the launcher activity and is null without one`() {
        assertNull(TunnelNotifications.openAppIntent(app))
        val launcher = ComponentName(app, "dev.vaayil.test.Launcher")
        shadowOf(app.packageManager).addActivityIfNotPresent(launcher)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            launcher, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        assertNotNull(TunnelNotifications.openAppIntent(app))
    }
}
