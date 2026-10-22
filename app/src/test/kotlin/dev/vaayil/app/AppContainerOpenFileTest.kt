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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    @Test
    fun `five opens read at most four files at once and leave the shared IO threads free`() = runBlocking {
        val hold = Hold()
        val jobs = (1..5).map { n ->
            val file = uri("held-$n.conf")
            shadowOf(app.contentResolver).registerInputStreamSupplier(file) {
                hold.enter()
                ByteArrayInputStream(VALID_CONF.toByteArray())
            }
            container.openFile(OpenRequest.File(file))
        }
        try {
            assertTrue("four reads never started", hold.awaitEntered(4))
            assertFalse("a fifth read ran beside four held ones", hold.anotherEnters())
            assertEquals(true, withTimeoutOrNull(10_000) { withContext(Dispatchers.IO) { true } })
            hold.release()
            withTimeout(10_000) { jobs.joinAll() }
        } finally {
            hold.release()
        }
        assertEquals(4, hold.mostAtOnce.get())
        assertEquals(5, container.pendingDrafts.size)
    }

    @Test
    fun `five opens whose name query hangs ask at most four at once`() = runBlocking {
        val hold = Hold()
        TestFilesProvider.onQuery = { hold.enter() }
        val jobs = (1..5).map { n ->
            val file = uri("named-$n.conf")
            serveTestFile(app, file, VALID_CONF.toByteArray(), "named.conf")
            container.openFile(OpenRequest.File(file))
        }
        try {
            assertTrue("four name queries never started", hold.awaitEntered(4))
            assertFalse("a fifth name query ran beside four held ones", hold.anotherEnters())
            assertEquals(true, withTimeoutOrNull(10_000) { withContext(Dispatchers.IO) { true } })
            hold.release()
            withTimeout(10_000) { jobs.joinAll() }
        } finally {
            hold.release()
        }
        assertEquals(4, hold.mostAtOnce.get())
        assertEquals(5, container.pendingDrafts.size)
    }

    @Test
    fun `a ninth open while eight files are being read is refused unread`() = runBlocking {
        val hold = Hold()
        val queried = java.util.Collections.synchronizedList(mutableListOf<Uri>())
        TestFilesProvider.onQuery = { queried += it }
        val jobs = (1..8).map { n ->
            val file = uri("held-$n.conf")
            shadowOf(app.contentResolver).registerInputStreamSupplier(file) {
                hold.enter()
                ByteArrayInputStream(VALID_CONF.toByteArray())
            }
            container.openFile(OpenRequest.File(file))
        }
        val ninthReads = AtomicInteger()
        val ninth = uri("ninth.conf")
        shadowOf(app.contentResolver).registerInputStreamSupplier(ninth) {
            ninthReads.incrementAndGet()
            ByteArrayInputStream(VALID_CONF.toByteArray())
        }
        try {
            assertTrue("four reads never started", hold.awaitEntered(4))
            withTimeout(10_000) { container.openFile(OpenRequest.File(ninth)).join() }
            assertEquals(OpenedFileOutcome.Failed(ImportMessages.UNREADABLE), container.openedFiles.take())
            assertEquals(0, ninthReads.get())
            assertFalse("the ninth file was queried", ninth in queried)
            hold.release()
            withTimeout(10_000) { jobs.joinAll() }
        } finally {
            hold.release()
        }
        assertEquals(8, container.pendingDrafts.size)
    }

    @Test
    fun `reads in flight and reviews waiting add up to the cap`() = runBlocking {
        repeat(4) { n ->
            val file = uri("waiting-$n.conf")
            serveTestFile(app, file, VALID_CONF.toByteArray(), "waiting.conf")
            container.openFile(OpenRequest.File(file)).join()
        }
        repeat(4) { container.openedFiles.take() }
        val hold = Hold()
        val queried = java.util.Collections.synchronizedList(mutableListOf<Uri>())
        TestFilesProvider.onQuery = { queried += it }
        val jobs = (5..8).map { n ->
            val file = uri("waiting-$n.conf")
            shadowOf(app.contentResolver).registerInputStreamSupplier(file) {
                hold.enter()
                ByteArrayInputStream(VALID_CONF.toByteArray())
            }
            container.openFile(OpenRequest.File(file))
        }
        val ninthReads = AtomicInteger()
        val ninth = uri("ninth.conf")
        shadowOf(app.contentResolver).registerInputStreamSupplier(ninth) {
            ninthReads.incrementAndGet()
            ByteArrayInputStream(VALID_CONF.toByteArray())
        }
        try {
            assertTrue("four reads never started", hold.awaitEntered(4))
            withTimeout(10_000) { container.openFile(OpenRequest.File(ninth)).join() }
            assertEquals(OpenedFileOutcome.Failed(ImportMessages.UNREADABLE), container.openedFiles.take())
            assertEquals(0, ninthReads.get())
            assertFalse("the ninth file was queried", ninth in queried)
            hold.release()
            withTimeout(10_000) { jobs.joinAll() }
        } finally {
            hold.release()
        }
    }

    @Test
    fun `a ninth open while eight reviews wait is refused unread, and leaving one with Back admits the next`() =
        runBlocking {
            val ids = (1..8).map { n ->
                val file = uri("waiting-$n.conf")
                serveTestFile(app, file, VALID_CONF.toByteArray(), "waiting.conf")
                container.openFile(OpenRequest.File(file)).join()
                (container.openedFiles.take() as OpenedFileOutcome.Review).draftId
            }
            val queried = java.util.Collections.synchronizedList(mutableListOf<Uri>())
            TestFilesProvider.onQuery = { queried += it }
            val ninthReads = AtomicInteger()
            val ninth = uri("ninth.conf")
            shadowOf(app.contentResolver).registerInputStreamSupplier(ninth) {
                ninthReads.incrementAndGet()
                ByteArrayInputStream(VALID_CONF.toByteArray())
            }

            container.openFile(OpenRequest.File(ninth)).join()
            assertEquals(OpenedFileOutcome.Failed(ImportMessages.UNREADABLE), container.openedFiles.take())
            assertEquals(0, ninthReads.get())
            assertFalse("the ninth file was queried", ninth in queried)

            // Leaving a review with Back drops its draft, as EditorEntry's onDispose does.
            container.pendingDrafts.remove(ids.first())
            container.importNotices.remove(ids.first())

            container.openFile(OpenRequest.File(ninth)).join()
            val outcome = container.openedFiles.take()
            assertTrue("expected a review, was $outcome", outcome is OpenedFileOutcome.Review)
            assertEquals(1, ninthReads.get())
            assertTrue("the ninth file was never queried", ninth in queried)
            assertEquals(8, container.pendingDrafts.size)
        }
}

/** Holds every call that enters it until [release], and counts how many it holds at once. */
private class Hold {
    private val held = AtomicInteger()
    val mostAtOnce = AtomicInteger()
    private val entered = Semaphore(0)
    private val gate = CountDownLatch(1)

    fun enter() {
        val now = held.incrementAndGet()
        mostAtOnce.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        entered.release()
        try {
            gate.await(10, TimeUnit.SECONDS)
        } finally {
            held.decrementAndGet()
        }
    }

    fun awaitEntered(count: Int): Boolean = entered.tryAcquire(count, 10, TimeUnit.SECONDS)

    /** Whether one more call enters within half a second. */
    fun anotherEnters(): Boolean = entered.tryAcquire(1, 500, TimeUnit.MILLISECONDS)

    fun release() = gate.countDown()
}
