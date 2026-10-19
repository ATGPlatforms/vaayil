package dev.vaayil.core.data

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.AutoConnectRules
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.model.SplitTunnelSelections
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B5: an unparsable preference file must reset to defaults instead of crash-looping every process
 * start (`Application.onCreate` reads the stores).
 *
 * A `preferencesDataStore` delegate lives for the life of one Robolectric sandbox, and so does the
 * file the first caller opened it with: a class sharing this sandbox with a store suite that ran
 * earlier would read that suite's already-open delegate and never see the garbage on disk (R1).
 * This class therefore runs in a sandbox of its own:
 * - `sdk = [35]` keeps it out of the module's default SDK-26 sandbox, where ActiveProfileStoreTest,
 *   AutomationRulesStoreTest and SplitTunnelRulesStoreTest run;
 * - [Config.instrumentedPackages] names a package no class uses. It changes only Robolectric's
 *   sandbox key (`InstrumentationConfiguration`), keeping this class out of the SDK-35 sandbox the
 *   crypto and graph suites share even if one of them ever opens a store.
 * A wrongly shared delegate cannot pass silently: the reads would return that delegate's own,
 * intact data and the per-store reset-warning assertions below would fail.
 * [NoStoresApplication] additionally keeps any app-level construction away from the files.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [35],
    application = CoreStoreCorruptionTest.NoStoresApplication::class,
    instrumentedPackages = ["dev.vaayil.core.data.corruptiontest.isolation"],
)
class CoreStoreCorruptionTest {
    class NoStoresApplication : Application()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `garbage core preference files reset to defaults and remain writable`() = runTest {
        seedGarbage("vaayil_settings")
        seedGarbage("vaayil_automation")
        seedGarbage("vaayil_split_tunnel")
        ShadowLog.clear()

        val active = ActiveProfileStore(context)
        val automation = AutomationRulesStore(context)
        val split = SplitTunnelRulesStore(context)

        // Reads return the safe defaults instead of throwing CorruptionException.
        assertNull(active.activeId.first())
        assertNull(active.lastConnectedId.first())
        assertNull(active.takeResumableActive())
        val rules = automation.snapshot()
        assertEquals(AutoConnectRules(), rules)
        assertFalse(rules.externalControlEnabled)
        assertNull(rules.externalControlToken)
        assertEquals(SplitTunnelSelections.INITIAL, split.snapshotSelections())
        assertFalse(split.snapshotSelections().allowLocalNetwork)
        assertEquals(SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, emptySet()), split.snapshot())
        assertFalse(split.hasStoredValue())

        // One reset warning per store, by tag, without the file's contents.
        listOf("ActiveProfileStore", "AutomationRulesStore", "SplitTunnelRulesStore").forEach { tag ->
            val warnings = ShadowLog.getLogsForTag(tag).filter { it.type == Log.WARN }.map { it.msg }
            assertTrue(warnings.size == 1 && warnings.single().contains("reset"), "reset warning for $tag: $warnings")
        }

        // The reset files are live stores, not read-only stubs.
        active.setActive("p1")
        assertEquals("p1", active.activeId.first())
        automation.setWifiGuard(true)
        assertTrue(automation.snapshot().wifiGuardEnabled)
        split.set(SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.example.tunnelled")))
        assertEquals(
            SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.example.tunnelled")),
            split.snapshot(),
        )
    }

    /** `0x0f` is not a valid preferences protobuf: the reader throws `CorruptionException`. */
    private fun seedGarbage(name: String) {
        val file = context.preferencesDataStoreFile(name)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x0f))
    }
}
