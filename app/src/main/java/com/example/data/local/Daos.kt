package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CleanIpDao {
    @Query("SELECT * FROM clean_ips ORDER BY isClean DESC, CASE WHEN httpsPingMs IS NULL THEN 999999 ELSE httpsPingMs END ASC, scannedAt DESC")
    fun observeAllCleanIps(): Flow<List<CleanIpEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCleanIp(entity: CleanIpEntity)

    @Query("DELETE FROM clean_ips WHERE idKey = :idKey")
    suspend fun deleteById(idKey: String)

    @Query("DELETE FROM clean_ips WHERE isClean = 0")
    suspend fun deleteFailed(): Int

    @Query("DELETE FROM clean_ips")
    suspend fun clearAll()
}
