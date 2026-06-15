package com.bytedance.zgx.pocketmind.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val MAX_SCREEN_TEXT_NODE_COUNT = 120
private const val MAX_SCREEN_STATE_NODE_WALK = 240
private const val DEFAULT_POST_ACTION_WAIT_MILLIS = 250L

class PocketMindAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService?.get() === this) {
            activeService = null
        }
        super.onDestroy()
    }

    private fun readSnapshot(maxChars: Int): CurrentScreenTextReadResult {
        val root = rootInActiveWindow
            ?: return CurrentScreenTextReadResult.Failed("当前屏幕没有可访问文本根节点")
        return runCatching {
            CurrentScreenTextReadResult.Available(
                root.toCurrentScreenTextSnapshot(
                    maxChars = maxChars,
                    capturedAtMillis = System.currentTimeMillis(),
                ),
            )
        }.getOrElse {
            CurrentScreenTextReadResult.Failed("当前屏幕文本读取失败")
        }
    }

    private fun observeSnapshot(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
        val root = rootInActiveWindow
            ?: return ScreenStateReadResult.Failed("当前屏幕没有可访问节点根节点")
        return runCatching {
            ScreenStateReadResult.Available(
                root.toScreenStateSnapshot(
                    maxTextChars = maxTextChars,
                    maxNodes = maxNodes,
                    capturedAtMillis = System.currentTimeMillis(),
                ),
            )
        }.getOrElse {
            ScreenStateReadResult.Failed("当前屏幕状态读取失败")
        }
    }

    private fun tapTarget(target: String, timeoutMillis: Long): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            val root = rootInActiveWindow
                ?: return@executeUiAction UiPrimitiveResult.failed(
                    reason = "当前屏幕没有可访问节点根节点",
                    failureKind = UiActionFailureKind.PageChanged,
                )
            val match = root.findTargetCandidate(target)
                ?: return@executeUiAction UiPrimitiveResult.failed(
                    reason = "未找到可点击目标：$target",
                    failureKind = UiActionFailureKind.NodeNotFound,
                )
            val clickNode = match.node.clickableSelfOrAncestor()
            val gestureBounds = match.node.safeBounds() ?: clickNode?.safeBounds()
            val gesturePerformed = gestureBounds
                ?.let { bounds -> dispatchTapGesture(bounds.centerX, bounds.centerY) }
                ?: false
            val performed = gesturePerformed ||
                clickNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (performed) {
                UiPrimitiveResult.succeeded("已点击目标：${match.label}")
            } else {
                UiPrimitiveResult.failed(
                    reason = "目标不可点击：${match.label}",
                    failureKind = UiActionFailureKind.NodeNotFound,
                )
            }
        }

    private fun typeText(text: String, target: String?, timeoutMillis: Long): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            if (text.isBlank()) {
                return@executeUiAction UiPrimitiveResult.failed(
                    reason = "输入文本不能为空",
                    retryable = false,
                    failureKind = UiActionFailureKind.Unknown,
                )
            }
            val root = rootInActiveWindow
                ?: return@executeUiAction UiPrimitiveResult.failed(
                    reason = "当前屏幕没有可访问节点根节点",
                    failureKind = UiActionFailureKind.PageChanged,
                )
            val targetNode = target
                ?.let { query -> root.findTargetCandidate(query)?.node }
            targetNode?.safeBounds()?.let { bounds ->
                dispatchTapGesture(bounds.centerX, bounds.centerY)
                sleepForUiIdle(timeoutMillis.coerceAtMost(DEFAULT_POST_ACTION_WAIT_MILLIS))
            }
            val editableNode = targetNode?.takeIf { it.isEditable } ?: root.findNodeCandidate { it.node.isEditable }?.node
                ?: return@executeUiAction UiPrimitiveResult.failed(
                    reason = "当前屏幕没有可输入文本框",
                    failureKind = UiActionFailureKind.NodeNotFound,
                )
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            val performed = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (performed) {
                UiPrimitiveResult.succeeded("已向输入框写入 ${text.length} 个字符")
            } else {
                UiPrimitiveResult.failed(
                    reason = "输入框不支持直接写入文本",
                    failureKind = UiActionFailureKind.KeyboardObscured,
                )
            }
        }

    private fun scrollTarget(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            val root = rootInActiveWindow
                ?: return@executeUiAction UiPrimitiveResult.failed(
                    reason = "当前屏幕没有可访问节点根节点",
                    failureKind = UiActionFailureKind.PageChanged,
                )
            val scrollableNode = target
                ?.let { query ->
                    root.findTargetCandidate(query) { candidate ->
                        candidate.node.scrollableSelfOrAncestor() != null
                    }?.node?.scrollableSelfOrAncestor()
                }
                ?: root.findNodeCandidate { candidate -> candidate.node.isScrollable }?.node
                ?: root.scrollableSelfOrDescendant()
                ?: return@executeUiAction UiPrimitiveResult.failed(
                    reason = "当前屏幕没有可滚动容器",
                    failureKind = UiActionFailureKind.NodeNotFound,
                )
            val action = when (direction) {
                UiScrollDirection.Up,
                UiScrollDirection.Left,
                UiScrollDirection.Backward -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

                UiScrollDirection.Down,
                UiScrollDirection.Right,
                UiScrollDirection.Forward -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val performed = scrollableNode.performAction(action)
            if (performed) {
                UiPrimitiveResult.succeeded("已滚动当前页面：${direction.schemaValue}")
            } else {
                UiPrimitiveResult.failed(
                    reason = "滚动动作未被当前容器接受",
                    failureKind = UiActionFailureKind.NodeNotFound,
                )
            }
        }

    private fun pressBack(timeoutMillis: Long): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                UiPrimitiveResult.succeeded("已执行系统返回")
            } else {
                UiPrimitiveResult.failed(
                    reason = "系统返回动作未被接受",
                    failureKind = UiActionFailureKind.Unknown,
                )
            }
        }

    private fun waitForScreen(timeoutMillis: Long): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis, preActionWaitMillis = timeoutMillis) {
            UiPrimitiveResult.succeeded("已等待屏幕稳定")
        }

    private fun executeUiAction(
        timeoutMillis: Long,
        preActionWaitMillis: Long = 0L,
        operation: () -> UiPrimitiveResult,
    ): UiActionReadResult {
        val before = observeSnapshot(
            maxTextChars = DEFAULT_DEVICE_CONTROL_MAX_TEXT_CHARS,
            maxNodes = DEFAULT_DEVICE_CONTROL_MAX_NODES,
        ).snapshotOrNull()
        if (preActionWaitMillis > 0L) {
            sleepForUiIdle(preActionWaitMillis)
        }
        val primitive = runCatching(operation).getOrElse {
            UiPrimitiveResult.failed(
                reason = "UI 动作执行失败",
                failureKind = UiActionFailureKind.Unknown,
            )
        }
        sleepForUiIdle(timeoutMillis.coerceAtMost(DEFAULT_POST_ACTION_WAIT_MILLIS))
        val after = observeSnapshot(
            maxTextChars = DEFAULT_DEVICE_CONTROL_MAX_TEXT_CHARS,
            maxNodes = DEFAULT_DEVICE_CONTROL_MAX_NODES,
        ).snapshotOrNull()
        return UiActionReadResult.Available(
            UiActionExecutionResult(
                status = if (primitive.performed) UiActionStatus.Succeeded else UiActionStatus.Failed,
                before = before,
                after = after,
                summary = primitive.summary,
                retryable = primitive.retryable,
                failureKind = primitive.failureKind,
            ),
        )
    }

    private fun sleepForUiIdle(timeoutMillis: Long) {
        val bounded = timeoutMillis.coerceIn(50L, MAX_UI_ACTION_TIMEOUT_MILLIS)
        runCatching { Thread.sleep(bounded) }
    }

    private fun dispatchTapGesture(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        val latch = CountDownLatch(1)
        var completed = false
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completed = false
                    latch.countDown()
                }
            },
            null,
        )
        if (!accepted) return false
        latch.await(500L, TimeUnit.MILLISECONDS)
        return completed
    }

    companion object {
        private var activeService: WeakReference<PocketMindAccessibilityService>? = null

        internal fun readCurrentScreenText(maxChars: Int): CurrentScreenTextReadResult {
            val service = activeService?.get()
                ?: return CurrentScreenTextReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            return service.readSnapshot(maxChars)
        }

        internal fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
            val service = activeService?.get()
                ?: return ScreenStateReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            return service.observeSnapshot(maxTextChars, maxNodes)
        }

        internal fun performTap(target: String, timeoutMillis: Long): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            return service.tapTarget(target, timeoutMillis)
        }

        internal fun performTypeText(text: String, target: String?, timeoutMillis: Long): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            return service.typeText(text, target, timeoutMillis)
        }

        internal fun performScroll(
            direction: UiScrollDirection,
            target: String?,
            timeoutMillis: Long,
        ): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            return service.scrollTarget(direction, target, timeoutMillis)
        }

        internal fun performPressBack(timeoutMillis: Long): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            return service.pressBack(timeoutMillis)
        }

        internal fun performWait(timeoutMillis: Long): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            return service.waitForScreen(timeoutMillis)
        }
    }
}

