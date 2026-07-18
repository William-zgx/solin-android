package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.resource.ThermalPressure
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

    @Test
    fun placementReceiptInvocationAndShadowUseExactSafeJsonSchemas() {
        val store = InMemoryAgentTraceStore(clockMillis = { 1L })
        val run = store.createRun("prompt-secret", sessionId = "session")
        val binding = testBinding(runId = run.id, placement = RunPlacement.Remote)
        val canaries = listOf(
            "prompt-secret",
            "https://api.example.com/v1",
            "127.0.0.1",
            "model-secret",
            "sk-secret",
            "token-secret",
            "tool-name-secret",
            "argument-secret",
        )

        store.appendStep(run.id, AgentStep.PlacementSelected(binding))
        store.appendStep(
            run.id,
            AgentStep.RunDataReceiptRecorded(
                RunDataReceipt(
                    destination = RunDataDestination.Remote,
                    currentPromptPrivacy = canaries[0],
                    evidenceSourceTypes = canaries,
                    protectedContentTypes = canaries,
                    deletableRecordTypes = canaries,
                    outputQualityGuardTriggered = true,
                    outputQualityIssue = canaries[3],
                    outputQualityRule = canaries[6],
                    outputQualityAction = canaries[7],
                ),
            ),
        )
        store.appendStep(
            run.id,
            AgentStep.ModelRuntimeInvocationStarted(
                ModelRuntimeInvocation(
                    runId = run.id,
                    placement = RunPlacement.Remote,
                    attempt = 1,
                    remoteProfileRevision = TEST_REMOTE_REVISION,
                ),
            ),
        )
        store.appendStep(
            run.id,
            AgentStep.ShadowPlacementEvaluated(ShadowPlacementTrace.from(binding.toPlacementDecision())),
        )

        val summaries = store.stepSummaries(run.id).associateBy { it.type }
        assertEquals(
            PLACEMENT_SELECTED_TRACE_KEYS,
            JSONObject(summaries.getValue("PlacementSelected").json).keysSet(),
        )
        assertEquals(
            RUN_DATA_RECEIPT_TRACE_KEYS,
            JSONObject(summaries.getValue("RunDataReceiptRecorded").json).keysSet(),
        )
        assertEquals(
            MODEL_RUNTIME_INVOCATION_TRACE_KEYS,
            JSONObject(summaries.getValue("ModelRuntimeInvocationStarted").json).keysSet(),
        )
        assertEquals(
            SHADOW_PLACEMENT_TRACE_KEYS,
            JSONObject(summaries.getValue("ShadowPlacementEvaluated").json).keysSet(),
        )

        val persistedJson = summaries.values.joinToString(separator = "\n") { it.json }
        canaries.forEach { canary -> assertFalse("leaked $canary", persistedJson.contains(canary)) }
        val receiptJson = JSONObject(summaries.getValue("RunDataReceiptRecorded").json)
        assertEquals("LocalOnly", receiptJson.getString("currentPromptPrivacy"))
        assertEquals(0, receiptJson.getJSONArray("evidenceSourceTypes").length())
        assertTrue(receiptJson.isNull("outputQualityRule"))
    }

    @Test
    fun shadowPlacementIsBestEffortAndNeverCreatesDispatchTrace() {
        val delegate = InMemoryAgentTraceStore(clockMillis = { 1L })
        val run = delegate.createRun("[redacted]", sessionId = "session")
        val decision = testBinding(runId = run.id).toPlacementDecision()

        assertTrue(delegate.appendShadowPlacementBestEffort(run.id, decision))
        assertEquals(listOf("ShadowPlacementEvaluated"), delegate.stepSummaries(run.id).map { it.type })
        assertFalse(
            delegate.stepSummaries(run.id).any { summary ->
                summary.type in setOf(
                    "PlacementSelected",
                    "RunDataReceiptRecorded",
                    "ModelRuntimeInvocationStarted",
                )
            },
        )

        val throwingStore = object : AgentTraceStore by delegate {
            override fun appendStep(runId: String, step: AgentStep) {
                error("shadow trace unavailable")
            }
        }
        assertFalse(throwingStore.appendShadowPlacementBestEffort(run.id, decision))
    }

    private fun RunPlacementBinding.toPlacementDecision(): PlacementDecision.Chosen =
        PlacementDecision.Chosen(
            policyVersion = policyVersion,
            preference = preference,
            primaryReason = primaryReason,
            placement = placement,
            diagnostics = PlacementDiagnostics(
                complexity = complexity,
                resourceBand = resourceBand,
                stableLowMemory = false,
                latestLowMemory = false,
                localHardBlocked = false,
                thermalPressure = ThermalPressure.Normal,
                localState = localState,
                remoteState = remoteState,
            ),
        )
}
