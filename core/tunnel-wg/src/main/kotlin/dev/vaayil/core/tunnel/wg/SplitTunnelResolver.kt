package dev.vaayil.core.tunnel.wg

import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy

data class ResolvedSplitTunnel(val excluded: List<String>, val included: List<String>)

object SplitTunnelResolver {
    fun resolve(policy: SplitTunnelPolicy, installed: Set<String>, ownPackage: String): ResolvedSplitTunnel {
        val live = policy.packages.filter { it in installed }
        return when (policy.mode) {
            SplitTunnelMode.EXCLUDE -> ResolvedSplitTunnel(excluded = live - ownPackage, included = emptyList())
            // The app's own sockets (the in-tunnel resolver's unprotected upstreams) must stay
            // inside the tunnel in INCLUDE mode too (T-4); the picker never offers the app itself.
            // An include list with no installed app would allow ONLY the app into the VPN and put
            // every other app outside it while the dashboard says Connected (S2): fail closed
            // and apply no restriction instead.
            SplitTunnelMode.INCLUDE ->
                if (live.isEmpty()) ResolvedSplitTunnel(excluded = emptyList(), included = emptyList())
                else ResolvedSplitTunnel(excluded = emptyList(), included = (live + ownPackage).distinct())
        }
    }
}
