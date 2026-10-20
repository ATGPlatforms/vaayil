package dev.vaayil.app.quick

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater pushes each state once, a refresh bypasses the duplicate check (a newly placed
 * widget needs the current views even when the state has not changed), and a throwing push is
 * logged without ending the collection, so the next change still lands.
 */
class WidgetUpdaterTest {
    private val home = QuickState(
        "Home",
        "Disconnected",
        QuickTone.IDLE,
        live = false,
        action = QuickAction.OpenApp,
    )
    private val work = QuickState(
        "Work",
        "Connected",
        QuickTone.ACTIVE,
        live = true,
        action = QuickAction.Disconnect,
    )

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val source = MutableSharedFlow<QuickState>(replay = 1)
    private val pushed = mutableListOf<QuickState>()

    private fun updater(push: (QuickState) -> Boolean = { state -> pushed += state; true }) =
        WidgetUpdater(WidgetPusher { push(it) })

    @Test
    fun `the first state is pushed at start`() {
        updater().launchIn(scope, source)

        source.tryEmit(home)

        assertEquals(listOf(home), pushed)
    }

    @Test
    fun `an unchanged state is not pushed twice`() {
        updater().launchIn(scope, source)

        source.tryEmit(home)
        source.tryEmit(home)
        source.tryEmit(work)

        assertEquals(listOf(home, work), pushed)
    }

    @Test
    fun `a refresh pushes even when nothing changed`() {
        val updater = updater()
        updater.launchIn(scope, source)
        source.tryEmit(home)

        updater.refresh(home)

        assertEquals(listOf(home, home), pushed)
    }

    @Test
    fun `a throwing push is logged and the updater keeps going`() {
        val job = updater { state ->
            if (state == home) throw IllegalStateException("the widget manager is gone")
            pushed += state
            true
        }.launchIn(scope, source)

        source.tryEmit(home)
        source.tryEmit(work)

        assertEquals(listOf(work), pushed)
        assertTrue("the updater stopped after a failed push", job.isActive)
    }
}
