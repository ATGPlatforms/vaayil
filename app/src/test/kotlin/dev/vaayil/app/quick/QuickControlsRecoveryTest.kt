package dev.vaayil.app.quick

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.automation.QuickTunnelCommands
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A store that cannot be read must not end the hub's long-lived readers: the failure is retried,
 * so the widget gets the next state and the shortcuts the next profile, and nothing reaches the
 * scope's uncaught-exception handler.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickControlsRecoveryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val failures = mutableListOf<Throwable>()
    private val handler = CoroutineExceptionHandler { _, e -> synchronized(failures) { failures += e } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)

    private val snapshot = QuickInputs(
        status = TunnelStatus.idle,
        handshake = HandshakeState.NONE,
        readable = listOf(QuickProfile("p1", "Home")),
        storedCount = 1,
        activeId = null,
        lastConnectedId = "p1",
    )

    @Before
    fun drainServices() {
        shadowOf(app).nextStartedService
    }

    @After
    fun stop() {
        scope.cancel()
        ConnectionRegistry.status.value = TunnelStatus.idle
        ConnectionRegistry.handshake.value = HandshakeState.NONE
    }

    @Test
    fun `a failed read is retried and the readers catch up`() {
        val pushed = mutableListOf<QuickState>()
        val host = Host()
        val controls = QuickControls(
            scope = scope,
            inputs = failingInputs(),
            commands = QuickTunnelCommands(app),
            requests = QuickRequests(),
            widgets = WidgetUpdater(
                WidgetPusher { state ->
                    synchronized(pushed) { pushed += state }
                    true
                },
            ),
            shortcuts = ShortcutUpdater(QuickShortcutSync(host), { setOf("p1") }),
        )

        controls.start()

        await("the widget never received the retried state") {
            synchronized(pushed) { pushed.contains(presentQuickState(snapshot)) }
        }
        await("the shortcuts never published the retried profile") {
            host.dynamic().contains(PublishedShortcut("connect:p1", "Home", true))
        }
        synchronized(failures) {
            assertTrue("a failed read reached the scope's handler: $failures", failures.isEmpty())
        }
    }

    /** One [IOException], then a snapshot followed by silence; each collection is a fresh attempt. */
    private fun failingInputs(): Flow<QuickInputs> {
        var collections = 0
        return flow {
            if (collections++ == 0) throw IOException("the store is gone")
            emit(snapshot)
            awaitCancellation()
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val recorded = synchronized(failures) { failures.map { it.javaClass.simpleName } }
        assertTrue("$description (handler recorded: $recorded)", condition())
    }

    /** Publishes in memory so a later sync sees its own result. */
    private class Host : ShortcutHost {
        private val lock = Any()
        private var dynamicShortcuts = emptyList<PublishedShortcut>()

        override fun maxCount(): Int = 5
        override fun dynamic(): List<PublishedShortcut> = synchronized(lock) { dynamicShortcuts }
        override fun pinned(): List<PublishedShortcut> = emptyList()

        override fun setDynamic(specs: List<ShortcutSpec>): Boolean = synchronized(lock) {
            dynamicShortcuts = specs.map { PublishedShortcut(it.id, it.name, true) }
            true
        }

        override fun update(specs: List<ShortcutSpec>): Boolean = true
        override fun disable(ids: List<String>, message: String) = Unit
        override fun enable(specs: List<ShortcutSpec>) = Unit
    }
}
