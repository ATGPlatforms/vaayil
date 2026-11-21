package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * w39: the Allowed IPs quick buttons. Side by side while both labels fit on one line in half
 * the row; otherwise stacked at full width, so a label never breaks inside a word.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScopeButtonsLargeTextTest {
    @get:Rule val compose = createComposeRule()

    private val routeEverything = "Route everything"
    private val splitSubnets = "Split subnets"

    private fun profile(allowedIps: List<String> = listOf("0.0.0.0/0")) = Profile(
        id = "p1",
        name = "Test",
        interfaceConfig = InterfaceConfig("k", listOf("10.5.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("pub", null, "host:51820", allowedIps, null),
    )

    private fun showEditor(
        fontScale: Float = 1f,
        current: MutableState<Profile> = mutableStateOf(profile()),
    ) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    ProfileEditorScreen(
                        state = EditorState(current.value, emptyList()),
                        onField = { f -> current.value = f(current.value) },
                        onSave = {},
                    )
                }
            }
        }
        compose.onNode(hasText(routeEverything) and isSelectable()).performScrollTo()
    }

    private fun chip(label: String) = compose.onNode(hasText(label) and isSelectable())

    /** The selectable group holding both buttons: their nearest semantics ancestor. */
    private fun chipRow() = chip(routeEverything).onParent()

    private fun textLayoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /** Every line but the last ends right after a space: no break inside a word (TabHeaderTest). */
    private fun assertBreaksAtWordEdges(layout: TextLayoutResult, name: String) {
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false) - 1
            assertTrue("line ${line + 1} broke inside a word", name[end] == ' ')
        }
    }

    private fun assertStacked() {
        val row = chipRow().fetchSemanticsNode().boundsInRoot
        val route = chip(routeEverything).fetchSemanticsNode().boundsInRoot
        val split = chip(splitSubnets).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the buttons still share a line (split.top=${split.top}, route.bottom=${route.bottom})",
            split.top >= route.bottom,
        )
        assertEquals("$routeEverything is not as wide as the row", row.width, route.width, 1f)
        assertEquals("$splitSubnets is not as wide as the row", row.width, split.width, 1f)
    }

    @Test
    fun `the quick buttons stack at font scale 2`() {
        showEditor(fontScale = 2f)
        assertEquals("$routeEverything broke inside a word", 1, textLayoutOf(routeEverything).lineCount)
        assertEquals("$splitSubnets broke inside a word", 1, textLayoutOf(splitSubnets).lineCount)
        assertStacked()
    }

    @Test
    @Config(qualifiers = "w332dp-h2000dp")
    fun `the stacked labels never break inside a word on a narrow phone`() {
        showEditor(fontScale = 2f)
        assertBreaksAtWordEdges(textLayoutOf(routeEverything), routeEverything)
        assertBreaksAtWordEdges(textLayoutOf(splitSubnets), splitSubnets)
        assertStacked()
    }

    @Test
    fun `the quick buttons stay side by side at font scale 1`() {
        showEditor()
        val row = chipRow().fetchSemanticsNode().boundsInRoot
        val route = chip(routeEverything).fetchSemanticsNode().boundsInRoot
        val split = chip(splitSubnets).fetchSemanticsNode().boundsInRoot
        assertEquals("the buttons are not on one line", route.top, split.top, 1f)
        assertTrue(
            "$routeEverything is wider than half the row (${route.width} > ${row.width / 2})",
            route.width <= row.width / 2 + 1f,
        )
        assertTrue(
            "$splitSubnets is wider than half the row (${split.width} > ${row.width / 2})",
            split.width <= row.width / 2 + 1f,
        )
    }

    @Test
    fun `stacked buttons keep their radio semantics`() {
        val current = mutableStateOf(profile())
        showEditor(fontScale = 2f, current = current)
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        chip(routeEverything).assert(radio)
        chip(splitSubnets).assert(radio)
        chipRow().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        chip(splitSubnets).performClick()
        chip(splitSubnets).assertIsSelected()
        assertEquals(
            listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"),
            current.value.peer.allowedIps,
        )
    }
}
