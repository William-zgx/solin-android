package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.IntentRoutingDecision
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.audit.NoOpToolAuditSink
import com.bytedance.zgx.solin.audit.ToolAuditEvent
import com.bytedance.zgx.solin.audit.ToolAuditEventType
import com.bytedance.zgx.solin.audit.ToolAuditSink
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceQuality
import com.bytedance.zgx.solin.evidence.EvidenceQualityLevel
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.memory.isAvailableForLocalContext
import com.bytedance.zgx.solin.runtime.estimateLocalRuntimeTokens
import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillModelOutputProgression
import com.bytedance.zgx.solin.skill.SkillModelStepBinding
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.skill.SkillRunProgressor
import com.bytedance.zgx.solin.skill.SkillStep
import com.bytedance.zgx.solin.skill.SkillToolResultProgression
import com.bytedance.zgx.solin.skill.authorizationContractHash
import com.bytedance.zgx.solin.skill.validateStructure
import com.bytedance.zgx.solin.skill.valueFreeCheckpointForPendingTool
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolPermission
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.cancelled
import com.bytedance.zgx.solin.tool.failed
import com.bytedance.zgx.solin.tool.isPublicEvidenceBatchEligible
import com.bytedance.zgx.solin.tool.isRemoteModelPlanningEligible
import com.bytedance.zgx.solin.tool.EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX
import com.bytedance.zgx.solin.tool.isUserConfirmedCompletedExternalOutcome
import com.bytedance.zgx.solin.tool.isUnverifiedExternalLaunch
import com.bytedance.zgx.solin.tool.rejected
import com.bytedance.zgx.solin.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
import com.bytedance.zgx.solin.tool.unverifiedExternalLaunchSummary
import org.json.JSONArray
import org.json.JSONObject

private const val REDACTED_AGENT_RUN_INPUT_VALUE = "[redacted]"
private const val RUN_CANCELLED_REASON = "Agent run was cancelled by the user."
private const val TOOL_STEP_BUDGET_EXCEEDED_REASON = "Agent run tool step budget exceeded."
private const val OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON =
    "Agent run observation decision budget exceeded."
private const val DEVICE_CONTROL_FAILURE_RECOVERY_REASON = "Device control failure recovery checkpoint."
private const val PENDING_EXTERNAL_OUTCOME_RESTORE_RUN_LIMIT = 20
private const val TOOL_OBSERVATION_AUDIT_SUMMARY = "Tool observation recorded."
private const val LOW_RISK_APP_CONTROL_UI_ACTION_CHECKPOINT_LIMIT = 5
private val ANSWER_CONTEXT_TOKEN_BUDGET =
    LocalModelTokenLimits.MAX_INPUT_TOKENS -
        LocalModelTokenLimits.SYSTEM_PROMPT_TOKEN_RESERVE -
        LocalModelTokenLimits.CURRENT_PROMPT_TOKEN_RESERVE
private const val ANSWER_PROMPT_SCAFFOLD_TOKEN_RESERVE = 256
private const val MIN_TRUNCATED_EVIDENCE_TOKENS = 96

