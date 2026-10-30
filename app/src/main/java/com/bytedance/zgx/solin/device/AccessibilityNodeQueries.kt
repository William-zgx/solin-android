package com.bytedance.zgx.solin.device

import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

private const val PERCENT_DENOMINATOR = 100

/** Horizontal inset used to hit the text part of a search bar rather than its trailing icons. */
private const val SEARCH_BAR_TAP_X_OFFSET_PERCENT = 35

/**
 * Vertical inset from the top of a browser result "search bar" container down into its input row.
 * Expressed in dp because the container height scales with density — the previous raw `72` was
 * pixels, so it landed in a different row on every screen density.
 */
private const val BROWSER_SEARCH_BAR_TAP_Y_OFFSET_DP = 24f

/** How far below a search field, in anchor-heights, a submit control may still belong to it. */
private const val SUBMIT_CANDIDATE_BELOW_SPAN = 3

/** Symmetric horizontal reach, in anchor-heights, on each side of a search field. */
private const val SUBMIT_CANDIDATE_HORIZONTAL_SPAN = 4
private val SUBMIT_SEARCH_OCR_TEXT_HINTS = listOf(
    "提交搜索",
    "搜索",
    "查找",
    "前往",
    "转到",
    "确定",
    "完成",
    "search",
    "go",
    "enter",
    "done",
).map { value -> value.normalizedLookupKey() }

internal fun AccessibilityNodeInfo.findFocusedEditableForTyping(): AccessibilityNodeInfo? =
    findNodeCandidate { candidate ->
        candidate.node.isSafeEditableForTyping() && candidate.node.isFocused
    }?.node

internal fun AccessibilityNodeInfo.isSafeEditableForTyping(): Boolean =
    isEnabled && isEditable && !isPassword

/**
 * One live node considered as a click/type target, plus the transient id the model may address it by.
 *
 * [label] is the wide runtime label ([runtimeNodeSearchLabel]) — it folds in `viewIdResourceName` and
 * the class name, which is often an icon-only control's only search evidence.
 */
internal data class NodeCandidate(
    val node: AccessibilityNodeInfo,
    val id: String,
    val label: String,
) {
    /**
     * Score for this node against [target], or null when it is not a usable target.
     *
     * The arithmetic itself lives in [runtimeTargetMatchScore] — the ONE scoring core also used by
     * `UiTargetResolver`, so the offline `UiAutomatorDumpReplayTest` corpus now covers the ranking that
     * really decides where a device gets tapped. What stays here is the part only a live node can
     * supply: the transient node-id direct hit, and "clickable through an ancestor".
     */
    fun targetMatchScore(
        target: String,
        profile: AppInteractionProfile? = null,
        rootBounds: ScreenBounds? = null,
    ): Int? {
        if (!node.isEnabled) return null
        val screenNode = node.toScreenNode(id = id)
        // A model addressing an observed node id has named THE node; that is stronger evidence than any
        // text heuristic and deliberately bypasses the kind-specific minimum score. Kept ahead of the
        // shared core (which knows nothing about transient ids) rather than folded into it.
        transientNodeIdTargetMatchScore(id, target)?.let { score ->
            return score + screenNodeActionabilityScore(screenNode)
        }
        return runtimeTargetMatchScore(
            node = screenNode,
            label = label,
            target = target,
            profile = profile,
            rootBounds = rootBounds,
            // A row whose own `clickable` is false but whose parent handles the touch is still tappable
            // via [activateCandidate]; the offline resolver has no parent to walk, so this reach is
            // supplied here. Lazy because the walk costs binder round-trips and only the SubmitSearch
            // branch consults it.
            effectivelyClickable = { node.isClickable || node.clickableSelfOrAncestor() != null },
        )
    }
}

/**
 * The ONE transient node-id generator, shared by the observe side and every click-side lookup.
 *
 * Both sides must count the same thing or the ids are meaningless: observe used
 * `collectedNodes.size` (meaningful nodes only) while the click side used a counter bumped for every
 * traversed node including the structural ones observe skips, so the two sequences could never agree.
 * A model that pointed at an observed `n7_…` therefore always missed, silently fell back to text
 * matching, and could never reach an icon-only node that has no text at all. [index] is consequently
 * defined as the ordinal among nodes passing [isMeaningfulScreenNode] — see
 * [transientNodeIdTargetMatchScore] for the salt-tolerant comparison on the click side.
 */
