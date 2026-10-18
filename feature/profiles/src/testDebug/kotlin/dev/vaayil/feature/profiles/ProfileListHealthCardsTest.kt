package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.data.ProfilesHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ProfileListHealthCardsTest {
    @get:Rule val compose = createComposeRule()

    private fun screen(state: ProfileListUiState, onDeleteUnreadable: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                ProfileListScreen(
                    ui = state,
                    onOpenEditor = {},
                    onOpenImport = {},
                    onScanQr = {},
                    onDelete = {},
                    onDuplicate = {},
                    onToggleConnect = {},
                    onShareQr = {},
                    onExport = {},
                    onDeleteUnreadable = onDeleteUnreadable,
                )
            }
        }
    }

    @Test
    fun `healthy state shows no cards`() {
        screen(ProfileListUiState(health = ProfilesHealth(atRestActive = true, unreadableIds = emptySet())))
        assertTrue(
            compose.onAllNodesWithText("Keystore is unavailable", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            compose.onAllNodesWithText("encryption key changed", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `degraded keystore shows the unencrypted-storage warning`() {
        screen(ProfileListUiState(health = ProfilesHealth(atRestActive = false, unreadableIds = emptySet())))
        assertTrue(
            compose.onAllNodesWithText(
                "Keys are stored unencrypted on this device because the Android Keystore is unavailable.",
            ).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `unreadable profiles show the count warning and Remove deletes them`() {
        var removed = 0
        screen(
            ProfileListUiState(health = ProfilesHealth(atRestActive = true, unreadableIds = setOf("p1", "p2"))),
            onDeleteUnreadable = { removed++ },
        )
        assertTrue(
            compose.onAllNodesWithText("2 profiles can't be read because the device's encryption key changed.")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithText("Remove").performClick()
        assertEquals(1, removed)
    }

    @Test
    fun `a lost key shows the read-only warning instead of the unencrypted one`() {
        screen(
            ProfileListUiState(
                health = ProfilesHealth(atRestActive = false, unreadableIds = emptySet(), writesRefused = true),
            ),
        )
        assertTrue(
            compose.onAllNodesWithText(
                "The Android Keystore is unavailable right now, so profiles are read-only. Vaayil keeps trying.",
            ).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            compose.onAllNodesWithText("Keys are stored unencrypted", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }
}
