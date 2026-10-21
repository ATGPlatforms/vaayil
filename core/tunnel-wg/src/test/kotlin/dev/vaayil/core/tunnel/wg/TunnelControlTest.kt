package dev.vaayil.core.tunnel.wg

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The row-24 regression: GoBackend stopSelf()s the VpnService whenever a tunnel goes DOWN,
 * so a connect that runs on a service instance's scope is cancelled mid-establish during a
 * start-while-up (the split-tunnel save restart), and its failure handler then tears down
 * the freshly established tunnel. The orchestration must run on a scope that outlives the
 * caller — the "caller" being the dying service instance.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class TunnelControlTest {

    @Test
    fun `connect settles even when the calling scope is cancelled mid-start`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<Pair<StartOutcome, TunnelStatus>>()
        // The caller's scope models the VpnTunnelService instance: destroyed mid-start.
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val control = TunnelControl(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectOp = { _, _, _ ->
                started.complete(Unit)
                release.await() // the establish window: the service dies here in the field
                StartOutcome.STARTED
            },
            statusNow = { TunnelStatus(ConnectionPhase.CONNECTED, "p", null) },
            stopOp = {},
        )
        callerScope.launch {
            control.connect(ApplicationProvider.getApplicationContext(), "p") { outcome, status ->
                settled.complete(outcome to status)
            }
        }
        started.await()
        callerScope.cancel()

        release.complete(Unit)
        val (outcome, status) = withTimeout(5_000) { settled.await() }
        assertEquals(StartOutcome.STARTED, outcome)
        assertEquals(ConnectionPhase.CONNECTED, status.phase)
    }

    @Test
    fun `disconnect runs its stop even when the calling scope is cancelled`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<Unit>()
        var stopped = false
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val control = TunnelControl(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectOp = { _, _, _ -> StartOutcome.REFUSED },
            statusNow = { TunnelStatus.idle },
            stopOp = {
                started.complete(Unit)
                release.await()
                stopped = true
            },
        )
        callerScope.launch { control.disconnect { settled.complete(Unit) } }
        started.await()
        callerScope.cancel()

        release.complete(Unit)
        withTimeout(5_000) { settled.await() }
        assertTrue(stopped)
    }

    @Test
    fun `settle callback runs after the stop completes, not before`() = runTest {
        val order = mutableListOf<String>()
        val settled = CompletableDeferred<Unit>()
        val control = TunnelControl(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectOp = { _, _, _ -> StartOutcome.REFUSED },
            statusNow = { TunnelStatus.idle },
            stopOp = { order += "stop" },
        )
        control.disconnect { order += "settle"; settled.complete(Unit) }
        withTimeout(5_000) { settled.await() }
        assertEquals(listOf("stop", "settle"), order)
    }

    @Test
    fun `disconnect runs the pre-stop step before the stop`() = runTest {
        val order = mutableListOf<String>()
        val settled = CompletableDeferred<Unit>()
        val control = TunnelControl(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectOp = { _, _, _ -> StartOutcome.REFUSED },
            statusNow = { TunnelStatus.idle },
            stopOp = { order += "stop" },
        )
        control.disconnect(beforeStop = { order += "before" }) { order += "settled"; settled.complete(Unit) }
        withTimeout(5_000) { settled.await() }
        assertEquals(listOf("before", "stop", "settled"), order)
    }

    @Test
    fun `a failing pre-stop step still stops`() = runTest {
        val order = mutableListOf<String>()
        val settled = CompletableDeferred<Unit>()
        val control = TunnelControl(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectOp = { _, _, _ -> StartOutcome.REFUSED },
            statusNow = { TunnelStatus.idle },
            stopOp = { order += "stop" },
        )
        control.disconnect(
            beforeStop = { throw IllegalStateException("scripted pre-stop failure") },
        ) { order += "settled"; settled.complete(Unit) }
        withTimeout(5_000) { settled.await() }
        assertEquals(listOf("stop", "settled"), order)
    }

    @Test
    fun `disconnect joins the job it is given`() = runTest {
        val connectRunning = CompletableDeferred<Unit>()
        val releaseConnect = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val settled = CompletableDeferred<Unit>()
        val control = TunnelControl(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectOp = { _, _, _ ->
                order += "connect"
                connectRunning.complete(Unit)
                releaseConnect.await() // the resume's establish window
                StartOutcome.STARTED
            },
            statusNow = { TunnelStatus.idle },
            stopOp = { order += "stop" },
        )
        val connect = control.connect(
            ApplicationProvider.getApplicationContext(), "p",
            start = CoroutineStart.LAZY,
            onSettled = { _, _ -> },
        )
        try {
            connect.start()
            withTimeout(5_000) { connectRunning.await() }

            control.disconnect(after = connect, beforeStop = { order += "before" }) {
                order += "settled"; settled.complete(Unit)
            }

            // Nothing past the join runs while the connect is still held.
            assertEquals(listOf("connect"), order)
        } finally {
            // An assertion failure must not leave the connect parked: the released connect
            // settles, its Job completes, and the joined teardown runs to the end.
            releaseConnect.complete(Unit)
        }
        withTimeout(5_000) { settled.await() }
        assertEquals(listOf("connect", "before", "stop", "settled"), order)
    }

    @Test
    fun `a lazy connect runs its op only after start, and a cancelled unstarted one never runs it`() = runTest {
        var connectCalls = 0
        val control = TunnelControl(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            connectOp = { _, _, _ -> connectCalls += 1; StartOutcome.REFUSED },
            statusNow = { TunnelStatus.idle },
            stopOp = {},
        )
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()

        val lazy = control.connect(app, "p", start = CoroutineStart.LAZY)
        assertEquals(0, connectCalls) // built but not run
        assertTrue(lazy.start())
        assertEquals(1, connectCalls) // runs only once started

        val cancelled = control.connect(app, "p", start = CoroutineStart.LAZY)
        cancelled.cancel()
        assertFalse(cancelled.start()) // a cancelled unstarted job never runs its op
        assertEquals(1, connectCalls)
    }

    @Test
    fun `every connect whose op ran counts as settled, whatever its outcome`() = runTest {
        val outcomes = ArrayDeque(listOf(StartOutcome.STARTED, StartOutcome.FAILED, StartOutcome.REFUSED))
        var opRuns = 0
        var settled = 0
        val control = TunnelControl(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> }),
            connectOp = { _, _, _ ->
                opRuns += 1
                outcomes.removeFirstOrNull() ?: throw IllegalStateException("scripted connect failure")
            },
            statusNow = { TunnelStatus.idle },
            stopOp = {},
            noteConnectSettled = { settled += 1 },
        )
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()

        control.connect(app, "p").join()
        control.connect(app, "p").join()
        control.connect(app, "p").join()
        assertEquals(3, settled)

        control.connect(app, "p").join()
        assertEquals(4, settled) // a throwing op settles too

        val lazy = control.connect(app, "p", start = CoroutineStart.LAZY)
        lazy.cancel()
        assertFalse(lazy.start())
        assertEquals(4, settled) // a connect cancelled before it ran never counts
        assertEquals(4, opRuns)

    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `reconcile runs the reconcile op on the process scope`() = runTest {
        var reconciled = 0
        val control = TunnelControl(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            connectOp = { _, _, _ -> StartOutcome.REFUSED },
            statusNow = { TunnelStatus.idle },
            stopOp = {},
            reconcileOp = { reconciled++ },
        )
        control.reconcile()
        assertEquals(1, reconciled)
    }
}
