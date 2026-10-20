package dev.vaayil.app.quick

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater syncs once per distinct profile input and once per app resume, so a refused
 * publish can be retried. A throwing read is logged and leaves the collection running
 * (Review Focus 5).
 */
class ShortcutUpdaterTest {
    /** Publishes in memory so a later sync sees its own result. */
    private class Host(refuseOnce: Boolean = false) : ShortcutHost {
        private val lock = Any()
        private var refuseOnce = refuseOnce
        private var dynamicShortcuts = emptyList<PublishedShortcut>()
        var attempts = 0

        override fun maxCount(): Int = 5
        override fun dynamic(): List<PublishedShortcut> = synchronized(lock) { dynamicShortcuts }
        override fun pinned(): List<PublishedShortcut> = emptyList()

        override fun setDynamic(specs: List<ShortcutSpec>): Boolean = synchronized(lock) {
            attempts++
            if (refuseOnce) {
                refuseOnce = false
                return false
            }
            dynamicShortcuts = specs.map { PublishedShortcut(it.id, it.name, true) }
            true
        }

        override fun update(specs: List<ShortcutSpec>): Boolean = true
        override fun disable(ids: List<String>, message: String) = Unit
        override fun enable(specs: List<ShortcutSpec>) = Unit
    }

    private val a = QuickProfile("a", "A")
    private val b = QuickProfile("b", "B")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inputs = MutableSharedFlow<QuickInputs>(replay = 1, extraBufferCapacity = 1)

    @After
    fun stop() {
        scope.cancel()
    }

    private fun snapshot(readable: List<QuickProfile>, lastConnectedId: String? = null) = QuickInputs(
        status = dev.vaayil.core.tunnel.api.TunnelStatus.idle,
        handshake = dev.vaayil.core.tunnel.api.HandshakeState.NONE,
        readable = readable,
        storedCount = readable.size,
        activeId = null,
        lastConnectedId = lastConnectedId,
    )

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(description, condition())
    }

    @Test
    fun `a change in the profiles syncs`() {
        val host = Host()
        ShortcutUpdater(QuickShortcutSync(host)) { setOf("a", "b") }.launchIn(scope, inputs)

        inputs.tryEmit(snapshot(listOf(a)))
        await("the first profile was not published") {
            host.dynamic().map { it.id } == listOf("connect:a")
        }

        inputs.tryEmit(snapshot(listOf(a, b)))
        await("the new profile was not published") {
            host.dynamic().map { it.id } == listOf("connect:a", "connect:b")
        }
    }

    @Test
    fun `a resume retries a refused publish`() {
        val host = Host(refuseOnce = true)
        val updater = ShortcutUpdater(QuickShortcutSync(host)) { setOf("a") }
        updater.launchIn(scope, inputs)

        inputs.tryEmit(snapshot(listOf(a)))
        await("the first publish was not attempted") { host.attempts >= 1 }
        assertTrue("the refused publish was kept", host.dynamic().isEmpty())

        updater.onAppResumed()

        await("the resumed sync did not publish") {
            host.dynamic().map { it.id } == listOf("connect:a")
        }
        assertEquals(2, host.attempts)
    }

    @Test
    fun `a throwing sync doesn't end the updater`() {
        val host = Host()
        val reads = AtomicInteger()
        val ids: suspend () -> Set<String> = {
            if (reads.getAndIncrement() == 0) throw IllegalStateException("the database is locked")
            setOf("a", "b")
        }
        ShortcutUpdater(QuickShortcutSync(host), ids).launchIn(scope, inputs)

        inputs.tryEmit(snapshot(listOf(a, b)))
        await("the failing sync was not attempted") { reads.get() >= 1 }

        inputs.tryEmit(snapshot(listOf(a, b), lastConnectedId = "b"))
        await("the next change was not published") {
            host.dynamic().map { it.id } == listOf("connect:b", "connect:a")
        }
    }
}
