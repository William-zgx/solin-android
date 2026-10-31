package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.extractUiTargetArgumentValue
import com.bytedance.zgx.solin.device.hasDangerousActionText
import com.bytedance.zgx.solin.device.hasOcrDangerousActionText
import com.bytedance.zgx.solin.device.hasStrongDangerousActionText
import com.bytedance.zgx.solin.device.normalizedLookupKey
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

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

internal fun ActionDraft.normalizedUiTargetDraft(): ActionDraft {
    if (!functionName.acceptsGuardedUiTarget()) return this
    val rawTarget = parameters["target"] ?: return this
    val normalizedTarget = rawTarget.extractUiTargetArgumentValue(toolName = functionName)
    if (normalizedTarget == rawTarget) return this
    return copy(parameters = parameters + ("target" to normalizedTarget))
}

internal fun ActionDraft.tapFirstForSearchTypingTarget(
    context: AgentObservationReplanContext,
    parseCache: ObservationJsonParseCache,
): ActionDraft {
    if (functionName != MobileActionFunctions.UI_TYPE_TEXT) return this
    val normalizedTarget = parameters["target"].normalizedLookupKey()
        .takeIf { value -> value.isNotBlank() }
        ?: return this
    if (context.observedResult.hasCurrentTargetEvidence(
            normalizedTarget,
            MobileActionFunctions.UI_TYPE_TEXT,
            parseCache,
        )
    ) {
        return this
    }
    if (!context.observedResult.hasCurrentSearchTapTargetEvidence(normalizedTarget, parseCache)) return this
    val tapParameters = parameters
        .filterKeys { key -> key != "text" && key != "verifySearchQuery" && key != "expectedAppName" }
    return copy(
        functionName = MobileActionFunctions.UI_TAP,
        title = "点击搜索入口",
        summary = "先点击搜索入口以打开输入框。",
        parameters = tapParameters,
    )
}

internal fun ActionDraft.hasMissingRequiredUiTarget(): Boolean =
    functionName == MobileActionFunctions.UI_TAP &&
        parameters["target"].normalizedLookupKey().isBlank()

internal fun AgentObservationReplanContext.shouldRejectNonLocalObservationTool(
    draft: ActionDraft,
    toolRegistry: ToolRegistry,
): Boolean {
    if (!observedResult.hasLocalOnlyObservationEvidence(toolRegistry.specFor(previousRequest.toolName))) return false
    if (draft.functionName !in MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES) return true
    return toolRegistry.specFor(draft.functionName)?.capability != ToolCapability.DeviceControl
}

internal fun AgentObservationReplanContext.shouldRejectUnsupportedSubmitSearch(
    draft: ActionDraft,
    parseCache: ObservationJsonParseCache,
): Boolean {
    if (draft.functionName != MobileActionFunctions.UI_SUBMIT_SEARCH) return false
    if (!observedResult.hasCurrentTargetEvidenceSource()) return false
    return !observedResult.hasCurrentSearchSubmitEvidence(parseCache)
}

internal fun AgentObservationReplanContext.shouldRejectUnsupportedTargetlessTyping(
    draft: ActionDraft,
    parseCache: ObservationJsonParseCache,
): Boolean {
    if (draft.functionName != MobileActionFunctions.UI_TYPE_TEXT) return false
    if (draft.parameters["target"].normalizedLookupKey().isNotBlank()) return false
    if (!observedResult.hasCurrentTargetEvidenceSource()) return false
    return !observedResult.hasCurrentTargetlessTypingEvidence(parseCache)
}

internal fun AgentObservationReplanContext.shouldRejectUnsupportedObservedTarget(
    draft: ActionDraft,
    parseCache: ObservationJsonParseCache,
): Boolean {
    if (!draft.functionName.acceptsGuardedUiTarget()) return false
    if (!observedResult.hasCurrentTargetEvidenceSource()) return false
    val nextTarget = draft.parameters["target"].normalizedLookupKey()
        .takeIf { value -> value.isNotBlank() }
        ?: return draft.functionName == MobileActionFunctions.UI_SCROLL &&
            !observedResult.hasCurrentScrollableEvidence(parseCache)
    return !observedResult.hasCurrentTargetEvidence(nextTarget, draft.functionName, parseCache)
}

internal fun AgentObservationReplanContext.shouldRejectTextOnlyUiControl(draft: ActionDraft): Boolean {
    if (!draft.functionName.isAutonomousUiControlAction()) return false
    if (observedResult.hasCurrentTargetEvidenceSource()) return false
    return observedResult.hasReadOnlyLocalScreenTextEvidence()
}