private fun AccessibilityNodeInfo.toCurrentScreenTextSnapshot(
    maxChars: Int,
    capturedAtMillis: Long,
): CurrentScreenTextSnapshot {
    val collector = AccessibilityTextCollector(maxChars)
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this)
    while (pending.isNotEmpty() && collector.nodeCount < MAX_SCREEN_TEXT_NODE_COUNT) {
        val node = pending.removeFirst()
        collector.visit(node)
        if (collector.isFull) break
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            pending.add(child)
            if (pending.size + collector.nodeCount >= MAX_SCREEN_TEXT_NODE_COUNT) {
                collector.markTruncated()
                break
            }
        }
    }
    if (pending.isNotEmpty()) {
        collector.markTruncated()
    }
    return CurrentScreenTextSnapshot(
        text = collector.text,
        packageName = packageName?.toString()?.takeIf { it.isNotBlank() },
        capturedAtMillis = capturedAtMillis,
        nodeCount = collector.nodeCount,
        truncated = collector.truncated,
        structureSummary = collector.structureSummary(),
    )
}

private class AccessibilityTextCollector(
    private val maxChars: Int,
) {
    private val values = mutableListOf<String>()
    private val seen = mutableSetOf<String>()
    private var usedChars = 0
    var nodeCount: Int = 0
        private set
    var truncated: Boolean = false
        private set
    private var visibleTextItemCount: Int = 0

    val isFull: Boolean
        get() = usedChars >= maxChars

    val text: String
        get() = values.joinToString(separator = "\n")

    fun visit(node: AccessibilityNodeInfo) {
        nodeCount += 1
        if (!node.isVisibleToUser || node.isPassword) return
        collect(node.text)
        collect(node.contentDescription)
    }

    fun markTruncated() {
        truncated = true
    }

    fun structureSummary(): String =
        "nodeCount=$nodeCount; visibleTextItemCount=$visibleTextItemCount; textSnapshotIncluded=${values.isNotEmpty()}"

    private fun collect(raw: CharSequence?) {
        if (raw == null || isFull) return
        val normalized = raw.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return
        if (!seen.add(normalized)) return
        val separatorChars = if (values.isEmpty()) 0 else 1
        val remaining = maxChars - usedChars - separatorChars
        if (remaining <= 0) {
            truncated = true
            return
        }
        val clipped = normalized.take(remaining)
        if (clipped.length < normalized.length) {
            truncated = true
        }
        values += clipped
        visibleTextItemCount += 1
        usedChars += clipped.length + separatorChars
    }
}

