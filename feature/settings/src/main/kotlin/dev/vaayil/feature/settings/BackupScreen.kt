package dev.vaayil.feature.settings

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.vaayil.core.data.DocumentRead
import dev.vaayil.core.data.readDocumentText
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.ui.Films
import dev.vaayil.core.ui.ScrimDialog
import dev.vaayil.core.ui.Sheet
import dev.vaayil.core.ui.TabHeader
import dev.vaayil.core.ui.VellumButton
import dev.vaayil.core.ui.VellumButtonRole
import dev.vaayil.core.ui.VellumColors
import dev.vaayil.core.ui.VellumEyebrow
import dev.vaayil.core.ui.VellumQuietAction
import dev.vaayil.core.ui.monoTextStyle
import dev.vaayil.core.ui.vellumQuietUnderline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline Storage Fallback (the mockup's Drive screen, honest offline edition): export a
 * .vylconf snapshot file you own — sealed with a passphrase you choose — and restore from one,
 * sealed or legacy plain. Copy keeps to what the codec really does — no cloud claims.
 */
@Composable
fun BackupScreen(
    vm: BackupViewModel,
    onRestored: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) {
            vm.cancelPassphrase()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val payload = vm.buildSnapshot()
            if (payload == null) {
                // The staged passphrase is gone (process death behind the picker): SAF has already
                // created the document, so remove the empty file and say so.
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                vm.reportExport(false)
                return@launch
            }
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(payload.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            vm.reportExport(written)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val read = readDocumentText(context.contentResolver, uri)) {
                    is DocumentRead.Text -> vm.restore(read.text) { onRestored() }
                    DocumentRead.TooLarge -> vm.reportTooLarge()
                    DocumentRead.Unreadable -> vm.reportReadFailure()
                }
            }
        }
    }

    // Shared TabHeader (device review 2026-09-21, X1) leads the scroll; the cards
    // keep their own 16dp rhythm. The header bakes its own 16dp content gap, so it
    // lives outside the spacedBy column rather than doubling it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        TabHeader(
            title = "Backup & Restore",
            intro = "Your WireGuard tunnels and split-tunnel policies, kept in a snapshot file you own.",
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Sheet(films = Films.One, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Rounded.EnhancedEncryption,
                        contentDescription = null,
                        tint = VellumColors.OnSurfaceVariant,
                    )
                    Column {
                        Text(
                            "${state.profileCount} profile${if (state.profileCount == 1) "" else "s"} on this device",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Snapshots are encrypted with a passphrase you choose. They carry interface keys, " +
                                "peer settings, DNS policy and per-app routing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VellumColors.OnSurfaceVariant,
                        )
                    }
                }
            }

            BackupAction(
                icon = Icons.Rounded.SaveAlt,
                title = "Export snapshot",
                body = "Write your profiles to an encrypted .vylconf file in your storage.",
                buttonLabel = "Export file",
                enabled = !state.busy && state.profileCount > 0,
                onAction = vm::beginExport,
            )
            BackupAction(
                icon = Icons.Rounded.FileOpen,
                title = "Restore from snapshot",
                body = "Profiles in the file are added or updated; nothing is deleted.",
                buttonLabel = "Import .vylconf",
                enabled = !state.busy,
                onAction = { importLauncher.launch(arrayOf("application/octet-stream", "text/*")) },
            )
            state.message?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = VellumColors.Primary,
                    )
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }

    when (state.passphrasePrompt) {
        PassphrasePrompt.EXPORT -> PassphraseDialog(
            mode = PassphraseDialogMode.CREATE,
            error = null,
            onConfirm = { pass ->
                vm.stageExportPassphrase(pass)
                exportLauncher.launch(vm.snapshotFileName())
            },
            onDismiss = vm::cancelPassphrase,
        )
        PassphrasePrompt.IMPORT -> PassphraseDialog(
            mode = PassphraseDialogMode.UNLOCK,
            error = state.passphraseError,
            onConfirm = vm::unlockImport,
            onDismiss = vm::cancelPassphrase,
        )
        null -> Unit
    }

    state.pendingRestore?.let { prompt ->
        ScrimDialog(onDismissRequest = vm::cancelRestore) {
            Text(
                "Restore this snapshot?",
                style = MaterialTheme.typography.headlineLarge,
                color = VellumColors.OnSurface,
            )
            Sheet(films = Films.Two, modifier = Modifier.fillMaxWidth()) {
                restoreSummaryRows(prompt).forEach { (label, value) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        VellumEyebrow(label)
                        Spacer(Modifier.weight(1f))
                        Text(
                            value,
                            style = monoTextStyle(),
                            color = VellumColors.OnSurface,
                            textAlign = TextAlign.End,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
            restoreNotes(prompt).forEach { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = VellumColors.OnSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VellumQuietAction("Cancel", vm::cancelRestore)
                Spacer(Modifier.width(8.dp))
                // Destructive, but never a red fill: the secondary construction with
                // the label in error-mark (spec §3.5 Backup).
                VellumButton(
                    "Restore",
                    vm::confirmRestore,
                    role = VellumButtonRole.SECONDARY,
                    labelColor = VellumColors.ErrorMark,
                )
            }
        }
    }
}

/**
 * An export/import action (spec §3.5 Backup; device review 2026-09-21, C10): a
 * clickable one-film sheet with NO stroke perimeter — [Sheet] passes
 * `boundary = false`, so the page keeps one edge style (the seam) and the sage
 * action label carries the control's identity (spec R5's text-label route). The
 * label sits under the body, right-aligned in the quiet construction (sage text
 * over the shared 1dp seam underline): as a trailing column it took ~95dp and
 * wrapped the "Restore from snapshot" title (review backup finding 2).
 */
@Composable
private fun BackupAction(
    icon: ImageVector,
    title: String,
    body: String,
    buttonLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Sheet(
        onClick = onAction,
        films = Films.One,
        enabled = enabled,
        boundary = false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = VellumColors.OnSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = VellumColors.OnSurface)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = VellumColors.OnSurfaceVariant,
                )
                Text(
                    buttonLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) VellumColors.Primary else VellumColors.Disabled,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp)
                        .vellumQuietUnderline(),
                )
            }
        }
    }
}

/** What the restore changes, as label/value ledger rows; the user confirms exactly this. */
internal fun restoreSummaryRows(p: RestorePrompt): List<Pair<String, String>> = buildList {
    val added = p.profiles - p.replacing // p.profiles counts every profile in the file, overwrites included (F8)
    if (added > 0) add("Added" to "$added profile${if (added == 1) "" else "s"}")
    if (p.replacing > 0) {
        add("Overwritten" to "${p.replacing} existing profile${if (p.replacing == 1) "" else "s"} with the same id")
    }
    p.rules?.let { rules ->
        val n = rules.active.packages.size
        val mode = if (rules.mode == SplitTunnelMode.EXCLUDE) "Exclusive" else "Inclusive"
        add("Split tunnel" to "Replaced by the snapshot's: $mode, $n app${if (n == 1) "" else "s"}")
    }
}

/** Prose under the restore ledger: what this restore can't carry over (K6). */
internal fun restoreNotes(p: RestorePrompt): List<String> =
    if (p.legacyRulesDropped) listOf(BackupViewModel.LEGACY_RULES_DROPPED) else emptyList()
