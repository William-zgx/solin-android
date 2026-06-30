package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionIntentConfidence
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.extractUiTargetArgumentValue
import com.bytedance.zgx.solin.audit.ToolAuditSummaryRedactor
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
import com.bytedance.zgx.solin.tool.ToolRegistry
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
private const val MAX_PRIOR_REQUEST_DETAILS = 6
private const val MAX_PRIOR_REQUEST_DETAILS_CHARS = 900

private val localOnlyObservationReplanAllowedTools = setOf(
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

    override fun planNext(context: AgentObservationReplanContext): AgentObservationReplan? {
        if (!context.observedResult.isModelReplannableObservation()) return null
        if (modelReplanLimit == 0) return null
        if (context.modelObservationReplanCount() >= modelReplanLimit) return null
        val actionModelPath = actionModelPathProvider()?.takeIf { path -> path.isNotBlank() } ?: return null
        val planningResult = actionPlanningRuntime.plan(
            input = context.observationModelPrompt(toolRegistry),
            actionModelPath = actionModelPath,
        )
        if (!planningResult.usedModel) return null
        if (planningResult.plan.kind != ActionPlanKind.Draft) return null
        val draft = planningResult.plan.draft?.normalizedUiTargetDraft() ?: return null
        if (context.shouldRejectNonLocalObservationTool(draft, toolRegistry)) return null
        if (draft.hasMissingRequiredUiTarget()) return null
        if (context.shouldRejectDangerousObservationAction(draft)) return null
        if (context.shouldRejectTextOnlyUiControl(draft)) return null
        if (context.shouldRejectUnsupportedSubmitSearch(draft)) return null
        if (context.shouldRejectUnsupportedTargetlessTyping(draft)) return null
        if (context.shouldRejectUnsupportedObservedTarget(draft)) return null
        if (context.shouldRejectUnsupportedRepeatTarget(draft)) return null
        return AgentObservationReplan(
            request = ToolRequest(
                toolName = draft.functionName,
                arguments = draft.parameters,
                reason = MODEL_OBSERVATION_REPLAN_REQUEST_REASON,
            ),
            draft = draft,
            plannedByModel = true,
            fallbackReason = MODEL_OBSERVATION_REPLAN_FALLBACK_REASON,
        )
    }
}

private fun ActionDraft.normalizedUiTargetDraft(): ActionDraft {
    if (!functionName.acceptsGuardedUiTarget()) return this
    val rawTarget = parameters["target"] ?: return this
    val normalizedTarget = rawTarget.extractUiTargetArgumentValue(toolName = functionName)
    if (normalizedTarget == rawTarget) return this
    return copy(parameters = parameters + ("target" to normalizedTarget))
}

private fun ActionDraft.hasMissingRequiredUiTarget(): Boolean =
    functionName == MobileActionFunctions.UI_TAP &&
        parameters["target"].normalizedLookupKey().isBlank()

private fun AgentObservationReplanContext.shouldRejectNonLocalObservationTool(
    draft: ActionDraft,
    toolRegistry: ToolRegistry,
): Boolean {
    if (!observedResult.hasLocalOnlyObservationEvidence()) return false
    if (draft.functionName !in localOnlyObservationReplanAllowedTools) return true
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
    return !observedResult.hasCurrentTargetEvidence(nextTarget)
}

private fun AgentObservationReplanContext.shouldRejectTextOnlyUiControl(draft: ActionDraft): Boolean {
    if (!draft.functionName.isAutonomousUiControlAction()) return false
    if (observedResult.hasCurrentTargetEvidenceSource()) return false
    return observedResult.hasReadOnlyLocalScreenTextEvidence()
}

