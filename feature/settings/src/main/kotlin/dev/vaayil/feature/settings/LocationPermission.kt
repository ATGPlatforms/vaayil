package dev.vaayil.feature.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat

/**
 * The Wi-Fi guard's location posture, computed permission-first on every call (never
 * stored), because turning Location on cannot help while a grant is missing (B2):
 *  - FINE missing → [PermissionState.APPROXIMATE_ONLY] when COARSE is granted (Android
 *    12's "Approximate"), else [PermissionState.DENIED];
 *  - FINE granted but background missing on API 29+ → [PermissionState.FOREGROUND_ONLY]
 *    (Android redacts the SSID while the app is off-screen);
 *  - grants complete but Location off on API 27+ → [PermissionState.LOCATION_OFF], where
 *    Android 8.1+ hides the SSID with the switch off; API 26 ignores the switch;
 *  - else → [PermissionState.GRANTED].
 */
enum class PermissionState { UNKNOWN, DENIED, APPROXIMATE_ONLY, FOREGROUND_ONLY, LOCATION_OFF, GRANTED }

/** [PermissionState.UNKNOWN] is only the view model's start value; a platform verdict is never unknown. */
fun permissionStateFor(context: Context): PermissionState {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (coarse) PermissionState.APPROXIMATE_ONLY else PermissionState.DENIED
    }
    if (Build.VERSION.SDK_INT >= 29) {
        val background = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (background != PackageManager.PERMISSION_GRANTED) return PermissionState.FOREGROUND_ONLY
    }
    if (Build.VERSION.SDK_INT >= 27 && !locationEnabled(context)) {
        return PermissionState.LOCATION_OFF
    }
    return PermissionState.GRANTED
}

/**
 * The platform Location switch (callers only ask on API 27+, the level Android starts
 * hiding the SSID with it off). A device without the service counts as on, so the card
 * never claims a pause it cannot observe.
 */
private fun locationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(LocationManager::class.java) ?: return true
    return LocationManagerCompat.isLocationEnabled(manager)
}
