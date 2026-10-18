package dev.vaayil.core.automation

import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.SsidDecision
import dev.vaayil.core.model.WifiSecurity
import dev.vaayil.core.model.decide
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.isLive

/**
 * The SSID rules see a tunnel that is still connecting as busy, exactly like the mobile rule:
 * a trusted network joined mid-connect must still disconnect, and an untrusted one must not
 * connect a second time on top of the connect in flight.
 */
internal fun AutoConnectRules.decideForPhase(ssid: String?, security: WifiSecurity, phase: ConnectionPhase): SsidDecision =
    decide(ssid, security, tunnelConnected = phase.isLive)

/**
 * Evaluate-on-change gate for the Wi-Fi rules: a delivery passes only when the name the rules see, or its
 * security, differs from the last one that passed (S4: a security reading that changes is evaluated again).
 * [forget] lets the next delivery pass whatever it is (M5).
 */
internal class WifiChangeGate {
    private var last: Pair<String?, WifiSecurity>? = null

    fun changed(ssid: String?, security: WifiSecurity): Boolean {
        val now = ssid to security
        if (now == last) return false
        last = now
        return true
    }

    fun forget() {
        last = null
    }
}
