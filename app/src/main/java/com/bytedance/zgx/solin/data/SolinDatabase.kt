package com.bytedance.zgx.solin.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.memory.MemoryRecordSensitivity
import com.bytedance.zgx.solin.memory.MemoryRecordSource
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

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
    @ColumnInfo(defaultValue = "'LocalOnly'")
    val privacy: String = MessagePrivacy.LocalOnly.name,
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

@Entity(tableName = "remote_send_audit_events")
data class RemoteSendAuditEventEntity(
    @PrimaryKey val id: String,
    val decision: String,
    val modelName: String?,
    val sensitiveCategoriesCsv: String,
    val imageCount: Int,
    val remoteHistoryCount: Int,
    val summary: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "memory_deletion_events")
data class MemoryDeletionEventEntity(
    @PrimaryKey val id: String,
    val recordId: String,
    val recordType: String,
    val operation: String,
    val recordTextHash: String,
    val recordSource: String,
    val recordSensitivity: String,
    val conflictKey: String?,
    val deletedAtMillis: Long,
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
    @ColumnInfo(defaultValue = "'LegacyImport'")
    val source: String = MemoryRecordSource.LegacyImport.name,
    @ColumnInfo(defaultValue = "'Normal'")
    val sensitivity: String = MemoryRecordSensitivity.Normal.name,
    @ColumnInfo(defaultValue = "'LocalOnly'")
    val privacy: String = MessagePrivacy.LocalOnly.name,
    val expiresAtMillis: Long? = null,
    val conflictKey: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "memory_embeddings",
    primaryKeys = ["recordId", "modelId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("recordId"),
        Index("modelId"),
    ],
)
data class MemoryEmbeddingEntity(
    val recordId: String,
    val modelId: String,
    val sourceHash: String,
    val dimension: Int,
    val vectorBlob: ByteArray,
    val updatedAtMillis: Long,
)

@Entity(tableName = "agent_runs")
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val input: String,
    val state: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val sessionId: String? = null,
)

