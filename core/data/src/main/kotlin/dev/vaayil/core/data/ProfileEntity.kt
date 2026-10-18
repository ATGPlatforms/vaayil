package dev.vaayil.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val privateKey: String,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val mtu: Int?,
    val listenPort: Int?,
    val peerPublicKey: String,
    val presharedKey: String?,
    val endpoint: String,
    val allowedIps: List<String>,
    val keepalive: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val dnsMode: String = "TUNNEL",
    val dnsSecureType: String? = null,
    val dnsSecureEndpoint: String? = null,
    val dnsRules: List<DnsSplitRule> = emptyList(),
)

fun ProfileEntity.toModel() = Profile(
    id = id, name = name,
    interfaceConfig = InterfaceConfig(privateKey, addresses, dnsServers, mtu, listenPort),
    peer = PeerConfig(peerPublicKey, presharedKey, endpoint, allowedIps, keepalive),
    dns = DnsPolicy(
        mode = DnsMode.valueOf(dnsMode),
        secureType = dnsSecureType?.let { SecureDnsType.valueOf(it) },
        secureEndpoint = dnsSecureEndpoint,
        rules = dnsRules,
    ),
)

fun Profile.toEntity(previous: ProfileEntity? = null) = ProfileEntity(
    id = id, name = name,
    privateKey = interfaceConfig.privateKey,
    addresses = interfaceConfig.addresses,
    dnsServers = interfaceConfig.dnsServers,
    mtu = interfaceConfig.mtu,
    listenPort = interfaceConfig.listenPort,
    peerPublicKey = peer.publicKey,
    presharedKey = peer.presharedKey,
    endpoint = peer.endpoint,
    allowedIps = peer.allowedIps,
    keepalive = peer.persistentKeepalive,
    createdAt = previous?.createdAt ?: System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis(),
    dnsMode = dns.mode.name,
    dnsSecureType = dns.secureType?.name,
    dnsSecureEndpoint = dns.secureEndpoint,
    dnsRules = dns.rules,
)
