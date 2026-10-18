package dev.vaayil.core.tunnel.wg

import kotlinx.coroutines.Job

/**
 * Process-wide admission for EngineProvider's same-boot crash resume (J3): one instance per
 * process, held by [EngineProvider]. A DISCONNECT or a revoke suppresses the resume for the
 * rest of the process, and so does any ordinary (non-resume) start the service handled.
 * Every method is synchronized and non-suspending: the monitor is never held across a wait,
 * and no Job is ever cancelled, joined or completed here. An accepted Job is forgotten once it
 * completes, so a finished resume never keeps its service instance reachable; [suppress] then
 * returns null, as there is nothing left to join.
 */
internal class CrashResumeGate {
    private var explicitStartSeen = false
    private var suppressed = false
    private var registered: Job? = null
    private var accepted = false

    /** Every service start that is not the resume (CONNECT, the null action, the Always-on start). */
    @Synchronized
    fun noteExplicitStart() {
        explicitStartSeen = true
    }

    /** Bars the resume and returns the accepted resume's Job while it is still running; null once it has finished, or when none was accepted. */
    @Synchronized
    fun suppress(): Job? {
        suppressed = true
        return registered
    }

    /** True while no ordinary start has been noted and nothing has suppressed the resume. */
    @Synchronized
    fun mayIssue(): Boolean = !explicitStartSeen && !suppressed

    /** Accepts the service's resume Job only while [mayIssue] holds and no resume was accepted before in this process. */
    @Synchronized
    fun register(job: Job): Boolean {
        if (!mayIssue() || accepted) return false
        accepted = true
        registered = job
        job.invokeOnCompletion { release(job) }
        return true
    }

    @Synchronized
    private fun release(job: Job) {
        if (registered === job) registered = null
    }
}
