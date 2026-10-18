package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.vaayil.core.data.ProfilesHealth
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.ProfileListScreen
import dev.vaayil.feature.profiles.ProfileListUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/**
 * Profiles tab fixtures: empty state, and a populated list (active hero card + saved rows
 * with connect switches). Fixtures use fixed profile ids so the pastel avatars are stable.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilesScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(fileName: String, ui: ProfileListUiState) {
        compose.setContent {
            VaayilTheme.Content {
                // Deterministic captures: the active pill's breathing dot holds still.
                CompositionLocalProvider(LocalReducedMotion provides true) {
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
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/$fileName.png")
    }

    private fun profile(id: String, name: String, endpoint: String, addresses: List<String> = listOf("10.0.0.2/32")) =
        Profile(
            id = id, name = name,
            interfaceConfig = InterfaceConfig("k", addresses, listOf("1.1.1.1"), 1420, null),
            peer = PeerConfig("K", null, endpoint, listOf("0.0.0.0/0"), 25),
        )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneEmpty() = capture("profiles-empty-w360dp", ProfileListUiState())

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneList() = capture(
        "profiles-list-w360dp",
        ProfileListUiState(
            items = listOf(
                profile("active-1", "Home relay", "vpn.example.net:51820"),
                profile("saved-2", "Office", "198.51.100.7:51820"),
                profile("saved-3", "Travel", "travel.example.com:51820"),
            ),
            activeId = "active-1",
            phase = ConnectionPhase.CONNECTED,
            handshakeState = HandshakeState.VERIFIED,
        ),
    )

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun tabletList() = capture(
        "profiles-list-w600dp",
        ProfileListUiState(
            items = listOf(
                profile("active-1", "Home relay", "vpn.example.net:51820"),
                profile("saved-2", "Office", "198.51.100.7:51820"),
            ),
            activeId = "active-1",
            phase = ConnectionPhase.DISCONNECTED,
        ),
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneDegraded() = capture(
        "profiles-degraded-w360dp",
        // Review fix round 1: the degraded path had no fixture, and its rhythm had
        // regressed (warning cards butting the hero at 0dp). The fixture pins the
        // full chain: header →16dp→ keystore card →12dp→ unreadable card →16dp→ hero.
        // The keystore card shows the read-only copy (a held key was lost, R6); the empty
        // variant below keeps the never-had-a-key copy, so both stay pinned.
        ProfileListUiState(
            items = listOf(
                profile("active-1", "Home relay", "vpn.example.net:51820"),
                profile("saved-2", "Office", "198.51.100.7:51820"),
            ),
            activeId = "active-1",
            phase = ConnectionPhase.CONNECTED,
            handshakeState = HandshakeState.VERIFIED,
            health = ProfilesHealth(atRestActive = false, unreadableIds = setOf("legacy-1"), writesRefused = true),
        ),
    )

    @Test
    @Config(qualifiers = "w393dp-h851dp-440dpi")
    fun phoneList440() = capture(
        // Device review C11: the populated list at true phone density.
        "profiles-list-d440",
        ProfileListUiState(
            items = listOf(
                profile("active-1", "Home relay", "vpn.example.net:51820"),
                profile("saved-2", "Office", "198.51.100.7:51820"),
                profile("saved-3", "Travel", "travel.example.com:51820"),
            ),
            activeId = "active-1",
            phase = ConnectionPhase.CONNECTED,
            handshakeState = HandshakeState.VERIFIED,
        ),
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneDegradedEmpty() = capture(
        // T7 re-review: health degraded AND zero items — the crossed state. The
        // empty branch must restore the same 16dp the list branch does after a
        // warning card; this fixture pins it (goldens-only until it existed).
        "profiles-degraded-empty-w360dp",
        ProfileListUiState(health = ProfilesHealth(atRestActive = false, unreadableIds = setOf("legacy-1"))),
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneAddressWarning() = capture(
        // T-1: IPv6-only addresses under IPv4-only Allowed IPs. The hero and the last saved row
        // carry the address-family warning; the plain row between them pins its absence.
        "profiles-address-warning-w360dp",
        ProfileListUiState(
            items = listOf(
                profile("active-1", "Home relay", "vpn.example.net:51820", addresses = listOf("fd00::2/128")),
                profile("saved-2", "Office", "198.51.100.7:51820"),
                profile("saved-3", "Travel", "travel.example.com:51820", addresses = listOf("fd00::3/128")),
            ),
            activeId = "active-1",
            phase = ConnectionPhase.CONNECTED,
            handshakeState = HandshakeState.VERIFIED,
        ),
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneLongNameHero() = capture(
        "profiles-long-name-w360dp",
        ProfileListUiState(
            items = listOf(
                profile("active-1", "Home relay through the Frankfurt exit node on the fibre line", "vpn.example.net:51820"),
                profile("saved-2", "Office", "198.51.100.7:51820"),
            ),
            activeId = "active-1",
            phase = ConnectionPhase.CONNECTED,
            handshakeState = HandshakeState.VERIFIED,
        ),
    )
}
