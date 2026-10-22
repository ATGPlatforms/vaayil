package dev.vaayil.app

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.config.WgConfigParser
import dev.vaayil.core.data.DocumentRead
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.feature.profiles.ImportMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The queue a launch's outcome waits in, and the mapping from a read-and-parse to that outcome. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class OpenedFilesTest {
    private val uri: Uri = Uri.parse("content://com.example.files/item/home.conf")

    private val profile = Profile(
        id = "d1",
        name = "home",
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), emptyList(), null, null),
        peer = PeerConfig("K", null, "vpn.example.com:51820", listOf("0.0.0.0/0"), null),
    )

    @Test
    fun `outcomes are taken oldest first and only once`() {
        val files = OpenedFiles()
        assertTrue(files.post(OpenedFileOutcome.Review("a"), files.generation()))
        assertTrue(files.post(OpenedFileOutcome.Failed("x"), files.generation()))

        assertEquals(OpenedFileOutcome.Review("a"), files.take())
        assertEquals(OpenedFileOutcome.Failed("x"), files.take())
        assertNull(files.take())
        assertTrue(files.pending.value.isEmpty())
    }

    @Test
    fun `a post after clear is refused and queues nothing`() {
        val files = OpenedFiles()
        val generation = files.generation()
        files.clear()

        assertFalse(files.post(OpenedFileOutcome.Review("a"), generation))
        assertNull(files.take())
    }

    @Test
    fun `clear empties the queue`() {
        val files = OpenedFiles()
        files.post(OpenedFileOutcome.Review("a"), files.generation())
        assertEquals(1, files.pending.value.size)

        files.clear()

        assertTrue(files.pending.value.isEmpty())
        assertNull(files.take())
    }

    @Test
    fun `a refused request fails as unreadable and reads nothing`() = runBlocking {
        var reads = 0
        var names = 0
        var parses = 0

        val outcome = openFileOutcome(
            OpenRequest.Refused,
            { reads++; DocumentRead.Text("cfg") },
            { names++; "home" },
            { _, _ -> parses++; profile },
        )

        assertEquals(OpenedFileOutcome.Failed(ImportMessages.UNREADABLE), outcome)
        assertEquals(0, reads)
        assertEquals(0, names)
        assertEquals(0, parses)
    }

    @Test
    fun `an unreadable file fails as unreadable`() = runBlocking {
        var parses = 0

        val outcome = openFileOutcome(
            OpenRequest.File(uri),
            { DocumentRead.Unreadable },
            { "home" },
            { _, _ -> parses++; profile },
        )

        assertEquals(OpenedFileOutcome.Failed(ImportMessages.UNREADABLE), outcome)
        assertEquals(0, parses)
    }

    @Test
    fun `a file over the cap fails as too large`() = runBlocking {
        val outcome = openFileOutcome(
            OpenRequest.File(uri),
            { DocumentRead.TooLarge },
            { "home" },
            { _, _ -> profile },
        )

        assertEquals(OpenedFileOutcome.Failed(ImportMessages.TOO_LARGE), outcome)
    }

    @Test
    fun `a parser rejection shows the parser's message`() = runBlocking {
        val outcome = openFileOutcome(
            OpenRequest.File(uri),
            { DocumentRead.Text("cfg") },
            { "home" },
            { _, _ -> throw WgConfigParser.ParseException(1, "Missing [Peer] section") },
        )

        assertEquals(OpenedFileOutcome.Failed("Missing [Peer] section"), outcome)
    }

    @Test
    fun `any other parse failure shows the generic copy`() = runBlocking {
        val outcome = openFileOutcome(
            OpenRequest.File(uri),
            { DocumentRead.Text("cfg") },
            { "home" },
            { _, _ -> throw IllegalStateException() },
        )

        assertEquals(OpenedFileOutcome.Failed(ImportMessages.NOT_A_CONFIG), outcome)
    }

    @Test
    fun `a cancelled open is not turned into an error`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                openFileOutcome(
                    OpenRequest.File(uri),
                    { DocumentRead.Text("cfg") },
                    { "home" },
                    { _, _ -> throw CancellationException("stopped") },
                )
            }
        }
    }

    @Test
    fun `a config opens a review named from the file`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()

        val outcome = openFileOutcome(
            OpenRequest.File(uri),
            { DocumentRead.Text("cfg") },
            { "home" },
            { text, name -> calls += text to name; profile },
        )

        assertEquals(listOf("cfg" to "home"), calls)
        assertEquals(OpenedFileOutcome.Review("d1"), outcome)
    }
}
