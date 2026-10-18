package dev.vaayil.feature.profiles

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Keyboards for machine data (F16): keys, CIDRs, endpoints and URLs must reach the model exactly as
 * typed — autocorrect rewrote a key into a different valid key on device, and auto-capitalisation
 * turned "https://" into a scheme the validator rejects.
 */
internal object EditorKeyboards {
    /** Keys, CIDRs, IPs. */
    val machineText = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Ascii,
    )

    /** Endpoints, DoH/DoT endpoints, split-DNS domains. */
    val uri = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrectEnabled = false,
        keyboardType = KeyboardType.Uri,
    )

    /** MTU, listen port, persistent keepalive. */
    val number = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Number)
}
