package com.bytedance.zgx.solin.device

/**
 * Dangerous-action keywords, defined once here and used at two strengths:
 *
 * - [dangerousActionTextMarkers] — screen-wide guards. Any enabled actionable node whose label
 *   matches blocks the actions that can commit anything on screen (submit, Enter, swipe,
 *   long-press), so this list stays narrow (payment, delete, send, publish, order, grant).
 * - [dangerousTapTargetMarkers] — whether one tap (named target or coordinate) would activate a
 *   critical control, which the agent never does itself. It is the screen list plus generic
 *   submit/confirm verbs, so it only ever stops more often (fail closed).
 *
 * Only ever add to these lists. What keeps them usable: [benignDangerPhrases] are removed before
 * matching, and Latin markers only match at the start of a word ("payment" yes, "display" no).
 */
internal val dangerousActionTextMarkers: List<String> = listOf(
    "支付",
    "付款",
    "转账",
    "下单",
    "提交订单",
    "购买",
    "立即购买",
    "删除",
    "发送",
    "发布",
    "发表",
    "授权",
    "允许",
    "同意",
)

internal val dangerousTapTargetMarkers: List<String> = dangerousActionTextMarkers + listOf(
    "提交",
    "确认",
    "send",
    "delete",
    "pay",
    "transfer",
    "submit",
    "post",
    "publish",
    "buy",
    "order",
    "confirm",
)

private val strongDangerousActionTextMarkers = listOf(
    "确认支付",
    "立即支付",
    "确认付款",
    "立即付款",
    "确认转账",
    "确认删除",
    "删除此",
    "发送",
    "发布",
    "发表",
    "授权登录",
    "确认授权",
    "同意授权",
    "提交订单",
    "确认下单",
    "立即购买",
)

private val standaloneDangerousActionTextMarkers = listOf(
    "支付",
    "付款",
    "转账",
    "下单",
    "购买",
    "删除",
    "发送",
    "发布",
    "发表",
    "授权",
    "允许",
    "同意",
)

/** Everyday phrases that contain a marker but name no action: dates, sort tabs, sales counts, an app. */
private val benignDangerPhrases = listOf("发布于", "发表于", "发布时间", "最新发布", "发布会", "人付款", "支付宝")

/** Longer labels read as content (a list row, a description), not as a button. */
private const val BUTTON_LABEL_MAX_CHARS = 16

/** Longest text that can be a dialog's critical question rather than page content. */
private const val DIALOG_TEXT_MAX_CHARS = 24

private val CAMEL_CASE_BOUNDARY_REGEX = Regex("([a-z])([A-Z])")

private fun String?.normalizedForDangerMatch(): String =
    benignDangerPhrases.fold(normalizedLookupKey()) { text, phrase -> text.replace(phrase, "") }

/**
 * Bare confirm buttons ("确定", "OK", …). Harmless on their own, but on a window that also shows a
 * critical phrase ("确定删除聊天记录?") they are the second step of that critical action.
 */
private val genericConfirmLabels = setOf("确定", "确认", "好", "好的", "是", "继续", "ok", "yes")

internal fun String?.isGenericConfirmLabel(): Boolean = normalizedLookupKey() in genericConfirmLabels

internal fun String?.hasDangerousActionText(): Boolean = containsAnyMarker(dangerousActionTextMarkers)

/** Whether a single `ui_tap` target needs confirmation; stricter than [hasDangerousActionText]. */
internal fun String?.hasDangerousTapTargetText(): Boolean = containsAnyMarker(dangerousTapTargetMarkers)

private fun String?.containsAnyMarker(markers: List<String>): Boolean {
    val normalized = normalizedForDangerMatch()
    if (normalized.isBlank()) return false
    val words by lazy { orEmpty().replace(CAMEL_CASE_BOUNDARY_REGEX, "$1 $2").lowercase() }
    return markers.any { marker ->
        if (marker.first().isLatinLetter()) {
            Regex("(?<![a-z])$marker").containsMatchIn(words)
        } else {
            normalized.contains(marker.normalizedLookupKey())
        }
    }
}

