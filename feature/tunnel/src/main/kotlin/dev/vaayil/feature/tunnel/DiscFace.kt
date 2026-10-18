package dev.vaayil.feature.tunnel

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus

/** What the hero disc shows and does for the profile picked in the pill. */
enum class DiscFace { ACTIVE, SWITCH, BUSY, FAILED, IDLE }

/**
 * ACTIVE only when the connected tunnel IS the selected profile. A different live profile
 * reads SWITCH, so the disc never says "disconnect" and then connects something else (B8).
 * FAILED only when the failure is the selected profile's own (F17b): another profile's failed
 * attempt leaves the selected one IDLE.
 */
fun discFace(status: TunnelStatus, selectedId: String?): DiscFace = when (status.phase) {
    ConnectionPhase.CONNECTED -> if (status.profileId == selectedId) DiscFace.ACTIVE else DiscFace.SWITCH
    ConnectionPhase.CONNECTING, ConnectionPhase.DISCONNECTING -> DiscFace.BUSY
    ConnectionPhase.FAILED ->
        if (status.profileId != null && status.profileId == selectedId) DiscFace.FAILED else DiscFace.IDLE
    ConnectionPhase.DISCONNECTED -> DiscFace.IDLE
}

/**
 * Whether the disc wears the sage window. Connected styling covers SWITCH (a tunnel is up
 * either way, only the action differs), but sage means this tunnel's own handshake happened
 * (G4): PENDING and WAITING keep the canvas window.
 */
fun discWearsSage(face: DiscFace, verified: Boolean): Boolean =
    (face == DiscFace.ACTIVE || face == DiscFace.SWITCH) && verified

/**
 * The phase the global status pill speaks for [phase] when the selected profile's disc shows
 * [face] (T2, sweep 2): the face already decides whose failure a FAILED phase is (F17b —
 * another profile's failure reads IDLE), so the pill asks the face instead of re-encoding
 * the rule beside it. Every other phase passes through.
 */
fun pillPhase(face: DiscFace, phase: ConnectionPhase): ConnectionPhase =
    if (phase == ConnectionPhase.FAILED && face == DiscFace.IDLE) ConnectionPhase.DISCONNECTED
    else phase
