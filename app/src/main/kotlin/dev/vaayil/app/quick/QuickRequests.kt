package dev.vaayil.app.quick

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long a posted request stays takeable. */
internal const val QUICK_REQUEST_TTL_MS = 30_000L

internal data class QuickRequest(val profileId: String, val postedAt: Long)

/**
 * The in-memory hand-off between a quick surface that posts a request — a launcher shortcut's
 * trampoline, or the tile when a connect needs VPN consent — and the app screen that services
 * it. Nothing is persisted: a request that outlived the process would point at a place the user
 * has long left, so a process death simply drops it.
 */
internal class QuickRequests(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val _pending = MutableStateFlow<QuickRequest?>(null)
    val pending: StateFlow<QuickRequest?> = _pending.asStateFlow()

    /** A newer request replaces one not yet taken. */
    @Synchronized
    fun post(profileId: String) {
        _pending.value = QuickRequest(profileId, clock())
    }

    /** Empties the slot; null when empty or older than [QUICK_REQUEST_TTL_MS]. */
    @Synchronized
    fun take(): String? {
        val request = _pending.value ?: return null
        _pending.value = null
        return request.profileId.takeIf { clock() - request.postedAt <= QUICK_REQUEST_TTL_MS }
    }
}
