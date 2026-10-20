package dev.vaayil.app.quick

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import dev.vaayil.app.R
import dev.vaayil.core.tunnel.wg.TunnelNotifications

/**
 * The widget's per-tone resources. ACTIVE is the only sage face (CONNECTED with a VERIFIED
 * handshake); WARNING covers connecting and waiting, IDLE disconnected, ERROR a failed start.
 */
internal data class WidgetLook(
    @DrawableRes val disc: Int,
    @DrawableRes val glyph: Int,
    @DrawableRes val dot: Int,
    @ColorRes val stateColor: Int,
    val discDescription: String,
)

internal fun widgetLook(state: QuickState): WidgetLook = WidgetLook(
    disc = when (state.tone) {
        QuickTone.ACTIVE -> R.drawable.quick_widget_disc_verified
        QuickTone.WARNING, QuickTone.IDLE -> R.drawable.quick_widget_disc_idle
        QuickTone.ERROR -> R.drawable.quick_widget_disc_failed
    },
    glyph = when (state.tone) {
        QuickTone.ACTIVE -> R.drawable.ic_quick_power_on_primary
        else -> R.drawable.ic_quick_power_idle
    },
    dot = when (state.tone) {
        QuickTone.ACTIVE -> R.drawable.quick_widget_dot_active
        QuickTone.WARNING -> R.drawable.quick_widget_dot_warning
        QuickTone.IDLE -> R.drawable.quick_widget_dot_idle
        QuickTone.ERROR -> R.drawable.quick_widget_dot_error
    },
    stateColor = when (state.tone) {
        QuickTone.ACTIVE -> R.color.vellum_on_field_active
        QuickTone.WARNING -> R.color.vellum_on_warning
        QuickTone.IDLE -> R.color.vellum_on_surface_variant
        QuickTone.ERROR -> R.color.vellum_on_error
    },
    discDescription = when (state.action) {
        is QuickAction.Connect -> "Connect ${state.name}"
        QuickAction.Disconnect -> "Disconnect ${state.name}"
        QuickAction.OpenApp -> "Open Vaayil"
    },
)

/**
 * The widget's tap targets by view id: the disc acts on [state]'s action and the rest of the
 * widget opens Vaayil. A target whose intent the platform cannot build is left out.
 */
internal fun widgetClicks(context: Context, state: QuickState): Map<Int, PendingIntent> {
    val openApp = TunnelNotifications.openAppIntent(context)
    val disc = when (val action = state.action) {
        is QuickAction.Connect -> PendingIntent.getBroadcast(
            context, 0,
            Intent(context, QuickWidgetReceiver::class.java)
                .setAction(QuickWidgetReceiver.ACTION_CONNECT)
                .putExtra(QuickWidgetReceiver.EXTRA_PROFILE_ID, action.profileId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        QuickAction.Disconnect -> PendingIntent.getBroadcast(
            context, 0,
            Intent(context, QuickWidgetReceiver::class.java)
                .setAction(QuickWidgetReceiver.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        QuickAction.OpenApp -> openApp
    }
    return buildMap {
        disc?.let { put(R.id.quick_widget_disc, it) }
        openApp?.let { put(android.R.id.background, it) }
    }
}

/**
 * The widget's views for [state], carrying the taps [widgetClicks] builds: the disc connects
 * or disconnects, and the rest of the widget opens Vaayil.
 */
internal fun quickWidgetViews(context: Context, state: QuickState): RemoteViews {
    val look = widgetLook(state)
    return RemoteViews(context.packageName, R.layout.quick_widget).apply {
        setTextViewText(R.id.quick_widget_name, state.name)
        setTextViewText(R.id.quick_widget_state, state.stateText)
        setTextColor(R.id.quick_widget_state, context.getColor(look.stateColor))
        setImageViewResource(R.id.quick_widget_dot, look.dot)
        setImageViewResource(R.id.quick_widget_disc, look.glyph)
        setInt(R.id.quick_widget_disc, "setBackgroundResource", look.disc)
        setContentDescription(R.id.quick_widget_disc, look.discDescription)
        setViewVisibility(R.id.quick_widget_state_row, View.VISIBLE)
        widgetClicks(context, state).forEach { (id, click) -> setOnClickPendingIntent(id, click) }
    }
}
