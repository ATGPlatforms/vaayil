package dev.vaayil.app

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.feature.settings.OnboardingScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F26 (spec U4) at F29's large text: the battery step's instructions make it taller. At
 * font scale 2.0, in the window a 360 x 640dp phone leaves under its system bars, the step
 * scrolls, and both of its actions keep their full height.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h568dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingLargeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `at font scale 2 the battery step scrolls to both actions at full height`() {
        compose.setContent {
            VaayilTheme.Content {
                AtFontScale(2f) {
                    OnboardingScreen(onDone = {}, onOpenBatterySettings = {}, batteryOptimizationDisabled = false)
                }
            }
        }
        // The battery step is the last pager page.
        repeat(2) {
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }
        compose.onNodeWithText("Open battery settings").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(52.dp)
        compose.onNodeWithText("I've done this — get started").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        // U3 (PF4): the quiet action's label wraps; the action keeps its 48dp target and
        // nothing clips.
        compose.onNodeWithText("I've done this — get started").performScrollTo().assertIsDisplayed()
        compose.assertNoTextClipped("on the onboarding battery step at font scale 2.0")
    }
}
