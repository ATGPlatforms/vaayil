package dev.vaayil.feature.settings

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.AutoConnectRules
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F9: every settings switch row is ONE toggleable that carries the label and the
 * state — TalkBack reads "Connect on boot, off" once, and the switch itself is no
 * longer a second focus stop.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsSwitchSemanticsTest {
    @get:Rule val compose = createComposeRule()

    private fun screen(connectOnBoot: Boolean = false, onConnectOnBoot: (Boolean) -> Unit = {}) {
        compose.setContent {
            SettingsScreen(
                ui = SettingsFlags(),
                automation = AutomationUiState(
                    rules = AutoConnectRules(connectOnBoot = connectOnBoot),
                    permissionState = PermissionState.GRANTED,
                ),
                appVersion = "1.0.0",
                onOpenKillSwitchGuide = {},
                onOpenVpnSettings = {},
                onOpenPrivacyPolicy = {},
                onOpenLicenses = {},
                onOpenSourceCode = {},
                onOpenAppLicense = {},
                onWifiGuard = {},
                onAutoConnectOnMobileData = {},
                onDisconnectOnTrusted = {},
                onConnectOnBoot = onConnectOnBoot,
                onAddTrustedSsid = {},
                onRemoveTrustedSsid = {},
                onExternalControl = {},
                onRegenerateExternalToken = {},
                onOpenLocationSettings = {},
            )
        }
    }

    @Test
    fun `the connect-on-boot row announces label and state and its tap fires once`() {
        var reported: Boolean? = null
        screen(connectOnBoot = false, onConnectOnBoot = { reported = it })
        // One focus stop per app: no Settings switch row may hide a label-less toggleable
        // Switch inside it (the F9 two-stop regression). All five rows are composed here.
        compose.onAllNodes(isToggleable() and SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
            .assertCountEquals(0)
        compose.onNode(hasText("Connect on boot") and isToggleable())
            .performScrollTo().assertIsOff()
        compose.onNode(hasText("Connect on boot") and isToggleable()).performClick()
        assertEquals(true, reported)
    }

    @Test
    fun `the external-control row is toggleable`() {
        screen()
        compose.onNode(hasText("Allow external control") and isToggleable())
            .performScrollTo().assertExists()
    }

    /** J10: lockdown blocks apps' connections, not their DNS lookups, so the row says connections. */
    @Test
    fun `the kill-switch row says Android blocks connections, not traffic`() {
        screen()
        compose.onNodeWithText("Block connections if the tunnel drops").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Block traffic if the tunnel drops").assertCountEquals(0)
    }
}
