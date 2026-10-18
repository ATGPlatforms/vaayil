package dev.vaayil.core.automation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.EngineProvider
import dev.vaayil.core.tunnel.wg.StartOutcome
import dev.vaayil.core.tunnel.wg.TunnelControl
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import java.lang.reflect.Field
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The shared J3 crash-resume fixture for the automation tests (R2): the tunnel-wg statics are
 * scripted by reflection — no production visibility is widened — and every static, barrier and
 * built service is restored by [restoreResumeFixture] from the test's finally.
 */

/** R2: the tunnel-wg statics are scripted by reflection; no production visibility is widened. */
internal fun controlOverrideField(): Field =
    VpnTunnelService::class.java.getDeclaredField("controlOverride").apply { isAccessible = true }

internal fun crashResumeGateField(): Field =
    EngineProvider::class.java.getDeclaredField("crashResumeGate").apply { isAccessible = true }

/** The gate class is internal to :core:tunnel-wg: built by reflection, restored fresh in finally. */
internal fun freshGate(): Any =
    Class.forName("dev.vaayil.core.tunnel.wg.CrashResumeGate").declaredConstructors
        .first { it.parameterCount == 0 }
        .apply { isAccessible = true }
        .newInstance()

/** The test-held parks, the services the delivery built, and what the scripted resume recorded. */
internal class ResumeFixture(
    val controlJob: Job,
    val lookup: CompletableDeferred<Unit>,
    val blocked: CompletableDeferred<Unit>,
    val connectCalls: MutableList<String>,
    val outcomes: MutableList<StartOutcome>,
    /** Every VpnTunnelService the delivery built; destroyed on restore so no collector outlives the test. */
    val services: MutableList<ServiceController<*>> = mutableListOf(),
)

/**
 * Installs a fresh crash-resume gate and a scripted [TunnelControl] through the tunnel-wg
 * statics (both restored by [restoreResumeFixture] from the test's finally). The script's
 * connectOp mirrors EngineProvider.connectById's shape: it parks on [ResumeFixture.lookup],
 * standing for the profile lookup, then calls admit() exactly where connectById would; a
 * false admit returns REFUSED without touching the registry, a true one publishes CONNECTED —
 * the UP a real start would produce — parks on [ResumeFixture.blocked], standing for the
 * start in flight, and returns STARTED. Every call and outcome is recorded for the assertions.
 * (The join pinning that needs a resume that is not yet up uses the LAZY probe of
 * BackgroundTunnelCommandsTest, registered on this fresh gate by reflection.)
 */
internal fun installResumeFixture(): ResumeFixture {
    val controlJob = SupervisorJob()
    val lookup = CompletableDeferred<Unit>()
    val blocked = CompletableDeferred<Unit>()
    val connectCalls = mutableListOf<String>()
    val outcomes = mutableListOf<StartOutcome>()
    controlOverrideField().set(
        null,
        TunnelControl(
            scope = CoroutineScope(controlJob + Dispatchers.Unconfined),
            connectOp = { _, id, admit ->
                connectCalls += id
                lookup.await()
                val started = admit()
                outcomes += if (started) StartOutcome.STARTED else StartOutcome.REFUSED
                if (!started) StartOutcome.REFUSED
                else {
                    ConnectionRegistry.status.value = TunnelStatus(ConnectionPhase.CONNECTED, id, null)
                    blocked.await()
                    StartOutcome.STARTED
                }
            },
            statusNow = { ConnectionRegistry.status.value },
            stopOp = {},
        ),
    )
    crashResumeGateField().set(null, freshGate())
    return ResumeFixture(controlJob, lookup, blocked, connectCalls, outcomes)
}

/**
 * R9: runs [extraCleanup] (a test's own teardown, e.g. its guarded service), releases every
 * barrier the script holds, joins [helper] boundedly (cancelling it when it overruns), drains
 * the scripted scope and the delivery's services, then restores the statics and the registry.
 * Failures are aggregated and surfaced — attached to the body's failure when it threw, thrown
 * otherwise.
 */
internal suspend fun restoreResumeFixture(
    fixture: ResumeFixture?,
    helper: Job?,
    primary: Throwable?,
    extraCleanup: (() -> Unit)? = null,
) {
    val errors = mutableListOf<Throwable>()
    runCatching { extraCleanup?.invoke() }.onFailure(errors::add)
    runCatching {
        fixture?.lookup?.complete(Unit)
        fixture?.blocked?.complete(Unit)
    }.onFailure(errors::add)
    if (helper != null && helper.isActive) {
        runCatching {
            try {
                withTimeout(5_000) { helper.join() }
            } catch (e: Throwable) {
                errors.add(e)
                helper.cancel()
            }
        }.onFailure(errors::add)
    }
    runCatching {
        fixture?.let { withTimeout(5_000) { it.controlJob.children.toList().joinAll() } }
    }.onFailure(errors::add)
    runCatching {
        // Destroy what the delivery built: a live collector would wake on later tests'
        // registry writes (the class's statics outlive a Robolectric method) and post
        // tunnel notifications into them.
        fixture?.services?.forEach { it.destroy() }
        shadowOf(Looper.getMainLooper()).idle()
    }.onFailure(errors::add)
    runCatching {
        controlOverrideField().set(null, null)
        crashResumeGateField().set(null, freshGate())
        ConnectionRegistry.status.value = TunnelStatus.idle
    }.onFailure(errors::add)
    runCatching { fixture?.controlJob?.cancel() }.onFailure(errors::add)
    errors.reduceOrNull { head, next -> head.apply { addSuppressed(next) } }?.let { failure ->
        val body = primary
        if (body != null) body.addSuppressed(failure) else throw failure
    }
}

/**
 * Builds the real resume ACTION_CONNECT — the public profile id plus the internal resume
 * extra by its literal — and delivers it to a Robolectric VpnTunnelService, whose
 * onStartCommand builds the LAZY connect through the scripted control and registers it on the
 * fresh gate. With [joinSettled] the scripted Job is joined, so callers observe its settled
 * state; without it a script the test parks keeps running, and the helper's own join of the
 * resume Job orders the test.
 */
internal suspend fun deliverLateResume(
    fixture: ResumeFixture,
    context: Context,
    profileId: String,
    joinSettled: Boolean = true,
) {
    val intent = Intent(context, VpnTunnelService::class.java)
        .setAction(VpnTunnelService.ACTION_CONNECT)
        .putExtra(VpnTunnelService.EXTRA_PROFILE_ID, profileId)
        .putExtra("dev.vaayil.extra.RESUME", true)
    // VpnTunnelService's supertype (GoBackend.VpnService) is not on this module's test compile
    // classpath, so Kotlin cannot prove the <T : Service> bound here: erase the class literal.
    @Suppress("UNCHECKED_CAST")
    val serviceClass = VpnTunnelService::class.java as Class<out Service>
    val controller = Robolectric.buildService(serviceClass, intent).create()
    fixture.services += controller
    controller.startCommand(0, 0)
    shadowOf(Looper.getMainLooper()).idle()
    if (joinSettled) withTimeout(5_000) { fixture.controlJob.children.toList().joinAll() }
}
