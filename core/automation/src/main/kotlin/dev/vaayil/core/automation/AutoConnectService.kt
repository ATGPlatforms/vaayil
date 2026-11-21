package dev.vaayil.core.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.AutomationRulesStore
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.data.ProfileRepository
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.SsidDecision
import dev.vaayil.core.model.WifiSecurity
import dev.vaayil.core.model.decide
import dev.vaayil.core.model.mobileDecision
import dev.vaayil.core.tunnel.api.isLive
import dev.vaayil.core.tunnel.wg.ConnectMode
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Foreground service (specialUse) hosting the [SsidWatcher] and the [MobileDataWatcher];
 * evaluates the connect rules whenever the SSID or the mobile-data state changes, and routes
 * every decision through VpnTunnelService intents — never the engine directly (spec §7 Phase 2).
 *
 * B2: the platform's Location switch is watched through MODE_CHANGED_ACTION while the service
 * runs; a change re-registers only the Wi-Fi watcher (when one runs, Y3), so the current network is delivered at
 * once, and re-words notification 43 — without an app start.
 *
 * B1: every command goes through [BackgroundTunnelCommands] (consent on CONNECT, a live-phase gate
 * on DISCONNECT), and no evaluation may kill this START_STICKY service: a store or target read that
 * throws skips its evaluation, a refused start is logged and the next evaluation still runs, and
 * anything the per-evaluation catch cannot hold reaches [scope]'s handler, which logs it.
 *
 * Y3: the service runs while either rule is on, but the [SsidWatcher] runs only while Auto-connect on untrusted
 * Wi-Fi is on. [observeRules] follows the stored rules: the watcher is registered when the rule turns on and
 * unregistered when it turns off, so with only Auto-connect on mobile data on nothing reads the Wi-Fi name or,
 * below API 31, the scan list, whatever location grants are held. Rules that can't be read turn it off,
 * and the rule is read again after [wifiGuardRetryDelayMs] until a read succeeds.
 *
 * AUTO-2 (spec §4.6): a rule edit acts at once only when it changes the answer for the last network the
 * watchers reported; otherwise the user's own Connect or Disconnect stands. The first read after the service
 * starts, and the first good read after a failed one, never act (R7).
 */
