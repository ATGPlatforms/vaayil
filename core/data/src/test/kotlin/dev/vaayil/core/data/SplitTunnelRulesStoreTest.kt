package dev.vaayil.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.vaayil.core.model.SplitTunnelMode
import dev.vaayil.core.model.SplitTunnelPolicy
import dev.vaayil.core.model.SplitTunnelSelections
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SplitTunnelRulesStoreTest {
    private lateinit var db: VaayilDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = buildInMemoryDatabase(context)
        runTest {
            SplitTunnelRulesStore(context).clear()
            ActiveProfileStore(context).apply {
                setActive(null)
                setLastConnected(null)
            }
        }
    }

    @After
    fun tearDown() {
        db.close()
    }


    @Test
    fun `set and snapshot round-trip`() = runTest {
        val store = SplitTunnelRulesStore(context)
        val rules = SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.a"))
        store.set(rules)
        assertEquals(rules, store.snapshot())
    }

    @Test
    fun `selections round-trip both mode sets`() = runTest {
        val store = SplitTunnelRulesStore(context)
        val selections = SplitTunnelSelections(
            SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"), included = setOf("com.b"))
        store.setSelections(selections)
        assertEquals(selections, store.snapshotSelections())
        // snapshot() derives the active policy from the active mode's set.
        assertEquals(SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, setOf("com.a")), store.snapshot())
        store.setSelections(selections.copy(mode = SplitTunnelMode.INCLUDE))
        assertEquals(SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.b")), store.snapshot())
    }

    @Test
    fun `legacy keys migrate the stored set into its mode's slot`() = runTest {
        val store = SplitTunnelRulesStore(context)
        store.seedLegacy(SplitTunnelMode.INCLUDE, setOf("com.a"))
        assertEquals(
            SplitTunnelSelections(SplitTunnelMode.INCLUDE, included = setOf("com.a")),
            store.snapshotSelections(),
        )
        assertEquals(SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.a")), store.snapshot())
        store.clear()
        store.seedLegacy(SplitTunnelMode.EXCLUDE, setOf("com.e"))
        assertEquals(
            SplitTunnelSelections(SplitTunnelMode.EXCLUDE, excluded = setOf("com.e")),
            store.snapshotSelections(),
        )
    }

    @Test
    fun `legacy-era stores still count as stored so promotion stays sealed`() = runTest {
        val store = SplitTunnelRulesStore(context)
        store.seedLegacy(SplitTunnelMode.EXCLUDE, setOf("com.a"))
        assertTrue(store.hasStoredValue())
    }

    @Test
    fun `set preserves the other mode's slot`() = runTest {
        val store = SplitTunnelRulesStore(context)
        store.setSelections(
            SplitTunnelSelections(
                SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"), included = setOf("com.b")))
        store.set(SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.x")))
        assertEquals(
            SplitTunnelSelections(
                SplitTunnelMode.INCLUDE, excluded = setOf("com.a"), included = setOf("com.x")),
            store.snapshotSelections(),
        )
    }

    @Test
    fun `Allow local network defaults to off`() = runTest {
        val store = SplitTunnelRulesStore(context)
        assertFalse(store.snapshotSelections().allowLocalNetwork)
        assertFalse(store.snapshot().allowLocalNetwork)
    }

    @Test
    fun `Allow local network persists with the rules`() = runTest {
        val selections = SplitTunnelSelections(
            SplitTunnelMode.EXCLUDE, excluded = setOf("com.a"), included = setOf("com.b"),
            allowLocalNetwork = true)
        SplitTunnelRulesStore(context).setSelections(selections)
        assertEquals(selections, SplitTunnelRulesStore(context).snapshotSelections())
    }

    @Test
    fun `set writes the policy's Allow local network`() = runTest {
        val store = SplitTunnelRulesStore(context)
        val policy = SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.a"), allowLocalNetwork = true)
        store.set(policy)
        assertEquals(policy, store.snapshot())
    }

    /** Minimal pre-migration profiles table: everything the rescue reads. */
    private fun openLegacyDb() =
        context.openOrCreateDatabase("vaayil.db", Context.MODE_PRIVATE, null).also { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS profiles (" +
                    "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                    "splitMode TEXT NOT NULL, splitPackages TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
            )
        }

    @Test
    fun `rescue seeds the store from the active profile's raw row`() = runTest {
        openLegacyDb().execSQL(
            "INSERT INTO profiles VALUES ('p1', 'Other', 'EXCLUDE', '[\"com.b\"]', 1)," +
                "('p2', 'Active', 'INCLUDE', '[\"com.a\"]', 2)",
        )
        val active = ActiveProfileStore(context).apply { setActive("p2") }
        val store = SplitTunnelRulesStore(context)
        rescueLegacySplitTunnelRules(store, context, active)
        assertEquals(SplitTunnelPolicy(SplitTunnelMode.INCLUDE, setOf("com.a")), store.snapshot())
        assertTrue(store.hasStoredValue())
    }

    @Test
    fun `rescue falls back to the last connected profile`() = runTest {
        openLegacyDb().execSQL(
            "INSERT INTO profiles VALUES ('p1', 'Last', 'EXCLUDE', '[\"com.c\"]', 1)",
        )
        val active = ActiveProfileStore(context).apply { setLastConnected("p1") }
        val store = SplitTunnelRulesStore(context)
        rescueLegacySplitTunnelRules(store, context, active)
        assertEquals(SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, setOf("com.c")), store.snapshot())
    }

    @Test
    fun `rescue is a one-time seal`() = runTest {
        val db = openLegacyDb()
        db.execSQL(
            "INSERT INTO profiles VALUES ('p1', 'Home', 'EXCLUDE', '[\"com.a\"]', 1)",
        )
        val active = ActiveProfileStore(context).apply { setActive("p1") }
        val store = SplitTunnelRulesStore(context)
        rescueLegacySplitTunnelRules(store, context, active)
        // A later change to the dormant columns must not re-seed.
        db.execSQL("UPDATE profiles SET splitPackages = '[\"com.z\"]'")
        rescueLegacySplitTunnelRules(store, context, active)
        assertEquals(setOf("com.a"), store.snapshot().packages)
    }

    @Test
    fun `fresh install without legacy columns is a no-op`() = runTest {
        val store = SplitTunnelRulesStore(context)
        rescueLegacySplitTunnelRules(store, context, ActiveProfileStore(context))
        assertEquals(SplitTunnelPolicy(SplitTunnelMode.EXCLUDE, emptySet()), store.snapshot())
        assertFalse(store.hasStoredValue())
    }
}