internal fun screenNodeId(index: Int, node: AccessibilityNodeInfo, salt: String?): String {
    val base = "n${index}_${node.fingerprint().shortStableHash()}"
    return if (salt.isNullOrBlank()) base else "${base}_$salt"
}

internal fun transientNodeIdTargetMatchScore(candidateId: String, target: String): Int? {
    val rawTarget = target.trim()
    if (rawTarget == candidateId) return 1_000
    if (rawTarget.startsWith("${candidateId}_")) return 950
    val normalizedTarget = target.normalizedLookupKey()
    if (normalizedTarget.isBlank()) return null
    val normalizedId = candidateId.normalizedLookupKey()
    if (normalizedId == normalizedTarget) return 1_000
    return null
}

internal fun AccessibilityNodeInfo.findNodeCandidate(
    predicate: (NodeCandidate) -> Boolean,
): NodeCandidate? {
    var meaningfulIndex = 0
    var found: NodeCandidate? = null
    // No recycling here: the returned NodeCandidate keeps its node, and callers walk its parents
    // afterwards (clickableSelfOrAncestor). Recycling would hand back a dead node.
    walkScreenNodes(maxWalkCount = MAX_SCREEN_STATE_NODE_WALK) { node ->
        // The walker visits every node, but only nodes the observe side would have published consume
        // an index — both sides must share that basis for ids to resolve.
        val consumesIndex = node.countsTowardScreenNodeIndex()
        val candidate = NodeCandidate(
            node = node,
            id = screenNodeId(index = meaningfulIndex, node = node, salt = null),
            label = node.nodeSearchLabel(),
        )
        if (consumesIndex) meaningfulIndex += 1
        if (predicate(candidate)) {
            found = candidate
            false
        } else {
            true
        }
    }
    return found
}

internal fun AccessibilityNodeInfo.findTargetCandidate(
    target: String,
    predicate: (NodeCandidate) -> Boolean = { true },
): NodeCandidate? =
    findTargetCandidates(target = target, predicate = predicate, limit = 1).firstOrNull()

/**
 * The dismiss control of a transient overlay, if this window has one.
 *
 * Uses the strict `isOverlayDismissLabel` from `UiDangerousActionGuards` — the same predicate the
 * ToolExecutor dismiss loop uses. The runtime used to carry its own looser copy (length limit 16, plus a
 * bare `contains("关闭"|"close"|"dismiss")`), which meant a "关闭订单" / "关闭免密支付" row, or any long
 * product description mentioning 关闭, qualified as a close button and got auto-tapped. Unifying on the
 * strict predicate (short, standalone, near-exact affordance) can only ever refuse more taps, never
 * more — the fail-closed direction.
 */
internal fun AccessibilityNodeInfo.findTransientOverlayDismissCandidate(): NodeCandidate? =
    findNodeCandidate { candidate ->
        candidate.node.isEnabled &&
            (
                candidate.node.isClickable ||
                    candidate.node.clickableSelfOrAncestor() != null ||
                    candidate.node.safeBounds() != null
                ) &&
            // Deliberately NOT `candidate.label`: that wide label appends viewIdResourceName and the
            // class name, so no real node could ever satisfy the strict predicate's exact/short match.
            candidate.node.overlayLabel().isOverlayDismissLabel()
    }

/**
 * The visible label used for overlay judgments: text, else contentDescription.
 *
 * Matches `ScreenNode.overlayLabel()` on the guards side so both overlay predicates see the same string
 * for the same node.
 */
private fun AccessibilityNodeInfo.overlayLabel(): String =
    text.normalizedNodeText() ?: contentDescription.normalizedNodeText() ?: ""

/**
 * Live-node mirror of `ScreenStateSnapshot.hasDangerousActionControl`: the same per-node rule and the
 * same narrow screen-wide [dangerousActionTextMarkers] list (not the stricter single-tap list).
 */
