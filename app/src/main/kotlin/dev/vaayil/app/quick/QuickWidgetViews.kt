package dev.vaayil.app.quick

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import dev.vaayil.app.R

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

/** The widget's views for [state]; the taps are Task 9's. */
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
    }
}
