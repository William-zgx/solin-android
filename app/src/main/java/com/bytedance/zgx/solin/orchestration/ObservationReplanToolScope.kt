package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import org.json.JSONObject

/**
 * The tool surface the observation replanner shows a small local model: only the tools relevant to
 * the current in-app loop, rendered as compact call signatures, plus fixed few-shot examples.
 *
 * Scope:
 *  - basic local device-control tools from [MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES];
 *  - at most one composite operation, only for explicit user intent and a complete local screen;
 *  - finish / ask_user / take_over / note ONLY when the latest observation is not LocalOnly-gated.
 *    Under LocalOnly evidence the acceptance guard (`non_local_observation_tool`) rejects every
 *    non-device-control tool, so advertising them there would only invite rejected drafts.
 *
 * This only narrows what the prompt advertises; acceptance is still decided by the unchanged guards.
 * [observationReplanToolSpecs] is also the list the action-planning prompt wrapper should use for
 * replanner calls instead of every registered spec (cross-track: action/ActionPlanningRuntime).
 */
internal val IN_APP_LOOP_CONTROL_TOOL_NAMES = listOf(
    MobileActionFunctions.FINISH,
    MobileActionFunctions.ASK_USER,
    MobileActionFunctions.TAKE_OVER,
    MobileActionFunctions.NOTE,
)

/** Fixed few-shot calls (ids are illustrative; the prompt says to copy targets from the shortlist). */
internal const val OBSERVATION_REPLAN_FEW_SHOT_EXAMPLES =
    """Examples: call:ui_tap{"target":"search-entry"} | """ +
        """call:ui_type_text{"target":"search-input","text":"牛奶"} | call:ui_submit_search{}"""

internal fun AgentObservationReplanContext.observationReplanToolSpecs(toolRegistry: ToolRegistry): List<ToolSpec> {
    val composite = applicablePhoneCompositeTool()
    val primitives = if (composite == null) MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES else
        MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES - setOf(
            MobileActionFunctions.UI_SWIPE, MobileActionFunctions.UI_LONG_PRESS, MobileActionFunctions.UI_PRESS_KEY,
        )
    val deviceControl = (primitives + listOfNotNull(composite))
        .mapNotNull(toolRegistry::specFor)
        .filter { spec -> spec.capability == ToolCapability.DeviceControl }
    if (isLocalOnlyGatedObservation(toolRegistry)) return deviceControl
    return deviceControl + IN_APP_LOOP_CONTROL_TOOL_NAMES.mapNotNull(toolRegistry::specFor)
}

/**
 * T1: screen text never selects a composite capability; only the user's current instruction does.
 * Unknown/truncated/private-field observations do not authorize a larger automation surface. This
 * is prompt narrowing, not permission: the boundary still authorizes every primitive separately.
 */
private fun AgentObservationReplanContext.applicablePhoneCompositeTool(): String? {
    if (observedResult.data["privacy"] != MessagePrivacy.LocalOnly.name) return null
    val raw = observedResult.data["afterScreenObservationJson"] ?: observedResult.data["screenObservationJson"] ?: return null
    val screen = screenObservationFromJsonStringOrNull(raw)?.takeIf {
        it.privacyLevel == MessagePrivacy.LocalOnly && !it.truncated && !it.packageName.isNullOrBlank() &&
            it.elements.isNotEmpty() && it.elements.all { element -> element.sensitiveFlags.isEmpty() }
    } ?: return null
    val intent = nextActionInput?.takeIf(String::isNotBlank) ?: run.input
    val scrollable = screen.elements.any { it.clickability.enabled && it.clickability.scrollable }
    return when {
        WHEEL_INTENT.containsMatchIn(intent) && scrollable -> MobileActionFunctions.UI_SET_WHEEL
        COLLECT_INTENT.containsMatchIn(intent) && scrollable -> MobileActionFunctions.UI_COLLECT
        FIND_INTENT.containsMatchIn(intent) && scrollable -> MobileActionFunctions.UI_FIND
        else -> null
    }
}

private val WHEEL_INTENT = Regex("滚轮|轮盘|\\b(?:wheel|number\\s*picker)\\b", RegexOption.IGNORE_CASE)
private val COLLECT_INTENT = Regex("采集|收集|读完|读取全部|聊天记录|总结.*(?:列表|文章|记录)|\\b(?:collect|read\\s+all|summari[sz]e)\\b", RegexOption.IGNORE_CASE)
private val FIND_INTENT = Regex("(?:滚动|列表|翻页).*(?:找到|查找|寻找)|(?:找到|查找|寻找).*(?:列表|滚动|翻页)|\\b(?:scroll.*find|find.*(?:list|scroll))\\b", RegexOption.IGNORE_CASE)

/** Compact signatures, e.g. `ui_type_text{target,text}`: `target` (when accepted) then required args. */
internal fun AgentObservationReplanContext.observationReplanToolSignatures(toolRegistry: ToolRegistry): String =
    observationReplanToolSpecs(toolRegistry)
        .joinToString(separator = ",") { spec -> spec.compactCallSignature() }
        .ifBlank { "none" }

internal fun ToolSpec.compactCallSignature(): String {
    val schema = runCatching { JSONObject(inputSchemaJson) }.getOrNull() ?: return "$name{}"
    val properties = schema.optJSONObject("properties") ?: JSONObject()
    val required = schema.optJSONArray("required")
        ?.let { array -> (0 until array.length()).map(array::optString) }
        .orEmpty()
    val names = buildList {
        if (properties.has("target")) add("target")
        required.filterNot { it == "target" }.forEach(::add)
        // Optional parameters are otherwise invisible in compact prompts. Show only the bounded
        // controls of the one applicable composite, rather than append full schemas/descriptions.
        when (name) {
            MobileActionFunctions.UI_COLLECT, MobileActionFunctions.UI_FIND -> addAll(listOf("direction?", "pages?", "container?"))
            MobileActionFunctions.UI_SET_WHEEL -> addAll(listOf("minValue?", "maxValue?"))
        }
    }
    if (names.isEmpty()) return name
    return names.joinToString(separator = ",", prefix = "$name{", postfix = "}")
}
