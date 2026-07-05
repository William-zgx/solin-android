package com.bytedance.zgx.solin.device

const val DEVICE_CONTROL_SOURCE_ACCESSIBILITY = "accessibility_active_window"
const val DEVICE_CONTROL_METADATA_POLICY =
    "accessibility_control_local_only_transient_node_ids_no_pixels_persisted"

const val DEFAULT_DEVICE_CONTROL_MAX_TEXT_CHARS = 2_000
const val MAX_DEVICE_CONTROL_TEXT_CHARS = 4_000
const val DEFAULT_DEVICE_CONTROL_MAX_NODES = 50
const val MAX_DEVICE_CONTROL_NODES = 120
const val DEFAULT_UI_ACTION_TIMEOUT_MILLIS = 1_000L
const val MIN_UI_ACTION_TIMEOUT_MILLIS = 100L
const val MAX_UI_ACTION_TIMEOUT_MILLIS = 10_000L
const val MAX_UI_TYPE_TEXT_CHARS = 2_000

interface CurrentScreenControlProvider {
    fun observeCurrentScreen(
        maxTextChars: Int = DEFAULT_DEVICE_CONTROL_MAX_TEXT_CHARS,
        maxNodes: Int = DEFAULT_DEVICE_CONTROL_MAX_NODES,
    ): ScreenStateReadResult

    fun tap(
        target: String,
        timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS,
    ): UiActionReadResult

    fun tapWithOcrGrounding(
        target: String,
        ocrGroundingHint: UiOcrGroundingHint?,
        timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS,
    ): UiActionReadResult =
        tap(target = target, timeoutMillis = timeoutMillis)

    fun typeText(
        text: String,
        target: String? = null,
        timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS,
        allowClipboardPasteFallback: Boolean = false,
    ): UiActionReadResult

    fun typeTextWithOcrGrounding(
        text: String,
        target: String? = null,
        ocrGroundingHint: UiOcrGroundingHint?,
        timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS,
        allowClipboardPasteFallback: Boolean = false,
    ): UiActionReadResult =
        typeText(
            text = text,
            target = target,
            timeoutMillis = timeoutMillis,
            allowClipboardPasteFallback = allowClipboardPasteFallback,
        )

    fun submitSearch(timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS): UiActionReadResult

    fun submitSearchWithOcrGrounding(
        ocrGroundingHint: UiOcrGroundingHint?,
        timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS,
    ): UiActionReadResult =
        submitSearch(timeoutMillis = timeoutMillis)

    fun scroll(
        direction: UiScrollDirection,
        target: String? = null,
        timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS,
    ): UiActionReadResult

    fun pressBack(timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS): UiActionReadResult

    fun waitForScreen(timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS): UiActionReadResult

    /**
     * Tap at a position specified by normalized 0-1000 coordinates.
     *
     * Resolution-agnostic targeting: (500, 500) always means the screen center.
     * Inspired by Open-AutoGLM's normalized coordinate system.
     */
    fun tapByNormalizedCoords(
        normalizedX: Int,
        normalizedY: Int,
        timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS,
    ): UiActionReadResult
}

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = left + ((right - left) / 2)
    val centerY: Int get() = top + ((bottom - top) / 2)
}

const val DEFAULT_OCR_GROUNDING_BOUNDS_COVERAGE = 0.55

fun ScreenBounds.containsOcrGroundingBounds(
    ocrBounds: ScreenBounds,
    minOcrCoverage: Double = DEFAULT_OCR_GROUNDING_BOUNDS_COVERAGE,
): Boolean {
    val intersectionLeft = maxOf(left, ocrBounds.left)
    val intersectionTop = maxOf(top, ocrBounds.top)
    val intersectionRight = minOf(right, ocrBounds.right)
    val intersectionBottom = minOf(bottom, ocrBounds.bottom)
    val intersectionArea = (intersectionRight - intersectionLeft).coerceAtLeast(0) *
        (intersectionBottom - intersectionTop).coerceAtLeast(0)
    val ocrArea = (ocrBounds.right - ocrBounds.left).coerceAtLeast(0) *
        (ocrBounds.bottom - ocrBounds.top).coerceAtLeast(0)
    if (ocrArea <= 0) return false
    return intersectionArea.toDouble() / ocrArea.toDouble() >= minOcrCoverage
}

