package com.bytedance.zgx.solin.debug

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.ScreenObservation
import com.bytedance.zgx.solin.device.UiTargetEvidenceCandidate
import com.bytedance.zgx.solin.device.UiTargetKind
import com.bytedance.zgx.solin.device.UiTargetResolver
import com.bytedance.zgx.solin.device.normalizedLookupKey
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
import com.bytedance.zgx.solin.orchestration.AgentObservationReplan
import com.bytedance.zgx.solin.orchestration.AgentObservationReplanContext
import com.bytedance.zgx.solin.orchestration.AgentObservationReplanner
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus

private const val MODEL_DRIVEN_APP_SEARCH_RECOVERY_REASON_PREFIX =
    "Debug model-driven app search recovery:"

internal class ModelDrivenAppSearchRecoveryObservationReplanner(
    private val verifySearchQueryProvider: () -> String?,
    private val expectedPackageNameProvider: () -> String?,
    private val expectedAppNameProvider: () -> String? = { null },
) : AgentObservationReplanner {
    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        val recoveryPlan = ModelDrivenAppSearchRecoveryPlanner.plan(
            observedResult = context.observedResult,
            verifySearchQuery = verifySearchQueryProvider(),
            expectedPackageName = expectedPackageNameProvider(),
            expectedAppName = expectedAppNameProvider(),
            previousToolName = context.previousRequest.toolName,
            attemptedRecoveryKinds = context.priorRequests
                .mapNotNull { request -> request.modelDrivenAppSearchRecoveryKind() }
                .toSet(),
        ) ?: return null
        val request = recoveryPlan.request.copy(
            reason = "$MODEL_DRIVEN_APP_SEARCH_RECOVERY_REASON_PREFIX ${recoveryPlan.kind}: ${recoveryPlan.summary}",
        )
        return AgentObservationReplan(
            request = request,
            draft = ActionDraft(
                functionName = request.toolName,
                title = "模型驱动搜索恢复",
                summary = recoveryPlan.summary,
                parameters = request.arguments,
                requiresConfirmation = false,
            ),
            plannedByModel = false,
            fallbackReason = "debug_model_driven_app_search_recovery",
        )
    }
}

internal fun ToolRequest.modelDrivenAppSearchRecoveryKind(): String? =
    reason
        .takeIf { value -> value.startsWith(MODEL_DRIVEN_APP_SEARCH_RECOVERY_REASON_PREFIX) }
        ?.removePrefix(MODEL_DRIVEN_APP_SEARCH_RECOVERY_REASON_PREFIX)
        ?.trim()
        ?.substringBefore(":")
        ?.trim()
        ?.takeIf { value -> value.isNotBlank() }

internal object ModelDrivenAppSearchRecoveryPlanner {
    const val KIND_TAP_SEARCH_ENTRY = "tap_search_entry"
    const val KIND_TYPE_SEARCH_QUERY = "type_search_query"
    const val KIND_SUBMIT_SEARCH = "submit_search"
    const val KIND_VERIFY_SEARCH_RESULTS = "verify_search_results"

