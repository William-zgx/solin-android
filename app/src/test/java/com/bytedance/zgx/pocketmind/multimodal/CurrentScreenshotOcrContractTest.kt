package com.bytedance.zgx.pocketmind.multimodal

import com.bytedance.zgx.pocketmind.MessagePrivacy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentScreenshotOcrContractTest {
    @Test
    fun schemaLocksOneShotLocalOnlyOcrBoundary() {
        val input = JSONObject(CurrentScreenshotOcrContract.INPUT_SCHEMA_JSON)
        val output = JSONObject(CurrentScreenshotOcrContract.OUTPUT_SCHEMA_JSON)
        val inputCaptureMode = input.getJSONObject("properties").getJSONObject("captureMode")
        val outputProperties = output.getJSONObject("properties")

        assertEquals(CurrentScreenshotOcrContract.TOOL_NAME, outputProperties.getJSONObject("toolName").getString("const"))
        assertEquals("current_screen", inputCaptureMode.getJSONArray("enum").getString(0))
        assertEquals("current_screen", outputProperties.getJSONObject("captureMode").getJSONArray("enum").getString(0))
        assertEquals(MessagePrivacy.LocalOnly.name, outputProperties.getJSONObject("privacy").getJSONArray("enum").getString(0))
        assertTrue(outputProperties.getJSONObject("requiresLocalModel").getBoolean("const"))
        assertEquals("boolean", outputProperties.getJSONObject("truncated").getString("type"))
        assertEquals(
            CurrentScreenshotOcrContract.OUTPUT_METADATA_POLICY,
            outputProperties.getJSONObject("metadataPolicy").getJSONArray("enum").getString(0),
        )
        assertFalse(outputProperties.has("pixels"))
        assertFalse(outputProperties.has("uri"))
        assertFalse(outputProperties.has("path"))
        assertFalse(outputProperties.has("windowTitle"))
        assertFalse(outputProperties.has("visualDescription"))
    }

    @Test
    fun boundaryRejectsContinuousRemotePersistentOrSemanticCapture() {
        val valid = CurrentScreenshotOcrBoundary(
            foregroundToolConfirmationRequired = true,
            mediaProjectionConsentRequired = true,
            oneShotOnly = true,
            localOnly = true,
            requiresLocalModel = true,
            persistsPixels = false,
            persistsUriPathOrWindowTitle = false,
            producesSemanticVisualUnderstanding = false,
        )

        assertTrue(CurrentScreenshotOcrContract.validateBoundary(valid).isEmpty())

        val invalid = valid.copy(
            foregroundToolConfirmationRequired = false,
            mediaProjectionConsentRequired = false,
            oneShotOnly = false,
            localOnly = false,
            requiresLocalModel = false,
            persistsPixels = true,
            persistsUriPathOrWindowTitle = true,
            producesSemanticVisualUnderstanding = true,
        )
        val errors = CurrentScreenshotOcrContract.validateBoundary(invalid)

        assertTrue(errors.any { it.contains("foreground tool confirmation") })
        assertTrue(errors.any { it.contains("MediaProjection") })
        assertTrue(errors.any { it.contains("one-shot") })
        assertTrue(errors.any { it.contains("LocalOnly") })
        assertTrue(errors.any { it.contains("must not persist pixels") })
        assertTrue(errors.any { it.contains("OCR text only") })
    }
}