private fun Char.isLatinLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

internal fun String?.hasOcrDangerousActionText(): Boolean {
    val normalized = normalizedForDangerMatch()
    if (normalized.isBlank()) return false
    if (hasStrongDangerousActionText()) return true
    return standaloneDangerousActionTextMarkers.any { marker ->
        normalized == marker.normalizedLookupKey()
    }
}

internal fun String?.hasStrongDangerousActionText(): Boolean {
    val normalized = normalizedForDangerMatch()
    if (normalized.isBlank()) return false
    return strongDangerousActionTextMarkers.any { marker ->
        normalized.contains(marker.normalizedLookupKey())
    }
}

internal fun ScreenStateSnapshot.hasDangerousActionControl(): Boolean =
    nodes.any { node -> node.hasDangerousActionControl() }

/**
 * Whether tapping [target] (a node id or label) would activate a critical control. The target text
 * itself is checked with the strict tap list, then every node the target can name (same id, or the
 * same label) — so "search-entry" on a chat screen is fine while "send-button" labelled 发送 is not.
 */
internal fun ScreenStateSnapshot.tapTargetHitsDangerousControl(target: String): Boolean {
    val dangerText = nodes.windowShowsDangerText()
    if (target.isCriticalTapLabel(dangerText)) return true
    val key = target.normalizedLookupKey()
    if (key.isBlank()) return false
    return nodes.any { node ->
        (node.id == target || node.tapLabels().any { label -> label.normalizedLookupKey() == key }) &&
            node.hasButtonLikeCriticalLabel(dangerText)
    }
}

/**
 * Whether a normalized (0-1000) tap point lands on a critical control (see
 * [tapAtPointHitsDangerousControl]). Without a screen size the point cannot be resolved, so this
 * falls back to the screen-wide check (fail closed).
 */
internal fun ScreenStateSnapshot.normalizedPointHitsDangerousControl(xNorm: Int, yNorm: Int): Boolean {
    val width = widthPx?.takeIf { it > 0 } ?: return hasDangerousActionControl()
    val height = heightPx?.takeIf { it > 0 } ?: return hasDangerousActionControl()
    return nodes.tapAtPointHitsDangerousControl(width * xNorm / 1000, height * yNorm / 1000)
}

/**
 * Whether a tap at absolute screen pixel ([x], [y]) would activate a critical control. Shared by
 * the executor preflight (on a snapshot) and the accessibility service (on live nodes, right
 * before the gesture), so both stop the same taps.
 *
 * Nodes do not expose z-order, so any clickable control under the point with a critical label
 * refuses the tap, even a larger one: a sheet button may be drawn over a smaller icon behind it.
 * Past that, the innermost actionable node is what the tap activates. An editable field is safe
 * (focusing never commits); anything else is critical when a button-like label sits inside it (an
 * unlabelled container around "立即支付"). When nothing actionable covers the point the tap cannot
 * be attributed, so the screen-wide check decides (fail closed).
 */
internal fun List<ScreenNode>.tapAtPointHitsDangerousControl(x: Int, y: Int): Boolean {
    val dangerText = windowShowsDangerText()
    val covering = actionableNodesAt(x, y)
    if (covering.isEmpty()) return any { node -> node.hasDangerousActionControl() }
    if (covering.any { node -> !node.editable && node.hasButtonLikeCriticalLabel(dangerText) }) return true
    val hit = covering.minBy { node -> node.bounds!!.area() }
    return !hit.editable && holdsCriticalControl(hit.bounds!!, dangerText)
}

/** Whether a labelled node inside [container] reads like a critical button. */
internal fun List<ScreenNode>.holdsCriticalControl(
    container: ScreenBounds,
    dangerText: Boolean = windowShowsDangerText(),
): Boolean = any { node -> node.bounds?.isInside(container) == true && node.hasButtonLikeCriticalLabel(dangerText) }

