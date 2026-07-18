package com.bytedance.zgx.solin.resource

import java.util.ArrayDeque

enum class StableResourceBand {
    Unknown,
    Normal,
    Warm,
    Hot,
}

data class StableResourceState(
    val band: StableResourceBand,
    val stableLowMemory: Boolean,
    val latestLowMemory: Boolean,
    val localHardBlocked: Boolean,
    val thermalPressure: ThermalPressure,
)

class StableResourceSnapshotAggregator(
    private val elapsedRealtimeMillis: () -> Long,
) {
    private val samples = ArrayDeque<TimedSnapshot>()
    private var lastObservedTimeMillis: Long? = null
    private var lastHotMajoritySampleTimeMillis: Long? = null
    private var lastLocalHardBlockTimeMillis: Long? = null

    @Synchronized
    fun record(snapshot: SystemResourceSnapshot): StableResourceState {
        val now = elapsedRealtimeMillis()
        prepare(now)
        samples.addLast(TimedSnapshot(now, snapshot))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()

        val rawBand = rawBand()
        if (rawBand == StableResourceBand.Hot && snapshot.pressure == ResourcePressure.Hot) {
            lastHotMajoritySampleTimeMillis = now
        }
        if (snapshot.localHardBlocked) lastLocalHardBlockTimeMillis = now
        return state(now, rawBand)
    }

    @Synchronized
    fun current(): StableResourceState {
        val now = elapsedRealtimeMillis()
        prepare(now)
        return state(now, rawBand())
    }

    private fun prepare(now: Long) {
        if (lastObservedTimeMillis?.let { now < it } == true) {
            samples.clear()
            lastHotMajoritySampleTimeMillis = null
            lastLocalHardBlockTimeMillis = null
        }
        lastObservedTimeMillis = now
        while (samples.isNotEmpty() && now - samples.first.timeMillis !in 0 until WINDOW_MILLIS) {
            samples.removeFirst()
        }
    }

    private fun rawBand(): StableResourceBand {
        if (samples.size < MIN_SAMPLES) return StableResourceBand.Unknown
        val hotCount = samples.count { it.snapshot.pressure == ResourcePressure.Hot }
        if (hotCount >= REQUIRED_VOTES) return StableResourceBand.Hot
        val warmOrHotCount = samples.count { it.snapshot.pressure != ResourcePressure.Normal }
        return if (warmOrHotCount >= REQUIRED_VOTES) {
            StableResourceBand.Warm
        } else {
            StableResourceBand.Normal
        }
    }

    private fun state(now: Long, rawBand: StableResourceBand): StableResourceState {
        val latest = samples.lastOrNull()?.snapshot
        val cooldownActive = lastHotMajoritySampleTimeMillis?.let {
            now - it in 0 until HOT_COOLDOWN_MILLIS
        } == true
        val localHardBlockCooldownActive = lastLocalHardBlockTimeMillis?.let {
            now - it in 0 until HOT_COOLDOWN_MILLIS
        } == true
        return StableResourceState(
            band = if (rawBand != StableResourceBand.Hot && cooldownActive) {
                StableResourceBand.Hot
            } else {
                rawBand
            },
            stableLowMemory = samples.size >= MIN_SAMPLES &&
                samples.count { it.snapshot.lowMemory } >= REQUIRED_VOTES,
            latestLowMemory = latest?.lowMemory == true,
            localHardBlocked = latest?.localHardBlocked == true || localHardBlockCooldownActive,
            thermalPressure = latest?.thermalPressure ?: ThermalPressure.Unknown,
        )
    }

    private data class TimedSnapshot(
        val timeMillis: Long,
        val snapshot: SystemResourceSnapshot,
    )

    private companion object {
        const val WINDOW_MILLIS = 10_000L
        const val HOT_COOLDOWN_MILLIS = 15_000L
        const val MAX_SAMPLES = 3
        const val MIN_SAMPLES = 2
        const val REQUIRED_VOTES = 2
    }
}
