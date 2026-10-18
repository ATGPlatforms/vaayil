package dev.vaayil.core.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.data.AutomationRulesStore
import dev.vaayil.core.data.BootClaim
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.needsGuardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * BOOT_COMPLETED: claim the boot for connect-on-boot (J1, at most once per device boot), connect
 * the active (or last-connected) profile when connect-on-boot is on and this boot's broadcast is
 * the first, and start the automation foreground service whenever either rule is on.
 *
 * MY_PACKAGE_REPLACED (F1): an app update kills the automation service and nothing else restarts
 * it, so re-arm it under the same predicate. An update is not a boot, so it never connects.
 *
 * Both broadcasts are exempt from background FGS-start limits. Store reads run on an IO coroutine
 * behind goAsync; every tunnel action is a service intent (spec §7 Phase 2). A refused start is
 * logged and never blocks the other start or crashes the receiver. A store that can't be read is
 * logged too (R12): nothing escapes the coroutine, so a boot never crashes the process, and the
 * pending result is always finished.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED
        if (!boot && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync() // null only when a test calls onReceive directly
        handle(context, boot) { pending?.finish() }
    }

    /**
     * The receiver's work, on an IO coroutine; [finish] runs exactly once, whatever happens. A boot broadcast
     * claims the boot FIRST, before the rules read, so every boot broadcast is stamped whatever happens next
     * (J1): Android 15+ also delivers BOOT_COMPLETED when user action takes a force-stopped app out of its
     * stopped state, and only the claim's stamp keeps a same-boot repeat from reconnecting. A claim that throws
     * logs one WARN with the exception's class only and counts as UNKNOWN; an UNKNOWN claim logs one INFO line;
     * both connect when the rule says so. A store read that throws is logged at WARN with the broadcast and the
     * exception's class only, never a stored value: unreadable rules start nothing, and an unreadable connect
     * target skips the connect but still re-arms the guard. `MY_PACKAGE_REPLACED` never claims a boot. The
     * reads are parameters so tests can make them throw.
     */
    internal fun handle(
        context: Context,
        boot: Boolean,
        readRules: suspend () -> AutoConnectRules = { AutomationRulesStore(context).snapshot() },
        readTarget: suspend () -> String? = { AutoConnectService.resolveConnectTarget(context) },
        claimBoot: suspend () -> BootClaim = { ActiveProfileStore(context).claimBoot() },
        finish: () -> Unit,
    ): Job = CoroutineScope(Dispatchers.IO).launch {
        val broadcast = if (boot) "boot" else "update"
        try {
            // J1: claim before anything reads the rules, so the stamp lands whatever happens next.
            val claim = if (boot) {
                val c = try {
                    claimBoot()
                } catch (e: Exception) {
                    Log.w(TAG, "$broadcast: boot claim unreadable (${e.javaClass.simpleName})")
                    BootClaim.UNKNOWN
                }
                if (c == BootClaim.UNKNOWN) {
                    Log.i(TAG, "$broadcast: boot number unknown; connect-on-boot runs as before")
                }
                c
            } else {
                null // an update is not a boot: it never claims one and never connects
            }
            val rules = try {
                readRules()
            } catch (e: Exception) {
                Log.w(TAG, "$broadcast: automation rules unreadable (${e.javaClass.simpleName}); nothing started")
                return@launch
            }
            if (boot && rules.connectOnBoot && claim != BootClaim.SAME_BOOT) {
                val target = try {
                    readTarget()
                } catch (e: Exception) {
                    Log.w(TAG, "$broadcast: connect target unreadable (${e.javaClass.simpleName}); not connecting")
                    null
                }
                // B1: the command gates the start on VPN consent (and logs a refused one).
                if (target != null) BackgroundTunnelCommands(context).connect(target)
            }
            if (rules.needsGuardService) {
                runCatching { AutoConnectService.start(context) }
                    .onFailure { Log.w(TAG, "automation service start refused", it) }
            }
        } finally {
            finish()
        }
    }

    private companion object { const val TAG = "BootReceiver" }
}
