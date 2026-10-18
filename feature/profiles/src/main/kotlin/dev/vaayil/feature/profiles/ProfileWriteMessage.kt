package dev.vaayil.feature.profiles

import dev.vaayil.core.data.AtRestUnavailableException
import dev.vaayil.core.data.InvalidProfileException

/**
 * The one message for a profile write that failed (R6). The editor's Save, Duplicate and the
 * delete snackbar's Undo all go through it, so no exception text reaches the UI (a throwable's
 * message can name table columns or worse):
 * - an at-rest outage gets the Keystore sentence;
 * - a profile the rules reject gets [InvalidProfileException]'s own sentence (T9: our copy,
 *   spelled once in the exception): "Profile is invalid: " plus its first validation error.
 *   The exception's message is the one spelling; the `?:` arm falls back to the caller's
 *   copy instead of keeping a second one here (final review minor);
 * - anything else gets the caller's [fallback].
 */
fun profileWriteMessage(e: Throwable, fallback: String): String = when (e) {
    is AtRestUnavailableException ->
        "Profiles can't be read or saved while the Android Keystore is unavailable. Try again in a moment."
    is InvalidProfileException -> e.message ?: fallback
    else -> fallback
}
