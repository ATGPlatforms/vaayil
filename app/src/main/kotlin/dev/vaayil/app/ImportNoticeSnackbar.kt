package dev.vaayil.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * F19: as an import's review opens, says once which search domains the import left out. The
 * notice is taken out of [notices] before it shows, so a rotation or a return to the same
 * review never repeats it. Leaving the review cancels the effect, and the snackbar with it.
 */
@Composable
internal fun ImportNoticeSnackbar(draftId: String?, notices: MutableMap<String, String>, snackbar: SnackbarHostState) {
    LaunchedEffect(draftId) {
        val notice = draftId?.let { notices.remove(it) } ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
    }
}
