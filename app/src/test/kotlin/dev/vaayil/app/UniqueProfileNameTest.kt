package dev.vaayil.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F18/F26: imported and duplicated names stay unique and never exceed the 48-character cap. */
class UniqueProfileNameTest {

    @Test
    fun `a free name is kept`() = assertEquals("Home", uniqueProfileName("Home", setOf("Work")))

    @Test
    fun `a taken name gets the first free number`() =
        assertEquals("Home (3)", uniqueProfileName("Home", setOf("home", "Home (2)")))

    @Test
    fun `a long name is trimmed so the suffix fits`() {
        val base = "x".repeat(48)
        val out = uniqueProfileName(base, setOf(base))
        assertEquals(48, out.length)
        assertTrue(out.endsWith(" (2)"))
    }

    @Test
    fun `a free over-long base comes back capped`() =
        assertEquals("x".repeat(48), uniqueProfileName("x".repeat(55), emptySet()))

    @Test
    fun `the duplicate of a 48-character name stays inside the cap and stays unique`() {
        // F26: the old `(source.name + " (copy)").take(48)` cut the suffix off and collided.
        val out = uniqueProfileName("x".repeat(48) + " (copy)", setOf("x".repeat(48)))
        assertEquals(48, out.length)
        assertTrue(out != "x".repeat(48))
        assertTrue(out.endsWith(" (2)"))
    }
}
