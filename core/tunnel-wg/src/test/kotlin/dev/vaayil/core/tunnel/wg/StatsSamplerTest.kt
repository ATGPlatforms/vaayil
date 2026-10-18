package dev.vaayil.core.tunnel.wg

import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.TestStatistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import dev.vaayil.core.tunnel.api.TunnelStats
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deferred minor #13: stop() cancels the sampling job without joining, so a sample that
 * completes after the cancel must not call update() and repopulate stats the engine just
 * zeroed.
 *
 * G4 (spec §4.4.3): the cadence is 1 s until a sample carries a handshake or the 30 s window ends.
 */
class StatsSamplerTest {
    /** getStatistics parks on a latch (on the IO dispatcher the sampler hops to), so a stop()
        can land while the sample is in flight; releasing lets it complete normally. */
    private class LatchedBackend(
        val entered: CountDownLatch,
        val release: CountDownLatch,
    ) : Backend {
        override fun getRunningTunnelNames(): Set<String> = emptySet()
        override fun getState(tunnel: Tunnel): Tunnel.State = Tunnel.State.DOWN
        override fun getStatistics(tunnel: Tunnel): Statistics {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return TestStatistics.empty()
        }
        override fun getVersion(): String = "test"
        // Added by the wireguard-android 1.0.20260102 bump; the sampler never calls these.
        override fun isAlwaysOn(): Boolean = false
        override fun isLockdownEnabled(): Boolean = false
        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: Config?): Tunnel.State =
            throw UnsupportedOperationException()
    }

    /** Returns its scripted samples in order and repeats the last one. */
    private class ScriptedBackend(private val results: List<Statistics>) : Backend {
        private val calls = AtomicInteger(0)
        override fun getStatistics(tunnel: Tunnel): Statistics =
            results[minOf(calls.getAndIncrement(), results.size - 1)]
        override fun getRunningTunnelNames(): Set<String> = emptySet()
        override fun getState(tunnel: Tunnel): Tunnel.State = Tunnel.State.DOWN
        override fun getVersion(): String = "test"
        override fun isAlwaysOn(): Boolean = false
        override fun isLockdownEnabled(): Boolean = false
        override fun setState(tunnel: Tunnel, state: Tunnel.State, config: Config?): Tunnel.State =
            throw UnsupportedOperationException()
    }

    /** Records each published sample and its arrival in ms since the log was armed. */
    private class SampleLog(expected: Int) {
        private val armedAt = System.nanoTime()
        val latch = CountDownLatch(expected)
        val samples = CopyOnWriteArrayList<Pair<Long, TunnelStats>>()

        fun record(stats: TunnelStats) {
            samples += (System.nanoTime() - armedAt) / 1_000_000 to stats
            latch.countDown()
        }

        /** Milliseconds between consecutive samples. */
        fun gaps(): List<Long> = samples.zipWithNext { a, b -> b.first - a.first }
    }

    private val tunnel = object : Tunnel {
        override fun getName() = "t"
        override fun onStateChange(state: Tunnel.State) = Unit
    }

    @Test
    fun `no stats write after stop`() {
        val writes = AtomicInteger(0)
        val backend = LatchedBackend(CountDownLatch(1), CountDownLatch(1))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sampler = StatsSampler(scope, fastIntervalMs = 50)
        sampler.start(backend, object : Tunnel {
            override fun getName() = "t"
            override fun onStateChange(state: Tunnel.State) = Unit
        }, 7) { writes.incrementAndGet() }
        check(backend.entered.await(2, TimeUnit.SECONDS)) // sample in flight
        sampler.stop()                                    // cancel lands mid-sample
        backend.release.countDown()                       // let it complete
        Thread.sleep(200)                                 // grace for any illegal publish
        assertEquals(0, writes.get()) // pre-fix: >=1 — stale stats published after stop
        scope.cancel()
    }

    @Test
    fun `samples carry their start's generation`() {
        val log = SampleLog(expected = 2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // fastIntervalMs sits above the immediacy bound: a first sample that waited for the
        // interval instead of sampling at once could not pass.
        val sampler = StatsSampler(scope, fastIntervalMs = 800, slowIntervalMs = 100)
        sampler.start(ScriptedBackend(listOf(TestStatistics.withHandshake(1_000))), tunnel, 7) { log.record(it) }
        assertTrue("two samples arrive: ${log.samples}", log.latch.await(5, TimeUnit.SECONDS))
        sampler.stop()
        assertTrue("the first sample arrives at once: ${log.samples}", log.samples.first().first < 500)
        assertEquals(1_000L, log.samples.first().second.handshakeAt)
        assertTrue(
            "every sample carries its start's generation: ${log.samples.map { it.second.generation }}",
            log.samples.all { it.second.generation == 7L },
        )
        scope.cancel()
    }

    @Test
    fun `handshake-less samples stay fast and a handshake switches the cadence to slow`() {
        val log = SampleLog(expected = 4)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sampler = StatsSampler(scope, fastIntervalMs = 50, slowIntervalMs = 250, fastWindowMs = 10_000)
        val backend = ScriptedBackend(
            listOf(TestStatistics.empty(), TestStatistics.empty(), TestStatistics.withHandshake(1_234)),
        )
        sampler.start(backend, tunnel, 3) { log.record(it) }
        assertTrue("four samples arrive: ${log.samples}", log.latch.await(10, TimeUnit.SECONDS))
        sampler.stop()
        val gaps = log.gaps()
        assertTrue("fast gaps before the handshake: $gaps", gaps[0] < 250 && gaps[1] < 250)
        assertTrue("the handshake sample is followed by the slow cadence: $gaps", gaps[2] >= 200)
        assertEquals(1_234L, log.samples[2].second.handshakeAt)
        scope.cancel()
    }

    @Test
    fun `the window caps the fast cadence without a handshake`() {
        val log = SampleLog(expected = 12)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sampler = StatsSampler(scope, fastIntervalMs = 50, slowIntervalMs = 250, fastWindowMs = 400)
        sampler.start(ScriptedBackend(listOf(TestStatistics.empty())), tunnel, 5) { log.record(it) }
        assertTrue("twelve samples arrive: ${log.samples}", log.latch.await(10, TimeUnit.SECONDS))
        sampler.stop()
        val samples = log.samples
        val gaps = log.gaps()
        val beforeWindow = gaps.filterIndexed { i, _ -> samples[i].first < 300 }
        assertTrue("no pre-window gap exceeds 150ms: $gaps", beforeWindow.all { it <= 150 })
        assertTrue("the cadence drops to slow after the window: $gaps", gaps.any { it >= 200 })
        scope.cancel()
    }
}
