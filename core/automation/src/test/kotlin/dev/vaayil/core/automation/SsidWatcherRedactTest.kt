package dev.vaayil.core.automation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Location denial must not look like an SSID: "<unknown ssid>" (the redacted
 * value on both pre-31 connectionInfo and 31+ sanitized transportInfo — also
 * the value of the SDK-hidden WifiInfo.UNKNOWN_SSID) flows through as
 * non-blank text unless redacted — which would make decide() treat every
 * Wi-Fi as untrusted and auto-connect (inverting spec §15.7's pause).
 */
class SsidWatcherRedactTest {

    @Test
    fun `unknown ssid literal redacts to null`() {
        assertEquals(null, redactSsid("<unknown ssid>"))
    }

    @Test
    fun `quoted ssid is unwrapped`() {
        assertEquals("Home", redactSsid("\"Home\""))
    }

    @Test
    fun `null redacts to null`() {
        assertEquals(null, redactSsid(null))
    }

    @Test
    fun `blank redacts to null`() {
        assertEquals(null, redactSsid("   "))
    }
}
