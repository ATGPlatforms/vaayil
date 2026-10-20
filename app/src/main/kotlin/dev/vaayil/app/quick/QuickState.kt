package dev.vaayil.app.quick

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.isLive

/** A stored profile the quick controls can show by name. */
internal data class QuickProfile(val id: String, val name: String)

internal enum class QuickTone { ACTIVE, WARNING, IDLE, ERROR }

internal sealed interface QuickAction {
    data class Connect(val profileId: String) : QuickAction
    data object Disconnect : QuickAction
    data object OpenApp : QuickAction
}

internal data class QuickInputs(
    val status: TunnelStatus,
    val handshake: HandshakeState,
    val readable: List<QuickProfile>,
    val storedCount: Int,
    val activeId: String?,
    val lastConnectedId: String?,
)

internal data class QuickState(
    val name: String,
    val stateText: String,
    val tone: QuickTone,
    val live: Boolean,
    val action: QuickAction,
)

private const val FALLBACK_LABEL = "Vaayil"

internal fun presentQuickState(inputs: QuickInputs): QuickState {
    val status = inputs.status
    if (status.phase.isLive) {
        val name = status.profileId
            ?.let { id -> inputs.readable.firstOrNull { it.id == id }?.name }
            ?: FALLBACK_LABEL
        val (stateText, tone) = when {
            status.phase == ConnectionPhase.CONNECTED && inputs.handshake == HandshakeState.VERIFIED ->
                "Connected" to QuickTone.ACTIVE
            status.phase == ConnectionPhase.CONNECTED && inputs.handshake == HandshakeState.WAITING ->
                "Waiting for handshake" to QuickTone.WARNING
            else -> "Connecting" to QuickTone.WARNING
        }
        return QuickState(name, stateText, tone, live = true, action = QuickAction.Disconnect)
    }

    if (inputs.readable.isEmpty()) {
        val stateText = if (inputs.storedCount == 0) "Add a profile" else "Open Vaayil"
        return QuickState(FALLBACK_LABEL, stateText, QuickTone.IDLE, live = false, action = QuickAction.OpenApp)
    }

    val failed = status.phase == ConnectionPhase.FAILED
    val failedProfile = if (failed) inputs.readable.firstOrNull { it.id == status.profileId } else null
    if (failedProfile != null) {
        return QuickState(
            failedProfile.name,
            "Couldn't connect",
            QuickTone.ERROR,
            live = false,
            action = QuickAction.Connect(failedProfile.id),
        )
    }

    val target = inputs.readable.firstOrNull { it.id == inputs.activeId }
        ?: inputs.readable.firstOrNull { it.id == inputs.lastConnectedId }
        ?: inputs.readable.first()
    val stateText = if (status.phase == ConnectionPhase.DISCONNECTING) "Disconnecting" else "Disconnected"
    return QuickState(target.name, stateText, QuickTone.IDLE, live = false, action = QuickAction.Connect(target.id))
}

internal data class TileLook(val label: String, val stateText: String, val active: Boolean)

internal fun tileLook(state: QuickState, secureLockScreen: Boolean): TileLook = TileLook(
    label = if (secureLockScreen) FALLBACK_LABEL else state.name,
    stateText = state.stateText,
    active = state.live,
)
