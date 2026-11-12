package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.device.ScreenObservation
import com.bytedance.zgx.solin.device.containsOcrGroundingBounds
import com.bytedance.zgx.solin.device.screenObservationFromJsonStringOrNull
import com.bytedance.zgx.solin.device.toScreenBoundsOrNull
import org.json.JSONArray
import org.json.JSONObject

internal val localObservationJsonKeys = listOf(
    "screenObservationJson",
    "beforeScreenObservationJson",
    "afterScreenObservationJson",
)

internal data class OcrTargetEvidencePrompt(
    val id: String,
    val text: String,
    val role: String,
    val bounds: String,
)

internal fun JSONObject.toOcrTargetEvidencePrompts(blockIndex: Int): List<OcrTargetEvidencePrompt> {
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

internal inline fun String?.withObservationElements(
    cache: ObservationJsonParseCache? = null,
    predicate: (JSONArray) -> Boolean,
): Boolean =
    this?.let { rawJson ->
        runCatching {
            predicate(rawJson.observationElements(cache))
        }.getOrDefault(false)
    } ?: false

internal inline fun String?.withOcrBlocks(
    cache: ObservationJsonParseCache? = null,
    predicate: (JSONArray) -> Boolean,
): Boolean =
    this?.let { rawJson ->
        runCatching {
            rawJson.ocrBlocksArrayOrNull(cache)?.let(predicate)
        }.getOrNull() ?: false
    } ?: false

/**
 * Resolves the observation `elements` [JSONArray] for [this] raw JSON, reusing [cache] when present
 * (throws on malformed JSON so callers keep the prior permissive [runCatching] fall-through).
 */
private fun String.observationElements(cache: ObservationJsonParseCache?): JSONArray =
    cache?.observationElements(this)
        ?: (JSONObject(this).optJSONArray("elements") ?: JSONArray())

/**
 * Resolves the OCR-blocks [JSONArray] for [this] raw JSON, reusing [cache] when present. Returns
 * null on malformed JSON so callers keep the prior permissive fall-through.
 */
internal fun String.ocrBlocksArrayOrNull(cache: ObservationJsonParseCache?): JSONArray? =
    if (cache != null) {
        cache.ocrBlocksArrayOrNull(this)
    } else {
        runCatching { JSONArray(this) }.getOrNull()
    }

/**
 * Lazy, invocation-local cache of parsed current-observation JSON. Memoizes the three expensive
 * shapes the model prompt and acceptance paths read repeatedly per [ModelObservationReplanner.planNext]
 * call — the permissive prompt `elements` [JSONArray], the OCR-blocks [JSONArray], and the non-throwing
 * typed [ScreenObservation] — keyed by the raw JSON string.
 *
 * Not thread-safe by design: a cache instance is created per planning invocation and never shared
 * across invocations or the shared [ModelObservationReplanner] instance. The parsed [JSONObject] /
 * [JSONArray] values are held in private maps and only handed out to read-only consumers; callers
 * must not mutate them (existing consumers only read). The type is `internal` so it can appear in the
 * `internal` prompt-builder signatures, but the raw parses themselves stay private.
 */
internal class ObservationJsonParseCache {
    private val elementArrays = HashMap<String, JSONArray>()
    private val ocrArrays = HashMap<String, JSONArray?>()
    private val screenObservations = HashMap<String, ScreenObservation?>()

    fun observationElements(rawJson: String): JSONArray =
        elementArrays.getOrPut(rawJson) {
            runCatching { JSONObject(rawJson).optJSONArray("elements") ?: JSONArray() }
                .getOrDefault(JSONArray())
        }

    // computeIfAbsent-style memoization that also caches null results, so malformed JSON is not
    // re-parsed on each read within the invocation (getOrPut would re-run its lambda for null values).
    fun ocrBlocksArrayOrNull(rawJson: String): JSONArray? {
        if (!ocrArrays.containsKey(rawJson)) {
            ocrArrays[rawJson] = runCatching { JSONArray(rawJson) }.getOrNull()
        }
        return ocrArrays[rawJson]
    }

    fun screenObservationOrNull(rawJson: String): ScreenObservation? {
        if (!screenObservations.containsKey(rawJson)) {
            screenObservations[rawJson] = screenObservationFromJsonStringOrNull(rawJson)
        }
        return screenObservations[rawJson]
    }
}

internal fun JSONArray.indexedObjects(limit: Int = length()): List<Pair<Int, JSONObject>> =
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

internal fun JSONArray.containsString(value: String): Boolean =
    (0 until length()).any { index -> optString(index) == value }

internal fun JSONObject.boundsPromptText(): String =
    "[${optInt("left")},${optInt("top")},${optInt("right")},${optInt("bottom")}]"

internal fun JSONObject.containsOcrGroundingElementBounds(ocrBounds: JSONObject?): Boolean {
    val container = toScreenBoundsOrNull() ?: return false
    val ocr = ocrBounds?.toScreenBoundsOrNull() ?: return false
    return container.containsOcrGroundingBounds(ocr)
}

internal fun JSONArray.joinCompact(): String =
    (0 until length())
        .joinToString(separator = ",") { index -> optString(index) }
        .ifBlank { "none" }

internal fun JSONObject.joinCompact(): String =
    keys().asSequence()
        .toList()
        .sorted()
        .joinToString(separator = ",") { key -> "$key=${opt(key)}" }
        .ifBlank { "none" }
