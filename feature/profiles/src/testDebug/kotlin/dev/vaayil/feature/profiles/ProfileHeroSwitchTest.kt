package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * J6: the active profile's hero card is that profile's row while it is active, so it
 * keeps the row's connect switch — on while the tunnel is connecting or connected,
 * labelled with the profile name for screen readers (F9), a tap running the same
 * `onToggleConnect` funnel the saved rows' switches run.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
class ProfileHeroSwitchTest {
    @get:Rule val compose = createComposeRule()

    private val toggled = mutableListOf<String>()

    private fun profile(id: String, name: String, endpoint: String) = Profile(
        id, name,
        InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), null, null),
        PeerConfig("K", null, endpoint, listOf("0.0.0.0/0"), 25),
    )

    private fun screen(ui: ProfileListUiState) {
        compose.setContent {
            MaterialTheme {
                ProfileListScreen(
                    ui = ui,
                    onOpenEditor = {},
                    onOpenImport = {},
                    onScanQr = {},
                    onDelete = {},
                    onDuplicate = {},
                    onToggleConnect = { id -> toggled.add(id) },
                    onShareQr = {},
                    onExport = {},
                    onDeleteUnreadable = {},
                )
            }
        }
    }

    private fun heroSwitch() = compose.onNode(isToggleable() and hasContentDescription("Alpha"))

    @Test
    fun `the hero switch is on while the active profile is connected`() {
        screen(
            ProfileListUiState(
                items = listOf(
                    profile("alpha", "Alpha", "vpn.example.net:51820"),
                    profile("beta", "Beta", "198.51.100.7:51820"),
                ),
                activeId = "alpha",
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
        )
        heroSwitch().performScrollTo().assertIsOn()
    }

    @Test
    fun `the hero switch is on while connecting and a tap toggles the active profile`() {
        screen(
            ProfileListUiState(
                items = listOf(
                    profile("alpha", "Alpha", "vpn.example.net:51820"),
                    profile("beta", "Beta", "198.51.100.7:51820"),
                ),
                activeId = "alpha",
                phase = ConnectionPhase.CONNECTING,
                handshakeState = HandshakeState.NONE,
            ),
        )
        // On while connecting too: a tap cancels the connect, like the Dashboard disc's
        // "Cancel connecting".
        heroSwitch().assertIsOn()
        heroSwitch().performClick()
        assertEquals(listOf("alpha"), toggled)
    }

    @Test
    fun `a hero that is not live shows its switch off and a tap toggles it`() {
        screen(
            ProfileListUiState(
                items = listOf(
                    profile("alpha", "Alpha", "vpn.example.net:51820"),
                    profile("beta", "Beta", "198.51.100.7:51820"),
                ),
                activeId = "alpha",
                phase = ConnectionPhase.DISCONNECTED,
            ),
        )
        heroSwitch().assertIsOff()
        heroSwitch().performClick()
        assertEquals(listOf("alpha"), toggled)
    }

    @Test
    fun `each profile on the tab has exactly one switch`() {
        screen(
            ProfileListUiState(
                items = listOf(
                    profile("alpha", "Alpha", "vpn.example.net:51820"),
                    profile("beta", "Beta", "198.51.100.7:51820"),
                ),
                activeId = "alpha",
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
        )
        // The hero's switch plus the saved row's: one toggleable per profile, not two
        // for the active one.
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
    }
}
