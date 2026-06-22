package com.bytedance.zgx.pocketmind.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val MAX_SCREEN_TEXT_NODE_COUNT = 120
private const val MAX_SCREEN_STATE_NODE_WALK = 240
private const val MAX_SCREEN_NODE_CHILDREN = 80
private const val SCREEN_TEXT_WALK_BUDGET_MILLIS = 1_500L
private const val SCREEN_STATE_WALK_BUDGET_MILLIS = 1_800L
private const val OBSERVE_HARD_TIMEOUT_MILLIS = 2_500L
private const val UI_ACTION_HARD_TIMEOUT_MILLIS = 4_000L
private const val DEFAULT_POST_ACTION_WAIT_MILLIS = 250L
private const val MAX_SEARCH_ENTRY_FOCUS_ATTEMPTS = 4
private const val MAX_SEARCH_ENTRY_FOCUS_WAIT_MILLIS = 900L
private const val SEARCH_ENTRY_FOCUS_POLL_MILLIS = 80L

class PocketMindAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controlOverlayView: TextView? = null

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
        hideControlProgressOverlay()
        super.onDestroy()
    }

    private fun showControlProgressOverlay(message: String) {
        mainHandler.post {
            val existing = controlOverlayView
            if (existing != null) {
                existing.text = message.controlProgressMessage()
                return@post
            }
            val windowManager = getSystemService(WindowManager::class.java) ?: return@post
            val view = TextView(this).apply {
                text = message.controlProgressMessage()
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(24, 12, 24, 12)
                background = GradientDrawable().apply {
                    setColor(Color.argb(188, 17, 24, 39))
                    cornerRadius = 0f
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 12f
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 0
            }
            runCatching {
                windowManager.addView(view, params)
                controlOverlayView = view
            }
        }
    }

    private fun hideControlProgressOverlay() {
        mainHandler.post {
            val view = controlOverlayView ?: return@post
            controlOverlayView = null
            runCatching {
                getSystemService(WindowManager::class.java)?.removeView(view)
            }
        }
    }

    private fun readSnapshot(maxChars: Int): CurrentScreenTextReadResult {
        val root = activeWindowRoot()
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
        val root = activeWindowRoot()
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
            val root = activeWindowRoot()
                ?: return@executeUiAction UiPrimitiveResult.failed(
                    reason = "当前屏幕没有可访问节点根节点",
                    failureKind = UiActionFailureKind.PageChanged,
                )
            if (UiTargetResolver.kindForTarget(target) == UiTargetKind.SearchEntry) {
                return@executeUiAction when (val result = focusSearchEditableFromEntry(root, target, timeoutMillis)) {
                    is EditableFocusResult.Found ->
                        UiPrimitiveResult.succeeded("已聚焦搜索输入框")

                    is EditableFocusResult.Failed ->
                        UiPrimitiveResult.failed(
                            reason = result.reason,
                            failureKind = result.failureKind,
                        )
                }
            }
            val match = root.findTargetCandidate(target)
                ?: return@executeUiAction UiPrimitiveResult.failed(
                    reason = "未找到可点击目标：$target",
                    failureKind = missingTargetFailureKind(target),
                )
            val performed = activateCandidate(match)
            if (performed) {
                UiPrimitiveResult.succeeded("已点击目标：${match.label}")
            } else {
                UiPrimitiveResult.failed(
                    reason = "目标不可点击：${match.label}",
                    failureKind = missingTargetFailureKind(target),
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
            val root = activeWindowRoot()
                ?: return@executeUiAction UiPrimitiveResult.failed(
                    reason = "当前屏幕没有可访问节点根节点",
                    failureKind = UiActionFailureKind.PageChanged,
                )
            val editableNode = when (val lookup = findEditableForTextInput(root, target, timeoutMillis)) {
                is EditableFocusResult.Found -> lookup.node
                is EditableFocusResult.Failed ->
                    return@executeUiAction UiPrimitiveResult.failed(
                        reason = lookup.reason,
                        failureKind = lookup.failureKind,
                    )
            }
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

    private fun submitSearch(timeoutMillis: Long): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            val root = activeWindowRoot()
                ?: return@executeUiAction UiPrimitiveResult.failed(
                    reason = "当前屏幕没有可访问节点根节点",
                    failureKind = UiActionFailureKind.PageChanged,
                )
            val editableNode = root.findNodeCandidate { candidate ->
                candidate.node.isEditable && candidate.node.isFocused
            }?.node ?: root.findNodeCandidate { candidate ->
                candidate.node.isEditable
            }?.node
            if (editableNode == null) {
                return@executeUiAction UiPrimitiveResult.failed(
                    reason = "当前屏幕没有可提交搜索的输入框",
                    failureKind = UiActionFailureKind.EditableNotFound,
                )
            }
            val imeAccepted = editableNode.performImeSearchAction()
            if (imeAccepted) {
                sleepForUiIdle(timeoutMillis.coerceAtMost(DEFAULT_POST_ACTION_WAIT_MILLIS))
            }
            val refreshedRoot = if (imeAccepted) activeWindowRoot() ?: root else root
            val refreshedEditableNode = refreshedRoot.findNodeCandidate { candidate ->
                candidate.node.isEditable && candidate.node.isFocused
            }?.node ?: refreshedRoot.findNodeCandidate { candidate ->
                candidate.node.isEditable
            }?.node
            if (imeAccepted && refreshedEditableNode == null) {
                return@executeUiAction UiPrimitiveResult.succeeded("已提交当前搜索输入")
            }
            val submitCandidate = refreshedRoot.findSearchSubmitCandidate(refreshedEditableNode ?: editableNode)
            val clickPerformed = submitCandidate?.let { candidate -> activateCandidate(candidate) } ?: false
            if (clickPerformed) {
                UiPrimitiveResult.succeeded("已点击搜索提交入口")
            } else if (imeAccepted) {
                UiPrimitiveResult.succeeded("已提交当前搜索输入")
            } else {
                UiPrimitiveResult.failed(
                    reason = "未找到可提交搜索的输入法动作或按钮",
                    failureKind = UiActionFailureKind.SubmitNotFound,
                )
            }
        }

    private fun scrollTarget(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            val root = activeWindowRoot()
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

    private fun activeWindowRoot(): AccessibilityNodeInfo? =
        rootInActiveWindow
            ?: windows
                .asSequence()
                .sortedWith(
                    compareByDescending<AccessibilityWindowInfo> { it.isActive }
                        .thenByDescending { it.isFocused },
                )
                .mapNotNull { window -> window.root }
                .firstOrNull()

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

    private fun activateCandidate(candidate: NodeCandidate): Boolean {
        val clickNode = candidate.node.clickableSelfOrAncestor()
        val gestureBounds = candidate.node.safeBounds() ?: clickNode?.safeBounds()
        val gesturePerformed = gestureBounds
            ?.let { bounds -> dispatchTapGesture(bounds.centerX, bounds.centerY) }
            ?: false
        return gesturePerformed || clickNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findEditableForTextInput(
        root: AccessibilityNodeInfo,
        target: String?,
        timeoutMillis: Long,
    ): EditableFocusResult {
        val normalizedTarget = target?.trim().orEmpty()
        if (normalizedTarget.isBlank()) {
            return root.findEditableForTyping()
                ?.let { EditableFocusResult.Found(it) }
                ?: EditableFocusResult.Failed(
                    reason = "当前屏幕没有可输入文本框",
                    failureKind = UiActionFailureKind.EditableNotFound,
                )
        }
        val kind = UiTargetResolver.kindForTarget(normalizedTarget)
        if (kind == UiTargetKind.EditableField) {
            return root.findEditableForTyping()
                ?.let { EditableFocusResult.Found(it) }
                ?: EditableFocusResult.Failed(
                    reason = "当前屏幕没有可输入文本框",
                    failureKind = UiActionFailureKind.EditableNotFound,
                )
        }
        if (kind == UiTargetKind.SearchEntry) {
            root.findEditableForTyping()?.let { return EditableFocusResult.Found(it) }
            return focusSearchEditableFromEntry(root, normalizedTarget, timeoutMillis)
        }

        val targetNode = root.findTargetCandidate(normalizedTarget)?.node
        if (targetNode?.isEditable == true) return EditableFocusResult.Found(targetNode)
        if (targetNode != null) {
            val candidate = NodeCandidate(
                node = targetNode,
                id = "target_${targetNode.fingerprint().shortStableHash()}",
                label = targetNode.nodeSearchLabel(),
            )
            activateCandidate(candidate)
            waitForEditable(timeoutMillis)?.let { return EditableFocusResult.Found(it) }
        }
        return root.findEditableForTyping()
            ?.let { EditableFocusResult.Found(it) }
            ?: EditableFocusResult.Failed(
                reason = "当前屏幕没有可输入文本框",
                failureKind = UiActionFailureKind.EditableNotFound,
            )
    }

    private fun focusSearchEditableFromEntry(
        initialRoot: AccessibilityNodeInfo,
        target: String,
        timeoutMillis: Long,
    ): EditableFocusResult {
        initialRoot.findEditableForTyping()?.let { return EditableFocusResult.Found(it) }
        var currentRoot = initialRoot
        val attemptedFingerprints = mutableSetOf<String>()
        var matchedCandidates = 0
        var activatedCandidates = 0

        repeat(MAX_SEARCH_ENTRY_FOCUS_ATTEMPTS) {
            val candidate = currentRoot.findTargetCandidates(
                target = target,
                predicate = { candidate ->
                    candidate.node.isEnabled &&
                        candidate.node.fingerprint() !in attemptedFingerprints &&
                        (candidate.node.isEditable || candidate.node.clickableSelfOrAncestor() != null)
                },
                limit = MAX_SEARCH_ENTRY_FOCUS_ATTEMPTS,
            ).firstOrNull() ?: return@repeat

            matchedCandidates += 1
            attemptedFingerprints += candidate.node.fingerprint()
            if (candidate.node.isEditable) {
                return EditableFocusResult.Found(candidate.node)
            }
            if (!activateCandidate(candidate)) {
                return@repeat
            }
            activatedCandidates += 1
            waitForEditable(timeoutMillis)?.let { return EditableFocusResult.Found(it) }
            currentRoot = activeWindowRoot() ?: currentRoot
        }

        return if (matchedCandidates == 0 || activatedCandidates == 0) {
            EditableFocusResult.Failed(
                reason = "未找到可打开输入框的搜索入口：$target",
                failureKind = UiActionFailureKind.SearchEntryNotFound,
            )
        } else {
            EditableFocusResult.Failed(
                reason = "已尝试 $activatedCandidates 个搜索入口，但未出现可输入文本框",
                failureKind = UiActionFailureKind.EditableNotFound,
            )
        }
    }

    private fun waitForEditable(timeoutMillis: Long): AccessibilityNodeInfo? {
        val waitMillis = timeoutMillis
            .coerceAtMost(MAX_SEARCH_ENTRY_FOCUS_WAIT_MILLIS)
            .coerceAtLeast(DEFAULT_POST_ACTION_WAIT_MILLIS)
        val deadline = System.currentTimeMillis() + waitMillis
        do {
            activeWindowRoot()?.findEditableForTyping()?.let { return it }
            sleepForUiIdle(SEARCH_ENTRY_FOCUS_POLL_MILLIS)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    companion object {
        private var activeService: WeakReference<PocketMindAccessibilityService>? = null

        internal fun readCurrentScreenText(maxChars: Int): CurrentScreenTextReadResult {
            val service = activeService?.get()
                ?: return CurrentScreenTextReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            showControlProgress("正在读取当前屏幕")
            return runDeviceControlWithTimeout(
                timeoutMillis = OBSERVE_HARD_TIMEOUT_MILLIS,
                fallback = { CurrentScreenTextReadResult.Failed("当前屏幕文本读取超时") },
            ) {
                service.readSnapshot(maxChars)
            }
        }

        internal fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
            val service = activeService?.get()
                ?: return ScreenStateReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            showControlProgress("正在观察当前屏幕")
            return runDeviceControlWithTimeout(
                timeoutMillis = OBSERVE_HARD_TIMEOUT_MILLIS,
                fallback = {
                    ScreenStateReadResult.Failed(
                        reason = "当前屏幕状态读取超时",
                        failureKind = UiActionFailureKind.Timeout,
                    )
                },
            ) {
                service.observeSnapshot(maxTextChars, maxNodes)
            }
        }

        internal fun performTap(target: String, timeoutMillis: Long): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            showControlProgress("正在点击：$target")
            return runDeviceControlWithTimeout(timeoutMillis = timeoutMillis.uiActionHardTimeout()) {
                service.tapTarget(target, timeoutMillis)
            }
        }

        internal fun performTypeText(text: String, target: String?, timeoutMillis: Long): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            showControlProgress("正在输入文本")
            return runDeviceControlWithTimeout(timeoutMillis = timeoutMillis.uiActionHardTimeout()) {
                service.typeText(text, target, timeoutMillis)
            }
        }

        internal fun performSubmitSearch(timeoutMillis: Long): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            showControlProgress("正在提交搜索")
            return runDeviceControlWithTimeout(timeoutMillis = timeoutMillis.uiActionHardTimeout()) {
                service.submitSearch(timeoutMillis)
            }
        }

        internal fun performScroll(
            direction: UiScrollDirection,
            target: String?,
            timeoutMillis: Long,
        ): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            showControlProgress("正在滚动页面")
            return runDeviceControlWithTimeout(timeoutMillis = timeoutMillis.uiActionHardTimeout()) {
                service.scrollTarget(direction, target, timeoutMillis)
            }
        }

        internal fun performPressBack(timeoutMillis: Long): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            showControlProgress("正在返回上一页")
            return runDeviceControlWithTimeout(timeoutMillis = timeoutMillis.uiActionHardTimeout()) {
                service.pressBack(timeoutMillis)
            }
        }

        internal fun performWait(timeoutMillis: Long): UiActionReadResult {
            val service = activeService?.get()
                ?: return UiActionReadResult.PermissionDenied("未开启 PocketMind 无障碍服务")
            showControlProgress("正在等待页面稳定")
            return runDeviceControlWithTimeout(timeoutMillis = timeoutMillis.uiActionHardTimeout()) {
                service.waitForScreen(timeoutMillis)
            }
        }

        internal fun showControlProgress(message: String) {
            activeService?.get()?.showControlProgressOverlay(message)
        }

        internal fun hideControlProgress() {
            activeService?.get()?.hideControlProgressOverlay()
        }

        private val deviceControlExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "PocketMindDeviceControl").apply {
                isDaemon = true
            }
        }

        private fun <T> runDeviceControlWithTimeout(
            timeoutMillis: Long,
            fallback: () -> T,
            operation: () -> T,
        ): T {
            val future: Future<T> = deviceControlExecutor.submit<T> { operation() }
            return try {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                future.cancel(true)
                fallback()
            } catch (_: Exception) {
                future.cancel(true)
                fallback()
            }
        }

        private fun runDeviceControlWithTimeout(
            timeoutMillis: Long,
            operation: () -> UiActionReadResult,
        ): UiActionReadResult =
            runDeviceControlWithTimeout(
                timeoutMillis = timeoutMillis,
                fallback = {
                    UiActionReadResult.Failed(
                        reason = "UI 动作执行超时",
                        retryable = true,
                        failureKind = UiActionFailureKind.Timeout,
                    )
                },
                operation = operation,
            )

        private fun Long.uiActionHardTimeout(): Long =
            (this + UI_ACTION_HARD_TIMEOUT_MILLIS).coerceAtMost(MAX_UI_ACTION_TIMEOUT_MILLIS)
    }
}

