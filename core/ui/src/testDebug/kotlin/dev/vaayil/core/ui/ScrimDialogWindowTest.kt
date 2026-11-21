package dev.vaayil.core.ui

import android.app.Application
import android.os.Build
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * w8 (spec §4.7): the dialog's window must not fit the system windows, or the platform pans
 * it under the keyboard instead of shrinking the sheet. Compose reads that one flag and asks
 * the window for ADJUST_RESIZE below 31; from 31 it asks for ADJUST_NOTHING, and the content's
 * safe-drawing padding does the work. The build pins this pair.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26, 30, 31, 35], application = Application::class)
class ScrimDialogWindowTest {
    @get:Rule val compose = createComposeRule()

    private fun showDialog() {
        compose.setContent {
            MaterialTheme {
                ScrimDialog(onDismissRequest = {}) { Text("Body text") }
            }
        }
        compose.waitForIdle()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the dialog window adjusts for the keyboard below 31 and leaves it alone from 31`() {
        showDialog()
        val window = requireNotNull(ShadowDialog.getLatestDialog().window) { "the dialog has no window" }
        val adjust = window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        val expected = if (Build.VERSION.SDK_INT < 31) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        assertEquals(
            "softInputMode adjust bits at SDK ${Build.VERSION.SDK_INT}",
            expected,
            adjust,
        )
    }
}