class AgentLoopRuntime(
    private val memoryIndex: MemoryIndex,
    private val actionPlanningRuntime: ActionPlanningRuntime,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val skillRuntime: SkillRuntime = BuiltInSkillRuntime(),
    private val safetyPolicy: SafetyPolicy = SafetyPolicy(),
    private val auditSink: ToolAuditSink = NoOpToolAuditSink,
    private val traceStore: AgentTraceStore = InMemoryAgentTraceStore(),
    private val observationReplanner: AgentObservationReplanner = NoOpAgentObservationReplanner,
    private val maxToolRetryAttempts: Int = 1,
    private val maxRunToolSteps: Int = 10,
    private val maxObservationDecisions: Int = 16,
    private val deviceControlSessionFinisher: () -> Unit = {},
) {
    private val skillProgressor = SkillRunProgressor(toolRegistry = toolRegistry)
    private val valueFreeCompletedStepFrontiersByRunId = mutableMapOf<String, Set<String>>()
    private val remoteToolScopesByRunId = mutableMapOf<String, RemoteToolScope>()
    private val remoteExposedToolNamesByRunId = mutableMapOf<String, Set<String>>()
    private val lowRiskDeviceActionConfirmationBypassByRunId = mutableMapOf<String, Boolean>()
    private val installedCapabilityProfilesByRunId = mutableMapOf<String, List<ModelCapabilityProfile>>()

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
        val createdRun = traceStore.createRun(input, sessionId)
        remoteToolScopesByRunId[createdRun.id] = options.remoteToolScope
        lowRiskDeviceActionConfirmationBypassByRunId[createdRun.id] = options.reduceDeviceActionConfirmations
        installedCapabilityProfilesByRunId[createdRun.id] = installedCapabilityProfiles.toList()
        traceStore.updateState(createdRun.id, AgentRunState.LoadingContext)

        val memoryHits = if (memoryEnabled) {
            runCatching { memoryIndex.search(input, topK = 3) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        traceStore.appendStep(createdRun.id, AgentStep.ContextLoaded(memoryHits, deviceContext))
        traceStore.updateState(createdRun.id, AgentRunState.Planning)

        val initialToolPlan = when (options.initialPlanningMode) {
            InitialPlanningMode.RuleFirst -> planToolIfSupported(
                input = input,
                installedCapabilityProfiles = installedCapabilityProfiles,
                actionModelPath = actionModelPath,
            )
            InitialPlanningMode.ModelFirstRemoteTools -> planLocalOnlySkillBeforeRemote(input)
        }
        when (initialToolPlan) {
            is AgentPlan.UseTool -> {
                return requestToolConfirmation(createdRun, initialToolPlan)
            }

            is AgentPlan.RejectedTool -> {
                traceStore.appendStep(createdRun.id, AgentStep.ModelPlanned(initialToolPlan))
                traceStore.appendStep(createdRun.id, AgentStep.ToolRejected(initialToolPlan.result))
                auditRejectedTool(createdRun.id, initialToolPlan.result)
                val failedRun = traceStore.updateState(createdRun.id, AgentRunState.Failed)
                clearEphemeralRunState(createdRun.id)
                return AgentLoopResult(
                    run = failedRun,
                    plan = initialToolPlan,
                    steps = traceStore.steps(createdRun.id),
                )
            }

            is AgentPlan.MissingModel -> {
                return failMissingModelPlan(createdRun, initialToolPlan)
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
            promptForModel = promptWithContextIfUseful(input, memoryHits, deviceContext),
            memoryHits = memoryHits,
            deviceContext = deviceContext,
        )
        traceStore.appendStep(createdRun.id, AgentStep.ModelPlanned(answerPlan))
        val generatingRun = traceStore.updateState(createdRun.id, AgentRunState.GeneratingAnswer)
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
            val plan = buildInitialToolPlan(
                request = recovery.request,
                draft = recovery.draft,
                plannedByModel = false,
                fallbackReason = "typed recovery action",
                skillPlan = null,
            )
        ) {
            is AgentPlan.UseTool -> requestToolConfirmation(createdRun, plan)
            is AgentPlan.RejectedTool -> rejectToolPlan(createdRun, plan)
            else -> null
        }
    }

    fun failStaleInFlightRuns(reason: String): Int =
        traceStore.failStaleInFlightRuns(reason)

    fun failModelGeneration(runId: String, reason: String): AgentModelObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.GeneratingAnswer) return null
        val failureReason = reason.ifBlank { "Model generation failed." }
        val decision = AgentObservationDecision.Fail(failureReason)
        traceStore.appendStep(runId, AgentStep.Failed(failureReason))
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        val updatedRun = traceStore.updateState(runId, AgentRunState.Failed)
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
        } else {
            traceStore.clearPendingConfirmationsForRun(runId)
        }
        val decision = AgentObservationDecision.Cancel
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        val updatedRun = traceStore.updateState(runId, AgentRunState.Cancelled)
        clearEphemeralRunState(runId)
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    private fun failRunBudget(
        runId: String,
        reason: String,
    ): AgentModelObservationResult {
        traceStore.clearPendingConfirmationsForRun(runId)
        traceStore.appendStep(runId, AgentStep.Failed(reason))
        val decision = AgentObservationDecision.Fail(reason)
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        val updatedRun = traceStore.updateState(runId, AgentRunState.Failed)
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
    ): AgentObservationResult {
        traceStore.clearPendingConfirmationsForRun(runId)
        traceStore.appendStep(runId, AgentStep.Failed(reason))
        val decision = AgentObservationDecision.Fail(reason)
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        val updatedRun = traceStore.updateState(runId, AgentRunState.Failed)
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
        val rejectedPlan = AgentPlan.RejectedTool(request.rejected(TOOL_STEP_BUDGET_EXCEEDED_REASON))
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

    private fun failNextPlanBudget(
        runId: String,
        request: ToolRequest,
    ): NextObservationPlan {
        val rejected = request.rejected(TOOL_STEP_BUDGET_EXCEEDED_REASON)
        traceStore.appendStep(runId, AgentStep.ModelPlanned(AgentPlan.RejectedTool(rejected)))
        traceStore.appendStep(runId, AgentStep.ToolRejected(rejected))
        auditRejectedTool(runId, rejected)
        traceStore.appendStep(runId, AgentStep.Failed(rejected.summary))
        return NextObservationPlan.Rejected(rejected.summary)
    }

    private fun toolStepBudgetExceeded(runId: String): Boolean =
        toolRequestsFor(runId).size >= maxRunToolSteps

    private fun observationDecisionBudgetExceeded(runId: String): Boolean =
        traceStore.steps(runId).count { step -> step is AgentStep.ObservationDecided } >= maxObservationDecisions

    fun confirmToolRequest(runId: String, requestId: String): AgentRun? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingUserConfirmation) return run
        val request = pendingToolRequest(runId, requestId)
            ?: return traceStore.run(runId) ?: run
        toolRegistry.validate(request)?.let { rejection ->
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRejectedTool(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return traceStore.updateState(runId, AgentRunState.Failed)
                .also { clearEphemeralRunState(runId) }
        }
        val spec = toolRegistry.specFor(request.toolName)
        if (spec == null) {
            val rejection = request.rejected("Unknown tool: ${request.toolName}")
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRejectedTool(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return traceStore.updateState(runId, AgentRunState.Failed)
                .also { clearEphemeralRunState(runId) }
        }
        val safetyDecision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = true))
        if (safetyDecision.outcome == SafetyOutcome.Reject) {
            val rejection = request.rejected(safetyDecision.reason)
            traceStore.appendStep(runId, AgentStep.SafetyChecked(safetyDecision))
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRejectedTool(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return traceStore.updateState(runId, AgentRunState.Failed)
                .also { clearEphemeralRunState(runId) }
        }
        traceStore.appendStep(runId, AgentStep.SafetyChecked(safetyDecision))
        traceStore.appendStep(runId, AgentStep.UserConfirmed(requestId))
        auditToolRequest(
            runId = runId,
            request = request,
            eventType = ToolAuditEventType.UserConfirmed,
            status = null,
            summary = safetyDecision.reason,
        )
        val executingRun = traceStore.updateState(runId, AgentRunState.ExecutingTool)
        traceStore.clearPendingConfirmation(runId, requestId)
        return executingRun
    }

    fun cancelToolRequest(runId: String, requestId: String): AgentObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingUserConfirmation) return null
        val request = pendingToolRequest(runId, requestId)
            ?: return null
        traceStore.appendStep(runId, AgentStep.UserRejected(requestId))
        auditToolRequest(
            runId = runId,
            request = request,
            eventType = ToolAuditEventType.UserCancelled,
            status = ToolStatus.Cancelled,
            summary = "User cancelled tool request before execution.",
        )
        val observed = observeToolResultInternal(
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
        val observed = observeToolResultInternal(
            runId = runId,
            result = result,
            allowedStates = setOf(AgentRunState.AwaitingUserConfirmation),
        )
        traceStore.clearPendingConfirmation(runId, requestId)
        return observed
    }

    fun observeToolResult(runId: String, result: ToolResult): AgentObservationResult? =
        observeToolResultInternal(
            runId = runId,
            result = result,
            allowedStates = setOf(AgentRunState.ExecutingTool, AgentRunState.RetryingTool),
        )

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
            planNextToolAfterObservation(run, request, traceResult)
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
        if (decision is AgentObservationDecision.PlanNextTool) {
            appendToolPlanSteps(
                runId = runId,
                plan = decision.plan,
            )
        }
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        val finalState = when (decision) {
            AgentObservationDecision.Complete -> AgentRunState.Completed
            is AgentObservationDecision.PlanNextTool -> decision.plan.nextExecutionState()
            is AgentObservationDecision.PlanToolBatch -> AgentRunState.ExecutingTool
            is AgentObservationDecision.Fail -> AgentRunState.Failed
            AgentObservationDecision.Cancel -> AgentRunState.Cancelled
            is AgentObservationDecision.ContinueWithModel -> AgentRunState.GeneratingAnswer
            is AgentObservationDecision.RetryTool -> AgentRunState.RetryingTool
        }
        val updatedRun = traceStore.updateState(runId, finalState)
        if (finalState in terminalRunStates) {
            clearEphemeralRunState(runId)
        }
        if (decision is AgentObservationDecision.PlanNextTool && decision.plan.requiresUserConfirmation()) {
            traceStore.savePendingConfirmation(decision.plan.toPendingSnapshot(updatedRun))
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
        if (observationDecisionBudgetExceeded(runId)) {
            return failRunBudget(runId, OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON)
        }
        val nextToolPlan = when {
            text.isNotBlank() -> planNextToolAfterModelResult(
                run = run,
                text = text.trim(),
                allowInlineToolCalls = allowInlineToolCalls,
            )
            latestSkillPlan(runId) != null -> NextObservationPlan.Rejected(
                "Model output was blank; cannot continue skill.",
            )
            else -> NextObservationPlan.None
        }
        val decision = when (nextToolPlan) {
            NextObservationPlan.None -> AgentObservationDecision.Complete
            is NextObservationPlan.Planned -> AgentObservationDecision.PlanNextTool(
                plan = nextToolPlan.plan,
                reason = "Model output satisfied the next skill step.",
            )

            is NextObservationPlan.Rejected -> AgentObservationDecision.Fail(nextToolPlan.reason)
        }
        if (decision is AgentObservationDecision.PlanNextTool) {
            appendToolPlanSteps(
                runId = runId,
                plan = decision.plan,
            )
        }
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        val finalState = when (decision) {
            AgentObservationDecision.Complete -> AgentRunState.Completed
            is AgentObservationDecision.PlanNextTool -> decision.plan.nextExecutionState()
            is AgentObservationDecision.PlanToolBatch -> AgentRunState.ExecutingTool
            is AgentObservationDecision.Fail -> AgentRunState.Failed
            AgentObservationDecision.Cancel -> AgentRunState.Cancelled
            is AgentObservationDecision.ContinueWithModel -> AgentRunState.GeneratingAnswer
            is AgentObservationDecision.RetryTool -> AgentRunState.RetryingTool
        }
        val updatedRun = traceStore.updateState(runId, finalState)
        if (finalState in terminalRunStates) {
            clearEphemeralRunState(runId)
        }
        if (decision is AgentObservationDecision.PlanNextTool && decision.plan.requiresUserConfirmation()) {
            traceStore.savePendingConfirmation(decision.plan.toPendingSnapshot(updatedRun))
        }
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
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

    fun observeModelToolRequest(runId: String, request: ToolRequest): AgentModelObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.GeneratingAnswer) return null
        if (observationDecisionBudgetExceeded(runId)) {
            return failRunBudget(runId, OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON)
        }
        if (toolStepBudgetExceeded(runId)) {
            return failRunBudget(runId, TOOL_STEP_BUDGET_EXCEEDED_REASON)
        }
        rejectRemoteToolIfNotExposedInCurrentScope(runId, request)?.let { rejected ->
            traceStore.appendRejectedRoutingDecision(
                runId = runId,
                path = IntentRoutingPath.RemoteToolPlanning,
                toolName = request.toolName,
                reason = "remote_tool_not_exposed",
            )
            traceStore.appendStep(runId, AgentStep.ModelPlanned(AgentPlan.RejectedTool(rejected)))
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejected))
            auditRejectedTool(runId, rejected)
            val decision = AgentObservationDecision.Fail(rejected.summary)
            traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
            val updatedRun = traceStore.updateState(runId, AgentRunState.Failed)
            clearEphemeralRunState(runId)
            return AgentModelObservationResult(
                run = updatedRun,
                decision = decision,
                steps = traceStore.steps(runId),
            )
        }
        if (toolRequestFor(runId, request.id) != null) {
            val rejected = request.rejected("Model tool request id already exists: ${request.id}")
            traceStore.appendRejectedRoutingDecision(
                runId = runId,
                path = IntentRoutingPath.RemoteToolPlanning,
                toolName = request.toolName,
                reason = "duplicate_model_tool_request_id",
            )
            traceStore.appendStep(runId, AgentStep.ModelPlanned(AgentPlan.RejectedTool(rejected)))
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejected))
            auditRejectedTool(runId, rejected)
            val decision = AgentObservationDecision.Fail(rejected.summary)
            traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
            val updatedRun = traceStore.updateState(runId, AgentRunState.Failed)
            clearEphemeralRunState(runId)
            return AgentModelObservationResult(
                run = updatedRun,
                decision = decision,
                steps = traceStore.steps(runId),
            )
        }
        val draft = draftForRemoteToolRequest(request)
        val skillPlan = skillRuntime.plan(run.input, draft, request)
        val plan = buildInitialToolPlan(
            request = request,
            draft = draft,
            plannedByModel = true,
            fallbackReason = "remote tool call",
            skillPlan = skillPlan,
        )
        return when (plan) {
            is AgentPlan.UseTool -> {
                val effectivePlan = plan.withConfirmationBypassForRun(runId)
                appendToolPlanSteps(runId, effectivePlan)
                val decision = AgentObservationDecision.PlanNextTool(
                    plan = effectivePlan,
                    reason = "Remote model requested a tool call.",
                )
                traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
                val updatedRun = traceStore.updateState(runId, effectivePlan.nextExecutionState())
                if (effectivePlan.requiresUserConfirmation()) {
                    traceStore.savePendingConfirmation(effectivePlan.toPendingSnapshot(updatedRun))
                }
                AgentModelObservationResult(
                    run = updatedRun,
                    decision = decision,
                    steps = traceStore.steps(runId),
                )
            }

            is AgentPlan.RejectedTool -> {
                traceStore.appendRejectedRoutingDecision(
                    runId = runId,
                    path = IntentRoutingPath.RemoteToolPlanning,
                    toolName = request.toolName,
                    reason = plan.result.summary.toRoutingReasonSlug(),
                )
                traceStore.appendStep(runId, AgentStep.ModelPlanned(plan))
                traceStore.appendStep(runId, AgentStep.ToolRejected(plan.result))
                auditRejectedTool(runId, plan.result)
                val decision = AgentObservationDecision.Fail(plan.result.summary)
                traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
                val updatedRun = traceStore.updateState(runId, AgentRunState.Failed)
                clearEphemeralRunState(runId)
                AgentModelObservationResult(
                    run = updatedRun,
                    decision = decision,
                    steps = traceStore.steps(runId),
                )
            }

            else -> null
        }
    }

    fun observeModelToolRequests(runId: String, requests: List<ToolRequest>): AgentModelObservationResult? {
        if (requests.size == 1) return observeModelToolRequest(runId, requests.single())
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.GeneratingAnswer) return null
        if (observationDecisionBudgetExceeded(runId)) {
            return failRunBudget(runId, OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON)
        }
        if (requests.isEmpty()) {
            return rejectModelToolBatch(
                runId = runId,
                result = ToolRequest(
                    id = "remote-tool-batch",
                    toolName = "tool_batch",
                    reason = "remote tool batch",
                ).rejected("Remote model returned an empty tool batch."),
            )
        }
        if (toolRequestsFor(runId).size + requests.size > maxRunToolSteps) {
            return failRunBudget(runId, TOOL_STEP_BUDGET_EXCEEDED_REASON)
        }
        requests.groupingBy { request -> request.id }
            .eachCount()
            .entries
            .firstOrNull { (_, count) -> count > 1 }
            ?.key
            ?.let { duplicatedRequestId ->
                return rejectModelToolBatch(
                    runId = runId,
                    result = requests.first().rejected(
                        "Model tool request id appears multiple times in one batch: $duplicatedRequestId",
                    ),
                )
            }
        requests.firstOrNull { request -> toolRequestFor(runId, request.id) != null }
            ?.let { existingRequest ->
                return rejectModelToolBatch(
                    runId = runId,
                    result = existingRequest.rejected("Model tool request id already exists: ${existingRequest.id}"),
                )
            }

        val plans = mutableListOf<AgentPlan.UseTool>()
        requests.forEach { request ->
            rejectRemoteToolIfNotExposedInCurrentScope(runId, request)?.let { rejection ->
                return rejectModelToolBatch(runId = runId, result = rejection.withAttemptedToolNames(requests))
            }
            toolRegistry.validate(request)?.let { rejection ->
                return rejectModelToolBatch(runId = runId, result = rejection.withAttemptedToolNames(requests))
            }
            val spec = toolRegistry.specFor(request.toolName) ?: return rejectModelToolBatch(
                runId = runId,
                result = request.rejected("Unknown tool: ${request.toolName}").withAttemptedToolNames(requests),
            )
            if (!spec.isPublicEvidenceBatchEligible()) {
                return rejectModelToolBatch(
                    runId = runId,
                    result = request.rejected(
                        "Tool ${request.toolName} is not eligible for parallel public evidence execution.",
                    ).withAttemptedToolNames(requests),
                )
            }
            val safetyDecision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = false))
            if (safetyDecision.outcome != SafetyOutcome.Allow) {
                return rejectModelToolBatch(
                    runId = runId,
                    result = request.rejected(safetyDecision.reason).withAttemptedToolNames(requests),
                    safetyDecision = safetyDecision,
                )
            }
            plans += AgentPlan.UseTool(
                request = request,
                draft = draftForRemoteToolRequest(request).withSafetyDecision(safetyDecision),
                plannedByModel = true,
                fallbackReason = "remote tool batch",
                skillRequest = null,
                skillPlan = null,
                safetyDecision = safetyDecision,
            )
        }

        plans.forEach { plan -> appendToolPlanSteps(runId, plan) }
        val decision = AgentObservationDecision.PlanToolBatch(
            plans = plans,
            reason = "Remote model requested ${plans.size} parallel public evidence tool calls.",
        )
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        val updatedRun = traceStore.updateState(runId, AgentRunState.ExecutingTool)
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    fun observeToolResults(runId: String, results: List<ToolResult>): AgentObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.ExecutingTool) return null
        val batchDecision = latestPlanToolBatch(runId) ?: return null
        val plans = batchDecision.plans
        val plannedIds = plans.mapTo(linkedSetOf()) { plan -> plan.request.id }
        val resultIds = results.mapTo(linkedSetOf()) { result -> result.requestId }
        if (results.size != resultIds.size || plannedIds != resultIds) return null
        if (plans.any { plan ->
                toolRegistry.specFor(plan.request.toolName)?.isPublicEvidenceBatchEligible() != true
            }
        ) {
            return null
        }

        traceStore.updateState(runId, AgentRunState.Observing)
        val resultsByRequestId = results.associateBy { result -> result.requestId }
        val observedPairs = plans.map { plan ->
            val rawResult = resultsByRequestId.getValue(plan.request.id)
            val validatedResult = toolRegistry.validateResult(plan.request, rawResult) ?: rawResult
            val publicResult = validatedResult.publicEvidenceBatchResultOrFailure(plan.request)
            val traceResult = publicResult.redactedForTrace(plan.request)
            traceStore.appendStep(runId, AgentStep.ToolObserved(traceResult))
            auditSink.record(
                ToolAuditEvent(
                    runId = runId,
                    requestId = traceResult.requestId,
                    toolName = plan.request.toolName,
                    skillId = null,
                    eventType = ToolAuditEventType.ToolObserved,
                    status = traceResult.status,
                    riskLevel = toolRegistry.specFor(plan.request.toolName)?.riskLevel,
                    permissions = toolRegistry.specFor(plan.request.toolName)?.permissions.orEmpty(),
                    summary = traceResult.auditSummaryForObservation(plan.request),
                ),
            )
            plan to traceResult
        }
        val successfulPairs = observedPairs.filter { (_, result) -> result.status == ToolStatus.Succeeded }
        val cancelledPair = observedPairs.firstOrNull { (_, result) -> result.status == ToolStatus.Cancelled }
        val gapPairs = observedPairs.filter { (_, result) ->
            result.status != ToolStatus.Succeeded && result.status != ToolStatus.Cancelled
        }
        val failedPair = gapPairs.firstOrNull()
        val aggregatePair = cancelledPair ?: failedPair
        val publicEvidenceSummary = successfulPairs.publicEvidenceBatchAuditSummary()
        val assistantMessage = when {
            cancelledPair != null -> "工具批量执行已取消：${cancelledPair.second.summary}"
            failedPair != null && successfulPairs.isNotEmpty() ->
                "工具批量执行部分失败：已获得 ${successfulPairs.size}/${observedPairs.size} 个公开只读工具结果；$publicEvidenceSummary；失败缺口：${failedPair.second.summary}"

            failedPair != null -> "工具批量执行失败：${failedPair.second.summary}"
            else -> "工具执行结果：已完成 ${observedPairs.size} 个公开只读工具调用；$publicEvidenceSummary。"
        }
        val aggregateStatus = when {
            cancelledPair != null -> ToolStatus.Cancelled
            successfulPairs.isNotEmpty() -> ToolStatus.Succeeded
            failedPair != null -> failedPair.second.status
            else -> ToolStatus.Succeeded
        }
        val aggregateResult = ToolResult(
            requestId = "public-evidence-batch:${plannedIds.joinToString(",")}",
            status = aggregateStatus,
            summary = assistantMessage,
            data = mapOf(
                "toolName" to "public_evidence_batch",
                "toolCount" to observedPairs.size.toString(),
                "succeededToolCount" to successfulPairs.size.toString(),
                "failedToolCount" to gapPairs.size.toString(),
                "cancelledToolCount" to observedPairs
                    .count { (_, result) -> result.status == ToolStatus.Cancelled }
                    .toString(),
            ),
            error = if (aggregateStatus == ToolStatus.Succeeded) null else aggregatePair?.second?.error,
            retryable = false,
        )
        traceStore.appendStep(runId, AgentStep.AssistantResponded(assistantMessage))
        if (observationDecisionBudgetExceeded(runId)) {
            return failObservationBudget(
                runId = runId,
                result = aggregateResult,
                assistantMessage = assistantMessage,
                reason = OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON,
            )
        }
        val continuationPrompt = if (cancelledPair == null && successfulPairs.isNotEmpty()) {
            publicEvidenceBatchContinuationPrompt(
                run = run,
                observedPairs = observedPairs,
                successfulPairs = successfulPairs,
                gapPairs = gapPairs,
            )
        } else {
            null
        }
        val decision = when {
            cancelledPair != null -> AgentObservationDecision.Cancel
            successfulPairs.isNotEmpty() -> AgentObservationDecision.ContinueWithModel(
                requiresLocalModel = false,
                reason = if (gapPairs.isEmpty()) {
                    "Parallel public evidence tools completed."
                } else {
                    "Parallel public evidence tools partially completed."
                },
            )

            failedPair != null -> AgentObservationDecision.Fail(failedPair.second.summary)
            else -> AgentObservationDecision.ContinueWithModel(
                requiresLocalModel = false,
                reason = "Parallel public evidence tools completed.",
            )
        }
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        val updatedRun = traceStore.updateState(
            runId,
            when (decision) {
                is AgentObservationDecision.ContinueWithModel -> AgentRunState.GeneratingAnswer
                AgentObservationDecision.Cancel -> AgentRunState.Cancelled
                else -> AgentRunState.Failed
            },
        )
        if (decision is AgentObservationDecision.ContinueWithModel) {
            remoteToolScopesByRunId[runId] = RemoteToolScope.PublicEvidenceOnly
        }
        if (updatedRun.state in terminalRunStates) {
            clearEphemeralRunState(runId)
        }
        return AgentObservationResult(
            run = updatedRun,
            result = aggregateResult,
            assistantMessage = assistantMessage,
            decision = decision,
            recoveryAction = null,
            continuationPromptForModel = continuationPrompt,
            continuationRequiresLocalModel = false,
            continuationRemoteToolScope = RemoteToolScope.PublicEvidenceOnly,
            retryRequest = null,
            retryAttempt = 0,
            steps = traceStore.steps(runId),
        )
    }

    private fun rejectModelToolBatch(
        runId: String,
        result: ToolResult,
        safetyDecision: SafetyDecision? = null,
    ): AgentModelObservationResult {
        safetyDecision?.let { decision ->
            traceStore.appendStep(runId, AgentStep.SafetyChecked(decision))
        }
        traceStore.appendRejectedRoutingDecision(
            runId = runId,
            path = IntentRoutingPath.RemoteToolPlanning,
            toolName = result.data["toolName"]
                ?: result.data["attemptedToolNames"]?.substringBefore(',')?.trim()?.takeIf { it.isNotBlank() }
                ?: "tool_batch",
            reason = result.summary.toRoutingReasonSlug(),
        )
        traceStore.appendStep(runId, AgentStep.ModelPlanned(AgentPlan.RejectedTool(result)))
        traceStore.appendStep(runId, AgentStep.ToolRejected(result))
        auditRejectedTool(runId, result)
        val decision = AgentObservationDecision.Fail(result.summary)
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        val updatedRun = traceStore.updateState(runId, AgentRunState.Failed)
        clearEphemeralRunState(runId)
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    private fun observeToolResultInternal(
        runId: String,
        result: ToolResult,
        allowedStates: Set<AgentRunState>,
    ): AgentObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state !in allowedStates) return null
        if (run.state == AgentRunState.ExecutingTool && latestPlanToolBatch(runId) != null) {
            return null
        }
        if (
            run.state in setOf(AgentRunState.ExecutingTool, AgentRunState.RetryingTool) &&
            latestExecutableRequestId(runId) != result.requestId
        ) {
            return null
        }
        val request = toolRequestFor(runId, result.requestId) ?: return null
        val safeResult = toolRegistry.validateResult(request, result) ?: result
        traceStore.updateState(runId, AgentRunState.Observing)
        val continuation = continuationForToolObservation(run, request, safeResult)
        val continuationPrompt = continuation?.prompt
        val continuationRequiresLocalModel = continuation?.requiresLocalModel ?: false
        val continuationRemoteToolScope =
            continuation?.remoteToolScope ?: RemoteToolScope.PublicEvidenceOnly
        val canPlanNextToolBeforeContinuation = continuation?.canPlanNextToolBeforeModel ?: false
        val rawLocalPlanningResult = if (
            result.requestId == request.id &&
            request.canPlanLocalToolFromCurrentScreenObservation(run) &&
            result.hasLocalObservationEvidenceForPlanning()
        ) {
            result
        } else {
            null
        }
        val observedResult = safeResult.redactedForTrace(request)
        val localPlanningResult = if (
            canPlanNextToolBeforeContinuation ||
            rawLocalPlanningResult != null
        ) {
            rawLocalPlanningResult ?: safeResult
        } else {
            observedResult
        }
        val budgetExceeded = observationDecisionBudgetExceeded(runId)
        val retryAttempt = if (budgetExceeded) 0 else nextRetryAttempt(runId, observedResult)
        val retryRequest = if (retryAttempt > 0) request else null
        traceStore.appendStep(runId, AgentStep.ToolObserved(observedResult))
        recordContinuationCursorForUnverifiedExternalLaunch(run, request, observedResult)
        auditSink.record(
            ToolAuditEvent(
                runId = runId,
                requestId = observedResult.requestId,
                toolName = request.toolName,
                skillId = skillIdForRequest(runId, observedResult.requestId),
                eventType = ToolAuditEventType.ToolObserved,
                status = observedResult.status,
                riskLevel = toolRegistry.specFor(request.toolName)?.riskLevel,
                permissions = toolRegistry.specFor(request.toolName)?.permissions.orEmpty(),
                summary = observedResult.auditSummaryForObservation(request),
            ),
        )
        val recoveryAction = recoveryActionForObservation(request, observedResult)
        val assistantMessage = messageForObservation(observedResult, retryAttempt, recoveryAction)
        traceStore.appendStep(
            runId,
            AgentStep.AssistantResponded(
                traceMessageForObservation(
                    request = request,
                    result = observedResult,
                    retryAttempt = retryAttempt,
                    recoveryAction = recoveryAction,
                ),
            ),
        )
        if (budgetExceeded) {
            return failObservationBudget(
                runId = runId,
                result = observedResult,
                assistantMessage = assistantMessage,
                reason = OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON,
            )
        }
        val nextToolPlan = if (
            observedResult.status == ToolStatus.Succeeded &&
            retryRequest == null
        ) {
            planNextOpenAppUiSearchStepAfterUnverifiedLaunch(run, request, localPlanningResult)
                ?: planNextLowRiskAppControlSkillStepBeforeContinuation(run, request, localPlanningResult)
                ?: planNextCompositeSkillSegmentBeforeContinuation(run, continuation)
                ?: if (
                    (continuationPrompt == null || canPlanNextToolBeforeContinuation) &&
                    canPlanNextToolAfterObservation(run, request, localPlanningResult)
                ) {
                    planNextToolAfterObservation(run, request, localPlanningResult)
                } else {
                    NextObservationPlan.None
                }
        } else if (
            observedResult.status == ToolStatus.Failed &&
            request.isDeviceControlTool() &&
            !observedResult.isPermissionFailure() &&
            retryRequest == null
        ) {
            planModelDeviceControlRecoveryAfterFailure(run, request, localPlanningResult)
                ?: planSafeDeviceControlRecoveryAfterFailure(run, request, observedResult)
        } else {
            NextObservationPlan.None
        }
        val decision = observationDecision(
            request = request,
            result = observedResult,
            retryRequest = retryRequest,
            retryAttempt = retryAttempt,
            continuationPrompt = continuationPrompt,
            continuationRequiresLocalModel = continuationRequiresLocalModel,
            nextToolPlan = nextToolPlan,
        )
        when (decision) {
            is AgentObservationDecision.RetryTool -> traceStore.appendStep(
                runId,
                AgentStep.ToolRetryScheduled(
                    request = decision.request,
                    attempt = decision.attempt,
                    reason = decision.reason,
                ),
            )

            is AgentObservationDecision.PlanNextTool -> appendToolPlanSteps(
                runId = runId,
                plan = decision.plan,
            )

            else -> Unit
        }
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        if (decision is AgentObservationDecision.RetryTool) {
            auditToolRequest(
                runId = runId,
                request = decision.request,
                eventType = ToolAuditEventType.ToolRetryScheduled,
                status = ToolStatus.Failed,
                summary = "Retry ${decision.attempt} scheduled.",
            )
        }
        val shouldAwaitExternalOutcome = shouldAwaitExternalOutcomeConfirmation(
            runId = runId,
            request = request,
            result = observedResult,
        )
        val finalState = when (decision) {
            AgentObservationDecision.Complete ->
                if (shouldAwaitExternalOutcome) {
                    AgentRunState.AwaitingExternalOutcome
                } else {
                    AgentRunState.Completed
                }

            is AgentObservationDecision.ContinueWithModel -> AgentRunState.GeneratingAnswer
            is AgentObservationDecision.RetryTool -> AgentRunState.RetryingTool
            is AgentObservationDecision.PlanNextTool -> decision.plan.nextExecutionState()
            is AgentObservationDecision.PlanToolBatch -> AgentRunState.ExecutingTool
            is AgentObservationDecision.Fail -> AgentRunState.Failed
            AgentObservationDecision.Cancel -> AgentRunState.Cancelled
        }
        val updatedRun = traceStore.updateState(runId, finalState)
        if (finalState in terminalRunStates) {
            clearEphemeralRunState(runId)
        }
        if (decision is AgentObservationDecision.ContinueWithModel) {
            remoteToolScopesByRunId[runId] = continuationRemoteToolScope
        }
        if (decision is AgentObservationDecision.PlanNextTool && decision.plan.requiresUserConfirmation()) {
            traceStore.savePendingConfirmation(decision.plan.toPendingSnapshot(updatedRun))
        }
        return AgentObservationResult(
            run = updatedRun,
            result = observedResult,
            assistantMessage = assistantMessage,
            decision = decision,
            recoveryAction = recoveryAction,
            continuationPromptForModel = continuationPrompt,
            continuationRequiresLocalModel = continuationRequiresLocalModel,
            continuationRemoteToolScope = continuationRemoteToolScope,
            retryRequest = retryRequest,
            retryAttempt = retryAttempt,
            steps = traceStore.steps(runId),
        )
    }

    private fun observationDecision(
        request: ToolRequest,
        result: ToolResult,
        retryRequest: ToolRequest?,
        retryAttempt: Int,
        continuationPrompt: String?,
        continuationRequiresLocalModel: Boolean,
        nextToolPlan: NextObservationPlan,
    ): AgentObservationDecision =
        when {
            retryRequest != null -> AgentObservationDecision.RetryTool(
                request = retryRequest,
                attempt = retryAttempt,
                reason = result.summary,
            )

            nextToolPlan is NextObservationPlan.Planned ->
                AgentObservationDecision.PlanNextTool(
                    plan = nextToolPlan.plan,
                    reason = result.summary,
                )

            nextToolPlan is NextObservationPlan.Rejected ->
                AgentObservationDecision.Fail(nextToolPlan.reason)

            result.status == ToolStatus.Succeeded && continuationPrompt != null ->
                AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = continuationRequiresLocalModel,
                    reason = result.summary,
                )

            result.status == ToolStatus.Succeeded -> AgentObservationDecision.Complete
            result.status == ToolStatus.Cancelled -> AgentObservationDecision.Cancel
            else -> AgentObservationDecision.Fail(result.summary)
        }

    private fun planNextToolAfterObservation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan {
        latestSkillPlan(run.id)?.let { skillPlan ->
            invalidSkillPlanReason(skillPlan)?.let { reason ->
                val requestedRequestIds = toolRequestsFor(run.id).mapTo(mutableSetOf()) { toolRequest ->
                    toolRequest.id
                }
                return rejectNextToolPlan(
                    run.id,
                    requestForSkillProgressionRejection(skillPlan, requestedRequestIds, reason),
                )
            }
        }
        planNextToolStepFromCurrentSkill(run, result)?.let { nextPlan ->
            return nextPlan
        }
        val priorRequests = toolRequestsFor(run.id)
        val completedSegmentCount = plannedSequentialSegmentCount(run.id)
        planNextToolFromContinuationCursor(run, request)?.let { nextPlan ->
            return nextPlan
        }
        planNextCompositeSkillSegment(
            runId = run.id,
            input = nextSequentialSegmentInput(run, completedSegmentCount),
        )?.let { nextPlan ->
            return nextPlan
        }
        val replan = observationReplanner.planNext(
            AgentObservationReplanContext(
                run = run,
                previousRequest = request,
                observedResult = result,
                priorRequests = priorRequests,
                nextActionInput = traceStore.nextActionInput(run.id),
                completedSegmentCount = completedSegmentCount,
            ),
        ) ?: return NextObservationPlan.None
        if (
            replan.plannedByModel &&
            !hasMobileActionPlanningModel(
                installedCapabilityProfiles = installedCapabilityProfilesByRunId[run.id].orEmpty(),
            )
        ) {
            return failMissingModelNextPlan(run.id, AgentPlan.MissingModel(ModelCapability.MobileAction))
        }
        val skillPlan = skillRuntime.plan(replan.input ?: run.input, replan.draft, replan.request)
        return buildNextToolPlan(
            runId = run.id,
            request = replan.request,
            draft = replan.draft,
            plannedByModel = replan.plannedByModel,
            fallbackReason = replan.fallbackReason,
            skillPlan = skillPlan,
        )
    }

    private fun planModelDeviceControlRecoveryAfterFailure(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan? {
        if (latestSkillPlan(run.id) != null) return null
        if (!result.hasLocalObservationEvidenceForPlanning()) return null
        val priorRequests = toolRequestsFor(run.id)
        val completedSegmentCount = plannedSequentialSegmentCount(run.id)
        val replan = observationReplanner.planNext(
            AgentObservationReplanContext(
                run = run,
                previousRequest = request,
                observedResult = result,
                priorRequests = priorRequests,
                nextActionInput = traceStore.nextActionInput(run.id),
                completedSegmentCount = completedSegmentCount,
            ),
        ) ?: return null
        if (
            replan.plannedByModel &&
            !hasMobileActionPlanningModel(
                installedCapabilityProfiles = installedCapabilityProfilesByRunId[run.id].orEmpty(),
            )
        ) {
            return failMissingModelNextPlan(run.id, AgentPlan.MissingModel(ModelCapability.MobileAction))
        }
        val skillPlan = skillRuntime.plan(replan.input ?: run.input, replan.draft, replan.request)
        return buildNextToolPlan(
            runId = run.id,
            request = replan.request,
            draft = replan.draft,
            plannedByModel = replan.plannedByModel,
            fallbackReason = replan.fallbackReason,
            skillPlan = skillPlan,
        )
    }

    private fun planSafeDeviceControlRecoveryAfterFailure(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan {
        val recoveryCount = toolRequestsFor(run.id).count { prior ->
            prior.reason == DEVICE_CONTROL_FAILURE_RECOVERY_REASON
        }
        if (recoveryCount >= 2) return NextObservationPlan.None
        val failureKind = result.data["failureKind"].orEmpty()
        if (failureKind == "permission_missing") return NextObservationPlan.None
        val nextToolName = when (request.toolName) {
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN -> MobileActionFunctions.UI_WAIT
            MobileActionFunctions.UI_WAIT -> MobileActionFunctions.OBSERVE_CURRENT_SCREEN
            else -> MobileActionFunctions.OBSERVE_CURRENT_SCREEN
        }
        val arguments = when (nextToolName) {
            MobileActionFunctions.UI_WAIT -> mapOf("timeoutMillis" to "500")
            else -> mapOf(
                "maxTextChars" to "2000",
                "maxNodes" to "50",
            )
        }
        val title = when (nextToolName) {
            MobileActionFunctions.UI_WAIT -> "等待屏幕稳定"
            else -> "重新观察当前屏幕"
        }
        val draft = ActionDraft(
            functionName = nextToolName,
            title = title,
            summary = "上一屏幕控制动作失败，将先$title 以便重新规划；不会读取截图像素或发送远程。",
            parameters = arguments,
        )
        val recoveryRequest = ToolRequest(
            toolName = nextToolName,
            arguments = arguments,
            reason = DEVICE_CONTROL_FAILURE_RECOVERY_REASON,
        )
        val skillPlan = skillRuntime.plan(run.input, draft, recoveryRequest)
        return buildNextToolPlan(
            runId = run.id,
            request = recoveryRequest,
            draft = draft,
            plannedByModel = false,
            fallbackReason = "device control failure recovery",
            skillPlan = skillPlan,
        )
    }

    private fun planNextToolFromContinuationCursor(
        run: AgentRun,
        request: ToolRequest,
    ): NextObservationPlan? {
        val cursor = traceStore.continuationCursor(run.id) ?: return null
        if (cursor.sourceRequestId != request.id) return null
        val completedSegmentCount = plannedSequentialSegmentCount(run.id)
        if (cursor.completedSegmentCount != completedSegmentCount) {
            return rejectNextToolPlan(
                run.id,
                cursor.request.rejected("Restored continuation cursor does not match the Agent trace."),
            )
        }
        return buildNextToolPlan(
            runId = run.id,
            request = cursor.request,
            draft = cursor.draft,
            plannedByModel = cursor.plannedByModel,
            fallbackReason = cursor.fallbackReason,
            skillPlan = cursor.skillPlan,
        )
    }

    private fun recordContinuationCursorForUnverifiedExternalLaunch(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ) {
        if (!result.isUnverifiedExternalLaunch()) return
        val cursor = traceStore.continuationCursor(run.id) ?: return
        if (cursor.sourceRequestId != request.id) return
        if (cursor.completedSegmentCount != plannedSequentialSegmentCount(run.id)) return
        val alreadyRecorded = traceStore.steps(run.id).any { step ->
            step is AgentStep.ContinuationCursorRecorded &&
                step.cursor.sourceRequestId == cursor.sourceRequestId
        }
        if (alreadyRecorded) return
        traceStore.appendStep(run.id, AgentStep.ContinuationCursorRecorded(cursor))
    }

    private fun planNextOpenAppUiSearchStepAfterUnverifiedLaunch(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan? {
        if (!result.isUnverifiedExternalLaunch()) return null
        if (!toolRegistry.isOpenAppLaunchTool(request.toolName)) return null
        val skillPlan = latestSkillPlan(run.id) ?: return null
        if (!skillPlan.manifest.continuesAfterUnverifiedOpenAppLaunch) return null
        val requestBelongsToSkill = skillPlan.steps
            .filterIsInstance<SkillStep.ToolStep>()
            .any { step -> step.request.id == request.id && step.request.toolName == request.toolName }
        if (!requestBelongsToSkill) return null
        val nextPlan = planNextToolStepFromCurrentSkill(run, result) ?: return null
        return nextPlan.takeIf { plan ->
            plan !is NextObservationPlan.Planned || plan.plan.request.isLowRiskAppControlContinuationTool()
        }
    }

    private fun planNextLowRiskAppControlSkillStepBeforeContinuation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan? {
        if (!request.isLowRiskAppControlContinuationTool()) return null
        val skillPlan = latestSkillPlan(run.id) ?: return null
        if (!skillPlan.isLowRiskAppControlSkill()) return null
        if (!skillPlan.hasToolStep(request)) return null
        invalidSkillPlanReason(skillPlan)?.let { reason ->
            val requestedRequestIds = toolRequestsFor(run.id).mapTo(mutableSetOf()) { toolRequest ->
                toolRequest.id
            }
            return rejectNextToolPlan(
                run.id,
                requestForSkillProgressionRejection(skillPlan, requestedRequestIds, reason),
            )
        }
        val nextPlan = planNextToolStepFromCurrentSkill(run, result) ?: return null
        return nextPlan.takeIf { plan ->
            plan !is NextObservationPlan.Planned ||
                plan.plan.request.isLowRiskAppControlContinuationTool() ||
                plan.plan.requiresUserConfirmation()
        }
    }

    private fun planNextCompositeSkillSegmentBeforeContinuation(
        run: AgentRun,
        continuation: ToolObservationContinuation?,
    ): NextObservationPlan? {
        if (continuation == null) return null
        if (continuation.canPlanNextToolBeforeModel) return null
        if (continuation.blocksSequentialTail) return null
        val completedSegmentCount = plannedSequentialSegmentCount(run.id)
        return planNextCompositeSkillSegment(
            runId = run.id,
            input = nextSequentialSegmentInput(run, completedSegmentCount),
        )
    }

    private fun canPlanNextToolAfterObservation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): Boolean {
        if (!request.startsExternalActivity()) return true
        return result.isUserConfirmedCompletedExternalOutcome() ||
            (
                request.isLowRiskDeviceActionConfirmationSkippable() &&
                    traceStore.continuationCursor(run.id)?.sourceRequestId == request.id
            )
    }

    private fun ToolRequest.startsExternalActivity(): Boolean =
        toolRegistry.specFor(toolName)?.permissions?.contains(ToolPermission.StartsExternalActivity) == true

    private fun ToolRequest.isDeviceControlTool(): Boolean =
        toolRegistry.specFor(toolName)?.capability == ToolCapability.DeviceControl

    private fun ToolRequest.canPlanLocalToolFromCurrentScreenObservation(run: AgentRun): Boolean =
        when (toolName) {
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR -> true

            else -> isDeviceControlTool() && latestSkillPlan(run.id) == null
        }

    private fun ToolResult.hasLocalObservationEvidenceForPlanning(): Boolean =
        listOf(
            "screenObservationJson",
            "beforeScreenObservationJson",
            "afterScreenObservationJson",
            "screenObservationDiffSummary",
            "ocrBlocksJson",
            "ocrText",
            "screenText",
        ).any { key -> data[key]?.isNotBlank() == true }

    private fun ToolResult.isPermissionFailure(): Boolean =
        error?.code == ToolErrorCode.PermissionDenied ||
            data["failureKind"] == "permission_missing"

    private fun planNextToolStepFromCurrentSkill(
        run: AgentRun,
        result: ToolResult,
    ): NextObservationPlan? {
        val skillPlan = latestSkillPlan(run.id) ?: return null
        val requestedRequestIds = toolRequestsFor(run.id).mapTo(mutableSetOf()) { request -> request.id }
        return when (val progression = skillProgressor.nextToolAfterToolResult(
            skillPlan = skillPlan,
            requestedRequestIds = requestedRequestIds,
            result = result,
            satisfiedStepIds = valueFreeCompletedStepFrontiersByRunId[run.id].orEmpty(),
        )) {
            SkillToolResultProgression.None -> null
            is SkillToolResultProgression.Rejected ->
                rejectNextToolPlan(
                    run.id,
                    requestForSkillProgressionRejection(skillPlan, requestedRequestIds, progression.reason),
                )
            is SkillToolResultProgression.BoundTool ->
                buildNextToolPlan(
                    runId = run.id,
                    request = progression.request,
                    draft = progression.draft,
                    plannedByModel = false,
                    fallbackReason = "skill tool step",
                    skillPlan = skillPlan,
                )
        }
    }

    private fun planNextToolAfterModelResult(
        run: AgentRun,
        text: String,
        allowInlineToolCalls: Boolean = true,
    ): NextObservationPlan {
        val skillPlan = latestSkillPlan(run.id)
            ?: return if (allowInlineToolCalls) {
                planExplicitToolCallAfterModelResult(run, text)
            } else {
                NextObservationPlan.None
            }
        val requestedRequestIds = toolRequestsFor(run.id).mapTo(mutableSetOf()) { request -> request.id }
        invalidSkillPlanReason(skillPlan)?.let { reason ->
            return rejectNextToolPlan(
                run.id,
                requestForSkillProgressionRejection(skillPlan, requestedRequestIds, reason),
            )
        }
        return when (val progression = skillProgressor.nextToolAfterModelOutput(
            skillPlan = skillPlan,
            requestedRequestIds = requestedRequestIds,
            modelOutput = text,
        )) {
            SkillModelOutputProgression.None -> NextObservationPlan.None
            is SkillModelOutputProgression.Rejected ->
                rejectNextToolPlan(
                    run.id,
                    requestForSkillProgressionRejection(skillPlan, requestedRequestIds, progression.reason),
                )
            is SkillModelOutputProgression.BoundTool ->
                buildNextToolPlan(
                    runId = run.id,
                    request = progression.request,
                    draft = progression.draft,
                    plannedByModel = false,
                    fallbackReason = "skill model step",
                    skillPlan = skillPlan,
                )
        }
    }

    private fun planExplicitToolCallAfterModelResult(
        run: AgentRun,
        text: String,
    ): NextObservationPlan {
        val draft = when (val parsed = actionPlanningRuntime.parseModelToolOutput(text)) {
            ModelToolOutputParseResult.None -> return NextObservationPlan.None
            is ModelToolOutputParseResult.Parsed -> parsed.draft
            is ModelToolOutputParseResult.Rejected -> {
                val rejectedRequest = ToolRequest(
                    toolName = parsed.toolName ?: "model_tool_call",
                    reason = "local model tool call",
                )
                return rejectNextToolPlan(run.id, rejectedRequest.rejected(parsed.reason))
            }
        }
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = "local model tool call",
        )
        if (
            request.requiresMobileActionProfileForInlineToolCall() &&
            !hasMobileActionPlanningModel(installedCapabilityProfilesByRunId[run.id].orEmpty())
        ) {
            return failMissingModelNextPlan(run.id, AgentPlan.MissingModel(ModelCapability.MobileAction))
        }
        val skillPlan = skillRuntime.plan(run.input, draft, request)
        return buildNextToolPlan(
            runId = run.id,
            request = request,
            draft = draft,
            plannedByModel = true,
            fallbackReason = "local model tool call",
            skillPlan = skillPlan,
        )
    }

    private fun requestForSkillProgressionRejection(
        skillPlan: SkillPlan,
        requestedRequestIds: Set<String>,
        reason: String,
    ): ToolResult {
        val rejectedRequest = skillPlan.steps
            .filterIsInstance<SkillStep.ToolStep>()
            .firstOrNull { step -> step.request.id !in requestedRequestIds }
            ?.request
            ?: ToolRequest(
                toolName = skillPlan.manifest.requiredTools.firstOrNull().orEmpty().ifBlank { "skill" },
                reason = skillPlan.request.reason,
            )
        return rejectedRequest.rejected(reason)
    }

    private fun buildNextToolPlan(
        runId: String,
        request: ToolRequest,
        draft: ActionDraft,
        plannedByModel: Boolean,
        fallbackReason: String?,
        skillPlan: SkillPlan?,
    ): NextObservationPlan {
        if (toolStepBudgetExceeded(runId)) {
            return failNextPlanBudget(runId, request)
        }
        if (toolRequestFor(runId, request.id) != null) {
            return rejectNextToolPlan(
                runId,
                request.rejected("Replanned tool request id already exists: ${request.id}"),
            )
        }
        invalidSkillPlanRejection(request, skillPlan)?.let { rejection ->
            return rejectNextToolPlan(runId, rejection)
        }
        val rejection = toolRegistry.validate(request)
        if (rejection != null) {
            return rejectNextToolPlan(runId, rejection)
        }
        val spec = toolRegistry.specFor(request.toolName) ?: run {
            val rejected = request.rejected("Unknown tool: ${request.toolName}")
            return rejectNextToolPlan(runId, rejected)
        }
        val safetyDecision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = false))
        if (safetyDecision.outcome == SafetyOutcome.Reject) {
            val rejected = request.rejected(safetyDecision.reason)
            traceStore.appendStep(runId, AgentStep.SafetyChecked(safetyDecision))
            return rejectNextToolPlan(runId, rejected)
        }
        val plan = AgentPlan.UseTool(
            request = request,
            draft = draft.withSafetyDecision(safetyDecision),
            plannedByModel = plannedByModel,
            fallbackReason = fallbackReason,
            skillRequest = skillPlan?.request,
            skillPlan = skillPlan,
            safetyDecision = safetyDecision,
        ).withConfirmationBypassForRun(runId)
            .withLowRiskAppControlContinuationBypassForRun(runId, skillPlan)
        return NextObservationPlan.Planned(
            plan,
        )
    }

    private fun failMissingModelNextPlan(
        runId: String,
        plan: AgentPlan.MissingModel,
    ): NextObservationPlan {
        val reason = "Missing model capability ${plan.capability.name}."
        traceStore.appendStep(runId, AgentStep.ModelPlanned(plan))
        traceStore.appendStep(runId, AgentStep.Failed(reason))
        return NextObservationPlan.Rejected(reason)
    }

    private fun rejectNextToolPlan(runId: String, result: ToolResult): NextObservationPlan {
        traceStore.appendStep(runId, AgentStep.ModelPlanned(AgentPlan.RejectedTool(result)))
        traceStore.appendStep(runId, AgentStep.ToolRejected(result))
        auditRejectedTool(runId, result)
        return NextObservationPlan.Rejected(result.summary)
    }

    private fun appendToolPlanSteps(
        runId: String,
        plan: AgentPlan.UseTool,
    ) {
        traceStore.run(runId)?.let { run ->
            traceStore.appendStep(runId, AgentStep.IntentRouted(plan.toIntentRoutingDecision(run.input)))
        }
        traceStore.appendStep(runId, AgentStep.ModelPlanned(plan))
        plan.skillRequest?.let { skillRequest ->
            traceStore.appendStep(runId, AgentStep.SkillPlanned(skillRequest, plan.skillPlan))
        }
        traceStore.appendStep(runId, AgentStep.SafetyChecked(plan.safetyDecision))
        traceStore.appendStep(runId, AgentStep.ToolRequested(plan.request, plan.draft))
        auditToolEvent(runId, plan, ToolAuditEventType.ToolPlanned, null, plan.request.reason)
        if (plan.requiresUserConfirmation()) {
            traceStore.appendStep(runId, AgentStep.UserConfirmationRequested(plan.request, plan.draft))
            auditToolEvent(
                runId = runId,
                plan = plan,
                eventType = ToolAuditEventType.ConfirmationRequested,
                status = null,
                summary = plan.safetyDecision.reason,
            )
        }
    }

    private fun requestToolConfirmation(
        run: AgentRun,
        plan: AgentPlan.UseTool,
    ): AgentLoopResult {
        val effectivePlan = plan.withConfirmationBypassForRun(run.id)
        if (toolStepBudgetExceeded(run.id)) {
            return failInitialPlanBudget(run, effectivePlan.request)
        }
        appendToolPlanSteps(run.id, effectivePlan)
        val nextState = if (effectivePlan.requiresUserConfirmation()) {
            AgentRunState.AwaitingUserConfirmation
        } else {
            AgentRunState.ExecutingTool
        }
        val waitingRun = traceStore.updateState(run.id, nextState)
        if (effectivePlan.requiresUserConfirmation()) {
            traceStore.savePendingConfirmation(effectivePlan.toPendingSnapshot(waitingRun))
        }
        return AgentLoopResult(
            run = waitingRun,
            plan = effectivePlan,
            steps = traceStore.steps(run.id),
        )
    }

    private fun rejectToolPlan(
        run: AgentRun,
        plan: AgentPlan.RejectedTool,
    ): AgentLoopResult {
        traceStore.appendStep(run.id, AgentStep.ModelPlanned(plan))
        traceStore.appendStep(run.id, AgentStep.ToolRejected(plan.result))
        auditRejectedTool(run.id, plan.result)
        val failedRun = traceStore.updateState(run.id, AgentRunState.Failed)
        clearEphemeralRunState(run.id)
        return AgentLoopResult(
            run = failedRun,
            plan = plan,
            steps = traceStore.steps(run.id),
        )
    }

    private fun failMissingModelPlan(
        run: AgentRun,
        plan: AgentPlan.MissingModel,
    ): AgentLoopResult {
        val reason = "Missing model capability ${plan.capability.name}."
        traceStore.appendStep(run.id, AgentStep.ModelPlanned(plan))
        traceStore.appendStep(run.id, AgentStep.Failed(reason))
        val failedRun = traceStore.updateState(run.id, AgentRunState.Failed)
        clearEphemeralRunState(run.id)
        return AgentLoopResult(
            run = failedRun,
            plan = plan,
            steps = traceStore.steps(run.id),
        )
    }

    fun latestPendingConfirmation(sessionId: String? = null): PendingToolConfirmationSnapshot? =
        traceStore.latestPendingConfirmation(sessionId)
            ?.takeIf { snapshot -> restoredPendingConfirmationIsAuthorized(snapshot) }
            ?.also { snapshot -> rememberValueFreeFrontier(snapshot) }

    fun latestPendingExternalOutcome(sessionId: String? = null): PendingExternalOutcomeSnapshot? =
        traceStore.recentRunSummaries(limit = PENDING_EXTERNAL_OUTCOME_RESTORE_RUN_LIMIT, stepLimit = 0)
            .asSequence()
            .map { summary -> summary.run }
            .filter { run ->
                run.state in pendingExternalOutcomeRestoreStates &&
                    (sessionId == null || run.sessionId == sessionId)
            }
            .mapNotNull { run -> pendingExternalOutcomeSnapshotFor(run) }
            .firstOrNull()

    private fun pendingExternalOutcomeSnapshotFor(run: AgentRun): PendingExternalOutcomeSnapshot? {
        val steps = traceStore.stepSummaries(run.id)
        val confirmedRequestIds = steps
            .asSequence()
            .filter { step -> step.type == "ExternalOutcomeConfirmed" }
            .mapNotNull { step -> step.requestIdFromJson() }
            .toSet()
        val requestsById = steps
            .asSequence()
            .filter { step -> step.type == "ToolRequested" }
            .mapNotNull { step -> step.restoredToolRequestSummaryOrNull() }
            .associateBy { request -> request.requestId }
        val observationEntry = steps
            .withIndex()
            .toList()
            .asReversed()
            .asSequence()
            .filter { indexedStep -> indexedStep.value.type == "ToolObserved" }
            .mapNotNull { indexedStep ->
                indexedStep.value.restoredExternalObservationOrNull()?.let { observation ->
                    indexedStep.index to observation
                }
            }
            .firstOrNull { (_, observation) ->
                observation.requestId !in confirmedRequestIds &&
                    observation.result.isUnverifiedExternalLaunch()
            } ?: return null
        val laterToolRequested = steps
            .drop(observationEntry.first + 1)
            .any { step -> step.type == "ToolRequested" }
        if (laterToolRequested) return null
        val observation = observationEntry.second
        val request = requestsById[observation.requestId] ?: return null
        if (request.isLowRiskRestoredExternalOutcomePopupSkippable(observation.result, toolRegistry)) return null
        val summary = if (observation.result.summary.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX)) {
            observation.result.summary
        } else {
            "$UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX：${observation.result.summary}"
        }
        return PendingExternalOutcomeSnapshot(
            runId = run.id,
            requestId = observation.requestId,
            toolName = request.toolName,
            title = request.title,
            summary = summary,
        )
    }

    private fun rememberValueFreeFrontier(snapshot: PendingToolConfirmationSnapshot) {
        val completedStepIds = snapshot.skillRunCheckpoint?.completedStepIds.orEmpty().toSet()
        if (completedStepIds.isEmpty()) {
            valueFreeCompletedStepFrontiersByRunId.remove(snapshot.run.id)
        } else {
            valueFreeCompletedStepFrontiersByRunId[snapshot.run.id] = completedStepIds
        }
    }

    private fun restoredPendingConfirmationIsAuthorized(snapshot: PendingToolConfirmationSnapshot): Boolean {
        val reason = invalidSkillPlanReason(snapshot.skillPlan)
            ?: invalidSkillPlanReason(snapshot.continuationCursor?.skillPlan)
            ?: return true
        val rejection = snapshot.request.rejected(reason)
        traceStore.appendStep(snapshot.run.id, AgentStep.ToolRejected(rejection))
        auditRejectedTool(snapshot.run.id, rejection)
        traceStore.clearPendingConfirmation(snapshot.run.id, snapshot.request.id)
        traceStore.appendStep(snapshot.run.id, AgentStep.Failed(rejection.summary))
        traceStore.updateState(snapshot.run.id, AgentRunState.Failed)
        clearEphemeralRunState(snapshot.run.id)
        return false
    }

    private fun AgentPlan.UseTool.toPendingSnapshot(run: AgentRun): PendingToolConfirmationSnapshot =
        plannedSequentialSegmentCount(run.id).let { completedSegmentCount ->
            val nextActionInput = run.input.explicitSequentialActionTextAt(completedSegmentCount)
            PendingToolConfirmationSnapshot(
                run = run,
                request = request,
                draft = draft,
                skillId = skillRequest?.skillId,
                skillPlan = skillPlan,
                plannedByModel = plannedByModel,
                fallbackReason = fallbackReason,
                nextActionInput = nextActionInput,
                continuationCursor = nextActionInput?.takeIf { traceStore is RoomAgentTraceStore }?.let { input ->
                    persistableContinuationCursor(
                        sourceRequestId = request.id,
                        completedSegmentCount = completedSegmentCount,
                        input = input,
                    )
                },
                skillRunCheckpoint = skillPlan?.valueFreeCheckpointForPendingTool(
                    runId = run.id,
                    pendingRequest = request,
                    toolRegistry = toolRegistry,
                ),
            )
        }

    private fun persistableContinuationCursor(
        sourceRequestId: String,
        completedSegmentCount: Int,
        input: String,
    ): AgentContinuationCursor? {
        val plan = planInitialSequentialSegment(input, actionModelPath = null) as? AgentPlan.UseTool
            ?: return null
        if (plan.plannedByModel) return null
        if (plan.skillPlan != null && !plan.skillPlan.isSingleToolStepPlan()) return null
        if (plan.request.arguments.isNotEmpty() || plan.draft.parameters.isNotEmpty()) return null
        if (plan.request.toolName != plan.draft.functionName) return null
        return AgentContinuationCursor(
            sourceRequestId = sourceRequestId,
            completedSegmentCount = completedSegmentCount,
            request = plan.request,
            draft = plan.draft,
            plannedByModel = false,
            fallbackReason = plan.fallbackReason,
            skillPlan = plan.skillPlan,
        )
    }

    private sealed class NextObservationPlan {
        data object None : NextObservationPlan()
        data class Planned(
            val plan: AgentPlan.UseTool,
        ) : NextObservationPlan()

        data class Rejected(
            val reason: String,
        ) : NextObservationPlan()
    }

    private fun planToolIfSupported(
        input: String,
        installedCapabilityProfiles: List<ModelCapabilityProfile>,
        actionModelPath: String?,
    ): AgentPlan? =
        planToolForInput(
            input = input,
            installedCapabilityProfiles = installedCapabilityProfiles,
            actionModelPath = actionModelPath,
            allowDirectSkillPlan = true,
            allowMultiStepSkillPlan = true,
        ) ?: input.initialSequentialActionInput()?.let { firstActionInput ->
            planInitialSequentialSegment(
                input = firstActionInput,
                installedCapabilityProfiles = installedCapabilityProfiles,
                actionModelPath = actionModelPath,
            )
        }

    private fun planLocalOnlySkillBeforeRemote(input: String): AgentPlan? {
        val skillPlan = skillRuntime.plan(input) ?: return null
        return buildInitialToolPlanFromSkill(skillPlan)
    }

    private fun planInitialSequentialSegment(
        input: String,
        installedCapabilityProfiles: List<ModelCapabilityProfile> = emptyList(),
        actionModelPath: String?,
    ): AgentPlan? =
        planCompositeSkillForInitialSequentialSegment(input)
            ?: planToolForInput(
                input = input,
                installedCapabilityProfiles = installedCapabilityProfiles,
                actionModelPath = actionModelPath,
                allowDirectSkillPlan = false,
                allowMultiStepSkillPlan = false,
            )

    private fun planCompositeSkillForInitialSequentialSegment(input: String): AgentPlan? {
        val skillPlan = skillRuntime.plan(input) ?: return null
        if (skillPlan.isSingleToolStepPlan()) return null
        return buildInitialToolPlanFromSkill(skillPlan)
    }

    private fun nextSequentialSegmentInput(
        run: AgentRun,
        completedSegmentCount: Int,
    ): String? =
        traceStore.nextActionInput(run.id)?.immediateSequentialActionText()
            ?: run.input.explicitSequentialActionTextAt(completedSegmentCount)

    private fun planNextCompositeSkillSegment(
        runId: String,
        input: String?,
    ): NextObservationPlan? {
        val skillPlan = input?.let { segmentInput -> skillRuntime.plan(segmentInput) } ?: return null
        if (skillPlan.isSingleToolStepPlan()) return null
        val toolStep = skillPlan.steps.firstOrNull() as? SkillStep.ToolStep
            ?: return null
        if (toolStep.dependsOn.isNotEmpty()) return null
        val validation = skillPlan.validateStructure(toolRegistry)
        if (!validation.isValid) {
            return rejectNextToolPlan(
                runId,
                toolStep.request.rejected("Invalid skill plan: ${validation.errors.joinToString()}"),
            )
        }
        return buildNextToolPlan(
            runId = runId,
            request = toolStep.request,
            draft = toolStep.draft,
            plannedByModel = false,
            fallbackReason = "skill-first",
            skillPlan = skillPlan,
        )
    }

    private fun planToolForInput(
        input: String,
        installedCapabilityProfiles: List<ModelCapabilityProfile>,
        actionModelPath: String?,
        allowDirectSkillPlan: Boolean,
        allowMultiStepSkillPlan: Boolean,
    ): AgentPlan? {
        if (allowDirectSkillPlan) {
            skillRuntime.plan(input)?.let { skillPlan ->
                if (!allowMultiStepSkillPlan && !skillPlan.isSingleToolStepPlan()) return null
                return buildInitialToolPlanFromSkill(skillPlan)
            }
        }
        val intent = actionPlanningRuntime.classifyIntent(input)
        if (!intent.isAction || !intent.confidence.isActionableForAgentPlan()) return null
        val result = actionPlanningRuntime.plan(input, actionModelPath)
        if (
            result.usedModel &&
            !hasMobileActionPlanningModel(
                installedCapabilityProfiles = installedCapabilityProfiles,
            )
        ) {
            return AgentPlan.MissingModel(ModelCapability.MobileAction)
        }
        val draft = result.plan.draft
        if (result.plan.kind != ActionPlanKind.Draft || draft == null) return null
        if (!allowMultiStepSkillPlan && draft.functionName.requiresLocalModelBeforeSequentialTail()) return null
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )
        val skillPlan = skillRuntime.plan(input, draft, request)
        if (!allowMultiStepSkillPlan && skillPlan != null && !skillPlan.isSingleToolStepPlan()) return null
        return buildInitialToolPlan(
            request = request,
            draft = draft,
            plannedByModel = result.usedModel,
            fallbackReason = result.fallbackReason,
            skillPlan = skillPlan,
        )
    }

    private fun hasMobileActionPlanningModel(
        installedCapabilityProfiles: List<ModelCapabilityProfile>,
    ): Boolean =
        installedCapabilityProfiles.any { profile -> profile.supportsMobileActionPlanning }

    private fun ToolRequest.requiresMobileActionProfileForInlineToolCall(): Boolean =
        toolRegistry.specFor(toolName)?.isPublicEvidenceBatchEligible() == false

    private fun String.initialSequentialActionInput(): String? =
        explicitSequentialActionTextAt(0)
            ?.takeIf { explicitSequentialActionTextAt(1) != null }

    private fun SkillPlan.isSingleToolStepPlan(): Boolean =
        steps.singleOrNull() is SkillStep.ToolStep

    private fun draftForRemoteToolRequest(request: ToolRequest): ActionDraft {
        val spec = toolRegistry.specFor(request.toolName)
        return ActionDraft(
            functionName = request.toolName,
            title = spec?.title ?: request.toolName,
            summary = spec?.title?.let { title -> "$title · 远程模型请求" } ?: "远程模型请求执行 ${request.toolName}",
            parameters = request.arguments,
            requiresConfirmation = spec?.confirmationPolicy == com.bytedance.zgx.solin.tool.ConfirmationPolicy.Required,
        )
    }

    private fun buildInitialToolPlanFromSkill(skillPlan: SkillPlan): AgentPlan? {
        val toolStep = skillPlan.steps.firstOrNull() as? SkillStep.ToolStep
            ?: return null
        if (toolStep.dependsOn.isNotEmpty()) return null
        val validation = skillPlan.validateStructure(toolRegistry)
        if (!validation.isValid) {
            return AgentPlan.RejectedTool(
                toolStep.request.rejected("Invalid skill plan: ${validation.errors.joinToString()}"),
            )
        }
        return buildInitialToolPlan(
            request = toolStep.request,
            draft = toolStep.draft,
            plannedByModel = false,
            fallbackReason = "skill-first",
            skillPlan = skillPlan,
        )
    }

    private fun buildInitialToolPlan(
        request: ToolRequest,
        draft: ActionDraft,
        plannedByModel: Boolean,
        fallbackReason: String?,
        skillPlan: SkillPlan?,
    ): AgentPlan {
        invalidSkillPlanRejection(request, skillPlan)?.let { rejection ->
            return AgentPlan.RejectedTool(rejection)
        }
        val rejection = toolRegistry.validate(request)
        if (rejection != null) return AgentPlan.RejectedTool(rejection)
        val spec = toolRegistry.specFor(request.toolName)
            ?: return AgentPlan.RejectedTool(
                request.rejected("Unknown tool: ${request.toolName}"),
            )
        val safetyDecision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = false))
        if (safetyDecision.outcome == SafetyOutcome.Reject) {
            return AgentPlan.RejectedTool(request.rejected(safetyDecision.reason))
        }
        return AgentPlan.UseTool(
            request = request,
            draft = draft.withSafetyDecision(safetyDecision),
            plannedByModel = plannedByModel,
            fallbackReason = fallbackReason,
            skillRequest = skillPlan?.request,
            skillPlan = skillPlan,
            safetyDecision = safetyDecision,
        )
    }

    private fun invalidSkillPlanRejection(
        request: ToolRequest,
        skillPlan: SkillPlan?,
    ): ToolResult? {
        val reason = invalidSkillPlanReason(skillPlan) ?: return null
        return request.rejected(reason)
    }

    private fun invalidSkillPlanReason(skillPlan: SkillPlan?): String? {
        skillPlan ?: return null
        unauthorizedSkillManifestReason(skillPlan)?.let { reason -> return reason }
        val validation = skillPlan.validateStructure(toolRegistry)
        return if (validation.isValid) {
            null
        } else {
            "Invalid skill plan: ${validation.errors.joinToString()}"
        }
    }

    private fun unauthorizedSkillManifestReason(skillPlan: SkillPlan): String? {
        val currentManifest = skillRuntime.manifests()
            .firstOrNull { manifest -> manifest.id == skillPlan.manifest.id }
            ?: return "Skill manifest is not authorized by current runtime: ${skillPlan.manifest.id}"
        if (currentManifest.version != skillPlan.manifest.version) {
            return "Skill manifest version changed: ${skillPlan.manifest.id}"
        }
        if (currentManifest.authorizationContractHash() != skillPlan.manifest.authorizationContractHash()) {
            return "Skill manifest changed: ${skillPlan.manifest.id}"
        }
        return null
    }

    private fun promptWithContextIfUseful(
        input: String,
        memoryHits: List<MemoryHit>,
        deviceContext: DeviceContextSnapshot?,
    ): String {
        val localMemoryHits = memoryHits.filter { hit -> hit.isAvailableForLocalContext() }
        if (localMemoryHits.isEmpty() && deviceContext == null) return input
        val evidenceCards = budgetEvidenceCards(
            cards = evidenceCardsForAnswerContext(localMemoryHits, deviceContext),
            input = input,
        )
        val memoryBlock = evidenceCards
            .filter { card -> card.sourceType == EvidenceSourceType.Memory }
            .joinToString(separator = "\n") { card -> card.toPromptLine() }
            .ifBlank {
                runCatching { memoryIndex.buildContext(localMemoryHits) }.getOrDefault("")
            }
        val safeMemoryBlock = if (memoryBlock.isBlank()) {
            "无"
        } else {
            memoryBlock
        }
        val deviceBlock = evidenceCards
            .firstOrNull { card -> card.sourceType == EvidenceSourceType.DeviceContext }
            ?.toPromptLine(prefix = "")
            ?: "无"
        return """
            请根据用户当前输入的语言回答。只有在以下本地记忆或设备上下文与当前问题明显相关时才使用；如果无关，请忽略，不要复述无关隐私内容。
            以下上下文已按相关性、隐私边界和端侧 token 预算裁剪；被标记为截断的内容只能作为弱证据。
            本地记忆：
            $safeMemoryBlock

            设备上下文：
            $deviceBlock

            用户问题：$input
        """.trimIndent()
    }

    private fun evidenceCardsForAnswerContext(
        memoryHits: List<MemoryHit>,
        deviceContext: DeviceContextSnapshot?,
    ): List<EvidenceCard> =
        buildList {
            memoryHits
                .filter { hit -> hit.isAvailableForLocalContext() }
                .forEach { hit ->
                    add(
                        EvidenceCard(
                            id = "memory:${hit.id}",
                            sourceType = EvidenceSourceType.Memory,
                            privacy = MessagePrivacy.LocalOnly,
                            requiresLocalModel = true,
                            text = hit.text,
                            quality = EvidenceQuality(hit.memoryEvidenceQualityLevel()),
                            tokenEstimate = estimateLocalRuntimeTokens(hit.text),
                        ),
                    )
                }
            val deviceText = deviceContext?.toPromptContext()?.takeIf { it.isNotBlank() }
            if (deviceText != null) {
                add(
                    EvidenceCard(
                        id = "device-context",
                        sourceType = EvidenceSourceType.DeviceContext,
                        privacy = MessagePrivacy.LocalOnly,
                        requiresLocalModel = true,
                        text = deviceText,
                        quality = EvidenceQuality(EvidenceQualityLevel.Medium),
                        tokenEstimate = estimateLocalRuntimeTokens(deviceText),
                    ),
                )
            }
        }

    private fun budgetEvidenceCards(
        cards: List<EvidenceCard>,
        input: String,
    ): List<EvidenceCard> {
        if (cards.isEmpty()) return emptyList()
        var remainingTokens = (
            ANSWER_CONTEXT_TOKEN_BUDGET -
                estimateLocalRuntimeTokens(input) -
                ANSWER_PROMPT_SCAFFOLD_TOKEN_RESERVE
            ).coerceAtLeast(0)
        if (remainingTokens <= 0) return emptyList()
        val selected = mutableListOf<EvidenceCard>()
        cards.sortedWith(evidencePriorityComparator).forEach { card ->
            val cost = card.tokenEstimate.coerceAtLeast(estimateLocalRuntimeTokens(card.text))
            when {
                cost <= remainingTokens -> {
                    selected += card
                    remainingTokens -= cost
                }
                remainingTokens >= MIN_TRUNCATED_EVIDENCE_TOKENS -> {
                    val truncatedText = card.text.takeFirstEstimatedTokens(remainingTokens)
                    if (truncatedText.isNotBlank()) {
                        selected += card.copy(
                            text = truncatedText,
                            truncated = true,
                            tokenEstimate = estimateLocalRuntimeTokens(truncatedText),
                        )
                        remainingTokens = 0
                    }
                }
            }
            if (remainingTokens <= 0) return@forEach
        }
        return selected.sortedWith(evidenceRenderComparator)
    }

    private fun messageForObservation(
        result: ToolResult,
        retryAttempt: Int = 0,
        recoveryAction: AgentRecoveryAction? = null,
    ): String =
        when (result.status) {
            ToolStatus.Succeeded -> if (result.isUnverifiedExternalLaunch()) {
                result.unverifiedExternalLaunchSummary()
            } else {
                buildString {
                    append("工具执行结果：${result.summary}")
                    recoveryAction?.recoveryHintForObservation()?.let { recoveryHint ->
                        append("\n")
                        append(recoveryHint)
                    }
                }
            }
            ToolStatus.Failed -> if (retryAttempt > 0) {
                "工具执行失败，正在重试（第 $retryAttempt 次）：${result.summary}"
            } else {
                "工具执行失败：${result.summary}\n${result.recoveryGuidanceForFailure()}"
            }
            ToolStatus.Rejected -> "工具请求已拒绝：${result.summary}"
            ToolStatus.Cancelled -> "工具执行已取消：${result.summary}"
        }

    private fun traceMessageForObservation(
        request: ToolRequest,
        result: ToolResult,
        retryAttempt: Int,
        recoveryAction: AgentRecoveryAction?,
    ): String {
        val metadata = result.reminderAuditMetadata(request.toolName)
        return when {
            result.isUnverifiedExternalLaunch() -> UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
            metadata.isNotEmpty() -> buildString {
                append("工具执行结果已记录，结果详情已隐藏。")
                recoveryAction?.recoveryHintForObservation()?.let { recoveryHint ->
                    append("\n")
                    append(recoveryHint)
                }
            }
            result.status == ToolStatus.Failed && retryAttempt > 0 ->
                "工具执行失败，正在重试（第 $retryAttempt 次），错误详情已隐藏。"
            result.status == ToolStatus.Failed ->
                "工具执行失败，错误详情已隐藏。${result.traceRecoveryGuidanceForFailure()}"
            result.status == ToolStatus.Rejected ->
                "工具请求已拒绝，详情已隐藏。"
            result.status == ToolStatus.Cancelled ->
                "工具执行已取消，详情已隐藏。"
            else ->
                "工具执行结果已记录，结果详情已隐藏。"
        }
    }

    private fun recoveryActionForObservation(
        request: ToolRequest,
        result: ToolResult,
    ): AgentRecoveryAction? {
        if (result.status != ToolStatus.Succeeded) return null
        if (request.toolName != MobileActionFunctions.SCHEDULE_REMINDER) return null
        val recoveryToolName = result.data["recoveryToolName"]?.takeIf { it.isNotBlank() } ?: return null
        if (recoveryToolName != MobileActionFunctions.CANCEL_REMINDER) return null
        val recoveryTaskId = result.data["recoveryTaskId"]?.takeIf { it.isSafeRecoveryTaskId() } ?: return null
        val recoveryRequest = ToolRequest(
            toolName = MobileActionFunctions.CANCEL_REMINDER,
            arguments = mapOf("taskId" to recoveryTaskId),
            reason = "撤销提醒任务：$recoveryTaskId",
        )
        return AgentRecoveryAction(
            sourceRequestId = result.requestId,
            sourceToolName = request.toolName,
            request = recoveryRequest,
            draft = ActionDraft(
                functionName = MobileActionFunctions.CANCEL_REMINDER,
                title = "撤销提醒",
                summary = "将取消提醒任务：$recoveryTaskId",
                parameters = recoveryRequest.arguments,
                requiresConfirmation = true,
            ),
        )
    }

    private fun String.isSafeRecoveryTaskId(): Boolean =
        matches(Regex("""task-[A-Za-z0-9_-]+"""))

    private data class NormalizedReminderRecovery(
        val taskId: String,
        val request: ToolRequest,
        val draft: ActionDraft,
    )

    private fun AgentRecoveryAction.normalizedReminderRecovery(): NormalizedReminderRecovery? {
        if (sourceRequestId.isBlank()) return null
        if (sourceToolName != MobileActionFunctions.SCHEDULE_REMINDER) return null
        if (request.id.isBlank()) return null
        if (request.toolName != MobileActionFunctions.CANCEL_REMINDER) return null
        if (draft.functionName != MobileActionFunctions.CANCEL_REMINDER) return null
        val taskId = request.arguments["taskId"]?.takeIf { it.isSafeRecoveryTaskId() } ?: return null
        val arguments = mapOf("taskId" to taskId)
        val normalizedRequest = request.copy(
            toolName = MobileActionFunctions.CANCEL_REMINDER,
            arguments = arguments,
            reason = "撤销提醒任务：$taskId",
        )
        val normalizedDraft = ActionDraft(
            functionName = MobileActionFunctions.CANCEL_REMINDER,
            title = "撤销提醒",
            summary = "将取消提醒任务：$taskId",
            parameters = arguments,
            requiresConfirmation = true,
        )
        return NormalizedReminderRecovery(
            taskId = taskId,
            request = normalizedRequest,
            draft = normalizedDraft,
        )
    }

    private fun AgentRecoveryAction.recoveryHintForObservation(): String? =
        when (request.toolName) {
            MobileActionFunctions.CANCEL_REMINDER ->
                "如需撤销该提醒，请再次确认执行 ${request.toolName}，taskId=${request.arguments["taskId"]}。"

            else -> null
        }

    private fun ToolResult.recoveryGuidanceForFailure(): String =
        when (error?.code) {
            ToolErrorCode.PermissionDenied ->
                "下一步：请授权所需权限后重试，或改用不需要该权限的请求。"
            ToolErrorCode.MissingArgument, ToolErrorCode.InvalidRequest, ToolErrorCode.InvalidResult ->
                "下一步：请补充或改正必要信息后重试。"
            ToolErrorCode.UnknownTool, ToolErrorCode.NoActivityFound ->
                "下一步：请确认相关系统能力可用后重试，或换一种方式完成。"
            ToolErrorCode.UserCancelled ->
                "下一步：操作已取消；需要继续时请重新发起。"
            ToolErrorCode.ExecutionFailed, null ->
                if (retryable) {
                    "下一步：自动重试次数已用尽；请稍后重试，或简化请求后再试。"
                } else {
                    "下一步：请调整请求后重试，或改用手动方式完成。"
                }
        }

    private fun ToolResult.traceRecoveryGuidanceForFailure(): String =
        when (error?.code) {
            ToolErrorCode.PermissionDenied -> " 下一步：授权后重试。"
            ToolErrorCode.MissingArgument, ToolErrorCode.InvalidRequest, ToolErrorCode.InvalidResult ->
                " 下一步：补充必要信息后重试。"
            ToolErrorCode.UnknownTool, ToolErrorCode.NoActivityFound ->
                " 下一步：确认系统能力可用后重试。"
            ToolErrorCode.UserCancelled -> " 下一步：需要继续时重新发起。"
            ToolErrorCode.ExecutionFailed, null -> " 下一步：调整请求或稍后重试。"
        }

    private fun ToolResult.auditSummaryForObservation(request: ToolRequest): String {
        val metadata = reminderAuditMetadata(request.toolName)
        return when {
            metadata.isNotEmpty() -> metadata.joinToString(separator = "; ")
            isUnverifiedExternalLaunch() -> UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
            summary.startsWith(EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX) -> EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX
            else -> TOOL_OBSERVATION_AUDIT_SUMMARY
        }
    }

    private fun ToolResult.reminderAuditMetadata(toolName: String): List<String> {
        if (toolName != MobileActionFunctions.SCHEDULE_REMINDER &&
            toolName != MobileActionFunctions.CANCEL_REMINDER
        ) {
            return emptyList()
        }
        return listOfNotNull(
            data["taskId"]?.takeIf { it.isSafeRecoveryTaskId() }?.let { "taskId=$it" },
            data["taskStatus"]?.takeIf { it.matches(Regex("""[A-Za-z]+""")) }?.let { "taskStatus=$it" },
            data["triggerAtMillis"]?.takeIf { it.matches(Regex("""\d+""")) }?.let { "triggerAtMillis=$it" },
            data["recoveryToolName"]
                ?.takeIf { it == MobileActionFunctions.CANCEL_REMINDER }
                ?.let { "recoveryToolName=$it" },
            data["recoveryTaskId"]?.takeIf { it.isSafeRecoveryTaskId() }?.let { "recoveryTaskId=$it" },
        )
    }

    private fun nextRetryAttempt(runId: String, result: ToolResult): Int {
        if (result.status != ToolStatus.Failed || !result.retryable) return 0
        if (result.error?.code == ToolErrorCode.PermissionDenied) return 0
        val request = requestForResult(runId, result) ?: return 0
        if (!request.allowsAutomaticRetry()) return 0
        val completedAttempts = traceStore.steps(runId)
            .asSequence()
            .filterIsInstance<AgentStep.ToolRetryScheduled>()
            .count { step -> step.request.id == result.requestId }
        if (completedAttempts >= maxToolRetryAttempts) return 0
        return completedAttempts + 1
    }

    private fun requestForResult(runId: String, result: ToolResult): ToolRequest? =
        traceStore.steps(runId)
            .asSequence()
            .mapNotNull { step ->
                when (step) {
                    is AgentStep.ToolRequested -> step.request
                    is AgentStep.ToolRetryScheduled -> step.request
                    else -> null
                }
            }
            .lastOrNull { request -> request.id == result.requestId }

    private fun ToolRequest.allowsAutomaticRetry(): Boolean {
        val spec = toolRegistry.specFor(toolName) ?: return false
        return spec.isPublicEvidenceBatchEligible()
    }

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
                    canPlanNextToolBeforeModel = request.canPlanLocalToolFromCurrentScreenObservation(run),
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
                    canPlanNextToolBeforeModel = request.canPlanLocalToolFromCurrentScreenObservation(run),
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
            只以工具公开证据为依据，并以每条证据的 retrievedAt 或 resultsJson.retrievedAt 判断时效；同一事实存在多条证据时，以 retrievedAt 最新且最相关的证据为准。
            涉及“最新”“目前”“当前”“今天”等时效性问题时，必须优先使用最新 retrievedAt 的工具证据；不得用模型训练知识、旧知识或未给出的网页内容补全空白。
            最终答案必须列出使用的来源/链接（来自 sources、results.url 或 source 字段）；如果来源或证据不足，请明确说明证据不足。
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

    private fun ToolRequest.publicEvidencePromptBlock(result: ToolResult): PublicEvidencePromptBlock =
        PublicEvidencePromptBlock(
            toolName = toolName,
            argumentBlock = argumentsPromptBlock(),
            summary = result.summary,
            dataBlock = result.promptDataBlock(),
        )

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
            mapIndexed { index, block ->
                """
                    工具 ${index + 1}
                    工具名称：${block.toolName}
                    工具参数：
                    ${block.argumentBlock}
                    工具观察：${block.summary.boundedPromptValue()}
                    工具公开数据：
                    ${block.dataBlock}
                """.trimIndent()
            }.joinToString(separator = "\n\n")
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
            canPlanNextToolBeforeModel = request.canPlanLocalToolFromCurrentScreenObservation(run),
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
        if (invalidSkillPlanReason(skillPlan) != null) return null
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
            RemoteToolScope.PublicEvidenceOnly -> isPublicEvidenceBatchEligible()
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
        val activeSkillPlan = skillPlan ?: this.skillPlan ?: latestSkillPlan(runId) ?: return this
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

    private fun SkillPlan.isLowRiskAppControlSkill(): Boolean =
        manifest.lowRiskAppControlEligible

    private fun SkillPlan.hasConfirmedToolStep(runId: String): Boolean {
        val stepRequestIds = toolStepRequestIds()
        return traceStore.steps(runId).any { step ->
            step is AgentStep.UserConfirmed && step.requestId in stepRequestIds
        }
    }

    private fun SkillPlan.hasToolStep(request: ToolRequest): Boolean =
        steps.filterIsInstance<SkillStep.ToolStep>()
            .any { step -> step.request.id == request.id && step.request.toolName == request.toolName }

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
        valueFreeCompletedStepFrontiersByRunId.remove(runId)
        remoteToolScopesByRunId.remove(runId)
        remoteExposedToolNamesByRunId.remove(runId)
        lowRiskDeviceActionConfirmationBypassByRunId.remove(runId)
        installedCapabilityProfilesByRunId.remove(runId)
    }

    private fun runUsedDeviceControlSession(runId: String): Boolean =
        toolRequestsFor(runId).any { request ->
            toolRegistry.startsDeviceControlSession(request.toolName) ||
                request.isDeviceControlTool()
        }

    private data class AppControlSession(
        val runId: String,
        val skillId: String,
        val targetPackage: String?,
        val lowRisk: Boolean,
        val stepCount: Int,
        val checkpointRequired: Boolean,
    )

    private data class ToolObservationContinuation(
        val prompt: String,
        val requiresLocalModel: Boolean,
        val remoteToolScope: RemoteToolScope = RemoteToolScope.PublicEvidenceOnly,
        val canPlanNextToolBeforeModel: Boolean = false,
        val blocksSequentialTail: Boolean = false,
    )

    private data class PublicEvidencePromptBlock(
        val toolName: String,
        val argumentBlock: String,
        val summary: String,
        val dataBlock: String,
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

    private val pendingExternalOutcomeRestoreStates = setOf(
        AgentRunState.AwaitingExternalOutcome,
        AgentRunState.Completed,
    )

    private fun ToolResult.requiresLocalModelContinuation(): Boolean =
        data["requiresLocalModel"]?.toBooleanStrictOrNull() == true ||
            data["privacy"] == MessagePrivacy.LocalOnly.name

    private fun ToolResult.withAttemptedToolNames(requests: List<ToolRequest>): ToolResult =
        copy(
            data = data + mapOf(
                "attemptedToolNames" to requests.joinToString(",") { request -> request.toolName },
            ),
        )

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
        auditSink.record(
            ToolAuditEvent(
                runId = runId,
                requestId = request.id,
                toolName = request.toolName,
                skillId = skillId,
                eventType = eventType,
                status = status,
                riskLevel = spec?.riskLevel,
                permissions = spec?.permissions.orEmpty(),
                summary = summary,
            ),
        )
    }

    private fun auditRejectedTool(runId: String, result: ToolResult) {
        val toolName = result.data["toolName"]
        val spec = toolName?.let(toolRegistry::specFor)
        auditSink.record(
            ToolAuditEvent(
                runId = runId,
                requestId = result.requestId,
                toolName = toolName,
                skillId = result.requestId.takeIf { it.isNotBlank() }?.let { requestId ->
                    skillIdForRequest(runId, requestId)
                },
                eventType = ToolAuditEventType.ToolRejected,
                status = result.status,
                riskLevel = spec?.riskLevel,
                permissions = spec?.permissions.orEmpty(),
                summary = result.summary,
            ),
        )
    }

    private fun toolRequestFor(runId: String, requestId: String): ToolRequest? =
        toolRequestsFor(runId)
            .firstOrNull { request -> request.id == requestId }

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
            .mapNotNull { step -> step.restoredUnverifiedExternalResultOrNull() }
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
                    .mapNotNull { step -> step.restoredToolRequestOrNull() }
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
        traceStore.steps(runId)
            .asReversed()
            .asSequence()
            .mapNotNull { step -> (step as? AgentStep.UserConfirmationRequested)?.request }
            .firstOrNull()

    private fun pendingToolRequest(runId: String, requestId: String): ToolRequest? {
        val restoredSnapshot = traceStore.latestPendingConfirmation()
            ?.takeIf { snapshot -> snapshot.run.id == runId && snapshot.request.id == requestId }
        if (restoredSnapshot != null) {
            if (!restoredPendingConfirmationIsAuthorized(restoredSnapshot)) return null
            rememberValueFreeFrontier(restoredSnapshot)
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
        traceStore.steps(runId)
            .asReversed()
            .asSequence()
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
        traceStore.steps(runId)
            .asSequence()
            .mapNotNull { step -> (step as? AgentStep.SkillPlanned)?.plan }
            .lastOrNull()

    private fun AgentTraceStepSummary.restoredToolRequestSummaryOrNull(): RestoredToolRequestSummary? {
        val json = jsonObjectOrNull() ?: return null
        val requestId = json.optString("requestId").takeIf { it.isNotBlank() } ?: return null
        val toolName = json.optString("toolName").takeIf { it.isNotBlank() } ?: return null
        val title = json.optString("draftTitle").takeIf { it.isNotBlank() } ?: toolName
        return RestoredToolRequestSummary(
            requestId = requestId,
            toolName = toolName,
            title = title,
        )
    }

    private fun AgentTraceStepSummary.restoredToolRequestOrNull(): ToolRequest? =
        restoredToolRequestSummaryOrNull()?.let { request ->
            ToolRequest(
                id = request.requestId,
                toolName = request.toolName,
                arguments = emptyMap(),
                reason = "",
            )
        }

    private fun AgentTraceStepSummary.restoredExternalObservationOrNull(): RestoredExternalObservation? {
        val result = restoredUnverifiedExternalResultOrNull() ?: return null
        return RestoredExternalObservation(
            requestId = result.requestId,
            result = result,
        )
    }

    private fun AgentTraceStepSummary.restoredUnverifiedExternalResultOrNull(): ToolResult? {
        val json = jsonObjectOrNull() ?: return null
        if (json.optString("status") != ToolStatus.Succeeded.name) return null
        val requestId = json.optString("requestId").takeIf { it.isNotBlank() } ?: return null
        val metadata = json.optJSONObject("completionMetadata") ?: return null
        val data = buildMap {
            metadata.keys().forEach { key ->
                val value = metadata.optString(key)
                if (value.isNotBlank()) put(key, value)
            }
        }
        val result = ToolResult(
            requestId = requestId,
            status = ToolStatus.Succeeded,
            summary = UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX,
            data = data,
        )
        return result.takeIf { it.isUnverifiedExternalLaunch() }
    }

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
        return json.optInt("toolStepCount", -1)
            .takeIf { count -> count >= 0 }
            ?: json.optInt("stepCount", -1).takeIf { count -> count >= 0 }
    }

    private fun AgentTraceStepSummary.jsonObjectOrNull(): JSONObject? =
        runCatching { JSONObject(json) }.getOrNull()

    private data class SummarySkillSegment(
        val key: String,
        val remainingToolSteps: Int,
    ) {
        fun afterTool(): SummarySkillSegment? =
            if (remainingToolSteps <= 1) null else copy(remainingToolSteps = remainingToolSteps - 1)
    }

    private data class RestoredToolRequestSummary(
        val requestId: String,
        val toolName: String,
        val title: String,
    ) {
        fun isLowRiskRestoredExternalOutcomePopupSkippable(
            result: ToolResult,
            toolRegistry: ToolRegistry,
        ): Boolean =
            toolRegistry.isLowRiskRestoredExternalOutcomePopupSkippable(toolName, result)
    }

    private data class RestoredExternalObservation(
        val requestId: String,
        val result: ToolResult,
    )

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
}

