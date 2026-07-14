package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.skill.valueFreeCheckpointForPendingTool
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
import com.bytedance.zgx.solin.tool.isUnverifiedExternalLaunch
import com.bytedance.zgx.solin.tool.rejected
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private val PENDING_EXTERNAL_OUTCOME_RESTORE_STATES = setOf(
    AgentRunState.AwaitingExternalOutcome,
    AgentRunState.Completed,
)

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
) {
    interface Host {
        fun plannedSequentialSegmentCount(runId: String): Int
        fun auditRejectedTool(runId: String, result: ToolResult)
        fun clearEphemeralRunState(runId: String)
    }

    val pendingExternalOutcomeRestoreStates: Set<AgentRunState>
        get() = PENDING_EXTERNAL_OUTCOME_RESTORE_STATES

    fun pendingExternalOutcomeSnapshotFor(run: AgentRun): PendingExternalOutcomeSnapshot? {
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
        host.auditRejectedTool(snapshot.run.id, rejection)
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

    /** Shared restore path used by [AgentLoopRuntime.toolRequestsFor]. */
    fun restoreToolRequest(step: AgentTraceStepSummary): ToolRequest? =
        step.restoredToolRequestSummaryOrNull()?.let { request ->
            ToolRequest(
                id = request.requestId,
                toolName = request.toolName,
                arguments = emptyMap(),
                reason = "",
            )
        }

    /** Shared restore path used by [AgentLoopRuntime.latestUnverifiedExternalResult]. */
    fun restoreUnverifiedExternalResult(step: AgentTraceStepSummary): ToolResult? =
        step.restoredUnverifiedExternalResultOrNull()

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

    private fun AgentTraceStepSummary.jsonObjectOrNull(): JSONObject? =
        runCatching { JSONObject(json) }.getOrNull()
}
