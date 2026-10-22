package dev.vaayil.app

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import org.robolectric.Shadows.shadowOf

/**
 * A document provider for the open-a-file tests: it answers the display-name query only, from
 * [names], and can be made to fail it. The bytes are not served through it; [serveTestFile]
 * registers them on the content resolver instead, which is what the production read uses.
 */
class TestFilesProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (queryFails) throw IllegalStateException("the name query failed")
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
        names[uri]?.let { cursor.addRow(arrayOf(it)) }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.example.files"

        val names: MutableMap<Uri, String> = mutableMapOf()
        var queryFails: Boolean = false

        fun reset() {
            names.clear()
            queryFails = false
        }
    }
}

/** Serves [bytes] for [uri] (a fresh stream per read) and records [displayName] for the query. */
fun serveTestFile(app: Application, uri: Uri, bytes: ByteArray, displayName: String?) {
    if (displayName == null) {
        TestFilesProvider.names.remove(uri)
    } else {
        TestFilesProvider.names[uri] = displayName
    }
    shadowOf(app.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
}

/** A config the parser accepts, matching the editor tests' saved profile. */
const val VALID_CONF = """
[Interface]
PrivateKey = eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=
Address = 10.0.0.2/32
DNS = 1.1.1.1

[Peer]
PublicKey = KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=
AllowedIPs = 0.0.0.0/0
Endpoint = vpn.example.com:51820
"""
