package dev.vaayil.core.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The import's file-name rule, and its fallback when the provider cannot answer the name query. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DocumentBaseNameTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the display name loses its conf suffix`() {
        assertEquals("home", documentBaseName("home.conf", "x"))
    }

    @Test
    fun `a bin extension is dropped`() {
        assertEquals("DOC-20261023-WA0000", documentBaseName("DOC-20261023-WA0000.bin", null))
    }

    @Test
    fun `a conf before a txt comes off with it`() {
        assertEquals("home", documentBaseName("home.conf.txt", null))
    }

    @Test
    fun `the suffix comes off ignoring case`() {
        assertEquals("home", documentBaseName("home.CONF", null))
    }

    @Test
    fun `a space after the suffix is trimmed away`() {
        assertEquals("home", documentBaseName("home.conf ", null))
    }

    @Test
    fun `another extension is kept`() {
        assertEquals("home.lan", documentBaseName("home.lan", null))
    }

    @Test
    fun `a name of only import suffixes is Imported`() {
        assertEquals("Imported", documentBaseName(".conf.txt", null))
    }

    @Test
    fun `a saved copy keeps the suffix inside its name`() {
        // The known limit: one pass over the suffixes leaves a suffix inside a saved copy.
        assertEquals("home.conf (1)", documentBaseName("home.conf (1).txt", null))
    }

    @Test
    fun `no display name falls back to the last path segment`() {
        assertEquals("work", documentBaseName(null, "work.conf"))
    }

    @Test
    fun `a path segment carrying a directory keeps only its file name`() {
        assertEquals("home", documentBaseName(null, "primary:Download/home.conf"))
    }

    @Test
    fun `nothing usable is named Imported`() {
        assertEquals("Imported", documentBaseName(null, null))
        assertEquals("Imported", documentBaseName(".conf", null))
        // A blank display name is still a name: it does not fall back to the path.
        assertEquals("Imported", documentBaseName("  ", "work.conf"))
    }

    @Test
    fun `a uri no provider serves is named from its last segment`() = runBlocking {
        assertEquals(
            "office",
            readDocumentBaseName(context.contentResolver, Uri.parse("content://com.example.none/item/office.conf")),
        )
    }
}
