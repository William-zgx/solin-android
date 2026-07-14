package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.AnswerCitationCheckStatus
import com.bytedance.zgx.solin.PublicWebEvidenceItem
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.checkPublicWebAnswerCitations
import com.bytedance.zgx.solin.publicWebEvidencePackFromToolResultData
import com.bytedance.zgx.solin.publicWebEvidencePacksFromToolResults
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.IntentRoutingDecision
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.audit.NoOpToolAuditSink
import com.bytedance.zgx.solin.audit.ToolAuditEvent
import com.bytedance.zgx.solin.audit.ToolAuditEventType
import com.bytedance.zgx.solin.audit.ToolAuditSink
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import com.bytedance.zgx.solin.plan.SessionPlanStore
import com.bytedance.zgx.solin.undo.UndoEntry
import com.bytedance.zgx.solin.undo.UndoPlan
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillModelStepBinding
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.skill.SkillRunProgressor
import com.bytedance.zgx.solin.skill.SkillStep
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.orchestration.ToolErrorCode as OrchestrationToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.cancelled
import com.bytedance.zgx.solin.tool.failed
import com.bytedance.zgx.solin.tool.isEligibleForParallelBatch
import com.bytedance.zgx.solin.tool.isRemoteModelPlanningEligible
import com.bytedance.zgx.solin.tool.EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX
import com.bytedance.zgx.solin.tool.isUserConfirmedCompletedExternalOutcome
import com.bytedance.zgx.solin.tool.isUnverifiedExternalLaunch
import com.bytedance.zgx.solin.tool.rejected
import com.bytedance.zgx.solin.tool.succeeded
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val REDACTED_AGENT_RUN_INPUT_VALUE = "[redacted]"
private const val RUN_CANCELLED_REASON = "Agent run was cancelled by the user."
private const val PENDING_EXTERNAL_OUTCOME_RESTORE_RUN_LIMIT = 20
private const val LOW_RISK_APP_CONTROL_UI_ACTION_CHECKPOINT_LIMIT = 5
private const val TAG = "AgentLoopRuntime"
private const val PUBLIC_EVIDENCE_CITATION_RETRY_REASON = "public_evidence_citation_retry"
private const val MAX_VERBOSE_TRACE_THINK_TEXT_CHARS = 4_000

/**
 * Envelope for messages pushed onto the steer/queued in-memory channels. Carries the target [runId]
 * alongside the ChatMessage batch so a single pair of per-runtime Channels can route messages to
 * the correct run even when multiple runs are tracked concurrently (e.g. subagent runs).
 *
 * v1 contract: channels are strictly in-memory; if the process dies, undelivered batches are lost
 * (acceptable for steer/queued user input because the UI layer retains the visible chat history).
 */
private data class PendingMessageBatch(
    val runId: String,
    val messages: List<ChatMessage>,
)

/**
 * Result of a drain-point pull on the steer/queued channels for a single run. [steer] contains
 * high-priority steer batches first (FIFO within class, user interruptions + hook messages),
 * [queued] contains normal-priority queued user messages. Callers should typically concatenate
 * steer + queued and APPEND to outgoing ChatMessage history as the most recent user turns.
 */
data class PendingMessagesDrain(
    val steer: List<ChatMessage>,
    val queued: List<ChatMessage>,
) {
    fun isEmpty(): Boolean = steer.isEmpty() && queued.isEmpty()

    val all: List<ChatMessage> get() = steer + queued
}