internal fun AccessibilityNodeInfo.hasDangerousActionControlNode(): Boolean {
    var found = false
    walkScreenNodes(
        maxWalkCount = MAX_SCREEN_STATE_NODE_WALK,
        // Pure predicate; nothing is retained past the visit.
        recycleVisitedNodes = true,
    ) { node ->
        if (node.toScreenNode(id = "").hasDangerousActionControl()) found = true
        !found
    }
    return found
}

internal fun AccessibilityNodeInfo.looksLikeSearchBlockingOverlay(): Boolean {
    var markerCount = 0
    walkScreenNodes(
        maxWalkCount = MAX_SCREEN_STATE_NODE_WALK,
        // Counts label matches only; nothing is retained past the visit.
        recycleVisitedNodes = true,
    ) { node ->
        if (node.overlayLabel().hasBlockingOverlayMarker()) {
            markerCount += 1
        }
        markerCount < BLOCKING_OVERLAY_MARKER_THRESHOLD
    }
    return markerCount >= BLOCKING_OVERLAY_MARKER_THRESHOLD
}

internal fun AccessibilityNodeInfo.findTargetCandidates(
    target: String,
    predicate: (NodeCandidate) -> Boolean = { true },
    limit: Int = 5,
): List<NodeCandidate> {
    var meaningfulIndex = 0
    val candidates = mutableListOf<Pair<NodeCandidate, Int>>()
    val profile = AppInteractionProfiles.forPackage(packageName?.toString())
    val rootBounds = safeBounds()
    // No recycling: every scored candidate is returned to the caller, which taps or types into it.
    walkScreenNodes(maxWalkCount = MAX_SCREEN_STATE_NODE_WALK) { node ->
        // Same counting basis as the observe side (see [screenNodeId]): structural nodes are traversed
        // but do not consume an index.
        val consumesIndex = node.countsTowardScreenNodeIndex()
        val candidate = NodeCandidate(
            node = node,
            id = screenNodeId(index = meaningfulIndex, node = node, salt = null),
            label = node.nodeSearchLabel(),
        )
        if (consumesIndex) meaningfulIndex += 1
        val score = candidate.targetMatchScore(
            target = target,
            profile = profile,
            rootBounds = rootBounds,
        )
        if (score != null && predicate(candidate)) {
            candidates += candidate to score
        }
        true
    }
    return candidates
        .sortedByDescending { (_, score) -> score }
        .take(limit.coerceAtLeast(1))
        .map { (candidate, _) -> candidate }
}

internal fun AccessibilityNodeInfo.findSearchSubmitCandidate(
    anchorEditable: AccessibilityNodeInfo?,
): NodeCandidate? {
    val profile = AppInteractionProfiles.forPackage(packageName?.toString())
    val rootBounds = safeBounds()
    return listOf("提交搜索", "搜索", "검색", "search", "前往")
        .asSequence()
        .mapNotNull { target ->
            findTargetCandidate(target) { candidate ->
                candidate.node.isEditable.not() &&
                    (candidate.node.isClickable || candidate.node.clickableSelfOrAncestor() != null) &&
                    candidate.node.isSubmitCandidateNear(anchorEditable)
            }?.let { candidate ->
                candidate to (
                    candidate.targetMatchScore(
                        target = target,
                        profile = profile,
                        rootBounds = rootBounds,
                    ) ?: 0
                    )
            }
        }
        .maxByOrNull { (_, score) -> score }
        ?.first
}

/**
 * Whether this node is close enough to [anchorEditable] to plausibly be its submit control.
 *
 * The horizontal window is deliberately symmetric — it extends [SUBMIT_CANDIDATE_HORIZONTAL_SPAN]
 * anchor-heights on BOTH sides — so it needs no RTL mirroring: a submit button sits to the right of the
 * field in LTR and to the left in RTL, and both are accepted. Distances are expressed in anchor heights
 * rather than pixels so the tolerance scales with text size and density.
 */
