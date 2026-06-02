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
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolSpec

sealed class AssistantRoute {
    data class Chat(
        val runId: String?,
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
        sessionId: String? = null,
    ): AssistantRoute

    fun requestRecoveryAction(action: AgentRecoveryAction, sessionId: String? = null): AssistantRoute

    fun failStaleInFlightRuns(reason: String): Int

    fun failModelGeneration(runId: String, reason: String): AgentModelObservationResult?

    fun cancelRun(runId: String, reason: String): AgentModelObservationResult?

    fun confirmToolRequest(runId: String, requestId: String): AgentRun?

    fun cancelToolRequest(runId: String, requestId: String): AgentObservationResult?

    fun failPendingToolRequest(runId: String, requestId: String, result: ToolResult): AgentObservationResult?

    fun observeToolResult(runId: String, result: ToolResult): AgentObservationResult?

    fun observeModelResult(runId: String, text: String): AgentModelObservationResult?

    fun observeModelToolRequest(runId: String, request: ToolRequest): AgentModelObservationResult?

    fun restorePendingAction(sessionId: String? = null): AssistantRoute.Action?

    fun recentTraceRuns(limit: Int = 5, stepLimit: Int = 20): List<AgentTraceRunSummary> = emptyList()

    fun deleteRunsForSession(sessionId: String): Int = 0

    fun availableToolSpecs(): List<ToolSpec> = emptyList()
}

class AssistantOrchestrator(
    private val memoryIndex: MemoryIndex,
    private val actionPlanningRuntime: ActionPlanningRuntime,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    toolAuditSink: ToolAuditSink = NoOpToolAuditSink,
    private val traceStore: AgentTraceStore = InMemoryAgentTraceStore(),
    actionModelPathProvider: () -> String? = { null },
    observationReplanner: AgentObservationReplanner = CompositeAgentObservationReplanner(
        ModelObservationReplanner(
            actionPlanningRuntime = actionPlanningRuntime,
            actionModelPathProvider = actionModelPathProvider,
            toolRegistry = toolRegistry,
        ),
        SequentialActionObservationReplanner(actionPlanningRuntime),
    ),
) : AssistantRouter {
    private val agentLoopRuntime = AgentLoopRuntime(
        memoryIndex = memoryIndex,
        actionPlanningRuntime = actionPlanningRuntime,
        toolRegistry = toolRegistry,
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
        sessionId: String?,
    ): AssistantRoute =
        agentLoopRuntime.runOnce(
            input = input,
            installedCapabilities = installedCapabilities,
            memoryEnabled = memoryEnabled,
            actionModelPath = actionModelPath,
            deviceContext = deviceContext,
            sessionId = sessionId,
        ).toAssistantRoute()

    override fun requestRecoveryAction(action: AgentRecoveryAction, sessionId: String?): AssistantRoute =
        agentLoopRuntime.requestRecoveryAction(action, sessionId)?.toAssistantRoute()
            ?: AssistantRoute.ToolRejected("撤销动作不可用")

    override fun failStaleInFlightRuns(reason: String): Int =
        agentLoopRuntime.failStaleInFlightRuns(reason)

    override fun failModelGeneration(runId: String, reason: String): AgentModelObservationResult? =
        agentLoopRuntime.failModelGeneration(runId, reason)

    override fun cancelRun(runId: String, reason: String): AgentModelObservationResult? =
        agentLoopRuntime.cancelRun(runId, reason)

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

    override fun observeModelToolRequest(runId: String, request: ToolRequest): AgentModelObservationResult? =
        agentLoopRuntime.observeModelToolRequest(runId, request)

    override fun restorePendingAction(sessionId: String?): AssistantRoute.Action? =
        agentLoopRuntime.latestPendingConfirmation(sessionId)?.toAssistantRoute()

    override fun recentTraceRuns(limit: Int, stepLimit: Int): List<AgentTraceRunSummary> =
        traceStore.recentRunSummaries(limit = limit, stepLimit = stepLimit)

    override fun deleteRunsForSession(sessionId: String): Int =
        traceStore.deleteRunsForSession(sessionId)

    override fun availableToolSpecs(): List<ToolSpec> =
        toolRegistry.specs()

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
