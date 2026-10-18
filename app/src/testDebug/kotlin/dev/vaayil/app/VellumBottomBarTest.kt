package dev.vaayil.app

import android.app.Application
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Nav-bar geometry contract (spec §3.4.3 VellumBottomBar, ruling B1; device review
 * C3/B.2): every destination is a full-cell touch target whose label AND indicator
 * capsule keep ≥8dp side margins inside the cell, the capsule is exactly 56×32dp
 * behind the icon only, and the bar's content height is 68dp. At font scale 2.0
 * the labels stay whole on one line and the bar grows to fit them (F29). Measured from
 * semantics bounds at the two shipped window widths — this is the "labels burst
 * through the capsule" guard.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VellumBottomBarTest {
    @get:Rule val compose = createComposeRule()

    private val routes = listOf("dashboard", "profiles", "split-tunnel", "backup")
    private val labels =
        mapOf("dashboard" to "Dashboard", "profiles" to "Profiles", "split-tunnel" to "Split Tunnel", "backup" to "Backup")

    private fun setBar(currentRoute: String): Density {
        var density = Density(1f)
        compose.setContent {
            density = LocalDensity.current
            VaayilTheme.Content { VellumBottomBar(currentRoute) {} }
        }
        compose.waitForIdle()
        return density
    }

    private fun assertNavGeometry() {
        val density = setBar(currentRoute = "dashboard")
        fun pxToDp(px: Float) = with(density) { px.toDp().value }

        routes.forEach { route ->
            val cell = compose.onNodeWithTag("vellum_nav_cell_$route", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithText(labels.getValue(route), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot

            assertTrue(
                "$route cell height ${pxToDp(cell.height)}dp must be 68dp ±0.5",
                abs(pxToDp(cell.height) - 68f) <= 0.5f,
            )
            val labelMargin = pxToDp((cell.width - label.width) / 2f)
            assertTrue(
                "$route label '${labels[route]}' leaves ${labelMargin}dp side margins, needs ≥8dp",
                labelMargin >= 8f,
            )

            val capsule = compose.onNodeWithTag("vellum_nav_capsule_$route", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$route capsule width ${pxToDp(capsule.width)}dp must be 56dp ±0.5",
                abs(pxToDp(capsule.width) - 56f) <= 0.5f,
            )
            assertTrue(
                "$route capsule height ${pxToDp(capsule.height)}dp must be 32dp ±0.5",
                abs(pxToDp(capsule.height) - 32f) <= 0.5f,
            )
            val capsuleMargin = pxToDp((cell.width - capsule.width) / 2f)
            assertTrue(
                "$route capsule leaves ${capsuleMargin}dp side margins, needs ≥8dp",
                capsuleMargin >= 8f,
            )
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun navLabelFitAtW360dp() = assertNavGeometry()

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun navLabelFitAtW600dp() = assertNavGeometry()

    @Test
    fun `the current tab is announced as selected, once`() {
        setBar(currentRoute = "profiles")
        compose.onNode(hasText("Profiles") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Dashboard") and isSelectable()).assertIsNotSelected()
        // The icon's description used to duplicate the label, so TalkBack read
        // "Profiles, Profiles"; the label below names the tab.
        compose.onAllNodesWithContentDescription("Profiles").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `at font scale 2 the labels stay whole on one line and the bar grows`() {
        // F29: at 2.0 the bar showed "Dashbo". The labels cap their font scale at 1.15 and
        // keep one line, and the 68dp floor gives way to the taller label line.
        var density = Density(1f)
        compose.setContent {
            density = LocalDensity.current
            VaayilTheme.Content { AtFontScale(2f) { VellumBottomBar(currentRoute = "split-tunnel") {} } }
        }
        compose.waitForIdle()
        fun pxToDp(px: Float) = with(density) { px.toDp().value }

        compose.assertNoTextClipped("in the bottom bar at font scale 2.0")
        routes.forEach { route ->
            val label = labels.getValue(route)
            val layout = compose.layoutOf(label)
            assertEquals("'$label' lines", 1, layout.lineCount)
            assertFalse("'$label' is cut with an ellipsis", layout.isLineEllipsized(0))

            val cell = compose.onNodeWithTag("vellum_nav_cell_$route", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val margin = pxToDp((cell.width - text.width) / 2f)
            assertTrue("$route label leaves ${margin}dp side margins, needs ≥4dp", margin >= 4f)
            val expected = 8f + 32f + 4f + pxToDp(text.height) + 8f
            assertTrue(
                "$route cell is ${pxToDp(cell.height)}dp, expected ${expected}dp (8 + 32 + 4 + label + 8)",
                abs(pxToDp(cell.height) - expected) <= 1f,
            )
            assertTrue("$route cell stayed ${pxToDp(cell.height)}dp tall", pxToDp(cell.height) > 68.5f)
        }
    }
}
