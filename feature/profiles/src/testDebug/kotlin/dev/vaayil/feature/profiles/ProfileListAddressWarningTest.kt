package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
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
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/** T-1 (spec U9): the address-family warning sits on the affected profile's own row or hero, and only there. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileListAddressWarningTest {
    @get:Rule val compose = createComposeRule()

    private val mismatch = "This profile won't carry connections. Its addresses don't match its Allowed IPs."
    private val dnsBypass = "DNS lookups will bypass this tunnel. Add a DNS server to keep them in."

    private fun profile(
        id: String,
        name: String,
        address: String,
        dnsServers: List<String> = listOf("1.1.1.1"),
    ) = Profile(
        id = id, name = name,
        interfaceConfig = InterfaceConfig(
            privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            addresses = listOf(address), dnsServers = dnsServers,
            mtu = 1420, listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
            presharedKey = null, endpoint = "vpn.example.com:51820",
            allowedIps = listOf("0.0.0.0/0"), persistentKeepalive = 25,
        ),
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
                    onToggleConnect = {},
                    onShareQr = {},
                    onExport = {},
                    onDeleteUnreadable = {},
                )
            }
        }
    }

    @Test
    fun `the warning sits under the mismatched saved row only`() {
        screen(
            ProfileListUiState(
                items = listOf(
                    profile("a", "Alpha", "10.0.0.2/32"),
                    profile("b", "Beta", "fd00::2/128"),
                    profile("c", "Gamma", "10.0.0.3/32"),
                ),
            ),
        )
        compose.onAllNodesWithText(mismatch).assertCountEquals(1)
        val note = compose.onNodeWithText(mismatch).getUnclippedBoundsInRoot()
        val beta = compose.onNodeWithText("Beta").getUnclippedBoundsInRoot()
        val gamma = compose.onNodeWithText("Gamma").getUnclippedBoundsInRoot()
        assertTrue(note.top > beta.bottom, "the note follows Beta's row")
        assertTrue(note.bottom < gamma.top, "the note comes before Gamma's row")
    }

    @Test
    fun `an active mismatched profile carries the warning in its hero`() {
        screen(
            ProfileListUiState(
                items = listOf(profile("a", "Alpha", "fd00::2/128"), profile("b", "Beta", "10.0.0.2/32")),
                activeId = "a",
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
        )
        compose.onAllNodesWithText(mismatch).assertCountEquals(1)
        val note = compose.onNodeWithText(mismatch).getUnclippedBoundsInRoot()
        val alpha = compose.onNodeWithText("Alpha").getUnclippedBoundsInRoot()
        val beta = compose.onNodeWithText("Beta").getUnclippedBoundsInRoot()
        assertTrue(note.top > alpha.bottom, "the note sits in Alpha's hero, under its name")
        assertTrue(note.bottom < beta.top, "the note sits above the saved rows")
    }

    @Test
    fun `the saved-row family note fits two lines at 360dp`() {
        screen(
            ProfileListUiState(
                items = listOf(
                    profile("a", "Alpha", "10.0.0.2/32"),
                    profile("b", "Beta", "fd00::2/128"),
                ),
            ),
        )
        // T10: the note card now aligns with the row's own padding, not the 68dp name
        // column, and its copy fits two lines at 360dp.
        assertTrue(
            textLayoutOf(mismatch).lineCount <= 2,
            "the family note wrapped past two lines",
        )
    }

    @Test
    fun `a full-tunnel row with no DNS server shows the DNS note once`() {
        screen(
            ProfileListUiState(
                items = listOf(
                    profile("a", "Alpha", "10.0.0.2/32", dnsServers = emptyList()),
                    profile("b", "Beta", "10.0.0.3/32"),
                ),
            ),
        )
        compose.onAllNodesWithText(dnsBypass).assertCountEquals(1)
        val note = compose.onNodeWithText(dnsBypass).getUnclippedBoundsInRoot()
        val alpha = compose.onNodeWithText("Alpha").getUnclippedBoundsInRoot()
        val beta = compose.onNodeWithText("Beta").getUnclippedBoundsInRoot()
        assertTrue(note.top > alpha.bottom, "the DNS note follows the profile without DNS")
        assertTrue(note.bottom < beta.top, "the DNS note doesn't appear under the profile with DNS")
    }

    private fun textLayoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
