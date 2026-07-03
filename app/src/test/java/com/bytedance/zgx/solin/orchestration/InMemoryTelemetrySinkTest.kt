package com.bytedance.zgx.solin.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryTelemetrySinkTest {

    @Test
    fun recordAndSnapshotContainSamples() {
        val sink = InMemoryTelemetrySink()

        sink.record(
            MetricSample.ToolCompleted(
                toolName = "calendar.read",
                latencyMs = 50L,
                succeeded = true,
            ),
        )
        sink.record(
            MetricSample.StepLatency(
                stepType = "planning",
                latencyMs = 20L,
            ),
        )
        sink.record(
            MetricSample.CounterInc(
                name = TelemetryCounter.ToolCalls,
                delta = 1L,
            ),
        )

        val snap = sink.snapshot()

        assertEquals(3, snap.totalSamples)
        assertTrue(snap.countsByVariant.containsKey("ToolCompleted"))
        assertTrue(snap.countsByVariant.containsKey("StepLatency"))
        assertTrue(snap.countsByVariant.containsKey("CounterInc"))
        assertEquals(1, snap.countsByVariant["ToolCompleted"])
        assertEquals(1L, snap.counterTotals[TelemetryCounter.ToolCalls])
        // Recent latency buffers contain the samples we recorded.
        assertEquals(listOf(50L), snap.recentLatencies["ToolCompleted:calendar.read"])
        assertEquals(listOf(20L), snap.recentLatencies["StepLatency:planning"])
    }

    @Test
    fun maxPerKeyBoundsBufferSize() {
        val maxPerKey = 50
        val sink = InMemoryTelemetrySink(maxPerKey = maxPerKey)

        repeat(300) { i ->
            sink.record(
                MetricSample.ToolCompleted(
                    toolName = "only_tool",
                    latencyMs = i.toLong(),
                    succeeded = true,
                ),
            )
        }

        val snap = sink.snapshot()
        assertEquals(300, snap.totalSamples)
        assertEquals(300, snap.countsByVariant["ToolCompleted"])

        val latencies = snap.recentLatencies["ToolCompleted:only_tool"]
        assertNotNull(latencies)
        assertTrue("buffer must be bounded by maxPerKey, got ${latencies!!.size}", latencies.size <= maxPerKey)
        // Ring buffer should contain the most recent entries.
        assertEquals(299L, latencies.last())
        // The buffer should be exactly maxPerKey since we wrote more than maxPerKey samples.
        assertEquals(maxPerKey, latencies.size)
    }

    @Test
    fun countersAccumulateAcrossMultipleRecords() {
        val sink = InMemoryTelemetrySink()
        repeat(5) {
            sink.record(MetricSample.CounterInc(name = TelemetryCounter.CacheHits, delta = 1L))
        }
        sink.record(MetricSample.CounterInc(name = TelemetryCounter.CacheHits, delta = 3L))

        val snap = sink.snapshot()
        assertEquals(8L, snap.counterTotals[TelemetryCounter.CacheHits])
    }
}
