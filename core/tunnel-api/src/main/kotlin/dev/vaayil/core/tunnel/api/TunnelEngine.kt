package dev.vaayil.core.tunnel.api

import dev.vaayil.core.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface TunnelEngine {
    val status: StateFlow<TunnelStatus>
    val stats: StateFlow<TunnelStats>
    suspend fun start(profile: Profile)
    suspend fun stop()
}

class FakeTunnelEngine(@Suppress("unused") private val scope: CoroutineScope) : TunnelEngine {
    private val _status = MutableStateFlow(TunnelStatus.idle)
    private val _stats = MutableStateFlow(TunnelStats.empty)
    override val status: StateFlow<TunnelStatus> = _status.asStateFlow()
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()
    var failNextStart: TunnelError? = null

    override suspend fun start(profile: Profile) {
        failNextStart?.let { _status.value = TunnelStatus(ConnectionPhase.FAILED, profile.id, it); return }
        _status.value = TunnelStatus(ConnectionPhase.CONNECTED, profile.id, null)
        _stats.value = TunnelStats(System.currentTimeMillis(), 0, 0)
    }

    override suspend fun stop() {
        _status.value = TunnelStatus.idle
        _stats.value = TunnelStats.empty
    }
}
