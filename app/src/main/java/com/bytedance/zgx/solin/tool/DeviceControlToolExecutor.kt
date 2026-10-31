package com.bytedance.zgx.solin.tool

import android.provider.Settings
import com.bytedance.zgx.solin.SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL
import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.NormalizedTarget
import com.bytedance.zgx.solin.action.ScreenshotPerception
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_METADATA_POLICY
import com.bytedance.zgx.solin.device.DEVICE_CONTROL_SOURCE_ACCESSIBILITY
import com.bytedance.zgx.solin.device.AppSearchProgressEvidence
import com.bytedance.zgx.solin.device.AppSearchResultVerifier
import com.bytedance.zgx.solin.device.SearchResultVerification
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.device.UiSystemKey
import com.bytedance.zgx.solin.device.DEFAULT_UI_GESTURE_DURATION_MILLIS
import com.bytedance.zgx.solin.device.DEFAULT_UI_LONG_PRESS_HOLD_MILLIS
import com.bytedance.zgx.solin.device.blockingOverlayDismissTarget
import com.bytedance.zgx.solin.device.hasBlockingOverlay
import com.bytedance.zgx.solin.device.hasDangerousActionControl
import com.bytedance.zgx.solin.device.normalizedPointHitsDangerousControl
import com.bytedance.zgx.solin.device.tapTargetHitsDangerousControl
import com.bytedance.zgx.solin.device.toScreenNodesJsonString
import com.bytedance.zgx.solin.device.toScreenObservationJsonString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class DeviceControlToolExecutor(
    private val provider: CurrentScreenControlProvider?,
    private val preflightProvider: CurrentScreenControlProvider? = provider,
) : ToolExecutor {
    override fun execute(request: ToolRequest): ToolResult {
        val controlProvider = provider
            ?: return request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "当前屏幕控制服务不可用",
                retryable = true,
                data = request.deviceControlBaseData(),
            )
        return when (request.toolName) {
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN -> executeObserve(request, controlProvider)
            MobileActionFunctions.UI_TAP -> executeTap(request, controlProvider)
            MobileActionFunctions.UI_TYPE_TEXT -> executeTypeText(request, controlProvider)
            MobileActionFunctions.UI_SUBMIT_SEARCH -> executeSubmitSearch(request, controlProvider)
            MobileActionFunctions.UI_SCROLL -> executeScroll(request, controlProvider)
            MobileActionFunctions.UI_SWIPE -> executeSwipe(request, controlProvider)
            MobileActionFunctions.UI_LONG_PRESS -> executeLongPress(request, controlProvider)
            MobileActionFunctions.UI_PRESS_KEY -> executePressKey(request, controlProvider)
            MobileActionFunctions.UI_PRESS_BACK -> executePressBack(request, controlProvider)
            MobileActionFunctions.UI_WAIT -> executeWait(request, controlProvider)
            else -> request.unknownToolFailure()
        }
    }

    private fun executeObserve(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val maxTextChars = request.arguments["maxTextChars"]?.toIntOrNull() ?: DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS
        val maxNodes = request.arguments["maxNodes"]?.toIntOrNull() ?: DEFAULT_MAX_SCREEN_STATE_NODES
        // Foreground-readiness poll: the post-launch in-app continuation threads an expectedPackageName
        // (ToolPlanCoordinator.planPostLaunchInAppContinuation) and fires the first observe the instant
        // open_app's startActivity() returns — before the launched app is actually resumed. Without a
        // settle, observe reads the cross-app transition window (activeWindowRoot() momentarily null or
        // Solin's own window) and fails. Bounded-poll observeCurrentScreen until the foreground package
        // matches the expected target, then map as usual. No added latency when expectedPackageName is
        // absent or the target is already foreground.
        val expectedPackage = request.expectedForegroundPackageName()
        // runBlocking WITHOUT a dispatcher on purpose: the coroutine runs on this thread's own
        // event loop, so the settle wait consumes no additional pooled thread. Passing
        // Dispatchers.IO here would take a SECOND IO thread while this call is often already
        // nested inside RoutingToolExecutor.executeBatch's own runBlocking(Dispatchers.IO),
        // which risks IO-pool starvation on a batch of device-control observes.
        val result = runBlocking {
            awaitSettledScreenObservation(
                provider = provider,
                maxTextChars = maxTextChars,
                maxNodes = maxNodes,
                expectedPackage = expectedPackage,
            )
        }
        if (expectedPackage != null && (result as? ScreenStateReadResult.Available)?.snapshot?.packageName != expectedPackage) {
            // Report Timeout (NOT app_not_foreground) so the observation coordinator keeps this
            // eligible for bounded settle-recovery rounds before falling back to open-then-stop.
            return request.failed(
                code = ToolErrorCode.ExecutionFailed,
                summary = "目标应用尚未进入前台，稍后重试观察。",
                retryable = true,
                data = request.deviceControlBaseData() + mapOf(
                    "failureKind" to UiActionFailureKind.Timeout.schemaValue,
                    "expectedPackageName" to expectedPackage,
                ),
            )
        }
        return when (result) {
            is ScreenStateReadResult.Available ->
                request.succeeded(
                    summary = result.snapshot.observationSummary(),
                    data = request.deviceControlBaseData() + result.snapshot.toObservationData(
                        requestedMaxTextChars = maxTextChars,
                        requestedMaxNodes = maxNodes,
                    ),
                )

            is ScreenStateReadResult.PermissionDenied ->
                request.deviceControlPermissionDenied(result.reason)

            is ScreenStateReadResult.Failed ->
                request.failed(
                    code = ToolErrorCode.ExecutionFailed,
                    summary = result.reason,
                    retryable = true,
                    data = request.deviceControlBaseData() + mapOf(
                        "failureKind" to result.failureKind.schemaValue,
                    ),
                )
        }
    }

    /**
     * Bounded, COOPERATIVELY CANCELLABLE foreground-settle poll.
     *
     * WHY suspend + [delay] instead of `Thread.sleep`: this executor runs on a coroutine
     * dispatcher thread (the batch path uses `Dispatchers.IO`, and
     * [TimeoutToolExecutionBoundary] wraps every call in `withTimeoutOrNull` + `withContext`).
     * `Thread.sleep` parked that pooled thread for up to
     * FOREGROUND_READINESS_POLL_MAX_ATTEMPTS * FOREGROUND_READINESS_POLL_INTERVAL_MILLIS
     * (1.5s) and — being uninterruptible by coroutine cancellation — kept it parked even
     * after the caller had already given up. `delay` suspends instead of blocking, so the
     * wait releases the thread and aborts as soon as its scope is cancelled (including
     * thread interruption, which [runBlocking] converts into job cancellation).
     *
     * Scope caveat, deliberately left for a later batch: [ToolExecutor.execute] is a
     * synchronous interface, so the [runBlocking] in [executeObserve] still starts a fresh
     * root job rather than a child of the boundary's job — an outer `withTimeoutOrNull`
     * therefore does not yet pre-empt this wait. Making the boundary's timeout fully
     * pre-emptive requires threading a suspend execution path through [ToolExecutor], a
     * larger change than this fix. Only the wait is made interruptible here; the provider
     * read itself stays blocking.
     *
     * Two poll conditions, unchanged from the previous loops:
     *  - [expectedPackage] non-null: the post-launch in-app continuation threads an
     *    expectedPackageName, so retry until the observed foreground package matches.
     *  - [expectedPackage] null: a REMOTE-planned launch emits open_app_by_name with only
     *    appName, so no expectedPackageName is ever threaded. Retry while the read is a
     *    transient Failed. PermissionMissing is NOT transient and stops the poll at once,
     *    and PermissionDenied is a distinct subtype that never matches here.
     */
    private suspend fun awaitSettledScreenObservation(
        provider: CurrentScreenControlProvider,
        maxTextChars: Int,
        maxNodes: Int,
        expectedPackage: String?,
    ): ScreenStateReadResult {
        var result = provider.observeCurrentScreen(maxTextChars = maxTextChars, maxNodes = maxNodes)
        var attempt = 0
        while (attempt < FOREGROUND_READINESS_POLL_MAX_ATTEMPTS &&
            result.needsForegroundSettleRetry(expectedPackage)
        ) {
            delay(FOREGROUND_READINESS_POLL_INTERVAL_MILLIS)
            result = provider.observeCurrentScreen(maxTextChars = maxTextChars, maxNodes = maxNodes)
            attempt++
        }
        return result
    }

    private fun ScreenStateReadResult.needsForegroundSettleRetry(expectedPackage: String?): Boolean =
        if (expectedPackage != null) {
            (this as? ScreenStateReadResult.Available)?.snapshot?.packageName != expectedPackage
        } else {
            (this as? ScreenStateReadResult.Failed)
                ?.failureKind
                ?.let { it != UiActionFailureKind.PermissionMissing } == true
        }

    private fun executeTap(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        // Support normalized 0-1000 coordinate targeting (inspired by Open-AutoGLM).
        // When targetX/targetY are present, use direct coordinate tapping instead of
        // named-target resolution. This is useful for canvas-based UIs where Accessibility
        // nodes don't provide useful target labels.
        val normalizedTarget = NormalizedTarget.fromArguments(request.arguments)
        if (normalizedTarget != null) {
            val targetLabel = "坐标(${normalizedTarget.x}, ${normalizedTarget.y})"
            // A normalized-coordinate tap is a raw-pixel action with no named-target second layer
            // (like swipe/long_press/press_key), so this preflight is the sole content-aware guard
            // between the model and the pixel. Fail CLOSED when the screen cannot be confirmed
            // dangerous-free — otherwise a remote-vision coordinate tap could land on a payment/send
            // control on a momentarily-unreadable screen. Named-target taps below keep fail-open
            // because their node matching provides the second layer.
            guardedActionPreflight(
                request,
                actionType = "tap",
                target = targetLabel,
                dangerScope = DangerScope.Point(normalizedTarget.x, normalizedTarget.y),
                failClosedOnUnavailable = true,
                dismissBlockingOverlays = false,
            )?.let { return it }
            return actionResult(
                request = request,
                actionType = "tap_normalized",
                target = targetLabel,
                result = provider.tapByNormalizedCoords(
                    normalizedX = normalizedTarget.x,
                    normalizedY = normalizedTarget.y,
                    timeoutMillis = request.timeoutMillis(),
                ).withExpectedForegroundPackageVerification(request),
            )
        }
        val target = request.arguments["target"].orEmpty()
        return guardedAction(
            request,
            actionType = "tap",
            target = target,
            dangerScope = DangerScope.Target(target),
            failClosedOnUnavailable = false,
            dismissBlockingOverlays = true,
        ) {
            provider.tap(
                target = target,
                timeoutMillis = request.timeoutMillis(),
            )
        }
    }

    private fun executeTypeText(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val target = request.arguments["target"].orEmpty()
        return guardedAction(
            request,
            actionType = "type_text",
            target = target,
            dangerScope = DangerScope.None,
            failClosedOnUnavailable = false,
            dismissBlockingOverlays = true,
        ) {
            provider.typeText(
                text = request.arguments["text"].orEmpty(),
                target = request.arguments["target"],
                timeoutMillis = request.timeoutMillis(),
                allowClipboardPasteFallback = request.allowClipboardPasteFallback(),
            )
        }
    }

    private fun executeSubmitSearch(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        return guardedAction(
            request,
            actionType = "submit_search",
            target = "",
            dangerScope = DangerScope.WholeScreen,
            failClosedOnUnavailable = false,
            dismissBlockingOverlays = false,
        ) {
            provider.submitSearch(
                timeoutMillis = request.timeoutMillis(),
            )
        }
    }

    private fun executeScroll(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val direction = UiScrollDirection.fromSchemaValue(request.arguments["direction"])
            ?: return request.invalidDeviceControlRequest("不支持的滚动方向：${request.arguments["direction"]}")
        val target = request.arguments["target"].orEmpty()
        return guardedAction(
            request,
            actionType = "scroll",
            target = target,
            dangerScope = DangerScope.None,
            failClosedOnUnavailable = false,
            dismissBlockingOverlays = false,
            extraData = mapOf("direction" to direction.schemaValue),
        ) {
            provider.scroll(
                direction = direction,
                target = request.arguments["target"],
                timeoutMillis = request.timeoutMillis(),
            )
        }
    }

    private fun executeSwipe(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val startX = request.arguments["startXNorm"]?.trim()?.toIntOrNull()
        val startY = request.arguments["startYNorm"]?.trim()?.toIntOrNull()
        val endX = request.arguments["endXNorm"]?.trim()?.toIntOrNull()
        val endY = request.arguments["endYNorm"]?.trim()?.toIntOrNull()
        if (startX == null || startY == null || endX == null || endY == null ||
            startX !in 0..1000 || startY !in 0..1000 || endX !in 0..1000 || endY !in 0..1000
        ) {
            return request.invalidDeviceControlRequest("滑动坐标必须为 0-1000 的归一化整数：startXNorm/startYNorm/endXNorm/endYNorm")
        }
        val durationMillis = request.arguments["durationMillis"]?.trim()?.toLongOrNull()
            ?: DEFAULT_UI_GESTURE_DURATION_MILLIS
        // No named target: the dangerous-action preflight guards against the whole current screen.
        return guardedAction(
            request,
            actionType = "swipe",
            target = "",
            dangerScope = DangerScope.WholeScreen,
            failClosedOnUnavailable = true,
            dismissBlockingOverlays = false,
        ) {
            provider.swipe(
                startXNorm = startX,
                startYNorm = startY,
                endXNorm = endX,
                endYNorm = endY,
                durationMillis = durationMillis,
                timeoutMillis = request.timeoutMillis(),
            )
        }
    }

    private fun executeLongPress(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val xNorm = request.arguments["xNorm"]?.trim()?.toIntOrNull()
        val yNorm = request.arguments["yNorm"]?.trim()?.toIntOrNull()
        if (xNorm == null || yNorm == null || xNorm !in 0..1000 || yNorm !in 0..1000) {
            return request.invalidDeviceControlRequest("长按坐标必须为 0-1000 的归一化整数：xNorm/yNorm")
        }
        val holdMillis = request.arguments["holdMillis"]?.trim()?.toLongOrNull()
            ?: DEFAULT_UI_LONG_PRESS_HOLD_MILLIS
        return guardedAction(
            request,
            actionType = "long_press",
            target = "",
            dangerScope = DangerScope.WholeScreen,
            failClosedOnUnavailable = true,
            dismissBlockingOverlays = false,
        ) {
            provider.longPress(
                xNorm = xNorm,
                yNorm = yNorm,
                holdMillis = holdMillis,
                timeoutMillis = request.timeoutMillis(),
            )
        }
    }

    private fun executePressKey(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val key = UiSystemKey.fromSchemaValue(request.arguments["key"])
            ?: return request.invalidDeviceControlRequest("不支持的系统按键：${request.arguments["key"]}（仅支持 home/recents/enter/delete）")
        return guardedAction(
            request,
            actionType = "press_key",
            target = key.schemaValue,
            dangerScope = DangerScope.WholeScreen,
            failClosedOnUnavailable = true,
            dismissBlockingOverlays = false,
            extraData = mapOf("key" to key.schemaValue),
        ) {
            provider.pressKey(
                key = key,
                timeoutMillis = request.timeoutMillis(),
            )
        }
    }

    /**
     * Runs [guardedActionPreflight] and, only if it passes, [perform]s the action and verifies the
     * expected foreground package afterwards. The two safety flags stay required for the reasons
     * documented on [guardedActionPreflight].
     */
    private fun guardedAction(
        request: ToolRequest,
        actionType: String,
        target: String,
        dangerScope: DangerScope,
        failClosedOnUnavailable: Boolean,
        dismissBlockingOverlays: Boolean,
        extraData: Map<String, String> = emptyMap(),
        perform: () -> UiActionReadResult,
    ): ToolResult {
        guardedActionPreflight(
            request,
            actionType = actionType,
            target = target,
            dangerScope = dangerScope,
            failClosedOnUnavailable = failClosedOnUnavailable,
            dismissBlockingOverlays = dismissBlockingOverlays,
        )?.let { return it }
        return actionResult(
            request = request,
            actionType = actionType,
            target = target,
            result = perform().withExpectedForegroundPackageVerification(request),
            extraData = extraData,
        )
    }

    /**
     * The preflight chain every mutating UI action runs before touching the screen:
     * expected-foreground-package check, then the dangerous-control check, then (only when
     * [dismissBlockingOverlays]) a bounded overlay dismissal followed by a second dangerous-control
     * check, because closing an overlay can reveal a dangerous control the first check never saw.
     *
     * All three flags are required at every call site on purpose — they are the per-action safety
     * differences: [dangerScope] is what the action can activate (see [DangerScope]);
     * [failClosedOnUnavailable] is true only for actions with no named-target second layer
     * (normalized tap, swipe, long_press, press_key); overlay dismissal + re-check applies only to
     * named-target tap and type_text.
     */
    private fun guardedActionPreflight(
        request: ToolRequest,
        actionType: String,
        target: String,
        dangerScope: DangerScope,
        failClosedOnUnavailable: Boolean,
        dismissBlockingOverlays: Boolean,
    ): ToolResult? {
        expectedForegroundPackagePreflight(request, actionType = actionType, target = target)?.let { return it }
        dangerousUiActionPreflight(
            request,
            actionType = actionType,
            target = target,
            dangerScope = dangerScope,
            failClosedOnUnavailable = failClosedOnUnavailable,
        )?.let { return it }
        if (dismissBlockingOverlays) {
            dismissBlockingOverlaysIfPresent()
            dangerousUiActionPreflight(
                request,
                actionType = actionType,
                target = target,
                dangerScope = dangerScope,
                failClosedOnUnavailable = failClosedOnUnavailable,
            )?.let { return it }
        }
        return null
    }

    private fun expectedForegroundPackagePreflight(
        request: ToolRequest,
        actionType: String,
        target: String,
    ): ToolResult? {
        val expectedPackage = request.expectedForegroundPackageName() ?: return null
        return when (
            val result = preflightProvider?.observeCurrentScreen(
                maxTextChars = DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS,
                maxNodes = DEFAULT_MAX_SCREEN_STATE_NODES,
            )
        ) {
            is ScreenStateReadResult.Available -> {
                val snapshot = result.snapshot
                if (snapshot.packageName == expectedPackage) {
                    null
                } else {
                    request.foregroundPackageGateFailure(
                        actionType = actionType,
                        target = target,
                        expectedPackage = expectedPackage,
                        actualPackage = snapshot.packageName,
                        snapshot = snapshot,
                    )
                }
            }

            is ScreenStateReadResult.PermissionDenied ->
                request.deviceControlPermissionDenied(result.reason)

            is ScreenStateReadResult.Failed ->
                request.foregroundPackageGateFailure(
                    actionType = actionType,
                    target = target,
                    expectedPackage = expectedPackage,
                    actualPackage = null,
                    snapshot = null,
                    summary = "无法确认目标应用仍在前台，已停止自动 UI 动作。",
                )

            null ->
                request.foregroundPackageGateFailure(
                    actionType = actionType,
                    target = target,
                    expectedPackage = expectedPackage,
                    actualPackage = null,
                    snapshot = null,
                    summary = "当前屏幕控制 preflight 不可用，无法确认目标应用仍在前台。",
                )
        }
    }

    private fun dangerousUiActionPreflight(
        request: ToolRequest,
        actionType: String,
        target: String,
        dangerScope: DangerScope,
        failClosedOnUnavailable: Boolean = false,
    ): ToolResult? {
        // Typing and scrolling never activate a control, so a pay/send button elsewhere on the
        // screen (every chat screen has "发送") must not stop them.
        if (dangerScope == DangerScope.None) return null
        // For named-target actions (tap/type/scroll/submit_search) node matching provides a second
        // content-aware layer, so a momentarily-unreadable screen falls through to those actions'
        // own not-found handling (fail-open here). For pure-coordinate gestures (swipe/long_press)
        // and system-key presses there is NO named-target layer — this preflight is the sole guard
        // between the model and a raw-pixel/system action — so those callers pass
        // failClosedOnUnavailable=true and we BLOCK when the screen cannot be confirmed dangerous-free.
        val snapshot = when (
            val result = preflightProvider?.observeCurrentScreen(
                maxTextChars = DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS,
                maxNodes = DEFAULT_MAX_SCREEN_STATE_NODES,
            )
        ) {
            is ScreenStateReadResult.Available -> result.snapshot
            is ScreenStateReadResult.PermissionDenied ->
                if (failClosedOnUnavailable) {
                    return request.deviceControlPermissionDenied(result.reason)
                } else {
                    return null
                }

            is ScreenStateReadResult.Failed ->
                if (failClosedOnUnavailable) {
                    return request.dangerousActionPreflightUnavailable(
                        actionType = actionType,
                        target = target,
                        summary = "无法读取当前屏幕以确认没有危险控件，已停止自动 UI 动作。",
                    )
                } else {
                    return null
                }

            null ->
                if (failClosedOnUnavailable) {
                    return request.dangerousActionPreflightUnavailable(
                        actionType = actionType,
                        target = target,
                        summary = "当前屏幕控制 preflight 不可用，无法确认没有危险控件，已停止自动 UI 动作。",
                    )
                } else {
                    return null
                }
        }
        val hitsDangerousControl = when (dangerScope) {
            DangerScope.None -> false
            is DangerScope.Target -> snapshot.tapTargetHitsDangerousControl(dangerScope.target)
            is DangerScope.Point -> snapshot.normalizedPointHitsDangerousControl(dangerScope.xNorm, dangerScope.yNorm)
            DangerScope.WholeScreen -> snapshot.hasDangerousActionControl()
        }
        if (!hitsDangerousControl) return null
        return request.failed(
            code = ToolErrorCode.ExecutionFailed,
            summary = if (dangerScope == DangerScope.WholeScreen) {
                "当前屏幕包含支付、发送、删除、发布、下单、购买、转账或授权类控件，已停止自动 UI 动作。"
            } else {
                "这一步会点击支付、发送、删除、发布、下单、转账或授权类控件，需要你亲自操作。"
            },
            retryable = false,
            data = (
                request.deviceControlBaseData() +
                mapOf(
                    "actionType" to actionType,
                    "status" to UiActionStatus.Failed.schemaValue(),
                    "retryable" to false.toString(),
                    "summary" to "dangerous_ui_action_control_detected",
                    "failureKind" to UiActionFailureKind.DangerousAction.schemaValue,
                    "beforeObservationId" to snapshot.id,
                    "afterObservationId" to "",
                    "verificationSummary" to "动作前检测到危险控件，未执行 UI 动作。",
                    "screenObservationDiffSummary" to "blocked_before_execution;reason=dangerous_action_control",
                ) +
                target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty() +
                snapshot.toBeforeObservationData()
            ).withAppSearchProgressEvidence(),
        )
    }

    /**
     * Before a low-risk UI action, if a blocking promotional overlay/interstitial occludes the
     * screen, attempt a bounded ([SolinConstants.AgentLoop.AD_DISMISS_MAX_ROUNDS]) closed-loop
     * dismissal: observe → tap a close/skip affordance → reuse the action's after-snapshot (falling
     * back to re-observe when unavailable) → stop when the overlay clears.
     *
     * Uses [preflightProvider] (the raw, non-OCR-grounding provider) exactly like
     * [dangerousUiActionPreflight], so the dismiss loop never disturbs the pending OCR-grounding
     * cache on the wrapping provider (which would drop a queued grounding hint before the real tap).
     *
     * Trust boundary: [ScreenStateSnapshot.blockingOverlayDismissTarget] is fail-closed — it never
     * returns a target on a screen carrying a dangerous-action control (支付/授权/删除/…), so this
     * loop can never auto-tap a payment/authorize/delete surface. On a persistent (sticky) overlay
     * the loop gives up after one ineffective tap rather than looping, and the real action proceeds.
     * All observation stays on the existing LocalOnly accessibility channel — no new pixel path.
     */
    private fun dismissBlockingOverlaysIfPresent() {
        val provider = preflightProvider ?: return
        var snapshot = (provider.observeCurrentScreen(
            maxTextChars = DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS,
            maxNodes = DEFAULT_MAX_SCREEN_STATE_NODES,
        ) as? ScreenStateReadResult.Available)?.snapshot ?: return
        repeat(SolinConstants.AgentLoop.AD_DISMISS_MAX_ROUNDS) {
            val dismissTarget = snapshot.blockingOverlayDismissTarget() ?: return
            val label = dismissTarget.text.ifBlank { dismissTarget.contentDescription }
            if (label.isBlank()) return
            val beforeSignature = snapshot.overlayContentSignature()
            val tapResult = provider.tap(target = label, timeoutMillis = DEFAULT_UI_ACTION_TIMEOUT_MILLIS)
            val after = (tapResult as? UiActionReadResult.Available)?.result?.after
                ?: (provider.observeCurrentScreen(
                    maxTextChars = DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS,
                    maxNodes = DEFAULT_MAX_SCREEN_STATE_NODES,
                ) as? ScreenStateReadResult.Available)?.snapshot
            // Stop when: observation failed, the overlay cleared, or the tap had no effect (the
            // content signature is unchanged — a sticky overlay). Uses a salt/timestamp-free
            // signature because ScreenStateSnapshot.id embeds a random salt, so id equality would
            // never hold and the sticky-stop branch would be dead.
            if (after == null ||
                !after.hasBlockingOverlay() ||
                after.overlayContentSignature() == beforeSignature
            ) {
                return
            }
            snapshot = after
        }
    }

    /**
     * Salt/timestamp-free signature of the visible screen content, used to detect an ineffective
     * (sticky) dismiss tap. Excludes [ScreenStateSnapshot.id] (which embeds a random salt) so equal
     * content compares equal across observations.
     */
    private fun ScreenStateSnapshot.overlayContentSignature(): String =
        "${packageName.orEmpty()}|$nodeCount|$textSummary"

    private fun executePressBack(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult =
        actionResult(
            request = request,
            actionType = "press_back",
            target = "",
            result = provider.pressBack(timeoutMillis = request.timeoutMillis()),
        )

    private fun executeWait(
        request: ToolRequest,
        provider: CurrentScreenControlProvider,
    ): ToolResult {
        val result = provider.waitForScreen(timeoutMillis = request.timeoutMillis())
            .withExpectedForegroundPackageVerification(request)
        val verification = result.searchVerificationFor(request)
        return actionResult(
            request = request,
            actionType = "wait",
            target = "",
            result = result.withSearchVerification(verification),
            extraData = verification.toData(),
        )
    }

    private fun actionResult(
        request: ToolRequest,
        actionType: String,
        target: String,
        result: UiActionReadResult,
        extraData: Map<String, String> = emptyMap(),
    ): ToolResult =
        when (result) {
            is UiActionReadResult.Available -> {
                val execution = result.result
                val after = execution.after
                val data = (
                    request.deviceControlBaseData() +
                    mapOf(
                        "actionType" to actionType,
                        "status" to execution.status.schemaValue(),
                        "retryable" to execution.retryable.toString(),
                        "summary" to execution.summary,
                        "beforeObservationId" to execution.before?.id.orEmpty(),
                        "afterObservationId" to after?.id.orEmpty(),
                        "verificationSummary" to (after?.observationSummary() ?: "动作后未能读取屏幕状态"),
                        "screenObservationDiffSummary" to screenObservationDiffSummary(
                            before = execution.before,
                            after = after,
                        ),
                    ) +
                    target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty() +
                    execution.failureKind?.let { mapOf("failureKind" to it.schemaValue) }.orEmpty() +
                    execution.before?.toBeforeObservationData().orEmpty() +
                    after?.toAfterObservationData().orEmpty() +
                    (after?.widthPx ?: execution.before?.widthPx)?.let {
                        mapOf("screenWidthPx" to it.toString())
                    }.orEmpty() +
                    (after?.heightPx ?: execution.before?.heightPx)?.let {
                        mapOf("screenHeightPx" to it.toString())
                    }.orEmpty() +
                    extraData
                    ).withAppSearchProgressEvidence()
                if (execution.status == UiActionStatus.Succeeded) {
                    request.succeeded(
                        summary = execution.summary,
                        data = data,
                    )
                } else {
                    request.failed(
                        code = ToolErrorCode.ExecutionFailed,
                        summary = execution.summary,
                        retryable = execution.retryable,
                        data = data,
                    )
                }
            }

            is UiActionReadResult.PermissionDenied ->
                request.deviceControlPermissionDenied(result.reason)

            is UiActionReadResult.Failed ->
                request.failed(
                    code = if (result.failureKind == UiActionFailureKind.PermissionMissing) {
                        ToolErrorCode.PermissionDenied
                    } else {
                        ToolErrorCode.ExecutionFailed
                    },
                    summary = result.reason,
                    retryable = result.retryable,
                    data = (
                        request.deviceControlBaseData() + mapOf(
                            "actionType" to actionType,
                            "status" to UiActionStatus.Failed.schemaValue(),
                            "retryable" to result.retryable.toString(),
                            "summary" to result.reason,
                            "failureKind" to result.failureKind.schemaValue,
                        ) + target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty()
                    ).withAppSearchProgressEvidence(),
                )
        }

    private fun ToolRequest.timeoutMillis(): Long =
        arguments["timeoutMillis"]?.trim()?.toLongOrNull() ?: DEFAULT_UI_ACTION_TIMEOUT_MILLIS

    private fun ToolRequest.allowClipboardPasteFallback(): Boolean =
        arguments["allowClipboardPasteFallback"]?.toBooleanStrictOrNull() == true

    private fun ToolRequest.expectedForegroundPackageName(): String? =
        arguments["expectedPackageName"]?.trim()?.takeIf { it.isNotBlank() }
            ?: arguments["targetPackageName"]?.trim()?.takeIf { it.isNotBlank() }

    private fun ToolRequest.foregroundPackageGateFailure(
        actionType: String,
        target: String,
        expectedPackage: String,
        actualPackage: String?,
        snapshot: ScreenStateSnapshot?,
        summary: String = "目标应用未保持在前台，已停止自动 UI 动作。",
    ): ToolResult =
        failed(
            code = ToolErrorCode.ExecutionFailed,
            summary = summary,
            retryable = false,
            data = (
                deviceControlBaseData() +
                mapOf(
                    "actionType" to actionType,
                    "status" to UiActionStatus.Failed.schemaValue(),
                    "retryable" to false.toString(),
                    "summary" to "foreground_package_mismatch",
                    "failureKind" to UiActionFailureKind.AppNotForeground.schemaValue,
                    "expectedPackageName" to expectedPackage,
                    "actualPackageName" to actualPackage.orEmpty(),
                    "beforeObservationId" to snapshot?.id.orEmpty(),
                    "afterObservationId" to snapshot?.id.orEmpty(),
                    "verificationSummary" to summary,
                    "screenObservationDiffSummary" to "blocked_before_execution;reason=app_not_foreground",
                ) +
                target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty() +
                snapshot?.toBeforeObservationData().orEmpty() +
                snapshot?.toAfterObservationData().orEmpty()
            ).withAppSearchProgressEvidence(),
        )

    private fun ToolRequest.dangerousActionPreflightUnavailable(
        actionType: String,
        target: String,
        summary: String,
    ): ToolResult =
        failed(
            code = ToolErrorCode.ExecutionFailed,
            summary = summary,
            retryable = true,
            data = (
                deviceControlBaseData() +
                mapOf(
                    "actionType" to actionType,
                    "status" to UiActionStatus.Failed.schemaValue(),
                    "retryable" to true.toString(),
                    "summary" to "dangerous_ui_action_preflight_unavailable",
                    "failureKind" to UiActionFailureKind.DangerousAction.schemaValue,
                    "beforeObservationId" to "",
                    "afterObservationId" to "",
                    "verificationSummary" to summary,
                    "screenObservationDiffSummary" to
                        "blocked_before_execution;reason=dangerous_action_preflight_unavailable",
                ) +
                target.takeIf { it.isNotBlank() }?.let { mapOf("target" to it) }.orEmpty()
            ).withAppSearchProgressEvidence(),
        )

    private fun UiActionReadResult.withExpectedForegroundPackageVerification(
        request: ToolRequest,
    ): UiActionReadResult {
        val expectedPackage = request.expectedForegroundPackageName() ?: return this
        if (this !is UiActionReadResult.Available) return this
        if (result.status != UiActionStatus.Succeeded) return this
        val afterPackage = result.after?.packageName
        if (afterPackage == expectedPackage) return this
        return copy(
            result = result.copy(
                status = UiActionStatus.Failed,
                summary = "目标应用未保持在前台，已停止自动 UI 动作。",
                retryable = false,
                failureKind = UiActionFailureKind.AppNotForeground,
            ),
        )
    }

    private fun UiActionReadResult.searchVerificationFor(request: ToolRequest): SearchResultVerification? {
        val query = request.arguments["verifySearchQuery"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val execution = (this as? UiActionReadResult.Available)?.result ?: return null
        return AppSearchResultVerifier.verify(
            before = execution.before,
            after = execution.after,
            query = query,
            expectedPackageName = request.expectedForegroundPackageName(),
            expectedAppName = request.arguments["expectedAppName"],
        )
    }

    private fun UiActionReadResult.withSearchVerification(
        verification: SearchResultVerification?,
    ): UiActionReadResult {
        verification ?: return this
        if (this !is UiActionReadResult.Available) return this
        if (result.status != UiActionStatus.Succeeded) return this
        if (verification.verified) {
            return copy(
                result = result.copy(
                    summary = verification.summary,
                ),
            )
        }
        return copy(
            result = result.copy(
                status = UiActionStatus.Failed,
                summary = verification.summary,
                retryable = true,
                failureKind = verification.failureKind ?: UiActionFailureKind.ResultNotVerified,
            ),
        )
    }

    private fun SearchResultVerification?.toData(): Map<String, String> =
        this?.let { verification ->
            mapOf(
                "searchVerificationStatus" to if (verification.verified) "verified" else "not_verified",
                "searchVerificationEvidence" to verification.evidence,
            )
        }.orEmpty()

    private fun ToolRequest.deviceControlPermissionDenied(reason: String): ToolResult =
        failed(
            code = ToolErrorCode.PermissionDenied,
            summary = reason.ifBlank { "需要开启Solin无障碍服务才能控制当前屏幕" },
            retryable = true,
            data = (
                deviceControlBaseData() + mapOf(
                    "specialAccess" to SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL,
                    "settingsAction" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    "failureKind" to UiActionFailureKind.PermissionMissing.schemaValue,
                )
            ).withAppSearchProgressEvidence(),
        )

    private fun ToolRequest.invalidDeviceControlRequest(summary: String): ToolResult =
        failed(
            code = ToolErrorCode.InvalidRequest,
            summary = summary,
            retryable = false,
            data = deviceControlBaseData(),
        )

    private fun ToolRequest.deviceControlBaseData(): Map<String, String> =
        localOnlyData() + mapOf(
            "source" to DEVICE_CONTROL_SOURCE_ACCESSIBILITY,
            "metadataPolicy" to DEVICE_CONTROL_METADATA_POLICY,
        )

    private fun Map<String, String>.withAppSearchProgressEvidence(): Map<String, String> =
        this + AppSearchProgressEvidence.fromData(this).toData()

    private fun ScreenStateSnapshot.toObservationData(
        requestedMaxTextChars: Int,
        requestedMaxNodes: Int,
    ): Map<String, String> =
        mapOf(
            "observationId" to id,
            "capturedAtMillis" to capturedAtMillis.toString(),
            "nodeCount" to nodeCount.toString(),
            "actionableNodeCount" to actionableNodeCount.toString(),
            "textSummary" to textSummary,
            "truncated" to truncated.toString(),
            "nodesJson" to nodes.toScreenNodesJsonString(),
            "screenObservationJson" to toScreenObservationJsonString(),
            "maxTextChars" to requestedMaxTextChars.toString(),
            "maxNodes" to requestedMaxNodes.toString(),
        ) + packageName?.takeIf { it.isNotBlank() }?.let { mapOf("packageName" to it) }.orEmpty() +
            widthPx?.let { mapOf("screenWidthPx" to it.toString()) }.orEmpty() +
            heightPx?.let { mapOf("screenHeightPx" to it.toString()) }.orEmpty() +
            toScreenshotPerception()?.let { mapOf("screenshotPerception" to it.formatForPrompt()) }.orEmpty()

    private fun ScreenStateSnapshot.toScreenshotPerception(): ScreenshotPerception? {
        val w = widthPx ?: return null
        val h = heightPx ?: return null
        return ScreenshotPerception(
            widthPx = w,
            heightPx = h,
            captureTimeMillis = capturedAtMillis,
        )
    }

    private fun ScreenStateSnapshot.toAfterObservationData(): Map<String, String> =
        toPrefixedObservationData(prefix = "after")

    private fun ScreenStateSnapshot.toBeforeObservationData(): Map<String, String> =
        toPrefixedObservationData(prefix = "before")

    /**
     * Before/after observation fields, keyed `<prefix>PackageName`, `<prefix>NodesJson`, … .
     * These key names are part of the private-output contract (uiActionPrivateOutputKeys and
     * the ui action output schema) and must not change.
     */
    private fun ScreenStateSnapshot.toPrefixedObservationData(prefix: String): Map<String, String> =
        mapOf(
            "${prefix}PackageName" to packageName.orEmpty(),
            "${prefix}CapturedAtMillis" to capturedAtMillis.toString(),
            "${prefix}NodeCount" to nodeCount.toString(),
            "${prefix}ActionableNodeCount" to actionableNodeCount.toString(),
            "${prefix}TextSummary" to textSummary,
            "${prefix}Truncated" to truncated.toString(),
            "${prefix}NodesJson" to nodes.toScreenNodesJsonString(),
            "${prefix}ScreenObservationJson" to toScreenObservationJsonString(),
        ) + widthPx?.let { mapOf("${prefix}ScreenWidthPx" to it.toString()) }.orEmpty() +
            heightPx?.let { mapOf("${prefix}ScreenHeightPx" to it.toString()) }.orEmpty() +
            toScreenshotPerception()?.let { mapOf("${prefix}ScreenshotPerception" to it.formatForPrompt()) }.orEmpty()

    private fun ScreenStateSnapshot.observationSummary(): String {
        val packagePart = packageName?.takeIf { it.isNotBlank() }?.let { "包名 $it，" }.orEmpty()
        return "已观察当前屏幕：${packagePart}${nodeCount} 个节点，${actionableNodeCount} 个可交互节点。"
    }

    private fun screenObservationDiffSummary(
        before: ScreenStateSnapshot?,
        after: ScreenStateSnapshot?,
    ): String {
        if (before == null && after == null) return "before_after_unavailable"
        if (before == null) return "before_unavailable;afterNodes=${after?.nodeCount ?: 0}"
        if (after == null) return "after_unavailable;beforeNodes=${before.nodeCount}"
        val beforeTexts = before.nodes.mapNotNull { node -> node.diffTextLabel() }.toOrderedSet()
        val afterTexts = after.nodes.mapNotNull { node -> node.diffTextLabel() }.toOrderedSet()
        val beforeActions = before.nodes.mapNotNull { node -> node.diffActionableLabel() }.toOrderedSet()
        val afterActions = after.nodes.mapNotNull { node -> node.diffActionableLabel() }.toOrderedSet()
        val addedTexts = (afterTexts - beforeTexts).take(6)
        val removedTexts = (beforeTexts - afterTexts).take(6)
        val addedActions = (afterActions - beforeActions).take(4)
        val removedActions = (beforeActions - afterActions).take(4)
        val changed = before.packageName != after.packageName ||
            before.nodeCount != after.nodeCount ||
            before.actionableNodeCount != after.actionableNodeCount ||
            addedTexts.isNotEmpty() ||
            removedTexts.isNotEmpty() ||
            addedActions.isNotEmpty() ||
            removedActions.isNotEmpty()
        return buildString {
            append("changed=")
            append(changed)
            append(";package=")
            append(before.packageName.orEmpty().ifBlank { "unknown" })
            append("->")
            append(after.packageName.orEmpty().ifBlank { "unknown" })
            append(";nodes=")
            append(before.nodeCount)
            append("->")
            append(after.nodeCount)
            append(";actionable=")
            append(before.actionableNodeCount)
            append("->")
            append(after.actionableNodeCount)
            append(";addedText=")
            append(addedTexts.joinToStringLimited().ifBlank { "none" })
            append(";removedText=")
            append(removedTexts.joinToStringLimited().ifBlank { "none" })
            append(";addedActionable=")
            append(addedActions.joinToStringLimited().ifBlank { "none" })
            append(";removedActionable=")
            append(removedActions.joinToStringLimited().ifBlank { "none" })
        }
    }

    private fun List<String>.toOrderedSet(): Set<String> =
        map { value -> value.trim() }
            .filter { value -> value.isNotBlank() }
            .toCollection(linkedSetOf())

    private fun List<String>.joinToStringLimited(): String =
        joinToString(separator = "|") { value -> value.take(64) }

    private fun ScreenNode.diffTextLabel(): String? =
        (text.ifBlank { contentDescription })
            .trim()
            .takeIf { value -> value.isNotBlank() }

    private fun ScreenNode.diffActionableLabel(): String? {
        if (!clickable && !editable && !scrollable) return null
        val label = diffTextLabel() ?: id.takeIf { value -> value.isNotBlank() } ?: className
        val role = when {
            editable -> "editable"
            clickable -> "clickable"
            scrollable -> "scrollable"
            else -> "actionable"
        }
        return "$role:$label"
    }

    private fun UiActionStatus.schemaValue(): String =
        when (this) {
            UiActionStatus.Succeeded -> "succeeded"
            UiActionStatus.Failed -> "failed"
        }

    internal companion object {
        const val DEFAULT_MAX_SCREEN_STATE_TEXT_CHARS = 2_000
        const val DEFAULT_MAX_SCREEN_STATE_NODES = 50
        const val DEFAULT_UI_ACTION_TIMEOUT_MILLIS = 1_000L

        // Bounded foreground-readiness poll for the post-launch continuation observe: up to
        // 6 * 250ms = 1.5s waiting for the launched target app to become the foreground package
        // before observing, covering a typical cold start without stalling the checkpoint budget.
        // internal (not private) so the cancellation regression test can assert against the real
        // budget instead of hardcoding the numbers.
        const val FOREGROUND_READINESS_POLL_MAX_ATTEMPTS = 6
        const val FOREGROUND_READINESS_POLL_INTERVAL_MILLIS = 250L
    }
}

/**
 * What a UI action can activate, i.e. what the dangerous-control preflight inspects. Critical
 * controls (pay, send, delete, publish, order, transfer, authorize) are never tapped by the agent;
 * the user taps them. Everything else on the same screen stays usable.
 */
private sealed interface DangerScope {
    /** Typing or scrolling never activates a control. */
    data object None : DangerScope

    /** A named tap activates only its target. */
    data class Target(val target: String) : DangerScope

    /** A normalized-coordinate tap activates whatever lies under the point. */
    data class Point(val xNorm: Int, val yNorm: Int) : DangerScope

    /** Submit, Enter, swipe and long-press can commit anything on screen (Enter sends a chat). */
    data object WholeScreen : DangerScope
}
