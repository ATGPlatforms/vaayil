package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** G4 (spec §4.4.4/§4.4.5): the Profiles hero's sage ACTIVE pill waits for this tunnel's handshake. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ProfileHeroHandshakeTest {
    @get:Rule val compose = createComposeRule()

    private val profile = Profile(
        "p1", "Home",
        InterfaceConfig("k", listOf("10.0.0.2/32"), emptyList(), null, null),
        PeerConfig("K", null, "h:51820", listOf("0.0.0.0/0"), 25),
    )

    private fun screen(handshake: HandshakeState) {
        compose.setContent {
            MaterialTheme {
                ProfileListScreen(
                    ui = ProfileListUiState(
                        items = listOf(profile),
                        activeId = "p1",
                        phase = ConnectionPhase.CONNECTED,
                        handshakeState = handshake,
                    ),
                    onOpenEditor = {},
                    onOpenImport = {},
                    onScanQr = {},
                    onDelete = {},
                    onDuplicate = {},
                    onToggleConnect = {},
                    onShareQr = {},
                    onExport = {},
                    onDeleteUnreadable = {},
                )
            }
        }
    }

    @Test
    fun `a tunnel still pending its handshake wears no ACTIVE pill`() {
        screen(HandshakeState.PENDING)
        compose.onAllNodesWithText("ACTIVE").assertCountEquals(0)
    }

    @Test
    fun `a tunnel still waiting for its handshake wears no ACTIVE pill`() {
        screen(HandshakeState.WAITING)
        compose.onAllNodesWithText("ACTIVE").assertCountEquals(0)
    }

    @Test
    fun `a handshaken tunnel wears the ACTIVE pill`() {
        screen(HandshakeState.VERIFIED)
        compose.onNodeWithText("ACTIVE").assertIsDisplayed()
    }
}
