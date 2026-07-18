package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.extractUiTargetArgumentValue
import com.bytedance.zgx.solin.audit.ToolAuditSummaryRedactor
import com.bytedance.zgx.solin.device.AppSearchProgressEvidence
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenObservation
import com.bytedance.zgx.solin.device.UiTargetEvidenceCandidate
import com.bytedance.zgx.solin.device.UiTargetKind
import com.bytedance.zgx.solin.device.UiTargetResolver
import com.bytedance.zgx.solin.device.containsOcrGroundingBounds
import com.bytedance.zgx.solin.device.hasDangerousActionText
import com.bytedance.zgx.solin.device.hasOcrDangerousActionText
import com.bytedance.zgx.solin.device.hasStrongDangerousActionText
import com.bytedance.zgx.solin.device.normalizedLookupKey
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

private const val DEFAULT_MAX_SEQUENTIAL_ACTIONS = 4
private const val DEFAULT_MAX_MODEL_OBSERVATION_REPLANS = 1
private const val SEQUENTIAL_REPLAN_REQUEST_REASON = "Explicit sequential action step planned."
private const val MODEL_OBSERVATION_REPLAN_REQUEST_REASON = "Observation model step planned."
private const val MODEL_OBSERVATION_REPLAN_FALLBACK_REASON = "observation model replan"
private const val MAX_LOCAL_OBSERVATION_ELEMENTS = 10
private const val MAX_LOCAL_OCR_BLOCKS = 8
private const val MAX_LOCAL_TARGET_CANDIDATES = 8
private const val MAX_LOCAL_TARGET_LABELS_PER_MODE = 4
private const val MAX_LOCAL_EVIDENCE_CHARS = 2_000
private const val MAX_LOCAL_DIAGNOSTICS_CHARS = 800
private const val MAX_PRIOR_REQUEST_DETAILS = 4
private const val MAX_PRIOR_REQUEST_DETAILS_CHARS = 360
private const val MAX_OBSERVATION_MODEL_PROMPT_CHARS = 2_600

internal val MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES = setOf(
    MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
    MobileActionFunctions.UI_TAP,
    MobileActionFunctions.UI_TYPE_TEXT,
    MobileActionFunctions.UI_SUBMIT_SEARCH,
    MobileActionFunctions.UI_SCROLL,
    MobileActionFunctions.UI_WAIT,
    MobileActionFunctions.UI_PRESS_BACK,
)
private val searchContextTextMarkers = listOf(
    "搜索",
    "查找",
    "查询",
    "关键词",
    "搜索词",
    "搜索框",
    "搜索栏",
    "搜索输入",
    "地址栏",
    "网址",
    "目的地",
    "search",
    "searchbox",
    "searchfield",
    "omnibox",
    "keyword",
)
private val strongSearchContextTextMarkers = listOf(
    "搜索商品",
    "搜索店铺",
    "搜索词",
    "搜索框",
    "搜索栏",
    "搜索输入",
    "搜索或输入网址",
    "地址栏",
    "网址",
    "目的地",
    "搜地点",
    "关键词",
    "searchbox",
    "searchfield",
    "omnibox",
    "keyword",
)
private val standaloneSearchSubmitTextMarkers = listOf(
    "搜索",
    "查找",
    "查询",
    "前往",
    "确定",
    "完成",
    "go",
    "enter",
    "search",
)
private val defaultSequentialToolRegistry = ToolRegistry()
private val localObservationJsonKeys = listOf(
    "screenObservationJson",
    "beforeScreenObservationJson",
    "afterScreenObservationJson",
)
private val localObservationDiagnosticKeys = listOf(
    "actionType",
    "target",
    "direction",
    "status",
    "retryable",
    "failureKind",
    "searchVerificationStatus",
    "searchVerificationEvidence",
    "uiActionOutcome",
    "uiActionOutcomeReason",
    "appSearchProgressStage",
    "verificationSummary",
    "beforeObservationId",
    "afterObservationId",
    "beforeNodeCount",
    "afterNodeCount",
    "beforeActionableNodeCount",
    "afterActionableNodeCount",
    "beforeTruncated",
    "afterTruncated",
)
private val defaultResolverPromptTargetKinds = listOf(
    UiTargetKind.SearchEntry,
    UiTargetKind.EditableField,
    UiTargetKind.SubmitSearch,
    UiTargetKind.FilterEntry,
    UiTargetKind.ScrollContainer,
)

private data class LocalObservationPromptSection(
    val text: String,
    val containsOcrSource: Boolean,
)

private data class LocalTargetCandidatePrompt(
    val label: String,
    val targetValue: String,
    val modeTags: List<String>,
    val role: String,
    val promptText: String,
)

private data class ResolverTargetRank(
    val mode: String,
    val targetValue: String,
    val rank: Int,
)

private data class OcrTargetEvidencePrompt(
    val id: String,
    val text: String,
    val role: String,
    val bounds: String,
)

fun interface AgentObservationReplanner {
    fun planNext(context: AgentObservationReplanContext): AgentObservationReplan?
}

data class AgentObservationReplanContext(
    val run: AgentRun,
    val previousRequest: ToolRequest,
    val observedResult: ToolResult,
    val priorRequests: List<ToolRequest>,
    val nextActionInput: String? = null,
    val completedSegmentCount: Int = priorRequests.size,
)

data class AgentObservationReplan(
    val request: ToolRequest,
    val draft: ActionDraft,
    val plannedByModel: Boolean = false,
    val fallbackReason: String? = null,
    val input: String? = null,
) {
    init {
        require(request.toolName == draft.functionName) {
            "Replanned request and draft must target the same tool."
        }
    }
}

object NoOpAgentObservationReplanner : AgentObservationReplanner {
    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? = null
}

class CompositeAgentObservationReplanner(
    vararg delegates: AgentObservationReplanner,
) : AgentObservationReplanner {
    private val delegates = delegates.toList()

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? =
        delegates.firstNotNullOfOrNull { delegate -> delegate.planNext(context) }
}

class ModelObservationReplanner(
    private val actionPlanningRuntime: ActionPlanningRuntime,
    private val actionModelPathProvider: () -> String? = { null },
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    maxModelReplans: Int = DEFAULT_MAX_MODEL_OBSERVATION_REPLANS,
) : AgentObservationReplanner {
    private val modelReplanLimit = maxModelReplans.coerceAtLeast(0)
    private val lastDiagnostic = AtomicReference<ModelObservationReplanDiagnostic?>(null)
    private val diagnostics = CopyOnWriteArrayList<ModelObservationReplanDiagnostic>()

    fun lastDiagnosticSnapshot(): ModelObservationReplanDiagnostic? = lastDiagnostic.get()
    fun diagnosticSnapshots(): List<ModelObservationReplanDiagnostic> = diagnostics.toList()

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (!context.observedResult.isModelReplannableObservation()) {
            recordDiagnostic(ModelObservationReplanDiagnostic.notAttempted("not_model_replannable_observation"))
            return null
        }
        if (modelReplanLimit == 0) {
            recordDiagnostic(ModelObservationReplanDiagnostic.notAttempted("model_replan_disabled"))
            return null
        }
        if (context.modelObservationReplanCount() >= modelReplanLimit) {
            recordDiagnostic(ModelObservationReplanDiagnostic.notAttempted("model_replan_limit_reached"))
            return null
        }
        val actionModelPath = actionModelPathProvider()?.takeIf { path -> path.isNotBlank() }
        if (actionModelPath == null) {
            recordDiagnostic(ModelObservationReplanDiagnostic.notAttempted("missing_action_model"))
            return null
        }
        val prompts = listOf(
            context.observationModelPrompt(toolRegistry),
            context.minimalObservationModelPrompt(toolRegistry),
        ).distinct()
        prompts.forEachIndexed { promptIndex, prompt ->
            val planningResult = actionPlanningRuntime.plan(
                input = prompt,
                actionModelPath = actionModelPath,
            )
            val acceptance = context.acceptModelObservationReplan(planningResult)
            if (acceptance.replan != null) {
                recordDiagnostic(
                    planningResult.toObservationReplanDiagnostic(
                        promptIndex = promptIndex,
                        reason = "accepted",
                    ),
                )
                return acceptance.replan
            }
            recordDiagnostic(
                planningResult.toObservationReplanDiagnostic(
                    promptIndex = promptIndex,
                    reason = acceptance.rejectionReason ?: "model_replan_not_accepted",
                ),
            )
            if (planningResult.usedModel &&
                planningResult.plan.kind == ActionPlanKind.Draft &&
                planningResult.plan.draft != null
            ) {
                return null
            }
        }
        return null
    }

    private fun recordDiagnostic(diagnostic: ModelObservationReplanDiagnostic) {
        lastDiagnostic.set(diagnostic)
        diagnostics += diagnostic
    }

    private fun AgentObservationReplanContext.acceptModelObservationReplan(
        planningResult: ActionPlanningResult,
    ): ModelObservationReplanAcceptance {
        if (!planningResult.usedModel) {
            return ModelObservationReplanAcceptance(
                rejectionReason = planningResult.modelFailureReason
                    ?: planningResult.fallbackReason
                    ?: "model_not_used",
            )
        }
        if (planningResult.plan.kind != ActionPlanKind.Draft) {
            return ModelObservationReplanAcceptance(
                rejectionReason = "model_plan_${planningResult.plan.kind.name.lowercase()}",
            )
        }
        val draft = planningResult.plan.draft
            ?.normalizedUiTargetDraft()
            ?.tapFirstForSearchTypingTarget(this)
            ?: return ModelObservationReplanAcceptance(rejectionReason = "missing_model_draft")
        val rejectionReason = when {
            shouldRejectNonLocalObservationTool(draft, toolRegistry) -> "non_local_observation_tool"
            draft.hasMissingRequiredUiTarget() -> "missing_required_ui_target"
            shouldRejectDangerousObservationAction(draft) -> "dangerous_observation_action"
            shouldRejectTextOnlyUiControl(draft) -> "text_only_ui_control_without_structured_target"
            shouldRejectUnsupportedSubmitSearch(draft) -> "unsupported_submit_search"
            shouldRejectUnsupportedTargetlessTyping(draft) -> "unsupported_targetless_typing"
            shouldRejectUnsupportedObservedTarget(draft) -> "unsupported_observed_target"
            shouldRejectUnsupportedRepeatTarget(draft) -> "unsupported_repeat_target"
            else -> null
        }
        if (rejectionReason != null) {
            return ModelObservationReplanAcceptance(rejectionReason = rejectionReason)
        }
        return ModelObservationReplanAcceptance(
            replan = AgentObservationReplan(
                request = ToolRequest(
                    toolName = draft.functionName,
                    arguments = draft.parameters,
                    reason = MODEL_OBSERVATION_REPLAN_REQUEST_REASON,
                ),
                draft = draft,
                plannedByModel = true,
                fallbackReason = MODEL_OBSERVATION_REPLAN_FALLBACK_REASON,
            ),
        )
    }
}

