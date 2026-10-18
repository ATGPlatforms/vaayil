package dev.vaayil.core.tunnel.wg

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J3's process gate: once an ordinary start has been noted or a disconnect has suppressed
 * the crash resume, the resume never issues again in this process. The gate only records
 * Jobs — it never cancels, joins or completes one.
 */
class CrashResumeGateTest {

    @Test
    fun `register refuses after an explicit start or a suppression`() {
        val explicit = CrashResumeGate()
        assertTrue(explicit.mayIssue())
        explicit.noteExplicitStart()
        assertFalse(explicit.mayIssue())
        assertFalse(explicit.register(Job()))

        val suppressed = CrashResumeGate()
        suppressed.suppress()
        assertFalse(suppressed.mayIssue())
        assertFalse(suppressed.register(Job()))

        // A second register after an accepted one is false: one resume per process.
        val accepted = CrashResumeGate()
        assertTrue(accepted.register(Job()))
        assertFalse(accepted.register(Job()))
    }

    @Test
    fun `suppress returns the registered resume job`() {
        // Nothing registered yet: a suppression bars the resume and carries no Job.
        val bare = CrashResumeGate()
        assertNull(bare.suppress())
        assertFalse(bare.register(Job()))

        // A registered resume's Job is what every later suppression returns.
        val gate = CrashResumeGate()
        val job = Job()
        assertTrue(gate.register(job))
        assertSame(job, gate.suppress())
        assertSame(job, gate.suppress())
    }

    @Test
    fun `a finished resume is released and no second resume is accepted`() {
        val gate = CrashResumeGate()
        val job = Job()
        assertTrue(gate.register(job))
        job.complete()
        // One resume per process, even after the accepted one finished.
        assertFalse(gate.register(Job()))
        // The finished Job is no longer held (it would keep its service instance reachable), so a
        // suppression has nothing left to join; joining a finished Job was a no-op anyway.
        assertNull(gate.suppress())
    }
}
