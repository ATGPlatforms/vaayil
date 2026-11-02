package dev.vaayil.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The VPN prominent disclosure's copy (Y2), pinned word for word: Play's VpnService policy wants
 * this text before Android's consent dialog, so any wording change must be deliberate.
 */
class VpnDisclosureCopyTest {
    @Test
    fun `the title is verbatim`() {
        assertEquals("VPN connection", VPN_DISCLOSURE_TITLE)
    }

    @Test
    fun `the body is verbatim`() {
        assertEquals(BODY, VPN_DISCLOSURE_TEXT)
    }

    @Test
    fun `the body says connections, never traffic or leak-free`() {
        assertFalse(VPN_DISCLOSURE_TEXT.contains("traffic", ignoreCase = true))
        assertFalse(VPN_DISCLOSURE_TEXT.contains("leak-free", ignoreCase = true))
    }

    private companion object {
        const val BODY =
            "Vaayil uses Android's VpnService to send your device's connections through an encrypted WireGuard tunnel to the server in your profile. Those connections and your DNS lookups pass through Vaayil only on their way to that server and to the DNS servers you set. Vaayil keeps no logs of them, shares them with no one and sends nothing to its developer. Next, Android asks you to allow the VPN connection."
    }
}
