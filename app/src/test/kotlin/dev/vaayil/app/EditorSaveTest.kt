package dev.vaayil.app

import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F22: the editor's save path must read the stored copy BEFORE saving (the restart needs to
 * know what changed), drop the draft only after a successful save, and never restart when
 * the save threw.
 */
class EditorSaveTest {
    private fun profile(id: String, name: String = "Home", mtu: Int? = 1280) = Profile(
        id, name,
        InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), mtu, null),
        PeerConfig("pub", null, "host:51820", listOf("0.0.0.0/0"), null),
    )

    @Test
    fun `the restart sees the stored copy from before the save`() = runBlocking {
        // One stateful store behind both lambdas: a read made after the save would see mtu 1300.
        val store = mutableMapOf("p1" to profile("p1", mtu = 1280))
        val after = profile("p1", mtu = 1300)
        var saveCalls = 0
        var restartBefore: Profile? = null
        var restartAfter: Profile? = null
        saveEditedProfile(
            after,
            stored = { id -> store[id] },
            save = { p ->
                saveCalls++
                store[p.id] = p
            },
            drafts = mutableMapOf(),
            notices = mutableMapOf(),
            restart = { before, a ->
                restartBefore = before
                restartAfter = a
            },
        )
        assertEquals(1280, restartBefore?.interfaceConfig?.mtu)
        assertEquals(1300, restartAfter?.interfaceConfig?.mtu)
        assertEquals(after, store["p1"])
        assertEquals(1, saveCalls)
    }

    @Test
    fun `saving a draft drops it and reports no stored copy`() = runBlocking {
        val drafts = mutableMapOf("draft-1" to profile("draft-1"))
        val after = profile("draft-1", mtu = 1300)
        var restartBefore: Profile? = null
        var restartAfter: Profile? = null
        saveEditedProfile(
            after,
            stored = { null },
            save = { },
            drafts = drafts,
            notices = mutableMapOf(),
            restart = { before, a ->
                restartBefore = before
                restartAfter = a
            },
        )
        assertNull(drafts["draft-1"])
        assertNull(restartBefore)
        assertEquals(after, restartAfter)
    }

    @Test
    fun `a throwing save keeps the draft and never restarts`() {
        val drafts = mutableMapOf("draft-1" to profile("draft-1"))
        var restarts = 0
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                saveEditedProfile(
                    profile("draft-1"),
                    stored = { null },
                    save = { throw IllegalStateException("disk full") },
                    drafts = drafts,
                    notices = mutableMapOf(),
                    restart = { _, _ -> restarts++ },
                )
            }
        }
        assertEquals("disk full", error.message)
        assertTrue(drafts.containsKey("draft-1"))
        assertEquals(0, restarts)
    }
}
