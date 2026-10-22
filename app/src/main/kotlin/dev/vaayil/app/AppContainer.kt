package dev.vaayil.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dev.vaayil.app.quick.AppWidgetPusher
import dev.vaayil.app.quick.CompatShortcutHost
import dev.vaayil.app.quick.QuickControls
import dev.vaayil.app.quick.QuickRequests
import dev.vaayil.app.quick.QuickShortcutSync
import dev.vaayil.app.quick.ShortcutUpdater
import dev.vaayil.app.quick.WidgetUpdater
import dev.vaayil.app.quick.quickInputs
import dev.vaayil.core.automation.AutoConnectService
import dev.vaayil.core.automation.AutomationReceiver
import dev.vaayil.core.automation.QuickTunnelCommands
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.AutomationRulesStore
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.data.SplitTunnelRulesStore
import dev.vaayil.core.data.readDocumentBaseName
import dev.vaayil.core.data.readDocumentText
import dev.vaayil.core.data.rescueLegacySplitTunnelRules
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.model.SplitTunnelSelections
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelEngine
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.EngineProvider
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import dev.vaayil.feature.profiles.EditorDraft
import dev.vaayil.feature.settings.SettingsFlags
import dev.vaayil.feature.tunnel.TunnelController
import dev.vaayil.feature.tunnel.TunnelControllerStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Flags live in their own DataStore file; core:data owns the active-profile file. A file that
 * cannot be parsed is replaced with empty flags instead of crashing every process start (C11):
 * `onboardingDone` resets, so onboarding runs again, rather than the app never starting.
 */
internal val Context.settingsDataStore by preferencesDataStore(
    name = "vaayil_flags",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w(TAG, "vaayil_flags is unreadable; the flags were reset")
        emptyPreferences()
    },
)

private const val TAG = "AppContainer"

/** How many reads of a file opened from another app run at once; a fifth open waits for one. */
private const val OPENED_FILE_READS_AT_ONCE = 4

/** How many imports Vaayil holds for review at once, reads in flight and waiting drafts together. */
private const val OPEN_IMPORTS_MAX = 8

private val darkModeFollowSystemKey = booleanPreferencesKey("darkModeFollowSystem")

/** S3: the pre-light-only theme flag. Nothing reads or writes it anymore; wipe once at start. */
internal suspend fun wipeLegacyFlags(context: Context) {
    context.settingsDataStore.edit { it.remove(darkModeFollowSystemKey) }
}

private val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")

/** C7: set by the first notification-permission ask, before its dialog launches. */
private val NOTIFICATIONS_ASKED = booleanPreferencesKey("notificationsAsked")

