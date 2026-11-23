// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** A measurement taken on the watch; kept for the watch's history after delivery. */
@Entity(
    tableName = "records",
    indices = [Index("delivered", "startedAtMs"), Index("startedAtMs")],
)
internal data class WatchRecordEntity(
    @PrimaryKey val id: String,
    val metaJson: String,
    val startedAtMs: Long,
    val wavePath: String?,
    val delivered: Boolean = false,
)

@Dao
internal interface WatchRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WatchRecordEntity)

    @Query("SELECT * FROM records WHERE delivered = 0 ORDER BY startedAtMs")
    suspend fun pending(): List<WatchRecordEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM records WHERE delivered = 0)")
    suspend fun hasPending(): Boolean

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun get(id: String): WatchRecordEntity?

    @Query("UPDATE records SET delivered = 1, wavePath = NULL WHERE id = :id")
    suspend fun markDelivered(id: String)

    @Query("SELECT * FROM records ORDER BY startedAtMs DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<WatchRecordEntity>>

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Keeps the newest [keep] delivered records (only their summaries: waves are deleted once
     * delivered), enough for streaks and the user's usual ranges.
     */
    @Query("DELETE FROM records WHERE delivered = 1 AND id NOT IN (SELECT id FROM records ORDER BY startedAtMs DESC LIMIT :keep)")
    suspend fun prune(keep: Int = WatchRecordStore.HISTORY)
}

/** A small message (HR batch, alert) waiting for the phone's ack. */
@Entity(
    tableName = "messages",
    indices = [Index("createdAtMs")],
)
internal data class MessageEntity(@PrimaryKey val id: String, val path: String, val payload: ByteArray, val createdAtMs: Long) {
    override fun equals(other: Any?): Boolean = this === other || (
        other is MessageEntity &&
            id == other.id &&
            path == other.path &&
            payload.contentEquals(other.payload) &&
            createdAtMs == other.createdAtMs
        )

    override fun hashCode(): Int = ((31 * id.hashCode() + path.hashCode()) * 31 + payload.contentHashCode()) * 31 + createdAtMs.hashCode()
}

@Dao
internal interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY createdAtMs LIMIT 200")
    suspend fun pending(): List<MessageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM messages)")
    suspend fun hasPending(): Boolean

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    /** Keeps the queue bounded if the phone is away for days; messages on [keepPath] (alerts) until [keepBeforeMs]. */
    @Query("DELETE FROM messages WHERE (path != :keepPath AND createdAtMs < :beforeMs) OR createdAtMs < :keepBeforeMs")
    suspend fun deleteOlderThan(beforeMs: Long, keepPath: String, keepBeforeMs: Long): Int
}

@Database(entities = [WatchRecordEntity::class, MessageEntity::class], version = 3, exportSchema = true)
abstract class WatchDatabase : RoomDatabase() {
    internal abstract fun records(): WatchRecordDao

    internal abstract fun messages(): MessageDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `path` TEXT NOT NULL, `payload` BLOB NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_records_delivered_startedAtMs` ON `records` (`delivered`, `startedAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_records_startedAtMs` ON `records` (`startedAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_createdAtMs` ON `messages` (`createdAtMs`)")
            }
        }

        fun create(context: Context): WatchDatabase =
            Room.databaseBuilder(context, WatchDatabase::class.java, "heartline-watch.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        fun inMemory(context: Context): WatchDatabase =
            Room.inMemoryDatabaseBuilder(context, WatchDatabase::class.java).allowMainThreadQueries().build()
    }
}
