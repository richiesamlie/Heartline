// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.heartline.shared.model.RecordKind
import kotlinx.coroutines.flow.Flow

@Dao
internal interface RecordDao {
    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY startedAtMs DESC")
    fun observe(kind: RecordKind): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE kind = :kind AND startedAtMs >= :fromMs ORDER BY startedAtMs DESC")
    suspend fun since(kind: RecordKind, fromMs: Long): List<RecordEntity>

    @Query("SELECT * FROM records WHERE kind = :kind AND startedAtMs >= :fromMs ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun latestSince(kind: RecordKind, fromMs: Long): RecordEntity?

    @Query("SELECT * FROM records WHERE id = :id")
    fun observeById(id: String): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun get(id: String): RecordEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM records WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: RecordEntity): Long

    @Query("UPDATE records SET summaryJson = :summaryJson, note = :note WHERE id = :id")
    suspend fun updateSummary(id: String, summaryJson: String, note: String?)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT wavePath FROM records WHERE id LIKE 'demo-%' AND wavePath IS NOT NULL")
    suspend fun demoWavePaths(): List<String>

    @Query("DELETE FROM records WHERE id LIKE 'demo-%'")
    suspend fun deleteDemo(): Int

    @Query("DELETE FROM records")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(entry: DeletedRecordEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM deleted_records WHERE id = :id)")
    suspend fun isDeleted(id: String): Boolean

    @Query("DELETE FROM deleted_records")
    suspend fun deleteAllTombstones()

    @Transaction
    suspend fun deleteWithTombstone(id: String, deletedAtMs: Long) {
        delete(id)
        insertTombstone(DeletedRecordEntity(id, deletedAtMs))
    }

    @Transaction
    suspend fun deleteAllAndTombstones() {
        deleteAll()
        deleteAllTombstones()
    }

    @Query("SELECT * FROM records ORDER BY startedAtMs")
    suspend fun all(): List<RecordEntity>

    @Query("SELECT COUNT(*) FROM records")
    suspend fun count(): Int
}
