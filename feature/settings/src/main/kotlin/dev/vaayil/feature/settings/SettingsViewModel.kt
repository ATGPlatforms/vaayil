package dev.vaayil.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vaayil.core.data.generateExternalControlToken
import dev.vaayil.core.model.AutoConnectRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsFlags(val onboardingDone: Boolean = false)

data class AutomationUiState(
    val rules: AutoConnectRules = AutoConnectRules(),
    val permissionState: PermissionState = PermissionState.UNKNOWN,
)

class SettingsViewModel(
    flags: Flow<SettingsFlags>,
    automation: Flow<AutoConnectRules>,
    private val setFlag: suspend (String, Boolean) -> Unit,
    private val mutateRules: suspend (suspend (AutoConnectRules) -> AutoConnectRules) -> Unit,
) : ViewModel() {
    val ui: StateFlow<SettingsFlags> =
        flags.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsFlags())

    private val permissionState = MutableStateFlow(PermissionState.UNKNOWN)

    val automationUi: StateFlow<AutomationUiState> =
        combine(automation, permissionState) { rules, permission ->
            AutomationUiState(rules, permission)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AutomationUiState())

    fun completeOnboarding() {
        viewModelScope.launch { setFlag("onboardingDone", true) }
    }

    /** The route writes the location-permission verdict here (launcher callback / entry check). */
    fun onPermissionResult(state: PermissionState) {
        permissionState.value = state
    }

    /**
     * Route-entry recompute: the platform verdict, verbatim. Every caller feeds
     * `permissionStateFor(context)`, which never returns UNKNOWN, so a rotation cannot
     * clobber a prompt verdict any more (the U2 reason for the old upgrade-only rule). The
     * value differs from the last one only when the permission genuinely changed in system
     * settings — in either direction — which is exactly what the card must show.
     */
    fun onPermissionEntry(state: PermissionState) {
        permissionState.value = state
    }

    fun setWifiGuard(enabled: Boolean) = mutateRulesAsync { it.copy(wifiGuardEnabled = enabled) }

    fun setAutoConnectOnMobileData(enabled: Boolean) =
        mutateRulesAsync { it.copy(autoConnectOnMobileData = enabled) }

    fun setDisconnectOnTrusted(enabled: Boolean) = mutateRulesAsync { it.copy(disconnectOnTrusted = enabled) }

    fun setConnectOnBoot(enabled: Boolean) = mutateRulesAsync { it.copy(connectOnBoot = enabled) }

    /**
     * Enabling mints a token exactly once: turning the toggle off keeps it, so automations
     * that already carry it survive a user who merely paused external control.
     */
    fun setExternalControl(enabled: Boolean) = mutateRulesAsync {
        if (enabled) {
            it.copy(
                externalControlEnabled = true,
                externalControlToken = it.externalControlToken ?: generateExternalControlToken(),
            )
        } else {
            it.copy(externalControlEnabled = false)
        }
    }

    fun regenerateExternalToken() = mutateRulesAsync { it.copy(externalControlToken = generateExternalControlToken()) }

    /** Trims and blank-guards to mirror AutomationRulesStore.addTrustedSsid's semantics. */
    fun addTrustedSsid(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty()) return
        mutateRulesAsync { it.copy(trustedSsids = it.trustedSsids + trimmed) }
    }

    fun removeTrustedSsid(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty()) return
        mutateRulesAsync { it.copy(trustedSsids = it.trustedSsids - trimmed) }
    }

    private fun mutateRulesAsync(transform: suspend (AutoConnectRules) -> AutoConnectRules) {
        viewModelScope.launch { mutateRules(transform) }
    }
}
