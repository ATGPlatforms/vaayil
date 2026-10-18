package dev.vaayil.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoConnectRulesTest {
    @Test
    fun `guard off means none`() {
        val r = AutoConnectRules(
            wifiGuardEnabled = false,
            trustedSsids = setOf("Home"),
            disconnectOnTrusted = true,
        )
        assertEquals(SsidDecision.NONE, r.decide("Home", WifiSecurity.SECURED, tunnelConnected = false))
        assertEquals(SsidDecision.NONE, r.decide("Home", WifiSecurity.SECURED, tunnelConnected = true))
    }

    @Test
    fun `untrusted network connects when disconnected`() {
        val r = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"))
        assertEquals(SsidDecision.CONNECT, r.decide("CoffeeShop", WifiSecurity.SECURED, tunnelConnected = false))
    }

    @Test
    fun `untrusted network is none when already connected`() {
        val r = AutoConnectRules(wifiGuardEnabled = true)
        assertEquals(SsidDecision.NONE, r.decide("CoffeeShop", WifiSecurity.SECURED, tunnelConnected = true))
    }

    @Test
    fun `empty trusted list treats every network as untrusted`() {
        val r = AutoConnectRules(wifiGuardEnabled = true)
        assertEquals(SsidDecision.CONNECT, r.decide("Anything", WifiSecurity.SECURED, false))
    }

    @Test
    fun `trusted network disconnects only when asked and connected`() {
        val r = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"), disconnectOnTrusted = true)
        assertEquals(SsidDecision.DISCONNECT, r.decide("Home", WifiSecurity.SECURED, tunnelConnected = true))
        assertEquals(SsidDecision.NONE, r.decide("Home", WifiSecurity.SECURED, tunnelConnected = false))
        assertEquals(SsidDecision.NONE, r.copy(disconnectOnTrusted = false).decide("Home", WifiSecurity.SECURED, true))
    }

    @Test
    fun `an open or unknown network is never trusted, whatever its name`() {
        val r = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"), disconnectOnTrusted = true)
        assertEquals(SsidDecision.NONE, r.decide("Home", WifiSecurity.OPEN, tunnelConnected = true)) // the tunnel stays
        assertEquals(SsidDecision.NONE, r.decide("Home", WifiSecurity.UNKNOWN, tunnelConnected = true))
        assertEquals(SsidDecision.CONNECT, r.decide("Home", WifiSecurity.OPEN, tunnelConnected = false))
        assertEquals(SsidDecision.CONNECT, r.decide("Home", WifiSecurity.UNKNOWN, tunnelConnected = false))
    }

    @Test
    fun `null or blank ssid is none`() {
        val r = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home"), disconnectOnTrusted = true)
        assertEquals(SsidDecision.NONE, r.decide(null, WifiSecurity.UNKNOWN, tunnelConnected = false))
        assertEquals(SsidDecision.NONE, r.decide("", WifiSecurity.UNKNOWN, tunnelConnected = false))
        assertEquals(SsidDecision.NONE, r.decide("   ", WifiSecurity.UNKNOWN, tunnelConnected = false))
    }

    @Test
    fun `normalizeSsid strips quotes`() {
        assertEquals("Home", normalizeSsid("\"Home\""))
        assertNull(normalizeSsid("\"\""))
        assertNull(normalizeSsid(null))
        assertEquals("Home", normalizeSsid("  Home  "))
        assertNull(normalizeSsid("   "))
    }

    @Test
    fun `mobile decision connects only when enabled on mobile with an idle tunnel`() {
        val r = AutoConnectRules(autoConnectOnMobileData = true)
        assertEquals(SsidDecision.CONNECT, r.mobileDecision(isMobileData = true, tunnelBusy = false))
        assertEquals(SsidDecision.NONE, r.mobileDecision(isMobileData = true, tunnelBusy = true))
        assertEquals(SsidDecision.NONE, r.mobileDecision(isMobileData = false, tunnelBusy = false))
    }

    @Test
    fun `mobile decision off means none`() {
        val r = AutoConnectRules(autoConnectOnMobileData = false)
        assertEquals(SsidDecision.NONE, r.mobileDecision(isMobileData = true, tunnelBusy = false))
    }

    @Test
    fun `the guard service is needed while either automation rule is on`() {
        assertFalse(AutoConnectRules().needsGuardService)
        assertTrue(AutoConnectRules(wifiGuardEnabled = true).needsGuardService)
        assertTrue(AutoConnectRules(autoConnectOnMobileData = true).needsGuardService)
        assertTrue(AutoConnectRules(wifiGuardEnabled = true, autoConnectOnMobileData = true).needsGuardService)
    }
}
