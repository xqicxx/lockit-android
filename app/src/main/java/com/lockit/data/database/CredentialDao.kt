package com.lockit.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    @Query("SELECT * FROM credentials ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials ORDER BY updatedAt DESC")
    suspend fun getAllEntities(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun getById(id: String): CredentialEntity?

    @Query("SELECT * FROM credentials WHERE name LIKE '%' || :query || '%' OR service LIKE '%' || :query || '%' OR type LIKE '%' || :query || '%' OR key LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<CredentialEntity>>

    @Query("SELECT DISTINCT service FROM credentials ORDER BY service")
    fun getAllServices(): Flow<List<String>>

    @Query("SELECT * FROM credentials WHERE service = :service ORDER BY name")
    fun getByService(service: String): Flow<List<CredentialEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CredentialEntity)

    @Update
    suspend fun update(entity: CredentialEntity)

    @Query("""
        UPDATE credentials SET
            name = :name,
            type = :type,
            service = :service,
            key = :key,
            value = :value,
            metadata = :metadata,
            createdAt = :createdAt,
            updatedAt = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateById(
        id: String,
        name: String,
        type: String,
        service: String,
        key: String,
        value: ByteArray,
        metadata: String,
        createdAt: Long,
        updatedAt: Long,
    )

    @Delete
    suspend fun delete(entity: CredentialEntity)

    @Query("SELECT COUNT(*) FROM credentials")
    fun getCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT service) FROM credentials")
    fun getServiceCount(): Flow<Int>
}
