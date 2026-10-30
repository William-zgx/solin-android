package com.bytedance.zgx.solin.device

/**
 * Dangerous-action keywords, defined once here and used at two strengths:
 *
 * - [dangerousActionTextMarkers] — screen-wide guards. Any enabled actionable node whose label
 *   matches blocks every automatic UI action on that screen, so this list stays narrow (payment,
 *   delete, send, publish, order, grant) to keep low-risk app search usable on shopping/map apps.
 * - [dangerousTapTargetMarkers] — whether one `ui_tap` target may skip confirmation
 *   (`ToolRegistry.isLowRiskDeviceActionConfirmationSkippable`). It is the screen list plus generic
 *   submit/confirm verbs, so it only ever asks for confirmation more often (fail closed).
 *
 * Only ever add to these lists.
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
    "授权",
    "允许",
    "同意",
)

internal fun String?.hasDangerousActionText(): Boolean = containsAnyMarker(dangerousActionTextMarkers)

/** Whether a single `ui_tap` target needs confirmation; stricter than [hasDangerousActionText]. */
internal fun String?.hasDangerousTapTargetText(): Boolean = containsAnyMarker(dangerousTapTargetMarkers)

private fun String?.containsAnyMarker(markers: List<String>): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    return markers.any { marker -> normalized.contains(marker.normalizedLookupKey()) }
}

internal fun String?.hasOcrDangerousActionText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    if (hasStrongDangerousActionText()) return true
    return standaloneDangerousActionTextMarkers.any { marker ->
        normalized == marker.normalizedLookupKey()
    }
}

internal fun String?.hasStrongDangerousActionText(): Boolean {
    val normalized = normalizedLookupKey()
    if (normalized.isBlank()) return false
    return strongDangerousActionTextMarkers.any { marker ->
        normalized.contains(marker.normalizedLookupKey())
    }
}

internal fun ScreenStateSnapshot.hasDangerousActionControl(): Boolean =
    nodes.any { node -> node.hasDangerousActionControl() }

/** Shared with the accessibility service's own overlay-dismiss path, which walks live nodes. */
internal fun ScreenNode.hasDangerousActionControl(): Boolean {
    if (!enabled) return false
    if (!clickable && !editable && !scrollable) return false
    val label = text.ifBlank { contentDescription }
    return label.hasDangerousActionText()
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
