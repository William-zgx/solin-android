package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.data.AgentStepEntity
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
import com.bytedance.zgx.solin.tool.isUnverifiedExternalLaunch

/**
 * The unverified external launch a run is still waiting on the user to confirm, reconstructed from
 * persisted (value-free) step summaries so it survives a process restart.
 */
internal data class PendingExternalOutcomeEvidence(
    val requestId: String,
    val toolName: String,
    val title: String,
    val result: ToolResult,
)

/**
 * Single definition of "this run has a pending, user-confirmable external outcome" after restart.
 *
 * Used by the UI restore ([PendingConfirmationSupport.pendingExternalOutcomeSnapshotFor]), by startup
 * repair ([RoomAgentTraceStore.failStaleInFlightRuns], which fails AwaitingExternalOutcome runs without
 * evidence) and by placement recovery (which additionally keeps its strict trace-shape checks). Keeping
 * one predicate means a run is never kept in AwaitingExternalOutcome when the UI cannot offer the
 * confirmation that would move it on.
 *
 * Evidence is the latest unverified external launch observation that:
 *  - has not been confirmed by an ExternalOutcomeConfirmed step,
 *  - is not followed by any later ToolRequested step (the run moved on),
 *  - has a restorable ToolRequested summary carrying its tool name,
 *  - is not a launch whose restored popup the tool registry marks as skippable.
 */
internal fun List<AgentTraceStepSummary>.pendingUnverifiedExternalOutcomeOrNull(
    toolRegistry: ToolRegistry,
): PendingExternalOutcomeEvidence? {
    val confirmedRequestIds = asSequence()
        .filter { step -> step.type == "ExternalOutcomeConfirmed" }
        .mapNotNull { step -> step.requestIdFromJson() }
        .toSet()
    val requestsById = asSequence()
        .filter { step -> step.type == "ToolRequested" }
        .mapNotNull { step -> step.restoredToolRequestSummaryOrNull() }
        .associateBy { request -> request.requestId }
    val (observationIndex, result) = withIndex()
        .toList()
        .asReversed()
        .asSequence()
        .filter { indexedStep -> indexedStep.value.type == "ToolObserved" }
        .mapNotNull { indexedStep ->
            indexedStep.value.restoredUnverifiedExternalResultOrNull()?.let { result ->
                indexedStep.index to result
            }
        }
        .firstOrNull { (_, result) ->
            result.requestId !in confirmedRequestIds && result.isUnverifiedExternalLaunch()
        } ?: return null
    val laterToolRequested = drop(observationIndex + 1).any { step -> step.type == "ToolRequested" }
    if (laterToolRequested) return null
    val request = requestsById[result.requestId] ?: return null
    if (toolRegistry.isLowRiskRestoredExternalOutcomePopupSkippable(request.toolName, result)) return null
    return PendingExternalOutcomeEvidence(
        requestId = result.requestId,
        toolName = request.toolName,
        title = request.title,
        result = result,
    )
}

/** Value-free view of a persisted step entity, matching what [AgentTraceStore.stepSummaries] returns. */
internal fun AgentStepEntity.toTraceStepSummary(): AgentTraceStepSummary =
    AgentTraceStepSummary(
        runId = runId,
        position = position,
        type = type,
        summary = summary,
        json = json,
        createdAtMillis = createdAtMillis,
    )

internal data class RestoredToolRequestSummary(
    val requestId: String,
    val toolName: String,
    val title: String,
)

internal fun AgentTraceStepSummary.restoredToolRequestSummaryOrNull(): RestoredToolRequestSummary? {
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

/** Value-free ToolRequest (no arguments, no reason) rebuilt from a persisted ToolRequested summary. */
internal fun AgentTraceStepSummary.restoredToolRequestOrNull(): ToolRequest? =
    restoredToolRequestSummaryOrNull()?.let { request ->
        ToolRequest(
            id = request.requestId,
            toolName = request.toolName,
            arguments = emptyMap(),
            reason = "",
        )
    }

/**
 * Rebuilds the allowlisted completion metadata of a persisted ToolObserved step as a [ToolResult],
 * returning it only when it is an unverified external launch.
 */
internal fun AgentTraceStepSummary.restoredUnverifiedExternalResultOrNull(): ToolResult? {
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
    return ToolResult(
        requestId = requestId,
        status = ToolStatus.Succeeded,
        summary = UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX,
        data = data,
    ).takeIf { result -> result.isUnverifiedExternalLaunch() }
}
