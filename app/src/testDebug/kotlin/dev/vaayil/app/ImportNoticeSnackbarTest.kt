package dev.vaayil.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/** F19 (spec U2): an import's search-domain notice shows once, as its review opens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ImportNoticeSnackbarTest {
    @get:Rule val compose = createComposeRule()

    private val notice = "Search domains aren't supported, so these were left out: corp.example."

    @Test
    fun `the notice shows when the review opens, and never again`() {
        val notices = ConcurrentHashMap(mapOf("d1" to notice))
        val snackbar = SnackbarHostState()
        var open by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Box {
                    if (open) ImportNoticeSnackbar(draftId = "d1", notices = notices, snackbar = snackbar)
                    SnackbarHost(snackbar)
                }
            }
        }
        compose.onNodeWithText(notice).assertIsDisplayed()
        assertEquals(emptyMap<String, String>(), notices.toMap())
        // Leave and come back, as a rotation does: the effect runs again and finds nothing to say.
        open = false
        compose.onNodeWithText(notice).assertDoesNotExist()
        open = true
        compose.onNodeWithText(notice).assertDoesNotExist()
    }

    @Test
    fun `editing a saved profile shows no notice`() {
        val notices = ConcurrentHashMap(mapOf("d1" to notice))
        val snackbar = SnackbarHostState()
        compose.setContent {
            MaterialTheme {
                Box {
                    ImportNoticeSnackbar(draftId = null, notices = notices, snackbar = snackbar)
                    SnackbarHost(snackbar)
                }
            }
        }
        compose.onNodeWithText(notice).assertDoesNotExist()
        assertEquals(mapOf("d1" to notice), notices.toMap())
    }
}
