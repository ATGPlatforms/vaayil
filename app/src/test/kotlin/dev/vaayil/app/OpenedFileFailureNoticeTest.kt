package dev.vaayil.app

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.feature.profiles.ImportMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * One opened-file failure snackbar at a time: a failure arriving while another waits or shows is
 * dropped, and the next one after it is dismissed shows again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class OpenedFileFailureNoticeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val snackbar = SnackbarHostState()
    private val notice = OpenedFileFailureNotice(snackbar, scope)

    @After
    fun tearDown() = scope.cancel()

    private fun shown(): String? = snackbar.currentSnackbarData?.visuals?.message

    private fun dismiss() = snackbar.currentSnackbarData!!.dismiss()

    @Test
    fun `two failures back to back show one snackbar`() {
        notice.show(ImportMessages.UNREADABLE)
        notice.show(ImportMessages.TOO_LARGE)

        assertEquals(ImportMessages.UNREADABLE, shown())
        dismiss()
        assertNull("the second failure waited instead of being dropped", shown())
    }

    @Test
    fun `a failure after the first one is dismissed shows again`() {
        notice.show(ImportMessages.UNREADABLE)
        dismiss()
        notice.show(ImportMessages.TOO_LARGE)

        assertEquals(ImportMessages.TOO_LARGE, shown())
    }

    @Test
    fun `a failure waiting behind another notice holds back a further one`() {
        scope.launch { snackbar.showSnackbar("Couldn't switch to Work. Still connected to Home.") }
        notice.show(ImportMessages.UNREADABLE)
        notice.show(ImportMessages.TOO_LARGE)

        dismiss()
        assertEquals(ImportMessages.UNREADABLE, shown())
        dismiss()
        assertNull(shown())
    }
}
