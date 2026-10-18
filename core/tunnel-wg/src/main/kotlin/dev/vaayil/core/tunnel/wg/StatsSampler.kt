package dev.vaayil.core.tunnel.wg

import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import dev.vaayil.core.tunnel.api.TunnelStats
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * G4 (spec §4.4.3): samples at once, then every [fastIntervalMs] until a sample carries a handshake
 * time or [fastWindowMs] have passed since the start; from then on every [slowIntervalMs]. Every
 * sample carries the generation of the start it belongs to (spec §4.4.2).
 */
class StatsSampler(
    private val scope: CoroutineScope,
    private val fastIntervalMs: Long = 1_000,
    private val slowIntervalMs: Long = 10_000,
    private val fastWindowMs: Long = 30_000,
) {
    private var job: Job? = null

    fun start(backend: Backend, tunnel: Tunnel, generation: Long, update: (TunnelStats) -> Unit) {
        job = scope.launch {
            val startedAt = TimeSource.Monotonic.markNow()
            while (isActive) {
                val statistics: Statistics? = withContext(Dispatchers.IO) {
                    runCatching { backend.getStatistics(tunnel) }.getOrNull()
                }
                val handshake = statistics?.peers()
                    ?.mapNotNull { statistics.peer(it)?.latestHandshakeEpochMillis?.takeIf { ms -> ms > 0 } }
                    ?.maxOrNull()
                // stop() cancels without joining; a sample completing after the cancel must not
                // repopulate stats the engine just zeroed (deferred minor #13).
                if (statistics != null && isActive) {
                    update(TunnelStats(handshake, statistics.totalRx(), statistics.totalTx(), generation))
                }
                // G4 §4.4.3: fast until a sample carries a handshake or the window ends. A failed
                // getStatistics leaves handshake null, so those cycles stay fast as well.
                val fast = handshake == null && startedAt.elapsedNow().inWholeMilliseconds < fastWindowMs
                delay(if (fast) fastIntervalMs else slowIntervalMs)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
