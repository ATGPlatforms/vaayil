package dev.vaayil.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vaayil.core.data.ProfilesHealth
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.isLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileListUiState(
    val items: List<Profile> = emptyList(),
    val activeId: String? = null,
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val health: ProfilesHealth = ProfilesHealth(atRestActive = true, unreadableIds = emptySet()),
    val handshakeState: HandshakeState = HandshakeState.NONE,
)

class ProfileListViewModel(
    profiles: Flow<List<Profile>>,
    health: Flow<ProfilesHealth>,
    status: StateFlow<TunnelStatus>,
    activeId: Flow<String?>,
    private val onSelectActive: suspend (String) -> Unit,
    private val onDelete: suspend (String) -> Unit,
    private val onDeleteUnreadable: suspend () -> Unit = {},
    private val onDuplicate: suspend (String) -> Unit = {},
    private val onDuplicateFailed: suspend (String) -> Unit = {},
    handshakeState: Flow<HandshakeState> = flowOf(HandshakeState.NONE),
) : ViewModel() {
    val ui: StateFlow<ProfileListUiState> =
        combine(profiles, status, activeId, health, handshakeState) { list, st, active, h, hs ->
            // The highlight follows the engine while it is live for a profile: the stored pointer
            // is written only after a successful start, so it lags a connect in flight and (after
            // a failed switch, which keeps the previous tunnel) it names the attempt, not the truth.
            ProfileListUiState(list, st.profileId?.takeIf { st.phase.isLive } ?: active, st.phase, h, hs)
        }.stateIn(
            CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Unconfined),
            SharingStarted.Eagerly,
            ProfileListUiState(),
        )
    fun toggleActive(id: String) { viewModelScope.launch { onSelectActive(id) } }
    fun delete(id: String) { viewModelScope.launch { onDelete(id) } }

    /**
     * Duplicate runs through save(), which validates and seals: a row that predates the current
     * rules, or an at-rest outage, comes back as a message here instead of tearing down this
     * scope (T-3). The message comes from [profileWriteMessage], never from the throwable (R6).
     */
    fun duplicate(id: String) {
        viewModelScope.launch {
            runCatching { onDuplicate(id) }.onFailure {
                onDuplicateFailed(profileWriteMessage(it, fallback = "Couldn't duplicate that profile."))
            }
        }
    }

    fun deleteUnreadable() { viewModelScope.launch { onDeleteUnreadable() } }
}
