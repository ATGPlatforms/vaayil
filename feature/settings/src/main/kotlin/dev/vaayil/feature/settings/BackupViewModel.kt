package dev.vaayil.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vaayil.core.data.BackupCodec
import dev.vaayil.core.data.BackupCryptoException
import dev.vaayil.core.data.InvalidProfileException
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelSelections
import dev.vaayil.core.model.validate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PassphrasePrompt { EXPORT, IMPORT }

/**
 * What a restore is about to change; shown once before anything is written. [legacyRulesDropped]: the
 * snapshot is a v1/v2 one whose per-profile split-tunnel rules this build can't restore (K6).
 */
data class RestorePrompt(
    val profiles: Int,
    val replacing: Int,
    val rules: SplitTunnelSelections?,
    val legacyRulesDropped: Boolean = false,
)

data class BackupUiState(
    val profileCount: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
    val passphrasePrompt: PassphrasePrompt? = null,
    val passphraseError: String? = null,
    val pendingRestore: RestorePrompt? = null,
)

/**
 * Offline backup: export every profile as a passphrase-sealed .vylconf snapshot (format v5,
 * [BackupCodec.encodeSealed]) and restore from a sealed or legacy plain snapshot. Profiles in
 * the file merge by id in one transaction; a restore that would overwrite rows or replace the
 * split-tunnel rules asks first. Nothing on the device is deleted by a restore.
 */
