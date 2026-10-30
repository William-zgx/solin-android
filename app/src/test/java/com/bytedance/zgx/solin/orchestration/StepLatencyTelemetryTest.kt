package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.memory.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class StepLatencyTelemetryTest {
    @Test
    fun concurrentRunsKeepIndependentModelGenerationStepStarts() {
        val telemetry = RecordingTelemetrySink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = ScriptedActionRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            telemetrySink = telemetry,
        )
        val first = runtime.runOnce("第一个问题", setOf(ModelCapability.Chat), memoryEnabled = false)
        val second = runtime.runOnce("第二个问题", setOf(ModelCapability.Chat), memoryEnabled = false)

        runtime.observeModelResult(first.run.id, "第一个回答")
        runtime.observeModelResult(second.run.id, "第二个回答")

        val latencies = telemetry.samples.filterIsInstance<MetricSample.StepLatency>()
            .filter { sample -> sample.stepType == "model_generation" }
        assertEquals(listOf(first.run.id, second.run.id), latencies.map { sample -> sample.runId })
    }
}
