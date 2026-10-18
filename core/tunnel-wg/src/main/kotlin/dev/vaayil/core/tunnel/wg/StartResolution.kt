package dev.vaayil.core.tunnel.wg

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus

/**
 * Which profile a start intent targets. An explicit id wins; otherwise the active record;
 * and only for a SYSTEM start (Always-on: null or SERVICE_INTERFACE action) the last
 * connected profile. Always-on is a standing instruction to keep the VPN up, so it survives
 * the manual Disconnect that clears the active record (T-7, ruling 2026-09-10). An in-app
 * ACTION_CONNECT without an id never falls back that far. The two record reads are lambdas:
 * an explicit id (every in-app connect) costs no DataStore read on the main thread.
 */
internal fun startProfileId(
    action: String?,
    explicitId: String?,
    activeId: () -> String?,
    lastConnectedId: () -> String?,
): String? =
    explicitId ?: activeId() ?: if (action != VpnTunnelService.ACTION_CONNECT) lastConnectedId() else null

/** What [EngineProvider.connectById] achieved. */
enum class StartOutcome { STARTED, REFUSED, FAILED }

/**
 * STARTED only when the status [WireGuardEngine.startWithOutcome] snapshotted under the engine lock —
 * the one its own start published — reports CONNECTED for the requested profile, so the outcome belongs
 * to its request and a start queued behind it cannot change it (A6). A start never throws on failure —
 * a failed switch leaves the previous tunnel CONNECTED under ITS id — so that status is the only
 * truthful signal, and the active/last-connected pointers must follow it.
 */
internal fun startOutcome(status: TunnelStatus, requestedId: String): StartOutcome =
    if (status.phase == ConnectionPhase.CONNECTED && status.profileId == requestedId) StartOutcome.STARTED
    else StartOutcome.FAILED
