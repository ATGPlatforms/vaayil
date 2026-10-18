package dev.vaayil.feature.settings

import dev.vaayil.core.model.AutoConnectRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SettingsViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Rules-store stand-in: state moves only if the VM routes setters through mutateRules. */
    private fun vmOver(rules: MutableStateFlow<AutoConnectRules>): SettingsViewModel =
        SettingsViewModel(
            MutableStateFlow(SettingsFlags()),
            rules,
            { _, _ -> },
            { transform -> rules.value = transform(rules.value) },
        )

    @Test
    fun `completeOnboarding persists flag`() = runTest(UnconfinedTestDispatcher()) {
        val flags = MutableStateFlow(SettingsFlags(onboardingDone = false))
        val set = mutableMapOf<String, Boolean>()
        val vm = SettingsViewModel(
            flags,
            MutableStateFlow(AutoConnectRules()),
            { k, v ->
                set[k] = v
                if (k == "onboardingDone") flags.value = flags.value.copy(onboardingDone = v)
            },
            { },
        )
        vm.completeOnboarding()
        assertEquals(true, set.getValue("onboardingDone"))
        assertTrue(vm.ui.value.onboardingDone)
    }

    @Test
    fun `toggling wifi guard persists and updates state`() = runTest {
        val rules = MutableStateFlow(AutoConnectRules())
        val vm = vmOver(rules)
        vm.setWifiGuard(true)
        advanceUntilIdle()
        assertTrue(rules.value.wifiGuardEnabled)
        assertTrue(vm.automationUi.value.rules.wifiGuardEnabled)
    }

    @Test
    fun `add trusted ssid trims and dedups`() = runTest {
        val rules = MutableStateFlow(AutoConnectRules())
        val vm = vmOver(rules)
        vm.addTrustedSsid("Home")
        vm.addTrustedSsid(" Home ")
        advanceUntilIdle()
        assertEquals(setOf("Home"), rules.value.trustedSsids)
        assertEquals(setOf("Home"), vm.automationUi.value.rules.trustedSsids)
    }

    @Test
    fun `removing ssid persists`() = runTest {
        val rules = MutableStateFlow(AutoConnectRules())
        val vm = vmOver(rules)
        vm.addTrustedSsid("Home")
        vm.removeTrustedSsid("Home")
        advanceUntilIdle()
        assertTrue(rules.value.trustedSsids.isEmpty())
        assertTrue(vm.automationUi.value.rules.trustedSsids.isEmpty())
    }

    @Test
    fun `permission result surfaces in automationUi`() = runTest {
        val rules = MutableStateFlow(AutoConnectRules())
        val vm = vmOver(rules)
        assertEquals(PermissionState.UNKNOWN, vm.automationUi.value.permissionState)
        vm.onPermissionResult(PermissionState.DENIED)
        advanceUntilIdle()
        assertEquals(PermissionState.DENIED, vm.automationUi.value.permissionState)
        vm.onPermissionResult(PermissionState.GRANTED)
        advanceUntilIdle()
        assertEquals(PermissionState.GRANTED, vm.automationUi.value.permissionState)
    }

    @Test
    fun `permission entry reflects the platform verdict in both directions`() = runTest {
        val rules = MutableStateFlow(AutoConnectRules())
        val vm = vmOver(rules)
        vm.onPermissionEntry(PermissionState.GRANTED); advanceUntilIdle()
        assertEquals(PermissionState.GRANTED, vm.automationUi.value.permissionState)
        vm.onPermissionEntry(PermissionState.FOREGROUND_ONLY); advanceUntilIdle() // narrowed in system settings
        assertEquals(PermissionState.FOREGROUND_ONLY, vm.automationUi.value.permissionState)
        vm.onPermissionEntry(PermissionState.DENIED); advanceUntilIdle() // revoked in system settings
        assertEquals(PermissionState.DENIED, vm.automationUi.value.permissionState)
        vm.onPermissionResult(PermissionState.GRANTED); advanceUntilIdle() // a fresh prompt verdict
        assertEquals(PermissionState.GRANTED, vm.automationUi.value.permissionState)
    }

    @Test
    fun `enabling external control mints a token once and disabling keeps it`() = runTest {
        val rules = MutableStateFlow(AutoConnectRules())
        val vm = vmOver(rules)
        vm.setExternalControl(true)
        advanceUntilIdle()
        val token = rules.value.externalControlToken
        assertTrue(rules.value.externalControlEnabled)
        assertEquals(43, token!!.length)
        vm.setExternalControl(false)
        advanceUntilIdle()
        assertFalse(rules.value.externalControlEnabled)
        assertEquals(token, rules.value.externalControlToken)
        vm.regenerateExternalToken()
        advanceUntilIdle()
        assertNotEquals(token, rules.value.externalControlToken)
    }
}
