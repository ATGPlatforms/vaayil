package dev.vaayil.app

import dev.vaayil.core.config.WgConfigParser
import dev.vaayil.core.config.toProfile
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.importedProfileName

/** A parsed import awaiting review, and the `DNS =` search domains the profile could not keep (F19). */
internal data class ImportDraft(val profile: Profile, val droppedSearchDomains: List<String>)

/**
 * Parses an imported config into an in-memory draft: nothing is written (F18 — the old
 * `ProfileRepository.import` saved first, so Back could not cancel). Bad input throws the
 * parser's [WgConfigParser.ParseException] exactly like the old import path did; the name is
 * then made unique against [taken]. Pure: the draft still must be stored by the caller.
 */
internal fun parseImportDraft(text: String, name: String, taken: Set<String>): ImportDraft {
    val config = WgConfigParser().parse(text)
    val profile = config.toProfile(name = importedProfileName(name))
    return ImportDraft(profile.copy(name = uniqueProfileName(profile.name, taken)), config.ignoredDnsEntries)
}

/** The review's notice for the search domains an import left out (F19), or null when it kept them all. */
internal fun searchDomainNotice(dropped: List<String>): String? =
    if (dropped.isEmpty()) {
        null
    } else {
        "Search domains aren't supported, so these were left out: ${dropped.joinToString(", ")}."
    }

/**
 * The import banner's text for a failed import: the parser's own message, which never echoes the
 * config (it carries keys), or null for anything else, so the screen falls back to its generic
 * copy. No raw exception text reaches the UI.
 */
internal fun importFailureMessage(e: Throwable): String? = (e as? WgConfigParser.ParseException)?.message
