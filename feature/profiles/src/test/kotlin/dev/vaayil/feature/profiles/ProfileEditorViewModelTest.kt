package dev.vaayil.feature.profiles

import dev.vaayil.core.data.AtRestUnavailableException
import dev.vaayil.core.model.DnsMode
import dev.vaayil.core.model.DnsPolicy
import dev.vaayil.core.model.DnsSplitRule
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SecureDnsType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditorViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `editing invalid fields surfaces validation errors`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProfileEditorViewModel({ null }, {})
        vm.load(null)
        vm.update { it.copy(name = "Home") }
        assertTrue(vm.state.value.errors.isNotEmpty())
    }

    @Test
    fun `valid profile saves and marks saved`() = runTest(UnconfinedTestDispatcher()) {
        var saved: Profile? = null
        val vm = ProfileEditorViewModel({ null }, { saved = it })
        vm.load(null)
        vm.update {
            it.copy(
                name = "Home",
                interfaceConfig = it.interfaceConfig.copy(
                    privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
                    addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"), mtu = 1420),
                peer = it.peer.copy(
                    publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
                    endpoint = "vpn.example.com:51820", allowedIps = listOf("0.0.0.0/0"), persistentKeepalive = 25),
            )
        }
        vm.save {}
        assertEquals("Home", saved?.name)
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun `dns policy edits flow through update and validate`() = runTest {
        val vm = ProfileEditorViewModel(get = { null }, saveFn = {})
        vm.load(null)
        vm.update { it.copy(dns = DnsPolicy(mode = DnsMode.SECURE)) }         // type+endpoint missing
        assertTrue(vm.state.value.errors.any { it.contains("Secure DNS type") })
        vm.update {
            it.copy(dns = DnsPolicy(
                mode = DnsMode.SECURE, secureType = SecureDnsType.DOH,
                secureEndpoint = "https://9.9.9.9/dns-query",
                rules = listOf(DnsSplitRule("corp.lan", bypass = true)),
            ))
        }
        // Blank draft keeps unrelated errors (name/keys/endpoint); assert DNS errors cleared.
        assertTrue(vm.state.value.errors.none {
            it.contains("Secure DNS") || it.contains("DoH") || it.contains("DoT") || it.contains("Split-DNS")
        })
    }

    private fun madeValid(p: Profile): Profile = p.copy(
        name = "Home",
        interfaceConfig = p.interfaceConfig.copy(
            privateKey = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0=",
            addresses = listOf("10.0.0.2/32"), dnsServers = listOf("1.1.1.1"), mtu = 1420),
        peer = p.peer.copy(
            publicKey = "KyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyU=",
            endpoint = "vpn.example.com:51820", allowedIps = listOf("0.0.0.0/0"), persistentKeepalive = 25),
    )

    @Test
    fun `a save failure is reported instead of crashing`() = runTest(UnconfinedTestDispatcher()) {
        var done = false
        val vm = ProfileEditorViewModel({ null }, { throw IllegalStateException("keystore busy") })
        vm.load(null)
        vm.update { madeValid(it) }
        vm.save { done = true }
        // A fixed message: a raw throwable's text must never reach the UI (it can name table
        // columns or worse; the reachable ones are database errors).
        assertEquals("Couldn't save the profile.", vm.state.value.saveError)
        assertFalse(vm.state.value.saved)
        assertFalse(done)
        vm.update { it } // any edit clears the report
        assertEquals(null, vm.state.value.saveError)
    }

    @Test
    fun `an edit made while a save is in flight is not overwritten`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val vm = ProfileEditorViewModel({ null }, { gate.await(); throw IllegalStateException("keystore busy") })
        vm.load(null)
        vm.update { madeValid(it) }
        var done = false
        vm.save { done = true }
        vm.update { it.copy(name = "Edited mid-save") } // the user keeps typing while the write is in flight
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("Edited mid-save", vm.state.value.profile.name)
        assertEquals("Couldn't save the profile.", vm.state.value.saveError)
        assertFalse(vm.state.value.saved)
        assertFalse(done)
    }

    @Test
    fun `a save that lands on a diverged edit does not close the editor`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val vm = ProfileEditorViewModel({ null }, { gate.await() })
        vm.load(null)
        vm.update { madeValid(it) }
        var done = false
        vm.save { done = true }
        vm.update { it.copy(name = "Edited mid-save") }
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("Edited mid-save", vm.state.value.profile.name)
        assertFalse(vm.state.value.saved) // the stored row does not match what is on screen
        assertFalse(done)                 // closing now (the view model dies with the entry) would discard that edit
    }

    @Test
    fun `a draft that is never saved never reaches the save function`() = runTest(UnconfinedTestDispatcher()) {
        val draft = NewProfileFactory.blank().copy(id = "draft-1", name = "Imported")
        var saves = 0
        val vm = ProfileEditorViewModel({ if (it == "draft-1") draft else null }, { saves++ })
        vm.load("draft-1")
        vm.update { it.copy(name = "Renamed") }
        // Dropped without saving: nothing reaches the save function.
        assertEquals(0, saves)
    }

    @Test
    fun `saving a draft saves it once`() = runTest(UnconfinedTestDispatcher()) {
        val draft = NewProfileFactory.blank().copy(id = "draft-1", name = "Imported")
        val saved = mutableListOf<Profile>()
        val vm = ProfileEditorViewModel({ if (it == "draft-1") draft else null }, { saved.add(it) })
        vm.load("draft-1")
        vm.update { madeValid(it) }
        vm.save {}
        assertEquals(listOf("draft-1"), saved.map { it.id })
    }

    @Test
    fun `an at-rest outage during save reports the Keystore sentence`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProfileEditorViewModel({ null }, { throw AtRestUnavailableException() })
        vm.load(null)
        vm.update { madeValid(it) }
        vm.save {}
        assertEquals(
            "Profiles can't be read or saved while the Android Keystore is unavailable. Try again in a moment.",
            vm.state.value.saveError,
        )
    }

    @Test
    fun `every change is kept for the editor's entry`() = runTest(UnconfinedTestDispatcher()) {
        val stored = madeValid(NewProfileFactory.blank().copy(id = "p1"))
        val kept = mutableListOf<EditorDraft>()
        val vm = ProfileEditorViewModel({ id -> stored.takeIf { it.id == id } }, {}, keepDraft = { kept += it })
        vm.load("p1")
        vm.update { it.copy(interfaceConfig = it.interfaceConfig.copy(privateKey = "Generated")) }
        assertEquals(EditorDraft(stored, stored), kept.first())
        assertEquals(stored, kept.last().baseline)
        assertEquals("Generated", kept.last().current.interfaceConfig.privateKey)
    }

    @Test
    fun `a recreated editor takes its kept draft back instead of the stored copy`() =
        runTest(UnconfinedTestDispatcher()) {
            val stored = madeValid(NewProfileFactory.blank().copy(id = "p1"))
            val edited = stored.copy(name = "Office")
            var reads = 0
            val vm = ProfileEditorViewModel(
                get = { reads++; stored },
                saveFn = {},
                restoreDraft = { EditorDraft(stored, edited) },
            )
            vm.load("p1")
            assertEquals(edited, vm.state.value.profile)
            assertTrue(vm.state.value.dirty)
            assertEquals(0, reads)
        }

    @Test
    fun `dirty follows the form against what it opened with`() = runTest(UnconfinedTestDispatcher()) {
        val stored = madeValid(NewProfileFactory.blank().copy(id = "p1"))
        val vm = ProfileEditorViewModel({ stored }, {})
        vm.load("p1")
        assertFalse(vm.state.value.dirty)
        vm.update { it.copy(name = "Office") }
        assertTrue(vm.state.value.dirty)
        vm.update { it.copy(name = stored.name) } // typed back to what it was
        assertFalse(vm.state.value.dirty)
    }

    @Test
    fun `after a save, Back compares against what was saved`() = runTest(UnconfinedTestDispatcher()) {
        val stored = madeValid(NewProfileFactory.blank().copy(id = "p1"))
        val gate = CompletableDeferred<Unit>()
        val vm = ProfileEditorViewModel({ stored }, { gate.await() })
        vm.load("p1")
        vm.update { it.copy(name = "Office") }
        vm.save {}
        vm.update { it.copy(name = "Office 2") } // typed while the write was in flight
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.dirty) // "Office 2" is not what was written
        vm.update { it.copy(name = "Office") }
        assertFalse(vm.state.value.dirty) // "Office" is
    }

    @Test
    fun `an id that finds nothing is reported and never kept`() = runTest(UnconfinedTestDispatcher()) {
        val kept = mutableListOf<EditorDraft>()
        val vm = ProfileEditorViewModel({ null }, {}, keepDraft = { kept += it })
        vm.load("gone") // an import draft lost to a process death
        assertTrue(vm.state.value.missing)
        assertEquals(emptyList<EditorDraft>(), kept)
        val fresh = ProfileEditorViewModel({ null }, {})
        fresh.load(null) // a new profile opens blank on purpose
        assertFalse(fresh.state.value.missing)
    }

    @Test
    fun `a stored profile that vanished keeps the blank form and keeps nothing`() = runTest {
        // T13: load() was given an id and found nothing — the form stays blank (M3), flags
        // missing, and keeps no draft, so a recreation looks again.
        var kept: EditorDraft? = null
        val vm = ProfileEditorViewModel(
            get = { null },
            saveFn = {},
            keepDraft = { kept = it },
        )
        vm.load("gone")
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state.missing)
        assertEquals(NewProfileFactory.blank().name, state.profile.name)
        assertEquals("", state.profile.interfaceConfig.privateKey)
        assertNull(kept)
    }
}
