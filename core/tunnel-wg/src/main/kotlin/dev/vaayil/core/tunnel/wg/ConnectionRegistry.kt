package dev.vaayil.core.tunnel.wg

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelEngine
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.handshakeState
import dev.vaayil.core.tunnel.api.isLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * K3: a connect to [targetName] that did not start while the tunnel of a different profile, [liveName], stayed
 * live. [unresolved]: the start failed with DnsFailure — F33's switch pre-check refused it, or GoBackend's own
 * DNS_RESOLUTION_FAILURE did (C1) — so the wording is true by construction.
 */
data class SwitchFailure(val targetName: String, val liveName: String, val unresolved: Boolean) {
    /** The snackbar text, verbatim from the K3 spec. */
    val message: String
        get() = if (unresolved) {
            "Couldn't switch to $targetName: its server name didn't resolve. Still connected to $liveName."
        } else {
            "Couldn't switch to $targetName. Still connected to $liveName."
        }
}

/**
 * C2: a restart of [profileName] that could not apply its new settings; the session kept running the
 * previous ones. [reason] names the cause without config text.
 */
data class RestartRefusal(val profileName: String, val reason: String) {
    /** The snackbar text, verbatim from the sweep-2 spec. */
    val message: String
        get() = "Couldn't apply the new settings to $profileName: $reason. It's still running the previous ones."
}

object ConnectionRegistry {
    val status = MutableStateFlow(TunnelStatus.idle)
    val stats = MutableStateFlow(TunnelStats.empty)
    /** G4/J13: the live tunnel's handshake state, computed once for the process from [status] and [stats] by [bridge]. NONE until a bridge runs. */
    val handshake = MutableStateFlow(HandshakeState.NONE)

    private var bridgeJob: Job? = null

    private val _switchFailures = MutableSharedFlow<SwitchFailure>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * K3: one event per switch that failed while another profile's tunnel stayed live, for whichever UI is
     * collecting at that moment. Never replayed: a screen that starts collecting later never shows an old
     * failure, and with nobody collecting the event is dropped.
     */
    val switchFailures: SharedFlow<SwitchFailure> = _switchFailures.asSharedFlow()

    /** Called from VpnTunnelService's settle callback; never suspends (a full buffer drops the oldest event). */
    fun reportSwitchFailure(failure: SwitchFailure) {
        _switchFailures.tryEmit(failure)
    }

    private val _restartRefusals = MutableSharedFlow<RestartRefusal>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * C2: one event per same-profile restart that was refused while its old session stayed live. Like
     * [switchFailures]: buffered, never replayed, dropped when nobody collects.
     */
    val restartRefusals: SharedFlow<RestartRefusal> = _restartRefusals.asSharedFlow()

    /** Called from VpnTunnelService's settle callback; never suspends (a full buffer drops the oldest event). */
    fun reportRestartRefusal(refusal: RestartRefusal) {
        _restartRefusals.tryEmit(refusal)
    }

    /** Marks the registry DISCONNECTED when it still claims a live tunnel; no-op otherwise. */
    fun resetIfLive() {
        if (status.value.phase.isLive) {
            status.value = TunnelStatus(ConnectionPhase.DISCONNECTED, null, null)
        }
    }

    /**
     * Mirrors [engine] into the registry for the process lifetime (F7/F5). The bridge used to live on
     * each VpnTunnelService instance, and GoBackend stopSelf()s that instance on every tunnel-DOWN —
     * including the one inside a switch — so the registry froze at DISCONNECTED while the new tunnel
     * came up, and a published failure (FAILED with DnsResolverFailure) never arrived. A second call
     * replaces the first bridge.
     */
    @Synchronized
    fun bridge(engine: TunnelEngine, scope: CoroutineScope) {
        bridgeJob?.cancel()
        bridgeJob = scope.launch {
            launch { engine.status.collect { status.value = it } }
            launch { engine.stats.collect { stats.value = it } }
            launch { handshakeState(status, stats).collect { handshake.value = it } }
        }
    }
}
