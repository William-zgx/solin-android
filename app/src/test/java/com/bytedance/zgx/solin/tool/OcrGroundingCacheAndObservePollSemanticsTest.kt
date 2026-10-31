package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.CalendarAvailabilityProvider
import com.bytedance.zgx.solin.device.CalendarAvailabilityQuery
import com.bytedance.zgx.solin.device.CalendarAvailabilityReadResult
import com.bytedance.zgx.solin.device.CalendarAvailabilityWindow
import com.bytedance.zgx.solin.device.ContactSummaryProvider
import com.bytedance.zgx.solin.device.ContactSummaryReadResult
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.ForegroundAppInfo
import com.bytedance.zgx.solin.device.ForegroundAppProvider
import com.bytedance.zgx.solin.device.ForegroundAppReadResult
import com.bytedance.zgx.solin.device.NotificationSummaryProvider
import com.bytedance.zgx.solin.device.NotificationSummaryReadResult
import com.bytedance.zgx.solin.device.RecentFileProvider
import com.bytedance.zgx.solin.device.RecentFileReadResult
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiOcrGroundingHint
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrReadResult
import com.bytedance.zgx.solin.multimodal.OcrTextBlock
import com.bytedance.zgx.solin.multimodal.OcrTextBounds
import com.bytedance.zgx.solin.multimodal.OcrTextLine
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for two concurrency/cancellation defects on the device-control path.
 *
 * 1. The OCR grounding cache used a bare `var`, so "consume invalidates" was a
 *    read-then-null pair rather than an atomic swap. Under concurrent consumers TWO UI
 *    actions could be handed the SAME grounding hint — and a grounding hint decides where a
 *    tap lands, so duplicated delivery is a safety defect, not a cache miss.
 * 2. The observe foreground-settle poll used `Thread.sleep`, which coroutine cancellation
 *    cannot interrupt, so a cancelled/timed-out observe still parked its worker thread for
 *    the full poll budget.
 *
 * These assert the SEMANTICS (at-most-once delivery; the wait is interruptible), not
 * timing luck, so they do not depend on hitting a race window.
 */
class OcrGroundingCacheAndObservePollSemanticsTest {

    // ── P0-1: grounding hint is delivered to at most one action ──────────────────────

