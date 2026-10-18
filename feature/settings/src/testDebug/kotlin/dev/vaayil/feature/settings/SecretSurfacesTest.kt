package dev.vaayil.feature.settings

import android.app.Application
import android.content.ClipboardManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Every surface that renders a secret marks the window secure, and the token clip is sensitive (S5). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SecretSurfacesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun secure() = (compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    @Test
    fun `the passphrase dialog marks the window secure`() {
        compose.setContent {
            MaterialTheme { PassphraseDialog(mode = PassphraseDialogMode.CREATE, error = null, onConfirm = {}, onDismiss = {}) }
        }
        compose.runOnIdle { assertTrue(secure()) }
    }

    @Test
    fun `the token block marks the window secure and copies a sensitive clip`() {
        val token = "tok-1234567890"
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    ui = SettingsFlags(),
                    automation = AutomationUiState(
                        rules = AutoConnectRules(externalControlEnabled = true, externalControlToken = token),
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
                    onConnectOnBoot = {},
                    onAddTrustedSsid = {},
                    onRemoveTrustedSsid = {},
                    onExternalControl = {},
                    onRegenerateExternalToken = {},
                    onOpenLocationSettings = {},
                )
            }
        }
        compose.runOnIdle { assertTrue(secure()) }
        compose.onNodeWithText("Copy token").performScrollTo().performClick()
        compose.runOnIdle {
            val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
            assertEquals(token, clip.getItemAt(0).text.toString())
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        }
    }
}
