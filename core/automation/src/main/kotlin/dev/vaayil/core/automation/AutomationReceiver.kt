package dev.vaayil.core.automation

import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.vaayil.core.data.AutomationRulesStore
import dev.vaayil.core.data.DataGraph
import dev.vaayil.core.tunnel.api.isLive
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Public automation entry point (Tasker, adb): CONNECT / DISCONNECT / TOGGLE
 * broadcast actions, documented in docs/automation.md. Shipped disabled
 * (T-3): the Settings toggle enables the component and mints a per-install
 * token, and every broadcast must carry that token (A-1). The actions can only
 * connect or disconnect existing user profiles — no data plane access, no
 * config read/write — and every transition is user-visible via the persistent
 * tunnel notification. Every tunnel decision routes through VpnTunnelService
 * intents — never the engine directly (spec §7 Phase 2).
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Android 12+: a consented VPN app is exempt from the background FGS-start
                // limits through REASON_OP_ACTIVATE_VPN, and every CONNECT here checks consent
                // first (BackgroundTunnelCommands), so these starts are allowed once Vaayil holds
                // it. A refused start is logged, never a crash.
                runCatching { handle(context, intent) }
                    .onFailure { e -> Log.w(TAG, "automation action ${intent.action} failed: ${e.message}") }
            } finally { pending?.finish() } // goAsync() is null when a test calls onReceive directly; BootReceiver does the same.
        }
    }

    internal suspend fun handle(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val rules = AutomationRulesStore(appContext).snapshot()
        // Opt-in gate (T-3): the component ships disabled and the Settings toggle enables it,
        // but a broadcast still has to carry the per-install token, so no unrelated app can
        // drive the tunnel even while the user allows Tasker to.
        if (!rules.externalControlEnabled) return
        val expected = rules.externalControlToken ?: return
        val presented = intent.getStringExtra(EXTRA_TOKEN) ?: return
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), presented.toByteArray(Charsets.UTF_8))) return
        perform(appContext, intent.action, intent.getStringExtra(EXTRA_PROFILE))
    }

    private suspend fun perform(appContext: Context, action: String?, hint: String?) {
        val commands = BackgroundTunnelCommands(appContext)
        when (action) {
            ACTION_CONNECT -> {
                val id = resolveProfileId(appContext, hint) ?: return
                commands.connect(id)
            }
            ACTION_DISCONNECT -> commands.disconnect(foreground = true)
            ACTION_TOGGLE -> {
                val phase = ConnectionRegistry.status.value.phase
                if (phase.isLive) {
                    perform(appContext, ACTION_DISCONNECT, null)
                } else {
                    perform(appContext, ACTION_CONNECT, hint)
                }
            }
        }
    }

    /**
     * Exact id, then exact name. A hint that matches nothing is a NO-OP (A-5): a renamed or
     * re-imported profile must not silently connect whatever happens to be active. A broadcast
     * without a hint targets the active profile, else the last connected one — ACTIVE is cleared
     * by every disconnect, so without that fallback the documented TOGGLE recipe worked once —
     * else the newest readable profile, with a pointer to a deleted profile cleared on the way
     * (B3, [AutoConnectService.resolveConnectTarget]).
     */
    private suspend fun resolveProfileId(context: Context, hint: String?): String? {
        if (hint == null) return AutoConnectService.resolveConnectTarget(context)
        val repo = DataGraph.repository(context)
        val all = repo.observeProfiles().first()
        return all.firstOrNull { it.id == hint }?.id ?: all.firstOrNull { it.name == hint }?.id
    }

    companion object {
        private const val TAG = "AutomationReceiver"
        const val ACTION_CONNECT = "dev.vaayil.automation.CONNECT"
        const val ACTION_DISCONNECT = "dev.vaayil.automation.DISCONNECT"
        const val ACTION_TOGGLE = "dev.vaayil.automation.TOGGLE"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_TOKEN = "token"
    }
}
