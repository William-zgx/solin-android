package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiScrollDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1: the ToolExecutor overlay-dismiss loop runs before a low-risk UI action. It is bounded, taps
 * only close/skip affordances, and is fail-closed against dangerous-action surfaces.
 */
class OverlayDismissLoopTest {
    @Test
    fun dismissesBlockingOverlayBeforeTapThenStopsWhenCleared() {
        // Keep a fresh dismiss-loop observation immediately before the automatic close tap, reuse
        // the close tap's after-snapshot for loop progress, then keep the final dangerous preflight.
        val provider = ScriptedControlProvider(
            observeSnapshots = listOf(
                overlaySnapshot("overlay-preflight"),
                overlaySnapshot("overlay-dismiss-check"),
                clearSnapshot("clear-post-preflight"),
            ),
            dismissAfterSnapshots = listOf(clearSnapshot("clear-from-tap")),
        )
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "tap-1",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "商品"),
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        // Exactly one dismiss tap (on the close affordance) then the real tap on 商品.
        assertEquals(listOf("关闭", "商品"), provider.tapTargets)
        assertEquals("preflight + dismiss check + final preflight", 3, provider.observeCount)
    }

    @Test
    fun boundedToMaxRoundsOnStickyOverlay() {
        // Overlay never clears (sticky): the loop must stop after AD_DISMISS_MAX_ROUNDS and still
        // let the real action run — never an unbounded loop.
        val stickyObserves = List(SolinConstants.AgentLoop.AD_DISMISS_MAX_ROUNDS + 2) {
            overlaySnapshot("sticky-$it")
        }
        val provider = ScriptedControlProvider(
            observeSnapshots = stickyObserves,
            dismissAfterSnapshots = List(SolinConstants.AgentLoop.AD_DISMISS_MAX_ROUNDS) {
                overlaySnapshot("sticky-after-$it")
            },
        )
        val executor = DeviceControlToolExecutor(provider = provider)

        executor.execute(
            ToolRequest(
                id = "tap-2",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "商品"),
            ),
        )

        val dismissTaps = provider.tapTargets.count { it == "关闭" }
        assertTrue(
            "dismiss taps must be bounded by AD_DISMISS_MAX_ROUNDS (was $dismissTaps)",
            dismissTaps <= SolinConstants.AgentLoop.AD_DISMISS_MAX_ROUNDS,
        )
        assertTrue("the real tap must still run", provider.tapTargets.contains("商品"))
    }

    @Test
    fun dismissLoopNeverTapsOnDangerousScreenButSafeTargetsStillRun() {
        // Overlay markers + a 立即购买 control: the dismiss loop must not tap the close affordance
        // (fail-closed), but the requested tap on an unrelated target still runs — only the
        // critical control itself is off limits.
        val provider = ScriptedControlProvider(
            observeSnapshots = listOf(dangerousOverlaySnapshot("danger-1")),
        )
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "tap-3",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "商品"),
            ),
        )

        assertEquals("no dismiss tap, only the requested tap", listOf("商品"), provider.tapTargets)
        assertEquals(ToolStatus.Succeeded, result.status)
    }

    @Test
    fun tapOnDangerousTargetIsBlockedByIdOrLabel() {
        listOf("cta", "立即购买").forEach { target ->
            val provider = ScriptedControlProvider(
                observeSnapshots = listOf(dangerousRevealedSnapshot("danger-$target")),
            )
            val result = DeviceControlToolExecutor(provider = provider).execute(
                ToolRequest(
                    id = "tap-$target",
                    toolName = MobileActionFunctions.UI_TAP,
                    arguments = mapOf("target" to target),
                ),
            )

            assertEquals(target, ToolStatus.Failed, result.status)
            assertEquals(target, "dangerous_ui_action_control_detected", result.data["summary"])
            assertTrue(target, result.summary.contains("需要你亲自操作"))
            assertEquals(target, emptyList<String>(), provider.tapTargets)
        }
    }

    @Test
    fun failsClosedWhenDismissRevealsDangerousTarget() {
        // Preflight #1 sees a benign overlay (dismissable) without the target. After the dismiss
        // tap, the revealed screen shows the target as a 立即购买 control. The post-dismiss
        // re-preflight must catch it and block the real tap.
        val provider = ScriptedControlProvider(
            observeSnapshots = listOf(
                overlaySnapshot("overlay-preflight"),
                overlaySnapshot("overlay-dismiss-check"),
                dangerousRevealedSnapshot("revealed-post-preflight"),
            ),
            dismissAfterSnapshots = listOf(dangerousRevealedSnapshot("revealed-from-tap")),
        )
        val executor = DeviceControlToolExecutor(provider = provider)

        val result = executor.execute(
            ToolRequest(
                id = "tap-4",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "cta"),
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("dangerous_ui_action_control_detected", result.data["summary"])
        // The dismiss tap happened, but the real tap must NOT (blocked by re-preflight).
        assertEquals(listOf("关闭"), provider.tapTargets)
        assertEquals("post-tap snapshot is reused inside the loop", 3, provider.observeCount)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────

    private fun node(
        id: String,
        text: String,
        clickable: Boolean = false,
    ): ScreenNode = ScreenNode(
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

    private fun overlaySnapshot(id: String) = snapshot(
        id = id,
        nodes = listOf(
            node("promo-1", "限时抢购 专属权益"),
            node("promo-2", "已获得 红包 倒计时"),
            node("close", "关闭", clickable = true),
            node("content", "商品", clickable = true),
        ),
    )

    private fun dangerousOverlaySnapshot(id: String) = snapshot(
        id = id,
        nodes = listOf(
            node("promo-1", "限时抢购 专属权益"),
            node("promo-2", "已获得 红包"),
            node("buy", "立即购买", clickable = true),
            node("close", "关闭", clickable = true),
            node("content", "商品", clickable = true),
        ),
    )

    private fun clearSnapshot(id: String) = snapshot(
        id = id,
        nodes = listOf(node("content", "商品", clickable = true)),
    )

    // A screen with a dangerous control and NO overlay markers: dismiss won't loop, but the
    // dangerous-action preflight must block a tap here.
    private fun dangerousRevealedSnapshot(id: String) = snapshot(
        id = id,
        nodes = listOf(
            node("cta", "立即购买", clickable = true),
            node("content", "商品", clickable = true),
        ),
    )

    private class ScriptedControlProvider(
        private val observeSnapshots: List<ScreenStateSnapshot>,
        private val dismissAfterSnapshots: List<ScreenStateSnapshot?> = emptyList(),
    ) : CurrentScreenControlProvider {
        private var observeIndex = 0
        private var dismissIndex = 0
        var observeCount = 0
            private set
        val tapTargets = mutableListOf<String>()

        override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
            observeCount += 1
            val snapshot = observeSnapshots.getOrElse(observeIndex) { observeSnapshots.last() }
            if (observeIndex < observeSnapshots.lastIndex) observeIndex += 1
            return ScreenStateReadResult.Available(snapshot)
        }

        override fun tap(target: String, timeoutMillis: Long): UiActionReadResult {
            tapTargets += target
            val after = if (target == "关闭") {
                dismissAfterSnapshots.getOrNull(dismissIndex++)
            } else {
                null
            }
            return succeeded(after = after)
        }

        override fun typeText(
            text: String,
            target: String?,
            timeoutMillis: Long,
            allowClipboardPasteFallback: Boolean,
        ): UiActionReadResult = succeeded()

        override fun submitSearch(timeoutMillis: Long): UiActionReadResult = succeeded()

        override fun scroll(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult =
            succeeded()

        override fun pressBack(timeoutMillis: Long): UiActionReadResult = succeeded()

        override fun waitForScreen(timeoutMillis: Long): UiActionReadResult = succeeded()

        override fun tapByNormalizedCoords(normalizedX: Int, normalizedY: Int, timeoutMillis: Long): UiActionReadResult =
            succeeded()

        private fun succeeded(after: ScreenStateSnapshot? = null): UiActionReadResult = UiActionReadResult.Available(
            UiActionExecutionResult(
                status = UiActionStatus.Succeeded,
                before = null,
                after = after,
                summary = "ok",
                retryable = false,
            ),
        )
    }
}
