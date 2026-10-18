package dev.vaayil.core.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

// B5: an unparsable file resets to defaults instead of crash-looping every process start.
private val Context.dataStore by preferencesDataStore(
    name = "vaayil_settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w(TAG, "Corrupt preferences file reset to defaults")
        emptyPreferences()
    },
)

private const val TAG = "ActiveProfileStore"

/** What a fresh process does with the ACTIVE record it inherits (F32). */
internal enum class ActiveRecordFate { NONE, CLEAR, RESUME }

/**
 * ACTIVE names a profile only from a STARTED connect until the disconnect, revoke or stale-id refusal that clears it,
 * so a record stamped with THIS boot means the previous process died with that tunnel up: resume it. A record from an
 * earlier boot, or one an older build wrote without a stamp, is cleared without a resume — reconnecting after a reboot
 * is connect-on-boot's decision alone. A boot that cannot be read never proves "same boot". A same-boot record whose
 * profile was resumed too recently ([resumedRecently], the F32 loop-breaker) is also cleared instead of resumed.
 */
internal fun activeRecordFate(activeId: String?, stampedBoot: Int?, currentBoot: Int?, resumedRecently: Boolean = false): ActiveRecordFate = when {
    activeId == null -> ActiveRecordFate.NONE
    stampedBoot != null && stampedBoot == currentBoot && !resumedRecently -> ActiveRecordFate.RESUME
    else -> ActiveRecordFate.CLEAR
}

/** F32: a second resume of the same profile this soon after the last one, in the same boot, is cleared instead. */
internal const val RESUME_COOLDOWN_MS = 60_000L

/** True when the last resume named [activeId] in [currentBoot] less than [RESUME_COOLDOWN_MS] before [now]. */
internal fun resumedRecently(markerId: String?, markerBoot: Int?, markerAt: Long?, activeId: String?, currentBoot: Int?, now: Long): Boolean =
    activeId != null && markerId == activeId && currentBoot != null && markerBoot == currentBoot &&
        markerAt != null && now - markerAt in 0 until RESUME_COOLDOWN_MS

/** Settings.Global.BOOT_COUNT (API 24+): one value for every process of a boot, bumped by each reboot. Null when unreadable. */
internal fun systemBootCount(context: Context): Int? =
    runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()

/**
 * J1: the outcome of the once-per-boot claim behind connect-on-boot. Android 15+ also delivers
 * BOOT_COMPLETED when user action takes a force-stopped app out of its stopped state, so only a
 * durable claim can tell a reboot from an unstop. NEW_BOOT admits; SAME_BOOT means this boot
 * already handled a boot broadcast; UNKNOWN means the boot could not be read and connect-on-boot
 * runs as before (fail open, the smaller harm).
 */
enum class BootClaim { NEW_BOOT, SAME_BOOT, UNKNOWN }

