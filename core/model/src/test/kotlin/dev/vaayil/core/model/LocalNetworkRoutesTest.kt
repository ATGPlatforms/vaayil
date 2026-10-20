package dev.vaayil.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** The route maths behind the local-network switch: trim local ranges out, keep local endpoints reachable. */
class LocalNetworkRoutesTest {
    private val v4Default = listOf(
        "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3", "64.0.0.0/2",
        "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/8", "169.0.0.0/9", "169.128.0.0/10", "169.192.0.0/11",
        "169.224.0.0/12", "169.240.0.0/13", "169.248.0.0/14", "169.252.0.0/15", "169.255.0.0/16", "170.0.0.0/7",
        "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9", "173.0.0.0/8", "174.0.0.0/7",
        "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11", "192.160.0.0/13", "192.169.0.0/16", "192.170.0.0/15",
        "192.172.0.0/14", "192.176.0.0/12", "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6",
        "200.0.0.0/5", "208.0.0.0/4",
    )
    private val v6Default = listOf(
        "0:0:0:0:0:0:0:0/1", "8000:0:0:0:0:0:0:0/2", "c000:0:0:0:0:0:0:0/3", "e000:0:0:0:0:0:0:0/4",
        "f000:0:0:0:0:0:0:0/5", "f800:0:0:0:0:0:0:0/6", "fe00:0:0:0:0:0:0:0/9", "fec0:0:0:0:0:0:0:0/10",
    )

    private fun routes(allowed: List<String>, iface: List<String> = emptyList(), dns: List<String> = emptyList()) =
        localNetworkRoutes(allowed, iface, dns)

    @Test
    fun `the local ranges are the private, link-local and multicast blocks`() {
        assertEquals(
            listOf(
                "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/3",
                "fc00::/7", "fe80::/10", "ff00::/8",
            ),
            LOCAL_NETWORK_RANGES,
        )
    }

    @Test
    fun `an IPv4 default route loses exactly the local ranges`() {
        assertEquals(v4Default, routes(listOf("0.0.0.0/0")))
    }

    @Test
    fun `an IPv6 default route loses exactly the local ranges`() {
        assertEquals(v6Default, routes(listOf("::/0")))
    }

    @Test
    fun `two halves trim to the same routes as the whole`() {
        assertEquals(v4Default, routes(listOf("0.0.0.0/1", "128.0.0.0/1")))
    }

    @Test
    fun `an entry inside a local range is kept as it is`() {
        assertEquals(v4Default + "192.168.1.0/24", routes(listOf("0.0.0.0/0", "192.168.1.0/24")))
        assertEquals(listOf("10.0.0.0/8"), routes(listOf("10.0.0.0/8")))
    }

    @Test
    fun `a kept entry is written with its host bits cleared`() {
        assertEquals(listOf("192.168.1.0/24"), routes(listOf("192.168.1.7/24")))
    }

    @Test
    fun `an entry with no local range in it stays as it is`() {
        assertEquals(
            listOf("100.64.0.0/10", "2001:db8:0:0:0:0:0:0/32"),
            routes(listOf("100.64.0.0/10", "2001:db8::/32")),
        )
    }

    @Test
    fun `the interface subnet stays in the tunnel`() {
        assertEquals(v4Default + "10.8.0.0/24", routes(listOf("0.0.0.0/0"), iface = listOf("10.8.0.2/24")))
    }

    @Test
    fun `a 32-bit interface address keeps only itself`() {
        assertEquals(v4Default + "10.8.0.2/32", routes(listOf("0.0.0.0/0"), iface = listOf("10.8.0.2/32")))
    }

    @Test
    fun `an interface subnet wider than a local range keeps the range inside it`() {
        assertEquals(v4Default + "172.16.0.0/12", routes(listOf("0.0.0.0/0"), iface = listOf("172.16.1.2/8")))
    }

    @Test
    fun `an interface subnet that is partly local gets back only its local part`() {
        assertEquals(v4Default + "10.0.0.0/8", routes(listOf("0.0.0.0/0"), iface = listOf("10.1.2.3/7")))
    }

    @Test
    fun `a wide interface subnet gains nothing that no trimmed entry covered`() {
        assertEquals(listOf("192.169.0.0/16"), routes(listOf("192.168.0.0/15"), iface = listOf("172.16.1.2/8")))
    }

    @Test
    fun `a private DNS server gets a host route`() {
        assertEquals(
            v4Default + "10.64.0.1/32",
            routes(listOf("0.0.0.0/0"), dns = listOf("10.64.0.1", "9.9.9.9")),
        )
    }

    @Test
    fun `nothing is kept that no trimmed entry covered`() {
        assertEquals(
            listOf("100.64.0.0/10"),
            routes(listOf("100.64.0.0/10"), iface = listOf("192.168.1.2/24"), dns = listOf("192.168.1.1")),
        )
    }

    @Test
    fun `a keep that a route already covers is not added`() {
        assertEquals(
            v4Default + "10.0.0.0/8",
            routes(listOf("0.0.0.0/0", "10.0.0.0/8"), dns = listOf("10.64.0.1")),
        )
        assertEquals(
            v4Default + "10.8.0.0/24",
            routes(listOf("0.0.0.0/0"), iface = listOf("10.8.0.2/24"), dns = listOf("10.8.0.1")),
        )
    }

    @Test
    fun `dual stack keeps each family's subnet and DNS host route`() {
        assertEquals(
            v4Default + v6Default + listOf("10.8.0.0/24", "fd42:0:0:0:0:0:0:0/64", "fd00:0:0:0:0:0:0:53/128"),
            routes(
                listOf("0.0.0.0/0", "::/0"),
                iface = listOf("10.8.0.2/24", "fd42::2/64"),
                dns = listOf("fd00::53", "9.9.9.9"),
            ),
        )
    }

    @Test
    fun `an IPv4-only profile gets no IPv6 route`() {
        assertEquals(
            v4Default + "10.8.0.0/24",
            routes(listOf("0.0.0.0/0"), iface = listOf("10.8.0.2/24", "fd42::2/64"), dns = listOf("fd00::53")),
        )
    }

    @Test
    fun `duplicates appear once`() {
        assertEquals(
            v4Default + "10.64.0.1/32",
            routes(listOf("0.0.0.0/0", "0.0.0.0/0"), dns = listOf("10.64.0.1", "10.64.0.1")),
        )
    }

    @Test
    fun `an entry that is not a strict literal passes through`() {
        assertEquals(
            v4Default + listOf("::ffff:10.0.0.0/104", "bogus"),
            routes(listOf("0.0.0.0/0", "::ffff:10.0.0.0/104", "bogus")),
        )
    }
}
