package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.action.ModelToolOutputParseResult
import com.bytedance.zgx.pocketmind.audit.NoOpToolAuditSink
import com.bytedance.zgx.pocketmind.audit.ToolAuditEvent
import com.bytedance.zgx.pocketmind.audit.ToolAuditEventType
import com.bytedance.zgx.pocketmind.audit.ToolAuditSink
import com.bytedance.zgx.pocketmind.device.DeviceContextSnapshot
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.memory.MemoryIndex
import com.bytedance.zgx.pocketmind.safety.SafetyContext
import com.bytedance.zgx.pocketmind.safety.SafetyOutcome
import com.bytedance.zgx.pocketmind.safety.SafetyPolicy
import com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntime
import com.bytedance.zgx.pocketmind.skill.SkillModelOutputProgression
import com.bytedance.zgx.pocketmind.skill.SkillModelStepBinding
import com.bytedance.zgx.pocketmind.skill.SkillPlan
import com.bytedance.zgx.pocketmind.skill.SkillRuntime
import com.bytedance.zgx.pocketmind.skill.SkillRunProgressor
import com.bytedance.zgx.pocketmind.skill.SkillStep
import com.bytedance.zgx.pocketmind.skill.validateStructure
import com.bytedance.zgx.pocketmind.skill.valueFreeCheckpointForPendingTool
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import com.bytedance.zgx.pocketmind.tool.cancelled
import com.bytedance.zgx.pocketmind.tool.isUnverifiedExternalLaunch
import com.bytedance.zgx.pocketmind.tool.rejected
import com.bytedance.zgx.pocketmind.tool.unverifiedExternalLaunchSummary

private const val REDACTED_AGENT_RUN_INPUT_VALUE = "[redacted]"

