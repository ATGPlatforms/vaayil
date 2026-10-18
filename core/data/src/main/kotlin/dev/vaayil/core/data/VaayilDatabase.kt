package dev.vaayil.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProfileEntity::class], version = 3, exportSchema = true)
@TypeConverters(Converters::class)
abstract class VaayilDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
}

/**
 * S-6, defence in depth rather than a fix for an active defect: Android's SQLite is compiled with
 * secure delete on by default (`-DSQLITE_SECURE_DELETE` in AOSP's libsqlite; measured under
 * Robolectric), so on current Android the protection is already on and this call changes no
 * observable behaviour. It stays because a security control should not rest on a platform compile
 * flag we do not control: on a SQLite build that does not default it on, the DROP TABLE in
 * MIGRATION_2_3 is where pages holding pre-encryption key material would reach the freelist
 * un-zeroed. The pragma is per connection, so each migration sets it on its own. There is
 * deliberately no behavioural test: with the platform default on, such a test passes with or
 * without this call. The query form is required because execSQL rejects the assignment form.
 */
private fun secureDeleteOn(db: SupportSQLiteDatabase) {
    db.query("PRAGMA secure_delete = ON").use { }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        secureDeleteOn(db)
        db.execSQL("ALTER TABLE profiles ADD COLUMN dnsMode TEXT NOT NULL DEFAULT 'TUNNEL'")
        db.execSQL("ALTER TABLE profiles ADD COLUMN dnsSecureType TEXT")
        db.execSQL("ALTER TABLE profiles ADD COLUMN dnsSecureEndpoint TEXT")
        db.execSQL("ALTER TABLE profiles ADD COLUMN dnsRules TEXT NOT NULL DEFAULT '[]'")
    }
}

// Canonical table recreate (DROP COLUMN needs SQLite 3.35+, minSdk 26 ships older).
// Drops the four dormant columns: splitMode/splitPackages (superseded by the global
// SplitTunnelRulesStore) and providerId/providerServerId (retired provider feature).
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        secureDeleteOn(db)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profiles_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`privateKey` TEXT NOT NULL, `addresses` TEXT NOT NULL, `dnsServers` TEXT NOT NULL, " +
                "`mtu` INTEGER, `listenPort` INTEGER, `peerPublicKey` TEXT NOT NULL, `presharedKey` TEXT, " +
                "`endpoint` TEXT NOT NULL, `allowedIps` TEXT NOT NULL, `keepalive` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "`dnsMode` TEXT NOT NULL, `dnsSecureType` TEXT, `dnsSecureEndpoint` TEXT, " +
                "`dnsRules` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO `profiles_new` " +
                "(`id`,`name`,`privateKey`,`addresses`,`dnsServers`,`mtu`,`listenPort`," +
                "`peerPublicKey`,`presharedKey`,`endpoint`,`allowedIps`,`keepalive`," +
                "`createdAt`,`updatedAt`,`dnsMode`,`dnsSecureType`,`dnsSecureEndpoint`,`dnsRules`) " +
                "SELECT `id`,`name`,`privateKey`,`addresses`,`dnsServers`,`mtu`,`listenPort`," +
                "`peerPublicKey`,`presharedKey`,`endpoint`,`allowedIps`,`keepalive`," +
                "`createdAt`,`updatedAt`,`dnsMode`,`dnsSecureType`,`dnsSecureEndpoint`,`dnsRules` " +
                "FROM `profiles`",
        )
        db.execSQL("DROP TABLE `profiles`")
        db.execSQL("ALTER TABLE `profiles_new` RENAME TO `profiles`")
    }
}

/** Deleted or rewritten rows must not leave key material in SQLite free pages (S-6). */
private val secureDelete = object : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        secureDeleteOn(db)
    }
}

fun buildDatabase(context: Context): VaayilDatabase =
    Room.databaseBuilder(context, VaayilDatabase::class.java, "vaayil.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .addCallback(secureDelete)
        .build()

fun buildInMemoryDatabase(context: Context): VaayilDatabase =
    Room.inMemoryDatabaseBuilder(context, VaayilDatabase::class.java)
        .addCallback(secureDelete)
        .allowMainThreadQueries().build()
