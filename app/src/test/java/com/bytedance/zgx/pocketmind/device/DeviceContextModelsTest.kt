package com.bytedance.zgx.pocketmind.device

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrContract
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
            toolReadiness = listOf(
                DeviceContextToolReadiness(
                    toolName = "query_contacts",
                    state = DeviceContextToolReadinessState.RequiresRuntimePermission,
                    reason = "contacts require confirmation",
                    runtimePermissions = listOf("READ_CONTACTS"),
                ),
                DeviceContextToolReadiness(
                    toolName = "read_current_screen_text",
                    state = DeviceContextToolReadinessState.RequiresSpecialAccess,
                    reason = "Accessibility text only",
                    specialAccessId = "accessibility_screen_text",
                ),
                DeviceContextToolReadiness(
                    toolName = "capture_current_screenshot_ocr",
                    state = DeviceContextToolReadinessState.RequiresForegroundConsent,
                    reason = "MediaProjection consent only",
                    specialAccessId = CurrentScreenshotOcrContract.CONSENT_REASON,
                ),
            ),
        )

        val promptContext = context.toPromptContext()

        assertTrue(promptContext.contains("Active session exists: true"))
        assertFalse(promptContext.contains("session-secret-123"))
        assertTrue(promptContext.contains("Chat"))
        assertTrue(promptContext.contains("MobileAction"))
        assertTrue(promptContext.contains("query_contacts: RequiresRuntimePermission"))
        assertTrue(promptContext.contains("permissions=READ_CONTACTS"))
        assertTrue(promptContext.contains("read_current_screen_text: RequiresSpecialAccess"))
        assertTrue(promptContext.contains("specialAccess=accessibility_screen_text"))
        assertTrue(promptContext.contains("capture_current_screenshot_ocr: RequiresForegroundConsent"))
        assertTrue(promptContext.contains("specialAccess=${CurrentScreenshotOcrContract.CONSENT_REASON}"))
    }
}
