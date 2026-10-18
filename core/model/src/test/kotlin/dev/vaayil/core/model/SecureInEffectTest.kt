package dev.vaayil.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B1 §6.2: the one "Secure DNS in effect" predicate the planner, the DNS summary and the editor share. */
class SecureInEffectTest {
    @Test
    fun `secure dns is in effect only with the mode, a type and a non-blank endpoint`() {
        val dot = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = "9.9.9.9")
        assertTrue(dot.secureInEffect)
        assertTrue(dot.copy(secureType = SecureDnsType.DOH, secureEndpoint = "https://9.9.9.9/dns-query").secureInEffect)
        assertFalse(dot.copy(mode = DnsMode.TUNNEL).secureInEffect)
        assertFalse(dot.copy(secureType = null).secureInEffect)
        assertFalse(dot.copy(secureEndpoint = null).secureInEffect)
        assertFalse(dot.copy(secureEndpoint = "   ").secureInEffect)
        assertFalse(DnsPolicy().secureInEffect)
    }
}
