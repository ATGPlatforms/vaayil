package dev.vaayil.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.validate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EditorState(
    val profile: Profile,
    val errors: List<String>,
    val saved: Boolean = false,
    /** Why the last Save did not persist; cleared by the next edit. */
    val saveError: String? = null,
    /** The form differs from what it opened with, or last saved: Back asks before discarding it (F30). */
    val dirty: Boolean = false,
    /** [ProfileEditorViewModel.load] was given an id and found nothing under it, so the form is blank (M3). */
    val missing: Boolean = false,
)

/**
 * An editor's in-progress draft (F30): the profile as the form opened, or as it was last saved,
 * and what the form shows now. The editor's back-stack entry keeps it in memory, so an edit
 * outlives the view model when Android recreates the activity. It holds a private key, so it
 * never goes into a saved-state Bundle; a process death loses it, by design.
 */
data class EditorDraft(val baseline: Profile, val current: Profile)

object NewProfileFactory {
    fun blank(): Profile = Profile(
        id = java.util.UUID.randomUUID().toString(), name = "",
        interfaceConfig = InterfaceConfig("", emptyList(), emptyList(), null, null),
        peer = PeerConfig("", null, "", emptyList(), null),
    )
}

/**
 * The editor's state. [restoreDraft] and [keepDraft] connect it to the draft its back-stack
 * entry keeps (F30): [load] prefers a kept draft over [get], and every change goes to [keepDraft].
 */
class ProfileEditorViewModel(
    private val get: suspend (String?) -> Profile?,
    private val saveFn: suspend (Profile) -> Unit,
    private val restoreDraft: () -> EditorDraft? = { null },
    private val keepDraft: (EditorDraft) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState(NewProfileFactory.blank(), emptyList()))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** What Back compares the form against: the profile as it opened, or as it was last saved. */
    private var baseline: Profile = _state.value.profile

    fun load(profileId: String?) {
        // A recreated editor (Android rebuilt the activity) takes its kept draft back.
        val kept = restoreDraft()
        if (kept != null) {
            baseline = kept.baseline
            _state.value = EditorState(kept.current, kept.current.validate(), dirty = kept.current != kept.baseline)
            return
        }
        viewModelScope.launch {
            val found = get(profileId)
            val profile = found ?: NewProfileFactory.blank()
            baseline = profile
            // An id that finds nothing (an import lost to a process death, M3): the editor's entry
            // decides what to show, and nothing is kept, so a recreation looks again.
            val missing = profileId != null && found == null
            _state.value = EditorState(profile, profile.validate(), missing = missing)
            if (!missing) keepDraft(EditorDraft(profile, profile))
        }
    }

    fun update(transform: (Profile) -> Profile) {
        val next = transform(_state.value.profile)
        _state.value = _state.value.copy(
            profile = next,
            errors = next.validate(),
            saved = false,
            saveError = null,
            dirty = next != baseline,
        )
        keepDraft(EditorDraft(baseline, next))
    }

    fun save(onDone: () -> Unit) {
        val current = _state.value
        if (current.errors.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { saveFn(current.profile) }
                .onSuccess {
                    // Settle against the latest state: an edit made while the write was in
                    // flight must survive, and if one landed the stored profile is already
                    // stale, so this is no longer a clean "saved" state.
                    val latest = _state.value
                    val clean = latest.profile == current.profile
                    // What was written is the new baseline: Back asks only about edits made since.
                    baseline = current.profile
                    _state.value = latest.copy(saved = clean, saveError = null, dirty = latest.profile != baseline)
                    keepDraft(EditorDraft(baseline, latest.profile))
                    // A diverged state still holds an unsaved edit: onDone pops the back stack
                    // and this view model dies with that entry, so leaving would throw the edit
                    // away. Only a clean save navigates.
                    if (clean) onDone()
                }
                .onFailure { e ->
                    // A Keystore hiccup or a rejected row must land as a message, not a crash of this scope.
                    // `saveError` is rendered verbatim, so it comes from the shared mapper (R6).
                    _state.value = _state.value.copy(
                        saved = false,
                        saveError = profileWriteMessage(e, fallback = "Couldn't save the profile."),
                    )
                }
        }
    }
}
