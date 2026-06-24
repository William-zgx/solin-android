package com.bytedance.zgx.pocketmind.rcperf

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.runtime.RuntimeBenchmarkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RcPerfTest {
    @Test
    fun resolveGpuFallbackStatusMapsRequestedAndLoadedBackends() {
        assertEquals(
            GpuFallbackStatus.NotNeeded,
            resolveGpuFallbackStatus(BackendChoice.GPU, BackendChoice.GPU),
        )
        assertEquals(
            GpuFallbackStatus.NotNeeded,
            resolveGpuFallbackStatus(BackendChoice.CPU, BackendChoice.CPU),
        )
        assertEquals(
            GpuFallbackStatus.CpuFallbackPassed,
            resolveGpuFallbackStatus(BackendChoice.GPU, BackendChoice.CPU),
        )
    }

    @Test
    fun buildRcPerfResultUsesRawBenchmarkThroughput() {
        val result = buildRcPerfResult(
            modelId = "chat-e2b",
            requestedBackend = BackendChoice.GPU,
            loadedBackend = BackendChoice.GPU,
            timing = RuntimeTimingSnapshot(
                loadMs = 1_200L,
                firstTokenMs = 340L,
                benchmark = RuntimeBenchmarkResult.Available(tokenCount = 128, tokensPerSecond = 17.5),
            ),
            stopGenerationRecoveryMs = 80L,
            visionInputMs = 410L,
            memorySearch5kMs = 12L,
        )

        val success = result as RcPerfResult.Success
        assertEquals(17.5, success.metrics.tokensPerSecond, 0.0001)
        assertEquals(128, success.metrics.tokenCount)
        assertEquals(GpuFallbackStatus.NotNeeded, success.metrics.gpuFallbackStatus)
    }

    @Test
    fun buildRcPerfResultFailsWhenBenchmarkUnavailableNeverFabricatesThroughput() {
        val result = buildRcPerfResult(
            modelId = "chat-e2b",
            requestedBackend = BackendChoice.GPU,
            loadedBackend = BackendChoice.GPU,
            timing = RuntimeTimingSnapshot(
                loadMs = 1_200L,
                firstTokenMs = 340L,
                benchmark = RuntimeBenchmarkResult.Unavailable("benchmark not reported"),
            ),
            stopGenerationRecoveryMs = 80L,
            visionInputMs = 410L,
            memorySearch5kMs = 12L,
        )

        val failure = result as RcPerfResult.Failure
        assertTrue(failure.reason.contains("tokensPerSecond unavailable"))
        assertTrue(failure.reason.contains("benchmark not reported"))
    }

    @Test
    fun buildRcPerfResultFailsWhenLoadOrFirstTokenMissing() {
        val missingLoad = buildRcPerfResult(
            modelId = "chat-e2b",
            requestedBackend = BackendChoice.CPU,
            loadedBackend = BackendChoice.CPU,
            timing = RuntimeTimingSnapshot(
                loadMs = null,
                firstTokenMs = 340L,
                benchmark = RuntimeBenchmarkResult.Available(tokenCount = 10, tokensPerSecond = 5.0),
            ),
            stopGenerationRecoveryMs = 80L,
            visionInputMs = 410L,
            memorySearch5kMs = 12L,
        )
        assertTrue((missingLoad as RcPerfResult.Failure).reason.contains("modelLoadMs unavailable"))

        val missingFirstToken = buildRcPerfResult(
            modelId = "chat-e2b",
            requestedBackend = BackendChoice.CPU,
            loadedBackend = BackendChoice.CPU,
            timing = RuntimeTimingSnapshot(
                loadMs = 1_200L,
                firstTokenMs = null,
                benchmark = RuntimeBenchmarkResult.Available(tokenCount = 10, tokensPerSecond = 5.0),
            ),
            stopGenerationRecoveryMs = 80L,
            visionInputMs = 410L,
            memorySearch5kMs = 12L,
        )
        assertTrue((missingFirstToken as RcPerfResult.Failure).reason.contains("firstTokenMs unavailable"))
    }

    @Test
    fun formatterRoundTripsSuccessResult() {
        val original = RcPerfResult.Success(
            RcPerfMetrics(
                modelId = "chat-e4b",
                backend = BackendChoice.CPU,
                modelLoadMs = 1_500L,
                firstTokenMs = 420L,
                tokenCount = 96,
                tokensPerSecond = 21.25,
                stopGenerationRecoveryMs = 70L,
                gpuFallbackStatus = GpuFallbackStatus.CpuFallbackPassed,
                visionInputMs = 510L,
                memorySearch5kMs = 18L,
            ),
        )

        val parsed = RcPerfResultFormatter.parse(RcPerfResultFormatter.format(original))

        assertEquals(original, parsed)
    }

    @Test
    fun formatterRoundTripsFailureResult() {
        val original = RcPerfResult.Failure("tokensPerSecond unavailable: benchmark not reported")

        val parsed = RcPerfResultFormatter.parse(RcPerfResultFormatter.format(original))

        assertEquals(original, parsed)
    }

    @Test
    fun formattedSuccessUsesWireValuesForGpuFallbackStatus() {
        val text = RcPerfResultFormatter.format(
            RcPerfResult.Success(
                RcPerfMetrics(
                    modelId = "chat-e2b",
                    backend = BackendChoice.GPU,
                    modelLoadMs = 1L,
                    firstTokenMs = 1L,
                    tokenCount = 1,
                    tokensPerSecond = 1.0,
                    stopGenerationRecoveryMs = 1L,
                    gpuFallbackStatus = GpuFallbackStatus.CpuFallbackPassed,
                    visionInputMs = 1L,
                    memorySearch5kMs = 1L,
                ),
            ),
        )

        assertTrue(text.contains("gpuFallbackStatus=cpu-fallback-passed"))
        assertTrue(text.contains("rcPerfSchema=${RcPerfResultFormatter.SCHEMA}"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rcPerfMetricsRejectsNonPositiveTokensPerSecond() {
        RcPerfMetrics(
            modelId = "chat-e2b",
            backend = BackendChoice.GPU,
            modelLoadMs = 1L,
            firstTokenMs = 1L,
            tokenCount = 1,
            tokensPerSecond = 0.0,
            stopGenerationRecoveryMs = 1L,
            gpuFallbackStatus = GpuFallbackStatus.NotNeeded,
            visionInputMs = 1L,
            memorySearch5kMs = 1L,
        )
    }

    @Test
    fun syntheticMemoryMeasuresFiveThousandRecordSearch() {
        var nanos = 0L
        val measurement = RcPerfSyntheticMemory.measure(
            recordCount = RcPerfSyntheticMemory.DEFAULT_RECORD_COUNT,
            nanoClock = {
                val current = nanos
                nanos += 7_000_000L
                current
            },
        )

        assertEquals(RcPerfSyntheticMemory.DEFAULT_RECORD_COUNT, measurement.recordCount)
        assertEquals(7L, measurement.elapsedMs)
        assertTrue(measurement.hitCount > 0)
    }
}
