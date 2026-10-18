package dev.vaayil.feature.settings

import dev.vaayil.core.data.BackupCodec
import dev.vaayil.core.model.InterfaceConfig
import dev.vaayil.core.model.PeerConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelSelections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val key = "A".repeat(43) + "="
    private val profile = Profile(
        id = "p1", name = "Home",
        interfaceConfig = InterfaceConfig(key, listOf("10.0.0.2/32"), emptyList(), null, null),
        peer = PeerConfig(key, null, "vpn.example.com:51820", listOf("0.0.0.0/0"), null),
    )
    private val selections = SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.browser"))
    private val stored = MutableStateFlow<List<Profile>>(emptyList())
    /** The stored row count, driven apart from [stored]: an unreadable row is stored but not readable. */
    private val storedRows = MutableStateFlow(0)
    private val storedIds = mutableSetOf<String>()
    private val exportPassphrase = "snapshot passphrase"
    private val saved = mutableListOf<Profile>()
    private val savedRules = mutableListOf<SplitTunnelSelections>()
    private lateinit var vm: BackupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = BackupViewModel(
            profiles = stored,
            storedCount = storedRows,
            existingIds = { storedIds },
            saveProfiles = { saved += it },
            selections = MutableStateFlow(selections),
            saveRules = { savedRules += it },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A v4 envelope with no rules section: the only shape that restores with no prompt at all. */
    private fun rulesFree(vararg profiles: Profile): String =
        BackupCodec.encode(profiles.toList(), selections).replace(Regex(""","splitTunnel":\{[^}]*\}"""), "")

    @Test
    fun `a sealed file asks for its passphrase before anything is saved`() = runTest {
        val sealed = BackupCodec.encodeSealed(listOf(profile), selections, "snapshot passphrase".toCharArray())
        var done = -1
        vm.restore(sealed) { done = it }
        assertEquals(PassphrasePrompt.IMPORT, vm.state.first { it.passphrasePrompt != null }.passphrasePrompt)
        assertTrue(saved.isEmpty())
        vm.unlockImport("wrong".toCharArray())
        assertEquals(
            BackupViewModel.WRONG_PASSPHRASE,
            vm.state.first { it.passphraseError != null }.passphraseError,
        )
        assertEquals(PassphrasePrompt.IMPORT, vm.state.value.passphrasePrompt)
        vm.unlockImport("snapshot passphrase".toCharArray())
        // The snapshot carries rules, so one confirmation precedes the write.
        val prompt = vm.state.first { it.pendingRestore != null }.pendingRestore!!
        assertNull(vm.state.value.passphrasePrompt)
        assertEquals(selections, prompt.rules)
        vm.confirmRestore()
        vm.state.first { it.message != null }
        assertEquals(listOf(profile), saved)
        assertEquals(listOf(selections), savedRules)
        assertEquals(1, done)
    }

    @Test
    fun `a rules-free plain snapshot restores without a prompt`() = runTest {
        vm.restore(rulesFree(profile)) {}
        val restored = vm.state.first { it.message != null }
        assertNull(restored.passphrasePrompt)
        assertNull(restored.pendingRestore)
        assertEquals(listOf(profile), saved)
        assertTrue(savedRules.isEmpty())
    }

    @Test
    fun `a snapshot with rules asks before replacing them`() = runTest {
        vm.restore(BackupCodec.encode(listOf(profile), selections)) {}
        val prompt = vm.state.first { it.pendingRestore != null }.pendingRestore!!
        assertEquals(1, prompt.profiles)
        assertEquals(0, prompt.replacing)
        assertEquals(selections, prompt.rules)
        assertTrue(saved.isEmpty() && savedRules.isEmpty())
        vm.confirmRestore()
        vm.state.first { it.message != null }
        assertEquals(listOf(selections), savedRules)
    }

    @Test
    fun `export stages a passphrase and seals the snapshot`() = runTest {
        stored.value = listOf(profile)
        assertNull(vm.buildSnapshot())
        vm.beginExport()
        assertEquals(PassphrasePrompt.EXPORT, vm.state.value.passphrasePrompt)
        vm.stageExportPassphrase("snapshot passphrase".toCharArray())
        assertNull(vm.state.value.passphrasePrompt)
        val payload = vm.buildSnapshot()
        assertNotNull(payload)
        assertTrue(BackupCodec.isSealed(payload!!))
        assertNull(vm.buildSnapshot()) // staged passphrase is single-use
    }

    /** Stages a passphrase and builds the sealed payload the picker would write. */
    private suspend fun sealedExport(): String {
        vm.beginExport()
        vm.stageExportPassphrase(exportPassphrase.toCharArray())
        return vm.buildSnapshot()!!
    }

    /** The payload really carries this many profiles — the counts the report must match. */
    private fun profilesIn(payload: String): Int =
        BackupCodec.decode(payload, exportPassphrase.toCharArray()).profiles.size

    @Test
    fun `a complete export names the profiles it wrote`() = runTest {
        stored.value = listOf(profile, profile.copy(id = "p2"))
        storedRows.value = 2
        assertEquals(2, profilesIn(sealedExport()))
        vm.reportExport(true)
        assertEquals("Encrypted snapshot written: 2 profiles.", vm.state.value.message)
    }

    @Test
    fun `a complete export of one profile uses the singular`() = runTest {
        stored.value = listOf(profile)
        storedRows.value = 1
        assertEquals(1, profilesIn(sealedExport()))
        vm.reportExport(true)
        assertEquals("Encrypted snapshot written: 1 profile.", vm.state.value.message)
    }

    @Test
    fun `unreadable rows are counted against the stored total`() = runTest {
        stored.value = listOf(profile)
        storedRows.value = 3
        assertEquals(1, profilesIn(sealedExport()))
        vm.reportExport(true)
        assertEquals("Wrote 1 of 3 profiles. 2 couldn't be read and were left out.", vm.state.value.message)
    }

    @Test
    fun `a single skipped row is reported in the singular`() = runTest {
        stored.value = listOf(profile)
        storedRows.value = 2
        assertEquals(1, profilesIn(sealedExport()))
        vm.reportExport(true)
        assertEquals("Wrote 1 of 2 profiles. 1 couldn't be read and was left out.", vm.state.value.message)
    }

    @Test
    fun `an export with nothing readable still reports the stored row`() = runTest {
        stored.value = emptyList()
        storedRows.value = 1
        assertEquals(0, profilesIn(sealedExport()))
        vm.reportExport(true)
        assertEquals("Wrote 0 of 1 profile. 1 couldn't be read and was left out.", vm.state.value.message)
    }

    @Test
    fun `an empty device exports zero profiles`() = runTest {
        stored.value = emptyList()
        storedRows.value = 0
        assertEquals(0, profilesIn(sealedExport()))
        vm.reportExport(true)
        assertEquals("Encrypted snapshot written: 0 profiles.", vm.state.value.message)
    }

    @Test
    fun `the written counts stay with the payload while storage changes`() = runTest {
        stored.value = listOf(profile)
        storedRows.value = 2
        assertEquals(1, profilesIn(sealedExport()))
        // Profiles added while the file is still being written must not rewrite the report.
        stored.value = listOf(profile, profile.copy(id = "p2"), profile.copy(id = "p3"))
        storedRows.value = 3
        vm.reportExport(true)
        assertEquals("Wrote 1 of 2 profiles. 1 couldn't be read and was left out.", vm.state.value.message)
    }

    @Test
    fun `a failed export does not reuse the previous counts`() = runTest {
        stored.value = listOf(profile)
        storedRows.value = 2
        sealedExport()
        vm.reportExport(false)
        assertEquals("Could not write the snapshot file.", vm.state.value.message)
        vm.reportExport(true)
        assertEquals("Encrypted snapshot written.", vm.state.value.message)
    }

    @Test
    fun `a new export never reports the previous export's counts`() = runTest {
        stored.value = listOf(profile)
        storedRows.value = 2
        sealedExport()          // the first export's payload; its file write is still in flight
        vm.beginExport()        // the user starts a second export
        vm.reportExport(true)   // the first write lands: the counts it captured are already gone
        assertEquals("Encrypted snapshot written.", vm.state.value.message)
        vm.stageExportPassphrase(exportPassphrase.toCharArray())
        stored.value = listOf(profile, profile.copy(id = "p2"))
        storedRows.value = 2
        vm.buildSnapshot()
        vm.reportExport(true)
        assertEquals("Encrypted snapshot written: 2 profiles.", vm.state.value.message)
    }

    @Test
    fun `garbage is reported with a constant message`() = runTest {
        vm.restore("{not json") {}
        assertEquals(BackupViewModel.UNREADABLE_SNAPSHOT, vm.state.first { it.message != null }.message)
    }

    @Test
    fun `restoring over existing ids asks first and applies on confirm`() = runTest {
        storedIds += "p1"
        val incoming = profile.copy(name = "Home v2")
        vm.restore(rulesFree(incoming, profile.copy(id = "p2", name = "Office"))) {}
        assertEquals(1, vm.state.first { it.pendingRestore != null }.pendingRestore!!.replacing)
        assertTrue(saved.isEmpty())
        vm.confirmRestore()
        vm.state.first { it.message != null }
        assertNull(vm.state.value.pendingRestore)
        assertEquals(listOf("Home v2", "Office"), saved.map { it.name })
    }

    @Test
    fun `collisions count rows the app cannot currently read`() = runTest {
        stored.value = emptyList()   // the decrypted list is empty (key not held)...
        storedIds += "p1"            // ...but the row exists and must not be overwritten silently
        vm.restore(rulesFree(profile)) {}
        assertEquals(1, vm.state.first { it.pendingRestore != null }.pendingRestore!!.replacing)
    }

    @Test
    fun `cancelling a restore saves nothing`() = runTest {
        storedIds += "p1"
        vm.restore(rulesFree(profile)) {}
        assertNotNull(vm.state.first { it.pendingRestore != null }.pendingRestore)
        vm.cancelRestore()
        assertNull(vm.state.value.pendingRestore)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `an invalid profile aborts the whole restore before anything is saved`() = runTest {
        val bad = profile.copy(id = "p9", name = "")
        vm.restore(BackupCodec.encode(listOf(profile.copy(id = "p2"), bad), selections)) {}
        assertTrue(vm.state.first { it.message != null }.message!!.contains("invalid"))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `too large and unreadable are distinct constant messages`() {
        vm.reportTooLarge()
        assertEquals(BackupViewModel.TOO_LARGE, vm.state.value.message)
        vm.reportReadFailure()
        assertEquals(BackupViewModel.COULD_NOT_READ, vm.state.value.message)
    }

    @Test
    fun `the restore summary names profiles collisions and rules`() {
        val plain = restoreSummaryRows(RestorePrompt(profiles = 2, replacing = 0, rules = null))
        assertEquals(listOf("Added" to "2 profiles"), plain)
        val full = restoreSummaryRows(RestorePrompt(profiles = 3, replacing = 1, rules = selections))
        assertEquals(
            listOf(
                "Added" to "2 profiles",
                "Overwritten" to "1 existing profile with the same id",
                "Split tunnel" to "Replaced by the snapshot's: Exclusive, 1 app",
            ),
            full,
        )
        val noAdditions = restoreSummaryRows(RestorePrompt(profiles = 4, replacing = 4, rules = null))
        assertEquals(listOf("Overwritten" to "4 existing profiles with the same id"), noAdditions)
    }

    @Test
    fun `a failing rules write is reported instead of crashing`() = runTest {
        val failing = BackupViewModel(
            profiles = stored,
            storedCount = storedRows,
            existingIds = { storedIds },
            saveProfiles = { saved += it },
            selections = MutableStateFlow(selections),
            saveRules = { throw IllegalStateException("datastore unavailable") },
        )
        failing.restore(BackupCodec.encode(listOf(profile), selections)) {}
        failing.state.first { it.pendingRestore != null }
        failing.confirmRestore()
        val done = failing.state.first { it.message != null }
        assertTrue(done.message!!.contains("could not be updated"))
        assertEquals(listOf(profile), saved) // the profile batch still committed
    }

    /** A v2 envelope: rules lived per profile then (splitMode/splitPackages). */
    private fun v2(packages: String) =
        """{"version":2,"profiles":[{"id":"p1","name":"Home","privateKey":"$key","addresses":["10.0.0.2/32"],"dnsServers":[],"peerPublicKey":"$key","endpoint":"vpn.example.com:51820","allowedIps":["0.0.0.0/0"],"splitMode":"EXCLUDE","splitPackages":$packages}]}"""

    @Test
    fun `an older snapshot's per-app rules are reported and the current rules stay`() = runTest {
        vm.restore(v2("""["com.browser"]""")) {}
        val done = vm.state.first { it.message != null }
        assertNull(done.pendingRestore) // the device's rules don't change, so nothing to confirm
        assertEquals(
            "Restored 1 profile (existing profiles with the same id were updated). " +
                "Per-app rules in this older snapshot can't be restored. Your current rules stay.",
            done.message,
        )
        assertEquals(listOf(profile), saved)
        assertTrue(savedRules.isEmpty())
    }

    @Test
    fun `the restore prompt carries the older-snapshot note`() = runTest {
        storedIds += "p1"
        vm.restore(v2("""["com.browser"]""")) {}
        val prompt = vm.state.first { it.pendingRestore != null }.pendingRestore!!
        assertTrue(prompt.legacyRulesDropped)
        assertEquals(
            listOf("Per-app rules in this older snapshot can't be restored. Your current rules stay."),
            restoreNotes(prompt),
        )
        vm.confirmRestore()
        assertTrue(vm.state.first { it.message != null }.message!!.endsWith(BackupViewModel.LEGACY_RULES_DROPPED))
    }

    @Test
    fun `an older snapshot without per-app rules gets no note`() = runTest {
        storedIds += "p1" // the snapshot's profile replaces a stored row: a real confirm prompt
        vm.restore(v2("[]")) {}
        val prompt = vm.state.first { it.pendingRestore != null }.pendingRestore!!
        assertFalse(prompt.legacyRulesDropped)
        assertEquals(1, prompt.replacing)
        assertEquals(emptyList<String>(), restoreNotes(prompt))
        vm.confirmRestore()
        assertEquals(
            "Restored 1 profile (existing profiles with the same id were updated).",
            vm.state.first { it.message != null }.message,
        )
    }

    @Test
    fun `an unbracketed IPv6 endpoint with a five-digit port restores bracketed`() = runTest {
        vm.restore(rulesFree(profile.copy(peer = profile.peer.copy(endpoint = "2001:db8::1:51820")))) {}
        vm.state.first { it.message != null }
        assertEquals("[2001:db8::1]:51820", saved.single().peer.endpoint)
    }

    @Test
    fun `an unbracketed IPv6 endpoint with a shorter port is refused naming the profile`() = runTest {
        vm.restore(rulesFree(profile.copy(peer = profile.peer.copy(endpoint = "2001:db8::1:853")))) {}
        assertEquals(
            "Profile \"Home\" in the snapshot is invalid: " +
                "Endpoint must put an IPv6 address in brackets, e.g. [2001:db8::1]:51820.",
            vm.state.first { it.message != null }.message,
        )
        assertTrue(saved.isEmpty())
    }
}
