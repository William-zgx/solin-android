package com.bytedance.zgx.solin.device

import kotlin.math.abs

/** Travel below which a swipe lands as a tap. */
internal const val DEGENERATE_SWIPE_MAX_TRAVEL_PX = 48

/**
 * Last line of the never-tap-critical-controls rule, as a pure decision over copied window nodes.
 *
 * Every tap the agent makes (named target, coordinate, OCR hint, search-entry focus, long-press,
 * barely-moving swipe) is checked against the live window right before the gesture, on the node it
 * will actually land on — target matching is fuzzy, so a pre-check on the requested target text alone
 * cannot be exact. `SolinAccessibilityService` only reads the windows (and the tapped node) into
 * [ScreenNode] values and throws [CriticalControlTapRefused] when this refuses.
 */
internal object CriticalTapGuard {
    /**
     * Whether a tap at absolute screen pixel ([x], [y]) may run. [tapped] is the node a named tap
     * resolved to plus its clickable ancestor (empty for a coordinate tap); [windowNodes] are the
     * visible nodes of every window and [walkComplete] is false when a walk was cut short.
     *
     * No readable window means the tap cannot be checked, and neither does a truncated walk unless it
     * saw a labelled node under the point.
     */
    fun checkTap(
        x: Int,
        y: Int,
        tapped: List<TappedNode>,
        windowNodes: List<ScreenNode>,
        walkComplete: Boolean,
    ): CriticalTapDecision = when {
        tapped.any { node -> node.isCritical() } -> refuse(CriticalTapRefusalReason.CriticalTappedNode)
        windowNodes.isEmpty() -> refuse(CriticalTapRefusalReason.UnreadableWindow)
        !walkComplete && !windowNodes.hasLabelledActionableNodeAt(x, y) ->
            refuse(CriticalTapRefusalReason.UnverifiedTruncatedWalk)
        windowNodes.tapAtPointHitsDangerousControl(x, y) -> refuse(CriticalTapRefusalReason.DangerousControlUnderPoint)
        windowNodes.windowShowsPaymentContext() && !windowNodes.tapLandsOnLabelledControl(x, y) ->
            refuse(CriticalTapRefusalReason.UnlabelledControlOnPaymentScreen)
        else -> CriticalTapDecision.Allow
    }

    /**
     * `ACTION_CLICK` fires the whole [container], so everything inside it must be safe to fire. Like
     * [checkTap], an unreadable window or a truncated walk cannot show that it is.
     */
    fun checkContainerClick(
        container: ScreenBounds?,
        windowNodes: List<ScreenNode>,
        walkComplete: Boolean,
    ): CriticalTapDecision = when {
        container == null -> refuse(CriticalTapRefusalReason.MissingBounds)
        windowNodes.isEmpty() -> refuse(CriticalTapRefusalReason.UnreadableWindow)
        !walkComplete -> refuse(CriticalTapRefusalReason.UnverifiedTruncatedWalk)
        windowNodes.holdsCriticalControl(container) -> refuse(CriticalTapRefusalReason.ContainerHoldsCriticalControl)
        else -> CriticalTapDecision.Allow
    }

    /**
     * A swipe clicks what it starts on when it barely moves, or when it starts and ends inside the
     * same actionable node (`View.onTouchEvent` clicks on release, however far it travelled). Such a
     * swipe is checked as a tap at its start point; any other swipe is left to the screen-wide check.
     */
    fun checkSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        windowNodes: List<ScreenNode>,
        walkComplete: Boolean,
    ): CriticalTapDecision {
        val degenerate = abs(endX - startX) + abs(endY - startY) < DEGENERATE_SWIPE_MAX_TRAVEL_PX
        if (!degenerate && !windowNodes.swipeStaysOnOneControl(startX, startY, endX, endY)) {
            return CriticalTapDecision.Allow
        }
        return checkTap(startX, startY, tapped = emptyList(), windowNodes = windowNodes, walkComplete = walkComplete)
    }

    /**
     * Whether an IME enter/submit may run. The action commits whatever the focused field holds — a
     * chat message, a post, a payment note — and no tap gesture happens, so [checkTap] never sees
     * it. [editableBounds] is the focused field, [windowNodes] are the visible nodes of every
     * window, [walkComplete] false means the walk was cut short, and [imeActionLabel] is the label
     * the platform reports for the IME action (null when unavailable).
     *
     * Refused when the IME action itself is named like a critical control (a keyboard key labelled
     * 发送 / Send), or when a critical send / publish / pay control sits beside the field. A search
     * box next to a 搜索 button is allowed: that is the benchmark's submit path.
     */
    fun checkImeAction(
        editableBounds: ScreenBounds?,
        windowNodes: List<ScreenNode>,
        walkComplete: Boolean,
        imeActionLabel: String?,
    ): CriticalTapDecision = when {
        imeActionLabel.isCriticalTapLabel(windowShowsDangerText = false) ->
            refuse(CriticalTapRefusalReason.CriticalImeActionLabel)
        editableBounds == null -> refuse(CriticalTapRefusalReason.MissingBounds)
        windowNodes.isEmpty() -> refuse(CriticalTapRefusalReason.UnreadableWindow)
        !walkComplete -> refuse(CriticalTapRefusalReason.UnverifiedTruncatedWalk)
        windowNodes.criticalControlNearInput(editableBounds) ->
            refuse(CriticalTapRefusalReason.CriticalControlNearInput)
        else -> CriticalTapDecision.Allow
    }

    private fun refuse(reason: CriticalTapRefusalReason) = CriticalTapDecision.Refuse(reason)

    /** Labels at any length; the view id only for a non-editable node, by whole token. */
    private fun TappedNode.isCritical(): Boolean =
        node.hasCriticalTapLabel(windowShowsDangerText = false) ||
            (!node.editable && viewIdResourceName.isCriticalViewId())
}

