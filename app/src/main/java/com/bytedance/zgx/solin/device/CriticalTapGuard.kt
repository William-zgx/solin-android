package com.bytedance.zgx.solin.device

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
        else -> CriticalTapDecision.Allow
    }

    /** `ACTION_CLICK` fires the whole [container], so everything inside it must be safe to fire. */
    fun checkContainerClick(container: ScreenBounds?, windowNodes: List<ScreenNode>): CriticalTapDecision = when {
        container == null -> refuse(CriticalTapRefusalReason.MissingBounds)
        windowNodes.holdsCriticalControl(container) -> refuse(CriticalTapRefusalReason.ContainerHoldsCriticalControl)
        else -> CriticalTapDecision.Allow
    }

    private fun refuse(reason: CriticalTapRefusalReason) = CriticalTapDecision.Refuse(reason)

    private fun TappedNode.isCritical(): Boolean =
        node.hasButtonLikeCriticalLabel(windowShowsDangerText = false) ||
            viewIdResourceName?.substringAfter("id/").hasDangerousTapTargetText()
}

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

    /** An ACTION_CLICK container holds a critical control. */
    ContainerHoldsCriticalControl,

    /** The tap has no bounds to check against. */
    MissingBounds,
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
