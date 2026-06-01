package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.device.DeviceContextSnapshot
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.safety.SafetyDecision
import com.bytedance.zgx.pocketmind.skill.SkillPlan
import com.bytedance.zgx.pocketmind.skill.SkillRequest
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult

enum class AgentRunState {
    Created,
    LoadingContext,
    Planning,
    AwaitingUserConfirmation,
    ExecutingTool,
    RetryingTool,
    Observing,
    GeneratingAnswer,
    Completed,
    Cancelled,
    Failed,
}

data class AgentRun(
    val id: String,
    val input: String,
    val state: AgentRunState,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
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
    val retryRequest: ToolRequest? = null,
    val retryAttempt: Int = 0,
    val steps: List<AgentStep>,
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
)
