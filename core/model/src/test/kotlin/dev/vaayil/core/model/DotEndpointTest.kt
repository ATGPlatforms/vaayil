package dev.vaayil.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** D7: DoT endpoint parsing — IPv6 literals (bracketed and bare) plus unchanged hostname forms. */
class DotEndpointTest {
    @Test
    fun `bracketed ipv6 with port`() {
        assertEquals("2001:db8::1" to 853, parseDotEndpoint("[2001:db8::1]:853"))
        assertEquals("2001:db8::1" to 5353, parseDotEndpoint("[2001:db8::1]:5353"))
        assertEquals("::1" to 853, parseDotEndpoint("[::1]:853"))
    }

    @Test
    fun `bracketed ipv6 without port gets default port`() {
        assertEquals("2001:db8::1" to DOT_DEFAULT_PORT, parseDotEndpoint("[2001:db8::1]"))
        assertEquals("::1" to DOT_DEFAULT_PORT, parseDotEndpoint("[::1]"))
    }

    @Test
    fun `bare ipv6 literal gets default port`() {
        assertEquals("2001:db8::1" to DOT_DEFAULT_PORT, parseDotEndpoint("2001:db8::1"))
        assertEquals("::1" to DOT_DEFAULT_PORT, parseDotEndpoint("::1"))
        assertEquals("2001:DB8::1" to DOT_DEFAULT_PORT, parseDotEndpoint("2001:DB8::1")) // host stays as written
    }

    @Test
    fun `hostname and ipv4 forms unchanged`() {
        assertEquals("one.one.one.one" to DOT_DEFAULT_PORT, parseDotEndpoint("one.one.one.one"))
        assertEquals("one.one.one.one" to 5353, parseDotEndpoint("one.one.one.one:5353"))
        assertEquals("9.9.9.9" to 853, parseDotEndpoint("9.9.9.9:853"))
    }

    @Test
    fun `ambiguous bare forms rejected`() {
        // Parses both as an address and as address:port — brackets required to express the port.
        assertNull(parseDotEndpoint("2001:db8::1:853"))
        assertNull(parseDotEndpoint("::1:853"))
    }

    @Test
    fun `malformed forms rejected`() {
        assertNull(parseDotEndpoint("[2001:db8::1")) // unclosed bracket
        assertNull(parseDotEndpoint("2001:db8::1:89:")) // trailing colon
        assertNull(parseDotEndpoint("[2001:db8::1]junk"))
        assertNull(parseDotEndpoint("[2001:db8::1]:0"))
        assertNull(parseDotEndpoint("[2001:db8::1]:99999"))
        assertNull(parseDotEndpoint("[2001:db8::1]:abc"))
        assertNull(parseDotEndpoint("[]:853"))
        assertNull(parseDotEndpoint("[9.9.9.9]"))
        assertNull(parseDotEndpoint("host:"))
        assertNull(parseDotEndpoint("host:abc"))
        assertNull(parseDotEndpoint("one.one.one.one:99999"))
        assertNull(parseDotEndpoint("1:2:3:4:5:6:7:8:853"))
        assertNull(parseDotEndpoint(""))
    }
}
