package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.device.UiSystemKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the per-action dangerous-control preflight policy when the screen cannot be read:
 * actions with no named-target second layer (normalized tap, swipe, long_press, press_key) fail
 * CLOSED; named-target actions (tap, type_text, submit_search, scroll) fall through (fail-open
 * here, their node matching and the service's live critical-tap check are the second layer),
 * except that a named target that is itself a critical control is refused without reading.
 */
class DeviceControlPreflightPolicyTest {
    @Test
    fun unreadableScreenPreflightPolicyPerAction() {
        val actual = cases.joinToString("\n") { (label, request) ->
            val provider = UnreadableScreenProvider()
            val result = DeviceControlToolExecutor(provider = provider).execute(request)
            val outcome = if (result.data["summary"] == "dangerous_ui_action_preflight_unavailable") {
                "blocked"
            } else if (result.data["summary"] == "dangerous_ui_action_control_detected") {
                "refused_critical dispatched=${provider.dispatched}"
            } else {
                "dispatched=${provider.dispatched}"
            }
            "$label -> $outcome"
        }

        assertEquals(
            """
            tap_named -> dispatched=[tap]
            tap_named_critical -> refused_critical dispatched=[]
            tap_normalized -> blocked
            type_text -> dispatched=[type_text]
            type_text_critical_target -> refused_critical dispatched=[]
            submit_search -> dispatched=[submit_search]
            scroll -> dispatched=[scroll]
            swipe -> blocked
            long_press -> blocked
            press_key -> blocked
            """.trimIndent(),
            actual,
        )
    }

    private val cases = listOf(
        "tap_named" to ToolRequest(toolName = MobileActionFunctions.UI_TAP, arguments = mapOf("target" to "搜索")),
        "tap_named_critical" to ToolRequest(toolName = MobileActionFunctions.UI_TAP, arguments = mapOf("target" to "确认支付")),
        "tap_normalized" to ToolRequest(
            toolName = MobileActionFunctions.UI_TAP,
            arguments = mapOf("targetX" to "500", "targetY" to "500"),
        ),
        "type_text" to ToolRequest(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            arguments = mapOf("text" to "abc", "target" to "搜索框"),
        ),
        "type_text_critical_target" to ToolRequest(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            arguments = mapOf("text" to "abc", "target" to "发送"),
        ),
        "submit_search" to ToolRequest(toolName = MobileActionFunctions.UI_SUBMIT_SEARCH),
        "scroll" to ToolRequest(toolName = MobileActionFunctions.UI_SCROLL, arguments = mapOf("direction" to "down")),
        "swipe" to ToolRequest(
            toolName = MobileActionFunctions.UI_SWIPE,
            arguments = mapOf("startXNorm" to "500", "startYNorm" to "800", "endXNorm" to "500", "endYNorm" to "200"),
        ),
        "long_press" to ToolRequest(
            toolName = MobileActionFunctions.UI_LONG_PRESS,
            arguments = mapOf("xNorm" to "300", "yNorm" to "400"),
        ),
        "press_key" to ToolRequest(toolName = MobileActionFunctions.UI_PRESS_KEY, arguments = mapOf("key" to "home")),
    )

    private class UnreadableScreenProvider : CurrentScreenControlProvider {
        val dispatched = mutableListOf<String>()

        override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult =
            ScreenStateReadResult.Failed(reason = "timeout", failureKind = UiActionFailureKind.Timeout)

        override fun tap(target: String, timeoutMillis: Long) = record("tap")

        override fun typeText(
            text: String,
            target: String?,
            timeoutMillis: Long,
            allowClipboardPasteFallback: Boolean,
        ) = record("type_text")

        override fun submitSearch(timeoutMillis: Long) = record("submit_search")

        override fun scroll(direction: UiScrollDirection, target: String?, timeoutMillis: Long) = record("scroll")

        override fun swipe(
            startXNorm: Int,
            startYNorm: Int,
            endXNorm: Int,
            endYNorm: Int,
            durationMillis: Long,
            timeoutMillis: Long,
        ) = record("swipe")

        override fun longPress(xNorm: Int, yNorm: Int, holdMillis: Long, timeoutMillis: Long) = record("long_press")

        override fun pressKey(key: UiSystemKey, timeoutMillis: Long) = record("press_key")

        override fun pressBack(timeoutMillis: Long) = record("press_back")

        override fun waitForScreen(timeoutMillis: Long) = record("wait")

        override fun tapByNormalizedCoords(normalizedX: Int, normalizedY: Int, timeoutMillis: Long) =
            record("tap_normalized")

        private fun record(action: String): UiActionReadResult {
            dispatched += action
            return UiActionReadResult.Available(
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
}
