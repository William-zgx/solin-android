package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.AppSearchProgressEvidence
import com.bytedance.zgx.solin.device.ScreenObservation
import com.bytedance.zgx.solin.device.UiTargetEvidenceCandidate
import com.bytedance.zgx.solin.device.UiTargetEvidenceSource
import com.bytedance.zgx.solin.device.UiTargetFallbackType
import com.bytedance.zgx.solin.device.UiTargetKind
import com.bytedance.zgx.solin.device.UiTargetResolver
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
import com.bytedance.zgx.solin.skill.extractObservationSearchQuery
import com.bytedance.zgx.solin.tool.ToolRequest

internal const val TYPED_OBSERVATION_REPLAN_REQUEST_REASON = "Typed observation candidate planned."
internal const val TYPED_OBSERVATION_FALLBACK_REASON = "typed_observation_candidate"

/**
 * High-confidence search steps that need no model call. Every other screen falls through unchanged.
 * This planner proposes ordinary requests; registry, confirmation and device gates still authorize them.
 */
class TypedObservationCandidateReplanner(
    private val enabled: Boolean = false
) : AgentObservationReplanner {
    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (!enabled) return null
        val observations = mutableMapOf<String, ScreenObservation?>()
        val parse: (String) -> ScreenObservation? = { raw ->
            observations.getOrPut(raw) { screenObservationFromJsonStringOrNull(raw) }
        }
        val stage = AppSearchProgressEvidence.fromData(context.observedResult.data, parse).appSearchProgressStage
        if (stage != "input_ready" && stage != "query_typed") return null
        val observation = context.currentObservation(parse) ?: return null
        val query = context.run.input.searchQuery() ?: return null
        return when (stage) {
            "input_ready" -> context.typeQuery(observation, query)
            "query_typed" -> context.submitQuery(observation, query)
            else -> null
        }
    }

    private fun AgentObservationReplanContext.typeQuery(
        observation: ScreenObservation,
        query: String
    ): AgentObservationReplan? {
        if (previousRequest.toolName == MobileActionFunctions.UI_TYPE_TEXT) return null
        val target = observation.uniqueRealCandidate(UiTargetKind.EditableField) ?: return null
        val arguments = mapOf(
            "target" to target.nodeId.orEmpty(),
            "text" to query,
            "verifySearchQuery" to query
        ).withExpectedPackage(observation)
        return replan(
            request = ToolRequest(
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = arguments,
                reason = TYPED_OBSERVATION_REPLAN_REQUEST_REASON
            ),
            title = "输入搜索关键词",
            summary = "在唯一明确的输入框中输入用户给出的关键词。"
        )
    }

    private fun AgentObservationReplanContext.submitQuery(
        observation: ScreenObservation,
        query: String
    ): AgentObservationReplan? {
        if (previousRequest.toolName != MobileActionFunctions.UI_TYPE_TEXT ||
            previousRequest.arguments["text"] != query ||
            previousRequest.arguments["verifySearchQuery"]?.let { it != query } == true
        ) {
            return null
        }
        if (observation.uniqueRealCandidate(UiTargetKind.SubmitSearch) == null) return null
        val arguments = mapOf("verifySearchQuery" to query).withExpectedPackage(observation)
        return replan(
            request = ToolRequest(
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = arguments,
                reason = TYPED_OBSERVATION_REPLAN_REQUEST_REASON
            ),
            title = "提交搜索",
            summary = "关键词已与用户原话核验，提交到唯一明确的搜索入口。"
        )
    }

    private fun AgentObservationReplanContext.replan(
        request: ToolRequest,
        title: String,
        summary: String
    ): AgentObservationReplan = AgentObservationReplan(
        request = request,
        draft = ActionDraft(
            functionName = request.toolName,
            title = title,
            summary = summary,
            parameters = request.arguments
        ),
        plannedByModel = false,
        fallbackReason = TYPED_OBSERVATION_FALLBACK_REASON,
        input = run.input
    )

    private fun AgentObservationReplanContext.currentObservation(
        parse: (String) -> ScreenObservation?
    ): ScreenObservation? {
        val json = observedResult.data["afterScreenObservationJson"]?.takeIf(String::isNotBlank)
            ?: observedResult.data["screenObservationJson"]?.takeIf(String::isNotBlank)
        return json?.let(parse)
    }
    private fun Map<String, String>.withExpectedPackage(observation: ScreenObservation): Map<String, String> = observation.packageName?.takeIf(String::isNotBlank)?.let { this + ("expectedPackageName" to it) } ?: this

    private fun ScreenObservation.uniqueRealCandidate(kind: UiTargetKind): UiTargetEvidenceCandidate? {
        val resolution = UiTargetResolver.explain(this, kind)
        val selected = resolution.selectedNodeId ?: return null
        return resolution.rankedCandidates.filter { candidate ->
            candidate.enabled &&
                candidate.source == UiTargetEvidenceSource.Accessibility &&
                candidate.fallbackType == UiTargetFallbackType.None
        }.singleOrNull()?.takeIf { it.nodeId == selected && !it.nodeId.isNullOrBlank() }
    }

    private fun String.searchQuery(): String? = extractObservationSearchQuery()
}
