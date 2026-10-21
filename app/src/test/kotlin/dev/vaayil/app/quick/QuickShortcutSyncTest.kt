package dev.vaayil.app.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync applies a plan in one fixed order: deletions first, revivals next, renames, and the
 * published list last. A refusal or a throwing host reports a retry without throwing.
 */
class QuickShortcutSyncTest {
    private class RecordingHost : ShortcutHost {
        var cap = 5
        var published = emptyList<PublishedShortcut>()
        var pinnedShortcuts = emptyList<PublishedShortcut>()
        var dynamicResult = true
        var throwOnDynamic = false
        val writes = mutableListOf<String>()

        override fun maxCount(): Int = cap
        override fun dynamic(): List<PublishedShortcut> = published
        override fun pinned(): List<PublishedShortcut> = pinnedShortcuts

        override fun setDynamic(specs: List<ShortcutSpec>): Boolean {
            writes += "setDynamic ${specs.joinToString(",") { it.id }}"
            if (throwOnDynamic) throw IllegalStateException("the launcher is rate limiting")
            return dynamicResult
        }

        override fun update(specs: List<ShortcutSpec>): Boolean {
            writes += "update ${specs.joinToString(",") { it.id }}"
            return true
        }

        override fun disable(ids: List<String>, message: String) {
            writes += "disable ${ids.joinToString(",")} $message"
        }

        override fun enable(specs: List<ShortcutSpec>) {
            writes += "enable ${specs.joinToString(",") { it.id }}"
        }
    }

    private val a = QuickProfile("a", "New")
    private val x = QuickProfile("x", "X")

    @Test
    fun `writes go in order disable enable update then the list`() {
        val host = RecordingHost().apply {
            pinnedShortcuts = listOf(
                PublishedShortcut("connect:d", "D", true),
                PublishedShortcut("connect:x", "X", false),
                PublishedShortcut("connect:a", "Old", true),
            )
        }

        assertTrue(QuickShortcutSync(host).sync(listOf(a, x), setOf("a", "x"), "x"))

        assertEquals(
            listOf(
                "disable connect:d $SHORTCUT_DELETED_MESSAGE",
                "enable connect:x",
                "update connect:a",
                "setDynamic connect:x,connect:a",
            ),
            host.writes,
        )
    }

    @Test
    fun `nothing is written when nothing differs`() {
        val host = RecordingHost().apply {
            published = listOf(PublishedShortcut("connect:a", "A", true))
            pinnedShortcuts = listOf(PublishedShortcut("connect:a", "A", true))
        }

        assertTrue(QuickShortcutSync(host).sync(listOf(QuickProfile("a", "A")), setOf("a"), null))
        assertTrue("something was written: ${host.writes}", host.writes.isEmpty())
    }

    @Test
    fun `a refused publish reports false`() {
        val host = RecordingHost().apply { dynamicResult = false }

        assertFalse(QuickShortcutSync(host).sync(listOf(a), setOf("a"), null))
        assertEquals(listOf("setDynamic connect:a"), host.writes)
    }

    @Test
    fun `a throwing host reports false`() {
        val host = RecordingHost().apply { throwOnDynamic = true }

        assertFalse(QuickShortcutSync(host).sync(listOf(a), setOf("a"), null))
    }
}
