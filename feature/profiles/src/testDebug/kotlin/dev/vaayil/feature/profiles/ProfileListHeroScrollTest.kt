package dev.vaayil.feature.profiles

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.ui.LocalReducedMotion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F26 (spec U4): when a profile becomes the active one, the Profiles list scrolls up to its
 * new hero. The list keeps its first visible row in place when an item is inserted above
 * it, so without the scroll the hero lands out of sight. Returning to the tab keeps the
 * user's place unless the hero changed while the tab was away; so does a recreated
 * activity whose new view model loads the list a moment later.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ProfileListHeroScrollTest {
    @get:Rule val compose = createComposeRule()

    private fun profile(id: String) = Profile(
        id, "Profile $id",
        InterfaceConfig("k", listOf("10.0.0.2/32"), emptyList(), null, null),
        PeerConfig("K", null, "h:51820", listOf("0.0.0.0/0"), 25),
    )

    // Twelve saved rows: the group is taller than the window, so nothing pulls the list back
    // on its own.
    private val profiles = (1..12).map { profile("p$it") }
    private val idle = ProfileListUiState(items = profiles)

    @Composable
    private fun Screen(ui: ProfileListUiState, listState: LazyListState, reducedMotion: Boolean) {
        MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
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
                    listState = listState,
                )
            }
        }
    }

    private fun assertAtTheHero(listState: LazyListState) {
        assertEquals("first visible item", 0, listState.firstVisibleItemIndex)
        assertEquals("scroll offset", 0, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun `a profile that becomes active scrolls its new hero into view`() {
        val ui = mutableStateOf(idle)
        val listState = LazyListState()
        compose.setContent { Screen(ui.value, listState, reducedMotion = false) }
        compose.waitForIdle()

        ui.value = idle.copy(activeId = "p7", phase = ConnectionPhase.CONNECTING)
        compose.waitForIdle()
        assertAtTheHero(listState)
    }

    @Test
    fun `under reduced motion the list jumps to the hero without animating`() {
        val ui = mutableStateOf(idle.copy(activeId = "p1", phase = ConnectionPhase.CONNECTED))
        val listState = LazyListState()
        compose.setContent { Screen(ui.value, listState, reducedMotion = true) }
        compose.waitForIdle()
        // The user reads the saved rows, then switches tunnels: the hero changes far out of
        // sight above them.
        compose.runOnIdle { runBlocking { listState.scrollToItem(1) } }

        compose.mainClock.autoAdvance = false
        ui.value = ui.value.copy(activeId = "p7")
        compose.waitForIdle() // delivers the change; the paused clock runs no frame
        // Three frames (48 ms): enough to recompose and jump, far too short for an animated
        // scroll back up past the saved rows.
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertAtTheHero(listState)
    }

    @Test
    fun `returning to the tab keeps the user's place`() {
        val restoration = StateRestorationTester(compose)
        var listState: LazyListState? = null
        val ui = idle.copy(activeId = "p1", phase = ConnectionPhase.CONNECTED)
        restoration.setContent {
            val state = rememberLazyListState()
            listState = state
            Screen(ui, state, reducedMotion = true)
        }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { listState!!.scrollToItem(1) } }

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("first visible item", 1, listState!!.firstVisibleItemIndex)
    }

    @Test
    fun `a recreation that reloads the list keeps the user's place`() {
        val restoration = StateRestorationTester(compose)
        var listState: LazyListState? = null
        val loaded = idle.copy(activeId = "p1", phase = ConnectionPhase.CONNECTED)
        var viewModel = mutableStateOf(loaded)
        restoration.setContent {
            val state = rememberLazyListState()
            listState = state
            Screen(viewModel.value, state, reducedMotion = true)
        }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { listState!!.scrollToItem(1) } }

        // The activity comes back without its view model: the new one starts with no
        // profiles, and the stored list arrives a moment later.
        viewModel = mutableStateOf(ProfileListUiState())
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        viewModel.value = loaded
        compose.waitForIdle()
        assertEquals("first visible item", 1, listState!!.firstVisibleItemIndex)
    }

    @Test
    fun `a hero that changed while the tab was away is scrolled to on return`() {
        val restoration = StateRestorationTester(compose)
        var listState: LazyListState? = null
        var activeId = "p1"
        restoration.setContent {
            val state = rememberLazyListState()
            listState = state
            Screen(idle.copy(activeId = activeId, phase = ConnectionPhase.CONNECTED), state, reducedMotion = true)
        }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { listState!!.scrollToItem(1) } }

        // Another tab connected p7; the Profiles tab comes back from its saved state.
        activeId = "p7"
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertAtTheHero(listState!!)
    }

    @Test
    fun `a disconnect clears the seen hero so a reconnect scrolls again`() {
        // Regression pin for the T7 restructure's null branch: no hero means the next one
        // is new to the user, so its arrival scrolls again.
        val ui = mutableStateOf(idle)
        val listState = LazyListState()
        compose.setContent { Screen(ui.value, listState, reducedMotion = true) }
        compose.waitForIdle()
        ui.value = idle.copy(activeId = "p1", phase = ConnectionPhase.CONNECTED)
        compose.waitForIdle()
        assertAtTheHero(listState)
        compose.runOnIdle { runBlocking { listState.scrollToItem(6) } }
        ui.value = idle // disconnected: the hero leaves, the seen hero clears
        compose.waitForIdle()
        ui.value = idle.copy(activeId = "p1", phase = ConnectionPhase.CONNECTED)
        compose.waitForIdle()
        assertAtTheHero(listState)
    }

    @Test
    fun `a landed animated scroll marks the hero seen`() {
        // Regression pin for the T7 restructure's landed branch: the animated scroll runs to
        // completion, the hero is marked seen, and coming back to the tab keeps the user's
        // place instead of scrolling again.
        val restoration = StateRestorationTester(compose)
        val ui = mutableStateOf(idle)
        val listState = LazyListState()
        restoration.setContent { Screen(ui.value, listState, reducedMotion = false) }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { listState.scrollToItem(1) } }
        ui.value = idle.copy(activeId = "p1", phase = ConnectionPhase.CONNECTED)
        compose.waitForIdle()
        assertAtTheHero(listState)
        // The scroll landed, so p1 is seen: the user reads the saved rows, and the tab's
        // return with the same hero leaves them there.
        compose.runOnIdle { runBlocking { listState.scrollToItem(1) } }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("first visible item", 1, listState.firstVisibleItemIndex)
    }
}
