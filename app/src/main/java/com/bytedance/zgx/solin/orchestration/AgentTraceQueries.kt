package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.IntentRoutingDecision
import com.bytedance.zgx.solin.action.IntentRoutingPath
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillStep
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.isUnverifiedExternalLaunch

/**
 * Read-only lookups over a run's trace for [AgentLoopRuntime] and its collaborators.
 *
 * Every query merges the live typed steps with the persisted value-free step summaries, so it keeps
 * working for runs restored after a process restart. Nothing here writes to the trace store.
 */
internal class AgentTraceQueries(
    private val traceStore: AgentTraceStore
) {
    fun toolRequestFor(runId: String, requestId: String): ToolRequest? = toolRequestsFor(runId).firstOrNull { request -> request.id == requestId }

    fun latestUnverifiedExternalResult(runId: String, requestId: String): ToolResult? = traceStore.steps(runId)
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

    fun latestExternalOutcomeConfirmation(runId: String, requestId: String): AgentStep.ExternalOutcomeConfirmed? = traceStore.steps(runId)
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
                        summary = step.summary
                    )
                )
            }

    fun toolRequestsFor(runId: String): List<ToolRequest> = traceStore.steps(runId)
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

    /**
     * Whether [requestId] is the tool a local planning model proposed in [runId]: the accepted
     * [IntentRoutingPath.ModelToolCall] decision [InitialToolPlanner] records before confirmation
     * names it. Persisted reasons are redacted, so a restored run compares the redacted tag.
     */
    fun localPlanningModelProposed(runId: String, requestId: String): Boolean {
        val tag = localModelProposedRequestTag(requestId)
        if (traceStore.steps(runId).any { step -> (step as? AgentStep.IntentRouted)?.decision?.namesLocalModelProposal(tag) == true }) {
            return true
        }
        val persistedTag = tag.shortTraceText(maxLength = PERSISTED_ROUTING_REASON_LENGTH)
        return traceStore.stepSummaries(runId).any { step ->
            step.type == "IntentRouted" && step.namesLocalModelProposal(persistedTag)
        }
    }

    private fun IntentRoutingDecision.namesLocalModelProposal(tag: String): Boolean = accepted && selectedPath == IntentRoutingPath.ModelToolCall && tag in rejectionReasons

    private fun AgentTraceStepSummary.namesLocalModelProposal(tag: String): Boolean {
        val json = jsonObjectOrNull() ?: return false
        val reasons = json.optJSONArray("rejectionReasons") ?: return false
        return json.optBoolean("accepted") &&
            json.optString("selectedPath") == IntentRoutingPath.ModelToolCall.name &&
            (0 until reasons.length()).any { index -> reasons.optString(index) == tag }
    }

    fun plannedSequentialSegmentCount(runId: String): Int {
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
                    val skillPlan = step.skillPlanSummaryFromJson()
                    val skillKey = skillPlan.requestId?.let { requestId -> "skill:$requestId" }
                    if (skillKey == null) {
                        adjacentSummarySkillSegment = null
                    } else if (skillPlan.toolRequestIds.isEmpty()) {
                        adjacentSummarySkillSegment = SummarySkillSegment(
                            key = skillKey,
                            remainingToolSteps = (skillPlan.toolStepCount ?: 1).coerceAtLeast(1)
                        )
                    } else {
                        skillPlan.toolRequestIds.forEach { requestId ->
                            summarySkillKeysByToolRequestId[requestId] = skillKey
                        }
                        adjacentSummarySkillSegment = null
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

    fun latestPendingToolRequest(runId: String): ToolRequest? = traceStore.steps(runId).asReversed().asSequence()
        .mapNotNull { step -> (step as? AgentStep.UserConfirmationRequested)?.request }
        .firstOrNull()

    fun latestExecutableRequestId(runId: String): String? = traceStore.steps(runId)
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

    fun latestObservationDecision(runId: String): AgentObservationDecision? = traceStore.steps(runId).asReversed().asSequence()
        .mapNotNull { step -> (step as? AgentStep.ObservationDecided)?.decision }
        .firstOrNull()

    fun latestPlanToolBatch(runId: String): AgentObservationDecision.PlanToolBatch? = latestObservationDecision(runId) as? AgentObservationDecision.PlanToolBatch

    fun skillIdForRequest(runId: String, requestId: String): String? {
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

    /** Latest live skill plan for [runId], optionally the latest one that [matching] accepts. */
    fun latestSkillPlan(runId: String, matching: (SkillPlan) -> Boolean = { true }): SkillPlan? = traceStore.steps(runId).asSequence()
        .mapNotNull { step -> (step as? AgentStep.SkillPlanned)?.plan }
        .filter(matching)
        .lastOrNull()

    private fun AgentTraceStepSummary.skillPlanSummaryFromJson(): SkillPlanSummary {
        val json = jsonObjectOrNull()
        val toolRequestIds = json?.optJSONArray("toolRequestIds")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { value -> value.isNotBlank() }?.let(::add)
                }
            }
        }.orEmpty()
        return SkillPlanSummary(
            requestId = json?.optString("skillRequestId")?.takeIf { it.isNotBlank() },
            toolRequestIds = toolRequestIds,
            toolStepCount = json?.optInt("toolStepCount", -1)?.takeIf { it >= 0 }
                ?: json?.optInt("stepCount", -1)?.takeIf { it >= 0 }
        )
    }

    private data class SkillPlanSummary(
        val requestId: String?,
        val toolRequestIds: List<String>,
        val toolStepCount: Int?
    )

    private data class SummarySkillSegment(val key: String, val remainingToolSteps: Int) {
        fun afterTool(): SummarySkillSegment? = if (remainingToolSteps <= 1) null else copy(remainingToolSteps = remainingToolSteps - 1)
    }
}

internal fun SkillPlan.toolStepRequestIds(): Set<String> = steps.filterIsInstance<SkillStep.ToolStep>()
    .mapTo(mutableSetOf()) { step -> step.request.id }
