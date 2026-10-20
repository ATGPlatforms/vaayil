package dev.vaayil.feature.profiles

import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelSelections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SplitTunnelViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val selections = MutableStateFlow(SplitTunnelSelections.INITIAL)
    private val saved = mutableListOf<SplitTunnelSelections>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // Production parity: the save writes the store, whose flow re-emits into the VM.
    private fun vm() = SplitTunnelViewModel(selections) { sel ->
        saved += sel
        selections.value = sel
    }

    private fun apps() = listOf(AppInfo("com.a", "A"), AppInfo("com.b", "B"))

    @Test
    fun `state hydrates from the persisted selections`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections(SplitTunnelMode.INCLUDE, included = setOf("com.a"))
        val vm = vm()
        assertEquals(SplitTunnelMode.INCLUDE, vm.state.value.mode)
        assertEquals(setOf("com.a"), vm.state.value.selectedPackages)
    }

    @Test
    fun `an external selections change syncs into state`() = runTest(dispatcher) {
        val vm = vm()
        selections.value = SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.x"))
        assertEquals(setOf("com.x"), vm.state.value.selectedPackages)
    }

    @Test
    fun `a stored inclusive policy with no apps is flagged on hydration`() = runTest(dispatcher) {
        // A restore (or a legacy store) can hold an inclusive policy with an empty list: the engine
        // applies no restriction for it, so the tab must say why rather than show an empty include
        // list as if it were a normal state.
        selections.value = SplitTunnelSelections(SplitTunnelMode.INCLUDE, included = emptySet())
        val vm = vm()
        assertEquals(SplitTunnelMode.INCLUDE, vm.state.value.mode)
        assertEquals(emptySet<String>(), vm.state.value.selectedPackages)
        assertTrue(vm.state.value.includeListEmpty)
    }

    @Test
    fun `a stored inclusive policy with apps is not flagged`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections(SplitTunnelMode.INCLUDE, included = setOf("com.a"))
        val vm = vm()
        assertFalse(vm.state.value.includeListEmpty)
    }

    @Test
    fun `mode switch persists both lists and survives the save echo`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"))
        val vm = vm()
        vm.load(apps())
        vm.setMode(SplitTunnelMode.INCLUDE)
        assertEquals(emptySet(), vm.state.value.selectedPackages)
        vm.setMode(SplitTunnelMode.EXCLUDE)
        assertEquals(setOf("com.a"), vm.state.value.selectedPackages)
        vm.setMode(SplitTunnelMode.INCLUDE)
        vm.togglePackage("com.b")
        advanceUntilIdle() // save lands and echoes through the selections flow
        // Both mode lists ride in one persisted object: the other mode's list survives
        // sessions, not just this process (known-issue P2).
        assertEquals(
            listOf(
                SplitTunnelSelections(
                    SplitTunnelMode.INCLUDE, excluded = setOf("com.a"), included = setOf("com.b"))),
            saved,
        )
        // The echo must not wipe the other mode's persisted list.
        vm.setMode(SplitTunnelMode.EXCLUDE)
        assertEquals(setOf("com.a"), vm.state.value.selectedPackages)
        advanceUntilIdle()
    }

    @Test
    fun `route all and bypass all mean all-or-nothing in both modes`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections.INITIAL
        val vm = vm()
        vm.load(apps())
        // Exclusive: bypass all = every loaded app travels outside the tunnel.
        vm.bypassAll()
        assertEquals(setOf("com.a", "com.b"), vm.state.value.selectedPackages)
        // Exclusive: route all = nothing is excluded.
        vm.routeAll()
        assertEquals(emptySet(), vm.state.value.selectedPackages)
        vm.setMode(SplitTunnelMode.INCLUDE)
        // Inclusive: route all = every loaded app travels through the tunnel.
        vm.routeAll()
        assertEquals(setOf("com.a", "com.b"), vm.state.value.selectedPackages)
        // Inclusive: bypass all = nothing is included.
        vm.bypassAll()
        assertEquals(emptySet(), vm.state.value.selectedPackages)
        vm.togglePackage("com.a") // end on a VALID inclusive edit: the refused bypassAll persists nothing
        advanceUntilIdle()
        assertTrue(saved.isNotEmpty())
        // The Exclusive-mode edits made before the mode switch persisted alongside.
        assertEquals(setOf<String>(), saved.last().excluded)
    }

    @Test
    fun `an inclusive policy with no apps is refused and flagged instead of saved`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"))
        val vm = vm()
        vm.load(apps())
        vm.setMode(SplitTunnelMode.INCLUDE)
        advanceUntilIdle()
        assertTrue(saved.isEmpty())                  // the previous rules stay in force
        assertTrue(vm.state.value.includeListEmpty)  // and the tab says why
        vm.togglePackage("com.b")
        advanceUntilIdle()
        assertEquals(setOf("com.b"), saved.single().included)
        assertFalse(vm.state.value.includeListEmpty)
    }

    @Test
    fun `the list is loading until apps arrive`() = runTest(dispatcher) {
        val vm = vm()
        assertTrue(vm.state.value.loading)
        vm.load(emptyList())
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `a refused edit cancels a valid edit still waiting in the debounce window`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections(SplitTunnelMode.INCLUDE, included = setOf("com.a"))
        val vm = vm()
        vm.load(apps())
        vm.togglePackage("com.b") // included = {com.a, com.b}: valid, a save is scheduled
        vm.togglePackage("com.a") // included = {com.b}: still valid, reschedules
        vm.togglePackage("com.b") // included = {}: refused — the pending save must be cancelled too
        advanceUntilIdle()
        assertTrue(saved.isEmpty())
        assertTrue(vm.state.value.includeListEmpty)
    }

    @Test
    fun `the switch hydrates from the store`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections.INITIAL.copy(allowLocalNetwork = true)
        val vm = vm()
        assertTrue(vm.state.value.allowLocalNetwork)
    }

    @Test
    fun `flipping the switch saves it with the app rules unchanged`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"))
        val vm = vm()
        vm.setAllowLocalNetwork(true)
        advanceUntilIdle()
        assertEquals(
            listOf(
                SplitTunnelSelections(
                    SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"), allowLocalNetwork = true)),
            saved,
        )
    }

    @Test
    fun `a flip during a refused inclusive edit saves the flag with the stored rules`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"))
        val vm = vm()
        vm.setMode(SplitTunnelMode.INCLUDE) // refused: an inclusive policy with no apps is never saved
        vm.setAllowLocalNetwork(true)
        advanceUntilIdle()
        // The refused app edit stays on screen, but the switch itself is a valid change and saves
        // with the rules the store last accepted.
        assertEquals(
            listOf(
                SplitTunnelSelections(
                    SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"), allowLocalNetwork = true)),
            saved,
        )
        assertEquals(SplitTunnelMode.INCLUDE, vm.state.value.mode)
        assertTrue(vm.state.value.includeListEmpty)
        assertTrue(vm.state.value.allowLocalNetwork)
    }

    @Test
    fun `a refused edit with the flag flipped back saves nothing`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"))
        val vm = vm()
        vm.setMode(SplitTunnelMode.INCLUDE)
        vm.setAllowLocalNetwork(true)
        vm.setAllowLocalNetwork(false)
        advanceUntilIdle()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a flag-only change from the store keeps a refused edit and carries the new flag`() = runTest(dispatcher) {
        selections.value = SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"))
        val vm = vm()
        vm.setMode(SplitTunnelMode.INCLUDE) // refused: an inclusive policy with no apps is never saved
        // Another writer, such as a restore, changes only the switch while the refused edit is on screen.
        selections.value =
            SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"), allowLocalNetwork = true)
        advanceUntilIdle()
        assertTrue(vm.state.value.allowLocalNetwork)
        assertEquals(SplitTunnelMode.INCLUDE, vm.state.value.mode)
        assertTrue(vm.state.value.includeListEmpty)
        assertTrue(saved.isEmpty())
        // The next valid edit saves with the store's new flag, not the one the tab started with.
        vm.togglePackage("com.b")
        advanceUntilIdle()
        assertEquals(
            listOf(
                SplitTunnelSelections(
                    SplitTunnelMode.INCLUDE,
                    excluded = setOf("com.a"),
                    included = setOf("com.b"),
                    allowLocalNetwork = true,
                ),
            ),
            saved,
        )
    }
}
