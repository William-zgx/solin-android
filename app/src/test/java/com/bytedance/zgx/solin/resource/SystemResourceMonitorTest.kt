package com.bytedance.zgx.solin.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemResourceMonitorTest {
    @Test
    fun procStatParserHandlesProcessNameWithSpacesAndParentheses() {
        val line = procStatLine(comm = "Solin (debug)", utime = 42L, stime = 8L)

        assertEquals(50L, parseProcStatCpuTicks(line))
    }

    @Test
    fun cpuPercentUsesProcessTicksWallTimeAndCoreCount() {
        val previous = CpuTickSample(totalTicks = 100L, elapsedRealtimeMillis = 1_000L)
        val current = CpuTickSample(totalTicks = 200L, elapsedRealtimeMillis = 2_000L)

        val percent = calculateAppCpuPercent(
            previous = previous,
            current = current,
            processors = 2,
            ticksPerSecond = 100L,
        )

        assertEquals(50, percent)
    }

    @Test
    fun pressureEscalatesForLowMemoryAndThermalHeat() {
        val warm = SystemResourceSnapshot(
            appPssBytes = 350L * 1024L * 1024L,
            javaHeapBytes = 90L * 1024L * 1024L,
            nativeHeapBytes = 120L * 1024L * 1024L,
            availableRamBytes = 900L * 1024L * 1024L,
            lowMemory = false,
            appCpuPercent = 58,
            thermalPressure = ThermalPressure.Normal,
        )
        val hot = warm.copy(
            lowMemory = true,
            thermalPressure = ThermalPressure.Hot,
        )

        assertEquals(ResourcePressure.Warm, warm.pressure)
        assertEquals(ResourcePressure.Hot, hot.pressure)
        assertEquals(90, hot.pressurePercent)
    }

    @Test
    fun nativeThermalMappingPreservesEveryAndroidBand() {
        assertEquals(ThermalPressure.Unknown, mapThermalPressure(apiLevel = 28, nativeStatus = 0))
        assertEquals(ThermalPressure.Unknown, mapThermalPressure(apiLevel = 29, nativeStatus = null))
        assertEquals(ThermalPressure.Normal, mapThermalPressure(apiLevel = 29, nativeStatus = 0))
        assertEquals(ThermalPressure.Normal, mapThermalPressure(apiLevel = 29, nativeStatus = 1))
        assertEquals(ThermalPressure.Warm, mapThermalPressure(apiLevel = 29, nativeStatus = 2))
        assertEquals(ThermalPressure.Severe, mapThermalPressure(apiLevel = 29, nativeStatus = 3))
        assertEquals(ThermalPressure.Critical, mapThermalPressure(apiLevel = 29, nativeStatus = 4))
        assertEquals(ThermalPressure.Emergency, mapThermalPressure(apiLevel = 29, nativeStatus = 5))
        assertEquals(ThermalPressure.Shutdown, mapThermalPressure(apiLevel = 29, nativeStatus = 6))
        assertEquals(ThermalPressure.Unknown, mapThermalPressure(apiLevel = 29, nativeStatus = 7))
    }

    @Test
    fun severeAndAboveCountAsHotButOnlyEmergencyAndShutdownHardBlock() {
        listOf(
            ThermalPressure.Hot,
            ThermalPressure.Severe,
            ThermalPressure.Critical,
            ThermalPressure.Emergency,
            ThermalPressure.Shutdown,
        ).forEach { thermal ->
            val snapshot = snapshot(thermal)
            assertEquals(ResourcePressure.Hot, snapshot.pressure)
            assertEquals(90, snapshot.pressurePercent)
        }

        assertFalse(snapshot(ThermalPressure.Hot).localHardBlocked)
        assertFalse(snapshot(ThermalPressure.Severe).localHardBlocked)
        assertFalse(snapshot(ThermalPressure.Critical).localHardBlocked)
        assertTrue(snapshot(ThermalPressure.Emergency).localHardBlocked)
        assertTrue(snapshot(ThermalPressure.Shutdown).localHardBlocked)
    }

    @Test
    fun procStatReaderFailureOnlyProducesNullCpuSample() {
        val sampler = AppCpuSampler(
            procStatReader = { error("proc unavailable") },
            elapsedRealtimeMillis = { 1_000L },
            processors = 1,
            ticksPerSecond = 100L,
        )

        assertNull(sampler.sample())
    }

    private fun snapshot(thermal: ThermalPressure): SystemResourceSnapshot = SystemResourceSnapshot(
        appPssBytes = 128L * 1024L * 1024L,
        javaHeapBytes = 64L * 1024L * 1024L,
        nativeHeapBytes = 32L * 1024L * 1024L,
        availableRamBytes = 1_024L * 1024L * 1024L,
        lowMemory = false,
        appCpuPercent = null,
        thermalPressure = thermal,
    )

    private fun procStatLine(
        comm: String,
        utime: Long,
        stime: Long,
    ): String = "123 ($comm) " + listOf(
        "S",
        "1",
        "1",
        "1",
        "0",
        "-1",
        "4194560",
        "10",
        "0",
        "0",
        "0",
        utime.toString(),
        stime.toString(),
        "0",
        "0",
        "20",
        "0",
    ).joinToString(" ")
}