private fun AgentObservationReplanContext.shouldRejectDangerousObservationAction(draft: ActionDraft): Boolean {
    if (!draft.functionName.isAutonomousUiControlAction()) return false
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

private fun String?.observationHasDangerousActionEvidence(): Boolean =
    this?.let { rawJson ->
        runCatching {
            val elements = JSONObject(rawJson).optJSONArray("elements") ?: JSONArray()
            (0 until elements.length()).any { index ->
                elements.optJSONObject(index)?.isDangerousActionEvidence() == true
            }
        }.getOrDefault(false)
    } ?: false

private fun JSONObject.isDangerousActionEvidence(): Boolean {
    val text = optString("text")
    val source = optString("source")
    return if (source == "ocr") {
        text.hasOcrDangerousActionText()
    } else {
        text.hasDangerousActionText() && hasActionableModeForPrompt()
    }
}

private fun String?.ocrBlocksHaveDangerousActionEvidence(): Boolean =
    this?.let { rawJson ->
        runCatching {
            val blocks = JSONArray(rawJson)
            (0 until blocks.length()).any { index ->
                blocks.optJSONObject(index)
                    ?.optString("text")
                    .hasOcrDangerousActionText()
            }
        }.getOrDefault(false)
    } ?: false

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
    return !observedResult.hasEvidenceSupportingRepeatTarget(nextTarget)
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

private fun ToolResult.hasEvidenceSupportingRepeatTarget(normalizedTarget: String): Boolean {
    if (data["screenObservationDiffSummary"].hasPositiveScreenChangeSignal()) return true
    return hasCurrentTargetEvidence(normalizedTarget)
}

private fun ToolResult.hasLocalOnlyObservationEvidence(): Boolean =
    data["privacy"] == MessagePrivacy.LocalOnly.name ||
        data["requiresLocalModel"] == true.toString() ||
        data["ocrText"]?.isNotBlank() == true ||
        data["ocrBlocksJson"]?.isNotBlank() == true ||
        data["screenText"]?.isNotBlank() == true ||
        localObservationJsonKeys.any { key -> data[key].isLocalOnlyObservationJson() }

private fun String?.isLocalOnlyObservationJson(): Boolean =
    this?.let { rawJson ->
        runCatching {
            val json = JSONObject(rawJson)
            json.optString("privacyLevel") == MessagePrivacy.LocalOnly.name
        }.getOrDefault(false)
    } ?: false

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
    this?.let { rawJson ->
        runCatching {
            val elements = JSONObject(rawJson).optJSONArray("elements") ?: JSONArray()
            elements.hasTargetEvidence(normalizedTarget)
        }.getOrDefault(false)
    } ?: false

private fun String?.observationHasScrollableEvidence(): Boolean =
    this?.let { rawJson ->
        runCatching {
            val elements = JSONObject(rawJson).optJSONArray("elements") ?: JSONArray()
            (0 until elements.length()).any { index ->
                elements.optJSONObject(index)?.hasScrollableModeForPrompt() == true
            }
        }.getOrDefault(false)
    } ?: false

private fun String?.observationHasSearchSubmitEvidence(): Boolean =
    this?.let { rawJson ->
        runCatching {
            val elements = JSONObject(rawJson).optJSONArray("elements") ?: JSONArray()
            val ocrTexts = mutableListOf<String>()
            val hasStructuredSearchSubmitEvidence = (0 until elements.length()).any { index ->
                val element = elements.optJSONObject(index) ?: return@any false
                if (element.optString("source") == "ocr") {
                    ocrTexts += element.optString("text")
                    false
                } else {
                    element.isSearchSubmitEvidence()
                }
            }
            hasStructuredSearchSubmitEvidence || ocrTexts.hasOcrSearchSubmitEvidence()
        }.getOrDefault(false)
    } ?: false

private fun String?.observationHasTargetlessTypingEvidence(): Boolean =
    this?.let { rawJson ->
        runCatching {
            val elements = JSONObject(rawJson).optJSONArray("elements") ?: JSONArray()
            val ocrTexts = mutableListOf<String>()
            val hasStructuredTypingEvidence = (0 until elements.length()).any { index ->
                val element = elements.optJSONObject(index) ?: return@any false
                if (element.optString("source") == "ocr") {
                    ocrTexts += element.optString("text")
                    false
                } else {
                    element.isSearchTypingEvidence()
                }
            }
            hasStructuredTypingEvidence || ocrTexts.any { text -> text.hasStrongSearchContextText() }
        }.getOrDefault(false)
    } ?: false

private fun String?.ocrBlocksHaveSearchSubmitEvidence(): Boolean =
    this?.let { rawJson ->
        runCatching {
            val blocks = JSONArray(rawJson)
            val texts = (0 until blocks.length()).mapNotNull { index ->
                blocks.optJSONObject(index)?.optString("text")
            }
            texts.hasOcrSearchSubmitEvidence()
        }.getOrDefault(false)
    } ?: false

private fun String?.ocrBlocksHaveTargetlessTypingEvidence(): Boolean =
    this?.let { rawJson ->
        runCatching {
            val blocks = JSONArray(rawJson)
            (0 until blocks.length()).any { index ->
                blocks.optJSONObject(index)
                    ?.optString("text")
                    .hasStrongSearchContextText()
            }
        }.getOrDefault(false)
    } ?: false

private fun List<String>.hasOcrSearchSubmitEvidence(): Boolean =
    any { text -> text.hasStrongSearchContextText() } &&
        any { text -> text.hasStandaloneSearchSubmitText() }

private fun JSONArray.hasTargetEvidence(normalizedTarget: String): Boolean {
    val elements = (0 until length()).mapNotNull(::optJSONObject)
    if (elements.any { element -> element.isTargetIdEvidence(normalizedTarget) }) return true
    if (elements.any { element -> element.isAccessibilityTextTargetEvidence(normalizedTarget) }) return true
    return elements
        .filter { element -> element.optString("source") == "ocr" }
        .count { element -> element.optString("text").matchesOcrTargetText(normalizedTarget) } == 1
}

private fun JSONObject.isTargetIdEvidence(normalizedTarget: String): Boolean {
    val source = optString("source")
    val idMatches = optString("id").normalizedLookupKey() == normalizedTarget
    if (!idMatches) return false
    return source == "ocr" || hasActionableModeForPrompt()
}

private fun JSONObject.isAccessibilityTextTargetEvidence(normalizedTarget: String): Boolean =
    optString("source") != "ocr" &&
        optString("text").normalizedLookupKey() == normalizedTarget &&
        hasActionableModeForPrompt()

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
    this?.let { rawJson ->
        runCatching {
            val blocks = JSONArray(rawJson)
            val evidences = (0 until blocks.length()).flatMap { index ->
                blocks.optJSONObject(index)?.toOcrTargetEvidencePrompts(index).orEmpty()
            }
            evidences.any { evidence -> evidence.id.normalizedLookupKey() == normalizedTarget } ||
                evidences.count { evidence -> evidence.text.matchesOcrTargetText(normalizedTarget) } == 1
        }.getOrDefault(false)
    } ?: false

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

private fun AgentObservationReplanContext.observationModelPrompt(toolRegistry: ToolRegistry): String {
    val privateOutputKeys = toolRegistry.privateOutputKeysFor(previousRequest.toolName)
    val publicDataKeys = observedResult.data.keys
        .filterNot { key -> key in privateOutputKeys }
        .sorted()
        .joinToString()
        .ifBlank { "none" }
    val omittedPrivateKeys = privateOutputKeys
        .filter { key -> key in observedResult.data }
        .sorted()
        .joinToString()
        .ifBlank { "none" }
    val priorTools = priorRequests
        .joinToString(separator = " -> ") { request -> request.toolName }
        .ifBlank { "none" }
    val priorRequestDetails = priorRequests.priorRequestDetailsPrompt()
    val previousArgumentKeys = previousRequest.arguments.keys
        .sorted()
        .joinToString()
        .ifBlank { "none" }
    val intentPreview = (nextActionInput?.immediateSequentialActionText() ?: run.input)
        .safeObservationPromptText()
    val observationSummary = observedResult.summary.safeObservationPromptText()
    val observationDiagnostics = observedResult.observationDiagnosticsPrompt()
    val localObservationEvidence = observedResult.localObservationEvidencePrompt(intentPreview)
    val localOnlyAllowedTools = observedResult.localOnlyObservationAllowedToolsPrompt(toolRegistry)
    return """
        Decide whether the user's request needs exactly one more mobile tool after the latest observation.
        Output a tool call only when the next action is clearly required; otherwise output ordinary text with no call.
        Do not repeat a tool that has already satisfied the request.
        LocalOnly observation evidence is available only to this local action model; do not treat it as remote-sendable content.
        When LocalOnly observation evidence is present, output only these local device-control tools: $localOnlyAllowedTools.
        Do not output web_search, external send/share, contacts/files/clipboard, background work, or any other non-listed tool from LocalOnly observation evidence.
        When LocalOnly evidence includes targets=[...], copy only the candidate target=... value into the tool target.
        Use targetShortlist(...) as the exact target string shortlist for tools that accept target.
        Candidate mode tags describe fit only; never output mode, bounds, confidence, or other candidate metadata as tool arguments.
        Prefer a type-tagged target for ui_type_text, a tap-tagged target for ui_tap, and a scroll-tagged target for ui_scroll.
        For ui_scroll, use a scroll-tagged target when one is available; if no scroll candidate or scrollable evidence exists, stop instead of blind scrolling.
        Use an ocrFallback target only when no Accessibility target fits.
        If the latest observation shows a failed action, do not repeat the same prior target unless new evidence supports it.
        If current LocalOnly evidence shows payment, sending, deletion, publishing, ordering, purchase, transfer, or authorization controls, stop instead of planning another UI action.

        User intent preview: $intentPreview
        Prior tools: $priorTools
        Prior request details: $priorRequestDetails
        Previous tool: ${previousRequest.toolName}
        Previous argument keys: $previousArgumentKeys
        Observation status: ${observedResult.status}
        Observation summary: $observationSummary
        Observation diagnostics: $observationDiagnostics
        Observation public data keys: $publicDataKeys
        Observation private data keys omitted: $omittedPrivateKeys
        LocalOnly observation evidence: $localObservationEvidence
        Completed segment count: $completedSegmentCount
    """.trimIndent()
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

private fun ToolResult.localOnlyObservationAllowedToolsPrompt(toolRegistry: ToolRegistry): String =
    if (!hasLocalOnlyObservationEvidence()) {
        "not_applicable"
    } else {
        localOnlyObservationReplanAllowedTools
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
    val indexed = (0 until length())
        .mapNotNull { index -> optJSONObject(index)?.let { element -> index to element } }
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
    if (source == "ocr" && optJSONObject("bounds") == null) return null
    val modeTags = buildList {
        if (clickability?.optBoolean("editable", false) == true) add("type")
        if (clickability?.optBoolean("clickable", false) == true) add("tap")
        if (clickability?.optBoolean("scrollable", false) == true) add("scroll")
        if (source == "ocr") add("ocrFallback")
    }
    if (modeTags.isEmpty()) return null
    val targetValue = if (source == "ocr") {
        optString("id")
            .takeIf { value -> value.isNotBlank() }
            ?.safeObservationPromptText(maxLength = 72)
            ?: text
    } else {
        optString("id")
            .takeIf { value -> value.isNotBlank() }
            ?.safeObservationPromptText(maxLength = 72)
            ?: text
    }
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
        append(optString("role", "unknown"))
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
        promptText = promptText,
    )
}

private fun JSONArray.ocrGroundedAccessibilityTargetCandidates(): List<LocalTargetCandidatePrompt> {
    val allElements = (0 until length()).mapNotNull(::optJSONObject)
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
        append(optString("role", "unknown"))
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
        val summaries = (0 until minOf(blocks.length(), MAX_LOCAL_OCR_BLOCKS))
            .mapNotNull { index -> blocks.optJSONObject(index)?.toOcrBlockPromptText(index) }
        val targetCandidates = if (includeExecutableTargets) {
            (0 until minOf(blocks.length(), MAX_LOCAL_OCR_BLOCKS))
                .flatMap { index -> blocks.optJSONObject(index)?.toOcrTargetCandidatePrompts(index).orEmpty() }
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

private fun JSONArray?.orEmptyJsonObjects(): List<Pair<Int, JSONObject>> =
    this?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { element -> index to element }
        }
    }.orEmpty()

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
