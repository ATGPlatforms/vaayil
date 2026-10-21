package dev.vaayil.core.automation

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import dev.vaayil.core.tunnel.wg.VpnTunnelService

/** Counts the tunnel starts by method, so the caller's foreground choice is observable. */
internal class RecordingStarts(context: Context) : ContextWrapper(context) {
    val disconnectIntents = mutableListOf<Intent>()
    var foregroundStarts = 0
    var plainStarts = 0

    override fun startForegroundService(service: Intent): ComponentName? {
        if (service.action == VpnTunnelService.ACTION_DISCONNECT) disconnectIntents += service
        foregroundStarts++
        return super.startForegroundService(service)
    }

    override fun startService(service: Intent): ComponentName? {
        if (service.action == VpnTunnelService.ACTION_DISCONNECT) disconnectIntents += service
        plainStarts++
        return super.startService(service)
    }
}
