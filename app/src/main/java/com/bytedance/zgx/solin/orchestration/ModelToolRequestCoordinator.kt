package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.safety.SafetyContext
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.isEligibleForParallelBatch
import com.bytedance.zgx.solin.tool.rejected

/**
 * Remote / model-inline tool-request observation collaborator for [AgentLoopRuntime].
 *
 * Owns [observeModelToolRequest] and [observeModelToolRequests] (batch public-evidence path).
 * Fail-closed budget, exposure, validation, and safety checks stay compatible with the prior
 * runtime-local path.
 */
internal class ModelToolRequestCoordinator(
    private val toolRegistry: ToolRegistry,
    private val skillRuntime: SkillRuntime,
    private val safetyPolicy: SafetyPolicy,
    private val traceStore: AgentTraceStore,
    private val toolObservationCoordinator: ToolObservationCoordinator,
    private val runBudget: AgentRunBudget,
    private val initialToolPlanner: InitialToolPlanner,
    private val host: Host,
) {
    interface Host {
        fun rejectRemoteToolIfNotExposedInCurrentScope(runId: String, request: ToolRequest): ToolResult?
        fun toolRequestFor(runId: String, requestId: String): ToolRequest?
        fun toolRequestsFor(runId: String): List<ToolRequest>
        fun failRunBudget(runId: String, reason: String): AgentModelObservationResult?
        fun withConfirmationBypass(runId: String, plan: AgentPlan.UseTool): AgentPlan.UseTool
        fun parkForAskUserIfNeeded(runId: String, plan: AgentPlan.UseTool): Boolean
        fun toPendingSnapshot(plan: AgentPlan.UseTool, run: AgentRun): PendingToolConfirmationSnapshot
        fun auditRejectedTool(runId: String, result: ToolResult)
        fun clearEphemeralRunState(runId: String)
        fun markStepStart(stepType: String)
    }

    fun observeModelToolRequest(runId: String, request: ToolRequest): AgentModelObservationResult? {
        val run = traceStore.run(runId) ?: return null
        if (run.state != AgentRunState.GeneratingAnswer) return null
        if (runBudget.observationDecisionBudgetExceeded(runId)) {
            return host.failRunBudget(runId, OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON)
        }
        if (runBudget.toolStepBudgetExceeded(runId)) {
            return host.failRunBudget(runId, TOOL_STEP_BUDGET_EXCEEDED_REASON)
        }
        host.rejectRemoteToolIfNotExposedInCurrentScope(runId, request)?.let { rejected ->
            val updatedRun = traceStore.compareAndSetState(
                runId = runId,
                expectedState = AgentRunState.GeneratingAnswer,
                state = AgentRunState.Failed,
            ) ?: return null
            traceStore.appendRejectedRoutingDecision(
                runId = runId,
                path = IntentRoutingPath.RemoteToolPlanning,
                toolName = request.toolName,
                reason = "remote_tool_not_exposed",
            )
            traceStore.appendStep(runId, AgentStep.ModelPlanned(AgentPlan.RejectedTool(rejected)))
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejected))
            host.auditRejectedTool(runId, rejected)
            val decision = AgentObservationDecision.Fail(rejected.summary)
            traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
            host.clearEphemeralRunState(runId)
            return AgentModelObservationResult(
                run = updatedRun,
                decision = decision,
                steps = traceStore.steps(runId),
            )
        }
        if (host.toolRequestFor(runId, request.id) != null) {
            val rejected = request.rejected("Model tool request id already exists: ${request.id}")
            val updatedRun = traceStore.compareAndSetState(
                runId = runId,
                expectedState = AgentRunState.GeneratingAnswer,
                state = AgentRunState.Failed,
            ) ?: return null
            traceStore.appendRejectedRoutingDecision(
                runId = runId,
                path = IntentRoutingPath.RemoteToolPlanning,
                toolName = request.toolName,
                reason = "duplicate_model_tool_request_id",
            )
            traceStore.appendStep(runId, AgentStep.ModelPlanned(AgentPlan.RejectedTool(rejected)))
            traceStore.appendStep(runId, AgentStep.ToolRejected(rejected))
            host.auditRejectedTool(runId, rejected)
            val decision = AgentObservationDecision.Fail(rejected.summary)
            traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
            host.clearEphemeralRunState(runId)
            return AgentModelObservationResult(
                run = updatedRun,
                decision = decision,
                steps = traceStore.steps(runId),
            )
        }
        val draft = initialToolPlanner.draftForRemoteToolRequest(request)
        val skillPlan = skillRuntime.plan(run.input, draft, request)
        val plan = initialToolPlanner.buildInitialToolPlan(
            request = request,
            draft = draft,
            plannedByModel = true,
            fallbackReason = "remote tool call",
            skillPlan = skillPlan,
        )
        return when (plan) {
            is AgentPlan.UseTool -> {
                val effectivePlan = host.withConfirmationBypass(runId, plan)
                val decision = AgentObservationDecision.PlanNextTool(
                    plan = effectivePlan,
                    reason = "Remote model requested a tool call.",
                )
                val nextState = effectivePlan.nextExecutionState()
                val updatedRun = traceStore.compareAndSetState(
                    runId = runId,
                    expectedState = AgentRunState.GeneratingAnswer,
                    state = nextState,
                ) ?: return null
                toolObservationCoordinator.appendToolPlanSteps(runId, effectivePlan)
                // ask_user interception: park in AwaitingUserAnswer before ExecutingTool.
                val parkedForAskUser = host.parkForAskUserIfNeeded(runId, effectivePlan)
                traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
                if (!parkedForAskUser && effectivePlan.requiresUserConfirmation()) {
                    traceStore.savePendingConfirmation(host.toPendingSnapshot(effectivePlan, updatedRun))
                }
                AgentModelObservationResult(
                    run = updatedRun,
                    decision = decision,
                    steps = traceStore.steps(runId),
                )
            }

            is AgentPlan.RejectedTool -> {
                val updatedRun = traceStore.compareAndSetState(
                    runId = runId,
                    expectedState = AgentRunState.GeneratingAnswer,
                    state = AgentRunState.Failed,
                ) ?: return null
                traceStore.appendRejectedRoutingDecision(
                    runId = runId,
                    path = IntentRoutingPath.RemoteToolPlanning,
                    toolName = request.toolName,
                    reason = plan.result.summary.toRoutingReasonSlug(),
                )
                traceStore.appendStep(runId, AgentStep.ModelPlanned(plan))
                traceStore.appendStep(runId, AgentStep.ToolRejected(plan.result))
                host.auditRejectedTool(runId, plan.result)
                val decision = AgentObservationDecision.Fail(plan.result.summary)
                traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
                host.clearEphemeralRunState(runId)
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
        if (runBudget.observationDecisionBudgetExceeded(runId)) {
            return host.failRunBudget(runId, OBSERVATION_DECISION_BUDGET_EXCEEDED_REASON)
        }
        if (requests.isEmpty()) {
            return toolObservationCoordinator.rejectModelToolBatch(
                runId = runId,
                result = ToolRequest(
                    id = "remote-tool-batch",
                    toolName = "tool_batch",
                    reason = "remote tool batch",
                ).rejected("Remote model returned an empty tool batch."),
            )
        }
        if (host.toolRequestsFor(runId).size + requests.size > runBudget.effectiveMaxToolSteps(runId)) {
            return host.failRunBudget(runId, TOOL_STEP_BUDGET_EXCEEDED_REASON)
        }
        requests.groupingBy { request -> request.id }
            .eachCount()
            .entries
            .firstOrNull { (_, count) -> count > 1 }
            ?.key
            ?.let { duplicatedRequestId ->
                return toolObservationCoordinator.rejectModelToolBatch(
                    runId = runId,
                    result = requests.first().rejected(
                        "Model tool request id appears multiple times in one batch: $duplicatedRequestId",
                    ),
                )
            }
        requests.firstOrNull { request -> host.toolRequestFor(runId, request.id) != null }
            ?.let { existingRequest ->
                return toolObservationCoordinator.rejectModelToolBatch(
                    runId = runId,
                    result = existingRequest.rejected("Model tool request id already exists: ${existingRequest.id}"),
                )
            }

        val plans = mutableListOf<AgentPlan.UseTool>()
        requests.forEach { request ->
            host.rejectRemoteToolIfNotExposedInCurrentScope(runId, request)?.let { rejection ->
                return toolObservationCoordinator.rejectModelToolBatch(
                    runId = runId,
                    result = rejection.withAttemptedToolNames(requests),
                )
            }
            toolRegistry.validate(request)?.let { rejection ->
                return toolObservationCoordinator.rejectModelToolBatch(
                    runId = runId,
                    result = rejection.withAttemptedToolNames(requests),
                )
            }
            val spec = toolRegistry.specFor(request.toolName) ?: return toolObservationCoordinator.rejectModelToolBatch(
                runId = runId,
                result = request.rejected("Unknown tool: ${request.toolName}").withAttemptedToolNames(requests),
            )
            if (!spec.isEligibleForParallelBatch()) {
                return toolObservationCoordinator.rejectModelToolBatch(
                    runId = runId,
                    result = request.rejected(
                        "Tool ${request.toolName} is not eligible for parallel public evidence execution.",
                    ).withAttemptedToolNames(requests),
                )
            }
            val safetyDecision = safetyPolicy.evaluate(spec, request, SafetyContext(userConfirmed = false))
            if (safetyDecision.outcome != SafetyOutcome.Allow) {
                return toolObservationCoordinator.rejectModelToolBatch(
                    runId = runId,
                    result = request.rejected(safetyDecision.reason).withAttemptedToolNames(requests),
                    safetyDecision = safetyDecision,
                )
            }
            plans += AgentPlan.UseTool(
                request = request,
                draft = initialToolPlanner.draftForRemoteToolRequest(request).withSafetyDecision(safetyDecision),
                plannedByModel = true,
                fallbackReason = "remote tool batch",
                skillRequest = null,
                skillPlan = null,
                safetyDecision = safetyDecision,
            )
        }

        val decision = AgentObservationDecision.PlanToolBatch(
            plans = plans,
            reason = "Remote model requested ${plans.size} parallel public evidence tool calls.",
        )
        val updatedRun = traceStore.compareAndSetState(
            runId = runId,
            expectedState = AgentRunState.GeneratingAnswer,
            state = AgentRunState.ExecutingTool,
        ) ?: return null
        plans.forEach { plan -> toolObservationCoordinator.appendToolPlanSteps(runId, plan) }
        traceStore.appendStep(runId, AgentStep.ObservationDecided(decision))
        host.markStepStart("tool_execution")
        return AgentModelObservationResult(
            run = updatedRun,
            decision = decision,
            steps = traceStore.steps(runId),
        )
    }

    private fun ToolResult.withAttemptedToolNames(requests: List<ToolRequest>): ToolResult =
        copy(
            data = data + mapOf(
                "attemptedToolNames" to requests.joinToString(",") { request -> request.toolName },
            ),
        )
}