internal fun AgentObservationReplanContext.shouldRejectDangerousObservationAction(
    draft: ActionDraft,
    parseCache: ObservationJsonParseCache,
): Boolean {
    if (!draft.functionName.isAutonomousUiControlAction()) return false
    // Typing and scrolling never activate a control (mirrors the executor's dangerous preflight).
    if (draft.functionName == MobileActionFunctions.UI_TYPE_TEXT ||
        draft.functionName == MobileActionFunctions.UI_SCROLL
    ) {
        return false
    }
    val target = draft.parameters["target"].normalizedLookupKey()
    if (
        target.isNotBlank() &&
        draft.functionName.acceptsGuardedUiTarget() &&
        observedResult.hasCurrentTargetEvidenceSource()
    ) {
        return observedResult.hasDangerousTargetEvidence(target, draft.functionName, parseCache)
    }
    return observedResult.hasDangerousUiActionEvidence(parseCache)
}

private fun String.isAutonomousUiControlAction(): Boolean =
    this == MobileActionFunctions.UI_TAP ||
        this == MobileActionFunctions.UI_TYPE_TEXT ||
        this == MobileActionFunctions.UI_SUBMIT_SEARCH ||
        this == MobileActionFunctions.UI_SCROLL ||
        this == MobileActionFunctions.UI_SWIPE ||
        this == MobileActionFunctions.UI_LONG_PRESS ||
        this == MobileActionFunctions.UI_PRESS_KEY

private fun ToolResult.hasDangerousUiActionEvidence(parseCache: ObservationJsonParseCache): Boolean =
    data["screenObservationJson"].observationHasDangerousActionEvidence(parseCache) ||
        data["afterScreenObservationJson"].observationHasDangerousActionEvidence(parseCache) ||
        data["ocrBlocksJson"].ocrBlocksHaveDangerousActionEvidence(parseCache) ||
        data["ocrText"].hasStrongDangerousActionText() ||
        data["screenText"].hasStrongDangerousActionText()

private fun ToolResult.hasDangerousTargetEvidence(
    normalizedTarget: String,
    toolName: String,
    parseCache: ObservationJsonParseCache,
): Boolean =
    data["screenObservationJson"].observationHasDangerousTargetEvidence(normalizedTarget, toolName, parseCache) ||
        data["afterScreenObservationJson"]
            .observationHasDangerousTargetEvidence(normalizedTarget, toolName, parseCache) ||
        data["ocrBlocksJson"].ocrBlocksHaveDangerousTargetEvidence(normalizedTarget, parseCache)

private fun String?.observationHasDangerousActionEvidence(parseCache: ObservationJsonParseCache): Boolean =
    withObservationElements(parseCache) { elements ->
        elements.objects().any { element -> element.isDangerousActionEvidence() }
    }

