package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiOcrGroundingHint
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.device.UiSystemKey
import com.bytedance.zgx.solin.device.hasOcrDangerousActionText
import com.bytedance.zgx.solin.device.hasSearchSubmitContext
import com.bytedance.zgx.solin.device.hasTargetlessTypingContext
import com.bytedance.zgx.solin.device.height
import com.bytedance.zgx.solin.device.normalizedLookupKey
import com.bytedance.zgx.solin.device.toScreenBoundsOrNull
import com.bytedance.zgx.solin.device.width
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

private const val CURRENT_SCREEN_OCR_GROUNDING_TTL_MILLIS = 15_000L
private const val OCR_GROUNDING_SIGNATURE_MAX_ELEMENTS = 24

internal class OcrGroundingCurrentScreenControlProvider(
    private val delegate: CurrentScreenControlProvider,
    private val cache: CurrentScreenOcrGroundingCache,
) : CurrentScreenControlProvider {
    override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
        cache.clear()
        return delegate.observeCurrentScreen(maxTextChars = maxTextChars, maxNodes = maxNodes)
    }

    override fun tap(target: String, timeoutMillis: Long): UiActionReadResult =
        tapWithOcrGrounding(target = target, ocrGroundingHint = null, timeoutMillis = timeoutMillis)

    override fun tapWithOcrGrounding(
        target: String,
        ocrGroundingHint: UiOcrGroundingHint?,
        timeoutMillis: Long,
    ): UiActionReadResult {
        val validationSnapshot = ocrGroundingValidationSnapshot()
        val hint = ocrGroundingHint ?: cache.consumeFor(
                target,
                currentSnapshot = validationSnapshot,
            )
        hint.rejectDangerousOcrAction(validationSnapshot)?.let { return it }
        return delegate.tapWithOcrGrounding(
            target = target,
            ocrGroundingHint = hint,
            timeoutMillis = timeoutMillis,
        )
    }

    override fun typeText(
        text: String,
        target: String?,
        timeoutMillis: Long,
        allowClipboardPasteFallback: Boolean,
    ): UiActionReadResult {
        val validationSnapshot = ocrGroundingValidationSnapshot()
        val hint = if (target.isNullOrBlank()) {
            cache.consumeForSearchEntry(currentSnapshot = validationSnapshot)
        } else {
            cache.consumeFor(target, currentSnapshot = validationSnapshot)
        }
        hint.rejectDangerousOcrAction(validationSnapshot)?.let { return it }
        validationSnapshot.rejectTargetlessTypingWithoutContext(target, hint)?.let { return it }
        return delegate.typeTextWithOcrGrounding(
            text = text,
            target = target,
            ocrGroundingHint = hint,
            timeoutMillis = timeoutMillis,
            allowClipboardPasteFallback = allowClipboardPasteFallback,
        )
    }

    override fun submitSearch(timeoutMillis: Long): UiActionReadResult =
        submitSearchWithOcrGrounding(ocrGroundingHint = null, timeoutMillis = timeoutMillis)

    override fun submitSearchWithOcrGrounding(
        ocrGroundingHint: UiOcrGroundingHint?,
        timeoutMillis: Long,
    ): UiActionReadResult {
        val validationSnapshot = ocrGroundingValidationSnapshot()
        val hint = ocrGroundingHint ?: cache.consumeForSubmitSearch(
            currentSnapshot = validationSnapshot,
        )
        hint.rejectDangerousOcrAction(validationSnapshot)?.let { return it }
        validationSnapshot.rejectSubmitWithoutSearchContext(hint)?.let { return it }
        return delegate.submitSearchWithOcrGrounding(
            ocrGroundingHint = hint,
            timeoutMillis = timeoutMillis,
        )
    }

    override fun scroll(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult {
        cache.clear()
        return delegate.scroll(direction = direction, target = target, timeoutMillis = timeoutMillis)
    }

    override fun pressBack(timeoutMillis: Long): UiActionReadResult {
        cache.clear()
        return delegate.pressBack(timeoutMillis = timeoutMillis)
    }

    override fun waitForScreen(timeoutMillis: Long): UiActionReadResult {
        cache.clear()
        return delegate.waitForScreen(timeoutMillis = timeoutMillis)
    }

    override fun tapByNormalizedCoords(
        normalizedX: Int,
        normalizedY: Int,
        timeoutMillis: Long,
    ): UiActionReadResult {
        cache.clear()
        return delegate.tapByNormalizedCoords(
            normalizedX = normalizedX,
            normalizedY = normalizedY,
            timeoutMillis = timeoutMillis,
        )
    }

    // swipe / longPress / pressKey operate on normalized coordinates or a closed system-key set and
    // need no OCR grounding, but they MUST still be forwarded to the delegate. Without these
    // overrides they fall through to the interface's default no-op-failure implementation, so
    // ui_swipe / ui_long_press / ui_press_key would always fail whenever the control provider is
    // wrapped for OCR grounding (the production path). Clear the cache like every other mutating
    // action and delegate straight through.
    override fun swipe(
        startXNorm: Int,
        startYNorm: Int,
        endXNorm: Int,
        endYNorm: Int,
        durationMillis: Long,
        timeoutMillis: Long,
    ): UiActionReadResult {
        cache.clear()
        return delegate.swipe(
            startXNorm = startXNorm,
            startYNorm = startYNorm,
            endXNorm = endXNorm,
            endYNorm = endYNorm,
            durationMillis = durationMillis,
            timeoutMillis = timeoutMillis,
        )
    }

    override fun longPress(
        xNorm: Int,
        yNorm: Int,
        holdMillis: Long,
        timeoutMillis: Long,
    ): UiActionReadResult {
        cache.clear()
        return delegate.longPress(
            xNorm = xNorm,
            yNorm = yNorm,
            holdMillis = holdMillis,
            timeoutMillis = timeoutMillis,
        )
    }

    override fun pressKey(key: UiSystemKey, timeoutMillis: Long): UiActionReadResult {
        cache.clear()
        return delegate.pressKey(key = key, timeoutMillis = timeoutMillis)
    }

    private fun ocrGroundingValidationSnapshot(): ScreenStateSnapshot? =
        when (val result = delegate.observeCurrentScreen()) {
            is ScreenStateReadResult.Available -> result.snapshot
            else -> null
        }

    private fun ScreenStateSnapshot?.rejectSubmitWithoutSearchContext(
        hint: UiOcrGroundingHint?,
    ): UiActionReadResult? {
        if (hint != null) return null
        if (this != null && hasSearchSubmitContext()) return null
        return toGuardFailure(
            reason = "当前屏幕没有搜索输入框、搜索提交控件或 OCR 搜索提交证据",
            failureKind = UiActionFailureKind.SubmitNotFound,
        )
    }

    private fun ScreenStateSnapshot?.rejectTargetlessTypingWithoutContext(
        target: String?,
        hint: UiOcrGroundingHint?,
    ): UiActionReadResult? {
        if (!target.isNullOrBlank()) return null
        if (hint != null) return null
        if (this != null && hasTargetlessTypingContext()) return null
        return toGuardFailure(
            reason = "当前屏幕没有明确的搜索输入目标，请先点击或指定输入框",
            failureKind = UiActionFailureKind.EditableNotFound,
        )
    }

    private fun UiOcrGroundingHint?.rejectDangerousOcrAction(
        snapshot: ScreenStateSnapshot?,
    ): UiActionReadResult? {
        if (this == null || !text.hasOcrDangerousActionText()) return null
        return snapshot.toGuardFailure(
            reason = "当前屏幕 OCR 显示支付、发送、删除、发布、下单、购买、转账或授权类控件，已停止自动 UI 动作。",
            failureKind = UiActionFailureKind.DangerousAction,
            retryable = false,
        )
    }

    private fun ScreenStateSnapshot?.toGuardFailure(
        reason: String,
        failureKind: UiActionFailureKind,
        retryable: Boolean = true,
    ): UiActionReadResult =
        if (this == null) {
            UiActionReadResult.Failed(
                reason = reason,
                retryable = retryable,
                failureKind = failureKind,
            )
        } else {
            UiActionReadResult.Available(
                UiActionExecutionResult(
                    status = UiActionStatus.Failed,
                    before = this,
                    after = this,
                    summary = reason,
                    retryable = retryable,
                    failureKind = failureKind,
                ),
            )
        }
}

