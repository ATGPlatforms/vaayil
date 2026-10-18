package dev.vaayil.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C5 + C6: the one scan path both launchers share. The options must be QR-only, prompted, and
 * must suppress the framework's own camera-denied dialog so Vaayil's own snackbar is the only
 * one; a result that denies the camera permission must say so instead of importing nothing in
 * silence. [SnackbarHostState] is a plain state machine, so no UI needs composing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QrScanTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `both launchers scan QR only and suppress the framework camera dialog`() {
        val intent = wireGuardScanOptions().createScanIntent(context)
        assertEquals(ScanOptions.QR_CODE, intent.getStringExtra(Intents.Scan.FORMATS))
        assertEquals("Scan WireGuard QR", intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE))
        assertTrue(intent.hasExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG))
        assertFalse(intent.getBooleanExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, true))
    }

    @Test
    fun `a denied camera permission shows the long snackbar and imports nothing`() {
        // What CaptureManager returns when the camera permission was refused: CANCELLED
        // with MISSING_CAMERA_PERMISSION and no scan contents.
        val denied = Intent().putExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, true)
        val result = ScanIntentResult.parseActivityResult(Activity.RESULT_CANCELED, denied)
        val snackbar = SnackbarHostState()
        var imported: String? = null
        CoroutineScope(Dispatchers.Unconfined).launch {
            handleQrScanResult(result, snackbar) { imported = it }
        }
        val data = snackbar.currentSnackbarData
        assertNotNull("the camera-denied snackbar must show", data)
        assertEquals("Vaayil needs camera access to scan a QR code.", data!!.visuals.message)
        assertEquals(SnackbarDuration.Long, data.visuals.duration)
        assertNull(imported)
        data.dismiss()
    }

    @Test
    fun `the camera flag wins over any text the result carries`() {
        // The flag is checked before the contents: a denial is never imported as a profile.
        val denied = Intent()
            .putExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, true)
            .putExtra(Intents.Scan.RESULT, "[Interface]\nPrivateKey = eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=\n")
        val result = ScanIntentResult.parseActivityResult(Activity.RESULT_CANCELED, denied)
        val snackbar = SnackbarHostState()
        var imported: String? = null
        CoroutineScope(Dispatchers.Unconfined).launch {
            handleQrScanResult(result, snackbar) { imported = it }
        }
        val data = snackbar.currentSnackbarData
        assertNotNull("the camera-denied snackbar must show", data)
        assertEquals("Vaayil needs camera access to scan a QR code.", data!!.visuals.message)
        assertNull(imported)
        data.dismiss()
    }

    @Test
    fun `an ordinary cancel is silent`() {
        for (intent in listOf<Intent?>(null, Intent())) {
            val result = ScanIntentResult.parseActivityResult(Activity.RESULT_CANCELED, intent)
            val snackbar = SnackbarHostState()
            var imported: String? = null
            runBlocking { handleQrScanResult(result, snackbar) { imported = it } }
            assertNull(snackbar.currentSnackbarData)
            assertNull(imported)
        }
    }

    @Test
    fun `a successful scan hands its text over once`() {
        val text = "[Interface]\nPrivateKey = eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=\nAddress = 10.0.0.2/32\n"
        val result = ScanIntentResult.parseActivityResult(
            Activity.RESULT_OK,
            Intent().putExtra(Intents.Scan.RESULT, text),
        )
        val snackbar = SnackbarHostState()
        val imported = mutableListOf<String>()
        runBlocking { handleQrScanResult(result, snackbar) { imported.add(it) } }
        assertEquals(listOf(text), imported)
        assertNull(snackbar.currentSnackbarData)
    }
}
