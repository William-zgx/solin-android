package com.bytedance.zgx.pocketmind.device

import com.bytedance.zgx.pocketmind.MessagePrivacy
import org.json.JSONArray
import org.json.JSONObject

private const val SCREEN_OBSERVATION_SCHEMA_VERSION = 1
private const val OBSERVATION_SOURCE_ACCESSIBILITY = "accessibility"

data class ScreenObservation(
    val observationId: String,
    val capturedAtMillis: Long,
    val packageName: String?,
    val privacyLevel: MessagePrivacy,
    val sources: List<String>,
    val elements: List<ObservationElement>,
    val truncated: Boolean,
)

data class ObservationElement(
    val id: String,
    val source: String,
    val bounds: ScreenBounds?,
    val text: String,
    val role: String,
    val clickability: ObservationClickability,
    val confidence: Double,
    val sensitiveFlags: List<String>,
    val privacyLevel: MessagePrivacy,
)

data class ObservationClickability(
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
)

fun ScreenStateSnapshot.toScreenObservation(maxElements: Int = Int.MAX_VALUE): ScreenObservation {
    val elements = nodes
        .take(maxElements.coerceAtLeast(0))
        .map { node -> node.toObservationElement() }
    return ScreenObservation(
        observationId = id,
        capturedAtMillis = capturedAtMillis,
        packageName = packageName,
        privacyLevel = MessagePrivacy.LocalOnly,
        sources = listOf(OBSERVATION_SOURCE_ACCESSIBILITY),
        elements = elements,
        truncated = truncated || elements.size < nodes.size,
    )
}

fun ScreenStateSnapshot.toScreenObservationJsonString(maxElements: Int = Int.MAX_VALUE): String =
    toScreenObservation(maxElements).toJsonObject().toString()

fun ScreenObservation.toJsonObject(): JSONObject =
    JSONObject()
        .put("schemaVersion", SCREEN_OBSERVATION_SCHEMA_VERSION)
        .put("observationId", observationId)
        .put("capturedAtMillis", capturedAtMillis)
        .put("packageName", packageName ?: JSONObject.NULL)
        .put("privacyLevel", privacyLevel.name)
        .put("sources", JSONArray(sources))
        .put("elementCount", elements.size)
        .put("sourceCounts", elements.sourceCountsJson())
        .put("truncated", truncated)
        .put("elements", JSONArray().apply {
            elements.forEach { element -> put(element.toJsonObject()) }
        })

private fun ScreenNode.toObservationElement(): ObservationElement {
    val flags = sensitiveFlags()
    return ObservationElement(
        id = id,
        source = OBSERVATION_SOURCE_ACCESSIBILITY,
        bounds = bounds,
        text = if (flags.isEmpty()) observationText() else "",
        role = role(),
        clickability = ObservationClickability(
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            enabled = enabled,
        ),
        confidence = 1.0,
        sensitiveFlags = flags,
        privacyLevel = MessagePrivacy.LocalOnly,
    )
}

private fun ScreenNode.role(): String =
    when {
        editable -> "editable"
        scrollable -> "scrollable"
        clickable -> "button"
        text.isNotBlank() || contentDescription.isNotBlank() -> "text"
        else -> "unknown"
    }

private fun ScreenNode.observationText(): String =
    listOf(text, contentDescription)
        .filter { value -> value.isNotBlank() }
        .distinct()
        .joinToString(" ")

private fun ScreenNode.sensitiveFlags(): List<String> {
    val label = listOf(id, text, contentDescription, className)
        .joinToString(" ")
        .lowercase()
    return if (SENSITIVE_TEXT_MARKERS.any { marker -> label.contains(marker) }) {
        listOf("credential")
    } else {
        emptyList()
    }
}

private fun ObservationElement.toJsonObject(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("source", source)
        .put("bounds", bounds?.toJsonObject())
        .put("text", text)
        .put("role", role)
        .put("clickability", clickability.toJsonObject())
        .put("confidence", confidence)
        .put("sensitiveFlags", JSONArray(sensitiveFlags))
        .put("privacyLevel", privacyLevel.name)

private fun ObservationClickability.toJsonObject(): JSONObject =
    JSONObject()
        .put("clickable", clickable)
        .put("editable", editable)
        .put("scrollable", scrollable)
        .put("enabled", enabled)

fun List<ScreenNode>.toScreenNodesJsonString(): String =
    JSONArray().apply {
        forEach { node ->
            put(
                JSONObject()
                    .put("id", node.id)
                    .put("text", node.text)
                    .put("contentDescription", node.contentDescription)
                    .put("className", node.className)
                    .put("bounds", node.bounds?.toJsonObject())
                    .put("clickable", node.clickable)
                    .put("editable", node.editable)
                    .put("scrollable", node.scrollable)
                    .put("enabled", node.enabled),
            )
        }
    }.toString()

private fun List<ObservationElement>.sourceCountsJson(): JSONObject {
    val counts = groupingBy { element -> element.source }.eachCount()
    return JSONObject().apply {
        counts.forEach { (source, count) -> put(source, count) }
    }
}

private fun ScreenBounds.toJsonObject(): JSONObject =
    JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)

private val SENSITIVE_TEXT_MARKERS = listOf(
    "password",
    "passcode",
    "passwd",
    "pwd",
    "secret",
    "token",
    "otp",
    "验证码",
    "密码",
    "口令",
)
