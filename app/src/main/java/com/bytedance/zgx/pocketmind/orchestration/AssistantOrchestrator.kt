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
import com.bytedance.zgx.pocketmind.tool.isPublicEvidenceBatchEligible
import com.bytedance.zgx.pocketmind.tool.isRemoteModelPlanningEligible

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
        val requiresUserConfirmation: Boolean = true,
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
        options: AgentRunOptions = AgentRunOptions(),
    ): AssistantRoute

    fun requestRecoveryAction(action: AgentRecoveryAction, sessionId: String? = null): AssistantRoute

    fun failStaleInFlightRuns(reason: String): Int

    fun failModelGeneration(runId: String, reason: String): AgentModelObservationResult?

    fun cancelRun(runId: String, reason: String): AgentModelObservationResult?

    fun confirmToolRequest(runId: String, requestId: String): AgentRun?

    fun cancelToolRequest(runId: String, requestId: String): AgentObservationResult?

    fun failPendingToolRequest(runId: String, requestId: String, result: ToolResult): AgentObservationResult?

    fun observeToolResult(runId: String, result: ToolResult): AgentObservationResult?

    fun recordExternalOutcome(
        runId: String,
        requestId: String,
        outcome: AgentExternalOutcome,
    ): AgentExternalOutcomeResult?

    fun observeModelResult(
        runId: String,
        text: String,
        allowInlineToolCalls: Boolean = true,
    ): AgentModelObservationResult?

    fun recordRemoteToolsExposed(
        runId: String,
        scope: RemoteToolScope,
        toolNames: Set<String>,
    )

    fun recordRunDataReceipt(runId: String, receipt: RunDataReceipt) = Unit

    fun recordModelOutputQualityGuardTriggered(runId: String, trace: ModelOutputQualityTrace) = Unit

    fun observeModelToolRequest(runId: String, request: ToolRequest): AgentModelObservationResult?

    fun observeModelToolRequests(runId: String, requests: List<ToolRequest>): AgentModelObservationResult? =
        when (requests.size) {
            1 -> observeModelToolRequest(runId, requests.single())
            else -> null
        }

    fun observeToolResults(runId: String, results: List<ToolResult>): AgentObservationResult? = null

    fun restorePendingAction(sessionId: String? = null): AssistantRoute.Action?

    fun restorePendingExternalOutcome(sessionId: String? = null): PendingExternalOutcomeSnapshot? = null

    fun recentTraceRuns(limit: Int = 5, stepLimit: Int = 20): List<AgentTraceRunSummary> = emptyList()

    fun deleteRunsForSession(sessionId: String): Int = 0

    fun availableToolSpecs(): List<ToolSpec> = emptyList()

    fun availableRemoteToolSpecs(scope: RemoteToolScope = RemoteToolScope.PublicEvidenceOnly): List<ToolSpec> =
        availableToolSpecs().filter { spec ->
            when (scope) {
                RemoteToolScope.PublicEvidenceOnly -> spec.isPublicEvidenceBatchEligible()
                RemoteToolScope.ModelPlanning -> spec.isRemoteModelPlanningEligible()
            }
        }
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
    deviceControlSessionFinisher: () -> Unit = {},
) : AssistantRouter {
    private val agentLoopRuntime = AgentLoopRuntime(
        memoryIndex = memoryIndex,
        actionPlanningRuntime = actionPlanningRuntime,
        toolRegistry = toolRegistry,
        auditSink = toolAuditSink,
        traceStore = traceStore,
        observationReplanner = observationReplanner,
        deviceControlSessionFinisher = deviceControlSessionFinisher,
    )

    override fun route(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
        actionModelPath: String?,
        deviceContext: DeviceContextSnapshot?,
        sessionId: String?,
        options: AgentRunOptions,
    ): AssistantRoute =
        agentLoopRuntime.runOnce(
            input = input,
            installedCapabilities = installedCapabilities,
            memoryEnabled = memoryEnabled,
            actionModelPath = actionModelPath,
            deviceContext = deviceContext,
            sessionId = sessionId,
            options = options,
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

    override fun recordExternalOutcome(
        runId: String,
        requestId: String,
        outcome: AgentExternalOutcome,
    ): AgentExternalOutcomeResult? =
        agentLoopRuntime.recordExternalOutcome(runId, requestId, outcome)

    override fun observeModelResult(
        runId: String,
        text: String,
        allowInlineToolCalls: Boolean,
    ): AgentModelObservationResult? =
        agentLoopRuntime.observeModelResult(
            runId = runId,
            text = text,
            allowInlineToolCalls = allowInlineToolCalls,
        )

    override fun recordRemoteToolsExposed(
        runId: String,
        scope: RemoteToolScope,
        toolNames: Set<String>,
    ) {
        agentLoopRuntime.recordRemoteToolsExposed(
            runId = runId,
            scope = scope,
            toolNames = toolNames,
        )
    }

    override fun recordRunDataReceipt(runId: String, receipt: RunDataReceipt) {
        agentLoopRuntime.recordRunDataReceipt(runId, receipt)
    }

    override fun recordModelOutputQualityGuardTriggered(runId: String, trace: ModelOutputQualityTrace) {
        agentLoopRuntime.recordModelOutputQualityGuardTriggered(runId, trace)
    }

    override fun observeModelToolRequest(runId: String, request: ToolRequest): AgentModelObservationResult? =
        agentLoopRuntime.observeModelToolRequest(runId, request)

    override fun observeModelToolRequests(
        runId: String,
        requests: List<ToolRequest>,
    ): AgentModelObservationResult? =
        agentLoopRuntime.observeModelToolRequests(runId, requests)

    override fun observeToolResults(runId: String, results: List<ToolResult>): AgentObservationResult? =
        agentLoopRuntime.observeToolResults(runId, results)

    override fun restorePendingAction(sessionId: String?): AssistantRoute.Action? =
        agentLoopRuntime.latestPendingConfirmation(sessionId)?.toAssistantRoute()

    override fun restorePendingExternalOutcome(sessionId: String?): PendingExternalOutcomeSnapshot? =
        agentLoopRuntime.latestPendingExternalOutcome(sessionId)

    override fun recentTraceRuns(limit: Int, stepLimit: Int): List<AgentTraceRunSummary> =
        traceStore.recentRunSummaries(limit = limit, stepLimit = stepLimit)

    override fun deleteRunsForSession(sessionId: String): Int =
        traceStore.deleteRunsForSession(sessionId)

    override fun availableToolSpecs(): List<ToolSpec> =
        toolRegistry.specs()

    override fun availableRemoteToolSpecs(scope: RemoteToolScope): List<ToolSpec> =
        availableToolSpecs().filter { spec ->
            when (scope) {
                RemoteToolScope.PublicEvidenceOnly -> spec.isPublicEvidenceBatchEligible()
                RemoteToolScope.ModelPlanning -> spec.isRemoteModelPlanningEligible()
            }
        }

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
        requiresUserConfirmation = true,
    )
