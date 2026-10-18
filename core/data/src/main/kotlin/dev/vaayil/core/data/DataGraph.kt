package dev.vaayil.core.data

import android.content.Context
import androidx.annotation.VisibleForTesting

/**
 * One database, one Keystore handle, one repository per process. AppContainer and every
 * receiver or service that needs profiles come through here; a second Room instance on
 * vaayil.db or a second KeystoreAtRestCrypto is a bug (S-3, S-4).
 *
 * The crypto carries the key-absence policy (B4), whose encrypted-values probe reads the
 * profile database file raw — before Room's first open — so the graph must know a Context
 * before [crypto] can be built: entering through [database] or [repository] binds it, and the
 * retained application context exists for that probe alone. AppContainer calls
 * `database(...)`/`repository(...)` before `crypto().warm()`.
 */
object DataGraph {
    @Volatile private var database: VaayilDatabase? = null
    @Volatile private var repository: ProfileRepository? = null
    @Volatile private var crypto: KeystoreAtRestCrypto? = null

    /** What [database] or [repository] bound the graph with; [reset] clears it. */
    @Volatile private var appContext: Context? = null

    /**
     * The process's at-rest crypto. Requires a bound Context: an unbound graph fails here rather
     * than handing out crypto that can never tell encrypted values from none (B4).
     */
    fun crypto(): KeystoreAtRestCrypto = crypto ?: synchronized(this) {
        crypto ?: buildCrypto(
            appContext ?: error("DataGraph needs a Context first: call database(context) or repository(context)"),
        ).also { crypto = it }
    }

    fun database(context: Context): VaayilDatabase = database ?: synchronized(this) {
        database ?: buildDatabase(bind(context)).also { database = it }
    }

    fun repository(context: Context): ProfileRepository = repository ?: synchronized(this) {
        repository ?: ProfileRepository(database(context).profileDao(), crypto()).also { repository = it }
    }

    /** Retains [context]'s application context: every entry point that has one binds the graph. */
    private fun bind(context: Context): Context =
        (context.applicationContext ?: context).also { appContext = it }

    /** The guarded at-rest key source (B4): the recovery owns the read/generate split and the evidence. */
    private fun buildCrypto(context: Context): KeystoreAtRestCrypto {
        val recovery = AtRestKeyRecovery(
            readKey = ::androidKeystoreKey,
            generateKey = ::generateAndroidKeystoreKey,
            clock = System::currentTimeMillis,
            encryptedValuesExist = { encryptedProfileValuesExist(context) },
            recordDirectory = context.noBackupFilesDir,
        )
        return KeystoreAtRestCrypto(scheduleRearm = AtRestRearm::schedule, keySource = recovery::acquire)
    }

    /** Tests only: Robolectric gives every test a fresh Application, so the singletons must go too. */
    @VisibleForTesting
    fun reset() {
        synchronized(this) {
            runCatching { database?.close() }
            database = null
            repository = null
            crypto = null
            appContext = null
        }
    }
}
