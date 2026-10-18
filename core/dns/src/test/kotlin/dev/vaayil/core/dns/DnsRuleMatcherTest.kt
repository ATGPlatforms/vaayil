package dev.vaayil.core.dns

import dev.vaayil.core.model.DnsSplitRule
import kotlin.test.Test
import kotlin.test.assertEquals

class DnsRuleMatcherTest {
    private val matcher = DnsRuleMatcher(listOf(
        DnsSplitRule("internal.corp", bypass = true),
        DnsSplitRule("ads.example", bypass = false),   // explicit tunnel rule (no-op default)
    ))

    @Test fun `exact match bypasses`() {
        assertEquals(SplitVia.BYPASS, matcher.classify("internal.corp"))
    }
    @Test fun `subdomain of bypass rule bypasses`() {
        assertEquals(SplitVia.BYPASS, matcher.classify("nas.internal.corp"))
    }
    @Test fun `similar-but-different domain does not bypass`() {
        assertEquals(SplitVia.TUNNEL, matcher.classify("evilinternal.corp"))
        assertEquals(SplitVia.TUNNEL, matcher.classify("internal.corp.example.com"))
    }
    @Test fun `unmatched goes to tunnel`() {
        assertEquals(SplitVia.TUNNEL, matcher.classify("example.com"))
    }
    @Test fun `matching is case-insensitive and trims the trailing dot`() {
        assertEquals(SplitVia.BYPASS, matcher.classify("NAS.INTERNAL.CORP."))
    }
}
