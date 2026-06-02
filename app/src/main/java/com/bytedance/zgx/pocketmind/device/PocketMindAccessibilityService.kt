package com.bytedance.zgx.pocketmind.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

private const val MAX_SCREEN_TEXT_NODE_COUNT = 120

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

    companion object {
        private var activeService: WeakReference<PocketMindAccessibilityService>? = null

        internal fun readCurrentScreenText(maxChars: Int): CurrentScreenTextReadResult {
            val service = activeService?.get()
                ?: return CurrentScreenTextReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            return service.readSnapshot(maxChars)
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
