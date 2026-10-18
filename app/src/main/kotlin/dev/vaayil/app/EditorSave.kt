package dev.vaayil.app

import dev.vaayil.core.model.Profile

/**
 * The editor's save path (F22): read the stored copy BEFORE saving, so the restart can tell
 * what changed; a save that throws leaves the draft and the tunnel alone.
 */
internal suspend fun saveEditedProfile(
    profile: Profile,
    stored: suspend (String) -> Profile?,
    save: suspend (Profile) -> Unit,
    drafts: MutableMap<String, Profile>,
    notices: MutableMap<String, String>,
    restart: (before: Profile?, after: Profile) -> Unit,
) {
    val before = stored(profile.id) // null for a new profile or an import draft
    save(profile)
    drafts.remove(profile.id)
    notices.remove(profile.id) // T11: a saved import's notice (if never shown) goes with it
    restart(before, profile)
}
