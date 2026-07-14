package com.bytedance.zgx.solin.orchestration

import android.util.Log
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.audit.ToolAuditEvent
import com.bytedance.zgx.solin.audit.ToolAuditEventType
import com.bytedance.zgx.solin.audit.ToolAuditSink
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.orchestration.ToolErrorCode as OrchestrationToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX
import com.bytedance.zgx.solin.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
import com.bytedance.zgx.solin.tool.isEligibleForParallelBatch
import com.bytedance.zgx.solin.tool.isUnverifiedExternalLaunch
import com.bytedance.zgx.solin.tool.unverifiedExternalLaunchSummary

private const val COORD_TAG = "ToolObservationCoordinator"
internal const val TOOL_OBSERVATION_AUDIT_SUMMARY = "Tool observation recorded."

/**
 * Tool-observation collaborator for [AgentLoopRuntime].
 *
 * Owns single-tool and batch observation, observation decisions, plan-step append,
 * confirmation/reject/missing-model initial-plan paths used by observation, and
 * observation message / recovery / audit helpers. Fail-closed privacy and safety
 * semantics stay compatible with the prior runtime-local path.
 */
internal class ToolObservationCoordinator(
    private val toolRegistry: ToolRegistry,
    private val auditSink: ToolAuditSink,
    private val eventBus: SolinEventBus,
    private val traceStore: AgentTraceStore,
    private val toolPlanCoordinator: ToolPlanCoordinator,
    private val runBudget: AgentRunBudget,
    private val maxToolRetryAttempts: Int,
    private val host: Host,
) {
    /**
     * Runtime-owned lookups and side effects the coordinator must not re-implement.
     * Keeps undo stack, remote tool scopes, continuation prompts, and parking state
     * on the runtime facade.
     */
    interface Host {
        val terminalRunStates: Set<AgentRunState>

        fun toolRequestFor(runId: String, requestId: String): ToolRequest?
        fun latestPlanToolBatch(runId: String): AgentObservationDecision.PlanToolBatch?
        fun latestExecutableRequestId(runId: String): String?
        fun continuationForToolObservation(
            run: AgentRun,
            request: ToolRequest?,
            result: ToolResult,
        ): ToolObservationContinuation?
        fun redactedForTrace(result: ToolResult, request: ToolRequest?): ToolResult
        fun failObservationBudget(
            runId: String,
            result: ToolResult,
            assistantMessage: String,
            reason: String,
        ): AgentObservationResult?
        fun parkForAskUserIfNeeded(runId: String, plan: AgentPlan.UseTool): Boolean
        fun parkForTakeOver(runId: String, prompt: String?, result: ToolResult)
        fun shouldAwaitExternalOutcomeConfirmation(
            runId: String,
            request: ToolRequest,
            result: ToolResult,
        ): Boolean
        fun skillIdForRequest(runId: String, requestId: String): String?
        fun clearEphemeralRunState(runId: String)
        fun auditToolRequest(
            runId: String,
            request: ToolRequest,
            eventType: ToolAuditEventType,
            status: ToolStatus?,
            summary: String,
        )
        fun auditRejectedTool(runId: String, result: ToolResult)
        fun auditToolEvent(
            runId: String,
            plan: AgentPlan.UseTool,
            eventType: ToolAuditEventType,
            status: ToolStatus?,
            summary: String,
        )
        fun publishRunEnded(runId: String, finalText: String? = null)
        fun publishRunFailed(runId: String, code: AgentErrorCode, message: String)
        fun markStepStart(stepType: String)
        fun recordVerboseTrace(
            runId: String,
            thinkText: String? = null,
            actionSummary: String? = null,
            actionToolName: String? = null,
            observationSummary: String? = null,
        )
        fun safeRecordTelemetry(sample: MetricSample, label: String)
        fun recordStepLatency(stepType: String, runId: String?)
        fun withConfirmationBypass(runId: String, plan: AgentPlan.UseTool): AgentPlan.UseTool
        fun toPendingSnapshot(plan: AgentPlan.UseTool, run: AgentRun): PendingToolConfirmationSnapshot
        fun failInitialPlanBudget(run: AgentRun, request: ToolRequest): AgentLoopResult
        fun addScratchpadNote(runId: String, noteContent: String)
        fun applyUndoBookkeeping(
            runId: String,
            request: ToolRequest,
            safeResult: ToolResult,
            observedStatus: ToolStatus,
        )
        fun markToolDispatchStarted(requestId: String)
        fun consumeToolDispatchLatencyMs(requestId: String): Long
        fun bumpAndGetTurnIndex(runId: String): Int
        fun setRemoteToolScope(runId: String, scope: RemoteToolScope)
        fun toOrchestrationErrorCode(code: ToolErrorCode?): OrchestrationToolErrorCode
        fun publicEvidenceBatchResultOrFailure(result: ToolResult, request: ToolRequest): ToolResult
        fun publicEvidenceBatchAuditSummary(
            successfulPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
        ): String
        fun publicEvidenceBatchContinuationPrompt(
            run: AgentRun,
            observedPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
            successfulPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
            gapPairs: List<Pair<AgentPlan.UseTool, ToolResult>>,
        ): String?
    }

    fun observeToolResults(runId: String, results: List<ToolResult>): AgentObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.ExecutingTool) return null
        val batchDecision = host.latestPlanToolBatch(runId) ?: return null
        val plans = batchDecision.plans
        val plannedIds = plans.mapTo(linkedSetOf()) { plan -> plan.request.id }
        val resultIds = results.mapTo(linkedSetOf()) { result -> result.requestId }
        if (results.size != resultIds.size || plannedIds != resultIds) return null
        if (plans.any { plan ->
                toolRegistry.specFor(plan.request.toolName)?.isEligibleForParallelBatch() != true
            }
        ) {
            return null
        }

        val observingRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = AgentRunState.ExecutingTool,
            state = AgentRunState.Observing,
        ) ?: return null
        val resultsByRequestId = results.associateBy { result -> result.requestId }
        val observedPairs = plans.map { plan ->
            val rawResult = resultsByRequestId.getValue(plan.request.id)
            val validatedResult = toolRegistry.validateResult(plan.request, rawResult) ?: rawResult
            val publicResult = host.publicEvidenceBatchResultOrFailure(validatedResult, plan.request)
            val traceResult = host.redactedForTrace(publicResult, plan.request)
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
            // Proof-of-wiring: also publish onto SolinEventBus for the new seam.
            eventBus.publish(
                SolinEvent.Audit.ToolAudited(
                    runId = runId,
                    requestId = traceResult.requestId,
                    toolName = plan.request.toolName,
                    skillId = host.skillIdForRequest(runId, plan.request.id),
                    eventType = ToolAuditEventType.ToolObserved.name,
                    status = traceResult.status.name,
                    riskLevel = toolRegistry.specFor(plan.request.toolName)?.riskLevel?.name,
                    permissionsCsv = toolRegistry.specFor(plan.request.toolName)?.permissions
                        ?.map { it.name }
                        ?.sorted()
                        ?.joinToString(separator = ",")
                        .orEmpty(),
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
        val publicEvidenceSummary = host.publicEvidenceBatchAuditSummary(successfulPairs)
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
        if (runBudget.observationDecisionBudgetExceeded(runId)) {
            return host.failObservationBudget(
                runId = runId,
                result = aggregateResult,
                assistantMessage = assistantMessage,
                reason = OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON,
            )
        }
        val continuationPrompt = if (cancelledPair == null && successfulPairs.isNotEmpty()) {
            host.publicEvidenceBatchContinuationPrompt(
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
        val finalState = when (decision) {
            is AgentObservationDecision.ContinueWithModel -> AgentRunState.GeneratingAnswer
            AgentObservationDecision.Cancel -> AgentRunState.Cancelled
            else -> AgentRunState.Failed
        }
        val updatedRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = observingRun.state,
            state = finalState,
        ) ?: return null
        if (decision is AgentObservationDecision.ContinueWithModel) {
            host.setRemoteToolScope(runId, RemoteToolScope.PublicEvidenceOnly)
            host.markStepStart("model_generation")
        }
        if (updatedRun.state in host.terminalRunStates) {
            host.clearEphemeralRunState(runId)
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

    fun rejectModelToolBatch(
        runId: String,
        result: ToolResult,
        safetyDecision: SafetyDecision? = null,
    ): AgentModelObservationResult? {
        val updatedRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = AgentRunState.GeneratingAnswer,
            state = AgentRunState.Failed,
        ) ?: return null
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
        host.auditRejectedTool(runId, result)
        val decision = AgentObservationDecision.Fail(result.summary)
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        host.clearEphemeralRunState(runId)
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    fun observeToolResultInternal(
        runId: String,
        result: ToolResult,
        allowedStates: Set<AgentRunState>,
    ): AgentObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state !in allowedStates) return null
        if (run.state == AgentRunState.ExecutingTool && host.latestPlanToolBatch(runId) != null) {
            return null
        }
        if (
            run.state in setOf(AgentRunState.ExecutingTool, AgentRunState.RetryingTool) &&
            host.latestExecutableRequestId(runId) != result.requestId
        ) {
            return null
        }
        val request = host.toolRequestFor(runId, result.requestId) ?: return null
        val safeResult = toolRegistry.validateResult(request, result) ?: result
        val searchResultVerified = safeResult.isVerifiedSearchResult()
        val observingRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = run.state,
            state = AgentRunState.Observing,
        ) ?: return null
        val continuation = host.continuationForToolObservation(run, request, safeResult)
        val continuationPrompt = continuation?.prompt
        val continuationRequiresLocalModel = continuation?.requiresLocalModel ?: false
        val continuationRemoteToolScope =
            continuation?.remoteToolScope ?: RemoteToolScope.PublicEvidenceOnly
        val canPlanNextToolBeforeContinuation = continuation?.canPlanNextToolBeforeModel ?: false
        val rawLocalPlanningResult = if (
            result.requestId == request.id &&
            toolPlanCoordinator.canPlanLocalToolFromCurrentScreenObservation(request, run) &&
            result.hasLocalObservationEvidenceForPlanning()
        ) {
            result
        } else {
            null
        }
        val observedResult = host.redactedForTrace(safeResult, request)
        // Handle note: add to per-run scratchpad
        if (request.toolName == MobileActionFunctions.NOTE) {
            val noteContent = safeResult.data["noteContent"]
            if (noteContent != null && noteContent.isNotBlank()) {
                host.addScratchpadNote(runId, noteContent)
            }
        }
        val shouldFinish = safeResult.data["shouldFinish"] == "true"
        val finishMessage = safeResult.data["finishMessage"]
        val shouldTakeOver = safeResult.data["shouldTakeOver"] == "true"
        val takeOverPrompt = safeResult.data["takeOverPrompt"]
        val localPlanningResult = if (
            canPlanNextToolBeforeContinuation ||
            rawLocalPlanningResult != null
        ) {
            rawLocalPlanningResult ?: safeResult
        } else {
            observedResult
        }
        val budgetExceeded = runBudget.observationDecisionBudgetExceeded(runId)
        val retryAttempt = if (budgetExceeded) 0 else nextRetryAttempt(runId, observedResult)
        val retryRequest = if (retryAttempt > 0) request else null
        traceStore.appendStep(runId, AgentStep.ToolObserved(observedResult))
        host.recordVerboseTrace(runId = runId, actionToolName = request.toolName, actionSummary = request.reason.ifBlank { request.toolName }, observationSummary = observedResult.summary.take(500))
        // Wave 2 lifecycle: dual-write ToolSucceeded / ToolFailed based on observed status.
        // Note: ToolStarted is published by ToolProgressPublisher at actual execution start
        // (ValidatingToolExecutor), so these are the terminal counterpart events.
        when (observedResult.status) {
            ToolStatus.Succeeded ->
                eventBus.publish(
                    SolinEvent.Agent.ToolSucceeded(
                        runId = runId,
                        toolCallId = request.id,
                        toolName = request.toolName,
                        summary = observedResult.summary,
                    ),
                )
            ToolStatus.Failed ->
                eventBus.publish(
                    SolinEvent.Agent.ToolFailed(
                        runId = runId,
                        toolCallId = request.id,
                        toolName = request.toolName,
                        code = host.toOrchestrationErrorCode(observedResult.error?.code),
                        message = observedResult.error?.message ?: observedResult.summary,
                        retryable = retryAttempt > 0,
                    ),
                )
            ToolStatus.Rejected -> {
                eventBus.publish(
                    SolinEvent.Agent.ToolFailed(
                        runId = runId,
                        toolCallId = request.id,
                        toolName = request.toolName,
                        code = OrchestrationToolErrorCode.SafetyRejected,
                        message = observedResult.error?.message ?: observedResult.summary,
                        retryable = false,
                    ),
                )
                // Wave 4 telemetry: count safety-policy rejections distinct from user-cancel / exec failures.
                host.safeRecordTelemetry(
                    MetricSample.CounterInc(
                        name = TelemetryCounter.SafetyBlocks,
                        runId = runId,
                    ),
                    "SafetyBlocks counter",
                )
            }
            ToolStatus.Cancelled ->
                eventBus.publish(
                    SolinEvent.Agent.ToolFailed(
                        runId = runId,
                        toolCallId = request.id,
                        toolName = request.toolName,
                        code = OrchestrationToolErrorCode.Cancelled,
                        message = observedResult.error?.message ?: observedResult.summary,
                        retryable = false,
                    ),
                )
        }
        // Wave 7 undo bookkeeping delegated to host (runtime-owned undo stack).
        host.applyUndoBookkeeping(
            runId = runId,
            request = request,
            safeResult = safeResult,
            observedStatus = observedResult.status,
        )
        // Wave 4 telemetry: record ToolCompleted sample with wall-clock latency since
        // ToolRequested was appended (0L fallback if no start marker exists, e.g. restored runs).
        val toolLatencyMs = host.consumeToolDispatchLatencyMs(request.id)
        host.safeRecordTelemetry(
            MetricSample.ToolCompleted(
                toolName = request.toolName,
                latencyMs = toolLatencyMs,
                succeeded = observedResult.status == ToolStatus.Succeeded,
                retryCount = retryAttempt,
                requestId = request.id,
                runId = runId,
            ),
            "ToolCompleted",
        )
        // Wave 4 telemetry: record step latency for tool_execution boundary.
        host.recordStepLatency("tool_execution", runId)
        toolPlanCoordinator.recordContinuationCursorForUnverifiedExternalLaunch(run, request, observedResult)
        val spec = toolRegistry.specFor(request.toolName)
        val riskLevel = spec?.riskLevel
        val permissions = spec?.permissions.orEmpty()
        val resolvedSkillId = host.skillIdForRequest(runId, observedResult.requestId)
        val observationSummary = observedResult.auditSummaryForObservation(request)
        auditSink.record(
            ToolAuditEvent(
                runId = runId,
                requestId = observedResult.requestId,
                toolName = request.toolName,
                skillId = resolvedSkillId,
                eventType = ToolAuditEventType.ToolObserved,
                status = observedResult.status,
                riskLevel = riskLevel,
                permissions = permissions,
                summary = observationSummary,
            ),
        )
        eventBus.publish(
            SolinEvent.Audit.ToolAudited(
                runId = runId,
                requestId = observedResult.requestId,
                toolName = request.toolName,
                skillId = resolvedSkillId,
                eventType = ToolAuditEventType.ToolObserved.name,
                status = observedResult.status.name,
                riskLevel = riskLevel?.name,
                permissionsCsv = permissions
                    .map { it.name }
                    .sorted()
                    .joinToString(separator = ","),
                summary = observationSummary,
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
            return host.failObservationBudget(
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
            val deterministicPlan = toolPlanCoordinator.planNextOpenAppUiSearchStepAfterUnverifiedLaunch(run, request, localPlanningResult)
                ?: toolPlanCoordinator.planNextLowRiskAppControlSkillStepBeforeContinuation(run, request, localPlanningResult)
                ?: toolPlanCoordinator.planNextCompositeSkillSegmentBeforeContinuation(
                    run = run,
                    canPlanNextToolBeforeModel = continuation?.canPlanNextToolBeforeModel == true,
                    blocksSequentialTail = continuation?.blocksSequentialTail == true,
                )
            deterministicPlan ?: if (searchResultVerified) {
                NextObservationPlan.None
            } else if (
                (continuationPrompt == null || canPlanNextToolBeforeContinuation) &&
                toolPlanCoordinator.canPlanNextToolAfterObservation(run, request, localPlanningResult)
            ) {
                toolPlanCoordinator.planNextToolAfterObservation(run, request, localPlanningResult)
            } else {
                NextObservationPlan.None
            }
        } else if (
            observedResult.status == ToolStatus.Failed &&
            toolPlanCoordinator.isDeviceControlTool(request) &&
            !observedResult.isPermissionFailure() &&
            !observedResult.isForegroundPackageGateFailure() &&
            retryRequest == null
        ) {
            toolPlanCoordinator.planModelDeviceControlRecoveryAfterFailure(run, request, localPlanningResult)
                ?: toolPlanCoordinator.planSafeDeviceControlRecoveryAfterFailure(run, request, observedResult)
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
            searchResultVerified = searchResultVerified,
            nextToolPlan = nextToolPlan,
        )
        val effectiveDecision = when {
            shouldFinish -> AgentObservationDecision.Complete
            shouldTakeOver -> {
                runCatching { host.parkForTakeOver(runId, takeOverPrompt, safeResult) }
                AgentObservationDecision.Complete
            }
            else -> decision
        }
        when (effectiveDecision) {
            is AgentObservationDecision.RetryTool -> traceStore.appendStep(
                runId,
                AgentStep.ToolRetryScheduled(
                    request = effectiveDecision.request,
                    attempt = effectiveDecision.attempt,
                    reason = effectiveDecision.reason,
                ),
            )

            is AgentObservationDecision.PlanNextTool -> appendToolPlanSteps(
                runId = runId,
                plan = effectiveDecision.plan,
            )

            else -> Unit
        }
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        // Capture observation decision reasoning for verbose trace (optimization #8).
        host.recordVerboseTrace(
            runId = runId,
            actionSummary = when (effectiveDecision) {
                AgentObservationDecision.Complete -> "observation:complete"
                is AgentObservationDecision.ContinueWithModel -> "observation:continue_with_model"
                is AgentObservationDecision.RetryTool -> "observation:retry_tool(${effectiveDecision.attempt})"
                is AgentObservationDecision.PlanNextTool -> "observation:plan_next_tool"
                is AgentObservationDecision.PlanToolBatch -> "observation:plan_tool_batch"
                is AgentObservationDecision.Fail -> "observation:fail"
                AgentObservationDecision.Cancel -> "observation:cancel"
            },
            observationSummary = when (effectiveDecision) {
                is AgentObservationDecision.ContinueWithModel -> effectiveDecision.reason
                is AgentObservationDecision.RetryTool -> effectiveDecision.reason
                is AgentObservationDecision.PlanNextTool -> effectiveDecision.reason
                is AgentObservationDecision.Fail -> effectiveDecision.reason
                else -> null
            }?.take(500),
        )
        if (effectiveDecision is AgentObservationDecision.RetryTool) {
            host.auditToolRequest(
                runId = runId,
                request = effectiveDecision.request,
                eventType = ToolAuditEventType.ToolRetryScheduled,
                status = ToolStatus.Failed,
                summary = "Retry ${effectiveDecision.attempt} scheduled.",
            )
        }
        val shouldAwaitExternalOutcome = host.shouldAwaitExternalOutcomeConfirmation(
            runId = runId,
            request = request,
            result = observedResult,
        )
        val finalState = when (effectiveDecision) {
            AgentObservationDecision.Complete ->
                if (shouldAwaitExternalOutcome) {
                    AgentRunState.AwaitingExternalOutcome
                } else {
                    AgentRunState.Completed
                }

            is AgentObservationDecision.ContinueWithModel -> AgentRunState.GeneratingAnswer
            is AgentObservationDecision.RetryTool -> AgentRunState.RetryingTool
            is AgentObservationDecision.PlanNextTool -> effectiveDecision.plan.nextExecutionState()
            is AgentObservationDecision.PlanToolBatch -> AgentRunState.ExecutingTool
            is AgentObservationDecision.Fail -> AgentRunState.Failed
            AgentObservationDecision.Cancel -> AgentRunState.Cancelled
        }
        // ask_user interception for PlanNextTool decisions originating from observation
        // (i.e. not the initial runOnce plan). Append UserQuestionAsked step + publish event
        // + remember pending state BEFORE updating run state so subscribers see the step
        // before the AwaitingUserAnswer state transition.
        var parkedForAskUser = false
        if (effectiveDecision is AgentObservationDecision.PlanNextTool &&
            effectiveDecision.plan.nextExecutionState() == AgentRunState.AwaitingUserAnswer
        ) {
            parkedForAskUser = host.parkForAskUserIfNeeded(runId, effectiveDecision.plan)
        }
        val updatedRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = observingRun.state,
            state = finalState,
        ) ?: return null
        // Wave 2 lifecycle: publish terminal / transition events alongside trace. Note that
        // TurnEnded requires tokensIn/tokensOut/ttft/duration which are known only when a model
        // generation completes; that is published from observeModelResult (and via run budget
        // fail paths here using zeros where unknown).
        when (finalState) {
            AgentRunState.Completed -> host.publishRunEnded(runId, finalText = assistantMessage)
            AgentRunState.Failed -> {
                val failureMessage = (effectiveDecision as? AgentObservationDecision.Fail)?.reason
                    ?: observedResult.summary
                host.publishRunFailed(runId, AgentErrorCode.Unknown("tool_failure"), failureMessage)
            }
            AgentRunState.Cancelled ->
                eventBus.publish(SolinEvent.Agent.RunCancelled(runId = runId, byUser = false))
            AgentRunState.GeneratingAnswer -> {
                // Starting a new model turn — bump the turn counter and publish TurnStarted.
                val nextTurn = host.bumpAndGetTurnIndex(runId)
                eventBus.publish(
                    SolinEvent.Agent.TurnStarted(runId = runId, turnIndex = nextTurn),
                )
                host.markStepStart("model_generation")
            }
            AgentRunState.RetryingTool -> host.markStepStart("tool_execution")
            AgentRunState.ExecutingTool -> host.markStepStart("tool_execution")
            else -> Unit
        }
        if (finalState in host.terminalRunStates) {
            host.clearEphemeralRunState(runId)
        }
        if (decision is AgentObservationDecision.ContinueWithModel) {
            host.setRemoteToolScope(runId, continuationRemoteToolScope)
        }
        if (decision is AgentObservationDecision.PlanNextTool) {
            val parkedForAskUser = decision.plan.nextExecutionState() == AgentRunState.AwaitingUserAnswer
            if (!parkedForAskUser && decision.plan.requiresUserConfirmation()) {
                traceStore.savePendingConfirmation(host.toPendingSnapshot(decision.plan, updatedRun))
            }
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
        searchResultVerified: Boolean,
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

            searchResultVerified -> AgentObservationDecision.Complete

            result.status == ToolStatus.Succeeded && continuationPrompt != null ->
                AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = continuationRequiresLocalModel,
                    reason = result.summary,
                )

            result.status == ToolStatus.Succeeded -> AgentObservationDecision.Complete
            result.status == ToolStatus.Cancelled -> AgentObservationDecision.Cancel
            else -> AgentObservationDecision.Fail(result.summary)
        }

    fun appendToolPlanSteps(
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
        // Wave 4 telemetry: record tool dispatch start for latency measurement, and
        // bump the ToolCalls monotonic counter. ToolStarted events are still published by
        // ToolProgressPublisher at actual executor entry; this counter is orchestration-side.
        host.markToolDispatchStarted(plan.request.id)
        host.safeRecordTelemetry(
            MetricSample.CounterInc(
                name = TelemetryCounter.ToolCalls,
                runId = runId,
            ),
            "ToolCalls counter",
        )
        // Wave 2 lifecycle: dual-write ToolPlanned. ToolStarted is published by the
        // ToolProgressPublisher wired into ValidatingToolExecutor once the tool handler
        // actually begins executing; this event represents the planning decision only.
        eventBus.publish(
            SolinEvent.Agent.ToolPlanned(
                runId = runId,
                toolCallId = plan.request.id,
                toolName = plan.request.toolName,
                title = plan.draft.title,
                reason = plan.request.reason,
                requiresConfirmation = plan.requiresUserConfirmation(),
            ),
        )
        host.auditToolEvent(runId, plan, ToolAuditEventType.ToolPlanned, null, plan.request.reason)
        if (plan.requiresUserConfirmation()) {
            traceStore.appendStep(runId, AgentStep.UserConfirmationRequested(plan.request, plan.draft))
            host.auditToolEvent(
                runId = runId,
                plan = plan,
                eventType = ToolAuditEventType.ConfirmationRequested,
                status = null,
                summary = plan.safetyDecision.reason,
            )
        }
    }

    fun requestToolConfirmation(
        run: AgentRun,
        plan: AgentPlan.UseTool,
    ): AgentLoopResult {
        val effectivePlan = host.withConfirmationBypass(run.id, plan)
        if (runBudget.toolStepBudgetExceeded(run.id)) {
            return host.failInitialPlanBudget(run, effectivePlan.request)
        }
        appendToolPlanSteps(run.id, effectivePlan)
        // ask_user interception: append UserQuestionAsked step + event + remember pending
        // state so answerUserQuestion can correlate a reply. Safety has already been evaluated
        // via buildInitialToolPlan and appended via appendToolPlanSteps above, satisfying the
        // "after safety, before execution" requirement. nextExecutionState() returns
        // AwaitingUserAnswer for ask_user so the state transition below parks the run without
        // ever entering ExecutingTool (and thus without ever invoking the executor).
        host.parkForAskUserIfNeeded(run.id, effectivePlan)
        val waitingRun = traceStore.updateState(run.id, effectivePlan.nextExecutionState())
        if (effectivePlan.requiresUserConfirmation()) {
            traceStore.savePendingConfirmation(host.toPendingSnapshot(effectivePlan, waitingRun))
        }
        return AgentLoopResult(
            run = waitingRun,
            plan = effectivePlan,
            steps = traceStore.steps(run.id),
        )
    }

    fun rejectToolPlan(
        run: AgentRun,
        plan: AgentPlan.RejectedTool,
    ): AgentLoopResult {
        traceStore.appendStep(run.id, AgentStep.ModelPlanned(plan))
        traceStore.appendStep(run.id, AgentStep.ToolRejected(plan.result))
        host.auditRejectedTool(run.id, plan.result)
        val failedRun = traceStore.updateState(run.id, AgentRunState.Failed)
        host.clearEphemeralRunState(run.id)
        return AgentLoopResult(
            run = failedRun,
            plan = plan,
            steps = traceStore.steps(run.id),
        )
    }

    fun failMissingModelPlan(
        run: AgentRun,
        plan: AgentPlan.MissingModel,
    ): AgentLoopResult {
        val reason = "Missing model capability ${plan.capability.name}."
        traceStore.appendStep(run.id, AgentStep.ModelPlanned(plan))
        traceStore.appendStep(run.id, AgentStep.Failed(reason))
        val failedRun = traceStore.updateState(run.id, AgentRunState.Failed)
        host.clearEphemeralRunState(run.id)
        return AgentLoopResult(
            run = failedRun,
            plan = plan,
            steps = traceStore.steps(run.id),
        )
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
        return spec.isEligibleForParallelBatch()
    }

}

/**
 * Value-free observation continuation produced after a tool result is observed.
 * Hoisted from [AgentLoopRuntime] so the observation coordinator can share the type.
 */
internal data class ToolObservationContinuation(
    val prompt: String,
    val requiresLocalModel: Boolean = false,
    val remoteToolScope: RemoteToolScope = RemoteToolScope.PublicEvidenceOnly,
    val canPlanNextToolBeforeModel: Boolean = false,
    val blocksSequentialTail: Boolean = false,
)

internal data class NormalizedReminderRecovery(
    val taskId: String,
    val request: ToolRequest,
    val draft: ActionDraft,
)

internal fun String.isSafeRecoveryTaskId(): Boolean =
    matches(Regex("""task-[A-Za-z0-9_-]+"""))

internal fun AgentRecoveryAction.normalizedReminderRecovery(): NormalizedReminderRecovery? {
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
