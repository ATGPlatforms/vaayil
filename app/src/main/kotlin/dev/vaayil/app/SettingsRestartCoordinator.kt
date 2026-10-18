package dev.vaayil.app

import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.isLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * C4: a save that lands while its profile is CONNECTING is applied to the tunnel that comes up.
 *
 * [request] never suspends. A save while the profile is CONNECTED restarts at once, as before; a
 * save while it is CONNECTING leaves a pending request — the token — that a watcher on [scope]
 * settles no later than the start's next status: CONNECTED for that same profile restarts it once,
 * and FAILED, DISCONNECTED, DISCONNECTING or another profile consumes the token without a restart.
 * The watcher runs on [scope] and never on the caller's coroutine, so a screen that goes away (or
 * a cancelled save) cannot drop a pending restart: the coordinator belongs on an app-lifetime
 * scope.
 *
 * Requests coalesce. Every save during one CONNECTING merges into the token — one restart, however
 * many saves — and a request landing after the start settled CONNECTED but before the watcher
 * observed it (StateFlow conflates) merges into that same settle. One watcher runs at a time, and
 * it consumes the token under [lock] before restarting, so one settle can produce at most one
 * restart; anything landing later requests afresh.
 */
internal class SettingsRestartCoordinator(
    private val scope: CoroutineScope,
    private val status: StateFlow<TunnelStatus>,
    private val restart: (String) -> Unit,
) {
    private val lock = Any()

    /** The profile the pending restart targets; null with no request outstanding. [lock]. */
    private var pendingId: String? = null

    /** True while a watcher is running or being started; a token always has one. [lock]. */
    private var watching = false

    /**
     * Asks that [profileId] be restarted once its start settles, or nothing when there is no start
     * to wait for. Null targets the profile the tunnel is on now: the split-tunnel rules are
     * app-wide. Returns as soon as the request is registered; a CONNECTED profile restarts before
     * it returns.
     */
    fun request(profileId: String? = null) {
        val current = status.value
        val target = profileId ?: current.profileId ?: return
        // Nothing live for that profile means nothing to re-apply to: a later connect reads the
        // saved settings itself, and a failed or disconnected start left no tunnel running.
        if (current.profileId != target || !current.phase.isLive) return
        synchronized(lock) {
            pendingId = target
            if (watching) return
            watching = true
            // Undispatched so a CONNECTED target restarts before request() returns, and so no
            // request can slip in after the watcher's first look at the status without being
            // seen by it: the launch happens inside the same lock as the token it serves.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { watch() }
        }
    }

    /**
     * Settles the pending request once, then stops. Reads the token and the status afresh each
     * turn, so a request that arrived while this watcher waited is served by the same watcher.
     */
    private suspend fun watch() {
        while (true) {
            val seen = status.value
            when (val settle = synchronized(lock) { settle(seen) }) {
                Settle.Wait -> status.first { it != seen }
                Settle.Stop -> return
                is Settle.Restart -> {
                    restart(settle.profileId)
                    return
                }
            }
        }
    }

    /**
     * [state]'s effect on the pending token. CONNECTING for the token's profile keeps waiting;
     * anything else consumes the token — that start can no longer settle as this profile's
     * CONNECTED — and only CONNECTED for that same profile carries the restart. Callers hold
     * [lock]; only a running watcher calls this, and it always has a token.
     */
    private fun settle(state: TunnelStatus): Settle {
        val id = checkNotNull(pendingId) { "a watcher settled without a pending request" }
        if (state.phase == ConnectionPhase.CONNECTING && state.profileId == id) return Settle.Wait
        pendingId = null
        watching = false
        return if (state.phase == ConnectionPhase.CONNECTED && state.profileId == id) {
            Settle.Restart(id)
        } else {
            Settle.Stop
        }
    }

    /** What one look at the status does: keep waiting, give up, or restart [profileId]. */
    private sealed interface Settle {
        data object Wait : Settle
        data object Stop : Settle
        data class Restart(val profileId: String) : Settle
    }
}
