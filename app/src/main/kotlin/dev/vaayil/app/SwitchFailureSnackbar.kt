package dev.vaayil.app

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * K3: shows each switch that failed while another profile's tunnel stayed live, in the shared snackbar host, while
 * the app is on screen. The registry never replays, so a failure that happened while nothing was collecting is
 * never shown late; there is no notification. C2's refused same-profile restarts ride the same host.
 */
@Composable
fun SwitchFailureSnackbar(snackbar: SnackbarHostState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(snackbar, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                // K3's failed switches and C2's refused restarts, each in its own collector so one
                // showing snackbar never starves the other flow. Both use SnackbarDuration.Long
                // (U1's half in this file): they must stay reachable long enough to read.
                launch {
                    ConnectionRegistry.switchFailures.collect {
                        snackbar.showSnackbar(it.message, duration = SnackbarDuration.Long)
                    }
                }
                launch {
                    ConnectionRegistry.restartRefusals.collect {
                        snackbar.showSnackbar(it.message, duration = SnackbarDuration.Long)
                    }
                }
            }
        }
    }
}
