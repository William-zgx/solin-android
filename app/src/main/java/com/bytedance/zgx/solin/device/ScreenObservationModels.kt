package com.bytedance.zgx.solin.device

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.common.optNullableString
import com.bytedance.zgx.solin.multimodal.OcrTextBlock
import com.bytedance.zgx.solin.multimodal.OcrTextBounds
import com.bytedance.zgx.solin.multimodal.OcrTextElement
import com.bytedance.zgx.solin.multimodal.OcrTextLine
import org.json.JSONArray
import org.json.JSONObject

private const val SCREEN_OBSERVATION_SCHEMA_VERSION = 1
private const val OBSERVATION_SOURCE_ACCESSIBILITY = "accessibility"
private const val OBSERVATION_SOURCE_OCR = "ocr"

data class ScreenObservation(
    val observationId: String,
    val capturedAtMillis: Long,
    val packageName: String?,
    val privacyLevel: MessagePrivacy,
    val sources: List<String>,
    val elements: List<ObservationElement>,
    val truncated: Boolean,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
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

fun ScreenStateSnapshot.toScreenObservation(maxElements: Int = Int.MAX_VALUE): ScreenObservation =
    toScreenObservation(ocrBlocks = emptyList(), maxElements = maxElements)

fun ScreenStateSnapshot.toScreenObservation(
    ocrBlocks: List<OcrTextBlock>,
    maxElements: Int = Int.MAX_VALUE,
): ScreenObservation {
    val allElements = nodes.map { node -> node.toObservationElement() } +
        ocrBlocks.toObservationElements()
    val elements = allElements.take(maxElements.coerceAtLeast(0))
    return ScreenObservation(
        observationId = id,
        capturedAtMillis = capturedAtMillis,
        packageName = packageName,
        privacyLevel = MessagePrivacy.LocalOnly,
        sources = elements.map { element -> element.source }.distinct(),
        elements = elements,
        truncated = truncated || elements.size < allElements.size,
        widthPx = widthPx,
        heightPx = heightPx,
    )
}

fun ScreenStateSnapshot.toScreenObservationJsonString(maxElements: Int = Int.MAX_VALUE): String =
    toScreenObservation(maxElements).toJsonObject().toString()

fun ScreenStateSnapshot.toScreenObservationJsonString(
    ocrBlocks: List<OcrTextBlock>,
    maxElements: Int = Int.MAX_VALUE,
): String =
    toScreenObservation(ocrBlocks = ocrBlocks, maxElements = maxElements).toJsonObject().toString()

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
        .put("widthPx", widthPx ?: JSONObject.NULL)
        .put("heightPx", heightPx ?: JSONObject.NULL)
        .put("elements", JSONArray().apply {
            elements.forEach { element -> put(element.toJsonObject()) }
        })

/**
 * Renders a compact, numbered Set-of-Marks style element table for the model prompt instead of
 * raw observation JSON. Each row is `[id] role "text" flags @l,t-r,b`, where `id` is the element's
 * existing addressable token (the model refers back to it verbatim; resolution reuses the current
 * node-id match path — no resolver change).
 *
 * - De-duplicates rows sharing the same (text, role, bounds) so repeated OCR fragments collapse.
 * - Skips elements with blank text AND no bounds (nothing the model can act on or reference).
 * - Sensitive text is already blanked to "" at observation-build time; such rows are rendered as
 *   `<敏感>` so the model knows a field exists without seeing its value.
 * - Caps output at [maxRows] rows and notes truncation, mirroring the observation's own truncation.
 */
fun ScreenObservation.toElementTable(maxRows: Int = DEFAULT_ELEMENT_TABLE_ROWS): String {
    val seen = HashSet<String>()
    val rows = ArrayList<String>()
    var omitted = 0
    for (element in elements) {
        val hasText = element.text.isNotBlank()
        val hasSensitive = element.sensitiveFlags.isNotEmpty()
        if (!hasText && !hasSensitive && element.bounds == null) continue
        val dedupeKey = "${element.role}${element.text}${element.bounds?.compactString().orEmpty()}"
        if (!seen.add(dedupeKey)) continue
        if (rows.size >= maxRows.coerceAtLeast(0)) {
            omitted += 1
            continue
        }
        rows.add(element.toElementTableRow())
    }
    if (rows.isEmpty()) return "(无可用的屏幕元素)"
    val header = "屏幕元素（LocalOnly；引用格式：使用行首方括号内的 id）:"
    val body = rows.joinToString("\n")
    val footer = if (omitted > 0 || truncated) "\n… 其余 $omitted 个元素已省略（屏幕过大）" else ""
    return "$header\n$body$footer"
}

private fun ObservationElement.toElementTableRow(): String {
    val displayText = when {
        sensitiveFlags.isNotEmpty() -> "<敏感>"
        text.isBlank() -> ""
        else -> "\"${text.replace('\n', ' ').trim()}\""
    }
    val flags = buildList {
        if (clickability.clickable) add("clickable")
        if (clickability.editable) add("editable")
        if (clickability.scrollable) add("scrollable")
        if (!clickability.enabled) add("disabled")
    }.joinToString(",")
    return buildString {
        append('[').append(id).append("] ")
        append(role)
        if (displayText.isNotEmpty()) append(' ').append(displayText)
        if (flags.isNotEmpty()) append(" (").append(flags).append(')')
        bounds?.let { append(" @").append(it.compactString()) }
    }
}

private fun ScreenBounds.compactString(): String = "$left,$top-$right,$bottom"

const val DEFAULT_ELEMENT_TABLE_ROWS = 40

fun screenObservationFromJsonStringOrNull(rawJson: String): ScreenObservation? =
    runCatching {
        JSONObject(rawJson).toScreenObservationOrNull()
    }.getOrNull()

private fun JSONObject.toScreenObservationOrNull(): ScreenObservation? {
    if (optInt("schemaVersion", -1) != SCREEN_OBSERVATION_SCHEMA_VERSION) return null
    val privacy = optString("privacyLevel").toMessagePrivacyOrNull() ?: return null
    val elements = optJSONArray("elements") ?: return null
    return ScreenObservation(
        observationId = optString("observationId").takeIf { value -> value.isNotBlank() } ?: return null,
        capturedAtMillis = optLong("capturedAtMillis", 0L),
        packageName = optNullableString("packageName"),
        privacyLevel = privacy,
        sources = optJSONArray("sources").stringList(),
        elements = (0 until elements.length()).mapNotNull { index ->
            elements.optJSONObject(index)?.toObservationElementOrNull()
        },
        truncated = optBoolean("truncated", false),
        widthPx = optInt("widthPx", -1).takeIf { it > 0 },
        heightPx = optInt("heightPx", -1).takeIf { it > 0 },
    )
}

private fun JSONObject.toObservationElementOrNull(): ObservationElement? =
    ObservationElement(
        id = optString("id").takeIf { value -> value.isNotBlank() } ?: return null,
        source = optString("source").takeIf { value -> value.isNotBlank() } ?: return null,
        bounds = optJSONObject("bounds")?.toScreenBoundsOrNull(),
        text = optString("text"),
        role = optString("role").takeIf { value -> value.isNotBlank() } ?: "unknown",
        clickability = optJSONObject("clickability")?.toObservationClickability() ?: ObservationClickability(
            clickable = false,
            editable = false,
            scrollable = false,
            enabled = true,
        ),
        confidence = optDouble("confidence", 0.0),
        sensitiveFlags = optJSONArray("sensitiveFlags").stringList(),
        privacyLevel = optString("privacyLevel").toMessagePrivacyOrNull() ?: return null,
    )

private fun JSONObject.toObservationClickability(): ObservationClickability =
    ObservationClickability(
        clickable = optBoolean("clickable", false),
        editable = optBoolean("editable", false),
        scrollable = optBoolean("scrollable", false),
        enabled = optBoolean("enabled", true),
    )

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

private fun List<OcrTextBlock>.toObservationElements(): List<ObservationElement> =
    flatMapIndexed { blockIndex, block ->
        buildList {
            add(
                block.toObservationElement(
                    id = "ocr:block:$blockIndex",
                    role = "ocr_block",
                ),
            )
            block.lines.forEachIndexed { lineIndex, line ->
                add(
                    line.toObservationElement(
                        id = "ocr:block:$blockIndex:line:$lineIndex",
                    ),
                )
                line.elements.forEachIndexed { elementIndex, element ->
                    add(
                        element.toObservationElement(
                            id = "ocr:block:$blockIndex:line:$lineIndex:element:$elementIndex",
                        ),
                    )
                }
            }
        }
    }

private fun OcrTextBlock.toObservationElement(id: String, role: String): ObservationElement =
    ocrObservationElement(
        id = id,
        text = text,
        bounds = bounds,
        role = role,
    )

private fun OcrTextLine.toObservationElement(id: String): ObservationElement =
    ocrObservationElement(
        id = id,
        text = text,
        bounds = bounds,
        role = "ocr_line",
    )

private fun OcrTextElement.toObservationElement(id: String): ObservationElement =
    ocrObservationElement(
        id = id,
        text = text,
        bounds = bounds,
        role = "ocr_element",
    )

private fun ocrObservationElement(
    id: String,
    text: String,
    bounds: OcrTextBounds?,
    role: String,
): ObservationElement {
    val flags = text.sensitiveFlags()
    return ObservationElement(
        id = id,
        source = OBSERVATION_SOURCE_OCR,
        bounds = bounds?.toScreenBounds(),
        text = if (flags.isEmpty()) text else "",
        role = role,
        clickability = ObservationClickability(
            clickable = false,
            editable = false,
            scrollable = false,
            enabled = true,
        ),
        confidence = 0.72,
        sensitiveFlags = flags,
        privacyLevel = MessagePrivacy.LocalOnly,
    )
}

private fun ScreenNode.role(): String =
    when {
        editable -> "editable"
        scrollable -> "scrollable"
        // Icon-only affordance: clickable with no visible text but a content description
        // (the label lives in contentDescription or must be grounded from nearby OCR).
        clickable && text.isBlank() && contentDescription.isNotBlank() -> "icon"
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
    return label.sensitiveFlags()
}

private fun String.sensitiveFlags(): List<String> =
    if (lowercase().let { value -> SENSITIVE_TEXT_MARKERS.any { marker -> value.contains(marker) } }) {
        listOf("credential")
    } else {
        emptyList()
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

/**
 * Single decoder for the `{left,top,right,bottom}` bounds object emitted by [ScreenBounds.toJsonObject]
 * and by the screen-observation / screen-node JSON payloads.
 *
 * Lives in `device` because [ScreenBounds] is a `device` type, and is `internal` (not private) so the
 * `tool` and `orchestration` readers of the same payload share one decoder instead of keeping private
 * byte-for-byte copies that can silently drift apart. Fails closed — a partial bounds object (any of
 * the four edges missing) decodes to `null` rather than to a `0`-padded rectangle, so callers cannot
 * mistake an incomplete payload for a real on-screen region and tap at (0,0).
 */
internal fun JSONObject.toScreenBoundsOrNull(): ScreenBounds? {
    if (!has("left") || !has("top") || !has("right") || !has("bottom")) return null
    return ScreenBounds(
        left = optInt("left"),
        top = optInt("top"),
        right = optInt("right"),
        bottom = optInt("bottom"),
    )
}

private fun JSONArray?.stringList(): List<String> =
    this?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { value -> value.isNotBlank() }
        }
    }.orEmpty()


private fun String.toMessagePrivacyOrNull(): MessagePrivacy? =
    runCatching { MessagePrivacy.valueOf(this) }.getOrNull()

private fun OcrTextBounds.toScreenBounds(): ScreenBounds =
    ScreenBounds(left = left, top = top, right = right, bottom = bottom)

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

/**
 * Trailing "affordance" tokens that ride at the end of an otherwise-informational row and denote a
 * separate tappable action (e.g. a 展开 chevron after a section title). Used by [UiTargetResolver]
 * to synthesize a distinct actionable sub-region so icon-heavy / composite rows remain reachable.
 */
internal val TRAILING_AFFORDANCE_MARKERS = listOf(
    "展开",
    "收起",
    "更多",
    "查看全部",
    "查看更多",
    "全部",
    "expand",
    "more",
    "see all",
    "view all",
)
