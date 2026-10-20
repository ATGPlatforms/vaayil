package dev.vaayil.app.quick

import android.app.Application
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The compat host publishes through the platform's shortcut manager: the launcher sees the
 * profile name as the short label, "Connect <name>" as the long label, and the trampoline
 * intent that carries the profile id.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class CompatShortcutHostTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Robolectric shares the application between methods: no stale start intent leaks in.
        while (shadowOf(app).nextStartedService != null) Unit
    }

    @After
    fun resetRegistry() {
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    @Test
    fun `published shortcuts carry the labels and the trampoline intent`() {
        val host = CompatShortcutHost(app)
        host.setDynamic(listOf(ShortcutSpec("p1", "Home"), ShortcutSpec("p2", "Work")))

        val published = ShortcutManagerCompat.getShortcuts(app, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
            .sortedBy { it.rank }
        assertEquals(listOf("connect:p1", "connect:p2"), published.map { it.id })

        val home = published.first()
        assertEquals("Home", home.shortLabel.toString())
        assertEquals("Connect Home", home.longLabel.toString())
        assertEquals(QuickShortcutActivity::class.java.name, home.intent.component?.className)
        assertEquals(QuickShortcutActivity.ACTION_CONNECT_SHORTCUT, home.intent.action)
        assertEquals("p1", home.intent.getStringExtra(QuickShortcutActivity.EXTRA_PROFILE_ID))

        assertEquals(
            listOf(
                PublishedShortcut("connect:p1", "Home", true),
                PublishedShortcut("connect:p2", "Work", true),
            ),
            host.dynamic(),
        )
    }

    @Test
    fun `the launcher's cap is read from the system`() {
        shadowOf(app.getSystemService(ShortcutManager::class.java))
            .setMaxShortcutCountPerActivity(2)

        assertEquals(2, CompatShortcutHost(app).maxCount())
    }
}