private fun AccessibilityNodeInfo.isSubmitCandidateNear(anchorEditable: AccessibilityNodeInfo?): Boolean {
    val anchorBounds = anchorEditable?.safeBounds() ?: return true
    val candidateBounds = safeBounds() ?: clickableSelfOrAncestor()?.safeBounds() ?: return false
    val anchorHeight = (anchorBounds.bottom - anchorBounds.top).coerceAtLeast(1)
    val sameRow = candidateBounds.centerY in
        (anchorBounds.top - anchorHeight)..(anchorBounds.bottom + anchorHeight)
    val nearBelow = candidateBounds.top in
        anchorBounds.bottom..(anchorBounds.bottom + anchorHeight * SUBMIT_CANDIDATE_BELOW_SPAN)
    val horizontalSlack = anchorHeight * SUBMIT_CANDIDATE_HORIZONTAL_SPAN
    val horizontallyRelated =
        candidateBounds.left <= anchorBounds.right + horizontalSlack &&
            candidateBounds.right >= anchorBounds.left - horizontalSlack
    return horizontallyRelated && (sameRow || nearBelow)
}

/**
 * Breadth-first traversal of the node tree under this root.
 *
 * @param recycleVisitedNodes opt-in node recycling. Below API 33 `AccessibilityNodeInfo` instances are
 * pooled and NOT reclaimed automatically, and one `ui_tap` runs up to seven full traversals of as many
 * as [MAX_SCREEN_STATE_NODE_WALK] nodes each, so the leak is continuous on API 28-32. It cannot be
 * unconditional though: a visitor that keeps a reference (every `NodeCandidate`-producing lookup does)
 * would be handed an already-recycled node and throw `IllegalStateException` on the next read. So only
 * call sites whose visitor provably retains nothing — the pure read-out traversals — pass `true`. The
 * root itself is never recycled: it belongs to the caller, which reuses it after the walk. Children
 * already pulled into `pending` stay valid after their parent is recycled; each is an independent
 * instance.
 */
internal fun AccessibilityNodeInfo.walkScreenNodes(
    maxWalkCount: Int,
    timeBudgetMillis: Long = SCREEN_STATE_WALK_BUDGET_MILLIS,
    recycleVisitedNodes: Boolean = false,
    visitor: (AccessibilityNodeInfo) -> Boolean,
): Boolean {
    val root = this
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(root)
    var walked = 0
    val deadlineMillis = System.currentTimeMillis() + timeBudgetMillis.coerceAtLeast(100L)
    val shouldRecycle = recycleVisitedNodes && Build.VERSION.SDK_INT <= LAST_MANUAL_NODE_RECYCLE_SDK

    // Recycling has to happen on every exit path, including the early returns for budget exhaustion —
    // those are the common case on a busy screen, so leaking there would defeat the whole fix.
    fun drain(node: AccessibilityNodeInfo?) {
        if (!shouldRecycle) return
        if (node != null && node !== root) runCatching { node.recycle() }
    }

    fun drainPending() {
        if (!shouldRecycle) return
        while (pending.isNotEmpty()) drain(pending.removeFirst())
    }

    while (pending.isNotEmpty() && walked < maxWalkCount) {
        if (System.currentTimeMillis() >= deadlineMillis) {
            drainPending()
            return false
        }
        val node = pending.removeFirst()
        walked += 1
        if (!visitor(node)) {
            // The visitor asked to stop, which for a retaining visitor means it just kept this node —
            // hand ownership over untouched and only reclaim what is still queued.
            drainPending()
            return true
        }
        val childCount = runCatching { node.childCount }
            .getOrDefault(0)
            .coerceAtMost(MAX_SCREEN_NODE_CHILDREN)
        for (index in 0 until childCount) {
            if (System.currentTimeMillis() >= deadlineMillis) {
                drain(node)
                drainPending()
                return false
            }
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            pending.add(child)
            if (pending.size + walked >= maxWalkCount) {
                drain(node)
                drainPending()
                return false
            }
        }
        drain(node)
    }
    val exhausted = pending.isEmpty()
    drainPending()
    return exhausted
}

