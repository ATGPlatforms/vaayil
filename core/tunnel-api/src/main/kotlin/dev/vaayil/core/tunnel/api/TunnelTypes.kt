package dev.vaayil.core.tunnel.api

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, FAILED }

/** User-facing label for a phase; single source shared by every surface that shows one. */
val ConnectionPhase.uiLabel: String
    get() = when (this) {
        ConnectionPhase.DISCONNECTED -> "Disconnected"
        ConnectionPhase.CONNECTING -> "Connecting"
        ConnectionPhase.CONNECTED -> "Connected"
        ConnectionPhase.DISCONNECTING -> "Disconnecting"
        ConnectionPhase.FAILED -> "Failed"
    }

/** A tunnel exists or is being brought up: the phases every disconnect path must act on. */
val ConnectionPhase.isLive: Boolean
    get() = this == ConnectionPhase.CONNECTED || this == ConnectionPhase.CONNECTING

sealed interface TunnelError {
    data class ParseError(val message: String) : TunnelError
    data object PermissionDenied : TunnelError
    data class DnsFailure(val endpoint: String) : TunnelError
    data object HandshakeTimeout : TunnelError
    /** The in-tunnel resolver itself failed to start (bind/listen/bootstrap) — distinct from
        a backend or endpoint failure so the B1 class of failures is identifiable on device. */
    data class DnsResolverFailure(val detail: String) : TunnelError
    data class BackendError(val message: String) : TunnelError
}

/**
 * A live session's condition the dashboard shows as a note (sweep 2, C2/C3). A notice never rides
 * [TunnelStatus.error]: a session can be CONNECTED and still carry one.
 */
sealed interface TunnelNotice {
    /** A same-profile restart could not apply its settings; the session still runs the previous ones. */
    data object SettingsNotApplied : TunnelNotice

    /** The DNS channel died and could not be reopened; queries are dropped until the next start. */
    data object DnsNotAnswering : TunnelNotice
}

/** G4 (spec §4.4.1): the live tunnel's handshake condition, shared by both features. */
enum class HandshakeState { NONE, PENDING, WAITING, VERIFIED }

data class TunnelStatus(
    val phase: ConnectionPhase,
    val profileId: String?,
    val error: TunnelError? = null,
    val notice: TunnelNotice? = null,
    /** G4 (spec §4.4.2): the start this CONNECTED session belongs to; 0 off a live session. */
    val generation: Long = 0,
) {
    companion object { val idle = TunnelStatus(ConnectionPhase.DISCONNECTED, null, null) }
}

data class TunnelStats(
    val handshakeAt: Long?,
    val rxBytes: Long,
    val txBytes: Long,
    /** G4 (spec §4.4.2): the generation of the start whose sampler produced this sample. */
    val generation: Long = 0,
) {
    companion object { val empty = TunnelStats(null, 0, 0) }
}
