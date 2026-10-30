package com.bytedance.zgx.solin.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedIntentIngestionGateTest {
    @Test
    fun keyInFlightIsNotStartedTwice() {
        val gate = SharedIntentIngestionGate()

        val first = requireNotNull(gate.tryStart("k", allowPreviouslyConsumed = false))
        assertNull(gate.tryStart("k", allowPreviouslyConsumed = false))
        assertNull(gate.tryStart("k", allowPreviouslyConsumed = true))
        assertTrue(gate.isCurrent("k", first))
    }

    @Test
    fun completedKeyIsSkippedUnlessReconsumptionIsAllowed() {
        val gate = SharedIntentIngestionGate()
        val first = gate.tryStart("k", allowPreviouslyConsumed = false)!!
        gate.markCompleted("k", first)

        assertFalse(gate.isCurrent("k", first))
        assertNull(gate.tryStart("k", allowPreviouslyConsumed = false))
        val second = gate.tryStart("k", allowPreviouslyConsumed = true)
        assertNotNull(second)
        assertTrue(second!! > first)
    }

    @Test
    fun clearedKeyCanBeStartedAgainAndIsNotCompleted() {
        val gate = SharedIntentIngestionGate()
        val first = gate.tryStart("k", allowPreviouslyConsumed = false)!!
        gate.clear("k", first)

        assertFalse(gate.isCurrent("k", first))
        assertNotNull(gate.tryStart("k", allowPreviouslyConsumed = false))
    }

    @Test
    fun staleGenerationCannotCompleteOrClearANewerIngestion() {
        val gate = SharedIntentIngestionGate()
        val stale = gate.tryStart("k", allowPreviouslyConsumed = false)!!
        gate.clear("k", stale)
        val current = gate.tryStart("k", allowPreviouslyConsumed = false)!!

        gate.markCompleted("k", stale)
        gate.clear("k", stale)

        assertTrue(gate.isCurrent("k", current))
        gate.markCompleted("k", current)
        assertNull(gate.tryStart("k", allowPreviouslyConsumed = false))
    }

    @Test
    fun keysAreIndependent() {
        val gate = SharedIntentIngestionGate()
        val a = gate.tryStart("a", allowPreviouslyConsumed = false)!!
        val b = gate.tryStart("b", allowPreviouslyConsumed = false)!!

        assertEquals(a + 1, b)
        gate.markCompleted("a", a)
        assertTrue(gate.isCurrent("b", b))
    }
}
