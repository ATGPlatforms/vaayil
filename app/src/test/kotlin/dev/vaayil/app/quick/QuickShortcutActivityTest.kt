package dev.vaayil.app.quick

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.app.AppContainer
import dev.vaayil.app.MainActivity
import dev.vaayil.app.settingsDataStore
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
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
import org.robolectric.annotation.Config

/**
 * The trampoline is invisible: it drops one validated request in the hub's slot and reopens the
 * app, then finishes. A bad id or a foreign action does neither, and never leaves the activity
 * (or a task) behind.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickShortcutActivityTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private lateinit var originalAccess: (Context) -> QuickControls

    @Before
    fun setUp() {
        runBlocking {
            DataGraph.reset()
            app.settingsDataStore.edit { it.clear() }
            ActiveProfileStore(app).apply {
                setActive(null)
                setLastConnected(null)
            }
            container = AppContainer(app, { _ -> }, { _ -> })
        }
        originalAccess = QuickControlsAccess.forContext
        QuickControlsAccess.forContext = { container.quickControls }
    }

    @After
    fun restoreAccess() {
        QuickControlsAccess.forContext = originalAccess
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    @Test
    fun `a valid shortcut posts its request and opens the app`() {
        val activity = Robolectric.buildActivity(
            QuickShortcutActivity::class.java,
            QuickShortcutActivity.intent(app, "p1"),
        ).create().get()

        assertEquals("p1", container.quickControls.requests.pending.value?.profileId)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `an invalid id posts nothing and opens nothing`() {
        val activity = Robolectric.buildActivity(
            QuickShortcutActivity::class.java,
            QuickShortcutActivity.intent(app, "a/b"),
        ).create().get()

        assertNull(container.quickControls.requests.pending.value)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `an intent without the shortcut action posts nothing`() {
        val intent = Intent(app, QuickShortcutActivity::class.java)
            .putExtra(QuickShortcutActivity.EXTRA_PROFILE_ID, "p1")
        val activity = Robolectric.buildActivity(QuickShortcutActivity::class.java, intent).create().get()

        assertNull(container.quickControls.requests.pending.value)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
}
