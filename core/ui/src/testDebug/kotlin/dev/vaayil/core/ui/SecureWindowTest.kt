package dev.vaayil.core.ui

import android.app.Application
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SecureWindowTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun hasSecure(w: Window) = (w.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    @Test
    fun `activity window is flagged while composed and cleared on dispose`() {
        // Composition is entered/left via state: setContent can only be called once per test.
        val show = mutableStateOf(false)
        compose.setContent { if (show.value) SecureWindow() }
        compose.runOnIdle { assertFalse(hasSecure(compose.activity.window)) }
        compose.runOnIdle { show.value = true }
        compose.runOnIdle { assertTrue(hasSecure(compose.activity.window)) }
        compose.runOnIdle { show.value = false } // disposal
        compose.runOnIdle { assertFalse(hasSecure(compose.activity.window)) }
    }

    @Test
    fun `one of two secure screens leaving keeps the activity flagged for the survivor`() {
        // The navigation fade composes both destinations: the arriving screen adds the flag,
        // the departing one disposes. A dispose must not clear a flag another holder still needs.
        val first = mutableStateOf(false)
        val second = mutableStateOf(false)
        compose.setContent {
            if (first.value) SecureWindow()
            if (second.value) SecureWindow()
        }
        compose.runOnIdle { first.value = true; second.value = true }
        compose.runOnIdle { assertTrue(hasSecure(compose.activity.window)) }
        compose.runOnIdle { first.value = false } // e.g. the import screen leaves
        compose.runOnIdle { assertTrue(hasSecure(compose.activity.window)) }
        compose.runOnIdle { second.value = false } // the last holder leaving clears it
        compose.runOnIdle { assertFalse(hasSecure(compose.activity.window)) }
    }

    @Test
    fun `a dialog composed inside gets its own window flagged`() {
        lateinit var dialogWindow: Window
        compose.setContent {
            Dialog(onDismissRequest = {}) {
                SecureWindow()
                dialogWindow = (LocalView.current.parent as DialogWindowProvider).window
            }
        }
        compose.runOnIdle { assertTrue(hasSecure(dialogWindow)) }
    }
}
