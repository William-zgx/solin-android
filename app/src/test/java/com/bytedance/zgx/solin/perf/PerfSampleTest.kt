package com.bytedance.zgx.solin.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfSampleTest {
    @Test
    fun requiredKeysAreModeledAndValidPhysicalSamplesPass() {
        assertEquals(
            listOf(
                "modelLoadMs",
                "firstTokenMs",
                "tokensPerSecond",
                "zvecStorageOpenMs",
                "zvecMemorySearch50kMs",
                "zvecMemoryIndex50kMs",
                "realAppSearchMs",
                "ocrMs",
            ),
            PerfSampleKey.entries.map { it.id },
        )

        val results = listOf(
            PerfSample(key = PerfSampleKey.ModelLoadMs, source = PerfSampleSource.PHYSICAL_ARM64, durationMs = 900),
            PerfSample(key = PerfSampleKey.FirstTokenMs, source = PerfSampleSource.PHYSICAL_ARM64, durationMs = 450),
            PerfSample(key = PerfSampleKey.TokensPerSecond, source = PerfSampleSource.PHYSICAL_ARM64, throughput = 18.5),
            PerfSample(key = PerfSampleKey.ZvecStorageOpenMs, source = PerfSampleSource.PHYSICAL_ARM64, durationMs = 12),
            PerfSample(key = PerfSampleKey.ZvecMemorySearch50kMs, source = PerfSampleSource.PHYSICAL_ARM64, durationMs = 32),
            PerfSample(key = PerfSampleKey.ZvecMemoryIndex50kMs, source = PerfSampleSource.PHYSICAL_ARM64, durationMs = 120),
            PerfSample(key = PerfSampleKey.RealAppSearchMs, source = PerfSampleSource.PHYSICAL_ARM64, durationMs = 80),
            PerfSample(key = PerfSampleKey.OcrMs, source = PerfSampleSource.PHYSICAL_ARM64, durationMs = 210),
        ).map { sample -> sample.validate() }

        assertTrue(results.all { errors -> errors.isEmpty() })
    }

    @Test
    fun negativeTimingIsInvalid() {
        val result = PerfSample(
            key = PerfSampleKey.OcrMs,
            source = PerfSampleSource.PHYSICAL_ARM64,
            durationMs = -1,
        ).validate()

        assertEquals(listOf("durationMs must be non-negative"), result)
    }

    @Test
    fun unknownSourceIsInvalid() {
        val result = PerfSample(
            key = PerfSampleKey.OcrMs,
            source = "cloudX64",
            durationMs = 1,
        ).validate()

        assertEquals(listOf("source must be one of localJvm, emulator, physicalArm64"), result)
    }

    @Test
    fun throughputMustBePositiveWhenPresent() {
        val zero = PerfSample(
            key = PerfSampleKey.TokensPerSecond,
            source = PerfSampleSource.PHYSICAL_ARM64,
            throughput = 0.0,
        ).validate()
        val nan = PerfSample(
            key = PerfSampleKey.TokensPerSecond,
            source = PerfSampleSource.PHYSICAL_ARM64,
            throughput = Double.NaN,
        ).validate()

        assertEquals(listOf("throughput must be positive"), zero)
        assertEquals(listOf("throughput must be positive"), nan)
    }

    @Test
    fun metricValueMustMatchKeyShape() {
        val durationWithThroughput = PerfSample(
            key = PerfSampleKey.OcrMs,
            source = PerfSampleSource.PHYSICAL_ARM64,
            throughput = 1.0,
        ).validate()
        val throughputWithDuration = PerfSample(
            key = PerfSampleKey.TokensPerSecond,
            source = PerfSampleSource.PHYSICAL_ARM64,
            durationMs = 1,
        ).validate()
        val durationWithBoth = PerfSample(
            key = PerfSampleKey.ModelLoadMs,
            source = PerfSampleSource.PHYSICAL_ARM64,
            durationMs = 1,
            throughput = 1.0,
        ).validate()

        assertEquals(
            listOf("durationMs is required for ocrMs", "throughput must be absent for ocrMs"),
            durationWithThroughput,
        )
        assertEquals(
            listOf("throughput is required for tokensPerSecond", "durationMs must be absent for tokensPerSecond"),
            throughputWithDuration,
        )
        assertEquals(
            listOf("throughput must be absent for modelLoadMs"),
            durationWithBoth,
        )
    }

    @Test
    fun emulatorAndLocalJvmAreRejectedForPhysicalOnlyZvecRealAppAndModelPerfKeys() {
        val physicalOnlyKeys = listOf(
            PerfSampleKey.ModelLoadMs,
            PerfSampleKey.FirstTokenMs,
            PerfSampleKey.TokensPerSecond,
            PerfSampleKey.ZvecStorageOpenMs,
            PerfSampleKey.ZvecMemorySearch50kMs,
            PerfSampleKey.ZvecMemoryIndex50kMs,
            PerfSampleKey.RealAppSearchMs,
        )

        val results = physicalOnlyKeys.flatMap { key ->
            listOf(
                key to sampleFor(key, PerfSampleSource.EMULATOR).validate(),
                key to sampleFor(key, PerfSampleSource.LOCAL_JVM).validate(),
            )
        }

        assertTrue(
            results.all { (key, errors) ->
                errors == listOf("source must be physicalArm64 for ${key.id}")
            },
        )
    }

    private fun sampleFor(key: PerfSampleKey, source: String): PerfSample =
        when (key.valueKind) {
            PerfSampleValueKind.DurationMs -> PerfSample(key = key, source = source, durationMs = 1)
            PerfSampleValueKind.Throughput -> PerfSample(key = key, source = source, throughput = 1.0)
        }
}