class AppContainer internal constructor(
    private val appContext: Context,
    startupRunner: suspend (List<StartupStep>) -> Unit,
    engineInitializer: (AppContainer) -> Unit,
) {
    /**
     * Production construction: the real start-up runner ([runStartupSteps]) and [initEngine].
     * Tests pass a recording runner and a no-op initializer through the internal constructor
     * instead, so a construction under test does neither native nor Keystore work.
     */
    constructor(appContext: Context) : this(
        appContext,
        { steps -> runStartupSteps(steps) },
        { container -> container.initEngine(); container.quickControls.start() },
    )

    val db = DataGraph.database(appContext)
    val profiles = DataGraph.repository(appContext)
    val activeProfile = ActiveProfileStore(appContext)
    val splitTunnelRules = SplitTunnelRulesStore(appContext)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The hub every quick surface reads. Construction does no I/O: the inputs are collected
     * once and shared by the long-lived readers, from `QuickControls.start()` on in production,
     * and a failed read is logged and retried. The inputs are the process-wide registry and the
     * same stores the rest of the app reads.
     */
    internal val quickControls = QuickControls(
        appScope,
        quickInputs(
            ConnectionRegistry.status,
            ConnectionRegistry.handshake,
            profiles.observeProfiles(),
            profiles.observeStoredCount(),
            activeProfile.activeId,
            activeProfile.lastConnectedId,
        ),
        QuickTunnelCommands(appContext),
        QuickRequests(),
        WidgetUpdater(AppWidgetPusher(appContext)),
        ShortcutUpdater(QuickShortcutSync(CompatShortcutHost(appContext)), profiles::storedIds),
    )

    /**
     * C4: settings saved while a tunnel is CONNECTING are applied when its start settles CONNECTED
     * for that same profile — one restart, however many saves land during the start, and nothing
     * at all when it settles FAILED, DISCONNECTED or as another profile. App-scoped like the
     * registry it reads, and the callback captures the application context, never the activity:
     * a screen that goes away cannot cancel a pending restart.
     */
    internal val settingsRestarts = SettingsRestartCoordinator(
        appScope,
        ConnectionRegistry.status,
    ) { profileId -> restartTunnel(appContext, profileId) }

    /**
     * UI connect/disconnect is service-routed: the controller is built over an engine adapter
     * that fires VpnTunnelService intents instead of engine calls. Only VpnTunnelService and
     * EngineProvider touch the WireGuard engine. The engine-to-registry bridge
     * (ConnectionRegistry.bridge) starts once in EngineProvider.init and lives for the process
     * (F7), so toggle() reads the engine's own status from the registry whether or not a
     * service instance is alive.
     */
    val tunnelController = TunnelController(
        ServiceTunnelEngine(appContext),
        StoreAdapter(activeProfile),
        { id -> profiles.get(id) },
    )

    /** G4/J13: the process-wide value from ConnectionRegistry.bridge, one timer for every surface. */
    val handshakeState: StateFlow<HandshakeState> = ConnectionRegistry.handshake

    val settingsFlags: Flow<SettingsFlags> = appContext.settingsDataStore.data.map { prefs ->
        SettingsFlags(
            onboardingDone = prefs[ONBOARDING_DONE] ?: false,
        )
    }

    suspend fun setFlag(key: String, value: Boolean) {
        val prefKey = when (key) {
            "onboardingDone" -> ONBOARDING_DONE
            else -> return
        }
        appContext.settingsDataStore.edit { it[prefKey] = value }
    }

    /**
     * C7: claims the one-time notification ask — true only to the first caller. The flag is
     * persisted before the dialog launches, so onboarding and the manual connect gate ask once
     * in total. Read and write land in one DataStore edit, so two callers cannot both win.
     */
    internal suspend fun claimNotificationPermissionRequest(): Boolean {
        var claimed = false
        appContext.settingsDataStore.edit { prefs ->
            if (prefs[NOTIFICATIONS_ASKED] != true) {
                prefs[NOTIFICATIONS_ASKED] = true
                claimed = true
            }
        }
        return claimed
    }

    val automationRules = AutomationRulesStore(appContext)

    /** Wi-Fi guard foreground service; paired with the Settings toggle. */
    fun startAutoConnectService() = AutoConnectService.start(appContext)

    fun stopAutoConnectService() = AutoConnectService.stop(appContext)

    /**
     * Imports awaiting review: parse lands the profile here, the editor opens on it, and only
     * Save writes the row (F18). Process death empties this map — acceptable, nothing was
     * written. ConcurrentHashMap because ImportViewModel parses off the main dispatcher.
     */
    val pendingDrafts = java.util.concurrent.ConcurrentHashMap<String, Profile>()

    /**
     * F19: the search-domain notice of each import draft that has one, keyed like
     * [pendingDrafts]. The review editor takes it out and shows it once as it opens.
     */
    val importNotices = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * F30: each open editor's in-progress draft, keyed by its back-stack entry id, so an edit
     * survives Android recreating the activity. Memory only: it holds private keys, so it never
     * goes into a saved-state Bundle, and a process death loses it, by design.
     */
    val editorDrafts = java.util.concurrent.ConcurrentHashMap<String, EditorDraft>()

    /**
     * Files opened from another app read on this view of IO, never on the shared threads: a
     * provider that never answers holds the thread reading from it, and cancelling the read can't
     * free it, so an app opening files without end must not reach the threads that also answer
     * the tunnel's DNS and start and stop it. A view of IO doesn't count against IO's own 64
     * threads; [OPENED_FILE_READS_AT_ONCE] of them read at a time, a fifth open waiting for one.
     */
    private val openedFileIo = Dispatchers.IO.limitedParallelism(OPENED_FILE_READS_AT_ONCE)

    /**
     * Opened-file reads in flight: admitted, not yet finished. With [pendingDrafts] it counts the
     * imports Vaayil holds for review, and an open past [OPEN_IMPORTS_MAX] is refused unread, so a
     * flood of opens can't grow memory without bound. Leaving a review (Back, Discard) or saving
     * it drops its draft and makes room for the next.
     */
    private val openedFileReads = AtomicInteger()

    /** Guards [admitOpenedFile]'s check-and-take, so two opens can't both take the last place. */
    private val openedFileAdmission = Any()

    /** Takes a place for one open, or refuses it while [OPEN_IMPORTS_MAX] imports are held. */
    private fun admitOpenedFile(): Boolean = synchronized(openedFileAdmission) {
        if (openedFileReads.get() + pendingDrafts.size >= OPEN_IMPORTS_MAX) {
            false
        } else {
            openedFileReads.incrementAndGet()
            true
        }
    }

    /**
     * The queue for files opened from another app: a read finishes before the screen that serves
     * it exists, so its outcome waits here. Memory only, like every draft (F30); [dropDrafts]
     * clears it.
     */
    internal val openedFiles = OpenedFiles()

    /**
     * Reads and parses a file opened from another app into [openedFiles], on the app scope: the
     * screen that serves the queue may not exist yet, so the read never belongs to an activity.
     * Nothing is saved; a review waits in [pendingDrafts] until its editor saves (F18). The read
     * and the name query run on [openedFileIo], and an open is refused unread while
     * [OPEN_IMPORTS_MAX] imports are held.
     */
    internal fun openFile(request: OpenRequest): Job {
        val generation = openedFiles.generation()
        val admitted = request is OpenRequest.File && admitOpenedFile()
        val job = appScope.launch {
            val outcome = openFileOutcome(
                if (admitted) request else OpenRequest.Refused,
                { readDocumentText(appContext.contentResolver, it, dispatcher = openedFileIo) },
                { readDocumentBaseName(appContext.contentResolver, it, dispatcher = openedFileIo) },
                ::parseImport,
            )
            // A session that ended while this read ran gets nothing: the draft it made goes too.
            if (!openedFiles.post(outcome, generation) && outcome is OpenedFileOutcome.Review) {
                pendingDrafts.remove(outcome.draftId)
                importNotices.remove(outcome.draftId)
            }
            Log.i(TAG, "Opened file: " + if (outcome is OpenedFileOutcome.Review) "review" else "failed")
        }
        // Whatever the outcome, the place is given back once the job has settled. The job
        // completes only after its draft is stored, so a finishing read is never left uncounted:
        // for that moment it counts as both a read and a draft, which can refuse an open early
        // but never lets one past the cap.
        if (admitted) job.invokeOnCompletion { openedFileReads.decrementAndGet() }
        return job
    }

    /** Drops every in-memory draft when the activity finishes for good (spec U7), and the queue. */
    fun dropDrafts() {
        // Empty the queue first: clearing the maps first would let a read finishing in between
        // put its draft back with nothing left to remove it.
        openedFiles.clear()
        pendingDrafts.clear()
        importNotices.clear()
        editorDrafts.clear()
    }

    private val parseMutex = Mutex()

    /**
     * Parses an import into an unsaved draft keyed by its id; throws the parser's exception on
     * bad input. The name is made unique against the stored profiles and the drafts still
     * awaiting review (F26), so two opened files of one name get two names; the mutex keeps
     * choosing the name and storing the draft one step, so two opens in flight cannot settle on
     * the same one. Search domains the profile cannot keep leave a notice for the review (F19).
     */
    suspend fun parseImport(text: String, name: String): Profile = parseMutex.withLock {
        val stored = profiles.observeProfiles().first().map { it.name }
        val pending = pendingDrafts.values.map { it.name }
        val draft = parseImportDraft(text, name, (stored + pending).toSet())
        searchDomainNotice(draft.droppedSearchDomains)?.let { importNotices[draft.profile.id] = it }
        pendingDrafts[draft.profile.id] = draft.profile
        draft.profile
    }

    /** Copy with a fresh id and a unique "(copy)" name suffix, backing the list screen's Duplicate action. */
    suspend fun duplicateProfile(id: String) {
        val source = profiles.get(id) ?: return
        val taken = profiles.observeProfiles().first().map { it.name }.toSet()
        profiles.save(
            source.copy(
                id = UUID.randomUUID().toString(),
                name = uniqueProfileName(source.name + " (copy)", taken),
            ),
        )
    }

    suspend fun installedApps(): Set<String> =
        appContext.packageManager.getInstalledPackages(0)
            .mapNotNull { it.packageName }
            .filterNot { it == appContext.packageName }
            .toSet()

    /**
     * C11: the one-time upgrades, in order. The runner runs them synchronously and strictly
     * BEFORE Room's first open: the legacy-rules rescue reads the raw SQLite file whose dormant
     * columns MIGRATION_2_3 then drops. Costs a few ms once; determinism is worth it.
     */
    private fun startupSteps(): List<StartupStep> = listOf(
        // First touch of the at-rest key: on a fresh install nothing else does, and the
        // Profiles tab would call the Keystore unavailable for the whole session.
        StartupStep("warm") { DataGraph.crypto().warm() },
        StartupStep("rescueLegacySplitTunnelRules") {
            rescueLegacySplitTunnelRules(splitTunnelRules, appContext, activeProfile)
        },
        StartupStep("reEncryptAtRest") { profiles.reEncryptAtRest() },
        StartupStep("wipeLegacyFlags") { wipeLegacyFlags(appContext) },
    )

    init {
        // Synchronous by design: every upgrade finishes before the engine takes over (C11).
        runBlocking { startupRunner(startupSteps()) }
        engineInitializer(this)
    }

    /**
     * F7: the engine-to-registry bridge and the service's engine seams, initialised once per
     * process, after the one-time upgrades above.
     */
    private fun initEngine() {
        EngineProvider.init(
            appContext,
            { installedApps() },
            { id -> profiles.get(id) },
            { splitTunnelRules.snapshot() },
        )
    }
}

