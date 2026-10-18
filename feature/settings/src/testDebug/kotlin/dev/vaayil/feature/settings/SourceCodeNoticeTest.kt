package dev.vaayil.feature.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GPL §0's appropriate legal notices live in Settings → About: Vaayil's own copyright,
 * licence and warranty notice with separate Source code and View licence actions,
 * beside the third-party notices row that stays.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SourceCodeNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun screen(
        onOpenLicenses: () -> Unit = {},
        onOpenSourceCode: () -> Unit = {},
        onOpenAppLicense: () -> Unit = {},
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            // The large-text pass renders as if the system font scale were [fontScale]
            // (the same override the app's text-fit tests use).
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                SettingsScreen(
                    ui = SettingsFlags(),
                    automation = AutomationUiState(),
                    appVersion = "1.0.0",
                    onOpenKillSwitchGuide = {},
                    onOpenVpnSettings = {},
                    onOpenPrivacyPolicy = {},
                    onOpenLicenses = onOpenLicenses,
                    onOpenSourceCode = onOpenSourceCode,
                    onOpenAppLicense = onOpenAppLicense,
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

    @Test
    fun `source and licence actions accompany the legal notice`() {
        screen()
        compose.onNodeWithText("Source code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View licence").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copyright © 2026 Ajay R.", substring = true).assertExists()
    }

    @Test
    fun `the legal notice carries the copyright, share right and no-warranty clauses`() {
        screen()
        listOf(
            "Copyright © 2026 Ajay R.",
            "Vaayil is free software",
            "you may share and change it under the GNU General Public License, version 3 or later",
            "It comes with no warranty.",
        ).forEach { clause ->
            compose.onNodeWithText(clause, substring = true).assertExists()
        }
    }

    @Test
    fun `source code calls its own callback once`() {
        var sourceOpens = 0
        var licenceOpens = 0
        screen(onOpenSourceCode = { sourceOpens++ }, onOpenAppLicense = { licenceOpens++ })
        compose.onNodeWithText("Source code").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, sourceOpens)
            assertEquals(0, licenceOpens)
        }
    }

    @Test
    fun `view licence calls its own callback once`() {
        var sourceOpens = 0
        var licenceOpens = 0
        screen(onOpenSourceCode = { sourceOpens++ }, onOpenAppLicense = { licenceOpens++ })
        compose.onNodeWithText("View licence").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, licenceOpens)
            assertEquals(0, sourceOpens)
        }
    }

    @Test
    fun `open-source licences still opens the third-party notices`() {
        var licenceRowOpens = 0
        screen(onOpenLicenses = { licenceRowOpens++ })
        compose.onNodeWithText("Open-source licences").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, licenceRowOpens) }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `the notice and both actions are reachable at 360dp with font scale 2_0`() {
        screen(fontScale = 2f)
        listOf("Source code", "View licence").forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Copyright © 2026 Ajay R.", substring = true).assertExists()
    }
}
