package dev.vaayil.app.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.vaayil.app.MainActivity
import dev.vaayil.core.automation.QuickConnectResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** What the tile's click needs from the platform: the lock state, the unlock prompt and the app. */
internal interface QuickTileHost {
    fun isSecure(): Boolean
    fun unlockAndRun(action: Runnable)
    fun openApp()
}

/**
 * A tile tap acts on [shown], the state the shade last displayed. Before the first state arrives
 * there is nothing to act on, so the tap does nothing. A disconnect waits for the unlock on a
 * secure lock screen (the same rule the tunnel notification's Disconnect follows); a connect
 * does not, because it only ever starts a tunnel. A connect that cannot get VPN consent leaves
 * one request for the app's manual connect gate, then opens the app — without the consent notice,
 * since the app itself is about to explain it.
 */
internal fun handleTileClick(shown: QuickState?, host: QuickTileHost, controls: QuickControls) {
    when (val action = shown?.action) {
        null -> Unit
        is QuickAction.Connect -> {
            val result = controls.commands.connect(action.profileId, noticeWithoutConsent = false)
            if (result == QuickConnectResult.NEEDS_CONSENT) {
                controls.requests.post(action.profileId)
                host.openApp()
            }
        }
        QuickAction.Disconnect -> {
            if (host.isSecure()) {
                host.unlockAndRun { controls.launchDisconnect() }
            } else {
                controls.launchDisconnect()
            }
        }
        QuickAction.OpenApp -> host.openApp()
    }
}

/**
 * The Quick Settings tile: it shows the hub's state (the profile's name and the state text,
 * hidden on a secure lock screen) and forwards its taps to [handleTileClick]. The system's shade
 * owns the tile's styling; only the icon and the copy come from the app.
 */
class QuickTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var shown: QuickState? = null

    /** The hub, resolved on first use so the service can be created without a container lookup. */
    private val controls: QuickControls by lazy { QuickControlsAccess.forContext(this) }

    /** The platform by default; tests replace it to observe the click's decisions. */
    internal var host: QuickTileHost = PlatformHost()

    override fun onClick() {
        super.onClick()
        handleTileClick(shown, host, controls)
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = scope.launch {
            controls.state.filterNotNull().collect { render(it) }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        listening?.cancel()
        listening = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Applies [state] to the bound tile. Nothing is recorded while the tile is unbound, so
     * [shown] only ever holds a state the tile actually displayed. The subtitle exists from API
     * 29 and the state description from API 30 (D2): below them, the content description carries
     * the state itself, because TalkBack has nowhere else to read it.
     */
    internal fun render(state: QuickState) {
        val tile = qsTile ?: return
        shown = state
        val look = tileLook(state, host.isSecure())
        tile.label = look.label
        tile.state = if (look.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = look.stateText
        }
        if (Build.VERSION.SDK_INT >= 30) {
            tile.stateDescription = look.stateText
            tile.contentDescription = look.label
        } else {
            tile.contentDescription = "${look.label}, ${look.stateText}"
        }
        tile.updateTile()
    }

    private inner class PlatformHost : QuickTileHost {
        override fun isSecure(): Boolean = this@QuickTileService.isSecure()

        override fun unlockAndRun(action: Runnable) {
            this@QuickTileService.unlockAndRun(action)
        }

        @SuppressLint("StartActivityAndCollapseDeprecated")
        @Suppress("DEPRECATION")
        override fun openApp() {
            val intent = Intent(this@QuickTileService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this@QuickTileService,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                startActivityAndCollapse(intent)
            }
        }
    }
}
