package dev.vaayil.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * C7: the one POST_NOTIFICATIONS ask, shared by onboarding's last card and the root's manual
 * connect gate. The ask is claimed in the flags store ([claimRequest]) before the dialog
 * launches — that claim, not any composable state, is what remembers the request — so skipping
 * onboarding, denying, recreating the activity or connecting later still leaves exactly one ask.
 *
 * Nothing is asked on API < 33 or with the permission already granted; [after] then runs at
 * once. An answered dialog runs [after] whatever the answer was (the tunnel notification is not
 * a blocker), and a claim that cannot be written continues without prompting. Taps while the
 * ask is in flight are ignored, and the marker clears on every exit, so a later tap can ask
 * again when the claim says it is still owed.
 */
@Composable
internal fun rememberNotificationPermissionGate(
    context: Context,
    claimRequest: suspend () -> Boolean,
): (after: () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Result intentionally ignored — no permission is not a blocker.
        val after = pending
        pending = null
        after?.invoke()
    }
    return { after ->
        when {
            pending != null -> Unit
            Build.VERSION.SDK_INT < 33 -> after()
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED -> after()
            else -> {
                pending = after
                scope.launch {
                    // A flag write that fails must never block the connect; cancellation ends
                    // the whole chain with the composition that owns it.
                    val claimed = try {
                        claimRequest()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        false
                    }
                    if (claimed) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else {
                        pending = null
                        after()
                    }
                }
            }
        }
    }
}
