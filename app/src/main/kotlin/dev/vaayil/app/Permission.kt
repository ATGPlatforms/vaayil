package dev.vaayil.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.vaayil.core.tunnel.api.isLive
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import kotlinx.coroutines.launch

/**
 * C8: the VPN consent gate. [prepareVpn] is the platform seam — null means consent is already
 * held — and a test injects its own answer. A dialog the user denied or cancelled shows
 * [VPN_CONSENT_DENIED] and never runs the action; just one dialog at a time, so taps while it is
 * up are ignored, and the pending action clears on either result, so the next tap asks again.
 */
@Composable
fun rememberVpnPermissionGate(
    context: Context,
    snackbar: SnackbarHostState,
    prepareVpn: () -> Intent? = { VpnService.prepare(context) },
): (action: () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
        else scope.launch { snackbar.showSnackbar(VPN_CONSENT_DENIED, duration = SnackbarDuration.Long) }
    }
    return { action ->
        if (pending == null) {
            val intent: Intent? = prepareVpn()
            if (intent == null) action()
            else {
                pending = action
                launcher.launch(intent)
            }
        }
    }
}

/**
 * C7/C8: the root's manual connect gate. The consent chain runs VPN → notification → [action]:
 * consent already held runs straight through, a granted consent is followed by the one-time
 * notification ask (two system dialogs never stack), and a denial at either step stops where it
 * is — the VPN denial in the snackbar, the notification denial still connecting. [profileId] is
 * the id `TunnelController.toggle` will act on, so the live profile's tap is the disconnect or
 * cancel it becomes and runs at once, never replaced by a prompt; a null target (no profile to
 * act on) does nothing.
 */
@Composable
internal fun rememberManualConnectionGate(
    container: AppContainer,
    snackbar: SnackbarHostState,
    prepareVpn: (Context) -> Intent? = VpnService::prepare,
): (profileId: String?, action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val vpnGate = rememberVpnPermissionGate(context, snackbar, prepareVpn = { prepareVpn(context) })
    val notificationGate = rememberNotificationPermissionGate(context, container::claimNotificationPermissionRequest)
    return { profileId, action ->
        if (profileId != null) {
            val status = ConnectionRegistry.status.value
            if (status.profileId == profileId && status.phase.isLive) action()
            else vpnGate { notificationGate(action) }
        }
    }
}

/** C8: the copy names the state, not a choice the user may never have made. */
private const val VPN_CONSENT_DENIED =
    "Vaayil didn't get VPN permission. If another app is set as Always-on VPN, turn that off first."
