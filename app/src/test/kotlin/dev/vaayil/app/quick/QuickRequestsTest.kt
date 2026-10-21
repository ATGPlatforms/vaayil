package dev.vaayil.app.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The in-memory slot a shortcut's trampoline fills and the app screen takes from. */
class QuickRequestsTest {
    private var now = 0L
    private val requests = QuickRequests { now }

    @Test
    fun `take returns the posted id once`() {
        requests.post("p1")

        assertEquals("p1", requests.take())
        assertNull(requests.take())
        assertNull(requests.pending.value)
    }

    @Test
    fun `a newer request replaces an older one`() {
        requests.post("p1")
        now = 1_000
        requests.post("p2")

        assertEquals("p2", requests.take())
    }

    @Test
    fun `a request expires 30 s after it is posted`() {
        requests.post("p1")
        now = QUICK_REQUEST_TTL_MS

        assertEquals("p1", requests.take())

        now = 0
        requests.post("p1")
        now = QUICK_REQUEST_TTL_MS + 1

        assertNull(requests.take())
        assertNull(requests.pending.value)
    }
}
