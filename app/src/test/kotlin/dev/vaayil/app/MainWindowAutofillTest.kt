package dev.vaayil.app

import android.app.Application
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.BasicText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * L1 (2026-10-18): the main window is out of autofill. `excludeFromAutofill` puts
 * `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` on the decor, so an automatic autofill
 * request sees none of the window's views; a dialog is its own window and stays fillable.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MainWindowAutofillTest {

    private fun launch(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    @Test
    fun `excluding the window takes a plain field out of autofill`() {
        val activity = launch()
        val field = EditText(activity)
        activity.setContentView(field)
        assertTrue("the plain field is important before the exclusion", field.isImportantForAutofill)

        activity.window.excludeFromAutofill()

        assertEquals(
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
            activity.window.decorView.importantForAutofill,
        )
        assertFalse("the plain field is not important after the exclusion", field.isImportantForAutofill)
    }

    @Test
    fun `a compose root that opts itself in still reads as excluded`() {
        val activity = launch()
        activity.setContent { BasicText("x") }
        shadowOf(Looper.getMainLooper()).idle()

        val composeRoot = requireNotNull(findComposeRoot(activity.window.decorView)) {
            "the compose root must be in the window"
        }
        assertTrue("compose's root opts itself in", composeRoot.isImportantForAutofill)

        activity.window.excludeFromAutofill()

        assertFalse("the excluded decor overrides the root's opt-in", composeRoot.isImportantForAutofill)
    }

    @Test
    fun `a dialog keeps its own autofill`() {
        val activity = launch()
        val dialog = Dialog(activity)
        val field = EditText(activity)
        dialog.setContentView(field)
        dialog.show()
        val decorImportanceBefore = dialog.window!!.decorView.importantForAutofill

        activity.window.excludeFromAutofill()

        assertTrue("the dialog's field stays important", field.isImportantForAutofill)
        assertNotEquals(
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
            dialog.window!!.decorView.importantForAutofill,
        )
        assertEquals(decorImportanceBefore, dialog.window!!.decorView.importantForAutofill)
    }

    private fun findComposeRoot(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findComposeRoot(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
