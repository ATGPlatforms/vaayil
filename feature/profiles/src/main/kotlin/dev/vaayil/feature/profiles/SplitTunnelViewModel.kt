package dev.vaayil.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelSelections
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SplitTunnelUiState(
    val mode: SplitTunnelMode = SplitTunnelMode.EXCLUDE,
    val selectedPackages: Set<String> = emptySet(),
    val apps: List<AppInfo> = emptyList(),
    val query: String = "",
    val showSystem: Boolean = false,
    val includeListEmpty: Boolean = false,
    val allowLocalNetwork: Boolean = false,
    val loading: Boolean = false,
)

/**
 * The Split Tunnel tab edits the app-wide rules: one policy applies to whichever profile
 * is connected. Edits save with a debounce and apply immediately when a tunnel is up
 * (the save lambda routes through the service-restart path in :app). Both modes' curated
 * lists persist in the store, so switching modes never drops the other list (P2).
 */
class SplitTunnelViewModel(
    selections: Flow<SplitTunnelSelections>,
    private val save: suspend (SplitTunnelSelections) -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(SplitTunnelUiState(loading = true))
    val state: MutableStateFlow<SplitTunnelUiState> = _state

    private var allApps: List<AppInfo> = emptyList()
    private var saveJob: Job? = null

    // The full dual-mode selections; the store is the source of truth and echoes land here.
    private var current = SplitTunnelSelections.INITIAL

    // The last echo the store sent. Comparing an echo with the previous one, the switch aside,
    // tells a flag-only write apart from any other change, so such an echo never wipes an
    // unsaved or refused app edit.
    private var stored: SplitTunnelSelections? = null

    init {
        viewModelScope.launch {
            selections.collect { sel ->
                val previous = stored
                stored = sel
                if (previous != null && previous.copy(allowLocalNetwork = sel.allowLocalNetwork) == sel) {
                    // The app rules did not move, so this echo can only carry the local-network
                    // switch: keep the app edit the tab is showing, saved or refused.
                    current = current.copy(allowLocalNetwork = sel.allowLocalNetwork)
                    _state.value = _state.value.copy(allowLocalNetwork = sel.allowLocalNetwork)
                } else {
                    current = sel
                    _state.value = _state.value.copy(
                        mode = sel.mode,
                        selectedPackages = sel.active.packages,
                        // Derived from what the store now holds, not only from refused edits: a
                        // restore can write an inclusive policy with an empty list, and the engine
                        // applies no restriction for it (S2), so the tab must say why.
                        includeListEmpty =
                            sel.mode == SplitTunnelMode.INCLUDE && sel.included.isEmpty(),
                        allowLocalNetwork = sel.allowLocalNetwork,
                    )
                }
            }
        }
    }

    /** Apps come from :app (InstalledAppsSource); query and toggles survive reloads. */
    fun load(apps: List<AppInfo>) {
        allApps = apps
        _state.value = _state.value.copy(apps = visibleApps(apps, _state.value.query), loading = false)
    }

    fun setMode(mode: SplitTunnelMode) {
        if (mode == _state.value.mode) return
        current = current.copy(mode = mode)
        _state.value = _state.value.copy(mode = mode, selectedPackages = current.active.packages)
        scheduleSave()
    }

    fun togglePackage(pkg: String) {
        val selected = _state.value.selectedPackages
        val updated = if (pkg in selected) selected - pkg else selected + pkg
        setSelection(updated)
    }

    /** The switch is independent of the app rules, so a refused app edit never blocks it. */
    fun setAllowLocalNetwork(allow: Boolean) {
        current = current.copy(allowLocalNetwork = allow)
        _state.value = _state.value.copy(allowLocalNetwork = allow)
        scheduleSave()
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query, apps = visibleApps(allApps, query))
    }

    fun setShowSystem(show: Boolean) {
        _state.value = _state.value.copy(showSystem = show)
    }

    /** Everything travels through the tunnel. */
    fun routeAll() = setSelection(
        if (_state.value.mode == SplitTunnelMode.INCLUDE) allPackages() else emptySet(),
    )

    /** Nothing travels through the tunnel. */
    fun bypassAll() = setSelection(
        if (_state.value.mode == SplitTunnelMode.EXCLUDE) allPackages() else emptySet(),
    )

    private fun allPackages(): Set<String> = allApps.map { it.packageName }.toSet()

    private fun setSelection(selected: Set<String>) {
        current = current.withActive(selected)
        _state.value = _state.value.copy(selectedPackages = selected)
        scheduleSave()
    }

    private fun scheduleSave() {
        val toSave = current
        saveJob?.cancel()
        if (toSave.mode == SplitTunnelMode.INCLUDE && toSave.included.isEmpty()) {
            // Refused (S2): an inclusive policy with nothing in it is never persisted, so the
            // previously saved rules stay in force and the tab shows why. Cancelling first also
            // discards an edit still inside the debounce window, which the user has superseded.
            _state.value = _state.value.copy(includeListEmpty = true)
            val storedRules = stored
            if (storedRules != null && toSave.allowLocalNetwork != storedRules.allowLocalNetwork) {
                // The switch does not depend on the app rules, so it still saves, with the rules
                // the store last accepted: the same debounced path as any other rule change.
                saveJob = viewModelScope.launch {
                    delay(500)
                    save(storedRules.copy(allowLocalNetwork = toSave.allowLocalNetwork))
                }
            }
            return
        }
        _state.value = _state.value.copy(includeListEmpty = false)
        saveJob = viewModelScope.launch {
            delay(500)
            save(toSave)
        }
    }

    private fun visibleApps(apps: List<AppInfo>, query: String): List<AppInfo> {
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }
}
