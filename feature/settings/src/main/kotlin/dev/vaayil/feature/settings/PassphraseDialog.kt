package dev.vaayil.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vaayil.core.ui.ScrimDialog
import dev.vaayil.core.ui.SecretTextField
import dev.vaayil.core.ui.SecureWindow
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumQuietAction

const val MIN_PASSPHRASE_LENGTH = 8

enum class PassphraseDialogMode { CREATE, UNLOCK }

/**
 * Passphrase entry for sealed snapshots. CREATE asks twice and enforces the minimum length;
 * UNLOCK asks once and shows [error] (a wrong passphrase keeps the dialog open). The Vellum
 * dialog: a lifted sheet over the token scrim ([ScrimDialog]), fields on [SecretTextField]
 * so focus lifts the well out of the sheet.
 */
@Composable
fun PassphraseDialog(
    mode: PassphraseDialogMode,
    error: String?,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val tooShort = passphrase.length < MIN_PASSPHRASE_LENGTH
    val mismatch = mode == PassphraseDialogMode.CREATE && confirm != passphrase
    val canConfirm = !tooShort && !mismatch
    val hint = when {
        error != null -> error
        passphrase.isNotEmpty() && tooShort -> "At least $MIN_PASSPHRASE_LENGTH characters."
        confirm.isNotEmpty() && mismatch -> "Passphrases don't match."
        else -> null
    }
    ScrimDialog(onDismissRequest = onDismiss) {
        SecureWindow()
        Text(
            if (mode == PassphraseDialogMode.CREATE) "Protect this snapshot" else "Unlock snapshot",
            style = MaterialTheme.typography.headlineLarge,
            color = VellumColors.OnSurface,
        )
        Text(
            if (mode == PassphraseDialogMode.CREATE) {
                "Choose a passphrase of at least $MIN_PASSPHRASE_LENGTH characters. Vaayil cannot recover it."
            } else {
                "Enter the passphrase this snapshot was exported with."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = VellumColors.OnSurfaceVariant,
        )
        SecretTextField("Passphrase", passphrase, { passphrase = it }, visible, { visible = !visible })
        if (mode == PassphraseDialogMode.CREATE) {
            SecretTextField("Confirm passphrase", confirm, { confirm = it }, visible, { visible = !visible })
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = VellumColors.M3Error)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VellumQuietAction("Cancel", onDismiss)
            Spacer(Modifier.width(8.dp))
            VellumButton(
                if (mode == PassphraseDialogMode.CREATE) "Export" else "Unlock",
                onClick = { onConfirm(passphrase.toCharArray()) },
                enabled = canConfirm,
            )
        }
    }
}
