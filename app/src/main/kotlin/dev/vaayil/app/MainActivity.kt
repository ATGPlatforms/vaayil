package dev.vaayil.app

import android.os.Bundle
import android.view.View
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge on the Vellum canvas (device-fix C1): transparent system bars with
        // dark glyphs. Both scrims are the canvas cream — Vellum is light-only.
        enableEdgeToEdge(
            SystemBarStyle.light(0xFFFBFAF7.toInt(), 0xFFFBFAF7.toInt()),
            SystemBarStyle.light(0xFFFBFAF7.toInt(), 0xFFFBFAF7.toInt()),
        )
        // L1: autofill took the editor's private key for a password and offered to save it. The
        // main window's views stay out of automatic autofill; dialogs are their own windows and
        // stay fillable (the backup passphrase).
        window.excludeFromAutofill()
        val container = (application as VaayilApplication).container
        setContent { VaayilTheme.Content { RootNav(container) } }
    }
}

/**
 * L1: an automatic autofill request sees none of this window's views. A descendant reads as
 * unimportant once an ancestor excludes its descendants, and the structure an automatic request
 * builds keeps only important views, so no service is offered the editor's keys. Dialogs are
 * their own windows and stay fillable.
 */
internal fun Window.excludeFromAutofill() {
    decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
}
