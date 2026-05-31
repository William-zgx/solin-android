package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
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
import com.bytedance.zgx.pocketmind.skill.SkillPlan
import com.bytedance.zgx.pocketmind.skill.SkillRuntime
import com.bytedance.zgx.pocketmind.skill.SkillStep
import com.bytedance.zgx.pocketmind.skill.validateStructure
import com.bytedance.zgx.pocketmind.tool.ToolRegistry
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import com.bytedance.zgx.pocketmind.tool.cancelled
import com.bytedance.zgx.pocketmind.tool.rejected

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
    @Suppress("UNUSED_PARAMETER")
    fun runOnce(
        input: String,
        installedCapabilities: Set<ModelCapability>,
        memoryEnabled: Boolean,
        actionModelPath: String? = null,
        deviceContext: DeviceContextSnapshot? = null,
    ): AgentLoopResult {
        val createdRun = traceStore.createRun(input)
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
                traceStore.appendStep(createdRun.id, AgentStep.ModelPlanned(toolPlan))
                toolPlan.skillRequest?.let { skillRequest ->
                    traceStore.appendStep(createdRun.id, AgentStep.SkillPlanned(skillRequest, toolPlan.skillPlan))
                }
                traceStore.appendStep(createdRun.id, AgentStep.SafetyChecked(toolPlan.safetyDecision))
                traceStore.appendStep(createdRun.id, AgentStep.ToolRequested(toolPlan.request, toolPlan.draft))
                auditToolEvent(createdRun.id, toolPlan, ToolAuditEventType.ToolPlanned, null, toolPlan.request.reason)
                traceStore.appendStep(
                    createdRun.id,
                    AgentStep.UserConfirmationRequested(toolPlan.request, toolPlan.draft),
                )
                auditToolEvent(
                    runId = createdRun.id,
                    plan = toolPlan,
                    eventType = ToolAuditEventType.ConfirmationRequested,
                    status = null,
                    summary = toolPlan.safetyDecision.reason,
                )
                val waitingRun = traceStore.updateState(createdRun.id, AgentRunState.AwaitingUserConfirmation)
                traceStore.savePendingConfirmation(toolPlan.toPendingSnapshot(waitingRun))
                return AgentLoopResult(
                    run = waitingRun,
                    plan = toolPlan,
                    steps = traceStore.steps(createdRun.id),
                )
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
        traceStore.clearPendingConfirmation(runId, requestId)
        return traceStore.updateState(runId, AgentRunState.ExecutingTool)
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
        traceStore.clearPendingConfirmation(runId, requestId)
        return observeToolResultInternal(
            runId = runId,
            result = request.cancelled("用户取消了工具请求"),
            allowedStates = setOf(AgentRunState.AwaitingUserConfirmation),
        )
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
        traceStore.clearPendingConfirmation(runId, requestId)
        return observeToolResultInternal(
            runId = runId,
            result = result,
            allowedStates = setOf(AgentRunState.AwaitingUserConfirmation),
        )
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
        traceStore.updateState(runId, AgentRunState.Observing)
        val continuationPrompt = promptForToolObservation(run, request, result)
        val observedResult = result.redactedForTrace(request)
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
                summary = observedResult.summary,
            ),
        )
        val assistantMessage = messageForObservation(observedResult, retryAttempt)
        traceStore.appendStep(runId, AgentStep.AssistantResponded(assistantMessage))
        val nextToolPlan = if (
            observedResult.status == ToolStatus.Succeeded &&
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
            continuationPromptForModel = continuationPrompt,
            continuationRequiresLocalModel = continuationPrompt != null &&
                request.toolName == MobileActionFunctions.READ_CLIPBOARD,
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
                    requiresLocalModel = request.toolName == MobileActionFunctions.READ_CLIPBOARD,
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
        val skillPlan = latestSkillPlan(run.id) ?: return NextObservationPlan.None
        if (skillPlan.request.skillId != BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL) {
            return NextObservationPlan.None
        }
        val validation = skillPlan.validateStructure()
        if (!validation.isValid) {
            return NextObservationPlan.Rejected("Invalid skill plan: ${validation.errors.joinToString()}")
        }
        val modelStep = skillPlan.steps
            .filterIsInstance<SkillStep.ModelStep>()
            .lastOrNull() ?: return NextObservationPlan.None
        val toolStep = skillPlan.steps
            .filterIsInstance<SkillStep.ToolStep>()
            .firstOrNull { step -> modelStep.id in step.dependsOn }
            ?: return NextObservationPlan.None
        val boundArguments = resolveSkillBindings(
            bindings = toolStep.argumentBindings,
            outputs = mapOf(modelStep.id to mapOf(modelStep.outputKey to text)),
        ) ?: return rejectNextToolPlan(run.id, toolStep.request.rejected("Missing model output binding for skill step."))
        val request = toolStep.request.copy(arguments = toolStep.request.arguments + boundArguments)
        val draft = toolStep.draft.copy(parameters = toolStep.draft.parameters + boundArguments)
        return buildNextToolPlan(
            runId = run.id,
            request = request,
            draft = draft,
            plannedByModel = false,
            fallbackReason = "skill model step",
            skillPlan = skillPlan,
        )
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

    private fun resolveSkillBindings(
        bindings: Map<String, String>,
        outputs: Map<String, Map<String, String>>,
    ): Map<String, String>? {
        val resolved = linkedMapOf<String, String>()
        bindings.forEach { (targetName, sourceRef) ->
            val sourceStepId = sourceRef.substringBefore('.', missingDelimiterValue = "")
            val sourceKey = sourceRef.substringAfter('.', missingDelimiterValue = "")
            if (sourceStepId.isBlank() || sourceKey.isBlank()) return null
            val value = outputs[sourceStepId]?.get(sourceKey) ?: return null
            resolved[targetName] = value
        }
        return resolved
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

    fun latestPendingConfirmation(): PendingToolConfirmationSnapshot? =
        traceStore.latestPendingConfirmation()

    private fun AgentPlan.UseTool.toPendingSnapshot(run: AgentRun): PendingToolConfirmationSnapshot =
        PendingToolConfirmationSnapshot(
            run = run,
            request = request,
            draft = draft,
            skillId = skillRequest?.skillId,
            skillPlan = skillPlan,
            plannedByModel = plannedByModel,
            fallbackReason = fallbackReason,
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

    private fun planToolIfSupported(input: String, actionModelPath: String?): AgentPlan? {
        skillRuntime.plan(input)?.let { skillPlan ->
            return buildInitialToolPlanFromSkill(skillPlan)
        }
        if (!actionPlanningRuntime.isLikelyAction(input)) return null
        val result = actionPlanningRuntime.plan(input, actionModelPath)
        val draft = result.plan.draft
        if (result.plan.kind != ActionPlanKind.Draft || draft == null) return null
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )
        val skillPlan = skillRuntime.plan(input, draft, request)
        return buildInitialToolPlan(
            request = request,
            draft = draft,
            plannedByModel = result.usedModel,
            fallbackReason = result.fallbackReason,
            skillPlan = skillPlan,
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

    private fun messageForObservation(result: ToolResult, retryAttempt: Int = 0): String =
        when (result.status) {
            ToolStatus.Succeeded -> "工具执行结果：${result.summary}"
            ToolStatus.Failed -> if (retryAttempt > 0) {
                "工具执行失败，正在重试（第 $retryAttempt 次）：${result.summary}"
            } else {
                "工具执行失败：${result.summary}"
            }
            ToolStatus.Rejected -> "工具请求已拒绝：${result.summary}"
            ToolStatus.Cancelled -> "工具执行已取消：${result.summary}"
        }

    private fun nextRetryAttempt(runId: String, result: ToolResult): Int {
        if (result.status != ToolStatus.Failed || !result.retryable) return 0
        if (result.error?.code == ToolErrorCode.PermissionDenied) return 0
        val completedAttempts = traceStore.steps(runId)
            .asSequence()
            .filterIsInstance<AgentStep.ToolRetryScheduled>()
            .count { step -> step.request.id == result.requestId }
        if (completedAttempts >= maxToolRetryAttempts) return 0
        return completedAttempts + 1
    }

    private fun promptForToolObservation(
        run: AgentRun,
        request: ToolRequest?,
        result: ToolResult,
    ): String? {
        if (result.status != ToolStatus.Succeeded) return null
        if (request?.toolName != MobileActionFunctions.READ_CLIPBOARD) return null
        val clipboardText = result.data["text"]?.takeIf { it.isNotBlank() } ?: return null
        val truncated = result.data["truncated"]?.toBooleanStrictOrNull() ?: false
        return """
            用户已经确认读取剪贴板。请根据用户原始请求处理剪贴板文本。
            如果用户没有明确要求逐字复述，不要完整抄回剪贴板原文；优先总结、改写、提取信息或回答问题。
            不要使用与当前请求无关的隐私内容。

            用户原始请求：${run.input}
            工具观察：${result.summary}
            剪贴板文本${if (truncated) "（已截断）" else ""}：
            $clipboardText
        """.trimIndent()
    }

    private fun ToolResult.redactedForTrace(request: ToolRequest?): ToolResult {
        if (request?.toolName != MobileActionFunctions.READ_CLIPBOARD) return this
        if ("text" !in data) return this
        return copy(
            summary = "已读取剪贴板文本",
            data = data + ("text" to "[redacted]"),
        )
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
