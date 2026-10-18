package dev.vaayil.core.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import dev.vaayil.core.model.AutoConnectRules

/**
 * Title and text of the automation notification (F2). Android hides the Wi-Fi name without the
 * background location grant, without precise location (Approximate) and — from API 27 on — while
 * Location is off: the notification must not claim the Wi-Fi rules run in any of those states. The
 * first matching row wins: background grant, then precise location, then the Location switch.
 */
internal fun guardNotificationCopy(
    rules: AutoConnectRules,
    backgroundLocation: Boolean,
    preciseLocation: Boolean,
    locationEnabled: Boolean,
): Pair<String, String?> {
    if (!rules.wifiGuardEnabled) return "Vaayil auto-connect is on" to null
    val missing = when {
        !backgroundLocation -> "allow location all the time"
        !preciseLocation -> "turn on precise location"
        !locationEnabled -> "turn on Location"
        else -> return "Vaayil auto-connect is on" to null
    }
    return if (rules.autoConnectOnMobileData) {
        "Vaayil auto-connect is on" to "Wi-Fi rules are paused: $missing."
    } else {
        "Vaayil auto-connect is paused" to
            "${missing.replaceFirstChar { it.uppercase() }} so Vaayil can see which Wi-Fi you're on."
    }
}

/**
 * B2: what the platform lets the Wi-Fi rules see, sampled in one place. [backgroundLocation]
 * follows the platform's own grant: BACKGROUND alone on API 31+ (Approximate + "Allow all the
 * time" is still a background grant), and [hasBackgroundLocation] before that, where one FINE
 * grant covered both. [precision][preciseLocation] is FINE. [locationEnabled] is the Location
 * switch: API 26 cannot hide a name with it, so the snapshot always reads enabled there; a missing
 * or unreadable manager on API 27+ reads off (fail closed).
 */
internal data class GuardLocationState(
    val backgroundLocation: Boolean,
    val preciseLocation: Boolean,
    val locationEnabled: Boolean,
)

/** B2: the platform state that decides whether the Wi-Fi name reaches the rules. */
internal fun guardLocationState(context: Context): GuardLocationState = GuardLocationState(
    backgroundLocation = if (Build.VERSION.SDK_INT >= 31) {
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    } else {
        hasBackgroundLocation(context)
    },
    preciseLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
    locationEnabled = if (Build.VERSION.SDK_INT >= 27) isLocationEnabled(context) else true,
)

/** Fine location, plus background location from Android 10 on, where it became a separate grant. */
internal fun hasBackgroundLocation(context: Context): Boolean {
    val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (Build.VERSION.SDK_INT < 29) return fine
    return fine &&
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
}

/** The Location switch on API 27+; anything unreadable counts as off, never as on. */
private fun isLocationEnabled(context: Context): Boolean = try {
    val manager = context.getSystemService(LocationManager::class.java) ?: return false
    LocationManagerCompat.isLocationEnabled(manager)
} catch (_: Exception) {
    false
}

/**
 * Without background location the Wi-Fi rules are paused outright (F28): an SSID visible only while
 * Vaayil is in the foreground would flip null -> SSID on every reopen and fire the rules.
 */
internal fun ssidForRules(ssid: String?, backgroundLocation: Boolean): String? = if (backgroundLocation) ssid else null
