package dev.vaayil.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitTunnelSelectionsTest {
    @Test
    fun `active derives the policy from the current mode's set`() {
        val selections = SplitTunnelSelections(
            SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"), included = setOf("com.b"))
        assertEquals(SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, setOf("com.a")), selections.active)
        val includeMode = selections.copy(mode = SplitTunnelMode.INCLUDE)
        assertEquals(SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.b")), includeMode.active)
    }

    @Test
    fun `withActive replaces only the current mode's set`() {
        val selections = SplitTunnelSelections(
            SplitTunnelMode.INCLUDE, excluded = setOf("com.a"), included = setOf("com.b"))
        assertEquals(
            SplitTunnelSelections(
                SplitTunnelMode.INCLUDE, excluded = setOf("com.a"), included = setOf("com.x")),
            selections.withActive(setOf("com.x")),
        )
        val excludeMode = selections.copy(mode = SplitTunnelMode.EXCLUDE)
        assertEquals(
            SplitTunnelSelections(
                SplitTunnelMode.EXCLUDE, excluded = setOf("com.x"), included = setOf("com.b")),
            excludeMode.withActive(setOf("com.x")),
        )
    }

    @Test
    fun `active carries Allow local network in both modes`() {
        val selections = SplitTunnelSelections(
            SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"), included = setOf("com.b"),
            allowLocalNetwork = true)
        assertTrue(selections.active.allowLocalNetwork)
        assertTrue(selections.copy(mode = SplitTunnelMode.INCLUDE).active.allowLocalNetwork)
        assertTrue(selections.withActive(setOf("com.c")).allowLocalNetwork)
    }
}
