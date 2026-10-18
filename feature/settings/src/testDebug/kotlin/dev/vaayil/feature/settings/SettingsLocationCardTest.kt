package dev.vaayil.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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

/** The Wi-Fi guard's per-permission-state automation copy, and the Play prominent disclosure. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsLocationCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun screen(permission: PermissionState, onOpenLocationSettings: () -> Unit) {
        compose.setContent {
            SettingsScreen(
                ui = SettingsFlags(),
                automation = AutomationUiState(
                    rules = AutoConnectRules(wifiGuardEnabled = true),
                    permissionState = permission,
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
                onConnectOnBoot = {},
                onAddTrustedSsid = {},
                onRemoveTrustedSsid = {},
                onExternalControl = {},
                onRegenerateExternalToken = {},
                onOpenLocationSettings = onOpenLocationSettings,
            )
        }
    }

    @Test
    fun `denied state shows the paused copy and opens location settings`() {
        var opened = 0
        screen(PermissionState.DENIED) { opened++ }
        assertTrue(
            compose.onAllNodesWithText("Location permission is off", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithText("Open location settings").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `foreground-only state does not claim the guard runs in the background`() {
        screen(PermissionState.FOREGROUND_ONLY) { }
        assertTrue(
            compose.onAllNodesWithText("Active while Vaayil runs in the background.")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `granted state keeps the background activity line`() {
        screen(PermissionState.GRANTED) { }
        assertTrue(
            compose.onAllNodesWithText("Active while Vaayil runs in the background.")
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `foreground-only state explains the hidden ssid and opens location settings`() {
        var opened = 0
        screen(PermissionState.FOREGROUND_ONLY) { opened++ }
        assertTrue(
            compose.onAllNodesWithText("Allow all the time", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithText("Open location settings").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `approximate-only state asks for precise location and opens the app page`() {
        var opened = 0
        screen(PermissionState.APPROXIMATE_ONLY) { opened++ }
        compose.onNodeWithText(
            "Vaayil needs precise location to see the Wi-Fi name. Turn on Use precise location for Vaayil.",
        ).performScrollTo().assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("Active while Vaayil runs in the background.")
                .fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithText("Open location settings").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `location-off state explains the paused rules and opens the system switch`() {
        var opened = 0
        screen(PermissionState.LOCATION_OFF) { opened++ }
        compose.onNodeWithText(
            "Location is off, so the Wi-Fi rules are paused. Turn it on so Vaayil can see which Wi-Fi you're on.",
        ).performScrollTo().assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("Active while Vaayil runs in the background.")
                .fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithText("Open Location settings").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the disclosure dialog buttons call back and the title copy is verbatim`() {
        var continued = 0
        var notNow = 0
        compose.setContent {
            LocationDisclosureDialog(onContinue = { continued++ }, onNotNow = { notNow++ })
        }
        compose.onNodeWithText("Wi-Fi network access").assertExists()
        assertTrue(
            compose.onAllNodesWithText("never stores your location or sends anything anywhere", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, continued)
        compose.onNodeWithText("Not now").performClick()
        assertEquals(1, notNow)
    }

    @Test
    fun `the trusted networks card says open Wi-Fi never counts as trusted`() {
        screen(PermissionState.GRANTED) { }
        compose.onNodeWithText("Open Wi-Fi networks (no password) never count as trusted.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
