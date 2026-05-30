package com.bytedance.zgx.pocketmind.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "chat_messages", primaryKeys = ["sessionId", "position"])
data class ChatMessageEntity(
    val sessionId: String,
    val position: Int,
    val role: String,
    val text: String,
    val tokenCount: Int?,
    val tokensPerSecond: Double?,
)

@Entity(tableName = "installed_models")
data class InstalledModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val path: String,
    val fileBytes: Long,
    val recommendedModelId: String?,
    val sourceRevision: String?,
    val verifiedSha256: String?,
    val verificationStatus: String,
)

@Entity(tableName = "download_records")
data class DownloadRecordEntity(
    @PrimaryKey val id: String,
    val downloadManagerId: Long,
    val sourceJson: String,
    val updatedAtMillis: Long,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAtMillis DESC")
    fun sessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    fun session(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY position ASC")
    fun messagesForSession(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages ORDER BY rowid DESC LIMIT :limit")
    fun recentMessages(limit: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSession(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    fun deleteMessages(sessionId: String)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    fun deleteSession(sessionId: String)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM installed_models")
    fun models(): List<InstalledModelEntity>

    @Query("SELECT * FROM installed_models WHERE id = :id LIMIT 1")
    fun model(id: String): InstalledModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(model: InstalledModelEntity)

    @Query("DELETE FROM installed_models WHERE id = :id")
    fun delete(id: String)
}

@Dao
interface DownloadRecordDao {
    @Query("SELECT * FROM download_records WHERE id = :id LIMIT 1")
    fun record(id: String = PENDING_DOWNLOAD_RECORD_ID): DownloadRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: DownloadRecordEntity)

    @Query("DELETE FROM download_records WHERE id = :id")
    fun delete(id: String = PENDING_DOWNLOAD_RECORD_ID)
}

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        InstalledModelEntity::class,
        DownloadRecordEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PocketMindDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun modelDao(): ModelDao
    abstract fun downloadRecordDao(): DownloadRecordDao

    companion object {
        @Volatile
        private var INSTANCE: PocketMindDatabase? = null

        fun get(context: Context): PocketMindDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketMindDatabase::class.java,
                    "pocketmind.db",
                )
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

const val PENDING_DOWNLOAD_RECORD_ID = "pending"