data class ModelObservationReplanDiagnostic(
    val attempted: Boolean,
    val reason: String,
    val promptIndex: Int? = null,
    val usedModel: Boolean = false,
    val modelAttempted: Boolean = false,
    val modelPlanKind: String? = null,
    val modelFailureReason: String? = null,
    val modelOutputPreview: String? = null,
) {
    companion object {
        fun notAttempted(reason: String): ModelObservationReplanDiagnostic =
            ModelObservationReplanDiagnostic(
                attempted = false,
                reason = reason,
            )
    }
}

private data class ModelObservationReplanAcceptance(
    val replan: AgentObservationReplan? = null,
    val rejectionReason: String? = null,
)

private fun ActionPlanningResult.toObservationReplanDiagnostic(
    promptIndex: Int,
    reason: String,
): ModelObservationReplanDiagnostic =
    ModelObservationReplanDiagnostic(
        attempted = true,
        reason = reason,
        promptIndex = promptIndex,
        usedModel = usedModel,
        modelAttempted = modelAttempted,
        modelPlanKind = plan.kind.name,
        modelFailureReason = modelFailureReason ?: fallbackReason,
        modelOutputPreview = modelOutputPreview,
    )

private fun ActionDraft.normalizedUiTargetDraft(): ActionDraft {
    if (!functionName.acceptsGuardedUiTarget()) return this
    val rawTarget = parameters["target"] ?: return this
    val normalizedTarget = rawTarget.extractUiTargetArgumentValue(toolName = functionName)
    if (normalizedTarget == rawTarget) return this
    return copy(parameters = parameters + ("target" to normalizedTarget))
}

private fun ActionDraft.tapFirstForSearchTypingTarget(context: AgentObservationReplanContext): ActionDraft {
    if (functionName != MobileActionFunctions.UI_TYPE_TEXT) return this
    val normalizedTarget = parameters["target"].normalizedLookupKey()
        .takeIf { value -> value.isNotBlank() }
        ?: return this
    if (context.observedResult.hasCurrentTargetEvidence(normalizedTarget, MobileActionFunctions.UI_TYPE_TEXT)) {
        return this
    }
    if (!context.observedResult.hasCurrentSearchTapTargetEvidence(normalizedTarget)) return this
    val tapParameters = parameters
        .filterKeys { key -> key != "text" && key != "verifySearchQuery" && key != "expectedAppName" }
    return copy(
        functionName = MobileActionFunctions.UI_TAP,
        title = "点击搜索入口",
        summary = "先点击搜索入口以打开输入框。",
        parameters = tapParameters,
    )
}

private fun ActionDraft.hasMissingRequiredUiTarget(): Boolean =
    functionName == MobileActionFunctions.UI_TAP &&
        parameters["target"].normalizedLookupKey().isBlank()

private fun AgentObservationReplanContext.shouldRejectNonLocalObservationTool(
    draft: ActionDraft,
    toolRegistry: ToolRegistry,
): Boolean {
    if (!observedResult.hasLocalOnlyObservationEvidence(toolRegistry.specFor(previousRequest.toolName))) return false
    if (draft.functionName !in MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES) return true
    return toolRegistry.specFor(draft.functionName)?.capability != ToolCapability.DeviceControl
}

private fun AgentObservationReplanContext.shouldRejectUnsupportedSubmitSearch(draft: ActionDraft): Boolean {
    if (draft.functionName != MobileActionFunctions.UI_SUBMIT_SEARCH) return false
    if (!observedResult.hasCurrentTargetEvidenceSource()) return false
    return !observedResult.hasCurrentSearchSubmitEvidence()
}

private fun AgentObservationReplanContext.shouldRejectUnsupportedTargetlessTyping(draft: ActionDraft): Boolean {
    if (draft.functionName != MobileActionFunctions.UI_TYPE_TEXT) return false
    if (draft.parameters["target"].normalizedLookupKey().isNotBlank()) return false
    if (!observedResult.hasCurrentTargetEvidenceSource()) return false
    return !observedResult.hasCurrentTargetlessTypingEvidence()
}

private fun AgentObservationReplanContext.shouldRejectUnsupportedObservedTarget(draft: ActionDraft): Boolean {
    if (!draft.functionName.acceptsGuardedUiTarget()) return false
    if (!observedResult.hasCurrentTargetEvidenceSource()) return false
    val nextTarget = draft.parameters["target"].normalizedLookupKey()
        .takeIf { value -> value.isNotBlank() }
        ?: return draft.functionName == MobileActionFunctions.UI_SCROLL &&
            !observedResult.hasCurrentScrollableEvidence()
    return !observedResult.hasCurrentTargetEvidence(nextTarget, draft.functionName)
}

private fun AgentObservationReplanContext.shouldRejectTextOnlyUiControl(draft: ActionDraft): Boolean {
    if (!draft.functionName.isAutonomousUiControlAction()) return false
    if (observedResult.hasCurrentTargetEvidenceSource()) return false
    return observedResult.hasReadOnlyLocalScreenTextEvidence()
}

private fun AgentObservationReplanContext.shouldRejectDangerousObservationAction(draft: ActionDraft): Boolean {
    if (!draft.functionName.isAutonomousUiControlAction()) return false
    val target = draft.parameters["target"].normalizedLookupKey()
    if (
        target.isNotBlank() &&
        draft.functionName.acceptsGuardedUiTarget() &&
        observedResult.hasCurrentTargetEvidenceSource()
    ) {
        return observedResult.hasDangerousTargetEvidence(target, draft.functionName)
    }
    return observedResult.hasDangerousUiActionEvidence()
}

private fun String.isAutonomousUiControlAction(): Boolean =
    this == MobileActionFunctions.UI_TAP ||
        this == MobileActionFunctions.UI_TYPE_TEXT ||
        this == MobileActionFunctions.UI_SUBMIT_SEARCH ||
        this == MobileActionFunctions.UI_SCROLL

private fun ToolResult.hasDangerousUiActionEvidence(): Boolean =
    data["screenObservationJson"].observationHasDangerousActionEvidence() ||
        data["afterScreenObservationJson"].observationHasDangerousActionEvidence() ||
        data["ocrBlocksJson"].ocrBlocksHaveDangerousActionEvidence() ||
        data["ocrText"].hasStrongDangerousActionText() ||
        data["screenText"].hasStrongDangerousActionText()

private fun ToolResult.hasDangerousTargetEvidence(normalizedTarget: String, toolName: String): Boolean =
    data["screenObservationJson"].observationHasDangerousTargetEvidence(normalizedTarget, toolName) ||
        data["afterScreenObservationJson"].observationHasDangerousTargetEvidence(normalizedTarget, toolName) ||
        data["ocrBlocksJson"].ocrBlocksHaveDangerousTargetEvidence(normalizedTarget)

private fun String?.observationHasDangerousActionEvidence(): Boolean =
    withObservationElements { elements ->
        elements.objects().any { element -> element.isDangerousActionEvidence() }
    }

private fun String?.observationHasDangerousTargetEvidence(normalizedTarget: String, toolName: String): Boolean =
    withObservationElements { elements ->
        elements.objects().any { element ->
            element.isDangerousTargetEvidence(normalizedTarget, toolName)
        }
    }

private fun JSONObject.isDangerousActionEvidence(): Boolean {
    val text = optString("text")
    val source = optString("source")
    return if (source == "ocr") {
        text.hasOcrDangerousActionText()
    } else {
        text.hasDangerousActionText() && hasActionableModeForPrompt()
    }
}

private fun JSONObject.isDangerousTargetEvidence(normalizedTarget: String, toolName: String): Boolean {
    if (!isTargetEvidenceMatch(normalizedTarget)) return false
    val text = optString("text")
    return if (optString("source") == "ocr") {
        text.hasOcrDangerousActionText()
    } else {
        text.hasDangerousActionText() && supportsToolTargetMode(toolName)
    }
}

private fun String?.ocrBlocksHaveDangerousActionEvidence(): Boolean =
    withOcrBlocks { blocks ->
        blocks.objects().any { block ->
            block.optString("text").hasOcrDangerousActionText()
        }
    }

private fun String?.ocrBlocksHaveDangerousTargetEvidence(normalizedTarget: String): Boolean =
    withOcrBlocks { blocks ->
        blocks.objects().any { block ->
            val text = block.optString("text")
            text.normalizedLookupKey() == normalizedTarget &&
                text.hasOcrDangerousActionText()
        }
    }

private fun AgentObservationReplanContext.shouldRejectUnsupportedRepeatTarget(draft: ActionDraft): Boolean {
    if (observedResult.status != ToolStatus.Failed) return false
    if (previousRequest.toolName != draft.functionName) return false
    if (!draft.functionName.acceptsGuardedUiTarget()) return false
    val previousTarget = previousRequest.arguments["target"].normalizedLookupKey()
        .takeIf { value -> value.isNotBlank() }
        ?: return false
    val nextTarget = draft.parameters["target"].normalizedLookupKey()
        .takeIf { value -> value.isNotBlank() }
        ?: return false
    if (previousTarget != nextTarget) return false
    return !observedResult.hasEvidenceSupportingRepeatTarget(nextTarget, draft.functionName)
}

private fun String.acceptsGuardedUiTarget(): Boolean =
    this == MobileActionFunctions.UI_TAP ||
        this == MobileActionFunctions.UI_TYPE_TEXT ||
        this == MobileActionFunctions.UI_SCROLL

private fun ToolResult.hasCurrentTargetEvidenceSource(): Boolean =
    data["screenObservationJson"]?.isNotBlank() == true ||
        data["afterScreenObservationJson"]?.isNotBlank() == true ||
        data["ocrBlocksJson"]?.isNotBlank() == true ||
        data["screenObservationDiffSummary"]?.isNotBlank() == true

