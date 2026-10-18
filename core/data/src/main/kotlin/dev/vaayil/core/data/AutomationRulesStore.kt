package dev.vaayil.core.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.vaayil.core.model.AutoConnectRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TAG = "AutomationRulesStore"

// Own file: DataStore singletons are per-file per-process, so automation rules must not
// share ActiveProfileStore's "vaayil_settings" delegate.
// B5: an unparsable file resets to defaults instead of crash-looping every process start.
private val Context.automationDataStore by preferencesDataStore(
    name = "vaayil_automation",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w(TAG, "Corrupt preferences file reset to defaults")
        emptyPreferences()
    },
)

class AutomationRulesStore(private val context: Context) {
    private val wifiGuardKey = booleanPreferencesKey("wifiGuardEnabled")
    private val trustedSsidsKey = stringSetPreferencesKey("trustedSsids")
    private val disconnectOnTrustedKey = booleanPreferencesKey("disconnectOnTrusted")
    private val connectOnBootKey = booleanPreferencesKey("connectOnBoot")
    private val autoConnectOnMobileDataKey = booleanPreferencesKey("autoConnectOnMobileData")
    private val externalControlKey = booleanPreferencesKey("externalControlEnabled")
    private val externalControlTokenKey = stringPreferencesKey("externalControlToken")

    val rules: Flow<AutoConnectRules> = context.automationDataStore.data.map { it.toRules() }

    suspend fun snapshot(): AutoConnectRules = rules.first()

    suspend fun setWifiGuard(enabled: Boolean) = mutate { it.copy(wifiGuardEnabled = enabled) }

    suspend fun setAutoConnectOnMobileData(enabled: Boolean) =
        mutate { it.copy(autoConnectOnMobileData = enabled) }

    suspend fun setDisconnectOnTrusted(enabled: Boolean) = mutate { it.copy(disconnectOnTrusted = enabled) }

    suspend fun setConnectOnBoot(enabled: Boolean) = mutate { it.copy(connectOnBoot = enabled) }

    suspend fun addTrustedSsid(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty()) return
        mutate { it.copy(trustedSsids = it.trustedSsids + trimmed) }
    }

    suspend fun removeTrustedSsid(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty()) return
        mutate { it.copy(trustedSsids = it.trustedSsids - trimmed) }
    }

    /** Single read-edit-write transaction; the settings VM routes every setter through this. */
    suspend fun mutate(transform: suspend (AutoConnectRules) -> AutoConnectRules) {
        context.automationDataStore.edit { prefs ->
            val next = transform(prefs.toRules())
            prefs[wifiGuardKey] = next.wifiGuardEnabled
            prefs[autoConnectOnMobileDataKey] = next.autoConnectOnMobileData
            prefs[disconnectOnTrustedKey] = next.disconnectOnTrusted
            prefs[connectOnBootKey] = next.connectOnBoot
            prefs[trustedSsidsKey] = next.trustedSsids
            prefs[externalControlKey] = next.externalControlEnabled
            val token = next.externalControlToken
            if (token == null) prefs.remove(externalControlTokenKey) else prefs[externalControlTokenKey] = token
        }
    }

    private fun Preferences.toRules(): AutoConnectRules = AutoConnectRules(
        wifiGuardEnabled = this[wifiGuardKey] ?: false,
        trustedSsids = this[trustedSsidsKey] ?: emptySet(),
        disconnectOnTrusted = this[disconnectOnTrustedKey] ?: false,
        connectOnBoot = this[connectOnBootKey] ?: false,
        autoConnectOnMobileData = this[autoConnectOnMobileDataKey] ?: false,
        externalControlEnabled = this[externalControlKey] ?: false,
        externalControlToken = this[externalControlTokenKey],
    )
}
