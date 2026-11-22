package com.bytedance.zgx.solin

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves app-side requests and state only (recognizer start/cancel calls, capture events); it says
 * nothing about the physical microphone indicator or OEM recognizer behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecognitionCoordinatorTest {
    private class Harness(scope: TestScope) {
        val events = mutableListOf<String>()
        var startCalls = 0
        var stopCalls = 0
        var cancelCalls = 0
        var releaseCalls = 0
        var generation = 0L
        val session = VoiceRecognitionSession(
            scope = scope.backgroundScope,
            sink = object : VoiceCaptureSink {
                override fun captureStarted() { events += "started" }
                override fun captureFinished() { events += "finished" }
                override fun level(rmsDb: Float) { events += "level" }
                override fun partial(transcript: String) { events += "partial" }
                override fun transcript(transcript: String) { events += "transcript" }
                override fun unavailable(message: String) { events += "unavailable:$message" }
            },
            cancelRecognizer = { cancelCalls++ },
            deadlineMs = DEADLINE,
        )

        fun begin(): Boolean =
            session.start(
                installListener = { generation = it },
                startListening = { startCalls++ },
            )

        fun terminalCount() = events.count { it.startsWith("unavailable") || it == "transcript" }
    }

    @Test
    fun silenceBeforeDeadlineIsNoOpAndAtDeadlineCancelsOnceWithoutRestart() = runTest {
        val h = Harness(this)
        assertTrue(h.begin())
        advanceTimeBy(DEADLINE - 1)
        runCurrent()
        assertEquals(0, h.cancelCalls)
        assertEquals(0, h.terminalCount())
        assertTrue(h.session.isActive)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, h.cancelCalls)
        assertEquals(1, h.terminalCount())
        assertEquals("unavailable:语音输入超时", h.events.last())
        assertEquals(1, h.startCalls)
        assertFalse(h.session.isActive)

        advanceTimeBy(DEADLINE * 3)
        runCurrent()
        assertEquals(1, h.cancelCalls)
        assertEquals(1, h.startCalls)
    }

    @Test
    fun finishAndSignalsDoNotExtendTheAbsoluteDeadline() = runTest {
        val h = Harness(this)
        h.begin()
        advanceTimeBy(DEADLINE - 10_000)
        h.session.onLevel(h.generation, 3f)
        h.session.onPartial(h.generation, "x")
        h.session.finish { h.stopCalls++ }
        assertEquals(1, h.stopCalls)
        assertTrue(h.session.isActive)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(0, h.cancelCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, h.cancelCalls)
        assertEquals(1, h.terminalCount())
    }

    @Test
    fun resultsBeforeDeadlineWinAndLaterTimeoutOrErrorAreIgnored() = runTest {
        val h = Harness(this)
        h.begin()
        advanceTimeBy(DEADLINE - 1)
        h.session.onResults(h.generation, "hello")
        h.session.onError(h.generation, "late error")
        advanceTimeBy(DEADLINE)
        runCurrent()
        assertEquals(listOf("started", "transcript"), h.events)
        assertEquals(0, h.cancelCalls)
    }

    @Test
    fun timeoutBeforeResultsWinsAndLateResultsProduceNoDraft() = runTest {
        val h = Harness(this)
        h.begin()
        advanceTimeBy(DEADLINE)
        runCurrent()
        h.session.onResults(h.generation, "secret words")
        h.session.onPartial(h.generation, "secret")
        h.session.onError(h.generation, "late")
        assertEquals(listOf("started", "unavailable:语音输入超时"), h.events)
    }

    @Test
    fun explicitCancelIsTerminalAndTimeoutDoesNotFireAfterwards() = runTest {
        val h = Harness(this)
        h.begin()
        h.session.cancel()
        advanceTimeBy(DEADLINE * 2)
        runCurrent()
        assertEquals(1, h.cancelCalls)
        assertEquals(1, h.terminalCount())
    }

    @Test
    fun onStopCancelsActiveGenerationRejectsLateResultsAndResumeDoesNotRestart() = runTest {
        val h = Harness(this)
        h.begin()
        h.session.stopForLifecycle("stopped")
        assertEquals(1, h.cancelCalls)
        assertFalse(h.session.isActive)
        h.session.onResults(h.generation, "late")
        advanceTimeBy(DEADLINE * 2)
        runCurrent()
        assertEquals(listOf("started", "unavailable:stopped"), h.events)
        // onResume has no session hook: nothing reopens the recognizer.
        assertEquals(1, h.startCalls)
        // A second stop while idle is a no-op.
        h.session.stopForLifecycle("stopped")
        assertEquals(1, h.cancelCalls)
        assertEquals(1, h.terminalCount())
    }

    @Test
    fun destroyAfterStopReleasesOnceWithoutDuplicateTerminal() = runTest {
        val h = Harness(this)
        h.begin()
        h.session.stopForLifecycle("stopped")
        h.session.destroy(releaseRecognizer = { h.releaseCalls++ }, canReport = { true })
        assertEquals(1, h.releaseCalls)
        assertEquals(1, h.terminalCount())
    }

    @Test
    fun destroyWhileActiveReportsOnceAndReleasesOnce() = runTest {
        val h = Harness(this)
        h.begin()
        h.session.destroy(releaseRecognizer = { h.releaseCalls++ }, canReport = { true })
        advanceTimeBy(DEADLINE * 2)
        runCurrent()
        assertEquals(1, h.releaseCalls)
        assertEquals(1, h.terminalCount())
        assertEquals(0, h.cancelCalls)
    }

    @Test
    fun repeatedStartWhileActiveCallsRecognizerOnce() = runTest {
        val h = Harness(this)
        assertTrue(h.begin())
        val gen = h.generation
        assertFalse(h.begin())
        assertFalse(h.begin())
        assertEquals(1, h.startCalls)
        assertEquals(gen, h.generation)
        assertEquals(listOf("started"), h.events)
    }

    @Test
    fun restartAfterTerminalGetsFreshGenerationAndRejectsStaleListener() = runTest {
        val h = Harness(this)
        h.begin()
        val stale = h.generation
        h.session.cancel()
        assertTrue(h.begin())
        h.session.onResults(stale, "stale")
        h.session.onError(stale, "stale")
        assertTrue(h.session.isActive)
        assertEquals(0, h.events.count { it == "transcript" })
        // The old deadline must not cut the new generation short.
        advanceTimeBy(DEADLINE - 1)
        runCurrent()
        assertTrue(h.session.isActive)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(h.session.isActive)
        assertEquals(2, h.startCalls)
    }

    @Test
    fun startFailureClearsGenerationOnceAndStopsWatchdog() = runTest {
        val h = Harness(this)
        h.session.start(installListener = { h.generation = it }, startListening = { error("boom") })
        assertFalse(h.session.isActive)
        advanceTimeBy(DEADLINE * 2)
        runCurrent()
        assertEquals(listOf("started", "unavailable:语音输入启动失败"), h.events)
        assertEquals(0, h.cancelCalls)
    }

    @Test
    fun finishWithoutActiveGenerationReportsInterruptedAndStopFailureIsTerminalOnce() = runTest {
        val h = Harness(this)
        h.session.finish { h.stopCalls++ }
        assertEquals(listOf("unavailable:语音输入已中断"), h.events)
        assertEquals(0, h.stopCalls)

        h.begin()
        h.session.finish(null)
        assertFalse(h.session.isActive)
        assertEquals(2, h.events.count { it == "unavailable:语音输入已中断" })
        h.begin()
        h.session.finish { error("no service") }
        assertFalse(h.session.isActive)
        assertEquals(1, h.events.count { it == "unavailable:当前设备没有可用语音识别服务" })
    }

    @Test
    fun gateRejectsIdZero() {
        assertFalse(VoiceInputSessionGate().isActive(0L))
    }

    private companion object {
        const val DEADLINE = 60_000L
    }
}