data class UiOcrGroundingHint(
    val observationId: String,
    val packageName: String?,
    val capturedAtMillis: Long?,
    val elementId: String,
    val text: String,
    val bounds: ScreenBounds,
)

data class ScreenNode(
    val id: String,
    val text: String,
    val contentDescription: String,
    val className: String,
    val bounds: ScreenBounds?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
)

data class ScreenStateSnapshot(
    val id: String,
    val packageName: String?,
    val capturedAtMillis: Long,
    val nodes: List<ScreenNode>,
    val textSummary: String,
    val truncated: Boolean,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
) {
    val nodeCount: Int get() = nodes.size
    val actionableNodeCount: Int
        get() = nodes.count { node -> node.clickable || node.editable || node.scrollable }
}

sealed class ScreenStateReadResult {
    data class Available(val snapshot: ScreenStateSnapshot) : ScreenStateReadResult()
    data class PermissionDenied(val reason: String) : ScreenStateReadResult()
    data class Failed(
        val reason: String,
        val failureKind: UiActionFailureKind = UiActionFailureKind.Unknown,
    ) : ScreenStateReadResult()
}

data class UiActionExecutionResult(
    val status: UiActionStatus,
    val before: ScreenStateSnapshot?,
    val after: ScreenStateSnapshot?,
    val summary: String,
    val retryable: Boolean,
    val failureKind: UiActionFailureKind? = null,
)

sealed class UiActionReadResult {
    data class Available(val result: UiActionExecutionResult) : UiActionReadResult()
    data class PermissionDenied(val reason: String) : UiActionReadResult()
    data class Failed(
        val reason: String,
        val retryable: Boolean = true,
        val failureKind: UiActionFailureKind = UiActionFailureKind.Unknown,
    ) : UiActionReadResult()
}

enum class UiActionStatus {
    Succeeded,
    Failed,
}

enum class UiActionFailureKind(val schemaValue: String) {
    NodeNotFound("node_not_found"),
    PageChanged("page_changed"),
    PermissionMissing("permission_missing"),
    KeyboardObscured("keyboard_obscured"),
    Timeout("timeout"),
    AppNotForeground("app_not_foreground"),
    SearchEntryNotFound("search_entry_not_found"),
    EditableNotFound("editable_not_found"),
    SubmitNotFound("submit_not_found"),
    ResultNotVerified("result_not_verified"),
    DangerousAction("dangerous_action"),
    Unknown("unknown"),
}

enum class UiTargetKind(val schemaValue: String) {
    SearchEntry("search_entry"),
    EditableField("editable_field"),
    SubmitSearch("submit_search"),
    FilterEntry("filter_entry"),
    ResultItem("result_item"),
    ScrollContainer("scroll_container"),
}

data class UiResolvedTarget(
    val kind: UiTargetKind,
    val nodeId: String?,
    val bounds: ScreenBounds?,
    val confidence: Int,
    val reason: String,
    val source: UiTargetEvidenceSource = UiTargetEvidenceSource.Accessibility,
    val fallbackType: UiTargetFallbackType = UiTargetFallbackType.None,
)

data class UiTargetScoreComponents(
    val semanticScore: Int,
    val profileHintScore: Int,
    val targetTextScore: Int,
    val actionabilityScore: Int,
    val positionScore: Int,
    val riskPenalty: Int,
    val noisePenalty: Int,
    val fallbackPenalty: Int = 0,
    val finalScore: Int,
)

data class UiTargetEvidenceCandidate(
    val nodeId: String?,
    val label: String,
    val bounds: ScreenBounds?,
    val source: UiTargetEvidenceSource = UiTargetEvidenceSource.Accessibility,
    val fallbackType: UiTargetFallbackType = UiTargetFallbackType.None,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val matchedProfileHint: String?,
    val score: UiTargetScoreComponents,
    val reason: String,
)

