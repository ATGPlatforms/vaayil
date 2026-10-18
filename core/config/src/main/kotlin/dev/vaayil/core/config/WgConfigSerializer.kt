package dev.vaayil.core.config

object WgConfigSerializer {
    class UnsafeValueException(field: String) : IllegalArgumentException("$field contains a line break")

    fun serialize(config: WgQuickConfig): String {
        refuseLineBreaks(
            "PrivateKey" to config.interfaceSection.privateKey,
            "Address" to config.interfaceSection.addresses.joinToString(","),
            "DNS" to config.interfaceSection.dnsServers.joinToString(","),
            "PublicKey" to config.peer.publicKey,
            "PresharedKey" to config.peer.presharedKey.orEmpty(),
            "AllowedIPs" to config.peer.allowedIps.joinToString(","),
            "Endpoint" to config.peer.endpoint,
        )
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${config.interfaceSection.privateKey}")
            if (config.interfaceSection.addresses.isNotEmpty()) appendLine("Address = ${config.interfaceSection.addresses.joinToString(", ")}")
            if (config.interfaceSection.dnsServers.isNotEmpty()) appendLine("DNS = ${config.interfaceSection.dnsServers.joinToString(", ")}")
            config.interfaceSection.mtu?.let { appendLine("MTU = $it") }
            config.interfaceSection.listenPort?.let { appendLine("ListenPort = $it") }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${config.peer.publicKey}")
            config.peer.presharedKey?.let { appendLine("PresharedKey = $it") }
            appendLine("AllowedIPs = ${config.peer.allowedIps.joinToString(", ")}")
            appendLine("Endpoint = ${config.peer.endpoint}")
            config.peer.persistentKeepalive?.let { appendLine("PersistentKeepalive = $it") }
        }.trimEnd() + "\n"
    }

    /**
     * Every emitted field must stay one line: a value carrying a line break would let a
     * crafted profile or backup append directives (PostUp/PreUp) to an exported .conf.
     */
    private fun refuseLineBreaks(vararg fields: Pair<String, String>) {
        fields.forEach { (name, value) ->
            if (value.any { it == '\n' || it == '\r' }) throw UnsafeValueException(name)
        }
    }
}
