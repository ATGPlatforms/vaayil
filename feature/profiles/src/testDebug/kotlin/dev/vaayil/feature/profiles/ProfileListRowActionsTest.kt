package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.ProfilesHealth
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F25: Share QR and Export .conf live in every saved row's overflow menu, not just the hero. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ProfileListRowActionsTest {
    @get:Rule val compose = createComposeRule()

    private fun profile(id: String, name: String) = Profile(
        id = id, name = name,
        interfaceConfig = InterfaceConfig(
            privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"),
            mtu = 1420, listenPort = null,
        ),
        peer = PeerConfig(
            publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
            presharedKey = null, endpoint = "vpn.example.com:51820",
            allowedIps = listOf("0.0.0.0/0"), persistentKeepalive = 25,
        ),
    )

    private fun screen(
        items: List<Profile>,
        onShareQr: (String) -> Unit = {},
        onExport: (String) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                ProfileListScreen(
                    ui = ProfileListUiState(items = items, health = ProfilesHealth(atRestActive = true, unreadableIds = emptySet())),
                    onOpenEditor = {},
                    onOpenImport = {},
                    onScanQr = {},
                    onDelete = {},
                    onDuplicate = {},
                    onToggleConnect = {},
                    onShareQr = onShareQr,
                    onExport = onExport,
                    onDeleteUnreadable = {},
                )
            }
        }
    }

    @Test
    fun `overflow menu shares a saved row by id`() {
        val shared = mutableListOf<String>()
        screen(listOf(profile("a", "Alpha"), profile("b", "Beta")), onShareQr = { shared += it })
        compose.onNodeWithContentDescription("More options for Beta").performClick()
        compose.onNodeWithText("Share QR").performClick()
        assertEquals(listOf("b"), shared)
    }

    @Test
    fun `overflow menu exports a saved row by id`() {
        val exported = mutableListOf<String>()
        screen(listOf(profile("a", "Alpha"), profile("b", "Beta")), onExport = { exported += it })
        compose.onNodeWithContentDescription("More options for Beta").performClick()
        compose.onNodeWithText("Export .conf").performClick()
        assertEquals(listOf("b"), exported)
    }

    @Test
    fun `menu still offers edit duplicate and delete`() {
        screen(listOf(profile("a", "Alpha"), profile("b", "Beta")))
        compose.onNodeWithContentDescription("More options for Beta").performClick()
        compose.onNodeWithText("Edit").assertExists()
        compose.onNodeWithText("Duplicate").assertExists()
        compose.onNodeWithText("Delete").assertExists()
    }
}
