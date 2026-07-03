package com.bytedance.zgx.solin.orchestration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolProgressPublisherTest {

    /**
     * Fake wall clock we can advance manually for deterministic throttle testing.
     */
    private class FakeClock(initial: Long = 1_000L) {
        @Volatile var nowMs: Long = initial
            private set

        fun advanceBy(ms: Long) {
            nowMs += ms
        }

        fun asSupplier(): () -> Long = { nowMs }
    }

    @Test
    fun startPublishesToolStartedAndInitialProgressAtZero() = runTest {
        val bus = DefaultSolinEventBus()
        val clock = FakeClock()
        val pub = DefaultToolProgressPublisher(runId = "run-1", eventBus = bus, clock = clock.asSupplier())

        val started = mutableListOf<SolinEvent.Agent.ToolStarted>()
        val progress = mutableListOf<SolinEvent.Agent.ToolProgress>()
        val j1 = backgroundScope.launch {
            bus.subscribe(SolinEvent.Agent.ToolStarted::class).take(1).toList(started)
        }
        val j2 = backgroundScope.launch {
            bus.subscribe(SolinEvent.Agent.ToolProgress::class).take(1).toList(progress)
        }
        advanceUntilIdle()
        pub.start(toolCallId = "tc-1", toolName = "calendar.read", description = "Reading calendar")
        advanceUntilIdle()
        j1.join(); j2.join()

        assertEquals(1, started.size)
        assertEquals("tc-1", started.single().toolCallId)
        assertEquals("calendar.read", started.single().toolName)
        assertEquals("run-1", started.single().runId)

        val init = progress.single()
        assertEquals(0f, init.progress, 0.001f)
        assertEquals("tc-1", init.toolCallId)
        assertEquals("Reading calendar", init.partialText)
    }

    @Test
    fun startIsIdempotentPerToolCallId() = runTest {
        val bus = DefaultSolinEventBus()
        val clock = FakeClock()
        val pub = DefaultToolProgressPublisher(runId = "run-1", eventBus = bus, clock = clock.asSupplier())

        val started = mutableListOf<SolinEvent.Agent.ToolStarted>()
        val progress = mutableListOf<SolinEvent.Agent.ToolProgress>()
        val j1 = backgroundScope.launch {
            bus.subscribe(SolinEvent.Agent.ToolStarted::class).take(1).toList(started)
        }
        val j2 = backgroundScope.launch {
            // Expect at most 1 progress event since second start is suppressed.
            // Use take(1) and do not advance past the point where we'd publish more.
            bus.subscribe(SolinEvent.Agent.ToolProgress::class).take(1).toList(progress)
        }
        advanceUntilIdle()
        pub.start("tc-1", "calendar.read", "first")
        clock.advanceBy(5000)
        pub.start("tc-1", "calendar.read", "second") // duplicate; must not re-publish
        advanceUntilIdle()
        j1.join(); j2.join()

        assertEquals(1, started.size)
        assertEquals(1, progress.size)
        assertEquals("first", progress.single().partialText)
    }

    @Test
    fun stepPublishesUpdatedProgress() = runTest {
        val bus = DefaultSolinEventBus()
        val clock = FakeClock()
        val pub = DefaultToolProgressPublisher(runId = "run-1", eventBus = bus, clock = clock.asSupplier())

        val progress = mutableListOf<SolinEvent.Agent.ToolProgress>()
        val job = backgroundScope.launch {
            // start(0f) + step(3/10) + step(7/10) = 3 events
            bus.subscribe(SolinEvent.Agent.ToolProgress::class).take(3).toList(progress)
        }
        advanceUntilIdle()
        pub.start("tc-1", "tool", null)
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.step("tc-1", "tool", completed = 3, total = 10, description = "working")
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.step("tc-1", "tool", completed = 7, total = 10, description = null)
        advanceUntilIdle()
        job.join()

        assertEquals(3, progress.size)
        assertEquals(0f, progress[0].progress, 0.001f)
        assertEquals(0.3f, progress[1].progress, 0.001f)
        assertTrue(progress[1].partialText!!.contains("3/10"))
        assertTrue(progress[1].partialText!!.contains("working"))
        assertEquals(0.7f, progress[2].progress, 0.001f)
        assertTrue(progress[2].partialText!!.contains("7/10"))
    }

    @Test
    fun stepWithUnknownTotalUsesIndeterminateProgress() = runTest {
        val bus = DefaultSolinEventBus()
        val clock = FakeClock()
        val pub = DefaultToolProgressPublisher(runId = "r", eventBus = bus, clock = clock.asSupplier())

        val progress = mutableListOf<SolinEvent.Agent.ToolProgress>()
        val job = backgroundScope.launch {
            bus.subscribe(SolinEvent.Agent.ToolProgress::class).take(2).toList(progress)
        }
        advanceUntilIdle()
        pub.start("tc-1", "tool", null)
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.step("tc-1", "tool", completed = 42, total = null, description = null)
        advanceUntilIdle()
        job.join()

        assertEquals(2, progress.size)
        assertEquals(-1f, progress[1].progress, 0.001f)
        assertTrue(progress[1].partialText!!.contains("42"))
    }

    @Test
    fun completeAlwaysPublishesFinalEventBypassingThrottle() = runTest {
        val bus = DefaultSolinEventBus()
        val clock = FakeClock()
        val pub = DefaultToolProgressPublisher(runId = "r", eventBus = bus, clock = clock.asSupplier())

        val progress = mutableListOf<SolinEvent.Agent.ToolProgress>()
        val job = backgroundScope.launch {
            // start(0f) + complete(1f) = at least 2. Use take(2) and verify the final one is complete.
            bus.subscribe(SolinEvent.Agent.ToolProgress::class).take(2).toList(progress)
        }
        advanceUntilIdle()
        pub.start("tc-1", "tool", null)
        // No clock advance — step is within throttle window and coalesced.
        pub.step("tc-1", "tool", completed = 5, total = 10, description = null)
        pub.complete("tc-1", "tool")
        advanceUntilIdle()
        job.join()

        val finalEvent = progress.last()
        assertEquals(1f, finalEvent.progress, 0.001f)
        assertEquals("completed", finalEvent.partialText)
        assertEquals("final event must be the completed event", 1f, progress.last().progress, 0.001f)
        assertEquals("completed", progress.last().partialText)
    }

    @Test
    fun failPublishesFailureWithErrorMessage() = runTest {
        val bus = DefaultSolinEventBus()
        val clock = FakeClock()
        val pub = DefaultToolProgressPublisher(runId = "r", eventBus = bus, clock = clock.asSupplier())

        val progress = mutableListOf<SolinEvent.Agent.ToolProgress>()
        val job = backgroundScope.launch {
            // start(0f) + fail(error) = 2 events
            bus.subscribe(SolinEvent.Agent.ToolProgress::class).take(2).toList(progress)
        }
        advanceUntilIdle()
        pub.start("tc-1", "tool", null)
        pub.fail("tc-1", "tool", error = "disk full")
        advanceUntilIdle()
        job.join()

        val failed = progress.last()
        assertEquals(-1f, failed.progress, 0.001f)
        assertTrue(failed.partialText!!.contains("error: disk full"))
    }

    @Test
    fun throttlingSuppressesRapidUpdatesWithinWindow() = runTest {
        val bus = DefaultSolinEventBus()
        val clock = FakeClock()
        val pub = DefaultToolProgressPublisher(runId = "r", eventBus = bus, clock = clock.asSupplier())

        val window = DefaultToolProgressPublisher.THROTTLE_WINDOW_MS
        // We expect: start(0f) + then 1 post-advance step = 2 events. Use take(2).
        val progress = mutableListOf<SolinEvent.Agent.ToolProgress>()
        val job = backgroundScope.launch {
            bus.subscribe(SolinEvent.Agent.ToolProgress::class).take(2).toList(progress)
        }
        advanceUntilIdle()
        pub.start("tc-1", "tool", null)
        repeat(100) { i ->
            pub.step("tc-1", "tool", completed = i + 1, total = 100, description = null)
        }
        clock.advanceBy(window + 1)
        pub.step("tc-1", "tool", completed = 100, total = 100, description = "done")
        advanceUntilIdle()
        job.join()

        assertEquals(2, progress.size)
        assertEquals(0f, progress[0].progress, 0.001f)
        assertEquals(1f, progress[1].progress, 0.001f)
        assertTrue(progress[1].partialText!!.contains("100/100"))
    }

    @Test
    fun concurrentUpdatesFromMultipleCoroutinesDoNotCrash() = runTest {
        // Use a recording wrapper around the bus to avoid subscriber-timing races.
        val base = DefaultSolinEventBus()
        val progressEvents = java.util.Collections.synchronizedList(mutableListOf<SolinEvent.Agent.ToolProgress>())
        val startedEvents = java.util.Collections.synchronizedList(mutableListOf<SolinEvent.Agent.ToolStarted>())
        val bus = object : SolinEventBus {
            override fun publish(event: SolinEvent) {
                base.publish(event)
                if (event is SolinEvent.Agent.ToolProgress) progressEvents.add(event)
                if (event is SolinEvent.Agent.ToolStarted) startedEvents.add(event)
            }
            override fun <E : SolinEvent> subscribe(type: kotlin.reflect.KClass<E>) = base.subscribe(type)
            override fun <E : SolinEvent> recent(type: kotlin.reflect.KClass<out E>, limit: Int) = base.recent(type, limit)
        }

        val clock = FakeClock()
        val pub = DefaultToolProgressPublisher(runId = "r", eventBus = bus, clock = clock.asSupplier())

        pub.start("tc-conc", "hammer", null)
        // Launch 50 coroutines that each call step() 20 times. Yield so they interleave on the
        // test dispatcher, which exercises thread-safety of the slot/CAS logic.
        val hammerJobs = (0 until 50).map { i ->
            backgroundScope.launch {
                repeat(20) { j ->
                    clock.advanceBy(1)
                    pub.step("tc-conc", "hammer", completed = i * 20 + j, total = 1000, description = null)
                    yield() // allow interleaving
                }
            }
        }
        hammerJobs.forEach { it.join() }
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.complete("tc-conc", "hammer")
        advanceUntilIdle()

        // No crash is the primary assertion. Additionally:
        assertTrue("expected start event", startedEvents.any { it.toolCallId == "tc-conc" })
        assertTrue("expected at least one throttled progress event", progressEvents.size >= 2)
        val last = progressEvents.last()
        assertEquals(1f, last.progress, 0.001f)
        assertEquals("completed", last.partialText)
    }

    @Test
    fun progressAcrossMultipleToolCallIdsDoesNotInterfere() = runTest {
        val bus = DefaultSolinEventBus()
        val clock = FakeClock()
        val pub = DefaultToolProgressPublisher(runId = "r", eventBus = bus, clock = clock.asSupplier())

        // Expect: 2 start events (0f A, 0f B) + 2 steps + complete A + fail B = 6 events.
        val progress = mutableListOf<SolinEvent.Agent.ToolProgress>()
        val job = backgroundScope.launch {
            bus.subscribe(SolinEvent.Agent.ToolProgress::class).take(6).toList(progress)
        }
        advanceUntilIdle()
        pub.start("tc-A", "toolA", "A-start")
        pub.start("tc-B", "toolB", "B-start")
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.step("tc-A", "toolA", completed = 1, total = 2, description = "a1")
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.step("tc-B", "toolB", completed = 5, total = 10, description = "b1")
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.complete("tc-A", "toolA")
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.fail("tc-B", "toolB", error = "b-fail")
        advanceUntilIdle()
        job.join()

        val aEvents = progress.filter { it.toolCallId == "tc-A" }
        val bEvents = progress.filter { it.toolCallId == "tc-B" }

        assertTrue("tc-A should have start event", aEvents.any { it.progress == 0f && it.partialText == "A-start" })
        assertTrue("tc-A should have complete event", aEvents.any { it.progress == 1f && it.partialText == "completed" })
        val aStep = aEvents.firstOrNull { it.partialText != null && "a1" in it.partialText }
        assertNotNull("tc-A step event with 'a1' must exist", aStep)
        assertEquals(0.5f, aStep!!.progress, 0.001f)

        assertTrue("tc-B should have start event", bEvents.any { it.progress == 0f && it.partialText == "B-start" })
        assertTrue("tc-B should have fail event", bEvents.any { it.partialText != null && "b-fail" in it.partialText })
        val bStep = bEvents.firstOrNull { it.partialText != null && "b1" in it.partialText }
        assertNotNull("tc-B step event with 'b1' must exist", bStep)
        assertEquals(0.5f, bStep!!.progress, 0.001f)
    }

    @Test
    fun updateDispatchRoutesEachVariantCorrectly() = runTest {
        val bus = DefaultSolinEventBus()
        val clock = FakeClock()
        val pub = DefaultToolProgressPublisher(runId = "r", eventBus = bus, clock = clock.asSupplier())

        // Expect: Started(0f) + LogLine + Intermediate + Bytes + Completed(1f) = 5 events.
        val progress = mutableListOf<SolinEvent.Agent.ToolProgress>()
        val job = backgroundScope.launch {
            bus.subscribe(SolinEvent.Agent.ToolProgress::class).take(5).toList(progress)
        }
        advanceUntilIdle()
        pub.update(ProgressUpdate.Started("tc-x", "x", description = "via-update"))
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.update(ProgressUpdate.LogLine("tc-x", "x", line = "hello log"))
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.update(ProgressUpdate.IntermediateResult("tc-x", "x", preview = "partial result"))
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.update(ProgressUpdate.BytesTransferred("tc-x", "x", bytesRead = 2048, totalBytes = 4096))
        clock.advanceBy(DefaultToolProgressPublisher.THROTTLE_WINDOW_MS + 1)
        pub.update(ProgressUpdate.Completed("tc-x", "x"))
        advanceUntilIdle()
        job.join()

        assertTrue("expected started event", progress.any { it.partialText == "via-update" && it.progress == 0f })
        assertTrue("expected log line", progress.any { it.partialText == "hello log" })
        assertTrue("expected intermediate", progress.any { it.partialText == "partial result" })
        assertTrue("expected bytes formatted", progress.any {
            val pt = it.partialText
            pt != null && ("KB" in pt || "2.0" in pt)
        })
        assertEquals(1f, progress.last().progress, 0.001f)
    }
}
