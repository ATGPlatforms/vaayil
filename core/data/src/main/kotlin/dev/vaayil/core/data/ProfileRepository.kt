package dev.vaayil.core.data

import dev.vaayil.core.config.WgConfigSerializer
import dev.vaayil.core.config.toWgQuickConfig
import dev.vaayil.core.model.Profile
import dev.vaayil.core.model.validate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Thrown by [ProfileRepository.save] when the model rejects the profile; nothing is written. */
class InvalidProfileException(val errors: List<String>) :
    IllegalArgumentException("Profile is invalid: ${errors.first()}")

/**
 * At-rest posture: whether secrets are encrypted, the rows whose secrets no longer read, and
 * whether writes are refused. While no key is held, [writesRefused] tells the two postures
 * apart: false means nothing is encrypted yet and writes are stored as plaintext (S-3); true
 * means a key is expected — one was held earlier in this process, or encrypted rows exist
 * without a key (DATA-5) — so profiles are read-only until it is back (F13, R6).
 */
data class ProfilesHealth(
    val atRestActive: Boolean,
    val unreadableIds: Set<String>,
    val writesRefused: Boolean = false,
)

class ProfileRepository(
    private val dao: ProfileDao,
    private val crypto: AtRestCrypto,
) {
    private class Decoded(val entity: ProfileEntity, val result: Result<Profile>)
    private class Snapshot(val active: Boolean, val rows: List<Decoded>)

    /**
     * One decode per row per emission, re-run whenever the at-rest posture changes: a row hidden
     * by an absent key must come back the moment the key arrives (S-3), and the list and the
     * health card must never disagree because they decoded separately.
     * Decodes on IO: a StrongBox decrypt costs tens of milliseconds and must never run on the
     * collector's (often main) thread.
     */
    private fun observeSnapshot(): Flow<Snapshot> =
        combine(dao.observeAll(), crypto.active) { list, active ->
            Snapshot(active, list.map { Decoded(it, decode(it)) })
        }.flowOn(Dispatchers.IO)

    /** Readable profiles only: a row whose secrets do not decrypt right now is excluded, never thrown. */
    fun observeProfiles(): Flow<List<Profile>> =
        observeSnapshot().map { snapshot -> snapshot.rows.mapNotNull { it.result.getOrNull() } }

    /**
     * Every stored row, readable or not. A row hidden by an absent key is still on the device
     * (S-3's posture, not deletion) — surfaces that count "profiles on this device" must use
     * this, or a transient key loss reads as an empty device.
     */
    fun observeStoredCount(): Flow<Int> = dao.observeAll().map { it.size }

    /**
     * At-rest posture plus the rows whose secrets fail to authenticate (sealed under a replaced key).
     * While no key is held it also asks whether a write would be refused (R6). Runs on IO: that
     * question can reach the Keystore.
     */
    fun observeHealth(): Flow<ProfilesHealth> =
        observeSnapshot().map { snapshot ->
            ProfilesHealth(
                atRestActive = snapshot.active,
                unreadableIds = snapshot.rows.filter { it.result.isCorrupt }.map { it.entity.id }.toSet(),
                writesRefused = !snapshot.active && refusesWrites(),
            )
        }
        // Load-bearing: moves this map (refusesWrites → Keystore) onto IO; observeSnapshot's flowOn covers only its combine.
        .flowOn(Dispatchers.IO)

    /**
     * Whether a write would be refused right now. While no key is held, [AtRestCrypto.encrypt]
     * does one of two things (its `active` contract): it passes plaintext through while nothing
     * is encrypted yet (S-3), or it throws [AtRestUnavailableException] when a key is expected —
     * held earlier in this process, or encrypted values present without one (F13, DATA-5).
     * Asking with an empty value writes nothing. The crypto's retry backoff rate-limits any
     * Keystore call this makes; if the call brings the key back, `active` flips and the next
     * snapshot reports the posture as active.
     */
    private fun refusesWrites(): Boolean =
        runCatching { crypto.encrypt("") }.exceptionOrNull() is AtRestUnavailableException

    /**
     * The stored profile with its secrets decrypted; null when there is no such row or it cannot be read.
     * Runs on IO: a StrongBox decrypt costs tens of milliseconds and the connect path calls this from the
     * main thread. A read refused only because no key is held right now forces one re-acquisition
     * ([AtRestCrypto.warm] ignores the retry backoff) and decodes once more, so a connect at boot during
     * the backoff does not refuse a profile the Keystore can serve (F13).
     */
    suspend fun get(id: String): Profile? = withContext(Dispatchers.IO) {
        val entity = dao.getById(id) ?: return@withContext null
        val first = decode(entity)
        if (first.exceptionOrNull() is AtRestUnavailableException) {
            crypto.warm()
            decode(entity).getOrNull()
        } else {
            first.getOrNull()
        }
    }

    /** Ids of every stored row, readable or not: a restore counts collisions against all of them. */
    suspend fun storedIds(): Set<String> = dao.observeAll().first().map { it.id }.toSet()

    /**
     * Removes the rows whose secrets no longer authenticate. No-op while no key is held: the
     * Keystore being absent is a posture, not corruption (S-4). A newer format or a transient
     * Keystore failure is never corruption either — only [AtRestCorruptException] qualifies.
     */
    suspend fun deleteUnreadable() {
        withContext(Dispatchers.IO) {
            if (!crypto.active.value) return@withContext
            dao.observeAll().first().filter { decode(it).isCorrupt }.forEach { dao.delete(it.id) }
        }
    }

    suspend fun save(profile: Profile) = saveAll(listOf(profile))

    /**
     * Validates every profile first, then writes all of them in one transaction: all rows or none.
     * A transient Keystore failure while sealing (one [ProviderException] arms the retry backoff
     * and fails the whole batch, a snapshot restore included) gets exactly one retry after forcing
     * the key back: [crypto.warm] ignores the backoff, and the retry re-seals from the model, so
     * nothing was written by the failed attempt.
     */
    suspend fun saveAll(profiles: List<Profile>) {
        profiles.forEach { p ->
            val errors = p.validate()
            if (errors.isNotEmpty()) throw InvalidProfileException(errors)
        }
        // Sealing is a Keystore operation: never on the caller's (often main) thread.
        withContext(Dispatchers.IO) {
            try {
                writeAll(profiles)
            } catch (e: AtRestUnavailableException) {
                crypto.warm()
                writeAll(profiles)
            }
        }
    }

    private suspend fun writeAll(profiles: List<Profile>) {
        val entities = profiles.map { it.withSecretsEncrypted().toEntity(dao.getById(it.id)) }
        dao.upsertAll(entities)
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun export(profile: Profile): String =
        WgConfigSerializer.serialize(profile.toWgQuickConfig())

    /**
     * One-time pass over every row whose secrets are still plaintext (pre-crypto era)
     * and re-encrypts them. Timestamps are preserved — this must not reorder the
     * profiles list. Idempotent: already-marked rows are skipped, rows that cannot be read
     * or sealed right now are left as they are (a startup pass must never throw).
     */
    suspend fun reEncryptAtRest() {
        withContext(Dispatchers.IO) {
            dao.observeAll().first().forEach { entity ->
                val needsMigration = !crypto.isEncrypted(entity.privateKey) ||
                    (entity.presharedKey != null && !crypto.isEncrypted(entity.presharedKey))
                if (!needsMigration) return@forEach
                val model = decode(entity).getOrNull() ?: return@forEach
                val sealed = runCatching { model.withSecretsEncrypted() }.getOrNull()
                    ?.toEntity(entity)?.copy(createdAt = entity.createdAt, updatedAt = entity.updatedAt)
                    ?: return@forEach
                // Degraded (no key yet): encrypt returned plaintext, so nothing changed; skip the write.
                if (sealed.privateKey == entity.privateKey && sealed.presharedKey == entity.presharedKey) return@forEach
                dao.upsert(sealed)
            }
        }
    }

    private fun decode(entity: ProfileEntity): Result<Profile> =
        runCatching { entity.toModel().withSecretsDecrypted() }

    /** Only a value that fails authentication under the held key is corruption (S-4). */
    private val Result<Profile>.isCorrupt: Boolean
        get() = exceptionOrNull() is AtRestCorruptException

    /** Secrets enter storage only as ciphertext; nothing above this class sees the blob. */
    private fun Profile.withSecretsEncrypted(): Profile = copy(
        interfaceConfig = interfaceConfig.copy(privateKey = crypto.encrypt(interfaceConfig.privateKey)),
        peer = peer.copy(presharedKey = peer.presharedKey?.let(crypto::encrypt)),
    )

    private fun Profile.withSecretsDecrypted(): Profile = copy(
        interfaceConfig = interfaceConfig.copy(privateKey = crypto.decrypt(interfaceConfig.privateKey)),
        peer = peer.copy(presharedKey = peer.presharedKey?.let(crypto::decrypt)),
    )
}
