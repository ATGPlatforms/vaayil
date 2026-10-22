package dev.vaayil.feature.profiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vaayil.core.data.DocumentRead
import dev.vaayil.core.data.readDocumentBaseName
import dev.vaayil.core.data.readDocumentText
import dev.vaayil.core.ui.Films
import dev.vaayil.core.ui.SecureWindow
import dev.vaayil.core.ui.Sheet
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumButtonRole
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumField
import dev.vaayil.core.ui.vellumSeam
import kotlinx.coroutines.launch

/**
 * The three entry doors to a profile (mockup), Vellum dressed: open a .conf file, scan a
 * QR code, or type the details in (paste lives with the file card — both feed the same
 * parser).
 */
@Composable
fun ImportScreen(
    vm: ImportViewModel,
    onImported: (String) -> Unit,
    onOpenManual: () -> Unit,
    onScanQr: () -> Unit = {},
) {
    SecureWindow() // the pasted config carries the private key: blank screenshots/recents (S5)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val runImport: (String, String) -> Unit = { text, name ->
        vm.importText(text, name) { outcome ->
            when (outcome) {
                is ImportOutcome.Success -> {
                    error = null
                    if (name == "Pasted") pasted = ""
                    onImported(outcome.profile.id)
                }
                is ImportOutcome.Failure ->
                    error = outcome.message ?: ImportMessages.NOT_A_CONFIG
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val read = readDocumentText(context.contentResolver, uri)) {
                    is DocumentRead.Text -> runImport(read.text, readDocumentBaseName(context.contentResolver, uri))
                    DocumentRead.TooLarge -> error = ImportMessages.TOO_LARGE
                    DocumentRead.Unreadable -> error = ImportMessages.UNREADABLE
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add a Profile", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Vaayil works with the tunnels you already have. Pick how you'd like to bring yours in.",
            style = MaterialTheme.typography.bodyMedium,
            color = VellumColors.OnSurfaceVariant,
        )
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        error?.let { ErrorBanner(it) }

        OptionCard(
            icon = Icons.Rounded.FileOpen,
            title = "Import a .conf file",
            body = "Open a wg-quick configuration from storage, or paste its text below.",
            actionLabel = "Choose file",
            onAction = { filePicker.launch(arrayOf("application/octet-stream", "text/*")) },
            enabled = !busy,
        ) {
            VellumField(
                value = pasted,
                onValueChange = { pasted = it },
                label = "Paste configuration",
                singleLine = false,
                minLines = 4,
                // Private keys ride through this box: autocorrect or capitals would
                // corrupt them. No imeAction, so Enter still inserts a newline.
                keyboardOptions = EditorKeyboards.machineText,
            )
            VellumButton(
                label = "Import pasted config",
                enabled = !busy && pasted.isNotBlank(),
                role = VellumButtonRole.SECONDARY,
                onClick = { runImport(pasted, "Pasted") },
            )
        }

        OptionCard(
            icon = Icons.Rounded.QrCodeScanner,
            title = "Scan a QR code",
            body = "Point the camera at the QR your provider or server shows you.",
            actionLabel = "Open camera",
            onAction = onScanQr,
            enabled = !busy,
        )

        OptionCard(
            icon = Icons.Rounded.Edit,
            title = "Type in the details",
            body = "Build the profile by hand — endpoint, keys, addresses, DNS.",
            actionLabel = "Open editor",
            onAction = onOpenManual,
            enabled = !busy,
        )
    }
}

/** Import failure (S5 input path): error surface with the 1dp error-mark seam. */
@Composable
private fun ErrorBanner(message: String) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VellumColors.Error, shape)
            .vellumSeam(shape, VellumColors.ErrorMark)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = VellumColors.OnError)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = VellumColors.OnError)
    }
}

@Composable
private fun OptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
    extra: @Composable () -> Unit = {},
) {
    Sheet(films = Films.One, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = VellumColors.Primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = VellumColors.OnSurfaceVariant,
                )
            }
        }
        extra()
        VellumButton(label = actionLabel, onClick = onAction, enabled = enabled)
    }
}
