package com.bytedance.zgx.pocketmind.device

import com.bytedance.zgx.pocketmind.ModelCapability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceContextModelsTest {
    @Test
    fun promptContextRedactsRawSessionId() {
        val context = DeviceContextSnapshot(
            isArm64Supported = true,
            inferenceMode = "local",
            installedCapabilities = setOf(ModelCapability.Chat, ModelCapability.MobileAction),
            memoryEnabled = true,
            availableStorageBytes = 2_097_152L,
            activeSessionId = "session-secret-123",
            hasPendingConfirmation = false,
        )

        val promptContext = context.toPromptContext()

        assertTrue(promptContext.contains("Active session exists: true"))
        assertFalse(promptContext.contains("session-secret-123"))
        assertTrue(promptContext.contains("Chat"))
        assertTrue(promptContext.contains("MobileAction"))
    }
}
