// SPDX-License-Identifier: GPL-3.0-or-later

package dev.vaayil.wgtunnel;

import androidx.annotation.Nullable;

import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.Peer;

import java.util.ArrayList;
import java.util.List;

/**
 * A tunnel's own route list for GoBackend, in place of the peers' AllowedIPs. A tunnel implements this interface
 * when it can compute the routes itself; a tunnel that does not, or that returns null, keeps the AllowedIPs.
 */
public interface VpnRoutes {
    /** The routes to hand VpnService.Builder instead of the peers' AllowedIPs; null means the AllowedIPs. */
    @Nullable
    List<InetNetwork> getVpnRoutes();

    /** The tunnel's own list when it implements VpnRoutes and has one; otherwise every peer's AllowedIPs, in order. */
    static List<InetNetwork> routesFor(final Tunnel tunnel, final Config config) {
        if (tunnel instanceof VpnRoutes) {
            final List<InetNetwork> routes = ((VpnRoutes) tunnel).getVpnRoutes();
            if (routes != null)
                return routes;
        }
        final List<InetNetwork> routes = new ArrayList<>();
        for (final Peer peer : config.getPeers())
            routes.addAll(peer.getAllowedIps());
        return routes;
    }
}
