package dev.vaayil.app.quick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.vaayil.app.MainActivity
import dev.vaayil.core.model.isValidProfileId

/**
 * The launcher shortcut's trampoline: it drops one validated request in the process-wide
 * [QuickRequests] slot and reopens the app, then finishes.
 *
 * MainActivity is singleTask, so the reopen brings its task back as it was — an open editor keeps
 * its draft (F30) — instead of starting a second instance. The trampoline inherits the
 * application's empty task affinity, so it runs in a task of its own and leaves none behind.
 * `Theme.NoDisplay` requires the finish in [onCreate].
 */
internal class QuickShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = intent
        if (request?.action == ACTION_CONNECT_SHORTCUT) {
            val profileId = request.getStringExtra(EXTRA_PROFILE_ID)
            if (profileId != null && isValidProfileId(profileId)) {
                QuickControlsAccess.forContext(this).requests.post(profileId)
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
    }

    companion object {
        const val ACTION_CONNECT_SHORTCUT = "dev.vaayil.app.quick.CONNECT_SHORTCUT"
        const val EXTRA_PROFILE_ID = "profile_id"

        /** The explicit intent a shortcut carries: this activity, this action, this profile id. */
        fun intent(context: Context, profileId: String): Intent =
            Intent(context, QuickShortcutActivity::class.java)
                .setAction(ACTION_CONNECT_SHORTCUT)
                .putExtra(EXTRA_PROFILE_ID, profileId)
    }
}
