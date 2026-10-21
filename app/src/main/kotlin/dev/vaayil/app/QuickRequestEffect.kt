package dev.vaayil.app

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import dev.vaayil.app.quick.QuickRequests
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.isLive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

private const val TAG = "QuickRequestEffect"

internal enum class QuickRequestOutcome { DROPPED, ALREADY_LIVE, GATED }

/**
 * Turns a quick surface's request (a launcher shortcut, or the tile when a connect needs VPN
 * consent) into the same action a tap inside the app takes. A request never overrides
 * onboarding, never touches a live profile, and never connects one that cannot be read. On a tab
 * other than the dashboard the dashboard comes up first, so any consent dialog has the disc
 * behind it; a pushed route (an editor draft, F30) is left where it is.
 */
internal suspend fun routeQuickRequest(
    profileId: String,
    currentRoute: String?,
    status: () -> TunnelStatus,
    lookup: suspend (String) -> Profile?,
    showDashboard: () -> Unit,
    gate: (profileId: String?, action: () -> Unit) -> Unit,
    connect: (Profile) -> Unit,
): QuickRequestOutcome {
    if (currentRoute == "onboarding") return QuickRequestOutcome.DROPPED
    if (isLiveFor(profileId, status())) return QuickRequestOutcome.ALREADY_LIVE
    val profile = lookup(profileId)
    if (profile == null) {
        Log.i(TAG, "A quick request named a profile that isn't readable; dropped")
        return QuickRequestOutcome.DROPPED
    }
    if (currentRoute in TAB_ROUTES && currentRoute != "dashboard") showDashboard()
    gate(profileId) {
        // The dialogs may have been up long enough for the profile to go live meanwhile.
        if (!isLiveFor(profileId, status())) connect(profile)
    }
    return QuickRequestOutcome.GATED
}

private fun isLiveFor(profileId: String, status: TunnelStatus): Boolean =
    status.profileId == profileId && status.phase.isLive

/**
 * Services the hub's pending request while the screen is started. A request that lands while the
 * app is stopped stays in the slot and is picked up on return, unless it has expired by then.
 */
@Composable
internal fun QuickRequestEffect(
    requests: QuickRequests,
    nav: NavHostController,
    onRequest: suspend (profileId: String, currentRoute: String?) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(requests, nav, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            requests.pending.filterNotNull().collect {
                val profileId = requests.take() ?: return@collect
                val route = nav.currentBackStackEntryFlow.first().destination.route?.substringBefore('?')
                onRequest(profileId, route)
            }
        }
    }
}
