package dev.vaayil.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.Profile
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.EditorDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/** F30 (spec U7): Back from an editor with unsaved changes asks first; an unchanged one leaves at once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
class EditorBackGuardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val drafts = ConcurrentHashMap<String, EditorDraft>()
    private val pendingDrafts = ConcurrentHashMap<String, Profile>()
    private val importNotices = ConcurrentHashMap<String, String>()
    private lateinit var nav: NavHostController

    /** Opens [route] from the Profiles tab, the way the app does. */
    private fun open(route: String) {
        compose.setContent {
            nav = rememberNavController()
            CompositionLocalProvider(LocalReducedMotion provides true) {
                VaayilTheme.Content { EditorTestGraph(nav, drafts, pendingDrafts, importNotices) }
            }
        }
        compose.runOnIdle {
            nav.navigateTab("profiles")
            nav.navigate(route)
        }
        compose.onNodeWithText("Edit Profile").assertExists()
    }

    private fun systemBack() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun rename(to: String) =
        compose.onNode(hasSetTextAction() and hasContentDescription("Profile name")).performTextReplacement(to)

    @Test
    fun `an unchanged editor leaves at once`() {
        open("editor?profileId=p1")
        systemBack()
        compose.onNodeWithText("Profiles tab").assertExists()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyMap<String, EditorDraft>(), drafts.toMap()) }
    }

    @Test
    fun `an edited profile asks before discarding`() {
        open("editor?profileId=p1")
        rename("Office")
        systemBack()
        compose.onNodeWithText("Discard changes?").assertExists()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Edit Profile").assertExists()
        // The header's back arrow asks too.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Profiles tab").assertExists()
        compose.runOnIdle { assertEquals(emptyMap<String, EditorDraft>(), drafts.toMap()) }
    }

    @Test
    fun `undoing the edit makes Back leave at once`() {
        open("editor?profileId=p1")
        rename("Office")
        rename("Home")
        systemBack()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Profiles tab").assertExists()
    }

    @Test
    fun `a new profile with a typed name asks before discarding`() {
        open("editor")
        rename("Office")
        systemBack()
        compose.onNodeWithText("Discard changes?").assertExists()
    }

    @Test
    fun `discarding an edited import drops the import`() {
        pendingDrafts["d1"] = savedHome.copy(id = "d1", name = "Imported")
        open("editor?draftId=d1")
        rename("Office")
        systemBack()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Profiles tab").assertExists()
        compose.runOnIdle {
            assertEquals(emptyMap<String, Profile>(), pendingDrafts.toMap())
            assertEquals(emptyMap<String, EditorDraft>(), drafts.toMap())
        }
    }

    @Test
    fun `tapping the scrim keeps editing`() {
        open("editor?profileId=p1")
        rename("Office")
        systemBack()
        compose.onNodeWithText("Discard changes?").assertExists()
        // The scrim, outside the centered sheet: its window's top-left corner. The dialog is a
        // second root, so pick the one holding the sheet (onRoot() would match both).
        compose.onNode(isRoot() and hasAnyDescendant(hasText("Discard changes?")))
            .performTouchInput { click(topLeft) }
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Edit Profile").assertExists()
    }

    @Test
    fun `discarding an import drops its notice with its draft`() {
        importNotices["d1"] = "Search domains aren't supported, so these were left out: corp.example"
        pendingDrafts["d1"] = savedHome.copy(id = "d1", name = "Imported")
        open("editor?draftId=d1")
        rename("Office")
        systemBack()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Profiles tab").assertExists()
        compose.runOnIdle {
            assertTrue(pendingDrafts.isEmpty())
            assertTrue(importNotices.isEmpty()) // T11: the notice dies with its draft
        }
    }
}
