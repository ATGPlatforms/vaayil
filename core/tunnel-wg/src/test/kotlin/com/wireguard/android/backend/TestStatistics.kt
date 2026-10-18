package com.wireguard.android.backend

import com.wireguard.crypto.KeyPair

/**
 * Test fixture for :core:tunnel-wg: builds a vendored [Statistics] whose constructor and `add()`
 * are package-private, which is why this file lives in the library's own package.
 */
object TestStatistics {
    fun withHandshake(handshakeEpochMillis: Long, rx: Long = 1_000, tx: Long = 2_000): Statistics {
        val s = Statistics()
        s.add(KeyPair().publicKey, rx, tx, handshakeEpochMillis)
        return s
    }

    fun empty(): Statistics = withHandshake(0)
}
