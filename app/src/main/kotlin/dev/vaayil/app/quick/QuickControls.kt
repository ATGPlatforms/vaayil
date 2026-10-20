package dev.vaayil.app.quick

import android.content.Context
import dev.vaayil.app.VaayilApplication
import dev.vaayil.core.automation.QuickTunnelCommands
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
 * shortcut trampoline. Construction does no I/O; [state] collects only while a surface is
 * subscribed, and a surface that needs the present snapshot asks for it directly.
 */
internal class QuickControls(
    private val scope: CoroutineScope,
    private val inputs: Flow<QuickInputs>,
    val commands: QuickTunnelCommands,
    val requests: QuickRequests,
    private val widgets: WidgetUpdater,
) {
    /** The shared rendered state; null until the first input arrives. */
    val state: StateFlow<QuickState?> =
        inputs.map(::presentQuickState).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun currentInputs(): QuickInputs = inputs.first()

    suspend fun currentState(): QuickState = presentQuickState(currentInputs())

    fun launchDisconnect(): Job = scope.launch { commands.disconnect() }

    /** D12: keeps every placed home-screen widget current for this hub's lifetime. */
    fun start() {
        widgets.launchIn(scope, state.filterNotNull())
    }

    /** A newly placed widget asks for the current views, even when the last push was the same. */
    suspend fun refreshWidgets() {
        widgets.refresh(currentState())
    }
}

/**
 * The seam a system-created component (the shortcut trampoline today, the tile, receiver and
 * widget provider later) uses to reach the process's container. Tests replace it.
 */
internal object QuickControlsAccess {
    @Volatile var forContext: (Context) -> QuickControls =
        { (it.applicationContext as VaayilApplication).container.quickControls }
}