fun AgentLoopResult.toAssistantRoute(): AssistantRoute =
    when (val planned = plan) {
        is AgentPlan.Answer -> AssistantRoute.Chat(
            runId = run.id,
            promptForModel = planned.promptForModel,
            memoryHits = planned.memoryHits,
            deviceContext = planned.deviceContext,
        )

        is AgentPlan.UseTool -> AssistantRoute.Action(
            runId = run.id,
            toolRequest = planned.request,
            draft = planned.draft,
            plannedByModel = planned.plannedByModel,
            fallbackReason = planned.fallbackReason,
            skillId = planned.skillRequest?.skillId,
            requiresUserConfirmation = planned.requiresUserConfirmation(),
        )

        is AgentPlan.RejectedTool -> AssistantRoute.ToolRejected(planned.result.summary)

        is AgentPlan.MissingModel -> AssistantRoute.MissingModel(planned.capability)
    }

private fun AgentPlan.UseTool.requiresUserConfirmation(): Boolean =
    safetyDecision.outcome == SafetyOutcome.RequireConfirmation

private fun AgentPlan.UseTool.toIntentRoutingDecision(input: String): IntentRoutingDecision {
    val path = routingPath()
    return IntentRoutingDecision(
        input = input,
        selectedPath = path,
        selectedToolName = request.toolName,
        selectedSkillId = skillRequest?.skillId,
        priority = path.routingPriority(),
        accepted = true,
        confidence = ActionIntentConfidence.High,
        rejectionReasons = emptyList(),
        requiresConfirmation = requiresUserConfirmation(),
    )
}

