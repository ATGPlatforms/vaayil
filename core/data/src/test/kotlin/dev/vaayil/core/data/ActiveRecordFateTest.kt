package dev.vaayil.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure F32 decision: what a fresh process does with the ACTIVE record it inherits. */
class ActiveRecordFateTest {
    @Test
    fun `no record means nothing to do`() {
        assertEquals(ActiveRecordFate.NONE, activeRecordFate(null, 7, 7))
        assertEquals(ActiveRecordFate.NONE, activeRecordFate(null, null, null))
    }

    @Test
    fun `a record stamped in this boot is resumed`() =
        assertEquals(ActiveRecordFate.RESUME, activeRecordFate("p1", 7, 7))

    @Test
    fun `a record from an earlier boot is cleared only`() =
        assertEquals(ActiveRecordFate.CLEAR, activeRecordFate("p1", 6, 7))

    @Test
    fun `an unstamped record is cleared only`() =
        assertEquals(ActiveRecordFate.CLEAR, activeRecordFate("p1", null, 7))

    @Test
    fun `an unreadable boot never proves the same boot`() {
        assertEquals(ActiveRecordFate.CLEAR, activeRecordFate("p1", 7, null))
        assertEquals(ActiveRecordFate.CLEAR, activeRecordFate("p1", null, null))
    }

    @Test
    fun `a recent resume of the same profile in the same boot is cleared instead`() {
        assertEquals(ActiveRecordFate.CLEAR, activeRecordFate("p1", 7, 7, resumedRecently = true))
        assertEquals(ActiveRecordFate.NONE, activeRecordFate(null, 7, 7, resumedRecently = true))
    }

    @Test
    fun `the resume marker binds only within its window, profile and boot`() {
        val boot = 7
        val markerAt = 0L
        assertTrue(resumedRecently("p1", boot, markerAt, "p1", boot, RESUME_COOLDOWN_MS - 1))
        assertFalse(resumedRecently("p1", boot, markerAt, "p1", boot, RESUME_COOLDOWN_MS))
        assertFalse(resumedRecently("p2", boot, markerAt, "p1", boot, RESUME_COOLDOWN_MS - 1))
        assertFalse(resumedRecently("p1", 6, markerAt, "p1", boot, RESUME_COOLDOWN_MS - 1))
        assertFalse(resumedRecently("p1", boot, markerAt, "p1", null, RESUME_COOLDOWN_MS - 1))
        assertFalse(resumedRecently(null, boot, null, "p1", boot, RESUME_COOLDOWN_MS - 1))
        assertFalse(resumedRecently("p1", boot, markerAt, "p1", boot, -1))
    }
}
