package dev.vaayil.app

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions

/**
 * The one scan configuration both QR launchers (Profiles tab, Import screen) use. The
 * framework's own camera-denied dialog is suppressed (C6) so the snackbar below is the only
 * surface that explains the refusal.
 */
internal fun wireGuardScanOptions(): ScanOptions = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt("Scan WireGuard QR")
    addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false)
}

/**
 * Turns one scan result into either imported text or a snackbar, for both launchers.
 * A refused camera permission is reported before the contents are looked at — the framework
 * returns it as a cancelled result with no scan text — and an ordinary cancel stays silent.
 * [onContents] receives the scanned text and runs the caller's own import continuation.
 */
internal suspend fun handleQrScanResult(
    result: ScanIntentResult,
    snackbar: SnackbarHostState,
    onContents: (String) -> Unit,
) {
    if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
        snackbar.showSnackbar(
            "Vaayil needs camera access to scan a QR code.",
            duration = SnackbarDuration.Long,
        )
        return
    }
    val text = result.contents ?: return
    onContents(text)
}
