package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.AppDeepTargets
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.data.AgentSkillRunCheckpointEntity
import com.bytedance.zgx.pocketmind.data.AgentRunEntity
import com.bytedance.zgx.pocketmind.data.AgentStepEntity
import com.bytedance.zgx.pocketmind.data.AgentTraceDao
import com.bytedance.zgx.pocketmind.data.PendingAgentConfirmationEntity
import com.bytedance.zgx.pocketmind.safety.SafetyDecision
import com.bytedance.zgx.pocketmind.safety.SafetyOutcome
import com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntime
import com.bytedance.zgx.pocketmind.skill.SkillStep
import com.bytedance.zgx.pocketmind.skill.valueFreeCheckpointForPendingTool
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
    fun roomStoreToolPlanningTraceDoesNotPersistParameterLikeReasonText() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-private-reason" },
        )
        val privateQuery = "private medical query"
        val request = ToolRequest(
            id = "request-search",
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to privateQuery),
            reason = "将在浏览器中搜索：$privateQuery",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.WEB_SEARCH,
            title = "联网搜索",
            summary = request.reason,
            parameters = request.arguments,
        )
        val run = store.createRun("search")

        store.appendStep(
            run.id,
            AgentStep.ModelPlanned(
                AgentPlan.UseTool(
                    request = request,
                    draft = draft,
                    plannedByModel = true,
                    fallbackReason = null,
                    safetyDecision = SafetyDecision(
                        outcome = SafetyOutcome.RequireConfirmation,
                        reason = "Search needs confirmation.",
                    ),
                ),
            ),
        )
        store.appendStep(run.id, AgentStep.ToolRequested(request, draft))

        val persistedTrace = store.stepSummaries(run.id).joinToString("\n") { step ->
            "${step.summary}\n${step.json}"
        }
        val requestedJson = JSONObject(store.stepSummaries(run.id).last().json)

        assertFalse(persistedTrace.contains(privateQuery))
        assertFalse(persistedTrace.contains(request.reason))
        assertTrue(persistedTrace.contains(MobileActionFunctions.WEB_SEARCH))
        assertTrue(persistedTrace.contains("argumentKeys"))
        assertFalse(requestedJson.has("reason"))
    }

    @Test
    fun roomStoreRedactsSensitiveTraceTextAcrossSummariesAndJson() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-redacted-trace" },
        )
        val apiKey = "sk-" + "c".repeat(32)
        val bearer = "Bearer " + "d".repeat(32)
        val email = "alice@example.com"
        val jsonCredential = "plain-token-123"
        val run = store.createRun("private prompt")
        val request = ToolRequest(
            id = "request-redacted",
            toolName = "share_text",
            arguments = mapOf("text" to "raw argument should stay out"),
            reason = "api_key=$apiKey email $email",
        )
        val draft = ActionDraft(
            functionName = "share_text",
            title = "Authorization: $bearer",
            summary = "Share sensitive text",
            parameters = request.arguments,
        )

        store.appendStep(run.id, AgentStep.ToolRequested(request, draft))
        store.appendStep(
            run.id,
            AgentStep.ToolObserved(
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Failed,
                    summary = "failed with token $apiKey and owner $email",
                ),
            ),
        )
        store.appendStep(
            run.id,
            AgentStep.AssistantResponded("""remote said $bearer for $email with {"access_token":"$jsonCredential"}"""),
        )

        val persistedTrace = store.stepSummaries(run.id).joinToString("\n") { step ->
            "${step.summary}\n${step.json}"
        }
        assertFalse(persistedTrace.contains(apiKey))
        assertFalse(persistedTrace.contains(bearer))
        assertFalse(persistedTrace.contains(email))
        assertFalse(persistedTrace.contains(jsonCredential))
        assertFalse(persistedTrace.contains("raw argument should stay out"))
        assertTrue(persistedTrace.contains("Authorization: [redacted]"))
        assertTrue(persistedTrace.contains("Bearer [redacted]"))
        assertTrue(persistedTrace.contains("[email]"))
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
    fun roomStoreRedactsAllowlistedCompletionMetadataValues() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-metadata-redacted" },
        )
        val email = "owner@example.com"
        val tokenValue = "private-token-" + "e".repeat(20)
        val run = store.createRun("open app")

        store.appendStep(
            run.id,
            AgentStep.ToolObserved(
                ToolResult(
                    requestId = "request-open",
                    status = ToolStatus.Succeeded,
                    summary = "opened",
                    data = mapOf(
                        "targetId" to email,
                        "recoveryTaskId" to "token=$tokenValue",
                        "targetPackage" to "com.example.app",
                    ),
                ),
            ),
        )

        val metadata = JSONObject(store.stepSummaries(run.id).single().json)
            .getJSONObject("completionMetadata")

        assertEquals("[email]", metadata.getString("targetId"))
        assertEquals("token=[redacted]", metadata.getString("recoveryTaskId"))
        assertEquals("com.example.app", metadata.getString("targetPackage"))
        assertFalse(metadata.toString().contains(email))
        assertFalse(metadata.toString().contains(tokenValue))
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
    fun roomStoreFailsPayloadPendingConfirmationWithoutPuttingRawArgumentsInTrace() {
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
                nextActionInput = "打开 Wi-Fi 设置",
            ),
        )
        assertEquals(rawPrompt, store.latestPendingConfirmation()?.run?.input)

        val persistedStep = store.stepSummaries(run.id).single()
        assertFalse(persistedStep.json.contains("private pending text"))
        val persistedPending = dao.latestPendingConfirmation()
        requireNotNull(persistedPending)
        assertFalse(JSONObject(persistedPending.argumentsJson).has("text"))
        assertFalse(JSONObject(persistedPending.draftParametersJson).has("text"))
        assertFalse(persistedPending.argumentsJson.contains("private pending text"))
        assertFalse(persistedPending.reason.contains("Share pending text"))
        assertFalse(persistedPending.draftParametersJson.contains("private pending text"))
        assertNull(persistedPending.nextActionInput)

        val restartedStore = RoomAgentTraceStore(traceDao = dao)
        val restored = restartedStore.latestPendingConfirmation()

        assertNull(restored)
        assertEquals(AgentRunState.Failed, restartedStore.run(waitingRun.id)?.state)
        assertTrue(restartedStore.steps(waitingRun.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertNull(restartedStore.nextActionInput(waitingRun.id))
    }

    @Test
    fun roomStoreRestoresToolSpecAllowlistedPendingArguments() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-app-deep-target" },
        )
        val run = store.updateState(store.createRun("打开应用详情").id, AgentRunState.AwaitingUserConfirmation)
        val request = ToolRequest(
            id = "request-app-details",
            toolName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
            arguments = mapOf(
                "targetId" to AppDeepTargets.APP_DETAILS_SETTINGS_ID,
                "packageName" to "com.example.app",
            ),
            reason = "Open app details",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
            title = "打开应用详情",
            summary = "打开 com.example.app 的详情页。",
            parameters = request.arguments,
        )

        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = run,
                request = request,
                draft = draft,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
                nextActionInput = "然后打开 Wi-Fi 设置",
            ),
        )

        val persisted = dao.latestPendingConfirmation()
        requireNotNull(persisted)
        assertEquals(AppDeepTargets.APP_DETAILS_SETTINGS_ID, JSONObject(persisted.argumentsJson).getString("targetId"))
        assertEquals("com.example.app", JSONObject(persisted.argumentsJson).getString("packageName"))
        assertNull(persisted.nextActionInput)
        val restored = RoomAgentTraceStore(traceDao = dao).latestPendingConfirmation()
        requireNotNull(restored)
        assertEquals(request.arguments, restored.request.arguments)
        assertEquals(request.arguments, restored.draft.parameters)
        assertNull(restored.nextActionInput)
    }

    @Test
    fun roomStoreRestoresCancelReminderPendingWithSafeTaskIdOnly() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-cancel-reminder" },
        )
        val run = store.updateState(store.createRun("取消提醒").id, AgentRunState.AwaitingUserConfirmation)
        val request = ToolRequest(
            id = "request-cancel",
            toolName = MobileActionFunctions.CANCEL_REMINDER,
            arguments = mapOf("taskId" to "task-1"),
            reason = "Cancel reminder task-1",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.CANCEL_REMINDER,
            title = "取消提醒",
            summary = "取消提醒 task-1",
            parameters = request.arguments,
        )

        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = run,
                request = request,
                draft = draft,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
                nextActionInput = "取消提醒",
            ),
        )

        val persisted = dao.latestPendingConfirmation()
        requireNotNull(persisted)
        assertTrue(persisted.argumentsJson.contains("task-1"))
        assertTrue(persisted.draftParametersJson.contains("task-1"))
        assertNull(persisted.nextActionInput)
        assertFalse(persisted.reason.contains("Cancel reminder"))
        val restored = RoomAgentTraceStore(traceDao = dao).latestPendingConfirmation()
        requireNotNull(restored)
        assertEquals(request.id, restored.request.id)
        assertEquals(mapOf("taskId" to "task-1"), restored.request.arguments)
        assertEquals(mapOf("taskId" to "task-1"), restored.draft.parameters)
        assertNull(restored.nextActionInput)
    }

    @Test
    fun roomStoreFailsUnsafeCancelReminderPendingWithoutPersistingTaskPayload() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-unsafe-cancel-reminder" },
        )
        val run = store.updateState(store.createRun("取消提醒").id, AgentRunState.AwaitingUserConfirmation)
        val unsafeTaskId = "token=secret"
        val request = ToolRequest(
            id = "request-unsafe-cancel",
            toolName = MobileActionFunctions.CANCEL_REMINDER,
            arguments = mapOf("taskId" to unsafeTaskId),
            reason = "Cancel reminder $unsafeTaskId",
        )

        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = run,
                request = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.CANCEL_REMINDER,
                    title = "取消提醒",
                    summary = "取消提醒 $unsafeTaskId",
                    parameters = request.arguments,
                ),
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
            ),
        )

        val persisted = dao.latestPendingConfirmation()
        requireNotNull(persisted)
        assertFalse(persisted.argumentsJson.contains(unsafeTaskId))
        assertFalse(persisted.draftParametersJson.contains(unsafeTaskId))
        val restartedStore = RoomAgentTraceStore(traceDao = dao)

        assertNull(restartedStore.latestPendingConfirmation())
        assertEquals(AgentRunState.Failed, restartedStore.run(run.id)?.state)
        assertTrue(restartedStore.steps(run.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
    }

    @Test
    fun roomStoreHydratesPriorToolRequestsForRestoreDedupWithoutOldConfirmations() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-hydrate-history" },
        )
        val run = store.createRun("先搜 Kotlin，然后打开 Wi-Fi 设置")
        val waitingRun = store.updateState(run.id, AgentRunState.AwaitingUserConfirmation)
        val oldRequest = ToolRequest(
            id = "request-old",
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to "Kotlin"),
            reason = "Search Kotlin",
        )
        val oldDraft = ActionDraft(
            functionName = MobileActionFunctions.WEB_SEARCH,
            title = "Web 搜索",
            summary = "Search Kotlin",
            parameters = oldRequest.arguments,
        )
        val currentRequest = ToolRequest(
            id = "request-current",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = "Open Wi-Fi settings",
        )
        val currentDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "Open Wi-Fi settings",
            parameters = emptyMap(),
        )
        store.appendStep(waitingRun.id, AgentStep.ToolRequested(oldRequest, oldDraft))
        store.appendStep(waitingRun.id, AgentStep.UserConfirmationRequested(oldRequest, oldDraft))
        store.appendStep(waitingRun.id, AgentStep.ToolRequested(currentRequest, currentDraft))
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = currentRequest,
                draft = currentDraft,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
                nextActionInput = "打开 Wi-Fi 设置",
            ),
        )
        assertNull(dao.latestPendingConfirmation()?.nextActionInput)
        dao.latestPendingConfirmation()
            ?.copy(nextActionInput = "旧版本持久化的后续动作")
            ?.let(dao::upsertPendingConfirmation)

        val restartedStore = RoomAgentTraceStore(traceDao = dao)
        val restored = restartedStore.latestPendingConfirmation()

        requireNotNull(restored)
        val hydratedSteps = restartedStore.steps(waitingRun.id)
        assertEquals(
            listOf("request-old", "request-current"),
            hydratedSteps.filterIsInstance<AgentStep.ToolRequested>().map { step -> step.request.id },
        )
        assertEquals(emptyMap<String, String>(), hydratedSteps.filterIsInstance<AgentStep.ToolRequested>().first().request.arguments)
        assertEquals(
            listOf("request-current"),
            hydratedSteps.filterIsInstance<AgentStep.UserConfirmationRequested>().map { step -> step.request.id },
        )
        assertNull(restored.nextActionInput)
        assertNull(restartedStore.nextActionInput(waitingRun.id))
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
                skillRunCheckpoint = skillPlan.valueFreeCheckpointForPendingTool(
                    runId = waitingRun.id,
                    pendingRequest = request,
                ),
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
    fun roomStorePersistsSkillRunCheckpointWithoutRawOutputs() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-skill-checkpoint" },
        )
        val rawInput = "总结剪贴板并分享给 alice@example.com"
        val rawClipboardText = "剪贴板私密原文 24680"
        val modelOutput = "私密摘要内容"
        val readRequest = ToolRequest(
            id = "request-read-clipboard",
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = "Read clipboard for summary",
        )
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本。",
            parameters = emptyMap(),
        )
        val skillPlan = BuiltInSkillRuntime().planClipboardSummaryShare(
            input = rawInput,
            readRequest = readRequest,
            readDraft = readDraft,
        )
        val shareStep = skillPlan.steps.filterIsInstance<SkillStep.ToolStep>().last()
        val shareRequest = shareStep.request.copy(
            arguments = mapOf("text" to modelOutput),
            reason = "Share summary: $modelOutput",
        )
        val shareDraft = shareStep.draft.copy(
            title = "分享摘要",
            summary = "Share summary: $modelOutput",
            parameters = shareRequest.arguments,
        )
        val waitingRun = store.updateState(store.createRun(rawInput).id, AgentRunState.AwaitingUserConfirmation)

        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = shareRequest,
                draft = shareDraft,
                skillId = skillPlan.request.skillId,
                skillPlan = skillPlan,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillRunCheckpoint = skillPlan.valueFreeCheckpointForPendingTool(
                    runId = waitingRun.id,
                    pendingRequest = shareRequest,
                ),
            ),
        )

        val checkpoint = requireNotNull(dao.skillRunCheckpoint(waitingRun.id, shareRequest.id))
        val persisted = listOf(
            checkpoint.completedStepIdsJson,
            checkpoint.outputKeysByStepJson,
            checkpoint.privateOutputRefsJson,
            checkpoint.manifestHash,
        ).joinToString("\n")

        assertFalse(persisted.contains(rawInput))
        assertFalse(persisted.contains(rawClipboardText))
        assertFalse(persisted.contains(modelOutput))
        assertFalse(persisted.contains("alice@example.com"))
        assertTrue(persisted.contains("shareText"))
        assertTrue(persisted.contains("read_clipboard.text"))
    }

    @Test
    fun roomStoreFailsCheckpointWhenCompletedOutputKeysChange() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-bad-checkpoint-output-keys" },
        )
        val readRequest = ToolRequest(
            id = "request-read-clipboard",
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = "Read clipboard for summary",
        )
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本。",
            parameters = emptyMap(),
        )
        val skillPlan = BuiltInSkillRuntime().planClipboardSummaryShare(
            input = "总结剪贴板并分享",
            readRequest = readRequest,
            readDraft = readDraft,
        )
        val shareStep = skillPlan.steps.filterIsInstance<SkillStep.ToolStep>().last()
        val waitingRun = store.updateState(
            store.createRun("总结剪贴板并分享").id,
            AgentRunState.AwaitingUserConfirmation,
        )
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = shareStep.request,
                draft = shareStep.draft,
                skillId = skillPlan.request.skillId,
                skillPlan = skillPlan,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillRunCheckpoint = skillPlan.valueFreeCheckpointForPendingTool(
                    runId = waitingRun.id,
                    pendingRequest = shareStep.request,
                ),
            ),
        )
        dao.skillRunCheckpoint(waitingRun.id, shareStep.request.id)
            ?.copy(
                outputKeysByStepJson = """{"read_clipboard":["summary","text"],"summarize_clipboard":["summary"]}""",
            )
            ?.let(dao::upsertSkillRunCheckpoint)

        val restartedStore = RoomAgentTraceStore(traceDao = dao)

        assertNull(restartedStore.latestPendingConfirmation())
        assertEquals(AgentRunState.Failed, restartedStore.run(waitingRun.id)?.state)
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertTrue(dao.skillRunCheckpointsForRun(waitingRun.id).isEmpty())
    }

    @Test
    fun roomStoreRejectsSkillRunCheckpointWithExecutableOutputValues() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-bad-checkpoint-value" },
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
        val waitingRun = store.updateState(store.createRun("总结剪贴板并分享").id, AgentRunState.AwaitingUserConfirmation)
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = request,
                draft = draft,
                skillId = skillPlan.request.skillId,
                skillPlan = skillPlan,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillRunCheckpoint = skillPlan.valueFreeCheckpointForPendingTool(
                    runId = waitingRun.id,
                    pendingRequest = request,
                ),
            ),
        )
        dao.skillRunCheckpoint(waitingRun.id, request.id)
            ?.copy(outputKeysByStepJson = """{"read_clipboard":["private clipboard text"]}""")
            ?.let(dao::upsertSkillRunCheckpoint)

        val restartedStore = RoomAgentTraceStore(traceDao = dao)

        assertNull(restartedStore.latestPendingConfirmation())
        assertEquals(AgentRunState.Failed, restartedStore.run(waitingRun.id)?.state)
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertTrue(dao.skillRunCheckpointsForRun(waitingRun.id).isEmpty())
    }

    @Test
    fun roomStoreFailsCheckpointWhenPendingStepDoesNotMatchSkillPlan() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-bad-checkpoint-step" },
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
        val waitingRun = store.updateState(store.createRun("总结剪贴板并分享").id, AgentRunState.AwaitingUserConfirmation)
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = request,
                draft = draft,
                skillId = skillPlan.request.skillId,
                skillPlan = skillPlan,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillRunCheckpoint = skillPlan.valueFreeCheckpointForPendingTool(
                    runId = waitingRun.id,
                    pendingRequest = request,
                ),
            ),
        )
        dao.skillRunCheckpoint(waitingRun.id, request.id)
            ?.copy(pendingStepId = "changed_step")
            ?.let(dao::upsertSkillRunCheckpoint)

        val restartedStore = RoomAgentTraceStore(traceDao = dao)

        assertNull(restartedStore.latestPendingConfirmation())
        assertEquals(AgentRunState.Failed, restartedStore.run(waitingRun.id)?.state)
        assertTrue(restartedStore.steps(waitingRun.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
    }

    @Test
    fun roomStoreRedactsSkillPlanInputWhenPersistingPendingConfirmation() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-redacted-skill-plan" },
        )
        val rawInput = "总结剪贴板并分享给 alice@example.com"
        val request = ToolRequest(
            id = "request-read-clipboard",
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = "Read clipboard for summary",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取剪贴板用于摘要。",
            parameters = emptyMap(),
        )
        val skillPlan = BuiltInSkillRuntime().planClipboardSummaryShare(
            input = rawInput,
            readRequest = request,
            readDraft = draft,
        )
        val run = store.createRun(rawInput)
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
                skillRunCheckpoint = skillPlan.valueFreeCheckpointForPendingTool(
                    runId = waitingRun.id,
                    pendingRequest = request,
                ),
            ),
        )

        val persistedSkillPlanJson = dao.latestPendingConfirmation()?.skillPlanJson.orEmpty()
        assertFalse(persistedSkillPlanJson.contains(rawInput))
        assertFalse(persistedSkillPlanJson.contains("alice@example.com"))

        val restored = RoomAgentTraceStore(traceDao = dao).latestPendingConfirmation()
        requireNotNull(restored)
        assertEquals("[redacted]", restored.skillPlan?.request?.arguments?.get("input"))
        assertEquals("[redacted]", restored.skillPlan?.request?.reason)
        val restoredReadStep = restored.skillPlan?.steps?.firstOrNull() as? SkillStep.ToolStep
        requireNotNull(restoredReadStep)
        assertEquals("[redacted]", restoredReadStep.request.reason)
        assertEquals("[redacted]", restoredReadStep.draft.title)
        assertEquals("[redacted]", restoredReadStep.draft.summary)
        assertFalse(restored.run.input.contains(rawInput))
        assertFalse(restored.request.reason.contains(rawInput))
    }

    @Test
    fun roomStoreFailsSkillPendingWithoutCheckpoint() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-skill-without-checkpoint" },
        )
        val request = ToolRequest(
            id = "request-read-clipboard",
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = "Read clipboard for summary",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取剪贴板用于摘要。",
            parameters = emptyMap(),
        )
        val skillPlan = BuiltInSkillRuntime().planClipboardSummaryShare(
            input = "总结剪贴板并分享",
            readRequest = request,
            readDraft = draft,
        )
        val waitingRun = store.updateState(
            store.createRun("总结剪贴板并分享").id,
            AgentRunState.AwaitingUserConfirmation,
        )

        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = request,
                draft = draft,
                skillId = skillPlan.request.skillId,
                skillPlan = skillPlan,
                plannedByModel = false,
                fallbackReason = "test missing checkpoint",
            ),
        )

        val restartedStore = RoomAgentTraceStore(traceDao = dao)

        assertNull(restartedStore.latestPendingConfirmation())
        assertEquals(AgentRunState.Failed, restartedStore.run(waitingRun.id)?.state)
        assertTrue(restartedStore.steps(waitingRun.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertTrue(dao.skillRunCheckpointsForRun(waitingRun.id).isEmpty())
    }

    @Test
    fun roomStoreClearsStaleCheckpointWhenSavingPlainPendingConfirmation() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-plain-pending-clears-checkpoint" },
        )
        val request = ToolRequest(
            id = "request-open-wifi",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = "Open Wi-Fi settings",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "Wi-Fi",
            summary = "Open Wi-Fi settings",
            parameters = emptyMap(),
        )
        val waitingRun = store.updateState(
            store.createRun("open wifi").id,
            AgentRunState.AwaitingUserConfirmation,
        )
        dao.upsertSkillRunCheckpoint(
            agentSkillRunCheckpointEntity(
                runId = waitingRun.id,
                requestId = request.id,
            ),
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

        val restartedStore = RoomAgentTraceStore(traceDao = dao)
        val restored = restartedStore.latestPendingConfirmation()

        requireNotNull(restored)
        assertEquals(waitingRun.id, restored.run.id)
        assertEquals(request.id, restored.request.id)
        assertNull(dao.skillRunCheckpoint(waitingRun.id, request.id))
    }

    @Test
    fun roomStoreSkipsPendingSkillPlanThatDoesNotContainPendingToolRequest() {
        var nextRun = 0
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-${++nextRun}" },
        )
        val validRun = store.updateState(store.createRun("open wifi").id, AgentRunState.AwaitingUserConfirmation)
        val validRequest = ToolRequest(
            id = "request-valid",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = "Open Wi-Fi",
        )
        val validDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
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

        val readRequest = ToolRequest(
            id = "request-read-clipboard",
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = "Read clipboard for summary",
        )
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本。",
            parameters = emptyMap(),
        )
        val skillPlan = BuiltInSkillRuntime().planClipboardSummaryShare(
            input = "总结剪贴板并分享",
            readRequest = readRequest,
            readDraft = readDraft,
        )
        val invalidRun = store.updateState(
            store.createRun("corrupt skill pending").id,
            AgentRunState.AwaitingUserConfirmation,
        )
        val invalidRequest = ToolRequest(
            id = "request-not-in-skill-plan",
            toolName = MobileActionFunctions.SHARE_TEXT,
            arguments = mapOf("text" to "stale"),
            reason = "Share stale text",
        )
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = invalidRun,
                request = invalidRequest,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.SHARE_TEXT,
                    title = "Share",
                    summary = "Share stale text",
                    parameters = invalidRequest.arguments,
                ),
                skillId = skillPlan.request.skillId,
                skillPlan = skillPlan,
                plannedByModel = false,
                fallbackReason = "test corrupt pending",
            ),
        )

        val restartedStore = RoomAgentTraceStore(traceDao = dao)
        val restored = restartedStore.latestPendingConfirmation()

        requireNotNull(restored)
        assertEquals(validRun.id, restored.run.id)
        assertEquals(validRequest.id, restored.request.id)
        assertEquals(AgentRunState.Failed, restartedStore.run(invalidRun.id)?.state)
        assertTrue(restartedStore.steps(invalidRun.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
    }

    @Test
    fun roomStoreFailsAwaitingRunWhenPendingConfirmationJsonIsCorrupt() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-corrupt-pending-json" },
        )
        val waitingRun = store.updateState(store.createRun("open wifi").id, AgentRunState.AwaitingUserConfirmation)
        val request = ToolRequest(
            id = "request-wifi",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = "Open Wi-Fi",
        )
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    title = "Wi-Fi",
                    summary = "Open Wi-Fi",
                    parameters = emptyMap(),
                ),
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
            ),
        )
        dao.latestPendingConfirmation()
            ?.copy(argumentsJson = "{")
            ?.let(dao::upsertPendingConfirmation)

        val restartedStore = RoomAgentTraceStore(traceDao = dao)

        assertNull(restartedStore.latestPendingConfirmation())
        assertEquals(AgentRunState.Failed, restartedStore.run(waitingRun.id)?.state)
        assertTrue(restartedStore.steps(waitingRun.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
    }

    @Test
    fun roomStoreFailsAwaitingRunWithoutPendingConfirmationOnStartupRepair() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-missing-pending" },
        )
        val waitingRun = store.updateState(store.createRun("open wifi").id, AgentRunState.AwaitingUserConfirmation)
        val restartedStore = RoomAgentTraceStore(traceDao = dao)

        val failedCount = restartedStore.failStaleInFlightRuns("process restarted")

        assertEquals(1, failedCount)
        assertEquals(AgentRunState.Failed, restartedStore.run(waitingRun.id)?.state)
        assertTrue(restartedStore.steps(waitingRun.id).any { step ->
            step is AgentStep.Failed &&
            step.reason.contains("Pending tool confirmation could not be restored")
        })
    }

    @Test
    fun roomStoreFailsSkillPendingWithoutCheckpointOnStartupRepair() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-skill-without-checkpoint-repair" },
        )
        val request = ToolRequest(
            id = "request-read-clipboard",
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = "Read clipboard for summary",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取剪贴板用于摘要。",
            parameters = emptyMap(),
        )
        val skillPlan = BuiltInSkillRuntime().planClipboardSummaryShare(
            input = "总结剪贴板并分享",
            readRequest = request,
            readDraft = draft,
        )
        val waitingRun = store.updateState(
            store.createRun("总结剪贴板并分享").id,
            AgentRunState.AwaitingUserConfirmation,
        )
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = request,
                draft = draft,
                skillId = skillPlan.request.skillId,
                skillPlan = skillPlan,
                plannedByModel = false,
                fallbackReason = "test missing checkpoint",
            ),
        )
        val restartedStore = RoomAgentTraceStore(traceDao = dao)

        val failedCount = restartedStore.failStaleInFlightRuns("process restarted")

        assertEquals(1, failedCount)
        assertEquals(AgentRunState.Failed, restartedStore.run(waitingRun.id)?.state)
        assertTrue(restartedStore.steps(waitingRun.id).any { step ->
            step is AgentStep.Failed &&
                step.reason.contains("Pending tool confirmation could not be restored")
        })
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertTrue(dao.skillRunCheckpointsForRun(waitingRun.id).isEmpty())
    }

    @Test
    fun malformedSkillPlanJsonDoesNotRestoreSkillPendingAsPlainPending() {
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            runIdFactory = { "run-corrupt-skill-plan" },
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
        val waitingRun = store.updateState(store.createRun("总结剪贴板并分享").id, AgentRunState.AwaitingUserConfirmation)
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
        dao.latestPendingConfirmation()
            ?.copy(skillPlanJson = "{")
            ?.let(dao::upsertPendingConfirmation)

        val restartedStore = RoomAgentTraceStore(traceDao = dao)

        assertNull(restartedStore.latestPendingConfirmation())
        assertEquals(AgentRunState.Failed, restartedStore.run(waitingRun.id)?.state)
        assertTrue(restartedStore.steps(waitingRun.id).none { step ->
            step is AgentStep.ToolRequested && step.request.id == request.id
        })
    }

    @Test
    fun roomStoreFailsStaleInFlightRunsButKeepsPendingConfirmationsOnStartup() {
        var now = 5_000L
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { now++ },
            runIdFactory = { "run-${now}" },
        )
        val staleRun = store.createRun("总结剪贴板并分享")
        store.updateState(staleRun.id, AgentRunState.GeneratingAnswer)
        val pendingRun = store.createRun("打开 Wi-Fi 设置")
        val waitingRun = store.updateState(pendingRun.id, AgentRunState.AwaitingUserConfirmation)
        val request = ToolRequest(
            id = "request-wifi",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = "Open Wi-Fi settings",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "将打开系统 Wi-Fi 设置页。",
            parameters = emptyMap(),
        )
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = waitingRun,
                request = request,
                draft = draft,
                skillId = BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL,
                plannedByModel = false,
                fallbackReason = "test fallback",
            ),
        )

        val restartedStore = RoomAgentTraceStore(traceDao = dao, clockMillis = { now++ })
        val failedCount = restartedStore.failStaleInFlightRuns("process restarted")

        assertEquals(1, failedCount)
        assertEquals(AgentRunState.Failed, restartedStore.run(staleRun.id)?.state)
        assertTrue(restartedStore.steps(staleRun.id).any { step ->
            step is AgentStep.Failed && step.reason == "process restarted"
        })
        val restoredPending = restartedStore.latestPendingConfirmation()
        requireNotNull(restoredPending)
        assertEquals(waitingRun.id, restoredPending.run.id)
        assertEquals(AgentRunState.AwaitingUserConfirmation, restoredPending.run.state)
        assertEquals(request.id, restoredPending.request.id)
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

    @Test
    fun roomStoreDeletesRunGraphForSessionOnly() {
        var now = 11_000L
        var nextRun = 0
        val dao = FakeAgentTraceDao()
        val store = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { now++ },
            runIdFactory = { "run-${++nextRun}" },
        )
        val deletedRun = store.updateState(
            store.createRun("delete me", sessionId = "session-delete").id,
            AgentRunState.AwaitingUserConfirmation,
        )
        val keptRun = store.updateState(
            store.createRun("keep me", sessionId = "session-keep").id,
            AgentRunState.AwaitingUserConfirmation,
        )
        val deletedRequest = ToolRequest(
            id = "request-delete",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            arguments = emptyMap(),
            reason = "Open Wi-Fi",
        )
        val keptRequest = deletedRequest.copy(id = "request-keep")
        val draft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "Wi-Fi",
            summary = "Open Wi-Fi",
            parameters = emptyMap(),
        )
        store.appendStep(deletedRun.id, AgentStep.UserConfirmationRequested(deletedRequest, draft))
        store.appendStep(keptRun.id, AgentStep.UserConfirmationRequested(keptRequest, draft))
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = deletedRun,
                request = deletedRequest,
                draft = draft,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
            ),
        )
        store.savePendingConfirmation(
            PendingToolConfirmationSnapshot(
                run = keptRun,
                request = keptRequest,
                draft = draft,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
            ),
        )
        dao.upsertSkillRunCheckpoint(
            agentSkillRunCheckpointEntity(
                runId = deletedRun.id,
                requestId = deletedRequest.id,
            ),
        )
        dao.upsertSkillRunCheckpoint(
            agentSkillRunCheckpointEntity(
                runId = keptRun.id,
                requestId = keptRequest.id,
            ),
        )

        val deletedCount = store.deleteRunsForSession("session-delete")

        assertEquals(1, deletedCount)
        assertNull(store.run(deletedRun.id))
        assertTrue(store.stepSummaries(deletedRun.id).isEmpty())
        assertNull(store.latestPendingConfirmation("session-delete"))
        assertTrue(dao.skillRunCheckpointsForRun(deletedRun.id).isEmpty())
        assertEquals(keptRun.id, store.run(keptRun.id)?.id)
        assertEquals(1, store.stepSummaries(keptRun.id).size)
        assertEquals(keptRequest.id, store.latestPendingConfirmation("session-keep")?.request?.id)
        assertEquals(1, dao.skillRunCheckpointsForRun(keptRun.id).size)
    }

    private fun agentSkillRunCheckpointEntity(
        runId: String,
        requestId: String,
    ): AgentSkillRunCheckpointEntity =
        AgentSkillRunCheckpointEntity(
            runId = runId,
            requestId = requestId,
            skillId = "skill-1",
            skillRequestId = "skill-request-1",
            manifestId = "skill-1",
            manifestVersion = 1,
            manifestHash = "a".repeat(64),
            phase = "AwaitingToolConfirmation",
            pendingStepIndex = 0,
            pendingStepId = "tool:$requestId",
            pendingToolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            completedStepIdsJson = "[]",
            outputKeysByStepJson = "{}",
            privateOutputRefsJson = "[]",
            schemaVersion = 1,
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )

    private class FakeAgentTraceDao : AgentTraceDao {
        private val runs = linkedMapOf<String, AgentRunEntity>()
        private val steps = mutableListOf<AgentStepEntity>()
        private val pendingConfirmations = linkedMapOf<String, PendingAgentConfirmationEntity>()
        private val skillRunCheckpoints = linkedMapOf<Pair<String, String>, AgentSkillRunCheckpointEntity>()

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

        override fun runIdsForSession(sessionId: String): List<String> =
            runs.values
                .filter { run -> run.sessionId == sessionId }
                .map { run -> run.id }

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

        override fun deleteStepsForSession(sessionId: String): Int {
            val runIdSet = runIdsForSession(sessionId).toSet()
            val before = steps.size
            steps.removeAll { step -> step.runId in runIdSet }
            return before - steps.size
        }

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

        override fun deletePendingConfirmationsForSession(sessionId: String): Int {
            val runIds = runIdsForSession(sessionId)
            val before = pendingConfirmations.size
            pendingConfirmations.keys.removeAll(runIds.toSet())
            return before - pendingConfirmations.size
        }

        override fun skillRunCheckpoint(
            runId: String,
            requestId: String,
        ): AgentSkillRunCheckpointEntity? =
            skillRunCheckpoints[runId to requestId]

        override fun skillRunCheckpointsForRun(runId: String): List<AgentSkillRunCheckpointEntity> =
            skillRunCheckpoints.values
                .filter { checkpoint -> checkpoint.runId == runId }
                .sortedWith(
                    compareByDescending<AgentSkillRunCheckpointEntity> { checkpoint -> checkpoint.updatedAtMillis }
                        .thenByDescending { checkpoint -> checkpoint.requestId },
                )

        override fun upsertSkillRunCheckpoint(checkpoint: AgentSkillRunCheckpointEntity) {
            skillRunCheckpoints[checkpoint.runId to checkpoint.requestId] = checkpoint
        }

        override fun deleteSkillRunCheckpoint(runId: String, requestId: String): Int =
            if (skillRunCheckpoints.remove(runId to requestId) != null) 1 else 0

        override fun deleteSkillRunCheckpointsForRun(runId: String): Int {
            val before = skillRunCheckpoints.size
            skillRunCheckpoints.keys.removeAll { (checkpointRunId, _) -> checkpointRunId == runId }
            return before - skillRunCheckpoints.size
        }

        override fun deleteSkillRunCheckpointsForSession(sessionId: String): Int {
            val runIdSet = runIdsForSession(sessionId).toSet()
            val before = skillRunCheckpoints.size
            skillRunCheckpoints.keys.removeAll { (checkpointRunId, _) -> checkpointRunId in runIdSet }
            return before - skillRunCheckpoints.size
        }

        override fun deleteRunsForSession(sessionId: String): Int {
            val runIds = runIdsForSession(sessionId)
            val before = runs.size
            runs.keys.removeAll(runIds.toSet())
            return before - runs.size
        }
    }
}