internal fun AccessibilityNodeInfo.toScreenStateSnapshot(
    maxTextChars: Int,
    maxNodes: Int,
    capturedAtMillis: Long,
): ScreenStateSnapshot {
    val collector = ScreenStateCollector(
        maxTextChars = maxTextChars,
        maxNodes = maxNodes,
        snapshotSalt = UUID.randomUUID().toString().take(8),
    )
    walkScreenNodes(maxWalkCount = MAX_SCREEN_STATE_NODE_WALK) { node ->
        collector.visit(node)
        !collector.isFull
    }
    return ScreenStateSnapshot(
        id = "screen_${capturedAtMillis}_${collector.snapshotSalt}",
        packageName = packageName?.toString()?.takeIf { it.isNotBlank() },
        capturedAtMillis = capturedAtMillis,
        nodes = collector.nodes,
        textSummary = collector.textSummary,
        truncated = collector.truncated,
    )
}

private class ScreenStateCollector(
    private val maxTextChars: Int,
    private val maxNodes: Int,
    val snapshotSalt: String,
) {
    private val textValues = mutableListOf<String>()
    private val seenText = mutableSetOf<String>()
    private var usedTextChars = 0
    private var visitedNodeCount = 0
    private val collectedNodes = mutableListOf<ScreenNode>()
    var truncated: Boolean = false
        private set

    val nodes: List<ScreenNode> get() = collectedNodes
    val isFull: Boolean get() = collectedNodes.size >= maxNodes && usedTextChars >= maxTextChars
    val textSummary: String get() = textValues.joinToString(separator = "\n")

    fun visit(node: AccessibilityNodeInfo) {
        visitedNodeCount += 1
        if (!node.isVisibleToUser || node.isPassword) return
        collectText(node.text)
        collectText(node.contentDescription)
        val shouldIncludeNode = node.isMeaningfulScreenNode()
        if (!shouldIncludeNode) return
        if (collectedNodes.size >= maxNodes) {
            truncated = true
            return
        }
        collectedNodes += node.toScreenNode(
            id = "n${collectedNodes.size}_${node.fingerprint().shortStableHash()}_$snapshotSalt",
        )
    }

    private fun collectText(raw: CharSequence?) {
        if (raw == null || usedTextChars >= maxTextChars) return
        val normalized = raw.normalizedNodeText() ?: return
        if (!seenText.add(normalized)) return
        val separatorChars = if (textValues.isEmpty()) 0 else 1
        val remaining = maxTextChars - usedTextChars - separatorChars
        if (remaining <= 0) {
            truncated = true
            return
        }
        val clipped = normalized.take(remaining)
        if (clipped.length < normalized.length) {
            truncated = true
        }
        textValues += clipped
        usedTextChars += clipped.length + separatorChars
    }
}

