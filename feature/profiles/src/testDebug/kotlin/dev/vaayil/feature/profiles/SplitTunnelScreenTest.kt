package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.SplitTunnelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression for the Serene Shield redesign: the mode segmenter rendered without click
 * handlers, so Exclusive/Inclusive (and by extension the quick-action chips beside it)
 * never fired. Tapping the visible labels must invoke the screen callbacks.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplitTunnelScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun setContent(
        onMode: (SplitTunnelMode) -> Unit = {},
        onAllowLocalNetwork: (Boolean) -> Unit = {},
        onRouteAll: () -> Unit = {},
        onBypassAll: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                SplitTunnelScreen(
                    state = SplitTunnelUiState(),
                    onMode = onMode,
                    onToggle = {},
                    onQuery = {},
                    onShowSystem = {},
                    onAllowLocalNetwork = onAllowLocalNetwork,
                    onRouteAll = onRouteAll,
                    onBypassAll = onBypassAll,
                )
            }
        }
    }

    /** Scrolls the tab's one lazy list until [text] is composed. */
    private fun scrollToText(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun `tapping the Inclusive segment fires the mode callback`() {
        val modes = mutableListOf<SplitTunnelMode>()
        setContent(onMode = { modes += it })
        compose.onNodeWithText("Inclusive").performClick()
        compose.waitForIdle()
        assertEquals(listOf(SplitTunnelMode.INCLUDE), modes)
    }

    @Test
    fun `quick action chips fire their callbacks`() {
        var routed = false
        var bypassed = false
        setContent(onRouteAll = { routed = true }, onBypassAll = { bypassed = true })
        scrollToText("Route All")
        compose.onNodeWithText("Route All").performClick()
        compose.onNodeWithText("Bypass All").performClick()
        compose.waitForIdle()
        assertTrue(routed)
        assertTrue(bypassed)
    }

    @Test
    fun `the show-system-apps row toggles the filter`() {
        // Device review 2026-09-21 C8: the filter moved from a chip to a switch row —
        // the whole row is the toggle target, so tapping the label fires the callback.
        var shown: Boolean? = null
        compose.setContent {
            MaterialTheme {
                SplitTunnelScreen(
                    state = SplitTunnelUiState(),
                    onMode = {}, onToggle = {}, onQuery = {},
                    onShowSystem = { shown = it }, onRouteAll = {}, onBypassAll = {},
                    onAllowLocalNetwork = {},
                )
            }
        }
        scrollToText("Show system apps")
        compose.onNodeWithText("Show system apps").performClick()
        compose.waitForIdle()
        assertEquals(true, shown)
    }

    @Test
    fun `the Allow local network row toggles the switch callback`() {
        var allowed: Boolean? = null
        setContent(onAllowLocalNetwork = { allowed = it })
        compose.onNodeWithText("Allow local network").performClick()
        compose.waitForIdle()
        assertEquals(true, allowed)
    }

    @Test
    fun `the Allow local network row is one toggleable focus stop`() {
        val allow = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SplitTunnelScreen(
                    state = SplitTunnelUiState(allowLocalNetwork = allow.value),
                    onMode = {}, onToggle = {}, onQuery = {}, onShowSystem = {},
                    onRouteAll = {}, onBypassAll = {},
                    onAllowLocalNetwork = { allow.value = it },
                )
            }
        }
        // The row is the toggle: its switch takes no clicks of its own, so the row is the only
        // toggleable, it carries the label, and no label-less toggleable sits beside it.
        val row = compose.onNode(isToggleable() and hasText("Allow local network"))
        row.assertIsOff()
        row.performClick()
        row.assertIsOn()
        compose.onAllNodes(isToggleable() and SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            .assertCountEquals(0)
    }

    @Test
    fun `the exclusive hint names the local network only while the switch is on`() {
        val allow = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SplitTunnelScreen(
                    state = SplitTunnelUiState(mode = SplitTunnelMode.EXCLUDE, allowLocalNetwork = allow.value),
                    onMode = {},
                    onToggle = {},
                    onQuery = {},
                    onShowSystem = {},
                    onRouteAll = {},
                    onBypassAll = {},
                    onAllowLocalNetwork = { allow.value = it },
                )
            }
        }
        val off = "Everything travels through the encrypted tunnel except the apps you select."
        val on = "Everything travels through the tunnel except the apps you select and your local network."
        compose.onNodeWithText(off).assertIsDisplayed()
        compose.onNodeWithText(on).assertDoesNotExist()
        compose.onNodeWithText("Allow local network").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(on).assertIsDisplayed()
        compose.onNodeWithText(off).assertDoesNotExist()
    }

    @Test
    fun `the inclusive hint ignores the switch`() {
        compose.setContent {
            MaterialTheme {
                SplitTunnelScreen(
                    state = SplitTunnelUiState(mode = SplitTunnelMode.INCLUDE, allowLocalNetwork = true),
                    onMode = {},
                    onToggle = {},
                    onQuery = {},
                    onShowSystem = {},
                    onRouteAll = {},
                    onBypassAll = {},
                    onAllowLocalNetwork = {},
                )
            }
        }
        compose.onNodeWithText("Only the apps you select travel through the tunnel; everything else stays outside.")
            .assertIsDisplayed()
        compose.onNodeWithText(
            "Everything travels through the tunnel except the apps you select and your local network.",
        ).assertDoesNotExist()
    }

    @Test
    fun `a loading list shows progress instead of the empty message`() {
        compose.setContent {
            MaterialTheme {
                SplitTunnelScreen(
                    state = SplitTunnelUiState(apps = emptyList(), loading = true),
                    onMode = {},
                    onToggle = {},
                    onQuery = {},
                    onShowSystem = {},
                    onRouteAll = {},
                    onBypassAll = {},
                    onAllowLocalNetwork = {},
                )
            }
        }
        scrollToText("Loading apps…")
        compose.onNodeWithText("Loading apps…").assertIsDisplayed()
        compose.onNodeWithText("No apps match.").assertDoesNotExist()
    }

    @Test
    fun `a loaded empty list shows the empty message`() {
        compose.setContent {
            MaterialTheme {
                SplitTunnelScreen(
                    state = SplitTunnelUiState(apps = emptyList(), loading = false),
                    onMode = {},
                    onToggle = {},
                    onQuery = {},
                    onShowSystem = {},
                    onRouteAll = {},
                    onBypassAll = {},
                    onAllowLocalNetwork = {},
                )
            }
        }
        scrollToText("No apps match.")
        compose.onNodeWithText("No apps match.").assertIsDisplayed()
    }

    @Test
    fun `an empty inclusive list shows the blocking warning and the restart note is always visible`() {
        compose.setContent {
            MaterialTheme {
                SplitTunnelScreen(
                    state = SplitTunnelUiState(mode = SplitTunnelMode.INCLUDE, includeListEmpty = true),
                    onAllowLocalNetwork = {},
                    onMode = {}, onToggle = {}, onQuery = {}, onShowSystem = {}, onRouteAll = {}, onBypassAll = {},
                )
            }
        }
        compose.onNodeWithText("Inclusive mode needs at least one app. Until you pick one, the previous rules stay in effect.")
            .assertIsDisplayed()
        // The note is always visible, whatever the mode (C9: saving restarts the tunnel,
        // so the copy says connections can leave it briefly).
        compose.onNodeWithText("Saving restarts the tunnel; connections can leave it briefly.")
            .assertIsDisplayed()
    }

    @Test
    fun `the search field carries its label for accessibility`() {
        // F27: the placeholder is a separate Text that goes away once the user types, so the
        // editable node itself carries the label.
        val queries = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SplitTunnelScreen(
                    state = SplitTunnelUiState(),
                    onMode = {}, onToggle = {}, onQuery = { queries += it },
                    onAllowLocalNetwork = {},
                    onShowSystem = {}, onRouteAll = {}, onBypassAll = {},
                )
            }
        }
        compose.onNode(hasSetTextAction() and hasContentDescription("Search installed apps"))
            .performTextInput("Cal")
        compose.runOnIdle { assertEquals("Cal", queries.lastOrNull()) }
    }

    private val fontScale = mutableStateOf(1f)

    @Test
    fun `the app icon's initial ignores the font scale`() {
        // T6: the 36dp box cannot hold a larger letter (F29); the cap was pinned only by a
        // fixture until now. The rule refuses a second setContent per test, so the one
        // composition reads its density from [fontScale] and both scales share it.
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale.value),
                ) {
                    SplitTunnelScreen(
                        state = SplitTunnelUiState(apps = listOf(AppInfo("com.example.z", "Zygisk"))),
                        onMode = {},
                        onToggle = {},
                        onQuery = {},
                        onShowSystem = {},
                        onRouteAll = {},
                        onBypassAll = {},
                        onAllowLocalNetwork = {},
                        iconLoader = AppIconLoader { _, _ -> null },
                    )
                }
            }
        }
        var at1 = 0f
        var at2 = 0f
        showAppAt(1f) { at1 = it }
        showAppAt(2f) { at2 = it }
        assertEquals("the initial grew with the font scale", at1, at2, 0.5f)
    }

    /** One app row at [scale]; reports the initial's laid-out height in px. */
    private fun showAppAt(scale: Float, measure: (Float) -> Unit) {
        compose.runOnIdle { fontScale.value = scale }
        // The row is the list's last item, so at scale 2 it sits below the fold and the lazy
        // list never composes it; scroll it in before measuring.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Zygisk"))
        // G3: the null loader still hops IO → Main before the initial renders, past
        // waitForIdle; wait for it so the measurement never races the empty 36dp box.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Z", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        measure(textLayoutOf("Z").size.height.toFloat())
    }

    private fun textLayoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
