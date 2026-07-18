package com.bytedance.zgx.solin.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableResourceSnapshotAggregatorTest {
    @Test
    fun zeroAndOneSamplesAreUnknownAndNullCpuIsStillValid() {
        val clock = FakeClock()
        val aggregator = StableResourceSnapshotAggregator(clock::now)

        assertEquals(StableResourceBand.Unknown, aggregator.current().band)
        aggregator.record(snapshot(ResourcePressure.Normal).copy(appCpuPercent = null))
        assertEquals(StableResourceBand.Unknown, aggregator.current().band)
        aggregator.record(snapshot(ResourcePressure.Normal))

        assertEquals(StableResourceBand.Normal, aggregator.current().band)
    }

    @Test
    fun twoOfThreeVotingCoversNormalWarmAndHotCombinations() {
        assertEquals(StableResourceBand.Normal, aggregate(ResourcePressure.Normal, ResourcePressure.Normal))
        assertEquals(StableResourceBand.Normal, aggregate(ResourcePressure.Normal, ResourcePressure.Warm))
        assertEquals(StableResourceBand.Warm, aggregate(ResourcePressure.Warm, ResourcePressure.Warm))
        assertEquals(StableResourceBand.Warm, aggregate(ResourcePressure.Warm, ResourcePressure.Hot))
        assertEquals(StableResourceBand.Hot, aggregate(ResourcePressure.Hot, ResourcePressure.Hot))
        assertEquals(
            StableResourceBand.Hot,
            aggregate(ResourcePressure.Hot, ResourcePressure.Normal, ResourcePressure.Hot),
        )
    }

    @Test
    fun fourthSampleEvictsTheOldest() {
        val clock = FakeClock()
        val aggregator = StableResourceSnapshotAggregator(clock::now)
        aggregator.record(snapshot(ResourcePressure.Warm))
        aggregator.record(snapshot(ResourcePressure.Warm))
        aggregator.record(snapshot(ResourcePressure.Normal))
        assertEquals(StableResourceBand.Warm, aggregator.current().band)

        aggregator.record(snapshot(ResourcePressure.Normal))

        assertEquals(StableResourceBand.Normal, aggregator.current().band)
    }

    @Test
    fun tenSecondWindowIsHalfOpen() {
        val clock = FakeClock()
        val aggregator = StableResourceSnapshotAggregator(clock::now)
        aggregator.record(snapshot(ResourcePressure.Warm))
        aggregator.record(snapshot(ResourcePressure.Warm))

        clock.advance(9_999L)
        assertEquals(StableResourceBand.Warm, aggregator.current().band)

        clock.advance(1L)
        assertEquals(StableResourceBand.Unknown, aggregator.current().band)
    }

    @Test
    fun hotCooldownUsesLatestHotMajoritySampleAndExpiresAtFifteenSeconds() {
        val clock = FakeClock()
        val aggregator = StableResourceSnapshotAggregator(clock::now)
        aggregator.record(snapshot(ResourcePressure.Hot))
        aggregator.record(snapshot(ResourcePressure.Normal))
        aggregator.record(snapshot(ResourcePressure.Hot))
        assertEquals(StableResourceBand.Hot, aggregator.current().band)

        clock.advance(9_999L)
        aggregator.record(snapshot(ResourcePressure.Normal))
        aggregator.record(snapshot(ResourcePressure.Normal))
        repeat(3) { assertEquals(StableResourceBand.Hot, aggregator.current().band) }

        clock.advance(5_000L)
        assertEquals(StableResourceBand.Hot, aggregator.current().band)

        clock.advance(1L)
        assertEquals(StableResourceBand.Normal, aggregator.current().band)
    }

    @Test
    fun laterHotSampleThatFormsMajorityMovesTheCooldownAnchor() {
        val clock = FakeClock()
        val aggregator = StableResourceSnapshotAggregator(clock::now)
        aggregator.record(snapshot(ResourcePressure.Hot))
        clock.advance(1_000L)
        aggregator.record(snapshot(ResourcePressure.Hot))
        assertEquals(StableResourceBand.Hot, aggregator.current().band)

        clock.advance(8_000L)
        aggregator.record(snapshot(ResourcePressure.Normal))
        aggregator.record(snapshot(ResourcePressure.Normal))

        clock.advance(6_999L)
        assertEquals(StableResourceBand.Hot, aggregator.current().band)

        clock.advance(1L)
        assertEquals(StableResourceBand.Normal, aggregator.current().band)
    }

    @Test
    fun lowMemoryHasStableAndLatestSignalsWithoutHardBlockingLocal() {
        val clock = FakeClock()
        val aggregator = StableResourceSnapshotAggregator(clock::now)

        val first = aggregator.record(snapshot(ResourcePressure.Normal, lowMemory = true))
        assertTrue(first.latestLowMemory)
        assertFalse(first.stableLowMemory)
        assertFalse(first.localHardBlocked)

        val second = aggregator.record(snapshot(ResourcePressure.Normal))
        assertFalse(second.latestLowMemory)
        assertFalse(second.stableLowMemory)
        assertEquals(StableResourceBand.Normal, second.band)

        val third = aggregator.record(snapshot(ResourcePressure.Normal, lowMemory = true))
        assertTrue(third.latestLowMemory)
        assertTrue(third.stableLowMemory)
        assertEquals(StableResourceBand.Hot, third.band)
        assertFalse(third.localHardBlocked)
    }

    @Test
    fun severeAndCriticalVoteHotWhileEmergencyAndShutdownHardBlockImmediately() {
        listOf(ThermalPressure.Severe, ThermalPressure.Critical).forEach { thermal ->
            val clock = FakeClock()
            val aggregator = StableResourceSnapshotAggregator(clock::now)
            val first = aggregator.record(snapshot(ResourcePressure.Normal, thermal = thermal))
            assertEquals(StableResourceBand.Unknown, first.band)
            assertFalse(first.localHardBlocked)
            assertEquals(thermal, first.thermalPressure)
            val second = aggregator.record(snapshot(ResourcePressure.Normal, thermal = thermal))
            assertEquals(StableResourceBand.Hot, second.band)
            assertFalse(second.localHardBlocked)
        }

        listOf(ThermalPressure.Emergency, ThermalPressure.Shutdown).forEach { thermal ->
            val clock = FakeClock()
            val state = StableResourceSnapshotAggregator(clock::now)
                .record(snapshot(ResourcePressure.Normal, thermal = thermal))
            assertEquals(StableResourceBand.Unknown, state.band)
            assertTrue(state.localHardBlocked)
            assertEquals(thermal, state.thermalPressure)
        }
    }

    @Test
    fun emergencyAndShutdownHardBlockCooldownExpiresAtFifteenSeconds() {
        listOf(ThermalPressure.Emergency, ThermalPressure.Shutdown).forEach { thermal ->
            val clock = FakeClock()
            val aggregator = StableResourceSnapshotAggregator(clock::now)
            aggregator.record(snapshot(ResourcePressure.Normal, thermal = thermal))
            aggregator.record(snapshot(ResourcePressure.Normal))

            clock.advance(14_999L)
            assertTrue(aggregator.current().localHardBlocked)

            clock.advance(1L)
            assertFalse(aggregator.current().localHardBlocked)
        }
    }

    @Test
    fun clockRollbackClearsHistoryAndCooldownThenRecordsAFirstNewEpochSample() {
        val clock = FakeClock(100L)
        val aggregator = StableResourceSnapshotAggregator(clock::now)
        aggregator.record(snapshot(ResourcePressure.Hot))
        aggregator.record(snapshot(ResourcePressure.Hot))
        assertEquals(StableResourceBand.Hot, aggregator.current().band)

        clock.set(90L)
        val firstNewEpoch = aggregator.record(snapshot(ResourcePressure.Normal))

        assertEquals(StableResourceBand.Unknown, firstNewEpoch.band)
        assertEquals(StableResourceBand.Unknown, aggregator.current().band)
        aggregator.record(snapshot(ResourcePressure.Normal))
        assertEquals(StableResourceBand.Normal, aggregator.current().band)
    }

    private fun aggregate(vararg pressures: ResourcePressure): StableResourceBand {
        val clock = FakeClock()
        val aggregator = StableResourceSnapshotAggregator(clock::now)
        pressures.forEach { aggregator.record(snapshot(it)) }
        return aggregator.current().band
    }

    private fun snapshot(
        pressure: ResourcePressure,
        lowMemory: Boolean = false,
        thermal: ThermalPressure = ThermalPressure.Normal,
    ): SystemResourceSnapshot = SystemResourceSnapshot(
        appPssBytes = 128L * MIB,
        javaHeapBytes = 64L * MIB,
        nativeHeapBytes = 32L * MIB,
        availableRamBytes = 1_024L * MIB,
        lowMemory = lowMemory,
        appCpuPercent = when (pressure) {
            ResourcePressure.Normal -> 20
            ResourcePressure.Warm -> 60
            ResourcePressure.Hot -> 80
        },
        thermalPressure = thermal,
    )

    private class FakeClock(private var value: Long = 0L) {
        fun now(): Long = value
        fun advance(delta: Long) {
            value += delta
        }
        fun set(newValue: Long) {
            value = newValue
        }
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
