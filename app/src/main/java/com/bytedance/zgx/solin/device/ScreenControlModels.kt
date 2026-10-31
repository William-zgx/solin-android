package com.bytedance.zgx.solin.device

const val DEVICE_CONTROL_SOURCE_ACCESSIBILITY = "accessibility_active_window"
const val DEVICE_CONTROL_METADATA_POLICY =
    "accessibility_control_local_only_transient_node_ids_no_pixels_persisted"

/**
 * Privacy policy advertised by screen-*text* reads (as opposed to screen *control* results above).
 *
 * Declared here beside its control-path sibling so the tool output schemas
 * (tool/ToolSchemasUi.kt) and the emitting call sites (tool/ToolExecutor.kt) interpolate one
 * shared constant. It previously existed only as a repeated string literal, which is exactly how
 * an advertised schema and the metadata actually emitted drift apart.
 */
const val CURRENT_SCREEN_TEXT_LOCAL_ONLY_POLICY =
    "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted"

const val DEFAULT_DEVICE_CONTROL_MAX_TEXT_CHARS = 2_000
const val MAX_DEVICE_CONTROL_TEXT_CHARS = 4_000
const val DEFAULT_DEVICE_CONTROL_MAX_NODES = 50
const val MAX_DEVICE_CONTROL_NODES = 120
const val DEFAULT_UI_ACTION_TIMEOUT_MILLIS = 1_000L
const val MIN_UI_ACTION_TIMEOUT_MILLIS = 100L

/**
 * Largest `timeoutMillis` a UI action may request.
 *
 * Not independent of the tool-execution boundary. The accessibility service wraps each action in a
 * watchdog of `requested + ~9s` (two full-tree observes, the settle wait, slack), and the two
 * preflight observes plus the readiness poll add ~7.5s before that window opens — all billed to
 * DEVICE_CONTROL_TOOL_EXECUTION_TIMEOUT_MILLIS. At this ceiling the worst case is ~26.5s, inside
 * that 30s budget; against the shared 20s default it would not fit, which is why device-control
 * tools have their own boundary. UiActionTimeoutBudgetTest pins the arithmetic.
 */
const val MAX_UI_ACTION_TIMEOUT_MILLIS = 10_000L
const val MAX_UI_TYPE_TEXT_CHARS = 2_000
const val DEFAULT_UI_GESTURE_DURATION_MILLIS = 300L
const val MIN_UI_GESTURE_DURATION_MILLIS = 20L
const val MAX_UI_GESTURE_DURATION_MILLIS = 3_000L
const val DEFAULT_UI_LONG_PRESS_HOLD_MILLIS = 600L
const val MIN_UI_LONG_PRESS_HOLD_MILLIS = 300L
const val MAX_UI_LONG_PRESS_HOLD_MILLIS = 3_000L
const val MIN_NORMALIZED_COORD = 0
const val MAX_NORMALIZED_COORD = 1_000

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

    /**
     * Swipe between two normalized 0-1000 points over [durationMillis].
     *
     * Resolution-agnostic like [tapByNormalizedCoords]: (500, 500) is screen center. Used for
     * carousels, map drags, pull-to-refresh, and side-swipe surfaces that have no scrollable
     * accessibility container for [scroll] to target. Default impl delegates to nothing so test
     * fakes remain source-compatible; the production provider overrides it.
     */
    fun swipe(
        startXNorm: Int,
        startYNorm: Int,
        endXNorm: Int,
        endYNorm: Int,
        durationMillis: Long = DEFAULT_UI_GESTURE_DURATION_MILLIS,
        timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS,
    ): UiActionReadResult =
        UiActionReadResult.Failed(
            reason = "swipe not supported by this provider",
            retryable = false,
            failureKind = UiActionFailureKind.Unknown,
        )

    /**
     * Long-press at a normalized 0-1000 point, holding for [holdMillis]. Default impl is a no-op
     * failure so test fakes stay source-compatible.
     */
    fun longPress(
        xNorm: Int,
        yNorm: Int,
        holdMillis: Long = DEFAULT_UI_LONG_PRESS_HOLD_MILLIS,
        timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS,
    ): UiActionReadResult =
        UiActionReadResult.Failed(
            reason = "long press not supported by this provider",
            retryable = false,
            failureKind = UiActionFailureKind.Unknown,
        )

    /**
     * Press a whitelisted system key ([UiSystemKey]). Never accepts an arbitrary keycode — the
     * enum is the injection-resistant boundary. Default impl is a no-op failure for test fakes.
     */
    fun pressKey(
        key: UiSystemKey,
        timeoutMillis: Long = DEFAULT_UI_ACTION_TIMEOUT_MILLIS,
    ): UiActionReadResult =
        UiActionReadResult.Failed(
            reason = "press key not supported by this provider",
            retryable = false,
            failureKind = UiActionFailureKind.Unknown,
        )

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
    /**
     * Short factual notes about the app's UI for planner prompts (see [appUiNotesFor]). Facts only,
     * never instructions; not used by scoring or verification.
     */
    val uiNotes: List<String> = emptyList(),
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

/**
 * Whitelisted system keys the agent may press. This closed enum — not a raw keycode — is the
 * injection-resistant boundary: the model can never request an arbitrary key event.
 */
enum class UiSystemKey(val schemaValue: String) {
    Home("home"),
    Recents("recents"),
    Enter("enter"),
    Delete("delete"),
    ;

    companion object {
        fun fromSchemaValue(value: String?): UiSystemKey? =
            entries.firstOrNull { key -> key.schemaValue == value?.lowercase() }
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

    override fun swipe(
        startXNorm: Int,
        startYNorm: Int,
        endXNorm: Int,
        endYNorm: Int,
        durationMillis: Long,
        timeoutMillis: Long,
    ): UiActionReadResult =
        SolinAccessibilityService.performSwipe(
            startXNorm = startXNorm.coerceIn(MIN_NORMALIZED_COORD, MAX_NORMALIZED_COORD),
            startYNorm = startYNorm.coerceIn(MIN_NORMALIZED_COORD, MAX_NORMALIZED_COORD),
            endXNorm = endXNorm.coerceIn(MIN_NORMALIZED_COORD, MAX_NORMALIZED_COORD),
            endYNorm = endYNorm.coerceIn(MIN_NORMALIZED_COORD, MAX_NORMALIZED_COORD),
            durationMillis = durationMillis.coerceIn(MIN_UI_GESTURE_DURATION_MILLIS, MAX_UI_GESTURE_DURATION_MILLIS),
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
        )

    override fun longPress(
        xNorm: Int,
        yNorm: Int,
        holdMillis: Long,
        timeoutMillis: Long,
    ): UiActionReadResult =
        SolinAccessibilityService.performLongPress(
            xNorm = xNorm.coerceIn(MIN_NORMALIZED_COORD, MAX_NORMALIZED_COORD),
            yNorm = yNorm.coerceIn(MIN_NORMALIZED_COORD, MAX_NORMALIZED_COORD),
            holdMillis = holdMillis.coerceIn(MIN_UI_LONG_PRESS_HOLD_MILLIS, MAX_UI_LONG_PRESS_HOLD_MILLIS),
            timeoutMillis = timeoutMillis.coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS),
        )

    override fun pressKey(key: UiSystemKey, timeoutMillis: Long): UiActionReadResult =
        SolinAccessibilityService.performPressKey(
            key = key,
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
