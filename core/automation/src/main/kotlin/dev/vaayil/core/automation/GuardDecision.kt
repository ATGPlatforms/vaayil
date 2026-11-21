package dev.vaayil.core.automation

import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.SsidDecision
import dev.vaayil.core.model.WifiSecurity
import dev.vaayil.core.model.decide
import dev.vaayil.core.model.mobileDecision
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

/**
 * AUTO-2/J3: the Wi-Fi answer for a reported network, shaped exactly like [evaluateWifi]'s: the
 * disconnect is a POLICY decision, not a phase read — a visible, secured, trusted SSID with
 * disconnect-on-trusted on must also supersede a crash resume that just came up (or is coming
 * up) while the registry still reads idle. Otherwise the phase decides, as before: on a live
 * tunnel the same rule disconnects, and untrusted networks connect or stay put exactly as
 * [decideForPhase] always answered.
 */
internal fun AutoConnectRules.wifiAnswer(ssid: String?, security: WifiSecurity, phase: ConnectionPhase): SsidDecision =
    if (decide(ssid, security, tunnelConnected = true) == SsidDecision.DISCONNECT) {
        SsidDecision.DISCONNECT
    } else {
        decideForPhase(ssid, security, phase)
    }

/**
 * AUTO-2 (spec §4.6): what a rule edit does to the current Wi-Fi network. Nothing when the edit
 * only turns the guard on or off (Y3 owns that transition), the new answer when it differs from
 * the previous one, and nothing else — so an edit that leaves the answer alone leaves the user's
 * own Connect or Disconnect standing.
 */
internal fun wifiRuleChange(
    previous: AutoConnectRules,
    current: AutoConnectRules,
    ssid: String?,
    security: WifiSecurity,
    phase: ConnectionPhase,
): SsidDecision {
    if (previous.wifiGuardEnabled != current.wifiGuardEnabled) return SsidDecision.NONE
    val after = current.wifiAnswer(ssid, security, phase)
    return if (after != previous.wifiAnswer(ssid, security, phase)) after else SsidDecision.NONE
}

/** AUTO-2 (spec §4.6): CONNECT only when the new mobile answer is CONNECT and the previous one was not. */
internal fun mobileRuleChange(
    previous: AutoConnectRules,
    current: AutoConnectRules,
    mobile: Boolean,
    busy: Boolean,
): SsidDecision =
    if (current.mobileDecision(mobile, busy) == SsidDecision.CONNECT &&
        previous.mobileDecision(mobile, busy) != SsidDecision.CONNECT
    ) {
        SsidDecision.CONNECT
    } else {
        SsidDecision.NONE
    }
