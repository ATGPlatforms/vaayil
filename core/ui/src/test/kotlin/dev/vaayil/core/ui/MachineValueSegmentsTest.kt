package dev.vaayil.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * U2: a machine value's line-break opportunities — after '.' and '/', before ':' — so a
 * port never splits from its colon. Display-only: the concatenation is always the value.
 */
class MachineValueSegmentsTest {
    @Test
    fun `an IPv4 endpoint breaks after dots and before the port`() {
        assertEquals(
            listOf("vpn.", "example.", "net", ":51820"),
            machineValueSegments("vpn.example.net:51820"),
        )
    }

    @Test
    fun `a bracketed IPv6 endpoint breaks before every colon`() {
        assertEquals(
            listOf("[2001", ":db8", ":", ":5]", ":443"),
            machineValueSegments("[2001:db8::5]:443"),
        )
    }

    @Test
    fun `a URL breaks after its slashes and dots`() {
        assertEquals(
            listOf("https", ":/", "/", "dns.", "example.", "net/", "dns-query"),
            machineValueSegments("https://dns.example.net/dns-query"),
        )
    }

    @Test
    fun `a DNS list keeps its commas and spaces`() {
        assertEquals(
            listOf("1.", "1.", "1.", "1, 9.", "9.", "9.", "9"),
            machineValueSegments("1.1.1.1, 9.9.9.9"),
        )
    }

    @Test
    fun `values without break points and empty values stay whole`() {
        assertEquals(listOf("auto"), machineValueSegments("auto"))
        assertEquals(listOf("—"), machineValueSegments("—"))
        assertEquals(listOf(""), machineValueSegments(""))
    }
}
