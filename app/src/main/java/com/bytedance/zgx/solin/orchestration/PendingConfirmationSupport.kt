package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.skill.valueFreeCheckpointForPendingTool
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
import com.bytedance.zgx.solin.tool.rejected
import java.util.concurrent.ConcurrentHashMap

private val PENDING_EXTERNAL_OUTCOME_RESTORE_STATES = setOf(
    AgentRunState.AwaitingExternalOutcome,
    AgentRunState.Completed,
)

internal sealed interface PendingConfirmationPlacementResolution {
    data class Ready(val permit: ActiveRunPlacementPermit) : PendingConfirmationPlacementResolution

    data class Blocked(
        val reason: PlacementReasonCode = PlacementReasonCode.PLACEMENT_NOT_RESTORABLE,
    ) : PendingConfirmationPlacementResolution
}

/**
 * Fail-closed placement gate for a model-backed pending confirmation restored from durable state.
 * Callers must not reconstruct a target from the current inference preference.
 */
internal object PendingConfirmationPlacementResolver {
    fun resolve(
        bindingStore: RunPlacementBindingStore,
        runId: String,
        context: RunPlacementRecoveryContext,
    ): PendingConfirmationPlacementResolution {
        val candidate = runCatching { bindingStore.inspectForRecovery(runId, context) }
            .getOrNull() as? RecoveryInspection.ContinuationCandidate
            ?: return PendingConfirmationPlacementResolution.Blocked()
        val active = runCatching { bindingStore.activeBinding(runId) }.getOrNull()
        val permit = when {
            active == null -> runCatching { bindingStore.activate(candidate) }.getOrNull()
            active.binding == candidate.binding -> active
            else -> null
        }
        return permit
            ?.takeIf { it.binding == candidate.binding && it.binding.dispatchState == ModelDispatchState.Idle }
            ?.let(PendingConfirmationPlacementResolution::Ready)
            ?: PendingConfirmationPlacementResolution.Blocked()
    }
}

/**
 * Pending confirmation / external-outcome restore helpers for [AgentLoopRuntime].
 *
 * Owns value-free frontier caching, skill-auth checks on restored pending confirmations,
 * pending-confirmation snapshot construction (including continuation cursors), and
 * pending external-outcome snapshot reconstruction from trace summaries.
 */
internal class PendingConfirmationSupport(
    private val toolRegistry: ToolRegistry,
    private val traceStore: AgentTraceStore,
    private val initialToolPlanner: InitialToolPlanner,
    private val valueFreeCompletedStepFrontiersByRunId: ConcurrentHashMap<String, Set<String>>,
    private val host: Host,
    private val auditRecorder: ToolAuditRecorder,
) {
    interface Host {
        fun plannedSequentialSegmentCount(runId: String): Int
        fun clearEphemeralRunState(runId: String)
    }

    val pendingExternalOutcomeRestoreStates: Set<AgentRunState>
        get() = PENDING_EXTERNAL_OUTCOME_RESTORE_STATES

    fun pendingExternalOutcomeSnapshotFor(run: AgentRun): PendingExternalOutcomeSnapshot? {
        val evidence = traceStore.stepSummaries(run.id).pendingUnverifiedExternalOutcomeOrNull(toolRegistry)
            ?: return null
        val summary = if (evidence.result.summary.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX)) {
            evidence.result.summary
        } else {
            "$UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX：${evidence.result.summary}"
        }
        return PendingExternalOutcomeSnapshot(
            runId = run.id,
            requestId = evidence.requestId,
            toolName = evidence.toolName,
            title = evidence.title,
            summary = summary,
        )
    }

    fun rememberValueFreeFrontier(snapshot: PendingToolConfirmationSnapshot) {
        val completedStepIds = snapshot.skillRunCheckpoint?.completedStepIds.orEmpty().toSet()
        if (completedStepIds.isEmpty()) {
            valueFreeCompletedStepFrontiersByRunId.remove(snapshot.run.id)
        } else {
            valueFreeCompletedStepFrontiersByRunId[snapshot.run.id] = completedStepIds
        }
    }

    fun restoredPendingConfirmationIsAuthorized(snapshot: PendingToolConfirmationSnapshot): Boolean {
        val reason = initialToolPlanner.invalidSkillPlanReason(snapshot.skillPlan)
            ?: initialToolPlanner.invalidSkillPlanReason(snapshot.continuationCursor?.skillPlan)
            ?: return true
        val rejection = snapshot.request.rejected(reason)
        traceStore.appendStep(snapshot.run.id, AgentStep.ToolRejected(rejection))
        auditRecorder.rejected(snapshot.run.id, rejection)
        traceStore.clearPendingConfirmation(snapshot.run.id, snapshot.request.id)
        traceStore.appendStep(snapshot.run.id, AgentStep.Failed(rejection.summary))
        traceStore.updateState(snapshot.run.id, AgentRunState.Failed)
        host.clearEphemeralRunState(snapshot.run.id)
        return false
    }

    fun toPendingSnapshot(plan: AgentPlan.UseTool, run: AgentRun): PendingToolConfirmationSnapshot =
        host.plannedSequentialSegmentCount(run.id).let { completedSegmentCount ->
            val nextActionInput = run.input.explicitSequentialActionTextAt(completedSegmentCount)
            PendingToolConfirmationSnapshot(
                run = run,
                request = plan.request,
                draft = plan.draft,
                skillId = plan.skillRequest?.skillId,
                skillPlan = plan.skillPlan,
                plannedByModel = plan.plannedByModel,
                fallbackReason = plan.fallbackReason,
                nextActionInput = nextActionInput,
                continuationCursor = nextActionInput?.takeIf { traceStore is RoomAgentTraceStore }?.let { input ->
                    persistableContinuationCursor(
                        sourceRequestId = plan.request.id,
                        completedSegmentCount = completedSegmentCount,
                        input = input,
                    )
                },
                skillRunCheckpoint = plan.skillPlan?.valueFreeCheckpointForPendingTool(
                    runId = run.id,
                    pendingRequest = plan.request,
                    toolRegistry = toolRegistry,
                ),
            )
        }

    fun persistableContinuationCursor(
        sourceRequestId: String,
        completedSegmentCount: Int,
        input: String,
    ): AgentContinuationCursor? {
        val plan = initialToolPlanner.planInitialSequentialSegment(input, actionModelPath = null) as? AgentPlan.UseTool
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
}
