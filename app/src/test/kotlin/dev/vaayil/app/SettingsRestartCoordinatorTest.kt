package dev.vaayil.app

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelError
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C4: a settings save that lands while its profile is CONNECTING must be applied to the start
 * that settles CONNECTED for that same profile — one restart, however many saves land during the
 * start. A start that settles FAILED, DISCONNECTED or as another profile makes the save trigger
 * nothing, and a save with no start to wait for registers nothing at all. Whatever lands before
 * the settle coalesces into that one restart.
 */
class SettingsRestartCoordinatorTest {
    private val status = MutableStateFlow(TunnelStatus.idle)
    private val restarts = mutableListOf<String>()

    private fun newCoordinator(scope: CoroutineScope) =
        SettingsRestartCoordinator(scope, status) { restarts += it }

    private fun connecting(id: String) = TunnelStatus(ConnectionPhase.CONNECTING, id, null)
    private fun connected(id: String) = TunnelStatus(ConnectionPhase.CONNECTED, id, null)

    @Test
    fun connectingThenConnectedRestartsOnce() = runBlocking {
        status.value = connecting("p1")
        val coordinator = newCoordinator(this)
        coordinator.request()
        assertEquals(emptyList<String>(), restarts) // nothing to re-apply to before the settle
        status.value = connected("p1")
        yield() // the watcher resumes on the status flow's emission
        assertEquals(listOf("p1"), restarts)
    }

    @Test
    fun failedOrDisconnectedDropsPending() = runBlocking {
        val coordinator = newCoordinator(this)

        status.value = connecting("p1")
        coordinator.request()
        status.value = TunnelStatus(ConnectionPhase.FAILED, "p1", TunnelError.HandshakeTimeout)
        yield()
        assertEquals(emptyList<String>(), restarts)
        status.value = connected("p1") // a later session of that profile: still nothing
        yield()
        assertEquals(emptyList<String>(), restarts)

        status.value = connecting("p1")
        coordinator.request()
        status.value = TunnelStatus.idle
        yield()
        assertEquals(emptyList<String>(), restarts)
    }

    @Test
    fun switchToAnotherProfileDropsPending() = runBlocking {
        status.value = connecting("p1")
        val coordinator = newCoordinator(this)
        coordinator.request("p1")
        status.value = connecting("p2") // the start in flight is another profile now
        yield()
        status.value = connected("p2")
        yield()
        assertEquals(emptyList<String>(), restarts)
        status.value = connected("p1") // a later p1 session: the dropped request stays dropped
        yield()
        assertEquals(emptyList<String>(), restarts)
    }

    @Test
    fun mixedSavesCoalesce() = runBlocking {
        status.value = connecting("p1")
        val coordinator = newCoordinator(this)
        coordinator.request() // a split-tunnel save: the rules apply to the profile on screen
        coordinator.request("p1") // an editor save of that same profile
        status.value = connected("p1")
        yield()
        assertEquals(listOf("p1"), restarts)
    }

    @Test
    fun connectedBeforeWaiterResumesStillCoalesces() = runBlocking {
        status.value = connecting("p1")
        val coordinator = newCoordinator(this)
        coordinator.request()
        status.value = connected("p1") // the settle emission: the watcher has not run yet
        coordinator.request() // lands in that window and merges into the one pending restart
        assertEquals(emptyList<String>(), restarts)
        yield()
        assertEquals(listOf("p1"), restarts)
    }

    @Test
    fun screenDisposalDoesNotCancelPending() = runBlocking {
        status.value = connecting("p1")
        val coordinator = newCoordinator(this)
        val screen = CoroutineScope(SupervisorJob())
        screen.launch(start = CoroutineStart.UNDISPATCHED) { coordinator.request() }
        screen.cancel() // the screen goes away: the caller's scope must not own the restart
        status.value = connected("p1")
        yield()
        assertEquals(listOf("p1"), restarts)
    }
}
