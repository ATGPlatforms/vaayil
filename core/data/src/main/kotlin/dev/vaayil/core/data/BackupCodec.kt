package dev.vaayil.core.data

import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelSelections
import dev.vaayil.core.model.bracketIpv6Endpoint
import dev.vaayil.core.model.isIpLiteral
import dev.vaayil.core.model.normalizeProfileName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object BackupCodec {
    const val VERSION = 4
    const val MAX_PROFILES = 200

    @Serializable
    data class Envelope(
        val version: Int,
        val profiles: List<BackupProfile>,
        // Global rules ride at the top level since v3; v1/v2 kept them per profile. Those
        // fields are ignored on decode (ignoreUnknownKeys), except that their presence is
        // reported as BackupContent.legacyRulesDropped (K6).
        val splitTunnel: BackupSplitTunnel? = null,
    )

    /**
     * v4 carries both mode sets ([excluded]/[included]). `packages` is the v3-and-earlier
     * field — a single set tied to [mode]; kept defaulted so old envelopes still decode.
     */
    @Serializable
    data class BackupSplitTunnel(
        val mode: String,
        val packages: Set<String> = emptySet(),
        val excluded: Set<String> = emptySet(),
        val included: Set<String> = emptySet(),
    )

    @Serializable
    data class BackupProfile(
        val id: String, val name: String, val privateKey: String,
        val addresses: List<String>, val dnsServers: List<String>,
        val mtu: Int? = null, val listenPort: Int? = null,
        val peerPublicKey: String, val presharedKey: String? = null,
        val endpoint: String, val allowedIps: List<String>,
        val keepalive: Int? = null,
        val dnsMode: String = "TUNNEL",
        val dnsSecureType: String? = null,
        val dnsSecureEndpoint: String? = null,
        val dnsRules: List<DnsSplitRule> = emptyList(),
    )

    /**
     * Decode result; [splitTunnel] is null for v1/v2 snapshots (device rules stay untouched).
     * [legacyRulesDropped]: a v1/v2 snapshot carried per-profile split-tunnel rules, which this
     * build cannot restore; the restore says so (K6). Always false from v3 on.
     */
    data class BackupContent(
        val profiles: List<Profile>,
        val splitTunnel: SplitTunnelSelections?,
        val legacyRulesDropped: Boolean = false,
    )

    /** Reads only what v1/v2 kept per profile, to report it; never restored. */
    @Serializable
    private data class LegacyEnvelope(val profiles: List<LegacyProfile> = emptyList())

    @Serializable
    private data class LegacyProfile(val splitPackages: Set<String> = emptySet())

    class PassphraseRequired : IllegalStateException("This snapshot is encrypted; a passphrase is required.")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(profiles: List<Profile>, selections: SplitTunnelSelections): String {
        val envelope = Envelope(
            VERSION,
            profiles.map {
                BackupProfile(
                    it.id, it.name, it.interfaceConfig.privateKey,
                    it.interfaceConfig.addresses, it.interfaceConfig.dnsServers,
                    it.interfaceConfig.mtu, it.interfaceConfig.listenPort,
                    it.peer.publicKey, it.peer.presharedKey,
                    it.peer.endpoint, it.peer.allowedIps,
                    it.peer.persistentKeepalive,
                    it.dns.mode.name, it.dns.secureType?.name,
                    it.dns.secureEndpoint, it.dns.rules,
                )
            },
            BackupSplitTunnel(
                mode = selections.mode.name,
                excluded = selections.excluded,
                included = selections.included,
            ),
        )
        return json.encodeToString(envelope)
    }

    /** True when [text] is a v5 sealed envelope rather than a plain snapshot. */
    fun isSealed(text: String): Boolean = BackupCrypto.isSealed(text)

    /** Seals [encode]'s output behind [passphrase] (format v5). */
    fun encodeSealed(
        profiles: List<Profile>,
        selections: SplitTunnelSelections,
        passphrase: CharArray,
    ): String = BackupCrypto.seal(encode(profiles, selections), passphrase)

    fun decode(text: String, passphrase: CharArray? = null): BackupContent {
        val sealed = BackupCrypto.parseSealed(text) // one parse: the view model no longer pre-checks
        val plain = if (sealed != null) BackupCrypto.open(sealed, passphrase ?: throw PassphraseRequired()) else text
        val envelope = json.decodeFromString<Envelope>(plain)
        check(envelope.version in 1..VERSION) {
            "Unsupported backup version: ${envelope.version} (this build reads 1..$VERSION)"
        }
        check(envelope.profiles.size <= MAX_PROFILES) { "Snapshot has more than $MAX_PROFILES profiles." }
        // Reported, never restored: a field that doesn't parse must not refuse the file, so it reads as "not flagged".
        val legacyRulesDropped = envelope.version <= 2 && runCatching {
            json.decodeFromString<LegacyEnvelope>(plain).profiles.any { it.splitPackages.isNotEmpty() }
        }.getOrDefault(false)
        // Snapshots written before the current validation rules may carry hostname DNS entries
        // (search domains), names over 48 characters, a blank preshared key or an unbracketed IPv6
        // endpoint: normalise them the way the .conf parser does instead of refusing the whole file
        // (the endpoint only when its port is unambiguous, R7).
        val profiles = envelope.profiles.map {
            Profile(
                id = it.id, name = normalizeProfileName(it.name),
                interfaceConfig = InterfaceConfig(
                    it.privateKey, it.addresses, it.dnsServers.filter(::isIpLiteral), it.mtu, it.listenPort),
                peer = PeerConfig(
                    it.peerPublicKey, it.presharedKey?.takeIf { p -> p.isNotBlank() }, bracketIpv6Endpoint(it.endpoint),
                    it.allowedIps, it.keepalive),
                dns = DnsPolicy(
                    mode = DnsMode.valueOf(it.dnsMode),
                    secureType = it.dnsSecureType?.let { t -> SecureDnsType.valueOf(t) },
                    secureEndpoint = it.dnsSecureEndpoint,
                    rules = it.dnsRules,
                ),
            )
        }
        val splitTunnel = envelope.splitTunnel?.let { st ->
            val mode = SplitTunnelMode.valueOf(st.mode)
            if (envelope.version >= 4) {
                SplitTunnelSelections(mode, st.excluded, st.included)
            } else {
                // v3 and earlier stored one set, tied to the active mode only.
                if (mode == SplitTunnelMode.EXCLUDE) {
                    SplitTunnelSelections(mode, excluded = st.packages)
                } else {
                    SplitTunnelSelections(mode, included = st.packages)
                }
            }
        }
        return BackupContent(profiles, splitTunnel, legacyRulesDropped)
    }
}
