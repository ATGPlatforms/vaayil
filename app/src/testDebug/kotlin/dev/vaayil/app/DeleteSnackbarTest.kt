package dev.vaayil.app

import android.app.Application
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.validate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * U1 (N2): the delete snackbar times out, has a close button, and a second delete's Undo is
 * reachable.
 *
 * `showDeletedSnackbar` suspends until the snackbar is dismissed or actioned — the app calls it
 * from a scope — so each test shows it from its own unconfined scope, which leaves it suspended
 * on the state machine. No UI needs composing: [SnackbarHostState] is a plain state machine.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DeleteSnackbarTest {
    @get:Rule val compose = createComposeRule()

    private val victim = Profile(
        "p1", "Home",
        InterfaceConfig("eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=", listOf("10.0.0.2/32"), listOf("1.1.1.1"), null, null),
        PeerConfig("KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=", null, "vpn.example.com:51820", listOf("0.0.0.0/0"), null),
    )

    /** Shows [profile] the way the Profiles tab does: from a scope, suspended on [snackbar]. */
    private fun show(snackbar: SnackbarHostState, profile: Profile, restore: suspend (Profile) -> Unit) {
        CoroutineScope(Dispatchers.Unconfined).launch { showDeletedSnackbar(snackbar, profile, restore) }
    }

    @Test
    fun `the delete snackbar is Long with a dismiss action`() = runBlocking {
        val snackbar = SnackbarHostState()
        show(snackbar, victim) {}
        val visuals = snackbar.currentSnackbarData!!.visuals
        assertEquals(SnackbarDuration.Long, visuals.duration)
        assertTrue(visuals.withDismissAction)
        assertEquals("Undo", visuals.actionLabel)
        snackbar.currentSnackbarData!!.dismiss()
    }

    @Test
    fun `a second delete's Undo is reachable once the first is dismissed`() = runBlocking {
        val snackbar = SnackbarHostState()
        val saved = mutableListOf<Profile>()
        show(snackbar, victim) { saved.add(it) }
        val office = victim.copy(id = "p2", name = "Office")
        show(snackbar, office) { saved.add(it) } // queued behind
        snackbar.currentSnackbarData!!.dismiss() // the first's close button / its timeout
        val second = snackbar.currentSnackbarData!!.visuals
        assertEquals("Deleted \"Office\"", second.message)
        assertEquals("Undo", second.actionLabel)
        snackbar.currentSnackbarData!!.performAction()
        assertEquals(listOf(office), saved)
    }

    @Test
    fun `a row the current rules refuse shows no Undo`() = runBlocking {
        val snackbar = SnackbarHostState()
        val legacy = victim.copy(peer = victim.peer.copy(endpoint = "a b")) // a space is not host:port
        assertTrue(legacy.validate().isNotEmpty()) // the premise: the current rules refuse this row
        show(snackbar, legacy) {}
        val visuals = snackbar.currentSnackbarData!!.visuals
        // refused restore: no Undo, but the close button and Long stay (U1)
        assertTrue(visuals.withDismissAction)
        assertEquals(SnackbarDuration.Long, visuals.duration)
        assertTrue(visuals.actionLabel == null)
        snackbar.currentSnackbarData!!.dismiss()
    }
}
