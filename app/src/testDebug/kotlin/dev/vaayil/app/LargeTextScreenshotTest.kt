package dev.vaayil.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.vaayil.core.data.ProfilesHealth
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStats
import dev.vaayil.core.tunnel.api.TunnelStatus
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.AppIconLoader
import dev.vaayil.feature.profiles.AppInfo
import dev.vaayil.feature.profiles.ProfileListScreen
import dev.vaayil.feature.profiles.ProfileListUiState
import dev.vaayil.feature.profiles.SplitTunnelScreen
import dev.vaayil.feature.profiles.SplitTunnelUiState
import dev.vaayil.feature.settings.OnboardingScreen
import dev.vaayil.feature.tunnel.DashboardScreen
import dev.vaayil.feature.tunnel.HomeUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F29 (spec U6): the tabs at font scale 2.0 on the narrowest phone width (w360dp). The
 * window is 2000dp tall, so every row is laid out and on screen for the clip check
 * (`TextClipping.kt`). One fixture per tab, and one for the bottom bar, records the
 * reflowed layout; the other tests are semantics-only. Long names wrap to two lines before
 * they ellipsize (U3).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeTextScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /** Renders [content] at font scale 2.0, with the breathing dots held still. */
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

    private fun capture(fileName: String) {
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/$fileName.png")
    }

    private fun profile(id: String, name: String, endpoint: String) = Profile(
        id = id, name = name,
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("K", null, endpoint, listOf("0.0.0.0/0"), 25),
    )

    // The dashboard matrix's profiles (DashboardPhaseScreenshotTest).
    private val home = profile("p1", "Home relay", "vpn.example.net:51820")
    private val office = profile("p2", "Office relay", "vpn.example.net:51820")

    // Review focus 3: 60-character names.
    private val LONG_NAME = "Home relay through the Frankfurt exit node on the fibre line"
    private val longHome = profile("p1", LONG_NAME, "vpn.example.net:51820")
    private val longOffice = profile("p2", "Office relay via the Amsterdam backup route over cellular 5G", "198.51.100.7:51820")

    private fun connectedUi(live: Profile, profiles: List<Profile>) = HomeUiState(
        status = TunnelStatus(profileId = live.id, phase = ConnectionPhase.CONNECTED, error = null),
        stats = TunnelStats(handshakeAt = System.currentTimeMillis() - 14_000, rxBytes = 1_523_000_000, txBytes = 403_000_000),
        activeProfile = live,
        profiles = profiles,
        handshakeState = HandshakeState.VERIFIED,
    )

    private fun idleUi(profiles: List<Profile>) =
        HomeUiState(TunnelStatus.idle, TunnelStats.empty, activeProfile = null, profiles = profiles)

    private fun showDashboard(ui: HomeUiState, selected: Profile?) = show {
        DashboardScreen(ui = ui, selected = selected, onSelectProfile = {}, onToggle = {}, onOpenProfiles = {})
    }

    private fun showProfiles(ui: ProfileListUiState) = show {
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

    // The Profiles tab's populated list (ProfilesScreenshotTest.phoneList's ids keep the avatars).
    private val listUi = ProfileListUiState(
        items = listOf(
            profile("active-1", LONG_NAME, "vpn.example.net:51820"),
            profile("saved-2", "Office", "198.51.100.7:51820"),
            profile("saved-3", "Travel", "travel.example.com:51820"),
        ),
        activeId = "active-1",
        phase = ConnectionPhase.CONNECTED,
        handshakeState = HandshakeState.VERIFIED,
    )

    @Test
    fun dashboardConnected() {
        showDashboard(connectedUi(home, listOf(home)), home)
        capture("dashboard-font2-w360dp")
        compose.assertNoTextClipped("on the connected dashboard at font scale 2.0")
        // The disc label never breaks mid-word (F29: "CONNE / CT").
        compose.assertLinesAtMost("ACTIVE", 1)
    }

    @Test
    fun dashboardIdle() {
        showDashboard(idleUi(listOf(home)), home)
        compose.assertNoTextClipped("on the idle dashboard at font scale 2.0")
        compose.assertLinesAtMost("CONNECT", 1)
    }

    @Test
    fun dashboardSwitch() {
        showDashboard(connectedUi(home, listOf(home, office)), office)
        compose.assertNoTextClipped("on the switch dashboard at font scale 2.0")
        compose.assertLinesAtMost("SWITCH", 1)
    }

    @Test
    fun dashboardEmpty() {
        showDashboard(HomeUiState(), null)
        compose.assertNoTextClipped("on the empty dashboard at font scale 2.0")
    }

    @Test
    fun aLongNameEllipsizesUnderTheDisc() {
        // One profile: the name is plain text on the hero seat.
        showDashboard(connectedUi(longHome, listOf(longHome)), longHome)
        compose.assertEllipsizedOnOneLine(longHome.name)
        compose.assertNoTextClipped("on the dashboard with a 60-character name")
    }

    @Test
    fun aLongNameEllipsizesInTheProfilePill() {
        // Two profiles: the name sits in the profile pill, beside its arrow.
        showDashboard(idleUi(listOf(longHome, longOffice)), longHome)
        compose.assertEllipsizedOnOneLine(longHome.name)
        compose.assertNoTextClipped("on the dashboard profile pill with a 60-character name")
    }

    @Test
    fun profilesList() {
        showProfiles(listUi)
        capture("profiles-font2-w360dp")
        compose.assertNoTextClipped("on the Profiles tab at font scale 2.0")
    }

    @Test
    fun profilesDegraded() {
        showProfiles(listUi.copy(health = ProfilesHealth(atRestActive = false, unreadableIds = setOf("legacy-1"))))
        compose.assertNoTextClipped("on the degraded Profiles tab at font scale 2.0")
    }

    @Test
    fun profilesEmpty() {
        showProfiles(ProfileListUiState())
        compose.assertNoTextClipped("on the empty Profiles tab at font scale 2.0")
    }

    @Test
    fun longNamesWrapToTwoLinesInTheProfilesHeroAndASavedRow() {
        showProfiles(
            ProfileListUiState(
                items = listOf(longHome, longOffice),
                activeId = longHome.id,
                phase = ConnectionPhase.CONNECTED,
                handshakeState = HandshakeState.VERIFIED,
            ),
        )
        compose.assertLinesAtMost(longHome.name, 2)
        compose.assertLinesAtMost(longOffice.name, 2)
        compose.assertNoTextClipped("on the Profiles tab with 60-character names")
    }

    // Device check 2026-10-03: the saved rows at 332dp font scale 2.0. At this width the
    // beside-space is only 76px, so neither row's pieces fit beside the switch and menu and
    // both stack their controls under the text; the fit branch is pinned at w360 (the 1.0
    // test in ProfileListLargeTextTest, and the Office row of profiles-font2-w360dp).
    private val narrowRows = ProfileListUiState(
        items = listOf(
            profile("saved-1", "vt-u2long realworld1", "vpn.example.net:51820"),
            profile("saved-2", "Office", "198.51.100.7:51820"),
        ),
    )

    @Test
    @Config(qualifiers = "w332dp-h2000dp")
    fun profilesNarrowRows() {
        showProfiles(narrowRows)
        capture("profiles-font2-w332dp")
        compose.assertNoTextClipped("on the Profiles rows at 332dp font scale 2.0")
    }

    @Test
    @Config(qualifiers = "w332dp-h2000dp")
    fun profilesNarrowHero() {
        showProfiles(narrowRows.copy(activeId = "saved-1", phase = ConnectionPhase.CONNECTED, handshakeState = HandshakeState.VERIFIED))
        capture("profiles-hero-font2-w332dp")
        compose.assertNoTextClipped("on the Profiles hero at 332dp font scale 2.0")
    }

    private fun showSplitTunnel(state: SplitTunnelUiState) = show {
        SplitTunnelScreen(
            state = state,
            onMode = {},
            onToggle = {},
            onQuery = {},
            onShowSystem = {},
            onRouteAll = {},
            onBypassAll = {},
            onAllowLocalNetwork = {},
            iconLoader = AppIconLoader { _, _ -> null },
        )
    }

    // The Split Tunnel tab's phone fixture state (SplitTunnelScreenshotTest.exclusive). Fake
    // packages keep the icon lookup on its initial-letter fallback.
    private val apps = (1..6).map { i -> AppInfo("com.example.app$i", "App $i") }
    private val exclusive = SplitTunnelUiState(
        mode = SplitTunnelMode.EXCLUDE,
        selectedPackages = setOf("com.example.app1", "com.example.app2"),
        apps = apps,
    )

    @Test
    fun splitTunnel() {
        showSplitTunnel(exclusive)
        // G3: the null loader still hops IO → Main before the initial renders, past
        // waitForIdle; settle so the capture never records an empty 36dp box.
        compose.awaitVisibleAppInitials(exclusive.apps)
        capture("split-tunnel-font2-w360dp")
        compose.assertNoTextClipped("on the Split Tunnel tab at font scale 2.0")
        // The count keeps a line of its own, and its two actions wrap below it (spec U6).
        compose.assertLinesAtMost("2 apps selected", 1)
        val count = compose.onNodeWithText("2 apps selected").fetchSemanticsNode().boundsInRoot
        val routeAll = compose.onNodeWithText("Route All").fetchSemanticsNode().boundsInRoot
        assertTrue("Route All did not wrap below the count", routeAll.top >= count.bottom)
    }

    @Test
    fun splitTunnelInclusiveEmpty() {
        showSplitTunnel(
            SplitTunnelUiState(
                mode = SplitTunnelMode.INCLUDE,
                selectedPackages = emptySet(),
                apps = apps,
                includeListEmpty = true,
            ),
        )
        compose.assertNoTextClipped("on the inclusive Split Tunnel tab at font scale 2.0")
    }

    @Test
    fun aLongAppNameEllipsizes() {
        val name = "Authenticator and password manager for the whole family"
        showSplitTunnel(SplitTunnelUiState(apps = listOf(AppInfo("com.example.auth", name))))
        compose.assertEllipsizedOnOneLine(name)
        compose.assertNoTextClipped("on the Split Tunnel tab with a long app name")
    }

    @Test
    fun bottomBar() {
        // The widest label selected: "Split Tunnel" in SemiBold.
        show { Box(Modifier.testTag("bottom-bar")) { VellumBottomBar(currentRoute = "split-tunnel") {} } }
        compose.onNodeWithTag("bottom-bar").captureRoboImage("src/testDebug/snapshots/bottom-bar-font2-w360dp.png")
        compose.assertNoTextClipped("in the bottom bar at font scale 2.0")
    }

    @Test
    fun onboarding() {
        show {
            OnboardingScreen(
                onDone = {},
                onOpenBatterySettings = {},
                batteryOptimizationDisabled = false,
            )
        }
        // The battery step is the last pager page (OnboardingLargeTextTest).
        repeat(2) {
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }
        capture("onboarding-font2-w360dp")
        compose.assertNoTextClipped("on onboarding at font scale 2.0")
    }

    @Test
    @Config(qualifiers = "w332dp-h2000dp")
    fun onboardingNarrow() {
        show {
            OnboardingScreen(
                onDone = {},
                onOpenBatterySettings = {},
                batteryOptimizationDisabled = false,
            )
        }
        repeat(2) {
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.waitForIdle()
        }
        capture("onboarding-font2-w332dp")
        compose.assertNoTextClipped("on onboarding at 332dp font scale 2.0")
    }
}
