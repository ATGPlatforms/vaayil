package dev.vaayil.app

import android.annotation.SuppressLint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.compose.dropUnlessStarted
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import dev.vaayil.core.model.Profile
import dev.vaayil.core.ui.ScrimDialog
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumButtonRole
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumQuietAction
import dev.vaayil.feature.profiles.EditorDraft
import dev.vaayil.feature.profiles.ProfileEditorScreen
import dev.vaayil.feature.profiles.ProfileEditorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The editor's route pattern. `RootNavGraph` registers it. */
internal const val EDITOR_ROUTE = "editor?profileId={profileId}&draftId={draftId}"

/**
 * Opens an import's review in place of the Import screen, so Back from the review lands where
 * the import started: the Profiles tab (U7).
 */
internal fun NavHostController.openImportReview(draftId: String) {
    navigate("editor?draftId=${Uri.encode(draftId)}") {
        popUpTo("import") { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Every entry on the back stack. `currentBackStack` is the only way to ask about an entry other
 * than the topmost one (an editor with a second editor pushed over it, F30); it is public but
 * marked library-group-only, so the app needs lint's leave for it.
 */
@SuppressLint("RestrictedApi")
private fun NavHostController.backStackEntries(): List<NavBackStackEntry> = currentBackStack.value

/**
 * The profile editor as one back-stack entry: its view model, the draft the entry keeps, and
 * Back (F30). `EditorRoute` passes the stores in; the tests drive it over a real NavHost.
 * - [drafts] keeps each open editor's draft under its back-stack entry id, so the new view model
 *   of a recreated activity takes the edit back. Memory only: the private key never goes into a
 *   saved-state Bundle, and a process death loses the draft, by design.
 * - [pendingDrafts] holds the unsaved imports (F18), keyed by draft id.
 * - [importNotices] the search-domain notice of each import draft, keyed like [pendingDrafts];
 *   a review that never opened leaves it to be dropped with the draft.
 * - [noticeScope] outlives the entry, so a notice shown as the entry leaves stays up (M3).
 */
@Composable
internal fun EditorEntry(
    nav: NavHostController,
    entryId: String,
    profileId: String?,
    draftId: String?,
    drafts: MutableMap<String, EditorDraft>,
    pendingDrafts: MutableMap<String, Profile>,
    importNotices: MutableMap<String, String>,
    snackbar: SnackbarHostState,
    noticeScope: CoroutineScope,
    get: suspend (String?) -> Profile?,
    saveFn: suspend (Profile) -> Unit,
) {
    // Keyed per profile id (or draft id): the factory — and thus load() — runs once per view
    // model. A rotation keeps the view model; a recreated activity's new one takes the kept
    // draft back.
    val vm: ProfileEditorViewModel = viewModel(key = draftId ?: profileId) {
        ProfileEditorViewModel(
            get = get,
            saveFn = saveFn,
            restoreDraft = { drafts[entryId] },
            keepDraft = { drafts[entryId] = it },
        ).apply { load(draftId ?: profileId) }
    }
    // Leaving the editor drops its draft, and an unsaved import with it (F18: Back must
    // cancel). Back, Save and Discard pop the entry before its content leaves composition, so
    // it is no longer on the back stack. A rotation, an activity teardown or a screen opened
    // over the editor leave the entry in place, and the draft with it (F30) — a second editor
    // pushed over this one (an opened file's review) included. A saved import was already
    // removed by the save path.
    DisposableEffect(entryId, draftId) {
        onDispose {
            val stillOpen = nav.backStackEntries().any { it.id == entryId }
            if (!stillOpen) {
                drafts.remove(entryId)
                if (draftId != null) {
                    pendingDrafts.remove(draftId)
                    // T11: a notice for a review that never showed dies with its draft.
                    importNotices.remove(draftId)
                }
            }
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    // C3: every way out of the editor goes through one guarded leave. Each activation re-checks
    // the entry's lifecycle, so a leave arriving after the entry was popped — its exit
    // transition still running, or its arrow still on screen — is dropped; and the pop itself
    // refuses when nothing sits below the editor, so the start destination can never go.
    val leave = dropUnlessResumed { nav.popIfNotRoot() }
    // A save may finish with the app stopped, and the entry is only STARTED then: dropping below
    // STARTED alone keeps that pop while still refusing one from an entry that is already gone.
    val leaveAfterSave = dropUnlessStarted { nav.popIfNotRoot() }
    if (draftId != null && state.missing) {
        // M3: the import this review was opened for died with the process, so there is nothing
        // to review. Say so and land on the Profiles tab, never on a blank form — but only
        // while the tabs are on the back stack: before onboarding ends there is no tab to land
        // on, so the screen beneath stays. Popping first keeps the review out of the tab's
        // saved state. Deliberately not lifecycle-gated: this effect runs while the entry may
        // be only STARTED.
        LaunchedEffect(entryId) {
            noticeScope.launch { snackbar.showSnackbar("This import was interrupted. Import the file again.") }
            nav.popIfNotRoot()
            if (nav.currentDestination?.route != "profiles" &&
                nav.backStackEntries().any { it.destination.route == "dashboard" }
            ) {
                nav.navigateTab("profiles")
            }
        }
    } else {
        EditorBackGuard(dirty = state.dirty, onLeave = leave) { onBack ->
            PushScreen(onBack = onBack) {
                ProfileEditorScreen(
                    state = state,
                    onField = vm::update,
                    onSave = { vm.save { leaveAfterSave() } },
                )
            }
        }
    }
}

/**
 * Back from an editor with unsaved changes asks before discarding them; an unchanged editor
 * leaves at once (F30). One decision for the system Back gesture and the header's arrow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorBackGuard(
    dirty: Boolean,
    onLeave: () -> Unit,
    content: @Composable (onBack: () -> Unit) -> Unit,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    // C3: raising the dialog re-checks the entry's lifecycle too, so a Back that arrives after
    // the entry was popped cannot put "Discard changes?" over the screen that took its place.
    val ask = dropUnlessResumed { asking = true }
    // Registered inside the entry, so while enabled it runs before NavHost's own Back handling.
    BackHandler(enabled = dirty) { ask() }
    content { if (dirty) ask() else onLeave() }
    if (asking) {
        ScrimDialog(onDismissRequest = { asking = false }) {
            Text("Discard changes?", style = MaterialTheme.typography.headlineLarge, color = VellumColors.OnSurface)
            // Ends at the right like the other confirm dialogs; at large font scales "Discard"
            // drops below "Keep editing" instead of clipping (F29).
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                VellumQuietAction("Keep editing", { asking = false })
                // Destructive, but never a red fill: the secondary construction with the label in error-mark.
                VellumButton(
                    "Discard",
                    { asking = false; onLeave() },
                    role = VellumButtonRole.SECONDARY,
                    labelColor = VellumColors.ErrorMark,
                )
            }
        }
    }
}
