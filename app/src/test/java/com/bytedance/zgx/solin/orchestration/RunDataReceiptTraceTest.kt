package com.bytedance.zgx.solin.orchestration

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDataReceiptTraceTest {
    @Test
    fun traceStoresReceiptPolicyAndCountsWithoutRawContent() {
        val store = InMemoryAgentTraceStore(clockMillis = { 1L })
        val run = store.createRun("raw prompt should not appear", sessionId = "session")
        store.appendStep(
            run.id,
            AgentStep.RunDataReceiptRecorded(
                RunDataReceipt(
                    destination = RunDataDestination.Remote,
                    currentPromptPrivacy = "RemoteEligible",
                    remoteHistoryCount = 2,
                    localOnlyHistoryFilteredCount = 1,
                    memoryHitCount = 0,
                    memoryContextIncluded = false,
                    deviceContextIncluded = false,
                    imageAttachmentCount = 1,
                    protectedSourceCount = 1,
                    evidenceCardCount = 2,
                    localOnlyEvidenceCardCount = 1,
                    truncatedEvidenceCardCount = 1,
                    lowQualityEvidenceCardCount = 0,
                    evidenceSourceTypes = listOf("Memory", "ImageAttachment"),
                    rawContentPersisted = false,
                    protectedContentTypes = listOf("本地记忆", "LocalOnly 历史"),
                    deletableRecordTypes = listOf("对话消息", "Agent 轨迹"),
                ),
            ),
        )

        val step = store.stepSummaries(run.id).single()
        val json = JSONObject(step.json)

        assertEquals("RunDataReceiptRecorded", step.type)
        assertTrue(step.summary.contains("remoteHistory=2"))
        assertEquals("Remote", json.getString("destination"))
        assertEquals(1, json.getInt("imageAttachmentCount"))
        assertEquals(2, json.getInt("evidenceCardCount"))
        assertEquals(1, json.getInt("localOnlyEvidenceCardCount"))
        assertEquals("Memory", json.getJSONArray("evidenceSourceTypes").getString(0))
        assertFalse(json.getBoolean("memoryContextIncluded"))
        assertEquals("本地记忆", json.getJSONArray("protectedContentTypes").getString(0))
        assertEquals("Agent 轨迹", json.getJSONArray("deletableRecordTypes").getString(1))
        assertFalse(json.toString().contains("raw prompt"))
    }

    @Test
    fun recentRunSummaryPinsRunDataReceiptOutsideVisibleStepWindow() {
        val store = InMemoryAgentTraceStore(clockMillis = { 1L })
        val run = store.createRun("raw prompt should not appear", sessionId = "session")
        store.appendStep(
            run.id,
            AgentStep.RunDataReceiptRecorded(
                RunDataReceipt(
                    destination = RunDataDestination.Remote,
                    currentPromptPrivacy = "RemoteEligible",
                ),
            ),
        )
        repeat(6) { index ->
            store.appendStep(run.id, AgentStep.AssistantResponded("step-$index"))
        }

        val summary = store.recentRunSummaries(limit = 1, stepLimit = 2).single()

        assertEquals(2, summary.steps.size)
        assertFalse(summary.steps.any { step -> step.type == "RunDataReceiptRecorded" })
        assertEquals("RunDataReceiptRecorded", summary.runDataReceiptStep?.type)
    }

    @Test
    fun traceStoresOutputQualityGuardWithoutRawOutput() {
        val store = InMemoryAgentTraceStore(clockMillis = { 1L })
        val run = store.createRun("raw prompt should not appear", sessionId = "session")
        store.appendStep(
            run.id,
            AgentStep.ModelOutputQualityGuardTriggered(
                ModelOutputQualityTrace(
                    issue = "RepetitionLoop",
                    severity = "Critical",
                    triggeredRule = "same_character_run>=32",
                    action = "StopAndKeepPrefix",
                    rawOutputLength = 64,
                    keptPrefix = true,
                    modelId = "chat-e2b",
                    backend = "CPU",
                    runtimeKind = "Local",
                ),
            ),
        )

        val step = store.stepSummaries(run.id).single()
        val json = JSONObject(step.json)

        assertEquals("ModelOutputQualityGuardTriggered", step.type)
        assertTrue(step.summary.contains("RepetitionLoop"))
        assertEquals("same_character_run>=32", json.getString("triggeredRule"))
        assertEquals(64, json.getInt("rawOutputLength"))
        assertFalse(json.toString().contains("raw prompt"))
    }
}
