package dev.vaayil.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * M5: every resume sends the guard service its start command while a guard rule is on
 * ([needsGuard]). A permission granted in system settings then takes effect on return: the
 * service re-reads it on each start (K4) and refreshes its notification copy (F2). At ON_RESUME
 * the app is in the foreground, so Android 12+'s background foreground-service start limit does
 * not apply — and a consented VPN app is exempt from it in any case (REASON_OP_ACTIVATE_VPN,
 * AOSP ActiveServices). Both catches stay defensive: a start the platform refuses for any other
 * reason, or an unreadable rules store, is dropped silently and the next resume asks again.
 */
@Composable
internal fun GuardServiceResumeEffect(needsGuard: suspend () -> Boolean, start: () -> Unit) {
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch {
            try {
                if (needsGuard()) start()
            } catch (_: IllegalStateException) {
                // A refused start (ForegroundServiceStartNotAllowedException among them).
            } catch (_: IOException) {
                // The stored rules could not be read; the next resume asks again.
            }
        }
    }
}
