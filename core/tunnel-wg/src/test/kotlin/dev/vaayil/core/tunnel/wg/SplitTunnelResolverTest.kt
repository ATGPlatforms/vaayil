package dev.vaayil.core.tunnel.wg

import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitTunnelResolverTest {
    private val installed = setOf("com.chrome", "com.whatsapp", "com.bank", "com.game")

    @Test
    fun `exclude mode returns excluded list`() {
        val r = SplitTunnelResolver.resolve(
            SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, setOf("com.bank", "com.game")), installed, "dev.vaayil.android")
        assertTrue(r.included.isEmpty())
        assertEquals(setOf("com.bank", "com.game"), r.excluded.toSet())
    }

    @Test
    fun `include mode returns included list`() {
        val r = SplitTunnelResolver.resolve(
            SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.bank")), installed, "dev.vaayil.android")
        assertTrue(r.excluded.isEmpty())
        assertEquals(listOf("com.bank", "dev.vaayil.android"), r.included)
    }

    @Test
    fun `stale packages are pruned`() {
        val r = SplitTunnelResolver.resolve(
            SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, setOf("com.gone", "com.bank")), installed, "dev.vaayil.android")
        assertEquals(listOf("com.bank"), r.excluded)
    }

    @Test
    fun `empty policy yields empty lists`() {
        val r = SplitTunnelResolver.resolve(
            SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, emptySet()), installed, "dev.vaayil.android")
        assertTrue(r.excluded.isEmpty() && r.included.isEmpty())
    }

    @Test
    fun `include mode always keeps the app itself inside the tunnel`() {
        val policy = SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.example.browser"))
        val resolved = SplitTunnelResolver.resolve(policy, setOf("com.example.browser"), "dev.vaayil.android")
        assertEquals(listOf("com.example.browser", "dev.vaayil.android"), resolved.included)
        assertTrue(resolved.excluded.isEmpty())
    }

    @Test
    fun `exclude mode never excludes the app itself`() {
        val policy = SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, setOf("dev.vaayil.android", "com.example.bank"))
        val resolved = SplitTunnelResolver.resolve(policy, setOf("dev.vaayil.android", "com.example.bank"), "dev.vaayil.android")
        assertEquals(listOf("com.example.bank"), resolved.excluded)
    }

    @Test
    fun `an include list with no installed app applies no restriction at all`() {
        // Allowing only the app itself into the VPN would put every other app outside it while
        // the dashboard says Connected (S2): fail closed and tunnel everything instead.
        val gone = SplitTunnelResolver.resolve(
            SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.uninstalled")), installed, "dev.vaayil.android")
        assertTrue(gone.included.isEmpty() && gone.excluded.isEmpty())
        val empty = SplitTunnelResolver.resolve(
            SplitTunnelPolicy(SplitTunnelMode.INCLUDE, emptySet()), installed, "dev.vaayil.android")
        assertTrue(empty.included.isEmpty() && empty.excluded.isEmpty())
    }
}
