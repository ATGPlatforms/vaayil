package dev.vaayil.app

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.DEFAULT_MAX_INPUT_BYTES
import dev.vaayil.core.data.DataGraph
import dev.vaayil.feature.profiles.ImportMessages
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Opening a file from another app reads it on the app scope and leaves one unsaved draft for the
 * review, named from the file: nothing is stored, and dropping the drafts drops a read still
 * running with it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class AppContainerOpenFileTest {
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

    @Test
    fun `an opened config waits as one draft named from the file and nothing is saved`() = runBlocking {
        val file = uri("1")
        serveTestFile(app, file, VALID_CONF.toByteArray(), "home.conf")

        container.openFile(OpenRequest.File(file)).join()

        val outcome = container.openedFiles.take()
        assertTrue("expected a review, was $outcome", outcome is OpenedFileOutcome.Review)
        val review = outcome as OpenedFileOutcome.Review
        assertEquals(setOf(review.draftId), container.pendingDrafts.keys)
        assertEquals("home", container.pendingDrafts[review.draftId]?.name)
        assertTrue(container.profiles.observeProfiles().first().isEmpty())
        assertNull(container.openedFiles.take())
    }

    @Test
    fun `a search domain in the config leaves its notice for the review`() = runBlocking {
        val file = uri("2")
        val conf = VALID_CONF.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1, corp.example")
        serveTestFile(app, file, conf.toByteArray(), "home.conf")

        container.openFile(OpenRequest.File(file)).join()

        val review = container.openedFiles.take() as OpenedFileOutcome.Review
        assertEquals(
            "Search domains aren't supported, so these were left out: corp.example.",
            container.importNotices[review.draftId],
        )
    }

    @Test
    fun `a provider whose name query fails still opens the review`() = runBlocking {
        val file = uri("work.conf")
        TestFilesProvider.queryFails = true
        serveTestFile(app, file, VALID_CONF.toByteArray(), null)

        container.openFile(OpenRequest.File(file)).join()

        val review = container.openedFiles.take() as OpenedFileOutcome.Review
        assertEquals("work", container.pendingDrafts[review.draftId]?.name)
    }

    @Test
    fun `a provider with no name for the file still opens the review`() = runBlocking {
        val file = uri("office.conf")
        serveTestFile(app, file, VALID_CONF.toByteArray(), null)

        container.openFile(OpenRequest.File(file)).join()

        val review = container.openedFiles.take() as OpenedFileOutcome.Review
        assertEquals("office", container.pendingDrafts[review.draftId]?.name)
    }

    @Test
    fun `a file over 1 MiB fails as too large and leaves no draft`() = runBlocking {
        val file = uri("big.conf")
        serveTestFile(app, file, ByteArray(DEFAULT_MAX_INPUT_BYTES + 1), "big.conf")

        container.openFile(OpenRequest.File(file)).join()

        assertEquals(OpenedFileOutcome.Failed(ImportMessages.TOO_LARGE), container.openedFiles.take())
        assertTrue(container.pendingDrafts.isEmpty())
    }

    @Test
    fun `text that is not a config fails and leaves no draft`() = runBlocking {
        val file = uri("not-a-config.conf")
        serveTestFile(app, file, "hello".toByteArray(), "not-a-config.conf")

        container.openFile(OpenRequest.File(file)).join()

        assertTrue(container.openedFiles.take() is OpenedFileOutcome.Failed)
        assertTrue(container.pendingDrafts.isEmpty())
    }

    @Test
    fun `two opens of one file before a save get two names`() = runBlocking {
        val file = uri("home.conf")
        serveTestFile(app, file, VALID_CONF.toByteArray(), "home.conf")

        val first = container.openFile(OpenRequest.File(file))
        val second = container.openFile(OpenRequest.File(file))
        first.join()
        second.join()

        assertEquals(setOf("home", "home (2)"), container.pendingDrafts.values.map { it.name }.toSet())
    }

    @Test
    fun `an open still reading when the drafts are dropped leaves nothing behind`() = runBlocking {
        val file = uri("home.conf")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        shadowOf(app.contentResolver).registerInputStreamSupplier(file) {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            ByteArrayInputStream(VALID_CONF.toByteArray())
        }

        val job = container.openFile(OpenRequest.File(file))
        assertTrue("the read never started", started.await(10, TimeUnit.SECONDS))
        container.dropDrafts()
        release.countDown()
        job.join()

        assertTrue(container.pendingDrafts.isEmpty())
        assertTrue(container.importNotices.isEmpty())
        assertTrue(container.openedFiles.pending.value.isEmpty())
    }

    @Test
    fun `dropping the drafts empties the queue`() = runBlocking {
        val file = uri("home.conf")
        serveTestFile(app, file, VALID_CONF.toByteArray(), "home.conf")
        container.openFile(OpenRequest.File(file)).join()
        assertFalse(container.openedFiles.pending.value.isEmpty())

        container.dropDrafts()

        assertTrue(container.openedFiles.pending.value.isEmpty())
    }
}
