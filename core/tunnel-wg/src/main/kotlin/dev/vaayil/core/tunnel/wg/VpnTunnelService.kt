package dev.vaayil.core.tunnel.wg

import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wireguard.android.backend.GoBackend
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class VpnTunnelService : GoBackend.VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The process-scope orchestrator: [TunnelControl.Default] unless a test substitutes one. */
    private val control: TunnelControl get() = controlOverride ?: TunnelControl.Default

    override fun onCreate() {
        super.onCreate()
        // The resolver's bootstrap and bypass sockets must escape the tunnel: publish this
        // instance's protect callbacks for the engine's controller while the service lives.
        EngineProvider.protectSocket = { socket -> this.protect(socket) }
        EngineProvider.protectDatagramSocket = { socket -> this.protect(socket) }
        TunnelNotifications.ensureChannel(this)
        promoteForeground()
        scope.launch {
            // The registry follows the engine for the process lifetime (ConnectionRegistry.bridge, F7); this instance
            // only renders it.
            combine(ConnectionRegistry.status, ConnectionRegistry.handshake) { status, handshake ->
                status to handshake
            }.collect { (status, handshake) ->
                val notification = TunnelNotifications.buildNotification(
                    this@VpnTunnelService, status, handshake, EngineProvider.engine?.liveTunnelName,
                    TunnelNotifications.openAppIntent(this@VpnTunnelService),
                )
                if (notification != null) {
                    // Promotes on CONNECTING and keeps the foreground notification in step
                    // with every subsequent state (the system tears an always-on VPN down
                    // unless its VpnService is foreground).
                    startForeground(TunnelNotifications.NOTIFICATION_ID, notification)
                } else {
                    TunnelNotifications.clear(this@VpnTunnelService)
                    ServiceCompat.stopForeground(this@VpnTunnelService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    /** Best-effort promotion from the current registry state. While idle there is no tunnel
        notification — but a service started via startForegroundService (I1's stale-pointer
        case) must still enter the foreground within the 5 s contract, so a minimal silent
        notification covers the idle window until stopSelf() removes it. */
    private fun promoteForeground() {
        val notification = TunnelNotifications.buildNotification(
            this, ConnectionRegistry.status.value, ConnectionRegistry.handshake.value,
            EngineProvider.engine?.liveTunnelName,
            TunnelNotifications.openAppIntent(this),
        ) ?: NotificationCompat.Builder(this, TunnelNotifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Vaayil")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(TunnelNotifications.NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground()
        val isResume = intent != null && intent.action == ACTION_CONNECT &&
            intent.getBooleanExtra(EXTRA_RESUME, false)
        // J3: every start that is not the resume bars it for the rest of the process.
        if (!isResume) EngineProvider.crashResumeGate.noteExplicitStart()
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                // J3: the disconnect bars the resume for good, and the teardown waits for a
                // resume that already passed its checks before it clears the records and stops.
                teardown(after = EngineProvider.crashResumeGate.suppress())
                stopSelf()
                // Fail-closed: the user asked for a disconnect, so the service must not come
                // back on its own. START_NOT_STICKY (plus the cleared active-profile record
                // in teardown) keeps a sticky restart from silently re-establishing the VPN.
                return START_NOT_STICKY
            }
            // Null action = explicit start (e.g. the always-on callback); SERVICE_INTERFACE
            // action = the system's Always-on VPN start. Both restore the last active profile.
            ACTION_CONNECT, null, android.net.VpnService.SERVICE_INTERFACE -> {
                val store = ActiveProfileStore(this)
                val profileId = startProfileId(
                    action = intent?.action,
                    explicitId = intent?.getStringExtra(EXTRA_PROFILE_ID),
                    activeId = { store.getActiveBlocking() },
                    lastConnectedId = { store.getLastConnectedBlocking() },
                )
                if (profileId == null) { stopSelf(startId); return START_NOT_STICKY }
                // C2: a same-profile restart that could not apply its settings is shown the same way. Its
                // outcome is STARTED (the old session lives), so the record is the only witness.
                // K3: a start that FAILED while another profile's tunnel stays live is shown by the app (a
                // snackbar) instead of passing silently. REFUSED never reached the engine and records nothing.
                // F32: once the connect settles, whatever the outcome, this start request is released. A live
                // tunnel is then held by the system's VPN binding alone (ServiceHold covers setState), so a
                // process death leaves no start request behind and takes its notification with it instead of
                // orphaning a "connected" notification. With nothing live the instance stops, never a teardown:
                // FAILED stays on the dashboard, and a refused or failed start never ends a session that is
                // still running. While a later start is in flight this is a no-op; the last start to settle
                // releases the service.
                val onConnectSettled: (StartOutcome, TunnelStatus) -> Unit = { outcome, _ ->
                    control.takeRestartRefusal(profileId)?.let { ConnectionRegistry.reportRestartRefusal(it) }
                    if (outcome == StartOutcome.FAILED) {
                        control.takeFailedSwitch(profileId)?.let { ConnectionRegistry.reportSwitchFailure(it) }
                    }
                    stopSelf(startId)
                }
                if (isResume) {
                    // J3: built LAZY so a resume the gate refuses is dropped before the connect
                    // runs, and admitted again inside connectById, after its profile lookup.
                    val job = control.connect(
                        this, profileId,
                        start = CoroutineStart.LAZY,
                        admit = { EngineProvider.crashResumeGate.mayIssue() },
                        onSettled = onConnectSettled,
                    )
                    if (!EngineProvider.crashResumeGate.register(job)) {
                        Log.i(TAG, "crash resume dropped: another start reached the service first")
                        job.cancel()
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    job.start()
                } else {
                    // Process-scope orchestration, never this instance's scope: a start-while-up
                    // makes GoBackend stopSelf() this service mid-establish (tunnel-DOWN paths
                    // always do), and a cancelled connect would tear down the fresh tunnel.
                    // A destroyed instance ignores the trailing stopSelf.
                    control.connect(this, profileId, onSettled = onConnectSettled)
                }
            }
            // No other action is sent; one that arrives holds no start request either.
            else -> stopSelf(startId)
        }
        // F32: never sticky. A process death leaves no start request behind, and EngineProvider's same-boot
        // resume is the one path that reconnects a dead session, whatever brings the process back (the guard's
        // restart, the next app open).
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // A1: the platform revoked the VPN, so no query may be answered from here on — and teardown() is
        // asynchronous (TunnelControl.disconnect posts the stop), so the resolver has to die synchronously,
        // first, before that posted work can run. Teardown's own stop retires it again; both are idempotent.
        EngineProvider.engine?.deactivateResolver()
        // J3: the revoke bars the resume and joins an accepted one before the teardown stops the engine.
        teardown(after = EngineProvider.crashResumeGate.suppress())
        stopSelf()
    }

    /**
     * Shared user/revocation teardown: stops the engine, clears the active-profile record,
     * and resets the registry, so the same-boot resume finds nothing to revive (always-on's
     * own start still reconnects the last profile, T-7). ACTIVE is cleared BEFORE the
     * engine stop and again after it (F32). Runs on the process scope
     * (NonCancellable inside): a stopSelf-triggered onDestroy cancels this instance's scope
     * while the engine stop is still settling, and an abandoned stop would orphan the live
     * tunnel — exactly the row-24 mechanism, so the stop must outlive the instance.
     */
    private fun teardown(after: Job? = null) {
        val store = ActiveProfileStore(applicationContext)
        control.disconnect(
            // J3: an accepted resume's Job is joined before the records are cleared, so its
            // settle can never write them back behind this teardown.
            after = after,
            // F32: cleared before the stop as well, so a process death mid-disconnect never leaves a record for the
            // same-boot resume to revive. Cleared again once the stop settles: a connect that settles STARTED while
            // the stop waits for the engine writes ACTIVE back.
            beforeStop = { store.setActive(null) },
        ) {
            store.setActive(null)
            ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.DISCONNECTED, null, null)
        }
    }

    override fun onDestroy() {
        EngineProvider.protectSocket = null // before scope.cancel: no protected socket outlives the service
        EngineProvider.protectDatagramSocket = null
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // GoBackend.VpnService.onDestroy turns off a tunnel this instance owned; the engine hears it
        // through that tunnel's DOWN callback.
        super.onDestroy()
        // Never assume the tunnel died with this instance (F7): a switch may have re-established on
        // another one. With an engine, re-check the backend once any start in flight settles; with
        // none (tests, a process that never initialised it) a live claim is certainly stale (T-2).
        if (EngineProvider.engine != null) control.reconcile() else ConnectionRegistry.resetIfLive()
    }

    companion object {
        const val ACTION_CONNECT = "dev.vaayil.action.CONNECT"
        const val ACTION_DISCONNECT = "dev.vaayil.action.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"

        /** Marks EngineProvider's same-boot resume (F32): a resume yields to any start this process handled before it. */
        internal const val EXTRA_RESUME = "dev.vaayil.extra.RESUME"

        /** Tests only: a scripted orchestrator; production leaves it null. */
        @Volatile internal var controlOverride: TunnelControl? = null

        private const val TAG = "VpnTunnelService"
    }
}
