package com.bytedance.zgx.solin.orchestration

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small JSON readers shared by the trace-summary consumers in this package (restore, startup
 * repair, trace queries). Persisted step summaries are value-free JSON; every reader here fails
 * soft (null / empty) on malformed input so callers can fail closed.
 */

internal fun AgentTraceStepSummary.jsonObjectOrNull(): JSONObject? =
    runCatching { JSONObject(json) }.getOrNull()

internal fun AgentTraceStepSummary.requestIdFromJson(): String? =
    jsonObjectOrNull()?.optString("requestId")?.takeIf { it.isNotBlank() }

/** Every JSONObject element of this array, skipping non-object entries. */
internal fun JSONArray.jsonObjects(): List<JSONObject> =
    buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }

internal fun firstNonBlank(vararg values: String): String? =
    values.firstOrNull { value -> value.isNotBlank() }