class AutoConnectService : Service() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e ->
                Log.w(TAG, "auto-connect evaluation failed (${e.javaClass.simpleName})", e)
            },
    )
    private var watcher: SsidWatcher? = null
    private var mobileWatcher: MobileDataWatcher? = null
    /** Evaluate-on-change gate for the Wi-Fi rules; M5/B2 forget it whenever the watcher re-registers, Y3 whenever it starts or stops. */
    internal val wifiGate = WifiChangeGate()
    /** M5/B2: the platform location state [watcher] last registered under; set in onCreate. */
    private lateinit var watchedLocationState: GuardLocationState
    /** B2: whether the MODE_CHANGED receiver is registered, so teardown unregisters exactly once. */
    private var locationReceiverRegistered = false
    /** C7: the last copy refreshNotification computed; null before the first computation. */
    private var lastNotificationCopy: Pair<String, String?>? = null
    private var lastMobile: Boolean? = null
    /** AUTO-2: the last successfully read rules; null before the first good read and after a failed one (R7). */
    private var lastRules: AutoConnectRules? = null
    /** AUTO-2: the last network the Wi-Fi rules saw (the name as the rules see it) with its security; null before any report. */
    private var lastWifi: Pair<String?, WifiSecurity>? = null

    /**
     * B2: Location turned on or off while the service runs, the app closed. MODE_CHANGED is a
     * system broadcast, so a context receiver registered NOT_EXPORTED still gets it and no other
     * app can fake it. The state sample re-registers the Wi-Fi watcher when one runs (Y3); the copy then follows it.
     */
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (refreshLocationState()) refreshNotification()
        }
    }

    /**
     * B1: where an evaluation reads its rules. The default reads the store lazily — constructing the
     * service, before it is attached, must not open the DataStore. Tests replace it.
     */
    internal var readRules: suspend () -> AutoConnectRules = { AutomationRulesStore(this).snapshot() }

    /**
     * Y3: the stored rules, following the store. Called when the watchers start in onCreate and again after
     * each failed read, so constructing the service still opens no DataStore. Tests replace it before create().
     */
    internal var observeRules: () -> Flow<AutoConnectRules> = {
        AutomationRulesStore(this).rules
    }

    /** B1: where an evaluation resolves the connect target. Defaults to the store-backed resolver. */
    internal var readTarget: suspend () -> String? = { resolveConnectTarget(this) }

    /** B1: the one admission point for the tunnel commands this service issues. */
    internal var tunnelCommands: BackgroundTunnelCommands = BackgroundTunnelCommands(this)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Automation", NotificationManager.IMPORTANCE_MIN))
        promote("Vaayil auto-connect is on", null)
        watchedLocationState = guardLocationState(this)
        try {
            startWatching()
        } catch (e: Exception) {
            Log.w(TAG, "watcher registration failed (${e.javaClass.simpleName})")
        }
        refreshNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must reach startForeground(); then re-check the copy (F2).
        // C7: with the LAST computed copy — an app resume must never flash the generic title over
        // the paused one. Before the first computation the generic copy is all there is.
        val (title, text) = lastNotificationCopy ?: ("Vaayil auto-connect is on" to null)
        promote(title, text)
        // M5/B2: a location change made outside the app (a grant in system Settings, Approximate
        // replacing precise, Location on or off) is picked up here, and every app resume sends a
        // start command. Re-registering makes Android deliver the current Wi-Fi at once; forgetting
        // the gate makes that delivery count as a change, so the rules run on it.
        refreshLocationState()
        refreshNotification()
        return START_STICKY
    }

    /** B2/C7: re-posts the notification with copy that matches what the rules can do right now (F2). */
    private fun refreshNotification() {
        scope.launch {
            // C7 + B1: a failed read keeps the last successfully computed copy.
            val rules = readRulesOrNull() ?: return@launch
            // B2: the tracked state, so this copy matches the gate the Wi-Fi rules read and a
            // refresh that was in flight when the state changed still computes the current copy.
            val copy = guardNotificationCopy(
                rules,
                watchedLocationState.backgroundLocation,
                watchedLocationState.preciseLocation,
                watchedLocationState.locationEnabled,
            )
            lastNotificationCopy = copy
            promote(copy.first, copy.second)
        }
    }

    /**
     * M5/B2: samples the platform's location state; on a change it unregisters only the Wi-Fi watcher
     * (when one runs, Y3), forgets its gate and registers it again, so its new callback is delivered
     * the current network at once. True when the state changed. Never touches the mobile watcher.
     */
    private fun refreshLocationState(): Boolean {
        val state = guardLocationState(this)
        if (state == watchedLocationState) return false
        watchedLocationState = state
        try {
            watcher?.unregister()
            wifiGate.forget()
            lastWifi = null // AUTO-2: the new callback has not reported yet
            watcher?.register()
        } catch (e: Exception) {
            Log.w(TAG, "watcher re-registration failed (${e.javaClass.simpleName})")
        }
        return true
    }

    private fun promote(title: String, text: String?) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(title, text),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun notification(title: String, text: String?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /** B1: one Wi-Fi evaluation; [setWifiWatching] wires the actual watcher callback to this. */
    internal fun evaluateWifi(raw: String?, security: WifiSecurity): Job = scope.launch {
        val ssid = ssidForRules(raw, wifiNameVisible())
        lastWifi = ssid to security // AUTO-2: remembered even when the gate skips the evaluation
        if (!wifiGate.changed(ssid, security)) return@launch        // evaluate on change only
        val rules = readRulesOrNull() ?: return@launch
        // J3: [wifiAnswer] keeps the policy disconnect ahead of the phase read.
        when (rules.wifiAnswer(ssid, security, ConnectionRegistry.status.value.phase)) {
            SsidDecision.CONNECT -> connectResolvedTarget(BackgroundSender.WIFI_RULE)
            SsidDecision.DISCONNECT -> tunnelCommands.disconnect(foreground = false)
            SsidDecision.NONE -> Unit
        }
    }

    /** B1: one mobile-data evaluation, gated the same way. */
    internal fun evaluateMobile(mobile: Boolean): Job = scope.launch {
        if (mobile == lastMobile) return@launch   // evaluate on change only
        lastMobile = mobile
        val rules = readRulesOrNull() ?: return@launch
        val busy = ConnectionRegistry.status.value.phase.isLive
        if (rules.mobileDecision(mobile, busy) == SsidDecision.CONNECT) connectResolvedTarget(BackgroundSender.MOBILE_RULE)
    }

    /** B2: whether the grants and the Location switch currently let the Wi-Fi name reach the rules. */
    private fun wifiNameVisible(): Boolean = with(watchedLocationState) {
        backgroundLocation && preciseLocation && locationEnabled
    }

    /**
     * I1: a stale pointer (deleted profile) must not foreground-start VpnTunnelService with
     * nothing to connect — the service would idle past the 5 s FGS contract. Resolve first,
     * then let [tunnelCommands] admit the start (B1) as [sender]'s rule (#14).
     */
    private suspend fun connectResolvedTarget(sender: BackgroundSender) {
        val target = readTargetOrNull() ?: return
        tunnelCommands.connect(target, sender)
    }

    /** B1: a read that throws is logged by class, never a stored value, and skips its evaluation. */
    private suspend fun readRulesOrNull(): AutoConnectRules? = try {
        readRules()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "rules unreadable (${e.javaClass.simpleName}); evaluation skipped")
        null
    }

    /** B1: the same discipline for the connect target. */
    private suspend fun readTargetOrNull(): String? = try {
        readTarget()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "connect target unreadable (${e.javaClass.simpleName}); not connecting")
        null
    }

    private fun startWatching() {
        // B2: the switch's broadcast is registered with the mobile-data watcher and lives as long as the service does.
        ContextCompat.registerReceiver(
            this,
            locationReceiver,
            IntentFilter(LocationManager.MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        locationReceiverRegistered = true
        mobileWatcher = MobileDataWatcher(this) { mobile ->
            evaluateMobile(mobile)
        }.also { it.register() }
        // Y3 + AUTO-2: one collection follows the stored rules — the watcher's on/off switch and the
        // edit comparison. A failed read emits null: the watcher goes off and the previous rules are
        // forgotten, so the first good read after it never acts (R7). The rule is read again after
        // [wifiGuardRetryDelayMs].
        scope.launch {
            flow<AutoConnectRules?> { emitAll(observeRules()) }
                .retryWhen { e, attempt ->
                    Log.w(TAG, "rules unreadable (${e.javaClass.simpleName}); Wi-Fi watcher off")
                    emit(null)
                    delay(wifiGuardRetryDelayMs(attempt))
                    true
                }
                .collect { rules -> rulesRead(rules) }
        }
    }

    /**
     * Y3 + AUTO-2: one successful rules read. The watcher follows the guard rule (Y3); AUTO-2 then
     * compares this read with the previous successful one and acts only when the answer for the
     * last network the watchers reported changed. [rules] null is the failed read's marker: the
     * watcher goes off and the previous rules are forgotten, so the next good read starts afresh.
     */
    private fun rulesRead(rules: AutoConnectRules?) {
        if (rules == null) {
            lastRules = null
            setWifiWatching(false)
            return
        }
        val previous = lastRules
        lastRules = rules
        setWifiWatching(rules.wifiGuardEnabled) // Y3: idempotent, so every good read may ask
        if (previous != null) actOnRuleChange(previous, rules)
    }

    /**
     * AUTO-2 (spec §4.6): an edit acts at once only when the answer for the network the watchers
     * last reported changes. Wi-Fi first; mobile data only when the Wi-Fi answer gave nothing to
     * do. The command runs in its own coroutine (never inline): a collector that throws would end
     * the collection, and a disconnect can wait on an accepted crash resume — either would stop
     * the watcher following the guard rule (Y3).
     */
    private fun actOnRuleChange(previous: AutoConnectRules, current: AutoConnectRules) {
        val phase = ConnectionRegistry.status.value.phase
        val wifi = lastWifi
        val wifiDecision = if (wifi != null) {
            wifiRuleChange(previous, current, wifi.first, wifi.second, phase)
        } else {
            SsidDecision.NONE // no report yet: nothing to compare
        }
        when (wifiDecision) {
            SsidDecision.CONNECT -> scope.launch { connectResolvedTarget(BackgroundSender.WIFI_RULE) }
            SsidDecision.DISCONNECT -> scope.launch { tunnelCommands.disconnect(foreground = false) }
            SsidDecision.NONE -> {
                val mobile = lastMobile ?: return // no report yet: nothing to compare
                if (mobileRuleChange(previous, current, mobile, phase.isLive) == SsidDecision.CONNECT) {
                    scope.launch { connectResolvedTarget(BackgroundSender.MOBILE_RULE) }
                }
            }
        }
    }

    /**
     * Y3: starts the [SsidWatcher] when Auto-connect on untrusted Wi-Fi turns on and stops it when the rule turns
     * off, forgetting the Wi-Fi gate and the last reported network both times, so a network that a late
     * evaluation recorded cannot hide the current network from the new callback, which Android delivers at once.
     */
    private fun setWifiWatching(enabled: Boolean) {
        try {
            if (enabled && watcher == null) {
                wifiGate.forget()
                lastWifi = null
                watcher = SsidWatcher(this, scope) { raw, security ->
                    evaluateWifi(raw, security)
                }.also { it.register() }
            } else if (!enabled && watcher != null) {
                watcher?.unregister()
                watcher = null
                wifiGate.forget()
                lastWifi = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi watcher update failed (${e.javaClass.simpleName})")
        }
    }

    override fun onDestroy() {
        try {
            if (locationReceiverRegistered) {
                unregisterReceiver(locationReceiver)
                locationReceiverRegistered = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "location receiver teardown failed (${e.javaClass.simpleName})")
        }
        try {
            watcher?.unregister(); watcher = null
            mobileWatcher?.unregister(); mobileWatcher = null
        } catch (e: Exception) {
            Log.w(TAG, "watcher teardown failed (${e.javaClass.simpleName})")
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AutoConnectService"
        const val CHANNEL_ID = "automation"
        const val NOTIFICATION_ID = 43
        fun connectIntent(
            context: Context,
            profileId: String? = null,
            mode: ConnectMode = ConnectMode.RESTART,
        ): Intent =
            Intent(context, VpnTunnelService::class.java)
                .setAction(VpnTunnelService.ACTION_CONNECT)
                .putExtra(VpnTunnelService.EXTRA_CONNECT_MODE, mode.name)
                .apply { if (profileId != null) putExtra(VpnTunnelService.EXTRA_PROFILE_ID, profileId) }

        /**
         * B3: the profile the background paths connect — boot, the guard/mobile evaluations and a
         * hintless Tasker broadcast all resolve here. ACTIVE is cleared by every disconnect
         * (Phase-1 fail-closed), so without the lastConnected fallback the guard would silently
         * no-op after any disconnect. A pointer to a deleted profile is cleared on the way, and
         * with no pointer left the newest readable profile connects.
         */
        suspend fun resolveConnectTarget(context: Context): String? =
            resolveAutomationTarget(ActiveProfileStore(context), DataGraph.repository(context))

        fun start(context: Context) { context.startForegroundService(Intent(context, AutoConnectService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, AutoConnectService::class.java)) }

        /**
         * Y3: the wait before reading the Wi-Fi rule again after failed read [attempt] (counted from 0 over the
         * service's life): 1 s, doubling each time, at most 60 s.
         */
        internal fun wifiGuardRetryDelayMs(attempt: Long): Long =
            minOf(60_000L, 1_000L shl attempt.coerceIn(0L, 6L).toInt())
    }
}

/**
 * B3: the profile an automation connect targets, in the dashboard disc's order (F23): ACTIVE,
 * else last-connected — each only while a stored row still carries that id, so a pointer to a
 * deleted profile (or a re-import, which mints a new id) is cleared instead of being trusted —
 * else the newest readable profile. A stored row whose secrets cannot be decrypted right now
 * keeps its pointer and stays the target: the connect path re-acquires the key itself (F13).
 * Null means there is nothing to connect, so nothing starts.
 */
internal suspend fun resolveAutomationTarget(store: ActiveProfileStore, repository: ProfileRepository): String? {
    val (active, lastConnected) = store.reconcileProfilePointers(repository.storedIds())
    return active ?: lastConnected ?: repository.observeProfiles().first().firstOrNull()?.id
}
