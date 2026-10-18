package dev.vaayil.core.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
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

/** U2: machine values wrap at break opportunities, and semantics keep the plain value. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MachineValueTextTest {
    @get:Rule val compose = createComposeRule()

    private fun textLayoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test
    fun `the port never splits from its colon`() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(150.dp)) { MachineValueText("vpn.example.net:51820") } }
        }
        compose.waitForIdle()
        // Every segment is an unbroken line (no mid-token break at this width)…
        listOf("vpn.", "example.", "net", ":51820").forEach {
            assertEquals("\"$it\" broke mid-segment", 1, textLayoutOf(it).lineCount)
        }
        // …and the port segment starts the second line.
        val host = compose.onNodeWithText("example.", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val port = compose.onNodeWithText(":51820", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("the port did not wrap to a new line", port.top > host.top)
    }

    @Test
    fun `a value that fits stays on one line`() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(300.dp)) { MachineValueText("vpn.example.net:51820") } }
        }
        compose.waitForIdle()
        val tops = listOf("vpn.", "example.", "net", ":51820").map { segment ->
            compose.onNodeWithText(segment, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("the value wrapped although it fits", tops.all { it == tops.first() })
    }

    @Test
    fun `semantics keep the plain value for TalkBack`() {
        compose.setContent {
            MaterialTheme { MachineValueText("vpn.example.net:51820") }
        }
        compose.waitForIdle()
        val plain = SemanticsMatcher("merged machine value") { node ->
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }
                ?.map(AnnotatedString::text)?.joinToString("") == "vpn.example.net:51820"
        }
        compose.onNode(plain).assertExists()
        // I3: the merged node also carries the plain value as its content description, so
        // TalkBack voices ONE utterance — the value — instead of per-segment fragments.
        assertEquals(
            "the merged node lost its content description",
            listOf("vpn.example.net:51820"),
            compose.onNode(plain).fetchSemanticsNode()
                .config.getOrElseNullable(SemanticsProperties.ContentDescription) { null },
        )
    }
}
