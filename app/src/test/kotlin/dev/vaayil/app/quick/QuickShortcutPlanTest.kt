package dev.vaayil.app.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The planner reconciles the launcher's dynamic and pinned Connect shortcuts with the profiles
 * the app can read: at most four, the last-connected profile first, and pinned shortcuts whose
 * profile is gone disabled rather than silently dropped.
 */
class QuickShortcutPlanTest {
    private val a = QuickProfile("a", "A")
    private val b = QuickProfile("b", "B")
    private val c = QuickProfile("c", "C")

    private fun plan(
        readable: List<QuickProfile> = listOf(a, b, c),
        storedIds: Set<String> = setOf("a", "b", "c"),
        lastConnectedId: String? = null,
        maxCount: Int = 5,
        dynamic: List<PublishedShortcut> = emptyList(),
        pinned: List<PublishedShortcut> = emptyList(),
    ): ShortcutPlan = planShortcuts(readable, storedIds, lastConnectedId, maxCount, dynamic, pinned)

    @Test
    fun `the last-connected profile comes first, then the Profiles tab order`() {
        val result = plan(lastConnectedId = "c")

        assertEquals(
            listOf(ShortcutSpec("c", "C"), ShortcutSpec("a", "A"), ShortcutSpec("b", "B")),
            result.dynamic,
        )
    }

    @Test
    fun `at most four, and fewer when the launcher allows fewer`() {
        val six = ('a'..'f').map { QuickProfile(it.toString(), it.uppercase()) }
        val stored = six.map { it.id }.toSet()

        assertEquals(4, plan(readable = six, storedIds = stored).dynamic?.size)
        assertEquals(
            listOf("a", "b"),
            plan(readable = six, storedIds = stored, maxCount = 2).dynamic?.map { it.profileId },
        )
    }

    @Test
    fun `nothing to publish when the list already matches`() {
        val published = listOf(
            PublishedShortcut("connect:a", "A", true),
            PublishedShortcut("connect:b", "B", true),
            PublishedShortcut("connect:c", "C", true),
        )

        assertNull(plan(dynamic = published).dynamic)
    }

    @Test
    fun `a reorder republishes`() {
        val published = listOf(
            PublishedShortcut("connect:b", "B", true),
            PublishedShortcut("connect:a", "A", true),
        )

        assertEquals(
            listOf("a", "b"),
            plan(readable = listOf(a, b), storedIds = setOf("a", "b"), dynamic = published)
                .dynamic?.map { it.profileId },
        )
    }

    @Test
    fun `a rename republishes and updates the pinned shortcut`() {
        val renamed = QuickProfile("a", "New")
        val old = PublishedShortcut("connect:a", "Old", true)

        val result = plan(
            readable = listOf(renamed),
            storedIds = setOf("a"),
            dynamic = listOf(old),
            pinned = listOf(old),
        )

        assertEquals(listOf(ShortcutSpec("a", "New")), result.dynamic)
        assertEquals(listOf(ShortcutSpec("a", "New")), result.update)
    }

    @Test
    fun `a deleted profile's pinned shortcut is disabled`() {
        val result = plan(
            readable = listOf(a),
            storedIds = setOf("a"),
            pinned = listOf(PublishedShortcut("connect:x", "X", true)),
        )

        assertEquals(listOf("connect:x"), result.disable)
    }

    @Test
    fun `a returning profile's pinned shortcut is enabled`() {
        val x = QuickProfile("x", "X")
        val result = plan(
            readable = listOf(x),
            storedIds = setOf("x"),
            pinned = listOf(PublishedShortcut("connect:x", "X", false)),
        )

        assertEquals(listOf(ShortcutSpec("x", "X")), result.enable)
        assertEquals(emptyList<ShortcutSpec>(), result.update)
    }

    @Test
    fun `a key outage leaves everything as it is`() {
        assertEquals(
            ShortcutPlan(null, emptyList(), emptyList(), emptyList()),
            plan(readable = emptyList(), storedIds = setOf("a", "b")),
        )
    }

    @Test
    fun `a stored but unreadable profile's pinned shortcut is left alone`() {
        val result = plan(
            readable = listOf(b),
            storedIds = setOf("a", "b"),
            pinned = listOf(PublishedShortcut("connect:a", "A", true)),
        )

        assertEquals(emptyList<String>(), result.disable)
        assertEquals(emptyList<ShortcutSpec>(), result.enable)
        assertEquals(emptyList<ShortcutSpec>(), result.update)
    }

    @Test
    fun `pinned shortcuts that aren't Vaayil's connect shortcuts are ignored`() {
        val result = plan(
            readable = listOf(a),
            storedIds = setOf("a"),
            pinned = listOf(
                PublishedShortcut("launch", "Launch", true),
                PublishedShortcut("connect", "Connect", true),
            ),
        )

        assertEquals(emptyList<String>(), result.disable)
        assertEquals(emptyList<ShortcutSpec>(), result.enable)
        assertEquals(emptyList<ShortcutSpec>(), result.update)
    }

    @Test
    fun `with no stored profile the list empties and every pinned connect shortcut is disabled`() {
        val published = listOf(
            PublishedShortcut("connect:a", "A", true),
            PublishedShortcut("connect:b", "B", true),
        )
        val result = plan(
            readable = emptyList(),
            storedIds = emptySet(),
            dynamic = published,
            pinned = published + PublishedShortcut("other", "Other", true),
        )

        assertEquals(emptyList<ShortcutSpec>(), result.dynamic)
        assertEquals(listOf("connect:a", "connect:b"), result.disable)
    }
}
