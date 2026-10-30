package com.bytedance.zgx.solin.multimodal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureForegroundSessionTest {
    @Test
    fun awaitReturnsReadyOnceServiceReportsForeground() {
        val tracker = ScreenCaptureForegroundSessionTracker()
        tracker.begin("req-1")
        val started = CountDownLatch(1)
        val worker = thread {
            started.countDown()
            Thread.sleep(50)
            tracker.markForeground("req-1")
        }
        started.await(1, TimeUnit.SECONDS)

        assertEquals(ScreenCaptureForegroundStatus.Ready, tracker.await("req-1", timeoutMillis = 2_000L))
        worker.join()
        assertTrue(tracker.isForeground("req-1"))
    }

    @Test
    fun awaitTimesOutWithDistinctForegroundReasonNotMissingConsent() {
        val tracker = ScreenCaptureForegroundSessionTracker()
        tracker.begin("req-1")

        val status = tracker.await("req-1", timeoutMillis = 20L)

        assertEquals(ScreenCaptureForegroundStatus.Failed(SCREEN_CAPTURE_FOREGROUND_TIMEOUT_REASON), status)
    }

    @Test
    fun awaitForUnknownOrEndedRequestFailsClosed() {
        val tracker = ScreenCaptureForegroundSessionTracker()
        assertEquals(
            ScreenCaptureForegroundStatus.Failed(SCREEN_CAPTURE_FOREGROUND_NOT_STARTED_REASON),
            tracker.await("missing", timeoutMillis = 0L),
        )
        tracker.begin("req-1")
        tracker.markForeground("req-1")
        assertTrue(tracker.end("req-1"))
        assertEquals(
            ScreenCaptureForegroundStatus.Failed(SCREEN_CAPTURE_FOREGROUND_NOT_STARTED_REASON),
            tracker.await("req-1", timeoutMillis = 0L),
        )
    }

    @Test
    fun startFailureIsTerminalAndLateForegroundDoesNotResurrectIt() {
        val tracker = ScreenCaptureForegroundSessionTracker()
        tracker.begin("req-1")
        tracker.markFailed("req-1", SCREEN_CAPTURE_FOREGROUND_START_FAILED_REASON)

        assertFalse(tracker.markForeground("req-1"))
        assertEquals(
            ScreenCaptureForegroundStatus.Failed(SCREEN_CAPTURE_FOREGROUND_START_FAILED_REASON),
            tracker.await("req-1", timeoutMillis = 0L),
        )
    }

    @Test
    fun newSessionSupersedesOldOneAndOldReportsAreIgnored() {
        val tracker = ScreenCaptureForegroundSessionTracker()
        tracker.begin("old")

        assertEquals("old", tracker.begin("new"))
        assertFalse(tracker.markForeground("old"))
        assertFalse(tracker.end("old"))
        assertEquals("new", tracker.activeRequestId())
    }

    @Test
    fun sessionRunsCaptureOnlyWhenForegroundAndAlwaysStops() {
        val gate = FakeGate(ScreenCaptureForegroundStatus.Ready)
        var captured = false

        val result = withScreenCaptureForegroundSession(
            gate = gate,
            requestId = "req-1",
            timeoutMillis = 10L,
            onMissingConsent = { "missing" },
            onFailed = { "failed:$it" },
            unexpectedFailureReason = "unavailable",
        ) {
            captured = true
            "ok"
        }

        assertEquals("ok", result)
        assertTrue(captured)
        assertEquals(listOf("req-1"), gate.stopped)
    }

    @Test
    fun foregroundServiceFailureIsReportedDistinctlyAndSkipsCapture() {
        val gate = FakeGate(ScreenCaptureForegroundStatus.Failed(SCREEN_CAPTURE_FOREGROUND_START_FAILED_REASON))
        var captured = false

        val result = withScreenCaptureForegroundSession(
            gate = gate,
            requestId = "req-1",
            timeoutMillis = 10L,
            onMissingConsent = { "missing" },
            onFailed = { "failed:$it" },
            unexpectedFailureReason = "unavailable",
        ) {
            captured = true
            "ok"
        }

        assertEquals("failed:$SCREEN_CAPTURE_FOREGROUND_START_FAILED_REASON", result)
        assertFalse(captured)
        assertEquals(listOf("req-1"), gate.stopped)
    }

    @Test
    fun projectionSecurityExceptionsMapToConsentOrForegroundFailure() {
        val gate = FakeGate(ScreenCaptureForegroundStatus.Ready)
        fun run(error: Throwable): String =
            withScreenCaptureForegroundSession(
                gate = gate,
                requestId = "req-1",
                timeoutMillis = 10L,
                onMissingConsent = { "missing" },
                onFailed = { "failed:$it" },
                unexpectedFailureReason = "unavailable",
            ) { throw error }

        assertEquals("missing", run(SecurityException("Invalid media projection token")))
        assertEquals(
            "failed:$SCREEN_CAPTURE_FOREGROUND_START_FAILED_REASON",
            run(
                SecurityException(
                    "Media projections require a foreground service of type " +
                        "ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION",
                ),
            ),
        )
        assertEquals("failed:unavailable", run(IllegalStateException("boom")))
        assertEquals(3, gate.stopped.size)
    }

    @Test
    fun noForegroundServiceGateIsImmediatelyReady() {
        assertEquals(
            ScreenCaptureForegroundStatus.Ready,
            NoForegroundServiceRequiredGate.awaitForeground("req", 0L),
        )
        assertNull(ScreenCaptureForegroundSessionTracker().activeRequestId())
    }

    private class FakeGate(private val status: ScreenCaptureForegroundStatus) : ScreenCaptureForegroundGate {
        val stopped = mutableListOf<String>()

        override fun start(requestId: String) = Unit

        override fun awaitForeground(requestId: String, timeoutMillis: Long): ScreenCaptureForegroundStatus = status

        override fun stop(requestId: String) {
            stopped += requestId
        }
    }
}
