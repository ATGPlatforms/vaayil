package dev.vaayil.core.data

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of reading a user-picked document: its text, too large for the cap, or not readable at all. */
sealed interface DocumentRead {
    data class Text(val text: String) : DocumentRead
    data object TooLarge : DocumentRead
    data object Unreadable : DocumentRead
}

/** Pure mapping from an opener to an outcome; every failure lands in exactly one branch. */
internal fun readDocument(open: () -> InputStream?, maxBytes: Int): DocumentRead =
    try {
        open()?.use { DocumentRead.Text(readBoundedText(it, maxBytes)) } ?: DocumentRead.Unreadable
    } catch (_: InputTooLargeException) {
        DocumentRead.TooLarge
    } catch (_: Exception) {
        DocumentRead.Unreadable
    }

/** Reads a SAF document as bounded UTF-8 on the IO dispatcher. Shared by the .conf and .vylconf importers. */
suspend fun readDocumentText(resolver: ContentResolver, uri: Uri, maxBytes: Int = DEFAULT_MAX_INPUT_BYTES): DocumentRead =
    withContext(Dispatchers.IO) { readDocument({ resolver.openInputStream(uri) }, maxBytes) }