/**
 * Single-slot cache handing OCR grounding evidence from `capture_current_screenshot_ocr`
 * to the very next UI action, with "consume means invalidate" semantics.
 *
 * WHY [AtomicReference] and not a plain `var`: writers and readers do not share a thread.
 * [RoutingToolExecutor.dispatchAsBuiltIn] writes (updateFrom / clear) and
 * [OcrGroundingCurrentScreenControlProvider] reads (consumeFor*), and the batch path runs
 * requests on `Dispatchers.IO` worker threads (see [RoutingToolExecutor.executeBatch]). A
 * plain `var` has no happens-before edge under the JMM, so a reader could observe a stale
 * or half-published hint; worse, the `read` then `null` pair in each consumeFor* was not
 * atomic, so two concurrent actions could both consume the SAME hint. That hint decides
 * where a tap lands, so a stale/duplicated read is a safety problem, not just a cache miss.
 * Today [RoutingToolExecutor.shouldRunSequentially] happens to route every DeviceControl
 * tool into the sequential segment, which hides the race — one ToolSpec executionMode edit
 * would expose it. `getAndSet(null)` makes "consume invalidates" atomic and unconditional.
 */
internal class CurrentScreenOcrGroundingCache(
    private val clockMillis: () -> Long,
) {
    private val cachedHint = AtomicReference<CachedOcrGroundingHint?>(null)

    fun updateFrom(result: ToolResult) {
        if (result.status != ToolStatus.Succeeded) {
            clear()
            return
        }
        cachedHint.set(parseGroundingHint(result))
    }

    private fun parseGroundingHint(result: ToolResult): CachedOcrGroundingHint? =
        runCatching {
            val observationJson = result.data["screenObservationJson"]?.takeIf { value -> value.isNotBlank() }
                ?: return@runCatching null
            val observation = JSONObject(observationJson)
            val screenSignature = observation.toOcrGroundingScreenSignature()
                ?: return@runCatching null
            val elements = observation.optJSONArray("elements") ?: return@runCatching null
            val ocrElements = (0 until elements.length())
                .mapNotNull { index -> elements.optJSONObject(index)?.toOcrGroundingHint(observation) }
            if (ocrElements.isEmpty()) null else CachedOcrGroundingHint(
                capturedAtMillis = clockMillis(),
                screenSignature = screenSignature,
                hints = ocrElements,
            )
        }.getOrNull()

    fun consumeFor(target: String, currentSnapshot: ScreenStateSnapshot?): UiOcrGroundingHint? {
        val cache = consume() ?: return null
        if (isExpiredOrMismatched(cache, currentSnapshot)) return null
        val normalizedTarget = target.normalizedLookupKey()
        if (normalizedTarget.isBlank()) return null
        return cache.hints.bestForTarget(normalizedTarget)
    }

    fun consumeForSearchEntry(currentSnapshot: ScreenStateSnapshot?): UiOcrGroundingHint? {
        val cache = consume() ?: return null
        if (isExpiredOrMismatched(cache, currentSnapshot)) return null
        return cache.hints
            .filter { hint -> hint.hasSearchContextText() }
            .maxByOrNull { hint -> hint.scoreForSearchEntryTarget() }
    }

    fun consumeForSubmitSearch(currentSnapshot: ScreenStateSnapshot?): UiOcrGroundingHint? {
        val cache = consume() ?: return null
        if (isExpiredOrMismatched(cache, currentSnapshot)) return null
        if (!cache.hints.hasOcrSearchSubmitContext()) return null
        return cache.hints
            .filter { hint -> hint.matchesSubmitSearchTarget() }
            .maxByOrNull { hint -> hint.scoreForSubmitSearchTarget() }
    }

    fun clear() {
        cachedHint.set(null)
    }

    /**
     * Atomically take the cached hint and invalidate the slot, so a hint is handed to at
     * most one action even under concurrent consumers.
     */
    private fun consume(): CachedOcrGroundingHint? = cachedHint.getAndSet(null)

    private fun isExpiredOrMismatched(
        cache: CachedOcrGroundingHint,
        currentSnapshot: ScreenStateSnapshot?,
    ): Boolean =
        clockMillis() - cache.capturedAtMillis > CURRENT_SCREEN_OCR_GROUNDING_TTL_MILLIS ||
            !cache.screenSignature.matches(currentSnapshot)
}

