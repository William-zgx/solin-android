package com.bytedance.zgx.solin.multimodal

import com.bytedance.zgx.solin.MessagePrivacy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentScreenshotOcrContractTest {
    @Test
    fun schemaLocksOneShotLocalOnlyOcrBoundary() {
        val input = JSONObject(CurrentScreenshotOcrContract.INPUT_SCHEMA_JSON)
        val output = JSONObject(CurrentScreenshotOcrContract.OUTPUT_SCHEMA_JSON)
        val inputCaptureMode = input.getJSONObject("properties").getJSONObject("captureMode")
        val outputProperties = output.getJSONObject("properties")
        val requiredOutputKeys = (0 until output.getJSONArray("required").length())
            .map { index -> output.getJSONArray("required").getString(index) }

        assertEquals(CurrentScreenshotOcrContract.TOOL_NAME, outputProperties.getJSONObject("toolName").getString("const"))
        assertEquals("current_screen", inputCaptureMode.getJSONArray("enum").getString(0))
        assertEquals("current_screen", outputProperties.getJSONObject("captureMode").getJSONArray("enum").getString(0))
        assertEquals(MessagePrivacy.LocalOnly.name, outputProperties.getJSONObject("privacy").getJSONArray("enum").getString(0))
        assertTrue(outputProperties.getJSONObject("requiresLocalModel").getBoolean("const"))
        assertEquals("boolean", outputProperties.getJSONObject("truncated").getString("type"))
        assertFalse(outputProperties.getJSONObject("rawPayloadIncluded").getBoolean("const"))
        assertEquals(
            CurrentScreenshotOcrContract.OUTPUT_METADATA_POLICY,
            outputProperties.getJSONObject("metadataPolicy").getJSONArray("enum").getString(0),
        )
        assertTrue(requiredOutputKeys.contains("screenObservationIncluded"))
        assertFalse(requiredOutputKeys.contains("ocrBlocksJson"))
        assertFalse(requiredOutputKeys.contains("screenObservationJson"))
        assertFalse(requiredOutputKeys.contains("screenObservationFailureKind"))
        assertEquals("string", outputProperties.getJSONObject("ocrBlocksJson").getString("type"))
        assertEquals(
            "application/json",
            outputProperties.getJSONObject("ocrBlocksJson").getString("contentMediaType"),
        )
        assertEquals("boolean", outputProperties.getJSONObject("screenObservationIncluded").getString("type"))
        assertEquals("string", outputProperties.getJSONObject("screenObservationJson").getString("type"))
        assertEquals(
            "application/json",
            outputProperties.getJSONObject("screenObservationJson").getString("contentMediaType"),
        )
        assertEquals("string", outputProperties.getJSONObject("screenObservationFailureKind").getString("type"))
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
        assertTrue(errors.any { it.contains("structured Accessibility/OCR observation only") })
    }

    @Test
    fun oneShotConsentStoreRequiresMatchingRequestIdAndTtl() {
        val store = RequestBoundOneShotConsentStore<String>(ttlMillis = 1_000L)

        store.set(requestId = "request-a", issuedAtMillis = 10_000L, data = "consent-a")

        assertFalse(store.has(requestId = "request-b", nowMillis = 10_100L))
        assertTrue(store.has(requestId = "request-a", nowMillis = 10_100L))
        assertNull(store.consume(requestId = "request-b", nowMillis = 10_100L))
        assertEquals("consent-a", store.consume(requestId = "request-a", nowMillis = 10_100L))
        assertNull(store.consume(requestId = "request-a", nowMillis = 10_100L))

        store.set(requestId = "request-a", issuedAtMillis = 10_000L, data = "expired")

        assertFalse(store.has(requestId = "request-a", nowMillis = 11_001L))
        assertNull(store.consume(requestId = "request-a", nowMillis = 11_001L))
        assertNull(store.consume(requestId = "request-a", nowMillis = 10_100L))
    }

    @Test
    fun oneShotConsentStoreClearsOnlyMatchingRequest() {
        val store = RequestBoundOneShotConsentStore<String>(ttlMillis = 1_000L)

        store.set(requestId = "request-a", issuedAtMillis = 10_000L, data = "consent-a")
        store.clear("request-b")

        assertEquals("consent-a", store.consume(requestId = "request-a", nowMillis = 10_100L))

        store.set(requestId = "request-a", issuedAtMillis = 10_000L, data = "consent-a")
        store.clear("request-a")

        assertNull(store.consume(requestId = "request-a", nowMillis = 10_100L))
    }
}
