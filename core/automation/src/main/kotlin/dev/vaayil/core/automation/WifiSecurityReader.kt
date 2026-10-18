package dev.vaayil.core.automation

import android.net.wifi.WifiInfo
import androidx.annotation.RequiresApi
import dev.vaayil.core.model.WifiSecurity

/**
 * S4, API 31+: the connected network's [WifiInfo.getCurrentSecurityType], classified. OPEN covers no
 * authentication, OWE (Enhanced Open), WEP and OSEN (only the server authenticates). Every type that
 * authenticates the client (a password, enterprise sign-in, Passpoint, DPP) is SECURED.
 * SECURITY_TYPE_UNKNOWN and any type newer than this list are UNKNOWN. Only SECURED can be trusted.
 */
@RequiresApi(31)
internal fun securityOfType(type: Int): WifiSecurity = when (type) {
    WifiInfo.SECURITY_TYPE_OPEN,
    WifiInfo.SECURITY_TYPE_OWE,
    WifiInfo.SECURITY_TYPE_WEP,
    WifiInfo.SECURITY_TYPE_OSEN,
    -> WifiSecurity.OPEN
    WifiInfo.SECURITY_TYPE_PSK,
    WifiInfo.SECURITY_TYPE_EAP,
    WifiInfo.SECURITY_TYPE_SAE,
    WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT,
    WifiInfo.SECURITY_TYPE_WAPI_PSK,
    WifiInfo.SECURITY_TYPE_WAPI_CERT,
    WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
    WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2,
    WifiInfo.SECURITY_TYPE_PASSPOINT_R3,
    WifiInfo.SECURITY_TYPE_DPP,
    -> WifiSecurity.SECURED
    else -> WifiSecurity.UNKNOWN
}

/** The BSSID Android reports when location access is missing; it names no access point. */
private const val REDACTED_BSSID = "02:00:00:00:00:00"

/** True when [bssid] names a real access point: not blank, not the redacted placeholder. */
private fun namesAccessPoint(bssid: String): Boolean =
    bssid.isNotBlank() && !bssid.equals(REDACTED_BSSID, ignoreCase = true)

/**
 * S4, below API 31: the last definite reading (SECURED or OPEN) of one BSSID. It stands in only for an UNKNOWN read of
 * that same BSSID (a gap in the scan list); any definite read replaces it at once, and a different BSSID never inherits
 * it. The watcher clears it when the network is lost or it re-registers.
 */
internal class BssidSecurityMemory {
    private var bssid: String? = null
    private var security = WifiSecurity.UNKNOWN

    /** What to report for [connectedBssid] when the scan list says [reading]. */
    fun resolve(connectedBssid: String?, reading: WifiSecurity): WifiSecurity {
        if (reading != WifiSecurity.UNKNOWN) {
            bssid = connectedBssid?.takeIf(::namesAccessPoint)
            security = reading
            return reading
        }
        val last = bssid
        return if (last != null && last.equals(connectedBssid, ignoreCase = true)) security else WifiSecurity.UNKNOWN
    }

    fun clear() {
        bssid = null
        security = WifiSecurity.UNKNOWN
    }
}

/**
 * S4, below API 31: the capabilities of the scan result whose BSSID is the connected one ([scans] holds BSSID to
 * capabilities). No BSSID, the redacted one, or no matching scan result is UNKNOWN.
 */
internal fun securityOfScan(connectedBssid: String?, scans: List<Pair<String?, String?>>): WifiSecurity {
    if (connectedBssid == null || !namesAccessPoint(connectedBssid)) {
        return WifiSecurity.UNKNOWN
    }
    val capabilities = scans.firstOrNull { (bssid, _) -> connectedBssid.equals(bssid, ignoreCase = true) }?.second
        ?: return WifiSecurity.UNKNOWN
    return securityOfCapabilities(capabilities)
}

/**
 * A scan result's capabilities string, such as "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]". WEP, OWE and OSEN mark networks
 * without client authentication (OPEN); PSK, SAE, EAP, WAPI, FILS and DPP authenticate the client (SECURED); a
 * string with neither, such as "[ESS]", is an open network.
 */
internal fun securityOfCapabilities(capabilities: String): WifiSecurity {
    val caps = capabilities.uppercase()
    return when {
        OPEN_MARKERS.any { it in caps } -> WifiSecurity.OPEN
        SECURED_MARKERS.any { it in caps } -> WifiSecurity.SECURED
        else -> WifiSecurity.OPEN
    }
}

private val OPEN_MARKERS = listOf("WEP", "OWE", "OSEN")
private val SECURED_MARKERS = listOf("PSK", "SAE", "EAP", "WAPI", "FILS", "DPP")