private data class CachedOcrGroundingHint(
    val capturedAtMillis: Long,
    val screenSignature: OcrGroundingScreenSignature,
    val hints: List<UiOcrGroundingHint>,
)

internal data class OcrGroundingScreenSignature(
    val packageName: String?,
    val nodeCount: Int,
    val actionableNodeCount: Int,
    val elementFingerprint: String,
) {
    fun matches(snapshot: ScreenStateSnapshot?): Boolean =
        snapshot?.toOcrGroundingScreenSignature() == this
}

internal fun ScreenStateSnapshot.toOcrGroundingScreenSignature(): OcrGroundingScreenSignature =
    OcrGroundingScreenSignature(
        packageName = packageName,
        nodeCount = nodeCount,
        actionableNodeCount = actionableNodeCount,
        elementFingerprint = nodes
            .asSequence()
            .map { node -> node.ocrGroundingFingerprint() }
            .take(OCR_GROUNDING_SIGNATURE_MAX_ELEMENTS)
            .joinToString(separator = ";"),
    )

private fun JSONObject.toOcrGroundingScreenSignature(): OcrGroundingScreenSignature? {
    val elements = optJSONArray("elements") ?: return null
    val accessibilityElements = (0 until elements.length())
        .mapNotNull { index -> elements.optJSONObject(index) }
        .filter { element -> element.optString("source") == "accessibility" }
    if (accessibilityElements.isEmpty()) return null
    return OcrGroundingScreenSignature(
        packageName = optNullableString("packageName"),
        nodeCount = accessibilityElements.size,
        actionableNodeCount = accessibilityElements.count { element -> element.isActionableObservationElement() },
        elementFingerprint = accessibilityElements
            .asSequence()
            .map { element -> element.ocrGroundingFingerprint() }
            .take(OCR_GROUNDING_SIGNATURE_MAX_ELEMENTS)
            .joinToString(separator = ";"),
    )
}