class ActiveProfileStore(
    private val context: Context,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    /** The current boot's identity: [systemBootCount] in production, a fixed value in tests. */
    private val bootCount: () -> Int? = { systemBootCount(context) },
) {
    private val key = stringPreferencesKey("active_profile_id")
    private val lastConnectedKey = stringPreferencesKey("last_connected_profile_id")

    /** The boot [key] was written in (F32): lets a fresh process tell a crash from a reboot. */
    private val activeBootKey = intPreferencesKey("active_profile_boot")

    /** The resume marker (F32 loop-breaker): the id, boot and clock of the last resume issued. */
    private val resumeProfileKey = stringPreferencesKey("resume_profile_id")
    private val resumeBootKey = intPreferencesKey("resume_boot")
    private val resumeAtKey = longPreferencesKey("resume_at")

    /** J1: the last boot whose BOOT_COMPLETED a receiver handled; the connect-on-boot admission stamp. */
    private val bootHandledKey = intPreferencesKey("boot_connect_handled_boot")

    val activeId: Flow<String?> = context.dataStore.data.map { it[key] }

    /**
     * Writes or clears ACTIVE together with the boot it was written in; clearing drops the stamp and the resume
     * marker (F32) too. Writing a DIFFERENT profile than the marker's resets the marker — a switch or a
     * disconnect restarts the loop-breaker — while the resumed connect's own STARTED write (same id) keeps it.
     */
    suspend fun setActive(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) {
                prefs.remove(key)
                prefs.remove(activeBootKey)
                prefs.remove(resumeProfileKey)
                prefs.remove(resumeBootKey)
                prefs.remove(resumeAtKey)
            } else {
                // Read inside the edit transform (F32/D): DataStore queues edits in call order, so a clear
                // issued after this write can never be applied first and leave a stamped orphan record.
                val boot = withContext(Dispatchers.IO) { bootCount() }
                prefs[key] = id
                if (boot != null) prefs[activeBootKey] = boot else prefs.remove(activeBootKey)
                if (prefs[resumeProfileKey] != id) {
                    prefs.remove(resumeProfileKey)
                    prefs.remove(resumeBootKey)
                    prefs.remove(resumeAtKey)
                }
            }
        }
    }

    /**
     * F32: hands a fresh process the profile whose tunnel died with the previous process, at most once. ACTIVE is
     * read and cleared in one edit, so a concurrent STARTED write is never erased by a stale read. Only a record
     * stamped with this boot is returned; any other is cleared without a resume, and lastConnected is never touched.
     * Clearing BEFORE the caller issues the resume makes it one-shot: STARTED writes ACTIVE again
     * (EngineProvider.connectById), so a resume whose process dies before STARTED leaves nothing to take.
     * The boot and clock are read inside the edit, and a RESUME also writes the loop-breaker marker (id, boot,
     * clock), so a same-boot resume of the same profile within [RESUME_COOLDOWN_MS] of the last one is cleared
     * instead — a session that crashes right after every start cannot loop. NONE and CLEAR leave the marker alone.
     * When the loop-breaker alone turned a resume into a clear, one WARN line names the profile id and the seconds
     * since the last resume (K7); it is logged after the edit is written.
     */
    suspend fun takeResumableActive(): String? {
        var resume: String? = null
        var skipped: Pair<String, Long>? = null
        context.dataStore.edit { prefs ->
            val boot = withContext(Dispatchers.IO) { bootCount() }
            val now = elapsedRealtime()
            val id = prefs[key]
            val stamp = prefs[activeBootKey]
            val recent = resumedRecently(prefs[resumeProfileKey], prefs[resumeBootKey], prefs[resumeAtKey], id, boot, now)
            val fate = activeRecordFate(id, stamp, boot, recent)
            if (fate == ActiveRecordFate.NONE) return@edit
            if (fate == ActiveRecordFate.RESUME) {
                resume = id
                if (id != null) prefs[resumeProfileKey] = id
                if (boot != null) prefs[resumeBootKey] = boot
                prefs[resumeAtKey] = now
            } else if (id != null && activeRecordFate(id, stamp, boot) == ActiveRecordFate.RESUME) {
                // Without the loop-breaker this record would have been resumed.
                skipped = id to now - (prefs[resumeAtKey] ?: now)
            }
            prefs.remove(key)
            prefs.remove(activeBootKey)
        }
        skipped?.let { (id, sinceMs) ->
            Log.w(TAG, "F32 loop-breaker: resume of $id skipped, last resume ${sinceMs / 1000} s ago")
        }
        return resume
    }

    /**
     * J1: claims the current boot for connect-on-boot, at most once per boot. One edit reads
     * [bootCount] on IO (as [takeResumableActive] does) and stamps `boot_connect_handled_boot`
     * with it BEFORE any target lookup or connect attempt: a boot's first BOOT_COMPLETED returns
     * NEW_BOOT and every later broadcast in the same boot — a new receiver or store instance
     * included — returns SAME_BOOT. The claim is a boot decision, not a per-successful-tunnel one:
     * a duplicate never retries a refused or missing-consent connect. The stamp is independent of
     * ACTIVE, LAST and the resume cooldown — disconnect, deletion, restore and rule toggles leave
     * it alone — and nothing but this method reads or clears it. A null or negative boot returns
     * UNKNOWN and writes nothing (never a substitute value), and an edit that throws propagates;
     * both keep today's fail-open behaviour. DataStore serializes edits, so concurrent claims have
     * exactly one NEW_BOOT.
     */
    suspend fun claimBoot(): BootClaim {
        var claim: BootClaim? = null
        context.dataStore.edit { prefs ->
            val boot = withContext(Dispatchers.IO) { bootCount() }
            if (boot == null || boot < 0) return@edit
            claim = if (prefs[bootHandledKey] == boot) BootClaim.SAME_BOOT else BootClaim.NEW_BOOT
            prefs[bootHandledKey] = boot
        }
        return claim ?: BootClaim.UNKNOWN
    }

    /**
     * Last profile a connect was initiated for. Unlike [activeId] — which teardown clears on
     * every disconnect (Phase-1 fail-closed) — no disconnect clears this key, so the Wi-Fi guard
     * and boot path can reconnect after any disconnect. Only [reconcileProfilePointers] removes
     * it, and only while no stored row carries that id (B3).
     */
    val lastConnectedId: Flow<String?> = context.dataStore.data.map { it[lastConnectedKey] }

    /** Null (removal) exists for test seeding and [reconcileProfilePointers]. */
    suspend fun setLastConnected(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(lastConnectedKey) else prefs[lastConnectedKey] = id
        }
    }

    /**
     * B3: reconciles both pointers with the profiles that still exist and reports what survived,
     * ACTIVE first. Both values are read inside the one edit — never from an earlier read, which a
     * concurrent write could have made stale — and only a pointer whose id no stored row carries is
     * cleared: the profile was deleted, or re-imported under a new id. A pointer to a row that
     * merely cannot be decrypted right now is kept; the connect path re-acquires the key itself
     * (F13). Clearing ACTIVE drops its boot stamp and resume marker exactly as `setActive(null)`
     * does, while a pointer that survives keeps its metadata.
     */
    suspend fun reconcileProfilePointers(storedIds: Set<String>): Pair<String?, String?> {
        var active: String? = null
        var lastConnected: String? = null
        context.dataStore.edit { prefs ->
            prefs[key]?.let { id ->
                if (id in storedIds) {
                    active = id
                } else {
                    prefs.remove(key)
                    prefs.remove(activeBootKey)
                    prefs.remove(resumeProfileKey)
                    prefs.remove(resumeBootKey)
                    prefs.remove(resumeAtKey)
                }
            }
            prefs[lastConnectedKey]?.let { id ->
                if (id in storedIds) {
                    lastConnected = id
                } else {
                    prefs.remove(lastConnectedKey)
                }
            }
        }
        return active to lastConnected
    }

    /** Used by the Always-on boot path where no coroutine scope exists yet. */
    fun getActiveBlocking(): String? = runBlocking { activeId.first() }

    /** Used by the Always-on boot path where no coroutine scope exists yet. */
    fun getLastConnectedBlocking(): String? = runBlocking { lastConnectedId.first() }
}
