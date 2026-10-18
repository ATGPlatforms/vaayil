package dev.vaayil.core.config

import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile

data class WgInterfaceSection(
    val privateKey: String,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val mtu: Int?,
    val listenPort: Int?,
)

data class WgPeerSection(
    val publicKey: String,
    val presharedKey: String?,
    val endpoint: String,
    val allowedIps: List<String>,
    val persistentKeepalive: Int?,
)

data class WgQuickConfig(
    val interfaceSection: WgInterfaceSection,
    val peer: WgPeerSection,
    /** Non-IP `DNS =` entries (wg-quick search domains) Vaayil does not support, in file order (F19). */
    val ignoredDnsEntries: List<String> = emptyList(),
)

fun WgQuickConfig.toProfile(
    name: String,
): Profile = Profile(
    id = java.util.UUID.randomUUID().toString(),
    name = name,
    interfaceConfig = InterfaceConfig(
        privateKey = interfaceSection.privateKey,
        addresses = interfaceSection.addresses,
        dnsServers = interfaceSection.dnsServers,
        mtu = interfaceSection.mtu,
        listenPort = interfaceSection.listenPort,
    ),
    peer = PeerConfig(
        publicKey = peer.publicKey,
        presharedKey = peer.presharedKey,
        endpoint = peer.endpoint,
        allowedIps = peer.allowedIps,
        persistentKeepalive = peer.persistentKeepalive,
    ),
)

fun Profile.toWgQuickConfig() = WgQuickConfig(
    interfaceSection = WgInterfaceSection(
        privateKey = interfaceConfig.privateKey,
        addresses = interfaceConfig.addresses,
        dnsServers = interfaceConfig.dnsServers,
        mtu = interfaceConfig.mtu,
        listenPort = interfaceConfig.listenPort,
    ),
    peer = WgPeerSection(
        publicKey = peer.publicKey,
        presharedKey = peer.presharedKey,
        endpoint = peer.endpoint,
        allowedIps = peer.allowedIps,
        persistentKeepalive = peer.persistentKeepalive,
    ),
)
