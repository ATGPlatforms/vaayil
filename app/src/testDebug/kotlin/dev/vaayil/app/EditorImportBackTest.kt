package dev.vaayil.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.Profile
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.EditorDraft
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/**
 * Spec U7: Back from an import's review lands on the Profiles tab (Review Focus 2), and a
 * review whose import died with the process says so instead of opening a blank form (M3).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
class EditorImportBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val drafts = ConcurrentHashMap<String, EditorDraft>()
    private val pendingDrafts = ConcurrentHashMap<String, Profile>()
    private val importNotices = ConcurrentHashMap<String, String>()
    private lateinit var nav: NavHostController

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            CompositionLocalProvider(LocalReducedMotion provides true) {
                VaayilTheme.Content { EditorTestGraph(nav, drafts, pendingDrafts, importNotices) }
            }
        }
    }

    @Test
    fun `Back from an untouched import's review lands on the Profiles tab`() {
        pendingDrafts["d1"] = savedHome.copy(id = "d1", name = "Imported")
        show()
        compose.runOnIdle {
            nav.navigateTab("profiles")
            nav.navigate("import") // the Profiles tab's Import entry
        }
        compose.onNodeWithText("Import screen").assertExists()
        compose.runOnIdle { nav.openImportReview("d1") }
        compose.onNodeWithText("Edit Profile").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Profiles tab").assertExists()
        compose.onNodeWithText("Import screen").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyMap<String, Profile>(), pendingDrafts.toMap()) }
    }

    @Test
    fun `a review whose import died with the process says so and lands on the Profiles tab`() {
        show()
        compose.runOnIdle {
            nav.navigateTab("profiles")
            nav.navigate("editor?draftId=gone") // restored after a process death: the map is empty
        }
        compose.onNodeWithText("This import was interrupted. Import the file again.").assertExists()
        compose.onNodeWithText("Profiles tab").assertExists()
        compose.onNodeWithText("Edit Profile").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyMap<String, EditorDraft>(), drafts.toMap()) }
    }

    @Test
    fun `an interrupted review restored over the Dashboard still lands on the Profiles tab`() {
        show()
        // A back stack saved before this fix: the old review replaced everything above the Dashboard.
        compose.runOnIdle { nav.navigate("editor?draftId=gone") }
        compose.onNodeWithText("This import was interrupted. Import the file again.").assertExists()
        compose.onNodeWithText("Profiles tab").assertExists()
    }
}