private fun AgentPlan.UseTool.routingPath(): IntentRoutingPath =
    when (fallbackReason) {
        "skill-first",
        "skill model step" -> IntentRoutingPath.SkillFirst
        "remote tool call",
        "remote tool batch" -> IntentRoutingPath.RemoteToolPlanning
        "local model tool call" -> IntentRoutingPath.ModelToolCall
        else -> IntentRoutingPath.ActionPlanner
    }

private fun AgentTraceStore.appendRejectedRoutingDecision(
    runId: String,
    path: IntentRoutingPath,
    toolName: String?,
    reason: String,
) {
    val run = run(runId) ?: return
    appendStep(
        runId,
        AgentStep.IntentRouted(
            IntentRoutingDecision(
                input = run.input,
                selectedPath = path,
                selectedToolName = toolName?.takeIf { it.isNotBlank() },
                selectedSkillId = null,
                priority = path.routingPriority(),
                accepted = false,
                confidence = ActionIntentConfidence.None,
                rejectionReasons = listOf(reason.toRoutingReasonSlug()),
                requiresConfirmation = null,
            ),
        ),
    )
}

private fun IntentRoutingPath.routingPriority(): Int =
    when (this) {
        IntentRoutingPath.SkillFirst -> 100
        IntentRoutingPath.ActionPlanner -> 80
        IntentRoutingPath.RemoteToolPlanning -> 70
        IntentRoutingPath.ModelToolCall -> 60
        IntentRoutingPath.NoAction -> 0
    }