private data class NodeCandidate(
    val node: AccessibilityNodeInfo,
    val id: String,
    val label: String,
) {
    fun matchesTarget(target: String): Boolean {
        return targetMatchScore(target) != null
    }

    fun targetMatchScore(target: String): Int? {
        val normalizedTarget = target.trim().lowercase(Locale.ROOT)
        if (normalizedTarget.isBlank()) return null
        val normalizedId = id.lowercase(Locale.ROOT)
        if (normalizedId == normalizedTarget) return 1_000 + actionabilityScore()
        if (normalizedTarget.startsWith("${normalizedId}_")) return 950 + actionabilityScore()

        val text = node.text.normalizedNodeText().orEmpty().lowercase(Locale.ROOT)
        val description = node.contentDescription.normalizedNodeText().orEmpty().lowercase(Locale.ROOT)
        val normalizedLabel = label.lowercase(Locale.ROOT)
        val baseScore = when {
            text == normalizedTarget || description == normalizedTarget -> 900
            normalizedLabel == normalizedTarget -> 850
            text.contains(normalizedTarget) || description.contains(normalizedTarget) -> 650
            normalizedLabel.contains(normalizedTarget) -> 300
            else -> return null
        }
        return baseScore + actionabilityScore() - (normalizedLabel.length / 32).coerceAtMost(50)
    }

    private fun actionabilityScore(): Int {
        var score = 0
        if (node.isClickable) score += 100
        if (node.isEditable) score += 100
        if (node.isScrollable) score += 100
        if (node.isEnabled) score += 20
        return score
    }
}

private data class UiPrimitiveResult(
    val performed: Boolean,
    val summary: String,
    val retryable: Boolean,
    val failureKind: UiActionFailureKind?,
) {
    companion object {
        fun succeeded(summary: String): UiPrimitiveResult =
            UiPrimitiveResult(
                performed = true,
                summary = summary,
                retryable = false,
                failureKind = null,
            )

        fun failed(
            reason: String,
            retryable: Boolean = true,
            failureKind: UiActionFailureKind,
        ): UiPrimitiveResult =
            UiPrimitiveResult(
                performed = false,
                summary = reason,
                retryable = retryable,
                failureKind = failureKind,
            )
    }
}

