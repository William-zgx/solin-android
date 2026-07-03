package com.bytedance.zgx.solin.orchestration

import java.util.UUID

sealed interface SolinEvent {
    val eventId: String
    val occurredAtMillis: Long
    val runId: String?
    val correlationId: String? get() = runId
    val replay: Int get() = DEFAULT_REPLAY

    sealed interface Agent : SolinEvent {
        data class RunStarted(
            override val runId: String,
            val modelLabel: String,
            val inputText: String,
            val profileId: String = "general",
            val parentRunId: String? = null,
            val depth: Int = 0,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class TurnStarted(
            override val runId: String,
            val turnIndex: Int,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class ToolPlanned(
            override val runId: String,
            val toolCallId: String,
            val toolName: String,
            val title: String? = null,
            val reason: String? = null,
            val requiresConfirmation: Boolean = false,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class ToolStarted(
            override val runId: String,
            val toolCallId: String,
            val toolName: String,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class ToolProgress(
            override val runId: String,
            val toolCallId: String,
            val partialText: String? = null,
            val progress: Float = -1f,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class ToolSucceeded(
            override val runId: String,
            val toolCallId: String,
            val toolName: String,
            val summary: String,
            val durationMs: Long? = null,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class ToolFailed(
            override val runId: String,
            val toolCallId: String,
            val toolName: String,
            val code: ToolErrorCode,
            val message: String? = null,
            val retryable: Boolean = false,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class TextDelta(
            override val runId: String,
            val text: String,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent {
            override val replay: Int get() = NO_REPLAY
        }

        data class TurnEnded(
            override val runId: String,
            val turnIndex: Int,
            val tokensIn: Int,
            val tokensOut: Int,
            val ttftMs: Long,
            val durationMs: Long,
            val reasoningTokens: Int = 0,
            val cacheReadTokens: Int = 0,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class RunEnded(
            override val runId: String,
            val totalTurns: Int = 0,
            val finalText: String? = null,
            val durationMs: Long? = null,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class RunFailed(
            override val runId: String,
            val code: AgentErrorCode,
            val message: String,
            val terminalTurnIndex: Int? = null,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class RunCancelled(
            override val runId: String,
            val reason: String? = null,
            val byUser: Boolean = true,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class RunSteered(
            override val runId: String,
            val injectedMessageCount: Int,
            val reason: String? = null,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class UserQuestionAsked(
            override val runId: String,
            val questionId: String,
            val prompt: String,
            val choices: List<String> = emptyList(),
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class UserQuestionAnswered(
            override val runId: String,
            val questionId: String,
            val answer: String,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class UserConfirmed(
            override val runId: String,
            val requestId: String,
            val toolCallId: String? = null,
            val actionLabel: String,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        data class UserRejected(
            override val runId: String,
            val requestId: String,
            val toolCallId: String? = null,
            val actionLabel: String,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        /**
         * Fired when the session plan store replaces the plan for a run (via plan_write or
         * internal update). Subscribers can use this to render a live todo/checklist UI.
         */
        data class PlanUpdated(
            override val runId: String,
            val itemCount: Int,
            val pendingCount: Int,
            val doneCount: Int,
            val updatedAtMillis: Long,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent

        /**
         * Fired when a just-completed tool produced a compensating undo plan. Only one
         * undo entry is live at a time; a subsequent successful tool replaces or clears it.
         */
        data class UndoPushed(
            override val runId: String,
            val sourceRequestId: String,
            val toolName: String,
            val summary: String,
            val availableUntilMillis: Long,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Agent
    }

    sealed interface Runtime : SolinEvent {
        data class ModelLoaded(
            val modelLabel: String,
            val loadMs: Long,
            override val runId: String? = null,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Runtime

        data class FirstToken(
            val modelLabel: String,
            val ttftMs: Long,
            override val runId: String? = null,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Runtime

        data class ResourceSample(
            val pressurePercent: Int,
            val appPssBytes: Long,
            val cpuPercent: Int?,
            val thermalPressureLabel: String? = null,
            override val runId: String? = null,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Runtime

        data class DownloadProgress(
            val modelLabel: String? = null,
            val bytesDownloaded: Long,
            val totalBytes: Long,
            override val runId: String? = null,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Runtime
    }

    sealed interface Audit : SolinEvent {
        /**
         * Fired when a tool audit record is persisted. Mirrors [com.bytedance.zgx.solin.audit.ToolAuditEvent]
         * fields closely enough that subscribers can reconstruct a full [com.bytedance.zgx.solin.audit.ToolAuditEvent]
         * for write-through to [com.bytedance.zgx.solin.audit.ToolAuditSink]. String-typed enum names are used
         * (rather than reified enum references) to keep the event hierarchy free of cyclic imports.
         */
        data class ToolAudited(
            override val runId: String?,
            val requestId: String?,
            val toolName: String?,
            val skillId: String? = null,
            /** Name of a [com.bytedance.zgx.solin.audit.ToolAuditEventType] entry. */
            val eventType: String,
            /** Name of a [com.bytedance.zgx.solin.tool.ToolStatus] entry, or null for lifecycle events. */
            val status: String? = null,
            /** Name of a [com.bytedance.zgx.solin.tool.RiskLevel] entry, if known. */
            val riskLevel: String? = null,
            /** Comma-separated [com.bytedance.zgx.solin.tool.ToolPermission] names; empty if unknown. */
            val permissionsCsv: String = "",
            val summary: String,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Audit

        /**
         * Fired when a remote-send decision is recorded. Field names mirror
         * [com.bytedance.zgx.solin.audit.RemoteSendAuditEvent]; [decision] is the name of a
         * [com.bytedance.zgx.solin.audit.RemoteSendDecision] entry and [sensitiveCategoriesCsv] is
         * a comma-sorted list of [com.bytedance.zgx.solin.safety.SafetyCategory] names.
         */
        data class RemoteSendAudited(
            override val runId: String?,
            val decision: String,
            val modelName: String?,
            val sensitiveCategoriesCsv: String = "",
            val imageCount: Int = 0,
            val remoteHistoryCount: Int = 0,
            val summary: String,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Audit
    }

    sealed interface Safety : SolinEvent {
        data class ConfirmationRequested(
            override val runId: String,
            val requestId: String,
            val toolCallId: String,
            val actionLabel: String,
            val riskLevel: String? = null,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Safety

        data class PolicyTriggered(
            override val runId: String,
            val policyName: String,
            val blocked: Boolean,
            val reason: String? = null,
            val toolCallId: String? = null,
            override val eventId: String = UUID.randomUUID().toString(),
            override val occurredAtMillis: Long = System.currentTimeMillis(),
        ) : Safety
    }

    companion object {
        const val DEFAULT_REPLAY: Int = 1
        const val NO_REPLAY: Int = 0
    }
}
