package dev.vaayil.app.quick

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.vaayil.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

internal const val SHORTCUT_PREFIX = "connect:"
internal const val MAX_QUICK_SHORTCUTS = 4
internal const val SHORTCUT_DELETED_MESSAGE = "This profile was deleted."

/** One Connect shortcut: the trampoline intent and the profile's name. */
internal data class ShortcutSpec(val profileId: String, val name: String) {
    val id: String get() = SHORTCUT_PREFIX + profileId
}

/** A shortcut the launcher currently holds, as the sync reads it back. */
internal data class PublishedShortcut(val id: String, val label: String, val enabled: Boolean)

/**
 * What one sync writes. A null [dynamic] means the published list already matches; empty
 * update/enable/disable lists mean there is nothing to write for that kind.
 */
internal data class ShortcutPlan(
    val dynamic: List<ShortcutSpec>?,
    val update: List<ShortcutSpec>,
    val disable: List<String>,
    val enable: List<ShortcutSpec>,
)

/**
 * Reconciles the launcher's shortcuts with the profiles the app can read. At most four are
 * published, last-connected first and otherwise in the Profiles tab order. A pinned shortcut
 * whose row is gone is disabled; one whose row is stored but unreadable is left alone,
 * so a key outage never erases the launcher's pins.
 */
internal fun planShortcuts(
    readable: List<QuickProfile>,
    storedIds: Set<String>,
    lastConnectedId: String?,
    maxCount: Int,
    dynamic: List<PublishedShortcut>,
    pinned: List<PublishedShortcut>,
): ShortcutPlan {
    if (readable.isEmpty() && storedIds.isNotEmpty()) {
        return ShortcutPlan(null, emptyList(), emptyList(), emptyList())
    }

    val wanted = readable
        .sortedBy { if (it.id == lastConnectedId) 0 else 1 }
        .take(minOf(MAX_QUICK_SHORTCUTS, maxCount))
        .map { ShortcutSpec(it.id, it.name) }

    val published = dynamic.map { it.id to it.label }
    val disable = mutableListOf<String>()
    val enable = mutableListOf<ShortcutSpec>()
    val update = mutableListOf<ShortcutSpec>()
    for (shortcut in pinned) {
        if (!shortcut.id.startsWith(SHORTCUT_PREFIX)) continue
        val profileId = shortcut.id.removePrefix(SHORTCUT_PREFIX)
        if (profileId !in storedIds) {
            if (shortcut.enabled) disable += shortcut.id
            continue
        }
        val profile = readable.firstOrNull { it.id == profileId } ?: continue
        val spec = ShortcutSpec(profile.id, profile.name)
        if (!shortcut.enabled) enable += spec
        if (shortcut.label != profile.name) update += spec
    }

    return ShortcutPlan(
        dynamic = if (published == wanted.map { it.id to it.name }) null else wanted,
        update = update,
        disable = disable,
        enable = enable,
    )
}

/** The platform seam the planner writes through. */
internal interface ShortcutHost {
    fun maxCount(): Int
    fun dynamic(): List<PublishedShortcut>
    fun pinned(): List<PublishedShortcut>
    fun setDynamic(specs: List<ShortcutSpec>): Boolean
    fun update(specs: List<ShortcutSpec>): Boolean
    fun disable(ids: List<String>, message: String)
    fun enable(specs: List<ShortcutSpec>)
}

