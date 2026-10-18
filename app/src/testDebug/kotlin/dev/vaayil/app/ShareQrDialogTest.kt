package dev.vaayil.app

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C5: a serializable profile whose config is past QR capacity (byte mode tops out at 2,953
 * bytes at the encoder's L level) must show the export advice instead of letting the encoder's
 * WriterException escape the composition; every other dialog state stays as it was, and the
 * window stays secure either way.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
class ShareQrDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val home = Profile(
        "p1", "Home",
        InterfaceConfig(
            "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            listOf("10.0.0.2/32"),
            listOf("1.1.1.1"),
            null,
            null,
        ),
        PeerConfig("KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=", null, "vpn.example.com:51820", listOf("0.0.0.0/0"), null),
    )

    /** 400 addresses serialize past 4,000 bytes: far beyond any QR code's byte-mode capacity. */
    private fun oversized(): Profile = home.copy(
        interfaceConfig = home.interfaceConfig.copy(addresses = List(400) { "10.0.0.1/32" }),
    )

    private fun show(profile: Profile, onDismiss: () -> Unit = {}) {
        compose.setContent {
            VaayilTheme.Content { ShareQrDialog(profile = profile, onDismiss = onDismiss) }
        }
    }

    @Test
    fun `an oversized config shows the export advice instead of a QR code`() {
        var dismissed = false
        show(oversized()) { dismissed = true }
        compose.onNodeWithText("This profile is too large for a QR code. Use Export .conf instead.")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("QR code for Home").assertDoesNotExist()
        // The private key is still on screen even when its QR cannot render (S-5, A-3).
        val flags = compose.activity.window.attributes.flags
        assertTrue("activity window lost FLAG_SECURE", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun `a normal config still renders its QR code`() {
        show(home)
        compose.onNodeWithContentDescription("QR code for Home").assertIsDisplayed()
        compose.onNodeWithText("This profile is too large for a QR code. Use Export .conf instead.")
            .assertDoesNotExist()
        compose.onNodeWithText("This profile can't be serialized.").assertDoesNotExist()
    }

    @Test
    fun `a config that cannot be serialized keeps its own message`() {
        // A legacy row can hold a line break; the serializer refuses to emit it.
        val legacy = home.copy(interfaceConfig = home.interfaceConfig.copy(privateKey = "eCyy\n0="))
        show(legacy)
        compose.onNodeWithText("This profile can't be serialized.").assertIsDisplayed()
        compose.onNodeWithText("This profile is too large for a QR code. Use Export .conf instead.")
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("QR code for Home").assertDoesNotExist()
    }
}
