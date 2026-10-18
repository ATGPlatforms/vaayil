package dev.vaayil.core.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B4's wiring: the graph must know a Context before it builds the crypto (the absence policy
 * reads the profile database file raw), the singleton must survive every entry point, and a
 * bound graph must refuse plaintext while the database holds encrypted rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class DataGraphTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = DataGraph.reset()

    @Test
    fun `bound graph keeps crypto singleton before first Room open`() {
        DataGraph.database(context)
        assertSame(DataGraph.crypto(), DataGraph.crypto())
        assertFalse(context.getDatabasePath("vaayil.db").exists())

        DataGraph.reset()
        DataGraph.repository(context)
        assertSame(DataGraph.crypto(), DataGraph.crypto())
        assertFalse(context.getDatabasePath("vaayil.db").exists())
    }

    @Test
    fun `bound graph refuses plaintext over encrypted rows`() {
        seedEncryptedRow()
        DataGraph.database(context)
        val crypto = DataGraph.crypto()
        // Robolectric has no AndroidKeyStore, so the read throws; the encrypted row outranks that.
        crypto.warm()
        assertThrows(AtRestUnavailableException::class.java) { crypto.encrypt("secret") }
        assertFalse(crypto.active.value)

        DataGraph.reset()
        assertThrows(IllegalStateException::class.java) { DataGraph.crypto() }
    }

    private fun seedEncryptedRow() {
        val file = context.getDatabasePath("vaayil.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE profiles (id TEXT, privateKey TEXT, presharedKey TEXT)")
            db.execSQL("INSERT INTO profiles (id, privateKey, presharedKey) VALUES ('p1', 'enc1:AAAA', NULL)")
        }
    }
}
