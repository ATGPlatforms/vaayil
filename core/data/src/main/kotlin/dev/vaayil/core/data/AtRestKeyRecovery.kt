package dev.vaayil.core.data

import android.util.Log
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import javax.crypto.SecretKey

/** The absence record's file name, inside [AtRestKeyRecovery]'s directory. */
internal const val AT_REST_ABSENCE_RECORD = "at-rest-key-absence.properties"

/**
 * The at-rest key acquisition policy (B4, DATA-1/DATA-5). Reading and generating are separate
 * because a *failed* Keystore read and an *absent* alias look identical to keystore2's callers:
 * taking either one for "no key" and generating would replace a real key over a transient error
 * and make every stored `enc1:` value unreadable. A key is therefore replaced only after
 * [REPLACE_AFTER_READS] absent reads spanning at least [REPLACE_AFTER_MS], with no successful
 * read in between; the evidence is persisted, so the count spans process restarts.
 *
 * The policy is driven entirely through constructor seams — the key source, the generator, the
 * clock, the encrypted-values predicate and the record directory — so a plain JUnit test drives
 * it with a [java.io.File] temporary directory and no Robolectric. Its only platform call is the
 * [Log] diagnostic in [clearRecord]; everything else is `java.*` and `javax.crypto`. Not
 * thread-safe: the at-rest crypto calls [acquire] under its own operation lock.
 */
internal class AtRestKeyRecovery(
    /** The stored key; null ONLY when the alias is absent; THROWS when a Keystore call fails. */
    private val readKey: () -> SecretKey?,
    /** Generates a fresh key, or null when it cannot. */
    private val generateKey: () -> SecretKey?,
    private val clock: () -> Long,
    /** Whether the profile database holds any `enc1:` value (the raw probe). */
    private val encryptedValuesExist: () -> Boolean,
    /** Where the absence record lives: `Context.getNoBackupFilesDir()` in production. */
    private val recordDirectory: File,
) {
    /**
     * The key to use now, or null for the S-3 plaintext posture; throws
     * [AtRestUnavailableException] while a key is expected but cannot be produced.
     */
    fun acquire(): SecretKey? {
        val read = try {
            readKey()
        } catch (_: Exception) {
            // A failed read is an outage, never an absence: it never counts toward replacement and
            // never generates. With nothing encrypted there is nothing to protect, so the S-3
            // posture stands — a Keystore that is only not ready yet must not block a fresh install.
            if (encryptedValuesExist()) throw AtRestUnavailableException()
            return null
        }
        if (read != null) {
            // Any successful read clears the record: replacement needs absences with no success between them.
            clearRecord()
            return read
        }
        if (!encryptedValuesExist()) {
            val fresh = generateKey() ?: return null
            clearRecord()
            return fresh
        }
        return replaceAfterPersistentAbsence()
    }

    /**
     * The alias is absent while encrypted values exist: this read is recorded, and a new key is
     * generated only once [REPLACE_AFTER_READS] recorded absences span [REPLACE_AFTER_MS], so a
     * transient misread (or a Keystore that is briefly unavailable) can never replace the real
     * key and unseal every stored secret.
     */
    private fun replaceAfterPersistentAbsence(): SecretKey {
        val now = clock()
        // Damaged evidence, and a clock that moved backwards, are not evidence of persistence:
        // readRecord reports them as absent, which restarts the count at this read.
        val evidence = readRecord()?.takeIf { now >= it.firstAbsentAtMs }
        val firstAbsentAtMs = evidence?.firstAbsentAtMs ?: now
        val count = (evidence?.count ?: 0) + 1
        writeRecord(firstAbsentAtMs, count)
        if (count < REPLACE_AFTER_READS || now - firstAbsentAtMs < REPLACE_AFTER_MS) {
            throw AtRestUnavailableException()
        }
        val fresh = generateKey() ?: throw AtRestUnavailableException() // still unavailable with a failed generation
        clearRecord()
        return fresh
    }

    /** The persisted evidence's shape: the record only ever carries a first read and a running count. */
    private class Record(val firstAbsentAtMs: Long, val count: Int)

    private fun recordFile() = File(recordDirectory, AT_REST_ABSENCE_RECORD)

    /** The persisted evidence, or null when the record is absent, damaged or not readable. */
    private fun readRecord(): Record? {
        val file = recordFile()
        val text = try {
            if (!file.exists()) return null
            file.readText()
        } catch (_: Exception) {
            return null // unreadable evidence is unusable evidence: the count starts over
        }
        val properties = try {
            Properties().apply { load(text.reader()) }
        } catch (_: Exception) {
            return null
        }
        if (properties.getProperty(PROPERTY_VERSION) != RECORD_VERSION) return null
        val firstAbsentAtMs = properties.getProperty(PROPERTY_FIRST_ABSENT_AT_MS)?.toLongOrNull() ?: return null
        val count = properties.getProperty(PROPERTY_COUNT)?.toIntOrNull() ?: return null
        if (firstAbsentAtMs < 0 || count < 1) return null
        return Record(firstAbsentAtMs, count)
    }

    /**
     * Records this read. Evidence that cannot be persisted must never permit replacement: the
     * exception refuses the write, and the next read starts over instead of continuing a count.
     */
    private fun writeRecord(firstAbsentAtMs: Long, count: Int) {
        try {
            writeAtomic("$PROPERTY_VERSION=$RECORD_VERSION\n$PROPERTY_FIRST_ABSENT_AT_MS=$firstAbsentAtMs\n$PROPERTY_COUNT=$count\n")
        } catch (_: Exception) {
            throw AtRestUnavailableException()
        }
    }

    /**
     * Deletes the record; never throws, because this runs on the successful-read path. A record
     * that cannot be deleted is overwritten with one the reader rejects (R2): a later absence
     * must never continue the count of a record a successful read has already invalidated.
     */
    private fun clearRecord() {
        val file = recordFile()
        if (runCatching { file.delete() }.getOrDefault(false)) return
        if (!runCatching { file.exists() }.getOrDefault(true)) return
        runCatching { writeAtomic(CLEARED_RECORD) }
            .onFailure { Log.w(TAG, "at-rest key absence record could not be cleared", it) }
    }

    /** Writes [content] to a sibling temporary file and swaps it in, so no torn write can be read. */
    private fun writeAtomic(content: String) {
        val temporary = File(recordDirectory, "$AT_REST_ABSENCE_RECORD.tmp")
        try {
            temporary.writeText(content)
            try {
                Files.move(temporary.toPath(), recordFile().toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), recordFile().toPath(), REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            runCatching { temporary.delete() }
            throw e
        }
    }

    private companion object {
        const val TAG = "AtRestKeyRecovery"
        const val PROPERTY_VERSION = "version"
        const val PROPERTY_FIRST_ABSENT_AT_MS = "firstAbsentAtMs"
        const val PROPERTY_COUNT = "count"
        const val RECORD_VERSION = "1"

        /** Twenty absent reads in all: as many as the rearms F13 already runs, 30 s apart. */
        const val REPLACE_AFTER_READS = 20

        /** And at least ten minutes from the first of them to the one making the decision. */
        const val REPLACE_AFTER_MS = 600_000L

        /** What [clearRecord] swaps in when deleting fails: a version [readRecord] always rejects. */
        const val CLEARED_RECORD = "version=0\n"
    }
}