class BackupViewModel(
    /** Readable profiles: what an export writes. Rows hidden by a transient key loss are
        absent here until the key returns (S-3) — an export in that window would be short. */
    private val profiles: Flow<List<Profile>>,
    /** Every stored row, readable or not: a row hidden by a transient key loss is still on
        the device, and "N profiles on this device" must not read as an empty device. */
    private val storedCount: Flow<Int>,
    /** Every stored row id, readable or not: collisions are counted against all of them. */
    private val existingIds: suspend () -> Set<String>,
    /** Writes the batch atomically (ProfileRepository.saveAll): all rows or none. */
    private val saveProfiles: suspend (List<Profile>) -> Unit,
    private val selections: Flow<SplitTunnelSelections>,
    private val saveRules: suspend (SplitTunnelSelections) -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state = _state.asStateFlow()

    private var stagedPassphrase: CharArray? = null      // export: set by the dialog, consumed once
    private var pendingImport: String? = null             // import: sealed text awaiting its passphrase
    private var pendingDone: ((Int) -> Unit)? = null
    private var pendingContent: BackupCodec.BackupContent? = null // restore awaiting confirmation
    /** (written, stored) for the export being written: captured by [buildSnapshot], consumed by [reportExport]. */
    private var exportCounts: Pair<Int, Int>? = null

    init {
        viewModelScope.launch {
            storedCount.collect { count -> _state.value = _state.value.copy(profileCount = count) }
        }
    }

    /** Export entry point: the passphrase dialog comes before the document picker. */
    fun beginExport() {
        exportCounts = null // a new export never inherits an earlier one's counts
        _state.value = _state.value.copy(passphrasePrompt = PassphrasePrompt.EXPORT, passphraseError = null)
    }

    /** The export dialog's confirm; the screen then launches CreateDocument. */
    fun stageExportPassphrase(passphrase: CharArray) {
        stagedPassphrase = passphrase
        _state.value = _state.value.copy(passphrasePrompt = null, passphraseError = null)
    }

    fun cancelPassphrase() {
        stagedPassphrase?.fill(' ')
        stagedPassphrase = null
        pendingImport = null
        pendingDone = null
        pendingContent = null
        exportCounts = null
        _state.value = _state.value.copy(passphrasePrompt = null, passphraseError = null, busy = false)
    }

    /** Sealed snapshot payload for the picked document, or null when no passphrase was staged. */
    suspend fun buildSnapshot(): String? {
        val passphrase = stagedPassphrase ?: return null
        _state.value = _state.value.copy(busy = true)
        try {
            val list = profiles.first()
            val rules = selections.first()
            val stored = storedCount.first()
            val payload = withContext(Dispatchers.Default) { BackupCodec.encodeSealed(list, rules, passphrase) }
            // The report describes THIS payload: the rows it carries against the stored total now.
            // The two flows can race (the count may lag the list), so the denominator never undershoots.
            exportCounts = list.size to maxOf(stored, list.size)
            return payload
        } finally {
            passphrase.fill(' ')
            stagedPassphrase = null
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun snapshotFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyy_MM_dd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "vaayil_backup_$stamp.vylconf"
    }

    /** Decodes once; a sealed file surfaces as the passphrase prompt from the codec itself. */
    fun restore(text: String, onDone: (Int) -> Unit) {
        if (_state.value.busy) return
        decodeAndApply(text, null, onDone)
    }

    /** The import dialog's confirm. A wrong passphrase keeps the dialog up with an error. */
    fun unlockImport(passphrase: CharArray) {
        val text = pendingImport ?: return
        decodeAndApply(text, passphrase, pendingDone ?: {})
    }

    private fun decodeAndApply(text: String, passphrase: CharArray?, onDone: (Int) -> Unit) {
        _state.value = _state.value.copy(busy = true, message = null, passphraseError = null)
        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.Default) { BackupCodec.decode(text, passphrase) }
                pendingImport = null
                pendingDone = null
                applyRestore(content, onDone)
            } catch (_: BackupCodec.PassphraseRequired) {
                pendingImport = text
                pendingDone = onDone
                _state.value = _state.value.copy(busy = false, passphrasePrompt = PassphrasePrompt.IMPORT, passphraseError = null)
            } catch (_: BackupCryptoException) {
                _state.value = _state.value.copy(busy = false, passphraseError = WRONG_PASSPHRASE)
            } catch (_: Exception) {
                pendingImport = null
                pendingDone = null
                _state.value = _state.value.copy(busy = false, passphrasePrompt = null, message = UNREADABLE_SNAPSHOT)
            } finally {
                passphrase?.fill(' ') // every exit path, including a wrong passphrase
            }
        }
    }

    private suspend fun applyRestore(content: BackupCodec.BackupContent, onDone: (Int) -> Unit) {
        // Whole-file validation before anything is written: a partially applied snapshot is
        // worse than none (S-2). Names only in the message, never field values.
        content.profiles.forEach { p ->
            p.validate().firstOrNull()?.let { reason ->
                _state.value = _state.value.copy(
                    busy = false, passphrasePrompt = null,
                    message = "Profile \"${p.name.ifBlank { "(unnamed)" }}\" in the snapshot is invalid: $reason",
                )
                return
            }
        }
        val existing = existingIds()
        val replacing = content.profiles.count { it.id in existing }
        if (replacing > 0 || content.splitTunnel != null) {
            pendingContent = content
            pendingDone = onDone
            _state.value = _state.value.copy(
                busy = false, passphrasePrompt = null,
                pendingRestore = RestorePrompt(
                    content.profiles.size, replacing, content.splitTunnel, content.legacyRulesDropped,
                ),
            )
            return
        }
        commitGuarded(content, onDone)
    }

    fun confirmRestore() {
        val content = pendingContent ?: return
        val done = pendingDone ?: {}
        pendingContent = null
        pendingDone = null
        _state.value = _state.value.copy(busy = true, pendingRestore = null)
        viewModelScope.launch { commitGuarded(content, done) }
    }

    fun cancelRestore() {
        pendingContent = null
        pendingDone = null
        _state.value = _state.value.copy(busy = false, pendingRestore = null)
    }

    /** Writes the batch; a refused row or a failed write lands as a message, and nothing partial is left behind. */
    private suspend fun commitGuarded(content: BackupCodec.BackupContent, onDone: (Int) -> Unit) {
        try {
            saveProfiles(content.profiles) // one transaction: all rows or none
        } catch (e: InvalidProfileException) {
            _state.value = _state.value.copy(busy = false, message = "A profile in the snapshot is invalid: ${e.errors.first()}")
            return
        } catch (_: Exception) {
            _state.value = _state.value.copy(busy = false, message = WRITE_FAILED)
            return
        }
        // Absent in v1/v2 snapshots: those predate global rules, so device rules stay.
        // Guarded separately from the profile batch: the profiles are already committed, so a
        // rules failure must be reported, not crash the restore or silently claim success.
        val rulesFailed = content.splitTunnel?.let { rules -> runCatching { saveRules(rules) }.isFailure } ?: false
        val rulesNote = when {
            rulesFailed -> " Split tunnel rules could not be updated."
            content.splitTunnel != null -> " Split tunnel rules restored."
            content.legacyRulesDropped -> " $LEGACY_RULES_DROPPED"
            else -> ""
        }
        _state.value = _state.value.copy(
            busy = false,
            passphrasePrompt = null,
            message = "Restored ${content.profiles.size} " +
                "profile${if (content.profiles.size == 1) "" else "s"} " +
                "(existing profiles with the same id were updated).$rulesNote",
        )
        onDone(content.profiles.size)
    }

    fun reportTooLarge() {
        _state.value = _state.value.copy(busy = false, message = TOO_LARGE)
    }

    /** The picked document could not be opened or read: not a verdict on its contents. */
    fun reportReadFailure() {
        _state.value = _state.value.copy(busy = false, message = COULD_NOT_READ)
    }

    /**
     * The picker's write settled. [success] false is a write failure; true reports what the file
     * holds — the counts captured by the export it came from, or a counts-free line when a newer
     * export already replaced them (the Export button is live again while a file is being written).
     */
    fun reportExport(success: Boolean) {
        val counts = exportCounts
        exportCounts = null // one report per capture: never reused by a later call
        _state.value = _state.value.copy(
            busy = false,
            message = when {
                !success -> "Could not write the snapshot file."
                counts == null -> "Encrypted snapshot written."
                else -> exportWrittenMessage(counts.first, counts.second)
            },
        )
    }

    /** What a written file holds: the whole store, or how many stored rows it left out. */
    private fun exportWrittenMessage(written: Int, stored: Int): String {
        val leftOut = stored - written
        return if (leftOut <= 0) {
            "Encrypted snapshot written: $written profile${if (written == 1) "" else "s"}."
        } else {
            "Wrote $written of $stored profile${if (stored == 1) "" else "s"}. " +
                "$leftOut couldn't be read and ${if (leftOut == 1) "was" else "were"} left out."
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    companion object {
        const val WRONG_PASSPHRASE = "Wrong passphrase, or the file is damaged."
        const val UNREADABLE_SNAPSHOT = "That file isn't a readable Vaayil snapshot."
        const val TOO_LARGE = "That file is too large to be a Vaayil snapshot."
        const val COULD_NOT_READ = "Couldn't read that file."
        const val WRITE_FAILED = "Couldn't write the profiles. Nothing was changed."
        const val LEGACY_RULES_DROPPED =
            "Per-app rules in this older snapshot can't be restored. Your current rules stay."
    }
}
