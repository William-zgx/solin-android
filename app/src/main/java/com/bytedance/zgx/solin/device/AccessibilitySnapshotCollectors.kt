package com.bytedance.zgx.solin.device

import android.view.accessibility.AccessibilityNodeInfo
import java.util.UUID

internal fun AccessibilityNodeInfo.toCurrentScreenTextSnapshot(
    maxChars: Int,
    capturedAtMillis: Long,
): CurrentScreenTextSnapshot {
    val collector = AccessibilityTextCollector(maxChars)
    val completed = walkScreenNodes(
        maxWalkCount = MAX_SCREEN_TEXT_NODE_COUNT,
        timeBudgetMillis = SCREEN_TEXT_WALK_BUDGET_MILLIS,
        // AccessibilityTextCollector copies out strings only — it retains no node.
        recycleVisitedNodes = true,
    ) { node ->
        collector.visit(node)
        !collector.isFull
    }
    if (!completed) {
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
        "nodeCount=$nodeCount; visibleTextItemCount=${values.size}; textSnapshotIncluded=${values.isNotEmpty()}"

    private fun collect(raw: CharSequence?) {
        if (raw == null || isFull) return
        val normalized = raw.normalizedNodeText() ?: return
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
        usedChars += clipped.length + separatorChars
    }
}

internal fun AccessibilityNodeInfo.toScreenStateSnapshot(
    maxTextChars: Int,
    maxNodes: Int,
    capturedAtMillis: Long,
    widthPx: Int? = null,
    heightPx: Int? = null,
): ScreenStateSnapshot {
    val collector = ScreenStateCollector(
        maxTextChars = maxTextChars,
        maxNodes = maxNodes,
        snapshotSalt = UUID.randomUUID().toString().take(8),
    )
    val completed = walkScreenNodes(
        maxWalkCount = MAX_SCREEN_STATE_NODE_WALK,
        timeBudgetMillis = SCREEN_STATE_WALK_BUDGET_MILLIS,
        // ScreenStateCollector flattens each node into an immutable ScreenNode value; no node escapes.
        recycleVisitedNodes = true,
    ) { node ->
        collector.visit(node)
        !collector.isFull
    }
    if (!completed) {
        collector.markTruncated()
    }
    return ScreenStateSnapshot(
        id = "screen_${capturedAtMillis}_${collector.snapshotSalt}",
        packageName = packageName?.toString()?.takeIf { it.isNotBlank() },
        capturedAtMillis = capturedAtMillis,
        nodes = collector.nodes,
        textSummary = collector.textSummary,
        truncated = collector.truncated,
        widthPx = widthPx,
        heightPx = heightPx,
    )
}

internal class ScreenStateCollector(
    private val maxTextChars: Int,
    private val maxNodes: Int,
    val snapshotSalt: String,
) {
    private val textValues = mutableListOf<String>()
    private val seenText = mutableSetOf<String>()
    private var usedTextChars = 0
    private val collectedNodes = mutableListOf<ScreenNode>()
    var truncated: Boolean = false
        private set

    val nodes: List<ScreenNode> get() = collectedNodes
    val isFull: Boolean get() = collectedNodes.size >= maxNodes && usedTextChars >= maxTextChars
    val textSummary: String get() = textValues.joinToString(separator = "\n")

    fun visit(node: AccessibilityNodeInfo) {
        if (!node.isVisibleToUser || node.isPassword) return
        collectText(node.text)
        collectText(node.contentDescription)
        if (!node.isMeaningfulScreenNode()) return
        if (collectedNodes.size >= maxNodes) {
            truncated = true
            return
        }
        collectedNodes += node.toScreenNode(
            id = screenNodeId(index = collectedNodes.size, node = node, salt = snapshotSalt),
        )
    }

    fun markTruncated() {
        truncated = true
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
