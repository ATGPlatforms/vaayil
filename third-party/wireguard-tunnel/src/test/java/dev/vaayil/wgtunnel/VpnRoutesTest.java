// SPDX-License-Identifier: GPL-3.0-or-later

package dev.vaayil.wgtunnel;

import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;
import com.wireguard.config.InetNetwork;
import com.wireguard.config.ParseException;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class VpnRoutesTest {
    private static final String WG_QUICK = String.join("\n",
            "[Interface]",
            "PrivateKey = " + "A".repeat(43) + "=",
            "Address = 10.0.0.2/32",
            "",
            "[Peer]",
            "PublicKey = " + "E".repeat(43) + "=",
            "AllowedIPs = 10.0.0.0/8, 0.0.0.0/0",
            "",
            "[Peer]",
            "PublicKey = " + "I".repeat(43) + "=",
            "AllowedIPs = ::/0");

    private static Config config() {
        try (final InputStream stream = new ByteArrayInputStream(WG_QUICK.getBytes(StandardCharsets.UTF_8))) {
            return Config.parse(stream);
        } catch (final IOException | BadConfigException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void aPlainTunnelGetsEveryPeersAllowedIpsInOrder() throws ParseException {
        assertEquals(
                Arrays.asList(InetNetwork.parse("10.0.0.0/8"), InetNetwork.parse("0.0.0.0/0"),
                        InetNetwork.parse("::/0")),
                VpnRoutes.routesFor(new PlainTunnel(), config()));
    }

    @Test
    public void aTunnelWithItsOwnRoutesGetsThem() throws ParseException {
        final List<InetNetwork> routes = Collections.singletonList(InetNetwork.parse("192.0.2.0/24"));
        assertEquals(routes, VpnRoutes.routesFor(new RoutingTunnel(routes), config()));
    }

    @Test
    public void aTunnelWithNullRoutesGetsTheAllowedIps() throws ParseException {
        assertEquals(
                Arrays.asList(InetNetwork.parse("10.0.0.0/8"), InetNetwork.parse("0.0.0.0/0"),
                        InetNetwork.parse("::/0")),
                VpnRoutes.routesFor(new RoutingTunnel(null), config()));
    }

    private static class PlainTunnel implements Tunnel {
        @Override
        public String getName() {
            return "test";
        }

        @Override
        public void onStateChange(final State newState) {
        }
    }

    private static final class RoutingTunnel extends PlainTunnel implements VpnRoutes {
        private final List<InetNetwork> routes;

        private RoutingTunnel(final List<InetNetwork> routes) {
            this.routes = routes;
        }

        @Override
        public List<InetNetwork> getVpnRoutes() {
            return routes;
        }
    }
}