/**
 * Screen fingerprint computed from the LIVE [ScreenNode] list.
 *
 * !! MUST STAY EQUIVALENT TO [JSONObject.ocrGroundingFingerprint] !!
 * [OcrGroundingScreenSignature.matches] stores the signature built from the observation JSON at
 * capture time and compares it against the signature built from live nodes at tap time, so the two
 * functions below are compared cross-representation. They are NOT interchangeable today — see the
 * KNOWN DIVERGENCES note on the JSON variant before touching either.
 */
private fun ScreenNode.ocrGroundingFingerprint(): String =
    listOf(
        clickable.toString(),
        editable.toString(),
        scrollable.toString(),
        enabled.toString(),
        bounds?.boundsFingerprint().orEmpty(),
        text.ifBlank { contentDescription }.normalizedLookupKey(),
    ).joinToString(separator = ",")

/**
 * Same fingerprint, read back from a serialized observation element.
 *
 * !! MUST STAY EQUIVALENT TO [ScreenNode.ocrGroundingFingerprint] !! Kept as a separate function
 * because the two inputs are different types; there is no shared receiver to hang one
 * implementation off. Any edit to one has to be mirrored in the other.
 *
 * KNOWN DIVERGENCES (pre-existing; deliberately NOT changed in this de-duplication pass because
 * they alter where a tap lands, which is a device-safety surface that needs its own reviewed fix):
 *  1. Text field. The node variant uses `text.ifBlank { contentDescription }` — one field or the
 *     other. The serialized `text` is `ScreenNode.observationText()`, which CONCATENATES both
 *     (`"text contentDescription"`). A node with two different non-blank fields therefore
 *     fingerprints differently on the two sides.
 *  2. Sensitive nodes. `toObservationElement` blanks `text` to `""` for credential-flagged nodes,
 *     so the JSON side fingerprints an empty text where the node side still fingerprints the real
 *     label. (The blanking itself is a privacy requirement and must stay.)
 *  3. `actionableNodeCount` in the enclosing signature. [isActionableObservationElement] excludes
 *     `enabled == false` elements; `ScreenStateSnapshot.actionableNodeCount` does not. A
 *     disabled-but-clickable node is counted on the node side only.
 *
 * Consequence today is fail-CLOSED, which is why the build stays green and no tap is misplaced: a
 * spurious mismatch makes [OcrGroundingScreenSignature.matches] return false, so the cached OCR
 * grounding hint is discarded and the tap falls back to ordinary target resolution. The cost is a
 * silently ineffective grounding cache on such screens, not a wrong tap. Any fix must unify both
 * sides onto ONE projection (most likely by fingerprinting `toScreenObservation()` elements on the
 * node side too, so the lossy serialization is applied identically) and land with a regression test.
 */
