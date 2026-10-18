package dev.vaayil.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.vaayil.feature.settings.PermissionState
import dev.vaayil.feature.settings.permissionStateFor

/**
 * C1: the Wi-Fi guard's disclosure launches one request for precise and approximate
 * location together — targetSdk 31+ ignores a FINE-only ask — and continues to background
 * location only after FINE, when it is still missing (API 29+). Every answer lands as a
 * [permissionStateFor] verdict through [onResult]; the returned lambda starts the chain.
 */
@Composable
internal fun rememberLocationPermissionRequest(onResult: (PermissionState) -> Unit): () -> Unit {
    val context = LocalContext.current
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // SDK 30+ routes this ask to the app's location settings page; the entry recompute
        // observes whatever the user chose when they come back.
        onResult(permissionStateFor(context))
    }
    val foregroundLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val state = permissionStateFor(context)
            if (state == PermissionState.FOREGROUND_ONLY) {
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                onResult(state)
            }
        }
    return {
        foregroundLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
}