private fun ToolResult.hasReadOnlyLocalScreenTextEvidence(): Boolean =
    data["ocrText"]?.isNotBlank() == true ||
        data["screenText"]?.isNotBlank() == true

private fun ToolResult.hasCurrentTargetEvidence(normalizedTarget: String): Boolean =
    data["screenObservationJson"].observationHasTargetEvidence(normalizedTarget) ||
        data["afterScreenObservationJson"].observationHasTargetEvidence(normalizedTarget) ||
        executableStandaloneOcrBlocksJson().ocrBlocksHaveTargetEvidence(normalizedTarget) ||
        data["screenObservationDiffSummary"].diffSummaryHasTargetEvidence(normalizedTarget)

private fun ToolResult.hasCurrentTargetEvidence(normalizedTarget: String, toolName: String): Boolean =
    data["screenObservationJson"].observationHasTargetEvidence(normalizedTarget, toolName) ||
        data["afterScreenObservationJson"].observationHasTargetEvidence(normalizedTarget, toolName) ||
        executableStandaloneOcrBlocksJson().ocrBlocksHaveTargetEvidence(normalizedTarget) ||
        data["screenObservationDiffSummary"].diffSummaryHasTargetEvidence(normalizedTarget)

private fun ToolResult.hasCurrentSearchTapTargetEvidence(normalizedTarget: String): Boolean =
    data["screenObservationJson"].observationHasSearchTapTargetEvidence(normalizedTarget) ||
        data["afterScreenObservationJson"].observationHasSearchTapTargetEvidence(normalizedTarget)

private fun ToolResult.hasCurrentScrollableEvidence(): Boolean =
    data["screenObservationJson"].observationHasScrollableEvidence() ||
        data["afterScreenObservationJson"].observationHasScrollableEvidence() ||
        data["screenObservationDiffSummary"].diffSummaryHasScrollableEvidence()

private fun ToolResult.hasCurrentSearchSubmitEvidence(): Boolean =
    data["screenObservationJson"].observationHasSearchSubmitEvidence() ||
        data["afterScreenObservationJson"].observationHasSearchSubmitEvidence() ||
        executableStandaloneOcrBlocksJson().ocrBlocksHaveSearchSubmitEvidence() ||
        data["screenObservationDiffSummary"].diffSummaryHasSearchSubmitEvidence()

private fun ToolResult.hasCurrentTargetlessTypingEvidence(): Boolean =
    data["screenObservationJson"].observationHasTargetlessTypingEvidence() ||
        data["afterScreenObservationJson"].observationHasTargetlessTypingEvidence() ||
        executableStandaloneOcrBlocksJson().ocrBlocksHaveTargetlessTypingEvidence() ||
        data["screenObservationDiffSummary"].diffSummaryHasTargetlessTypingEvidence()

private fun ToolResult.executableStandaloneOcrBlocksJson(): String? {
    val blocksJson = data["ocrBlocksJson"]?.takeIf { value -> value.isNotBlank() } ?: return null
    if (data["screenObservationIncluded"] != true.toString()) return null
    if (data["screenObservationJson"].isNullOrBlank()) return null
    if (!data["screenObservationFailureKind"].isNullOrBlank()) return null
    return blocksJson
}

private fun ToolResult.hasEvidenceSupportingRepeatTarget(normalizedTarget: String, toolName: String): Boolean {
    if (data["screenObservationDiffSummary"].hasPositiveScreenChangeSignal()) return true
    return hasCurrentTargetEvidence(normalizedTarget, toolName)
}

private fun ToolResult.hasLocalOnlyObservationEvidence(spec: ToolSpec?): Boolean =
    PromptPrivacyPlanner.build(listOf(toPromptPrivacySegment(spec))).let { plan ->
        plan.aggregatePrivacy == MessagePrivacy.LocalOnly || plan.requiresLocalModel
    }

private fun String?.hasPositiveScreenChangeSignal(): Boolean =
    this?.contains(Regex("""\bchanged\s*=\s*true\b""", RegexOption.IGNORE_CASE)) == true

private fun String?.diffSummaryHasTargetEvidence(normalizedTarget: String): Boolean {
    if (normalizedTarget.isBlank()) return false
    return this
        .diffSummaryValuesFor("addedText", "addedActionable")
        .flatMap { value -> value.diffSummaryEvidenceLabels() }
        .any { value -> value.normalizedLookupKey() == normalizedTarget }
}

private fun String?.diffSummaryHasScrollableEvidence(): Boolean =
    this.diffSummaryValuesFor("addedActionable")
        .any { value -> value.normalizedLookupKey().contains("scrollable") }

private fun String?.diffSummaryHasSearchSubmitEvidence(): Boolean =
    this.diffSummaryValuesFor("addedText", "addedActionable")
        .any { value -> value.hasStrongSearchContextText() }

private fun String?.diffSummaryHasTargetlessTypingEvidence(): Boolean =
    this.diffSummaryValuesFor("addedText", "addedActionable")
        .any { value -> value.hasStrongSearchContextText() }

private fun String?.diffSummaryValuesFor(vararg keys: String): List<String> {
    val acceptedKeys = keys.toSet()
    return this
        ?.split(";")
        .orEmpty()
        .mapNotNull { segment ->
            val parts = segment.split("=", limit = 2)
            val key = parts.getOrNull(0)?.trim()
            val value = parts.getOrNull(1)?.trim()
            value?.takeIf { key in acceptedKeys && it.isNotBlank() && it != "none" }
        }
}

private fun String.diffSummaryEvidenceLabels(): List<String> =
    split("|")
        .map { value -> value.trim() }
        .filter { value -> value.isNotBlank() && value != "none" }
        .flatMap { value ->
            val labelWithoutMode = value.substringAfter(":", value).trim()
            listOf(value, labelWithoutMode)
        }
        .distinct()

private fun String?.observationHasTargetEvidence(normalizedTarget: String): Boolean =
    withObservationElements { elements ->
        elements.hasTargetEvidence(normalizedTarget)
    }

private fun String?.observationHasTargetEvidence(normalizedTarget: String, toolName: String): Boolean =
    withObservationElements { elements ->
        elements.hasTargetEvidence(normalizedTarget, toolName)
    }

private fun String?.observationHasSearchTapTargetEvidence(normalizedTarget: String): Boolean =
    withObservationElements { elements ->
        elements.hasSearchTapTargetEvidence(normalizedTarget)
    }

private fun String?.observationHasScrollableEvidence(): Boolean =
    withObservationElements { elements ->
        elements.objects().any { element -> element.hasScrollableModeForPrompt() }
    }

private fun String?.observationHasSearchSubmitEvidence(): Boolean =
    withObservationElements { elements ->
        val ocrTexts = mutableListOf<String>()
        val hasStructuredSearchSubmitEvidence = elements.objects().any { element ->
            if (element.optString("source") == "ocr") {
                ocrTexts += element.optString("text")
                false
            } else {
                element.isSearchSubmitEvidence()
            }
        }
        hasStructuredSearchSubmitEvidence || ocrTexts.hasOcrSearchSubmitEvidence()
    }

private fun String?.observationHasTargetlessTypingEvidence(): Boolean =
    withObservationElements { elements ->
        val ocrTexts = mutableListOf<String>()
        val hasStructuredTypingEvidence = elements.objects().any { element ->
            if (element.optString("source") == "ocr") {
                ocrTexts += element.optString("text")
                false
            } else {
                element.isSearchTypingEvidence()
            }
        }
        hasStructuredTypingEvidence || ocrTexts.any { text -> text.hasStrongSearchContextText() }
    }

private fun String?.ocrBlocksHaveSearchSubmitEvidence(): Boolean =
    withOcrBlocks { blocks ->
        val texts = blocks.objects().map { block -> block.optString("text") }
        texts.hasOcrSearchSubmitEvidence()
    }

private fun String?.ocrBlocksHaveTargetlessTypingEvidence(): Boolean =
    withOcrBlocks { blocks ->
        blocks.objects().any { block ->
            block.optString("text").hasStrongSearchContextText()
        }
    }

private fun List<String>.hasOcrSearchSubmitEvidence(): Boolean =
    any { text -> text.hasStrongSearchContextText() } &&
        any { text -> text.hasStandaloneSearchSubmitText() }

private fun JSONArray.hasTargetEvidence(normalizedTarget: String): Boolean {
    val elements = objects()
    if (elements.any { element -> element.isTargetIdEvidence(normalizedTarget) }) return true
    if (elements.any { element -> element.isAccessibilityTextTargetEvidence(normalizedTarget) }) return true
    return elements
        .filter { element -> element.optString("source") == "ocr" }
        .count { element -> element.optString("text").matchesOcrTargetText(normalizedTarget) } == 1
}

private fun JSONArray.hasTargetEvidence(normalizedTarget: String, toolName: String): Boolean {
    val elements = objects()
    if (elements.any { element -> element.isTargetIdEvidence(normalizedTarget, toolName) }) return true
    if (elements.any { element -> element.isAccessibilityTextTargetEvidence(normalizedTarget, toolName) }) return true
    return elements
        .filter { element -> element.optString("source") == "ocr" }
        .count { element -> element.optString("text").matchesOcrTargetText(normalizedTarget) } == 1
}

private fun JSONArray.hasSearchTapTargetEvidence(normalizedTarget: String): Boolean {
    val elements = objects()
    val ocrElements = elements.filter { element -> element.optString("source") == "ocr" }
    return elements.any { element ->
        element.isTargetEvidenceMatch(normalizedTarget) &&
            element.supportsToolTargetMode(MobileActionFunctions.UI_TAP) &&
            element.hasSearchContextText(ocrElements)
    }
}

private fun JSONObject.hasSearchContextText(ocrElements: List<JSONObject>): Boolean {
    if (optString("text").hasSearchContextText()) return true
    val bounds = optJSONObject("bounds") ?: return false
    return ocrElements.any { ocrElement ->
        ocrElement.optString("text").hasSearchContextText() &&
            bounds.containsOcrGroundingElementBounds(ocrElement.optJSONObject("bounds"))
    }
}

private fun JSONObject.isTargetEvidenceMatch(normalizedTarget: String): Boolean =
    optString("id").normalizedLookupKey() == normalizedTarget ||
        (
            optString("source") != "ocr" &&
                optString("text").normalizedLookupKey() == normalizedTarget
            )

