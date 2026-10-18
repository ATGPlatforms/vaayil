package dev.vaayil.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: String): ProfileEntity?

    @Upsert suspend fun upsert(entity: ProfileEntity)

    /** One statement, one transaction: a restore writes every row or none. */
    @Upsert suspend fun upsertAll(entities: List<ProfileEntity>)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)
}
