package com.bytedance.zgx.pocketmind.resource

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemResourceMonitorTest {
    @Test
    fun procStatParserHandlesProcessNameWithSpacesAndParentheses() {
        val line = procStatLine(comm = "Pocket Mind (debug)", utime = 42L, stime = 8L)

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
