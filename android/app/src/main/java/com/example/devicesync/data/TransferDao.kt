package com.example.devicesync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    @Query("SELECT * FROM file_transfers ORDER BY timestamp DESC")
    fun getAllTransfers(): Flow<List<TransferItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TransferItem)

    @Query("UPDATE file_transfers SET status = :status, localUriOrPath = COALESCE(:localUriOrPath, localUriOrPath) WHERE transferId = :id")
    suspend fun updateStatus(id: String, status: String, localUriOrPath: String? = null)

    @Query("UPDATE file_transfers SET status = 'failed' WHERE status = 'in_progress' AND timestamp < :cutoff")
    suspend fun failStaleTransfers(cutoff: Long)

    @Query("SELECT COUNT(*) FROM file_transfers")
    suspend fun getCount(): Int

    @Query("DELETE FROM file_transfers WHERE transferId = (SELECT transferId FROM file_transfers ORDER BY timestamp ASC LIMIT 1)")
    suspend fun deleteOldest()
}
