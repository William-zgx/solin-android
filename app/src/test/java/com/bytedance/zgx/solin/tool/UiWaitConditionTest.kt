package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.device.UiSystemKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiWaitConditionTest {
    private fun snapshot(vararg texts: String, packageName: String = "com.example.shop") = ScreenStateSnapshot(
        id = "s-${texts.joinToString()}",
        packageName = packageName,
        capturedAtMillis = 1L,
        nodes = texts.map { text ->
            ScreenNode(
                id = text,
                text = text,
                contentDescription = "",
                className = "android.widget.TextView",
                bounds = null,
                clickable = false,
                editable = false,
                scrollable = false,
                enabled = true,
            )
        },
        textSummary = texts.joinToString(" "),
        truncated = false,
    )

    /** Serves [reads] in order (the last one repeats) and records every call it gets. */
    private class ScriptedProvider(private val reads: List<ScreenStateReadResult>) : CurrentScreenControlProvider {
        val calls = mutableListOf<String>()
        private var index = 0

        override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
            calls += "observe"
            return reads[minOf(index++, reads.lastIndex)]
        }

        override fun tap(target: String, timeoutMillis: Long) = record("tap")
        override fun typeText(text: String, target: String?, timeoutMillis: Long, allowClipboardPasteFallback: Boolean) =
            record("type_text")
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
        override fun waitForScreen(timeoutMillis: Long) = record("wait:$timeoutMillis")
        override fun tapByNormalizedCoords(normalizedX: Int, normalizedY: Int, timeoutMillis: Long) = record("tap_normalized")

        private fun record(action: String): UiActionReadResult {
            calls += action
            return UiActionReadResult.Available(
                UiActionExecutionResult(UiActionStatus.Succeeded, before = null, after = null, summary = "ok", retryable = false),
            )
        }
    }

    private fun waitRequest(vararg arguments: Pair<String, String>) =
        ToolRequest(toolName = MobileActionFunctions.UI_WAIT, arguments = mapOf(*arguments))

    private fun available(snapshot: ScreenStateSnapshot) = ScreenStateReadResult.Available(snapshot)

    // ── Executor ────────────────────────────────────────────────────────────────────────────

    @Test
    fun conditionMetSucceedsAndOnlyObserves() {
        val provider = ScriptedProvider(listOf(available(snapshot("加载中")), available(snapshot("搜索 结果", "综合"))))
        val request = waitRequest("expectText" to "搜索结果")
        val result = DeviceControlToolExecutor(provider).execute(request)

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("true", result.data["conditionMet"])
        assertEquals("wait", result.data["actionType"])
        // ui_wait never taps or types; it does not even run the settle wait.
        assertEquals(listOf("observe", "observe"), provider.calls)
        assertNull(defaultBuiltInToolRegistry.validateResult(request, result))
    }

    @Test
    fun timeoutFailsWithTimeoutKindAndConditionNotMet() {
        val provider = ScriptedProvider(listOf(available(snapshot("加载中"))))
        val started = System.currentTimeMillis()
        val request = waitRequest("expectText" to "搜索结果", "timeoutMillis" to "100")
        val result = DeviceControlToolExecutor(provider).execute(request)

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("false", result.data["conditionMet"])
        assertEquals("timeout", result.data["failureKind"])
        assertTrue(result.retryable)
        // 100ms was clamped up to the 500ms floor.
        assertTrue(System.currentTimeMillis() - started >= UI_WAIT_CONDITION_MIN_TIMEOUT_MILLIS)
        assertTrue(provider.calls.all { it == "observe" })
        // Failed private results are sanitized to value-free diagnostics; conditionMet survives.
        val sanitized = defaultBuiltInToolRegistry.validateResult(request, result) ?: result
        assertEquals("false", sanitized.data["conditionMet"])
        assertEquals("timeout", sanitized.data["failureKind"])
    }

    @Test
    fun noConditionKeepsTheSettleWaitPath() {
        val provider = ScriptedProvider(listOf(available(snapshot("x"))))
        val result = DeviceControlToolExecutor(provider).execute(waitRequest("timeoutMillis" to "500"))

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(listOf("wait:500"), provider.calls)
        assertFalse(result.data.containsKey("conditionMet"))
    }

    @Test
    fun noConditionStillRejectsTheOldOutOfRangeTimeout() {
        assertEquals(ToolStatus.Rejected, defaultBuiltInToolRegistry.validate(waitRequest("timeoutMillis" to "15000"))?.status)
        assertNull(defaultBuiltInToolRegistry.validate(waitRequest("timeoutMillis" to "10000")))

        val provider = ScriptedProvider(listOf(available(snapshot("x"))))
        val result = DeviceControlToolExecutor(provider).execute(waitRequest("timeoutMillis" to "15000"))

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun conditionArgumentsPassTheSchema() {
        assertNull(
            defaultBuiltInToolRegistry.validate(
                waitRequest(
                    "expectText" to "综合",
                    "expectGoneText" to "加载中",
                    "expectPackage" to "com.example.shop",
                    "timeoutMillis" to "20000",
                ),
            ),
        )
    }

    // ── Poll loop (fake clock) ──────────────────────────────────────────────────────────────

    private fun poll(
        condition: UiWaitCondition,
        reads: List<ScreenStateReadResult>,
        guardPackage: String? = null,
    ): Pair<UiWaitOutcome, Int> {
        var now = 0L
        var observed = 0
        val outcome = runBlocking {
            awaitUiWaitCondition(
                condition = condition,
                guardPackage = guardPackage,
                observe = { reads[minOf(observed++, reads.lastIndex)] },
                clock = { now },
                sleep = { millis -> now += millis },
            )
        }
        return outcome to observed
    }

    private fun condition(text: String? = null, gone: String? = null, pkg: String? = null, timeout: Long = 2_000L) =
        UiWaitCondition(expectText = text, expectGoneText = gone, expectPackage = pkg, timeoutMillis = timeout)

    @Test
    fun textMatchingIgnoresWhitespaceAndCase() {
        assertTrue(condition(text = "Search Results").holds(snapshot("search results")))
        assertTrue(condition(text = "搜索结果").holds(snapshot("搜 索\n结果")))
        assertFalse(condition(text = "搜索结果").holds(snapshot("搜索")))
    }

    @Test
    fun goneNeedsAReadableScreen() {
        assertTrue(condition(gone = "加载中").holds(snapshot("综合")))
        assertFalse(condition(gone = "加载中").holds(snapshot("加 载 中…")))
        assertFalse(condition(gone = "加载中").holds(snapshot()))
    }

    @Test
    fun allConditionsMustHold() {
        val both = condition(text = "综合", pkg = "com.example.shop")
        assertTrue(both.holds(snapshot("综合")))
        assertFalse(both.holds(snapshot("综合", packageName = "com.other")))
    }

    @Test
    fun timeoutIsBoundedEvenWhenTheClockStalls() {
        var observed = 0
        val outcome = runBlocking {
            awaitUiWaitCondition(
                condition = condition(text = "never", timeout = 900L),
                guardPackage = null,
                observe = { observed++; available(snapshot("x")) },
                clock = { 0L },
                sleep = {},
            )
        }
        assertEquals(UiWaitStop.Timeout, outcome.stop)
        assertEquals(900L / UI_WAIT_CONDITION_POLL_INTERVAL_MILLIS + 2, observed.toLong())
    }

    @Test
    fun appSwitchStopsEarlyAfterTwoReads() {
        val (outcome, observed) = poll(
            condition(text = "综合"),
            listOf(available(snapshot("a")), available(snapshot("b", packageName = "com.other"))),
            guardPackage = "com.example.shop",
        )
        assertEquals(UiWaitStop.AppSwitched, outcome.stop)
        assertEquals(3, observed)
    }

    @Test
    fun theWaitedForPackageIsNotAnAppSwitch() {
        val (outcome, _) = poll(
            condition(pkg = "com.other"),
            listOf(available(snapshot("a")), available(snapshot("b", packageName = "com.other"))),
            guardPackage = "com.example.shop",
        )
        assertEquals(UiWaitStop.Met, outcome.stop)
    }

    @Test
    fun aNewOverlayStopsTheWait() {
        val (outcome, _) = poll(
            condition(text = "综合"),
            listOf(available(snapshot("首页")), available(snapshot("优惠券", "红包", "综合"))),
        )
        assertEquals(UiWaitStop.DialogAppeared, outcome.stop)
    }

    @Test
    fun theConditionTimeoutFitsTheDeviceControlBoundary() {
        // Worst case: the deadline lands just as one more observe starts (5s observe hard timeout).
        assertTrue(UI_WAIT_CONDITION_MAX_TIMEOUT_MILLIS + 5_000L < DEVICE_CONTROL_TOOL_EXECUTION_TIMEOUT_MILLIS)
    }

    @Test
    fun argumentsParseAndClamp() {
        assertNull(UiWaitCondition.fromArguments(mapOf("timeoutMillis" to "3000")))
        assertEquals(
            UiWaitCondition("综合", null, null, UI_WAIT_CONDITION_DEFAULT_TIMEOUT_MILLIS),
            UiWaitCondition.fromArguments(mapOf("expectText" to " 综合 ")),
        )
        assertEquals(
            UI_WAIT_CONDITION_MAX_TIMEOUT_MILLIS,
            UiWaitCondition.fromArguments(mapOf("expectPackage" to "a.b", "timeoutMillis" to "99999"))?.timeoutMillis,
        )
    }
}