private fun JSONObject.isTargetIdEvidence(normalizedTarget: String, toolName: String? = null): Boolean {
    val source = optString("source")
    val idMatches = optString("id").normalizedLookupKey() == normalizedTarget
    if (!idMatches) return false
    return source == "ocr" || toolName?.let(::supportsToolTargetMode) ?: hasActionableModeForPrompt()
}

private fun JSONObject.isAccessibilityTextTargetEvidence(
    normalizedTarget: String,
    toolName: String? = null,
): Boolean =
    optString("source") != "ocr" &&
        optString("text").normalizedLookupKey() == normalizedTarget &&
        (toolName?.let(::supportsToolTargetMode) ?: hasActionableModeForPrompt())

private fun JSONObject.supportsToolTargetMode(toolName: String): Boolean {
    val clickability = optJSONObject("clickability") ?: return false
    if (clickability.optBoolean("enabled", true) == false) return false
    return when (toolName) {
        MobileActionFunctions.UI_TYPE_TEXT -> clickability.optBoolean("editable", false)
        MobileActionFunctions.UI_TAP -> clickability.optBoolean("clickable", false) ||
            clickability.optBoolean("editable", false)
        MobileActionFunctions.UI_SCROLL -> clickability.optBoolean("scrollable", false)
        else -> hasActionableModeForPrompt()
    }
}

private fun JSONObject.isSearchSubmitEvidence(): Boolean {
    val text = optString("text")
    val clickability = optJSONObject("clickability") ?: return false
    if (clickability.optBoolean("enabled", true) == false) return false
    if (clickability.optBoolean("editable", false) && text.hasSearchContextText()) return true
    if (clickability.optBoolean("clickable", false) && text.hasStrongSearchContextText()) return true
    return false
}

private fun JSONObject.isSearchTypingEvidence(): Boolean {
    val text = optString("text")
    val clickability = optJSONObject("clickability") ?: return false
    if (clickability.optBoolean("enabled", true) == false) return false
    return clickability.optBoolean("editable", false) && text.hasSearchContextText()
}

private fun String?.hasSearchContextText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    return searchContextTextMarkers.any { marker ->
        normalized.contains(marker.normalizedLookupKey())
    }
}

private fun String?.hasStrongSearchContextText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    return strongSearchContextTextMarkers.any { marker ->
        normalized.contains(marker.normalizedLookupKey())
    }
}

private fun String?.hasStandaloneSearchSubmitText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    return standaloneSearchSubmitTextMarkers.any { marker ->
        normalized == marker.normalizedLookupKey()
    }
}

private fun String?.ocrBlocksHaveTargetEvidence(normalizedTarget: String): Boolean =
    withOcrBlocks { blocks ->
        val evidences = blocks.indexedObjects().flatMap { (index, block) ->
            block.toOcrTargetEvidencePrompts(index)
        }
        evidences.any { evidence -> evidence.id.normalizedLookupKey() == normalizedTarget } ||
            evidences.count { evidence -> evidence.text.matchesOcrTargetText(normalizedTarget) } == 1
    }

private fun String.matchesOcrTargetText(normalizedTarget: String): Boolean {
    val normalizedText = normalizedLookupKey()
    if (normalizedText.isBlank() || normalizedTarget.isBlank()) return false
    return normalizedText == normalizedTarget ||
        normalizedText.contains(normalizedTarget) ||
        normalizedTarget.contains(normalizedText)
}

class SequentialActionObservationReplanner(
    private val actionPlanningRuntime: ActionPlanningRuntime,
    maxSequentialActions: Int = DEFAULT_MAX_SEQUENTIAL_ACTIONS,
    private val toolRegistry: ToolRegistry = defaultSequentialToolRegistry,
) : AgentObservationReplanner {
    private val sequentialActionLimit = maxSequentialActions.coerceAtLeast(1)

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (context.observedResult.status != ToolStatus.Succeeded) return null
        val completedSegmentCount = context.completedSegmentCount
        if (completedSegmentCount <= 0 || completedSegmentCount >= sequentialActionLimit) return null
        val nextInput = context.nextActionInput?.immediateSequentialActionText()
            ?: context.run.input.explicitSequentialActionTextAt(completedSegmentCount)
            ?: return null
        val intent = actionPlanningRuntime.classifyIntent(nextInput)
        if (!intent.isAction || !intent.confidence.isActionableForSequentialReplan()) return null
        val planningResult = actionPlanningRuntime.plan(nextInput, actionModelPath = null)
        val draft = planningResult.plan.draft ?: return null
        if (planningResult.plan.kind != ActionPlanKind.Draft) return null
        if (
            draft.functionName.requiresLocalModelBeforeSequentialTail(toolRegistry) &&
            context.run.input.explicitSequentialActionTextAt(completedSegmentCount + 1) != null
        ) {
            return null
        }
        return AgentObservationReplan(
            request = ToolRequest(
                toolName = draft.functionName,
                arguments = draft.parameters,
                reason = SEQUENTIAL_REPLAN_REQUEST_REASON,
            ),
            draft = draft,
            plannedByModel = planningResult.usedModel,
            fallbackReason = planningResult.fallbackReason,
            input = nextInput,
        )
    }

}

internal fun ActionIntentConfidence.isActionableForAgentPlan(): Boolean =
    this == ActionIntentConfidence.Medium || this == ActionIntentConfidence.High

internal fun ActionIntentConfidence.isActionableForSequentialReplan(): Boolean =
    this == ActionIntentConfidence.High

private fun AgentObservationReplanContext.modelObservationReplanCount(): Int =
    priorRequests.count { request -> request.reason == MODEL_OBSERVATION_REPLAN_REQUEST_REASON }

private fun ToolResult.isModelReplannableObservation(): Boolean =
    when (status) {
        ToolStatus.Succeeded -> true
        ToolStatus.Failed -> isRecoverableLocalObservationFailure()
        ToolStatus.Cancelled,
        ToolStatus.Rejected -> false
    }

private fun ToolResult.isRecoverableLocalObservationFailure(): Boolean {
    if (!retryable) return false
    if (error?.code != null && error.code != ToolErrorCode.ExecutionFailed) return false
    if (data["failureKind"] == "permission_missing") return false
    return localObservationJsonKeys.any { key -> data[key]?.isNotBlank() == true } ||
        data["screenObservationDiffSummary"]?.isNotBlank() == true ||
        data["ocrBlocksJson"]?.isNotBlank() == true ||
        data["ocrText"]?.isNotBlank() == true ||
        data["screenText"]?.isNotBlank() == true
}

internal fun AgentObservationReplanContext.observationModelPrompt(toolRegistry: ToolRegistry): String {
    val intentPreview = (nextActionInput?.immediateSequentialActionText() ?: run.input)
        .safeObservationPromptText()
    val observationSummary = observedResult.summary.safeObservationPromptText()
    val observationDiagnostics = observedResult.observationDiagnosticsPrompt()
    val localObservationEvidence = observedResult.localObservationEvidencePrompt(intentPreview)
    val localOnlyAllowedTools = localOnlyObservationAllowedToolsPrompt(toolRegistry)
    val priorRequestDetails = priorRequests.priorRequestDetailsPrompt()
    val appSearchProgress = AppSearchProgressEvidence.fromData(observedResult.data).toPromptText()
    return """
        This is user-authorized local device UI automation; LocalOnly screen evidence stays on-device, so do not refuse when a listed UI action clearly advances the request.
        Output exactly one call such as call:ui_tap{"target":"..."} or call:ui_type_text{"target":"...","text":"..."} when clear; otherwise output ordinary text with no call.
        Decide if the user's request needs exactly one more mobile tool after the latest observation; if unclear, output ordinary text with no call.
        Do not repeat completed work. LocalOnly evidence stays on-device.
        When LocalOnly observation evidence is present, output only these local device-control tools.
        Allowed LocalOnly tools: $localOnlyAllowedTools.
        Do not output web_search or any other non-listed tool from LocalOnly observation evidence.
        When LocalOnly evidence includes targets=[...], copy only the candidate target=... value into the tool target.
        Use only target values from targetShortlist(...) for tools that accept target.
        Never output mode, bounds, confidence, or other candidate metadata as tool arguments.
        Prefer a type-tagged target for ui_type_text, a tap-tagged target for ui_tap, and a scroll-tagged target for ui_scroll; use ocrFallback only if no Accessibility target fits.
        For an app search request, follow tap -> type -> submit.
        If only tap= search entry is visible and no type= input is visible, output ui_tap with that tap value first; only after a type= input appears output ui_type_text with that type value; after the query is typed and submit evidence exists, output ui_submit_search{}.
        Do not output ordinary text when a targetShortlist entry clearly advances the app search request.
        For ui_scroll, stop if no scroll candidate/evidence exists.
        If the latest action failed, avoid the same target unless new evidence supports it.
        Stop on payment, sending, deletion, publishing, ordering, purchase, transfer, or authorization controls.

        User intent preview: $intentPreview
        Prior request details: $priorRequestDetails
        Previous tool: ${previousRequest.toolName}
        Observation status: ${observedResult.status}
        App search progress: $appSearchProgress
        Observation summary: $observationSummary
        Observation diagnostics: $observationDiagnostics
        LocalOnly observation evidence: $localObservationEvidence
    """.trimIndent()
        .compressForObservationModelContext()
}

internal fun String.compressForObservationModelContext(
    maxChars: Int = MAX_OBSERVATION_MODEL_PROMPT_CHARS,
): String {
    val normalized = lineSequence()
        .map { line -> line.trimEnd() }
        .joinToString(separator = "\n")
        .trim()
    if (normalized.length <= maxChars) return normalized

    val valueBudgets = ObservationPromptValueBudgets.forPrompt(maxChars)
    val compressedLines = normalized.lines().map { line ->
        when {
            line.startsWith("LocalOnly observation evidence:") ->
                line.compressLabeledPromptValue(
                    label = "LocalOnly observation evidence:",
                    valueBudget = valueBudgets.localEvidence,
                    protectedPatterns = localEvidenceProtectedPromptPatterns,
                    maxListEntries = 4,
                )

            line.startsWith("Observation diagnostics:") ->
                line.compressLabeledPromptValue(
                    label = "Observation diagnostics:",
                    valueBudget = valueBudgets.diagnostics,
                    protectedPatterns = diagnosticProtectedPromptPatterns,
                )

            line.startsWith("Prior request details:") ->
                line.compressLabeledPromptValue(
                    label = "Prior request details:",
                    valueBudget = valueBudgets.priorRequests,
                    protectedPatterns = priorRequestProtectedPromptPatterns,
                )

            line.startsWith("Observation summary:") ->
                line.compressLabeledPromptValue(
                    label = "Observation summary:",
                    valueBudget = valueBudgets.summary,
                    protectedPatterns = emptyList(),
                )

            line.startsWith("User intent preview:") ->
                line.compressLabeledPromptValue(
                    label = "User intent preview:",
                    valueBudget = valueBudgets.intent,
                    protectedPatterns = emptyList(),
                )

            else -> line
        }
    }
    val compressed = compressedLines.joinToString(separator = "\n")
    if (compressed.length <= maxChars) return compressed

    return compressed.lines()
        .prioritizedForObservationPrompt()
        .joinPrioritizedObservationPrompt(maxChars)
}

