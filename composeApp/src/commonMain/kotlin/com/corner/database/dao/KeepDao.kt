package com.corner.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.corner.database.entity.Keep
import kotlinx.coroutines.flow.Flow

@Dao
interface KeepDao {
    @Query("SELECT * FROM Keep")
    fun getAll(): Flow<List<Keep>>

    @Query("SELECT * FROM Keep WHERE type = 0 ORDER BY createTime DESC")
    suspend fun findVodOnce(): List<Keep>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(keep: Keep)

    @Query("SELECT * FROM Keep WHERE type = 0 ORDER BY createTime DESC")
    fun getVod(): Flow<List<Keep>>

    @Query("SELECT * FROM Keep WHERE type = 0 AND cid = :cid AND `key` = :key")
    suspend fun findVod(cid: Long, key: String): Keep?

    @Query("DELETE FROM Keep WHERE type = 0 AND cid = :cid AND `key` = :key")
    suspend fun deleteVod(cid: Long, key: String)

    @Query("DELETE FROM Keep WHERE type = 0")
    suspend fun deleteAllVod()

    @Query("SELECT * FROM Keep WHERE type = 1 ORDER BY createTime DESC")
    fun getLive(): Flow<List<Keep>>

    @Query("SELECT * FROM Keep WHERE type = 1 AND `key` = :key")
    suspend fun findLive(key: String): Keep?

    @Query("DELETE FROM Keep WHERE type = 1 AND `key` = :key")
    suspend fun deleteLive(key: String)
}