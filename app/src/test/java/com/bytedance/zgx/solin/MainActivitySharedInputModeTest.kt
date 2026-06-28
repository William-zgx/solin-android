package com.bytedance.zgx.solin

import com.bytedance.zgx.solin.multimodal.SharedInputReadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySharedInputModeTest {
    @Test
    fun remoteWithoutVisionUsesProtectedUnsupportedSignal() {
        assertEquals(
            SharedInputReadMode.LocalPrompt,
            sharedInputReadModeFor(
                inferenceMode = InferenceMode.Local,
                localSupportsVisionInput = false,
                remoteConfigured = false,
                remoteSupportsVisionInput = false,
            ),
        )
        assertEquals(
            SharedInputReadMode.LocalVision,
            sharedInputReadModeFor(
                inferenceMode = InferenceMode.Local,
                localSupportsVisionInput = true,
                remoteConfigured = false,
                remoteSupportsVisionInput = false,
            ),
        )
        assertEquals(
            SharedInputReadMode.RemoteVision,
            sharedInputReadModeFor(
                inferenceMode = InferenceMode.Remote,
                remoteConfigured = true,
                remoteSupportsVisionInput = true,
            ),
        )
        assertEquals(
            SharedInputReadMode.RemoteVisionUnsupportedSignal,
            sharedInputReadModeFor(
                inferenceMode = InferenceMode.Remote,
                remoteConfigured = true,
                remoteSupportsVisionInput = false,
            ),
        )
        assertEquals(
            SharedInputReadMode.RemoteVisionUnsupportedSignal,
            sharedInputReadModeFor(
                inferenceMode = InferenceMode.Remote,
                remoteConfigured = false,
                remoteSupportsVisionInput = true,
            ),
        )
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
