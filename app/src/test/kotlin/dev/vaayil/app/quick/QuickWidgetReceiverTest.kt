package dev.vaayil.app.quick

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.app.AppContainer
import dev.vaayil.app.R
import dev.vaayil.app.settingsDataStore
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.EngineProvider
import dev.vaayil.core.tunnel.wg.TunnelNotifications
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import java.lang.reflect.Field
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowVpnService

/**
 * A widget disc tap acts on the state the last push showed: a connect for a readable profile
 * through the same commands the tile uses, nothing for a profile deleted since that push, and
 * a disconnect through B1's foreground path. Every handled intent refreshes the placed widgets.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickWidgetReceiverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private lateinit var originalAccess: (Context) -> QuickControls

    /** A 32-byte key (base64) that passes the repository's write validation. */
    private val key = "A".repeat(43) + "="

    @Before
    fun setUp() {
        // Robolectric shares the application between methods: no stale start may leak in.
        while (shadowOf(app).nextStartedService != null) Unit
        ShadowVpnService.setPrepareResult(null)
        runBlocking {
            DataGraph.reset()
            app.settingsDataStore.edit { it.clear() }
            ActiveProfileStore(app).apply {
                setActive(null)
                setLastConnected(null)
            }
            container = AppContainer(app, { _ -> }, { _ -> })
            container.profiles.storedIds().forEach { container.profiles.delete(it) }
            container.profiles.save(profile("p1", "Home"))
        }
        originalAccess = QuickControlsAccess.forContext
        QuickControlsAccess.forContext = { container.quickControls }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
    }

    @After
    fun tearDown() {
        QuickControlsAccess.forContext = originalAccess
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    @Test
    fun `a connect while a tunnel is live only refreshes`() {
        val widget = placeWidget()
        awaitText(widget, R.id.quick_widget_state, "Disconnected")

        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        ConnectionRegistry.handshake.value = HandshakeState.VERIFIED
        handle(connect("p1"))

        assertNull("the tap started a service while live", shadowOf(app).nextStartedService)
        assertNull(
            "the tap posted the consent notice while live",
            notifications.getNotification(TunnelNotifications.NOTIFICATION_ID),
        )
        awaitText(widget, R.id.quick_widget_state, "Connected")
    }

    @Test
    fun `a connect for a profile that is gone only refreshes`() {
        handle(connect("gone"))

        assertNull("the tap started a service", shadowOf(app).nextStartedService)
        assertNull(
            "the tap posted a notice for a profile it cannot read",
            notifications.getNotification(TunnelNotifications.NOTIFICATION_ID),
        )
    }

    @Test
    fun `a connect without consent posts the consent notice`() {
        ShadowVpnService.setPrepareResult(Intent()) // missing consent

        handle(connect("p1"))

        assertNull("a missing consent started the service", shadowOf(app).nextStartedService)
        val posted = notifications.getNotification(TunnelNotifications.NOTIFICATION_ID)
        assertNotNull("no consent notice in slot 42", posted)
        assertEquals(
            "Vaayil couldn't connect",
            posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
    }

    @Test
    fun `a connect with consent starts the shown profile`() {
        handle(connect("p1"))

        val started = shadowOf(app).nextStartedService
        assertEquals(VpnTunnelService::class.java.name, started?.component?.className)
        assertEquals(VpnTunnelService.ACTION_CONNECT, started?.action)
        assertEquals("p1", started?.getStringExtra(VpnTunnelService.EXTRA_PROFILE_ID))
    }

    @Test
    fun `a disconnect with nothing live clears ACTIVE, starts nothing and bars a late resume`() {
        crashResumeGateField().set(null, freshGate())
        try {
            runBlocking { ActiveProfileStore(app).setActive("p1") }

            handle(disconnect())

            assertNull(
                "the disconnect did not clear ACTIVE",
                runBlocking { ActiveProfileStore(app).activeId.first() },
            )
            assertNull(
                "a disconnect with nothing live started the service",
                shadowOf(app).nextStartedService,
            )
            assertFalse(
                "the disconnect left the crash resume open",
                mayIssue(crashResumeGateField().get(null)),
            )
        } finally {
            crashResumeGateField().set(null, freshGate())
        }
    }

    @Test
    fun `a disconnect while live sends DISCONNECT`() {
        ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)

        handle(disconnect())

        val started = shadowOf(app).nextStartedService
        assertEquals(VpnTunnelService::class.java.name, started?.component?.className)
        assertEquals(VpnTunnelService.ACTION_DISCONNECT, started?.action)
    }

    private fun connect(profileId: String): Intent =
        Intent(app, QuickWidgetReceiver::class.java)
            .setAction(QuickWidgetReceiver.ACTION_CONNECT)
            .putExtra(QuickWidgetReceiver.EXTRA_PROFILE_ID, profileId)

    private fun disconnect(): Intent =
        Intent(app, QuickWidgetReceiver::class.java)
            .setAction(QuickWidgetReceiver.ACTION_DISCONNECT)

    private fun handle(intent: Intent) = runBlocking { QuickWidgetReceiver().handle(app, intent) }

    private val notifications: ShadowNotificationManager
        get() = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    private fun placeWidget(): Int =
        shadowOf(AppWidgetManager.getInstance(app))
            .createWidget(QuickWidgetProvider::class.java, R.layout.quick_widget)

    /** The refresh is synchronous here, but the placement's own push is not: poll boundedly. */
    private fun awaitText(id: Int, viewId: Int, expected: String, timeoutMs: Long = 5_000) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (textOf(id, viewId) != expected && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(expected, textOf(id, viewId))
    }

    private fun textOf(id: Int, viewId: Int): String? =
        shadowOf(AppWidgetManager.getInstance(app)).getViewFor(id)
            .findViewById<TextView>(viewId)
            ?.text
            ?.toString()

    /** The gate class is internal to :core:tunnel-wg: built and read by reflection, as its own fixture does. */
    private fun crashResumeGateField(): Field =
        EngineProvider::class.java.getDeclaredField("crashResumeGate").apply { isAccessible = true }

    private fun freshGate(): Any =
        Class.forName("dev.vaayil.core.tunnel.wg.CrashResumeGate").declaredConstructors
            .first { it.parameterCount == 0 }
            .apply { isAccessible = true }
            .newInstance()

    private fun mayIssue(gate: Any?): Boolean {
        assertNotNull("no crash-resume gate installed", gate)
        return gate!!.javaClass.getMethod("mayIssue").invoke(gate) as Boolean
    }

    private fun profile(id: String, name: String) = Profile(
        id, name,
        InterfaceConfig(key, listOf("10.0.0.2/32"), emptyList(), 1280, null),
        PeerConfig(key, null, "h:51820", listOf("0.0.0.0/0"), 25),
    )
}
