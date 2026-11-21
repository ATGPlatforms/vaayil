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

/**
 * #14: how a connect treats a tunnel that is already up. [RESTART] is today's behaviour and the
 * default, so every sender that names no mode keeps it. [KEEP_ANY] asks only that a tunnel be up,
 * and [KEEP_SAME] asks that the named profile be the one up; a different live profile is still
 * switched to.
 */
enum class ConnectMode { RESTART, KEEP_ANY, KEEP_SAME }

/** What [EngineProvider.connectById] achieved. [KEPT]: a live tunnel already satisfied the request, so nothing started. */
enum class StartOutcome { STARTED, REFUSED, FAILED, KEPT }

/**
 * #14: the mode an intent carries. ACTION_CONNECT reads [VpnTunnelService.EXTRA_CONNECT_MODE]
 * ([ConnectMode.name]); a missing or unknown value is [ConnectMode.RESTART], so every in-app sender
 * keeps today's behaviour. The null action and the system's Always-on start
 * ([android.net.VpnService.SERVICE_INTERFACE]) are standing instructions to keep a tunnel up, so
 * they are [ConnectMode.KEEP_ANY]. The crash resume is an ACTION_CONNECT without the extra, so it
 * restarts.
 */
internal fun connectModeOf(action: String?, modeExtra: String?): ConnectMode = when (action) {
    null, android.net.VpnService.SERVICE_INTERFACE -> ConnectMode.KEEP_ANY
    VpnTunnelService.ACTION_CONNECT -> when (modeExtra) {
        ConnectMode.KEEP_ANY.name -> ConnectMode.KEEP_ANY
        ConnectMode.KEEP_SAME.name -> ConnectMode.KEEP_SAME
        else -> ConnectMode.RESTART
    }
    else -> ConnectMode.RESTART
}

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
