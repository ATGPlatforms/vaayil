package dev.vaayil.core.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** G1 (spec §4.1): the lifted dialog sheet's 560dp cap must bind before the fill. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ScrimDialogWidthTest {
    @get:Rule val compose = createComposeRule()

    private fun showDialog() {
        compose.setContent {
            MaterialTheme {
                ScrimDialog(onDismissRequest = {}, modifier = Modifier.testTag("scrim-sheet")) {
                    Text("Body text")
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun `the sheet is capped at 560dp and centred`() {
        showDialog()
        val sheetNode = compose.onNodeWithTag("scrim-sheet").fetchSemanticsNode()
        val sheet = sheetNode.boundsInRoot
        // onRoot() sees two roots while the dialog window is up; the sheet's own root is
        // the dialog window, which is what the scrim (and so the centring) spans.
        val root = generateSequence(sheetNode) { it.parent }.last().boundsInRoot
        fun dp(px: Float) = with(compose.density) { px.toDp() }
        assertTrue("sheet width ${dp(sheet.width).value}dp must be at most 560dp", dp(sheet.width).value <= 560f + 1f)
        val left = dp(sheet.left).value
        val right = dp(root.width - sheet.right).value
        assertTrue("not centred: $left vs $right", kotlin.math.abs(left - right) <= 1f)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp")
    fun `at phone width the sheet takes the available width`() {
        showDialog()
        val sheet = compose.onNodeWithTag("scrim-sheet").fetchSemanticsNode().boundsInRoot
        fun dp(px: Float) = with(compose.density) { px.toDp() }
        // 411dp − 2 × 24dp scrim padding.
        assertTrue("sheet width ${dp(sheet.width).value}dp must be 363dp", kotlin.math.abs(dp(sheet.width).value - 363f) <= 1f)
    }
}
