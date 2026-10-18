package dev.vaayil.feature.tunnel

import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F23: after a disconnect the dashboard disc must stay on the last-connected profile,
 * not jump to whichever profile the list happens to put first.
 */
class DashboardTargetTest {
    private fun profile(id: String) = Profile(
        id, "Home",
        InterfaceConfig("k", listOf("10.0.0.2/32"), emptyList(), null, null),
        PeerConfig("K", null, "h:51820", listOf("0.0.0.0/0"), 25),
    )

    private val newest = profile("n")
    private val older = profile("o")
    private val oldest = profile("x")
    private val list = listOf(newest, older, oldest)

    @Test
    fun `a picked profile wins over active and last connected`() {
        assertEquals(oldest, dashboardTarget(list, pickedId = "x", activeId = "o", lastConnectedId = "n"))
    }

    @Test
    fun `the active profile wins when nothing is picked`() {
        assertEquals(older, dashboardTarget(list, null, activeId = "o", lastConnectedId = "x"))
    }

    @Test
    fun `after disconnect the disc stays on the last connected profile`() {
        assertEquals(oldest, dashboardTarget(list, null, null, lastConnectedId = "x"))
    }

    @Test
    fun `a deleted pick falls through to the newest profile`() {
        assertEquals(newest, dashboardTarget(list, "gone", null, null))
    }

    @Test
    fun `no profiles means no target`() {
        assertNull(dashboardTarget(emptyList(), null, null, null))
    }

    @Test
    fun `a profile connected elsewhere replaces the pick`() {
        assertNull(pickAfterLiveChange("b", "a", "c"))
    }

    @Test
    fun `a pick made with nothing live gives way to a profile connected elsewhere`() {
        assertNull(pickAfterLiveChange("b", null, "c"))
    }

    @Test
    fun `connecting the picked profile keeps the pick`() {
        assertEquals("b", pickAfterLiveChange("b", "a", "b"))
    }

    @Test
    fun `a disconnect keeps the pick`() {
        assertEquals("b", pickAfterLiveChange("b", "a", null))
    }

    @Test
    fun `an unchanged live profile keeps the pick`() {
        assertEquals("b", pickAfterLiveChange("b", "a", "a"))
    }

    @Test
    fun `a connect elsewhere after switching to the pick replaces it`() {
        assertNull(pickAfterLiveChange("b", "b", "a"))
    }

    @Test
    fun `no pick stays no pick`() {
        assertNull(pickAfterLiveChange(null, "a", "c"))
    }
}