private sealed class EditableFocusResult {
    data class Found(val node: AccessibilityNodeInfo) : EditableFocusResult()
    data class Failed(
        val reason: String,
        val failureKind: UiActionFailureKind,
    ) : EditableFocusResult()
}

private fun missingTargetFailureKind(target: String): UiActionFailureKind =
    if (UiTargetResolver.kindForTarget(target) == UiTargetKind.SearchEntry) {
        UiActionFailureKind.SearchEntryNotFound
    } else {
        UiActionFailureKind.NodeNotFound
    }

private fun String.controlProgressMessage(): String {
    val compact = replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() }
        ?: DeviceControlSessionService.DEFAULT_REASON
    return "PocketMind · ${compact.take(64)}"
}

private fun AccessibilityNodeInfo.findEditableForTyping(): AccessibilityNodeInfo? =
    findFocusedEditableCandidate()?.node
        ?: findNodeCandidate { candidate -> candidate.node.isEditable }?.node

private fun AccessibilityNodeInfo.toCurrentScreenTextSnapshot(
    maxChars: Int,
    capturedAtMillis: Long,
): CurrentScreenTextSnapshot {
    val collector = AccessibilityTextCollector(maxChars)
    val completed = walkScreenNodes(
        maxWalkCount = MAX_SCREEN_TEXT_NODE_COUNT,
        timeBudgetMillis = SCREEN_TEXT_WALK_BUDGET_MILLIS,
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
    val completed = walkScreenNodes(
        maxWalkCount = MAX_SCREEN_STATE_NODE_WALK,
        timeBudgetMillis = SCREEN_STATE_WALK_BUDGET_MILLIS,
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

private data class NodeCandidate(
    val node: AccessibilityNodeInfo,
    val id: String,
    val label: String,
) {
    fun matchesTarget(target: String): Boolean {
        return targetMatchScore(target) != null
    }

    fun targetMatchScore(
        target: String,
        profile: AppInteractionProfile? = null,
        rootBounds: ScreenBounds? = null,
    ): Int? {
        if (!node.isEnabled) return null
        val normalizedTarget = target.normalizedLookupKey()
        if (normalizedTarget.isBlank()) return null
        val normalizedId = id.normalizedLookupKey()
        if (normalizedId == normalizedTarget) return 1_000 + actionabilityScore()
        if (normalizedTarget.startsWith("${normalizedId}_")) return 950 + actionabilityScore()

        val kind = UiTargetResolver.kindForTarget(target)
        val text = node.text.normalizedNodeText().normalizedLookupKey()
        val description = node.contentDescription.normalizedNodeText().normalizedLookupKey()
        val normalizedLabel = label.normalizedLookupKey()
        if (kind == UiTargetKind.SubmitSearch && looksNonTextSearchControl(normalizedLabel)) return null
        semanticTargetMatchScore(target, normalizedLabel, profile)?.let { score ->
            val finalScore = score +
                actionabilityScore() +
                targetPositionScore(kind, rootBounds) -
                labelLengthPenalty(normalizedLabel) -
                targetRiskPenalty(kind, normalizedLabel, profile, rootBounds) -
                negativeSemanticPenalty(kind, normalizedLabel)
            return finalScore.takeIf { it >= minimumRuntimeScore(kind) }
        }
        val baseScore = when {
            text == normalizedTarget || description == normalizedTarget -> 900
            normalizedLabel == normalizedTarget -> 850
            text.contains(normalizedTarget) || description.contains(normalizedTarget) -> 650
            normalizedLabel.contains(normalizedTarget) -> 300
            else -> return null
        }
        val finalScore = baseScore + actionabilityScore() + targetPositionScore(kind, rootBounds) -
            (normalizedLabel.length / 32).coerceAtMost(50) -
            targetRiskPenalty(kind, normalizedLabel, profile, rootBounds) -
            negativeSemanticPenalty(kind, normalizedLabel)
        return finalScore.takeIf { it >= minimumRuntimeScore(kind) }
    }

    private fun semanticTargetMatchScore(
        target: String,
        normalizedLabel: String,
        profile: AppInteractionProfile?,
    ): Int? {
        val kind = UiTargetResolver.kindForTarget(target) ?: return null
        val hintScore = profileHintScore(kind, profile, normalizedLabel)
        val score = when (kind) {
            UiTargetKind.SearchEntry -> {
                var value = hintScore
                if (node.isEditable) value += 780
                if (normalizedLabel.hasSearchEntryStrongEvidence()) value += 680
                if (normalizedLabel.hasGenericSearchEvidence()) value += if (node.isEditable) 560 else 300
                if (looksInputLike(normalizedLabel)) value += 180
                if (normalizedLabel == "搜索" && !node.isEditable) value -= 260
                value
            }

            UiTargetKind.EditableField ->
                if (node.isEditable) 760 else 0

            UiTargetKind.SubmitSearch ->
                if (
                    !node.isEditable &&
                    !looksNonTextSearchControl(normalizedLabel) &&
                    (looksSearchSubmitLike(normalizedLabel) || hintScore > 0)
                ) {
                    700 + hintScore
                } else {
                    0
                }

            UiTargetKind.FilterEntry ->
                if (normalizedLabel.contains("筛选") || normalizedLabel.contains("filter")) 700 else 0

            UiTargetKind.ScrollContainer ->
                if (node.isScrollable) 700 else 0

            UiTargetKind.ResultItem -> 0
        }
        return score.takeIf { it > 0 }
    }

    private fun actionabilityScore(): Int {
        var score = 0
        if (node.isClickable) score += 100
        if (node.isEditable) score += 100
        if (node.isScrollable) score += 100
        if (node.isEnabled) score += 20
        return score
    }

    private fun labelLengthPenalty(normalizedLabel: String): Int =
        (normalizedLabel.length / 16).coerceAtMost(400)

    private fun targetRiskPenalty(
        kind: UiTargetKind?,
        normalizedLabel: String,
        profile: AppInteractionProfile?,
        rootBounds: ScreenBounds?,
    ): Int {
        if (kind?.requiresPreciseTarget() != true || node.isEditable) return 0
        var penalty = 0
        val areaRatio = areaRatio(rootBounds)
        val heightRatio = heightRatio(rootBounds)
        penalty += when {
            areaRatio >= 0.35f || heightRatio >= 0.55f -> 820
            areaRatio >= 0.20f || heightRatio >= 0.38f -> 460
            areaRatio >= 0.12f -> 180
            else -> 0
        }
        if (node.isScrollable) penalty += 380
        if (looksResultOrCommerceContainer(normalizedLabel, profile)) penalty += 360
        return penalty
    }

    private fun areaRatio(rootBounds: ScreenBounds?): Float {
        val bounds = node.safeBounds() ?: return 0f
        val rootArea = (rootBounds?.width() ?: 0).toLong() * (rootBounds?.height() ?: 0).toLong()
        if (rootArea <= 0L) return 0f
        val nodeArea = bounds.width().toLong() * bounds.height().toLong()
        return nodeArea.toFloat() / rootArea.toFloat()
    }

    private fun heightRatio(rootBounds: ScreenBounds?): Float {
        val bounds = node.safeBounds() ?: return 0f
        val rootHeight = rootBounds?.height() ?: return 0f
        if (rootHeight <= 0) return 0f
        return bounds.height().toFloat() / rootHeight.toFloat()
    }

    private fun targetPositionScore(kind: UiTargetKind?, rootBounds: ScreenBounds?): Int {
        kind ?: return 0
        val bounds = node.safeBounds() ?: return 0
        val safeRootBounds = rootBounds ?: return 0
        val rootWidth = safeRootBounds.width()
        val rootHeight = safeRootBounds.height()
        if (rootWidth <= 0 || rootHeight <= 0) return 0
        val topRatio = (bounds.top - safeRootBounds.top).toFloat() / rootHeight.toFloat()
        val widthRatio = bounds.width().toFloat() / rootWidth.toFloat()
        val heightRatio = bounds.height().toFloat() / rootHeight.toFloat()
        return when (kind) {
            UiTargetKind.SearchEntry,
            UiTargetKind.EditableField -> {
                var score = 0
                if (topRatio <= 0.25f) score += 140
                if (widthRatio >= 0.35f && heightRatio >= 0.02f && heightRatio <= 0.14f) score += 140
                if (topRatio >= 0.65f) score -= 180
                score
            }

            UiTargetKind.SubmitSearch -> if (topRatio <= 0.30f) 80 else 0
            else -> 0
        }
    }

    private fun negativeSemanticPenalty(kind: UiTargetKind?, normalizedLabel: String): Int {
        kind ?: return 0
        if (kind != UiTargetKind.SearchEntry && kind != UiTargetKind.EditableField) return 0
        var penalty = 0
        if (
            normalizedLabel.contains("拍照") ||
            normalizedLabel.contains("拍立淘") ||
            normalizedLabel.contains("拍照搜") ||
            normalizedLabel.contains("相机") ||
            normalizedLabel.contains("扫一扫") ||
            normalizedLabel.contains("语音") ||
            normalizedLabel.contains("图片") ||
            normalizedLabel.contains("找同款")
        ) {
            penalty += 520
        }
        if (
            normalizedLabel.contains("商品图片") ||
            normalizedLabel.contains("推荐") ||
            normalizedLabel.contains("猜你喜欢")
        ) {
            penalty += 260
        }
        return penalty
    }
}

private fun profileHintScore(
    kind: UiTargetKind,
    profile: AppInteractionProfile?,
    normalizedLabel: String,
): Int {
    val hints = when (kind) {
        UiTargetKind.SearchEntry -> profile?.searchEntryHints.orEmpty()
        UiTargetKind.SubmitSearch -> profile?.submitHints.orEmpty()
        UiTargetKind.ResultItem -> profile?.resultHints.orEmpty()
        else -> emptySet()
    }
    return hints.maxOfOrNull { hint ->
        phraseScore(normalizedLabel, hint.normalizedLookupKey()) ?: 0
    } ?: 0
}

private fun minimumRuntimeScore(kind: UiTargetKind?): Int =
    when (kind) {
        UiTargetKind.SearchEntry -> 560
        UiTargetKind.EditableField -> 600
        UiTargetKind.SubmitSearch -> 650
        UiTargetKind.FilterEntry -> 430
        UiTargetKind.ScrollContainer -> 650
        UiTargetKind.ResultItem,
        null -> 1
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

private fun AccessibilityNodeInfo.findFocusedEditableCandidate(): NodeCandidate? =
    findNodeCandidate { candidate -> candidate.node.isEditable && candidate.node.isFocused }

private fun AccessibilityNodeInfo.findTargetCandidate(
    target: String,
    predicate: (NodeCandidate) -> Boolean = { true },
): NodeCandidate? =
    findTargetCandidates(target = target, predicate = predicate, limit = 1).firstOrNull()

private fun AccessibilityNodeInfo.findTargetCandidates(
    target: String,
    predicate: (NodeCandidate) -> Boolean = { true },
    limit: Int = 5,
): List<NodeCandidate> {
    var candidateIndex = 0
    val candidates = mutableListOf<Pair<NodeCandidate, Int>>()
    val profile = AppInteractionProfiles.forPackage(packageName?.toString())
    val rootBounds = safeBounds()
    walkScreenNodes(maxWalkCount = MAX_SCREEN_STATE_NODE_WALK) { node ->
        val candidate = NodeCandidate(
            node = node,
            id = "n${candidateIndex}_${node.fingerprint().shortStableHash()}",
            label = node.nodeSearchLabel(),
        )
        candidateIndex += 1
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

private fun AccessibilityNodeInfo.findSearchSubmitCandidate(
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

private fun AccessibilityNodeInfo.isSubmitCandidateNear(anchorEditable: AccessibilityNodeInfo?): Boolean {
    val anchorBounds = anchorEditable?.safeBounds() ?: return true
    val candidateBounds = safeBounds() ?: clickableSelfOrAncestor()?.safeBounds() ?: return false
    val anchorHeight = (anchorBounds.bottom - anchorBounds.top).coerceAtLeast(1)
    val sameRow = candidateBounds.centerY in
        (anchorBounds.top - anchorHeight)..(anchorBounds.bottom + anchorHeight)
    val nearBelow = candidateBounds.top in
        anchorBounds.bottom..(anchorBounds.bottom + anchorHeight * 3)
    val horizontallyRelated =
        candidateBounds.left <= anchorBounds.right + anchorHeight * 4 &&
            candidateBounds.right >= anchorBounds.left - anchorHeight * 4
    return horizontallyRelated && (sameRow || nearBelow)
}

private fun AccessibilityNodeInfo.walkScreenNodes(
    maxWalkCount: Int,
    timeBudgetMillis: Long = SCREEN_STATE_WALK_BUDGET_MILLIS,
    visitor: (AccessibilityNodeInfo) -> Boolean,
): Boolean {
    val pending = ArrayDeque<AccessibilityNodeInfo>()
    pending.add(this)
    var walked = 0
    val deadlineMillis = System.currentTimeMillis() + timeBudgetMillis.coerceAtLeast(100L)
    while (pending.isNotEmpty() && walked < maxWalkCount) {
        if (System.currentTimeMillis() >= deadlineMillis) return false
        val node = pending.removeFirst()
        walked += 1
        if (!visitor(node)) return true
        val childCount = runCatching { node.childCount }
            .getOrDefault(0)
            .coerceAtMost(MAX_SCREEN_NODE_CHILDREN)
        for (index in 0 until childCount) {
            if (System.currentTimeMillis() >= deadlineMillis) return false
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            pending.add(child)
            if (pending.size + walked >= maxWalkCount) return false
        }
    }
    return pending.isEmpty()
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
        viewIdResourceName?.takeIf { it.isNotBlank() },
        className?.toString()?.takeIf { it.isNotBlank() },
    ).joinToString(" ")

private fun AccessibilityNodeInfo.fingerprint(): String {
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

private fun AccessibilityNodeInfo.performImeSearchAction(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

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

private fun String.hasGenericSearchEvidence(): Boolean =
    contains("搜索") ||
        contains("搜") ||
        contains("search") ||
        contains("查找") ||
        contains("查询") ||
        contains("검색")

private fun String.hasSearchEntryStrongEvidence(): Boolean =
    contains("搜索栏") ||
        contains("搜索框") ||
        contains("搜索商品") ||
        contains("搜索发现") ||
        contains("搜索宝贝") ||
        contains("搜索京东") ||
        contains("搜索好物") ||
        contains("搜索输入") ||
        contains("输入文字") ||
        contains("输入关键词") ||
        contains("请输入搜索词") ||
        contains("地址栏") ||
        contains("网址") ||
        contains("目的地") ||
        contains("去哪儿") ||
        contains("搜地点") ||
        contains("公交地铁") ||
        contains("搜索词或网址") ||
        contains("searchbox") ||
        contains("searchfield") ||
        contains("omnibox")

private fun String?.looksLikeSearchOrEditableTarget(): Boolean {
    val normalized = orEmpty().lowercase(Locale.ROOT)
    return UiTargetResolver.kindForTarget(normalized)?.let { kind ->
        kind == UiTargetKind.SearchEntry || kind == UiTargetKind.EditableField
    } == true ||
        normalized.hasGenericSearchEvidence() ||
        normalized.contains("输入") ||
        normalized.contains("edit") ||
        normalized.contains("地址")
}
