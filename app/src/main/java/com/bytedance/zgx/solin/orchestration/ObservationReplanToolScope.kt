package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import org.json.JSONObject

/**
 * The tool surface the observation replanner shows a small local model: only the tools relevant to
 * the current in-app loop, rendered as compact call signatures, plus fixed few-shot examples.
 *
 * Scope:
 *  - local device-control tools from [MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES], always;
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
    val deviceControl = MODEL_OBSERVATION_REPLAN_ACTION_TOOL_NAMES
        .mapNotNull(toolRegistry::specFor)
        .filter { spec -> spec.capability == ToolCapability.DeviceControl }
    if (isLocalOnlyGatedObservation(toolRegistry)) return deviceControl
    return deviceControl + IN_APP_LOOP_CONTROL_TOOL_NAMES.mapNotNull(toolRegistry::specFor)
}

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
    }
    if (names.isEmpty()) return name
    return names.joinToString(separator = ",", prefix = "$name{", postfix = "}")
}
