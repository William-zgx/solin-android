package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.IntentRoutingDecision
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRequest
import com.bytedance.zgx.solin.skill.SkillRunCheckpoint
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult

enum class AgentRunState {
    Created,
    LoadingContext,
    Planning,
    AwaitingUserConfirmation,
    ExecutingTool,
    RetryingTool,
    Observing,
    GeneratingAnswer,
    AwaitingExternalOutcome,
    Completed,
    Cancelled,
    Failed,
}

enum class AgentRuntimePhase {
    Created,
    LoadingContext,
    Planning,
    NeedsConfirmation,
    Executing,
    WaitingExternalResult,
    GeneratingAnswer,
    Completed,
    Cancelled,
    Failed,
}

fun AgentRunState.contractPhase(): AgentRuntimePhase =
    when (this) {
        AgentRunState.Created -> AgentRuntimePhase.Created
        AgentRunState.LoadingContext -> AgentRuntimePhase.LoadingContext
        AgentRunState.Planning -> AgentRuntimePhase.Planning
        AgentRunState.AwaitingUserConfirmation -> AgentRuntimePhase.NeedsConfirmation
        AgentRunState.ExecutingTool,
        AgentRunState.RetryingTool,
        AgentRunState.Observing -> AgentRuntimePhase.Executing

        AgentRunState.GeneratingAnswer -> AgentRuntimePhase.GeneratingAnswer
        AgentRunState.AwaitingExternalOutcome -> AgentRuntimePhase.WaitingExternalResult
        AgentRunState.Completed -> AgentRuntimePhase.Completed
        AgentRunState.Cancelled -> AgentRuntimePhase.Cancelled
        AgentRunState.Failed -> AgentRuntimePhase.Failed
    }

enum class RunDataDestination {
    Local,
    Remote,
}

data class RunDataReceipt(
    val destination: RunDataDestination,
    val currentPromptPrivacy: String,
    val remoteHistoryCount: Int = 0,
    val localOnlyHistoryFilteredCount: Int = 0,
    val memoryHitCount: Int = 0,
    val semanticMemoryHitCount: Int = 0,
    val lexicalMemoryHitCount: Int = 0,
    val memoryContextIncluded: Boolean = false,
    val deviceContextIncluded: Boolean = false,
    val imageAttachmentCount: Int = 0,
    val protectedSourceCount: Int = 0,
    val evidenceCardCount: Int = 0,
    val localOnlyEvidenceCardCount: Int = 0,
    val truncatedEvidenceCardCount: Int = 0,
    val lowQualityEvidenceCardCount: Int = 0,
    val evidenceSourceTypes: List<String> = emptyList(),
    val rawContentPersisted: Boolean = false,
    val protectedContentTypes: List<String> = emptyList(),
    val deletableRecordTypes: List<String> = listOf("对话消息", "Agent 轨迹"),
    val outputQualityGuardTriggered: Boolean = false,
    val outputQualityIssue: String? = null,
    val outputQualityRule: String? = null,
    val outputQualityAction: String? = null,
    val outputQualityStopped: Boolean = false,
    val outputQualityKeptPrefix: Boolean = false,
) {
    init {
        require(remoteHistoryCount >= 0) { "remoteHistoryCount must be >= 0" }
        require(localOnlyHistoryFilteredCount >= 0) { "localOnlyHistoryFilteredCount must be >= 0" }
        require(memoryHitCount >= 0) { "memoryHitCount must be >= 0" }
        require(semanticMemoryHitCount >= 0) { "semanticMemoryHitCount must be >= 0" }
        require(lexicalMemoryHitCount >= 0) { "lexicalMemoryHitCount must be >= 0" }
        require(imageAttachmentCount >= 0) { "imageAttachmentCount must be >= 0" }
        require(protectedSourceCount >= 0) { "protectedSourceCount must be >= 0" }
        require(evidenceCardCount >= 0) { "evidenceCardCount must be >= 0" }
        require(localOnlyEvidenceCardCount >= 0) { "localOnlyEvidenceCardCount must be >= 0" }
        require(truncatedEvidenceCardCount >= 0) { "truncatedEvidenceCardCount must be >= 0" }
        require(lowQualityEvidenceCardCount >= 0) { "lowQualityEvidenceCardCount must be >= 0" }
    }
}

