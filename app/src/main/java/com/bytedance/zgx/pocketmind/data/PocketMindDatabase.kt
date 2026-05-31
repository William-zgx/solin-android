package com.bytedance.zgx.pocketmind.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bytedance.zgx.pocketmind.MessagePrivacy

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
    @ColumnInfo(defaultValue = "'RemoteEligible'")
    val privacy: String = MessagePrivacy.RemoteEligible.name,
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

@Entity(tableName = "tool_audit_events")
data class ToolAuditEventEntity(
    @PrimaryKey val id: String,
    val runId: String?,
    val requestId: String?,
    val toolName: String?,
    val skillId: String?,
    val eventType: String,
    val status: String?,
    val riskLevel: String?,
    val permissionsCsv: String,
    val summary: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val body: String,
    val triggerAtMillis: Long,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "memory_records")
data class MemoryRecordEntity(
    @PrimaryKey val id: String,
    val type: String,
    val text: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "agent_runs")
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val input: String,
    val state: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "agent_steps", primaryKeys = ["runId", "position"])
data class AgentStepEntity(
    val runId: String,
    val position: Int,
    val type: String,
    val summary: String,
    val json: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "pending_agent_confirmations")
data class PendingAgentConfirmationEntity(
    @PrimaryKey val runId: String,
    val requestId: String,
    val toolName: String,
    val argumentsJson: String,
    val reason: String,
    val draftFunctionName: String,
    val draftTitle: String,
    val draftSummary: String,
    val draftParametersJson: String,
    val skillId: String?,
    val skillPlanJson: String?,
    val plannedByModel: Boolean,
    val fallbackReason: String?,
    val createdAtMillis: Long,
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

@Dao
interface ToolAuditDao {
    @Query("SELECT * FROM tool_audit_events ORDER BY createdAtMillis DESC LIMIT :limit")
    fun recent(limit: Int): List<ToolAuditEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(event: ToolAuditEventEntity)
}

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks WHERE id = :taskId LIMIT 1")
    fun task(taskId: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'Scheduled' ORDER BY triggerAtMillis ASC LIMIT :limit")
    fun scheduled(limit: Int): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'Scheduled' AND type = :type ORDER BY triggerAtMillis ASC LIMIT :limit")
    fun scheduledByType(type: String, limit: Int): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks ORDER BY updatedAtMillis DESC, id ASC LIMIT :limit")
    fun recent(limit: Int): List<ScheduledTaskEntity>

    @Query(
        """
        UPDATE scheduled_tasks
        SET status = 'Running', updatedAtMillis = :updatedAtMillis
        WHERE id = :taskId AND type = 'Reminder' AND status = 'Scheduled'
        """,
    )
    fun markReminderRunningIfScheduled(taskId: String, updatedAtMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(task: ScheduledTaskEntity)
}

@Dao
interface MemoryRecordDao {
    @Query("SELECT * FROM memory_records ORDER BY updatedAtMillis DESC")
    fun records(): List<MemoryRecordEntity>

    @Query("SELECT * FROM memory_records WHERE id = :id LIMIT 1")
    fun record(id: String): MemoryRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: MemoryRecordEntity)

    @Query("DELETE FROM memory_records WHERE id = :id")
    fun delete(id: String): Int

    @Query("DELETE FROM memory_records")
    fun deleteAll()
}

@Dao
interface AgentTraceDao {
    @Query("SELECT * FROM agent_runs WHERE id = :runId LIMIT 1")
    fun run(runId: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs ORDER BY updatedAtMillis DESC, createdAtMillis DESC, id DESC LIMIT :limit")
    fun recentRuns(limit: Int): List<AgentRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRun(run: AgentRunEntity)

    @Query("UPDATE agent_runs SET state = :state, updatedAtMillis = :updatedAtMillis WHERE id = :runId")
    fun updateRunState(runId: String, state: String, updatedAtMillis: Long): Int

    @Query("UPDATE agent_runs SET updatedAtMillis = :updatedAtMillis WHERE id = :runId")
    fun touchRun(runId: String, updatedAtMillis: Long): Int

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM agent_steps WHERE runId = :runId")
    fun nextStepPosition(runId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertStep(step: AgentStepEntity)

    @Query("SELECT * FROM agent_steps WHERE runId = :runId ORDER BY position ASC")
    fun steps(runId: String): List<AgentStepEntity>

    @Query("SELECT * FROM pending_agent_confirmations ORDER BY updatedAtMillis DESC, createdAtMillis DESC, runId DESC")
    fun pendingConfirmations(): List<PendingAgentConfirmationEntity>

    @Query("SELECT * FROM pending_agent_confirmations ORDER BY updatedAtMillis DESC, createdAtMillis DESC, runId DESC LIMIT 1")
    fun latestPendingConfirmation(): PendingAgentConfirmationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPendingConfirmation(pending: PendingAgentConfirmationEntity)

    @Query("DELETE FROM pending_agent_confirmations WHERE runId = :runId AND requestId = :requestId")
    fun deletePendingConfirmation(runId: String, requestId: String): Int
}

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        InstalledModelEntity::class,
        DownloadRecordEntity::class,
        ToolAuditEventEntity::class,
        ScheduledTaskEntity::class,
        MemoryRecordEntity::class,
        AgentRunEntity::class,
        AgentStepEntity::class,
        PendingAgentConfirmationEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class PocketMindDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun modelDao(): ModelDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun toolAuditDao(): ToolAuditDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun memoryRecordDao(): MemoryRecordDao
    abstract fun agentTraceDao(): AgentTraceDao

    companion object {
        @Volatile
        private var INSTANCE: PocketMindDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tool_audit_events` (
                        `id` TEXT NOT NULL,
                        `runId` TEXT,
                        `requestId` TEXT,
                        `toolName` TEXT,
                        `skillId` TEXT,
                        `eventType` TEXT NOT NULL,
                        `status` TEXT,
                        `riskLevel` TEXT,
                        `permissionsCsv` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scheduled_tasks` (
                        `id` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `triggerAtMillis` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `chat_messages` ADD COLUMN `privacy` TEXT NOT NULL DEFAULT '${MessagePrivacy.RemoteEligible.name}'",
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_records` (
                        `id` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_runs` (
                        `id` TEXT NOT NULL,
                        `input` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_steps` (
                        `runId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `json` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`runId`, `position`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_agent_confirmations` (
                        `runId` TEXT NOT NULL,
                        `requestId` TEXT NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `argumentsJson` TEXT NOT NULL,
                        `reason` TEXT NOT NULL,
                        `draftFunctionName` TEXT NOT NULL,
                        `draftTitle` TEXT NOT NULL,
                        `draftSummary` TEXT NOT NULL,
                        `draftParametersJson` TEXT NOT NULL,
                        `skillId` TEXT,
                        `skillPlanJson` TEXT,
                        `plannedByModel` INTEGER NOT NULL,
                        `fallbackReason` TEXT,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`runId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        fun get(context: Context): PocketMindDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketMindDatabase::class.java,
                    "pocketmind.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                    )
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

const val PENDING_DOWNLOAD_RECORD_ID = "pending"
