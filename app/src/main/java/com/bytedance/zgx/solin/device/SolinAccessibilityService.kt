package com.bytedance.zgx.solin.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import com.bytedance.zgx.solin.action.NormalizedTarget
import com.bytedance.zgx.solin.multimodal.RawScreenshotReadResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Every traversal limit and wall-clock budget this file depends on, in one place.
 *
 * These numbers are not independent: [UI_ACTION_FIXED_OVERHEAD_MILLIS] must dominate everything
 * [executeUiAction] spends around the primitive (two full-tree observes plus the post-action idle
 * wait), otherwise the outer hard timeout fires before the primitive can possibly report and every
 * long-timeout request degrades into a spurious `Timeout`. Keeping them adjacent is what makes that
 * arithmetic reviewable — the previous fixed `+4000` silently violated it once `ui_wait` accepted
 * timeouts above ~6s.
 */
internal const val MAX_SCREEN_TEXT_NODE_COUNT = 120
internal const val MAX_SCREEN_STATE_NODE_WALK = 240
internal const val MAX_SCREEN_NODE_CHILDREN = 80
internal const val MAX_SELF_OR_ANCESTOR_WALK_DEPTH = 6

/**
 * Last SDK level on which `AccessibilityNodeInfo.recycle()` still does something.
 *
 * API 33 deprecated it and made instances garbage-collected normally; on API 28-32 (this app's
 * `minSdk` is 28) failing to recycle leaks pooled instances for the whole session.
 */
internal const val LAST_MANUAL_NODE_RECYCLE_SDK = 32
internal const val SCREEN_TEXT_WALK_BUDGET_MILLIS = 1_500L
internal const val SCREEN_STATE_WALK_BUDGET_MILLIS = 3_000L
private const val OBSERVE_HARD_TIMEOUT_MILLIS = 5_000L

/** Observes bracketing a primitive in [executeUiAction]: one `before`, one `after`. */
private const val UI_ACTION_OBSERVE_COUNT = 2

/** Headroom for handler hops, binder round-trips and the gesture callback grace waits. */
private const val UI_ACTION_TIMEOUT_SLACK_MILLIS = 1_500L

/** Upper bound on the post-action idle wait, so an honoured model timeout still stays bounded. */
private const val MAX_POST_ACTION_WAIT_MILLIS = 1_500L
private const val UI_ACTION_FIXED_OVERHEAD_MILLIS =
    (UI_ACTION_OBSERVE_COUNT * SCREEN_STATE_WALK_BUDGET_MILLIS) +
        MAX_POST_ACTION_WAIT_MILLIS +
        UI_ACTION_TIMEOUT_SLACK_MILLIS
private const val TAKE_SCREENSHOT_CAPTURE_TIMEOUT_MILLIS = 6_000L

/**
 * The framework rejects captures closer than ~1s apart with
 * `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`; stay above that nominal second so clock granularity
 * and scheduling jitter cannot land us just under it and abort the loop.
 */
private const val TAKE_SCREENSHOT_MIN_INTERVAL_MILLIS = 1_200L
private const val ACTIVE_WINDOW_ROOT_WAIT_MILLIS = 1_000L
private const val DEFAULT_POST_ACTION_WAIT_MILLIS = 250L
private const val MAX_SEARCH_ENTRY_FOCUS_ATTEMPTS = 4
private const val MAX_SEARCH_ENTRY_FOCUS_WAIT_MILLIS = 3_000L
private const val SEARCH_ENTRY_FOCUS_POLL_MILLIS = 80L

/** A tap is a zero-length stroke; the duration only has to exceed the framework's touch slop time. */
private const val TAP_GESTURE_STROKE_DURATION_MILLIS = 80L

/** Grace added to a gesture's own duration before we stop waiting for its result callback. */
private const val GESTURE_CALLBACK_GRACE_MILLIS = 500L
private val deviceControlTask = ThreadLocal<DeviceControlTaskCoordinator.Lease?>()

/**
 * What the framework reported about one dispatched gesture.
 *
 * The distinction that matters is [TimedOut] vs [Cancelled]: both mean "no confirmed touch", but only
 * [Cancelled] (and [NotAccepted], where dispatch never started) proves nothing was delivered. A
 * timed-out callback may still have landed, so compensating for it with a second input event risks a
 * duplicate activation.
 */
internal enum class GestureOutcome {
    /** `dispatchGesture` refused the request — no touch was ever injected. */
    NotAccepted,

    /** The framework confirmed the gesture ran to completion. */
    Completed,

    /** The framework explicitly cancelled the gesture; no touch reached the app. */
    Cancelled,

    /** No callback arrived before the grace window elapsed; delivery is unknown. */
    TimedOut,
    ;

    val performed: Boolean get() = this == Completed

    /** True only when we know no touch landed, so a compensating click cannot double-fire. */
    val allowsFallbackClick: Boolean get() = this == NotAccepted || this == Cancelled
}

/**
 * Result of a selection-aware backspace: the new text plus where the caret should end up.
 *
 * [selection] is `-1` when the caret position is unknown, meaning the caller should not attempt a
 * restore (the platform will leave it at the end, which is the old behaviour).
 */
internal data class BackspaceEdit(val text: String, val selection: Int)

/**
 * Computes a backspace against [current] honouring the reported selection.
 *
 * Three cases, in the order a real IME handles them: a non-empty selection is deleted wholesale; a
 * collapsed caret deletes the one character before it; an unusable/unreported selection falls back to
 * dropping the last character. Pure so the off-by-one and boundary behaviour is unit-testable —
 * `AccessibilityNodeInfo` reports `-1` for "no selection", and out-of-range values have been observed
 * on stale nodes, so both must be treated as "unknown" rather than trusted into a substring call.
 */
internal fun backspaceEdit(current: String, selectionStart: Int, selectionEnd: Int): BackspaceEdit {
    val validRange = 0..current.length
    if (selectionStart !in validRange || selectionEnd !in validRange) {
        return BackspaceEdit(text = current.dropLast(1), selection = -1)
    }
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    if (start != end) {
        return BackspaceEdit(
            text = current.removeRange(start, end),
            selection = start,
        )
    }
    if (start == 0) return BackspaceEdit(text = current, selection = 0)
    return BackspaceEdit(
        text = current.removeRange(start - 1, start),
        selection = start - 1,
    )
}

private fun tapPathAt(x: Int, y: Int): Path =
    Path().apply { moveTo(x.toFloat(), y.toFloat()) }

/**
 * Clamps a model-supplied normalized pair into the official [NormalizedTarget].
 *
 * Reuses `action.NormalizedTarget.toAbsolutePixels` rather than re-deriving `x * width / 1000` at each
 * gesture site — three hand-written copies of that formula is three places for the mapping to drift
 * from the one the rest of the pipeline documents. Clamping (not `require`) keeps the existing
 * fail-soft behaviour for out-of-range input; the schema already bounds it upstream.
 */
private fun clampedNormalizedTarget(x: Int, y: Int): NormalizedTarget =
    NormalizedTarget(
        x = x.coerceIn(MIN_NORMALIZED_COORD, MAX_NORMALIZED_COORD),
        y = y.coerceIn(MIN_NORMALIZED_COORD, MAX_NORMALIZED_COORD),
    )

class SolinAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controlOverlayView: TextView? = null

    /**
     * Dedicated executor for [takeScreenshot] result delivery. NOT the device-control executor
     * (that thread blocks on the capture latch) and NOT the main thread (which can be busy rendering
     * a window transition right when the loop captures, delaying delivery past the timeout).
     */
    private val screenshotCallbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SolinScreenshotCallback").apply { isDaemon = true }
    }

    /**
     * Clipboard content Solin wrote and still owes a restore for.
     *
     * Held on the service (not captured per-callback) so [onInterrupt] / [onDestroy] / [onUnbind]
     * can drain it. The paste's own delayed restore is deliberately NOT lease-guarded: interrupt,
     * reconnect and teardown are exactly the cases where pasted user text would otherwise stay in
     * the system clipboard. Every restore is token-scoped and content-checked, so running more than
     * one is harmless.
     */
    private val clipboardRestorer = ClipboardPasteRestorer<ClipData>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        deviceControlTasks.connect(this)?.let { previous ->
            (previous as? SolinAccessibilityService)?.hideControlProgressOverlay()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        deviceControlTasks.interrupt(this)
        scheduleClipboardDrainAfterInterrupt()
        hideControlProgressOverlay()
    }

    /**
     * Backstop for the paste's delayed restore: drains the restore owed at interrupt time after the
     * usual post-paste settle window (an in-flight `ACTION_PASTE` binder call cannot be interrupted
     * and must still read Solin's text). Token-scoped: if a newer paste has started by then, its own
     * restore handles the clipboard.
     */
    private fun scheduleClipboardDrainAfterInterrupt() {
        val restoreToken = clipboardRestorer.pendingToken() ?: return
        mainHandler.postDelayed(
            { drainPendingClipboardOnTeardown(restoreToken) },
            DEFAULT_POST_ACTION_WAIT_MILLIS,
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Unbind can happen without onDestroy (user toggles the service off, system rebinds later), and
        // it is the last point where we still have a live context to hand the clipboard back.
        drainPendingClipboardOnTeardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        deviceControlTasks.invalidate(this)
        drainPendingClipboardOnTeardown()
        hideControlProgressOverlay()
        // The callback thread outlives the service otherwise: it is only fed by takeScreenshot, so once
        // the service is gone nothing can arrive on it and keeping it alive just leaks a thread per
        // service instance across enable/disable cycles.
        screenshotCallbackExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun showControlProgressOverlay(message: String) {
        mainHandler.post {
            if (!deviceControlTasks.isCurrentOwner(this)) return@post
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
        // Same size the gestures are dispatched against, so a normalized point resolves to the
        // same pixel in the preflight hit test as in the real tap.
        val (widthPx, heightPx) = runCatching { gestureScreenSizePx() }.getOrNull() ?: (null to null)
        return runCatching {
            ScreenStateReadResult.Available(
                root.toScreenStateSnapshot(
                    maxTextChars = maxTextChars,
                    maxNodes = maxNodes,
                    capturedAtMillis = System.currentTimeMillis(),
                    widthPx = widthPx,
                    heightPx = heightPx,
                ),
            )
        }.getOrElse {
            ScreenStateReadResult.Failed("当前屏幕状态读取失败")
        }
    }

    private fun tapTarget(
        target: String,
        timeoutMillis: Long,
        ocrGroundingHint: UiOcrGroundingHint? = null,
    ): UiActionReadResult =
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
                        tapOcrGroundingHint(root, target, ocrGroundingHint)
                            ?: UiPrimitiveResult.failed(
                                reason = result.reason,
                                failureKind = result.failureKind,
                            )
                }
            }
            val match = root.findTargetCandidate(target)
                ?: return@executeUiAction tapOcrGroundingHint(root, target, ocrGroundingHint)
                    ?: UiPrimitiveResult.failed(
                        reason = "未找到可点击目标：$target",
                        failureKind = missingTargetFailureKind(target),
                    )
            val performed = activateCandidate(match)
            if (performed) {
                UiPrimitiveResult.succeeded("已点击目标：${match.label}")
            } else {
                tapOcrGroundingHint(root, target, ocrGroundingHint)
                    ?: UiPrimitiveResult.failed(
                        reason = "目标不可点击：${match.label}",
                        failureKind = missingTargetFailureKind(target),
                    )
            }
        }

    private fun typeText(
        text: String,
        target: String?,
        timeoutMillis: Long,
        ocrGroundingHint: UiOcrGroundingHint? = null,
        allowClipboardPasteFallback: Boolean = false,
    ): UiActionReadResult =
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
                    when (val ocrLookup = focusEditableFromOcrGrounding(root, target, ocrGroundingHint, timeoutMillis)) {
                        is EditableFocusResult.Found -> ocrLookup.node
                        is EditableFocusResult.Failed ->
                            return@executeUiAction UiPrimitiveResult.failed(
                                reason = ocrLookup.reason,
                                failureKind = ocrLookup.failureKind,
                            )

                        null ->
                            return@executeUiAction UiPrimitiveResult.failed(
                                reason = lookup.reason,
                                failureKind = lookup.failureKind,
                            )
                    }
            }
            prepareEditableForTextInput(editableNode)
            val directTextPerformed = setTextDirectly(editableNode, text)
            val pasteFallbackPerformed = !directTextPerformed &&
                allowClipboardPasteFallback &&
                pasteTextIntoEditable(editableNode, text)
            val performed = directTextPerformed || pasteFallbackPerformed
            if (performed) {
                UiPrimitiveResult.succeeded("已向输入框写入 ${text.length} 个字符")
            } else {
                UiPrimitiveResult.failed(
                    reason = if (allowClipboardPasteFallback) {
                        "输入框不支持直接写入文本"
                    } else {
                        "输入框不支持直接写入文本，剪贴板粘贴 fallback 未启用"
                    },
                    failureKind = UiActionFailureKind.KeyboardObscured,
                )
            }
        }

    private fun submitSearch(
        timeoutMillis: Long,
        ocrGroundingHint: UiOcrGroundingHint? = null,
    ): UiActionReadResult =
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
                return@executeUiAction tapSubmitSearchOcrGrounding(root, ocrGroundingHint)
                    ?: UiPrimitiveResult.failed(
                        reason = "当前屏幕没有可提交搜索的输入框",
                        failureKind = UiActionFailureKind.EditableNotFound,
                    )
            }
            throwIfInterrupted()
            val imeAccepted = submitUiSideEffect { editableNode.performImeSearchAction() }
            if (imeAccepted) {
                sleepForUiIdle(postActionWaitMillis(timeoutMillis))
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
            // The IME may already have submitted, so a refused extra click is not a failed search.
            val clickPerformed = try {
                submitCandidate?.let { candidate -> activateCandidate(candidate) } ?: false
            } catch (refused: CriticalControlTapRefused) {
                if (!imeAccepted) throw refused
                false
            }
            if (clickPerformed) {
                UiPrimitiveResult.succeeded("已点击搜索提交入口")
            } else if (imeAccepted) {
                UiPrimitiveResult.succeeded("已提交当前搜索输入")
            } else {
                tapSubmitSearchOcrGrounding(refreshedRoot, ocrGroundingHint)
                    ?: UiPrimitiveResult.failed(
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
            if (scrollableNode != null) {
                val action = when (direction) {
                    UiScrollDirection.Up,
                    UiScrollDirection.Left,
                    UiScrollDirection.Backward -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

                    UiScrollDirection.Down,
                    UiScrollDirection.Right,
                    UiScrollDirection.Forward -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                throwIfInterrupted()
                if (submitUiSideEffect { scrollableNode.performAction(action) }) {
                    return@executeUiAction UiPrimitiveResult.succeeded("已滚动当前页面：${direction.schemaValue}")
                }
            }
            scrollBySwipeFallback(
                direction = direction,
                containerBounds = scrollableNode?.safeBounds(),
                actionUnavailableReason = if (scrollableNode == null) {
                    "当前屏幕没有可滚动容器"
                } else {
                    "滚动动作未被当前容器接受"
                },
            )
        }

    /**
     * Replays a refused (or impossible) accessibility scroll as one swipe inside the container.
     *
     * WebView / Flutter / React Native surfaces commonly expose no scrollable node, or refuse
     * ACTION_SCROLL_*, while scrolling fine under a finger. The fallback is a raw-coordinate gesture, so it
     * applies the same fail-closed rule as `ui_swipe`'s preflight rather than `ui_scroll`'s fail-open one:
     * it re-reads the screen and refuses when the screen is unreadable or carries a dangerous-action
     * control. Risk level and confirmation are untouched — this runs inside the already-confirmed
     * `ui_scroll` call and never widens what that call may reach (one straight stroke, safe area only).
     */
    private fun scrollBySwipeFallback(
        direction: UiScrollDirection,
        containerBounds: ScreenBounds?,
        actionUnavailableReason: String,
    ): UiPrimitiveResult {
        val snapshot = observeSnapshot(
            maxTextChars = DEFAULT_DEVICE_CONTROL_MAX_TEXT_CHARS,
            maxNodes = DEFAULT_DEVICE_CONTROL_MAX_NODES,
        ).snapshotOrNull()
            ?: return UiPrimitiveResult.failed(
                reason = "$actionUnavailableReason，且无法读取屏幕确认没有危险控件，未执行滑动回退",
                retryable = false,
                failureKind = UiActionFailureKind.NodeNotFound,
            )
        if (snapshot.hasDangerousActionControl()) {
            return UiPrimitiveResult.failed(
                reason = "$actionUnavailableReason，当前屏幕包含危险控件，未执行滑动回退",
                retryable = false,
                failureKind = UiActionFailureKind.DangerousAction,
            )
        }
        val (screenWidth, screenHeight) = gestureScreenSizePx()
        val path = scrollFallbackSwipePath(
            direction = direction,
            containerBounds = containerBounds,
            screenWidthPx = screenWidth,
            screenHeightPx = screenHeight,
        ) ?: return UiPrimitiveResult.failed(
            reason = "$actionUnavailableReason，滚动容器过小，无法安全执行滑动回退",
            failureKind = UiActionFailureKind.NodeNotFound,
        )
        throwIfInterrupted()
        val performed = dispatchSwipeGesture(path.startX, path.startY, path.endX, path.endY, path.durationMillis)
        return if (performed) {
            UiPrimitiveResult.succeeded("已通过滑动手势滚动当前页面：${direction.schemaValue}")
        } else {
            UiPrimitiveResult.failed(
                reason = "$actionUnavailableReason，滑动回退手势也未被系统接受",
                failureKind = UiActionFailureKind.NodeNotFound,
            )
        }
    }

    private fun pressBack(timeoutMillis: Long): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            throwIfInterrupted()
            if (submitUiSideEffect { performGlobalAction(GLOBAL_ACTION_BACK) }) {
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

    private fun activeWindowRoot(): AccessibilityNodeInfo? = readOnMainThread { activeWindowRootDirect() }

    private fun <T> readOnMainThread(read: () -> T?): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return read()
        }
        val latch = CountDownLatch(1)
        var value: T? = null
        mainHandler.post {
            value = runCatching(read).getOrNull()
            latch.countDown()
        }
        return if (
            runCatching {
                latch.await(ACTIVE_WINDOW_ROOT_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
        ) {
            value
        } else {
            null
        }
    }

    /**
     * The window whose tree we act on.
     *
     * `isActive` outranks the window type on purpose: the type-first ordering picked whichever
     * TYPE_APPLICATION window happened to sort first, which in split-screen or under an app-overlay is
     * the background app — so we would read and tap the window the user is not interacting with. Active
     * (then focused) identifies the window receiving input, and only among equals does preferring an
     * application window over system chrome help.
     */
    private fun activeWindowRootDirect(): AccessibilityNodeInfo? =
        windows
            .asSequence()
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isActive }
                    .thenByDescending { it.isFocused }
                    .thenByDescending { it.type == AccessibilityWindowInfo.TYPE_APPLICATION },
            )
            .mapNotNull { window -> window.root }
            .firstOrNull()
            ?: rootInActiveWindow

    private fun executeUiAction(
        timeoutMillis: Long,
        preActionWaitMillis: Long = 0L,
        operation: () -> UiPrimitiveResult,
    ): UiActionReadResult {
        throwIfInterrupted()
        val before = observeSnapshot(
            maxTextChars = DEFAULT_DEVICE_CONTROL_MAX_TEXT_CHARS,
            maxNodes = DEFAULT_DEVICE_CONTROL_MAX_NODES,
        ).snapshotOrNull()
        if (preActionWaitMillis > 0L) {
            sleepForUiIdle(preActionWaitMillis)
        }
        throwIfInterrupted()
        val primitive = runCatching(operation).getOrElse { error ->
            if (error is CriticalControlTapRefused) {
                return@getOrElse UiPrimitiveResult.failed(
                    reason = CRITICAL_CONTROL_TAP_REFUSED_MESSAGE,
                    retryable = false,
                    failureKind = UiActionFailureKind.DangerousAction,
                )
            }
            UiPrimitiveResult.failed(
                reason = "UI 动作执行失败",
                retryable = false,
                failureKind = if (
                    error is InterruptedException ||
                    error is DeviceControlTaskCancelledException
                ) {
                    UiActionFailureKind.Timeout
                } else {
                    UiActionFailureKind.Unknown
                },
            )
        }
        sleepForUiIdle(postActionWaitMillis(timeoutMillis))
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
        Thread.sleep(bounded)
    }

    private fun dispatchTapGesture(x: Int, y: Int): Boolean {
        refuseCriticalTapAt(x, y)
        return dispatchGestureOutcome(
            path = tapPathAt(x, y),
            durationMillis = TAP_GESTURE_STROKE_DURATION_MILLIS,
        ).performed
    }

    /** Reads the live windows for [CriticalTapGuard.checkTap]; throws [CriticalControlTapRefused]. */
    private fun refuseCriticalTapAt(x: Int, y: Int, node: AccessibilityNodeInfo? = null) {
        val (nodes, complete) = windowRootsForTapCheck().tapCheckNodes()
        CriticalTapGuard.checkTap(x, y, node.tappedNodes(), nodes, complete).throwIfRefused()
    }

    private fun refuseCriticalContainerClick(container: AccessibilityNodeInfo) {
        val (nodes, complete) = windowRootsForTapCheck().tapCheckNodes()
        CriticalTapGuard.checkContainerClick(container.safeBounds(), nodes, complete).throwIfRefused()
    }

    /** A swipe that barely moves, or that starts and ends on one control, clicks it on release. */
    private fun refuseSwipeThatClicks(startX: Int, startY: Int, endX: Int, endY: Int) {
        val (nodes, complete) = windowRootsForTapCheck().tapCheckNodes()
        CriticalTapGuard.checkSwipe(startX, startY, endX, endY, nodes, complete).throwIfRefused()
    }

    private fun windowRootsForTapCheck(): List<AccessibilityNodeInfo> =
        readOnMainThread { windows.mapNotNull { window -> window.root } }
            .orEmpty()
            .ifEmpty { listOfNotNull(activeWindowRoot()) }

    /**
     * Dispatches one stroke and reports what the framework actually told us.
     *
     * `completed` is an [AtomicBoolean] rather than a plain `var`: the callback runs on a framework
     * thread while the control thread reads it, and a non-volatile field gives no happens-before edge
     * — the reader could observe a stale `false` after a successful gesture. The latch's own return
     * value is kept too, because "callback said cancelled" and "callback never arrived" must not be
     * collapsed: only the former is safe to compensate for with a second input event.
     */
    private fun dispatchGestureOutcome(path: Path, durationMillis: Long): GestureOutcome {
        throwIfInterrupted()
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMillis))
            .build()
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val accepted = submitUiSideEffect {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        completed.set(false)
                        latch.countDown()
                    }
                },
                null,
            )
        }
        if (!accepted) return GestureOutcome.NotAccepted
        val callbackArrived = runCatching {
            latch.await(durationMillis + GESTURE_CALLBACK_GRACE_MILLIS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        throwIfInterrupted()
        return when {
            !callbackArrived -> GestureOutcome.TimedOut
            completed.get() -> GestureOutcome.Completed
            else -> GestureOutcome.Cancelled
        }
    }

    /** Wall-clock of the last takeScreenshot call, for the ~1/sec framework rate-limit guard. */
    @Volatile
    private var lastScreenshotAtMillis = 0L

    /**
     * Captures the current screen via [AccessibilityService.takeScreenshot] (API 30+) and returns
     * transient compacted JPEG bytes for the opt-in remote-vision GUI automation path. NO
     * MediaProjection and NO foreground service: the already-connected accessibility service
     * captures directly. Screen-pixel egress is governed upstream by the in-app opt-in toggle plus
     * the replanner's first-confirm gate — not by any per-capture OS consent dialog.
     *
     * Runs on the device-control thread inside [runDeviceControlWithTimeout]. The framework
     * screenshot callback MUST be delivered on a different thread (this one blocks on the latch), so
     * the dedicated [screenshotCallbackExecutor] is used — not the main thread, which may be busy
     * rendering a window transition exactly when the loop captures. A capture is a read, not a UI
     * side effect, so it is submitted via [submitUiRead] and a capture timeout leaves the task
     * retryable. Fails closed (returns [RawScreenshotReadResult.Failed]) on unsupported API,
     * timeout, framework error, or decode failure.
     */
    private fun takeScreenshotRaw(): RawScreenshotReadResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return RawScreenshotReadResult.Failed("当前系统不支持无障碍截图")
        }
        throwIfInterrupted()
        // Framework rate-limits takeScreenshot to ~1/sec (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT).
        // Space consecutive captures so a fast local model does not trip it and prematurely stop the loop.
        val sinceLast = System.currentTimeMillis() - lastScreenshotAtMillis
        if (sinceLast in 0 until TAKE_SCREENSHOT_MIN_INTERVAL_MILLIS) {
            sleepForUiIdle(TAKE_SCREENSHOT_MIN_INTERVAL_MILLIS - sinceLast)
        }
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<RawScreenshotReadResult>(
            RawScreenshotReadResult.Failed("当前屏幕截图不可用"),
        )
        try {
            submitUiRead {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotCallbackExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            resultRef.set(screenshot.toRawScreenshotReadResult())
                            latch.countDown()
                        }

                        override fun onFailure(errorCode: Int) {
                            resultRef.set(takeScreenshotErrorToResult(errorCode))
                            latch.countDown()
                        }
                    },
                )
            }
        } catch (throwable: Throwable) {
            return RawScreenshotReadResult.Failed("当前屏幕截图调用失败(${throwable.javaClass.simpleName})")
        }
        val done = latch.await(TAKE_SCREENSHOT_CAPTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        lastScreenshotAtMillis = System.currentTimeMillis()
        throwIfInterrupted()
        if (!done) return RawScreenshotReadResult.Failed("当前屏幕截图超时")
        return resultRef.get()
    }

    /**
     * Tap at a position specified by normalized 0-1000 coordinates.
     *
     * Inspired by Open-AutoGLM's normalized coordinate system: the model outputs resolution-agnostic
     * (x, y) in [0, 1000] and we map to absolute pixel coordinates based on the actual screen size.
     * This makes targeting consistent across devices with different resolutions and densities.
     */
    private fun tapByNormalizedCoords(
        normalizedX: Int,
        normalizedY: Int,
        timeoutMillis: Long,
    ): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            val (screenWidth, screenHeight) = gestureScreenSizePx()
            val (absX, absY) = clampedNormalizedTarget(normalizedX, normalizedY)
                .toAbsolutePixels(screenWidth, screenHeight)
            val performed = dispatchTapGesture(absX, absY)
            if (performed) {
                UiPrimitiveResult.succeeded("已点击坐标 ($normalizedX, $normalizedY)")
            } else {
                UiPrimitiveResult.failed(
                    reason = "坐标点击未被系统接受",
                    failureKind = UiActionFailureKind.Unknown,
                )
            }
        }

    /**
     * Screen size gestures are injected against, in real display pixels.
     *
     * `resources.displayMetrics` is the app's own view of the display: it excludes system decor and
     * shrinks in multi-window, so on a device with gesture navigation a normalized y=1000 landed short
     * of the real bottom edge. Gestures are dispatched in absolute screen coordinates, so they must be
     * derived from the display's real size. Falls back to app metrics if the real size is unavailable —
     * the previous behaviour, never worse.
     */
    private fun gestureScreenSizePx(): Pair<Int, Int> {
        val appMetrics = resources.displayMetrics
        val fallback = appMetrics.widthPixels to appMetrics.heightPixels
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fallback
        val bounds = runCatching {
            getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        }.getOrNull() ?: return fallback
        val width = bounds.width()
        val height = bounds.height()
        return if (width > 0 && height > 0) width to height else fallback
    }

    private fun swipeByNormalizedCoords(
        startXNorm: Int,
        startYNorm: Int,
        endXNorm: Int,
        endYNorm: Int,
        durationMillis: Long,
        timeoutMillis: Long,
    ): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            val (screenWidth, screenHeight) = gestureScreenSizePx()
            val (startX, startY) = clampedNormalizedTarget(startXNorm, startYNorm)
                .toAbsolutePixels(screenWidth, screenHeight)
            val (endX, endY) = clampedNormalizedTarget(endXNorm, endYNorm)
                .toAbsolutePixels(screenWidth, screenHeight)
            refuseSwipeThatClicks(startX, startY, endX, endY)
            val performed = dispatchSwipeGesture(startX, startY, endX, endY, durationMillis)
            if (performed) {
                UiPrimitiveResult.succeeded(
                    "已滑动：($startXNorm, $startYNorm) → ($endXNorm, $endYNorm)",
                )
            } else {
                UiPrimitiveResult.failed(
                    reason = "滑动手势未被系统接受",
                    failureKind = UiActionFailureKind.Unknown,
                )
            }
        }

    private fun longPressByNormalizedCoords(
        xNorm: Int,
        yNorm: Int,
        holdMillis: Long,
        timeoutMillis: Long,
    ): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            val (screenWidth, screenHeight) = gestureScreenSizePx()
            val (absX, absY) = clampedNormalizedTarget(xNorm, yNorm)
                .toAbsolutePixels(screenWidth, screenHeight)
            // A view with no long-click handler clicks on release.
            refuseCriticalTapAt(absX, absY)
            val performed = dispatchLongPressGesture(absX, absY, holdMillis)
            if (performed) {
                UiPrimitiveResult.succeeded("已长按坐标 ($xNorm, $yNorm)")
            } else {
                UiPrimitiveResult.failed(
                    reason = "长按手势未被系统接受",
                    failureKind = UiActionFailureKind.Unknown,
                )
            }
        }

    private fun pressSystemKey(key: UiSystemKey, timeoutMillis: Long): UiActionReadResult =
        executeUiAction(timeoutMillis = timeoutMillis) {
            when (key) {
                UiSystemKey.Home -> globalActionPrimitive(GLOBAL_ACTION_HOME, "已回到主屏")
                UiSystemKey.Recents -> globalActionPrimitive(GLOBAL_ACTION_RECENTS, "已打开最近任务")
                UiSystemKey.Enter -> imeEnterPrimitive()
                UiSystemKey.Delete -> deleteLastCharPrimitive()
            }
        }

    private fun globalActionPrimitive(action: Int, successSummary: String): UiPrimitiveResult {
        throwIfInterrupted()
        return if (submitUiSideEffect { performGlobalAction(action) }) {
            UiPrimitiveResult.succeeded(successSummary)
        } else {
            UiPrimitiveResult.failed(
                reason = "系统按键动作未被接受",
                failureKind = UiActionFailureKind.Unknown,
            )
        }
    }

    /**
     * Enter/confirm on the focused editable, with an API 28/29 fallback.
     *
     * `ACTION_IME_ENTER` only exists from API 30, and `minSdk` is 28 — on those two releases this
     * primitive could never do anything at all, so `press_key(enter)` and the IME half of
     * `submit_search` were permanently dead. The fallback clicks a submit affordance adjacent to the
     * focused field, the same evidence [findSearchSubmitCandidate] already requires. The strict
     * focused-safe-editable precondition above is unchanged: we still refuse to submit a form the user
     * did not focus, and never touch a password field.
     */
    private fun imeEnterPrimitive(): UiPrimitiveResult {
        val root = activeWindowRoot()
            ?: return UiPrimitiveResult.failed(
                reason = "当前屏幕没有可访问节点根节点",
                failureKind = UiActionFailureKind.PageChanged,
            )
        val editableNode = root.focusedSafeEditable()
            ?: return UiPrimitiveResult.failed(
                reason = "当前没有可提交的输入框",
                failureKind = UiActionFailureKind.EditableNotFound,
            )
        if (submitUiSideEffect { editableNode.performImeSearchAction() }) {
            return UiPrimitiveResult.succeeded("已执行回车/确认")
        }
        val submitCandidate = root.findSearchSubmitCandidate(editableNode)
        if (submitCandidate != null && activateCandidate(submitCandidate)) {
            return UiPrimitiveResult.succeeded("已点击输入框旁的提交入口（当前系统不支持输入法回车）")
        }
        return UiPrimitiveResult.failed(
            reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "输入法回车动作未被接受"
            } else {
                "当前系统不支持输入法回车，且未找到输入框旁的提交入口"
            },
            failureKind = UiActionFailureKind.SubmitNotFound,
        )
    }

    /**
     * Deletes one character before the cursor on the focused editable.
     *
     * Prefers a selection-aware edit: reading `textSelectionStart/End` lets us remove the character the
     * cursor is actually behind and then restore the caret. The previous unconditional
     * `SET_TEXT(dropLast(1))` always deleted the last character and slammed the caret to the end, so
     * editing mid-string removed the wrong character and any in-flight pinyin composing text was
     * destroyed. `SET_TEXT` remains the fallback when no usable selection is reported.
     */
    private fun deleteLastCharPrimitive(): UiPrimitiveResult {
        val root = activeWindowRoot()
            ?: return UiPrimitiveResult.failed(
                reason = "当前屏幕没有可访问节点根节点",
                failureKind = UiActionFailureKind.PageChanged,
            )
        val editableNode = root.focusedSafeEditable()
            ?: return UiPrimitiveResult.failed(
                reason = "当前没有可编辑的输入框",
                failureKind = UiActionFailureKind.EditableNotFound,
            )
        val current = editableNode.text?.toString().orEmpty()
        if (current.isEmpty()) {
            return UiPrimitiveResult.succeeded("输入框已为空，无需删除")
        }
        val edit = backspaceEdit(current, editableNode.textSelectionStart, editableNode.textSelectionEnd)
        if (!setTextDirectly(editableNode, edit.text)) {
            return UiPrimitiveResult.failed(
                reason = "删除字符动作未被接受",
                failureKind = UiActionFailureKind.NodeNotFound,
            )
        }
        // Best-effort caret restore: SET_TEXT leaves the cursor at the end, which would silently turn a
        // mid-string edit into "and now everything you type goes to the end".
        restoreSelection(editableNode, edit.selection)
        return UiPrimitiveResult.succeeded("已删除光标前的字符")
    }

    private fun restoreSelection(editableNode: AccessibilityNodeInfo, selection: Int) {
        if (selection < 0) return
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selection)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selection)
        }
        runCatching {
            submitUiSideEffect {
                editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            }
        }
    }

    private fun dispatchSwipeGesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMillis: Long): Boolean {
        throwIfInterrupted()
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatchStrokeGesture(path, durationMillis)
    }

    private fun dispatchLongPressGesture(x: Int, y: Int, holdMillis: Long): Boolean {
        throwIfInterrupted()
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        return dispatchStrokeGesture(path, holdMillis)
    }

    private fun dispatchStrokeGesture(path: Path, durationMillis: Long): Boolean {
        val bounded = durationMillis.coerceIn(
            MIN_UI_GESTURE_DURATION_MILLIS,
            MAX_UI_LONG_PRESS_HOLD_MILLIS,
        )
        return dispatchGestureOutcome(path = path, durationMillis = bounded).performed
    }

    private fun activateCandidate(candidate: NodeCandidate): Boolean {
        throwIfInterrupted()
        val clickNode = candidate.node.clickableSelfOrAncestor()
        val preferredPoint = candidate.node.searchEntryFallbackTapPoint(candidate.label)
        val gestureBounds = candidate.node.safeBounds() ?: clickNode?.safeBounds()
        val tapPoint = preferredPoint ?: gestureBounds?.let { bounds -> bounds.centerX to bounds.centerY }
        val checkPoint = tapPoint ?: clickNode?.safeBounds()?.let { bounds -> bounds.centerX to bounds.centerY }
        if (checkPoint == null) throw CriticalControlTapRefused(CriticalTapRefusalReason.MissingBounds)
        refuseCriticalTapAt(checkPoint.first, checkPoint.second, candidate.node)
        val outcome = tapPoint
            ?.let { (x, y) -> dispatchGestureOutcome(tapPathAt(x, y), TAP_GESTURE_STROKE_DURATION_MILLIS) }
            ?: GestureOutcome.NotAccepted
        if (outcome.performed) return true
        // Only compensate with ACTION_CLICK when the framework positively told us no touch landed.
        // On TimedOut the gesture may well have been delivered and merely reported late, and a second
        // activation there is a real double-tap — on a payment or "confirm order" button that is a
        // duplicate irreversible action, so we would rather report failure and let the caller re-plan
        // against a fresh observation than risk acting twice.
        if (!outcome.allowsFallbackClick) return false
        clickNode?.let(::refuseCriticalContainerClick)
        return clickNode?.let { node ->
            submitUiSideEffect { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        } == true
    }

    private fun tapOcrGroundingHint(
        root: AccessibilityNodeInfo,
        target: String,
        hint: UiOcrGroundingHint?,
    ): UiPrimitiveResult? {
        val safeHint = hint?.takeIf { candidate -> candidate.matchesCurrentWindow(root) } ?: return null
        val performed = dispatchTapGesture(safeHint.bounds.centerX, safeHint.bounds.centerY)
        return if (performed) {
            UiPrimitiveResult.succeeded("已根据 OCR 证据点击目标：${safeHint.text.ifBlank { target }}")
        } else {
            UiPrimitiveResult.failed(
                reason = "OCR 目标点击未被系统接受：${safeHint.text.ifBlank { target }}",
                failureKind = missingTargetFailureKind(target),
            )
        }
    }

    private fun tapSubmitSearchOcrGrounding(
        root: AccessibilityNodeInfo,
        hint: UiOcrGroundingHint?,
    ): UiPrimitiveResult? =
        tapOcrGroundingHint(
            root = root,
            target = "提交搜索",
            hint = hint?.takeIf { candidate -> candidate.matchesSubmitSearchText() },
        )

    private fun dismissTransientSearchOverlay(root: AccessibilityNodeInfo): Boolean {
        // Same fail-closed rule as `blockingOverlayDismissTarget` on the executor side: never
        // auto-tap a dismiss affordance on a window that also carries a payment/send/delete/authorize
        // control. The system-back fallback below stays available (back cancels, it never confirms).
        root.takeUnless { it.hasDangerousActionControlNode() }
            ?.findTransientOverlayDismissCandidate()
            ?.let { candidate ->
            if (activateCandidate(candidate)) {
                sleepForUiIdle(DEFAULT_POST_ACTION_WAIT_MILLIS)
                return true
            }
        }
        if (!root.looksLikeSearchBlockingOverlay()) return false
        throwIfInterrupted()
        val dismissed = submitUiSideEffect { performGlobalAction(GLOBAL_ACTION_BACK) }
        if (dismissed) sleepForUiIdle(DEFAULT_POST_ACTION_WAIT_MILLIS)
        return dismissed
    }

    private fun prepareEditableForTextInput(editableNode: AccessibilityNodeInfo) {
        throwIfInterrupted()
        submitUiSideEffect { editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val bounds = editableNode.safeBounds() ?: return
        dispatchTapGesture(bounds.centerX, bounds.centerY)
        sleepForUiIdle(DEFAULT_POST_ACTION_WAIT_MILLIS)
    }

    private fun setTextDirectly(editableNode: AccessibilityNodeInfo, text: String): Boolean {
        throwIfInterrupted()
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return submitUiSideEffect {
            editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    private fun pasteTextIntoEditable(editableNode: AccessibilityNodeInfo, text: String): Boolean {
        throwIfInterrupted()
        val clipboard = getSystemService(ClipboardManager::class.java)
            ?.let(::AndroidPasteClipboard)
            ?: return false
        // Remember what we displaced so the restore can verify the clipboard is still ours, and so
        // lifecycle teardown can finish the job: whatever the user was typing (a search term, an
        // address) must not be left sitting in the system clipboard for other apps to read.
        val restoreToken = clipboardRestorer.writeForPaste(clipboard, text) {
            submitUiSideEffect { clipboard.setText(text) }
        }
        try {
            return submitUiSideEffect {
                editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        } finally {
            // Scheduled even when the paste itself is refused or throws: the clipboard already holds
            // the user's text at this point and must be handed back.
            mainHandler.postDelayed(
                {
                    // Not lease-guarded: an interrupt, reconnect or teardown racing the paste must
                    // still hand the clipboard back. Token-scoped and content-checked, so a newer
                    // paste or a clip the user copied since is never touched.
                    clipboardRestorer.restore(clipboard, restoreToken)
                },
                DEFAULT_POST_ACTION_WAIT_MILLIS,
            )
        }
    }

    /**
     * Last-resort clipboard drain for interrupt and service teardown.
     *
     * Runs outside the task-lease guard on purpose: by the time [onInterrupt] / [onDestroy] /
     * [onUnbind] fires the lease is already invalidated, so the normal restore path would decline and
     * the pasted text would survive. Still content-checked, so a clip the user created after us is
     * untouched.
     */
    private fun drainPendingClipboardOnTeardown(token: Long? = null) {
        if (clipboardRestorer.pendingToken() == null) return
        val clipboard = runCatching { getSystemService(ClipboardManager::class.java) }
            .getOrNull()
            ?.let(::AndroidPasteClipboard)
        clipboardRestorer.drain(clipboard, token)
    }

    private fun findEditableForTextInput(
        root: AccessibilityNodeInfo,
        target: String?,
        timeoutMillis: Long,
    ): EditableFocusResult {
        val normalizedTarget = target?.trim().orEmpty()
        if (normalizedTarget.isBlank()) {
            return root.findFocusedEditableForTyping()
                ?.let { EditableFocusResult.Found(it) }
                ?: EditableFocusResult.Failed(
                    reason = "当前屏幕没有已聚焦的可输入文本框",
                    failureKind = UiActionFailureKind.EditableNotFound,
                )
        }
        val kind = UiTargetResolver.kindForTarget(normalizedTarget)
        if (kind == UiTargetKind.EditableField) {
            return root.findFocusedEditableForTyping()
                ?.let { EditableFocusResult.Found(it) }
                ?: EditableFocusResult.Failed(
                    reason = "当前屏幕没有已聚焦的可输入文本框",
                    failureKind = UiActionFailureKind.EditableNotFound,
                )
        }
        if (kind == UiTargetKind.SearchEntry) {
            root.findFocusedEditableForTyping()?.let { return EditableFocusResult.Found(it) }
            return focusSearchEditableFromEntry(root, normalizedTarget, timeoutMillis)
        }

        val targetNode = root.findTargetCandidate(normalizedTarget)?.node
        if (targetNode?.isSafeEditableForTyping() == true) return EditableFocusResult.Found(targetNode)
        if (targetNode != null) {
            val candidate = NodeCandidate(
                node = targetNode,
                id = "target_${targetNode.fingerprint().shortStableHash()}",
                label = targetNode.nodeSearchLabel(),
            )
            activateCandidate(candidate)
            waitForEditable(timeoutMillis)?.let { return EditableFocusResult.Found(it) }
        }
        return EditableFocusResult.Failed(
            reason = "未找到可输入目标：$normalizedTarget",
            failureKind = UiActionFailureKind.EditableNotFound,
        )
    }

    private fun focusEditableFromOcrGrounding(
        root: AccessibilityNodeInfo,
        target: String?,
        hint: UiOcrGroundingHint?,
        timeoutMillis: Long,
    ): EditableFocusResult? {
        val safeHint = hint?.takeIf { candidate ->
            candidate.matchesCurrentWindow(root) &&
                (target.isNullOrBlank() || candidate.matchesTargetText(target))
        } ?: return null
        if (!dispatchTapGesture(safeHint.bounds.centerX, safeHint.bounds.centerY)) {
            return EditableFocusResult.Failed(
                reason = "OCR 输入目标点击未被系统接受：${safeHint.text.ifBlank { target.orEmpty() }}",
                failureKind = missingTargetFailureKind(target.orEmpty()),
            )
        }
        sleepForUiIdle(DEFAULT_POST_ACTION_WAIT_MILLIS)
        waitForEditable(timeoutMillis)?.let { return EditableFocusResult.Found(it) }
        val refreshedRoot = activeWindowRoot() ?: root
        refreshedRoot.findFocusedEditableForTyping()?.let { return EditableFocusResult.Found(it) }
        return EditableFocusResult.Failed(
            reason = "已根据 OCR 证据点击目标：${safeHint.text.ifBlank { target.orEmpty() }}，但未出现可输入文本框",
            failureKind = UiActionFailureKind.EditableNotFound,
        )
    }

    private fun focusSearchEditableFromEntry(
        initialRoot: AccessibilityNodeInfo,
        target: String,
        timeoutMillis: Long,
    ): EditableFocusResult {
        initialRoot.findFocusedEditableForTyping()?.let { return EditableFocusResult.Found(it) }
        var currentRoot = initialRoot
        val attemptedFingerprints = mutableSetOf<String>()
        var matchedCandidates = 0
        var activatedCandidates = 0
        val perAttemptWaitMillis = (timeoutMillis / MAX_SEARCH_ENTRY_FOCUS_ATTEMPTS)
            .coerceIn(DEFAULT_POST_ACTION_WAIT_MILLIS, MAX_SEARCH_ENTRY_FOCUS_WAIT_MILLIS)

        repeat(MAX_SEARCH_ENTRY_FOCUS_ATTEMPTS) {
            val candidate = currentRoot.findTargetCandidates(
                target = target,
                predicate = { candidate ->
                    candidate.node.isEnabled &&
                        candidate.node.fingerprint() !in attemptedFingerprints &&
                        (
                            candidate.node.isEditable ||
                                candidate.node.clickableSelfOrAncestor() != null ||
                                candidate.node.searchEntryFallbackTapPoint(candidate.label) != null
                            )
                },
                limit = MAX_SEARCH_ENTRY_FOCUS_ATTEMPTS,
            ).firstOrNull() ?: return@repeat

            matchedCandidates += 1
            val candidateFingerprint = candidate.node.fingerprint()
            attemptedFingerprints += candidateFingerprint
            if (candidate.node.isEditable) {
                return EditableFocusResult.Found(candidate.node)
            }
            // A refused candidate is one search entry ruled out, not the end of the search for one.
            val activated = try {
                activateCandidate(candidate)
            } catch (_: CriticalControlTapRefused) {
                false
            }
            if (!activated) {
                return@repeat
            }
            activatedCandidates += 1
            waitForEditable(perAttemptWaitMillis)?.let { return EditableFocusResult.Found(it) }
            val refreshedRoot = activeWindowRoot() ?: currentRoot
            refreshedRoot.findFocusedEditableForTyping()?.let { return EditableFocusResult.Found(it) }
            if (dismissTransientSearchOverlay(refreshedRoot)) {
                attemptedFingerprints -= candidateFingerprint
                waitForEditable(perAttemptWaitMillis)?.let { return EditableFocusResult.Found(it) }
            }
            currentRoot = activeWindowRoot() ?: refreshedRoot
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
            if (Thread.currentThread().isInterrupted) throw InterruptedException("UI action cancelled")
            activeWindowRoot()?.findFocusedEditableForTyping()?.let { return it }
            sleepForUiIdle(SEARCH_ENTRY_FOCUS_POLL_MILLIS)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    private fun throwIfInterrupted() {
        deviceControlTask.get()?.requireActive()
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("UI action cancelled")
        }
    }

    companion object {
        internal fun readCurrentScreenText(maxChars: Int): CurrentScreenTextReadResult {
            val task = deviceControlTasks.startTask()
                ?: return CurrentScreenTextReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return CurrentScreenTextReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在读取当前屏幕")
            return runDeviceControlWithTimeout(
                task = task,
                timeoutMillis = OBSERVE_HARD_TIMEOUT_MILLIS,
                fallback = { CurrentScreenTextReadResult.Failed("当前屏幕文本读取超时") },
            ) {
                service.readSnapshot(maxChars)
            }
        }

        internal fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
            val task = deviceControlTasks.startTask()
                ?: return ScreenStateReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return ScreenStateReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在观察当前屏幕")
            return runDeviceControlWithTimeout(
                task = task,
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

        internal fun performTap(
            target: String,
            timeoutMillis: Long,
            ocrGroundingHint: UiOcrGroundingHint? = null,
        ): UiActionReadResult {
            val task = deviceControlTasks.startTask()
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在点击：$target")
            return runDeviceControlWithTimeout(task, timeoutMillis.uiActionHardTimeout()) {
                service.tapTarget(target, timeoutMillis, ocrGroundingHint)
            }
        }

        /**
         * Perform a tap at normalized 0-1000 coordinates.
         *
         * Used when the model outputs explicit normalized coordinates rather than a named target.
         * The coordinates are resolution-agnostic: (500, 500) always means the screen center.
         */
        internal fun performTapByNormalizedCoords(
            normalizedX: Int,
            normalizedY: Int,
            timeoutMillis: Long,
        ): UiActionReadResult {
            val task = deviceControlTasks.startTask()
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在点击坐标：($normalizedX, $normalizedY)")
            return runDeviceControlWithTimeout(task, timeoutMillis.uiActionHardTimeout()) {
                service.tapByNormalizedCoords(normalizedX, normalizedY, timeoutMillis)
            }
        }

        internal fun performTypeText(
            text: String,
            target: String?,
            timeoutMillis: Long,
            ocrGroundingHint: UiOcrGroundingHint? = null,
            allowClipboardPasteFallback: Boolean = false,
        ): UiActionReadResult {
            val task = deviceControlTasks.startTask()
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在输入文本")
            return runDeviceControlWithTimeout(task, timeoutMillis.uiActionHardTimeout()) {
                service.typeText(
                    text = text,
                    target = target,
                    timeoutMillis = timeoutMillis,
                    ocrGroundingHint = ocrGroundingHint,
                    allowClipboardPasteFallback = allowClipboardPasteFallback,
                )
            }
        }

        internal fun performSubmitSearch(
            timeoutMillis: Long,
            ocrGroundingHint: UiOcrGroundingHint? = null,
        ): UiActionReadResult {
            val task = deviceControlTasks.startTask()
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在提交搜索")
            return runDeviceControlWithTimeout(task, timeoutMillis.uiActionHardTimeout()) {
                service.submitSearch(timeoutMillis, ocrGroundingHint)
            }
        }

        internal fun performScroll(
            direction: UiScrollDirection,
            target: String?,
            timeoutMillis: Long,
        ): UiActionReadResult {
            val task = deviceControlTasks.startTask()
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在滚动页面")
            return runDeviceControlWithTimeout(task, timeoutMillis.uiActionHardTimeout()) {
                service.scrollTarget(direction, target, timeoutMillis)
            }
        }

        internal fun performSwipe(
            startXNorm: Int,
            startYNorm: Int,
            endXNorm: Int,
            endYNorm: Int,
            durationMillis: Long,
            timeoutMillis: Long,
        ): UiActionReadResult {
            val task = deviceControlTasks.startTask()
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在滑动屏幕")
            return runDeviceControlWithTimeout(task, timeoutMillis.uiActionHardTimeout()) {
                service.swipeByNormalizedCoords(
                    startXNorm = startXNorm,
                    startYNorm = startYNorm,
                    endXNorm = endXNorm,
                    endYNorm = endYNorm,
                    durationMillis = durationMillis,
                    timeoutMillis = timeoutMillis,
                )
            }
        }

        internal fun performLongPress(
            xNorm: Int,
            yNorm: Int,
            holdMillis: Long,
            timeoutMillis: Long,
        ): UiActionReadResult {
            val task = deviceControlTasks.startTask()
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在长按屏幕")
            return runDeviceControlWithTimeout(task, timeoutMillis.uiActionHardTimeout()) {
                service.longPressByNormalizedCoords(
                    xNorm = xNorm,
                    yNorm = yNorm,
                    holdMillis = holdMillis,
                    timeoutMillis = timeoutMillis,
                )
            }
        }

        internal fun performPressKey(key: UiSystemKey, timeoutMillis: Long): UiActionReadResult {
            val task = deviceControlTasks.startTask()
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在执行系统按键")
            return runDeviceControlWithTimeout(task, timeoutMillis.uiActionHardTimeout()) {
                service.pressSystemKey(key, timeoutMillis)
            }
        }

        internal fun performPressBack(timeoutMillis: Long): UiActionReadResult {
            val task = deviceControlTasks.startTask()
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在返回上一页")
            return runDeviceControlWithTimeout(task, timeoutMillis.uiActionHardTimeout()) {
                service.pressBack(timeoutMillis)
            }
        }

        internal fun performWait(timeoutMillis: Long): UiActionReadResult {
            val task = deviceControlTasks.startTask()
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return UiActionReadResult.PermissionDenied("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在等待页面稳定")
            return runDeviceControlWithTimeout(task, timeoutMillis.uiActionHardTimeout()) {
                service.waitForScreen(timeoutMillis)
            }
        }

        /**
         * Captures the current screen as transient compacted JPEG bytes for the opt-in remote-vision
         * GUI automation path, via [AccessibilityService.takeScreenshot]. Fails closed to
         * [RawScreenshotReadResult.Failed] when the accessibility service is not connected. [requestId]
         * is a correlation/log id only — there is no per-request MediaProjection consent on this path.
         */
        internal fun performTakeScreenshotRaw(requestId: String): RawScreenshotReadResult {
            val task = deviceControlTasks.startTask()
                ?: return RawScreenshotReadResult.Failed("未开启Solin无障碍服务")
            val service = task.owner as? SolinAccessibilityService
                ?: return RawScreenshotReadResult.Failed("未开启Solin无障碍服务")
            showControlProgress(task, service, "正在截取当前屏幕")
            return runDeviceControlWithTimeout(
                task = task,
                timeoutMillis = TAKE_SCREENSHOT_CAPTURE_TIMEOUT_MILLIS +
                    TAKE_SCREENSHOT_MIN_INTERVAL_MILLIS +
                    UI_ACTION_TIMEOUT_SLACK_MILLIS,
                fallback = { RawScreenshotReadResult.Failed("当前屏幕截图超时") },
            ) {
                service.takeScreenshotRaw()
            }
        }

        internal fun showControlProgress(message: String) {
            (deviceControlTasks.currentOwner() as? SolinAccessibilityService)
                ?.showControlProgressOverlay(message)
        }

        internal fun hideControlProgress() {
            (deviceControlTasks.currentOwner() as? SolinAccessibilityService)
                ?.hideControlProgressOverlay()
        }

        private fun showControlProgress(
            task: DeviceControlTaskCoordinator.Lease,
            service: SolinAccessibilityService,
            message: String,
        ) {
            task.runIfActive {
                service.showControlProgressOverlay(message)
            }
        }

        private val deviceControlExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SolinDeviceControl").apply {
                isDaemon = true
            }
        }

        private fun <T> runDeviceControlWithTimeout(
            task: DeviceControlTaskCoordinator.Lease,
            timeoutMillis: Long,
            fallback: () -> T,
            operation: () -> T,
        ): T {
            val future: Future<T> = deviceControlExecutor.submit<T> {
                try {
                    task.requireActive()
                    deviceControlTask.set(task)
                    operation()
                } finally {
                    deviceControlTask.remove()
                    task.finish()
                }
            }
            task.attachFuture(future)
            return try {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                task.cancel(DeviceControlTaskCancellationReason.Timeout)
                future.cancel(true)
                fallback()
            } catch (_: Exception) {
                task.cancel(DeviceControlTaskCancellationReason.Failure)
                future.cancel(true)
                fallback()
            }
        }

        private fun runDeviceControlWithTimeout(
            task: DeviceControlTaskCoordinator.Lease,
            timeoutMillis: Long,
            operation: () -> UiActionReadResult,
        ): UiActionReadResult =
            runDeviceControlWithTimeout(
                task = task,
                timeoutMillis = timeoutMillis,
                fallback = {
                    uiActionTimeoutResult(task)
                },
                operation = operation,
            )

        private fun Long.uiActionHardTimeout(): Long =
            uiActionHardTimeoutMillis(this)
    }
}

/**
 * Outer watchdog budget for a UI action whose model-requested inner timeout is [requestedTimeoutMillis].
 *
 * Must stay strictly greater than everything [SolinAccessibilityService.executeUiAction] can spend:
 * the pre-action idle wait (up to the requested timeout, which is what `ui_wait` asks for), the two
 * bracketing full-tree observes, the post-action idle wait, and handler/binder slack. A fixed additive
 * margin cannot satisfy that — with `ui_wait` accepting up to 10s, a `+4000` watchdog fired while the
 * pre-action wait was still running, so every large timeout reported a spurious `Timeout` even though
 * nothing had gone wrong. Pure (no Android instances) so the arithmetic is unit-testable.
 */
internal fun uiActionHardTimeoutMillis(requestedTimeoutMillis: Long): Long {
    val requested = requestedTimeoutMillis
        .coerceIn(MIN_UI_ACTION_TIMEOUT_MILLIS, MAX_UI_ACTION_TIMEOUT_MILLIS)
    return requested + UI_ACTION_FIXED_OVERHEAD_MILLIS
}

/**
 * How long to let the UI settle after a primitive, given the model's requested `timeoutMillis`.
 *
 * The schema advertises `timeoutMillis` as the caller's patience for the action, but this was
 * previously `coerceAtMost(250)`, so a model asking for 3s never got more than 250ms and read a
 * half-rendered `after` snapshot. Honour the request, floored at [DEFAULT_POST_ACTION_WAIT_MILLIS] so
 * a tiny timeout still gets a usable settle window, and capped at [MAX_POST_ACTION_WAIT_MILLIS] so
 * the wait stays inside the overhead budget [uiActionHardTimeoutMillis] reserves for it.
 */
internal fun postActionWaitMillis(requestedTimeoutMillis: Long): Long =
    requestedTimeoutMillis.coerceIn(DEFAULT_POST_ACTION_WAIT_MILLIS, MAX_POST_ACTION_WAIT_MILLIS)

private fun <T> submitUiSideEffect(action: () -> T): T {
    val task = deviceControlTask.get()
    return task?.submitUiSideEffect(action) ?: action()
}

/** Like [submitUiSideEffect] for read-only framework calls: lease-checked, but not marked as a UI change. */
private fun <T> submitUiRead(action: () -> T): T {
    val task = deviceControlTask.get()
    return task?.submitUiRead(action) ?: action()
}

private val deviceControlTasks = DeviceControlTaskCoordinator()

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
    return "Solin · ${compact.take(64)}"
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