data class ModelOutputQualityTrace(
    val issue: String,
    val severity: String,
    val triggeredRule: String,
    val action: String,
    val rawOutputLength: Int,
    val keptPrefix: Boolean,
    val modelId: String?,
    val backend: String?,
    val runtimeKind: String,
)

data class AgentRun(
    val id: String,
    val input: String,
    val state: AgentRunState,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val sessionId: String? = null,
)

data class AgentContext(
    val memoryHits: List<MemoryHit>,
    val deviceContext: DeviceContextSnapshot? = null,
)

sealed class AgentPlan {
    data class Answer(
        val promptForModel: String,
        val memoryHits: List<MemoryHit>,
        val deviceContext: DeviceContextSnapshot? = null,
    ) : AgentPlan()

    data class UseTool(
        val request: ToolRequest,
        val draft: ActionDraft,
        val plannedByModel: Boolean,
        val fallbackReason: String?,
        val skillRequest: SkillRequest? = null,
        val skillPlan: SkillPlan? = null,
        val safetyDecision: SafetyDecision,
    ) : AgentPlan()

    data class RejectedTool(
        val result: ToolResult,
    ) : AgentPlan()

    data class MissingModel(
        val capability: ModelCapability,
    ) : AgentPlan()
}

sealed class AgentStep {
    data class ContextLoaded(
        val memoryHits: List<MemoryHit>,
        val deviceContext: DeviceContextSnapshot? = null,
    ) : AgentStep()

    data class ModelPlanned(
        val plan: AgentPlan,
    ) : AgentStep()

    data class IntentRouted(
        val decision: IntentRoutingDecision,
    ) : AgentStep()

    data class RemoteToolsExposed(
        val scope: RemoteToolScope,
        val toolNames: List<String>,
    ) : AgentStep()

    data class RunDataReceiptRecorded(
        val receipt: RunDataReceipt,
    ) : AgentStep()

    data class ModelOutputQualityGuardTriggered(
        val trace: ModelOutputQualityTrace,
    ) : AgentStep()

    data class ToolRequested(
        val request: ToolRequest,
        val draft: ActionDraft,
    ) : AgentStep()

    data class SkillPlanned(
        val request: SkillRequest,
        val plan: SkillPlan? = null,
    ) : AgentStep()

    data class SafetyChecked(
        val decision: SafetyDecision,
    ) : AgentStep()

    data class UserConfirmationRequested(
        val request: ToolRequest,
        val draft: ActionDraft,
    ) : AgentStep()

    data class UserConfirmed(
        val requestId: String,
    ) : AgentStep()

    data class UserRejected(
        val requestId: String,
    ) : AgentStep()

    data class ToolObserved(
        val result: ToolResult,
    ) : AgentStep()

    data class ExternalOutcomeConfirmed(
        val requestId: String,
        val outcome: AgentExternalOutcome,
        val result: ToolResult,
    ) : AgentStep()

    data class ContinuationCursorRecorded(
        val cursor: AgentContinuationCursor,
    ) : AgentStep()

    data class ToolRetryScheduled(
        val request: ToolRequest,
        val attempt: Int,
        val reason: String,
    ) : AgentStep()

    data class ObservationDecided(
        val decision: AgentObservationDecision,
    ) : AgentStep()

    data class AssistantResponded(
        val text: String,
    ) : AgentStep()

    data class ToolRejected(
        val result: ToolResult,
    ) : AgentStep()

    data class Failed(
        val reason: String,
    ) : AgentStep()

    data class RestoredSummary(
        val persistedType: String,
        val summary: String,
        val json: String,
    ) : AgentStep()
}

data class AgentLoopResult(
    val run: AgentRun,
    val plan: AgentPlan,
    val steps: List<AgentStep>,
)

sealed class AgentObservationDecision {
    data object Complete : AgentObservationDecision()

    data class ContinueWithModel(
        val requiresLocalModel: Boolean,
        val reason: String,
    ) : AgentObservationDecision()

    data class RetryTool(
        val request: ToolRequest,
        val attempt: Int,
        val reason: String,
    ) : AgentObservationDecision()

    data class PlanNextTool(
        val plan: AgentPlan.UseTool,
        val reason: String,
    ) : AgentObservationDecision()

    data class PlanToolBatch(
        val plans: List<AgentPlan.UseTool>,
        val reason: String,
    ) : AgentObservationDecision()

    data class Fail(
        val reason: String,
    ) : AgentObservationDecision()

    data object Cancel : AgentObservationDecision()
}