private fun String.toRoutingReasonSlug(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "tool_rejected" }

private fun ActionDraft.withSafetyDecision(safetyDecision: SafetyDecision): ActionDraft =
    if (safetyDecision.outcome == SafetyOutcome.RequireConfirmation && !requiresConfirmation) {
        copy(requiresConfirmation = true)
    } else {
        this
    }

private fun AgentPlan.UseTool.nextExecutionState(): AgentRunState =
    if (requiresUserConfirmation()) {
        AgentRunState.AwaitingUserConfirmation
    } else {
        AgentRunState.ExecutingTool
    }

private fun String.boundedPromptValue(maxLength: Int = 2_000): String =
    if (length <= maxLength) this else take(maxLength).trimEnd() + "..."

private val auditWhitespaceRegex = Regex("\\s+")

private fun String.compactAuditValue(maxLength: Int): String =
    trim().replace(auditWhitespaceRegex, " ").boundedPromptValue(maxLength)

private val evidencePriorityComparator: Comparator<EvidenceCard> =
    compareBy<EvidenceCard> { it.sourceType.priorityForPromptBudget() }
        .thenByDescending { it.quality.level.priorityForPromptBudget() }

private val evidenceRenderComparator: Comparator<EvidenceCard> =
    compareBy { it.sourceType.priorityForPromptRender() }

