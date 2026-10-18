package dev.vaayil.core.tunnel.wg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.api.uiLabel

object TunnelNotifications {
    const val CHANNEL_ID = "tunnel"
    const val NOTIFICATION_ID = 42

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN connection", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /**
     * Builds the state notification, or null when the state must not show one
     * (DISCONNECTED / FAILED cancel any existing notification instead).
     * J13: while CONNECTED the title follows this tunnel's handshake (G4), never the phase alone.
     */
    internal fun buildNotification(
        context: Context,
        status: TunnelStatus,
        handshake: HandshakeState,
        profileName: String?,
        contentIntent: PendingIntent?,
    ): Notification? {
        if (status.phase == ConnectionPhase.DISCONNECTED || status.phase == ConnectionPhase.FAILED) return null
        val disconnect = PendingIntent.getService(
            context, 1,
            Intent(context, VpnTunnelService::class.java).setAction(VpnTunnelService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // No byte counts (F3): this notification is rebuilt on phase and handshake changes, so
        // totals would freeze at their connect-time zeros. The dashboard owns the live totals.
        val text = if (status.phase == ConnectionPhase.CONNECTED) profileName else null
        val title = if (status.phase == ConnectionPhase.CONNECTED) {
            when (handshake) {
                HandshakeState.VERIFIED -> "Vaayil is connected"
                HandshakeState.WAITING -> "Vaayil is waiting for a handshake"
                HandshakeState.NONE, HandshakeState.PENDING -> "Vaayil is connecting"
            }
        } else {
            "Vaayil is ${status.phase.uiLabel.lowercase()}"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(status.phase == ConnectionPhase.CONNECTED)
            .addAction(0, "Disconnect", disconnect)
            .build()
    }

    /** Opens Vaayil's launcher activity (F4); null when the package has none (library tests). */
    fun openAppIntent(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            PendingIntent.getActivity(
                context, 2,
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    fun clear(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }
}
