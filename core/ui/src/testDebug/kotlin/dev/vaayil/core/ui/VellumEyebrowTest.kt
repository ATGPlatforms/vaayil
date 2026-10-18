package dev.vaayil.core.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The type-repair contracts (device review 2026-09-21, C4/F1): the 11sp/700/+0.115em
 * eyebrow style renders UPPERCASE ONLY — `VellumEyebrow` must uppercase its input so a
 * mixed-case string can never reach the screen in that style — and the mono face pins
 * at 14sp/20sp with zero tracking so mono digits sit just under the Schibsted cap
 * height instead of running 25% wider than the caps they sit beside.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class VellumEyebrowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun eyebrowUppercasesItsInput() {
        compose.setContent {
            VellumEyebrow("Session totals")
        }
        compose.onNodeWithText("SESSION TOTALS").assertExists()
        // The mixed-case form must never exist in the semantics tree — that is the
        // whole rule: the eyebrow style never renders mixed case.
        compose.onAllNodesWithText("Session totals").assertCountEquals(0)
    }

    @Test
    fun monoTextStylePinsMonoFaceAt14spOver20spWithZeroTracking() {
        // Production bodyMedium is 15sp/23sp/+0.004em; a canary with the same shape
        // proves the copy overrides every field rather than inheriting the body style.
        val canaryBody = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            lineHeight = 23.sp,
            letterSpacing = 0.004.em,
        )
        lateinit var resolved: TextStyle
        compose.setContent {
            MaterialTheme(typography = Typography(bodyMedium = canaryBody)) {
                resolved = monoTextStyle()
            }
        }
        compose.runOnIdle {
            assertEquals("mono face pins at 14sp (DESIGN.md §3)", 14.sp, resolved.fontSize)
            assertEquals("mono leading pins at 20sp", 20.sp, resolved.lineHeight)
            assertEquals("mono tracking is zero", 0.sp, resolved.letterSpacing)
            assertEquals(FontFamily.Monospace, resolved.fontFamily)
        }
    }
}