private fun JSONObject.ocrGroundingFingerprint(): String {
    val clickability = optJSONObject("clickability")
    return listOf(
        clickability?.optBoolean("clickable", false).toString(),
        clickability?.optBoolean("editable", false).toString(),
        clickability?.optBoolean("scrollable", false).toString(),
        clickability?.optBoolean("enabled", true).toString(),
        optJSONObject("bounds")?.boundsFingerprint().orEmpty(),
        optString("text").normalizedLookupKey(),
    ).joinToString(separator = ",")
}

private fun JSONObject.isActionableObservationElement(): Boolean {
    val clickability = optJSONObject("clickability") ?: return false
    if (clickability.optBoolean("enabled", true) == false) return false
    return clickability.optBoolean("clickable", false) ||
        clickability.optBoolean("editable", false) ||
        clickability.optBoolean("scrollable", false)
}

/**
 * Bounds half of the fingerprint pair above.
 *
 * !! THESE TWO MUST PRODUCE THE SAME STRING FOR THE SAME RECTANGLE !! They are compared
 * cross-representation (see [ScreenNode.ocrGroundingFingerprint]). They cannot share one body
 * because there is no common receiver type, so the format string is duplicated on purpose.
 *
 * Unlike the text field, this half is provably equivalent for every payload the serializer emits:
 * `ScreenBounds.toJsonObject()` always writes all four edges as ints, and a `bounds` object is only
 * ever produced from a `ScreenBounds`. The `optInt` defaults below are therefore unreachable in
 * practice; they only matter for a hand-written partial payload, which would fingerprint as `0` edges
 * here and so simply fail the match (fail-closed — the grounding hint is dropped, never mis-applied).
 */
private fun ScreenBounds.boundsFingerprint(): String =
    "$left,$top,$right,$bottom"

private fun JSONObject.boundsFingerprint(): String =
    "${optInt("left")},${optInt("top")},${optInt("right")},${optInt("bottom")}"

private fun List<UiOcrGroundingHint>.hasOcrSearchSubmitContext(): Boolean =
    any { hint -> hint.matchesSubmitSearchTarget() } &&
        any { hint -> hint.hasSearchContextText() }

private fun UiOcrGroundingHint.hasSearchContextText(): Boolean {
    val normalizedText = text.normalizedLookupKey()
    if (normalizedText.isBlank()) return false
    return listOf(
        "搜索框",
        "搜索栏",
        "搜索商品",
        "搜索输入",
        "搜索词",
        "搜索或输入网址",
        "地址栏",
        "网址",
        "目的地",
        "搜地点",
        "searchbox",
        "searchfield",
        "omnibox",
        "keyword",
    ).any { marker -> normalizedText.contains(marker.normalizedLookupKey()) }
}

