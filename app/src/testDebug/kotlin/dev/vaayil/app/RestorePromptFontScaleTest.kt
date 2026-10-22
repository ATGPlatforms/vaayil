package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelSelections
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.settings.BackupScreen
import dev.vaayil.feature.settings.BackupViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The restore prompt's longest form at font scale 2.0 on a small phone: every line it can
 * show (one added profile, two overwritten, replaced split-tunnel rules and the legacy-rule
 * note) must stay readable, and its Cancel/Restore row must stay on screen.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestorePromptFontScaleTest {
    @get:Rule val compose = createComposeRule()

    // AtFontScale only reaches the main window. The prompt is a Dialog with a window of its
    // own, whose density comes from the app's resources, so raise those too.
    @Before fun useLargeText() = RuntimeEnvironment.setFontScale(2f)

    @After fun restoreTextScale() = RuntimeEnvironment.setFontScale(1f)

    private var savedProfiles: List<Profile>? = null
    private var savedRules: SplitTunnelSelections? = null

    private fun viewModel() = BackupViewModel(
        profiles = MutableStateFlow(emptyList()),
        storedCount = MutableStateFlow(3),
        existingIds = { setOf("p1", "p2") },
        saveProfiles = { savedProfiles = it },
        selections = MutableStateFlow(SplitTunnelSelections.INITIAL),
        saveRules = { savedRules = it },
    )

    private fun openLongestPrompt(vm: BackupViewModel, fontScale: Float = 2f) {
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AtFontScale(fontScale) { BackupScreen(vm = vm, onRestored = {}) }
                }
            }
        }
        compose.waitForIdle()
        vm.restore(LONGEST) {}
        // Decoding hops to Dispatchers.Default and the prompt lands on Main afterwards.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Restore this snapshot?").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    /** The layout of the one text node in the open dialog that renders exactly [text]. */
    private fun dialogLayoutOf(text: String): TextLayoutResult {
        val matches = compose.allTextLayouts(isDialog()).filter { it.text == text }
        assertEquals("text nodes rendering \"${text.take(48)}\" in the dialog", 1, matches.size)
        return matches.single().layout
    }

    /** Every line but the last ends right after a space: no break inside a word. */
    private fun assertBreaksAtWordEdges(layout: TextLayoutResult, text: String) {
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false) - 1
            assertTrue(
                "\"${text.take(48)}\" line ${line + 1} broke inside a word",
                text[end] == ' ',
            )
        }
    }

    private fun assertLongestPromptShown() {
        compose.onNodeWithText("ADDED").assertExists()
        compose.onNodeWithText("1 profile").assertExists()
        compose.onNodeWithText("OVERWRITTEN").assertExists()
        compose.onNodeWithText("2 existing profiles with the same id").assertExists()
        compose.onNodeWithText("SPLIT TUNNEL").assertExists()
        compose.onNodeWithText("Replaced by the snapshot's: Exclusive, 12 apps, Allow local network on")
            .assertExists()
        compose.onNodeWithText(LEGACY_NOTE).assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `the longest restore prompt fits at font scale 2 and Restore can be pressed`() {
        val vm = viewModel()
        openLongestPrompt(vm)
        assertLongestPromptShown()
        compose.assertNoTextClipped("in the restore prompt at font scale 2.0", isDialog())

        captureScreenRoboImage("src/testDebug/snapshots/restore-prompt-font2-w360dp.png")

        compose.onNodeWithText("Restore").assertIsDisplayed().performClick()
        // The save runs in a coroutine: wait for the recorded write, not just an idle frame.
        compose.waitUntil(10_000) { savedProfiles != null }
        assertEquals(listOf("p1", "p2", "p3"), savedProfiles!!.map { it.id })
        assertEquals(12, savedRules?.active?.packages?.size)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `at font scale 2 each summary value breaks only at word edges`() {
        val vm = viewModel()
        openLongestPrompt(vm)

        val added = "1 profile"
        val overwritten = "2 existing profiles with the same id"
        val rules = "Replaced by the snapshot's: Exclusive, 12 apps, Allow local network on"
        assertBreaksAtWordEdges(dialogLayoutOf(added), added)
        assertBreaksAtWordEdges(dialogLayoutOf(overwritten), overwritten)
        assertBreaksAtWordEdges(dialogLayoutOf(rules), rules)
        // Breaking only at word edges proves nothing if a long value never wraps.
        assertTrue(
            "\"${overwritten.take(48)}\" did not wrap (${dialogLayoutOf(overwritten).lineCount} line)",
            dialogLayoutOf(overwritten).lineCount > 1,
        )
        assertTrue(
            "\"${rules.take(48)}\" did not wrap (${dialogLayoutOf(rules).lineCount} line)",
            dialogLayoutOf(rules).lineCount > 1,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `the longest restore prompt fits at font scale 2 and Cancel can be pressed`() {
        val vm = viewModel()
        openLongestPrompt(vm)
        assertLongestPromptShown()
        compose.assertNoTextClipped("in the restore prompt at font scale 2.0", isDialog())

        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertTrue(
            "the prompt is still up",
            compose.onAllNodesWithText("Restore this snapshot?").fetchSemanticsNodes().isEmpty(),
        )
        assertNull(savedProfiles)
        assertNull(savedRules)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `at font scale 1 a short value stays beside its label`() {
        RuntimeEnvironment.setFontScale(1f)
        val vm = viewModel()
        openLongestPrompt(vm, fontScale = 1f)

        // Merged lookups collapse the whole ledger into one node; the text nodes themselves
        // are leaves of the unmerged tree.
        val label = compose.onNodeWithText("ADDED", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("1 profile", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "\"1 profile\" left ADDED's line (value ${value.top}..${value.bottom}, " +
                "label ${label.top}..${label.bottom})",
            value.top < label.bottom && label.top < value.bottom,
        )
        assertTrue(
            "\"1 profile\" did not follow ADDED on its line " +
                "(value.left=${value.left}, label.right=${label.right})",
            value.left >= label.right,
        )
    }
}

/**
 * A v2 envelope carrying every part of the restore prompt: three profiles (two overwrite
 * stored ids), a per-profile legacy rule set the decoder reports as dropped, and top-level
 * split-tunnel rules. A real v2 file never carried the top-level rules, but the decoder reads
 * them in any version, so this is the longest prompt the screen can show.
 */
private val LONGEST = """
    {
      "version": 2,
      "profiles": [
        {"id":"p1","name":"Home","privateKey":"$KEY44","addresses":["10.0.0.2/32"],"dnsServers":["1.1.1.1"],"peerPublicKey":"$KEY44","endpoint":"vpn.example.com:51820","allowedIps":["0.0.0.0/0"]},
        {"id":"p2","name":"Office","privateKey":"$KEY44","addresses":["10.0.0.2/32"],"dnsServers":["1.1.1.1"],"peerPublicKey":"$KEY44","endpoint":"vpn.example.com:51820","allowedIps":["0.0.0.0/0"]},
        {"id":"p3","name":"Cafe","privateKey":"$KEY44","addresses":["10.0.0.2/32"],"dnsServers":["1.1.1.1"],"peerPublicKey":"$KEY44","endpoint":"vpn.example.com:51820","allowedIps":["0.0.0.0/0"],"splitMode":"EXCLUDE","splitPackages":["com.example.browser"]}
      ],
      "splitTunnel": {
        "mode": "EXCLUDE",
        "packages": [
          "com.example.browser","com.example.mail","com.example.maps","com.example.notes",
          "com.example.chat","com.example.camera","com.example.music","com.example.photos",
          "com.example.calendar","com.example.docs","com.example.files","com.example.clock"
        ],
        "allowLocalNetwork": true
      }
    }
""".trimIndent()

private const val KEY44 = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0="
private const val LEGACY_NOTE =
    "Per-app rules in this older snapshot can't be restored. Your current rules stay."
