package com.bytedance.zgx.solin

import com.bytedance.zgx.solin.multimodal.SharedInputReadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySharedInputModeTest {
    @Test
    fun sourceReadModeIsIndependentOfInferencePreference() {
        // One constant for every inference mode: the read never depends on the destination.
        assertEquals(SharedInputReadMode.DestinationNeutralVision, SHARED_INPUT_READ_MODE)
    }

    @Test
    fun voiceInputSessionGateInvalidatesCancelledSessionBeforeRestart() {
        val gate = VoiceInputSessionGate()
        val cancelled = gate.startSession()

        gate.cancelActiveSession()
        assertFalse(gate.isActive(cancelled))
        val restarted = gate.startSession()

        assertFalse(gate.isActive(cancelled))
        assertTrue(gate.isActive(restarted))

        gate.clearIfActive(cancelled)
        assertTrue(gate.isActive(restarted))

        gate.clearIfActive(restarted)
        assertFalse(gate.isActive(restarted))
    }
}
