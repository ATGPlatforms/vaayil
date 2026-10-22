package dev.vaayil.app

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.DataGraph
import dev.vaayil.feature.profiles.ImportMessages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * What an intent from another app asks Vaayil to read, and what it must refuse: only a content
 * URI handed over by Open with or Share, never one of Vaayil's own providers, never another
 * scheme, and never a share's text. A handled intent is read on the app scope into one unsaved
 * draft, exactly as a picked file is.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class OpenedFileIntentTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        DataGraph.reset()
        runBlocking { app.settingsDataStore.edit { it.clear() } }
        container = AppContainer(app, { _ -> }, { _ -> })
        Robolectric.setupContentProvider(TestFilesProvider::class.java, TestFilesProvider.AUTHORITY)
    }

    @After
    fun tearDown() {
        TestFilesProvider.reset()
    }

    private fun uri(lastSegment: String): Uri =
        Uri.parse("content://${TestFilesProvider.AUTHORITY}/item/$lastSegment")

    private fun requestOf(intent: Intent?): OpenRequest? =
        openRequestOf(intent, app::isOwnProviderAuthority)

    private fun view(uri: Uri, type: String? = null): Intent =
        if (type == null) {
            Intent(Intent.ACTION_VIEW).setData(uri)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
        }

    @Test
    fun `Vaayil's own provider authority is recognised`() {
        assertTrue(app.isOwnProviderAuthority("dev.vaayil.android.androidx-startup"))
        assertFalse(app.isOwnProviderAuthority("com.example.files"))
    }

    @Test
    fun `Open with a content uri asks to read it`() {
        val file = uri("1")
        assertEquals(OpenRequest.File(file), requestOf(view(file)))
    }

    @Test
    fun `Share with a stream asks to read the stream`() {
        val file = uri("1")
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, file)
        assertEquals(OpenRequest.File(file), requestOf(intent))
    }

    @Test
    fun `Share with only ClipData reads its first item`() {
        val file = uri("1")
        val intent = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
        intent.clipData = ClipData.newRawUri("", file)
        assertEquals(OpenRequest.File(file), requestOf(intent))
    }

    @Test
    fun `the launcher's intent and the shortcut's reopen are not opens`() {
        assertNull(requestOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertNull(requestOf(Intent(app, MainActivity::class.java)))
        assertNull(requestOf(null))
    }

    @Test
    fun `a file uri is refused`() {
        assertEquals(
            OpenRequest.Refused,
            requestOf(view(Uri.parse("file:///sdcard/Download/home.conf"))),
        )
    }

    @Test
    fun `another scheme is refused`() {
        assertEquals(
            OpenRequest.Refused,
            requestOf(view(Uri.parse("android.resource://dev.vaayil.android/raw/x"))),
        )
        assertEquals(
            OpenRequest.Refused,
            requestOf(view(Uri.parse("https://example.com/home.conf"))),
        )
    }

    @Test
    fun `Vaayil's own provider is refused, however it is written`() {
        val own = "dev.vaayil.android.androidx-startup"
        assertEquals(OpenRequest.Refused, requestOf(view(Uri.parse("content://$own/x"))))
        assertEquals(OpenRequest.Refused, requestOf(view(Uri.parse("content://0@$own/x"))))
        assertEquals(
            OpenRequest.Refused,
            requestOf(view(Uri.parse("content://${own.uppercase()}/x"))),
        )
    }

    @Test
    fun `Open with no uri is refused`() {
        assertEquals(OpenRequest.Refused, requestOf(Intent(Intent.ACTION_VIEW)))
    }

    @Test
    fun `a share with only text is refused and its text never read`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, VALID_CONF)
        assertEquals(OpenRequest.Refused, requestOf(intent))
    }

    @Test
    fun `a share with a file and text reads the file`() {
        val file = uri("1")
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, file)
            .putExtra(Intent.EXTRA_TEXT, VALID_CONF)
        assertEquals(OpenRequest.File(file), requestOf(intent))
    }

    @Test
    fun `a share whose stream is not a uri is refused`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, "content://com.example.files/item/1")
        assertEquals(OpenRequest.Refused, requestOf(intent))
    }

    @Test
    fun `a fresh launch is the only one handled in onCreate`() {
        val open = view(uri("1"))
        assertTrue(isFreshLaunch(null, open))
        assertFalse(isFreshLaunch(Bundle(), open))
        assertFalse(
            isFreshLaunch(
                null,
                Intent(open).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY),
            ),
        )
    }

    @Test
    fun `the logged type is a fixed label`() {
        assertEquals("text/plain", loggedOpenType("text/plain"))
        assertEquals("application/octet-stream", loggedOpenType("application/octet-stream"))
        assertEquals("unset", loggedOpenType(null))
        assertEquals("other", loggedOpenType("text/html; x=<script>"))
    }

    @Test
    fun `an Open with intent leaves one draft named from the file and saves nothing`() = runBlocking {
        for ((index, type) in listOf("application/octet-stream", "text/plain").withIndex()) {
            val file = uri("open-$index")
            serveTestFile(app, file, VALID_CONF.toByteArray(), "home.conf")

            openRequestOf(view(file, type), app::isOwnProviderAuthority)!!
                .let(container::openFile)
                .join()

            val outcome = container.openedFiles.take()
            assertTrue("$type: expected a review, was $outcome", outcome is OpenedFileOutcome.Review)
            val review = outcome as OpenedFileOutcome.Review
            assertEquals("$type: one draft", setOf(review.draftId), container.pendingDrafts.keys)
            assertEquals(
                "$type: named from the file",
                "home",
                container.pendingDrafts[review.draftId]?.name,
            )
            assertTrue("$type: nothing saved", container.profiles.observeProfiles().first().isEmpty())
            // The next open would otherwise take the uniqueness suffix; both types start alike.
            container.pendingDrafts.clear()
        }
    }

    @Test
    fun `a Share intent leaves one draft named from the file and saves nothing`() = runBlocking {
        val file = uri("shared")
        serveTestFile(app, file, VALID_CONF.toByteArray(), "home.conf")

        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, file)
        openRequestOf(intent, app::isOwnProviderAuthority)!!
            .let(container::openFile)
            .join()

        val outcome = container.openedFiles.take()
        assertTrue("expected a review, was $outcome", outcome is OpenedFileOutcome.Review)
        val review = outcome as OpenedFileOutcome.Review
        assertEquals(setOf(review.draftId), container.pendingDrafts.keys)
        assertEquals("home", container.pendingDrafts[review.draftId]?.name)
        assertTrue(container.profiles.observeProfiles().first().isEmpty())
    }

    @Test
    fun `a refused intent says Could not read file`() = runBlocking {
        val refused = listOf(
            view(Uri.parse("file:///sdcard/Download/home.conf")),
            view(Uri.parse("content://dev.vaayil.android.androidx-startup/x")),
        )
        for (intent in refused) {
            openRequestOf(intent, app::isOwnProviderAuthority)!!
                .let(container::openFile)
                .join()
            assertEquals(
                OpenedFileOutcome.Failed(ImportMessages.UNREADABLE),
                container.openedFiles.take(),
            )
            assertTrue(container.pendingDrafts.isEmpty())
        }
    }
}
