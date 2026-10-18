package dev.vaayil.app

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * One one-time start-up upgrade. [name] identifies the step in logs and tests; [run] does its
 * work. Steps run in list order, before Room's first open.
 */
internal data class StartupStep(val name: String, val run: suspend () -> Unit)

/**
 * C11: runs the one-time upgrades in order, isolating each one. A step that fails is logged and
 * the ones after it still run, so a single unreadable store or upgrade cannot crash every process
 * start — the pass is best-effort by design. Cancellation is not a failure and stops the run at
 * once. The log names the step and the exception's class only: an exception's message can echo
 * stored data, secrets included.
 */
internal suspend fun runStartupSteps(steps: List<StartupStep>) {
    for (step in steps) {
        try {
            step.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "start-up step ${step.name} failed (${e.javaClass.simpleName}); continuing")
        }
    }
}

private const val TAG = "AppStartup"
