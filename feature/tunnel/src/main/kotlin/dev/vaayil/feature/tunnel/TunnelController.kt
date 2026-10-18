package dev.vaayil.feature.tunnel

import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.TunnelEngine
import dev.vaayil.core.tunnel.api.isLive

class TunnelController(
    private val engine: TunnelEngine,
    private val store: TunnelControllerStore,
    private val getProfile: suspend (String) -> Profile?,
) {
    /**
     * No active-pointer write here: the service funnel (EngineProvider.connectById) records it
     * only on a STARTED outcome. Writing it eagerly named the attempted profile as connected
     * before anything had connected, so a failed switch left the stored pointer disagreeing
     * with the tunnel that is actually up.
     */
    suspend fun connect(profile: Profile) {
        engine.start(profile)
    }

    suspend fun disconnect() {
        engine.stop()
        store.setActive(null)
    }

    suspend fun toggle(profileId: String) {
        val st = engine.status.value
        if (st.profileId == profileId && st.phase.isLive) {
            disconnect()
        } else {
            getProfile(profileId)?.let { connect(it) }
        }
    }
}
