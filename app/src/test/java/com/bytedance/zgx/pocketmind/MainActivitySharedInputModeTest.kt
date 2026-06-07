package com.bytedance.zgx.pocketmind

import com.bytedance.zgx.pocketmind.multimodal.SharedInputReadMode
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivitySharedInputModeTest {
    @Test
    fun remoteWithoutVisionUsesProtectedUnsupportedSignal() {
        assertEquals(
            SharedInputReadMode.LocalPrompt,
            sharedInputReadModeFor(
                inferenceMode = InferenceMode.Local,
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
}
