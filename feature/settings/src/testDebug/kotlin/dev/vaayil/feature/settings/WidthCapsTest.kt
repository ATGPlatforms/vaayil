package dev.vaayil.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.AutoConnectRules
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G1 (spec §4.1): the Settings 600dp and Privacy 520dp caps must bind before their fills —
 * a centred column on wide screens, the full width on phones.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class WidthCapsTest {
    @get:Rule val compose = createComposeRule()

    /** The demo state of SettingsScreenshotTest's `settings-automation-w360dp` fixture. */
    @Composable
    private fun settingsScreenContent() {
        SettingsScreen(
            ui = SettingsFlags(),
            automation = AutomationUiState(
                rules = AutoConnectRules(wifiGuardEnabled = true, trustedSsids = setOf("Home")),
                permissionState = PermissionState.GRANTED,
            ),
            appVersion = "0.2.0",
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

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun `settings column is capped at 600dp and centred`() {
        compose.setContent { settingsScreenContent() }
        compose.waitForIdle()
        assertCappedAndCentred(tag = "settings-column", capDp = 600f)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp")
    fun `settings column fills a phone`() {
        compose.setContent { settingsScreenContent() }
        compose.waitForIdle()
        assertFillsRoot(tag = "settings-column")
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun `privacy column is capped at 520dp and centred`() {
        // The app hosts this screen inside TabContainer; the host supplies the same
        // top-centre placement so the standalone column's cap and centring are measurable.
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                PrivacyPolicyScreen()
            }
        }
        compose.waitForIdle()
        assertCappedAndCentred(tag = "privacy-column", capDp = 520f)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp")
    fun `privacy column fills a phone`() {
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                PrivacyPolicyScreen()
            }
        }
        compose.waitForIdle()
        assertFillsRoot(tag = "privacy-column")
    }

    @Test
    @Config(qualifiers = "w1000dp-h800dp")
    fun `licence column is capped at 520dp and centred`() {
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                AppLicenseScreen()
            }
        }
        compose.waitForIdle()
        assertCappedAndCentred(tag = "app-license-column", capDp = 520f)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp")
    fun `licence column fills a phone`() {
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                AppLicenseScreen()
            }
        }
        compose.waitForIdle()
        assertFillsRoot(tag = "app-license-column")
    }

    private fun assertCappedAndCentred(tag: String, capDp: Float) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        fun dp(px: Float) = with(compose.density) { px.toDp() }
        assertTrue("$tag width ${dp(node.width).value}dp must be at most ${capDp}dp", dp(node.width).value <= capDp + 1f)
        val left = dp(node.left).value
        val right = dp(root.width - node.right).value
        assertTrue("$tag not centred: $left vs $right", kotlin.math.abs(left - right) <= 1f)
    }

    private fun assertFillsRoot(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        fun dp(px: Float) = with(compose.density) { px.toDp() }
        assertTrue(
            "$tag width ${dp(node.width).value}dp must fill ${dp(root.width).value}dp",
            kotlin.math.abs(dp(node.width).value - dp(root.width).value) <= 1f,
        )
    }
}
