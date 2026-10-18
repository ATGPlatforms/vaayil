package dev.vaayil.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
 * C3: a Back activation that arrives while the leaving entry's exit transition is still running
 * must never pop the start destination, and a repeated push must never stack a route twice.
 * The real [PushScreen], [VellumTopBar] and NavHost carry the flow; the editor runs through the
 * production [EditorEntry] via [EditorTestGraph].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
class NavigationSafetyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val drafts = ConcurrentHashMap<String, EditorDraft>()
    private val pendingDrafts = ConcurrentHashMap<String, Profile>()
    private val importNotices = ConcurrentHashMap<String, String>()
    private lateinit var nav: NavHostController

    private fun show() {
        compose.setContent {
            nav = rememberNavController()
            CompositionLocalProvider(LocalReducedMotion provides true) {
                VaayilTheme.Content { PushedScreenGraph(nav) }
            }
        }
    }

    private fun showEditor() {
        compose.setContent {
            nav = rememberNavController()
            CompositionLocalProvider(LocalReducedMotion provides true) {
                VaayilTheme.Content { EditorTestGraph(nav, drafts, pendingDrafts, importNotices) }
            }
        }
    }

    /** Lets every enter/exit transition in flight finish (virtual time, no sleeps). */
    private fun settle() = compose.mainClock.advanceTimeBy(1_000)

    /**
     * The click action as the tree holds it right now, kept across the pop: an outgoing entry can
     * still call it after the entry has been left behind.
     */
    private fun retainedClick(node: SemanticsNode): () -> Unit {
        val action = node.config[SemanticsActions.OnClick].action
        return { action?.invoke() }
    }

    private fun rename(to: String) =
        compose.onNode(hasSetTextAction() and hasContentDescription("Profile name")).performTextReplacement(to)

    @Test
    fun doubleBackKeepsDashboard() {
        show()
        compose.onNodeWithContentDescription("Settings").performClick()
        settle()
        compose.onNodeWithText("Settings screen").assertExists()

        // The popped screen's exit transition is still running: freeze it and hit Back again.
        compose.mainClock.autoAdvance = false
        val back = retainedClick(compose.onNodeWithContentDescription("Back").fetchSemanticsNode())
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals("dashboard", nav.currentDestination?.route) }
        compose.runOnIdle { back() }

        compose.runOnIdle { assertEquals("dashboard", nav.currentDestination?.route) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Dashboard").assertExists()
        compose.onNodeWithText("Settings screen").assertDoesNotExist()

        // Unfrozen, one Back still leaves a pushed screen.
        compose.onNodeWithContentDescription("Settings").performClick()
        settle()
        compose.onNodeWithContentDescription("Back").performClick()
        settle()
        compose.onNodeWithText("Dashboard").assertExists()
        compose.runOnIdle { assertEquals("dashboard", nav.currentDestination?.route) }
    }

    @Test
    fun doubleGearPushesSettingsOnce() {
        show()
        // Two activations of the gear before the first push is even on screen.
        compose.onNodeWithContentDescription("Settings").performTouchInput { click(); click() }
        settle()
        compose.onNodeWithText("Settings screen").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        settle()
        compose.runOnIdle { assertEquals("dashboard", nav.currentDestination?.route) }
        compose.onNodeWithText("Dashboard").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun outgoingEntryIgnoresEnterBurst() {
        show()
        compose.onNodeWithContentDescription("Settings").performClick()
        settle()
        compose.onNodeWithText("Settings screen").assertExists()

        val back = retainedClick(compose.onNodeWithContentDescription("Back").fetchSemanticsNode())
        // Freeze the exit transition, then hammer the arrow with the keyboard.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Back").requestFocus()
            .performKeyInput { repeat(8) { pressKey(Key.Enter) } }
        compose.runOnIdle { assertEquals("dashboard", nav.currentDestination?.route) }
        compose.runOnIdle { back() }

        compose.runOnIdle { assertEquals("dashboard", nav.currentDestination?.route) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Dashboard").assertExists()
    }

    @Test
    fun editorDiscardAndSaveKeepTheRoot() {
        showEditor()
        // The editor sits directly on the start destination: one pop too many empties the NavHost.
        compose.runOnIdle { nav.navigatePush("editor?profileId=p1") }
        compose.onNodeWithText("Edit Profile").assertExists()
        rename("Office")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertExists()

        val discard = retainedClick(compose.onNodeWithText("Discard").fetchSemanticsNode())
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { discard() }

        compose.runOnIdle { assertEquals("dashboard", nav.currentDestination?.route) }
        compose.onNodeWithText("Dashboard tab").assertExists()

        // Save completes by popping the editor; the Dashboard under it stays.
        compose.runOnIdle { nav.navigatePush("editor?profileId=p1") }
        compose.onNodeWithText("Edit Profile").assertExists()
        rename("Office")
        compose.onNodeWithText("Save Profile").performScrollTo().performClick()
        settle()
        compose.runOnIdle { assertEquals("dashboard", nav.currentDestination?.route) }
        compose.onNodeWithText("Dashboard tab").assertExists()
    }
}

/**
 * RootNav's pushed-route slice: the production [PushScreen] and [VellumTopBar] over a real
 * NavHost, with the stand-ins the app's routes render.
 */
@Composable
private fun PushedScreenGraph(nav: NavHostController) {
    NavHost(navController = nav, startDestination = "dashboard") {
        composable("dashboard") {
            Column(Modifier.fillMaxSize()) {
                VellumTopBar(onOpenSettings = { nav.navigatePush("settings") })
                Text("Dashboard")
            }
        }
        composable("settings") {
            PushScreen(onBack = { nav.popIfNotRoot() }) { Text("Settings screen") }
        }
        composable("privacy") {
            PushScreen(onBack = { nav.popIfNotRoot() }) { Text("Privacy screen") }
        }
    }
}
