package com.bytedance.zgx.solin.orchestration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSolinEventBusTest {

    private fun runStarted(runId: String = "run-1", input: String = "hi") =
        SolinEvent.Agent.RunStarted(runId = runId, modelLabel = "test-model", inputText = input)

    private fun turnStarted(runId: String = "run-1", turn: Int = 0) =
        SolinEvent.Agent.TurnStarted(runId = runId, turnIndex = turn)

    private fun toolPlanned(runId: String = "run-1", toolCallId: String = "tc-1", toolName: String = "calendar.read") =
        SolinEvent.Agent.ToolPlanned(runId = runId, toolCallId = toolCallId, toolName = toolName)

    private fun textDelta(runId: String = "run-1", text: String = "x") =
        SolinEvent.Agent.TextDelta(runId = runId, text = text)

    @Test
    fun publishDeliversEventsOfSubscribedType() = runTest {
        val bus = DefaultSolinEventBus()
        val received = mutableListOf<SolinEvent.Agent>()

        val job = backgroundScope.launch {
            bus.subscribe(SolinEvent.Agent::class).take(2).toList(received)
        }
        advanceUntilIdle()

        bus.publish(runStarted())
        bus.publish(turnStarted())
        advanceUntilIdle()
        job.join()

        assertTrue("expected RunStarted", received.any { it is SolinEvent.Agent.RunStarted })
        assertTrue("expected TurnStarted", received.any { it is SolinEvent.Agent.TurnStarted })
    }

    @Test
    fun subscribeFiltersOutUnrelatedTypes() = runTest {
        val bus = DefaultSolinEventBus()
        val received = mutableListOf<SolinEvent.Agent.ToolPlanned>()

        val job = backgroundScope.launch {
            bus.subscribe(SolinEvent.Agent.ToolPlanned::class).take(1).toList(received)
        }
        advanceUntilIdle()

        bus.publish(runStarted())
        bus.publish(toolPlanned())
        bus.publish(turnStarted())
        advanceUntilIdle()
        job.join()

        assertEquals(1, received.size)
        assertEquals("calendar.read", received.single().toolName)
    }

    @Test
    fun recentReturnsLastNDurableEvents() {
        val bus = DefaultSolinEventBus()
        repeat(20) { bus.publish(runStarted(runId = "run-$it", input = "input-$it")) }

        val lastFive: List<SolinEvent.Agent.RunStarted> = bus.recent(SolinEvent.Agent.RunStarted::class, limit = 5)
        assertEquals(5, lastFive.size)
        assertEquals("run-19", lastFive.last().runId)
        assertEquals("run-15", lastFive.first().runId)

        val all: List<SolinEvent.Agent.RunStarted> = bus.recent(SolinEvent.Agent.RunStarted::class)
        assertEquals(20, all.size)
    }

    @Test
    fun recentExcludesNoReplayEvents() {
        val bus = DefaultSolinEventBus()
        bus.publish(textDelta(text = "token-1"))
        bus.publish(runStarted())

        val textDeltas: List<SolinEvent.Agent.TextDelta> = bus.recent(SolinEvent.Agent.TextDelta::class)
        assertTrue("TextDelta has replay=NO_REPLAY and must not appear in recent()", textDeltas.isEmpty())

        val runStarts: List<SolinEvent.Agent.RunStarted> = bus.recent(SolinEvent.Agent.RunStarted::class)
        assertEquals(1, runStarts.size)
    }

    @Test
    fun recentWithZeroLimitReturnsEmpty() {
        val bus = DefaultSolinEventBus()
        repeat(10) { bus.publish(runStarted(runId = "r-$it")) }
        assertTrue(bus.recent(SolinEvent.Agent.RunStarted::class, limit = 0).isEmpty())
    }

    @Test
    fun publishDoesNotThrowWhenNoSubscribers() {
        val bus = DefaultSolinEventBus()
        repeat(50) { bus.publish(runStarted(runId = "run-$it")) }
        val snapshot = bus.recent(SolinEvent.Agent.RunStarted::class)
        assertEquals(50, snapshot.size)
    }

    @Test
    fun replayCacheDeliversPriorEventsToNewSubscribers() = runTest {
        val bus = DefaultSolinEventBus()
        // Publish before subscribing: replay cache should deliver these to a new subscriber.
        bus.publish(runStarted(runId = "run-past"))
        advanceUntilIdle()

        val received = mutableListOf<SolinEvent.Agent.RunStarted>()
        val job = backgroundScope.launch {
            bus.subscribe(SolinEvent.Agent.RunStarted::class).take(1).toList(received)
        }
        advanceUntilIdle()
        job.join()

        assertEquals(1, received.size)
        assertEquals("run-past", received.single().runId)
    }

    @Test
    fun highVolumePublishDoesNotThrowAndIsBounded() {
        val bus = DefaultSolinEventBus()
        // Publishing 1000 high-frequency events on a bus with no subscribers must not throw.
        // Buffer overflow is DROP_OLDEST; tryEmit always succeeds because MutableSharedFlow
        // configured with extraBufferCapacity + onBufferOverflow=DROP_OLDEST never suspends tryEmit.
        repeat(1000) { i ->
            bus.publish(textDelta(text = "t-$i"))
        }
        // Also publish many durable events; the replay cache is capped at REPLAY_CACHE_SIZE=200.
        repeat(500) { i ->
            bus.publish(runStarted(runId = "run-$i"))
        }
        val durable: List<SolinEvent.Agent.RunStarted> = bus.recent(SolinEvent.Agent.RunStarted::class)
        // Replay cache is capped at 200.
        assertTrue("replay cache must be bounded to REPLAY_CACHE_SIZE=200, got ${durable.size}", durable.size <= 200)
    }
}