/** Whether the node a truncated walk found under the point can be trusted to stand for the tap. */
internal fun List<ScreenNode>.hasLabelledActionableNodeAt(x: Int, y: Int): Boolean =
    actionableNodesAt(x, y).minByOrNull { node -> node.bounds!!.area() }?.tapLabels()?.isNotEmpty() == true

private fun List<ScreenNode>.actionableNodesAt(x: Int, y: Int): List<ScreenNode> =
    filter { node -> node.enabled && (node.clickable || node.editable) && node.bounds.covers(x, y) }

internal fun ScreenNode.tapLabels(): List<String> = listOf(text, contentDescription).filter { it.isNotBlank() }

internal fun ScreenNode.tapLabel(): String = text.ifBlank { contentDescription }

internal fun ScreenNode.hasButtonLikeCriticalLabel(windowShowsDangerText: Boolean): Boolean =
    tapLabels().any { label -> label.isButtonLikeCriticalLabel(windowShowsDangerText) }

internal fun String?.isButtonLikeCriticalLabel(windowShowsDangerText: Boolean): Boolean =
    orEmpty().length <= BUTTON_LABEL_MAX_CHARS && isCriticalTapLabel(windowShowsDangerText)

/** A tap label is critical on its own, or a bare confirm on a window showing critical text. */
internal fun String?.isCriticalTapLabel(windowShowsDangerText: Boolean): Boolean =
    hasDangerousTapTargetText() || (windowShowsDangerText && isGenericConfirmLabel())

internal fun List<ScreenNode>.windowShowsDangerText(): Boolean =
    any { node ->
        node.tapLabels().any { label -> label.length <= DIALOG_TEXT_MAX_CHARS && label.hasDangerousActionText() }
    }

private fun ScreenBounds?.covers(x: Int, y: Int): Boolean =
    this != null && x in left..right && y in top..bottom

private fun ScreenBounds.isInside(outer: ScreenBounds): Boolean =
    left >= outer.left && top >= outer.top && right <= outer.right && bottom <= outer.bottom

private fun ScreenBounds.area(): Long = (right - left).toLong() * (bottom - top)

/** Shared with the accessibility service's own overlay-dismiss path, which walks live nodes. */
internal fun ScreenNode.hasDangerousActionControl(): Boolean {
    if (!enabled || editable) return false
    if (!clickable && !scrollable) return false
    return tapLabels().any { label -> label.hasDangerousActionText() }
}

// ── Blocking overlay / interstitial detection ────────────────────────────────────────────────────
//
// ONE definition, shared by all three consumers: the ToolExecutor dismiss loop, the search-focus loop
// inside SolinAccessibilityService, and the snapshot-level guards below. The accessibility service used
// to keep a looser private copy (dismiss-label length limit 16 instead of 8, plus a bare
// `contains("关闭"|"close"|"dismiss")` that no exactness check gated) — so the runtime, the only one
// that actually taps, was the most willing to treat arbitrary content text as a close button. Both are
// now the strict version; a stricter predicate can only refuse taps, which is the fail-closed direction.

private val blockingOverlayMarkers = listOf(
    "优惠券",
    "立即购买",
    "倒计时",
    "限时抢购",
    "专属权益",
    "已获得",
    "红包",
    "弹窗",
    // App-update dialogs.
    "新版本",
    "版本更新",
    "立即更新",
    "立即升级",
    "更新内容",
    // Splash / interstitial ads (the regulated "tap to open details or a third-party app" copy).
    "跳过广告",
    "跳转详情",
    "第三方应用",
    "摇一摇",
)

private val overlayDismissLabels = listOf(
    "关闭",
    "取消",
    "跳过",
    "稍后",
    "暂不",
    "我知道了",
    "不感兴趣",
    // Update / rating / notification-permission nags.
    "以后再说",
    "下次再说",
    "稍后再说",
    "稍后提醒",
    "暂不更新",
    "暂不升级",
    // Ads and coupon pop-ups.
    "跳过广告",
    "残忍拒绝",
)

