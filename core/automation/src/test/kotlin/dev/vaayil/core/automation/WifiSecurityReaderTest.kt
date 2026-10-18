package dev.vaayil.core.automation

import android.net.wifi.WifiInfo
import dev.vaayil.core.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Test

/** S4: only a network that authenticates its clients may ever count as trusted. */
class WifiSecurityReaderTest {
    @Test
    fun `API 31 types without client authentication are open`() {
        for (type in listOf(
            WifiInfo.SECURITY_TYPE_OPEN, WifiInfo.SECURITY_TYPE_OWE, WifiInfo.SECURITY_TYPE_WEP, WifiInfo.SECURITY_TYPE_OSEN,
        )) {
            assertEquals("type $type", WifiSecurity.OPEN, securityOfType(type))
        }
    }

    @Test
    fun `API 31 types that authenticate the client are secured`() {
        for (type in listOf(
            WifiInfo.SECURITY_TYPE_PSK, WifiInfo.SECURITY_TYPE_EAP, WifiInfo.SECURITY_TYPE_SAE,
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT, WifiInfo.SECURITY_TYPE_WAPI_PSK,
            WifiInfo.SECURITY_TYPE_WAPI_CERT, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
            WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2, WifiInfo.SECURITY_TYPE_PASSPOINT_R3, WifiInfo.SECURITY_TYPE_DPP,
        )) {
            assertEquals("type $type", WifiSecurity.SECURED, securityOfType(type))
        }
    }

    @Test
    fun `an unknown or newer API 31 type is unknown`() {
        assertEquals(WifiSecurity.UNKNOWN, securityOfType(WifiInfo.SECURITY_TYPE_UNKNOWN))
        assertEquals(WifiSecurity.UNKNOWN, securityOfType(99))
    }

    @Test
    fun `scan capabilities without client authentication are open`() {
        assertEquals(WifiSecurity.OPEN, securityOfCapabilities("[ESS]"))
        assertEquals(WifiSecurity.OPEN, securityOfCapabilities(""))
        assertEquals(WifiSecurity.OPEN, securityOfCapabilities("[WEP][ESS]"))
        assertEquals(WifiSecurity.OPEN, securityOfCapabilities("[RSN-OWE-CCMP][ESS]"))
        assertEquals(WifiSecurity.OPEN, securityOfCapabilities("[ESS][OWE_TRANSITION]"))
    }

    @Test
    fun `scan capabilities with client authentication are secured`() {
        assertEquals(WifiSecurity.SECURED, securityOfCapabilities("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals(WifiSecurity.SECURED, securityOfCapabilities("[RSN-SAE-CCMP][ESS]"))
        assertEquals(WifiSecurity.SECURED, securityOfCapabilities("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]"))
        assertEquals(WifiSecurity.SECURED, securityOfCapabilities("[WAPI-PSK]"))
    }

    @Test
    fun `the connected network is found by its BSSID, and no match is unknown`() {
        val scans = listOf("aa:bb:cc:00:00:01" to "[ESS]", "AA:BB:CC:00:00:02" to "[WPA2-PSK-CCMP][ESS]")
        assertEquals(WifiSecurity.SECURED, securityOfScan("aa:bb:cc:00:00:02", scans))
        assertEquals(WifiSecurity.OPEN, securityOfScan("aa:bb:cc:00:00:01", scans))
        assertEquals(WifiSecurity.UNKNOWN, securityOfScan("aa:bb:cc:00:00:03", scans))
        assertEquals(WifiSecurity.UNKNOWN, securityOfScan(null, scans))
        // Without location Android reports this placeholder BSSID: it names no access point.
        assertEquals(WifiSecurity.UNKNOWN, securityOfScan("02:00:00:00:00:00", scans + ("02:00:00:00:00:00" to "[ESS]")))
    }

    // ---- K4, below API 31: one BSSID's last definite reading stands in for a scan-list gap.

    @Test
    fun `an unknown read of the remembered BSSID reports its last definite reading`() {
        val memory = BssidSecurityMemory()
        assertEquals(WifiSecurity.SECURED, memory.resolve("aa:bb:cc:00:00:02", WifiSecurity.SECURED))
        // The BSSID matches ignoring case.
        assertEquals(WifiSecurity.SECURED, memory.resolve("AA:BB:CC:00:00:02", WifiSecurity.UNKNOWN))
    }

    @Test
    fun `a definite read replaces the memory at once`() {
        val memory = BssidSecurityMemory()
        assertEquals(WifiSecurity.SECURED, memory.resolve("aa:bb:cc:00:00:02", WifiSecurity.SECURED))
        assertEquals(WifiSecurity.OPEN, memory.resolve("aa:bb:cc:00:00:02", WifiSecurity.OPEN))
        assertEquals(WifiSecurity.OPEN, memory.resolve("aa:bb:cc:00:00:02", WifiSecurity.UNKNOWN))
    }

    @Test
    fun `a different BSSID never inherits the memory`() {
        val memory = BssidSecurityMemory()
        assertEquals(WifiSecurity.SECURED, memory.resolve("aa:bb:cc:00:00:02", WifiSecurity.SECURED))
        assertEquals(WifiSecurity.UNKNOWN, memory.resolve("aa:bb:cc:00:00:03", WifiSecurity.UNKNOWN))
        assertEquals(WifiSecurity.UNKNOWN, memory.resolve(null, WifiSecurity.UNKNOWN))
        // The redacted placeholder names no access point: never the remembered one.
        assertEquals(WifiSecurity.UNKNOWN, memory.resolve("02:00:00:00:00:00", WifiSecurity.UNKNOWN))
    }

    @Test
    fun `clear forgets the memory`() {
        val memory = BssidSecurityMemory()
        assertEquals(WifiSecurity.SECURED, memory.resolve("aa:bb:cc:00:00:02", WifiSecurity.SECURED))
        memory.clear()
        assertEquals(WifiSecurity.UNKNOWN, memory.resolve("aa:bb:cc:00:00:02", WifiSecurity.UNKNOWN))
    }

    @Test
    fun `a definite read without a usable BSSID remembers nothing`() {
        val memory = BssidSecurityMemory()
        assertEquals(WifiSecurity.SECURED, memory.resolve(null, WifiSecurity.SECURED))
        assertEquals(WifiSecurity.UNKNOWN, memory.resolve(null, WifiSecurity.UNKNOWN))
        assertEquals(WifiSecurity.OPEN, memory.resolve("02:00:00:00:00:00", WifiSecurity.OPEN))
        assertEquals(WifiSecurity.UNKNOWN, memory.resolve("02:00:00:00:00:00", WifiSecurity.UNKNOWN))
    }
}