private fun AgentObservationReplanContext.minimalObservationModelPrompt(toolRegistry: ToolRegistry): String {
    val intentPreview = (nextActionInput?.immediateSequentialActionText() ?: run.input)
        .safeObservationPromptText(maxLength = 120)
    val localOnlyAllowedTools = localOnlyObservationAllowedToolsPrompt(toolRegistry)
    val localEvidence = observedResult.localObservationEvidencePrompt(intentPreview)
    val targetShortlists = localEvidence.targetShortlistSummariesForPrompt()
    val targetEvidence = localEvidence.targetEvidenceSummariesForPrompt()
    val targetAnchors = targetShortlists.targetShortlistTargetSummary().orEmpty()
    val appSearchProgress = AppSearchProgressEvidence.fromData(observedResult.data).toPromptText()
    return """
        本机已获得用户授权执行手机 UI 自动化。只输出一个 call，不解释，不拒绝。
        允许工具: $localOnlyAllowedTools
        用户请求: $intentPreview
        当前进度: $appSearchProgress
        当前屏幕可用目标: $targetShortlists $targetEvidence $targetAnchors
        上一步工具: ${previousRequest.toolName}
        如果是应用内搜索，严格按 tap -> type -> submit：当前只有 tap= 搜索入口、还没有 type= 输入框时先 ui_tap；type= 输入框出现后再 ui_type_text；已输入且有提交证据后 ui_submit_search{}。
        若要点击，输出 call:ui_tap{"target":"候选target"}。
        若要输入搜索词，输出 call:ui_type_text{"target":"候选target","text":"用户要搜索的词"}。
        若要提交搜索，输出 call:ui_submit_search{}。
    """.trimIndent().compressForObservationModelContext(maxChars = 1_200)
}

private data class ObservationPromptValueBudgets(
    val localEvidence: Int,
    val diagnostics: Int,
    val priorRequests: Int,
    val summary: Int,
    val intent: Int,
) {
    companion object {
        fun forPrompt(maxChars: Int): ObservationPromptValueBudgets =
            ObservationPromptValueBudgets(
                localEvidence = (maxChars * 0.44).toInt().coerceIn(720, 1_600),
                diagnostics = (maxChars * 0.10).toInt().coerceIn(120, 360),
                priorRequests = (maxChars * 0.08).toInt().coerceIn(96, 260),
                summary = (maxChars * 0.06).toInt().coerceIn(80, 180),
                intent = (maxChars * 0.06).toInt().coerceIn(80, 180),
            )
    }
}

private val localEvidenceProtectedPromptPatterns = listOf(
    Regex("""targetShortlist\([^)]*\)"""),
    Regex("""screenObservationDiffSummary=[^|]+"""),
    Regex("""addedText=[^;|]+"""),
    Regex("""addedActionable=[^;|]+"""),
    Regex("""afterScreenObservationJson\(id=[^)]+\)"""),
    Regex("""screenObservationJson\(id=[^)]+\)"""),
)

private val diagnosticProtectedPromptPatterns = listOf(
    Regex("""resultRetryable=[^;|]+"""),
    Regex("""failureKind=[^;|]+"""),
    Regex("""actionType=[^;|]+"""),
    Regex("""uiActionOutcome=[^;|]+"""),
    Regex("""uiActionOutcomeReason=[^;|]+"""),
    Regex("""appSearchProgressStage=[^;|]+"""),
    Regex("""target=[^;|]+"""),
    Regex("""errorCode=[^;|]+"""),
)

private val priorRequestProtectedPromptPatterns = listOf(
    Regex("""ui_[a-z_]+\{[^}]*target=[^}]*\}"""),
    Regex("""observe_current_screen\{[^}]*\}"""),
)

private fun String.compressLabeledPromptValue(
    label: String,
    valueBudget: Int,
    protectedPatterns: List<Regex>,
    maxListEntries: Int = 3,
): String {
    val value = removePrefix(label).trim()
    if (value.length <= valueBudget) return this
    val compactValue = value
        .compressPromptLists("elements", maxOf(2, maxListEntries / 2))
        .compressPromptLists("targets", maxListEntries)
    val shortlistTargetSummary = compactValue.targetShortlistTargetSummary()
    val targetEvidenceSummary = compactValue.targetEvidenceSummariesForPrompt()
        .takeUnless { summary -> summary == "none" }
    val compactValueWithoutDuplicatedEvidence = if (targetEvidenceSummary != null) {
        compactValue.replaceFirst(Regex("""\s*targetEvidence\([^)]*\)"""), "")
    } else {
        compactValue
    }
    val compressibleValue = listOfNotNull(
        shortlistTargetSummary,
        targetEvidenceSummary,
        compactValueWithoutDuplicatedEvidence,
    )
        .joinToString(separator = " ")
    val queryAwareProtectedPatterns = compressibleValue.targetShortlistTargetValuePatterns() + protectedPatterns
    return "$label ${compressibleValue.extractivePromptCompress(valueBudget, queryAwareProtectedPatterns)}"
}

private fun String.compressPromptLists(
    listName: String,
    maxEntries: Int,
): String =
    replace(Regex("""$listName=\[([^]]*)]""")) { match ->
        val entries = match.groupValues[1]
            .split("; ")
            .filter { entry -> entry.isNotBlank() }
        if (entries.size <= maxEntries) {
            match.value
        } else {
            val kept = entries.take(maxEntries).joinToString(separator = "; ")
            "$listName=[$kept; ...+${entries.size - maxEntries}]"
        }
    }

private fun String.targetShortlistTargetValuePatterns(): List<Regex> {
    return targetShortlistTargetValues()
        .map { targetValue -> Regex("""target=${Regex.escape(targetValue)}""") }
}

private fun String.targetShortlistTargetSummary(): String? {
    val values = targetShortlistTargetValues()
    if (values.isEmpty()) return null
    return values.joinToString(prefix = "shortlistTargets=[", postfix = "]", separator = "|") { value ->
        "target=$value"
    }
}