private fun String?.observationHasDangerousTargetEvidence(
    normalizedTarget: String,
    toolName: String,
    parseCache: ObservationJsonParseCache,
): Boolean =
    withObservationElements(parseCache) { elements ->
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

private fun String?.ocrBlocksHaveDangerousActionEvidence(parseCache: ObservationJsonParseCache): Boolean =
    withOcrBlocks(parseCache) { blocks ->
        blocks.objects().any { block ->
            block.optString("text").hasOcrDangerousActionText()
        }
    }

private fun String?.ocrBlocksHaveDangerousTargetEvidence(
    normalizedTarget: String,
    parseCache: ObservationJsonParseCache,
): Boolean =
    withOcrBlocks(parseCache) { blocks ->
        blocks.objects().any { block ->
            val text = block.optString("text")
            text.normalizedLookupKey() == normalizedTarget &&
                text.hasOcrDangerousActionText()
        }
    }

internal fun AgentObservationReplanContext.shouldRejectUnsupportedRepeatTarget(
    draft: ActionDraft,
    parseCache: ObservationJsonParseCache,
): Boolean {
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
    return !observedResult.hasEvidenceSupportingRepeatTarget(nextTarget, draft.functionName, parseCache)
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

private fun ToolResult.hasCurrentTargetEvidence(
    normalizedTarget: String,
    toolName: String,
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    data["screenObservationJson"].observationHasTargetEvidence(normalizedTarget, toolName, parseCache) ||
        data["afterScreenObservationJson"].observationHasTargetEvidence(normalizedTarget, toolName, parseCache) ||
        executableStandaloneOcrBlocksJson().ocrBlocksHaveTargetEvidence(normalizedTarget, parseCache) ||
        data["screenObservationDiffSummary"].diffSummaryHasTargetEvidence(normalizedTarget)

private fun ToolResult.hasCurrentSearchTapTargetEvidence(
    normalizedTarget: String,
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    data["screenObservationJson"].observationHasSearchTapTargetEvidence(normalizedTarget, parseCache) ||
        data["afterScreenObservationJson"].observationHasSearchTapTargetEvidence(normalizedTarget, parseCache)

private fun ToolResult.hasCurrentScrollableEvidence(
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    data["screenObservationJson"].observationHasScrollableEvidence(parseCache) ||
        data["afterScreenObservationJson"].observationHasScrollableEvidence(parseCache) ||
        data["screenObservationDiffSummary"].diffSummaryHasScrollableEvidence()

private fun ToolResult.hasCurrentSearchSubmitEvidence(
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    data["screenObservationJson"].observationHasSearchSubmitEvidence(parseCache) ||
        data["afterScreenObservationJson"].observationHasSearchSubmitEvidence(parseCache) ||
        executableStandaloneOcrBlocksJson().ocrBlocksHaveSearchSubmitEvidence(parseCache) ||
        data["screenObservationDiffSummary"].diffSummaryHasSearchSubmitEvidence()

private fun ToolResult.hasCurrentTargetlessTypingEvidence(
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    data["screenObservationJson"].observationHasTargetlessTypingEvidence(parseCache) ||
        data["afterScreenObservationJson"].observationHasTargetlessTypingEvidence(parseCache) ||
        executableStandaloneOcrBlocksJson().ocrBlocksHaveTargetlessTypingEvidence(parseCache) ||
        data["screenObservationDiffSummary"].diffSummaryHasTargetlessTypingEvidence()

internal fun ToolResult.executableStandaloneOcrBlocksJson(): String? {
    val blocksJson = data["ocrBlocksJson"]?.takeIf { value -> value.isNotBlank() } ?: return null
    if (data["screenObservationIncluded"] != true.toString()) return null
    if (data["screenObservationJson"].isNullOrBlank()) return null
    if (!data["screenObservationFailureKind"].isNullOrBlank()) return null
    return blocksJson
}

private fun ToolResult.hasEvidenceSupportingRepeatTarget(
    normalizedTarget: String,
    toolName: String,
    parseCache: ObservationJsonParseCache? = null,
): Boolean {
    if (data["screenObservationDiffSummary"].hasPositiveScreenChangeSignal()) return true
    return hasCurrentTargetEvidence(normalizedTarget, toolName, parseCache)
}

internal fun ToolResult.hasLocalOnlyObservationEvidence(spec: ToolSpec?): Boolean =
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

private fun String?.observationHasTargetEvidence(
    normalizedTarget: String,
    toolName: String,
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    withObservationElements(parseCache) { elements ->
        elements.hasTargetEvidence(normalizedTarget, toolName)
    }

private fun String?.observationHasSearchTapTargetEvidence(
    normalizedTarget: String,
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    withObservationElements(parseCache) { elements ->
        elements.hasSearchTapTargetEvidence(normalizedTarget)
    }

private fun String?.observationHasScrollableEvidence(
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    withObservationElements(parseCache) { elements ->
        elements.objects().any { element -> element.hasScrollableModeForPrompt() }
    }

private fun String?.observationHasSearchSubmitEvidence(
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    withObservationElements(parseCache) { elements ->
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

private fun String?.observationHasTargetlessTypingEvidence(
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    withObservationElements(parseCache) { elements ->
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

private fun String?.ocrBlocksHaveSearchSubmitEvidence(
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    withOcrBlocks(parseCache) { blocks ->
        val texts = blocks.objects().map { block -> block.optString("text") }
        texts.hasOcrSearchSubmitEvidence()
    }

private fun String?.ocrBlocksHaveTargetlessTypingEvidence(
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    withOcrBlocks(parseCache) { blocks ->
        blocks.objects().any { block ->
            block.optString("text").hasStrongSearchContextText()
        }
    }

private fun List<String>.hasOcrSearchSubmitEvidence(): Boolean =
    any { text -> text.hasStrongSearchContextText() } &&
        any { text -> text.hasStandaloneSearchSubmitText() }

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

private fun String?.ocrBlocksHaveTargetEvidence(
    normalizedTarget: String,
    parseCache: ObservationJsonParseCache? = null,
): Boolean =
    withOcrBlocks(parseCache) { blocks ->
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

// Clickability-mode predicates shared by the guards above and prompt candidate selection.
internal fun JSONObject.hasActionableModeForPrompt(): Boolean {
    val clickability = optJSONObject("clickability") ?: return false
    if (clickability.optBoolean("enabled", true) == false) return false
    return clickability.optBoolean("clickable", false) ||
        clickability.optBoolean("editable", false) ||
        clickability.optBoolean("scrollable", false)
}

internal fun JSONObject.hasScrollableModeForPrompt(): Boolean {
    val clickability = optJSONObject("clickability") ?: return false
    if (clickability.optBoolean("enabled", true) == false) return false
    return clickability.optBoolean("scrollable", false)
}
