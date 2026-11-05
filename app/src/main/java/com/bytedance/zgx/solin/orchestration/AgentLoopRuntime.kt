package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.publicWebEvidencePacksFromToolResults
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.IntentRoutingDecision
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.audit.NoOpToolAuditSink
import com.bytedance.zgx.solin.audit.ToolAuditEventType
import com.bytedance.zgx.solin.audit.ToolAuditSink
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.evidence.EvidenceBlobStore
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import com.bytedance.zgx.solin.plan.SessionPlanStore
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.skill.SkillRunProgressor
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
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

private const val RUN_CANCELLED_REASON = "Agent run was cancelled by the user."
private const val PENDING_EXTERNAL_OUTCOME_RESTORE_RUN_LIMIT = 20
private const val TAG = "AgentLoopRuntime"

/**
 * Attempt cap for [AgentLoopRuntime.terminateRun]'s compare-and-set retry loop.
 *
 * Sized for genuine concurrent contention (a handful of racing transitions), not for a store whose
 * views permanently disagree — in that case the loop must give up and log rather than spin.
 */
private const val MAX_TERMINATE_RUN_CAS_ATTEMPTS = 8
private const val TERMINATE_RUN_BACKOFF_STEP_MILLIS = 5L
private const val TERMINATE_RUN_BACKOFF_MAX_MILLIS = 40L

/**
 * Backoff between contended CAS attempts in [AgentLoopRuntime.terminateRun].
 *
 * Sleeps rather than spin-hints. Each attempt costs two disk reads, so a sub-microsecond
 * `onSpinWait`/`yield` let all attempts elapse within single-digit milliseconds — a competitor
 * blocked on Room I/O for longer than that would exhaust the budget without ever getting the
 * chance to finish its own transition. Growing the wait keeps the total bounded (8 attempts ≈
 * 180ms) while actually leaving room for the other writer.
 */