private fun EvidenceSourceType.priorityForPromptBudget(): Int =
    when (this) {
        EvidenceSourceType.Memory -> 0
        EvidenceSourceType.DeviceContext -> 1
        EvidenceSourceType.UserPrompt -> 2
        EvidenceSourceType.ToolResult,
        EvidenceSourceType.OcrText,
        EvidenceSourceType.FilePreview,
        EvidenceSourceType.ImageAttachment,
        EvidenceSourceType.PublicWeb,
        EvidenceSourceType.ProtectedSource -> 3
    }

private fun EvidenceSourceType.priorityForPromptRender(): Int =
    when (this) {
        EvidenceSourceType.Memory -> 0
        EvidenceSourceType.DeviceContext -> 1
        else -> 2
    }

private fun EvidenceQualityLevel.priorityForPromptBudget(): Int =
    when (this) {
        EvidenceQualityLevel.High -> 3
        EvidenceQualityLevel.Medium -> 2
        EvidenceQualityLevel.Unknown -> 1
        EvidenceQualityLevel.Low -> 0
    }

private fun MemoryHit.memoryEvidenceQualityLevel(): EvidenceQualityLevel =
    when {
        finalScore >= 0.70f -> EvidenceQualityLevel.High
        finalScore >= 0.25f -> EvidenceQualityLevel.Medium
        else -> EvidenceQualityLevel.Low
    }

private fun EvidenceCard.toPromptLine(prefix: String = "- "): String {
    val quality = when (this.quality.level) {
        EvidenceQualityLevel.High -> "高"
        EvidenceQualityLevel.Medium -> "中"
        EvidenceQualityLevel.Low -> "低"
        EvidenceQualityLevel.Unknown -> "未知"
    }
    val truncatedLabel = if (truncated) "，已截断" else ""
    return "$prefix[证据=${sourceType.name}，质量=$quality$truncatedLabel] $text"
}

private fun String.takeFirstEstimatedTokens(maxTokens: Int): String {
    if (maxTokens <= 0) return ""
    var usedTokens = 0
    val builder = StringBuilder()
    for (char in this) {
        val cost = 1
        if (usedTokens + cost > maxTokens) break
        builder.append(char)
        usedTokens += cost
    }
    return builder.toString().trimEnd()
}
