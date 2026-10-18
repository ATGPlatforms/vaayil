package dev.vaayil.core.tunnel.wg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Keeps this app's [VpnTunnelService] instance alive for the length of a GoBackend setState (F7).
 *
 * The pinned GoBackend `stopSelf()`s the service in its static future on every tunnel-DOWN,
 * including the DOWN inside a switch's own setState(UP). Once that instance is destroyed its
 * onDestroy resets the future and the UP half starts the library's plain `GoBackend$VpnService`:
 * no notification, no onRevoke teardown, no protect callbacks. A BIND_AUTO_CREATE binding held
 * across the call keeps the instance, and so the future, alive; the switch re-establishes on
 * [VpnTunnelService], and afterwards the system's own VPN binding keeps it up. Same-UID binds are
 * exempt from the BIND_VPN_SERVICE permission the manifest sets; VpnService.onBind returns null
 * for this intent, and the connection still counts as an auto-create binding.
 */
fun interface ServiceHold {
    /** Takes the hold; closing the returned handle releases it. */
    fun acquire(): AutoCloseable

    companion object {
        /** No hold: unit tests over a fake backend, where no service exists. */
        val None = ServiceHold { AutoCloseable {} }
    }
}

class BoundServiceHold(private val context: Context) : ServiceHold {
    override fun acquire(): AutoCloseable {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = runCatching {
            context.bindService(Intent(context, VpnTunnelService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "bindService threw", it) }.getOrDefault(false)
        if (!bound) Log.w(TAG, "could not bind VpnTunnelService; a switch may land on GoBackend's own service")
        return AutoCloseable { runCatching { context.unbindService(connection) } }
    }

    private companion object { const val TAG = "ServiceHold" }
}
