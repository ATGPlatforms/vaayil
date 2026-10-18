package dev.vaayil.app

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Runs [drop] when the activity finishes for good (spec U7: the in-memory drafts end with the
 * task, closing the import drafts' leak). A rotation or "Don't keep activities" destroys the
 * activity without finishing it, so the drafts survive those (F30).
 */
@Composable
internal fun DropDraftsOnFinish(drop: () -> Unit) {
    // LocalActivity, never a cast of LocalContext: lint's ContextCastToActivity check is an error.
    val activity = LocalActivity.current
    val currentDrop by rememberUpdatedState(drop)
    DisposableEffect(activity) {
        onDispose { if (activity?.isFinishing == true) currentDrop() }
    }
}