internal fun AccessibilityNodeInfo.toScreenNode(id: String): ScreenNode =
    ScreenNode(
        id = id,
        text = text.normalizedNodeText().orEmpty(),
        contentDescription = contentDescription.normalizedNodeText().orEmpty(),
        className = className?.toString().orEmpty(),
        bounds = safeBounds(),
        clickable = isClickable,
        editable = isEditable,
        scrollable = isScrollable,
        enabled = isEnabled,
    )

internal fun AccessibilityNodeInfo.isMeaningfulScreenNode(): Boolean =
    isClickable ||
        isEditable ||
        isScrollable ||
        text.normalizedNodeText().orEmpty().isNotBlank() ||
        contentDescription.normalizedNodeText().orEmpty().isNotBlank()

/**
 * Whether this node consumes a [screenNodeId] index.
 *
 * Mirrors exactly what [ScreenStateCollector.visit] appends to `collectedNodes`: invisible and
 * password nodes are dropped before the meaningfulness test there, so the click side has to drop them
 * too — otherwise the two index sequences drift apart again the moment a screen contains an offscreen
 * or password field.
 */
private fun AccessibilityNodeInfo.countsTowardScreenNodeIndex(): Boolean =
    isVisibleToUser && !isPassword && isMeaningfulScreenNode()

internal fun AccessibilityNodeInfo.nodeSearchLabel(): String =
    runtimeNodeSearchLabel(
        text = text.normalizedNodeText(),
        contentDescription = contentDescription.normalizedNodeText(),
        viewIdResourceName = viewIdResourceName,
        className = className?.toString(),
    )

internal fun AccessibilityNodeInfo.fingerprint(): String {
    val bounds = safeBounds()
    return listOf(
        className?.toString().orEmpty(),
        viewIdResourceName.orEmpty(),
        text.normalizedNodeText().orEmpty(),
        contentDescription.normalizedNodeText().orEmpty(),
        bounds?.left?.toString().orEmpty(),
        bounds?.top?.toString().orEmpty(),
        bounds?.right?.toString().orEmpty(),
        bounds?.bottom?.toString().orEmpty(),
        isClickable.toString(),
        isEditable.toString(),
        isScrollable.toString(),
    ).joinToString("|")
}

internal fun String.shortStableHash(): String =
    fold(0) { acc, char -> (acc * 31) + char.code }
        .toUInt()
        .toString(radix = 36)

internal fun AccessibilityNodeInfo.safeBounds(): ScreenBounds? {
    val rect = Rect()
    getBoundsInScreen(rect)
    if (rect.isEmpty) return null
    return ScreenBounds(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
    )
}

internal fun UiOcrGroundingHint.matchesCurrentWindow(root: AccessibilityNodeInfo): Boolean {
    val hintPackage = packageName?.takeIf { value -> value.isNotBlank() }
    val rootPackage = root.packageName?.toString()?.takeIf { value -> value.isNotBlank() }
    if (hintPackage != null && rootPackage != null && hintPackage != rootPackage) return false
    if (bounds.width() <= 0 || bounds.height() <= 0) return false
    val rootBounds = root.safeBounds() ?: return true
    return bounds.centerX in rootBounds.left..rootBounds.right &&
        bounds.centerY in rootBounds.top..rootBounds.bottom
}

internal fun UiOcrGroundingHint.matchesTargetText(target: String): Boolean {
    val normalizedText = text.normalizedLookupKey()
    val normalizedTarget = target.normalizedLookupKey()
    return normalizedText.isNotBlank() &&
        (
            normalizedText == normalizedTarget ||
                normalizedText.contains(normalizedTarget) ||
                normalizedTarget.contains(normalizedText)
            )
}

internal fun UiOcrGroundingHint.matchesSubmitSearchText(): Boolean {
    val normalizedText = text.normalizedLookupKey()
    if (normalizedText.isBlank()) return false
    return normalizedText in SUBMIT_SEARCH_OCR_TEXT_HINTS
}

internal fun AccessibilityNodeInfo.clickableSelfOrAncestor(): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = this
    repeat(MAX_SELF_OR_ANCESTOR_WALK_DEPTH) {
        val node = current ?: return null
        if (node.isEnabled && node.isClickable) return node
        current = node.parent
    }
    return null
}

