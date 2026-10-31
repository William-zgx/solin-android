package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.device.UiSystemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G1/G2/G3: ui_swipe / ui_long_press / ui_press_key. New agent-callable UI actions must reach the
 * provider with coerced/validated args, fail-closed when a dangerous control occupies the screen,
 * and reject anything outside the whitelist / normalized-coordinate range (injection resistance).
 */
class UiGestureActionTest {

    // ── Swipe ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun swipeForwardsNormalizedArgsToProvider() {
        val provider = RecordingControlProvider(observe = benignSnapshot())
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "swipe-1",
                toolName = MobileActionFunctions.UI_SWIPE,
                arguments = mapOf(
                    "startXNorm" to "500",
                    "startYNorm" to "800",
                    "endXNorm" to "500",
                    "endYNorm" to "200",
                    "durationMillis" to "400",
                ),
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(listOf(SwipeCall(500, 800, 500, 200, 400L)), provider.swipeCalls)
    }

    @Test
    fun swipeRejectsOutOfRangeCoordinates() {
        val provider = RecordingControlProvider(observe = benignSnapshot())
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "swipe-bad",
                toolName = MobileActionFunctions.UI_SWIPE,
                arguments = mapOf(
                    "startXNorm" to "500",
                    "startYNorm" to "800",
                    "endXNorm" to "1500", // out of 0..1000
                    "endYNorm" to "200",
                ),
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertTrue("out-of-range swipe must not reach the provider", provider.swipeCalls.isEmpty())
    }

    @Test
    fun swipeFailsClosedWhenDangerousControlPresent() {
        val provider = RecordingControlProvider(observe = dangerousSnapshot())
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "swipe-danger",
                toolName = MobileActionFunctions.UI_SWIPE,
                arguments = mapOf(
                    "startXNorm" to "500",
                    "startYNorm" to "800",
                    "endXNorm" to "500",
                    "endYNorm" to "200",
                ),
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("dangerous_ui_action_control_detected", result.data["summary"])
        assertTrue("dangerous screen must block the swipe", provider.swipeCalls.isEmpty())
    }

    // ── Long press ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun longPressForwardsNormalizedArgsToProvider() {
        val provider = RecordingControlProvider(observe = benignSnapshot())
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "lp-1",
                toolName = MobileActionFunctions.UI_LONG_PRESS,
                arguments = mapOf("xNorm" to "300", "yNorm" to "400", "holdMillis" to "800"),
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(listOf(LongPressCall(300, 400, 800L)), provider.longPressCalls)
    }

    @Test
    fun longPressFailsClosedWhenDangerousControlPresent() {
        val provider = RecordingControlProvider(observe = dangerousSnapshot())
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "lp-danger",
                toolName = MobileActionFunctions.UI_LONG_PRESS,
                arguments = mapOf("xNorm" to "300", "yNorm" to "400"),
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("dangerous_ui_action_control_detected", result.data["summary"])
        assertTrue(provider.longPressCalls.isEmpty())
    }

    // ── Press key ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun pressKeyAcceptsWhitelistedKeys() {
        listOf(
            "home" to UiSystemKey.Home,
            "recents" to UiSystemKey.Recents,
            "enter" to UiSystemKey.Enter,
            "delete" to UiSystemKey.Delete,
        ).forEach { (schema, expected) ->
            val provider = RecordingControlProvider(observe = benignSnapshot())
            val executor = DeviceControlToolExecutor(provider = provider)

            val result = executor.execute(
                ToolRequest(
                    id = "key-$schema",
                    toolName = MobileActionFunctions.UI_PRESS_KEY,
                    arguments = mapOf("key" to schema),
                ),
            )

            assertEquals(ToolStatus.Succeeded, result.status)
            assertEquals(listOf(expected), provider.pressKeyCalls)
        }
    }

    @Test
    fun pressKeyRejectsNonWhitelistedKey() {
        // Injection resistance: an arbitrary keycode / unknown key never reaches the provider.
        val provider = RecordingControlProvider(observe = benignSnapshot())
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "key-bad",
                toolName = MobileActionFunctions.UI_PRESS_KEY,
                arguments = mapOf("key" to "KEYCODE_POWER"),
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertTrue("non-whitelisted key must not reach the provider", provider.pressKeyCalls.isEmpty())
    }

    @Test
    fun pressKeyFailsClosedWhenDangerousControlPresent() {
        val provider = RecordingControlProvider(observe = dangerousSnapshot())
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "key-danger",
                toolName = MobileActionFunctions.UI_PRESS_KEY,
                arguments = mapOf("key" to "enter"),
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("dangerous_ui_action_control_detected", result.data["summary"])
        assertTrue(provider.pressKeyCalls.isEmpty())
    }

    // ── Fail-closed when the screen cannot be read (A: dangerousUiActionPreflight fail-closed) ──────

    @Test
    fun swipeFailsClosedWhenScreenReadFails() {
        val provider = RecordingControlProvider(
            observe = benignSnapshot(),
            observeResult = ScreenStateReadResult.Failed(
                reason = "当前屏幕状态读取超时",
                failureKind = UiActionFailureKind.Timeout,
            ),
        )
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "swipe-read-fail",
                toolName = MobileActionFunctions.UI_SWIPE,
                arguments = mapOf(
                    "startXNorm" to "500",
                    "startYNorm" to "800",
                    "endXNorm" to "500",
                    "endYNorm" to "200",
                ),
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("dangerous_ui_action_preflight_unavailable", result.data["summary"])
        assertTrue(
            "coordinate swipe must NOT dispatch when the screen can't be confirmed dangerous-free",
            provider.swipeCalls.isEmpty(),
        )
    }

    @Test
    fun longPressFailsClosedWhenScreenReadFails() {
        val provider = RecordingControlProvider(
            observe = benignSnapshot(),
            observeResult = ScreenStateReadResult.Failed(
                reason = "当前屏幕状态读取超时",
                failureKind = UiActionFailureKind.Timeout,
            ),
        )
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "lp-read-fail",
                toolName = MobileActionFunctions.UI_LONG_PRESS,
                arguments = mapOf("xNorm" to "300", "yNorm" to "400"),
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("dangerous_ui_action_preflight_unavailable", result.data["summary"])
        assertTrue(provider.longPressCalls.isEmpty())
    }

    @Test
    fun pressKeyFailsClosedWhenScreenPermissionDenied() {
        val provider = RecordingControlProvider(
            observe = benignSnapshot(),
            observeResult = ScreenStateReadResult.PermissionDenied("accessibility disabled"),
        )
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "key-perm-denied",
                toolName = MobileActionFunctions.UI_PRESS_KEY,
                arguments = mapOf("key" to "enter"),
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertTrue(
            "press_key must NOT dispatch when accessibility is unavailable",
            provider.pressKeyCalls.isEmpty(),
        )
    }

    // ── Blocked-before-execution result contract ──────────────────────────────────────────────────

    @Test
    fun blockedBeforeExecutionResultsKeepTheirDataContract() {
        // The dangerous-control, unreadable-screen and foreground-mismatch blocks all build their
        // result through one shared helper. Key order is part of the recorded tool data, so this
        // pins the exact map each path emits, not just the fields it happens to contain.
        val dangerous = DeviceControlToolExecutor(
            RecordingControlProvider(observe = dangerousSnapshot()),
        ).execute(
            ToolRequest(
                id = "blocked-danger",
                toolName = MobileActionFunctions.UI_SWIPE,
                arguments = swipeArguments(),
            ),
        )
        assertEquals(
            listOf(
                "toolName", "privacy", "requiresLocalModel", "source", "metadataPolicy",
                "actionType", "status", "retryable", "summary", "failureKind",
                "beforeObservationId", "afterObservationId", "verificationSummary",
                "screenObservationDiffSummary",
                "beforePackageName", "beforeCapturedAtMillis", "beforeNodeCount",
                "beforeActionableNodeCount", "beforeTextSummary", "beforeTruncated",
                "beforeNodesJson", "beforeScreenObservationJson",
                "uiActionOutcome", "uiActionOutcomeReason", "appSearchProgressStage",
            ),
            dangerous.data.keys.toList(),
        )
        assertEquals("dangerous_ui_action_control_detected", dangerous.data["summary"])
        assertEquals("dangerous_action", dangerous.data["failureKind"])
        assertEquals("blocked_before_execution;reason=dangerous_action_control", dangerous.data["screenObservationDiffSummary"])

        val unavailable = DeviceControlToolExecutor(
            RecordingControlProvider(
                observe = benignSnapshot(),
                observeResult = ScreenStateReadResult.Failed(
                    reason = "当前屏幕状态读取超时",
                    failureKind = UiActionFailureKind.Timeout,
                ),
            ),
        ).execute(
            ToolRequest(
                id = "blocked-unavailable",
                toolName = MobileActionFunctions.UI_SWIPE,
                arguments = swipeArguments(),
            ),
        )
        assertEquals(
            listOf(
                "toolName", "privacy", "requiresLocalModel", "source", "metadataPolicy",
                "actionType", "status", "retryable", "summary", "failureKind",
                "beforeObservationId", "afterObservationId", "verificationSummary",
                "screenObservationDiffSummary",
                "uiActionOutcome", "uiActionOutcomeReason", "appSearchProgressStage",
            ),
            unavailable.data.keys.toList(),
        )
        assertEquals("dangerous_ui_action_preflight_unavailable", unavailable.data["summary"])
        assertEquals("true", unavailable.data["retryable"])

        val mismatchProvider = RecordingControlProvider(
            observe = snapshot(id = "foreground-mismatch", nodes = listOf(node("n0", "Continue"))),
        )
        val mismatch = DeviceControlToolExecutor(provider = mismatchProvider).execute(
            ToolRequest(
                id = "blocked-mismatch",
                toolName = MobileActionFunctions.UI_SWIPE,
                arguments = swipeArguments() + mapOf("expectedPackageName" to "com.other.app"),
            ),
        )
        assertEquals(
            listOf(
                "toolName", "privacy", "requiresLocalModel", "source", "metadataPolicy",
                "actionType", "status", "retryable", "summary", "failureKind",
                "expectedPackageName", "actualPackageName",
                "beforeObservationId", "afterObservationId", "verificationSummary",
                "screenObservationDiffSummary",
                "beforePackageName", "beforeCapturedAtMillis", "beforeNodeCount",
                "beforeActionableNodeCount", "beforeTextSummary", "beforeTruncated",
                "beforeNodesJson", "beforeScreenObservationJson",
                "afterPackageName", "afterCapturedAtMillis", "afterNodeCount",
                "afterActionableNodeCount", "afterTextSummary", "afterTruncated",
                "afterNodesJson", "afterScreenObservationJson",
                "uiActionOutcome", "uiActionOutcomeReason", "appSearchProgressStage",
            ),
            mismatch.data.keys.toList(),
        )
        assertEquals("foreground_package_mismatch", mismatch.data["summary"])
        assertEquals("app_not_foreground", mismatch.data["failureKind"])
        assertEquals("com.other.app", mismatch.data["expectedPackageName"])
        assertEquals("com.example.app", mismatch.data["actualPackageName"])
        assertTrue(
            "foreground mismatch must NOT dispatch",
            mismatchProvider.swipeCalls.isEmpty(),
        )
    }

    private fun swipeArguments(): Map<String, String> = mapOf(
        "startXNorm" to "500",
        "startYNorm" to "800",
        "endXNorm" to "500",
        "endYNorm" to "200",
    )

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────────

    private fun node(id: String, text: String, clickable: Boolean = false): ScreenNode = ScreenNode(
        id = id,
        text = text,
        contentDescription = "",
        className = "android.view.View",
        bounds = ScreenBounds(0, 0, 100, 48),
        clickable = clickable,
        editable = false,
        scrollable = false,
        enabled = true,
    )

    private fun snapshot(id: String, nodes: List<ScreenNode>) = ScreenStateSnapshot(
        id = id,
        packageName = "com.example.app",
        capturedAtMillis = 1L,
        nodes = nodes,
        textSummary = nodes.joinToString(" ") { it.text },
        truncated = false,
    )

    private fun benignSnapshot() = snapshot(
        id = "benign",
        nodes = listOf(node("content", "商品列表", clickable = true)),
    )

    private fun dangerousSnapshot() = snapshot(
        id = "danger",
        nodes = listOf(node("buy", "立即购买", clickable = true), node("content", "商品", clickable = true)),
    )

    private data class SwipeCall(val sx: Int, val sy: Int, val ex: Int, val ey: Int, val duration: Long)
    private data class LongPressCall(val x: Int, val y: Int, val hold: Long)

    private class RecordingControlProvider(
        private val observe: ScreenStateSnapshot,
        private val observeResult: ScreenStateReadResult? = null,
    ) : CurrentScreenControlProvider {
        val swipeCalls = mutableListOf<SwipeCall>()
        val longPressCalls = mutableListOf<LongPressCall>()
        val pressKeyCalls = mutableListOf<UiSystemKey>()

        override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult =
            observeResult ?: ScreenStateReadResult.Available(observe)

        override fun tap(target: String, timeoutMillis: Long): UiActionReadResult = ok()

        override fun typeText(
            text: String,
            target: String?,
            timeoutMillis: Long,
            allowClipboardPasteFallback: Boolean,
        ): UiActionReadResult = ok()

        override fun submitSearch(timeoutMillis: Long): UiActionReadResult = ok()

        override fun scroll(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult = ok()

        override fun swipe(
            startXNorm: Int,
            startYNorm: Int,
            endXNorm: Int,
            endYNorm: Int,
            durationMillis: Long,
            timeoutMillis: Long,
        ): UiActionReadResult {
            swipeCalls += SwipeCall(startXNorm, startYNorm, endXNorm, endYNorm, durationMillis)
            return ok()
        }

        override fun longPress(xNorm: Int, yNorm: Int, holdMillis: Long, timeoutMillis: Long): UiActionReadResult {
            longPressCalls += LongPressCall(xNorm, yNorm, holdMillis)
            return ok()
        }

        override fun pressKey(key: UiSystemKey, timeoutMillis: Long): UiActionReadResult {
            pressKeyCalls += key
            return ok()
        }

        override fun pressBack(timeoutMillis: Long): UiActionReadResult = ok()

        override fun waitForScreen(timeoutMillis: Long): UiActionReadResult = ok()

        override fun tapByNormalizedCoords(normalizedX: Int, normalizedY: Int, timeoutMillis: Long): UiActionReadResult = ok()

        private fun ok(): UiActionReadResult = UiActionReadResult.Available(
            UiActionExecutionResult(
                status = UiActionStatus.Succeeded,
                before = null,
                after = null,
                summary = "ok",
                retryable = false,
            ),
        )
    }
}