private class StoreAdapter(private val s: ActiveProfileStore) : TunnelControllerStore {
    override suspend fun setActive(id: String?) = s.setActive(id)
}

/**
 * TunnelEngine adapter that never touches the WireGuard engine: start/stop fire VpnTunnelService
 * intents (the service owns engine access), and status/stats are read back from ConnectionRegistry,
 * which the process-lifetime bridge started in EngineProvider.init keeps current (F7). Lets
 * TunnelController keep its exact surface while control stays service-routed.
 */
private class ServiceTunnelEngine(private val context: Context) : TunnelEngine {
    override val status get() = ConnectionRegistry.status
    override val stats get() = ConnectionRegistry.stats

    override suspend fun start(profile: Profile) {
        context.startService(
            Intent(context, VpnTunnelService::class.java)
                .setAction(VpnTunnelService.ACTION_CONNECT)
                .putExtra(VpnTunnelService.EXTRA_PROFILE_ID, profile.id),
        )
    }

    override suspend fun stop() {
        context.startService(
            Intent(context, VpnTunnelService::class.java)
                .setAction(VpnTunnelService.ACTION_DISCONNECT),
        )
    }
}

/**
 * Saves the app-wide split-tunnel rules and has the live tunnel apply them: at once while it is
 * CONNECTED, or once a CONNECTING start settles CONNECTED for that same profile (C4, through
 * [SettingsRestartCoordinator]); a start that settles FAILED, DISCONNECTED or as another profile
 * applies nothing. Never for an edit to the inactive mode's list, and never while nothing is
 * live: a restart is a full down/resolve/up cycle with an unprotected window unless Android
 * lockdown is on (S6).
 */