/**
 * Whether a view id names a critical control: a whole token of the entry name is a tap marker
 * (`btn_send`, `payButton`, `confirm-order`), split on `_`, `-`, `.` and camelCase. Word-start
 * matching refused rows such as `sender_name`; "sender" is not a marker token. `order` and `post`
 * are also nouns, so `order_item` and `post_card` (a row or card naming its content) pass unless a
 * control token says otherwise (`order_item_btn`). Verb tokens (send, pay, delete, …) always refuse.
 */
internal fun String?.isCriticalViewId(): Boolean {
    val entry = this?.substringAfter("id/")?.takeIf { it.isNotBlank() } ?: return false
    val tokens = entry.replace(VIEW_ID_CAMEL_BOUNDARY_REGEX, "$1_$2").lowercase().split(VIEW_ID_SEPARATOR_REGEX)
        .filter { it.isNotEmpty() }
    val critical = tokens.mapNotNull { token -> token.criticalViewIdToken() }
    if (critical.isEmpty()) return false
    val nounRow = critical.all { token -> token in viewIdNounTokens } &&
        tokens.any { token -> token in viewIdRowTokens } &&
        tokens.none { token -> token in viewIdControlTokens }
    return !nounRow
}

/** The marker a token names: itself, or an unsplit compound with a control word (`sendbtn`, `btnpay`). */
private fun String.criticalViewIdToken(): String? {
    if (this in viewIdCriticalTokens) return this
    return viewIdCriticalTokens.firstOrNull { marker ->
        viewIdControlTokens.any { control -> this == marker + control || this == control + marker }
    }
}

/** Tap markers as whole id tokens, plus the CJK markers' usual English ids. */
private val viewIdCriticalTokens = setOf(
    "send", "pay", "payment", "buy", "purchase", "checkout", "delete", "remove", "post", "publish",
    "order", "submit", "confirm", "transfer", "authorize", "authorise", "grant", "allow", "agree",
)

/** Critical tokens that are also nouns for content ("an order", "a post"). */
private val viewIdNounTokens = setOf("order", "post")

/** Tokens that mark a list row or content cell rather than a button. */
private val viewIdRowTokens = setOf("item", "row", "cell", "card", "list", "title", "info", "detail")

/** Tokens that mark a control even inside a row-like id (`order_item_btn`). */
private val viewIdControlTokens = setOf("btn", "button", "action")

private val VIEW_ID_CAMEL_BOUNDARY_REGEX = Regex("([a-z0-9])([A-Z])")

private val VIEW_ID_SEPARATOR_REGEX = Regex("""[_\-.\s]+""")

/** A node a named tap resolved to, with the view id the window walk does not keep. */
internal data class TappedNode(val node: ScreenNode, val viewIdResourceName: String?)

internal enum class CriticalTapRefusalReason {
    /** The tapped node or its clickable ancestor has a critical label or view id. */
    CriticalTappedNode,

    /** No window could be read, so the tap cannot be checked. */
    UnreadableWindow,

    /** The walk was cut short and saw no labelled actionable node under the point. */
    UnverifiedTruncatedWalk,

    /** A critical control sits under the point (see [tapAtPointHitsDangerousControl]). */
    DangerousControlUnderPoint,

    /**
     * The window shows a payment, transfer, or authorization step ([windowShowsPaymentContext]) and
     * what the tap lands on has no label, so it could be a custom-drawn pay or keypad control.
     */
    UnlabelledControlOnPaymentScreen,

    /** An ACTION_CLICK container holds a critical control. */
    ContainerHoldsCriticalControl,

    /** The tap has no bounds to check against. */
    MissingBounds,

    /** The IME action itself is named like a critical control (a send key). */
    CriticalImeActionLabel,

    /** A critical send / publish / pay control sits beside the focused input. */
    CriticalControlNearInput,
}

internal sealed interface CriticalTapDecision {
    object Allow : CriticalTapDecision

    data class Refuse(val reason: CriticalTapRefusalReason) : CriticalTapDecision
}

internal fun CriticalTapDecision.throwIfRefused() {
    if (this is CriticalTapDecision.Refuse) throw CriticalControlTapRefused(reason)
}

internal const val CRITICAL_CONTROL_TAP_REFUSED_MESSAGE =
    "这一步会点击支付、发送、删除、发布、下单、转账或授权类控件，需要你亲自操作。"

/** Thrown before a tap that would activate a critical control; ends the UI action as DangerousAction. */
internal class CriticalControlTapRefused(val reason: CriticalTapRefusalReason) :
    RuntimeException("critical control tap refused: $reason")
