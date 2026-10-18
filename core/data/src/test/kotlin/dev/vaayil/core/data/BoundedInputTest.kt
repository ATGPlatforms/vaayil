package dev.vaayil.core.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedInputTest {
    @Test
    fun `reads up to the limit and refuses one byte more`() {
        val exact = ByteArray(64) { 'a'.code.toByte() }
        assertEquals(String(exact), readBoundedText(ByteArrayInputStream(exact), maxBytes = 64))
        val over = ByteArray(65) { 'a'.code.toByte() }
        val e = assertThrows(InputTooLargeException::class.java) { readBoundedText(ByteArrayInputStream(over), maxBytes = 64) }
        assertEquals(64, e.maxBytes)
    }

    @Test
    fun `decodes utf-8`() {
        val text = "Profil – ünïcode"
        assertEquals(text, readBoundedText(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun `document reads map to text too-large or unreadable`() {
        val text = "[Interface]\n"
        assertEquals(DocumentRead.Text(text), readDocument({ ByteArrayInputStream(text.toByteArray()) }, maxBytes = 64))
        assertEquals(DocumentRead.TooLarge, readDocument({ ByteArrayInputStream(ByteArray(65)) }, maxBytes = 64))
        assertEquals(DocumentRead.Unreadable, readDocument({ null }, maxBytes = 64))
        assertEquals(DocumentRead.Unreadable, readDocument({ throw java.io.FileNotFoundException("gone") }, maxBytes = 64))
        assertTrue(readDocument({ ByteArrayInputStream(text.toByteArray()) }, maxBytes = 64) is DocumentRead.Text)
    }
}
