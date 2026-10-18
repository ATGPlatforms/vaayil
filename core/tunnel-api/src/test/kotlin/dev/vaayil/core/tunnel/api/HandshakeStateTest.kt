package dev.vaayil.core.tunnel.api

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.HandshakeState.NONE
import dev.vaayil.core.tunnel.api.HandshakeState.PENDING
import dev.vaayil.core.tunnel.api.HandshakeState.VERIFIED
import dev.vaayil.core.tunnel.api.HandshakeState.WAITING
import dev.vaayil.core.tunnel.api.TunnelNotice
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** G4 (spec §4.4.5): virtual-time checks of the handshake state flow — the 10 s clock, U3's
 *  2 s settle window, and the generation gate that keeps a switch from leaking A's handshake. */
@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeStateTest {
    private val status = MutableStateFlow(TunnelStatus.idle)
    private val stats = MutableStateFlow(TunnelStats.empty)

    /** Every emission, with the virtual time it happened at. */
    private val seen = mutableListOf<Pair<Long, HandshakeState>>()
    private val latest get() = seen.last().second

    private fun TestScope.watch() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            handshakeState(status, stats).collect { seen += testScheduler.currentTime to it }
        }
    }

    /** Runs the virtual clock to [ms], including the tasks due exactly at [ms]. */
    private fun TestScope.at(ms: Long) {
        advanceTimeBy(ms - testScheduler.currentTime)
        runCurrent()
    }

    private fun connected(id: String, generation: Long) =
        TunnelStatus(ConnectionPhase.CONNECTED, id, null, null, generation)
    private fun sample(handshakeAt: Long?, tx: Long, generation: Long) =
        TunnelStats(handshakeAt = handshakeAt, rxBytes = 0, txBytes = tx, generation = generation)

    @Test
    fun `a dead peer goes PENDING then WAITING at the deadline plus the settle rule`() = runTest {
        watch()
        status.value = connected("p1", 1)
        stats.value = sample(handshakeAt = null, tx = 148, generation = 1) // the sample taken as CONNECTED lands
        runCurrent()
        assertEquals(PENDING, latest)
        at(10_049)
        assertEquals(PENDING, latest)
        at(10_050)
        stats.value = sample(handshakeAt = null, tx = 296, generation = 1)
        runCurrent()
        assertEquals(WAITING, latest)
        assertEquals(10_050L, seen.first { it.second == WAITING }.first)
    }

    @Test
    fun `a handshake at 1s goes PENDING then VERIFIED`() = runTest {
        watch()
        status.value = connected("p1", 1)
        runCurrent()
        assertEquals(PENDING, latest)
        at(1_000)
        stats.value = sample(handshakeAt = 1_000L, tx = 296, generation = 1)
        runCurrent()
        assertEquals(VERIFIED, latest)
        at(30_000)
        assertEquals(VERIFIED, latest)
    }

    @Test
    fun `a late handshake turns WAITING into VERIFIED`() = runTest {
        watch()
        status.value = connected("p1", 1)
        stats.value = sample(handshakeAt = null, tx = 148, generation = 1)
        at(12_000)
        assertEquals(WAITING, latest)
        at(20_000)
        stats.value = sample(handshakeAt = 19_500L, tx = 444, generation = 1)
        runCurrent()
        assertEquals(VERIFIED, latest)
    }

    @Test
    fun `VERIFIED holds through a notice republish`() = runTest {
        watch()
        status.value = connected("p1", 1)
        stats.value = sample(handshakeAt = 1_000L, tx = 296, generation = 1)
        runCurrent()
        assertEquals(VERIFIED, latest)
        // C3's DnsNotAnswering republish: same profile, same generation — a new status value,
        // but not a new session, so the verdict must not flash back through PENDING.
        status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null, TunnelNotice.DnsNotAnswering, 1)
        runCurrent()
        assertEquals(VERIFIED, latest)
        val firstVerified = seen.indexOfFirst { it.second == VERIFIED }
        assertTrue(firstVerified >= 0)
        assertTrue(seen.drop(firstVerified + 1).all { it.second == VERIFIED }, "state flashed: $seen")
    }

    @Test
    fun `the switch race stays PENDING until B's own handshake`() = runTest {
        watch()
        status.value = connected("a", 7)
        stats.value = sample(handshakeAt = 500L, tx = 1, generation = 7)
        runCurrent()
        assertEquals(VERIFIED, latest)
        // B's CONNECTED arrives while the stats still carry A's handshake and A's generation:
        // A's handshake must not mark B verified (spec §4.4.2).
        status.value = connected("b", 8)
        runCurrent()
        assertEquals(PENDING, latest)
        stats.value = sample(handshakeAt = null, tx = 2, generation = 8)
        runCurrent()
        assertEquals(PENDING, latest)
        stats.value = sample(handshakeAt = 3_000L, tx = 3, generation = 8)
        runCurrent()
        assertEquals(VERIFIED, latest)
    }

    @Test
    fun `a handshake that arrived before CONNECTED verifies at once`() = runTest {
        watch()
        // B's first sample can land before B's CONNECTED: the current value already decides.
        stats.value = sample(handshakeAt = 9_000L, tx = 1, generation = 9)
        status.value = connected("b", 9)
        runCurrent()
        assertEquals(VERIFIED, latest)
        assertTrue(seen.none { it.second == PENDING }, "a PENDING flash slipped in: $seen")
    }

    @Test
    fun `a surviving republish of A keeps A's verdict`() = runTest {
        watch()
        status.value = connected("a", 7)
        stats.value = sample(handshakeAt = 500L, tx = 1, generation = 7)
        runCurrent()
        assertEquals(VERIFIED, latest)
        // A refused/failed switch republishes the live tunnel: same profile, same generation.
        status.value = connected("a", 7)
        runCurrent()
        assertEquals(VERIFIED, latest)
        val firstVerified = seen.indexOfFirst { it.second == VERIFIED }
        assertTrue(firstVerified >= 0)
        assertTrue(
            seen.drop(firstVerified + 1).none { it.second == PENDING || it.second == WAITING },
            "the republish reset the state: $seen",
        )
    }

    @Test
    fun `a handshake in the first sample after the deadline goes PENDING to VERIFIED with no WAITING`() = runTest {
        // F21's settle rescue: the peer answered at 9.9 s but the deadline's sample still shows
        // none, so the sample that lands just after the deadline carries it. No WAITING flash.
        watch()
        status.value = connected("p1", 1)
        stats.value = sample(handshakeAt = null, tx = 148, generation = 1)
        runCurrent()
        assertEquals(PENDING, latest)
        at(10_050)
        stats.value = sample(handshakeAt = 9_900L, tx = 296, generation = 1)
        runCurrent()
        assertEquals(VERIFIED, latest)
        at(30_000)
        assertEquals(VERIFIED, latest)
        assertTrue(seen.none { it.second == WAITING }, "waiting appeared: $seen")
    }

    @Test
    fun `leaving CONNECTED ends the session`() = runTest {
        watch()
        status.value = connected("p1", 1)
        stats.value = sample(handshakeAt = null, tx = 148, generation = 1)
        at(12_000)
        assertEquals(WAITING, latest)
        status.value = TunnelStatus.idle
        runCurrent()
        assertEquals(NONE, latest)
    }

    @Test
    fun `a new tunnel start restarts the ten seconds`() = runTest {
        watch()
        status.value = connected("p1", 1)
        stats.value = sample(handshakeAt = null, tx = 148, generation = 1)
        at(8_000)
        status.value = TunnelStatus(ConnectionPhase.CONNECTING, "p1", null)
        at(9_000)
        status.value = connected("p1", 2) // same profile, new generation: a new session
        at(12_000) // the first session's clock would have fired at 12 s
        assertEquals(PENDING, latest)
        at(20_999) // the new session's clock fires at 9 + 10 + 2 = 21 s
        assertEquals(PENDING, latest)
        at(21_000)
        assertEquals(WAITING, latest)
    }
}
