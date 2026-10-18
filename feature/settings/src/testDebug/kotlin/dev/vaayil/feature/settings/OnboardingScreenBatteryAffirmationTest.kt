package dev.vaayil.feature.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Returning from the system battery screen used to confirm nothing (known-issue): with the
 * exemption in hand the battery card now affirms it (sage check + copy); before it, the card
 * keeps its prompt and shows no affirmation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class OnboardingScreenBatteryAffirmationTest {
    @get:Rule val compose = createComposeRule()

    private val batteryListHelp =
        "Android opens a list of apps. Switch the list to All apps, tap Vaayil, then choose Don't optimize."

    private fun setContent(batteryOptimizationDisabled: Boolean) {
        compose.setContent {
            MaterialTheme {
                OnboardingScreen(
                    onDone = {},
                    onOpenBatterySettings = {},
                    batteryOptimizationDisabled = batteryOptimizationDisabled,
                )
            }
        }
        // The battery card is the last pager page; swipe through the intro pages to reach it.
        repeat(2) {
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }
    }

    @Test
    fun `next advances from the first step to the second`() {
        compose.setContent {
            MaterialTheme {
                OnboardingScreen(
                    onDone = {},
                    onOpenBatterySettings = {},
                    batteryOptimizationDisabled = false,
                )
            }
        }
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(
            "Android will ask you to allow VPN connections — this is normal",
        ).assertIsDisplayed()
    }

    @Test
    fun `battery card keeps prompt without affirmation while optimization is on`() {
        setContent(batteryOptimizationDisabled = false)
        compose.onNodeWithText("Open battery settings").assertIsDisplayed()
        compose.onNodeWithText(
            "Battery optimization is off — Vaayil can keep a stable background connection.",
        ).assertDoesNotExist()
    }

    @Test
    fun `battery card affirms optimization is off when handled`() {
        setContent(batteryOptimizationDisabled = true)
        compose.onNodeWithText(
            "Battery optimization is off — Vaayil can keep a stable background connection.",
        ).assertIsDisplayed()
        // P2: the exemption in hand replaces the prompt primary, it doesn't join it.
        compose.onNodeWithText("Open battery settings").assertDoesNotExist()
        compose.onNodeWithText("I've done this — get started").assertIsDisplayed()
        // The list instructions go with the prompt.
        compose.onNodeWithText(batteryListHelp).assertDoesNotExist()
    }

    @Test
    fun `the battery prompt says how to find Vaayil in the system list`() {
        // F26: the button opens the system's list of apps, not a Vaayil page (the per-app
        // dialog needs a Play-restricted permission), so the card says how to find Vaayil there.
        setContent(batteryOptimizationDisabled = false)
        compose.onNodeWithText(batteryListHelp).assertIsDisplayed()
        compose.onNodeWithText("Open battery settings").assertIsDisplayed()
    }
}
