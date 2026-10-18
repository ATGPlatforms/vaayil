package dev.vaayil.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelSelections
import dev.vaayil.core.ui.LocalReducedMotion
import dev.vaayil.feature.settings.BackupScreen
import dev.vaayil.feature.settings.BackupViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/**
 * Backup tab fixture: the idle surface (info card + export/import action sheets, no
 * dialog). The view model runs on fake flows/lambdas — nothing touches storage.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun profile(id: String, name: String) = Profile(
        id = id, name = name,
        interfaceConfig = InterfaceConfig("k", listOf("10.0.0.2/32"), listOf("1.1.1.1"), 1420, null),
        peer = PeerConfig("K", null, "vpn.example.net:51820", listOf("0.0.0.0/0"), 25),
    )

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun backupIdle() {
        val vm = BackupViewModel(
            profiles = MutableStateFlow(listOf(profile("p-1", "Home relay"), profile("p-2", "Office"))),
            storedCount = MutableStateFlow(2),
            existingIds = { emptySet() },
            saveProfiles = {},
            selections = MutableStateFlow(SplitTunnelSelections.INITIAL),
            saveRules = {},
        )
        compose.setContent {
            VaayilTheme.Content {
                // Deterministic captures: nothing here animates, but the wrap is the
                // convention every fixture class follows (spec §3.7).
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    BackupScreen(vm = vm, onRestored = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/backup-idle-w360dp.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h2000dp")
    fun backupIdleAtFontScale2() {
        val vm = BackupViewModel(
            profiles = MutableStateFlow(listOf(profile("p-1", "Home relay"), profile("p-2", "Office"))),
            storedCount = MutableStateFlow(2),
            existingIds = { emptySet() },
            saveProfiles = {},
            selections = MutableStateFlow(SplitTunnelSelections.INITIAL),
            saveRules = {},
        )
        compose.setContent {
            VaayilTheme.Content {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    AtFontScale(2f) { BackupScreen(vm = vm, onRestored = {}) }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/testDebug/snapshots/backup-font2-w360dp.png")
        compose.assertNoTextClipped("on the Backup tab at font scale 2.0")
    }
}
