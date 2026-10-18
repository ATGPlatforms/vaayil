package dev.vaayil.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/**
 * Marks every window this composable renders into as secure (FLAG_SECURE) while it is
 * composed: the system then blanks it in screenshots, the recents thumbnail and screen
 * recordings (S-5, A-3). Sets the flag on the activity window and, when composed inside
 * a dialog, on the dialog's own window; both are cleared on dispose so the rest of the
 * app keeps normal window behaviour. Use wherever a config's private key is on screen:
 * the QR share dialog and the profile editor.
 *
 * The flag belongs to the window, not to this composable, so holders are reference-counted:
 * a navigation fade composes the arriving editor before the departing import screen disposes
 * (and a share-QR dialog sits inside the editor), and an un-counted dispose would clear the
 * flag the survivor just set.
 */
@Composable
fun SecureWindow() {
    val view: View = LocalView.current
    DisposableEffect(view) {
        val activityWindow = view.context.activityWindow()
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val held = listOfNotNull(activityWindow, dialogWindow)
        held.forEach { it.retainSecure() }
        onDispose { held.forEach { it.releaseSecure() } }
    }
}

/** Live holder count per window. Compose effects run on the main thread, the only thread here. */
private val secureHolders = WeakHashMap<Window, Int>()

private fun Window.retainSecure() {
    secureHolders[this] = (secureHolders[this] ?: 0) + 1
    addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

private fun Window.releaseSecure() {
    val remaining = (secureHolders[this] ?: 1) - 1
    if (remaining > 0) {
        secureHolders[this] = remaining
    } else {
        secureHolders.remove(this)
        clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private fun Context.activityWindow(): Window? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}
