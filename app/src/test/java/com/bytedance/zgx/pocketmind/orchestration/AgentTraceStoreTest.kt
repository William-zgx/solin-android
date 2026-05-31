package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.data.AgentRunEntity
import com.bytedance.zgx.pocketmind.data.AgentStepEntity
import com.bytedance.zgx.pocketmind.data.AgentTraceDao
import com.bytedance.zgx.pocketmind.data.PendingAgentConfirmationEntity
import com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntime
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceStoreTest {
    @Test
    fun inMemoryStoreKeepsTypedStepsAndStepSummaries() {
        var now = 1_000L
        val store = InMemoryAgentTraceStore(clockMillis = { now++ })
        val run = store.createRun("hello")

        store.appendStep(run.id, AgentStep.AssistantResponded("first\nanswer"))

        assertEquals(listOf(AgentStep.AssistantResponded("first\nanswer")), store.steps(run.id))
        val summary = store.stepSummaries(run.id).single()
        assertEquals(run.id, summary.runId)
        assertEquals(0, summary.position)
        assertEquals("AssistantResponded", summary.type)
        assertTrue(summary.summary.contains("first answer"))
        assertTrue(summary.json.contains("textPreview"))
    }

    @Test
    fun roomStorePersistsRunAndStepSummariesWithoutRawToolArguments() {
        var now = 2_000L
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { now++ },
            runIdFactory = { "run-persisted" },
        )
        val rawPrompt = "share something private raw prompt"
        val run = store.createRun(rawPrompt)
        val request = ToolRequest(
            id = "request-1",
            toolName = "share_text",
            arguments = mapOf("text" to "raw text that should stay out of trace json"),
            reason = "Share drafted text",
        )
        val draft = ActionDraft(
            functionName = "share_text",
            title = "Share",
            summary = "Share drafted text",
            parameters = request.arguments,
        )

        val planningRun = store.updateState(run.id, AgentRunState.Planning)
        store.appendStep(run.id, AgentStep.ToolRequested(request, draft))

        assertEquals(rawPrompt, run.input)
        assertEquals(rawPrompt, planningRun.input)
        assertEquals(rawPrompt, store.run(run.id)?.input)
        assertEquals(AgentRunState.Planning, store.run(run.id)?.state)
        assertEquals(listOf(AgentStep.ToolRequested(request, draft)), store.steps(run.id))
        val persistedStep = store.stepSummaries(run.id).single()
        assertEquals("ToolRequested", persistedStep.type)
        assertTrue(persistedStep.summary.contains("share_text"))
        assertTrue(persistedStep.json.contains("argumentKeys"))
        assertFalse(persistedStep.json.contains("raw text that should stay out"))

        val restartedStore = RoomAgentTraceStore(traceDao = dao)
        assertEquals(AgentRunState.Planning, restartedStore.run(run.id)?.state)
        assertFalse(restartedStore.run(run.id)?.input.orEmpty().contains(rawPrompt))
        assertFalse(restartedStore.recentRunSummaries(limit = 1).single().run.input.contains(rawPrompt))
        val restoredStep = restartedStore.steps(run.id).single()
        require(restoredStep is AgentStep.RestoredSummary)
        assertEquals("ToolRequested", restoredStep.persistedType)
        assertTrue(restoredStep.summary.contains("share_text"))
        assertTrue(restoredStep.json.contains("argumentKeys"))
        assertFalse(restoredStep.json.contains("raw text that should stay out"))
        assertEquals(listOf(persistedStep), restartedStore.stepSummaries(run.id))
    }

    @Test
    fun roomStorePersistsOnlyAllowlistedToolObservationCompletionMetadata() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-completion" },
        )
        val run = store.createRun("open app")

        store.appendStep(
            run.id,
            AgentStep.ToolObserved(
                ToolResult(
                    requestId = "request-open",
                    status = ToolStatus.Succeeded,
                    summary = "已打开应用深层目标",
                    data = mapOf(
                        "toolName" to MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                        "completionState" to "ExternalActivityOpened",
                        "completionVerified" to "false",
                        "externalOutcome" to "Unknown",
                        "targetKind" to "AppDeepTarget",
                        "intentAction" to "android.settings.APPLICATION_DETAILS_SETTINGS",
                        "targetId" to "android_app_details_settings",
                        "targetPackage" to "com.example.app",
                        "specialAccess" to "usage_stats",
                        "recoveryToolName" to MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
                        "metadataPolicy" to "AllowlistedCompletionMetadata",
                        "rawPayloadIncluded" to "false",
                        "rawUri" to "https://example.com/private?q=secret",
                        "rawText" to "secret payload",
                    ),
                ),
            ),
        )

        val persistedStep = store.stepSummaries(run.id).single()
        val json = JSONObject(persistedStep.json)
        val metadata = json.getJSONObject("completionMetadata")

        assertEquals("ExternalActivityOpened", metadata.getString("completionState"))
        assertEquals("false", metadata.getString("completionVerified"))
        assertEquals("Unknown", metadata.getString("externalOutcome"))
        assertEquals("AppDeepTarget", metadata.getString("targetKind"))
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", metadata.getString("intentAction"))
        assertEquals("android_app_details_settings", metadata.getString("targetId"))
        assertEquals("com.example.app", metadata.getString("targetPackage"))
        assertEquals("usage_stats", metadata.getString("specialAccess"))
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, metadata.getString("recoveryToolName"))
        assertEquals("AllowlistedCompletionMetadata", metadata.getString("metadataPolicy"))
        assertEquals("false", metadata.getString("rawPayloadIncluded"))
        assertFalse(persistedStep.json.contains("secret payload"))
        assertFalse(persistedStep.json.contains("private?q=secret"))
        assertFalse(metadata.has("rawText"))
        assertFalse(metadata.has("rawUri"))
    }

    @Test
    fun roomStorePersistsReminderRecoveryMetadataWithoutReminderContent() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-reminder-recovery" },
        )
        val run = store.createRun("提醒我稍后喝水")

        store.appendStep(
            run.id,
            AgentStep.ToolObserved(
                ToolResult(
                    requestId = "request-reminder",
                    status = ToolStatus.Succeeded,
                    summary = "已安排后台提醒",
                    data = mapOf(
                        "toolName" to MobileActionFunctions.SCHEDULE_REMINDER,
                        "taskId" to "task-1",
                        "taskStatus" to "Scheduled",
                        "triggerAtMillis" to "901000",
                        "recoveryToolName" to MobileActionFunctions.CANCEL_REMINDER,
                        "recoveryTaskId" to "task-1",
                        "title" to "喝水",
                        "body" to "这是提醒正文",
                    ),
                ),
            ),
        )

        val persistedStep = store.stepSummaries(run.id).single()
        val json = JSONObject(persistedStep.json)
        val metadata = json.getJSONObject("completionMetadata")

        assertEquals(MobileActionFunctions.CANCEL_REMINDER, metadata.getString("recoveryToolName"))
        assertEquals("task-1", metadata.getString("recoveryTaskId"))
        assertFalse(persistedStep.json.contains("喝水"))
        assertFalse(persistedStep.json.contains("这是提醒正文"))
        assertFalse(metadata.has("title"))
        assertFalse(metadata.has("body"))
    }

    @Test
    fun roomStoreRestoresPendingConfirmationWithoutPuttingRawArgumentsInTrace() {
        var now = 3_000L
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { now++ },
            runIdFactory = { "run-pending" },
        )
        val rawPrompt = "share draft private raw prompt"
        val run = store.createRun(rawPrompt)
        val waitingRun = store.updateState(run.id, AgentRunState.AwaitingUserConfirmation)
        assertEquals(rawPrompt, waitingRun.input)
        val request = ToolRequest(
            id = "request-pending",
            toolName = "share_text",
            arguments = mapOf("text" to "private pending text"),
            reason = "Share pending text",
        )
        val draft = ActionDraft(
            functionName = "share_text",
            title = "Share",
            summary = "Share pending text",
            parameters = request.arguments,
        )
        store.appendStep(waitingRun.id, AgentStep.ToolRequested(request, draft))
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = request,
                draft = draft,
                skillId = "share_skill",
                plannedByModel = false,
                fallbackReason = "test fallback",
            ),
        )
        assertEquals(rawPrompt, store.latestPendingConfirmation()?.run?.input)

        val persistedStep = store.stepSummaries(run.id).single()
        assertFalse(persistedStep.json.contains("private pending text"))

        val restartedStore = RoomAgentTraceStore(traceDao = dao)
        val restored = restartedStore.latestPendingConfirmation()

        requireNotNull(restored)
        assertEquals(waitingRun.id, restored.run.id)
        assertEquals(AgentRunState.AwaitingUserConfirmation, restored.run.state)
        assertFalse(restored.run.input.contains(rawPrompt))
        assertEquals("request-pending", restored.request.id)
        assertEquals("private pending text", restored.request.arguments["text"])
        assertEquals("share_skill", restored.skillId)
        assertTrue(restartedStore.steps(waitingRun.id).any { step ->
            step is AgentStep.ToolRequested && step.request.id == "request-pending"
        })
        assertTrue(restartedStore.steps(waitingRun.id).any { step ->
            step is AgentStep.UserConfirmationRequested && step.request.id == "request-pending"
        })

        assertTrue(restartedStore.clearPendingConfirmation(waitingRun.id, request.id))
        assertNull(restartedStore.latestPendingConfirmation())
    }

    @Test
    fun roomStoreRestoresPendingSkillPlanForContinuation() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-skill" },
        )
        val request = ToolRequest(
            id = "request-read-clipboard",
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = "Read clipboard for summary",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本。",
            parameters = emptyMap(),
        )
        val skillPlan = BuiltInSkillRuntime().planClipboardSummaryShare(
            input = "总结剪贴板并分享",
            readRequest = request,
            readDraft = draft,
        )
        val run = store.createRun("总结剪贴板并分享")
        val waitingRun = store.updateState(run.id, AgentRunState.AwaitingUserConfirmation)
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = request,
                draft = draft,
                skillId = skillPlan.request.skillId,
                skillPlan = skillPlan,
                plannedByModel = false,
                fallbackReason = "test fallback",
            ),
        )

        val restartedStore = RoomAgentTraceStore(traceDao = dao)
        val restored = restartedStore.latestPendingConfirmation()

        requireNotNull(restored)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, restored.skillPlan?.request?.skillId)
        assertEquals(3, restored.skillPlan?.steps?.size)
        val hydratedSteps = restartedStore.steps(waitingRun.id)
        val skillIndex = hydratedSteps.indexOfFirst { step -> step is AgentStep.SkillPlanned }
        val toolIndex = hydratedSteps.indexOfFirst { step -> step is AgentStep.ToolRequested }
        assertTrue(skillIndex >= 0)
        assertTrue(toolIndex >= 0)
        assertTrue(skillIndex < toolIndex)
    }

    @Test
    fun pendingConfirmationIsIgnoredWhenRunIsNoLongerAwaitingConfirmation() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-old" },
        )
        val run = store.createRun("open wifi")
        val waitingRun = store.updateState(run.id, AgentRunState.AwaitingUserConfirmation)
        val request = ToolRequest(
            id = "request-old",
            toolName = "open_wifi_settings",
            reason = "Open Wi-Fi",
        )
        val draft = ActionDraft(
            functionName = "open_wifi_settings",
            title = "Wi-Fi",
            summary = "Open Wi-Fi",
            parameters = emptyMap(),
        )
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = request,
                draft = draft,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
            ),
        )
        store.updateState(run.id, AgentRunState.Completed)

        assertNull(store.latestPendingConfirmation())
    }

    @Test
    fun latestPendingConfirmationSkipsStaleRowsAndRestoresOlderAwaitingRun() {
        var nextRun = 0
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-${++nextRun}" },
        )
        val validRun = store.updateState(store.createRun("open wifi").id, AgentRunState.AwaitingUserConfirmation)
        val validRequest = ToolRequest(
            id = "request-valid",
            toolName = "open_wifi_settings",
            reason = "Open Wi-Fi",
        )
        val validDraft = ActionDraft(
            functionName = "open_wifi_settings",
            title = "Wi-Fi",
            summary = "Open Wi-Fi",
            parameters = emptyMap(),
        )
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = validRun,
                request = validRequest,
                draft = validDraft,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
            ),
        )

        val staleRun = store.updateState(store.createRun("share").id, AgentRunState.AwaitingUserConfirmation)
        val staleRequest = ToolRequest(
            id = "request-stale",
            toolName = "share_text",
            arguments = mapOf("text" to "stale"),
            reason = "Share stale text",
        )
        val staleDraft = ActionDraft(
            functionName = "share_text",
            title = "Share",
            summary = "Share stale text",
            parameters = staleRequest.arguments,
        )
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = staleRun,
                request = staleRequest,
                draft = staleDraft,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
            ),
        )
        store.updateState(staleRun.id, AgentRunState.Completed)

        val restored = store.latestPendingConfirmation()

        requireNotNull(restored)
        assertEquals(validRun.id, restored.run.id)
        assertEquals(validRequest.id, restored.request.id)
    }

    @Test
    fun roomStoreReturnsRecentRunSummariesWithStepLimit() {
        var now = 10_000L
        var nextRun = 0
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { now++ },
            runIdFactory = { "run-${++nextRun}" },
        )
        val olderRun = store.createRun("older")
        store.appendStep(olderRun.id, AgentStep.AssistantResponded("older step"))
        val newestRun = store.createRun("newest")
        store.appendStep(newestRun.id, AgentStep.AssistantResponded("step one"))
        store.appendStep(newestRun.id, AgentStep.AssistantResponded("step two"))

        val summaries = store.recentRunSummaries(limit = 2, stepLimit = 1)

        assertEquals(listOf(newestRun.id, olderRun.id), summaries.map { it.run.id })
        assertEquals(listOf("Assistant responded: step two"), summaries.first().steps.map { it.summary })
        assertEquals(emptyList<AgentTraceRunSummary>(), store.recentRunSummaries(limit = 0, stepLimit = 1))
        assertTrue(store.recentRunSummaries(limit = 1, stepLimit = 0).single().steps.isEmpty())
    }

    private class FakeAgentTraceDao : AgentTraceDao {
        private val runs = linkedMapOf<String, AgentRunEntity>()
        private val steps = mutableListOf<AgentStepEntity>()
        private val pendingConfirmations = linkedMapOf<String, PendingAgentConfirmationEntity>()

        override fun run(runId: String): AgentRunEntity? =
            runs[runId]

        override fun recentRuns(limit: Int): List<AgentRunEntity> =
            runs.values
                .sortedWith(
                    compareByDescending<AgentRunEntity> { run -> run.updatedAtMillis }
                        .thenByDescending { run -> run.createdAtMillis }
                        .thenByDescending { run -> run.id },
                )
                .take(limit)

        override fun upsertRun(run: AgentRunEntity) {
            runs[run.id] = run
        }

        override fun updateRunState(runId: String, state: String, updatedAtMillis: Long): Int {
            val run = runs[runId] ?: return 0
            runs[runId] = run.copy(state = state, updatedAtMillis = updatedAtMillis)
            return 1
        }

        override fun touchRun(runId: String, updatedAtMillis: Long): Int {
            val run = runs[runId] ?: return 0
            runs[runId] = run.copy(updatedAtMillis = updatedAtMillis)
            return 1
        }

        override fun nextStepPosition(runId: String): Int =
            steps
                .filter { step -> step.runId == runId }
                .maxOfOrNull { step -> step.position + 1 }
                ?: 0

        override fun insertStep(step: AgentStepEntity) {
            steps.removeAll { existing ->
                existing.runId == step.runId && existing.position == step.position
            }
            steps += step
        }

        override fun steps(runId: String): List<AgentStepEntity> =
            steps
                .filter { step -> step.runId == runId }
                .sortedBy { step -> step.position }

        override fun pendingConfirmations(): List<PendingAgentConfirmationEntity> =
            pendingConfirmations.values.sortedWith(
                compareByDescending<PendingAgentConfirmationEntity> { pending -> pending.updatedAtMillis }
                    .thenByDescending { pending -> pending.createdAtMillis }
                    .thenByDescending { pending -> pending.runId },
            )

        override fun latestPendingConfirmation(): PendingAgentConfirmationEntity? =
            pendingConfirmations().firstOrNull()

        override fun upsertPendingConfirmation(pending: PendingAgentConfirmationEntity) {
            pendingConfirmations[pending.runId] = pending
        }

        override fun deletePendingConfirmation(runId: String, requestId: String): Int {
            val existing = pendingConfirmations[runId] ?: return 0
            if (existing.requestId != requestId) return 0
            pendingConfirmations.remove(runId)
            return 1
        }
    }
}
