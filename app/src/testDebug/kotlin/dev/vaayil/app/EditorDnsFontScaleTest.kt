package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SecureDnsType
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.EditorState
import dev.vaayil.feature.profiles.ProfileEditorScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #16 (spec §4.3): the editor's DNS sheet at font scale 2.0 on a 360dp phone and on a
 * 332dp one. `PillSegmented` grows and its "Secure DNS" label wraps instead of being cut
 * off, and nothing in the sheet clips. The sheet is an inline part of the editor's scroll,
 * not a window of its own, so `AtFontScale` reaches it; the window is 2000dp tall
 * (`LargeTextScreenshotTest`'s reason), so the whole editor lays out for the clip check.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorDnsFontScaleTest {
    @get:Rule val compose = createComposeRule()

    private val profile = Profile(
        id = "editor-1",
        name = "Home relay",
        interfaceConfig = InterfaceConfig(
            privateKey = "not-a-real-key",
            addresses = listOf("10.0.0.2/32"),
            dnsServers = listOf("1.1.1.1"),
            mtu = 1420,
            listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "server-key-material",
            presharedKey = null,
            endpoint = "vpn.example.net:51820",
            allowedIps = listOf("0.0.0.0/0"),
            persistentKeepalive = 25,
        ),
        dns = DnsPolicy(
            mode = DnsMode.SECURE,
            secureType = SecureDnsType.DOH,
            secureEndpoint = "https://cloudflare-dns.com/dns-query",
        ),
    )

    private fun showEditor() {
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AtFontScale(2f) {
                        ProfileEditorScreen(
                            state = EditorState(profile, emptyList()),
                            onField = {},
                            onSave = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        // The DNS sheet is inline in the editor's one scroll; bring it into the frame.
        compose.onAllNodesWithText("Secure DNS").onFirst().performScrollTo()
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w360dp-h2000dp")
    fun `the DNS sheet fits at font scale 2 on a 360dp phone`() {
        showEditor()
        compose.assertNoTextClipped("in the editor's DNS sheet at 360dp, font scale 2.0")
    }

    @Test
    @Config(qualifiers = "w332dp-h2000dp")
    fun `the DNS sheet fits at font scale 2 on a 332dp phone`() {
        showEditor()
        compose.assertNoTextClipped("in the editor's DNS sheet at 332dp, font scale 2.0")
    }
}