private fun ScreenStateReadResult.snapshotOrNull(): ScreenStateSnapshot? =
    (this as? ScreenStateReadResult.Available)?.snapshot

private fun AccessibilityNodeInfo.findNodeCandidate(
    predicate: (NodeCandidate) -> Boolean,
): NodeCandidate? {
    var candidateIndex = 0
    var found: NodeCandidate? = null
    walkScreenNodes(maxWalkCount = MAX_SCREEN_STATE_NODE_WALK) { node ->
        val label = node.nodeSearchLabel()
        val candidate = NodeCandidate(
            node = node,
            id = "n${candidateIndex}_${node.fingerprint().shortStableHash()}",
            label = label,
        )
        candidateIndex += 1
        if (predicate(candidate)) {
            found = candidate
            false
        } else {
            true
        }
    }
    return found
}

private fun AccessibilityNodeInfo.findTargetCandidate(
    target: String,
    predicate: (NodeCandidate) -> Boolean = { true },
): NodeCandidate? {
    var candidateIndex = 0
    var best: NodeCandidate? = null
    var bestScore = Int.MIN_VALUE
    walkScreenNodes(maxWalkCount = MAX_SCREEN_STATE_NODE_WALK) { node ->
        val candidate = NodeCandidate(
            node = node,
            id = "n${candidateIndex}_${node.fingerprint().shortStableHash()}",
            label = node.nodeSearchLabel(),
        )
        candidateIndex += 1
        val score = candidate.targetMatchScore(target)
        if (score != null && predicate(candidate) && score > bestScore) {
            best = candidate
            bestScore = score
        }
        true
    }
    return best
}

private fun AccessibilityNodeInfo.walkScreenNodes(
    maxWalkCount: Int,
    visitor: (AccessibilityNodeInfo) -> Boolean,
) {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this)
    var walked = 0
    while (pending.isNotEmpty() && walked < maxWalkCount) {
        val node = pending.removeFirst()
        walked += 1
        if (!visitor(node)) return
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            pending.add(child)
            if (pending.size + walked >= maxWalkCount) return
        }
    }
}

private fun AccessibilityNodeInfo.toScreenNode(id: String): ScreenNode =
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

private fun AccessibilityNodeInfo.isMeaningfulScreenNode(): Boolean =
    isClickable ||
        isEditable ||
        isScrollable ||
        text.normalizedNodeText().orEmpty().isNotBlank() ||
        contentDescription.normalizedNodeText().orEmpty().isNotBlank()

private fun AccessibilityNodeInfo.nodeSearchLabel(): String =
    listOfNotNull(
        text.normalizedNodeText(),
        contentDescription.normalizedNodeText(),
        className?.toString()?.takeIf { it.isNotBlank() },
    ).joinToString(" ")

private fun AccessibilityNodeInfo.fingerprint(): String {
    val bounds = safeBounds()
    return listOf(
        className?.toString().orEmpty(),
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

private fun String.shortStableHash(): String =
    fold(0) { acc, char -> (acc * 31) + char.code }
        .toUInt()
        .toString(radix = 36)

private fun AccessibilityNodeInfo.safeBounds(): ScreenBounds? {
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

private fun AccessibilityNodeInfo.clickableSelfOrAncestor(): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = this
    repeat(6) {
        val node = current ?: return null
        if (node.isEnabled && node.isClickable) return node
        current = node.parent
    }
    return null
}

private fun AccessibilityNodeInfo.scrollableSelfOrAncestor(): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = this
    repeat(6) {
        val node = current ?: return null
        if (node.isEnabled && node.isScrollable) return node
        current = node.parent
    }
    return null
}

private fun AccessibilityNodeInfo.scrollableSelfOrDescendant(): AccessibilityNodeInfo? {
    if (isEnabled && isScrollable) return this
    return findNodeCandidate { candidate -> candidate.node.isEnabled && candidate.node.isScrollable }?.node
}

private fun CharSequence?.normalizedNodeText(): String? =
    this
        ?.toString()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
