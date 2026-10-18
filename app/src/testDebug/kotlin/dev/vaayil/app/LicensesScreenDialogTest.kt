package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.ui.LocalReducedMotion
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The licence viewer (task-9 review round 1): a full license (Apache-2.0 ≈ 10k
 * chars) must scroll inside the sheet — a plain Column clips it at the window
 * edge and pushes Done off-screen — and Done must stay visible below the body.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
class LicensesScreenDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `license body scrolls and done stays visible`() {
        showLicenses()
        awaitRow("AboutLibraries Core Library")
        compose.onAllNodesWithText("AboutLibraries Core Library")[0].performClick()
        compose.waitForIdle()
        // The Apache-2.0 body is far taller than the bound, so the dialog must own a
        // vertical scroll axis (the pre-fix plain Column had none and clipped). The
        // list behind the scrim owns one scrollable; the bounded dialog body adds its
        // own — this fails with exactly one while the body is an unscrollable Column.
        val scrollableCount = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size
        assertTrue(scrollableCount >= 2)
        // Done rides below the scrollable body, inside the window — never clipped.
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onAllNodesWithText("Apache License", substring = true)[0].assertExists()
    }

    /**
     * C10: the protobuf runtime's repackaged BSD-3-Clause used to render the
     * SPDX <<var;...>> template; the dialog must show the concrete upstream
     * text, with no template residue anywhere on screen.
     */
    @Test
    fun `protobufDialogContainsConcreteBsdText`() {
        showLicenses()
        awaitRow("AboutLibraries Core Library")
        // ~176 rows put the protobuf row off-screen at w360dp-h800dp.
        compose.onAllNodes(hasScrollAction())[0]
            .performScrollToNode(hasText("Preferences External Protobuf"))
        compose.waitForIdle()
        compose.onNodeWithText("Preferences External Protobuf").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Redistribution and use", substring = true)[0].assertExists()
        compose.onAllNodesWithText("Copyright 2008 Google Inc.", substring = true)[0].assertExists()
        compose.onAllNodesWithText("<<var;", substring = true).assertCountEquals(0)
    }

    private fun showLicenses() {
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                VaayilTheme.Content { LicensesScreen() }
            }
        }
    }

    /** rememberLibraries parses off the main thread; poll until rows exist. */
    private fun awaitRow(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            try {
                compose.onAllNodesWithText(text)[0].assertExists()
                return
            } catch (_: AssertionError) {
                Thread.sleep(100)
            }
        }
        compose.onAllNodesWithText(text)[0].assertExists()
    }
}
