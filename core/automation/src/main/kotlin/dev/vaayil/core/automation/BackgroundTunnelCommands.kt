package dev.vaayil.core.automation

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.vaayil.core.data.ActiveProfileStore
import dev.vaayil.core.tunnel.wg.EngineProvider
import dev.vaayil.core.tunnel.wg.TunnelNotifications
import dev.vaayil.core.tunnel.wg.VpnTunnelService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** B1's CONNECT admission: a start was issued, consent is missing, or the command threw. */
internal enum class ConnectAdmission { STARTED, NO_CONSENT, REFUSED }

/**
 * B1: the one admission point for the tunnel commands the background automation paths issue — the
 * Wi-Fi guard, connect-on-boot and Tasker. On Android 14+ a [VpnTunnelService] start without VPN
 * consent crashes the process (specialUse's FOREGROUND_SERVICE_SYSTEM_EXEMPTED type needs
 * OP_ACTIVATE_VPN), so a CONNECT checks consent first and, without it, posts one notification in
 * the tunnel's own slot (42) — the next tunnel notification replaces it, so nothing needs clearing —
 * that opens Vaayil to connect manually. A DISCONNECT starts the service only while a tunnel is
 * live (a live tunnel implies consent) and never checks consent itself.
 *
 * Every failure — a throwing prepare, a refused start, a notification the platform rejects — is
 * caught and logged: the callers are receivers and a sticky service, so nothing may propagate.
 * The constructor performs no context access: [AutoConnectService] builds one before it is attached.
 */
internal class BackgroundTunnelCommands(private val context: Context) {

    /**
     * Starts VpnTunnelService for [profileId] only with VPN consent. Without consent, [postNotice]
     * posts the notice and the admission is [ConnectAdmission.NO_CONSENT]; a prepare or a start
     * that threw is [ConnectAdmission.REFUSED]. Only [ConnectAdmission.STARTED] means a start was
     * issued.
     */
    fun connect(profileId: String, postNotice: Boolean = true): ConnectAdmission {
        // The engine's own consent check (EngineProvider): prepare() returns null when the app is
        // already prepared. Its side effect — preparing Vaayil — happens immediately before a
        // connect that would take the VPN over anyway.
        val prepared = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            Log.w(TAG, "connect refused: prepare failed (${e.javaClass.simpleName})")
            return ConnectAdmission.REFUSED
        }
        if (prepared != null) {
            if (postNotice) postMissingConsent()
            return ConnectAdmission.NO_CONSENT
        }
        return try {
            context.startForegroundService(AutoConnectService.connectIntent(context, profileId))
            ConnectAdmission.STARTED
        } catch (e: Exception) {
            Log.w(TAG, "connect start refused (${e.javaClass.simpleName})")
            ConnectAdmission.REFUSED
        }
    }

    /**
     * J3: a background DISCONNECT supersedes a pending same-boot crash resume. First it bars the
     * resume for the rest of the process and takes the accepted resume's Job — before any
     * suspension or phase check, so a resume parked in its profile lookup or engine start can
     * never win the race. Then, under [NonCancellable] — a guard instance dying mid-join must not
     * strand a resumed tunnel — it clears ACTIVE (LAST stays; a store failure is logged by class
     * and the steps go on), joins the accepted resume's Job, reads [EngineProvider.isTunnelLive]
     * (the engine's own phase when the engine exists, so a lagging bridge cannot hide a fresh UP),
     * and only while live starts the ACTION_DISCONNECT with [foreground]. B1 stands: no service
     * start while nothing is live, no consent check, no notice, no new timeout — the join waits as
     * long as the start's own timeouts let it run. [foreground] keeps the caller's start choice:
     * the guard, itself a foreground service, uses [Context.startService]; Tasker's receiver,
     * which is not exempt, uses [Context.startForegroundService].
     */
    suspend fun disconnect(foreground: Boolean) {
        try {
            val resume = EngineProvider.suppressCrashResume()
            withContext(NonCancellable) {
                try {
                    ActiveProfileStore(context).setActive(null)
                } catch (e: Exception) {
                    Log.w(TAG, "active record clear failed (${e.javaClass.simpleName})")
                }
                resume?.join()
                if (EngineProvider.isTunnelLive()) {
                    val intent = Intent(context, VpnTunnelService::class.java)
                        .setAction(VpnTunnelService.ACTION_DISCONNECT)
                    if (foreground) context.startForegroundService(intent) else context.startService(intent)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "disconnect start refused (${e.javaClass.simpleName})")
        }
    }

    private fun postMissingConsent() {
        // POST_NOTIFICATIONS is a runtime grant from API 33: without it the post is skipped
        // outright — the command must not fail because the notice cannot be shown.
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            TunnelNotifications.ensureChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                TunnelNotifications.NOTIFICATION_ID,
                NotificationCompat.Builder(context, TunnelNotifications.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Vaayil couldn't connect")
                    .setContentText("VPN permission is off. Open Vaayil and connect once to allow it.")
                    .setContentIntent(TunnelNotifications.openAppIntent(context))
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "consent notification refused (${e.javaClass.simpleName})")
        }
    }

    private companion object { const val TAG = "BackgroundTunnelCommands" }
}
