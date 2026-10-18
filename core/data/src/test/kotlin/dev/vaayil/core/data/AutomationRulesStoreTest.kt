package dev.vaayil.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.AutoConnectRules
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AutomationRulesStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AutomationRulesStore(context)

    @Before
    fun reset() = runTest { store.mutate { _ -> AutoConnectRules() } }

    @Test
    fun `every field round-trips through snapshot`() = runTest {
        store.setWifiGuard(true)
        store.setDisconnectOnTrusted(true)
        store.setConnectOnBoot(true)
        store.addTrustedSsid("Home")
        val on = store.snapshot()
        assertTrue(on.wifiGuardEnabled)
        assertTrue(on.disconnectOnTrusted)
        assertTrue(on.connectOnBoot)
        assertEquals(setOf("Home"), on.trustedSsids)

        store.setWifiGuard(false)
        store.setDisconnectOnTrusted(false)
        store.setConnectOnBoot(false)
        store.removeTrustedSsid("Home")
        val off = store.snapshot()
        assertFalse(off.wifiGuardEnabled)
        assertFalse(off.disconnectOnTrusted)
        assertFalse(off.connectOnBoot)
        assertEquals(emptySet(), off.trustedSsids)
    }

    @Test
    fun `addTrustedSsid trims and dedups`() = runTest {
        store.addTrustedSsid(" Home ")
        store.addTrustedSsid("Home")
        store.addTrustedSsid("   ")
        assertEquals(setOf("Home"), store.snapshot().trustedSsids)
    }

    @Test
    fun `removeTrustedSsid of absent value is a no-op`() = runTest {
        store.addTrustedSsid("Home")
        store.removeTrustedSsid("Cafe")
        store.removeTrustedSsid("   ")
        assertEquals(setOf("Home"), store.snapshot().trustedSsids)
        store.removeTrustedSsid("Home")
        assertEquals(emptySet(), store.snapshot().trustedSsids)
    }

    @Test
    fun `external control fields round-trip and default off`() = runTest {
        val store = AutomationRulesStore(context)
        val initial = store.snapshot()
        assertFalse(initial.externalControlEnabled)
        assertNull(initial.externalControlToken)
        store.mutate { it.copy(externalControlEnabled = true, externalControlToken = "tok") }
        val after = store.snapshot()
        assertTrue(after.externalControlEnabled)
        assertEquals("tok", after.externalControlToken)
        store.mutate { it.copy(externalControlEnabled = false) }
        assertEquals("tok", store.snapshot().externalControlToken) // token survives a disable
    }

    @Test
    fun `generated tokens are 43 url-safe characters and unique`() {
        val a = generateExternalControlToken()
        val b = generateExternalControlToken()
        assertEquals(43, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(a, b)
    }
}
