package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.feature.settings.AutomationUiState
import dev.vaayil.feature.settings.PermissionState
import dev.vaayil.feature.settings.SettingsFlags
import dev.vaayil.feature.settings.SettingsScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/** Free Settings: protection and automation across phone, tablet and large-text sizes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun show(fontScale: Float = 1f, permission: PermissionState = PermissionState.GRANTED) {
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                VaayilTheme.Content {
                    AtFontScale(fontScale) {
                        SettingsScreen(
                            ui = SettingsFlags(),
                            automation = AutomationUiState(
                                rules = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home")),
                                permissionState = permission,
                            ),
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
                            onRegenerateExternalToken = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(fileName: String) {
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/$fileName.png")
    }

    /** Scrolls About's last line into view; the notice's controls must come with it. */
    private fun assertAboutReachable() {
        compose.onNodeWithText("Vaayil 1.0.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Source code").assertIsDisplayed()
        compose.onNodeWithText("View licence").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phone() {
        show()
        capture("settings-automation-w360dp")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneApproximate() {
        show(permission = PermissionState.APPROXIMATE_ONLY)
        capture("settings-approximate-w360dp")
        compose.assertNoTextClipped("on free Settings with only approximate location")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneLocationOff() {
        show(permission = PermissionState.LOCATION_OFF)
        capture("settings-location-off-w360dp")
        compose.assertNoTextClipped("on free Settings with the Location switch off")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneAbout() {
        show()
        assertAboutReachable()
        capture("settings-about-w360dp")
        compose.assertNoTextClipped("on free Settings scrolled to About")
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun tabletMedium() {
        show()
        capture("settings-automation-w600dp")
    }

    @Test
    @Config(qualifiers = "w840dp-h960dp")
    fun tabletLarge() {
        show()
        capture("settings-automation-w840dp")
    }

    @Test
    @Config(qualifiers = "w840dp-h960dp")
    fun tabletLargeAbout() {
        show()
        assertAboutReachable()
        capture("settings-about-w840dp")
        compose.assertNoTextClipped("on free Settings scrolled to About on a tablet")
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-440dpi")
    fun phone440() {
        // Device review C11: the tab at true phone density.
        show()
        capture("settings-automation-d440")
    }

    @Test
    @Config(qualifiers = "w360dp-h2000dp")
    fun phoneAtFontScale2() {
        show(fontScale = 2f)
        capture("settings-automation-font2-w360dp")
        compose.assertNoTextClipped("on free Settings at font scale 2.0")
        // C12: the h2000dp top capture ends mid-list, so a second capture records the rest.
        // The two viewports overlap because the content scrolls less than one viewport;
        // otherwise a band of the list would appear in neither shot.
        val viewport = compose.onNodeWithTag("settings-column").fetchSemanticsNode().size.height
        val headline = { compose.onNodeWithText("Settings").fetchSemanticsNode().positionInRoot.y }
        val atRest = headline()
        compose.onNodeWithText("Vaayil 1.0.0").performScrollTo()
        compose.onNodeWithText("Vaayil 1.0.0").assertIsDisplayed()
        val scrolled = atRest - headline()
        assertTrue(
            "the font-2 captures do not overlap: scrolled $scrolled px in a $viewport px viewport",
            scrolled > 0f && scrolled < viewport,
        )
        capture("settings-automation-font2-bottom-w360dp")
        compose.assertNoTextClipped("on free Settings at font scale 2.0, scrolled to the bottom")
        // The notice's controls and the version stay reachable at font 2.0 too.
        listOf("Source code", "View licence", "Vaayil 1.0.0").forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }
}
