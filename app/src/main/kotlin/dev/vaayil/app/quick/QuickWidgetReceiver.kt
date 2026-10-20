package dev.vaayil.app.quick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The widget's disc tap: a connect for the profile the last push showed, or a disconnect of the
 * live tunnel. Both go through [QuickControls.commands], so the connect keeps B1's consent gate
 * (the widget posts the consent notice) and the disconnect bars a late crash resume. The shown id
 * is re-checked against the profiles readable now, so a tap for a profile deleted since the last
 * push sends nothing and posts no notice. Every handled intent refreshes the placed widgets.
 */
class QuickWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { handle(context, intent) }
                    .onFailure { e ->
                        Log.w(TAG, "widget action ${intent.action} failed: ${e.javaClass.simpleName}", e)
                    }
            } finally {
                pending?.finish() // null when a test calls onReceive directly.
            }
        }
    }

    internal suspend fun handle(context: Context, intent: Intent) {
        val controls = QuickControlsAccess.forContext(context)
        when (intent.action) {
            ACTION_CONNECT -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (profileId != null && controls.currentInputs().readable.any { it.id == profileId }) {
                    controls.commands.connect(profileId, noticeWithoutConsent = true)
                }
            }
            ACTION_DISCONNECT -> controls.commands.disconnect()
        }
        controls.refreshWidgets()
    }

    companion object {
        const val ACTION_CONNECT = "dev.vaayil.app.quick.WIDGET_CONNECT"
        const val ACTION_DISCONNECT = "dev.vaayil.app.quick.WIDGET_DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val TAG = "QuickWidgetReceiver"
    }
}
