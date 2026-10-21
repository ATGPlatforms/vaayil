package dev.vaayil.core.automation

import android.content.Context
import android.os.SystemClock
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.tunnel.wg.ConnectionRegistry
import dev.vaayil.core.tunnel.wg.EngineProvider
import kotlinx.coroutines.flow.StateFlow

/** What a quick-control connect asked the tunnel to do. */
enum class QuickConnectResult { SENT, SKIPPED, NEEDS_CONSENT, REFUSED }

/**
 * The cap on how long one quick-control send keeps a second one from being a duplicate: the guard
 * also needs the status unchanged and no connect settled since the send, and lifts early when
 * either no longer holds.
 */
internal const val QUICK_CONNECT_SETTLE_MS = 10_000L

/**
 * The quick controls' public entry to the tunnel commands: a Quick Settings tile and the widget
 * go through it instead of starting [dev.vaayil.core.tunnel.wg.VpnTunnelService] themselves.
 *
 * A connect sends one CONNECT through [BackgroundTunnelCommands] and arms a duplicate guard: the
 * next connect is [QuickConnectResult.SKIPPED] without touching consent only while no connect has
 * settled since that send, the tunnel status still equals the snapshot taken just before it, and
 * less than [QUICK_CONNECT_SETTLE_MS] has passed. A settled connect means the earlier send is
 * over, so the next tap is a new attempt. A live tunnel is skipped before anything else, so no
 * quick surface ever asks for consent while a tunnel is up. The entry's own [disconnect] clears
 * the guard.
 */
class QuickTunnelCommands internal constructor(
    private val commands: BackgroundTunnelCommands,
    private val status: StateFlow<TunnelStatus>,
    private val isLive: () -> Boolean,
    private val clock: () -> Long,
    private val settledConnects: () -> Long,
) {
    /** Production wiring: the background commands, the process registry and the engine's live state. */
    constructor(context: Context) : this(
        BackgroundTunnelCommands(context.applicationContext),
        ConnectionRegistry.status,
        EngineProvider::isTunnelLive,
        SystemClock::elapsedRealtime,
        { ConnectionRegistry.connectsSettled },
    )

    /**
     * The last send's guard: the status and the settled-connect count read just before it, and
     * when it was sent.
     */
    private var statusAtSend: TunnelStatus? = null
    private var settledAtSend: Long? = null
    private var sentAt: Long? = null

    /**
     * Sends one CONNECT for [profileId], or explains why it did not. [noticeWithoutConsent] picks
     * whether a missing VPN consent also posts the consent notice: the widget does, the tile does
     * not. The call is synchronous because [BackgroundTunnelCommands.connect] is.
     */
    @Synchronized
    fun connect(profileId: String, noticeWithoutConsent: Boolean): QuickConnectResult {
        if (isLive()) return QuickConnectResult.SKIPPED
        val sent = sentAt
        if (sent != null && status.value == statusAtSend && settledConnects() == settledAtSend &&
            clock() - sent < QUICK_CONNECT_SETTLE_MS
        ) {
            return QuickConnectResult.SKIPPED
        }
        val snapshot = status.value
        val settledBeforeSend = settledConnects()
        return when (commands.connect(profileId, postNotice = noticeWithoutConsent)) {
            ConnectAdmission.STARTED -> {
                statusAtSend = snapshot
                settledAtSend = settledBeforeSend
                sentAt = clock()
                QuickConnectResult.SENT
            }
            ConnectAdmission.NO_CONSENT -> QuickConnectResult.NEEDS_CONSENT
            ConnectAdmission.REFUSED -> QuickConnectResult.REFUSED
        }
    }

    /** Drops the duplicate guard, then runs B1's foreground disconnect. */
    suspend fun disconnect() {
        synchronized(this) {
            statusAtSend = null
            settledAtSend = null
            sentAt = null
        }
        commands.disconnect(foreground = true)
    }
}
