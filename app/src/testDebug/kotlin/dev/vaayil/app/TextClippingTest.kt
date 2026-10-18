package dev.vaayil.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.style.TextOverflow
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
 * The clip checks in `TextClipping.kt` (F29): each kind of cut is reported, and text that
 * fits (wrapped, or cut with an ellipsis on purpose) is not.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextClippingTest {
    @get:Rule val compose = createComposeRule()

    private val long = "A sentence far too long for this narrow box"

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { MaterialTheme { content() } }
        compose.waitForIdle()
    }

    private fun assertOneProblem(kind: String) {
        val problems = compose.textClipProblems()
        assertEquals(problems.toString(), 1, problems.size)
        assertTrue(problems.single(), problems.single().contains(kind))
    }

    @Test
    fun aOneLineTextWiderThanItsBoxIsCutAtTheSide() {
        show { Box(Modifier.width(100.dp)) { Text(long, maxLines = 1, softWrap = false) } }
        assertOneProblem("cut at the side")
    }

    @Test
    fun aWrappedTextTallerThanItsBoxIsCutAtTheBottom() {
        show { Box(Modifier.size(width = 100.dp, height = 20.dp)) { Text(long) } }
        assertOneProblem("cut at the bottom")
    }

    @Test
    fun aTextCutByMaxLinesIsCutAfterItsLastLine() {
        show { Box(Modifier.width(100.dp)) { Text(long, maxLines = 1) } }
        assertOneProblem("cut after line 1")
    }

    @Test
    fun aTextOffTheScreenIsReported() {
        show { Text("Off the edge", modifier = Modifier.offset(x = 400.dp)) }
        assertOneProblem("off the screen")
    }

    @Test
    fun anEllipsizedTextIsNotReported() {
        show { Box(Modifier.width(100.dp)) { Text(long, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        assertEquals(emptyList<String>(), compose.textClipProblems())
        compose.assertEllipsizedOnOneLine(long)
    }

    @Test
    fun aWrappedTextThatFitsIsNotReported() {
        show { Box(Modifier.width(100.dp)) { Text(long) } }
        assertEquals(emptyList<String>(), compose.textClipProblems())
    }
}
