package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.audit.NoOpToolAuditSink
import com.bytedance.zgx.pocketmind.audit.ToolAuditSink
import com.bytedance.zgx.pocketmind.device.DeviceContextSnapshot
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.memory.MemoryIndex
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolRequest

sealed class AssistantRoute {
    data class Chat(
        val promptForModel: String,
        val memoryHits: List<MemoryHit>,
        val deviceContext: DeviceContextSnapshot? = null,
    ) : AssistantRoute()

    data class Action(
        val draft: ActionDraft,
        val plannedByModel: Boolean,
        val fallbackReason: String?,
        val runId: String? = null,
        val toolRequest: ToolRequest? = null,
        val skillId: String? = null,
    ) : AssistantRoute()

    data class ToolRejected(
        val summary: String,
    ) : AssistantRoute()

    data class MissingModel(
        val capability: ModelCapability,
    ) : AssistantRoute()
}

interface AssistantRouter : AutoCloseable {
    fun route(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
        actionModelPath: String? = null,
        deviceContext: DeviceContextSnapshot? = null,
    ): AssistantRoute

    fun confirmToolRequest(runId: String, requestId: String): AgentRun?

    fun cancelToolRequest(runId: String, requestId: String): AgentObservationResult?

    fun failPendingToolRequest(runId: String, requestId: String, result: ToolResult): AgentObservationResult?

    fun observeToolResult(runId: String, result: ToolResult): AgentObservationResult?

    fun observeModelResult(runId: String, text: String): AgentModelObservationResult?

    fun restorePendingAction(): AssistantRoute.Action?
}

class AssistantOrchestrator(
    private val memoryIndex: MemoryIndex,
    private val actionPlanningRuntime: ActionPlanningRuntime,
    toolAuditSink: ToolAuditSink = NoOpToolAuditSink,
    traceStore: AgentTraceStore = InMemoryAgentTraceStore(),
    observationReplanner: AgentObservationReplanner = SequentialActionObservationReplanner(actionPlanningRuntime),
) : AssistantRouter {
    private val agentLoopRuntime = AgentLoopRuntime(
        memoryIndex = memoryIndex,
        actionPlanningRuntime = actionPlanningRuntime,
        auditSink = toolAuditSink,
        traceStore = traceStore,
        observationReplanner = observationReplanner,
    )

    override fun route(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
        actionModelPath: String?,
        deviceContext: DeviceContextSnapshot?,
    ): AssistantRoute =
        agentLoopRuntime.runOnce(
            input = input,
            installedCapabilities = installedCapabilities,
            memoryEnabled = memoryEnabled,
            actionModelPath = actionModelPath,
            deviceContext = deviceContext,
        ).toAssistantRoute()

    override fun confirmToolRequest(runId: String, requestId: String): AgentRun? =
        agentLoopRuntime.confirmToolRequest(runId, requestId)

    override fun cancelToolRequest(runId: String, requestId: String): AgentObservationResult? =
        agentLoopRuntime.cancelToolRequest(runId, requestId)

    override fun failPendingToolRequest(
        runId: String,
        requestId: String,
        result: ToolResult,
    ): AgentObservationResult? =
        agentLoopRuntime.failPendingToolRequest(runId, requestId, result)

    override fun observeToolResult(runId: String, result: ToolResult): AgentObservationResult? =
        agentLoopRuntime.observeToolResult(runId, result)

    override fun observeModelResult(runId: String, text: String): AgentModelObservationResult? =
        agentLoopRuntime.observeModelResult(runId, text)

    override fun restorePendingAction(): AssistantRoute.Action? =
        agentLoopRuntime.latestPendingConfirmation()?.toAssistantRoute()

    override fun close() {
        (actionPlanningRuntime as? AutoCloseable)?.close()
    }
}

private fun PendingToolConfirmationSnapshot.toAssistantRoute(): AssistantRoute.Action =
    AssistantRoute.Action(
        runId = run.id,
        draft = draft,
        plannedByModel = plannedByModel,
        fallbackReason = fallbackReason,
        toolRequest = request,
        skillId = skillId,
    )
