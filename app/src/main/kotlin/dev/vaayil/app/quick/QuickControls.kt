package dev.vaayil.app.quick

import android.content.Context
import android.util.Log
import dev.vaayil.app.VaayilApplication
import dev.vaayil.core.automation.QuickTunnelCommands
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Folds every input a quick surface reads into one snapshot. Readable profiles are reduced to
 * [QuickProfile] here, so the presenter never handles a profile's secrets.
 */
internal fun quickInputs(
    status: Flow<TunnelStatus>,
    handshake: Flow<HandshakeState>,
    profiles: Flow<List<Profile>>,
    storedCount: Flow<Int>,
    activeId: Flow<String?>,
    lastConnectedId: Flow<String?>,
): Flow<QuickInputs> = combine(
    status,
    handshake,
    profiles,
    storedCount,
    combine(activeId, lastConnectedId) { active, last -> active to last },
) { currentStatus, currentHandshake, readable, stored, pointers ->
    QuickInputs(
        status = currentStatus,
        handshake = currentHandshake,
        readable = readable.map { QuickProfile(it.id, it.name) },
        storedCount = stored,
        activeId = pointers.first,
        lastConnectedId = pointers.second,
    )
}

/**
 * The hub every system-created quick surface reads: the Quick Settings tile, the widget and the
 * shortcut trampoline. Construction does no I/O; the inputs are collected once and shared by the
 * long-lived readers, from [start] on in production, and a surface that needs the present
 * snapshot asks for it directly. A failed read is logged and retried.
 */
internal class QuickControls(
    private val scope: CoroutineScope,
    private val inputs: Flow<QuickInputs>,
    val commands: QuickTunnelCommands,
    val requests: QuickRequests,
    private val widgets: WidgetUpdater,
    private val shortcuts: ShortcutUpdater,
) {
    /** The one collection upstream of both readers; a stored read never ends it. */
    private val live: SharedFlow<QuickInputs> = inputs
        .retryWhen { cause, attempt ->
            Log.w(TAG, "quick inputs failed: ${cause.javaClass.simpleName}", cause)
            delay(minOf(30_000L, 1_000L shl attempt.coerceAtMost(5).toInt()))
            true
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), replay = 1)

    /** The shared rendered state; null until the first input arrives. */
    val state: StateFlow<QuickState?> =
        live.map(::presentQuickState).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun currentInputs(): QuickInputs = inputs.first()

    suspend fun currentState(): QuickState = presentQuickState(currentInputs())

    fun launchDisconnect(): Job = scope.launch { commands.disconnect() }

    /** D12: keeps every placed home-screen widget current and the launcher's shortcuts in step. */
    fun start() {
        widgets.launchIn(scope, state.filterNotNull())
        shortcuts.launchIn(scope, live)
    }

    /** Re-runs the shortcut sync with the latest inputs; every app resume calls this. */
    fun onAppResumed() {
        shortcuts.onAppResumed()
    }

    /** A newly placed widget asks for the current views, even when the last push was the same. */
    suspend fun refreshWidgets() {
        widgets.refresh(currentState())
    }

    private companion object {
        const val TAG = "QuickControls"
    }
}

/**
 * The seam a system-created component (the shortcut trampoline, the tile, the widget provider
 * and its receiver) uses to reach the process's container. Tests replace it.
 */
internal object QuickControlsAccess {
    @Volatile var forContext: (Context) -> QuickControls =
        { (it.applicationContext as VaayilApplication).container.quickControls }
}
