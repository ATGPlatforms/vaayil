package dev.vaayil.core.model

/**
 * Both split-tunnel curated lists plus which mode is active. The store persists BOTH
 * sets, so switching modes never drops the other mode's list — across sessions, not
 * just in memory (known-issue P2).
 */
data class SplitTunnelSelections(
    val mode: SplitTunnelMode,
    val excluded: Set<String> = emptySet(),
    val included: Set<String> = emptySet(),
) {
    /** The policy for the active mode: its curated set as [SplitTunnelPolicy.packages]. */
    val active: SplitTunnelPolicy
        get() = when (mode) {
            SplitTunnelMode.EXCLUDE -> SplitTunnelPolicy(mode, excluded)
            SplitTunnelMode.INCLUDE -> SplitTunnelPolicy(mode, included)
        }

    /** Replaces the active mode's curated set, leaving the other mode's list untouched. */
    fun withActive(packages: Set<String>): SplitTunnelSelections = when (mode) {
        SplitTunnelMode.EXCLUDE -> copy(excluded = packages)
        SplitTunnelMode.INCLUDE -> copy(included = packages)
    }

    companion object {
        val INITIAL = SplitTunnelSelections(SplitTunnelMode.EXCLUDE)
    }
}
