package dev.vaayil.core.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared tab header (device review 2026-09-21, C9/X1): one header construction
 * for every tab. Beyond rendering, the contract that matters is the action slot —
 * the header must compose the caller's action and let it keep its own click target
 * (the Profiles "Add Profile" menu), and a null intro must render the title alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersTitleIntroAndFiresTheAction() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                TabHeader(
                    title = "VPN Profiles",
                    intro = "3 tunnels configured • 1 active",
                    action = {
                        Text(
                            "Add Profile",
                            modifier = Modifier.clickable { clicks++ },
                        )
                    },
                )
            }
        }
        compose.onNodeWithText("VPN Profiles").assertIsDisplayed()
        compose.onNodeWithText("3 tunnels configured • 1 active").assertIsDisplayed()
        compose.onNodeWithText("Add Profile").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun nullIntroRendersTitleOnly() {
        compose.setContent {
            MaterialTheme {
                Box {
                    TabHeader(title = "Backup & Restore", intro = null)
                }
            }
        }
        compose.onNodeWithText("Backup & Restore").assertIsDisplayed()
    }

    @Test
    fun numericRhythmPins12Title4IntroAnd16Content() {
        // Device review C9/X1: the ONE header grid — title 12dp below the top bar,
        // 4dp to the intro, 16dp from the intro to the tab's content. The goldens
        // carry this visually; this test pins it numerically via semantics bounds.
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.testTag("header-host")) {
                    TabHeader(title = "VPN Profiles", intro = "3 tunnels configured • 1 active")
                }
            }
        }
        val host = compose.onNodeWithTag("header-host").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("VPN Profiles").fetchSemanticsNode().boundsInRoot
        val intro = compose.onNodeWithText("3 tunnels configured • 1 active").fetchSemanticsNode().boundsInRoot
        fun dp(value: Int) = with(compose.density) { value.dp.toPx() }
        assertEquals(dp(12), title.top - host.top, 0.5f)
        assertEquals(dp(4), intro.top - title.bottom, 0.5f)
        assertEquals(dp(16), host.bottom - intro.bottom, 0.5f)
    }

    private fun titleLayout(title: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test
    fun `a title that cannot share a line moves the action below and breaks at spaces`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    TabHeader(
                        title = "Internationalisation Profiles",
                        intro = null,
                        action = { VellumButton(label = "Add Profile", onClick = {}) },
                    )
                }
            }
        }
        compose.waitForIdle()
        val action = compose.onNodeWithText("Add Profile").fetchSemanticsNode().boundsInRoot
        val titleBox = compose.onNodeWithText("Internationalisation Profiles").fetchSemanticsNode().boundsInRoot
        assertTrue("the action did not move under the title", action.top >= titleBox.bottom - 1f)
        val layout = titleLayout("Internationalisation Profiles")
        assertTrue("the title stayed on one line", layout.lineCount >= 2)
        // No line breaks inside a word: every line but the last ends right after a space.
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false) - 1
            assertTrue(
                "line ${line + 1} broke inside a word",
                "Internationalisation Profiles"[end] == ' ',
            )
        }
    }

    @Test
    fun `a title that fits keeps its action on the title line`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    TabHeader(
                        title = "VPN Profiles",
                        intro = null,
                        action = { VellumButton(label = "Add Profile", onClick = {}) },
                    )
                }
            }
        }
        compose.waitForIdle()
        val action = compose.onNodeWithText("Add Profile").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("VPN Profiles").fetchSemanticsNode().boundsInRoot
        // Same line: the action starts above the title's bottom edge. (The button is 52dp
        // tall and baseline-aligned, so its bottom legitimately hangs below the 32dp title
        // line — only its top proves it did not drop; a dropped action starts at that edge.)
        assertTrue("the action dropped below a fitting title", action.top < title.bottom)
    }
}
