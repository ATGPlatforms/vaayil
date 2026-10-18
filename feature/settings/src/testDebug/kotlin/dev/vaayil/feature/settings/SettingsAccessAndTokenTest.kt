package dev.vaayil.feature.settings

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.AutoConnectRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings exposes free protection and automation; rotating the control token still asks first (F10). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsAccessAndTokenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun screen(
        automation: AutomationUiState = AutomationUiState(),
        onRegenerateExternalToken: () -> Unit = {},
    ) {
        compose.setContent {
            SettingsScreen(
                ui = SettingsFlags(),
                automation = automation,
                appVersion = "1.0.0",
                onOpenKillSwitchGuide = {},
                onOpenVpnSettings = {},
                onOpenPrivacyPolicy = {},
                onOpenLicenses = {},
                onOpenSourceCode = {},
                onOpenAppLicense = {},
                onOpenLocationSettings = {},
                onWifiGuard = {},
                onAutoConnectOnMobileData = {},
                onDisconnectOnTrusted = {},
                onConnectOnBoot = {},
                onAddTrustedSsid = {},
                onRemoveTrustedSsid = {},
                onExternalControl = {},
                onRegenerateExternalToken = onRegenerateExternalToken,
            )
        }
    }

    @Test
    fun `Settings starts with Protection and has no License trial or Buy section`() {
        screen()
        compose.onNodeWithText("PROTECTION").assertIsDisplayed()
        // C12: Protection stays the first section, above Automation and About.
        val protectionY = compose.onNodeWithText("PROTECTION").fetchSemanticsNode().positionInRoot.y
        val automationY = compose.onNodeWithText("AUTOMATION").fetchSemanticsNode().positionInRoot.y
        val aboutY = compose.onNodeWithText("ABOUT").fetchSemanticsNode().positionInRoot.y
        assertTrue(
            "Protection is not the first section ($protectionY, $automationY, $aboutY)",
            protectionY < automationY && protectionY < aboutY,
        )
        compose.onAllNodesWithText("LICENSE", ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("trial", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Buy", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("purchase", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun `New token asks before replacing`() {
        var regenerated = 0
        screen(
            automation = AutomationUiState(
                rules = AutoConnectRules(
                    externalControlEnabled = true,
                    externalControlToken = "t".repeat(43),
                ),
            ),
            onRegenerateExternalToken = { regenerated++ },
        )
        compose.onNodeWithText("New token").performScrollTo().performClick()
        assertEquals(0, regenerated)
        compose.onNodeWithText("Replace the token?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, regenerated)
        compose.onNodeWithText("Replace the token?").assertDoesNotExist()
        compose.onNodeWithText("New token").performScrollTo().performClick()
        compose.onNodeWithText("Replace token").performClick()
        assertEquals(1, regenerated)
    }
}
