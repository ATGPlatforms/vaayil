package dev.vaayil.app.quick

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** What the updater needs from the platform: false when no widget is placed, or no manager. */
internal fun interface WidgetPusher {
    fun push(state: QuickState): Boolean
}

/** Pushes a state to every placed widget through the system's widget manager. */
internal class AppWidgetPusher(private val context: Context) : WidgetPusher {
    override fun push(state: QuickState): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        val ids = manager.getAppWidgetIds(ComponentName(context, QuickWidgetProvider::class.java))
        if (ids.isEmpty()) return false
        manager.updateAppWidget(ids, quickWidgetViews(context, state))
        return true
    }
}

/**
 * Keeps every placed widget on the hub's state. Each state differing from the last successfully
 * pushed one is pushed once; [refresh] pushes regardless, because a newly placed widget needs
 * the current views even when the state has not changed. A refresh reads its state under the
 * same lock, so it never lands an older state over a newer push, and a null state pushes
 * nothing. A push that throws is logged and counts as not pushed, so the collection never ends
 * and the next change still lands.
 */
internal class WidgetUpdater(private val pusher: WidgetPusher) {
    private var lastPushed: QuickState? = null

    fun launchIn(scope: CoroutineScope, states: Flow<QuickState>): Job = scope.launch {
        states.collect { state ->
            synchronized(this@WidgetUpdater) {
                if (state != lastPushed) pushLocked(state)
            }
        }
    }

    fun refresh(newest: () -> QuickState?) {
        synchronized(this@WidgetUpdater) { newest()?.let(::pushLocked) }
    }

    /** Callers hold this updater's lock: pushes never overlap and their record is ordered. */
    private fun pushLocked(state: QuickState) {
        try {
            if (pusher.push(state)) lastPushed = state
        } catch (e: Exception) {
            Log.w(TAG, "widget push failed: ${e.javaClass.simpleName}", e)
        }
    }

    private companion object {
        const val TAG = "WidgetUpdater"
    }
}
