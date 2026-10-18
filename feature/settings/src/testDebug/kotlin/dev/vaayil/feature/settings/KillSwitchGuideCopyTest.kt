package dev.vaayil.feature.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C9 and J10: the guide says Android is what blocks apps' connections (Vaayil only asks for the switches), what an
 * app excluded in Split Tunnel loses, and that Android doesn't block DNS lookups (J10).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h2000dp")
class KillSwitchGuideCopyTest {
    @get:Rule val compose = createComposeRule()

    private val intro =
        "Android can block apps' connections whenever Vaayil isn't connected. Turn these on in Android settings:"
    private val close =
        "With these on, Android blocks apps' connections if the tunnel drops, and Vaayil reconnects after a reboot. " +
            "Apps you exclude in Split Tunnel can't connect at all. Android doesn't block DNS lookups, though: " +
            "while the tunnel is down, and for excluded apps, the network you're on can still see the names apps look up."

    @Test
    fun `the guide says Android blocks connections but not DNS lookups`() {
        compose.setContent {
            MaterialTheme {
                KillSwitchGuideScreen()
            }
        }
        compose.onNodeWithText(intro).assertIsDisplayed()
        compose.onNodeWithText(close).assertIsDisplayed()
        compose.onAllNodesWithText("block traffic", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("blocks traffic", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("reach the network at all", substring = true).assertCountEquals(0)
    }
}