data class UiTargetResolutionEvidence(
    val kind: UiTargetKind,
    val target: String?,
    val packageName: String?,
    val selectedNodeId: String?,
    val rankedCandidates: List<UiTargetEvidenceCandidate>,
    val failureKind: UiActionFailureKind? = null,
)

data class AppInteractionProfile(
    val appNameAliases: Set<String>,
    val packageNames: Set<String>,
    val searchEntryHints: Set<String>,
    val submitHints: Set<String>,
    val resultHints: Set<String>,
)

data class SearchResultVerification(
    val verified: Boolean,
    val summary: String,
    val failureKind: UiActionFailureKind? = null,
    val evidence: String = "",
)

enum class UiScrollDirection(val schemaValue: String) {
    Up("up"),
    Down("down"),
    Left("left"),
    Right("right"),
    Forward("forward"),
    Backward("backward"),
    ;

    companion object {
        fun fromSchemaValue(value: String?): UiScrollDirection? =
            entries.firstOrNull { direction -> direction.schemaValue == value?.lowercase() }
    }
}

class AndroidCurrentScreenControlProvider : CurrentScreenControlProvider {
    override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult =
        SolinAccessibilityService.observeCurrentScreen(
            maxTextChars = maxTextChars.coerceIn(1, MAX_DEVICE_CONTROL_TEXT_CHARS),
            maxNodes = maxNodes.coerceIn(1, MAX_DEVICE_CONTROL_NODES),
        )

    override fun tap(target: String, timeoutMillis: Long): UiActionReadResult =
        SolinAccessibilityService.performTap(
            target = target,
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
        )

    override fun tapWithOcrGrounding(
        target: String,
        ocrGroundingHint: UiOcrGroundingHint?,
        timeoutMillis: Long,
    ): UiActionReadResult =
        SolinAccessibilityService.performTap(
            target = target,
            ocrGroundingHint = ocrGroundingHint,
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
        )

    override fun typeText(
        text: String,
        target: String?,
        timeoutMillis: Long,
        allowClipboardPasteFallback: Boolean,
    ): UiActionReadResult =
        SolinAccessibilityService.performTypeText(
            text = text.take(MAX_UI_TYPE_TEXT_CHARS),
            target = target?.trim()?.takeIf { it.isNotBlank() },
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
            allowClipboardPasteFallback = allowClipboardPasteFallback,
        )

    override fun typeTextWithOcrGrounding(
        text: String,
        target: String?,
        ocrGroundingHint: UiOcrGroundingHint?,
        timeoutMillis: Long,
        allowClipboardPasteFallback: Boolean,
    ): UiActionReadResult =
        SolinAccessibilityService.performTypeText(
            text = text.take(MAX_UI_TYPE_TEXT_CHARS),
            target = target?.trim()?.takeIf { it.isNotBlank() },
            ocrGroundingHint = ocrGroundingHint,
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
            allowClipboardPasteFallback = allowClipboardPasteFallback,
        )

    override fun submitSearch(timeoutMillis: Long): UiActionReadResult =
        SolinAccessibilityService.performSubmitSearch(
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
        )

    override fun submitSearchWithOcrGrounding(
        ocrGroundingHint: UiOcrGroundingHint?,
        timeoutMillis: Long,
    ): UiActionReadResult =
        SolinAccessibilityService.performSubmitSearch(
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
            ocrGroundingHint = ocrGroundingHint,
        )

    override fun scroll(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult =
        SolinAccessibilityService.performScroll(
            direction = direction,
            target = target?.trim()?.takeIf { it.isNotBlank() },
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
        )

    override fun pressBack(timeoutMillis: Long): UiActionReadResult =
        SolinAccessibilityService.performPressBack(
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
        )

    override fun waitForScreen(timeoutMillis: Long): UiActionReadResult =
        SolinAccessibilityService.performWait(
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
        )

    override fun tapByNormalizedCoords(
        normalizedX: Int,
        normalizedY: Int,
        timeoutMillis: Long,
    ): UiActionReadResult =
        SolinAccessibilityService.performTapByNormalizedCoords(
            normalizedX = normalizedX.coerceIn(0, 1000),
            normalizedY = normalizedY.coerceIn(0, 1000),
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
        )
}
