package dev.vaayil.core.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B4's raw probe. The decision to refuse plaintext must hold before Room ever opens, and must
 * see rows that still live in Room's WAL — the later F13 re-arms query it mid-session.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class EncryptedProfileValuesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseFile: File get() = context.getDatabasePath("vaayil.db")

    @After
    fun tearDown() = DataGraph.reset()

    @Test
    fun `missing database is not created`() {
        assertFalse(encryptedProfileValuesExist(context))
        assertFalse(databaseFile.exists())
    }

    @Test
    fun `no profiles table and plaintext rows hold none`() {
        rawDatabase { } // an existing database the probe can open, without Room's table
        assertFalse(encryptedProfileValuesExist(context))

        databaseFile.delete()
        rawDatabase { db ->
            db.execSQL(PROFILES_TABLE)
            db.execSQL("INSERT INTO profiles (id, privateKey, presharedKey) VALUES ('p1', 'plain', NULL)")
        }
        assertFalse(encryptedProfileValuesExist(context))
    }

    @Test
    fun `either secret column counts`() {
        rawDatabase { db ->
            db.execSQL(PROFILES_TABLE)
            db.execSQL("INSERT INTO profiles (id, privateKey, presharedKey) VALUES ('p1', 'enc1:AAAA', NULL)")
        }
        assertTrue(encryptedProfileValuesExist(context))

        databaseFile.delete()
        rawDatabase { db ->
            db.execSQL(PROFILES_TABLE)
            db.execSQL("INSERT INTO profiles (id, privateKey, presharedKey) VALUES ('p2', 'plain', 'enc1:AAAA')")
        }
        assertTrue(encryptedProfileValuesExist(context))
    }

    @Test
    fun `unreadable existing database fails safe`() {
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeText("this file is not a SQLite database at all")
        assertTrue(encryptedProfileValuesExist(context))
    }

    @Test
    fun `probe sees encrypted rows in live Room WAL`() {
        val database = buildDatabase(context)
        try {
            val sql = database.openHelper.writableDatabase // a real open: Room creates the schema in WAL mode
            sql.query("PRAGMA wal_autocheckpoint = 0").use { }
            assertFalse(encryptedProfileValuesExist(context))
            sql.execSQL(
                "INSERT INTO profiles (id, name, privateKey, addresses, dnsServers, peerPublicKey, " +
                    "endpoint, allowedIps, createdAt, updatedAt, dnsMode, dnsRules) " +
                    "VALUES ('p1', 'P', 'enc1:AAAA', '[]', '[]', 'peer', '1.2.3.4:51820', '[]', 1, 1, 'TUNNEL', '[]')",
            )
            // No checkpoint ran: the committed row is in the WAL, not yet in the main file.
            val wal = File(databaseFile.path + "-wal")
            assertTrue("expected a non-empty WAL, but found ${wal.length()} bytes", wal.length() > 0)
            assertTrue(encryptedProfileValuesExist(context))
        } finally {
            database.close()
        }
    }

    private fun rawDatabase(block: (SQLiteDatabase) -> Unit) {
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use(block)
    }

    private companion object {
        const val PROFILES_TABLE =
            "CREATE TABLE profiles (id TEXT NOT NULL PRIMARY KEY, privateKey TEXT NOT NULL, presharedKey TEXT)"
    }
}
