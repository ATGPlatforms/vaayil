package dev.vaayil.feature.tunnel

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.ui.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** T-8 (spec §3.2): the dashboard card's copy, its place on the board, and its two actions. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h2000dp")
class PrivateDnsCardTest {
    @get:Rule val compose = createComposeRule()

    private val home = Profile(
        id = "home", name = "Home relay",
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("K", null, "vpn.example.net:51820", listOf("0.0.0.0/0"), 25),
    )
    private val office = Profile(
        id = "office", name = "Office relay",
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.3/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("K", null, "vpn.example.org:51820", listOf("0.0.0.0/0"), 25),
    )

    private val connectedUi = HomeUiState(
        status = TunnelStatus(ConnectionPhase.CONNECTED, "home"),
        activeProfile = home,
        profiles = listOf(home, office),
        handshakeState = HandshakeState.VERIFIED,
        privateDnsHost = "dns.google",
        vpnHasDnsServer = true,
    )

    private fun show(
        ui: HomeUiState = connectedUi,
        selected: Profile = home,
        onOpenNetworkSettings: () -> Unit = {},
        onDismissPrivateDns: (String) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    DashboardScreen(
                        ui = ui,
                        selected = selected,
                        onSelectProfile = {},
                        onToggle = {},
                        onOpenProfiles = {},
                        onOpenNetworkSettings = onOpenNetworkSettings,
                        onDismissPrivateDns = onDismissPrivateDns,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the card shows its copy with the host in monospace`() {
        show()
        compose.onNodeWithText(BODY).assertIsDisplayed()
        compose.onNodeWithText("Open network settings").assertIsDisplayed()
        compose.onNodeWithText("Got it").assertIsDisplayed()
        val text = compose.onNodeWithText(BODY).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        val mono = text.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(1, mono.size)
        assertEquals("dns.google", text.text.substring(mono.single().start, mono.single().end))
    }

    @Test
    fun `the card sits between the hero and session totals`() {
        show()
        val hint = compose.onNodeWithText("Tap the disc to disconnect").getBoundsInRoot()
        val body = compose.onNodeWithText(BODY).getBoundsInRoot()
        val totals = compose.onNodeWithText("SESSION TOTALS").getBoundsInRoot()
        assertTrue("hint ends at ${hint.bottom}, card starts at ${body.top}", hint.bottom <= body.top)
        assertTrue("card ends at ${body.bottom}, totals start at ${totals.top}", body.bottom <= totals.top)
    }

    @Test
    fun `the card shows while another profile is picked`() {
        show(selected = office)
        compose.onNodeWithText(BODY).assertIsDisplayed()
    }

    @Test
    fun `each action calls back once per tap`() {
        var opens = 0
        val dismissed = mutableListOf<String>()
        show(onOpenNetworkSettings = { opens++ }, onDismissPrivateDns = { dismissed += it })
        compose.onNodeWithText("Open network settings").performClick()
        assertEquals(1, opens)
        assertTrue(dismissed.isEmpty())
        compose.onNodeWithText("Got it").performClick()
        assertEquals(listOf("dns.google"), dismissed)
        assertEquals(1, opens)
    }

    @Test
    fun `the card is absent for a null host`() {
        show(connectedUi.copy(privateDnsHost = null))
        compose.onNodeWithText("Private DNS", substring = true).assertDoesNotExist()
    }

    private companion object {
        const val BODY = "Android's Private DNS is set to dns.google, so apps send their DNS lookups there, not to the connected " +
            "profile's DNS. If the tunnel can't reach that provider, they fail. To use the profile's DNS, set Private DNS to Automatic or Off."
    }
}
