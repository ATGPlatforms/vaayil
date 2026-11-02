package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.vaayil.core.ui.LocalReducedMotion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The VPN prominent disclosure (Y2) at font scale 2.0 on a small phone: its copy must stay whole
 * and its Not now and Accept buttons must stay in view, because they are the dialog's only way
 * out. `w360dp-h580dp` is the room a 360x640dp phone leaves a dialog once the status and
 * navigation bars are drawn; Robolectric never draws them, so a `h640dp` window would hand the
 * dialog about 60dp a phone does not have.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VpnDisclosureFontScaleTest {
    @get:Rule val compose = createComposeRule()

    // AtFontScale only reaches the main window. The dialog has a window of its own, whose
    // density comes from the app's resources, so raise those too.
    @Before fun useLargeText() = RuntimeEnvironment.setFontScale(2f)

    @After fun restoreTextScale() = RuntimeEnvironment.setFontScale(1f)

    private var accepted = 0
    private var notNow = 0

    private fun show() {
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    VpnDisclosureDialog(onAccept = { accepted++ }, onNotNow = { notNow++ })
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The disclosure's one scrollable. The unmerged tree is required: the sheet's tap-absorbing
     * `clickable` merges every descendant it can, so the merged tree reports the sheet.
     */
    private fun scrollNode() = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        useUnmergedTree = true,
    )

    private fun scrollRange() =
        scrollNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    @Test
    @Config(qualifiers = "w360dp-h580dp")
    fun `the disclosure at font scale 1`() {
        RuntimeEnvironment.setFontScale(1f)
        show()
        captureScreenRoboImage("src/testDebug/snapshots/vpn-disclosure-w360dp.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h580dp")
    fun `the disclosure fits at font scale 2 and Accept can be pressed`() {
        show()
        captureScreenRoboImage("src/testDebug/snapshots/vpn-disclosure-font2-w360dp.png")

        compose.assertNoTextClipped("in the VPN disclosure at font scale 2.0", isDialog())

        compose.onNodeWithText("Accept").assertIsDisplayed().performClick()
        assertEquals(1, accepted)
        assertEquals(0, notNow)
    }

    @Test
    @Config(qualifiers = "w360dp-h580dp")
    fun `the disclosure's Not now can be pressed at font scale 2`() {
        show()

        compose.onNodeWithText("Not now").assertIsDisplayed().performClick()
        assertEquals(1, notNow)
        assertEquals(0, accepted)
    }

    @Test
    @Config(qualifiers = "w360dp-h580dp")
    fun `at font scale 2 the disclosure's text scrolls to its end above the buttons`() {
        show()

        val max = scrollRange().maxValue()
        assertTrue("the disclosure does not scroll at font scale 2.0 (range ${max}px)", max > 0f)

        scrollNode().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, max) }
        compose.waitForIdle()

        val scroll = scrollNode().fetchSemanticsNode()
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("scroll position at the end", range.maxValue(), range.value(), 0.5f)

        // The body's last line is the panel's last line of copy; read its box unclipped, because
        // the scroll area clips what it holds.
        val body = compose.onNodeWithText(
            "Next, Android asks you to allow the VPN connection.",
            substring = true,
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val bodyBottom = body.positionInRoot.y + body.size.height
        val scrollBottom = scroll.positionInRoot.y + scroll.size.height
        assertTrue(
            "the body's last line ($bodyBottom) runs past the scroll area's bottom ($scrollBottom)",
            bodyBottom <= scrollBottom + 1f,
        )

        val acceptTop = compose.onNodeWithText("Accept").fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "the scroll area's bottom (${scroll.boundsInRoot.bottom}) is below Accept's top ($acceptTop)",
            scroll.boundsInRoot.bottom <= acceptTop,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h580dp")
    fun `at font scale 2 the disclosure's button labels stay on one line`() {
        show()

        // The dialog's own layouts: `layoutOf` and `assertLinesAtMost` read the default root,
        // which an open dialog makes ambiguous.
        val layouts = compose.allTextLayouts(isDialog())
        assertEquals(
            "Accept's label lines at font scale 2.0",
            1,
            layouts.single { it.text == "Accept" }.layout.lineCount,
        )
        assertEquals(
            "Not now's label lines at font scale 2.0",
            1,
            layouts.single { it.text == "Not now" }.layout.lineCount,
        )
    }

    @Test
    fun `the disclosure's nodes carry the approved copy`() {
        show()
        compose.onNodeWithText(VPN_DISCLOSURE_TITLE).assertExists()
        compose.onNodeWithText(VPN_DISCLOSURE_TEXT).assertExists()
    }
}