    fun plan(
        observedResult: ToolResult,
        verifySearchQuery: String?,
        expectedPackageName: String?,
        expectedAppName: String? = null,
        previousToolName: String? = null,
        attemptedRecoveryKinds: Set<String>,
    ): ModelDrivenAppSearchRecoveryPlan? {
        if (observedResult.status != ToolStatus.Succeeded) return null
        val query = verifySearchQuery?.trim()?.takeIf { value -> value.isNotBlank() } ?: return null
        val observation = observedResult.currentObservation() ?: return null
        val expectedPackage = expectedPackageName?.trim()?.takeIf { value -> value.isNotBlank() }
        if (expectedPackage != null && observation.packageName != expectedPackage) return null

        if (
            previousToolName == MobileActionFunctions.UI_SUBMIT_SEARCH &&
            KIND_VERIFY_SEARCH_RESULTS !in attemptedRecoveryKinds
        ) {
            return ModelDrivenAppSearchRecoveryPlan(
                kind = KIND_VERIFY_SEARCH_RESULTS,
                request = ToolRequest(
                    toolName = MobileActionFunctions.UI_WAIT,
                    arguments = buildMap {
                        put("timeoutMillis", "2500")
                        put("verifySearchQuery", query)
                        expectedPackage?.let { value -> put("expectedPackageName", value) }
                        expectedAppName?.trim()
                            ?.takeIf { value -> value.isNotBlank() }
                            ?.let { value -> put("expectedAppName", value) }
                    },
                    reason = "Debug model-driven app search recovery: verify search results.",
                ),
                summary = "模型提交搜索后未产出可靠验证等待，基于已提交搜索动作等待并验证结果。",
            )
        }

        if (KIND_TAP_SEARCH_ENTRY !in attemptedRecoveryKinds) {
            observation.bestSearchEntryCandidate()?.let { candidate ->
                return ModelDrivenAppSearchRecoveryPlan(
                    kind = KIND_TAP_SEARCH_ENTRY,
                    request = ToolRequest(
                        toolName = MobileActionFunctions.UI_TAP,
                        arguments = mapOf(
                            "target" to candidate.targetLabel(fallback = "搜索入口"),
                            "timeoutMillis" to "1500",
                        ),
                        reason = "Debug model-driven app search recovery: tap search entry.",
                    ),
                    summary = "模型未产出下一步工具，基于本地搜索入口证据点击搜索入口。",
                )
            }
        }

        if (KIND_TYPE_SEARCH_QUERY !in attemptedRecoveryKinds) {
            observation.bestEditableCandidate()?.let {
                return ModelDrivenAppSearchRecoveryPlan(
                    kind = KIND_TYPE_SEARCH_QUERY,
                    request = ToolRequest(
                        toolName = MobileActionFunctions.UI_TYPE_TEXT,
                        arguments = mapOf(
                            "target" to "搜索输入框",
                            "text" to query,
                            "timeoutMillis" to "1500",
                        ),
                        reason = "Debug model-driven app search recovery: type verified query.",
                    ),
                    summary = "模型未产出下一步工具，基于本地输入框证据输入搜索词。",
                )
            }
        }

        if (
            KIND_SUBMIT_SEARCH !in attemptedRecoveryKinds &&
            observation.containsVisibleQuery(query)
        ) {
            observation.bestSubmitCandidate()?.let {
                return ModelDrivenAppSearchRecoveryPlan(
                    kind = KIND_SUBMIT_SEARCH,
                    request = ToolRequest(
                        toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                        arguments = mapOf("timeoutMillis" to "2000"),
                        reason = "Debug model-driven app search recovery: submit visible query.",
                    ),
                    summary = "模型未产出下一步工具，基于本地提交按钮和关键词证据提交搜索。",
                )
            }
        }

        return null
    }

    private fun ToolResult.currentObservation(): ScreenObservation? =
        data["screenObservationJson"].parseScreenObservation()
            ?: data["afterScreenObservationJson"].parseScreenObservation()

    private fun String?.parseScreenObservation(): ScreenObservation? =
        takeUnless { value -> value.isNullOrBlank() }
            ?.let { rawJson -> screenObservationFromJsonStringOrNull(rawJson) }

    private fun ScreenObservation.bestSearchEntryCandidate(): UiTargetEvidenceCandidate? {
        val submitCandidateLabel = bestSubmitCandidate()?.label.normalizedLookupKey()
        return UiTargetResolver.explain(this, UiTargetKind.SearchEntry)
            .rankedCandidates
            .firstOrNull { candidate ->
                candidate.enabled &&
                    (candidate.editable || candidate.clickable) &&
                    !candidate.isSubmitOnlySearchButton(submitCandidateLabel)
            }
    }

    private fun ScreenObservation.bestEditableCandidate(): UiTargetEvidenceCandidate? =
        UiTargetResolver.explain(this, UiTargetKind.EditableField)
            .rankedCandidates
            .firstOrNull { candidate -> candidate.enabled && candidate.editable }

    private fun ScreenObservation.bestSubmitCandidate(): UiTargetEvidenceCandidate? =
        UiTargetResolver.explain(this, UiTargetKind.SubmitSearch)
            .rankedCandidates
            .firstOrNull { candidate -> candidate.enabled && candidate.clickable }

    private fun ScreenObservation.containsVisibleQuery(query: String): Boolean {
        val normalizedQuery = query.normalizedLookupKey()
        if (normalizedQuery.isBlank()) return false
        return elements.any { element ->
            element.text.normalizedLookupKey().contains(normalizedQuery)
        }
    }

    private fun UiTargetEvidenceCandidate.isSubmitOnlySearchButton(submitCandidateLabel: String): Boolean {
        val normalizedLabel = label.normalizedLookupKey()
        return !editable &&
            normalizedLabel.isNotBlank() &&
            normalizedLabel == submitCandidateLabel &&
            (normalizedLabel == "搜索" || normalizedLabel == "search" || normalizedLabel == "搜")
    }

    private fun UiTargetEvidenceCandidate.targetLabel(fallback: String): String =
        label.trim().takeIf { value -> value.isNotBlank() }
            ?: matchedProfileHint?.trim()?.takeIf { value -> value.isNotBlank() }
            ?: fallback
}

internal data class ModelDrivenAppSearchRecoveryPlan(
    val kind: String,
    val request: ToolRequest,
    val summary: String,
)