private val INITIAL_SEQUENTIAL_CONTINUATION_TOOL_NAMES = setOf(
    MobileActionFunctions.READ_CLIPBOARD,
    MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
    MobileActionFunctions.READ_RECENT_IMAGE_OCR,
    MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
)

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
) {
    private val skillProgressor = SkillRunProgressor(toolRegistry = toolRegistry)

    @Suppress("UNUSED_PARAMETER")
    fun runOnce(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
        actionModelPath: String? = null,
        deviceContext: DeviceContextSnapshot? = null,
        sessionId: String? = null,
    ): AgentLoopResult {
        val createdRun = traceStore.createRun(input, sessionId)
        traceStore.updateState(createdRun.id, AgentRunState.LoadingContext)

        val memoryHits = if (memoryEnabled) {
            runCatching { memoryIndex.search(input, topK = 3) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        traceStore.appendStep(createdRun.id, AgentStep.ContextLoaded(memoryHits, deviceContext))
        traceStore.updateState(createdRun.id, AgentRunState.Planning)

        when (val toolPlan = planToolIfSupported(input, actionModelPath)) {
            is AgentPlan.UseTool -> {
                return requestToolConfirmation(createdRun, toolPlan)
            }

            is AgentPlan.RejectedTool -> {
                traceStore.appendStep(createdRun.id, AgentStep.ModelPlanned(toolPlan))
                traceStore.appendStep(createdRun.id, AgentStep.ToolRejected(toolPlan.result))
                auditRejectedTool(createdRun.id, toolPlan.result)
                val failedRun = traceStore.updateState(createdRun.id, AgentRunState.Failed)
                return AgentLoopResult(
                    run = failedRun,
                    plan = toolPlan,
                    steps = traceStore.steps(createdRun.id),
                )
            }

            null -> Unit
            else -> Unit
        }

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

    fun confirmToolRequest(runId: String, requestId: String): AgentRun? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.AwaitingUserConfirmation) return run
        val request = pendingToolRequest(runId, requestId)
            ?: return run
        val spec = toolRegistry.specFor(request.toolName)
        if (spec == null) {
            val rejection = request.rejected("Unknown tool: ${request.toolName}")
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRejectedTool(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return traceStore.updateState(runId, AgentRunState.Failed)
        }
        val safetyDecision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = true))
        if (safetyDecision.outcome == SafetyOutcome.Reject) {
            val rejection = request.rejected(safetyDecision.reason)
            traceStore.appendStep(runId, AgentStep.SafetyChecked(safetyDecision))
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejection))
            auditRejectedTool(runId, rejection)
            traceStore.clearPendingConfirmation(runId, requestId)
            return traceStore.updateState(runId, AgentRunState.Failed)
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

    fun observeModelResult(runId: String, text: String): AgentModelObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.GeneratingAnswer) return null
        val nextToolPlan = when {
            text.isNotBlank() -> planNextToolAfterModelResult(run, text.trim())
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
            is AgentObservationDecision.PlanNextTool -> AgentRunState.AwaitingUserConfirmation
            is AgentObservationDecision.Fail -> AgentRunState.Failed
            AgentObservationDecision.Cancel -> AgentRunState.Cancelled
            is AgentObservationDecision.ContinueWithModel -> AgentRunState.GeneratingAnswer
            is AgentObservationDecision.RetryTool -> AgentRunState.RetryingTool
        }
        val updatedRun = traceStore.updateState(runId, finalState)
        if (decision is AgentObservationDecision.PlanNextTool) {
            traceStore.savePendingConfirmation(decision.plan.toPendingSnapshot(updatedRun))
        }
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    fun observeModelToolRequest(runId: String, request: ToolRequest): AgentModelObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.GeneratingAnswer) return null
        if (toolRequestFor(runId, request.id) != null) {
            val rejected = request.rejected("Model tool request id already exists: ${request.id}")
            traceStore.appendStep(runId, AgentStep.ModelPlanned(AgentPlan.RejectedTool(rejected)))
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejected))
            auditRejectedTool(runId, rejected)
            val decision = AgentObservationDecision.Fail(rejected.summary)
            traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
            val updatedRun = traceStore.updateState(runId, AgentRunState.Failed)
            return AgentModelObservationResult(
                run = updatedRun,
                decision = decision,
                steps = traceStore.steps(runId),
            )
        }
        val draft = draftForRemoteToolRequest(request)
        val plan = buildInitialToolPlan(
            request = request,
            draft = draft,
            plannedByModel = true,
            fallbackReason = "remote tool call",
            skillPlan = null,
        )
        return when (plan) {
            is AgentPlan.UseTool -> {
                appendToolPlanSteps(runId, plan)
                val decision = AgentObservationDecision.PlanNextTool(
                    plan = plan,
                    reason = "Remote model requested a tool call.",
                )
                traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
                val updatedRun = traceStore.updateState(runId, AgentRunState.AwaitingUserConfirmation)
                traceStore.savePendingConfirmation(plan.toPendingSnapshot(updatedRun))
                AgentModelObservationResult(
                    run = updatedRun,
                    decision = decision,
                    steps = traceStore.steps(runId),
                )
            }

            is AgentPlan.RejectedTool -> {
                traceStore.appendStep(runId, AgentStep.ModelPlanned(plan))
                traceStore.appendStep(runId, AgentStep.ToolRejected(plan.result))
                auditRejectedTool(runId, plan.result)
                val decision = AgentObservationDecision.Fail(plan.result.summary)
                traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
                val updatedRun = traceStore.updateState(runId, AgentRunState.Failed)
                AgentModelObservationResult(
                    run = updatedRun,
                    decision = decision,
                    steps = traceStore.steps(runId),
                )
            }

            else -> null
        }
    }

    private fun observeToolResultInternal(
        runId: String,
        result: ToolResult,
        allowedStates: Set<AgentRunState>,
    ): AgentObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state !in allowedStates) return null
        if (
            run.state in setOf(AgentRunState.ExecutingTool, AgentRunState.RetryingTool) &&
            latestConfirmedRequestId(runId) != result.requestId
        ) {
            return null
        }
        val request = toolRequestFor(runId, result.requestId) ?: return null
        val safeResult = toolRegistry.validateResult(request, result) ?: result
        traceStore.updateState(runId, AgentRunState.Observing)
        val continuation = continuationForToolObservation(run, request, safeResult)
        val continuationPrompt = continuation?.prompt
        val continuationRequiresLocalModel = continuation?.requiresLocalModel ?: false
        val observedResult = safeResult.redactedForTrace(request)
        val retryAttempt = nextRetryAttempt(runId, observedResult)
        val retryRequest = if (retryAttempt > 0) request else null
        traceStore.appendStep(runId, AgentStep.ToolObserved(observedResult))
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
        traceStore.appendStep(runId, AgentStep.AssistantResponded(assistantMessage))
        val nextToolPlan = if (
            observedResult.status == ToolStatus.Succeeded &&
            !observedResult.isUnverifiedExternalLaunch() &&
            retryRequest == null &&
            continuationPrompt == null
        ) {
            planNextToolAfterObservation(run, request, observedResult)
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
                summary = "Retry ${decision.attempt} scheduled: ${observedResult.summary}",
            )
        }
        val finalState = when (decision) {
            AgentObservationDecision.Complete -> AgentRunState.Completed
            is AgentObservationDecision.ContinueWithModel -> AgentRunState.GeneratingAnswer
            is AgentObservationDecision.RetryTool -> AgentRunState.RetryingTool
            is AgentObservationDecision.PlanNextTool -> AgentRunState.AwaitingUserConfirmation
            is AgentObservationDecision.Fail -> AgentRunState.Failed
            AgentObservationDecision.Cancel -> AgentRunState.Cancelled
        }
        val updatedRun = traceStore.updateState(runId, finalState)
        if (decision is AgentObservationDecision.PlanNextTool) {
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

            result.status == ToolStatus.Succeeded && continuationPrompt != null ->
                AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = continuationRequiresLocalModel,
                    reason = result.summary,
                )

            result.status == ToolStatus.Succeeded && nextToolPlan is NextObservationPlan.Planned ->
                AgentObservationDecision.PlanNextTool(
                    plan = nextToolPlan.plan,
                    reason = result.summary,
                )

            result.status == ToolStatus.Succeeded && nextToolPlan is NextObservationPlan.Rejected ->
                AgentObservationDecision.Fail(nextToolPlan.reason)

            result.status == ToolStatus.Succeeded -> AgentObservationDecision.Complete
            result.status == ToolStatus.Cancelled -> AgentObservationDecision.Cancel
            else -> AgentObservationDecision.Fail(result.summary)
        }

    private fun planNextToolAfterObservation(
        run: AgentRun,
        request: ToolRequest,
        result: ToolResult,
    ): NextObservationPlan {
        val replan = observationReplanner.planNext(
            AgentObservationReplanContext(
                run = run,
                previousRequest = request,
                observedResult = result,
                priorRequests = toolRequestsFor(run.id),
                nextActionInput = traceStore.nextActionInput(run.id),
            ),
        ) ?: return NextObservationPlan.None
        val skillPlan = skillRuntime.plan(run.input, replan.draft, replan.request)
        return buildNextToolPlan(
            runId = run.id,
            request = replan.request,
            draft = replan.draft,
            plannedByModel = replan.plannedByModel,
            fallbackReason = replan.fallbackReason,
            skillPlan = skillPlan,
        )
    }

    private fun planNextToolAfterModelResult(
        run: AgentRun,
        text: String,
    ): NextObservationPlan {
        val skillPlan = latestSkillPlan(run.id)
            ?: return planExplicitToolCallAfterModelResult(run, text)
        val requestedRequestIds = toolRequestsFor(run.id).mapTo(mutableSetOf()) { request -> request.id }
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
        return NextObservationPlan.Planned(
            AgentPlan.UseTool(
                request = request,
                draft = draft,
                plannedByModel = plannedByModel,
                fallbackReason = fallbackReason,
                skillRequest = skillPlan?.request,
                skillPlan = skillPlan,
                safetyDecision = safetyDecision,
            ),
        )
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
        traceStore.appendStep(runId, AgentStep.ModelPlanned(plan))
        plan.skillRequest?.let { skillRequest ->
            traceStore.appendStep(runId, AgentStep.SkillPlanned(skillRequest, plan.skillPlan))
        }
        traceStore.appendStep(runId, AgentStep.SafetyChecked(plan.safetyDecision))
        traceStore.appendStep(runId, AgentStep.ToolRequested(plan.request, plan.draft))
        auditToolEvent(runId, plan, ToolAuditEventType.ToolPlanned, null, plan.request.reason)
        traceStore.appendStep(runId, AgentStep.UserConfirmationRequested(plan.request, plan.draft))
        auditToolEvent(
            runId = runId,
            plan = plan,
            eventType = ToolAuditEventType.ConfirmationRequested,
            status = null,
            summary = plan.safetyDecision.reason,
        )
    }

    private fun requestToolConfirmation(
        run: AgentRun,
        plan: AgentPlan.UseTool,
    ): AgentLoopResult {
        appendToolPlanSteps(run.id, plan)
        val waitingRun = traceStore.updateState(run.id, AgentRunState.AwaitingUserConfirmation)
        traceStore.savePendingConfirmation(plan.toPendingSnapshot(waitingRun))
        return AgentLoopResult(
            run = waitingRun,
            plan = plan,
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
        return AgentLoopResult(
            run = failedRun,
            plan = plan,
            steps = traceStore.steps(run.id),
        )
    }

    fun latestPendingConfirmation(sessionId: String? = null): PendingToolConfirmationSnapshot? =
        traceStore.latestPendingConfirmation(sessionId)

    private fun AgentPlan.UseTool.toPendingSnapshot(run: AgentRun): PendingToolConfirmationSnapshot =
        PendingToolConfirmationSnapshot(
            run = run,
            request = request,
            draft = draft,
            skillId = skillRequest?.skillId,
            skillPlan = skillPlan,
            plannedByModel = plannedByModel,
            fallbackReason = fallbackReason,
            nextActionInput = run.input.explicitSequentialActionTextAt(toolRequestsFor(run.id).size),
            skillRunCheckpoint = skillPlan?.valueFreeCheckpointForPendingTool(
                runId = run.id,
                pendingRequest = request,
                toolRegistry = toolRegistry,
            ),
        )

    private sealed class NextObservationPlan {
        data object None : NextObservationPlan()
        data class Planned(
            val plan: AgentPlan.UseTool,
        ) : NextObservationPlan()

        data class Rejected(
            val reason: String,
        ) : NextObservationPlan()
    }

    private fun planToolIfSupported(input: String, actionModelPath: String?): AgentPlan? =
        planToolForInput(
            input = input,
            actionModelPath = actionModelPath,
            allowDirectSkillPlan = true,
            allowMultiStepSkillPlan = true,
        ) ?: input.initialSequentialActionInput()?.let { firstActionInput ->
            planToolForInput(
                input = firstActionInput,
                actionModelPath = actionModelPath,
                allowDirectSkillPlan = false,
                allowMultiStepSkillPlan = false,
            )
        }

    private fun planToolForInput(
        input: String,
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
        if (!actionPlanningRuntime.isLikelyAction(input)) return null
        val result = actionPlanningRuntime.plan(input, actionModelPath)
        val draft = result.plan.draft
        if (result.plan.kind != ActionPlanKind.Draft || draft == null) return null
        if (!allowMultiStepSkillPlan && draft.functionName in INITIAL_SEQUENTIAL_CONTINUATION_TOOL_NAMES) return null
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
            requiresConfirmation = true,
        )
    }

    private fun buildInitialToolPlanFromSkill(skillPlan: SkillPlan): AgentPlan? {
        val toolStep = skillPlan.steps.firstOrNull() as? SkillStep.ToolStep
            ?: return null
        if (toolStep.dependsOn.isNotEmpty()) return null
        val validation = skillPlan.validateStructure()
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
            draft = draft,
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
        val validation = skillPlan?.validateStructure() ?: return null
        return if (validation.isValid) {
            null
        } else {
            request.rejected("Invalid skill plan: ${validation.errors.joinToString()}")
        }
    }

    private fun promptWithContextIfUseful(
        input: String,
        memoryHits: List<MemoryHit>,
        deviceContext: DeviceContextSnapshot?,
    ): String {
        if (memoryHits.isEmpty() && deviceContext == null) return input
        val context = runCatching { memoryIndex.buildContext(memoryHits) }.getOrDefault("")
        val memoryBlock = if (context.isBlank()) {
            "无"
        } else {
            context
        }
        val deviceBlock = deviceContext?.toPromptContext() ?: "无"
        return """
            请根据用户当前输入的语言回答。只有在以下本地记忆或设备上下文与当前问题明显相关时才使用；如果无关，请忽略，不要复述无关隐私内容。
            本地记忆：
            $memoryBlock

            设备上下文：
            $deviceBlock

            用户问题：$input
        """.trimIndent()
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
                "工具执行失败：${result.summary}"
            }
            ToolStatus.Rejected -> "工具请求已拒绝：${result.summary}"
            ToolStatus.Cancelled -> "工具执行已取消：${result.summary}"
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

    private fun ToolResult.auditSummaryForObservation(request: ToolRequest): String {
        val baseSummary = if (isUnverifiedExternalLaunch()) unverifiedExternalLaunchSummary() else summary
        val metadata = reminderAuditMetadata(request.toolName)
        return if (metadata.isEmpty()) {
            baseSummary
        } else {
            "$baseSummary (${metadata.joinToString(separator = "; ")})"
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
        if (spec.riskLevel == RiskLevel.HighExternalSend ||
            spec.riskLevel == RiskLevel.CriticalDeviceOrPayment
        ) {
            return false
        }
        val sideEffectPermissions = setOf(
            ToolPermission.StartsExternalActivity,
            ToolPermission.SendsTextToExternalApp,
            ToolPermission.SchedulesBackgroundWork,
            ToolPermission.PostsNotification,
        )
        if (spec.permissions.any { permission -> permission in sideEffectPermissions }) return false
        val readPermissions = setOf(
            ToolPermission.ReadsDeviceContext,
            ToolPermission.ReadsClipboard,
            ToolPermission.ReadsCalendar,
            ToolPermission.ReadsContacts,
            ToolPermission.ReadsFiles,
            ToolPermission.ReadsAccessibilityText,
        )
        return spec.riskLevel == RiskLevel.LowReadOnly ||
            spec.permissions.any { permission -> permission in readPermissions }
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
                )
            }

            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> {
                val ocrText = result.data["ocrText"]?.takeIf { it.isNotBlank() } ?: return null
                val truncated = result.data["truncated"]?.toBooleanStrictOrNull() ?: false
                val contentLabel = request.toolName.recentImageOcrContentLabel()
                val sourceBoundary = if (request.toolName == MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR) {
                    "这不是当前屏幕捕获，也不是图片语义理解；只使用已提取的截图文字。"
                } else {
                    "这不是当前屏幕捕获，也不是图片语义理解；只使用已提取的图片文字。"
                }
                ToolObservationContinuation(
                    prompt = """
                    用户已经确认读取$contentLabel 并在本地提取 OCR 文本。请根据用户原始请求处理 OCR 摘录。
                    $sourceBoundary
                    如果用户没有明确要求逐字复述，不要完整抄回 OCR 原文；优先总结、改写、提取信息或回答问题。

                    用户原始请求：${run.input}
                    工具观察：${result.summary}
                    $contentLabel OCR 文本${if (truncated) "（已截断）" else ""}：
                    $ocrText
                    """.trimIndent(),
                    requiresLocalModel = true,
                )
            }

            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> {
                val screenText = result.data["screenText"]?.takeIf { it.isNotBlank() } ?: return null
                val truncated = result.data["truncated"]?.toBooleanStrictOrNull() ?: false
                ToolObservationContinuation(
                    prompt = """
                    用户已经确认读取当前屏幕的 Accessibility 可访问文本快照。请根据用户原始请求处理这段屏幕文本。
                    这不是截图捕获、不是 OCR、不是图片语义理解；只使用当前屏幕暴露的可访问文本。
                    如果用户没有明确要求逐字复述，不要完整抄回屏幕文本；优先总结、提取信息或回答问题。

                    用户原始请求：${run.input}
                    工具观察：${result.summary}
                    当前屏幕文本${if (truncated) "（已截断）" else ""}：
                    $screenText
                    """.trimIndent(),
                    requiresLocalModel = true,
                )
            }

            else -> skillModelContinuationAfterToolObservation(run, request, result)
        }
    }

    private fun skillModelContinuationAfterToolObservation(
        run: AgentRun,
        request: ToolRequest?,
        result: ToolResult,
    ): ToolObservationContinuation? {
        request ?: return null
        val skillPlan = latestSkillPlan(run.id) ?: return null
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

    private data class ToolObservationContinuation(
        val prompt: String,
        val requiresLocalModel: Boolean,
    )

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

    private fun toolRequestsFor(runId: String): List<ToolRequest> =
        traceStore.steps(runId)
            .asSequence()
            .mapNotNull { step -> (step as? AgentStep.ToolRequested)?.request }
            .toList()

    private fun latestPendingToolRequest(runId: String): ToolRequest? =
        traceStore.steps(runId)
            .asReversed()
            .asSequence()
            .mapNotNull { step -> (step as? AgentStep.UserConfirmationRequested)?.request }
            .firstOrNull()

    private fun pendingToolRequest(runId: String, requestId: String): ToolRequest? {
        val liveRequest = latestPendingToolRequest(runId)
            ?.takeIf { request -> request.id == requestId }
        if (liveRequest != null) return liveRequest
        return traceStore.latestPendingConfirmation()
            ?.takeIf { snapshot -> snapshot.run.id == runId && snapshot.request.id == requestId }
            ?.request
    }

    private fun latestConfirmedRequestId(runId: String): String? =
        traceStore.steps(runId)
            .asReversed()
            .asSequence()
            .mapNotNull { step -> (step as? AgentStep.UserConfirmed)?.requestId }
            .firstOrNull()

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
        )

        is AgentPlan.RejectedTool -> AssistantRoute.ToolRejected(planned.result.summary)

        is AgentPlan.MissingModel -> AssistantRoute.MissingModel(planned.capability)
    }
