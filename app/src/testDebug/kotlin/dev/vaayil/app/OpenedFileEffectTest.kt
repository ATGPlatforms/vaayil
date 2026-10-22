package dev.vaayil.app

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.Profile
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.EditorDraft
import dev.vaayil.feature.profiles.ImportMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/**
 * A file opened from another app opens its review as a new editor entry over whatever the
 * screen shows — the tabs, onboarding, or an editor whose draft stays kept beneath it (F30) —
 * and a failure becomes one notice. The queue hands each outcome out once, so a recreation
 * never repeats an open.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
class OpenedFileEffectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = OpenedFiles()
    private val failures = mutableListOf<String>()
    private val drafts = ConcurrentHashMap<String, EditorDraft>()
    private val pendingDrafts = ConcurrentHashMap<String, Profile>()
    private val importNotices = ConcurrentHashMap<String, String>()
    private lateinit var nav: NavHostController
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private var shown by mutableStateOf(true)
    private var start = "dashboard"

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry, LocalReducedMotion provides true) {
                if (shown) {
                    nav = rememberNavController()
                    VaayilTheme.Content {
                        EditorTestGraph(nav, drafts, pendingDrafts, importNotices, start)
                        OpenedFileEffect(opened, nav) { failures += it }
                    }
                }
            }
        }
    }

    /** Queues a review for a new import draft [id] named [name], as an open from another app does. */
    private fun postReview(id: String, name: String) {
        pendingDrafts[id] = savedHome.copy(id = id, name = name)
        opened.post(OpenedFileOutcome.Review(id), opened.generation())
    }

    private fun editorEntries(): Int = compose.runOnIdle {
        nav.currentBackStack.value.count { it.destination.route == EDITOR_ROUTE }
    }

    /** Bounded wait for the effect's collection to run on the main thread. */
    private fun awaitEditorEntries(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (editorEntries() < count && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("editor entries", count, editorEntries())
    }

    private fun awaitFailures(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (compose.runOnIdle { failures.size } < count && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.runOnIdle { assertEquals("failures", count, failures.size) }
    }

    /** Taps "Generate" and returns the new private key, read from the kept draft. */
    private fun generateKey(): String {
        compose.onNodeWithText("Generate").performClick()
        return compose.runOnIdle { drafts.values.single().current.interfaceConfig.privateKey }
    }

    private fun assertKeyShown(key: String) {
        compose.onNodeWithContentDescription("Show Private key").performClick()
        compose.onNodeWithText(key).assertExists()
    }

    private fun recreate(keepViewModels: Boolean) {
        val saved = compose.runOnIdle { registry.performSave() }
        shown = false
        compose.waitForIdle()
        if (!keepViewModels) compose.runOnIdle { compose.activity.viewModelStore.clear() }
        compose.runOnIdle {
            registry = SaveableStateRegistry(saved) { true }
            shown = true
        }
        compose.waitForIdle()
    }

    @Test
    fun `an opened file's review opens over the Dashboard and Back discards it`() {
        show()
        postReview("d1", "Opened")

        compose.onNodeWithText("Opened").assertExists()
        awaitEditorEntries(1)
        compose.runOnIdle { assertTrue(opened.pending.value.isEmpty()) }

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Dashboard tab").assertExists()
        compose.runOnIdle { assertTrue(pendingDrafts.isEmpty()) }
    }

    @Test
    fun `a recreation does not open the review again`() {
        show()
        postReview("d1", "Opened")
        awaitEditorEntries(1)

        recreate(keepViewModels = false)

        awaitEditorEntries(1)
        compose.onNodeWithText("Opened").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Dashboard tab").assertExists()
    }

    @Test
    fun `over onboarding the review opens at once and Save returns to onboarding`() {
        start = "onboarding"
        show()
        postReview("d1", "Opened")

        compose.onNodeWithText("Opened").assertExists()
        compose.onNodeWithText("Save Profile").performScrollTo().performClick()

        compose.onNodeWithText("Onboarding").assertExists()
        compose.onNodeWithText("Profiles tab").assertDoesNotExist()
        compose.onNodeWithText("Dashboard tab").assertDoesNotExist()
    }

    @Test
    fun `over onboarding Back returns to onboarding`() {
        start = "onboarding"
        show()
        postReview("d1", "Opened")

        compose.onNodeWithText("Opened").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Onboarding").assertExists()
        compose.onNodeWithText("Profiles tab").assertDoesNotExist()
        compose.onNodeWithText("Dashboard tab").assertDoesNotExist()
    }

    @Test
    fun `over an edited editor the review is a new entry and Back returns to the kept draft`() {
        show()
        compose.runOnIdle {
            nav.navigateTab("profiles")
            nav.navigate("editor?profileId=p1")
        }
        compose.waitForIdle()
        val key = generateKey()
        postReview("d1", "Opened")

        compose.onNodeWithText("Opened").assertExists()
        awaitEditorEntries(2)

        compose.onNodeWithContentDescription("Back").performClick()
        assertKeyShown(key)
    }

    @Test
    fun `a second open over a review is a new entry`() {
        show()
        postReview("d1", "First")
        awaitEditorEntries(1)
        postReview("d2", "Second")

        compose.onNodeWithText("Second").assertExists()
        awaitEditorEntries(2)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("First").assertExists()
    }

    @Test
    fun `opens queued before the graph exists open in order, the newest on top`() {
        postReview("d1", "First")
        postReview("d2", "Second")
        show()

        compose.onNodeWithText("Second").assertExists()
        awaitEditorEntries(2)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("First").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Dashboard tab").assertExists()
    }

    @Test
    fun `a failed open shows its message and pushes nothing`() {
        show()
        opened.post(OpenedFileOutcome.Failed(ImportMessages.UNREADABLE), opened.generation())

        awaitFailures(1)

        assertEquals(0, editorEntries())
        compose.runOnIdle {
            assertEquals(listOf(ImportMessages.UNREADABLE), failures)
            assertEquals("dashboard", nav.currentDestination?.route)
        }
    }

    @Test
    fun `an open waits until the screen is started`() {
        show()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

        postReview("d1", "Opened")
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, editorEntries())

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitEditorEntries(1)
    }
}
