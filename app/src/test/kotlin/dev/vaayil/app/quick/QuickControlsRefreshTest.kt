package dev.vaayil.app.quick

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.automation.QuickTunnelCommands
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A refresh pushes the hub's newest state: one read taken while the state was older never lands
 * over the push of a change that arrived meanwhile, so nothing is left to trigger another push.
 * Before the hub has its first state (a process a widget broadcast just started), the refresh
 * reads the stores itself.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QuickControlsRefreshTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val failures = mutableListOf<Throwable>()
    private val handler = CoroutineExceptionHandler { _, e -> synchronized(failures) { failures += e } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    private val pushed = mutableListOf<QuickState>()
    private val host = Host()

    private val disconnected = QuickInputs(
        status = TunnelStatus.idle,
        handshake = HandshakeState.NONE,
        readable = listOf(QuickProfile("p1", "Home")),
        storedCount = 1,
        activeId = null,
        lastConnectedId = "p1",
    )
    private val connecting = disconnected.copy(
        status = TunnelStatus(ConnectionPhase.CONNECTING, "p1"),
        activeId = "p1",
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
    fun `a refresh never lands an older state over a newer push`() {
        assertNotEquals(presentQuickState(disconnected), presentQuickState(connecting))
        val hub = MutableStateFlow(disconnected)
        val readTaken = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val collections = AtomicInteger()
        val inputs = flow {
            if (collections.getAndIncrement() == 0) {
                emitAll(hub)
            } else {
                val read = hub.value
                readTaken.complete(Unit)
                release.await()
                emit(read)
            }
        }
        val controls = controls(inputs)

        controls.start()
        await("the first state was never pushed") {
            synchronized(pushed) { pushed.lastOrNull() == presentQuickState(disconnected) }
        }

        val refresh = scope.launch { controls.refreshWidgets() }
        await("the refresh neither read the stores nor finished") {
            readTaken.isCompleted || refresh.isCompleted
        }

        hub.value = connecting
        await("the live push never landed") {
            synchronized(pushed) { presentQuickState(connecting) in pushed }
        }

        release.complete(Unit)
        await("the refresh never returned") { refresh.isCompleted }

        assertEquals(presentQuickState(connecting), synchronized(pushed) { pushed.last() })
        synchronized(failures) {
            assertTrue("a failure reached the scope's handler: $failures", failures.isEmpty())
        }
    }

    @Test
    fun `before the hub has a state, a refresh reads the stores itself`() {
        val controls = controls(flowOf(disconnected))

        runBlocking { controls.refreshWidgets() }

        assertEquals(listOf(presentQuickState(disconnected)), synchronized(pushed) { pushed.toList() })
    }

    private fun controls(inputs: Flow<QuickInputs>) = QuickControls(
        scope = scope,
        inputs = inputs,
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
