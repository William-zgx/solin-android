package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult

enum class AgentRunState {
    Created,
    LoadingContext,
    Planning,
    AwaitingUserConfirmation,
    ExecutingTool,
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
)

sealed class AgentPlan {
    data class Answer(
        val promptForModel: String,
        val memoryHits: List<MemoryHit>,
    ) : AgentPlan()

    data class UseTool(
        val request: ToolRequest,
        val draft: ActionDraft,
        val plannedByModel: Boolean,
        val fallbackReason: String?,
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
    ) : AgentStep()

    data class ModelPlanned(
        val plan: AgentPlan,
    ) : AgentStep()

    data class ToolRequested(
        val request: ToolRequest,
        val draft: ActionDraft,
    ) : AgentStep()

    data class UserConfirmationRequested(
        val request: ToolRequest,
        val draft: ActionDraft,
    ) : AgentStep()

    data class ToolRejected(
        val result: ToolResult,
    ) : AgentStep()

    data class Failed(
        val reason: String,
    ) : AgentStep()
}

data class AgentLoopResult(
    val run: AgentRun,
    val plan: AgentPlan,
    val steps: List<AgentStep>,
)
