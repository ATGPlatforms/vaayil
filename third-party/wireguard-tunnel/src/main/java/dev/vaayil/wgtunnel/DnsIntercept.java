// SPDX-License-Identifier: GPL-3.0-or-later

package dev.vaayil.wgtunnel;

import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import androidx.annotation.Nullable;

/**
 * Vaayil's handle on the DNS intercept inside libwg-go. libwg-go catches every packet the device sends to a
 * resolver address and carries the DNS queries over one process-wide socket pair; {@link #open} creates that pair
 * and hands over Vaayil's end. libwg-go must already be loaded, which constructing a GoBackend does (the GoBackend
 * constructor calls SharedLibraryLoader.loadSharedLibrary, GoBackend.java).
 */
public final class DnsIntercept {
    private static volatile List<InetAddress> resolvers = Collections.emptyList();

    private DnsIntercept() {
    }

    /**
     * Opens the process-wide DNS channel, or replaces it: libwg-go closes its end of the previous pair, so a reader
     * of the previous descriptor sees end-of-file (net/unix/af_unix.c unix_release_sock; one ECONNRESET first if
     * libwg-go's end still held an unread answer). GoBackend refuses to start a tunnel until a channel was opened
     * (wgTurnOn returns -2, and GoBackend.java setStateInternal throws BackendException for a negative result).
     *
     * @param resolverAddresses the resolver addresses the intercept catches; never empty
     * @return Vaayil's end of the pair, a SOCK_SEQPACKET socket the caller owns and must close (the returned
     *         ParcelFileDescriptor owns the descriptor: ParcelFileDescriptor.java adoptFd)
     * @throws IOException when libwg-go could not open the channel
     */
    public static synchronized ParcelFileDescriptor open(final List<InetAddress> resolverAddresses)
            throws IOException {
        if (resolverAddresses.isEmpty())
            throw new IllegalArgumentException("no resolver addresses");
        final StringBuilder list = new StringBuilder();
        for (final InetAddress address : resolverAddresses) {
            if (list.length() > 0)
                list.append(',');
            list.append(address.getHostAddress());
        }
        final int fd = wgOpenDnsChannel(list.toString());
        if (fd < 0)
            throw new IOException("wgOpenDnsChannel failed: " + fd);
        resolvers = Collections.unmodifiableList(new ArrayList<>(resolverAddresses));
        return ParcelFileDescriptor.adoptFd(fd);
    }

    /** Whether {@code address} is a resolver address of the channel opened last. */
    public static boolean isResolver(final InetAddress address) {
        return resolvers.contains(address);
    }

    /**
     * The host-route prefix GoBackend adds for a DNS server that is a resolver address of the channel opened last,
     * or -1 when it is not one.
     */
    public static int resolverRoutePrefix(final InetAddress dnsServer) {
        return resolverRoutePrefix(dnsServer, resolvers);
    }

    /**
     * The host-route prefix for {@code dnsServer}: 32 for an IPv4 and 128 for an IPv6 resolver address, -1 when
     * {@code dnsServer} is not in {@code resolverSet}.
     */
    public static int resolverRoutePrefix(final InetAddress dnsServer, final Collection<InetAddress> resolverSet) {
        if (!resolverSet.contains(dnsServer))
            return -1;
        if (dnsServer instanceof Inet4Address)
            return 32;
        if (dnsServer instanceof Inet6Address)
            return 128;
        return -1;
    }

    /** One line of libwg-go runtime numbers (goroutines, heap in use, heap released), or null. */
    @Nullable
    public static String runtimeStats() {
        return wgRuntimeStats();
    }

    private static native int wgOpenDnsChannel(String resolvers);

    @Nullable
    private static native String wgRuntimeStats();
}
