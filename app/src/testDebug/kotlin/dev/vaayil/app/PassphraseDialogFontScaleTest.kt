package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.settings.PassphraseDialog
import dev.vaayil.feature.settings.PassphraseDialogMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The passphrase dialog's two forms at font scale 2.0 on a small phone: every line and field
 * must stay whole, and its buttons must stay on screen. `w360dp-h580dp` is the room a 360x640dp
 * phone leaves a dialog once the status and navigation bars are drawn; Robolectric never draws
 * them, so a `h640dp` window would hand the dialog about 60dp a phone does not have.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PassphraseDialogFontScaleTest {
    @get:Rule val compose = createComposeRule()

    // AtFontScale only reaches the main window. The dialog has a window of its own, whose
    // density comes from the app's resources, so raise those too.
    @Before fun useLargeText() = RuntimeEnvironment.setFontScale(2f)

    @After fun restoreTextScale() = RuntimeEnvironment.setFontScale(1f)

    private var confirmed: String? = null
    private var dismissed = 0

    private fun show(mode: PassphraseDialogMode, error: String? = null) {
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    PassphraseDialog(
                        mode = mode,
                        error = error,
                        onConfirm = { confirmed = String(it) },
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The dialog's one scrollable. The unmerged tree is required: the sheet's tap-absorbing
     * `clickable` merges every descendant it can, so the merged tree reports the sheet.
     */
    private fun scrollNode() = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        useUnmergedTree = true,
    )

    /** A field's editable node; `VellumField` names it with the label shown above the well. */
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasContentDescription(label))

    private fun fill(label: String) {
        field(label).performScrollTo().performTextInput("correct horse")
    }

    private fun scrollRange() =
        scrollNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    @Test
    @Config(qualifiers = "w360dp-h580dp")
    fun `the export dialog at font scale 1`() {
        RuntimeEnvironment.setFontScale(1f)
        show(PassphraseDialogMode.CREATE)
        captureScreenRoboImage("src/testDebug/snapshots/passphrase-export-w360dp.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h580dp")
    fun `the export dialog fits at font scale 2 and Export can be pressed`() {
        show(PassphraseDialogMode.CREATE)
        captureScreenRoboImage("src/testDebug/snapshots/passphrase-export-font2-w360dp.png")

        assertTrue(
            "the dialog does not scroll at font scale 2.0 (range ${scrollRange().maxValue()}px)",
            scrollRange().maxValue() > 0f,
        )

        fill("Passphrase")
        fill("Confirm passphrase")
        compose.assertNoTextClipped("in the passphrase dialog at font scale 2.0", isDialog())

        val minimum = with(compose.density) { 48.dp.toPx() }
        for (label in listOf("Passphrase", "Confirm passphrase")) {
            val height = field(label).fetchSemanticsNode().size.height
            assertTrue("\"$label\" is ${height}px tall at font scale 2.0, under 48dp", height >= minimum)
        }

        compose.onNodeWithText("Export").assertIsDisplayed().performClick()
        assertEquals("correct horse", confirmed)
    }

    @Test
    @Config(qualifiers = "w360dp-h580dp")
    fun `the export dialog's Cancel can be pressed at font scale 2`() {
        show(PassphraseDialogMode.CREATE)

        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        assertEquals(1, dismissed)
        assertNull(confirmed)
    }

    @Test
    @Config(qualifiers = "w360dp-h580dp")
    fun `the unlock dialog with its error fits at font scale 2 and Unlock can be pressed`() {
        show(PassphraseDialogMode.UNLOCK, "Wrong passphrase, or the file is damaged.")

        fill("Passphrase")
        compose.assertNoTextClipped("in the unlock dialog at font scale 2.0", isDialog())
        compose.onNodeWithText("Wrong passphrase, or the file is damaged.").assertExists()

        compose.onNodeWithText("Unlock").assertIsDisplayed().performClick()
        assertEquals("correct horse", confirmed)
    }

    @Test
    @Config(qualifiers = "w360dp-h580dp")
    fun `at font scale 1 the export dialog's last field keeps the room its focus ring draws in`() {
        RuntimeEnvironment.setFontScale(1f)
        show(PassphraseDialogMode.CREATE)

        // A focused field draws its 2dp ring and its lift outside its own box, and the scroll
        // area clips at its edges: the 12dp below the last field is that room. Both boxes are
        // read unclipped, so the check measures the layout, not the viewport's crop.
        val scroll = scrollNode().fetchSemanticsNode()
        val field = field("Confirm passphrase").fetchSemanticsNode()
        val room = scroll.positionInRoot.y + scroll.size.height -
            (field.positionInRoot.y + field.size.height)
        assertEquals(
            "room below Confirm passphrase",
            12f,
            with(compose.density) { room.toDp().value },
            0.5f,
        )
    }
}
