package dev.vaayil.app

import android.net.Uri
import dev.vaayil.core.data.DocumentRead
import dev.vaayil.core.model.Profile
import dev.vaayil.feature.profiles.ImportMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What an open from another app asked for: a content URI to read, or [Refused] when the app will
 * not read it (not a `content:` URI, or served by Vaayil itself), so the screen can say so.
 */
internal sealed interface OpenRequest {
    data class File(val uri: Uri) : OpenRequest
    data object Refused : OpenRequest
}

/** The result of one open, waiting for the screen that serves it: a review for its draft, or the error copy. */
internal sealed interface OpenedFileOutcome {
    data class Review(val draftId: String) : OpenedFileOutcome
    data class Failed(val message: String) : OpenedFileOutcome
}

/**
 * The in-memory hand-off between an open from another app and the screen that shows its result.
 * A read finishes before that screen exists, so its outcome waits here; nothing is persisted, and
 * a process death simply drops a result for a launch the user has long left. [clear] also fences
 * reads still running, so an outcome of an earlier app session never lands in this one.
 */
internal class OpenedFiles {
    private val _pending = MutableStateFlow<List<OpenedFileOutcome>>(emptyList())
    val pending: StateFlow<List<OpenedFileOutcome>> = _pending.asStateFlow()
    private var currentGeneration = 0L

    /** The generation a read captures before it starts; [post] takes it back. */
    @Synchronized
    fun generation(): Long = currentGeneration

    /** Queues [outcome] for [generation]; false, and nothing queued, once [clear] ran since it was captured. */
    @Synchronized
    fun post(outcome: OpenedFileOutcome, generation: Long): Boolean {
        if (generation != currentGeneration) return false
        _pending.value = _pending.value + outcome
        return true
    }

    /** The oldest queued outcome, removed; null when empty. */
    @Synchronized
    fun take(): OpenedFileOutcome? {
        val queue = _pending.value
        if (queue.isEmpty()) return null
        _pending.value = queue.drop(1)
        return queue.first()
    }

    /** Empties the queue and refuses every outcome a read started before now still owes. */
    @Synchronized
    fun clear() {
        currentGeneration++
        _pending.value = emptyList()
    }
}

/**
 * Reads and parses one opened file into the outcome the screen shows. [Refused] fails without
 * touching the file at all; a read that is too large or unreadable maps to the import screen's own
 * copy (c6), and a parser rejection keeps the parser's message, which never echoes the config.
 * Cancellation is the caller going away, not a failed open, so it is rethrown.
 */
internal suspend fun openFileOutcome(
    request: OpenRequest,
    read: suspend (Uri) -> DocumentRead,
    baseName: suspend (Uri) -> String,
    parse: suspend (text: String, name: String) -> Profile,
): OpenedFileOutcome = when (request) {
    OpenRequest.Refused -> OpenedFileOutcome.Failed(ImportMessages.UNREADABLE)
    is OpenRequest.File -> when (val document = read(request.uri)) {
        DocumentRead.TooLarge -> OpenedFileOutcome.Failed(ImportMessages.TOO_LARGE)
        DocumentRead.Unreadable -> OpenedFileOutcome.Failed(ImportMessages.UNREADABLE)
        is DocumentRead.Text -> try {
            OpenedFileOutcome.Review(parse(document.text, baseName(request.uri)).id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OpenedFileOutcome.Failed(importFailureMessage(e) ?: ImportMessages.NOT_A_CONFIG)
        }
    }
}
