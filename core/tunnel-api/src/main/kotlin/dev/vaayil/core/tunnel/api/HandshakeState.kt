package dev.vaayil.core.tunnel.api

import dev.vaayil.core.tunnel.api.HandshakeState.NONE
import dev.vaayil.core.tunnel.api.HandshakeState.PENDING
import dev.vaayil.core.tunnel.api.HandshakeState.VERIFIED
import dev.vaayil.core.tunnel.api.HandshakeState.WAITING
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * G4 (spec §4.4): the live tunnel's handshake state. A session is one uninterrupted CONNECTED
 * run for one profile AND one tunnel generation: a handshake counts only when
 * [TunnelStats.generation] matches [TunnelStatus.generation], so a switch away from a healthy
 * tunnel can never leak its handshake into the newcomer. PENDING covers CONNECTED before this
 * tunnel's own first handshake (a state not yet known reads PENDING too — fail closed, never
 * sage). After [waitMillis] without one, U3's settle rule holds: one more stats update, or
 * [settleMillis] without one, then WAITING. VERIFIED holds for the rest of the session.
 * It runs once for the process in `ConnectionRegistry.bridge` (J13); the dashboard, the Profiles tab and the tunnel
 * notification all read that one result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun handshakeState(
    status: Flow<TunnelStatus>,
    stats: StateFlow<TunnelStats>,
    waitMillis: Long = 10_000,
    settleMillis: Long = 2_000,
): Flow<HandshakeState> = status
    .map { if (it.phase == ConnectionPhase.CONNECTED) it.profileId.orEmpty() to it.generation else null }
    .distinctUntilChanged()
    .transformLatest { session ->
        if (session == null) { emit(NONE); return@transformLatest }
        val generation = session.second
        fun TunnelStats?.fromThisTunnel(): Boolean = this != null && this.generation == generation && handshakeAt != null
        // B's sample can land before B's CONNECTED: the current value already decides.
        if (stats.value.fromThisTunnel()) { emit(VERIFIED); awaitCancellation() }
        emit(PENDING)
        val verifiedWithinWait = withTimeoutOrNull(waitMillis) {
            stats.drop(1).first { it.fromThisTunnel() }
        } != null
        if (verifiedWithinWait) { emit(VERIFIED); awaitCancellation() }
        // U3's settle rule, unchanged: one more stats update, or settleMillis with none.
        withTimeoutOrNull(settleMillis) { stats.drop(1).first() }
        if (stats.value.fromThisTunnel()) { emit(VERIFIED); awaitCancellation() }
        emit(WAITING)
        stats.first { it.fromThisTunnel() }
        emit(VERIFIED)
    }
    .distinctUntilChanged()
