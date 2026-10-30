package com.bytedance.zgx.solin.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.memory.MemoryRecordSensitivity
import com.bytedance.zgx.solin.memory.MemoryRecordSource

/* Room entities of [SolinDatabase]. Split out of SolinDatabase.kt; schema unchanged. */

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