/**
 * A splash-ad skip control carrying its countdown: "跳过 3", "3s 跳过", "跳过广告 5秒", "Skip 5".
 *
 * Still an exact match — the whole normalized label must be a skip word plus a one/two-digit countdown
 * (optionally suffixed s/秒) — so "跳过3天" or "跳过此步骤3" never qualify. The countdown makes the label
 * change every second, which is why it cannot be listed verbatim in [overlayDismissLabels].
 */
private val countdownSkipLabelRegex =
    Regex("""^(?:(?:跳过广告|跳过|skipad|skip)\d{1,2}(?:s|秒)?|\d{1,2}(?:s|秒)?(?:跳过广告|跳过|skipad|skip))$""")

private fun String.isCountdownSkipLabel(): Boolean = countdownSkipLabelRegex.matches(this)

/** How many blocking-overlay markers a screen must show before it counts as occluded. */
internal const val BLOCKING_OVERLAY_MARKER_THRESHOLD = 2

/**
 * Longest a string can be and still plausibly be a standalone close/skip affordance.
 *
 * Deliberately tight: real close controls are 2-4 characters ("关闭", "我知道了", "skip"). The looser
 * limit the accessibility service used to apply let "关闭免密支付" through as a dismiss target.
 */
private const val MAX_OVERLAY_DISMISS_LABEL_LENGTH = 8

internal fun String?.isOverlayDismissLabel(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank() || normalized.length > MAX_OVERLAY_DISMISS_LABEL_LENGTH) return false
    // Require an (almost) exact close/skip affordance, not a substring of longer content text.
    // A real dialog close control is a short standalone label; matching substrings of long text
    // (e.g. a product description that happens to contain 关闭/跳过) caused false-positive taps.
    if (overlayDismissLabels.any { label -> normalized == label.normalizedLookupKey() }) return true
    if (normalized.isCountdownSkipLabel()) return true
    val exactAscii = setOf("close", "dismiss", "skip", "x", "×")
    return normalized in exactAscii
}

internal fun String?.hasBlockingOverlayMarker(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    // A counting-down skip control only exists on splash/interstitial ads, so it is itself a marker.
    if (normalized.isCountdownSkipLabel()) return true
    return blockingOverlayMarkers.any { marker -> normalized.contains(marker.normalizedLookupKey()) }
}

private fun ScreenNode.overlayLabel(): String = text.ifBlank { contentDescription }

/**
 * Heuristic: the screen carries a promotional/interstitial overlay that occludes real content.
 * True when at least [BLOCKING_OVERLAY_MARKER_THRESHOLD] nodes match blocking-overlay markers
 * (coupon/countdown/red-packet/…), the same threshold the search-focus loop's
 * `looksLikeSearchBlockingOverlay` applies.
 */
internal fun ScreenStateSnapshot.hasBlockingOverlay(): Boolean {
    val markerCount = nodes.count { node -> node.overlayLabel().hasBlockingOverlayMarker() }
    return markerCount >= BLOCKING_OVERLAY_MARKER_THRESHOLD
}

/**
 * The dismiss target for a blocking overlay, if one is present: an enabled, actionable node whose
 * label is a close/skip affordance. Returns null when there is no overlay or no safe close control.
 * Fail-closed: never returns a target on a screen that also carries a dangerous-action control —
 * the caller must additionally route the tap through the dangerous-action preflight.
 */
internal fun ScreenStateSnapshot.blockingOverlayDismissTarget(): ScreenNode? {
    if (!hasBlockingOverlay()) return null
    if (hasDangerousActionControl()) return null
    return nodes.firstOrNull { node ->
        node.enabled &&
            node.clickable &&
            node.overlayLabel().isOverlayDismissLabel()
    }
}
