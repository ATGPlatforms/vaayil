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
 * C8/Y2: the VPN consent gate. [prepareVpn] is the platform seam — null means consent is already
 * held — and a test injects its own answer. Whenever consent is missing, [VpnDisclosureDialog]
 * (Play's VpnService prominent disclosure) shows first and nothing launches: Accept hides it and
 * re-checks [prepareVpn] — consent arrived meanwhile runs the action at once, an Intent still
 * launches Android's dialog — and Not now hides it and clears the pending action, so nothing
 * runs and the next request shows the disclosure again. A dialog the user denied or cancelled
 * shows [VPN_CONSENT_DENIED] and never runs the action. Just one dialog at a time: the pending
 * action is set while the disclosure or Android's dialog is up, so taps in that window are
 * ignored, and it clears on either result, so the next tap asks again. The disclosure's state
 * lives in `remember`, never `rememberSaveable`, because an action can't be saved — a recreation
 * closes the disclosure and the next tap shows it again.
 */
@Composable
fun rememberVpnPermissionGate(
    context: Context,
    snackbar: SnackbarHostState,
    prepareVpn: () -> Intent? = { VpnService.prepare(context) },
): (action: () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var disclosureShown by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
        else scope.launch { snackbar.showSnackbar(VPN_CONSENT_DENIED, duration = SnackbarDuration.Long) }
    }
    if (disclosureShown) {
        VpnDisclosureDialog(
            onAccept = {
                disclosureShown = false
                val action = pending
                val intent: Intent? = prepareVpn()
                if (intent == null) {
                    pending = null
                    action?.invoke()
                } else {
                    launcher.launch(intent)
                }
            },
            onNotNow = {
                disclosureShown = false
                pending = null
            },
        )
    }
    return { action ->
        if (pending == null) {
            val intent: Intent? = prepareVpn()
            if (intent == null) action()
            else {
                pending = action
                disclosureShown = true
            }
        }
    }
}

/**
 * C7/C8: the root's manual connect gate. The consent chain runs the VPN disclosure and consent →
 * notification → [action]: the disclosure (Y2) shows only when consent is missing, Accept
 * launches Android's dialog or, when consent arrived meanwhile, carries on as a granted consent
 * does, consent already held runs straight through, a granted consent is followed by the
 * one-time notification ask (two system dialogs never stack), and a denial at either step stops
 * where it is — the VPN denial in the snackbar, the notification denial still connecting.
 * [profileId] is the id `TunnelController.toggle` will act on, so the live profile's tap is the
 * disconnect or cancel it becomes and runs at once, never replaced by a prompt; a null target
 * (no profile to act on) does nothing.
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
