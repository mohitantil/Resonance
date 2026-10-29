package com.example.devicesync.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<ClipboardItem>>

    @Query("SELECT COUNT(*) FROM clipboard_items WHERE clipboardId = :id")
    suspend fun existsById(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ClipboardItem)

    @Query("SELECT COUNT(*) FROM clipboard_items")
    suspend fun getCount(): Int

    @Query("DELETE FROM clipboard_items WHERE clipboardId = (SELECT clipboardId FROM clipboard_items ORDER BY timestamp ASC LIMIT 1)")
    suspend fun deleteOldest()
}