/** Publishes through [ShortcutManagerCompat]: dynamic and pinned shortcuts and the launcher's cap. */
internal class CompatShortcutHost(private val context: Context) : ShortcutHost {
    override fun maxCount(): Int = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)

    override fun dynamic(): List<PublishedShortcut> =
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
            .sortedBy { it.rank }
            .map(::published)

    override fun pinned(): List<PublishedShortcut> =
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .map(::published)

    override fun setDynamic(specs: List<ShortcutSpec>): Boolean =
        ShortcutManagerCompat.setDynamicShortcuts(context, infos(specs))

    override fun update(specs: List<ShortcutSpec>): Boolean =
        ShortcutManagerCompat.updateShortcuts(context, infos(specs))

    override fun disable(ids: List<String>, message: String) {
        ShortcutManagerCompat.disableShortcuts(context, ids, message)
    }

    override fun enable(specs: List<ShortcutSpec>) {
        ShortcutManagerCompat.enableShortcuts(context, infos(specs))
    }

    private fun infos(specs: List<ShortcutSpec>): List<ShortcutInfoCompat> =
        specs.mapIndexed { rank, spec -> info(spec, rank) }

    private fun info(spec: ShortcutSpec, rank: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, spec.id)
            .setShortLabel(spec.name)
            .setLongLabel("Connect ${spec.name}")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_connect))
            .setIntent(QuickShortcutActivity.intent(context, spec.profileId))
            .setRank(rank)
            .build()

    private fun published(shortcut: ShortcutInfoCompat) = PublishedShortcut(
        id = shortcut.id,
        label = shortcut.shortLabel?.toString().orEmpty(),
        enabled = shortcut.isEnabled,
    )
}

/**
 * One reconciliation per sync: reads the launcher, plans, then writes deletions, revivals,
 * renames and the published list in that order. False means the launcher refused a write or
 * threw; the next sync, including the one every app resume asks for, tries again.
 */
internal class QuickShortcutSync(private val host: ShortcutHost) {
    fun sync(readable: List<QuickProfile>, storedIds: Set<String>, lastConnectedId: String?): Boolean {
        val plan = try {
            planShortcuts(
                readable = readable,
                storedIds = storedIds,
                lastConnectedId = lastConnectedId,
                maxCount = host.maxCount(),
                dynamic = host.dynamic(),
                pinned = host.pinned(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "shortcut read failed: ${e.javaClass.simpleName}", e)
            return false
        }

        return try {
            if (plan.disable.isNotEmpty()) host.disable(plan.disable, SHORTCUT_DELETED_MESSAGE)
            if (plan.enable.isNotEmpty()) host.enable(plan.enable)
            if (plan.update.isNotEmpty() && !host.update(plan.update)) return false
            if (plan.dynamic != null && !host.setDynamic(plan.dynamic)) return false
            true
        } catch (e: Exception) {
            Log.w(TAG, "shortcut write failed: ${e.javaClass.simpleName}", e)
            false
        }
    }

    private companion object {
        const val TAG = "QuickShortcutSync"
    }
}

/**
 * Keeps the launcher's shortcuts on the hub's inputs: one sync per distinct readable set,
 * last-connected pointer and stored count, plus one per app resume so a refused publish is
 * retried. A read or write that throws is logged and never ends the collection.
 */
internal class ShortcutUpdater(
    private val sync: QuickShortcutSync,
    private val storedIds: suspend () -> Set<String>,
) {
    private val lock = Any()
    private var latest: QuickInputs? = null
    private var resumes: MutableSharedFlow<Unit>? = null

    fun launchIn(scope: CoroutineScope, inputs: Flow<QuickInputs>): Job = scope.launch {
        val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        synchronized(lock) { resumes = requests }
        try {
            merge(
                inputs
                    .onEach { synchronized(lock) { latest = it } }
                    .map { Triple(it.readable, it.lastConnectedId, it.storedCount) }
                    .distinctUntilChanged()
                    .map { },
                requests,
            ).collect { syncLatest() }
        } finally {
            synchronized(lock) { if (resumes === requests) resumes = null }
        }
    }

    /** Re-runs the sync with the latest inputs, from the collection's own scope. */
    fun onAppResumed() {
        val requests = synchronized(lock) { if (latest == null) null else resumes }
        requests?.tryEmit(Unit)
    }

    private suspend fun syncLatest() {
        val inputs = synchronized(lock) { latest } ?: return
        try {
            sync.sync(inputs.readable, storedIds(), inputs.lastConnectedId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "shortcut sync failed: ${e.javaClass.simpleName}", e)
        }
    }

    private companion object {
        const val TAG = "ShortcutUpdater"
    }
}