data class AgentObservationResult(
    val run: AgentRun,
    val result: ToolResult,
    val assistantMessage: String,
    val decision: AgentObservationDecision,
    val recoveryAction: AgentRecoveryAction? = null,
    val continuationPromptForModel: String? = null,
    val continuationRequiresLocalModel: Boolean = false,
    val continuationRemoteToolScope: RemoteToolScope = RemoteToolScope.PublicEvidenceOnly,
    val retryRequest: ToolRequest? = null,
    val retryAttempt: Int = 0,
    val steps: List<AgentStep>,
)

enum class AgentExternalOutcome {
    Completed,
    NotCompleted,
    OpenedOnly,
}

data class AgentExternalOutcomeResult(
    val run: AgentRun,
    val result: ToolResult,
    val assistantMessage: String,
    val decision: AgentObservationDecision,
    val steps: List<AgentStep>,
)

data class PendingExternalOutcomeSnapshot(
    val runId: String,
    val requestId: String,
    val toolName: String,
    val title: String,
    val summary: String,
)

data class AgentContinuationCursor(
    val sourceRequestId: String,
    val completedSegmentCount: Int,
    val request: ToolRequest,
    val draft: ActionDraft,
    val plannedByModel: Boolean = false,
    val fallbackReason: String? = null,
    val skillPlan: SkillPlan? = null,
)

data class AgentContinuationCursorV2(
    val sourceRequestId: String,
    val completedSegmentCount: Int,
    val request: ToolRequest,
    val draft: ActionDraft,
    val plannedByModel: Boolean = false,
    val fallbackReason: String? = null,
    val skillPlan: SkillPlan? = null,
    val currentResultRevalidated: Boolean = false,
    val rawPayloadPersisted: Boolean = false,
) {
    fun restoreDecisionFor(sourceRequestId: String): AgentContinuationCursorRestoreDecision =
        when {
            this.sourceRequestId != sourceRequestId ->
                AgentContinuationCursorRestoreDecision.NotRestorable("continuation cursor source request changed")
            rawPayloadPersisted || request.arguments.isNotEmpty() || draft.parameters.isNotEmpty() ->
                AgentContinuationCursorRestoreDecision.NotRestorable("continuation cursor contains executable payload")
            !currentResultRevalidated ->
                AgentContinuationCursorRestoreDecision.NotRestorable("continuation cursor requires current result revalidation")
            plannedByModel ->
                AgentContinuationCursorRestoreDecision.NotRestorable("model-planned continuation cursor is not restorable")
            skillPlan?.request?.arguments?.isNotEmpty() == true ->
                AgentContinuationCursorRestoreDecision.NotRestorable("skill continuation cursor contains skill input payload")
            else -> AgentContinuationCursorRestoreDecision.Restorable
        }
}

sealed class AgentContinuationCursorRestoreDecision {
    data object Restorable : AgentContinuationCursorRestoreDecision()

    data class NotRestorable(
        val reason: String,
    ) : AgentContinuationCursorRestoreDecision()
}

fun AgentContinuationCursor.toV2(
    currentResultRevalidated: Boolean = false,
    rawPayloadPersisted: Boolean = request.arguments.isNotEmpty() || draft.parameters.isNotEmpty(),
): AgentContinuationCursorV2 =
    AgentContinuationCursorV2(
        sourceRequestId = sourceRequestId,
        completedSegmentCount = completedSegmentCount,
        request = request,
        draft = draft,
        plannedByModel = plannedByModel,
        fallbackReason = fallbackReason,
        skillPlan = skillPlan,
        currentResultRevalidated = currentResultRevalidated,
        rawPayloadPersisted = rawPayloadPersisted,
    )

data class AgentModelObservationResult(
    val run: AgentRun,
    val decision: AgentObservationDecision,
    val steps: List<AgentStep>,
)

data class AgentRecoveryAction(
    val sourceRequestId: String,
    val sourceToolName: String,
    val request: ToolRequest,
    val draft: ActionDraft,
)

data class PendingToolConfirmationSnapshot(
    val run: AgentRun,
    val request: ToolRequest,
    val draft: ActionDraft,
    val skillId: String?,
    val skillPlan: SkillPlan? = null,
    val plannedByModel: Boolean,
    val fallbackReason: String?,
    val nextActionInput: String? = null,
    val continuationCursor: AgentContinuationCursor? = null,
    val skillRunCheckpoint: SkillRunCheckpoint? = null,
)
