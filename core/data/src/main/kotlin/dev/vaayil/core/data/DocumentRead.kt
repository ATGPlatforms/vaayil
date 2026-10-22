package dev.vaayil.core.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
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

/**
 * The import's name for a file: its display name, else its URI's last path segment, without a
 * trailing `.conf`; "Imported" when neither leaves anything. A blank display name is still a
 * name, so it never falls back to the path.
 */
internal fun documentBaseName(displayName: String?, lastPathSegment: String?): String =
    (displayName ?: lastPathSegment)?.removeSuffix(".conf")?.trim()?.takeIf { it.isNotBlank() } ?: "Imported"

/**
 * [documentBaseName] for a document a provider serves. The name query is best effort: a provider
 * that throws, returns no row or has no display-name column leaves the last path segment to name
 * the file, so opening it still works.
 */
suspend fun readDocumentBaseName(resolver: ContentResolver, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val displayName = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                // By index, not position: a provider that ignores the projection must not turn
                // another column into the name.
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        } catch (_: Exception) {
            null
        }
        documentBaseName(displayName, uri.lastPathSegment)
    }
