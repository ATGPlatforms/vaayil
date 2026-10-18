package dev.vaayil.feature.profiles

import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The prefix→label table and its display-order output (F14, ruling R3): the Save blocker
 * line must name the fields the editor shows, in the order the editor shows them, with
 * validation itself untouched in :core:model.
 */
class SaveBlockersTest {

    private fun profile(dns: DnsPolicy = DnsPolicy()) = Profile(
        id = "p1",
        name = "Test",
        interfaceConfig = InterfaceConfig("k", listOf("10.5.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("pub", null, "host:51820", listOf("0.0.0.0/0"), null),
        dns = dns,
    )

    @Test
    fun `a blank profile maps to the display-order labels`() {
        val blockers = saveBlockers(NewProfileFactory.blank().validate(), emptyList())
        assertEquals(
            listOf("Profile name", "Private key", "Endpoint", "Server public key", "Allowed IPs"),
            blockers,
        )
    }

    @Test
    fun `an MTU message maps to MTU (Advanced)`() {
        assertEquals(
            listOf("MTU (Advanced)"),
            saveBlockers(listOf("MTU must be between 576 and 65535."), emptyList()),
        )
    }

    @Test
    fun `an unmapped message is returned verbatim`() {
        assertEquals(
            listOf("Something new."),
            saveBlockers(listOf("Something new."), emptyList()),
        )
    }

    @Test
    fun `labels follow display order regardless of emission order and deduplicate`() {
        val errors = listOf(
            "Peer public key must be 32 bytes base64-encoded (44 chars).", // → Server public key
            "Endpoint must be host:port (got \"\").",                       // → Endpoint
            "Secure DNS type must be DoH or DoT.",                          // → DNS
            "Split-DNS domain \"a b\" is not a valid hostname.",            // → DNS again: one label
        )
        assertEquals(
            listOf("Endpoint", "Server public key", "DNS"),
            saveBlockers(errors, emptyList()),
        )
    }

    @Test
    fun `numeric format-error labels map through the table`() {
        assertEquals(
            listOf("MTU (Advanced)", "Persistent keepalive (Advanced)"),
            saveBlockers(emptyList(), listOf("MTU", "Persistent keepalive")),
        )
    }

    @Test
    fun `an invalid draft is rejected with the model's message`() {
        assertEquals(
            "Split-DNS domain \"not a domain!\" is not a valid hostname.",
            splitDnsDraftError(profile(), "not a domain!"),
        )
    }

    @Test
    fun `a duplicate draft is rejected regardless of case`() {
        val withRule = profile(dns = DnsPolicy(rules = listOf(DnsSplitRule("corp.example", bypass = true))))
        assertEquals(
            "Duplicate split-DNS domain \"CORP.example\".",
            splitDnsDraftError(withRule, "CORP.example"),
        )
    }

    @Test
    fun `a valid draft is accepted`() {
        assertNull(splitDnsDraftError(profile(), "internal.corp"))
    }

    /** Copy of ProfileValidationTest's validProfile(): this file may not import the :core:model test. */
    private fun validProfile() = Profile(
        id = "p1", name = "Home",
        interfaceConfig = InterfaceConfig(
            privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
            mtu = 1420, listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
            presharedKey = null, endpoint = "vpn.example.com:51820",
            allowedIps = listOf("0.0.0.0/0"), persistentKeepalive = 25,
        ),
    )

    /**
     * Pins every validate() message to the Save-blocker label the editor shows (F14). If a
     * reworded message stops matching its PREFIX_TO_LABEL prefix, the field's error line is
     * silently dropped and the raw sentence leaks after "To save, fix:". One defect per row,
     * so the label list is exactly one element.
     */
    @Test
    fun `every validate() message maps to the field that shows it`() {
        assertEquals(emptyList<String>(), validProfile().validate())
        val rows: List<Triple<String, (Profile) -> Profile, String>> = listOf(
            Triple("name blank", { it.copy(name = "") }, "Profile name"),
            Triple("name 49 characters", { it.copy(name = "x".repeat(49)) }, "Profile name"),
            Triple("name containing control character", { it.copy(name = "Ho\u0007me") }, "Profile name"),
            Triple("private key not base64", {
                it.copy(interfaceConfig = it.interfaceConfig.copy(privateKey = "x"))
            }, "Private key"),
            Triple("address not CIDR", {
                it.copy(interfaceConfig = it.interfaceConfig.copy(addresses = listOf("bad")))
            }, "Addresses"),
            Triple("address is the reserved resolver", {
                it.copy(interfaceConfig = it.interfaceConfig.copy(addresses = listOf("10.111.222.3/32")))
            }, "Addresses"),
            Triple("DNS server not an IP", {
                it.copy(interfaceConfig = it.interfaceConfig.copy(dnsServers = listOf("dns.example")))
            }, "DNS servers"),
            Triple("endpoint without host or port", {
                it.copy(peer = it.peer.copy(endpoint = "nohost"))
            }, "Endpoint"),
            Triple("endpoint port 0", {
                it.copy(peer = it.peer.copy(endpoint = "host:0"))
            }, "Endpoint"),
            Triple("peer public key not base64", {
                it.copy(peer = it.peer.copy(publicKey = "x"))
            }, "Server public key"),
            Triple("allowed IPs empty", {
                it.copy(peer = it.peer.copy(allowedIps = emptyList()))
            }, "Allowed IPs"),
            Triple("allowed IPs not CIDR", {
                it.copy(peer = it.peer.copy(allowedIps = listOf("bad")))
            }, "Allowed IPs"),
            Triple("secure DNS without a type", {
                it.copy(
                    dns = DnsPolicy(
                        mode = DnsMode.SECURE,
                        secureType = null,
                        secureEndpoint = "https://dns.example/dns-query",
                    ),
                )
            }, "DNS"),
            Triple("secure DNS DoH with blank endpoint", {
                it.copy(dns = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOH, secureEndpoint = ""))
            }, "DNS"),
            Triple("secure DNS DoH with plain-http endpoint", {
                it.copy(
                    dns = DnsPolicy(
                        mode = DnsMode.SECURE,
                        secureType = SecureDnsType.DOH,
                        secureEndpoint = "http://dns.example/dns-query",
                    ),
                )
            }, "DNS"),
            Triple("secure DNS DoT with malformed endpoint", {
                it.copy(
                    dns = DnsPolicy(mode = DnsMode.SECURE, secureType = SecureDnsType.DOT, secureEndpoint = "a:b:c"),
                )
            }, "DNS"),
            Triple("split-DNS rule not a hostname", {
                it.copy(dns = DnsPolicy(rules = listOf(DnsSplitRule("bad_domain!", bypass = true))))
            }, "DNS"),
            Triple("split-DNS rules duplicated case-insensitively", {
                it.copy(
                    dns = DnsPolicy(
                        rules = listOf(DnsSplitRule("a.example", bypass = true), DnsSplitRule("A.example", bypass = false)),
                    ),
                )
            }, "DNS"),
            Triple("MTU below the floor", {
                it.copy(interfaceConfig = it.interfaceConfig.copy(mtu = 100))
            }, "MTU (Advanced)"),
            Triple("listen port 0", {
                it.copy(interfaceConfig = it.interfaceConfig.copy(listenPort = 0))
            }, "Listen port (Advanced)"),
            Triple("preshared key not base64", {
                it.copy(peer = it.peer.copy(presharedKey = "x"))
            }, "Preshared key (Advanced)"),
            Triple("persistent keepalive above the ceiling", {
                it.copy(peer = it.peer.copy(persistentKeepalive = 70000))
            }, "Persistent keepalive (Advanced)"),
        )
        for ((row, defect, label) in rows) {
            val p = defect(validProfile())
            assertEquals(row, listOf(label), saveBlockers(p.validate(), emptyList()))
        }
    }
}
