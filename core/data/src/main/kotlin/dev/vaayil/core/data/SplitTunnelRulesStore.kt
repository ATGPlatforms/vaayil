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
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.model.SplitTunnelSelections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.map

private const val TAG = "SplitTunnelRulesStore"

// Own file: DataStore singletons are per-file per-process, so split-tunnel rules must not
// share ActiveProfileStore's or AutomationRulesStore's delegates.
// B5: an unparsable file resets to defaults instead of crash-looping every process start.
private val Context.splitTunnelDataStore by preferencesDataStore(
    name = "vaayil_split_tunnel",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w(TAG, "Corrupt preferences file reset to defaults")
        emptyPreferences()
    },
)

/**
 * The app-wide split-tunnel rules. Rules are global, not per profile: they apply to
 * whichever profile is connected. Per-profile storage ended with the pre-global schema;
 * its dormant columns are read once by [rescueLegacySplitTunnelRules] to seed this store
 * and are then dropped by MIGRATION_2_3 (S2). Both modes' curated lists persist, so
 * switching modes never drops the other list (known-issue P2).
 */
class SplitTunnelRulesStore(private val context: Context) {
    private val modeKey = stringPreferencesKey("split_mode")
    private val excludedKey = stringSetPreferencesKey("split_excluded")
    private val includedKey = stringSetPreferencesKey("split_included")
    private val allowLocalNetworkKey = booleanPreferencesKey("split_allow_local_network")
    // Pre-dual-set era: read once for migration, never written.
    private val legacyModeKey = stringPreferencesKey("split_tunnel_mode")
    private val legacyPackagesKey = stringSetPreferencesKey("split_tunnel_packages")

    val selections: Flow<SplitTunnelSelections> =
        context.splitTunnelDataStore.data.map { it.toSelections() }

    val rules: Flow<SplitTunnelPolicy> = selections.map { it.active }

    suspend fun snapshotSelections(): SplitTunnelSelections = selections.first()

    /** Active mode+set; EngineProvider's injected splitTunnelRules lambda depends on it. */
    suspend fun snapshot(): SplitTunnelPolicy = rules.first()

    suspend fun setSelections(selections: SplitTunnelSelections) {
        context.splitTunnelDataStore.edit { prefs ->
            prefs[modeKey] = selections.mode.name
            prefs[excludedKey] = selections.excluded
            prefs[includedKey] = selections.included
            prefs[allowLocalNetworkKey] = selections.allowLocalNetwork
            prefs.remove(legacyModeKey)
            prefs.remove(legacyPackagesKey)
        }
    }

    /**
     * Writes the policy's set into its mode's slot, preserving the other mode's slot. It stores the
     * policy's Allow local network flag too, so a policy built without the flag turns the switch off.
     */
    suspend fun set(policy: SplitTunnelPolicy) {
        // Mode first, THEN the slot pick: withActive-before-copy would file the set
        // under the old mode and flip the mode out from under it.
        setSelections(
            snapshotSelections()
                .copy(mode = policy.mode, allowLocalNetwork = policy.allowLocalNetwork)
                .withActive(policy.packages),
        )
    }

    /** Absence means the store was never written — the window [rescueLegacySplitTunnelRules] closes.
        Legacy-era stores count too, so their data migrates by reads instead of a re-seed. */
    suspend fun hasStoredValue(): Boolean =
        context.splitTunnelDataStore.data.first().let {
            it[modeKey] != null || it[legacyModeKey] != null
        }

    /** Test seeding only: empties the store so promotion paths can be exercised fresh (Robolectric
        shares the DataStore file across test methods in a sandbox). */
    internal suspend fun clear() = context.splitTunnelDataStore.edit { it.clear() }

    /** Test seeding only: writes pre-dual-set keys to exercise the read migration. */
    internal suspend fun seedLegacy(mode: SplitTunnelMode, packages: Set<String>) {
        context.splitTunnelDataStore.edit { prefs ->
            prefs[legacyModeKey] = mode.name
            prefs[legacyPackagesKey] = packages
        }
    }

    private fun Preferences.toSelections(): SplitTunnelSelections {
        val mode = (this[modeKey] ?: this[legacyModeKey])
            ?.let { runCatching { SplitTunnelMode.valueOf(it) }.getOrNull() }
            ?: SplitTunnelMode.EXCLUDE
        val allowLocalNetwork = this[allowLocalNetworkKey] ?: false
        val excluded = this[excludedKey]
        val included = this[includedKey]
        if (excluded == null && included == null) {
            // Legacy single-set era: the stored set belongs to the stored mode's slot.
            val packages = this[legacyPackagesKey] ?: emptySet()
            return if (mode == SplitTunnelMode.EXCLUDE) {
                SplitTunnelSelections(mode, excluded = packages, allowLocalNetwork = allowLocalNetwork)
            } else {
                SplitTunnelSelections(mode, included = packages, allowLocalNetwork = allowLocalNetwork)
            }
        }
        return SplitTunnelSelections(
            mode, excluded.orEmpty(), included.orEmpty(), allowLocalNetwork = allowLocalNetwork)
    }
}

/**
 * One-time upgrade from the per-profile era, run synchronously in Application.onCreate —
 * strictly BEFORE Room opens/migrates the database (MIGRATION_2_3 drops the dormant
 * columns this reads). Seeds the global store straight from the SQLite file: the active
 * profile first, then the last connected, then the newest row. The write seals the
 * store, so this never runs twice; missing columns (fresh install, already-migrated db)
 * and missing rows are no-ops. Reads raw because the Room entity no longer carries the
 * dormant columns.
 */
suspend fun rescueLegacySplitTunnelRules(
    store: SplitTunnelRulesStore,
    context: Context,
    active: ActiveProfileStore,
) {
    if (store.hasStoredValue()) return
    val db = context.openOrCreateDatabase("vaayil.db", Context.MODE_PRIVATE, null)
    try {
        val columns = mutableSetOf<String>()
        val pragmaCursor = db.rawQuery("PRAGMA table_info(profiles)", null)
        pragmaCursor.use { c ->
            while (c.moveToNext()) columns += c.getString(1)
        }
        if (!columns.contains("splitMode")) return
        val preferredId = active.activeId.first() ?: active.lastConnectedId.first()
        val selectionArgs = arrayOf<String?>(preferredId)
        val rowCursor = db.rawQuery(
            "SELECT splitMode, splitPackages FROM profiles " +
                "ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, updatedAt DESC LIMIT 1",
            selectionArgs,
        )
        rowCursor.use { c ->
            if (!c.moveToFirst()) return
            val mode = runCatching { SplitTunnelMode.valueOf(c.getString(0)) }.getOrNull() ?: return
            val packagesJson = c.getString(1) ?: return
            val packages = runCatching {
                Json.decodeFromString(SetSerializer(String.serializer()), packagesJson)
            }.getOrNull() ?: return
            store.set(SplitTunnelPolicy(mode, packages))
        }
    } finally {
        db.close()
    }
}
