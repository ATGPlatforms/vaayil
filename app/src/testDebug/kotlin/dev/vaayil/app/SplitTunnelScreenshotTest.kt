package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.profiles.AppIconLoader
import dev.vaayil.feature.profiles.AppInfo
import dev.vaayil.feature.profiles.SplitTunnelScreen
import dev.vaayil.feature.profiles.SplitTunnelUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/**
 * Split Tunnel tab fixtures: exclusive with a few picks (phone + tablet) and the
 * inclusive-with-empty-list guard. Fake package names keep the launcher-icon lookup
 * on its fallback path, so the captures are deterministic.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplitTunnelScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(fileName: String, state: SplitTunnelUiState) {
        compose.setContent {
            VaayilTheme.Content {
                // Deterministic captures: nothing here animates, but the wrap is the
                // convention every fixture class follows (spec §3.7).
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    SplitTunnelScreen(
                        state = state,
                        onMode = {},
                        onToggle = {},
                        onQuery = {},
                        onShowSystem = {},
                        onRouteAll = {},
                        onBypassAll = {},
                        iconLoader = AppIconLoader { _, _ -> null },
                    )
                }
            }
        }
        compose.waitForIdle()
        // G3: the null loader still hops IO → Main before the initial renders, past
        // waitForIdle; settle so the capture never records an empty 36dp box.
        compose.awaitVisibleAppInitials(state.apps)
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/$fileName.png")
    }

    private fun apps(count: Int) = (1..count).map { i -> AppInfo("com.example.app$i", "App $i") }

    private val exclusive = SplitTunnelUiState(
        mode = SplitTunnelMode.EXCLUDE,
        selectedPackages = setOf("com.example.app1", "com.example.app2"),
        apps = apps(6),
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneExclusive() = capture("split-tunnel-exclusive-w360dp", exclusive)

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun phoneInclusiveEmpty() = capture(
        "split-tunnel-inclusive-empty-w360dp",
        SplitTunnelUiState(
            mode = SplitTunnelMode.INCLUDE,
            selectedPackages = emptySet(),
            apps = apps(6),
            includeListEmpty = true,
        ),
    )

    @Test
    @Config(qualifiers = "w600dp-h960dp")
    fun tabletExclusive() = capture("split-tunnel-exclusive-w600dp", exclusive)

    @Test
    @Config(qualifiers = "w393dp-h851dp-440dpi")
    fun phone440() = capture(
        // Device review C11: the tab at true phone density — 1dp hairlines and the
        // 11sp filter labels as they render on hardware.
        "split-tunnel-exclusive-d440",
        exclusive,
    )
}