private fun JSONObject.toOcrGroundingHint(observation: JSONObject): UiOcrGroundingHint? {
    if (optString("source") != "ocr") return null
    val text = optString("text").takeIf { value -> value.isNotBlank() } ?: return null
    val bounds = optJSONObject("bounds")?.toScreenBoundsOrNull() ?: return null
    if (bounds.width() <= 0 || bounds.height() <= 0) return null
    return UiOcrGroundingHint(
        observationId = observation.optString("observationId", "unknown"),
        packageName = observation.optNullableString("packageName"),
        capturedAtMillis = observation.optLong("capturedAtMillis").takeIf { value -> value > 0L },
        elementId = optString("id", "ocr"),
        text = text,
        bounds = bounds,
    )
}

private fun List<UiOcrGroundingHint>.bestForTarget(normalizedTarget: String): UiOcrGroundingHint? {
    val idMatches = filter { hint -> hint.elementId.normalizedLookupKey() == normalizedTarget }
    if (idMatches.isNotEmpty()) {
        return idMatches.maxByOrNull { hint -> hint.scoreForTarget(normalizedTarget) }
    }
    return filter { hint -> hint.textMatchesTarget(normalizedTarget) }.singleOrNull()
}

/** OCR text that normalizes to blank (e.g. a lone "<" back arrow) matches no target. */
private fun UiOcrGroundingHint.textMatchesTarget(normalizedTarget: String): Boolean {
    val normalizedText = text.normalizedLookupKey()
    return normalizedText.isNotBlank() &&
        (normalizedText.contains(normalizedTarget) || normalizedTarget.contains(normalizedText))
}

private fun UiOcrGroundingHint.scoreForTarget(normalizedTarget: String): Int {
    val normalizedText = text.normalizedLookupKey()
    val normalizedId = elementId.normalizedLookupKey()
    return when {
        normalizedId == normalizedTarget -> 1_400
        normalizedText == normalizedTarget -> 1_000
        normalizedText.contains(normalizedTarget) -> 800 - (normalizedText.length - normalizedTarget.length).coerceAtLeast(0)
        normalizedTarget.contains(normalizedText) -> 620 - (normalizedTarget.length - normalizedText.length).coerceAtLeast(0)
        else -> 0
    } + bounds.width().coerceAtMost(200) + bounds.height().coerceAtMost(120)
}

private val SUBMIT_SEARCH_OCR_HINTS = listOf(
    "提交搜索",
    "搜索",
    "查找",
    "前往",
    "转到",
    "search",
    "go",
    "enter",
).map { value -> value.normalizedLookupKey() }

private fun UiOcrGroundingHint.matchesSubmitSearchTarget(): Boolean {
    val normalizedText = text.normalizedLookupKey()
    if (normalizedText.isBlank()) return false
    return normalizedText in SUBMIT_SEARCH_OCR_HINTS
}

private fun UiOcrGroundingHint.scoreForSubmitSearchTarget(): Int {
    val normalizedText = text.normalizedLookupKey()
    val matchScore = when {
        normalizedText in SUBMIT_SEARCH_OCR_HINTS -> 1_200
        else -> 0
    }
    val rightBias = (bounds.left / 20).coerceIn(0, 120)
    val topBias = if (bounds.top <= 300) 80 else 0
    val areaPenalty = (bounds.area() / 1_000).coerceAtMost(500)
    val widthPenalty = (bounds.width() / 8).coerceAtMost(250)
    return matchScore + rightBias + topBias - areaPenalty - widthPenalty
}

private fun UiOcrGroundingHint.scoreForSearchEntryTarget(): Int {
    val normalizedText = text.normalizedLookupKey()
    val contextScore = if (hasSearchContextText()) 1_200 else 0
    val topBias = if (bounds.top <= 360) 90 else 0
    val leftBias = ((10_000 - bounds.left.coerceIn(0, 10_000)) / 100).coerceIn(0, 80)
    val widthScore = (bounds.width() / 8).coerceAtMost(240)
    val shortTextPenalty = if (normalizedText.length <= 2) 220 else 0
    return contextScore + topBias + leftBias + widthScore - shortTextPenalty
}

private fun ScreenBounds.area(): Int =
    boundsDimension(width()) * boundsDimension(height())

private fun boundsDimension(value: Int): Int =
    value.coerceAtLeast(0).coerceAtMost(10_000)

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { value -> value.isNotBlank() }
