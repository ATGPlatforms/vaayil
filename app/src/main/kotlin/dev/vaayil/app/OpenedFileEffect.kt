package dev.vaayil.app

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Opens an opened file's review as its own editor entry over whatever the screen shows. Never
 * single-top: reusing an editor already on top would replace its kept draft with this review
 * (c1), while a new entry leaves it beneath, intact (F30).
 */
internal fun NavHostController.pushOpenedFileReview(draftId: String) {
    navigate("editor?draftId=${Uri.encode(draftId)}")
}

/**
 * Serves the queue of files opened from another app while the screen is started: a review is
 * pushed over the tabs, onboarding or another editor, and a failure goes to [onFailed]. The
 * queue hands each outcome out once ([OpenedFiles.take] removes it), so a recreation collects
 * an emptied queue and never repeats an open.
 */
@Composable
internal fun OpenedFileEffect(
    opened: OpenedFiles,
    nav: NavHostController,
    onFailed: (message: String) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(opened, nav, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            opened.pending.collect { queue ->
                if (queue.isEmpty()) return@collect
                while (true) {
                    when (val outcome = opened.take() ?: break) {
                        is OpenedFileOutcome.Review -> nav.pushOpenedFileReview(outcome.draftId)
                        is OpenedFileOutcome.Failed -> onFailed(outcome.message)
                    }
                }
            }
        }
    }
}

/**
 * Shows opened-file failures one at a time, in the Import screen's Short snackbar (c6). While one
 * waits for the snackbar or shows, a further failure is dropped, whatever it says: another app can
 * open files faster than anyone can read about them, and a queue of their errors would hold back
 * every other notice, a failed switch (K3) among them. `openFile` still logs each failure.
 */
internal class OpenedFileFailureNotice(
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    private val active = AtomicBoolean(false)

    fun show(message: String) {
        if (!active.compareAndSet(false, true)) return
        scope.launch {
            try {
                snackbar.showSnackbar(message)
            } finally {
                active.set(false)
            }
        }
    }
}
