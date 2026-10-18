// SPDX-License-Identifier: GPL-3.0-or-later

package dev.vaayil.wgtunnel;

import org.junit.Test;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DnsInterceptTest {
    private static final List<InetAddress> RESOLVERS = Arrays.asList(
            address("10.111.222.3"), address("fdcb:b743:7f0b::3"));

    private static InetAddress address(final String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void anIpv4ResolverGetsAHostRoute() {
        assertEquals(32, DnsIntercept.resolverRoutePrefix(address("10.111.222.3"), RESOLVERS));
    }

    @Test
    public void anIpv6ResolverGetsAHostRouteInAnySpelling() {
        assertEquals(128, DnsIntercept.resolverRoutePrefix(address("fdcb:b743:7f0b::3"), RESOLVERS));
        assertEquals(128, DnsIntercept.resolverRoutePrefix(address("FDCB:B743:7F0B:0:0:0:0:0003"), RESOLVERS));
    }

    @Test
    public void anOrdinaryDnsServerGetsNoRoute() {
        assertEquals(-1, DnsIntercept.resolverRoutePrefix(address("9.9.9.9"), RESOLVERS));
        assertEquals(-1, DnsIntercept.resolverRoutePrefix(address("2620:fe::fe"), RESOLVERS));
        assertEquals(-1, DnsIntercept.resolverRoutePrefix(address("10.111.222.4"), RESOLVERS));
    }

    @Test
    public void nothingIsAResolverBeforeAChannelOpens() {
        assertEquals(-1, DnsIntercept.resolverRoutePrefix(address("10.111.222.3")));
        assertFalse(DnsIntercept.isResolver(address("10.111.222.3")));
    }
}
