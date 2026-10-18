package dev.vaayil.core.tunnel.wg

import dev.vaayil.core.tunnel.wg.NetworkTraits.Transport
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S10: the pure underlying-network selection, the bind wrapper and the protect-then-bind composition. */
class UnderlyingNetworkTest {
    private fun traits(
        transport: Transport,
        validated: Boolean = true,
        vpn: Boolean = false,
        internet: Boolean = true,
        foreground: Boolean = true,
        restricted: Boolean = false,
    ) = NetworkTraits(
        isVpn = vpn,
        hasInternet = internet,
        validated = validated,
        foreground = foreground,
        notRestricted = !restricted,
        transport = transport,
    )

    @Test
    fun `vpn networks and networks without internet never qualify`() {
        val snapshot = listOf(
            "vpn" to traits(Transport.OTHER, vpn = true),
            "local-only" to traits(Transport.WIFI, internet = false),
        )
        assertNull(selectUnderlying(snapshot))
    }

    @Test
    fun `a validated network beats a better transport that is not validated`() {
        val snapshot = listOf(
            "wifi" to traits(Transport.WIFI, validated = false),
            "cell" to traits(Transport.CELLULAR, validated = true),
        )
        assertEquals("cell", selectUnderlying(snapshot))
    }

    @Test
    fun `transport ranks ethernet over wifi over cellular over other`() {
        val all = listOf(
            "other" to traits(Transport.OTHER),
            "cell" to traits(Transport.CELLULAR),
            "wifi" to traits(Transport.WIFI),
            "eth" to traits(Transport.ETHERNET),
        )
        assertEquals("eth", selectUnderlying(all))
        assertEquals("wifi", selectUnderlying(all.filter { it.first != "eth" }))
        assertEquals("cell", selectUnderlying(all.filter { it.first == "cell" || it.first == "other" }))
        assertEquals("other", selectUnderlying(all.filter { it.first == "other" }))
    }

    @Test
    fun `a tie keeps the first network in snapshot order`() {
        val snapshot = listOf("first" to traits(Transport.WIFI), "second" to traits(Transport.WIFI))
        assertEquals("first", selectUnderlying(snapshot))
    }

    @Test
    fun `an unvalidated network still qualifies when nothing is validated`() {
        assertEquals("wifi", selectUnderlying(listOf("wifi" to traits(Transport.WIFI, validated = false))))
    }

    @Test
    fun `a background network never qualifies, even when validated`() {
        val snapshot = listOf(
            "wifi" to traits(Transport.WIFI, validated = false),
            "cell" to traits(Transport.CELLULAR, foreground = false),
        )
        assertEquals("wifi", selectUnderlying(snapshot))
        assertNull(selectUnderlying(listOf("cell" to traits(Transport.CELLULAR, foreground = false))))
    }

    @Test
    fun `a restricted network never qualifies`() {
        val snapshot = listOf(
            "wifi" to traits(Transport.WIFI, restricted = true),
            "cell" to traits(Transport.CELLULAR, validated = false),
        )
        assertEquals("cell", selectUnderlying(snapshot))
        assertNull(selectUnderlying(listOf("wifi" to traits(Transport.WIFI, restricted = true))))
    }

    @Test
    fun `an empty snapshot selects nothing`() {
        assertNull(selectUnderlying(emptyList<Pair<String, NetworkTraits>>()))
    }

    @Test
    fun `a bind that throws skips the attempt instead of failing`() {
        val failing = skipOnBindFailure<String> { throw IOException("bind refused") }
        assertFalse(failing("socket"))
        val bound = mutableListOf<String>()
        val binding = skipOnBindFailure<String> { bound += it }
        assertTrue(binding("socket"))
        assertEquals(listOf("socket"), bound)
    }

    @Test
    fun `protect runs before bind and a refused protect never binds`() {
        val steps = mutableListOf<String>()
        val route = UnderlyingRoute(
            emptyList(),
            bindDatagram = { steps += "bind-udp"; true },
            bindStream = { steps += "bind-tcp"; true },
        )
        DatagramSocket().use { udp ->
            assertTrue(protectAndBindDatagram({ steps += "protect-udp"; true }, route)(udp))
            assertFalse(protectAndBindDatagram({ steps += "protect-udp"; false }, route)(udp))
        }
        Socket().use { tcp ->
            assertTrue(protectAndBindStream({ steps += "protect-tcp"; true }, route)(tcp))
            assertFalse(protectAndBindStream({ steps += "protect-tcp"; false }, route)(tcp))
        }
        assertEquals(
            listOf("protect-udp", "bind-udp", "protect-udp", "protect-tcp", "bind-tcp", "protect-tcp"),
            steps,
        )
    }

    @Test
    fun `a failed bind fails the composed callback`() {
        val route = UnderlyingRoute(emptyList(), bindDatagram = { false })
        DatagramSocket().use { udp -> assertFalse(protectAndBindDatagram({ true }, route)(udp)) }
    }
}
