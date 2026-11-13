package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.common.keysSet
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceEncodingTest {
    @Test
    fun contextLoadedKeepsDeviceContextPresenceSeparateFromSummary() {
        val without = AgentStep.ContextLoaded(memoryHits = emptyList()).toTraceStepSummary("run", 0, 0L)
        val with = AgentStep.ContextLoaded(
            memoryHits = emptyList(),
            deviceContext = DeviceContextSnapshot(
                isArm64Supported = true,
                inferenceMode = "local",
                installedCapabilities = emptySet(),
                memoryEnabled = false,
                availableStorageBytes = 0L,
                activeSessionId = null,
                hasPendingConfirmation = false
            )
        ).toTraceStepSummary("run", 1, 0L)

        assertFalse(JSONObject(without.json).getBoolean("hasDeviceContext"))
        assertEquals("Loaded 0 memory hit(s) without device context.", without.summary)
        assertTrue(JSONObject(with.json).getBoolean("hasDeviceContext"))
        assertEquals("Loaded 0 memory hit(s) with device context.", with.summary)
    }

    @Test
    fun nestedObservationDecisionKeepsOuterAndInnerTypes() {
        val encoded = AgentStep.ObservationDecided(AgentObservationDecision.Complete)
            .toTraceStepSummary("run", 0, 0L)

        assertEquals("ObservationDecided", encoded.type)
        assertEquals("Observation complete.", encoded.summary)
        assertEquals("ObservationDecided", JSONObject(encoded.json).getString("type"))
        assertEquals("Complete", JSONObject(encoded.json).getJSONObject("decision").getString("type"))
    }

    @Test
    fun restoredSummaryUsesNormalizedTypeForValidJsonAndFallbackForMalformed() {
        val restored = AgentStep.RestoredSummary(
            persistedType = "Custom",
            summary = "old",
            json = """{"z":1,"type":"Legacy"}"""
        ).toTraceStepSummary("run", 0, 0L)
        val malformed = AgentStep.RestoredSummary("Custom", "old", "{bad")
            .toTraceStepSummary("run", 1, 0L)

        val validJson = JSONObject(restored.json)
        assertEquals(setOf("type"), validJson.keysSet())
        assertEquals("Custom", validJson.getString("type"))
        assertEquals("Custom", JSONObject(malformed.json).getString("type"))
        assertEquals("Custom", JSONObject(malformed.json).getString("persistedType"))
        assertEquals("old", JSONObject(malformed.json).getString("summary"))
    }
}
