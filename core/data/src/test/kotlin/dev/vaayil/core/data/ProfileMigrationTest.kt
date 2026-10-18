package dev.vaayil.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs MIGRATION_1_2 + MIGRATION_2_3 the way an upgraded install does, then validates
 * the result against Room's OWN expected schema (bundled 3.json) — the hand-written-SQL
 * failure mode (column order/affinity drift) fails here, which is exactly the crash a
 * real v2 install would hit at first DAO access on startup.
 */
@RunWith(AndroidJUnit4::class)
class ProfileMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VaayilDatabase::class.java,
    )

    @Test
    fun `migrate 2 to 3 drops dormant columns and preserves data`() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            // Full-era row: split values set, provider set, secure DNS configured.
            db.execSQL(
                """
                INSERT INTO profiles (
                    id, name, privateKey, addresses, dnsServers, mtu, listenPort,
                    peerPublicKey, presharedKey, endpoint, allowedIps, keepalive,
                    splitMode, splitPackages, providerId, providerServerId,
                    createdAt, updatedAt, dnsMode, dnsSecureType, dnsSecureEndpoint, dnsRules
                ) VALUES (
                    'p1', 'Home', '$KEY44', '["10.0.0.2/32"]', '["1.1.1.1"]', 1420, NULL,
                    '$KEY44', NULL, 'vpn.example.com:51820', '["0.0.0.0/0"]', 25,
                    'INCLUDE', '["com.a","com.b"]', 'provider-a', 'server-1',
                    100, 200, 'SECURE', 'DOH', 'https://9.9.9.9/dns-query',
                    '[{"domain":"internal.corp","bypass":true}]'
                )
                """.trimIndent(),
            )
            // Pre-provider row: NULL optionals, tunnel DNS.
            db.execSQL(
                """
                INSERT INTO profiles (
                    id, name, privateKey, addresses, dnsServers, mtu, listenPort,
                    peerPublicKey, presharedKey, endpoint, allowedIps, keepalive,
                    splitMode, splitPackages, providerId, providerServerId,
                    createdAt, updatedAt, dnsMode, dnsSecureType, dnsSecureEndpoint, dnsRules
                ) VALUES (
                    'p2', 'Second', '$KEY44', '["10.0.0.3/32"]', '["9.9.9.9"]', NULL, 51820,
                    '$KEY44', NULL, 'vpn2.example.com:51820', '["0.0.0.0/0"]', NULL,
                    'EXCLUDE', '[]', NULL, NULL,
                    300, 400, 'TUNNEL', NULL, NULL, '[]'
                )
                """.trimIndent(),
            )
        }

        // Runs the real migrations and validates the schema against Room's 3.json.
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3)

        db.query("SELECT * FROM profiles WHERE id = 'p1'").use { c ->
            assertTrue(c.moveToFirst(), "p1 missing after migration")
            val names = (0 until c.columnCount).associate { c.getColumnName(it) to it }
            // The four dormant columns must be gone:
            assertTrue(!names.containsKey("splitMode"), "splitMode survived")
            assertTrue(!names.containsKey("splitPackages"), "splitPackages survived")
            assertTrue(!names.containsKey("providerId"), "providerId survived")
            assertTrue(!names.containsKey("providerServerId"), "providerServerId survived")
            assertEquals("vpn.example.com:51820", c.getString(names.getValue("endpoint")))
            assertEquals("SECURE", c.getString(names.getValue("dnsMode")))
            assertEquals("DOH", c.getString(names.getValue("dnsSecureType")))
            assertEquals("[{\"domain\":\"internal.corp\",\"bypass\":true}]", c.getString(names.getValue("dnsRules")))
            assertEquals(100L, c.getLong(names.getValue("createdAt")))
        }
        db.query("SELECT * FROM profiles WHERE id = 'p2'").use { c ->
            assertTrue(c.moveToFirst(), "p2 missing after migration")
            val names = (0 until c.columnCount).associate { c.getColumnName(it) to it }
            assertNull(c.getString(names.getValue("presharedKey")))
            assertNull(c.getString(names.getValue("dnsSecureType")))
            assertEquals("TUNNEL", c.getString(names.getValue("dnsMode")))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
        private const val KEY44 = "eCyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy0="
    }
}