private fun String.targetShortlistTargetValues(): List<String> {
    val shortlist = Regex("""targetShortlist\(([^)]*)\)""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?: return emptyList()
    return shortlist
        .split(",", "|")
        .mapNotNull { part -> part.substringAfter("=", part).trim().takeIf { it.isNotBlank() } }
        .distinct()
}

private fun String.targetShortlistSummariesForPrompt(): String =
    Regex("""targetShortlist\([^)]*\)""")
        .findAll(this)
        .map { match -> match.value }
        .distinct()
        .joinToString(separator = " ")
        .ifBlank { "none" }

private fun String.targetEvidenceSummariesForPrompt(): String =
    Regex("""targetEvidence\([^)]*\)""")
        .findAll(this)
        .map { match -> match.value }
        .distinct()
        .joinToString(separator = " ")
        .ifBlank { "none" }

private fun String.extractivePromptCompress(
    maxChars: Int,
    protectedPatterns: List<Regex>,
): String {
    if (length <= maxChars) return this
    val protectedSnippets = protectedPatterns
        .flatMap { pattern -> pattern.findAll(this).map { match -> match.value } }
        .distinct()
    val protectedText = protectedSnippets.joinToString(separator = " | ")
        .takeIf { value -> value.isNotBlank() }
    val reserved = protectedText?.length?.plus(12) ?: 0
    val headBudget = (maxChars - reserved).coerceAtLeast(maxChars / 3)
    val head = take(headBudget.coerceAtMost(maxChars)).trimEnd()
    val joined = buildString {
        append(head)
        protectedText?.let { text ->
            append(" ... ")
            append(text)
        }
    }
    return if (joined.length <= maxChars) {
        joined
    } else {
        joined.take(maxChars - 3).trimEnd() + "..."
    }
}

private fun List<String>.prioritizedForObservationPrompt(): List<String> {
    val requiredPrefixes = listOf(
        "This is user-authorized",
        "Output exactly one call",
        "Decide if",
        "When LocalOnly observation evidence",
        "Allowed LocalOnly tools",
        "Do not output web_search",
        "When LocalOnly evidence includes",
        "Use only target values",
        "Never output mode",
        "Prefer a type-tagged target",
        "For an app search request",
        "If only tap=",
        "Do not output ordinary text",
        "For ui_scroll",
        "If the latest action failed",
        "Stop on",
        "User intent preview:",
        "Prior request details:",
        "Previous tool:",
        "Observation status:",
        "App search progress:",
        "Observation diagnostics:",
        "LocalOnly observation evidence:",
    )
    return filter { line ->
        line.isBlank() || requiredPrefixes.any { prefix -> line.startsWith(prefix) }
    }
}

private fun List<String>.joinPrioritizedObservationPrompt(maxChars: Int): String {
    val localEvidenceLine = firstOrNull { line -> line.startsWith("LocalOnly observation evidence:") }
    val diagnosticsLine = firstOrNull { line -> line.startsWith("Observation diagnostics:") }
    val baseLines = filterNot { line ->
        line.startsWith("LocalOnly observation evidence:") ||
            line.startsWith("Observation diagnostics:")
    }.prioritizedBaseObservationLines()
    val localEvidenceBudget = (maxChars * 0.42).toInt().coerceIn(320, 900)
    val diagnosticsBudget = (maxChars * 0.12).toInt().coerceIn(90, 220)
    val baseBudget = (maxChars - localEvidenceBudget - diagnosticsBudget - 2).coerceAtLeast(maxChars / 3)
    val parts = buildList {
        baseLines.joinPromptLinesWithinBudget(baseBudget)
            .takeIf { text -> text.isNotBlank() }
            ?.let(::add)
        diagnosticsLine
            ?.extractivePromptCompress(diagnosticsBudget, diagnosticProtectedPromptPatterns)
            ?.let(::add)
        localEvidenceLine
            ?.extractivePromptCompress(
                localEvidenceBudget,
                localEvidenceLine.targetShortlistTargetValuePatterns() + localEvidenceProtectedPromptPatterns,
            )
            ?.let(::add)
    }
    val joined = parts.joinToString(separator = "\n")
    return if (joined.length <= maxChars) joined else joined.take(maxChars - 3).trimEnd() + "..."
}

private fun List<String>.joinPromptLinesWithinBudget(maxChars: Int): String {
    val accepted = mutableListOf<String>()
    var used = 0
    for (line in filter { value -> value.isNotBlank() }) {
        val candidate = if (line.length > 180) {
            line.extractivePromptCompress(
                maxChars = 180,
                protectedPatterns = listOf(Regex("""targetShortlist\([^)]*\)""")),
            )
        } else {
            line
        }
        val nextUsed = used + candidate.length + if (accepted.isEmpty()) 0 else 1
        if (nextUsed > maxChars) continue
        accepted += candidate
        used = nextUsed
    }
    return accepted.joinToString(separator = "\n")
}

private fun List<String>.prioritizedBaseObservationLines(): List<String> {
    val priorityPrefixes = listOf(
        "This is user-authorized",
        "Output exactly one call",
        "User intent preview:",
        "Previous tool:",
        "Observation status:",
        "App search progress:",
        "Prior request details:",
        "Use only target values",
        "For an app search request",
        "When LocalOnly observation evidence",
        "Allowed LocalOnly tools",
        "Do not output web_search",
        "When LocalOnly evidence includes",
        "Never output mode",
        "Stop on",
        "For ui_scroll",
        "Prefer a type-tagged target",
        "Do not output ordinary text",
        "If only tap=",
        "If the latest action failed",
        "Decide if",
    )
    return withIndex()
        .sortedWith(
            compareBy<IndexedValue<String>> { indexed ->
                priorityPrefixes.indexOfFirst { prefix -> indexed.value.startsWith(prefix) }
                    .takeIf { index -> index >= 0 }
                    ?: Int.MAX_VALUE
            }.thenBy { indexed -> indexed.index },
        )
        .map { indexed -> indexed.value }
}

private fun List<ToolRequest>.priorRequestDetailsPrompt(): String =
    takeLast(MAX_PRIOR_REQUEST_DETAILS)
        .map { request -> request.toPriorRequestDetailPrompt() }
        .joinToString(separator = " -> ")
        .ifBlank { "none" }
        .safeObservationPromptText(maxLength = MAX_PRIOR_REQUEST_DETAILS_CHARS)

private fun ToolRequest.toPriorRequestDetailPrompt(): String {
    val details = buildList {
        arguments["target"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.safeObservationPromptText(maxLength = 96)
            ?.let { value -> add("target=$value") }
        arguments["direction"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.safeObservationPromptText(maxLength = 32)
            ?.let { value -> add("direction=$value") }
        arguments["captureMode"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.safeObservationPromptText(maxLength = 48)
            ?.let { value -> add("captureMode=$value") }
        arguments["text"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { value -> add("textChars=${value.length}") }
        arguments["timeoutMillis"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.safeObservationPromptText(maxLength = 16)
            ?.let { value -> add("timeoutMillis=$value") }
    }.joinToString(separator = ",")
    return if (details.isBlank()) toolName else "$toolName{$details}"
}

private fun ToolResult.observationDiagnosticsPrompt(): String {
    val diagnostics = buildList {
        if (status == ToolStatus.Failed) {
            add("resultRetryable=$retryable")
        }
        error?.code?.let { code -> add("errorCode=${code.name}") }
        localObservationDiagnosticKeys.forEach { key ->
            data[key]
                ?.takeIf { value -> value.isNotBlank() }
                ?.safeObservationPromptText(maxLength = 160)
                ?.let { value -> add("$key=$value") }
        }
    }
    return diagnostics
        .joinToString(separator = "; ")
        .ifBlank { "none" }
        .safeObservationPromptText(maxLength = MAX_LOCAL_DIAGNOSTICS_CHARS)
}

private fun ToolResult.localObservationEvidencePrompt(intentPreview: String): String {
    val intentTargetKind = intentPreview.promptResolverTargetKind()
    val observationSections = localObservationJsonKeys.mapNotNull { key ->
        data[key]
            ?.takeIf { value -> value.isNotBlank() }
            ?.toScreenObservationPromptSection(key, intentTargetKind)
    }
    val sections = buildList {
        observationSections
            .map { section -> section.text }
            .forEach(::add)
        if (observationSections.none { section -> section.containsOcrSource }) {
            data["ocrBlocksJson"]
                ?.takeIf { value -> value.isNotBlank() }
                ?.toOcrBlocksPromptSection(includeExecutableTargets = executableStandaloneOcrBlocksJson() != null)
                ?.let(::add)
        }
        data["ocrText"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.safeObservationPromptText(maxLength = 360)
            ?.let { text -> add("ocrText=$text") }
        data["screenText"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.safeObservationPromptText(maxLength = 360)
            ?.let { text -> add("screenText=$text") }
        data["screenObservationDiffSummary"]
            ?.takeIf { value -> value.isNotBlank() }
            ?.safeObservationPromptText(maxLength = 520)
            ?.let { text -> add("screenObservationDiffSummary=$text") }
    }
    return sections.joinToString(separator = " | ")
        .ifBlank { "none" }
        .safeObservationPromptText(maxLength = MAX_LOCAL_EVIDENCE_CHARS)
    }

private fun AgentObservationReplanContext.localOnlyObservationAllowedToolsPrompt(toolRegistry: ToolRegistry): String =
    if (!observedResult.hasLocalOnlyObservationEvidence(toolRegistry.specFor(previousRequest.toolName))) {
        "not_applicable"
    } else {
        MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES
            .filter { toolName -> toolRegistry.specFor(toolName)?.capability == ToolCapability.DeviceControl }
            .joinToString(separator = ",")
            .ifBlank { "none" }
    }

private fun String.toScreenObservationPromptSection(
    key: String,
    intentTargetKind: UiTargetKind?,
): LocalObservationPromptSection? =
    runCatching {
        val json = JSONObject(this)
        val elements = json.optJSONArray("elements") ?: JSONArray()
        val selectedElements = elements.selectedObservationElementsForPrompt()
        val elementSummaries = selectedElements
            .map { element -> element.toObservationElementPromptText() }
        val fusedTargetCandidates = elements.ocrGroundedAccessibilityTargetCandidates()
        val resolverTargetRanks = screenObservationFromJsonStringOrNull(this)
            .resolverTargetRanks(intentTargetKind)
        val targetCandidates = (
            fusedTargetCandidates +
                selectedElements.mapNotNull { element -> element.toTargetCandidatePrompt() }
            )
            .distinctBy { candidate -> candidate.promptText }
            .rankedByResolver(resolverTargetRanks)
            .take(MAX_LOCAL_TARGET_CANDIDATES)
        val targetSummaries = targetCandidates
            .map { candidate -> candidate.promptText }
        val targetShortlist = targetCandidates.targetShortlistPromptText()
        val targetEvidence = targetCandidates.targetEvidencePromptText()
        val text = buildString {
            append(key)
            append("(id=")
            append(json.optString("observationId", "unknown"))
            append(",package=")
            append(json.optNullableString("packageName") ?: "unknown")
            append(",sources=")
            append(json.optJSONArray("sources")?.joinCompact() ?: "none")
            append(",sourceCounts=")
            append(json.optJSONObject("sourceCounts")?.joinCompact() ?: "none")
            append(",elements=")
            append(json.optInt("elementCount", elements.length()))
            append(",truncated=")
            append(json.optBoolean("truncated", false))
            append(")")
            targetShortlist?.let { summary ->
                append(" targetShortlist(")
                append(summary)
                append(")")
            }
            targetEvidence?.let { summary ->
                append(" targetEvidence(")
                append(summary)
                append(")")
            }
            if (elementSummaries.isNotEmpty()) {
                append(" elements=[")
                append(elementSummaries.joinToString(separator = "; "))
                append("]")
            }
            if (targetSummaries.isNotEmpty()) {
                append(" targets=[")
                append(targetSummaries.joinToString(separator = "; "))
                append("]")
            }
        }
        LocalObservationPromptSection(
            text = text,
            containsOcrSource = json.containsOcrObservation(elements),
        )
    }.getOrNull()

private fun JSONArray.selectedObservationElementsForPrompt(): List<JSONObject> {
    val indexed = indexedObjects()
    val selectedIndices = linkedSetOf<Int>()

    fun includeMatching(limit: Int, predicate: (JSONObject) -> Boolean) {
        indexed
            .asSequence()
            .filter { (_, element) -> predicate(element) }
            .take(limit)
            .forEach { (index, _) -> selectedIndices += index }
    }

    fun includeOcrCandidates(limit: Int) {
        indexed
            .asSequence()
            .filter { (_, element) -> element.optString("source") == "ocr" }
            .filter { (_, element) -> element.optString("text").normalizedLookupKey().isNotBlank() }
            .filter { (_, element) -> element.optJSONObject("bounds") != null }
            .sortedWith(
                compareBy<Pair<Int, JSONObject>> { (_, element) -> element.ocrPromptRolePriority() }
                    .thenBy { (index, _) -> index },
            )
            .distinctBy { (_, element) -> element.optString("text").normalizedLookupKey() }
            .take(limit)
            .forEach { (index, _) -> selectedIndices += index }
    }

    includeMatching(limit = 6) { element ->
        element.optString("source") == "accessibility" && element.hasActionableModeForPrompt()
    }
    includeOcrCandidates(limit = 4)
    includeMatching(limit = 6) { element -> element.optString("source") == "accessibility" }
    indexed.forEach { (index, _) ->
        if (selectedIndices.size < MAX_LOCAL_OBSERVATION_ELEMENTS) {
            selectedIndices += index
        }
    }
    return selectedIndices
        .take(MAX_LOCAL_OBSERVATION_ELEMENTS)
        .mapNotNull(::optJSONObject)
}

private fun JSONObject.ocrPromptRolePriority(): Int =
    when (optString("role")) {
        "ocr_element" -> 0
        "ocr_line" -> 1
        "ocr_block" -> 2
        else -> 3
    }

private fun JSONObject.hasActionableModeForPrompt(): Boolean {
    val clickability = optJSONObject("clickability") ?: return false
    if (clickability.optBoolean("enabled", true) == false) return false
    return clickability.optBoolean("clickable", false) ||
        clickability.optBoolean("editable", false) ||
        clickability.optBoolean("scrollable", false)
}

private fun JSONObject.hasScrollableModeForPrompt(): Boolean {
    val clickability = optJSONObject("clickability") ?: return false
    if (clickability.optBoolean("enabled", true) == false) return false
    return clickability.optBoolean("scrollable", false)
}

private fun JSONObject.toObservationElementPromptText(): String {
    val clickability = optJSONObject("clickability")
    val flags = buildList {
        if (clickability?.optBoolean("clickable", false) == true) add("clickable")
        if (clickability?.optBoolean("editable", false) == true) add("editable")
        if (clickability?.optBoolean("scrollable", false) == true) add("scrollable")
        if (clickability?.optBoolean("enabled", true) == false) add("disabled")
    }.joinToString(separator = "+").ifBlank { "static" }
    val text = optString("text")
        .takeIf { value -> value.isNotBlank() }
        ?.safeObservationPromptText(maxLength = 72)
        ?: "no_text"
    val bounds = optJSONObject("bounds")?.boundsPromptText()
    return buildString {
        append(optString("id", "unknown"))
        append("{")
        append(optString("source", "unknown"))
        append("/")
        append(optString("role", "unknown"))
        append(",text=")
        append(text)
        bounds?.let { value ->
            append(",bounds=")
            append(value)
        }
        append(",")
        append(flags)
        append(",confidence=")
        append(optDouble("confidence", 0.0).formatPromptScore())
        append("}")
    }
}

private fun JSONObject.toTargetCandidatePrompt(): LocalTargetCandidatePrompt? {
    val text = optString("text")
        .takeIf { value -> value.isNotBlank() }
        ?.safeObservationPromptText(maxLength = 72)
        ?: return null
    val clickability = optJSONObject("clickability")
    if (clickability?.optBoolean("enabled", true) == false) return null
    val source = optString("source", "unknown")
    val role = optString("role", "unknown")
    if (source == "ocr" && optJSONObject("bounds") == null) return null
    val modeTags = buildList {
        if (clickability?.optBoolean("editable", false) == true) add("type")
        if (clickability?.optBoolean("clickable", false) == true) add("tap")
        if (clickability?.optBoolean("scrollable", false) == true) add("scroll")
        if (source == "ocr") add("ocrFallback")
    }
    if (modeTags.isEmpty()) return null
    val targetValue = optString("id")
        .takeIf { value -> value.isNotBlank() }
        ?.safeObservationPromptText(maxLength = 72)
        ?: text
    val bounds = optJSONObject("bounds")?.boundsPromptText()
    val promptText = buildString {
        append(text)
        append("{target=")
        append(targetValue)
        append(",modeTags=")
        append(modeTags.joinToString(separator = "+"))
        append(",source=")
        append(source)
        append(",role=")
        append(role)
        bounds?.let { value ->
            append(",bounds=")
            append(value)
        }
        append(",confidence=")
        append(optDouble("confidence", 0.0).formatPromptScore())
        append("}")
    }
    return LocalTargetCandidatePrompt(
        label = text,
        targetValue = targetValue,
        modeTags = modeTags,
        role = role,
        promptText = promptText,
    )
}

private fun JSONArray.ocrGroundedAccessibilityTargetCandidates(): List<LocalTargetCandidatePrompt> {
    val allElements = objects()
    val ocrElements = allElements
        .filter { element ->
            element.optString("source") == "ocr" &&
                element.optString("text").isNotBlank() &&
                element.optJSONObject("bounds") != null
        }
    if (ocrElements.isEmpty()) return emptyList()
    return allElements
        .asSequence()
        .filter { element ->
            element.optString("source") == "accessibility" &&
                element.optString("text").isBlank() &&
                element.hasActionableModeForPrompt() &&
                element.optJSONObject("bounds") != null
        }
        .mapNotNull { element -> element.toOcrGroundedAccessibilityTargetCandidate(ocrElements) }
        .distinctBy { candidate -> candidate.targetValue }
        .take(MAX_LOCAL_TARGET_CANDIDATES)
        .toList()
}

private fun JSONObject.toOcrGroundedAccessibilityTargetCandidate(
    ocrElements: List<JSONObject>,
): LocalTargetCandidatePrompt? {
    val bounds = optJSONObject("bounds") ?: return null
    val ocrText = ocrElements
        .asSequence()
        .filter { element -> bounds.containsOcrGroundingElementBounds(element.optJSONObject("bounds")) }
        .sortedBy { element -> element.optJSONObject("bounds")?.optInt("top", Int.MAX_VALUE) ?: Int.MAX_VALUE }
        .map { element -> element.optString("text").trim() }
        .filter { text -> text.isNotBlank() }
        .distinctBy { text -> text.normalizedLookupKey() }
        .take(2)
        .joinToString(" ")
        .takeIf { text -> text.isNotBlank() }
        ?.safeObservationPromptText(maxLength = 72)
        ?: return null
    val targetValue = optString("id")
        .takeIf { value -> value.isNotBlank() }
        ?.safeObservationPromptText(maxLength = 72)
        ?: return null
    val clickability = optJSONObject("clickability")
    val role = optString("role", "unknown")
    val modeTags = buildList {
        if (clickability?.optBoolean("editable", false) == true) add("type")
        if (clickability?.optBoolean("clickable", false) == true) add("tap")
        if (clickability?.optBoolean("scrollable", false) == true) add("scroll")
    }
    if (modeTags.isEmpty()) return null
    val promptText = buildString {
        append(ocrText)
        append("{target=")
        append(targetValue)
        append(",modeTags=")
        append(modeTags.joinToString(separator = "+"))
        append(",source=accessibility+ocr,role=")
        append(role)
        append(",bounds=")
        append(bounds.boundsPromptText())
        append(",confidence=")
        append(optDouble("confidence", 1.0).formatPromptScore())
        append("}")
    }
    return LocalTargetCandidatePrompt(
        label = ocrText,
        targetValue = targetValue,
        modeTags = modeTags,
        role = role,
        promptText = promptText,
    )
}

private fun List<LocalTargetCandidatePrompt>.targetShortlistPromptText(): String? {
    val supportedModeTags = listOf("type", "tap", "scroll", "ocrFallback")
    return supportedModeTags
        .mapNotNull { mode ->
            val targetValues = asSequence()
                .filter { candidate -> mode in candidate.modeTags }
                .map { candidate -> candidate.targetValue }
                .distinct()
                .take(MAX_LOCAL_TARGET_LABELS_PER_MODE)
                .toList()
            targetValues
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "|")
                ?.let { joinedTargets -> "$mode=$joinedTargets" }
        }
        .joinToString(separator = ",")
        .takeIf { value -> value.isNotBlank() }
}

private fun List<LocalTargetCandidatePrompt>.targetEvidencePromptText(): String? =
    take(MAX_LOCAL_TARGET_LABELS_PER_MODE)
        .joinToString(separator = "; ") { candidate ->
            buildString {
                append("id=")
                append(candidate.targetValue.safeObservationPromptText(maxLength = 96))
                append(",text=")
                append(candidate.label.safeObservationPromptText(maxLength = 48))
                append(",mode=")
                append(candidate.modeTags.joinToString(separator = "+"))
                append(",role=")
                append(candidate.role.safeObservationPromptText(maxLength = 32))
            }
        }
        .takeIf { value -> value.isNotBlank() }

private fun List<LocalTargetCandidatePrompt>.rankedByResolver(
    resolverTargetRanks: List<ResolverTargetRank>,
): List<LocalTargetCandidatePrompt> {
    if (resolverTargetRanks.isEmpty()) return this
    val rankByModeAndTarget = mutableMapOf<String, Int>()
    resolverTargetRanks.forEach { rank ->
        val key = "${rank.mode}:${rank.targetValue.normalizedLookupKey()}"
        rankByModeAndTarget[key] = minOf(rankByModeAndTarget[key] ?: Int.MAX_VALUE, rank.rank)
    }
    return withIndex()
        .sortedWith(
            compareBy<IndexedValue<LocalTargetCandidatePrompt>> { indexed ->
                indexed.value.bestResolverRank(rankByModeAndTarget)
            }.thenBy { indexed -> indexed.index },
        )
        .map { indexed -> indexed.value }
}

private fun LocalTargetCandidatePrompt.bestResolverRank(rankByModeAndTarget: Map<String, Int>): Int =
    modeTags.minOfOrNull { mode ->
        rankByModeAndTarget["$mode:${targetValue.normalizedLookupKey()}"]
            ?: rankByModeAndTarget["$mode:${label.normalizedLookupKey()}"]
            ?: Int.MAX_VALUE
    } ?: Int.MAX_VALUE

private fun String?.promptResolverTargetKind(): UiTargetKind? {
    val resolverKind = UiTargetResolver.kindForTarget(this)
    if (resolverKind != null) return resolverKind
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return null
    return if (
        listOf("滚动", "滑动", "下滑", "上滑", "scroll", "swipe").any { marker ->
            normalized.contains(marker.normalizedLookupKey())
        }
    ) {
        UiTargetKind.ScrollContainer
    } else {
        null
    }
}

private fun ScreenObservation?.resolverTargetRanks(intentTargetKind: UiTargetKind?): List<ResolverTargetRank> =
    this?.let { observation ->
        val rankedKinds = defaultResolverPromptTargetKinds.prioritizing(intentTargetKind)
        rankedKinds.flatMapIndexed { kindIndex, kind ->
            UiTargetResolver.explain(observation, kind)
                .rankedCandidates
                .take(MAX_LOCAL_TARGET_LABELS_PER_MODE)
                .flatMapIndexed { candidateIndex, candidate ->
                    candidate.toResolverTargetRanks(
                        kind = kind,
                        rank = (kindIndex * MAX_LOCAL_TARGET_LABELS_PER_MODE) + candidateIndex,
                    )
                }
        }
    }.orEmpty()

private fun List<UiTargetKind>.prioritizing(kind: UiTargetKind?): List<UiTargetKind> =
    if (kind == null || kind !in this) {
        this
    } else {
        listOf(kind) + filterNot { candidate -> candidate == kind }
    }

private fun UiTargetEvidenceCandidate.toResolverTargetRanks(
    kind: UiTargetKind,
    rank: Int,
): List<ResolverTargetRank> {
    val targetValue = nodeId?.takeIf { value -> value.isNotBlank() } ?: label
    val modes = when (kind) {
        UiTargetKind.SearchEntry -> buildList {
            if (editable) add("type")
            if (clickable || !editable) add("tap")
            if (source.schemaValue == "ocr") add("ocrFallback")
        }

        UiTargetKind.EditableField -> buildList {
            add("type")
            if (clickable) add("tap")
        }

        UiTargetKind.SubmitSearch,
        UiTargetKind.FilterEntry -> listOf("tap")

        UiTargetKind.ScrollContainer -> listOf("scroll")
        UiTargetKind.ResultItem -> emptyList()
    }
    return modes
        .distinct()
        .flatMap { mode ->
            listOf(
                ResolverTargetRank(mode = mode, targetValue = targetValue, rank = rank),
                ResolverTargetRank(mode = mode, targetValue = label, rank = rank),
            )
        }
}

private fun String.toOcrBlocksPromptSection(includeExecutableTargets: Boolean): String? =
    runCatching {
        val blocks = JSONArray(this)
        val selectedBlocks = blocks.indexedObjects(limit = MAX_LOCAL_OCR_BLOCKS)
        val summaries = selectedBlocks
            .mapNotNull { (index, block) -> block.toOcrBlockPromptText(index) }
        val targetCandidates = if (includeExecutableTargets) {
            selectedBlocks
                .flatMap { (index, block) -> block.toOcrTargetCandidatePrompts(index) }
                .distinctBy { candidate -> candidate.promptText }
                .take(MAX_LOCAL_TARGET_CANDIDATES)
        } else {
            emptyList()
        }
        buildString {
            append("ocrBlocks(count=")
            append(blocks.length())
            append(")")
            targetCandidates.targetShortlistPromptText()?.let { summary ->
                append(" targetShortlist(")
                append(summary)
                append(")")
            }
            targetCandidates.targetEvidencePromptText()?.let { summary ->
                append(" targetEvidence(")
                append(summary)
                append(")")
            }
            if (summaries.isNotEmpty()) {
                append("=[")
                append(summaries.joinToString(separator = "; "))
                append("]")
            }
            if (targetCandidates.isNotEmpty()) {
                append(" targets=[")
                append(targetCandidates.joinToString(separator = "; ") { candidate -> candidate.promptText })
                append("]")
            }
        }
    }.getOrNull()

private fun JSONObject.toOcrTargetCandidatePrompts(blockIndex: Int): List<LocalTargetCandidatePrompt> =
    toOcrTargetEvidencePrompts(blockIndex).map { evidence -> evidence.toOcrTargetCandidatePrompt() }

private fun JSONObject.toOcrTargetEvidencePrompts(blockIndex: Int): List<OcrTargetEvidencePrompt> {
    val lineEntries = optJSONArray("lines").orEmptyJsonObjects()
    val nestedTargets = lineEntries.flatMap { (lineIndex, line) ->
        val lineId = "ocr:block:$blockIndex:line:$lineIndex"
        val elementTargets = line.optJSONArray("elements").orEmptyJsonObjects().mapNotNull { (elementIndex, element) ->
            element.toOcrTargetEvidencePrompt(
                id = "$lineId:element:$elementIndex",
                role = "ocr_element",
            )
        }
        elementTargets + listOfNotNull(line.toOcrTargetEvidencePrompt(id = lineId, role = "ocr_line"))
    }
    return nestedTargets + listOfNotNull(toOcrTargetEvidencePrompt(id = "ocr:block:$blockIndex", role = "ocr_block"))
}

private inline fun String?.withObservationElements(predicate: (JSONArray) -> Boolean): Boolean =
    withJsonArray({ rawJson -> JSONObject(rawJson).optJSONArray("elements") ?: JSONArray() }, predicate)

private inline fun String?.withOcrBlocks(predicate: (JSONArray) -> Boolean): Boolean =
    withJsonArray(::JSONArray, predicate)

private inline fun String?.withJsonArray(
    crossinline arrayFor: (String) -> JSONArray,
    predicate: (JSONArray) -> Boolean,
): Boolean =
    this?.let { rawJson ->
        runCatching {
            predicate(arrayFor(rawJson))
        }.getOrDefault(false)
    } ?: false

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull(::optJSONObject)

private fun JSONArray.indexedObjects(limit: Int = length()): List<Pair<Int, JSONObject>> =
    (0 until minOf(length(), limit)).mapNotNull { index ->
        optJSONObject(index)?.let { element -> index to element }
    }

private fun JSONArray?.orEmptyJsonObjects(): List<Pair<Int, JSONObject>> =
    this?.indexedObjects().orEmpty()

private fun JSONObject.toOcrTargetEvidencePrompt(
    id: String,
    role: String,
): OcrTargetEvidencePrompt? {
    val text = optString("text").takeIf { value -> value.isNotBlank() } ?: return null
    val bounds = optJSONObject("bounds")?.boundsPromptText() ?: return null
    return OcrTargetEvidencePrompt(
        id = id,
        text = text,
        role = role,
        bounds = bounds,
    )
}

private fun OcrTargetEvidencePrompt.toOcrTargetCandidatePrompt(): LocalTargetCandidatePrompt {
    val text = text.safeObservationPromptText(maxLength = 72)
    val targetValue = id.safeObservationPromptText(maxLength = 96)
    val promptText = buildString {
        append(text)
        append("{target=")
        append(targetValue)
        append(",modeTags=ocrFallback,source=ocr,role=")
        append(role)
        append(",bounds=")
        append(bounds)
        append("}")
    }
    return LocalTargetCandidatePrompt(
        label = text,
        targetValue = targetValue,
        modeTags = listOf("ocrFallback"),
        role = role,
        promptText = promptText,
    )
}

private fun JSONObject.toOcrBlockPromptText(index: Int): String {
    val text = optString("text")
        .safeObservationPromptText(maxLength = 72)
        .ifBlank { "no_text" }
    val bounds = optJSONObject("bounds")?.boundsPromptText()
    return buildString {
        append("block")
        append(index)
        append("{text=")
        append(text)
        bounds?.let { value ->
            append(",bounds=")
            append(value)
        }
        append("}")
    }
}

private fun JSONObject.containsOcrObservation(elements: JSONArray): Boolean =
    optJSONObject("sourceCounts")?.optInt("ocr", 0)?.let { count -> count > 0 } == true ||
        optJSONArray("sources")?.containsString("ocr") == true ||
        (0 until elements.length()).any { index ->
            elements.optJSONObject(index)?.optString("source") == "ocr"
        }

private fun JSONArray.containsString(value: String): Boolean =
    (0 until length()).any { index -> optString(index) == value }

private fun JSONObject.boundsPromptText(): String =
    "[${optInt("left")},${optInt("top")},${optInt("right")},${optInt("bottom")}]"

private fun JSONObject.containsOcrGroundingElementBounds(ocrBounds: JSONObject?): Boolean {
    val container = toScreenBoundsOrNull() ?: return false
    val ocr = ocrBounds?.toScreenBoundsOrNull() ?: return false
    return container.containsOcrGroundingBounds(ocr)
}

private fun JSONObject.toScreenBoundsOrNull(): ScreenBounds? {
    if (!has("left") || !has("top") || !has("right") || !has("bottom")) return null
    return ScreenBounds(
        left = optInt("left"),
        top = optInt("top"),
        right = optInt("right"),
        bottom = optInt("bottom"),
    )
}

private fun JSONArray.joinCompact(): String =
    (0 until length())
        .joinToString(separator = ",") { index -> optString(index) }
        .ifBlank { "none" }

private fun JSONObject.joinCompact(): String =
    keys().asSequence()
        .toList()
        .sorted()
        .joinToString(separator = ",") { key -> "$key=${opt(key)}" }
        .ifBlank { "none" }

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { value -> value.isNotBlank() }

private fun Double.formatPromptScore(): String =
    String.format(java.util.Locale.US, "%.2f", this)

private fun String.safeObservationPromptText(maxLength: Int = 160): String {
    val compact = lineSequence()
        .joinToString(" ") { line -> line.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()
    val redacted = ToolAuditSummaryRedactor.redact(compact)
    return if (redacted.length <= maxLength) redacted else redacted.take(maxLength - 3) + "..."
}

internal fun String.explicitNextActionText(): String? =
    explicitSequentialActionTextAt(1)

internal fun String.explicitSequentialActionTextAt(index: Int): String? {
    if (index < 0) return null
    return explicitSequentialActionSegments().getOrNull(index)
}

internal fun String.requiresLocalModelBeforeSequentialTail(): Boolean =
    requiresLocalModelBeforeSequentialTail(defaultSequentialToolRegistry)

internal fun String.requiresLocalModelBeforeSequentialTail(toolRegistry: ToolRegistry): Boolean =
    toolRegistry.requiresSequentialLocalModelBeforeTail(this)

internal fun String.immediateSequentialActionText(): String? =
    (explicitSequentialActionTextAt(0) ?: trimActionText()).takeIf { it.isNotBlank() }

private fun String.explicitSequentialActionSegments(): List<String> {
    val matches = sequenceConnector.findAll(this).toList()
    if (matches.isEmpty()) return emptyList()
    val starts = listOf(0) + matches.map { match -> match.range.last + 1 }
    val ends = matches.map { match -> match.range.first } + length
    return starts.zip(ends)
        .mapNotNull { (start, end) ->
            substring(start.coerceAtMost(length), end.coerceAtMost(length)).trimActionText()
                .takeIf { it.isNotBlank() }
        }
}

private fun String.trimActionText(): String {
    val trimmed = trim(' ', '\t', '\n', '\r', '，', '。', ',', '.', ';', '；', ':', '：')
    return trimmed
        .replace(Regex("""^(?:先\s*|first,?\s+)""", RegexOption.IGNORE_CASE), "")
        .trim(' ', '\t', '\n', '\r', '，', '。', ',', '.', ';', '；', ':', '：')
}

private val sequenceConnector = Regex(
    pattern = """(?i)(?:\b(?:and\s+then|then)\b|然后|接着|随后|之后|再)""",
)
