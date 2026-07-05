package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.audit.NoOpToolAuditSink
import com.bytedance.zgx.solin.audit.ToolAuditSink
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.plan.SessionPlanStore
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.isEligibleForParallelBatch
import com.bytedance.zgx.solin.tool.isRemoteModelPlanningEligible

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
        installedCapabilityProfiles: List<ModelCapabilityProfile> = emptyList(),
    ): AssistantRoute

    fun requestRecoveryAction(action: AgentRecoveryAction, sessionId: String? = null): AssistantRoute

    fun failStaleInFlightRuns(reason: String): Int

    fun failModelGeneration(runId: String, reason: String): AgentModelObservationResult?

    fun cancelRun(runId: String, reason: String): AgentModelObservationResult?

    /**
     * Queue mid-run steering [messages] for [runId]. When the run is currently mid-model-stream,
     * the in-flight generation is cooperatively cancelled (tool execution is NEVER interrupted for
     * safety) and the steer messages are injected as the next user turn before the replan. Messages
     * are drained (steer first, then any normal-queued user input) at the top of each model call.
     * Returns true if the run is currently active (messages enqueued); false if unknown/terminal
     * (messages discarded).
     *
     * Default impl returns false (no-op router).
     */
    fun steerRun(runId: String, messages: List<ChatMessage>): Boolean = false

    fun confirmToolRequest(runId: String, requestId: String): AgentRun?

    fun cancelToolRequest(runId: String, requestId: String): AgentObservationResult?

    fun failPendingToolRequest(runId: String, requestId: String, result: ToolResult): AgentObservationResult?

    /**
     * Resume an `ask_user` parking point by supplying the user's [answer]. Mirrors
     * [confirmToolRequest] but produces a synthetic tool result and feeds it through the
     * observation pipeline (like [cancelToolRequest]) so the agent replans with the answer.
     */
    fun answerUserQuestion(runId: String, questionId: String, answer: String): AgentObservationResult?

    /**
     * Cancel an outstanding `ask_user` parking point. Synthesizes a Cancelled tool result so
     * the agent sees explicit cancellation and can replan or terminate.
     */
    fun cancelUserQuestion(runId: String, questionId: String): AgentObservationResult?

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

    /**
     * Preflight hook: compact [messages] to fit [tokenBudget] before a model call. Default impl
     * is a no-op (returns messages unchanged); production wiring supplies [DefaultContextCompactor]
     * so history is truncated/summarized when it approaches the window, and the ONE-retry
     * overflow-recovery path calls this with [force]=true after a context-length error.
     */
    suspend fun compactHistory(
        messages: List<ChatMessage>,
        tokenBudget: Int,
        runId: String? = null,
        force: Boolean = false,
        estimatedTokens: ((List<ChatMessage>) -> Int)? = null,
    ): List<ChatMessage> = messages

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

    fun runEvents(runId: String): List<AgentRunEvent> = emptyList()

    fun publicWebEvidence(runId: String): List<PublicWebEvidencePack> = emptyList()

    fun deleteRunsForSession(sessionId: String): Int = 0

    fun availableToolSpecs(): List<ToolSpec> = emptyList()

    fun availableRemoteToolSpecs(scope: RemoteToolScope = RemoteToolScope.PublicEvidenceOnly): List<ToolSpec> =
        availableToolSpecs().filter { spec ->
            when (scope) {
                RemoteToolScope.PublicEvidenceOnly -> spec.isEligibleForParallelBatch()
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
    private val skillRuntime: SkillRuntime = BuiltInSkillRuntime(),
    actionModelPathProvider: () -> String? = { null },
    observationReplanner: AgentObservationReplanner = CompositeAgentObservationReplanner(
        DeadLoopDetectionReplanner(),
        ModelObservationReplanner(
            actionPlanningRuntime = actionPlanningRuntime,
            actionModelPathProvider = actionModelPathProvider,
            toolRegistry = toolRegistry,
        ),
        SequentialActionObservationReplanner(
            actionPlanningRuntime = actionPlanningRuntime,
            toolRegistry = toolRegistry,
        ),
    ),
    deviceControlSessionFinisher: () -> Unit = {},
    private val eventBus: SolinEventBus = NoOpSolinEventBus,
    private val hooks: AgentHooks = NoOpAgentHooks,
    private val telemetrySink: TelemetrySink = NoOpTelemetrySink,
    private val systemContextContributors: List<SystemContextContributor> = emptyList(),
    systemPromptBuilder: SystemPromptBuilder? = null,
    private val evidenceBlobStore: EvidenceBlobStore = NoOpEvidenceBlobStore,
    private val sessionPlanStore: SessionPlanStore? = null,
    private val contextCompactor: ContextCompactor = NoOpContextCompactor,
) : AssistantRouter {
    private val resolvedSystemPromptBuilder = systemPromptBuilder
        ?: systemContextContributors.takeIf { it.isNotEmpty() }?.let {
            SystemPromptBuilder(contributors = it, includeDeviceControlSurvivalRules = true)
        }
    private val agentLoopRuntime = AgentLoopRuntime(
        memoryIndex = memoryIndex,
        actionPlanningRuntime = actionPlanningRuntime,
        toolRegistry = toolRegistry,
        auditSink = toolAuditSink,
        traceStore = traceStore,
        skillRuntime = skillRuntime,
        observationReplanner = observationReplanner,
        deviceControlSessionFinisher = deviceControlSessionFinisher,
        eventBus = eventBus,
        hooks = hooks,
        telemetrySink = telemetrySink,
        systemPromptBuilder = resolvedSystemPromptBuilder,
        evidenceBlobStore = evidenceBlobStore,
        sessionPlanStore = sessionPlanStore,
        contextCompactor = contextCompactor,
    )

    override fun route(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
        actionModelPath: String?,
        deviceContext: DeviceContextSnapshot?,
        sessionId: String?,
        options: AgentRunOptions,
        installedCapabilityProfiles: List<ModelCapabilityProfile>,
    ): AssistantRoute =
        agentLoopRuntime.runOnce(
            input = input,
            installedCapabilities = installedCapabilities,
            memoryEnabled = memoryEnabled,
            actionModelPath = actionModelPath,
            deviceContext = deviceContext,
            sessionId = sessionId,
            options = options,
            installedCapabilityProfiles = installedCapabilityProfiles,
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

    override fun steerRun(runId: String, messages: List<ChatMessage>): Boolean =
        agentLoopRuntime.steerRun(runId, messages)

    /**
     * High-priority steer: cancels the in-flight model generation (when the run is mid-model-
     * stream; never interrupts tool execution for safety) and enqueues [messages] to be the next
     * user turn on the immediately-following model call. Mirrors [steerRun] on AssistantRouter
     * but returns the boolean accepted/active flag from the runtime.
     */
    fun steer(runId: String, messages: List<ChatMessage>): Boolean =
        agentLoopRuntime.steer(messages = messages, runId = runId)

    /**
     * Normal-priority queued user input: appends [messages] as fresh user turns at the next drain
     * point (after current turn/tool completes, before the next model call). Does not cancel
     * anything. Returns true if the run was active and the batch was accepted; false if unknown
     * or terminal (messages dropped).
     */
    fun queueUserInput(runId: String, messages: List<ChatMessage>): Boolean =
        agentLoopRuntime.queue(messages = messages, runId = runId)

    /**
     * Register/unregister the currently in-flight model streaming Job for [runId] so the runtime
     * can cooperatively cancel it when [steerRun] / [steer] arrives mid-model-stream. Callers
     * (typically SolinViewModel) MUST pair these calls around their `.collect { chunk -> ... }`
     * loop, ideally via `try/finally` to clear the reference on normal completion or error.
     *
     * Tool-execution Jobs must NOT be registered (tool execution is non-cancellable in v1 for
     * safety). Only model streaming collection Jobs should be registered.
     */
    fun registerModelCallJob(runId: String, job: kotlinx.coroutines.Job) {
        agentLoopRuntime.registerModelCallJob(runId, job)
    }

    fun unregisterModelCallJob(runId: String, job: kotlinx.coroutines.Job? = null) {
        agentLoopRuntime.unregisterModelCallJob(runId, job)
    }

    /** Expose drain so upper layers (ViewModel) can pull+prepend pending steering at turn start. */
    fun drainSteeringMessages(runId: String): List<ChatMessage> =
        agentLoopRuntime.drainSteeringMessages(runId)

    /**
     * Wave 8: structured drain returning steer vs queued messages separately. Upper layers that
     * want to distinguish high-priority steer interruptions (e.g. to mark them in UI or flush
     * partial model output) should prefer this over the legacy single-list [drainSteeringMessages].
     */
    fun drainPendingMessages(runId: String): PendingMessagesDrain =
        agentLoopRuntime.drainPendingMessages(runId)

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

    override fun answerUserQuestion(
        runId: String,
        questionId: String,
        answer: String,
    ): AgentObservationResult? =
        agentLoopRuntime.answerUserQuestion(runId, questionId, answer)

    override fun cancelUserQuestion(runId: String, questionId: String): AgentObservationResult? =
        agentLoopRuntime.cancelUserQuestion(runId, questionId)

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

    override fun runEvents(runId: String): List<AgentRunEvent> =
        agentLoopRuntime.runEvents(runId)

    override fun publicWebEvidence(runId: String): List<PublicWebEvidencePack> =
        agentLoopRuntime.publicWebEvidence(runId)

    override fun deleteRunsForSession(sessionId: String): Int =
        traceStore.deleteRunsForSession(sessionId)

    override fun availableToolSpecs(): List<ToolSpec> =
        toolRegistry.specs()

    override fun availableRemoteToolSpecs(scope: RemoteToolScope): List<ToolSpec> =
        availableToolSpecs().filter { spec ->
            when (scope) {
                RemoteToolScope.PublicEvidenceOnly -> spec.isEligibleForParallelBatch()
                RemoteToolScope.ModelPlanning -> spec.isRemoteModelPlanningEligible()
            }
        }

    override fun close() {
        (actionPlanningRuntime as? AutoCloseable)?.close()
    }

    // -----------------------------------------------------------------------
    // Wave 4: suspend hook/telemetry entry points forwarded to AgentLoopRuntime.
    // These are invoked by SolinViewModel at lifecycle points that occur
    // outside the reactive runtime (tool execution, message-list assembly).
    // -----------------------------------------------------------------------

    suspend fun beforeToolCall(runId: String, request: ToolRequest): BeforeToolCallResult =
        agentLoopRuntime.beforeToolCall(runId, request)

    suspend fun afterToolCall(
        runId: String,
        request: ToolRequest,
        result: ToolResult,
        durationMs: Long,
    ): AfterToolCallResult = agentLoopRuntime.afterToolCall(runId, request, result, durationMs)

    suspend fun transformContext(messages: List<ChatMessage>): List<ChatMessage> =
        agentLoopRuntime.transformContext(messages)

    suspend fun prepareNextTurn(
        runId: String,
        turnIndex: Int,
        messageCount: Int,
        pendingToolCalls: Int,
    ): TurnUpdate? = agentLoopRuntime.prepareNextTurn(runId, turnIndex, messageCount, pendingToolCalls)

    suspend fun shouldStopAfterTurn(
        runId: String,
        turnIndex: Int,
        messageCount: Int,
        pendingToolCalls: Int,
    ): Boolean = agentLoopRuntime.shouldStopAfterTurn(runId, turnIndex, messageCount, pendingToolCalls)

    /** Suspend-aware steering drain that includes hook-provided messages. */
    suspend fun drainSteeringMessagesSuspend(runId: String): List<ChatMessage> =
        agentLoopRuntime.drainSteeringMessagesSuspend(runId)

    /** Suspend-aware drain returning steer vs queued buckets; includes hook messages in steer. */
    suspend fun drainPendingMessagesSuspend(runId: String): PendingMessagesDrain =
        agentLoopRuntime.drainPendingMessagesSuspend(runId)

    fun recordCompactionTriggered(
        runId: String?,
        reason: String,
        contextBefore: Int,
        contextAfter: Int,
    ) {
        agentLoopRuntime.recordCompactionTriggered(runId, reason, contextBefore, contextAfter)
    }

    fun markStepStart(stepType: String) {
        agentLoopRuntime.markStepStart(stepType)
    }

    /**
     * Compact [messages] to fit [tokenBudget]. Passthrough to [AgentLoopRuntime.compactHistory]
     * so the ViewModel/orchestrator layer can preflight history before each model send. Returns
     * the original list on any failure (caller then lets the model raise its native overflow).
     *
     * @param force When true, run aggressive compaction regardless of budget (used by the
     *   post-overflow ONE_RETRY path).
     * @param estimatedTokens Optional model-specific estimator; defaults to a chars/4 heuristic.
     */
    override suspend fun compactHistory(
        messages: List<ChatMessage>,
        tokenBudget: Int,
        runId: String?,
        force: Boolean,
        estimatedTokens: ((List<ChatMessage>) -> Int)?,
    ): List<ChatMessage> = agentLoopRuntime.compactHistory(
        messages = messages,
        tokenBudget = tokenBudget,
        runId = runId,
        force = force,
        estimatedTokens = estimatedTokens ?: ::estimateTokensApproximate,
    )
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
