package dev.vaayil.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.Profile
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.EditorDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/**
 * F30 (spec U7, Review Focus 1): an edit survives Android recreating the activity, and the
 * private key never enters saved state. Recreation is driven the way the activity drives it:
 * save every saveable value, dispose the composition, clear the view models when the activity
 * is destroyed for good ("Don't keep activities"; a rotation keeps them), then compose again
 * from the saved values.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
class EditorDraftSurvivalTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val drafts = ConcurrentHashMap<String, EditorDraft>()
    private val pendingDrafts = ConcurrentHashMap<String, Profile>()
    private val importNotices = ConcurrentHashMap<String, String>()
    private lateinit var nav: NavHostController
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private var shown by mutableStateOf(true)

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry, LocalReducedMotion provides true) {
                if (shown) {
                    nav = rememberNavController()
                    VaayilTheme.Content { EditorTestGraph(nav, drafts, pendingDrafts, importNotices) }
                }
            }
        }
    }

    private fun openEditor() {
        show()
        compose.runOnIdle {
            nav.navigateTab("profiles")
            nav.navigate("editor?profileId=p1")
        }
        compose.onNodeWithText("Edit Profile").assertExists()
    }

    /** Taps "Generate" and returns the new private key, read from the kept draft. */
    private fun generateKey(): String {
        compose.onNodeWithText("Generate").performClick()
        return compose.runOnIdle { drafts.values.single().current.interfaceConfig.privateKey }
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
        compose.onNodeWithText("Edit Profile").assertExists()
    }

    private fun assertKeyShown(key: String) {
        compose.onNodeWithContentDescription("Show Private key").performClick()
        compose.onNodeWithText(key).assertExists()
    }

    @Test
    fun `a generated key survives Don't keep activities`() {
        openEditor()
        val key = generateKey()
        assertNotEquals(savedHome.interfaceConfig.privateKey, key)
        recreate(keepViewModels = false)
        assertKeyShown(key)
        // The restored form is still unsaved, so Back still asks.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Discard changes?").assertExists()
    }

    @Test
    fun `a generated key survives a rotation`() {
        openEditor()
        val key = generateKey()
        recreate(keepViewModels = true)
        assertKeyShown(key)
    }

    @Test
    fun `a screen opened over the editor keeps its draft`() {
        openEditor()
        val key = generateKey()
        compose.runOnIdle { nav.navigate("dashboard") }
        compose.onNodeWithText("Dashboard tab").assertExists()
        compose.runOnIdle { assertEquals(key, drafts.values.single().current.interfaceConfig.privateKey) }
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Edit Profile").assertExists()
        recreate(keepViewModels = false)
        assertKeyShown(key)
    }

    @Test
    fun `an editor under a second editor keeps its draft through a recreation`() {
        openEditor()                                   // Profiles tab, then editor?profileId=p1
        val key = generateKey()
        pendingDrafts["d2"] = savedHome.copy(id = "d2", name = "Opened")
        compose.runOnIdle { nav.navigate("editor?draftId=d2") }   // a second editor entry on top
        compose.onNodeWithText("Opened").assertExists()
        compose.runOnIdle { assertTrue(drafts.values.any { it.current.interfaceConfig.privateKey == key }) }
        recreate(keepViewModels = false)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }   // the untouched review leaves
        assertKeyShown(key)
    }

    @Test
    fun `an import's review under a second review keeps its import through a recreation`() {
        pendingDrafts["d1"] = savedHome.copy(id = "d1", name = "First")
        pendingDrafts["d2"] = savedHome.copy(id = "d2", name = "Second")
        show()
        compose.runOnIdle { nav.navigateTab("profiles"); nav.navigate("editor?draftId=d1") }
        compose.onNodeWithText("First").assertExists()
        compose.runOnIdle { nav.navigate("editor?draftId=d2") }
        compose.onNodeWithText("Second").assertExists()
        compose.runOnIdle { assertEquals(setOf("d1", "d2"), pendingDrafts.keys.toSet()) }
        recreate(keepViewModels = false)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("First").assertExists()
        compose.onNodeWithText("This import was interrupted. Import the file again.").assertDoesNotExist()
        compose.runOnIdle { assertEquals(setOf("d1"), pendingDrafts.keys.toSet()) }
    }

    @Test
    fun `the private key never enters saved state`() {
        openEditor()
        val key = generateKey()
        val strings = savedStrings(compose.runOnIdle { registry.performSave() })
        assertTrue("the walk reaches the editor's nav arguments", "p1" in strings)
        assertTrue("the generated key is in saved state", strings.none { key in it })
    }
}

/** Every string in a saved-state tree: Bundles, maps, collections and snapshot states, all the way down. */
@Suppress("DEPRECATION") // Bundle.get(String): the walk cannot know each value's type up front.
private fun savedStrings(value: Any?, into: MutableList<String> = mutableListOf()): List<String> {
    when (value) {
        is CharSequence -> into += value.toString()
        is Bundle -> value.keySet().forEach { savedStrings(value.get(it), into) }
        is State<*> -> savedStrings(value.value, into)
        is Map<*, *> -> value.forEach { (k, v) -> savedStrings(k, into); savedStrings(v, into) }
        is Iterable<*> -> value.forEach { savedStrings(it, into) }
        is Array<*> -> value.forEach { savedStrings(it, into) }
    }
    return into
}
