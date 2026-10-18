package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The ordinary typo flow: 'a' typed onto a valid MTU must leave the raw draft ('1420a') in the
 * field with the inline "Must be a number" error — never silently blank it — and (U1) block
 * Save until the draft parses again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ProfileEditorScreenNumericFieldTest {
    @get:Rule val compose = createComposeRule()

    private fun profile(mtu: Int?) = Profile(
        id = "p1",
        name = "Test",
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), mtu, null),
        peer = PeerConfig("pub", null, "host:51820", listOf("0.0.0.0/0"), null),
    )

    @Test
    fun `mtu field keeps unparseable draft and shows inline error`() {
        val current = androidx.compose.runtime.mutableStateOf(profile(mtu = 1420))
        compose.setContent {
            MaterialTheme {
                ProfileEditorScreen(
                    state = EditorState(current.value, emptyList()),
                    onField = { f -> current.value = f(current.value) },
                    onSave = {},
                )
            }
        }
        // MTU lives in the collapsed Advanced section; the typo flow starts by expanding it.
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("1420").performScrollTo().performClick()
        compose.onNodeWithText("1420").performTextInput("a")
        compose.onNodeWithText("1420a").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Must be a number").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `unparseable numeric draft disables save until fixed`() {
        val current = androidx.compose.runtime.mutableStateOf(profile(mtu = 1420))
        compose.setContent {
            MaterialTheme {
                ProfileEditorScreen(
                    state = EditorState(current.value, emptyList()),
                    onField = { f -> current.value = f(current.value) },
                    onSave = {},
                )
            }
        }
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("1420").performScrollTo().performClick()
        compose.onNodeWithText("1420").performTextInput("a")
        compose.onNodeWithText("Save Profile").performScrollTo().assert(isNotEnabled()) // U1: null-persist path blocked
        // Fix the draft by replacing it in place: the label lives ABOVE the well now, so the
        // editable node is matched by its own text, not the label's.
        compose.onNodeWithText("1420a").performScrollTo().performTextReplacement("1420")
        compose.onNodeWithText("Save Profile").performScrollTo().assert(isEnabled())
    }

    @Test
    fun `collapsing advanced after a typo leaves the model at the pre-typo number`() {
        val current = androidx.compose.runtime.mutableStateOf(profile(mtu = 1420))
        compose.setContent {
            MaterialTheme {
                ProfileEditorScreen(
                    state = EditorState(current.value, emptyList()),
                    onField = { f -> current.value = f(current.value) },
                    onSave = {},
                )
            }
        }
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("1420").performScrollTo().performClick()
        compose.onNodeWithText("1420").performTextInput("a")
        // The typo draft must not reach the model — the section's collapse re-enables Save, so a
        // null pushed by that keystroke would be persisted as an unset MTU.
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1420, current.value.interfaceConfig.mtu) }
    }

    @Test
    fun `collapsing advanced clears a numeric format error so save is usable again`() {
        val current = androidx.compose.runtime.mutableStateOf(profile(mtu = 1420))
        compose.setContent {
            MaterialTheme {
                ProfileEditorScreen(
                    state = EditorState(current.value, emptyList()),
                    onField = { f -> current.value = f(current.value) },
                    onSave = {},
                )
            }
        }
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("1420").performScrollTo().performClick()
        compose.onNodeWithText("1420").performTextInput("a")
        compose.onNodeWithText("Save Profile").performScrollTo().assert(isNotEnabled())
        compose.onNodeWithText("Advanced").performScrollTo().performClick() // collapse: the field leaves composition
        compose.onNodeWithText("Save Profile").performScrollTo().assert(isEnabled())
    }
}
