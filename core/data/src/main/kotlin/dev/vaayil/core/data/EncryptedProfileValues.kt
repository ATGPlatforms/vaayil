package dev.vaayil.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** Room's database file (VaayilDatabase.buildDatabase); repeated here so the probe needs no Room. */
private const val DATABASE_FILE = "vaayil.db"

/**
 * Whether the profile database holds any at-rest-encrypted secret (B4). Read straight from the
 * SQLite file, without Room: the at-rest key is warmed before Room's first open, so the absence
 * policy's "is anything encrypted?" question cannot go through the database layer. The same
 * probe runs again on F13's later re-arms, against the live database's WAL.
 *
 * A missing file and a database without the `profiles` table hold none. A file that exists but
 * cannot be scanned counts as holding some (fail safe): an unreadable database must never be
 * taken for a fresh install and let plaintext writes replace the encrypted rows' key.
 *
 * Only a boolean leaves this function — never a stored value.
 */
internal fun encryptedProfileValuesExist(context: Context): Boolean {
    val file = context.getDatabasePath(DATABASE_FILE)
    return try {
        if (!file.exists()) {
            false
        } else {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                if (!profilesTableExists(db)) {
                    false
                } else {
                    db.rawQuery(ENCRYPTED_VALUES_SQL, null).use { cursor ->
                        cursor.moveToFirst() && cursor.getInt(0) != 0
                    }
                }
            }
        }
    } catch (_: Exception) {
        true
    }
}

/** A database Room has not opened yet holds no profiles table, and therefore no encrypted value. */
private fun profilesTableExists(db: SQLiteDatabase): Boolean =
    db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'profiles' LIMIT 1", null)
        .use { it.moveToFirst() }

private val ENCRYPTED_VALUES_SQL =
    "SELECT EXISTS(SELECT 1 FROM profiles WHERE privateKey LIKE '${AtRestCrypto.MARKER}%' " +
        "OR presharedKey LIKE '${AtRestCrypto.MARKER}%')"
