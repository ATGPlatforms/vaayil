package dev.vaayil.core.data

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/** The boot-stamped ACTIVE record and the one-shot take (F32). The DataStore is a process
    singleton, so every test seeds and clears through it. */
@RunWith(AndroidJUnit4::class)
class ActiveProfileStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun store(boot: Int?, now: Long = 0L) = ActiveProfileStore(context, elapsedRealtime = { now }) { boot }

    @Before
    fun setUp() = runBlocking {
        store(null).setActive(null)
        store(null).setLastConnected(null)
    }

    @After
    fun tearDown() = runBlocking {
        store(null).setActive(null)
        store(null).setLastConnected(null)
    }

    @Test
    fun `a record written in this boot is taken exactly once`() = runBlocking {
        store(7).setActive("p1")
        store(7).setLastConnected("p1")

        assertEquals("p1", store(7).takeResumableActive())
        assertNull(store(7).activeId.first())
        assertEquals("p1", store(7).lastConnectedId.first())

        assertNull(store(7).takeResumableActive())
    }

    @Test
    fun `a record from an earlier boot is cleared without a resume`() = runBlocking {
        store(6).setActive("p1")
        store(6).setLastConnected("p1")

        assertNull(store(7).takeResumableActive())
        assertNull(store(7).activeId.first())
        assertEquals("p1", store(7).lastConnectedId.first())
    }

    @Test
    fun `a record an older build wrote without a stamp is cleared without a resume`() = runBlocking {
        store(null).setActive("p1")

        assertNull(store(7).takeResumableActive())
        assertNull(store(7).activeId.first())
    }

    @Test
    fun `a record written while the boot is unreadable never inherits an older stamp`() = runBlocking {
        store(7).setActive("p1")
        store(null).setActive("p2")

        assertNull(store(7).takeResumableActive())
        assertNull(store(7).activeId.first())
    }

    @Test
    fun `no record means nothing is taken and lastConnected is untouched`() = runBlocking {
        store(7).setLastConnected("p9")

        assertNull(store(7).takeResumableActive())
        assertEquals("p9", store(7).lastConnectedId.first())
    }

    @Test
    fun `reconciliation clears only missing current pointers`() = runBlocking {
        store(7).setActive("kept")
        store(7).setLastConnected("kept")
        assertEquals("kept" to "kept", store(7).reconcileProfilePointers(setOf("kept")))

        store(7).setLastConnected("gone")
        assertEquals("kept" to null, store(7).reconcileProfilePointers(setOf("kept")))
        assertEquals("kept", store(7).activeId.first())
        assertNull(store(7).lastConnectedId.first())

        // A retained ACTIVE keeps its boot stamp: this boot can still resume it.
        assertEquals("kept", store(7).takeResumableActive())

        // A pointer whose row is gone is cleared like setActive(null): nothing survives to resume.
        store(7).setActive("gone")
        assertEquals(null to null, store(7).reconcileProfilePointers(emptySet()))
        assertNull(store(7).takeResumableActive())
    }

    @Test
    fun `a resumed profile is not resumed again within the cooldown`() = runBlocking {
        store(7, now = 1_000).setActive("p1")
        assertEquals("p1", store(7, now = 1_000).takeResumableActive())
        store(7, now = 1_000).setActive("p1") // the resumed connect's own STARTED write keeps the marker

        assertNull(store(7, now = 60_000).takeResumableActive())
        assertNull(store(7, now = 60_000).activeId.first())
    }

    @Test
    fun `the cooldown expires so a later crash of the same profile is resumed again`() = runBlocking {
        store(7, now = 1_000).setActive("p1")
        assertEquals("p1", store(7, now = 1_000).takeResumableActive())
        store(7, now = 1_000).setActive("p1")

        assertEquals("p1", store(7, now = 61_000).takeResumableActive())
    }

    @Test
    fun `a switch to another profile resets the loop-breaker`() = runBlocking {
        store(7, now = 1_000).setActive("p1")
        assertEquals("p1", store(7, now = 1_000).takeResumableActive())

        store(7, now = 1_000).setActive("p2")
        store(7, now = 1_000).setActive("p1")

        assertEquals("p1", store(7, now = 2_000).takeResumableActive())
    }

    @Test
    fun `a disconnect and reconnect resets the loop-breaker`() = runBlocking {
        store(7, now = 1_000).setActive("p1")
        assertEquals("p1", store(7, now = 1_000).takeResumableActive())

        store(7, now = 1_000).setActive(null)
        store(7, now = 1_000).setActive("p1")

        assertEquals("p1", store(7, now = 2_000).takeResumableActive())
    }

    @Test
    fun `a marker from an earlier boot never blocks a take`() = runBlocking {
        store(6, now = 1_000).setActive("p1")
        assertEquals("p1", store(6, now = 1_000).takeResumableActive())

        store(7, now = 2_000).setActive("p1")

        assertEquals("p1", store(7, now = 2_000).takeResumableActive())
    }

    @Test
    fun `a clear issued after a stamped write wins regardless of coroutine interleaving`() = runBlocking {
        val s = store(7)
        val write = launch(start = CoroutineStart.UNDISPATCHED) { s.setActive("p") }
        val clear = launch(start = CoroutineStart.UNDISPATCHED) { s.setActive(null) }
        joinAll(write, clear)
        assertNull(s.activeId.first())
    }

    private fun loopBreakerWarnings() =
        ShadowLog.getLogsForTag("ActiveProfileStore").filter { it.type == Log.WARN }.map { it.msg }

    @Test
    fun `a resume the loop-breaker skips is logged with the profile id and the seconds since the last resume`() = runBlocking {
        store(7, now = 1_000).setActive("p1")
        assertEquals("p1", store(7, now = 1_000).takeResumableActive())
        store(7, now = 1_000).setActive("p1")
        assertEquals(emptyList<String>(), loopBreakerWarnings()) // a resume itself logs nothing

        assertNull(store(7, now = 60_000).takeResumableActive())

        assertEquals(listOf("F32 loop-breaker: resume of p1 skipped, last resume 59 s ago"), loopBreakerWarnings())
    }

    @Test
    fun `a record cleared for any other reason is not logged as a loop`() = runBlocking {
        store(6).setActive("p1")
        assertNull(store(7).takeResumableActive())               // a record from an earlier boot
        store(7, now = 1_000).setActive("p2")
        assertEquals("p2", store(7, now = 1_000).takeResumableActive())
        store(null, now = 1_000).setActive("p2")                 // the resumed connect's write, boot unreadable
        assertNull(store(7, now = 2_000).takeResumableActive())  // unstamped: cleared for that, inside the window
        assertEquals(emptyList<String>(), loopBreakerWarnings())
    }

    // J1: the once-per-boot claim behind connect-on-boot. The stamp lives in the process-wide
    // DataStore and outlives a Robolectric test, so each test claims its own distinct boots.
    @Test
    fun `boot claim survives a new store and admits the next boot`() = runBlocking {
        assertEquals(BootClaim.NEW_BOOT, store(100).claimBoot())
        // A new store instance in the same boot finds the boot taken.
        assertEquals(BootClaim.SAME_BOOT, store(100).claimBoot())
        assertEquals(BootClaim.NEW_BOOT, store(101).claimBoot())
    }

    @Test
    fun `concurrent boot claims have one winner`() = runBlocking {
        val claims = listOf(
            async { store(200).claimBoot() },
            async { store(200).claimBoot() },
        ).awaitAll()
        // DataStore serializes edits, so exactly one broadcast admits and the other reads the stamp.
        assertEquals(listOf(BootClaim.NEW_BOOT, BootClaim.SAME_BOOT), claims.sorted())
    }

    @Test
    fun `an unknown boot is never stamped`() = runBlocking {
        assertEquals(BootClaim.UNKNOWN, store(null).claimBoot())
        assertEquals(BootClaim.UNKNOWN, store(-1).claimBoot())
        // An unknown boot leaves nothing behind: a real boot afterwards still admits.
        assertEquals(BootClaim.NEW_BOOT, store(300).claimBoot())
        assertEquals(BootClaim.UNKNOWN, store(null).claimBoot())
        assertEquals(BootClaim.SAME_BOOT, store(300).claimBoot())
    }

    @Test
    fun `disconnect and reconciliation keep the boot claim`() = runBlocking {
        val s = store(400)
        s.setLastConnected("p1")
        assertEquals(BootClaim.NEW_BOOT, s.claimBoot())
        assertEquals("p1", s.lastConnectedId.first()) // a new boot's claim never touches LAST
        s.setActive(null)
        s.reconcileProfilePointers(emptySet())
        assertNull(s.takeResumableActive())
        s.setLastConnected("p1") // the reconcile above cleared it: no stored row carries p1
        assertEquals(BootClaim.SAME_BOOT, store(400).claimBoot())
        assertEquals("p1", store(400).lastConnectedId.first()) // nor does a same-boot claim
    }
}