class AgentLoopRuntime(
    private val memoryIndex: MemoryIndex,
    private val actionPlanningRuntime: ActionPlanningRuntime,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val skillRuntime: SkillRuntime = BuiltInSkillRuntime(),
    private val safetyPolicy: SafetyPolicy = SafetyPolicy(),
    private val auditSink: ToolAuditSink = NoOpToolAuditSink,
    private val traceStore: AgentTraceStore = InMemoryAgentTraceStore(),
    private val observationReplanner: AgentObservationReplanner = NoOpAgentObservationReplanner,
    private val eventBus: SolinEventBus = NoOpSolinEventBus,
    private val hooks: AgentHooks = NoOpAgentHooks,
    private val telemetrySink: TelemetrySink = NoOpTelemetrySink,
    private val maxToolRetryAttempts: Int = SolinConstants.AgentLoop.MAX_TOOL_RETRY_ATTEMPTS,
    private val maxRunToolSteps: Int = SolinConstants.AgentLoop.MAX_RUN_TOOL_STEPS,
    private val maxObservationDecisions: Int = SolinConstants.AgentLoop.MAX_OBSERVATION_DECISIONS,
    private val deviceControlSessionFinisher: () -> Unit = {},
    private val systemPromptBuilder: SystemPromptBuilder? = null,
    private val evidenceBlobStore: EvidenceBlobStore = NoOpEvidenceBlobStore,
    private val sessionPlanStore: SessionPlanStore? = null,
    private val contextCompactor: ContextCompactor = NoOpContextCompactor,
    private val contextAssembler: ContextAssembler? = null,
) {
    private val resolvedContextAssembler: ContextAssembler =
        contextAssembler ?: ContextAssembler(
            systemPromptBuilder = systemPromptBuilder,
            memoryIndex = memoryIndex,
        )
    private val runtimeJob: Job = SupervisorJob()
    private val runtimeScope: CoroutineScope = CoroutineScope(Dispatchers.Default + runtimeJob)
    private val planSubscriptionJob: Job? = sessionPlanStore?.let { store ->
        runtimeScope.launch {
            store.updates
                .onEach { snapshot ->
                    runCatching {
                        eventBus.publish(
                            SolinEvent.Agent.PlanUpdated(
                                runId = snapshot.runId,
                                itemCount = snapshot.items.size,
                                pendingCount = snapshot.pendingCount(),
                                doneCount = snapshot.doneCount(),
                                updatedAtMillis = snapshot.updatedAtMillis,
                            ),
                        )
                    }.onFailure { throwable ->
                        Log.e(TAG, "Plan update subscription failed", throwable)
                    }
                }
                .collect {}
        }
    }
    @Volatile
    private var closed = false

    private val undoStack = java.util.ArrayDeque<UndoEntry>()
    private val skillProgressor = SkillRunProgressor(toolRegistry = toolRegistry)
    private val valueFreeCompletedStepFrontiersByRunId = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private val remoteToolScopesByRunId = java.util.concurrent.ConcurrentHashMap<String, RemoteToolScope>()
    private val remoteExposedToolNamesByRunId = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private val lowRiskDeviceActionConfirmationBypassByRunId = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val installedCapabilityProfilesByRunId = java.util.concurrent.ConcurrentHashMap<String, List<ModelCapabilityProfile>>()
    private val profilesByRunId = java.util.concurrent.ConcurrentHashMap<String, AgentProfile>()
    private val scratchpad = com.bytedance.zgx.solin.memory.PerRunScratchpad()

    private val runBudget = AgentRunBudget(
        maxRunToolSteps = maxRunToolSteps,
        maxObservationDecisions = maxObservationDecisions,
        profilesByRunId = profilesByRunId,
        toolRequestsFor = { runId -> toolRequestsFor(runId) },
        observationDecidedCount = { runId ->
            traceStore.steps(runId).count { step -> step is AgentStep.ObservationDecided }
        },
        sessionPlanStore = sessionPlanStore,
    )
    private val initialToolPlanner = InitialToolPlanner(
        toolRegistry = toolRegistry,
        skillRuntime = skillRuntime,
        safetyPolicy = safetyPolicy,
        actionPlanningRuntime = actionPlanningRuntime,
        traceStore = traceStore,
    )
    private val toolPlanCoordinator = ToolPlanCoordinator(
        toolRegistry = toolRegistry,
        skillRuntime = skillRuntime,
        skillProgressor = skillProgressor,
        observationReplanner = observationReplanner,
        actionPlanningRuntime = actionPlanningRuntime,
        traceStore = traceStore,
        safetyPolicy = safetyPolicy,
        runBudget = runBudget,
        host = object : ToolPlanCoordinator.Host {
            override fun latestSkillPlan(runId: String): SkillPlan? =
                this@AgentLoopRuntime.latestSkillPlan(runId)

            override fun latestModelDrivenAppSearchSkillPlan(runId: String): SkillPlan? =
                this@AgentLoopRuntime.latestModelDrivenAppSearchSkillPlan(runId)

            override fun invalidSkillPlanReason(skillPlan: SkillPlan?): String? =
                initialToolPlanner.invalidSkillPlanReason(skillPlan)

            override fun invalidSkillPlanRejection(request: ToolRequest, skillPlan: SkillPlan?): ToolResult? =
                initialToolPlanner.invalidSkillPlanRejection(request, skillPlan)

            override fun toolRequestsFor(runId: String): List<ToolRequest> =
                this@AgentLoopRuntime.toolRequestsFor(runId)

            override fun toolRequestFor(runId: String, requestId: String): ToolRequest? =
                this@AgentLoopRuntime.toolRequestFor(runId, requestId)

            override fun plannedSequentialSegmentCount(runId: String): Int =
                this@AgentLoopRuntime.plannedSequentialSegmentCount(runId)

            override fun nextSequentialSegmentInput(run: AgentRun, completedSegmentCount: Int): String? =
                initialToolPlanner.nextSequentialSegmentInput(run, completedSegmentCount)

            override fun hasMobileActionPlanningModel(
                installedCapabilityProfiles: List<ModelCapabilityProfile>,
            ): Boolean = initialToolPlanner.hasMobileActionPlanningModel(installedCapabilityProfiles)

            override fun installedCapabilityProfiles(runId: String): List<ModelCapabilityProfile> =
                installedCapabilityProfilesByRunId[runId].orEmpty()

            override fun valueFreeCompletedStepFrontiers(runId: String): Set<String> =
                valueFreeCompletedStepFrontiersByRunId[runId].orEmpty()

            override fun withConfirmationBypass(runId: String, plan: AgentPlan.UseTool): AgentPlan.UseTool =
                plan.withConfirmationBypassForRun(runId)

            override fun withLowRiskAppControlContinuationBypass(
                runId: String,
                plan: AgentPlan.UseTool,
                skillPlan: SkillPlan?,
            ): AgentPlan.UseTool = plan.withLowRiskAppControlContinuationBypassForRun(runId, skillPlan)

            override fun auditRejectedTool(runId: String, result: ToolResult) =
                this@AgentLoopRuntime.auditRejectedTool(runId, result)
        },
    )

    private val toolObservationCoordinator = ToolObservationCoordinator(
        toolRegistry = toolRegistry,
        auditSink = auditSink,
        eventBus = eventBus,
        traceStore = traceStore,
        toolPlanCoordinator = toolPlanCoordinator,
        runBudget = runBudget,
        maxToolRetryAttempts = maxToolRetryAttempts,
        host = object : ToolObservationCoordinator.Host {
            override val terminalRunStates: Set<AgentRunState>
                get() = this@AgentLoopRuntime.terminalRunStates

            override fun toolRequestFor(runId: String, requestId: String): ToolRequest? =
                this@AgentLoopRuntime.toolRequestFor(runId, requestId)

            override fun latestPlanToolBatch(runId: String): AgentObservationDecision.PlanToolBatch? =
                this@AgentLoopRuntime.latestPlanToolBatch(runId)

            override fun latestExecutableRequestId(runId: String): String? =
                this@AgentLoopRuntime.latestExecutableRequestId(runId)

            override fun continuationForToolObservation(
                run: AgentRun,
                request: ToolRequest?,
                result: ToolResult,
            ): ToolObservationContinuation? =
                this@AgentLoopRuntime.continuationForToolObservation(run, request, result)

            override fun redactedForTrace(result: ToolResult, request: ToolRequest?): ToolResult =
                result.redactedForTrace(request)

            override fun failObservationBudget(
                runId: String,
                result: ToolResult,
                assistantMessage: String,
                reason: String,
            ): AgentObservationResult? =
                this@AgentLoopRuntime.failObservationBudget(runId, result, assistantMessage, reason)

            override fun parkForAskUserIfNeeded(runId: String, plan: AgentPlan.UseTool): Boolean =
                this@AgentLoopRuntime.parkForAskUserIfNeeded(runId, plan)

            override fun parkForTakeOver(runId: String, prompt: String?, result: ToolResult) {
                this@AgentLoopRuntime.parkForTakeOver(runId, prompt, result)
            }

            override fun shouldAwaitExternalOutcomeConfirmation(
                runId: String,
                request: ToolRequest,
                result: ToolResult,
            ): Boolean = this@AgentLoopRuntime.shouldAwaitExternalOutcomeConfirmation(runId, request, result)

            override fun skillIdForRequest(runId: String, requestId: String): String? =
                this@AgentLoopRuntime.skillIdForRequest(runId, requestId)

            override fun clearEphemeralRunState(runId: String) {
                this@AgentLoopRuntime.clearEphemeralRunState(runId)
            }

            override fun auditToolRequest(
                runId: String,
                request: ToolRequest,
                eventType: ToolAuditEventType,
                status: ToolStatus?,
                summary: String,
            ) {
                this@AgentLoopRuntime.auditToolRequest(
                    runId = runId,
                    request = request,
                    eventType = eventType,
                    status = status,
                    summary = summary,
                )
            }

            override fun auditRejectedTool(runId: String, result: ToolResult) {
                this@AgentLoopRuntime.auditRejectedTool(runId, result)
            }

            override fun auditToolEvent(
                runId: String,
                plan: AgentPlan.UseTool,
                eventType: ToolAuditEventType,
                status: ToolStatus?,
                summary: String,
            ) {
                this@AgentLoopRuntime.auditToolEvent(runId, plan, eventType, status, summary)
            }

            override fun publishRunEnded(runId: String, finalText: String?) {
                this@AgentLoopRuntime.publishRunEnded(runId, finalText = finalText)
            }

            override fun publishRunFailed(runId: String, code: AgentErrorCode, message: String) {
                this@AgentLoopRuntime.publishRunFailed(runId, code, message)
            }

            override fun markStepStart(stepType: String) {
                this@AgentLoopRuntime.markStepStart(stepType)
            }

            override fun recordVerboseTrace(
                runId: String,
                thinkText: String?,
                actionSummary: String?,
                actionToolName: String?,
                observationSummary: String?,
            ) {
                this@AgentLoopRuntime.recordVerboseTrace(
                    runId = runId,
                    thinkText = thinkText,
                    actionSummary = actionSummary,
                    actionToolName = actionToolName,
                    observationSummary = observationSummary,
                )
            }

            override fun safeRecordTelemetry(sample: MetricSample, label: String) {
                this@AgentLoopRuntime.safeRecordTelemetry(sample, label)
            }

            override fun recordStepLatency(stepType: String, runId: String?) {
                this@AgentLoopRuntime.recordStepLatency(stepType, runId)
            }

            override fun withConfirmationBypass(runId: String, plan: AgentPlan.UseTool): AgentPlan.UseTool =
                plan.withConfirmationBypassForRun(runId)

            override fun toPendingSnapshot(plan: AgentPlan.UseTool, run: AgentRun): PendingToolConfirmationSnapshot =
                pendingConfirmationSupport.toPendingSnapshot(plan, run)

            override fun failInitialPlanBudget(run: AgentRun, request: ToolRequest): AgentLoopResult =
                this@AgentLoopRuntime.failInitialPlanBudget(run, request)

            override fun addScratchpadNote(runId: String, noteContent: String) {
                runCatching { scratchpad.addNote(runId, noteContent) }
                    .onFailure { Log.e(TAG, "Scratchpad note failed", it) }
            }

            override fun applyUndoBookkeeping(
                runId: String,
                request: ToolRequest,
                safeResult: ToolResult,
                observedStatus: ToolStatus,
            ) {
                runCatching {
                    when (observedStatus) {
                        ToolStatus.Succeeded -> {
                            val policy = toolRegistry.undoPolicyFor(request.toolName)
                            val undoPlan = policy?.planUndoAfter(request, safeResult)
                            when (undoPlan) {
                                is UndoPlan.CompensatingTool -> {
                                    synchronized(undoStack) {
                                        undoStack.clear()
                                        val now = System.currentTimeMillis()
                                        undoStack.push(
                                            UndoEntry(
                                                sourceRunId = runId,
                                                sourceRequestId = request.id,
                                                toolName = request.toolName,
                                                plan = undoPlan,
                                                availableUntilMillis = now + undoPlan.ttlMillis,
                                                createdAtMillis = now,
                                            ),
                                        )
                                    }
                                    eventBus.publish(
                                        SolinEvent.Agent.UndoPushed(
                                            runId = runId,
                                            sourceRequestId = request.id,
                                            toolName = request.toolName,
                                            summary = undoPlan.summary,
                                            availableUntilMillis = System.currentTimeMillis() + undoPlan.ttlMillis,
                                        ),
                                    )
                                }
                                is UndoPlan.NotApplicable,
                                is UndoPlan.ExternalHandoff,
                                is UndoPlan.NotUndoable,
                                null -> {
                                    synchronized(undoStack) { undoStack.clear() }
                                }
                            }
                        }
                        ToolStatus.Failed,
                        ToolStatus.Rejected,
                        ToolStatus.Cancelled -> {
                            synchronized(undoStack) { undoStack.clear() }
                        }
                    }
                }.onFailure { throwable ->
                    Log.e(TAG, "Undo bookkeeping failed; clearing stack safely", throwable)
                    runCatching { synchronized(undoStack) { undoStack.clear() } }
                }
            }

            override fun markToolDispatchStarted(requestId: String) {
                toolDispatchStartedAtMillis[requestId] = System.currentTimeMillis()
            }

            override fun consumeToolDispatchLatencyMs(requestId: String): Long {
                val toolStartMs = toolDispatchStartedAtMillis.remove(requestId)
                return toolStartMs?.let { System.currentTimeMillis() - it } ?: 0L
            }

            override fun bumpAndGetTurnIndex(runId: String): Int =
                runTurnIndex.compute(runId) { _, old -> (old ?: 0) + 1 }!!

            override fun setRemoteToolScope(runId: String, scope: RemoteToolScope) {
                remoteToolScopesByRunId[runId] = scope
            }

            override fun toOrchestrationErrorCode(code: ToolErrorCode?): OrchestrationToolErrorCode =
                code.toOrchestrationErrorCode()

            override fun publicEvidenceBatchResultOrFailure(
                result: ToolResult,
                request: ToolRequest,
            ): ToolResult = result.publicEvidenceBatchResultOrFailure(request)

            override fun publicEvidenceBatchAuditSummary(
                successfulPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
            ): String = successfulPairs.publicEvidenceBatchAuditSummary()

            override fun publicEvidenceBatchContinuationPrompt(
                run: AgentRun,
                observedPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
                successfulPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
                gapPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
            ): String? = this@AgentLoopRuntime.publicEvidenceBatchContinuationPrompt(
                run = run,
                observedPairs = observedPairs,
                successfulPairs = successfulPairs,
                gapPairs = gapPairs,
            )
        },
    )

    private val pendingConfirmationSupport = PendingConfirmationSupport(
        toolRegistry = toolRegistry,
        traceStore = traceStore,
        initialToolPlanner = initialToolPlanner,
        valueFreeCompletedStepFrontiersByRunId = valueFreeCompletedStepFrontiersByRunId,
        host = object : PendingConfirmationSupport.Host {
            override fun plannedSequentialSegmentCount(runId: String): Int =
                this@AgentLoopRuntime.plannedSequentialSegmentCount(runId)

            override fun auditRejectedTool(runId: String, result: ToolResult) =
                this@AgentLoopRuntime.auditRejectedTool(runId, result)

            override fun clearEphemeralRunState(runId: String) =
                this@AgentLoopRuntime.clearEphemeralRunState(runId)
        },
    )

    private val modelToolRequestCoordinator = ModelToolRequestCoordinator(
        toolRegistry = toolRegistry,
        skillRuntime = skillRuntime,
        safetyPolicy = safetyPolicy,
        traceStore = traceStore,
        toolObservationCoordinator = toolObservationCoordinator,
        runBudget = runBudget,
        initialToolPlanner = initialToolPlanner,
        host = object : ModelToolRequestCoordinator.Host {
            override fun rejectRemoteToolIfNotExposedInCurrentScope(runId: String, request: ToolRequest): ToolResult? =
                this@AgentLoopRuntime.rejectRemoteToolIfNotExposedInCurrentScope(runId, request)

            override fun toolRequestFor(runId: String, requestId: String): ToolRequest? =
                this@AgentLoopRuntime.toolRequestFor(runId, requestId)

            override fun toolRequestsFor(runId: String): List<ToolRequest> =
                this@AgentLoopRuntime.toolRequestsFor(runId)

            override fun failRunBudget(runId: String, reason: String): AgentModelObservationResult? =
                this@AgentLoopRuntime.failRunBudget(runId, reason)

            override fun withConfirmationBypass(runId: String, plan: AgentPlan.UseTool): AgentPlan.UseTool =
                plan.withConfirmationBypassForRun(runId)

            override fun parkForAskUserIfNeeded(runId: String, plan: AgentPlan.UseTool): Boolean =
                this@AgentLoopRuntime.parkForAskUserIfNeeded(runId, plan)

            override fun toPendingSnapshot(plan: AgentPlan.UseTool, run: AgentRun): PendingToolConfirmationSnapshot =
                pendingConfirmationSupport.toPendingSnapshot(plan, run)

            override fun auditRejectedTool(runId: String, result: ToolResult) =
                this@AgentLoopRuntime.auditRejectedTool(runId, result)

            override fun clearEphemeralRunState(runId: String) =
                this@AgentLoopRuntime.clearEphemeralRunState(runId)

            override fun markStepStart(stepType: String) =
                this@AgentLoopRuntime.markStepStart(stepType)
        },
    )

    // Wave 2 SolinEvent lifecycle bookkeeping. Tracks per-run wall-clock start time and turn index
    // so TurnStarted/TurnEnded/RunEnded events carry monotonic counters without disturbing the
    // existing traceStore contract.
    private val runStartedAtMillis = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val runTurnIndex = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Wave 3 mid-run steering: ChatMessages injected via steerRun()/steer() are queued here and
    // drained (prepended to the model message list) on the next model call.
    //
    // Wave 8 (real steer / high-priority queue): two in-memory Channels replace the prior
    // ConcurrentHashMap-of-CopyOnWriteArrayList pendingSteeringMessages store. steerMessages gets
    // first-class priority at every drain point (drain all steer batches before queued batches) and
    // steer() additionally cooperatively cancels the registered in-flight model-call Job when the
    // run is mid-model-stream so the next turn begins immediately against the new direction.
    // Tool execution is NEVER cancelled by steer (safety-critical device actions must run to
    // completion); steer batches that arrive during ExecutingTool/RetryingTool are held on the
    // channel and injected after the current tool's result is observed, before the next model turn.
    private val steerMessages = Channel<PendingMessageBatch>(capacity = Channel.UNLIMITED)
    private val queuedMessages = Channel<PendingMessageBatch>(capacity = Channel.UNLIMITED)

    // Per-run tracker for the currently in-flight model streaming Job. Registered by the caller
    // (typically SolinViewModel) via [registerModelCallJob] right before it begins collecting
    // streaming tokens from the local/remote model runtime, and cleared on completion via
    // [unregisterModelCallJob] (which is a safe no-op if a different Job is registered, to handle
    // the race-free case where a steer cancelled one Job and a new one has already started).
    //
    // Tool execution jobs are intentionally NOT tracked here: tool calls are not cancellable
    // mid-flight in v1 for safety, so steer() never interrupts a tool Job. The phase check against
    // AgentRunState.GeneratingAnswer (vs ExecutingTool/RetryingTool) is the guard that keeps tool
    // execution safe from steer-driven cancellation.
    private val activeModelCallJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    // Pending ask_user parking state. Maps runId -> (questionId -> originating ToolRequest) so that
    // answerUserQuestion/cancelUserQuestion can correlate a reply back to the originating tool call
    // and synthesize a ToolResult with the correct requestId. Only valid while the run is in
    // AwaitingUserAnswer; cleared on answer/cancel/cancelRun/terminal state.
    private data class PendingUserQuestionState(
        val questionId: String,
        val request: ToolRequest,
    )
    private val pendingUserQuestionsByRunId = java.util.concurrent.ConcurrentHashMap<String, PendingUserQuestionState>()

    // Wave 4 telemetry bookkeeping: wall-clock time a ToolRequested step was appended, keyed by
    // requestId, so ToolCompleted telemetry can record latencyMs. Entries are cleared when the
    // corresponding tool result is observed so the map stays bounded by in-flight tool count.
    private val toolDispatchStartedAtMillis = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Wave 4 telemetry: stepType -> last started-at epoch millis, so StepLatency samples can
    // record deltas when the next step boundary arrives.
    private val stepStartedAtMillis = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun runOnce(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
        actionModelPath: String? = null,
        deviceContext: DeviceContextSnapshot? = null,
        sessionId: String? = null,
        options: AgentRunOptions = AgentRunOptions(),
        installedCapabilityProfiles: List<ModelCapabilityProfile> = emptyList(),
    ): AgentLoopResult {
        val profile = options.profile
        val createdRun = runCatching { traceStore.createRun(input, sessionId) }
            .getOrElse { throwable ->
                // If the trace store's createRun fails (e.g. Room DB error on a fresh
                // test install where tables were cleared), fall back to an ephemeral
                // in-memory run so the route can still proceed. The run won't be
                // persisted to the database, but all in-memory bookkeeping
                // (profilesByRunId, runStartedAtMillis, etc.) still works.
                val now = System.currentTimeMillis()
                AgentRun(
                    id = "ephemeral-${now}-${(0..Int.MAX_VALUE).random()}",
                    input = input,
                    state = AgentRunState.Created,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    sessionId = sessionId,
                ).also { run ->
                    android.util.Log.w(
                        "AgentLoopRuntime",
                        "traceStore.createRun failed, using ephemeral run ${run.id}: ${throwable.message}",
                    )
                }
            }
        val startedAt = System.currentTimeMillis()
        // Only register an explicit (non-default) profile so the constructor's
        // maxRunToolSteps remains the effective cap for callers that don't opt in
        // to per-profile budgets (preserves backward-compat for tests/clients).
        if (profile != AgentProfile.DEFAULT) {
            profilesByRunId[createdRun.id] = profile
        }
        runStartedAtMillis[createdRun.id] = startedAt
        runTurnIndex[createdRun.id] = 0
        remoteToolScopesByRunId[createdRun.id] = options.remoteToolScope
        lowRiskDeviceActionConfirmationBypassByRunId[createdRun.id] = options.reduceDeviceActionConfirmations
        installedCapabilityProfilesByRunId[createdRun.id] = installedCapabilityProfiles.toList()
        traceStore.updateState(createdRun.id, AgentRunState.LoadingContext)
        // Wave 2 lifecycle: dual-write RunStarted + initial TurnStarted alongside trace steps.
        eventBus.publish(
            SolinEvent.Agent.RunStarted(
                runId = createdRun.id,
                modelLabel = actionModelPath ?: "local",
                inputText = input,
                profileId = profile.profileId,
                parentRunId = (profile as? AgentProfile.Subagent)?.parentRunId,
                depth = (profile as? AgentProfile.Subagent)?.depth ?: 0,
            ),
        )
        eventBus.publish(
            SolinEvent.Agent.TurnStarted(
                runId = createdRun.id,
                turnIndex = 0,
                occurredAtMillis = startedAt,
            ),
        )
        // Subagent depth validation: reject nested runs exceeding the hard cap immediately
        // after lifecycle bookkeeping so subscribers see a paired RunStarted/RunFailed.
        if (profile is AgentProfile.Subagent && profile.depth > AgentProfile.MAX_SUBAGENT_DEPTH) {
            val message = "Subagent depth ${profile.depth} exceeds maximum ${AgentProfile.MAX_SUBAGENT_DEPTH}."
            val syntheticRequest = ToolRequest(
                id = "subagent-depth-guard",
                toolName = "subagent",
                reason = "subagent depth validation",
            )
            val rejectedResult = syntheticRequest.rejected(message)
            val rejectedPlan = AgentPlan.RejectedTool(rejectedResult)
            traceStore.appendStep(createdRun.id, AgentStep.ModelPlanned(rejectedPlan))
            traceStore.appendStep(createdRun.id, AgentStep.ToolRejected(rejectedResult))
            auditRejectedTool(createdRun.id, rejectedResult)
            traceStore.appendStep(createdRun.id, AgentStep.Failed(message))
            val failedRun = traceStore.updateState(createdRun.id, AgentRunState.Failed)
            eventBus.publish(
                SolinEvent.Agent.RunFailed(
                    runId = createdRun.id,
                    code = AgentErrorCode.Validation,
                    message = message,
                ),
            )
            clearEphemeralRunState(createdRun.id)
            return AgentLoopResult(
                run = failedRun,
                plan = rejectedPlan,
                steps = traceStore.steps(createdRun.id),
            )
        }

        val memoryHits = if (memoryEnabled) {
            runCatching { memoryIndex.search(input, topK = 3) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        traceStore.appendStep(createdRun.id, AgentStep.ContextLoaded(memoryHits, deviceContext))
        traceStore.updateState(createdRun.id, AgentRunState.Planning)

        val initialToolPlan = when (options.initialPlanningMode) {
            InitialPlanningMode.RuleFirst -> initialToolPlanner.planToolIfSupported(
                input = input,
                installedCapabilityProfiles = installedCapabilityProfiles,
                actionModelPath = actionModelPath,
            )
            InitialPlanningMode.ModelFirstRemoteTools -> initialToolPlanner.planLocalOnlySkillBeforeRemote(input)
        }
        when (initialToolPlan) {
            is AgentPlan.UseTool -> {
                return toolObservationCoordinator.requestToolConfirmation(createdRun, initialToolPlan)
            }

            is AgentPlan.RejectedTool -> {
                traceStore.appendStep(createdRun.id, AgentStep.ModelPlanned(initialToolPlan))
                traceStore.appendStep(createdRun.id, AgentStep.ToolRejected(initialToolPlan.result))
                auditRejectedTool(createdRun.id, initialToolPlan.result)
                val failedRun = traceStore.updateState(createdRun.id, AgentRunState.Failed)
                eventBus.publish(
                    SolinEvent.Agent.RunFailed(
                        runId = createdRun.id,
                        code = AgentErrorCode.Validation,
                        message = initialToolPlan.result.summary,
                    ),
                )
                clearEphemeralRunState(createdRun.id)
                return AgentLoopResult(
                    run = failedRun,
                    plan = initialToolPlan,
                    steps = traceStore.steps(createdRun.id),
                )
            }

            is AgentPlan.MissingModel -> {
                return toolObservationCoordinator.failMissingModelPlan(createdRun, initialToolPlan)
            }

            null -> Unit
            else -> Unit
        }

        traceStore.appendStep(
            createdRun.id,
            AgentStep.IntentRouted(
                IntentRoutingDecision(
                    input = input,
                    selectedPath = IntentRoutingPath.NoAction,
                    selectedToolName = null,
                    selectedSkillId = null,
                    priority = 0,
                    accepted = false,
                    confidence = ActionIntentConfidence.None,
                    rejectionReasons = listOf("no_action_intent_detected"),
                    requiresConfirmation = null,
                ),
            ),
        )
        val answerPlan = AgentPlan.Answer(
            promptForModel = resolvedContextAssembler.assembleAnswerPrompt(input, memoryHits, deviceContext, createdRun),
            memoryHits = memoryHits,
            deviceContext = deviceContext,
        )
        traceStore.appendStep(createdRun.id, AgentStep.ModelPlanned(answerPlan))
        val generatingRun = traceStore.updateState(createdRun.id, AgentRunState.GeneratingAnswer)
        markStepStart("model_generation")
        return AgentLoopResult(
            run = generatingRun,
            plan = answerPlan,
            steps = traceStore.steps(createdRun.id),
        )
    }

    fun requestRecoveryAction(action: AgentRecoveryAction, sessionId: String? = null): AgentLoopResult? {
        val recovery = action.normalizedReminderRecovery() ?: return null
        val createdRun = traceStore.createRun("撤销提醒任务：${recovery.taskId}", sessionId)
        traceStore.updateState(createdRun.id, AgentRunState.LoadingContext)
        traceStore.appendStep(createdRun.id, AgentStep.ContextLoaded(emptyList()))
        traceStore.updateState(createdRun.id, AgentRunState.Planning)
        return when (
            val plan = initialToolPlanner.buildInitialToolPlan(
                request = recovery.request,
                draft = recovery.draft,
                plannedByModel = false,
                fallbackReason = "typed recovery action",
                skillPlan = null,
            )
        ) {
            is AgentPlan.UseTool -> toolObservationCoordinator.requestToolConfirmation(createdRun, plan)
            is AgentPlan.RejectedTool -> toolObservationCoordinator.rejectToolPlan(createdRun, plan)
            else -> null
        }
    }

    fun failStaleInFlightRuns(reason: String): Int =
        traceStore.failStaleInFlightRuns(reason)

    fun runEvents(runId: String): List<AgentRunEvent> {
        val run = traceStore.run(runId) ?: return emptyList()
        return AgentStepRunEventAdapter.adapt(run, traceStore.steps(runId))
    }

    fun publicWebEvidence(runId: String): List<PublicWebEvidencePack> =
        publicWebEvidencePacksFromToolResults(
            traceStore.steps(runId)
                .mapNotNull { step -> (step as? AgentStep.ToolObserved)?.result },
        )

    fun failModelGeneration(runId: String, reason: String): AgentModelObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.GeneratingAnswer) return null
        val failureReason = reason.ifBlank { "Model generation failed." }
        val decision = AgentObservationDecision.Fail(failureReason)
        val updatedRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = AgentRunState.GeneratingAnswer,
            state = AgentRunState.Failed,
        ) ?: return null
        traceStore.appendStep(runId, AgentStep.Failed(failureReason))
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        publishRunFailed(runId, AgentErrorCode.ModelTimeout, failureReason)
        clearEphemeralRunState(runId)
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    fun cancelRun(runId: String, reason: String = RUN_CANCELLED_REASON): AgentModelObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state in terminalRunStates) return null
        // Drop any queued steer/queued messages for a run that is being torn down. We cannot
        // selectively purge from an UNLIMITED Channel, so drain into a discard sink by runId.
        // Residual batches will naturally be ignored by any later drain call because the run is
        // terminal (drain methods check state before using messages), but clearing the tracker map
        // below ensures no model-call-Job remains cancellable after cancellation.
        cancelAndClearModelCallJob(runId)
        if (run.state == AgentRunState.AwaitingUserConfirmation) {
            latestPendingToolRequest(runId)?.let { request ->
                val cancelled = cancelToolRequest(runId, request.id) ?: return null
                return AgentModelObservationResult(
                    run = cancelled.run,
                    decision = cancelled.decision,
                    steps = cancelled.steps,
                )
            }
            traceStore.clearPendingConfirmationsForRun(runId)
        } else if (run.state == AgentRunState.AwaitingUserAnswer) {
            val pending = pendingUserQuestionsByRunId.remove(runId)
            if (pending != null) {
                val cancelled = cancelUserQuestion(runId, pending.questionId) ?: return null
                return AgentModelObservationResult(
                    run = cancelled.run,
                    decision = cancelled.decision,
                    steps = cancelled.steps,
                )
            }
            traceStore.clearPendingConfirmationsForRun(runId)
        }
        val decision = AgentObservationDecision.Cancel
        val updatedRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = run.state,
            state = AgentRunState.Cancelled,
        ) ?: return null
        traceStore.clearPendingConfirmationsForRun(runId)
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        eventBus.publish(
            SolinEvent.Agent.RunCancelled(
                runId = runId,
                reason = reason,
                byUser = true,
            ),
        )
        clearEphemeralRunState(runId)
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    fun terminateRun(runId: String, reason: String = RUN_CANCELLED_REASON): AgentModelObservationResult? {
        cancelAndClearModelCallJob(runId)
        traceStore.clearPendingConfirmationsForRun(runId)
        pendingUserQuestionsByRunId.remove(runId)
        val decision = AgentObservationDecision.Cancel
        var updatedRun: AgentRun? = null
        while (updatedRun == null) {
            val run = traceStore.run(runId) ?: return null
            if (run.state in terminalRunStates) return null
            updatedRun = traceStore.compareAndSetState(
                runId = runId,
                expectedState = run.state,
                state = AgentRunState.Cancelled,
            )
        }
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        eventBus.publish(
            SolinEvent.Agent.RunCancelled(
                runId = runId,
                reason = reason,
                byUser = true,
            ),
        )
        clearEphemeralRunState(runId)
        return AgentModelObservationResult(
            run = requireNotNull(updatedRun),
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    fun deleteRunsForSession(sessionId: String): Int {
        val runIds = traceStore.recentRunSummaries(limit = Int.MAX_VALUE, stepLimit = 0)
            .asSequence()
            .map { summary -> summary.run }
            .filter { run -> run.sessionId == sessionId }
            .map { run -> run.id }
            .toList()
        runIds.forEach { runId -> terminateRun(runId, RUN_DELETED_SESSION_REASON) }
        val deletedCount = traceStore.deleteRunsForSession(sessionId)
        runIds.forEach(::clearDeletedRunState)
        return deletedCount
    }

    /**
     * High-priority steer: enqueue [messages] to be injected at the next drain point (top of the
     * next model-call iteration) and, when the run is currently mid-model-stream, cooperatively
     * cancel the in-flight model call Job so the streaming loop aborts promptly and the next turn
     * starts against the new direction. Returns true if the run is active (so messages will be
     * delivered); false if the run is unknown or terminal (messages are dropped).
     *
     * Safety: when the run is currently executing/retries a tool (ExecutingTool / RetryingTool),
     * the in-flight tool Job is NOT cancelled — safety-critical device actions must run to
     * completion. The steer batch is still enqueued and will be injected after the tool's result
     * is observed, before the next model turn.
     *
     * Thread-safe: may be invoked from any thread (typically the UI main thread). Cancellation is
     * wrapped in runCatching so a misbehaving Job that throws on cancel does not crash the caller.
     */
    fun steer(messages: List<ChatMessage>, runId: String? = activeRunIdForSteer()): Boolean {
        if (messages.isEmpty()) return false
        val id = runId ?: return false
        val run = traceStore.run(id) ?: return false
        if (run.state in terminalRunStates) return false
        val enqueued = steerMessages.trySend(PendingMessageBatch(id, messages)).isSuccess
        if (!enqueued) return false
        // Only cancel a model-streaming job; never interrupt tool execution. The state check is
        // racy (a tool could start between this line and .cancel()) so additionally rely on the
        // register/unregister pairing in the model-stream call sites: tool-execution Jobs are
        // never registered with this runtime so cancel() below cannot reach them.
        if (run.state == AgentRunState.GeneratingAnswer) {
            runCatching {
                activeModelCallJobs[id]?.cancel(
                    kotlinx.coroutines.CancellationException(
                        "run $id steered by user; aborting in-flight model generation",
                    ),
                )
            }
        }
        return true
    }

    /**
     * Normal-priority queue: enqueue [messages] to be picked up at the next drain point AFTER all
     * steer batches have been consumed. Never cancels anything. Returns true on successful
     * enqueue to an active run; false otherwise.
     */
    fun queue(messages: List<ChatMessage>, runId: String? = activeRunIdForSteer()): Boolean {
        if (messages.isEmpty()) return false
        val id = runId ?: return false
        val run = traceStore.run(id) ?: return false
        if (run.state in terminalRunStates) return false
        return queuedMessages.trySend(PendingMessageBatch(id, messages)).isSuccess
    }

    /**
     * Register a [Job] representing the current in-flight model streaming call for [runId].
     * [steer] will [Job.cancel] this Job (with a CancellationException) when a high-priority
     * steer arrives while the run is in [AgentRunState.GeneratingAnswer]. Callers MUST pair this
     * with [unregisterModelCallJob] in a `try/finally` around the streaming collect to avoid
     * leaking a stale reference.
     *
     * Tool-execution Jobs must NOT be registered (tool execution is non-cancellable in v1 for
     * safety). Only model streaming Jobs (the `.collect { chunk -> ... }` over the local LiteRt
     * or remote chat runtime Flow) should be registered.
     */
    fun registerModelCallJob(runId: String, job: Job) {
        activeModelCallJobs[runId] = job
    }

    /**
     * Remove the registered model-call Job for [runId]. If [expected] is non-null, only remove if
     * the registered Job is the same instance (defends against the race where a steer cancelled
     * the old Job and a new one has already been registered).
     */
    fun unregisterModelCallJob(runId: String, expected: Job? = null) {
        if (expected == null) {
            activeModelCallJobs.remove(runId)
            return
        }
        activeModelCallJobs.remove(runId, expected)
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        activeModelCallJobs.keys.toList().forEach(::cancelAndClearModelCallJob)
        planSubscriptionJob?.cancel()
        steerMessages.close()
        queuedMessages.close()
        runtimeJob.cancel()
        valueFreeCompletedStepFrontiersByRunId.clear()
        remoteToolScopesByRunId.clear()
        remoteExposedToolNamesByRunId.clear()
        lowRiskDeviceActionConfirmationBypassByRunId.clear()
        installedCapabilityProfilesByRunId.clear()
        profilesByRunId.clear()
        pendingUserQuestionsByRunId.clear()
        toolDispatchStartedAtMillis.clear()
        stepStartedAtMillis.clear()
        runStartedAtMillis.keys.toList().forEach { runId ->
            runCatching { scratchpad.clear(runId) }
        }
        runStartedAtMillis.clear()
        runTurnIndex.clear()
        synchronized(undoStack) {
            undoStack.clear()
        }
    }

    private fun cancelAndClearModelCallJob(runId: String) {
        val job = activeModelCallJobs.remove(runId) ?: return
        runCatching {
            job.cancel(
                kotlinx.coroutines.CancellationException(
                    "run $runId cancelled; aborting in-flight model generation",
                ),
            )
        }
    }

    /**
     * Heuristic for the runId-less overloads of [steer] / [queue]: return the single currently
     * in-flight (non-terminal) run id, or null if there are zero or more than one (in which case
     * the caller must pass an explicit runId). This keeps steer()/queue() usable from simple UI
     * paths that track a single active run without introducing ambiguity in subagent fan-out.
     */
    private fun activeRunIdForSteer(): String? {
        var found: String? = null
        for (runId in runStartedAtMillis.keys) {
            val run = traceStore.run(runId) ?: continue
            if (run.state in terminalRunStates) continue
            if (found != null) return null // >1 active; ambiguous
            found = runId
        }
        return found
    }

    /**
     * Wave 8: drain point for the steer/queued channels for [runId]. Drains ALL pending steer
     * batches first (high priority), then ALL pending normal-queued batches. The returned lists
     * are ordered by arrival (FIFO) within each priority class. Hook-supplied steering messages
     * ([AgentHooks.getSteeringMessages]) are NOT appended here because this method is non-suspend;
     * prefer [drainPendingMessagesSuspend] when a coroutine scope is available so hook messages
     * flow through the same prepend path.
     *
     * Publishes [SolinEvent.Agent.RunSteered] once when the combined injected count is non-empty.
     *
     * Callers (typically the ViewModel immediately before building the ChatMessage list for the
     * model) should concatenate `steer + queued` messages and APPEND them to the outgoing history
     * as fresh user messages — the design specifies they are added to history so the model sees
     * them as the most recent user turns, rather than being prepended before existing history.
     */
    fun drainPendingMessages(runId: String): PendingMessagesDrain {
        val steer = mutableListOf<ChatMessage>()
        val queued = mutableListOf<ChatMessage>()
        // Two-phase pump for each channel: pull everything available off the channel with
        // tryReceive, keep batches addressed to [runId], and put off-target batches back onto
        // the same channel (UNLIMITED channels never reject trySend so this is safe). Off-target
        // re-enqueue may reorder relative to concurrent producers, but steer/queue delivery is
        // already best-effort-FIFO at this layer (the UI layer orders by user action time).
        pumpChannel(runId = runId, channel = steerMessages, priority = Priority.Steer, steerAccum = steer, queuedAccum = queued)
        pumpChannel(runId = runId, channel = queuedMessages, priority = Priority.Queue, steerAccum = steer, queuedAccum = queued)
        val totalInjected = steer.size + queued.size
        if (totalInjected > 0) {
            eventBus.publish(
                SolinEvent.Agent.RunSteered(
                    runId = runId,
                    injectedMessageCount = totalInjected,
                    reason = if (steer.isNotEmpty()) "steer" else "queue",
                ),
            )
        }
        return PendingMessagesDrain(steer = steer.toList(), queued = queued.toList())
    }

    private enum class Priority { Steer, Queue }

    /**
     * Drain all currently-available batches from [channel]: those matching [runId] are appended to
     * [steerAccum] or [queuedAccum] per [priority], and off-target batches are immediately
     * re-enqueued to the same channel so other runs' pending messages survive.
     */
    private fun pumpChannel(
        runId: String,
        channel: Channel<PendingMessageBatch>,
        priority: Priority,
        steerAccum: MutableList<ChatMessage>,
        queuedAccum: MutableList<ChatMessage>,
    ) {
        // We bound the pump defensively: in the worst case a concurrent producer could keep
        // trySend-ing while we loop, but that would require another thread to call steer/queue
        // repeatedly which cannot happen faster than we drain (tryReceive is lock-free and
        // O(1)). The cap guards against any pathological bug from causing an infinite loop here.
        var pumped = 0
        val maxPump = 1024
        val requeue = mutableListOf<PendingMessageBatch>()
        while (pumped < maxPump) {
            val result = channel.tryReceive()
            val batch = result.getOrNull() ?: break
            pumped++
            if (batch.runId == runId) {
                when (priority) {
                    Priority.Steer -> steerAccum.addAll(batch.messages)
                    Priority.Queue -> queuedAccum.addAll(batch.messages)
                }
            } else {
                requeue.add(batch)
            }
        }
        for (b in requeue) {
            channel.trySend(b)
        }
    }

    /**
     * Suspend-aware drain: behaves like [drainPendingMessages] but also appends any hook-supplied
     * messages from [AgentHooks.getSteeringMessages] (treated as steer-priority). Upper layers
     * that hold a coroutine scope (e.g. ViewModel) should prefer this overload so hook-injected
     * messages flow through the same injection path.
     */
    suspend fun drainPendingMessagesSuspend(runId: String): PendingMessagesDrain {
        val base = drainPendingMessages(runId)
        val hookMessages = safeHookCall(
            label = "getSteeringMessages",
            default = emptyList<ChatMessage>(),
        ) { hooks.getSteeringMessages() }
        return if (hookMessages.isEmpty()) {
            base
        } else {
            base.copy(steer = base.steer + hookMessages)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Back-compat shims: preserve Wave 3 steerRun / drainSteeringMessages / drainSteeringMessagesSuspend
    // API surface so existing callers (ViewModel, tests) keep compiling without modification.
    // steerRun is the high-priority entry (equivalent to steer()); drainSteeringMessages returns
    // the concatenated steer+queued lists (matching the prior "all pending steering" contract).
    // -------------------------------------------------------------------------------------------

    /**
     * Wave 3 mid-run steering (back-compat): enqueue [messages] at high priority. Now a thin
     * wrapper over [steer] which cooperatively cancels the in-flight model call when the run is
     * mid-model-stream. Returns true if the run is active; false otherwise.
     */
    fun steerRun(runId: String, messages: List<ChatMessage>): Boolean {
        if (messages.isEmpty()) return false
        val run = traceStore.run(runId) ?: return false
        if (run.state in terminalRunStates) return false
        return steer(messages = messages, runId = runId)
    }

    /**
     * Back-compat non-suspend drain: returns ALL pending (steer + queued + prior-API steer)
     * messages for [runId] in arrival order. Hook-supplied messages are NOT included (same as
     * prior behavior — hooks are suspend). Replaces the old pendingSteeringMessages map drain.
     */
    fun drainSteeringMessages(runId: String): List<ChatMessage> {
        val drain = drainPendingMessages(runId)
        return drain.steer + drain.queued
    }

    /**
     * Wave 3 / Wave 4 back-compat: returns steer+queued+hook messages combined. Hook messages
     * are appended after the high-priority steer batches (consistent with how the suspend
     * overload of the Wave 3 drain behaved).
     */
    suspend fun drainSteeringMessagesSuspend(runId: String): List<ChatMessage> {
        val drain = drainPendingMessagesSuspend(runId)
        return drain.steer + drain.queued
    }

    private fun failRunBudget(
        runId: String,
        reason: String,
    ): AgentModelObservationResult? {
        val updatedRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = AgentRunState.GeneratingAnswer,
            state = AgentRunState.Failed,
        ) ?: return null
        traceStore.clearPendingConfirmationsForRun(runId)
        val augmentedReason = augmentReasonWithStepBudgetHint(runId, reason)
        traceStore.appendStep(runId, AgentStep.Failed(augmentedReason))
        val decision = AgentObservationDecision.Fail(augmentedReason)
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        publishRunFailed(runId, AgentErrorCode.Unknown("budget_exceeded"), augmentedReason)
        clearEphemeralRunState(runId)
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    private fun failObservationBudget(
        runId: String,
        result: ToolResult,
        assistantMessage: String,
        reason: String,
    ): AgentObservationResult? {
        val updatedRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = AgentRunState.Observing,
            state = AgentRunState.Failed,
        ) ?: return null
        traceStore.clearPendingConfirmationsForRun(runId)
        traceStore.appendStep(runId, AgentStep.Failed(reason))
        val decision = AgentObservationDecision.Fail(reason)
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        publishRunFailed(runId, AgentErrorCode.Unknown("budget_exceeded"), reason)
        clearEphemeralRunState(runId)
        return AgentObservationResult(
            run = updatedRun,
            result = result,
            assistantMessage = assistantMessage,
            decision = decision,
            recoveryAction = null,
            continuationPromptForModel = null,
            continuationRequiresLocalModel = false,
            retryRequest = null,
            retryAttempt = 0,
            steps = traceStore.steps(runId),
        )
    }

    private fun failInitialPlanBudget(
        run: AgentRun,
        request: ToolRequest,
    ): AgentLoopResult {
        val hintReason = augmentReasonWithStepBudgetHint(run.id, TOOL_STEP_BUDGET_EXCEEDED_REASON)
        val rejectedPlan = AgentPlan.RejectedTool(request.rejected(hintReason))
        traceStore.appendStep(run.id, AgentStep.ModelPlanned(rejectedPlan))
        traceStore.appendStep(run.id, AgentStep.ToolRejected(rejectedPlan.result))
        auditRejectedTool(run.id, rejectedPlan.result)
        traceStore.appendStep(run.id, AgentStep.Failed(rejectedPlan.result.summary))
        val failedRun = traceStore.updateState(run.id, AgentRunState.Failed)
        clearEphemeralRunState(run.id)
        return AgentLoopResult(
            run = failedRun,
            plan = rejectedPlan,
            steps = traceStore.steps(run.id),
        )
    }

    private fun effectiveMaxToolSteps(runId: String): Int = runBudget.effectiveMaxToolSteps(runId)
    private fun toolStepBudgetExceeded(runId: String): Boolean = runBudget.toolStepBudgetExceeded(runId)
    private fun observationDecisionBudgetExceeded(runId: String): Boolean =
        runBudget.observationDecisionBudgetExceeded(runId)
    private fun augmentReasonWithStepBudgetHint(runId: String, reason: String): String =
        runBudget.augmentReasonWithStepBudgetHint(runId, reason)

    fun confirmToolRequest(runId: String, requestId: String): AgentRun? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingUserConfirmation) return run
        val request = pendingToolRequest(runId, requestId)
            ?: return traceStore.run(runId) ?: run
        toolRegistry.validate(request)?.let { rejection ->
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRejectedTool(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return traceStore.compareAndSetState(
                runId = runId,
                expectedState = AgentRunState.AwaitingUserConfirmation,
                state = AgentRunState.Failed,
            )
                .also { clearEphemeralRunState(runId) }
        }
        val spec = toolRegistry.specFor(request.toolName)
        if (spec == null) {
            val rejection = request.rejected("Unknown tool: ${request.toolName}")
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRejectedTool(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return traceStore.compareAndSetState(
                runId = runId,
                expectedState = AgentRunState.AwaitingUserConfirmation,
                state = AgentRunState.Failed,
            )
                .also { clearEphemeralRunState(runId) }
        }
        val safetyDecision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = true))
        if (safetyDecision.outcome == SafetyOutcome.Reject) {
            val rejection = request.rejected(safetyDecision.reason)
            traceStore.appendStep(runId, AgentStep.SafetyChecked(safetyDecision))
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRejectedTool(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return traceStore.compareAndSetState(
                runId = runId,
                expectedState = AgentRunState.AwaitingUserConfirmation,
                state = AgentRunState.Failed,
            )
                .also { clearEphemeralRunState(runId) }
        }
        traceStore.appendStep(runId, AgentStep.SafetyChecked(safetyDecision))
        traceStore.appendStep(runId, AgentStep.UserConfirmed(requestId))
        eventBus.publish(
            SolinEvent.Agent.UserConfirmed(
                runId = runId,
                requestId = requestId,
                toolCallId = request.id,
                actionLabel = request.toolName,
            ),
        )
        auditToolRequest(
            runId = runId,
            request = request,
            eventType = ToolAuditEventType.UserConfirmed,
            status = null,
            summary = safetyDecision.reason,
        )
        val executingRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = AgentRunState.AwaitingUserConfirmation,
            state = AgentRunState.ExecutingTool,
        ) ?: return null
        markStepStart("tool_execution")
        traceStore.clearPendingConfirmation(runId, requestId)
        return executingRun
    }

    fun cancelToolRequest(runId: String, requestId: String): AgentObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingUserConfirmation) return null
        val request = pendingToolRequest(runId, requestId)
            ?: return null
        traceStore.appendStep(runId, AgentStep.UserRejected(requestId))
        eventBus.publish(
            SolinEvent.Agent.UserRejected(
                runId = runId,
                requestId = requestId,
                toolCallId = request.id,
                actionLabel = request.toolName,
            ),
        )
        auditToolRequest(
            runId = runId,
            request = request,
            eventType = ToolAuditEventType.UserCancelled,
            status = ToolStatus.Cancelled,
            summary = "User cancelled tool request before execution.",
        )
        val observed = toolObservationCoordinator.observeToolResultInternal(
            runId = runId,
            result = request.cancelled("用户取消了工具请求"),
            allowedStates = setOf(AgentRunState.AwaitingUserConfirmation),
        )
        traceStore.clearPendingConfirmation(runId, requestId)
        return observed
    }

    fun failPendingToolRequest(
        runId: String,
        requestId: String,
        result: ToolResult,
    ): AgentObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingUserConfirmation) return null
        val request = pendingToolRequest(runId, requestId)
            ?: return null
        if (result.requestId != request.id) return null

        traceStore.appendStep(runId, AgentStep.UserConfirmed(requestId))
        auditToolRequest(
            runId = runId,
            request = request,
            eventType = ToolAuditEventType.UserConfirmed,
            status = null,
            summary = "User confirmed tool request, but execution was blocked before start.",
        )
        val observed = toolObservationCoordinator.observeToolResultInternal(
            runId = runId,
            result = result,
            allowedStates = setOf(AgentRunState.AwaitingUserConfirmation),
        )
        traceStore.clearPendingConfirmation(runId, requestId)
        return observed
    }

    /**
     * Resume an `ask_user` parking point by supplying the user's [answer]. Mirrors
     * [confirmToolRequest] in shape but, like [cancelToolRequest], produces a synthetic
     * [ToolResult] and feeds it through [observeToolResultInternal] so the agent replans with
     * the answer in context.
     *
     * Returns `null` if the run is not in [AgentRunState.AwaitingUserAnswer] or [questionId]
     * does not match the currently pending question.
     */
    fun answerUserQuestion(
        runId: String,
        questionId: String,
        answer: String,
    ): AgentObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingUserAnswer) return null
        val pending = pendingUserQuestionsByRunId[runId] ?: return null
        if (pending.questionId != questionId) return null
        val trimmedAnswer = answer.trim()
        if (trimmedAnswer.isEmpty()) return null
        traceStore.appendStep(
            runId,
            AgentStep.UserQuestionAnswered(
                questionId = questionId,
                answer = trimmedAnswer,
            ),
        )
        eventBus.publish(
            SolinEvent.Agent.UserQuestionAnswered(
                runId = runId,
                questionId = questionId,
                answer = trimmedAnswer,
            ),
        )
        val syntheticResult = pending.request.succeeded(
            summary = "用户已回答澄清问题",
            data = mapOf(
                "toolName" to MobileActionFunctions.ASK_USER,
                "questionId" to questionId,
                "answer" to trimmedAnswer,
                // ask_user answers are user-typed, potentially sensitive, and must stay on device.
                // Mark as LocalOnly so policyContinuationAfterToolObservation routes the
                // continuation through the local-model-only path (matching read_clipboard /
                // query_contacts / other LocalEvidence tools).
                "privacy" to MessagePrivacy.LocalOnly.name,
                "requiresLocalModel" to "true",
            ),
        )
        val observed = toolObservationCoordinator.observeToolResultInternal(
            runId = runId,
            result = syntheticResult,
            allowedStates = setOf(AgentRunState.AwaitingUserAnswer),
        )
        pendingUserQuestionsByRunId.remove(runId)
        return observed
    }

    /**
     * Cancel an outstanding `ask_user` parking point (e.g. user dismissed the prompt). Mirrors
     * [cancelToolRequest]: synthesizes a Cancelled [ToolResult] so the agent sees an explicit
     * cancellation and can replan (typically by falling back to a direct answer or terminating).
     */
    fun cancelUserQuestion(
        runId: String,
        questionId: String,
    ): AgentObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingUserAnswer) return null
        val pending = pendingUserQuestionsByRunId[runId] ?: return null
        if (pending.questionId != questionId) return null
        auditToolRequest(
            runId = runId,
            request = pending.request,
            eventType = ToolAuditEventType.UserCancelled,
            status = ToolStatus.Cancelled,
            summary = "User cancelled clarifying question before answering.",
        )
        val syntheticResult = pending.request.cancelled(
            summary = "用户取消了澄清问题",
            data = mapOf(
                "toolName" to MobileActionFunctions.ASK_USER,
                "questionId" to questionId,
            ),
        )
        val observed = toolObservationCoordinator.observeToolResultInternal(
            runId = runId,
            result = syntheticResult,
            allowedStates = setOf(AgentRunState.AwaitingUserAnswer),
        )
        pendingUserQuestionsByRunId.remove(runId)
        return observed
    }

    fun observeToolResult(runId: String, result: ToolResult): AgentObservationResult? =
        toolObservationCoordinator.observeToolResultInternal(
            runId = runId,
            result = result,
            allowedStates = setOf(AgentRunState.ExecutingTool, AgentRunState.RetryingTool),
        )

    fun observeToolResults(runId: String, results: List<ToolResult>): AgentObservationResult? =
        toolObservationCoordinator.observeToolResults(runId, results)

    fun recordExternalOutcome(
        runId: String,
        requestId: String,
        outcome: AgentExternalOutcome,
    ): AgentExternalOutcomeResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingExternalOutcome) return null
        val request = toolRequestFor(runId, requestId) ?: return null
        if (latestExternalOutcomeConfirmation(runId, requestId) != null) return null
        val priorResult = latestUnverifiedExternalResult(runId, requestId) ?: return null
        val summary = "$EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX：${outcome.userFacingLabel}"
        val result = priorResult.copy(
            summary = summary,
            data = priorResult.data + mapOf(
                "toolName" to request.toolName,
                "completionVerified" to outcome.completionVerified.toString(),
                "externalOutcome" to outcome.metadataValue,
                "externalOutcomeSource" to "UserConfirmed",
            ),
        )
        val safeResult = toolRegistry.validateResult(request, result) ?: result
        if (safeResult.status != ToolStatus.Succeeded) return null
        val traceResult = safeResult.redactedForTrace(request)
        traceStore.appendStep(runId, AgentStep.ExternalOutcomeConfirmed(requestId, outcome, traceResult))
        auditToolRequest(
            runId = runId,
            request = request,
            eventType = ToolAuditEventType.ExternalOutcomeConfirmed,
            status = traceResult.status,
            summary = traceResult.summary,
        )
        val nextToolPlan = if (traceResult.isUserConfirmedCompletedExternalOutcome()) {
            toolPlanCoordinator.planNextToolAfterObservation(run, request, traceResult)
        } else {
            NextObservationPlan.None
        }
        val decision = when (nextToolPlan) {
            NextObservationPlan.None -> AgentObservationDecision.Complete
            is NextObservationPlan.Planned -> AgentObservationDecision.PlanNextTool(
                plan = nextToolPlan.plan,
                reason = traceResult.summary,
            )

            is NextObservationPlan.Rejected -> AgentObservationDecision.Fail(nextToolPlan.reason)
        }
        val finalState = when (decision) {
            AgentObservationDecision.Complete -> AgentRunState.Completed
            is AgentObservationDecision.PlanNextTool -> decision.plan.nextExecutionState()
            is AgentObservationDecision.PlanToolBatch -> AgentRunState.ExecutingTool
            is AgentObservationDecision.Fail -> AgentRunState.Failed
            AgentObservationDecision.Cancel -> AgentRunState.Cancelled
            is AgentObservationDecision.ContinueWithModel -> AgentRunState.GeneratingAnswer
            is AgentObservationDecision.RetryTool -> AgentRunState.RetryingTool
        }
        val updatedRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = AgentRunState.AwaitingExternalOutcome,
            state = finalState,
        ) ?: return null
        if (decision is AgentObservationDecision.PlanNextTool) {
            toolObservationCoordinator.appendToolPlanSteps(
                runId = runId,
                plan = decision.plan,
            )
        }
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        if (finalState in terminalRunStates) {
            clearEphemeralRunState(runId)
        }
        if (decision is AgentObservationDecision.PlanNextTool && decision.plan.requiresUserConfirmation()) {
            traceStore.savePendingConfirmation(pendingConfirmationSupport.toPendingSnapshot(decision.plan, updatedRun))
        }
        return AgentExternalOutcomeResult(
            run = updatedRun,
            result = traceResult,
            assistantMessage = traceResult.summary,
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    fun observeModelResult(
        runId: String,
        text: String,
        allowInlineToolCalls: Boolean = true,
    ): AgentModelObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.GeneratingAnswer) return null
        // Capture model's raw output / reasoning for verbose trace (optimization #8).
        // This records the model's full text before tool-call extraction, enabling
        // post-hoc debugging of why the model chose a particular action.
        if (text.isNotBlank()) {
            recordVerboseTrace(
                runId = runId,
                thinkText = text.take(MAX_VERBOSE_TRACE_THINK_TEXT_CHARS),
            )
        }
        if (observationDecisionBudgetExceeded(runId)) {
            return failRunBudget(runId, OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON)
        }
        // Wave 4 telemetry: record step latency for model_generation (delta since
        // step boundary was marked by the caller, with 0L fallback).
        recordStepLatency("model_generation", runId)
        val nextToolPlan = when {
            text.isNotBlank() -> toolPlanCoordinator.planNextToolAfterModelResult(
                run = run,
                text = text.trim(),
                allowInlineToolCalls = allowInlineToolCalls,
            )
            latestSkillPlan(runId) != null -> NextObservationPlan.Rejected(
                "Model output was blank; cannot continue skill.",
            )
            else -> NextObservationPlan.None
        }
        val citationRetryPrompt = if (nextToolPlan == NextObservationPlan.None && text.isNotBlank()) {
            publicEvidenceCitationRetryPromptOrNull(run, text.trim())
        } else {
            null
        }
        val decision = when {
            citationRetryPrompt != null -> AgentObservationDecision.ContinueWithModel(
                requiresLocalModel = false,
                reason = PUBLIC_EVIDENCE_CITATION_RETRY_REASON,
            )

            nextToolPlan == NextObservationPlan.None -> AgentObservationDecision.Complete
            nextToolPlan is NextObservationPlan.Planned -> AgentObservationDecision.PlanNextTool(
                plan = nextToolPlan.plan,
                reason = "Model output satisfied the next skill step.",
            )

            nextToolPlan is NextObservationPlan.Rejected -> AgentObservationDecision.Fail(nextToolPlan.reason)
            else -> AgentObservationDecision.Complete
        }
        val finalState = when (decision) {
            AgentObservationDecision.Complete -> AgentRunState.Completed
            is AgentObservationDecision.PlanNextTool -> decision.plan.nextExecutionState()
            is AgentObservationDecision.PlanToolBatch -> AgentRunState.ExecutingTool
            is AgentObservationDecision.Fail -> AgentRunState.Failed
            AgentObservationDecision.Cancel -> AgentRunState.Cancelled
            is AgentObservationDecision.ContinueWithModel -> AgentRunState.GeneratingAnswer
            is AgentObservationDecision.RetryTool -> AgentRunState.RetryingTool
        }
        val updatedRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = AgentRunState.GeneratingAnswer,
            state = finalState,
        ) ?: return null
        if (decision is AgentObservationDecision.PlanNextTool) {
            toolObservationCoordinator.appendToolPlanSteps(
                runId = runId,
                plan = decision.plan,
            )
        }
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        // Wave 4 telemetry: GenerationComplete recorded with placeholder zeros for token/ttft stats.
        // TODO(Wave 5+): populate ttftMs/inputTokens/outputTokens/thinkingTokens/tps from streaming
        //   runtime callbacks once those metrics are surfaced to the orchestration layer.
        safeRecordTelemetry(
            MetricSample.GenerationComplete(
                ttftMs = 0L,
                totalMs = 0L,
                inputTokens = 0,
                outputTokens = 0,
                thinkingTokens = 0,
                tps = 0.0,
                backend = ServingBackend.LocalLiteRt,
                modelId = "",
                runId = runId,
            ),
            "GenerationComplete",
        )
        // Wave 2: publish terminal events after observeModelResult.
        when (finalState) {
            AgentRunState.Completed -> {
                val turnIdx = runTurnIndex[runId] ?: 0
                eventBus.publish(
                    SolinEvent.Agent.TurnEnded(
                        runId = runId,
                        turnIndex = turnIdx,
                        tokensIn = 0,
                        tokensOut = 0,
                        ttftMs = 0L,
                        durationMs = 0L,
                    ),
                )
                publishRunEnded(runId)
            }
            AgentRunState.Failed -> {
                val msg = (decision as? AgentObservationDecision.Fail)?.reason
                    ?: "observation failed"
                publishRunFailed(runId, AgentErrorCode.Unknown("model_observation"), msg)
            }
            AgentRunState.Cancelled ->
                eventBus.publish(SolinEvent.Agent.RunCancelled(runId = runId, byUser = false))
            AgentRunState.GeneratingAnswer -> {
                val nextTurn = runTurnIndex.compute(runId) { _, old -> (old ?: 0) + 1 }!!
                eventBus.publish(
                    SolinEvent.Agent.TurnStarted(runId = runId, turnIndex = nextTurn),
                )
                markStepStart("model_generation")
            }
            AgentRunState.RetryingTool -> markStepStart("tool_execution")
            AgentRunState.ExecutingTool -> markStepStart("tool_execution")
            else -> Unit
        }
        if (finalState in terminalRunStates) {
            clearEphemeralRunState(runId)
        }
        if (decision is AgentObservationDecision.PlanNextTool && decision.plan.requiresUserConfirmation()) {
            traceStore.savePendingConfirmation(pendingConfirmationSupport.toPendingSnapshot(decision.plan, updatedRun))
        }
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
            continuationPromptForModel = citationRetryPrompt,
            continuationRequiresLocalModel = false,
            continuationRemoteToolScope = RemoteToolScope.PublicEvidenceOnly,
        )
    }

    fun recordRemoteToolsExposed(
        runId: String,
        scope: RemoteToolScope,
        toolNames: Set<String>,
    ) {
        val run = traceStore.run(runId) ?: return
        if (run.state != AgentRunState.GeneratingAnswer) return
        val sanitizedNames = toolNames
            .mapNotNull { toolName -> toolRegistry.specFor(toolName) }
            .filter { spec -> spec.isExposableInRemoteToolScope(scope) }
            .map { spec -> spec.name }
            .toSortedSet()
        remoteToolScopesByRunId[runId] = scope
        remoteExposedToolNamesByRunId[runId] = sanitizedNames
        traceStore.appendStep(
            runId,
            AgentStep.RemoteToolsExposed(
                scope = scope,
                toolNames = sanitizedNames.toList(),
            ),
        )
    }

    fun recordRunDataReceipt(runId: String, receipt: RunDataReceipt) {
        val run = traceStore.run(runId) ?: return
        if (run.state in terminalRunStates) return
        traceStore.appendStep(runId, AgentStep.RunDataReceiptRecorded(receipt))
    }

    fun recordModelOutputQualityGuardTriggered(runId: String, trace: ModelOutputQualityTrace) {
        val run = traceStore.run(runId) ?: return
        if (run.state in terminalRunStates) return
        traceStore.appendStep(runId, AgentStep.ModelOutputQualityGuardTriggered(trace))
    }

    /**
     * Record a [MetricSample.CompactionTriggered] telemetry sample and bump the
     * [TelemetryCounter.CompactionTriggered] counter. Called by the context compactor
     * (wired upstream of this runtime) whenever it truncates or summarizes history.
     */
    fun recordCompactionTriggered(
        runId: String?,
        reason: String,
        contextBefore: Int,
        contextAfter: Int,
    ) {
        safeRecordTelemetry(
            MetricSample.CompactionTriggered(
                reason = reason,
                contextBefore = contextBefore,
                contextAfter = contextAfter,
                runId = runId,
            ),
            "CompactionTriggered",
        )
        safeRecordTelemetry(
            MetricSample.CounterInc(
                name = TelemetryCounter.CompactionTriggered,
                runId = runId,
            ),
            "CompactionTriggered counter",
        )
    }

    /**
     * Run the configured [ContextCompactor] against [messages] targeting [tokenBudget]. Failures
     * in the compactor are isolated (logged, original list returned) so a broken compactor can
     * never lose history; callers should still be prepared to see overflow errors from the model
     * on the returned list. When [force] is true we request aggressive compaction by passing a
     * zero budget — this is the retry path after a context-overflow model error.
     *
     * @param estimatedTokens Model-specific token estimator (chars/4 for remote, LiteRT heuristic
     *   for local). Compactor uses it to measure before/after sizes; defaults to a chars/4
     *   approximation which is good enough for a preflight.
     */
    suspend fun compactHistory(
        messages: List<ChatMessage>,
        tokenBudget: Int,
        runId: String? = null,
        force: Boolean = false,
        estimatedTokens: (List<ChatMessage>) -> Int = ::estimateTokensApproximate,
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        if (contextCompactor is NoOpContextCompactor) return messages
        return runCatching {
            // When forced (overflow-retry), pass budget = 0 so compactor treats it as OverBudget
            // and runs aggressive truncation regardless of current estimate.
            val effectiveBudget = if (force) 0 else tokenBudget.coerceAtLeast(0)
            val result = contextCompactor.compact(messages, effectiveBudget, estimatedTokens)
            if (result.triggerReason != CompactionTrigger.None && result.compactionCount > 0) {
                recordCompactionTriggered(
                    runId = runId,
                    reason = if (force) {
                        "forced_${result.triggerReason.name}"
                    } else {
                        result.triggerReason.name
                    },
                    contextBefore = result.tokensBefore,
                    contextAfter = result.tokensAfter,
                )
            }
            result.messages
        }.onFailure { throwable ->
            Log.e(TAG, "Context compactor failed; falling back to original history", throwable)
        }.getOrDefault(messages)
    }

    /**
     * Mark a step boundary so the next [recordStepLatency] with the same [stepType] can compute a
     * delta. Callers that drive the outer loop (ViewModel/orchestrator) can use this to bracket
     * long-running phases (e.g. mark "model_generation" before sending a prompt, recordStepLatency
     * after observeModelResult).
     */
    fun markStepStart(stepType: String) {
        stepStartedAtMillis[stepType] = System.currentTimeMillis()
    }

    // -----------------------------------------------------------------------
    // Wave 4: public suspend hook entry points. Each is wrapped in runCatching
    // so a throwing hook cannot crash the agent loop; failures are logged and
    // safe defaults are returned. These are the canonical integration points
    // upper layers (ViewModel / Orchestrator) should call at lifecycle points
    // that occur OUTSIDE this runtime (executor.execute, message-list assembly,
    // turn continuation).
    // -----------------------------------------------------------------------

    /**
     * Invoke [AgentHooks.beforeToolCall] for the given tool [request]. Returns the hook's result
     * (Proceed/Blocked), defaulting to Proceed if the hook throws or returns unexpectedly.
     * Callers should treat Blocked as "do NOT call executor.execute" and surface [Blocked.reason]
     * to the user / trace.
     */
    suspend fun beforeToolCall(
        runId: String,
        request: ToolRequest,
    ): BeforeToolCallResult = safeHookCall(
        label = "beforeToolCall",
        default = BeforeToolCallResult.Proceed,
    ) {
        hooks.beforeToolCall(
            BeforeToolCallContext(
                runId = runId,
                toolCallId = request.id,
                toolName = request.toolName,
                args = request.arguments,
            ),
        )
    }

    /**
     * Invoke [AgentHooks.afterToolCall] for the given [result] and record a ToolCompleted
     * telemetry sample with the supplied [durationMs]. Returns the hook's disposition (Keep /
     * ReplaceContent / Terminate); Keep is returned on any hook failure so execution continues.
     */
    suspend fun afterToolCall(
        runId: String,
        request: ToolRequest,
        result: ToolResult,
        durationMs: Long,
    ): AfterToolCallResult = safeHookCall(
        label = "afterToolCall",
        default = AfterToolCallResult.Keep,
    ) {
        // Record telemetry at the same lifecycle point as the hook so dashboards see the tool
        // completion regardless of whether the hook decides to replace/terminate.
        safeRecordTelemetry(
            MetricSample.ToolCompleted(
                toolName = request.toolName,
                latencyMs = durationMs,
                succeeded = result.status == ToolStatus.Succeeded,
                requestId = request.id,
                runId = runId,
            ),
            "ToolCompleted (external)",
        )
        hooks.afterToolCall(
            AfterToolCallContext(
                runId = runId,
                toolCallId = request.id,
                toolName = request.toolName,
                result = result,
                durationMs = durationMs,
            ),
        )
    }

    /**
     * Invoke [AgentHooks.transformContext] on the assembled [messages]. Returns the transformed
     * list; on hook failure the original list is returned unchanged so the model call proceeds.
     */
    suspend fun transformContext(messages: List<ChatMessage>): List<ChatMessage> = safeHookCall(
        label = "transformContext",
        default = messages,
    ) { hooks.transformContext(messages) }

    /**
     * Invoke [AgentHooks.prepareNextTurn] at the start of each continuation turn. Returns the
     * hook's [TurnUpdate] (prependMessages/stopAfterTurn) or null on failure / no-op.
     */
    suspend fun prepareNextTurn(
        runId: String,
        turnIndex: Int,
        messageCount: Int,
        pendingToolCalls: Int,
    ): TurnUpdate? = safeHookCall(
        label = "prepareNextTurn",
        default = null,
    ) {
        hooks.prepareNextTurn(
            TurnContext(
                runId = runId,
                turnIndex = turnIndex,
                messageCount = messageCount,
                pendingToolCalls = pendingToolCalls,
            ),
        )
    }

    /**
     * Invoke [AgentHooks.shouldStopAfterTurn] after a model turn completes. Returns false on any
     * hook failure so the default stop logic (Complete / PlanNextTool / etc.) governs.
     */
    suspend fun shouldStopAfterTurn(
        runId: String,
        turnIndex: Int,
        messageCount: Int,
        pendingToolCalls: Int,
    ): Boolean = safeHookCall(
        label = "shouldStopAfterTurn",
        default = false,
    ) {
        hooks.shouldStopAfterTurn(
            TurnContext(
                runId = runId,
                turnIndex = turnIndex,
                messageCount = messageCount,
                pendingToolCalls = pendingToolCalls,
            ),
        )
    }

    fun observeModelToolRequest(runId: String, request: ToolRequest): AgentModelObservationResult? =
        modelToolRequestCoordinator.observeModelToolRequest(runId, request)

    fun observeModelToolRequests(runId: String, requests: List<ToolRequest>): AgentModelObservationResult? =
        modelToolRequestCoordinator.observeModelToolRequests(runId, requests)

    fun latestPendingConfirmation(sessionId: String? = null): PendingToolConfirmationSnapshot? =
        traceStore.latestPendingConfirmation(sessionId)
            ?.takeIf { snapshot -> pendingConfirmationSupport.restoredPendingConfirmationIsAuthorized(snapshot) }
            ?.also { snapshot -> pendingConfirmationSupport.rememberValueFreeFrontier(snapshot) }

    fun latestPendingExternalOutcome(sessionId: String? = null): PendingExternalOutcomeSnapshot? =
        traceStore.recentRunSummaries(limit = PENDING_EXTERNAL_OUTCOME_RESTORE_RUN_LIMIT, stepLimit = 0)
            .asSequence()
            .map { summary -> summary.run }
            .filter { run ->
                run.state in pendingConfirmationSupport.pendingExternalOutcomeRestoreStates &&
                    (sessionId == null || run.sessionId == sessionId)
            }
            .mapNotNull { run -> pendingConfirmationSupport.pendingExternalOutcomeSnapshotFor(run) }
            .firstOrNull()

    private fun continuationForToolObservation(
        run: AgentRun,
        request: ToolRequest?,
        result: ToolResult,
    ): ToolObservationContinuation? {
        if (result.status != ToolStatus.Succeeded) return null
        skillModelContinuationAfterToolObservation(run, request, result)?.let { continuation ->
            return continuation
        }
        return when (request?.toolName) {
            MobileActionFunctions.READ_CLIPBOARD -> {
                val clipboardText = result.data["text"]?.takeIf { it.isNotBlank() } ?: return null
                val truncated = result.data["truncated"]?.toBooleanStrictOrNull() ?: false
                ToolObservationContinuation(
                    prompt = """
                    用户已经确认读取剪贴板。请根据用户原始请求处理剪贴板文本。
                    如果用户没有明确要求逐字复述，不要完整抄回剪贴板原文；优先总结、改写、提取信息或回答问题。
                    不要使用与当前请求无关的隐私内容。

                    用户原始请求：${run.input}
                    工具观察：${result.summary}
                    剪贴板文本${if (truncated) "（已截断）" else ""}：
                    $clipboardText
                    """.trimIndent(),
                    requiresLocalModel = true,
                    canPlanNextToolBeforeModel = toolPlanCoordinator.canPlanLocalToolFromCurrentScreenObservation(request, run),
                )
            }

            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR -> {
                val ocrText = result.data["ocrText"]?.takeIf { it.isNotBlank() } ?: return null
                val truncated = result.data["truncated"]?.toBooleanStrictOrNull() ?: false
                val screenObservationJson = if (request.toolName == MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR) {
                    result.data["screenObservationJson"]?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val contentLabel = request.toolName.recentImageOcrContentLabel()
                val sourceBoundary = when (request.toolName) {
                    MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR ->
                        "这不是当前屏幕捕获，也不是图片语义理解；只使用已提取的截图文字。"

                    MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR ->
                        "这是用户前台同意后的单次当前屏幕截图 OCR；只使用本地 OCR 文本和可选 Accessibility/OCR 结构化观测，不推断视觉语义。"

                    else ->
                        "这不是当前屏幕捕获，也不是图片语义理解；只使用已提取的图片文字。"
                }
                val screenObservationSection = screenObservationJson?.let { observationJson ->
                    """

                    当前屏幕结构化观测 JSON（LocalOnly，融合 OCR/Accessibility）：
                    $observationJson
                    """.trimIndent()
                }.orEmpty()
                ToolObservationContinuation(
                    prompt = """
                    用户已经确认读取$contentLabel 并在本地提取 OCR 文本。请根据用户原始请求处理 OCR 摘录。
                    $sourceBoundary
                    如果用户没有明确要求逐字复述，不要完整抄回 OCR 原文；优先总结、改写、提取信息或回答问题。

                    用户原始请求：${run.input}
                    工具观察：${result.summary}
                    $contentLabel OCR 文本${if (truncated) "（已截断）" else ""}：
                    $ocrText
                    $screenObservationSection
                    """.trimIndent(),
                    requiresLocalModel = true,
                    canPlanNextToolBeforeModel = toolPlanCoordinator.canPlanLocalToolFromCurrentScreenObservation(request, run),
                )
            }

            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> {
                val screenText = result.data["screenText"]?.takeIf { it.isNotBlank() } ?: return null
                val truncated = result.data["truncated"]?.toBooleanStrictOrNull() ?: false
                ToolObservationContinuation(
                    prompt = """
                    用户已经确认读取当前 active window 的 Accessibility 可访问文本快照。请根据用户原始请求处理这段屏幕文本。
                    这不是截图捕获、不是 OCR、不是视觉/VLM 或语义屏幕理解；只使用当前屏幕暴露的可访问文本。
                    如果用户没有明确要求逐字复述，不要完整抄回屏幕文本；优先总结、提取信息或回答问题。

                    用户原始请求：${run.input}
                    工具观察：${result.summary}
                    当前屏幕文本${if (truncated) "（已截断）" else ""}：
                    $screenText
                    """.trimIndent(),
                    requiresLocalModel = true,
                )
            }

            else -> policyContinuationAfterToolObservation(run, request, result)
        }
    }

    private fun policyContinuationAfterToolObservation(
        run: AgentRun,
        request: ToolRequest?,
        result: ToolResult,
    ): ToolObservationContinuation? {
        request ?: return null
        val spec = toolRegistry.specFor(request.toolName) ?: return null
        return when (spec.resultContinuationPolicy) {
            ToolResultContinuationPolicy.None -> null
            ToolResultContinuationPolicy.PublicEvidence ->
                publicEvidenceContinuationAfterToolObservation(run, request, result)

            ToolResultContinuationPolicy.LocalEvidence ->
                localEvidenceContinuationAfterToolObservation(run, request, result)
        }
    }

    private fun publicEvidenceContinuationAfterToolObservation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): ToolObservationContinuation? {
        if (!result.isPublicEvidenceForRemoteModel()) return null
        val evidenceBlocks = priorPublicEvidencePromptBlocks(
            runId = run.id,
            excludedRequestIds = setOf(request.id),
        ) + request.publicEvidencePromptBlock(result)
        return ToolObservationContinuation(
            prompt = publicEvidenceContinuationPrompt(
                run = run,
                evidenceBlocks = evidenceBlocks,
                gapBlocks = emptyList(),
            ),
            requiresLocalModel = false,
            canPlanNextToolBeforeModel = true,
        )
    }

    private fun publicEvidenceBatchContinuationPrompt(
        run: AgentRun,
        observedPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
        successfulPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
        gapPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
    ): String {
        val currentRequestIds = observedPairs.mapTo(mutableSetOf()) { (plan, _) -> plan.request.id }
        val evidenceBlocks = priorPublicEvidencePromptBlocks(
            runId = run.id,
            excludedRequestIds = currentRequestIds,
        ) + successfulPairs.map { (plan, result) ->
            plan.request.publicEvidencePromptBlock(result)
        }
        val gapBlocks = gapPairs.map { (plan, result) ->
            plan.request.publicEvidenceGapBlock(result)
        }
        return publicEvidenceContinuationPrompt(
            run = run,
            evidenceBlocks = evidenceBlocks,
            gapBlocks = gapBlocks,
        )
    }

    private fun publicEvidenceContinuationPrompt(
        run: AgentRun,
        evidenceBlocks: List<PublicEvidencePromptBlock>,
        gapBlocks: List<PublicEvidenceGapBlock>,
    ): String {
        val gapSection = if (gapBlocks.isEmpty()) {
            ""
        } else {
            """

            失败缺口：
            ${gapBlocks.renderPublicEvidenceGapBlocks()}
            """.trimIndent()
        }
        return """
            请根据以下公开只读工具结果回答用户原始问题。不要只回答最后一次工具结果；如果用户要求比较、计算、总结或判断，请综合所有可用证据完成对应推理。
            只以工具公开证据为依据，并以每条编号来源的 retrievedAt 判断时效；同一事实存在多条证据时，以 retrievedAt 最新且最相关的证据为准。
            涉及“最新”“目前”“当前”“今天”等时效性问题时，必须优先使用最新 retrievedAt 的工具证据；不得用模型训练知识、旧知识或未给出的网页内容补全空白。
            关键事实必须带来源编号，例如 [S1] [S2]；只能引用下方存在的来源编号，不能编造来源编号或链接。
            如果来源不足、冲突、质量为 Low，或缺少可核验 URL，请明确说明证据不足，不要给过度确定结论。
            如果存在失败缺口，请先基于成功证据部分回答；无法完成的部分明确说明缺少什么信息，不要编造。
            如果工具结果仍不足以完成用户请求，可以继续调用公开只读工具补充证据；仍不足时请明确说明缺少什么信息，不要编造。

            用户原始请求：${run.input}

            成功公开证据：
            ${evidenceBlocks.renderPublicEvidenceBlocks()}
            $gapSection
        """.trimIndent()
    }

    private fun priorPublicEvidencePromptBlocks(
        runId: String,
        excludedRequestIds: Set<String>,
    ): List<PublicEvidencePromptBlock> {
        val requestsById = toolRequestsFor(runId).associateBy { request -> request.id }
        return traceStore.steps(runId)
            .asSequence()
            .mapNotNull { step -> (step as? AgentStep.ToolObserved)?.result }
            .filter { result ->
                result.requestId !in excludedRequestIds &&
                    result.status == ToolStatus.Succeeded &&
                    result.isPublicEvidenceForRemoteModel()
            }
            .mapNotNull { result ->
                val request = requestsById[result.requestId] ?: return@mapNotNull null
                val spec = toolRegistry.specFor(request.toolName) ?: return@mapNotNull null
                if (spec.resultContinuationPolicy != ToolResultContinuationPolicy.PublicEvidence) {
                    return@mapNotNull null
                }
                request.publicEvidencePromptBlock(result)
            }
            .toList()
    }

    private fun ToolResult.isPublicEvidenceForRemoteModel(): Boolean =
        data["privacy"] == MessagePrivacy.RemoteEligible.name &&
            data["requiresLocalModel"]?.toBooleanStrictOrNull() == false

    private fun ToolRequest.publicEvidencePromptBlock(result: ToolResult): PublicEvidencePromptBlock {
        val evidencePack = publicWebEvidencePackFromToolResultData(result.data)
        return PublicEvidencePromptBlock(
            toolName = toolName,
            argumentBlock = argumentsPromptBlock(),
            summary = result.summary,
            dataBlock = if (evidencePack == null) result.promptDataBlock() else "",
            evidencePack = evidencePack,
        )
    }

    private fun ToolRequest.publicEvidenceGapBlock(result: ToolResult): PublicEvidenceGapBlock =
        PublicEvidenceGapBlock(
            toolName = toolName,
            argumentBlock = argumentsPromptBlock(),
            status = result.status.name,
            summary = result.summary,
            errorMessage = result.error?.message,
        )

    private fun ToolRequest.argumentsPromptBlock(): String =
        arguments.entries
            .sortedBy { (key, _) -> key }
            .joinToString(separator = "\n") { (key, value) ->
                "$key: ${value.boundedPromptValue()}"
            }
            .ifBlank { "无" }

    private fun List<Pair<AgentPlan.UseTool, ToolResult>>.publicEvidenceBatchAuditSummary(): String {
        val remotePairs = filter { (_, result) ->
            result.status == ToolStatus.Succeeded && result.isPublicEvidenceForRemoteModel()
        }
        if (remotePairs.isEmpty()) return "证据摘要：无可公开摘要"
        val queries = remotePairs
            .mapNotNull { (plan, result) ->
                result.data["query"]?.takeIf { it.isNotBlank() }
                    ?: plan.request.arguments["query"]?.takeIf { it.isNotBlank() }
            }
            .distinct()
            .take(3)
            .joinToString(separator = "、") { query -> "「${query.compactAuditValue(80)}」" }
        val sourceLabels = remotePairs
            .flatMap { (_, result) -> result.publicEvidenceSourceLabels() }
            .distinct()
        val keyItems = remotePairs
            .flatMap { (_, result) -> result.publicEvidenceKeyItems() }
            .distinct()
            .take(3)
            .joinToString(separator = "；") { item -> item.compactAuditValue(120) }
        val parts = buildList {
            if (queries.isNotBlank()) add("查询：$queries")
            if (sourceLabels.isNotEmpty()) add("来源 ${sourceLabels.size} 个")
            if (keyItems.isNotBlank()) add("关键项：$keyItems")
        }
        return if (parts.isEmpty()) {
            "证据摘要：无可公开摘要"
        } else {
            parts.joinToString(separator = "；", prefix = "证据摘要：")
        }
    }

    private fun ToolResult.publicEvidenceSourceLabels(): List<String> {
        val json = publicEvidenceResultsJsonObject()
        val sourceLabels = json?.optJSONArray("sources")
            ?.objects()
            ?.mapNotNull { source ->
                firstNonBlank(
                    source.optString("name").trim(),
                    source.optString("url").trim(),
                    source.optString("id").trim(),
                )
            }
            .orEmpty()
        if (sourceLabels.isNotEmpty()) return sourceLabels
        return listOfNotNull(
            data["source"]?.takeIf { it.isNotBlank() },
            json?.optString("provider")?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun ToolResult.publicEvidenceKeyItems(): List<String> {
        val json = publicEvidenceResultsJsonObject()
        val resultItems = json?.optJSONArray("results")
            ?.objects()
            ?.mapNotNull { result ->
                val title = result.optString("title").trim()
                val url = result.optString("url").trim()
                val requestedLocation = result.optString("requestedLocation").trim()
                val location = result.optString("location").trim()
                when {
                    title.isNotBlank() && url.isNotBlank() -> "$title $url"
                    url.isNotBlank() -> url
                    title.isNotBlank() -> title
                    requestedLocation.isNotBlank() -> requestedLocation
                    location.isNotBlank() -> location
                    else -> null
                }
            }
            .orEmpty()
        val sourceLinks = json?.optJSONArray("sources")
            ?.objects()
            ?.mapNotNull { source -> source.optString("url").trim().takeIf { it.isNotBlank() } }
            .orEmpty()
        val fallbackSummary = data["summaryText"]
            ?.takeIf { it.isNotBlank() }
            ?: summary.takeIf { it.isNotBlank() }
        return (resultItems + sourceLinks).ifEmpty {
            listOfNotNull(fallbackSummary)
        }
    }

    private fun ToolResult.publicEvidenceResultsJsonObject(): JSONObject? =
        data["resultsJson"]?.let { rawJson ->
            runCatching { JSONObject(rawJson) }.getOrNull()
        }

    private fun JSONArray.objects(): List<JSONObject> =
        buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(::add)
            }
        }

    private fun firstNonBlank(vararg values: String): String? =
        values.firstOrNull { value -> value.isNotBlank() }

    private fun List<PublicEvidencePromptBlock>.renderPublicEvidenceBlocks(): String =
        if (isEmpty()) {
            "无"
        } else {
            var sourceIndex = 1
            mapIndexed { index, block ->
                val evidencePack = block.evidencePack
                val numberedItems = evidencePack?.items?.map { item ->
                    NumberedPublicWebEvidenceItem(
                        sourceId = "S${sourceIndex++}",
                        pack = evidencePack,
                        item = item,
                    )
                }.orEmpty()
                if (numberedItems.isNotEmpty()) {
                    """
                        工具 ${index + 1}
                        工具名称：${block.toolName}
                        工具参数：
                        ${block.argumentBlock}
                        工具观察：${block.summary.boundedPromptValue()}
                        证据包：retrievedAt=${evidencePack?.retrievedAt.orEmpty()} freshness=${evidencePack?.freshness.orEmpty()} quality=${evidencePack?.quality.orEmpty()}
                        编号来源：
                        ${numberedItems.joinToString(separator = "\n\n") { item -> item.renderPublicWebEvidencePromptItem() }}
                    """.trimIndent()
                } else {
                """
                    工具 ${index + 1}
                    工具名称：${block.toolName}
                    工具参数：
                    ${block.argumentBlock}
                    工具观察：${block.summary.boundedPromptValue()}
                    工具公开数据：
                    ${block.dataBlock}
                """.trimIndent()
                }
            }.joinToString(separator = "\n\n")
        }

    private fun NumberedPublicWebEvidenceItem.renderPublicWebEvidencePromptItem(): String =
        """
            [$sourceId] ${item.title.boundedPromptValue()}
            source: ${item.sourceName.ifBlank { "unknown" }.boundedPromptValue()}
            url: ${item.url.ifBlank { "无可核验 URL" }.boundedPromptValue()}
            retrievedAt: ${pack.retrievedAt.ifBlank { "unknown" }}
            freshness: ${pack.freshness.ifBlank { "unknown" }}
            quality: ${item.qualityLabel.ifBlank { pack.quality }.ifBlank { "unknown" }}
            snippet: ${item.snippet.ifBlank { "无摘要" }.boundedPromptValue()}
        """.trimIndent()

    private fun publicEvidenceCitationRetryPromptOrNull(
        run: AgentRun,
        answer: String,
    ): String? {
        if (publicEvidenceCitationRetryAlreadyAttempted(run.id)) return null
        val numberedItems = numberedPublicEvidenceItemsForRun(run.id)
        if (numberedItems.isEmpty()) return null
        val check = checkPublicWebAnswerCitations(
            answer = answer,
            sourceIds = numberedItems.mapTo(linkedSetOf()) { item -> item.sourceId },
            hasLowQualityEvidence = numberedItems.any { item ->
                item.item.qualityLabel == "Low" || item.pack.quality == "Low"
            },
            hasVerifiableUrl = numberedItems.any { item -> item.item.url.isNotBlank() },
        )
        if (check.status == AnswerCitationCheckStatus.Warning) {
            recordPublicEvidenceCitationWarningIfNeeded(run.id, check.reason, answer.length)
        }
        if (!check.shouldRetry) return null
        return """
            上一版回答未通过公开来源引用检查：${check.reason}。
            请重写一次最终答案：关键事实必须引用下方已有编号来源；不能引用不存在的来源编号；证据不足、冲突、低质量或无可核验 URL 时必须明说。不要输出工具 JSON。

            上一版回答：
            ${answer.boundedPromptValue()}

            ${publicEvidenceContinuationPrompt(
            run = run,
            evidenceBlocks = priorPublicEvidencePromptBlocks(
                runId = run.id,
                excludedRequestIds = emptySet(),
            ),
            gapBlocks = emptyList(),
        )}
        """.trimIndent()
    }

    private fun numberedPublicEvidenceItemsForRun(runId: String): List<NumberedPublicWebEvidenceItem> =
        buildList {
            var sourceIndex = 1
            priorPublicEvidencePromptBlocks(
                runId = runId,
                excludedRequestIds = emptySet(),
            ).forEach { block ->
                val pack = block.evidencePack ?: return@forEach
                pack.items.forEach { item ->
                    add(
                        NumberedPublicWebEvidenceItem(
                            sourceId = "S${sourceIndex++}",
                            pack = pack,
                            item = item,
                        ),
                    )
                }
            }
        }

    private fun recordPublicEvidenceCitationWarningIfNeeded(
        runId: String,
        reason: String,
        rawOutputLength: Int,
    ) {
        val alreadyRecorded = traceStore.steps(runId).any { step ->
            val trace = (step as? AgentStep.ModelOutputQualityGuardTriggered)?.trace
            trace?.triggeredRule == "public_web_answer_citation_check" &&
                trace.issue == reason
        }
        if (alreadyRecorded) return
        traceStore.appendStep(
            runId,
            AgentStep.ModelOutputQualityGuardTriggered(
                ModelOutputQualityTrace(
                    issue = reason,
                    severity = "warning",
                    triggeredRule = "public_web_answer_citation_check",
                    action = "record",
                    rawOutputLength = rawOutputLength,
                    keptPrefix = true,
                    modelId = null,
                    backend = null,
                    runtimeKind = "unknown",
                ),
            ),
        )
    }

    private fun publicEvidenceCitationRetryAlreadyAttempted(runId: String): Boolean =
        traceStore.steps(runId).any { step ->
            val decision = (step as? AgentStep.ObservationDecided)?.decision
            decision is AgentObservationDecision.ContinueWithModel &&
                decision.reason == PUBLIC_EVIDENCE_CITATION_RETRY_REASON
        }

    private fun List<PublicEvidenceGapBlock>.renderPublicEvidenceGapBlocks(): String =
        if (isEmpty()) {
            "无"
        } else {
            mapIndexed { index, block ->
                """
                    缺口 ${index + 1}
                    工具名称：${block.toolName}
                    工具参数：
                    ${block.argumentBlock}
                    状态：${block.status}
                    失败摘要：${block.summary.boundedPromptValue()}
                    错误：${block.errorMessage?.boundedPromptValue() ?: "无"}
                """.trimIndent()
            }.joinToString(separator = "\n\n")
        }

    private fun ToolResult.publicEvidenceBatchResultOrFailure(request: ToolRequest): ToolResult {
        if (status != ToolStatus.Succeeded) return this
        if (!isPublicEvidenceForRemoteModel()) {
            val summary = "Tool ${request.toolName} returned local-only evidence in a public evidence batch."
            return request.failed(
                code = ToolErrorCode.InvalidResult,
                summary = summary,
                retryable = false,
                data = mapOf("toolName" to request.toolName),
            )
        }
        return this
    }

    private fun localEvidenceContinuationAfterToolObservation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): ToolObservationContinuation? {
        if (result.data["privacy"] != MessagePrivacy.LocalOnly.name) return null
        if (result.data["requiresLocalModel"]?.toBooleanStrictOrNull() != true) return null
        val localData = result.promptDataBlock()
        return ToolObservationContinuation(
            prompt = """
                用户已经确认读取本地只读工具结果。请只根据用户原始请求处理这份本地证据，不要使用与当前请求无关的隐私内容。
                如果用户没有明确要求逐字复述，不要完整抄回本地原始数据；优先总结、比较、提取信息或回答问题。

                用户原始请求：${run.input}
                工具名称：${request.toolName}
                工具观察：${result.summary}
                本地工具数据：
                $localData
            """.trimIndent(),
            requiresLocalModel = true,
            canPlanNextToolBeforeModel = toolPlanCoordinator.canPlanLocalToolFromCurrentScreenObservation(request, run),
        )
    }

    private fun ToolResult.promptDataBlock(): String =
        data.entries
            .sortedBy { (key, _) -> key }
            .joinToString(separator = "\n") { (key, value) ->
                "$key: ${value.boundedPromptValue()}"
            }
            .ifBlank { "无" }

    private fun skillModelContinuationAfterToolObservation(
        run: AgentRun,
        request: ToolRequest?,
        result: ToolResult,
    ): ToolObservationContinuation? {
        request ?: return null
        val skillPlan = latestSkillPlan(run.id) ?: return null
        if (initialToolPlanner.invalidSkillPlanReason(skillPlan) != null) return null
        val currentStepIndex = skillPlan.steps.indexOfFirst { step ->
            step is SkillStep.ToolStep && step.request.id == request.id
        }
        if (currentStepIndex < 0) return null
        val currentStep = skillPlan.steps[currentStepIndex] as SkillStep.ToolStep
        val requestedStepIds = skillPlan.steps
            .filterIsInstance<SkillStep.ToolStep>()
            .filter { step -> toolRequestFor(run.id, step.request.id) != null }
            .mapTo(mutableSetOf()) { step -> step.id }
        val nextModelStep = skillPlan.steps
            .drop(currentStepIndex + 1)
            .filterIsInstance<SkillStep.ModelStep>()
            .firstOrNull { step ->
                currentStep.id in step.dependsOn && step.dependsOn.all { dependency -> dependency in requestedStepIds }
            } ?: return null
        val outputs = skillProgressor.initialOutputs(skillPlan)
        outputs[currentStep.id] = skillProgressor.outputForToolResult(result, currentStep.draft)
        val inputs = when (val binding = skillProgressor.bindModelStep(nextModelStep, outputs)) {
            is SkillModelStepBinding.Bound -> binding.inputs
            is SkillModelStepBinding.Missing -> return null
        }
        val originalRequest = originalRequestForSkillContinuation(run, skillPlan)
        val privateRefs = skillProgressor.privateOutputRefsFor(currentStep.id, request.toolName)
        val requiresLocalModel = nextModelStep.keepsSensitiveInputLocal ||
            nextModelStep.inputBindings.values.any { sourceRef -> sourceRef in privateRefs } ||
            toolRegistry.privateOutputKeysFor(request.toolName).isNotEmpty() ||
            result.requiresLocalModelContinuation()
        val inputBlock = inputs.entries.joinToString(separator = "\n\n") { (name, value) ->
            "$name:\n$value"
        }
        return ToolObservationContinuation(
            prompt = """
                继续执行本地 Skill：${skillPlan.manifest.title}
                用户原始请求：$originalRequest
                当前模型步骤：${nextModelStep.title}
                步骤指令：${nextModelStep.instruction}

                可用输入：
                $inputBlock

                只输出 `${nextModelStep.outputKey}` 对应的文本，不要输出 JSON 或额外说明。
            """.trimIndent(),
            requiresLocalModel = requiresLocalModel,
            blocksSequentialTail = true,
        )
    }

    private fun originalRequestForSkillContinuation(
        run: AgentRun,
        skillPlan: SkillPlan,
    ): String {
        return run.input
            .takeIf { input -> input.isNotBlank() && input != REDACTED_AGENT_RUN_INPUT_VALUE }
            ?: skillPlan.request.arguments["input"]?.takeIf { input ->
                input.isNotBlank() && input != REDACTED_AGENT_RUN_INPUT_VALUE
            }
            ?: skillPlan.request.reason.takeIf { reason ->
                reason.isNotBlank() && reason != REDACTED_AGENT_RUN_INPUT_VALUE
            }
            ?: skillPlan.manifest.title
    }

    private fun rejectRemoteToolIfNotExposedInCurrentScope(
        runId: String,
        request: ToolRequest,
    ): ToolResult? {
        val spec = toolRegistry.specFor(request.toolName)
            ?: return request.rejected("Unknown tool: ${request.toolName}")
        val scope = remoteToolScopesByRunId[runId] ?: RemoteToolScope.PublicEvidenceOnly
        val exposedNames = remoteExposedToolNamesByRunId[runId]
            ?: return request.rejected(
                "Remote tool ${request.toolName} cannot be used before a remote tool snapshot is recorded.",
            )
        if (request.toolName !in exposedNames) {
            return request.rejected(
                "Remote tool ${request.toolName} was not exposed in the current remote tool snapshot.",
            )
        }
        return if (spec.isExposableInRemoteToolScope(scope)) {
            null
        } else {
            request.rejected(
                "Remote tool ${request.toolName} was not exposed in the current ${scope.name} tool scope.",
            )
        }
    }

    private fun ToolSpec.isExposableInRemoteToolScope(scope: RemoteToolScope): Boolean =
        when (scope) {
            RemoteToolScope.PublicEvidenceOnly -> isEligibleForParallelBatch()
            RemoteToolScope.ModelPlanning -> isRemoteModelPlanningEligible()
        }

    private fun AgentPlan.UseTool.withConfirmationBypassForRun(runId: String): AgentPlan.UseTool {
        if (lowRiskDeviceActionConfirmationBypassByRunId[runId] != true) return this
        if (!request.isLowRiskDeviceActionConfirmationSkippable()) return this
        if (exceedsLowRiskAppControlCheckpointForRun(runId)) return this
        if (safetyDecision.outcome != SafetyOutcome.RequireConfirmation) return this
        val bypassDecision = SafetyDecision(
            outcome = SafetyOutcome.Allow,
            reason = "Low-risk device action confirmation was skipped by the user's settings.",
        )
        return copy(
            draft = draft.copy(requiresConfirmation = false),
            safetyDecision = bypassDecision,
        )
    }

    private fun AgentPlan.UseTool.withLowRiskAppControlContinuationBypassForRun(
        runId: String,
        skillPlan: SkillPlan?,
    ): AgentPlan.UseTool {
        val activeSkillPlan = lowRiskAppControlSkillPlanForRun(runId, skillPlan ?: this.skillPlan) ?: return this
        appControlSessionForRun(runId, activeSkillPlan)?.takeIf { it.lowRisk } ?: return this
        if (!request.isLowRiskAppControlContinuationTool()) return this
        if (safetyDecision.outcome != SafetyOutcome.RequireConfirmation) return this
        if (!activeSkillPlan.hasConfirmedToolStep(runId)) return this
        if (exceedsLowRiskAppControlCheckpointForRun(runId, activeSkillPlan)) return this
        val bypassDecision = SafetyDecision(
            outcome = SafetyOutcome.Allow,
            reason = "Low-risk app control continuation was allowed after the user's initial confirmation.",
        )
        return copy(
            draft = draft.copy(requiresConfirmation = false),
            safetyDecision = bypassDecision,
        )
    }

    private fun lowRiskAppControlSkillPlanForRun(runId: String, preferred: SkillPlan?): SkillPlan? =
        preferred?.takeIf { plan -> plan.isLowRiskAppControlSkill() }
            ?: latestLowRiskAppControlSkillPlan(runId)

    private fun AgentPlan.UseTool.exceedsLowRiskAppControlCheckpointForRun(
        runId: String,
        activeSkillPlan: SkillPlan? = skillPlan ?: latestSkillPlan(runId),
    ): Boolean {
        val session = appControlSessionForRun(runId, activeSkillPlan) ?: return false
        return request.isLowRiskAppControlContinuationTool() &&
            request.isCheckpointedUiActionTool() &&
            session.checkpointRequired
    }

    private fun appControlSessionForRun(
        runId: String,
        activeSkillPlan: SkillPlan? = latestSkillPlan(runId),
    ): AppControlSession? {
        val plan = activeSkillPlan ?: return null
        if (!plan.isLowRiskAppControlSkill()) return null
        val checkpointedStepCount = plan.requestedCheckpointedUiActionCount(runId)
        return AppControlSession(
            runId = runId,
            skillId = plan.manifest.id,
            targetPackage = plan.expectedPackageName(),
            lowRisk = true,
            stepCount = checkpointedStepCount,
            checkpointRequired = checkpointedStepCount >= LOW_RISK_APP_CONTROL_UI_ACTION_CHECKPOINT_LIMIT,
        )
    }

    private fun shouldAwaitExternalOutcomeConfirmation(
        runId: String,
        request: ToolRequest,
        result: ToolResult,
    ): Boolean {
        if (!result.isUnverifiedExternalLaunch()) return false
        if (request.isLowRiskDeviceActionConfirmationSkippable()) return false
        return true
    }

    private fun ToolRequest.isLowRiskDeviceActionConfirmationSkippable(): Boolean =
        toolRegistry.isLowRiskDeviceActionConfirmationSkippable(this)

    private fun ToolRequest.isLowRiskAppControlContinuationTool(): Boolean =
        toolRegistry.isLowRiskAppControlContinuationTool(this)

    private fun ToolRequest.isCheckpointedUiActionTool(): Boolean =
        toolRegistry.isCheckpointedUiActionTool(toolName)

    private fun SkillPlan.hasConfirmedToolStep(runId: String): Boolean {
        val stepRequestIds = toolStepRequestIds()
        return traceStore.steps(runId).any { step ->
            step is AgentStep.UserConfirmed && step.requestId in stepRequestIds
        }
    }

    private fun SkillPlan.requestedCheckpointedUiActionCount(runId: String): Int {
        val stepRequestIds = toolStepRequestIds()
        return toolRequestsFor(runId).count { request ->
            request.id in stepRequestIds && request.isCheckpointedUiActionTool()
        }
    }

    private fun SkillPlan.expectedPackageName(): String? =
        steps.filterIsInstance<SkillStep.ToolStep>()
            .firstNotNullOfOrNull { step ->
                step.request.arguments["expectedPackageName"]?.takeIf { it.isNotBlank() }
            }

    private fun SkillPlan.toolStepRequestIds(): Set<String> =
        steps.filterIsInstance<SkillStep.ToolStep>()
            .mapTo(mutableSetOf()) { step -> step.request.id }

    private fun clearEphemeralRunState(runId: String) {
        if (runUsedDeviceControlSession(runId)) {
            runCatching { deviceControlSessionFinisher() }
        }
        toolRequestsFor(runId).forEach { request ->
            toolDispatchStartedAtMillis.remove(request.id)
        }
        valueFreeCompletedStepFrontiersByRunId.remove(runId)
        remoteToolScopesByRunId.remove(runId)
        remoteExposedToolNamesByRunId.remove(runId)
        lowRiskDeviceActionConfirmationBypassByRunId.remove(runId)
        installedCapabilityProfilesByRunId.remove(runId)
        profilesByRunId.remove(runId)
        // Best-effort drain of queued steer/queued batches for the terminating run so the
        // channels don't accumulate messages for dead runs. Off-target batches are re-enqueued
        // by pumpChannel; this is safe to call after the run has gone terminal.
        drainPendingMessages(runId)
        cancelAndClearModelCallJob(runId)
        pendingUserQuestionsByRunId.remove(runId)
        runCatching { scratchpad.clear(runId) }
        // Wave 7: expire undo entries whose source run is terminating. Any surviving entries
        // with a later TTL than the run lifetime are invalidated by the run end.
        runCatching {
            synchronized(undoStack) {
                val iter = undoStack.iterator()
                while (iter.hasNext()) {
                    if (iter.next().sourceRunId == runId) iter.remove()
                }
            }
        }
        // Wave 2 lifecycle metadata: keep briefly for post-terminal subscribers; clear on next
        // run or close. Left intentionally out of the strict clear to allow late subscribers to
        // still read start-time; bounded by process memory.
    }

    private fun clearDeletedRunState(runId: String) {
        runStartedAtMillis.remove(runId)
        runTurnIndex.remove(runId)
    }

    /**
     * Wave 7: return the currently-live undo entry (compensating tool) if one exists and its
     * TTL has not elapsed, or null otherwise. Clears an expired entry as a side effect.
     */
    fun latestUndo(): UndoEntry? {
        synchronized(undoStack) {
            val top = undoStack.peek() ?: return null
            val now = System.currentTimeMillis()
            if (top.availableUntilMillis <= now) {
                undoStack.clear()
                return null
            }
            return top
        }
    }

    /**
     * Wave 7: return the [UndoPlan] of the currently-live undo entry, or null if none is
     * available / the entry is expired. Useful for UI affordances that want to show the
     * compensating-tool summary without exposing the full entry struct.
     */
    fun peekUndoPlan(): UndoPlan? = latestUndo()?.plan

    private fun runUsedDeviceControlSession(runId: String): Boolean =
        toolRequestsFor(runId).any { request ->
            toolRegistry.startsDeviceControlSession(request.toolName) ||
                toolPlanCoordinator.isDeviceControlTool(request)
        }

    private data class AppControlSession(
        val runId: String,
        val skillId: String,
        val targetPackage: String?,
        val lowRisk: Boolean,
        val stepCount: Int,
        val checkpointRequired: Boolean,
    )

    private data class PublicEvidencePromptBlock(
        val toolName: String,
        val argumentBlock: String,
        val summary: String,
        val dataBlock: String,
        val evidencePack: PublicWebEvidencePack? = null,
    )

    private data class NumberedPublicWebEvidenceItem(
        val sourceId: String,
        val pack: PublicWebEvidencePack,
        val item: PublicWebEvidenceItem,
    )

    private data class PublicEvidenceGapBlock(
        val toolName: String,
        val argumentBlock: String,
        val status: String,
        val summary: String,
        val errorMessage: String?,
    )

    private val terminalRunStates = setOf(
        AgentRunState.Completed,
        AgentRunState.Cancelled,
        AgentRunState.Failed,
    )

    private companion object {
        const val RUN_DELETED_SESSION_REASON = "Session deleted before this Agent run completed."
    }

    // -----------------------------------------------------------------------
    // Wave 2 SolinEvent lifecycle helpers — dual-write alongside traceStore.
    // -----------------------------------------------------------------------

    private fun publishRunFailed(
        runId: String,
        code: AgentErrorCode,
        message: String,
        terminalTurnIndex: Int? = runTurnIndex[runId],
    ) {
        eventBus.publish(
            SolinEvent.Agent.RunFailed(
                runId = runId,
                code = code,
                message = message,
                terminalTurnIndex = terminalTurnIndex,
            ),
        )
    }

    private fun publishRunEnded(
        runId: String,
        finalText: String? = null,
    ) {
        val start = runStartedAtMillis[runId]
        val durationMs = start?.let { System.currentTimeMillis() - it }
        val turns = runTurnIndex[runId] ?: 0
        eventBus.publish(
            SolinEvent.Agent.RunEnded(
                runId = runId,
                totalTurns = turns,
                finalText = finalText,
                durationMs = durationMs,
            ),
        )
    }

    private fun ToolErrorCode?.toOrchestrationErrorCode(): OrchestrationToolErrorCode = when (this) {
        null -> OrchestrationToolErrorCode.Unknown("unknown")
        ToolErrorCode.PermissionDenied -> OrchestrationToolErrorCode.Unknown("permission_denied")
        ToolErrorCode.UnknownTool -> OrchestrationToolErrorCode.ToolNotFound
        ToolErrorCode.InvalidRequest, ToolErrorCode.InvalidResult, ToolErrorCode.MissingArgument ->
            OrchestrationToolErrorCode.InvalidArgs
        ToolErrorCode.NoActivityFound -> OrchestrationToolErrorCode.Unknown("no_activity_found")
        ToolErrorCode.ExecutionFailed -> OrchestrationToolErrorCode.ExecutionFailed
        ToolErrorCode.UserCancelled -> OrchestrationToolErrorCode.Cancelled
    }

    private fun ToolResult.requiresLocalModelContinuation(): Boolean =
        data["requiresLocalModel"]?.toBooleanStrictOrNull() == true ||
            data["privacy"] == MessagePrivacy.LocalOnly.name

    private fun ToolResult.redactedForTrace(request: ToolRequest?): ToolResult {
        val toolName = request?.toolName ?: return this
        val privateKeys = toolRegistry.privateOutputKeysFor(toolName)
        if (privateKeys.isEmpty()) return this
        val redactedData = privateKeys.fold(data) { currentData, key ->
            if (key in currentData) {
                currentData + (key to "[redacted]")
            } else {
                currentData
            }
        }
        if (redactedData == data) return this
        return copy(
            summary = toolRegistry.redactedResultSummaryFor(toolName) ?: summary,
            data = redactedData,
        )
    }

    private fun String?.recentImageOcrContentLabel(): String =
        when (this) {
            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> "最近图片"
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR -> "当前屏幕截图"
            else -> "最近截图"
        }

    private fun auditToolEvent(
        runId: String,
        plan: AgentPlan.UseTool,
        eventType: ToolAuditEventType,
        status: ToolStatus?,
        summary: String,
    ) {
        auditToolRequest(
            runId = runId,
            request = plan.request,
            eventType = eventType,
            status = status,
            skillId = plan.skillRequest?.skillId,
            summary = summary,
        )
    }

    private fun auditToolRequest(
        runId: String,
        request: ToolRequest,
        eventType: ToolAuditEventType,
        status: ToolStatus?,
        skillId: String? = skillIdForRequest(runId, request.id),
        summary: String,
    ) {
        val spec = toolRegistry.specFor(request.toolName)
        val riskLevel = spec?.riskLevel
        val permissions = spec?.permissions.orEmpty()
        auditSink.record(
            ToolAuditEvent(
                runId = runId,
                requestId = request.id,
                toolName = request.toolName,
                skillId = skillId,
                eventType = eventType,
                status = status,
                riskLevel = riskLevel,
                permissions = permissions,
                summary = summary,
            ),
        )
        // Wave 4 dual-write: publish the same event onto SolinEventBus so the
        // ToolAuditRepository subscriber can record it through the new seam. The
        // direct record() call above stays in place until the bus path is verified.
        eventBus.publish(
            SolinEvent.Audit.ToolAudited(
                runId = runId,
                requestId = request.id,
                toolName = request.toolName,
                skillId = skillId,
                eventType = eventType.name,
                status = status?.name,
                riskLevel = riskLevel?.name,
                permissionsCsv = permissions
                    .map { it.name }
                    .sorted()
                    .joinToString(separator = ","),
                summary = summary,
            ),
        )
    }

    private fun auditRejectedTool(runId: String, result: ToolResult) {
        val toolName = result.data["toolName"]
        val spec = toolName?.let(toolRegistry::specFor)
        val resolvedSkillId = result.requestId.takeIf { it.isNotBlank() }?.let { requestId ->
            skillIdForRequest(runId, requestId)
        }
        val permissions = spec?.permissions.orEmpty()
        auditSink.record(
            ToolAuditEvent(
                runId = runId,
                requestId = result.requestId,
                toolName = toolName,
                skillId = resolvedSkillId,
                eventType = ToolAuditEventType.ToolRejected,
                status = result.status,
                riskLevel = spec?.riskLevel,
                permissions = permissions,
                summary = result.summary,
            ),
        )
        eventBus.publish(
            SolinEvent.Audit.ToolAudited(
                runId = runId,
                requestId = result.requestId,
                toolName = toolName,
                skillId = resolvedSkillId,
                eventType = ToolAuditEventType.ToolRejected.name,
                status = result.status.name,
                riskLevel = spec?.riskLevel?.name,
                permissionsCsv = permissions
                    .map { it.name }
                    .sorted()
                    .joinToString(separator = ","),
                summary = result.summary,
            ),
        )
    }

    private fun toolRequestFor(runId: String, requestId: String): ToolRequest? =
        toolRequestsFor(runId).firstOrNull { request -> request.id == requestId }

    private fun latestUnverifiedExternalResult(runId: String, requestId: String): ToolResult? =
        traceStore.steps(runId)
            .asReversed()
            .asSequence()
            .mapNotNull { step -> (step as? AgentStep.ToolObserved)?.result }
            .firstOrNull { result ->
                result.requestId == requestId && result.isUnverifiedExternalLaunch()
            } ?: traceStore.stepSummaries(runId)
            .asReversed()
            .asSequence()
            .filter { step -> step.type == "ToolObserved" }
            .mapNotNull { step -> pendingConfirmationSupport.restoreUnverifiedExternalResult(step) }
            .firstOrNull { result -> result.requestId == requestId }

    private fun latestExternalOutcomeConfirmation(runId: String, requestId: String): AgentStep.ExternalOutcomeConfirmed? =
        traceStore.steps(runId)
            .asReversed()
            .asSequence()
            .mapNotNull { step -> step as? AgentStep.ExternalOutcomeConfirmed }
            .firstOrNull { step -> step.requestId == requestId }
            ?: traceStore.stepSummaries(runId)
                .asReversed()
                .asSequence()
                .filter { step -> step.type == "ExternalOutcomeConfirmed" }
                .firstOrNull { step -> step.requestIdFromJson() == requestId }
                ?.let { step ->
                    AgentStep.ExternalOutcomeConfirmed(
                        requestId = requestId,
                        outcome = AgentExternalOutcome.OpenedOnly,
                        result = ToolResult(
                            requestId = requestId,
                            status = ToolStatus.Succeeded,
                            summary = step.summary,
                        ),
                    )
                }

    private fun toolRequestsFor(runId: String): List<ToolRequest> =
        traceStore.steps(runId)
            .asSequence()
            .mapNotNull { step -> (step as? AgentStep.ToolRequested)?.request }
            .toList()
            .let { liveRequests ->
                val liveIds = liveRequests.mapTo(mutableSetOf()) { request -> request.id }
                liveRequests + traceStore.stepSummaries(runId)
                    .asSequence()
                    .filter { step -> step.type == "ToolRequested" }
                    .mapNotNull { step -> pendingConfirmationSupport.restoreToolRequest(step) }
                    .filterNot { request -> request.id in liveIds }
                    .toList()
            }

    private fun plannedSequentialSegmentCount(runId: String): Int {
        val segmentKeys = linkedSetOf<String>()
        val liveSkillKeysByToolRequestId = mutableMapOf<String, String>()
        var adjacentLiveSkillSegmentKey: String? = null
        traceStore.steps(runId).forEach { step ->
            when (step) {
                is AgentStep.SkillPlanned -> {
                    val skillKey = "skill:${step.request.id}"
                    val toolRequestIds = step.plan?.toolStepRequestIds().orEmpty()
                    if (toolRequestIds.isEmpty()) {
                        adjacentLiveSkillSegmentKey = skillKey
                    } else {
                        toolRequestIds.forEach { requestId ->
                            liveSkillKeysByToolRequestId[requestId] = skillKey
                        }
                        adjacentLiveSkillSegmentKey = null
                    }
                }

                is AgentStep.ToolRequested -> {
                    segmentKeys += liveSkillKeysByToolRequestId.remove(step.request.id)
                        ?: adjacentLiveSkillSegmentKey
                        ?: "tool:${step.request.id}"
                    adjacentLiveSkillSegmentKey = null
                }

                else -> Unit
            }
        }
        val summarySkillKeysByToolRequestId = mutableMapOf<String, String>()
        var adjacentSummarySkillSegment: SummarySkillSegment? = null
        traceStore.stepSummaries(runId).forEach { step ->
            when (step.type) {
                "SkillPlanned" -> {
                    val skillKey = step.skillRequestIdFromJson()?.let { requestId -> "skill:$requestId" }
                    if (skillKey == null) {
                        adjacentSummarySkillSegment = null
                    } else {
                        val toolRequestIds = step.skillToolRequestIdsFromJson()
                        if (toolRequestIds.isEmpty()) {
                            val fallbackToolStepCount = step.skillToolStepCountFromJson() ?: 1
                            adjacentSummarySkillSegment = SummarySkillSegment(
                                key = skillKey,
                                remainingToolSteps = fallbackToolStepCount.coerceAtLeast(1),
                            )
                        } else {
                            toolRequestIds.forEach { requestId ->
                                summarySkillKeysByToolRequestId[requestId] = skillKey
                            }
                            adjacentSummarySkillSegment = null
                        }
                    }
                }

                "ToolRequested" -> {
                    step.requestIdFromJson()?.let { requestId ->
                        val skillKey = summarySkillKeysByToolRequestId.remove(requestId)
                        segmentKeys += skillKey
                            ?: adjacentSummarySkillSegment?.key
                            ?: "tool:$requestId"
                        if (skillKey == null) {
                            adjacentSummarySkillSegment = adjacentSummarySkillSegment?.afterTool()
                        }
                    }
                }
            }
        }
        return segmentKeys.size
    }

    private fun latestPendingToolRequest(runId: String): ToolRequest? =
        traceStore.steps(runId).asReversed().asSequence()
            .mapNotNull { step -> (step as? AgentStep.UserConfirmationRequested)?.request }
            .firstOrNull()

    private fun pendingToolRequest(runId: String, requestId: String): ToolRequest? {
        val restoredSnapshot = traceStore.latestPendingConfirmation()
            ?.takeIf { snapshot -> snapshot.run.id == runId && snapshot.request.id == requestId }
        if (restoredSnapshot != null) {
            if (!pendingConfirmationSupport.restoredPendingConfirmationIsAuthorized(restoredSnapshot)) return null
            pendingConfirmationSupport.rememberValueFreeFrontier(restoredSnapshot)
        }
        val liveRequest = latestPendingToolRequest(runId)
            ?.takeIf { request -> request.id == requestId }
        return liveRequest ?: restoredSnapshot?.request
    }

    private fun latestExecutableRequestId(runId: String): String? =
        traceStore.steps(runId)
            .asReversed()
            .asSequence()
            .mapNotNull { step ->
                when (step) {
                    is AgentStep.UserConfirmed -> step.requestId
                    is AgentStep.ToolRetryScheduled -> step.request.id
                    is AgentStep.ToolRequested -> step.request.id
                    else -> null
                }
            }
            .firstOrNull()

    private fun latestObservationDecision(runId: String): AgentObservationDecision? =
        traceStore.steps(runId).asReversed().asSequence()
            .mapNotNull { step -> (step as? AgentStep.ObservationDecided)?.decision }
            .firstOrNull()

    private fun latestPlanToolBatch(runId: String): AgentObservationDecision.PlanToolBatch? =
        latestObservationDecision(runId) as? AgentObservationDecision.PlanToolBatch

    private fun skillIdForRequest(runId: String, requestId: String): String? {
        val steps = traceStore.steps(runId)
        val requestIndex = steps.indexOfFirst { step ->
            step is AgentStep.ToolRequested && step.request.id == requestId
        }
        if (requestIndex < 0) return null
        val previousToolIndex = steps.subList(0, requestIndex).indexOfLast { step ->
            step is AgentStep.ToolRequested
        }
        return steps
            .subList(previousToolIndex + 1, requestIndex)
            .asSequence()
            .mapNotNull { step -> (step as? AgentStep.SkillPlanned)?.request?.skillId }
            .lastOrNull()
    }

    private fun latestSkillPlan(runId: String): SkillPlan? =
        traceStore.steps(runId).asSequence()
            .mapNotNull { step -> (step as? AgentStep.SkillPlanned)?.plan }
            .lastOrNull()

    private fun latestLowRiskAppControlSkillPlan(runId: String): SkillPlan? =
        traceStore.steps(runId).asSequence()
            .mapNotNull { step -> (step as? AgentStep.SkillPlanned)?.plan }
            .filter { plan -> plan.isLowRiskAppControlSkill() }
            .lastOrNull()

    private fun latestModelDrivenAppSearchSkillPlan(runId: String): SkillPlan? =
        traceStore.steps(runId).asSequence()
            .mapNotNull { step -> (step as? AgentStep.SkillPlanned)?.plan }
            .filter { plan -> plan.isModelDrivenAppSearchSkill() }
            .lastOrNull()

    private fun AgentTraceStepSummary.requestIdFromJson(): String? =
        jsonObjectOrNull()?.optString("requestId")?.takeIf { it.isNotBlank() }

    private fun AgentTraceStepSummary.skillRequestIdFromJson(): String? =
        jsonObjectOrNull()?.optString("skillRequestId")?.takeIf { it.isNotBlank() }

    private fun AgentTraceStepSummary.skillToolRequestIdsFromJson(): List<String> {
        val array = jsonObjectOrNull()?.optJSONArray("toolRequestIds") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { value -> value.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun AgentTraceStepSummary.skillToolStepCountFromJson(): Int? {
        val json = jsonObjectOrNull() ?: return null
        return json.optInt("toolStepCount", -1).takeIf { it >= 0 }
            ?: json.optInt("stepCount", -1).takeIf { it >= 0 }
    }

    private fun AgentTraceStepSummary.jsonObjectOrNull(): JSONObject? =
        runCatching { JSONObject(json) }.getOrNull()

    private data class SummarySkillSegment(val key: String, val remainingToolSteps: Int) {
        fun afterTool(): SummarySkillSegment? =
            if (remainingToolSteps <= 1) null else copy(remainingToolSteps = remainingToolSteps - 1)
    }

    private val AgentExternalOutcome.metadataValue: String
        get() = when (this) {
            AgentExternalOutcome.Completed -> "Completed"
            AgentExternalOutcome.NotCompleted -> "NotCompleted"
            AgentExternalOutcome.OpenedOnly -> "OpenedOnly"
        }

    private val AgentExternalOutcome.completionVerified: Boolean
        get() = this == AgentExternalOutcome.Completed

    private val AgentExternalOutcome.userFacingLabel: String
        get() = when (this) {
            AgentExternalOutcome.Completed -> "目标应用中的操作已完成"
            AgentExternalOutcome.NotCompleted -> "目标应用中的操作未完成"
            AgentExternalOutcome.OpenedOnly -> "只确认外部界面已打开"
        }

    /**
     * After safety checks pass and the plan has been appended to the trace, if the plan is an
     * `ask_user` tool call, append the [AgentStep.UserQuestionAsked] step, publish the
     * [SolinEvent.Agent.UserQuestionAsked] event, and remember the pending question state so
     * [answerUserQuestion] / [cancelUserQuestion] can correlate a reply back to the originating
     * ToolRequest. Returns `true` if interception occurred (i.e. the plan was ask_user).
     *
     * NOTE: ask_user is NEVER executed by the tool executor — intercepting here (after safety,
     * before transitioning to ExecutingTool) prevents the ViewModel from ever seeing an
     * ExecutingTool state that would trigger executor.execute. The "tool result" is synthesized
     * by answerUserQuestion/cancelUserQuestion and fed back through [observeToolResultInternal].
     * Callers are responsible for transitioning run state to [AgentRunState.AwaitingUserAnswer]
     * themselves (typically via [AgentPlan.UseTool.nextExecutionState]).
     */
    private fun parkForAskUserIfNeeded(
        runId: String,
        plan: AgentPlan.UseTool,
    ): Boolean {
        if (plan.request.toolName != MobileActionFunctions.ASK_USER) return false
        val prompt = plan.request.arguments["prompt"]?.takeIf { it.isNotBlank() }
            ?: return false // malformed call; let normal execution reject it
        val choices = parseAskUserChoices(plan.request.arguments)
        val questionId = java.util.UUID.randomUUID().toString()
        traceStore.appendStep(
            runId,
            AgentStep.UserQuestionAsked(
                questionId = questionId,
                prompt = prompt,
                choices = choices,
            ),
        )
        eventBus.publish(
            SolinEvent.Agent.UserQuestionAsked(
                runId = runId,
                questionId = questionId,
                prompt = prompt,
                choices = choices,
            ),
        )
        pendingUserQuestionsByRunId[runId] = PendingUserQuestionState(
            questionId = questionId,
            request = plan.request,
        )
        // Wave 4 telemetry: count ask_user interception (question parked in AwaitingUserAnswer).
        safeRecordTelemetry(
            MetricSample.CounterInc(
                name = TelemetryCounter.AskUserQuestions,
                runId = runId,
            ),
            "AskUserQuestions counter",
        )
        return true
    }

    private fun parkForTakeOver(runId: String, prompt: String?, result: ToolResult) {
        val takeOverPrompt = prompt?.takeIf { it.isNotBlank() } ?: "请完成需要人工操作的步骤，完成后告诉我继续。"
        val questionId = java.util.UUID.randomUUID().toString()
        traceStore.appendStep(runId, AgentStep.UserQuestionAsked(questionId = questionId, prompt = takeOverPrompt, choices = listOf("已完成，继续")))
        eventBus.publish(SolinEvent.Agent.UserQuestionAsked(runId = runId, questionId = questionId, prompt = takeOverPrompt, choices = listOf("已完成，继续")))
        pendingUserQuestionsByRunId[runId] = PendingUserQuestionState(questionId = questionId, request = ToolRequest(toolName = MobileActionFunctions.TAKE_OVER, reason = "take_over parking"))
        safeRecordTelemetry(MetricSample.CounterInc(name = TelemetryCounter.AskUserQuestions, runId = runId), "TakeOver parking")
    }

    // -----------------------------------------------------------------------
    // Wave 4 telemetry + hook safety helpers.
    //
    // All hook invocations and telemetry recordings MUST go through these
    // wrappers so a throwing hook implementation or sink cannot crash the
    // agent run; failures are logged with Log.e and safe defaults are used.
    // -----------------------------------------------------------------------

    private inline fun <T> safeHookCall(
        label: String,
        default: T,
        block: () -> T,
    ): T = runCatching(block).getOrElse { throwable ->
        Log.e(TAG, "Agent hook '$label' failed; using safe default", throwable)
        default
    }

    private fun safeRecordTelemetry(
        sample: MetricSample,
        label: String,
    ) {
        runCatching { telemetrySink.record(sample) }.onFailure { throwable ->
            Log.e(TAG, "Telemetry sink failed to record '$label'", throwable)
        }
    }

    private fun recordStepLatency(stepType: String, runId: String?) {
        val start = stepStartedAtMillis.remove(stepType) ?: return
        val latency = System.currentTimeMillis() - start
        safeRecordTelemetry(
            MetricSample.StepLatency(
                stepType = stepType,
                latencyMs = latency,
                runId = runId,
            ),
            "StepLatency:$stepType",
        )
    }

    private fun recordVerboseTrace(runId: String, thinkText: String? = null, actionSummary: String? = null, actionToolName: String? = null, observationSummary: String? = null) {
        runCatching {
            val stepIndex = traceStore.steps(runId).size
            traceStore.appendVerboseTrace(runId, VerboseTraceEntry(stepIndex = stepIndex, thinkText = thinkText, actionSummary = actionSummary, actionToolName = actionToolName, observationSummary = observationSummary))
        }.onFailure { Log.e(TAG, "Verbose trace recording failed", it) }
    }

    fun scratchpadForPrompt(runId: String): String? = scratchpad.formatForPrompt(runId)

    private fun String.boundedPromptValue(maxLength: Int = 2_000): String =
        com.bytedance.zgx.solin.evidence.EvidenceBounds.headTail(
            text = this,
            maxChars = maxLength,
            sourceType = com.bytedance.zgx.solin.evidence.EvidenceSourceType.ToolResult,
            privacy = com.bytedance.zgx.solin.MessagePrivacy.LocalOnly,
            store = evidenceBlobStore,
        ).text

    private fun String.compactAuditValue(maxLength: Int): String =
        trim().replace(auditWhitespaceRegex, " ").boundedPromptValue(maxLength)
}

private val auditWhitespaceRegex = Regex("\\s+")
