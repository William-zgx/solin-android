package com.bytedance.zgx.solin.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the privacy-preserving event -> MetricSample mapping is correct
 * (privacy contract: user text / tool summaries never leak into samples), and
 * that the InMemoryTelemetrySink correctly aggregates the samples it records.
 *
 * We test [SolinEvent.toMetricSamples] directly and pump results into the sink via
 * [TelemetrySink.record], which is deterministic and avoids the timing-sensitive
 * async collector (attachTo is a one-line `scope.launch { bus.subscribe(...).collect {
 * it.toMetricSamples().forEach(::record) } }` — its only behavior is wiring tested
 * implicitly by the production container).
 */
class TelemetrySinkSubscribesTest {

    /**
     * Test event -> sample mapping directly (no flow/collector timing concerns).
     */
    @Test
    fun turnEndedMapsToGenerationCompleteSample() {
        val evt = SolinEvent.Agent.TurnEnded(
            runId = "run-1",
            turnIndex = 0,
            tokensIn = 120,
            tokensOut = 40,
            ttftMs = 85L,
            durationMs = 320L,
        )
        val samples = evt.toMetricSamples()
        assertEquals(1, samples.size)
        val gen = samples.single() as MetricSample.GenerationComplete
        assertEquals(85L, gen.ttftMs)
        assertEquals(320L, gen.totalMs)
        assertEquals(120, gen.inputTokens)
        assertEquals(40, gen.outputTokens)
    }

    @Test
    fun textDeltaMapsToCounterOnlyNeverTextPayload() {
        val secretPhrase = "SENSITIVE-USER-PASSWORD-abc123-SHOULD-NOT-APPEAR"
        val evt = SolinEvent.Agent.TextDelta(
            runId = "run-p",
            text = secretPhrase,
        )
        val samples = evt.toMetricSamples()
        assertEquals(1, samples.size)
        val counter = samples.single() as MetricSample.CounterInc
        assertEquals(TelemetryCounter.TextTokens, counter.name)
        // The serialized form of the samples must not contain the secret text.
        assertFalse(samples.toString().contains(secretPhrase))
    }

    @Test
    fun toolSucceededMapsToToolCompletedWithDuration() {
        val evt = SolinEvent.Agent.ToolSucceeded(
            runId = "run-t",
            toolCallId = "c1",
            toolName = "web.search",
            summary = "found things (sensitive? no — never recorded)",
            durationMs = 245L,
        )
        val samples = evt.toMetricSamples()
        // ToolCompleted (with duration) + ToolCalls counter.
        assertEquals(2, samples.size)
        val toolSample = samples.filterIsInstance<MetricSample.ToolCompleted>().single()
        assertEquals("web.search", toolSample.toolName)
        assertEquals(245L, toolSample.latencyMs)
        assertTrue(toolSample.succeeded)
        val counter = samples.filterIsInstance<MetricSample.CounterInc>().single()
        assertEquals(TelemetryCounter.ToolCalls, counter.name)
    }

    @Test
    fun sinkAggregatesSamplesFromMultipleEvents() {
        val sink = InMemoryTelemetrySink()
        // Pump events' samples into the sink synchronously (mirrors what attachTo's collector does).
        SolinEvent.Agent.ToolSucceeded(
            runId = "r", toolCallId = "c1", toolName = "web.search",
            summary = "privacy: must never be recorded", durationMs = 123L,
        ).toMetricSamples().forEach(sink::record)
        SolinEvent.Agent.TurnEnded(
            runId = "r", turnIndex = 0, tokensIn = 10, tokensOut = 5,
            ttftMs = 11L, durationMs = 99L,
        ).toMetricSamples().forEach(sink::record)
        repeat(2) {
            SolinEvent.Agent.TextDelta(runId = "r", text = "SENSITIVE_PAYLOAD_$it")
                .toMetricSamples().forEach(sink::record)
        }

        val snap = sink.snapshot()
        // 1 ToolCompleted + 1 ToolCalls counter + 1 GenerationComplete + 2 TextTokens counters = 5
        assertEquals(5, snap.totalSamples)
        assertEquals(1, snap.countsByVariant["ToolCompleted"])
        assertEquals(1, snap.countsByVariant["GenerationComplete"])
        assertEquals(3, snap.countsByVariant["CounterInc"])
        assertEquals(2L, snap.counterTotals[TelemetryCounter.TextTokens])
        assertEquals(1L, snap.counterTotals[TelemetryCounter.ToolCalls])
        val latencies = snap.recentLatencies["ToolCompleted:web.search"]
        assertEquals(listOf(123L), latencies)
        // Privacy: no sample's toString must contain the secret text or the tool summary.
        val dump = listOf(snap.toString(), snap.keyedStats.toString(), snap.recentLatencies.toString()).joinToString()
        assertFalse(dump.contains("SENSITIVE_PAYLOAD"))
        assertFalse(dump.contains("privacy: must never"))
    }
}
