package dev.vaayil.feature.tunnel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.TunnelStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val status: TunnelStatus = TunnelStatus.idle,
    val stats: TunnelStats = TunnelStats.empty,
    val activeProfile: Profile? = null,
    val profiles: List<Profile> = emptyList(),
    val lastConnectedId: String? = null,
    val handshakeState: HandshakeState = HandshakeState.NONE,
    /** T-8 (spec §3.2): the hostname Android's Private DNS is set to, from the app's watcher. */
    val privateDnsHost: String? = null,
    /** T-8 (spec §3.2): whether the VPN network itself lists a DNS server. */
    val vpnHasDnsServer: Boolean = false,
    /** T-8 (spec §3.2): the hostname "Got it" hid; the card stays away for it. */
    val privateDnsDismissedHost: String? = null,
)

class HomeViewModel(
    status: Flow<TunnelStatus>,
    stats: Flow<TunnelStats>,
    active: Flow<String?>,
    profiles: Flow<List<Profile>>,
    private val controller: TunnelController,
    lastConnected: Flow<String?>,
    handshakeState: Flow<HandshakeState> = flowOf(HandshakeState.NONE),
    privateDnsHost: Flow<String?> = flowOf(null),
    vpnHasDnsServer: Flow<Boolean> = flowOf(false),
    privateDnsDismissedHost: Flow<String?> = flowOf(null),
    private val saveDismissedPrivateDns: suspend (host: String) -> Unit = {},
) : ViewModel() {
    val ui: StateFlow<HomeUiState> =
        combine(
            combine(status, stats, active, profiles, lastConnected) { s, st, id, list, last ->
                HomeUiState(s, st, list.firstOrNull { it.id == id }, list, last)
            },
            handshakeState,
            privateDnsHost,
            vpnHasDnsServer,
            privateDnsDismissedHost,
        ) { state, hs, host, vpnDns, dismissed ->
            state.copy(
                handshakeState = hs,
                privateDnsHost = host,
                vpnHasDnsServer = vpnDns,
                privateDnsDismissedHost = dismissed,
            )
        }
            .stateIn(
                CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Unconfined),
                SharingStarted.Eagerly,
                HomeUiState(),
            )

    fun toggle(id: String? = null) {
        val now = ui.value
        val target = id ?: dashboardTarget(now.profiles, null, now.activeProfile?.id, now.lastConnectedId)?.id ?: return
        viewModelScope.launch { controller.toggle(target) }
    }

    fun disconnect() { viewModelScope.launch { controller.disconnect() } }

    /** T-8 (spec §3.2): "Got it" — the hostname is remembered as dismissed. */
    fun dismissPrivateDns(host: String) { viewModelScope.launch { saveDismissedPrivateDns(host) } }
}