internal suspend fun saveSplitTunnelRules(
    store: SplitTunnelRulesStore,
    selections: SplitTunnelSelections,
    restarts: SettingsRestartCoordinator,
) {
    val before = store.snapshotSelections().active
    store.setSelections(selections)
    // The save is on disk before the restart request: a pending one reads the store as it settles.
    if (selections.active != before) restarts.request()
}

/**
 * F22: a saved edit to the connected profile applies at once, like a split-tunnel save (spec §11),
 * and as its CONNECTING start settles when the save landed mid-connect (C4). Only when something
 * the tunnel uses changed: a rename alone waits for the next connect, and an edit to a profile
 * that is not the live one waits for that profile's own next connect.
 */
internal fun restartIfConnectedProfileChanged(
    before: Profile?,
    after: Profile,
    restarts: SettingsRestartCoordinator,
) {
    if (before != null && before.copy(name = after.name) == after) return
    restarts.request(after.id)
}

/**
 * C4: the one production restart call — [VpnTunnelService]'s ACTION_CONNECT for [profileId], the
 * same service intent the UI's connect path fires. [SettingsRestartCoordinator]'s callback is
 * built on it, so the restart intent is constructed in one place.
 */
internal fun restartTunnel(context: Context, profileId: String) {
    context.startService(
        Intent(context, VpnTunnelService::class.java)
            .setAction(VpnTunnelService.ACTION_CONNECT)
            .putExtra(VpnTunnelService.EXTRA_PROFILE_ID, profileId),
    )
}

/**
 * Flips the exported automation receiver: shipped disabled, enabled only by the Settings
 * toggle (T-3). The token gate in the receiver is the second half of the same opt-in.
 */
internal fun setExternalControlEnabled(context: Context, enabled: Boolean) {
    context.packageManager.setComponentEnabledSetting(
        ComponentName(context, AutomationReceiver::class.java),
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
}
