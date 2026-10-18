package dev.vaayil.feature.profiles

import dev.vaayil.core.data.AtRestUnavailableException
import dev.vaayil.core.data.InvalidProfileException
import dev.vaayil.core.data.ProfilesHealth
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.tunnel.api.ConnectionPhase
import dev.vaayil.core.tunnel.api.HandshakeState
import dev.vaayil.core.tunnel.api.TunnelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileListViewModelTest {
    private fun profile(id: String, name: String) = Profile(
        id, name,
        InterfaceConfig("k", listOf("10.0.0.2/32"), emptyList(), null, null),
        PeerConfig("K", null, "h:51820", listOf("0.0.0.0/0"), 25),
    )
    @Test
    fun `ui state combines profiles status and active id`() = runTest(UnconfinedTestDispatcher()) {
        val profiles = MutableStateFlow(listOf(profile("p1", "Home")))
        val status = MutableStateFlow(TunnelStatus.idle)
        val activeId = MutableStateFlow<String?>(null)
        val health = MutableStateFlow(ProfilesHealth(atRestActive = true, unreadableIds = emptySet()))
        val vm = ProfileListViewModel(
            profiles = profiles,
            health = health,
            status = status,
            activeId = activeId,
            onSelectActive = {},
            onDelete = {},
        )
        assertEquals(listOf("Home"), vm.ui.value.items.map { it.name })
        assertEquals(ConnectionPhase.DISCONNECTED, vm.ui.value.phase)
        status.value = TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)
        activeId.value = "p1"
        assertEquals(ConnectionPhase.CONNECTED, vm.ui.value.phase)
        assertEquals("p1", vm.ui.value.activeId)
    }

    @Test
    fun `a failed switch keeps the previously connected profile highlighted`() = runTest(UnconfinedTestDispatcher()) {
        // Failed switch: the engine reports the previous tunnel as still CONNECTED, while the
        // stored pointer names the profile that was attempted (what connect() used to write
        // before anything had connected). The tab must name the profile that is actually up.
        val vm = ProfileListViewModel(
            profiles = MutableStateFlow(listOf(profile("p1", "Home"), profile("p2", "Office"))),
            health = MutableStateFlow(ProfilesHealth(atRestActive = true, unreadableIds = emptySet())),
            status = MutableStateFlow(TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)),
            activeId = MutableStateFlow<String?>("p2"),
            onSelectActive = {},
            onDelete = {},
        )
        assertEquals("p1", vm.ui.value.activeId)
    }

    @Test
    fun `the highlight follows the engine while a connect is in flight`() = runTest(UnconfinedTestDispatcher()) {
        // The pointer is only written on a successful start, so during a connect the engine's
        // profile id is the only truth about which profile is coming up.
        val vm = ProfileListViewModel(
            profiles = MutableStateFlow(listOf(profile("p1", "Home"), profile("p2", "Office"))),
            health = MutableStateFlow(ProfilesHealth(atRestActive = true, unreadableIds = emptySet())),
            status = MutableStateFlow(TunnelStatus(ConnectionPhase.CONNECTING, "p2", null)),
            activeId = MutableStateFlow<String?>("p1"),
            onSelectActive = {},
            onDelete = {},
        )
        assertEquals("p2", vm.ui.value.activeId)
    }

    @Test
    fun `a duplicate the save rejects is reported instead of escaping the view model scope`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            var reported: String? = null
            val vm = ProfileListViewModel(
                profiles = MutableStateFlow(emptyList()),
                health = MutableStateFlow(ProfilesHealth(atRestActive = true, unreadableIds = emptySet())),
                status = MutableStateFlow(TunnelStatus.idle),
                activeId = MutableStateFlow<String?>(null),
                onSelectActive = {},
                onDelete = {},
                // A row that predates today's validation rules reaches save() through the
                // list screen's Duplicate action; the copy must not crash the app (T-3).
                onDuplicate = {
                    throw InvalidProfileException(
                        listOf("DNS server \"corp.example.com\" must be an IP address."),
                    )
                },
                onDuplicateFailed = { reported = it },
            )
            vm.duplicate("legacy")
            assertEquals(
                "Profile is invalid: DNS server \"corp.example.com\" must be an IP address.",
                reported,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `the handshake state reaches the ui state`() = runTest(UnconfinedTestDispatcher()) {
        val handshake = MutableStateFlow(HandshakeState.NONE)
        val vm = ProfileListViewModel(
            profiles = MutableStateFlow(listOf(profile("p1", "Home"))),
            health = MutableStateFlow(ProfilesHealth(atRestActive = true, unreadableIds = emptySet())),
            status = MutableStateFlow(TunnelStatus(ConnectionPhase.CONNECTED, "p1", null)),
            activeId = MutableStateFlow<String?>("p1"),
            onSelectActive = {},
            onDelete = {},
            handshakeState = handshake,
        )
        assertEquals(HandshakeState.NONE, vm.ui.value.handshakeState)
        handshake.value = HandshakeState.WAITING
        assertEquals(HandshakeState.WAITING, vm.ui.value.handshakeState)
        handshake.value = HandshakeState.VERIFIED
        assertEquals(HandshakeState.VERIFIED, vm.ui.value.handshakeState)
    }

    private fun duplicateReports(failure: Throwable): List<String> {
        val reported = mutableListOf<String>()
        val vm = ProfileListViewModel(
            profiles = MutableStateFlow(emptyList()),
            health = MutableStateFlow(ProfilesHealth(atRestActive = true, unreadableIds = emptySet())),
            status = MutableStateFlow(TunnelStatus.idle),
            activeId = MutableStateFlow<String?>(null),
            onSelectActive = {},
            onDelete = {},
            onDuplicate = { throw failure },
            onDuplicateFailed = { reported += it },
        )
        vm.duplicate("p1")
        return reported
    }

    @Test
    fun `a duplicate refused by an at-rest outage reports the Keystore sentence`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            assertEquals(
                listOf("Profiles can't be read or saved while the Android Keystore is unavailable. Try again in a moment."),
                duplicateReports(AtRestUnavailableException()),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a duplicate that fails for another reason never shows the exception text`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            assertEquals(
                listOf("Couldn't duplicate that profile."),
                duplicateReports(IllegalStateException("SQLITE_FULL: database or disk is full")),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }
}
