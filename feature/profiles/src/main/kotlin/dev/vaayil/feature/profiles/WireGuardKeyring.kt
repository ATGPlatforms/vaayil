package dev.vaayil.feature.profiles

import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

/**
 * Curve25519 key generation for manually-entered profiles (mockup: "Generate keypair").
 * Uses wireguard-android's pure-Java crypto package — no engine access (the control-plane
 * rule is untouched: this creates material, it never talks to tunnels).
 */
object WireGuardKeyring {
    fun generatePrivateKey(): String = KeyPair().privateKey.toBase64()

    /** Derived public key, or null when the private key isn't valid base64 Curve25519. */
    fun publicKeyFor(privateKeyBase64: String): String? = runCatching {
        KeyPair(Key.fromBase64(privateKeyBase64.trim())).publicKey.toBase64()
    }.getOrNull()
}
