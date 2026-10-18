package dev.vaayil.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.feature.settings.PrivacyPolicyScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * G1 (spec §4.1): the tab-content cap must bind before the fill so wide screens see a
 * centred 720dp column, and a narrower long-form screen hosted in the container (the
 * privacy policy, cap 520dp) must still be centred rather than hugging the band's edge.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabContainerWidthTest {
    @get:Rule val compose = createComposeRule()

    private fun showContent() {
        compose.setContent {
            VaayilTheme.Content {
                TabContainer { Box(Modifier.fillMaxWidth().height(8.dp).testTag("tab-content")) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun `at 1000dp the tab content is capped at 720dp and centred`() {
        showContent()
        val content = compose.onNodeWithTag("tab-content").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        fun dp(px: Float) = with(compose.density) { px.toDp() }
        assertTrue(
            "content width ${dp(content.width).value}dp must be at most 720dp",
            dp(content.width).value <= 720f + 1f,
        )
        val left = dp(content.left).value
        val right = dp(root.width - content.right).value
        assertTrue("not centred: left $left dp vs right $right dp", kotlin.math.abs(left - right) <= 1f)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp")
    fun `at phone width the tab content fills the screen`() {
        showContent()
        val content = compose.onNodeWithTag("tab-content").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        fun dp(px: Float) = with(compose.density) { px.toDp() }
        assertTrue(
            "content width ${dp(content.width).value}dp must fill ${dp(root.width).value}dp",
            kotlin.math.abs(dp(content.width).value - dp(root.width).value) <= 1f,
        )
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun `at 1000dp a narrow long-form screen is centred inside the tab container`() {
        compose.setContent {
            VaayilTheme.Content {
                TabContainer { PrivacyPolicyScreen() }
            }
        }
        compose.waitForIdle()
        val column = compose.onNodeWithTag("privacy-column").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        fun dp(px: Float) = with(compose.density) { px.toDp() }
        assertTrue(
            "privacy column width ${dp(column.width).value}dp must be at most 520dp",
            dp(column.width).value <= 520f + 1f,
        )
        val left = dp(column.left).value
        val right = dp(root.width - column.right).value
        assertTrue("privacy column not centred: $left vs $right", kotlin.math.abs(left - right) <= 1f)
    }
}
