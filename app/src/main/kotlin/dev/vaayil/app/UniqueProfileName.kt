package dev.vaayil.app

import dev.vaayil.core.model.MAX_PROFILE_NAME_LENGTH

/**
 * First case-insensitively free name derived from [base]: `base` itself when free, otherwise
 * `base (2)`, `base (3)`, … The result never exceeds [maxLength] — an over-long base is capped
 * first, and a taken one is trimmed further so the numeric suffix still fits (F26: a plain
 * 48-character cut turned "… (copy)" back into the original's name and collided).
 */
internal fun uniqueProfileName(
    base: String,
    taken: Set<String>,
    maxLength: Int = MAX_PROFILE_NAME_LENGTH,
): String {
    fun free(candidate: String) = taken.none { it.equals(candidate, ignoreCase = true) }
    val capped = base.take(maxLength)
    if (free(capped)) return capped
    var n = 2
    while (true) {
        val suffix = " ($n)"
        val candidate = capped.take(maxLength - suffix.length) + suffix
        if (free(candidate)) return candidate
        n++
    }
}
