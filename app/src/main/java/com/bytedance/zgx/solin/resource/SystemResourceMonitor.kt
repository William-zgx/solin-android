package com.bytedance.zgx.solin.resource

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlin.math.roundToInt

internal const val SYSTEM_RESOURCE_SAMPLE_INTERVAL_MS = 1_500L

private const val BYTES_PER_KIB = 1024L
private const val BYTES_PER_MIB = 1024L * 1024L
private const val DEFAULT_CLOCK_TICKS_PER_SECOND = 100L

enum class ResourcePressure(val label: String) {
    Normal("流畅"),
    Warm("可能卡顿"),
    Hot("高负载"),
}

enum class ThermalPressure(val label: String) {
    Unknown("未知"),
    Normal("正常"),
    Warm("偏热"),
    Hot("过热"),
    Severe("严重"),
    Critical("临界"),
    Emergency("紧急"),
    Shutdown("关机"),
}

data class SystemResourceSnapshot(
    val appPssBytes: Long,
    val javaHeapBytes: Long,
    val nativeHeapBytes: Long,
    val availableRamBytes: Long,
    val lowMemory: Boolean,
    val appCpuPercent: Int?,
    val thermalPressure: ThermalPressure,
) {
    val pressurePercent: Int
        get() {
            val memoryPressure = when {
                lowMemory -> 90
                availableRamBytes in 1 until 256L * BYTES_PER_MIB -> 90
                availableRamBytes in 1 until 512L * BYTES_PER_MIB -> 70
                appPssBytes >= 1_200L * BYTES_PER_MIB -> 80
                appPssBytes >= 768L * BYTES_PER_MIB -> 60
                else -> 20
            }
            val thermalPercent = when (thermalPressure) {
                ThermalPressure.Hot,
                ThermalPressure.Severe,
                ThermalPressure.Critical,
                ThermalPressure.Emergency,
                ThermalPressure.Shutdown,
                -> 90
                ThermalPressure.Warm -> 70
                ThermalPressure.Normal,
                ThermalPressure.Unknown,
                -> 0
            }
            return maxOf(memoryPressure, appCpuPercent ?: 0, thermalPercent).coerceIn(0, 100)
        }

    val localHardBlocked: Boolean
        get() = thermalPressure == ThermalPressure.Emergency ||
            thermalPressure == ThermalPressure.Shutdown

    val pressure: ResourcePressure
        get() = when {
            pressurePercent >= 75 -> ResourcePressure.Hot
            pressurePercent >= 50 -> ResourcePressure.Warm
            else -> ResourcePressure.Normal
        }
}

class SystemResourceMonitor(
    context: Context,
    private val procStatReader: () -> String? = { File("/proc/self/stat").readText() },
    private val elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val ticksPerSecond = readClockTicksPerSecond()
    private val cpuSampler = AppCpuSampler(
        procStatReader = procStatReader,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        processors = processors,
        ticksPerSecond = ticksPerSecond,
    )

    fun sample(): SystemResourceSnapshot? = runCatching {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val systemMemory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(systemMemory)
        val runtime = Runtime.getRuntime()

        SystemResourceSnapshot(
            appPssBytes = memoryInfo.totalPss.toLong() * BYTES_PER_KIB,
            javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            availableRamBytes = systemMemory.availMem,
            lowMemory = systemMemory.lowMemory,
            appCpuPercent = cpuSampler.sample(),
            thermalPressure = thermalPressure(),
        )
    }.getOrNull()

    private fun thermalPressure(): ThermalPressure {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus
        } else {
            null
        }
        return mapThermalPressure(Build.VERSION.SDK_INT, status)
    }
}

internal class AppCpuSampler(
    private val procStatReader: () -> String?,
    private val elapsedRealtimeMillis: () -> Long,
    private val processors: Int,
    private val ticksPerSecond: Long,
) {
    private var previousCpuSample: CpuTickSample? = null

    @Synchronized
    fun sample(): Int? {
        val statLine = runCatching(procStatReader).getOrNull() ?: return null
        val ticks = parseProcStatCpuTicks(statLine) ?: return null
        val elapsedRealtime = runCatching(elapsedRealtimeMillis).getOrNull() ?: return null
        val current = CpuTickSample(
            totalTicks = ticks,
            elapsedRealtimeMillis = elapsedRealtime,
        )
        val percent = calculateAppCpuPercent(
            previous = previousCpuSample,
            current = current,
            processors = processors,
            ticksPerSecond = ticksPerSecond,
        )
        previousCpuSample = current
        return percent
    }
}

internal fun mapThermalPressure(
    apiLevel: Int,
    nativeStatus: Int?,
): ThermalPressure {
    if (apiLevel < Build.VERSION_CODES.Q || nativeStatus == null) return ThermalPressure.Unknown
    return when (nativeStatus) {
        PowerManager.THERMAL_STATUS_NONE,
        PowerManager.THERMAL_STATUS_LIGHT,
        -> ThermalPressure.Normal
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalPressure.Warm
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalPressure.Severe
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalPressure.Critical
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalPressure.Emergency
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalPressure.Shutdown
        else -> ThermalPressure.Unknown
    }
}

internal data class CpuTickSample(
    val totalTicks: Long,
    val elapsedRealtimeMillis: Long,
)

internal fun parseProcStatCpuTicks(statLine: String): Long? {
    val commEnd = statLine.lastIndexOf(") ")
    if (commEnd < 0) return null
    val fields = statLine
        .substring(commEnd + 2)
        .trim()
        .split(Regex("\\s+"))
    if (fields.size <= 12) return null
    val utime = fields[11].toLongOrNull() ?: return null
    val stime = fields[12].toLongOrNull() ?: return null
    return utime + stime
}

internal fun calculateAppCpuPercent(
    previous: CpuTickSample?,
    current: CpuTickSample,
    processors: Int,
    ticksPerSecond: Long,
): Int? {
    previous ?: return null
    val tickDelta = current.totalTicks - previous.totalTicks
    val millisDelta = current.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
    if (tickDelta < 0L || millisDelta <= 0L || ticksPerSecond <= 0L) return null
    val cpuMillis = tickDelta * 1_000.0 / ticksPerSecond
    val availableMillis = millisDelta * processors.coerceAtLeast(1)
    return ((cpuMillis / availableMillis) * 100.0).roundToInt().coerceIn(0, 100)
}

private fun readClockTicksPerSecond(): Long =
    runCatching { Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0L } }
        .getOrNull()
        ?: DEFAULT_CLOCK_TICKS_PER_SECOND
