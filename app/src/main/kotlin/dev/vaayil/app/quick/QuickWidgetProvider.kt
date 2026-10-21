package dev.vaayil.app.quick

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The home-screen widget. The system sends APPWIDGET_UPDATE when a widget is placed and when a
 * host restarts; each update asks the hub for the current state and pushes it to every placed
 * widget, even when the updater last pushed the same state.
 */
class QuickWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                QuickControlsAccess.forContext(context).refreshWidgets()
            } catch (e: Exception) {
                Log.w(TAG, "widget refresh failed: ${e.javaClass.simpleName}", e)
            } finally {
                pending?.finish() // null when a test calls onReceive directly.
            }
        }
    }

    private companion object {
        const val TAG = "QuickWidgetProvider"
    }
}