    /**
     * Fire many concurrent named-target taps against a single captured grounding hint.
     * Exactly one tap may receive a non-null hint; all others must see null. Before the
     * AtomicReference fix the read-then-null pair allowed several taps to observe the same
     * hint, which would have re-applied stale OCR coordinates to unrelated actions.
     */
    @Test
    fun concurrentTapsConsumeTheGroundingHintAtMostOnce() {
        val threads = 8
        val provider = RecordingControlProvider(
            observeResult = ScreenStateReadResult.Available(groundedSnapshot()),
        )
        val executor = routingExecutorWithOcrGrounding(provider)

        // Publish exactly one grounding hint via the OCR capture path.
        executor.execute(captureOcrRequest())
        assertTrue(
            "capture must have published a hint, otherwise the test proves nothing",
            provider.observeCallCount.get() > 0,
        )

        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        try {
            repeat(threads) { i ->
                pool.execute {
                    ready.countDown()
                    go.await()
                    executor.execute(
                        ToolRequest(
                            id = "concurrent-tap-$i",
                            toolName = MobileActionFunctions.UI_TAP,
                            arguments = mapOf("target" to GROUNDED_TARGET, "timeoutMillis" to "500"),
                            reason = "test",
                        ),
                    )
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads, provider.tapOcrGroundingHints.size)
        val delivered = provider.tapOcrGroundingHints.count { it != null }
        assertTrue(
            "grounding hint was delivered $delivered times; consume must invalidate atomically",
            delivered <= 1,
        )
    }

    /** A punctuation-only OCR block (a "<" back arrow) normalizes to blank and must not match any target. */
    @Test
    fun punctuationOnlyOcrBlockIsNotAGroundingHintForAnUnrelatedTarget() {
        val provider = RecordingControlProvider(
            observeResult = ScreenStateReadResult.Available(groundedSnapshot()),
        )
        val executor = routingExecutorWithOcrGrounding(provider, ocrText = "<")

        executor.execute(captureOcrRequest())
        executor.execute(tapRequest("tap-1"))

        assertEquals(1, provider.tapOcrGroundingHints.size)
        assertNull(provider.tapOcrGroundingHints[0])
    }

    /**
     * Sequential companion to the concurrent case: the FIRST consumer gets the hint, the
     * second gets null. This pins the "consume invalidates" contract itself, so a future
     * refactor that keeps the hint alive for a second action fails here rather than only
     * under a hard-to-reproduce interleaving.
     */
    @Test
    fun secondTapAfterASingleCaptureReceivesNoGroundingHint() {
        val provider = RecordingControlProvider(
            observeResult = ScreenStateReadResult.Available(groundedSnapshot()),
        )
        val executor = routingExecutorWithOcrGrounding(provider)

        executor.execute(captureOcrRequest())
        executor.execute(tapRequest("tap-1"))
        executor.execute(tapRequest("tap-2"))

        assertEquals(2, provider.tapOcrGroundingHints.size)
        assertNotNull("first tap should consume the captured hint", provider.tapOcrGroundingHints[0])
        assertNull("second tap must not reuse a consumed hint", provider.tapOcrGroundingHints[1])
    }

    /** A non-OCR built-in tool between capture and tap must invalidate the hint. */
    @Test
    fun unrelatedToolBetweenCaptureAndTapInvalidatesTheGroundingHint() {
        val provider = RecordingControlProvider(
            observeResult = ScreenStateReadResult.Available(groundedSnapshot()),
        )
        val executor = routingExecutorWithOcrGrounding(provider)

        executor.execute(captureOcrRequest())
        executor.execute(
            ToolRequest(
                id = "unrelated",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                reason = "test",
            ),
        )
        executor.execute(tapRequest("tap-after-unrelated"))

        assertEquals(listOf<UiOcrGroundingHint?>(null), provider.tapOcrGroundingHints)
    }

    // ── P0-2: the observe foreground-settle wait is cancellable ─────────────────────

    /**
     * The settle poll must abort promptly when interrupted instead of parking the thread for
     * the whole `MAX_ATTEMPTS * INTERVAL` budget.
     *
     * `expectedPackageName` never matches, so the poll runs to its full budget: 6 attempts
     * x 250ms = 1.5s of waiting. We interrupt after a fraction of that and require the call
     * to unwind well before the uninterruptible budget elapses.
     *
     * WHY interrupt rather than an enclosing `withTimeoutOrNull`: `executeObserve` still uses
     * `runBlocking` (the [ToolExecutor.execute] interface is synchronous), which starts a ROOT
     * job — so an outer coroutine's cancellation does not propagate into it, and asserting on
     * that would be asserting something the code does not yet promise. Thread interruption
     * IS converted by `runBlocking` into cancellation of its coroutine, so this exercises
     * exactly the property the fix delivers: the wait itself is interruptible. With
     * `Thread.sleep` the interrupt could not break the wait and the call ran the full budget.
     */
    @Test
    fun observeSettlePollAbortsEarlyWhenTheCallingThreadIsInterrupted() {
        val fullPollBudgetMillis =
            DeviceControlToolExecutor.FOREGROUND_READINESS_POLL_MAX_ATTEMPTS *
                DeviceControlToolExecutor.FOREGROUND_READINESS_POLL_INTERVAL_MILLIS
        val provider = RecordingControlProvider(
            // Always a different package, so the settle condition never becomes satisfied.
            observeResult = ScreenStateReadResult.Available(
                groundedSnapshot(packageName = "com.example.other"),
            ),
        )
        val executor = DeviceControlToolExecutor(provider)
        val elapsedMillis = AtomicLong(-1L)
        val started = CountDownLatch(1)

        val worker = Thread {
            started.countDown()
            val begin = System.nanoTime()
            runCatching {
                executor.execute(
                    ToolRequest(
                        id = "observe-cancelled",
                        toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                        arguments = mapOf("expectedPackageName" to "com.example.never"),
                        reason = "test",
                    ),
                )
            }
            elapsedMillis.set((System.nanoTime() - begin) / 1_000_000)
        }
        worker.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // Let at least one poll interval begin, then interrupt mid-wait.
        Thread.sleep(DeviceControlToolExecutor.FOREGROUND_READINESS_POLL_INTERVAL_MILLIS / 2)
        worker.interrupt()
        worker.join(TimeUnit.SECONDS.toMillis(20))

        assertTrue("worker should have finished", !worker.isAlive)
        val observed = elapsedMillis.get()
        assertTrue("observe never recorded an elapsed time", observed >= 0)
        assertTrue(
            "observe took ${observed}ms; a cancellable wait must unwind well before the " +
                "${fullPollBudgetMillis}ms uninterruptible poll budget",
            observed < fullPollBudgetMillis,
        )
    }

    /** Guard the happy path: a matching package must settle without consuming the budget. */
    @Test
    fun observeSucceedsImmediatelyWhenExpectedPackageIsAlreadyForeground() {
        val provider = RecordingControlProvider(
            observeResult = ScreenStateReadResult.Available(groundedSnapshot()),
        )
        val result = DeviceControlToolExecutor(provider).execute(
            ToolRequest(
                id = "observe-settled",
                toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                arguments = mapOf("expectedPackageName" to GROUNDED_PACKAGE),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("no retry should be needed on the happy path", 1, provider.observeCallCount.get())
    }

    /**
     * PermissionMissing is NOT a transient read failure: the settle poll must stop at once
     * rather than burn the whole retry budget on a read that cannot recover. Preserving this
     * fail-fast branch matters because retrying would delay surfacing the permission gate.
     */
    @Test
    fun observeStopsPollingImmediatelyOnPermissionMissing() {
        val provider = RecordingControlProvider(
            observeResult = ScreenStateReadResult.Failed(
                reason = "accessibility service not enabled",
                failureKind = UiActionFailureKind.PermissionMissing,
            ),
        )
        val result = DeviceControlToolExecutor(provider).execute(
            ToolRequest(
                id = "observe-permission-missing",
                toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(
            "PermissionMissing must not be retried",
            1,
            provider.observeCallCount.get(),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private fun captureOcrRequest() = ToolRequest(
        id = "capture-ocr",
        toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
        arguments = mapOf("captureMode" to "current_screen"),
        reason = "test",
    )

    private fun tapRequest(id: String) = ToolRequest(
        id = id,
        toolName = MobileActionFunctions.UI_TAP,
        arguments = mapOf("target" to GROUNDED_TARGET, "timeoutMillis" to "500"),
        reason = "test",
    )

    private fun routingExecutorWithOcrGrounding(
        provider: CurrentScreenControlProvider,
        ocrText: String = GROUNDED_TARGET,
    ): RoutingToolExecutor =
        RoutingToolExecutor(
            calendarAvailabilityProvider = object : CalendarAvailabilityProvider {
                override fun queryAvailability(
                    window: CalendarAvailabilityWindow,
                ): CalendarAvailabilityReadResult =
                    CalendarAvailabilityReadResult.Available(
                        CalendarAvailabilityQuery.snapshotFromBusyIntervals(
                            window = window,
                            busyIntervals = emptyList(),
                        ),
                    )
            },
            foregroundAppProvider = object : ForegroundAppProvider {
                override fun currentForegroundApp(): ForegroundAppReadResult =
                    ForegroundAppReadResult.Available(
                        ForegroundAppInfo(
                            packageName = GROUNDED_PACKAGE,
                            appLabel = "Example",
                            lastTimeUsedMillis = 1L,
                        ),
                    )
            },
            contactSummaryProvider = object : ContactSummaryProvider {
                override fun queryContacts(query: String, maxCount: Int): ContactSummaryReadResult =
                    ContactSummaryReadResult.Available(emptyList())
            },
            notificationSummaryProvider = object : NotificationSummaryProvider {
                override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult =
                    NotificationSummaryReadResult.Available(emptyList())
            },
            recentFileProvider = object : RecentFileProvider {
                override fun recentFiles(kind: String, maxCount: Int): RecentFileReadResult =
                    RecentFileReadResult.Available(emptyList())
            },
            webSearchProvider = object : WebSearchProvider {
                override fun search(request: WebSearchRequest): WebSearchReadResult =
                    WebSearchReadResult.Failed("unused")
            },
            delegate = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult =
                    request.failed(
                        code = ToolErrorCode.UnknownTool,
                        summary = "delegate not reached for ${request.toolName}",
                        retryable = false,
                    )
            },
            currentScreenshotOcrProvider = object : CurrentScreenshotOcrProvider {
                override fun setOneShotConsent(
                    requestId: String,
                    resultCode: Int,
                    data: android.content.Intent?,
                    issuedAtMillis: Long,
                ) = Unit

                override fun clearOneShotConsent(requestId: String) = Unit

                override fun hasOneShotConsent(requestId: String, nowMillis: Long): Boolean = true

                override fun captureCurrentScreenshotOcr(
                    requestId: String,
                    nowMillis: Long,
                ): CurrentScreenshotOcrReadResult =
                    CurrentScreenshotOcrReadResult.Available(
                        text = ocrText,
                        truncated = false,
                        ocrBlocks = listOf(
                            OcrTextBlock(
                                text = ocrText,
                                bounds = OcrTextBounds(left = 10, top = 20, right = 110, bottom = 70),
                                lines = listOf(OcrTextLine(text = ocrText)),
                            ),
                        ),
                    )
            },
            currentScreenControlProvider = provider,
        )

    /**
     * Control provider that records every grounding hint it is handed. Deliberately backed by
     * synchronized collections and atomics: the concurrency test calls it from many threads
     * at once, so an unsynchronized recorder would itself be racy and could mask the defect.
     */
    private class RecordingControlProvider(
        private val observeResult: ScreenStateReadResult,
    ) : CurrentScreenControlProvider {
        val observeCallCount = AtomicInteger(0)
        val tapOcrGroundingHints: MutableList<UiOcrGroundingHint?> =
            Collections.synchronizedList(mutableListOf())

        private val actionResult = UiActionReadResult.Available(
            UiActionExecutionResult(
                status = UiActionStatus.Succeeded,
                before = groundedSnapshot(),
                after = groundedSnapshot(),
                summary = "action completed",
                retryable = false,
            ),
        )

        override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
            observeCallCount.incrementAndGet()
            return observeResult
        }

        override fun tap(target: String, timeoutMillis: Long): UiActionReadResult =
            tapWithOcrGrounding(target = target, ocrGroundingHint = null, timeoutMillis = timeoutMillis)

        override fun tapWithOcrGrounding(
            target: String,
            ocrGroundingHint: UiOcrGroundingHint?,
            timeoutMillis: Long,
        ): UiActionReadResult {
            tapOcrGroundingHints += ocrGroundingHint
            return actionResult
        }

        override fun typeText(
            text: String,
            target: String?,
            timeoutMillis: Long,
            allowClipboardPasteFallback: Boolean,
        ): UiActionReadResult = actionResult

        override fun submitSearch(timeoutMillis: Long): UiActionReadResult = actionResult

        override fun scroll(
            direction: UiScrollDirection,
            target: String?,
            timeoutMillis: Long,
        ): UiActionReadResult = actionResult

        override fun pressBack(timeoutMillis: Long): UiActionReadResult = actionResult

        override fun waitForScreen(timeoutMillis: Long): UiActionReadResult = actionResult

        override fun tapByNormalizedCoords(
            normalizedX: Int,
            normalizedY: Int,
            timeoutMillis: Long,
        ): UiActionReadResult = actionResult
    }

    private companion object {
        const val GROUNDED_TARGET = "继续"
        const val GROUNDED_PACKAGE = "com.example.app"

        fun groundedSnapshot(packageName: String = GROUNDED_PACKAGE): ScreenStateSnapshot =
            ScreenStateSnapshot(
                id = "screen-grounded",
                packageName = packageName,
                capturedAtMillis = Instant.parse("2026-06-01T09:00:00Z").toEpochMilli(),
                nodes = listOf(
                    ScreenNode(
                        id = "n0_container",
                        text = "",
                        contentDescription = "",
                        className = "android.widget.FrameLayout",
                        bounds = ScreenBounds(0, 0, 1080, 2200),
                        clickable = false,
                        editable = false,
                        scrollable = false,
                        enabled = true,
                    ),
                ),
                textSummary = "无可点击文本",
                truncated = false,
                widthPx = 1080,
                heightPx = 2200,
            )
    }
}
