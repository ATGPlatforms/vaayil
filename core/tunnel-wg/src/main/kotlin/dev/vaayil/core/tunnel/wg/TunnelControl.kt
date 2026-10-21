package dev.vaayil.core.tunnel.wg

import android.content.Context
import android.util.Log
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-lifetime tunnel orchestration. Connect and disconnect coroutines must never run on
 * a [VpnTunnelService] instance's scope: the pinned GoBackend calls
 * `vpnService.get(0, NANOSECONDS).stopSelf()` on every tunnel-DOWN (setStateInternal), and a
 * start-while-up — the split-tunnel save restart (spec §11) — tears the previous tunnel down
 * inside the new start's own setState. The service instance therefore dies mid-establish, its
 * scope cancels the connect, and the connect's own failure handler tears down the freshly
 * established tunnel. A screen lock during the restart is the field case: it leaves the app
 * in the background, where the fresh VpnService start is platform-blocked. Orchestrating on
 * this scope means the connect outlives the instance; the engine holds the service across
 * setState (ServiceHold) and the registry follows the engine for the process lifetime.
 */
class TunnelControl(
    private val scope: CoroutineScope,
    private val connectOp: suspend (Context, String, () -> Boolean) -> StartOutcome,
    private val statusNow: () -> TunnelStatus,
    private val stopOp: suspend () -> Unit,
    private val reconcileOp: suspend () -> Unit = {},
    /** K3: the engine's recorded failure of the last start of a profile id, consumed once; null when none. */
    private val failedSwitchOp: (String) -> SwitchFailure? = { null },
    /** C2: the engine's recorded restart refusal of a profile id, consumed once; null when none. */
    private val restartRefusalOp: (String) -> RestartRefusal? = { null },
    /** Counts one connect whose work has ended, whatever the outcome; the quick controls' duplicate guard reads the count. */
    private val noteConnectSettled: () -> Unit = {},
) {
    /**
     * Starts [profileId]; [onSettled] fires with the outcome once the start has fully settled,
     * regardless of whether the calling service instance is still alive (a dead instance simply
     * ignores the trailing stopSelf). Each connect whose work ends — by returning or by throwing —
     * counts as settled for the quick controls' duplicate guard. [admit] is the caller's last
     * check before the engine is touched (J3: the crash resume passes the gate here); [start]
     * builds the Job lazily so a refused resume is dropped before [connectOp] ever runs. Returns
     * the connect's Job.
     */
    fun connect(
        context: Context,
        profileId: String,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        admit: () -> Boolean = { true },
        onSettled: (StartOutcome, TunnelStatus) -> Unit = { _, _ -> },
    ): Job =
        scope.launch(start = start) {
            val outcome = try { connectOp(context, profileId, admit) } finally { noteConnectSettled() }
            onSettled(outcome, statusNow())
        }

    /**
     * Stops the engine; [onSettled] fires after the stop has fully completed. [after] is joined
     * before [beforeStop] and the stop (J3: a teardown waits for a resume that already passed
     * its checks, so its settle can never write records behind the teardown); [beforeStop] runs
     * next; its failure is logged and never keeps the tunnel up.
     */
    fun disconnect(after: Job? = null, beforeStop: suspend () -> Unit = {}, onSettled: suspend () -> Unit = {}) {
        scope.launch {
            after?.join()
            runCatching { beforeStop() }.onFailure { Log.w(TAG, "pre-stop step failed; stopping anyway", it) }
            stopOp()
            onSettled()
        }
    }

    /** A VpnTunnelService instance died: let the engine re-check the backend (F7). */
    fun reconcile() { scope.launch { reconcileOp() } }

    /** K3: what the app shows for the last start of [profileId] when it failed while another tunnel stayed live. */
    fun takeFailedSwitch(profileId: String): SwitchFailure? = failedSwitchOp(profileId)

    /** C2: what the app shows for the last restart of [profileId] that could not apply its settings. */
    fun takeRestartRefusal(profileId: String): RestartRefusal? = restartRefusalOp(profileId)

    companion object {
        private const val TAG = "TunnelControl"

        /**
         * The engine (and its resolver) live in [EngineProvider] and outlive every service
         * instance, so their orchestration scope must too. Main.immediate: [VpnTunnelService]
         * scopes run there, and the engine documents its lock-free teardown guard against
         * main-thread-dispatched callbacks.
         */
        val Default: TunnelControl by lazy {
            TunnelControl(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                connectOp = { context, profileId, admit -> EngineProvider.connectById(context, profileId, admit) },
                statusNow = { EngineProvider.engine?.status?.value ?: TunnelStatus.idle },
                stopOp = { EngineProvider.engine?.stop() },
                reconcileOp = { EngineProvider.engine?.reconcile() },
                failedSwitchOp = { id -> EngineProvider.engine?.takeFailedSwitch(id) },
                restartRefusalOp = { id -> EngineProvider.engine?.takeRestartRefusal(id) },
                noteConnectSettled = ConnectionRegistry::noteConnectSettled,
            )
        }
    }
}
