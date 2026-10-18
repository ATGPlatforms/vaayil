package dev.vaayil.core.automation

import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.SsidDecision
import dev.vaayil.core.model.WifiSecurity
import dev.vaayil.core.tunnel.api.ConnectionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SSID rules must treat a tunnel that is still connecting as busy, like the mobile rule does. */
class GuardDecisionTest {
    private val rules = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"), disconnectOnTrusted = true)

    @Test
    fun `a trusted network joined while connecting disconnects`() =
        assertEquals(SsidDecision.DISCONNECT, rules.decideForPhase("Home", WifiSecurity.SECURED, ConnectionPhase.CONNECTING))

    @Test
    fun `an untrusted network while connecting does not connect again`() =
        assertEquals(SsidDecision.NONE, rules.decideForPhase("Cafe", WifiSecurity.SECURED, ConnectionPhase.CONNECTING))

    @Test
    fun `an untrusted network while idle connects`() =
        assertEquals(SsidDecision.CONNECT, rules.decideForPhase("Cafe", WifiSecurity.SECURED, ConnectionPhase.DISCONNECTED))

    @Test
    fun `an open network named like a trusted one keeps the tunnel`() {
        assertEquals(SsidDecision.NONE, rules.decideForPhase("Home", WifiSecurity.OPEN, ConnectionPhase.CONNECTED))
        assertEquals(SsidDecision.NONE, rules.decideForPhase("Home", WifiSecurity.UNKNOWN, ConnectionPhase.CONNECTING))
    }

    @Test
    fun `the change gate passes each new reading once and a forgotten one again`() {
        val gate = WifiChangeGate()
        assertTrue(gate.changed("Home", WifiSecurity.SECURED))
        assertFalse(gate.changed("Home", WifiSecurity.SECURED))
        assertTrue(gate.changed("Home", WifiSecurity.UNKNOWN)) // same name, new security reading: evaluate again
        gate.forget()
        assertTrue(gate.changed("Home", WifiSecurity.UNKNOWN))
    }
}