@Entity(
    tableName = "agent_run_placement_bindings",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AgentRunPlacementBindingEntity(
    @PrimaryKey val runId: String,
    val schemaVersion: Int,
    val policyVersion: Int,
    val preference: String,
    val placement: String,
    val primaryReason: String,
    val complexity: String,
    val resourceBand: String,
    val localState: String,
    val remoteState: String,
    val remoteProfileRevision: String?,
    val bootCount: Long,
    val boundAtElapsedRealtimeMillis: Long,
    val dispatchState: String,
    val attempt: Int,
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
    val nextActionInput: String?,
    val continuationCursorJson: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "agent_skill_run_checkpoints", primaryKeys = ["runId", "requestId"])
data class AgentSkillRunCheckpointEntity(
    val runId: String,
    val requestId: String,
    val skillId: String,
    val skillRequestId: String,
    val manifestId: String,
    val manifestVersion: Int,
    val manifestHash: String,
    val phase: String,
    val pendingStepIndex: Int,
    val pendingStepId: String,
    val pendingToolName: String,
    val completedStepIdsJson: String,
    val outputKeysByStepJson: String,
    val privateOutputRefsJson: String,
    val schemaVersion: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "local_storage_migration_state")
data class LocalStorageMigrationStateEntity(
    @PrimaryKey val id: String,
    val phase: String,
    val lastDomain: String?,
    val lastId: String?,
    val startedAtMillis: Long,
    val completedAtMillis: Long?,
    val errorJson: String?,
    val schemaVersion: Int,
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

    @Query(
        """
        DELETE FROM tool_audit_events
        WHERE id NOT IN (
            SELECT id FROM tool_audit_events
            ORDER BY createdAtMillis DESC
            LIMIT :maxRecords
        )
        """,
    )
    fun pruneToMostRecent(maxRecords: Int): Int
}

@Dao
interface RemoteSendAuditDao {
    @Query("SELECT * FROM remote_send_audit_events ORDER BY createdAtMillis DESC LIMIT :limit")
    fun recent(limit: Int): List<RemoteSendAuditEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(event: RemoteSendAuditEventEntity)

    @Query(
        """
        DELETE FROM remote_send_audit_events
        WHERE id NOT IN (
            SELECT id FROM remote_send_audit_events
            ORDER BY createdAtMillis DESC
            LIMIT :maxRecords
        )
        """,
    )
    fun pruneToMostRecent(maxRecords: Int): Int
}

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks WHERE id = :taskId LIMIT 1")
    fun task(taskId: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'Scheduled' ORDER BY triggerAtMillis ASC LIMIT :limit")
    fun scheduled(limit: Int): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE status IN ('Scheduled', 'Running') ORDER BY triggerAtMillis ASC, id ASC LIMIT :limit")
    fun scheduledOrRunning(limit: Int): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'Scheduled' AND type = :type ORDER BY triggerAtMillis ASC, id ASC LIMIT :limit")
    fun scheduledByType(type: String, limit: Int): List<ScheduledTaskEntity>

    @Query(
        """
        SELECT * FROM scheduled_tasks
        WHERE status = 'Scheduled'
            AND type = :type
            AND (
                :afterTriggerAtMillis IS NULL
                OR triggerAtMillis > :afterTriggerAtMillis
                OR (triggerAtMillis = :afterTriggerAtMillis AND id > :afterId)
            )
        ORDER BY triggerAtMillis ASC, id ASC
        LIMIT :limit
        """,
    )
    fun scheduledByTypeAfter(
        type: String,
        afterTriggerAtMillis: Long?,
        afterId: String?,
        limit: Int,
    ): List<ScheduledTaskEntity>

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

    @Query(
        """
        UPDATE scheduled_tasks
        SET status = :status, updatedAtMillis = :updatedAtMillis
        WHERE id = :taskId AND type = 'Reminder' AND status = 'Running'
        """,
    )
    fun updateReminderStatusIfRunning(
        taskId: String,
        status: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE scheduled_tasks
        SET status = 'Running', updatedAtMillis = :updatedAtMillis
        WHERE id = :taskId AND type = 'PeriodicCheck' AND status = 'Scheduled'
        """,
    )
    fun markPeriodicCheckRunningIfScheduled(taskId: String, updatedAtMillis: Long): Int

    @Query(
        """
        UPDATE scheduled_tasks
        SET body = :body, triggerAtMillis = :triggerAtMillis, status = :status, updatedAtMillis = :updatedAtMillis
        WHERE id = :taskId AND type = 'PeriodicCheck' AND status = 'Running'
        """,
    )
    fun recordPeriodicCheckRunIfRunning(
        taskId: String,
        body: String,
        triggerAtMillis: Long,
        status: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE scheduled_tasks
        SET status = :status, updatedAtMillis = :updatedAtMillis
        WHERE id = :taskId AND type = 'PeriodicCheck' AND status = 'Running'
        """,
    )
    fun updatePeriodicCheckStatusIfRunning(
        taskId: String,
        status: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE scheduled_tasks
        SET status = 'Scheduled', updatedAtMillis = :updatedAtMillis
        WHERE id = :taskId
            AND type = :type
            AND status = 'Running'
            AND updatedAtMillis <= :staleUpdatedAtMillis
        """,
    )
    fun markStaleRunningTaskScheduled(
        taskId: String,
        type: String,
        staleUpdatedAtMillis: Long,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE scheduled_tasks
        SET status = 'Scheduled', updatedAtMillis = :updatedAtMillis
        WHERE type = :type
            AND status = 'Running'
            AND updatedAtMillis <= :staleUpdatedAtMillis
        """,
    )
    fun markStaleRunningTasksScheduledByType(
        type: String,
        staleUpdatedAtMillis: Long,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE scheduled_tasks
        SET status = :status, updatedAtMillis = :updatedAtMillis
        WHERE id = :taskId AND status = 'Scheduled'
        """,
    )
    fun updateScheduledStatusIfScheduled(
        taskId: String,
        status: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE scheduled_tasks
        SET triggerAtMillis = :triggerAtMillis, updatedAtMillis = :updatedAtMillis
        WHERE id = :taskId AND type = 'Reminder' AND status = 'Scheduled'
        """,
    )
    fun updateReminderTriggerAtIfScheduled(
        taskId: String,
        triggerAtMillis: Long,
        updatedAtMillis: Long,
    ): Int

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
interface MemoryEmbeddingDao {
    @Query("SELECT * FROM memory_embeddings WHERE recordId = :recordId AND modelId = :modelId LIMIT 1")
    fun embedding(recordId: String, modelId: String): MemoryEmbeddingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(embedding: MemoryEmbeddingEntity)

    @Query("DELETE FROM memory_embeddings WHERE recordId = :recordId")
    fun delete(recordId: String): Int

    @Query("DELETE FROM memory_embeddings WHERE modelId = :modelId")
    fun deleteForModel(modelId: String): Int

    @Query("DELETE FROM memory_embeddings")
    fun deleteAll()
}

@Dao
interface LocalStorageMigrationStateDao {
    @Query("SELECT * FROM local_storage_migration_state WHERE id = :id LIMIT 1")
    fun state(id: String): LocalStorageMigrationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: LocalStorageMigrationStateEntity)
}

@Dao
interface MemoryDeletionEventDao {
    @Query("SELECT * FROM memory_deletion_events ORDER BY deletedAtMillis DESC, id DESC")
    fun events(): List<MemoryDeletionEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(event: MemoryDeletionEventEntity)
}

@Dao
abstract class MemoryDeletionTransactionDao {
    @Query("DELETE FROM memory_records WHERE id = :id")
    abstract fun deleteRecord(id: String): Int

    @Query("DELETE FROM memory_records")
    abstract fun deleteAllRecords()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertDeletionEvent(event: MemoryDeletionEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertDeletionEvents(events: List<MemoryDeletionEventEntity>)

    @Transaction
    open fun deleteRecordAndAppendEvent(id: String, event: MemoryDeletionEventEntity): Int {
        val deleted = deleteRecord(id)
        if (deleted > 0) {
            insertDeletionEvent(event)
        }
        return deleted
    }

    @Transaction
    open fun clearRecordsAndAppendEvents(events: List<MemoryDeletionEventEntity>) {
        deleteAllRecords()
        if (events.isNotEmpty()) {
            insertDeletionEvents(events)
        }
    }
}

data class RunPlacementRecoverySnapshotEntity(
    val run: AgentRunEntity,
    val binding: AgentRunPlacementBindingEntity,
    val steps: List<AgentStepEntity>,
    val pendingConfirmation: PendingAgentConfirmationEntity?,
)

data class RunPlacementTerminalizationEntity(
    val targetStateMatched: Boolean,
    val binding: AgentRunPlacementBindingEntity?,
)

@Dao
abstract class RunPlacementBindingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertRunStrict(run: AgentRunEntity)

    @Query("SELECT * FROM agent_runs WHERE id = :runId LIMIT 1")
    abstract fun run(runId: String): AgentRunEntity?

    @Query(
        """
        UPDATE agent_runs
        SET state = :state, updatedAtMillis = :updatedAtMillis
        WHERE id = :runId AND state NOT IN ('Completed', 'Cancelled', 'Failed')
        """,
    )
    abstract fun updateRunTerminal(runId: String, state: String, updatedAtMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertBindingStrict(binding: AgentRunPlacementBindingEntity)

    @Query("SELECT * FROM agent_run_placement_bindings WHERE runId = :runId LIMIT 1")
    abstract fun binding(runId: String): AgentRunPlacementBindingEntity?

    @Query(
        """
        UPDATE agent_run_placement_bindings
        SET dispatchState = 'Started', attempt = attempt + 1
        WHERE runId = :runId
          AND placement = :placement
          AND attempt = :expectedAttempt
          AND dispatchState IN ('Pending', 'Idle')
        """,
    )
    abstract fun compareAndSetStarted(runId: String, placement: String, expectedAttempt: Int): Int

    @Query(
        """
        UPDATE agent_run_placement_bindings
        SET dispatchState = 'Idle'
        WHERE runId = :runId
          AND placement = :placement
          AND attempt = :attempt
          AND dispatchState = 'Started'
        """,
    )
    abstract fun compareAndSetIdle(
        runId: String,
        placement: String,
        attempt: Int,
    ): Int

    @Query(
        """
        UPDATE agent_run_placement_bindings
        SET dispatchState = 'Terminal'
        WHERE runId = :runId AND dispatchState != 'Terminal'
        """,
    )
    abstract fun markBindingTerminal(runId: String): Int

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM agent_steps WHERE runId = :runId")
    abstract fun nextStepPosition(runId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertStepStrict(step: AgentStepEntity)

    @Query("SELECT * FROM agent_steps WHERE runId = :runId ORDER BY position ASC")
    abstract fun steps(runId: String): List<AgentStepEntity>

    @Query("SELECT * FROM pending_agent_confirmations WHERE runId = :runId LIMIT 1")
    abstract fun pendingConfirmation(runId: String): PendingAgentConfirmationEntity?

    @Transaction
    open fun bindAndReserveTransaction(
        binding: AgentRunPlacementBindingEntity,
        placementStep: AgentStepEntity,
    ): AgentRunPlacementBindingEntity {
        val parent = run(binding.runId) ?: error("Binding parent run is missing")
        check(parent.state !in TERMINAL_AGENT_RUN_STATES) { "Terminal run cannot be bound" }
        insertBindingStrict(binding)
        appendStepStrict(placementStep)
        return binding(binding.runId) ?: error("Binding disappeared during transaction")
    }

    @Transaction
    open fun claimAndRecordTransaction(
        runId: String,
        placement: String,
        expectedAttempt: Int,
        receiptStep: AgentStepEntity,
        invocationStep: AgentStepEntity,
    ): AgentRunPlacementBindingEntity? {
        val parent = run(runId) ?: return null
        if (parent.state in TERMINAL_AGENT_RUN_STATES) return null
        if (compareAndSetStarted(runId, placement, expectedAttempt) != 1) return null
        appendStepStrict(receiptStep)
        appendStepStrict(invocationStep)
        return binding(runId) ?: error("Binding disappeared during claim transaction")
    }

    @Transaction
    open fun terminalizeTransaction(
        runId: String,
        state: String,
        updatedAtMillis: Long,
    ): RunPlacementTerminalizationEntity? {
        if (state !in TERMINAL_AGENT_RUN_STATES) return null
        val parent = run(runId) ?: return null
        val currentBinding = binding(runId)
        if (currentBinding != null && currentBinding.dispatchState != "Terminal") {
            check(markBindingTerminal(runId) == 1) {
                "Placement terminalization lost inside transaction"
            }
        }
        if (parent.state !in TERMINAL_AGENT_RUN_STATES) {
            check(updateRunTerminal(runId, state, updatedAtMillis) == 1) {
                "Run terminal update lost after placement terminalization"
            }
        }
        return RunPlacementTerminalizationEntity(
            targetStateMatched = parent.state !in TERMINAL_AGENT_RUN_STATES || parent.state == state,
            binding = currentBinding?.copy(dispatchState = "Terminal"),
        )
    }

    @Transaction
    open fun recoverySnapshot(runId: String): RunPlacementRecoverySnapshotEntity? {
        val run = run(runId) ?: return null
        val binding = binding(runId) ?: return null
        return RunPlacementRecoverySnapshotEntity(
            run = run,
            binding = binding,
            steps = steps(runId),
            pendingConfirmation = pendingConfirmation(runId),
        )
    }

    private fun appendStepStrict(step: AgentStepEntity) {
        insertStepStrict(step.copy(position = nextStepPosition(step.runId)))
    }

    private companion object {
        val TERMINAL_AGENT_RUN_STATES = setOf("Completed", "Cancelled", "Failed")
    }
}

@Dao
interface AgentTraceDao {
    @Query("SELECT * FROM agent_runs WHERE id = :runId LIMIT 1")
    fun run(runId: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs ORDER BY updatedAtMillis DESC, createdAtMillis DESC, id DESC LIMIT :limit")
    fun recentRuns(limit: Int): List<AgentRunEntity>

    @Query("SELECT id FROM agent_runs WHERE sessionId = :sessionId")
    fun runIdsForSession(sessionId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertRunIfAbsent(run: AgentRunEntity): Long

    @Query(
        """
        UPDATE agent_runs
        SET state = :state, updatedAtMillis = :updatedAtMillis
        WHERE id = :runId AND state NOT IN ('Completed', 'Cancelled', 'Failed')
        """,
    )
    fun updateRunState(runId: String, state: String, updatedAtMillis: Long): Int

    @Query(
        """
        UPDATE agent_runs
        SET state = :state, updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
          AND state = :expectedState
          AND state NOT IN ('Completed', 'Cancelled', 'Failed')
        """,
    )
    fun compareAndSetRunStateIfExpected(
        runId: String,
        expectedState: String,
        state: String,
        updatedAtMillis: Long,
    ): Int

    @Transaction
    fun compareAndSetRunState(
        runId: String,
        expectedState: String,
        state: String,
        updatedAtMillis: Long,
    ): AgentRunEntity? {
        val current = run(runId) ?: return null
        if (compareAndSetRunStateIfExpected(runId, expectedState, state, updatedAtMillis) != 1) {
            return null
        }
        return current.copy(
            state = state,
            updatedAtMillis = updatedAtMillis,
        )
    }

    @Query("UPDATE agent_runs SET updatedAtMillis = :updatedAtMillis WHERE id = :runId")
    fun touchRun(runId: String, updatedAtMillis: Long): Int

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM agent_steps WHERE runId = :runId")
    fun nextStepPosition(runId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertStep(step: AgentStepEntity)

    @Transaction
    fun insertNextStep(step: AgentStepEntity) {
        insertStep(step.copy(position = nextStepPosition(step.runId)))
    }

    @Query("SELECT * FROM agent_steps WHERE runId = :runId ORDER BY position ASC")
    fun steps(runId: String): List<AgentStepEntity>

    @Query("DELETE FROM agent_steps WHERE runId IN (SELECT id FROM agent_runs WHERE sessionId = :sessionId)")
    fun deleteStepsForSession(sessionId: String): Int

    @Query("SELECT * FROM pending_agent_confirmations ORDER BY updatedAtMillis DESC, createdAtMillis DESC, runId DESC")
    fun pendingConfirmations(): List<PendingAgentConfirmationEntity>

    @Query("SELECT * FROM pending_agent_confirmations ORDER BY updatedAtMillis DESC, createdAtMillis DESC, runId DESC LIMIT 1")
    fun latestPendingConfirmation(): PendingAgentConfirmationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPendingConfirmation(pending: PendingAgentConfirmationEntity)

    @Query("DELETE FROM pending_agent_confirmations WHERE runId = :runId AND requestId = :requestId")
    fun deletePendingConfirmation(runId: String, requestId: String): Int

    @Query("DELETE FROM pending_agent_confirmations WHERE runId IN (SELECT id FROM agent_runs WHERE sessionId = :sessionId)")
    fun deletePendingConfirmationsForSession(sessionId: String): Int

    @Query("SELECT * FROM agent_skill_run_checkpoints WHERE runId = :runId AND requestId = :requestId LIMIT 1")
    fun skillRunCheckpoint(runId: String, requestId: String): AgentSkillRunCheckpointEntity?

    @Query("SELECT * FROM agent_skill_run_checkpoints WHERE runId = :runId ORDER BY updatedAtMillis DESC, requestId DESC")
    fun skillRunCheckpointsForRun(runId: String): List<AgentSkillRunCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSkillRunCheckpoint(checkpoint: AgentSkillRunCheckpointEntity)

    @Query("DELETE FROM agent_skill_run_checkpoints WHERE runId = :runId AND requestId = :requestId")
    fun deleteSkillRunCheckpoint(runId: String, requestId: String): Int

    @Query("DELETE FROM agent_skill_run_checkpoints WHERE runId = :runId")
    fun deleteSkillRunCheckpointsForRun(runId: String): Int

    @Query("DELETE FROM agent_skill_run_checkpoints WHERE runId IN (SELECT id FROM agent_runs WHERE sessionId = :sessionId)")
    fun deleteSkillRunCheckpointsForSession(sessionId: String): Int

    @Transaction
    fun upsertPendingConfirmationWithCheckpoint(
        pending: PendingAgentConfirmationEntity,
        checkpoint: AgentSkillRunCheckpointEntity?,
    ) {
        upsertPendingConfirmation(pending)
        if (checkpoint == null) {
            deleteSkillRunCheckpoint(pending.runId, pending.requestId)
        } else {
            upsertSkillRunCheckpoint(checkpoint)
        }
    }

    @Transaction
    fun deletePendingConfirmationWithCheckpoint(runId: String, requestId: String): Int {
        val deletedPending = deletePendingConfirmation(runId, requestId)
        deleteSkillRunCheckpoint(runId, requestId)
        return deletedPending
    }

    @Transaction
    fun deletePendingConfirmationWithRunCheckpoints(runId: String, requestId: String): Int {
        val deletedPending = deletePendingConfirmation(runId, requestId)
        deleteSkillRunCheckpointsForRun(runId)
        return deletedPending
    }

    @Query("DELETE FROM agent_runs WHERE sessionId = :sessionId")
    fun deleteRunsForSession(sessionId: String): Int

    @Transaction
    fun deleteRunGraphForSession(sessionId: String): Int {
        deleteStepsForSession(sessionId)
        deletePendingConfirmationsForSession(sessionId)
        deleteSkillRunCheckpointsForSession(sessionId)
        return deleteRunsForSession(sessionId)
    }
}

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        InstalledModelEntity::class,
        DownloadRecordEntity::class,
        ToolAuditEventEntity::class,
        RemoteSendAuditEventEntity::class,
        MemoryDeletionEventEntity::class,
        ScheduledTaskEntity::class,
        MemoryRecordEntity::class,
        MemoryEmbeddingEntity::class,
        AgentRunEntity::class,
        AgentRunPlacementBindingEntity::class,
        AgentStepEntity::class,
        PendingAgentConfirmationEntity::class,
        AgentSkillRunCheckpointEntity::class,
        LocalStorageMigrationStateEntity::class,
    ],
    version = 18,
    exportSchema = false,
)
abstract class SolinDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun modelDao(): ModelDao
    abstract fun downloadRecordDao(): DownloadRecordDao
    abstract fun toolAuditDao(): ToolAuditDao
    abstract fun remoteSendAuditDao(): RemoteSendAuditDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun memoryRecordDao(): MemoryRecordDao
    abstract fun memoryEmbeddingDao(): MemoryEmbeddingDao
    abstract fun memoryDeletionEventDao(): MemoryDeletionEventDao
    abstract fun memoryDeletionTransactionDao(): MemoryDeletionTransactionDao
    abstract fun agentTraceDao(): AgentTraceDao
    abstract fun runPlacementBindingDao(): RunPlacementBindingDao
    abstract fun localStorageMigrationStateDao(): LocalStorageMigrationStateDao

    companion object {
        @Volatile
        private var INSTANCE: SolinDatabase? = null

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
                db.execSQL(
                    "UPDATE `chat_messages` SET `privacy` = '${MessagePrivacy.LocalOnly.name}'",
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

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pending_agent_confirmations` ADD COLUMN `nextActionInput` TEXT",
                )
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_skill_run_checkpoints` (
                        `runId` TEXT NOT NULL,
                        `requestId` TEXT NOT NULL,
                        `skillId` TEXT NOT NULL,
                        `skillRequestId` TEXT NOT NULL,
                        `manifestId` TEXT NOT NULL,
                        `manifestVersion` INTEGER NOT NULL,
                        `manifestHash` TEXT NOT NULL,
                        `phase` TEXT NOT NULL,
                        `pendingStepIndex` INTEGER NOT NULL,
                        `pendingStepId` TEXT NOT NULL,
                        `pendingToolName` TEXT NOT NULL,
                        `completedStepIdsJson` TEXT NOT NULL,
                        `outputKeysByStepJson` TEXT NOT NULL,
                        `privateOutputRefsJson` TEXT NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`runId`, `requestId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent_runs` ADD COLUMN `sessionId` TEXT")
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pending_agent_confirmations` ADD COLUMN `continuationCursorJson` TEXT",
                )
                db.execSQL("UPDATE `pending_agent_confirmations` SET `nextActionInput` = NULL")
            }
        }

        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_messages` RENAME TO `chat_messages_legacy_default`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `sessionId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `role` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `tokenCount` INTEGER,
                        `tokensPerSecond` REAL,
                        `privacy` TEXT NOT NULL DEFAULT '${MessagePrivacy.LocalOnly.name}',
                        PRIMARY KEY(`sessionId`, `position`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `chat_messages` (
                        `sessionId`,
                        `position`,
                        `role`,
                        `text`,
                        `tokenCount`,
                        `tokensPerSecond`,
                        `privacy`
                    )
                    SELECT
                        `sessionId`,
                        `position`,
                        `role`,
                        `text`,
                        `tokenCount`,
                        `tokensPerSecond`,
                        `privacy`
                    FROM `chat_messages_legacy_default`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `chat_messages_legacy_default`")
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `remote_send_audit_events` (
                        `id` TEXT NOT NULL,
                        `decision` TEXT NOT NULL,
                        `modelName` TEXT,
                        `sensitiveCategoriesCsv` TEXT NOT NULL,
                        `imageCount` INTEGER NOT NULL,
                        `remoteHistoryCount` INTEGER NOT NULL,
                        `summary` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_embeddings` (
                        `recordId` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `sourceHash` TEXT NOT NULL,
                        `dimension` INTEGER NOT NULL,
                        `vectorBlob` BLOB NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`recordId`, `modelId`),
                        FOREIGN KEY(`recordId`) REFERENCES `memory_records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_embeddings_recordId` ON `memory_embeddings` (`recordId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_embeddings_modelId` ON `memory_embeddings` (`modelId`)")
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memory_records` ADD COLUMN `source` TEXT NOT NULL DEFAULT '${MemoryRecordSource.LegacyImport.name}'",
                )
                db.execSQL(
                    "ALTER TABLE `memory_records` ADD COLUMN `sensitivity` TEXT NOT NULL DEFAULT '${MemoryRecordSensitivity.Normal.name}'",
                )
                db.execSQL(
                    "ALTER TABLE `memory_records` ADD COLUMN `privacy` TEXT NOT NULL DEFAULT '${MessagePrivacy.LocalOnly.name}'",
                )
                db.execSQL("ALTER TABLE `memory_records` ADD COLUMN `expiresAtMillis` INTEGER")
                db.execSQL("ALTER TABLE `memory_records` ADD COLUMN `conflictKey` TEXT")
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_deletion_events` (
                        `id` TEXT NOT NULL,
                        `recordId` TEXT NOT NULL,
                        `recordType` TEXT NOT NULL,
                        `operation` TEXT NOT NULL,
                        `recordTextHash` TEXT NOT NULL,
                        `recordSource` TEXT NOT NULL,
                        `recordSensitivity` TEXT NOT NULL,
                        `conflictKey` TEXT,
                        `deletedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_storage_migration_state` (
                        `id` TEXT NOT NULL,
                        `phase` TEXT NOT NULL,
                        `lastDomain` TEXT,
                        `lastId` TEXT,
                        `startedAtMillis` INTEGER NOT NULL,
                        `completedAtMillis` INTEGER,
                        `errorJson` TEXT,
                        `schemaVersion` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_run_placement_bindings` (
                        `runId` TEXT NOT NULL,
                        `schemaVersion` INTEGER NOT NULL,
                        `policyVersion` INTEGER NOT NULL,
                        `preference` TEXT NOT NULL,
                        `placement` TEXT NOT NULL,
                        `primaryReason` TEXT NOT NULL,
                        `complexity` TEXT NOT NULL,
                        `resourceBand` TEXT NOT NULL,
                        `localState` TEXT NOT NULL,
                        `remoteState` TEXT NOT NULL,
                        `remoteProfileRevision` TEXT,
                        `bootCount` INTEGER NOT NULL,
                        `boundAtElapsedRealtimeMillis` INTEGER NOT NULL,
                        `dispatchState` TEXT NOT NULL,
                        `attempt` INTEGER NOT NULL,
                        PRIMARY KEY(`runId`),
                        FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        fun get(context: Context): SolinDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: createEncryptedDatabase(context.applicationContext)
                    .also { INSTANCE = it }
            }

        internal fun closeForInstrumentation() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        private fun createEncryptedDatabase(context: Context): SolinDatabase {
            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            val plaintextDatabase = EncryptedDatabaseMigrator.isPlaintextSqlite(databaseFile)
            val passphrase = LocalDatabaseKeyManager(context).loadOrCreatePassphrase(
                allowCreate = !databaseFile.exists() || plaintextDatabase,
            )
            val factory = SupportOpenHelperFactory(passphrase.copyOf())
            EncryptedDatabaseMigrator(
                context = context,
                databaseName = DATABASE_NAME,
                passphrase = passphrase,
                openCandidate = { candidateName, candidateFactory ->
                    databaseBuilder(context, candidateName, candidateFactory).build()
                },
            ).ensureEncrypted()
            return databaseBuilder(context, DATABASE_NAME, factory).build()
        }

        private fun databaseBuilder(
            context: Context,
            name: String,
            factory: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory,
        ): RoomDatabase.Builder<SolinDatabase> =
            Room.databaseBuilder(
                context,
                SolinDatabase::class.java,
                name,
            )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                )

        private const val DATABASE_NAME = "solin.db"
    }
}

const val PENDING_DOWNLOAD_RECORD_ID = "pending"
