package dev.vaayil.app

import dev.vaayil.core.config.WgConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * F18: parse-without-save. A draft is built in memory only; bad input throws like `import` did.
 * F19: the search domains a draft could not keep come back with it, for the review's notice.
 */
class ImportDraftTest {

    // Borrowed from ImportViewModelTest: a minimal valid wg-quick config.
    private val conf = """
        [Interface]
        PrivateKey = eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=
        Address = 10.0.0.2/32

        [Peer]
        PublicKey = KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=
        AllowedIPs = 0.0.0.0/0
        Endpoint = vpn.example.com:51820
    """.trimIndent()

    @Test
    fun `a valid config keeps a free name`() {
        val draft = parseImportDraft(conf, "Home", emptySet())
        assertEquals("Home", draft.profile.name)
    }

    @Test
    fun `a taken name comes back with the first free number`() {
        val draft = parseImportDraft(conf, "Home", setOf("Home", "Home (2)"))
        assertEquals("Home (3)", draft.profile.name)
    }

    @Test
    fun `malformed text throws the parser's exception`() {
        assertThrows(WgConfigParser.ParseException::class.java) {
            parseImportDraft("not a config", "Home", emptySet())
        }
    }

    @Test
    fun `search domains come back with the draft`() {
        val draft = parseImportDraft(
            conf.replace("Address = 10.0.0.2/32", "Address = 10.0.0.2/32\nDNS = 1.1.1.1, corp.example, lab.example"),
            "Home",
            emptySet(),
        )
        assertEquals(listOf("1.1.1.1"), draft.profile.interfaceConfig.dnsServers)
        assertEquals(listOf("corp.example", "lab.example"), draft.droppedSearchDomains)
    }

    @Test
    fun `the notice names every dropped search domain`() {
        assertEquals(
            "Search domains aren't supported, so these were left out: corp.example, lab.example.",
            searchDomainNotice(listOf("corp.example", "lab.example")),
        )
        assertNull(searchDomainNotice(emptyList()))
    }

    @Test
    fun `only a parser refusal reaches the import banner`() {
        val refusal = WgConfigParser.ParseException(0, "That doesn't look like a WireGuard config.")
        assertEquals("That doesn't look like a WireGuard config.", importFailureMessage(refusal))
        assertNull(importFailureMessage(IllegalStateException("SQLITE_FULL: database or disk is full")))
    }
}
