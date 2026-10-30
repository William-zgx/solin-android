package com.bytedance.zgx.solin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/* Room DAOs of [SolinDatabase]. Split out of SolinDatabase.kt; queries unchanged. */

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