private fun terminateRunBackoff(attempt: Int) {
    runCatching {
        Thread.sleep((TERMINATE_RUN_BACKOFF_STEP_MILLIS * attempt).coerceAtMost(TERMINATE_RUN_BACKOFF_MAX_MILLIS))
    }
}

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
    private val memoryRetrieval: MemoryRetrieval = MemoryRetrieval(memoryIndex),
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

    private val skillProgressor = SkillRunProgressor(toolRegistry = toolRegistry)
    // Per-run maps live in one holder so terminal cleanup cannot miss one; the aliases below keep the
    // call sites unchanged.
    private val runScopedState = RunScopedState()
    private val traceQueries = AgentTraceQueries(traceStore)
    private val auditRecorder = ToolAuditRecorder(toolRegistry, auditSink, eventBus, traceQueries)
    private val runEvents = RunTransitionEventPublisher(eventBus, runScopedState) { runId, stepType ->
        markStepStart(runId, stepType)
    }
    private val valueFreeCompletedStepFrontiersByRunId = runScopedState.valueFreeCompletedStepFrontiersByRunId
    private val remoteToolScopesByRunId = runScopedState.remoteToolScopesByRunId
    private val remoteExposedToolNamesByRunId = runScopedState.remoteExposedToolNamesByRunId
    private val lowRiskDeviceActionConfirmationBypassByRunId = runScopedState.lowRiskDeviceActionConfirmationBypassByRunId
    private val remoteGuiDrivingEnabledByRunId = runScopedState.remoteGuiDrivingEnabledByRunId
    private val installedCapabilityProfilesByRunId = runScopedState.installedCapabilityProfilesByRunId
    private val scratchpad = com.bytedance.zgx.solin.memory.PerRunScratchpad()

    // Per-run wall-clock start time for lifecycle events. Declared ahead of [runBudget] because the
    // budget's wall-clock deadline reads it (a property initializer may not forward-reference).
    private val runStartedAtMillis = runScopedState.runStartedAtMillis

    /**
     * Per-run accounting of time spent parked on a user or an external app, so the wall-clock
     * deadline bills the agent's own work rather than the user's response time.
     *
     * Sampled lazily by [parkedMillisFor] instead of hooked into every state transition: runs enter
     * the awaiting states from a dozen call sites, and instrumenting each one is what makes this
     * kind of bookkeeping drift. Reading the run's current state and its `updatedAtMillis` at
     * budget-check time needs no such coverage — while a run sits parked, `updatedAtMillis` is the
     * moment it parked, so the parked stretch is directly measurable.
     */
    private val parkedMillisByRunId = runScopedState.parkedMillisByRunId
    private val lastParkSampleAtMillis = runScopedState.lastParkSampleAtMillis

    /**
     * Total parked milliseconds for [runId], advancing the accumulator when the run is parked now.
     *
     * Each call adds the stretch since the previous sample if the run is still in an awaiting state,
     * so repeated budget checks across a long park accumulate the full duration without needing an
     * "unparked" callback. `updatedAtMillis` bounds the first sample so time before the park is
     * never counted.
     */
    private fun parkedMillisFor(runId: String): Long {
        val run = traceStore.run(runId) ?: return parkedMillisByRunId[runId] ?: 0L
        val now = System.currentTimeMillis()
        if (run.state !in parkedRunStates) {
            lastParkSampleAtMillis.remove(runId)
            return parkedMillisByRunId[runId] ?: 0L
        }
        val since = lastParkSampleAtMillis[runId] ?: run.updatedAtMillis
        val delta = (now - since).coerceAtLeast(0L)
        lastParkSampleAtMillis[runId] = now
        return parkedMillisByRunId.merge(runId, delta) { a, b -> a + b } ?: delta
    }

    private val runBudget = AgentRunBudget(
        maxRunToolSteps = maxRunToolSteps,
        maxObservationDecisions = maxObservationDecisions,
        toolRequestsFor = traceQueries::toolRequestsFor,
        observationDecidedCount = { runId ->
            traceStore.steps(runId).count { step -> step is AgentStep.ObservationDecided }
        },
        sessionPlanStore = sessionPlanStore,
        // Both counts above are derived from traceStore.steps(); when that read degrades the counts
        // silently reset to zero, so the budget must know and fail closed instead of reopening the
        // anti-runaway gate. See AgentTraceStore.stepHistoryDegraded.
        stepHistoryDegraded = { runId -> traceStore.stepHistoryDegraded(runId) },
        // runStartedAtMillis is otherwise statistics-only; feeding it to the budget turns it into the
        // run-level wall-clock deadline (step counts alone cannot bound an untimed model generation).
        runStartedAtMillis = { runId -> runStartedAtMillis[runId] },
        runParkedMillis = ::parkedMillisFor,
        compositeStepCount = { runScopedState.compositeStepsByRunId[it] ?: 0 },
    )
    private val initialToolPlanner = InitialToolPlanner(
        toolRegistry = toolRegistry,
        skillRuntime = skillRuntime,
        safetyPolicy = safetyPolicy,
        actionPlanningRuntime = actionPlanningRuntime,
        traceStore = traceStore,
    )
    private val confirmationBypassPolicy = ConfirmationBypassPolicy(
        toolRegistry = toolRegistry,
        traceStore = traceStore,
        traceQueries = traceQueries,
        isCriticalOnlyConfirmationEnabled = { runId -> lowRiskDeviceActionConfirmationBypassByRunId[runId] == true },
        safetyPolicy = safetyPolicy,
        // Remote-vision runs must confirm one step before their first screenshot leaves.
        isRemoteVisionDrivingEnabled = { runId -> remoteGuiDrivingEnabledByRunId[runId] == true },
    )
    private val publicEvidenceContinuation = PublicEvidenceContinuation(
        toolRegistry = toolRegistry,
        traceStore = traceStore,
        traceQueries = traceQueries,
        evidenceBlobStore = evidenceBlobStore,
    )
    private val pendingConfirmationSupport = PendingConfirmationSupport(
        toolRegistry = toolRegistry,
        traceStore = traceStore,
        traceQueries = traceQueries,
        initialToolPlanner = initialToolPlanner,
        valueFreeCompletedStepFrontiersByRunId = valueFreeCompletedStepFrontiersByRunId,
        host = object : PendingConfirmationSupport.Host {
            override fun clearEphemeralRunState(runId: String) =
                this@AgentLoopRuntime.clearEphemeralRunState(runId)
        },
        auditRecorder = auditRecorder,
    )

    private val toolPlanCoordinator = ToolPlanCoordinator(
        toolRegistry = toolRegistry,
        skillRuntime = skillRuntime,
        skillProgressor = skillProgressor,
        observationReplanner = observationReplanner,
        actionPlanningRuntime = actionPlanningRuntime,
        traceStore = traceStore,
        traceQueries = traceQueries,
        safetyPolicy = safetyPolicy,
        runBudget = runBudget,
        initialToolPlanner = initialToolPlanner,
        confirmationBypassPolicy = confirmationBypassPolicy,
        host = object : ToolPlanCoordinator.Host {
            override fun installedCapabilityProfiles(runId: String): List<ModelCapabilityProfile> =
                installedCapabilityProfilesByRunId[runId].orEmpty()

            override fun remoteGuiDrivingEnabled(runId: String): Boolean =
                remoteGuiDrivingEnabledByRunId[runId] ?: false

            override fun localScratchpadNotes(runId: String): List<String> =
                scratchpadNotesFromPromptBlock(runCatching { scratchpad.formatForPrompt(runId) }.getOrNull())

            // First-confirm-then-auto, in BOTH confirmation modes: the remote-GUI opt-in is not a
            // per-run approval, so a screenshot may leave only after the user confirmed a step of
            // this run. Critical-only mode rarely prompts, so such a run usually has no confirmed
            // step and the remote-vision path stays closed (the local action model takes over).
            override fun runHasUserConfirmedStep(runId: String): Boolean =
                traceStore.steps(runId).any { step -> step is AgentStep.UserConfirmed }

            override fun valueFreeCompletedStepFrontiers(runId: String): Set<String> =
                valueFreeCompletedStepFrontiersByRunId[runId].orEmpty()
        },
        auditRecorder = auditRecorder,
    )

    // Eager: every dependency above is initialized by now.
    private val toolObservationContinuationBuilder = ToolObservationContinuationBuilder(
        toolRegistry = toolRegistry,
        toolPlanCoordinator = toolPlanCoordinator,
        initialToolPlanner = initialToolPlanner,
        skillProgressor = skillProgressor,
        traceQueries = traceQueries,
        publicEvidenceContinuation = publicEvidenceContinuation,
        scratchpad = scratchpad,
        evidenceBlobStore = evidenceBlobStore,
    )

    private val toolObservationCoordinator = ToolObservationCoordinator(
        routeObserver = (observationReplanner as? VerifiedPhoneRouteReplanner)?.let { routes -> routes::observe },
        toolRegistry = toolRegistry,
        auditRecorder = auditRecorder,
        runEvents = runEvents,
        eventBus = eventBus,
        traceStore = traceStore,
        traceQueries = traceQueries,
        toolPlanCoordinator = toolPlanCoordinator,
        runBudget = runBudget,
        maxToolRetryAttempts = maxToolRetryAttempts,
        confirmationBypassPolicy = confirmationBypassPolicy,
        pendingConfirmationSupport = pendingConfirmationSupport,
        publicEvidenceContinuation = publicEvidenceContinuation,
        continuationBuilder = toolObservationContinuationBuilder,
        host = object : ToolObservationCoordinator.Host {
            override fun hasPendingUserInput(runId: String): Boolean =
                runScopedState.pendingUserInputByRunId[runId] == true

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

            override fun clearEphemeralRunState(runId: String) {
                this@AgentLoopRuntime.clearEphemeralRunState(runId)
            }

            override fun safeRecordTelemetry(sample: MetricSample, label: String) {
                this@AgentLoopRuntime.safeRecordTelemetry(sample, label)
            }

            override fun recordStepLatency(stepType: String, runId: String?) {
                this@AgentLoopRuntime.recordStepLatency(stepType, runId)
            }

            override fun failInitialPlanBudget(run: AgentRun, request: ToolRequest): AgentLoopResult =
                this@AgentLoopRuntime.failInitialPlanBudget(run, request)

            override fun addScratchpadNote(runId: String, noteContent: String) {
                runCatching { scratchpad.addNote(runId, noteContent) }
                    .onFailure { Log.e(TAG, "Scratchpad note failed", it) }
            }

            override fun markToolDispatchStarted(requestId: String) {
                toolDispatchStartedAtMillis[requestId] = System.currentTimeMillis()
            }

            override fun consumeToolDispatchLatencyMs(requestId: String): Long {
                val toolStartMs = toolDispatchStartedAtMillis.remove(requestId)
                return toolStartMs?.let { System.currentTimeMillis() - it } ?: 0L
            }

            override fun setRemoteToolScope(runId: String, scope: RemoteToolScope) {
                remoteToolScopesByRunId[runId] = scope
            }
        },
    )

    private val modelToolRequestCoordinator = ModelToolRequestCoordinator(
        toolRegistry = toolRegistry,
        skillRuntime = skillRuntime,
        safetyPolicy = safetyPolicy,
        traceStore = traceStore,
        traceQueries = traceQueries,
        toolObservationCoordinator = toolObservationCoordinator,
        runBudget = runBudget,
        initialToolPlanner = initialToolPlanner,
        confirmationBypassPolicy = confirmationBypassPolicy,
        pendingConfirmationSupport = pendingConfirmationSupport,
        host = object : ModelToolRequestCoordinator.Host {
            override fun rejectRemoteToolIfNotExposedInCurrentScope(runId: String, request: ToolRequest): ToolResult? =
                this@AgentLoopRuntime.rejectRemoteToolIfNotExposedInCurrentScope(runId, request)

            override fun failRunBudget(runId: String, reason: String): AgentModelObservationResult? =
                this@AgentLoopRuntime.failRunBudget(runId, reason)

            override fun publishRunFailed(runId: String, code: AgentErrorCode, message: String) =
                this@AgentLoopRuntime.publishRunFailed(runId, code, message)

            override fun parkForAskUserIfNeeded(runId: String, plan: AgentPlan.UseTool): Boolean =
                this@AgentLoopRuntime.parkForAskUserIfNeeded(runId, plan)

            override fun clearEphemeralRunState(runId: String) =
                this@AgentLoopRuntime.clearEphemeralRunState(runId)

            override fun markStepStart(runId: String, stepType: String) =
                this@AgentLoopRuntime.markStepStart(runId, stepType)
        },
        auditRecorder = auditRecorder,
    )

    // Per-run monotonic turn counter for lifecycle events.
    private val runTurnIndex = runScopedState.runTurnIndex

    // Mid-run steering: steer batches are drained before queued batches at every drain point, and
    // steer() also cancels the registered in-flight model-call Job so the next turn starts against
    // the new direction. Tool execution is NEVER cancelled by steer (safety-critical device actions
    // must run to completion); batches arriving during ExecutingTool/RetryingTool wait on the
    // channel until the tool's result is observed.
    //
    // Producers are live via ChatController (presentation/ChatMidRunSteering): it registers model
    // streaming Jobs, steers/queues a LocalOnly copy of mid-run input and re-sends the user's
    // message through the normal send pipeline once the run yields. The channels only carry the
    // pending marker (see pendingUserInputByRunId); nothing here injects them into a prompt.
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

    // Wall-clock time a ToolRequested step was appended, keyed by requestId, so ToolCompleted telemetry can record latencyMs. Entries are cleared when the
    // corresponding tool result is observed so the map stays bounded by in-flight tool count.
    private val toolDispatchStartedAtMillis = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // (runId, stepType) -> last started-at epoch millis, so StepLatency samples can
    // record deltas when the next step boundary arrives. Keyed per run: concurrent runs (e.g.
    // subagents) would otherwise clobber each other's start marker and drop or mis-measure samples.
    private val stepStartedAtMillis = runScopedState.stepStartedAtMillis

    /**
     * Creates the trace run, falling back to an ephemeral in-memory run when the trace store fails
     * (e.g. a Room error on a fresh install). The ephemeral run is not persisted, but all in-memory
     * bookkeeping still works so the route can proceed.
     */
    private fun createRunOrEphemeral(input: String, sessionId: String?): AgentRun =
        runCatching { traceStore.createRun(input, sessionId) }
            .getOrElse { throwable ->
                val now = System.currentTimeMillis()
                AgentRun(
                    id = "ephemeral-${now}-${(0..Int.MAX_VALUE).random()}",
                    input = input,
                    state = AgentRunState.Created,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                    sessionId = sessionId,
                ).also { run ->
                    Log.w(TAG, "traceStore.createRun failed, using ephemeral run ${run.id}: ${throwable.message}")
                }
            }

    /**
     * Fails a run whose initial plan was rejected before any tool ran: records the rejection,
     * audits it, moves the run to Failed with a Validation RunFailed event, and drops per-run state.
     * [failedStepMessage], when set, is also appended as an [AgentStep.Failed] trace step.
     */
    private fun failInitialPlanValidation(
        runId: String,
        plan: AgentPlan.RejectedTool,
        failedStepMessage: String?,
    ): AgentLoopResult {
        traceStore.appendStep(runId, AgentStep.ModelPlanned(plan))
        traceStore.appendStep(runId, AgentStep.ToolRejected(plan.result))
        auditRecorder.rejected(runId, plan.result)
        failedStepMessage?.let { message -> traceStore.appendStep(runId, AgentStep.Failed(message)) }
        val failedRun = traceStore.updateState(runId, AgentRunState.Failed)
        eventBus.publish(
            SolinEvent.Agent.RunFailed(
                runId = runId,
                code = AgentErrorCode.Validation,
                message = failedStepMessage ?: plan.result.summary,
            ),
        )
        clearEphemeralRunState(runId)
        return AgentLoopResult(
            run = failedRun,
            plan = plan,
            steps = traceStore.steps(runId),
        )
    }

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
        val createdRun = createRunOrEphemeral(input, sessionId)
        com.bytedance.zgx.solin.device.background.BackgroundPhoneSession.captureForRun(createdRun.id)
        val startedAt = System.currentTimeMillis()
        runStartedAtMillis[createdRun.id] = startedAt
        runTurnIndex[createdRun.id] = 0
        remoteToolScopesByRunId[createdRun.id] = options.remoteToolScope
        lowRiskDeviceActionConfirmationBypassByRunId[createdRun.id] = options.reduceDeviceActionConfirmations
        remoteGuiDrivingEnabledByRunId[createdRun.id] = options.remoteGuiDrivingEnabled
        installedCapabilityProfilesByRunId[createdRun.id] = installedCapabilityProfiles.toList()
        traceStore.updateState(createdRun.id, AgentRunState.LoadingContext)
        eventBus.publish(
            SolinEvent.Agent.RunStarted(
                runId = createdRun.id,
                modelLabel = actionModelPath ?: "local",
                inputText = input,
            ),
        )
        eventBus.publish(
            SolinEvent.Agent.TurnStarted(
                runId = createdRun.id,
                turnIndex = 0,
                occurredAtMillis = startedAt,
            ),
        )
        val memoryHits = memoryRetrieval.retrieve(input, memoryEnabled).hits
        traceStore.appendStep(createdRun.id, AgentStep.ContextLoaded(memoryHits, deviceContext))
        traceStore.updateState(createdRun.id, AgentRunState.Planning)

        val initialToolPlan = when (options.initialPlanningMode) {
            InitialPlanningMode.RuleFirst -> initialToolPlanner.planToolIfSupported(
                input = input,
                installedCapabilityProfiles = installedCapabilityProfiles,
                actionModelPath = actionModelPath,
                remoteGuiDrivingEnabled = options.remoteGuiDrivingEnabled,
            )
            InitialPlanningMode.ModelFirstRemoteTools -> initialToolPlanner.planLocalOnlySkillBeforeRemote(input)
        }
        when (initialToolPlan) {
            is AgentPlan.UseTool -> {
                return toolObservationCoordinator.requestToolConfirmation(createdRun, initialToolPlan)
            }

            is AgentPlan.RejectedTool -> {
                return failInitialPlanValidation(createdRun.id, initialToolPlan, failedStepMessage = null)
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
        markStepStart(createdRun.id, "model_generation")
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
        if (run.state.isTerminal()) return null
        // Drop any queued steer/queued messages for a run that is being torn down. We cannot
        // selectively purge from an UNLIMITED Channel, so drain into a discard sink by runId.
        // Residual batches will naturally be ignored by any later drain call because the run is
        // terminal (drain methods check state before using messages), but clearing the tracker map
        // below ensures no model-call-Job remains cancellable after cancellation.
        cancelAndClearModelCallJob(runId)
        if (run.state == AgentRunState.AwaitingUserConfirmation) {
            traceQueries.latestPendingToolRequest(runId)?.let { request ->
                val cancelled = cancelToolRequest(runId, request.id) ?: return null
                return AgentModelObservationResult(
                    run = cancelled.run,
                    decision = cancelled.decision,
                    steps = cancelled.steps,
                )
            }
            traceStore.clearPendingConfirmationsForRun(runId)
        } else if (run.state == AgentRunState.AwaitingUserAnswer) {
            // Read, do not remove: cancelUserQuestion looks the pending question up itself (and
            // removes it once the cancellation has been observed).
            val pending = pendingUserQuestionsByRunId[runId]
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
        val decision = AgentObservationDecision.Cancel
        // Bounded CAS retry, "read once, decide, then update" per iteration. The previous
        // `while (updatedRun == null)` loop was unbounded and spun with no backoff while doing two
        // disk reads per iteration: RoomAgentTraceStore.compareAndSetState can fail *persistently*
        // (not just transiently) when its DB and in-memory views disagree about the current state,
        // which turns "retry until success" into a real infinite loop that also hammers the DB.
        //
        // Nothing destructive happens before the CAS wins. Cancelling the model job, dropping the
        // pending confirmation row and forgetting the pending question used to run up here, so the
        // give-up path left a NON-terminal run whose pending-confirmation row was already gone —
        // and `awaitingWithoutPendingRuns` then failed it as UNRESTORABLE on the next process start,
        // showing the user a spurious "cannot be restored" instead of their own cancellation.
        var updatedRun: AgentRun? = null
        var attempt = 0
        while (updatedRun == null && attempt < MAX_TERMINATE_RUN_CAS_ATTEMPTS) {
            attempt++
            val run = traceStore.run(runId) ?: return null
            if (run.state.isTerminal()) return null
            updatedRun = traceStore.compareAndSetState(
                runId = runId,
                expectedState = run.state,
                state = AgentRunState.Cancelled,
            )
            if (updatedRun == null) terminateRunBackoff(attempt)
        }
        if (updatedRun == null) {
            runCatching {
                Log.e(
                    TAG,
                    "terminateRun($runId) gave up after $MAX_TERMINATE_RUN_CAS_ATTEMPTS CAS attempts; " +
                        "run state is contended or the trace store's persisted/live views disagree",
                )
            }
            return null
        }
        cancelAndClearModelCallJob(runId)
        traceStore.clearPendingConfirmationsForRun(runId)
        pendingUserQuestionsByRunId.remove(runId)
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

    fun deleteRunsForSession(sessionId: String): Int {
        val runIds = traceStore.recentRunSummaries(limit = Int.MAX_VALUE, stepLimit = 0)
            .asSequence()
            .map { summary -> summary.run }
            .filter { run -> run.sessionId == sessionId }
            .map { run -> run.id }
            .toList()
        runIds.forEach { runId -> terminateRun(runId, RUN_DELETED_SESSION_REASON) }
        val deletedCount = traceStore.deleteRunsForSession(sessionId)
        (observationReplanner as? VerifiedPhoneRouteReplanner)?.clear()
        runIds.forEach(runScopedState::forgetRun)
        runIds.forEach(com.bytedance.zgx.solin.device.background.BackgroundPhoneSession::forgetRun)
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
        if (run.state.isTerminal()) return false
        val enqueued = steerMessages.trySend(PendingMessageBatch(id, messages)).isSuccess
        if (!enqueued) return false
        runScopedState.pendingUserInputByRunId[id] = true
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
        if (run.state.isTerminal()) return false
        val enqueued = queuedMessages.trySend(PendingMessageBatch(id, messages)).isSuccess
        if (enqueued) runScopedState.pendingUserInputByRunId[id] = true
        return enqueued
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
     *
     * [cancelRun] / [terminateRun] also cancel any registered Job.
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
        pendingUserQuestionsByRunId.clear()
        toolDispatchStartedAtMillis.clear()
        runStartedAtMillis.keys.toList().forEach { runId ->
            runCatching { scratchpad.clear(runId) }
        }
        // Owner disposal is not a terminal transition: retain restored background-run markers.
        runScopedState.trackedRunIds().forEach(com.bytedance.zgx.solin.device.background.BackgroundPhoneSession::releaseRun)
        runScopedState.clear()
        (observationReplanner as? VerifiedPhoneRouteReplanner)?.clear()
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
            if (run.state.isTerminal()) continue
            if (found != null) return null // >1 active; ambiguous
            found = runId
        }
        return found
    }

    /**
     * Drain point for the steer/queued channels for [runId]. Drains ALL pending steer
     * batches first (high priority), then ALL pending normal-queued batches. The returned lists
     * are ordered by arrival (FIFO) within each priority class.
     *
     * Publishes [SolinEvent.Agent.RunSteered] once when the combined injected count is non-empty.
     *
     * [clearEphemeralRunState] also calls it to discard batches addressed to a terminating run.
     *
     * Callers (typically the ViewModel immediately before building the ChatMessage list for the
     * model) should concatenate `steer + queued` messages and APPEND them to the outgoing history
     * as fresh user messages — the design specifies they are added to history so the model sees
     * them as the most recent user turns, rather than being prepended before existing history.
     */
    fun drainPendingMessages(runId: String): PendingMessagesDrain {
        runScopedState.pendingUserInputByRunId.remove(runId)
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
        val augmentedReason = runBudget.augmentReasonWithStepBudgetHint(runId, reason)
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
        val hintReason = runBudget.augmentReasonWithStepBudgetHint(run.id, TOOL_STEP_BUDGET_EXCEEDED_REASON)
        val rejectedPlan = AgentPlan.RejectedTool(request.rejected(hintReason))
        traceStore.appendStep(run.id, AgentStep.ModelPlanned(rejectedPlan))
        traceStore.appendStep(run.id, AgentStep.ToolRejected(rejectedPlan.result))
        auditRecorder.rejected(run.id, rejectedPlan.result)
        traceStore.appendStep(run.id, AgentStep.Failed(rejectedPlan.result.summary))
        val failedRun = traceStore.updateState(run.id, AgentRunState.Failed)
        // The run already published RunStarted from runOnce, so it owes a terminal counterpart.
        publishRunFailed(run.id, AgentErrorCode.Unknown("budget_exceeded"), hintReason)
        clearEphemeralRunState(run.id)
        return AgentLoopResult(
            run = failedRun,
            plan = rejectedPlan,
            steps = traceStore.steps(run.id),
        )
    }

    /**
     * Fails a run parked in AwaitingUserConfirmation when the confirmed request turns out to be
     * invalid, unknown, or safety-rejected. Publishes RunFailed because the run already published
     * RunStarted, and no later transition publisher runs on this path.
     */
    private fun failAwaitingConfirmation(runId: String, reason: String): AgentRun? =
        traceStore.compareAndSetState(
            runId = runId,
            expectedState = AgentRunState.AwaitingUserConfirmation,
            state = AgentRunState.Failed,
        )
            ?.also {
                publishRunFailed(runId, AgentErrorCode.Validation, reason)
                clearEphemeralRunState(runId)
            }

    fun confirmToolRequest(runId: String, requestId: String): AgentRun? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingUserConfirmation) return run
        val request = pendingToolRequest(runId, requestId)
            ?: return traceStore.run(runId) ?: run
        toolRegistry.validate(request)?.let { rejection ->
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRecorder.rejected(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return failAwaitingConfirmation(runId, rejection.summary)
        }
        val spec = toolRegistry.specFor(request.toolName)
        if (spec == null) {
            val rejection = request.rejected("Unknown tool: ${request.toolName}")
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRecorder.rejected(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return failAwaitingConfirmation(runId, rejection.summary)
        }
        val safetyDecision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = true))
        if (safetyDecision.outcome == SafetyOutcome.Reject) {
            val rejection = request.rejected(safetyDecision.reason)
            traceStore.appendStep(runId, AgentStep.SafetyChecked(safetyDecision))
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRecorder.rejected(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return failAwaitingConfirmation(runId, rejection.summary)
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
        auditRecorder.request(
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
        markStepStart(runId, "tool_execution")
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
        auditRecorder.request(
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
        auditRecorder.request(
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
        auditRecorder.request(
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

    fun authorizeCompositeChild(runId: String, parent: ToolRequest, child: ToolRequest): Boolean =
        synchronized(runScopedState) {
            if (runScopedState.pendingUserInputByRunId[runId] == true || runBudget.toolStepBudgetExceeded(runId) ||
                runBudget.runDeadlineExceeded(runId) ||
                !confirmationBypassPolicy.canAuthorizeCompositeChild(runId, parent, child)) return@synchronized false
            val ids = runScopedState.compositeChildIdsByRunId.getOrPut(runId) { mutableSetOf() }
            if (!ids.add(child.id)) return@synchronized false
            runScopedState.compositeStepsByRunId.merge(runId, 1, Int::plus)
            auditRecorder.request(runId, child, ToolAuditEventType.ToolPlanned, null,
                "Composite child authorized by current run policy.")
            true
        }

    fun observeCompositeChild(runId: String, parent: ToolRequest, child: ToolRequest, result: ToolResult) {
        if (result.requestId != child.id || !child.id.startsWith("${parent.id}:child:")) return
        auditRecorder.request(runId, child, ToolAuditEventType.ToolObserved, result.status,
            "Composite child finished; private evidence remains local.")
    }

    fun observeToolResults(runId: String, results: List<ToolResult>): AgentObservationResult? =
        toolObservationCoordinator.observeToolResults(runId, results)

    fun recordExternalOutcome(
        runId: String,
        requestId: String,
        outcome: AgentExternalOutcome,
    ): AgentExternalOutcomeResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingExternalOutcome) return null
        val request = traceQueries.toolRequestFor(runId, requestId) ?: return null
        if (traceQueries.latestExternalOutcomeConfirmation(runId, requestId) != null) return null
        val priorResult = traceQueries.latestUnverifiedExternalResult(runId, requestId) ?: return null
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
        val traceResult = safeResult.redactedForTrace(request, toolRegistry)
        traceStore.appendStep(runId, AgentStep.ExternalOutcomeConfirmed(requestId, outcome, traceResult))
        auditRecorder.request(
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
        val parkedForAskUser = appendPlannedNextToolSteps(runId, decision)
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        // This path leaves AwaitingExternalOutcome without going through publishTransition, so the
        // Failed branch publishes its own terminal counterpart.
        if (finalState == AgentRunState.Failed) {
            publishRunFailed(
                runId,
                AgentErrorCode.Unknown("tool_failure"),
                (decision as? AgentObservationDecision.Fail)?.reason ?: traceResult.summary,
            )
        }
        if (finalState.isTerminal()) {
            clearEphemeralRunState(runId)
        }
        if (
            !parkedForAskUser &&
            decision is AgentObservationDecision.PlanNextTool &&
            decision.plan.requiresUserConfirmation()
        ) {
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
        if (runBudget.observationDecisionBudgetExceeded(runId)) {
            return failRunBudget(runId, OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON)
        }
        // Time-domain guard, checked alongside the step-count budget: a local generation has no
        // timeout, so N bounded turns can still add up to an unbounded run. Fail closed here (a safe
        // GeneratingAnswer -> Failed boundary) rather than starting yet another turn.
        if (runBudget.runDeadlineExceeded(runId)) {
            return failRunBudget(runId, RUN_WALL_CLOCK_DEADLINE_EXCEEDED_REASON)
        }
        // Step latency for model_generation (delta since the caller marked the step, 0L fallback).
        recordStepLatency("model_generation", runId)
        val nextToolPlan = when {
            text.isNotBlank() -> toolPlanCoordinator.planNextToolAfterModelResult(
                run = run,
                text = text.trim(),
                allowInlineToolCalls = allowInlineToolCalls,
            )
            traceQueries.latestSkillPlan(runId) != null -> NextObservationPlan.Rejected(
                "Model output was blank; cannot continue skill.",
            )
            else -> NextObservationPlan.None
        }
        val citationRetryPrompt = if (nextToolPlan == NextObservationPlan.None && text.isNotBlank()) {
            publicEvidenceContinuation.citationRetryPromptOrNull(run, text.trim())
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
        val parkedForAskUser = appendPlannedNextToolSteps(runId, decision)
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        // GenerationComplete carries placeholder zeros: token/ttft stats are not surfaced to the
        // orchestration layer yet.
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
        runEvents.publishTransition(
            runId = runId,
            finalState = finalState,
            completedText = null,
            publishTurnEndedOnCompletion = true,
            failureCode = AgentErrorCode.Unknown("model_observation"),
            failureMessage = (decision as? AgentObservationDecision.Fail)?.reason ?: "observation failed",
        )
        if (finalState.isTerminal()) {
            clearEphemeralRunState(runId)
        }
        if (
            !parkedForAskUser &&
            decision is AgentObservationDecision.PlanNextTool &&
            decision.plan.requiresUserConfirmation()
        ) {
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

    /**
     * Appends the plan steps for a [AgentObservationDecision.PlanNextTool] decision and, when the
     * planned tool is `ask_user`, registers the pending question. Returns true when the run was
     * parked on a question (callers must then skip the pending-confirmation snapshot).
     *
     * Every path whose next state comes from [AgentPlan.UseTool.nextExecutionState] can land in
     * [AgentRunState.AwaitingUserAnswer]; parking here keeps the model-text and external-outcome
     * paths consistent with the observation / remote-tool / initial-plan paths, which park too.
     */
    private fun appendPlannedNextToolSteps(runId: String, decision: AgentObservationDecision): Boolean {
        if (decision !is AgentObservationDecision.PlanNextTool) return false
        toolObservationCoordinator.appendToolPlanSteps(
            runId = runId,
            plan = decision.plan,
        )
        return parkForAskUserIfNeeded(runId, decision.plan)
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
        if (run.state.isTerminal()) return
        traceStore.appendStep(runId, AgentStep.RunDataReceiptRecorded(receipt))
    }

    fun recordModelOutputQualityGuardTriggered(runId: String, trace: ModelOutputQualityTrace) {
        val run = traceStore.run(runId) ?: return
        if (run.state.isTerminal()) return
        traceStore.appendStep(runId, AgentStep.ModelOutputQualityGuardTriggered(trace))
    }

    /**
     * Record a [MetricSample.CompactionTriggered] telemetry sample and bump the
     * [TelemetryCounter.CompactionTriggered] counter. Called by the context compactor
     * (wired upstream of this runtime) whenever it truncates or summarizes history.
     */
    private fun recordCompactionTriggered(
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
     * Mark a step boundary so the next [recordStepLatency] for the same [runId] and [stepType] can
     * compute a delta. Callers that drive the outer loop (ViewModel/orchestrator) can use this to bracket
     * long-running phases (e.g. mark "model_generation" before sending a prompt, recordStepLatency
     * after observeModelResult).
     */
    private fun markStepStart(runId: String, stepType: String) {
        stepStartedAtMillis[StepStartKey(runId, stepType)] = System.currentTimeMillis()
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

    private fun shouldAwaitExternalOutcomeConfirmation(
        runId: String,
        request: ToolRequest,
        result: ToolResult,
    ): Boolean {
        if (!result.isUnverifiedExternalLaunch()) return false
        if (request.isLowRiskDeviceActionConfirmationSkippable()) return false
        // Asking "did it work?" after every auto-run draft/launch is exactly the friction
        // critical-only mode removes; the run completes as "opened" instead.
        return !confirmationBypassPolicy.isAutoApproved(runId, request)
    }

    private fun ToolRequest.isLowRiskDeviceActionConfirmationSkippable(): Boolean =
        toolRegistry.isLowRiskDeviceActionConfirmationSkippable(this)

    private fun clearEphemeralRunState(runId: String) {
        if (runUsedDeviceControlSession(runId)) {
            runCatching { deviceControlSessionFinisher() }
        }
        traceQueries.toolRequestsFor(runId).forEach { request ->
            toolDispatchStartedAtMillis.remove(request.id)
        }
        // Every per-run map, including the lifecycle timestamps / turn counter / parked time /
        // step markers that used to survive until session delete. Lifecycle events that read them
        // (RunEnded, RunFailed) are always published before this cleanup runs.
        runScopedState.forgetRun(runId)
        com.bytedance.zgx.solin.device.background.BackgroundPhoneSession.forgetRun(runId)
        // Best-effort drain of queued steer/queued batches for the terminating run so the
        // channels don't accumulate messages for dead runs. Off-target batches are re-enqueued
        // by pumpChannel; this is safe to call after the run has gone terminal.
        drainPendingMessages(runId)
        cancelAndClearModelCallJob(runId)
        pendingUserQuestionsByRunId.remove(runId)
        runCatching { scratchpad.clear(runId) }
    }

    /** Run ids that still hold in-memory per-run state; test hook for terminal-cleanup coverage. */
    internal fun runIdsWithScopedState(): Set<String> =
        runScopedState.trackedRunIds() + pendingUserQuestionsByRunId.keys + activeModelCallJobs.keys

    private fun runUsedDeviceControlSession(runId: String): Boolean =
        traceQueries.toolRequestsFor(runId).any { request ->
            toolRegistry.startsDeviceControlSession(request.toolName) ||
                toolPlanCoordinator.isDeviceControlTool(request)
        }

    /**
     * States in which a run is waiting on someone outside the agent, so its wall-clock deadline is
     * paused. Read by [parkedMillisFor]; see the wall-clock deadline in AgentRunBudget.
     */
    private val parkedRunStates = setOf(
        AgentRunState.AwaitingUserConfirmation,
        AgentRunState.AwaitingUserAnswer,
        AgentRunState.AwaitingExternalOutcome,
    )

    private companion object {
        const val RUN_DELETED_SESSION_REASON = "Session deleted before this Agent run completed."
    }

    private fun publishRunFailed(runId: String, code: AgentErrorCode, message: String) =
        runEvents.publishRunFailed(runId, code, message)


    private fun pendingToolRequest(runId: String, requestId: String): ToolRequest? {
        val restoredSnapshot = traceStore.latestPendingConfirmation()
            ?.takeIf { snapshot -> snapshot.run.id == runId && snapshot.request.id == requestId }
        if (restoredSnapshot != null) {
            if (!pendingConfirmationSupport.restoredPendingConfirmationIsAuthorized(restoredSnapshot)) return null
            pendingConfirmationSupport.rememberValueFreeFrontier(restoredSnapshot)
        }
        val liveRequest = traceQueries.latestPendingToolRequest(runId)
            ?.takeIf { request -> request.id == requestId }
        return liveRequest ?: restoredSnapshot?.request
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
        // Count ask_user interception (question parked in AwaitingUserAnswer).
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

    // Telemetry recordings MUST go through safeRecordTelemetry so a throwing sink cannot crash the run.

    private fun safeRecordTelemetry(
        sample: MetricSample,
        label: String,
    ) {
        runCatching { telemetrySink.record(sample) }.onFailure { throwable ->
            Log.e(TAG, "Telemetry sink failed to record '$label'", throwable)
        }
    }

    private fun recordStepLatency(stepType: String, runId: String?) {
        val start = runId?.let { id -> stepStartedAtMillis.remove(StepStartKey(id, stepType)) } ?: return
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
}

