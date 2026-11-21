package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.DnsSplitRule
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/**
 * The editor with every surface of the Vellum restyle on screen at once (spec §3.7):
 * all four sections expanded, secure DNS on DoH (hostname endpoint, so the warning
 * hint block renders), and one split-DNS rule. Advanced starts collapsed, so the
 * test expands it before the capture.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScreenshotTest {
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
            rules = listOf(DnsSplitRule("internal.corp", bypass = true)),
        ),
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun editorFull() = captureFull("editor-full-w360dp")

    @Test
    @Config(qualifiers = "w393dp-h851dp-440dpi")
    fun editor440() = captureFull(
        // Device review C11: every editor surface at true phone density — the
        // 1dp field-well seams and 11sp section eyebrows as they render on hardware.
        "editor-full-d440",
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun editorPrivateDns() {
        // T-8 (spec §3.3): the note under the DNS header names Android's Private DNS host.
        showEditor(privateDnsHost = "dns.google")
        // Scroll to the heading under the note, so the whole note box is in the frame. The pill row
        // below it repeats "Secure DNS"; the heading comes first.
        compose.onAllNodesWithText("Secure DNS").onFirst().performScrollTo()
        compose.onNodeWithText(PRIVATE_DNS_NOTE).assertIsDisplayed()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/editor-private-dns-w360dp.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun editorDnsFont2() {
        // #16 (spec §4.3): at font scale 2.0 "Secure DNS" wraps and the pill grows with it.
        showEditor(fontScale = 2f)
        // Frame the two policy pills: the endpoint label sits right under them, so scrolling to
        // it leaves the wrapped "Secure DNS" pill fully above it.
        compose.onNodeWithText("DoH endpoint (https URL)").performScrollTo()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/editor-dns-font2-w360dp.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun editorScopeFont2() {
        // w39: at font scale 2.0 the two Allowed IPs quick buttons stack, each full width.
        showEditor(fontScale = 2f)
        // Frame both stacked buttons: the DNS sheet's heading below them pulls the pair clear
        // of the screen's bottom edge.
        compose.onAllNodesWithText("Secure DNS").onFirst().performScrollTo()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/editor-scope-font2-w360dp.png")
    }

    private fun showEditor(privateDnsHost: String? = null, fontScale: Float = 1f) {
        compose.setContent {
            VaayilTheme.Content {
                // Deterministic capture: no motion phases mid-frame.
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    val screen: @Composable () -> Unit = {
                        ProfileEditorScreen(
                            state = EditorState(profile, emptyList()),
                            onField = {},
                            onSave = {},
                            privateDnsHost = privateDnsHost,
                        )
                    }
                    if (fontScale == 1f) screen() else AtFontScale(fontScale) { screen() }
                }
            }
        }
    }

    private fun captureFull(fileName: String) {
        showEditor()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/$fileName.png")
    }

    private companion object {
        const val PRIVATE_DNS_NOTE = "Android's Private DNS is set to dns.google. While it is, apps' DNS lookups " +
            "skip this profile's DNS servers, Secure DNS and split-DNS rules."
    }
}