/**
 * Where to tap a search bar that reports itself as neither clickable nor editable.
 *
 * Such containers only react to a touch inside their text region, so we aim at an inset rather than the
 * geometric centre (which on browser omniboxes lands on the reload/menu icons). The inset is mirrored
 * for RTL layouts: in Arabic/Hebrew the text starts at the right edge, so a fixed left inset would aim
 * at the trailing icons instead of the text — the same bug the offset exists to avoid.
 */
internal fun AccessibilityNodeInfo.searchEntryFallbackTapPoint(label: String): Pair<Int, Int>? {
    val bounds = safeBounds() ?: return null
    val normalizedLabel = label.normalizedLookupKey()
    if (isBrowserResultSearchBarLabel(normalizedLabel)) {
        val yOffset = browserSearchBarTapYOffsetPx()
            .coerceAtMost((bounds.height() - 1).coerceAtLeast(1))
        return bounds.searchBarTapX() to (bounds.top + yOffset)
    }
    if (!isNonActionableSearchBarLabel(normalizedLabel, isClickable, isEditable)) return null
    return bounds.searchBarTapX() to bounds.centerY
}

/**
 * Horizontal tap coordinate inside a search bar, [SEARCH_BAR_TAP_X_OFFSET_PERCENT] in from the edge
 * where its text begins — left for LTR, right for RTL.
 */
private fun ScreenBounds.searchBarTapX(): Int {
    val width = width()
    val offset = (width * SEARCH_BAR_TAP_X_OFFSET_PERCENT / PERCENT_DENOMINATOR)
        .coerceIn(1, (width - 1).coerceAtLeast(1))
    return if (isRtlLayout()) right - offset else left + offset
}

/**
 * Whether the device's default locale lays text out right-to-left.
 *
 * Derived from the locale rather than a node property because [AccessibilityNodeInfo] exposes no layout
 * direction; the default locale is what the app hierarchy resolves its own direction from in practice.
 */
private fun isRtlLayout(): Boolean =
    TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL

/** Density-correct px for [BROWSER_SEARCH_BAR_TAP_Y_OFFSET_DP]. */
private fun browserSearchBarTapYOffsetPx(): Int =
    (BROWSER_SEARCH_BAR_TAP_Y_OFFSET_DP * Resources.getSystem().displayMetrics.density)
        .toInt()
        .coerceAtLeast(1)

private fun isNonActionableSearchBarLabel(
    normalizedLabel: String,
    clickable: Boolean,
    editable: Boolean,
): Boolean =
    !clickable && !editable && (normalizedLabel == "搜索栏" || normalizedLabel.startsWith("搜索栏"))

internal fun AccessibilityNodeInfo.performImeSearchAction(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

/**
 * The currently-focused editable node, restricted to safe fields (enabled, editable, non-password).
 *
 * Enter/Delete deliberately require an ACTUALLY focused editable and never fall back to an arbitrary
 * first editable on screen: pressing Enter on an unintended field can submit a form, and Delete
 * mutates that field's text — acting on a field the user did not focus is a mis-operation. Password
 * fields are excluded so a system-key press can never disturb credential input.
 */
internal fun AccessibilityNodeInfo.focusedSafeEditable(): AccessibilityNodeInfo? =
    findNodeCandidate { candidate -> candidate.node.isSafeEditableForTyping() && candidate.node.isFocused }?.node

internal fun AccessibilityNodeInfo.scrollableSelfOrAncestor(): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = this
    repeat(MAX_SELF_OR_ANCESTOR_WALK_DEPTH) {
        val node = current ?: return null
        if (node.isEnabled && node.isScrollable) return node
        current = node.parent
    }
    return null
}

internal fun AccessibilityNodeInfo.scrollableSelfOrDescendant(): AccessibilityNodeInfo? {
    if (isEnabled && isScrollable) return this
    return findNodeCandidate { candidate -> candidate.node.isEnabled && candidate.node.isScrollable }?.node
}

internal fun CharSequence?.normalizedNodeText(): String? =
    this
        ?.toString()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
