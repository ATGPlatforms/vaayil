package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.EditorState
import dev.vaayil.feature.profiles.ProfileEditorScreen
import dev.vaayil.feature.tunnel.DashboardScreen
import dev.vaayil.feature.tunnel.HomeUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T-8 (spec §3.2, §3.3): the Private DNS warning at font scale 2.0 on the narrowest phone
 * width. The body wraps without clipping and only at word edges — the hostname aside, which
 * is the one value allowed to split — and both of the card's actions stay reachable. The
 * window is 2000dp tall (`LargeTextScreenshotTest`'s reason), so the whole surface lays out.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateDnsLargeTextTest {
    @get:Rule val compose = createComposeRule()

    private val home = Profile(
        id = "p1", name = "Home relay",
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("K", null, "vpn.example.net:51820", listOf("0.0.0.0/0"), 25),
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AtFontScale(2f) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the dashboard card fits at font scale 2`() {
        show {
            DashboardScreen(
                ui = HomeUiState(
                    status = TunnelStatus(profileId = home.id, phase = ConnectionPhase.CONNECTED, error = null),
                    stats = TunnelStats(
                        handshakeAt = System.currentTimeMillis() - 14_000,
                        rxBytes = 1_523_000_000,
                        txBytes = 403_000_000,
                    ),
                    activeProfile = home,
                    profiles = listOf(home),
                    handshakeState = HandshakeState.VERIFIED,
                    privateDnsHost = LONG_HOST,
                    vpnHasDnsServer = true,
                ),
                selected = home,
                onSelectProfile = {},
                onToggle = {},
                onOpenProfiles = {},
            )
        }
        compose.assertNoTextClipped("dashboard Private DNS card at font scale 2")
        val body = dashboardBody(LONG_HOST)
        val at = body.indexOf(LONG_HOST)
        assertBreaksAtWordEdges(compose.layoutOf(body), body, except = at until at + LONG_HOST.length)
        compose.onNodeWithText("Open network settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Got it").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the editor note fits at font scale 2`() {
        show {
            ProfileEditorScreen(
                state = EditorState(home, emptyList()),
                onField = {},
                onSave = {},
                privateDnsHost = LONG_HOST,
            )
        }
        val note = editorNote(LONG_HOST)
        // The note itself: the editor's other labels are outside T-8.
        compose.assertNoTextClipped("editor Private DNS note at font scale 2", root = hasText(note))
        val at = note.indexOf(LONG_HOST)
        assertBreaksAtWordEdges(compose.layoutOf(note), note, except = at until at + LONG_HOST.length)
    }

    private companion object {
        const val LONG_HOST = "security-filter-dns.cleanbrowsing.org"

        fun dashboardBody(host: String) = "Android's Private DNS is set to $host, so apps send their DNS lookups " +
            "there, not to the connected profile's DNS. If the tunnel can't reach that provider, they fail. To " +
            "use the profile's DNS, set Private DNS to Automatic or Off."

        fun editorNote(host: String) = "Android's Private DNS is set to $host. While it is, apps' DNS lookups " +
            "skip this profile's DNS servers, Secure DNS and split-DNS rules."
    }
}
