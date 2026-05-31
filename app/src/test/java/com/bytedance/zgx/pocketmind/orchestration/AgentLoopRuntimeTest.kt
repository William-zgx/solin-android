package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlan
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningResult
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.audit.InMemoryToolAuditSink
import com.bytedance.zgx.pocketmind.audit.ToolAuditEventType
import com.bytedance.zgx.pocketmind.data.AgentRunEntity
import com.bytedance.zgx.pocketmind.data.AgentStepEntity
import com.bytedance.zgx.pocketmind.data.AgentTraceDao
import com.bytedance.zgx.pocketmind.data.PendingAgentConfirmationEntity
import com.bytedance.zgx.pocketmind.device.DeviceContextSnapshot
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.memory.MemoryIndex
import com.bytedance.zgx.pocketmind.memory.MemoryRepository
import com.bytedance.zgx.pocketmind.safety.SafetyOutcome
import com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntime
import com.bytedance.zgx.pocketmind.skill.SkillManifest
import com.bytedance.zgx.pocketmind.skill.SkillPlan
import com.bytedance.zgx.pocketmind.skill.SkillRequest
import com.bytedance.zgx.pocketmind.skill.SkillRuntime
import com.bytedance.zgx.pocketmind.skill.SkillStep
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolError
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopRuntimeTest {
    @Test
    fun pureChatInputPlansAnswerAndKeepsMemoryHits() {
        val memoryRepository = MemoryRepository()
        memoryRepository.index("preference", "用户喜欢端侧离线聊天")
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = memoryRepository,
            actionPlanningRuntime = actionRuntime,
            traceStore = traceStore,
        )

        val result = runtime.runOnce(
            input = "端侧聊天偏好是什么",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
        require(result.plan is AgentPlan.Answer)
        assertTrue(result.plan.promptForModel.contains("用户当前输入的语言"))
        assertTrue(result.plan.promptForModel.contains("用户喜欢端侧离线聊天"))
        assertEquals(listOf("preference"), result.plan.memoryHits.map { it.id })
        assertEquals(0, actionRuntime.planCallCount)
        assertTrue(result.steps.any { step ->
            step is AgentStep.ContextLoaded &&
                step.memoryHits.map { it.id } == listOf("preference")
        })
        assertTrue(result.steps.any { step ->
            step is AgentStep.ModelPlanned && step.plan is AgentPlan.Answer
        })
    }

    @Test
    fun memorySearchFailureFallsBackToEmptyContext() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = ThrowingMemoryIndex(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "普通问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
        require(result.plan is AgentPlan.Answer)
        assertTrue(result.plan.memoryHits.isEmpty())
        assertEquals("普通问题", result.plan.promptForModel)
        assertTrue(result.steps.any { step ->
            step is AgentStep.ContextLoaded && step.memoryHits.isEmpty()
        })
    }

    @Test
    fun memoryContextBuildFailureStillAllowsDeviceContextPrompt() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = ThrowingMemoryIndex(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val deviceContext = DeviceContextSnapshot(
            isArm64Supported = true,
            inferenceMode = "Remote",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            availableStorageBytes = 512L * 1024L * 1024L,
            activeSessionId = "session-1",
            hasPendingConfirmation = false,
        )

        val result = runtime.runOnce(
            input = "现在用的是什么推理模式",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            deviceContext = deviceContext,
        )

        require(result.plan is AgentPlan.Answer)
        assertTrue(result.plan.memoryHits.isEmpty())
        assertTrue(Regex("""本地记忆：\s+无""").containsMatchIn(result.plan.promptForModel))
        assertTrue(result.plan.promptForModel.contains("Inference mode: Remote"))
    }

    @Test
    fun pureChatInputCanCarryMinimalDeviceContext() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val deviceContext = DeviceContextSnapshot(
            isArm64Supported = true,
            inferenceMode = "Remote",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            availableStorageBytes = 512L * 1024L * 1024L,
            activeSessionId = "session-1",
            hasPendingConfirmation = false,
        )

        val result = runtime.runOnce(
            input = "现在用的是什么推理模式",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
            deviceContext = deviceContext,
        )

        require(result.plan is AgentPlan.Answer)
        assertTrue(result.plan.promptForModel.contains("设备上下文"))
        assertTrue(result.plan.promptForModel.contains("Inference mode: Remote"))
        assertTrue(result.steps.any { step ->
            step is AgentStep.ContextLoaded && step.deviceContext == deviceContext
        })
    }

    @Test
    fun wifiActionInputRequestsConfirmationBeforeExecution() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = traceStore,
        )

        val result = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.plan.request.toolName)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.plan.draft.functionName)
        assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, result.plan.skillRequest?.skillId)
        assertEquals(SafetyOutcome.RequireConfirmation, result.plan.safetyDecision.outcome)
        assertTrue(result.plan.draft.requiresConfirmation)
        assertTrue(!result.plan.plannedByModel)
        assertEquals("test fallback", result.plan.fallbackReason)
        assertEquals(1, actionRuntime.planCallCount)
        assertEquals(
            listOf(
                AgentStep.ContextLoaded::class,
                AgentStep.ModelPlanned::class,
                AgentStep.SkillPlanned::class,
                AgentStep.SafetyChecked::class,
                AgentStep.ToolRequested::class,
                AgentStep.UserConfirmationRequested::class,
            ),
            result.steps.map { it::class },
        )
        assertTrue(result.steps.any { step ->
            step is AgentStep.SkillPlanned &&
                step.request.skillId == BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL
        })
        assertTrue(result.steps.any { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.OPEN_WIFI_SETTINGS &&
                step.draft.functionName == MobileActionFunctions.OPEN_WIFI_SETTINGS
        })
    }

    @Test
    fun reminderActionInputUsesReminderSkillAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.SCHEDULE_REMINDER,
                        title = "后台提醒",
                        summary = "将在 15 分钟后提醒：喝水",
                        parameters = mapOf(
                            "title" to "喝水",
                            "body" to "提醒我 15 分钟后喝水",
                            "delayMinutes" to "15",
                        ),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "提醒我 15 分钟后喝水",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, result.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, result.plan.skillRequest?.skillId)
        assertEquals(SafetyOutcome.RequireConfirmation, result.plan.safetyDecision.outcome)
    }

    @Test
    fun clipboardActionInputUsesClipboardSkillAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "读取剪贴板",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals(SafetyOutcome.RequireConfirmation, result.plan.safetyDecision.outcome)
    }

    @Test
    fun skillFirstClipboardSummaryShareBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, result.plan.skillRequest?.skillId)
        assertEquals(3, result.plan.skillPlan?.steps?.size)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
        val pending = runtime.latestPendingConfirmation()
        requireNotNull(pending)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, pending.skillId)
        assertEquals(result.plan.skillPlan, pending.skillPlan)
        assertTrue(result.steps.any { step ->
            step is AgentStep.SkillPlanned &&
                step.plan?.request?.skillId == BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL
        })
    }

    @Test
    fun skillFirstClipboardContextBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "读取剪贴板",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
    }

    @Test
    fun skillFirstPlanStillUsesRegistryAndRejectsInvalidToolArguments() {
        val invalidSkillRuntime = object : SkillRuntime {
            private val manifest = SkillManifest(
                id = "invalid_email_skill",
                version = 1,
                title = "Invalid email",
                description = "Invalid direct skill for test",
                triggerExamples = listOf("invalid email"),
                requiredTools = listOf(MobileActionFunctions.COMPOSE_EMAIL),
                inputSchemaJson = """{"type":"object","additionalProperties":false}""",
                riskLevel = RiskLevel.MediumDraftOrNavigation,
            )

            override fun manifests(): List<SkillManifest> = listOf(manifest)

            override fun plan(input: String): SkillPlan =
                SkillPlan(
                    request = SkillRequest(
                        id = "invalid-skill-request",
                        skillId = manifest.id,
                        arguments = emptyMap(),
                        reason = input,
                    ),
                    manifest = manifest,
                    steps = listOf(
                        SkillStep.ToolStep(
                            id = "compose_email",
                            request = ToolRequest(
                                toolName = MobileActionFunctions.COMPOSE_EMAIL,
                                arguments = emptyMap(),
                                reason = "invalid direct skill",
                            ),
                            draft = ActionDraft(
                                functionName = MobileActionFunctions.COMPOSE_EMAIL,
                                title = "邮件草稿",
                                summary = "invalid direct skill",
                                parameters = emptyMap(),
                            ),
                        ),
                    ),
                )

            override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null
        }
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = invalidSkillRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "invalid email",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        require(result.plan is AgentPlan.RejectedTool)
        assertEquals(ToolStatus.Rejected, result.plan.result.status)
        assertTrue(result.plan.result.summary.contains("requires argument"))
        assertEquals(null, runtime.latestPendingConfirmation())
    }

    @Test
    fun clipboardObservationBuildsContinuationPromptAndRedactsTrace() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawClipboardText = "这是一段剪贴板文本"
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本（12 字符）",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "text" to rawClipboardText,
                    "truncated" to "false",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.steps.any { step ->
            step is AgentStep.ObservationDecided &&
                step.decision is AgentObservationDecision.ContinueWithModel
        })
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("这是一段剪贴板文本"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("读取剪贴板并总结"))
        assertEquals("[redacted]", observed.result.data["text"])
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["text"])
        assertTrue(!observed.steps.toString().contains(rawClipboardText))
        assertTrue(!auditSink.events.toString().contains(rawClipboardText))
    }

    @Test
    fun clipboardSummarySharePlansShareAfterLocalModelResult() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawClipboardText = "只应出现在 continuation prompt 的剪贴板原文"
        val modelSummary = "摘要：这段内容适合分享。"
        val planned = runtime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, planned.plan.skillRequest?.skillId)
        assertTrue(planned.plan.skillPlan?.steps?.size == 3)
        assertTrue(planned.steps.any { step ->
            step is AgentStep.SkillPlanned &&
                step.plan?.request?.skillId == BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL
        })
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "text" to rawClipboardText,
                    "truncated" to "false",
                ),
            ),
        )
        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)

        val modelObserved = runtime.observeModelResult(planned.run.id, modelSummary)

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val sharePlan = modelObserved.decision.plan
        assertEquals(MobileActionFunctions.SHARE_TEXT, sharePlan.request.toolName)
        assertEquals(modelSummary, sharePlan.request.arguments["text"])
        assertEquals(modelSummary, sharePlan.draft.parameters["text"])
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, sharePlan.skillRequest?.skillId)
        assertEquals(
            listOf(MobileActionFunctions.READ_CLIPBOARD, MobileActionFunctions.SHARE_TEXT),
            modelObserved.steps.filterIsInstance<AgentStep.ToolRequested>().map { it.request.toolName },
        )
        assertTrue(modelObserved.steps.toString().contains(modelSummary))
        assertTrue(!modelObserved.steps.toString().contains(rawClipboardText))
        assertTrue(!auditSink.events.toString().contains(rawClipboardText))
        assertTrue(!auditSink.events.toString().contains(modelSummary))

        runtime.confirmToolRequest(planned.run.id, sharePlan.request.id)
        val completed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = sharePlan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开系统分享面板",
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
    }

    @Test
    fun compositeSkillIgnoresOldRequestIdsAfterShareIsPendingOrExecuting() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(
                likelyAction = true,
                planningResult = readClipboardPlanningResult(),
            ),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        val readRequestId = planned.plan.request.id
        runtime.confirmToolRequest(planned.run.id, readRequestId)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = readRequestId,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "text" to "剪贴板原文",
                    "truncated" to "false",
                ),
            ),
        )
        requireNotNull(observed)
        val modelObserved = runtime.observeModelResult(planned.run.id, "摘要文本")
        requireNotNull(modelObserved)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val shareRequestId = modelObserved.decision.plan.request.id

        val stillAwaiting = runtime.confirmToolRequest(planned.run.id, readRequestId)

        assertEquals(AgentRunState.AwaitingUserConfirmation, stillAwaiting?.state)
        runtime.confirmToolRequest(planned.run.id, shareRequestId)
        val staleObservation = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = readRequestId,
                status = ToolStatus.Succeeded,
                summary = "旧剪贴板观察不应生效",
                data = mapOf("text" to "不应重新进入模型"),
            ),
        )

        assertNull(staleObservation)
    }

    @Test
    fun blankCompositeModelResultFailsWithoutPlanningShare() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(
                likelyAction = true,
                planningResult = readClipboardPlanningResult(),
            ),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "text" to "剪贴板原文",
                    "truncated" to "false",
                ),
            ),
        )
        requireNotNull(observed)

        val modelObserved = runtime.observeModelResult(planned.run.id, "   ")

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.Failed, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.Fail)
        assertTrue(modelObserved.steps.filterIsInstance<AgentStep.ToolRequested>().none {
            it.request.toolName == MobileActionFunctions.SHARE_TEXT
        })
    }

    @Test
    fun clipboardContinuationDoesNotCallObservationReplanner() {
        var replanCallCount = 0
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner {
                replanCallCount += 1
                error("clipboard continuation should win before replanning")
            },
        )
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "text" to "需要本地续写的文本",
                    "truncated" to "false",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(0, replanCallCount)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.steps.none { it is AgentStep.ToolRejected })
    }

    @Test
    fun toolObservationIsIgnoredBeforeConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = traceStore,
        )
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf("text" to "不应进入 trace 的内容"),
            ),
        )

        assertNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, traceStore.run(planned.run.id)?.state)
        assertTrue(traceStore.steps(planned.run.id).none { it is AgentStep.ToolObserved })
        assertTrue(!traceStore.steps(planned.run.id).toString().contains("不应进入 trace 的内容"))
    }

    @Test
    fun toolObservationWithUnknownRequestIdIsIgnored() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CLIPBOARD,
                        title = "读取剪贴板",
                        summary = "将读取当前剪贴板文本。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = traceStore,
        )
        val planned = runtime.runOnce(
            input = "读取剪贴板并总结",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = "unknown-request",
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf("text" to "不应进入 trace 的内容"),
            ),
        )

        assertNull(observed)
        assertEquals(AgentRunState.ExecutingTool, traceStore.run(planned.run.id)?.state)
        assertTrue(traceStore.steps(planned.run.id).none { it is AgentStep.ToolObserved })
        assertTrue(!traceStore.steps(planned.run.id).toString().contains("不应进入 trace 的内容"))
    }

    @Test
    fun confirmedToolResultIsObservedAndCompletesRun() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val executing = runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        assertEquals(AgentRunState.ExecutingTool, executing?.state)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开网页搜索",
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertTrue(observed.assistantMessage.contains("已打开网页搜索"))
        assertTrue(observed.steps.any { it is AgentStep.UserConfirmed })
        assertTrue(observed.steps.any { it is AgentStep.ToolObserved })
        assertTrue(observed.steps.any { step ->
            step is AgentStep.ObservationDecided &&
                step.decision == AgentObservationDecision.Complete
        })
        assertTrue(observed.steps.any { it is AgentStep.AssistantResponded })
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
                ToolAuditEventType.UserConfirmed,
                ToolAuditEventType.ToolObserved,
            ),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == null || event.toolName == MobileActionFunctions.WEB_SEARCH
        })
    }

    @Test
    fun restoredPendingConfirmationCanBeConfirmedObservedAndCleared() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-web" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(traceDao = dao),
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(restoredPending)
        assertEquals(planned.run.id, restoredPending.run.id)
        assertEquals(planned.plan.request.id, restoredPending.request.id)

        val executing = restoredRuntime.confirmToolRequest(
            runId = restoredPending.run.id,
            requestId = restoredPending.request.id,
        )

        assertEquals(AgentRunState.ExecutingTool, executing?.state)
        assertNull(restoredRuntime.latestPendingConfirmation())

        val observed = restoredRuntime.observeToolResult(
            runId = restoredPending.run.id,
            result = ToolResult(
                requestId = restoredPending.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开网页搜索",
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertNull(restoredRuntime.latestPendingConfirmation())
        assertTrue(observed.steps.any { it is AgentStep.UserConfirmed })
        assertTrue(observed.steps.any { it is AgentStep.ToolObserved })
    }

    @Test
    fun restoredClipboardSummaryPendingContinuesWithModelAndPlansShareConfirmation() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-clipboard" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, planned.plan.skillRequest?.skillId)

        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = RoomAgentTraceStore(traceDao = dao),
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(restoredPending)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, restoredPending.request.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, restoredPending.skillPlan?.request?.skillId)
        assertEquals(3, restoredPending.skillPlan?.steps?.size)

        val executing = restoredRuntime.confirmToolRequest(
            runId = restoredPending.run.id,
            requestId = restoredPending.request.id,
        )
        assertEquals(AgentRunState.ExecutingTool, executing?.state)

        val observed = restoredRuntime.observeToolResult(
            runId = restoredPending.run.id,
            result = ToolResult(
                requestId = restoredPending.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "text" to "剪贴板原文",
                    "truncated" to "false",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("剪贴板原文"))

        val modelObserved = restoredRuntime.observeModelResult(
            runId = restoredPending.run.id,
            text = "摘要：恢复后生成的分享内容",
        )

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val sharePlan = modelObserved.decision.plan
        assertEquals(MobileActionFunctions.SHARE_TEXT, sharePlan.request.toolName)
        assertEquals("摘要：恢复后生成的分享内容", sharePlan.request.arguments["text"])
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, sharePlan.skillRequest?.skillId)

        val nextPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(nextPending)
        assertEquals(sharePlan.request.id, nextPending.request.id)
        assertEquals(MobileActionFunctions.SHARE_TEXT, nextPending.request.toolName)
        assertEquals("摘要：恢复后生成的分享内容", nextPending.request.arguments["text"])
    }

    @Test
    fun successfulObservationCanPlanNextToolAndRequestConfirmationAgain() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val nextDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "搜索完成后继续打开 Wi-Fi 设置页。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )
        val nextRequest = ToolRequest(
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = nextDraft.summary,
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner { context ->
                if (
                    context.previousRequest.toolName == MobileActionFunctions.WEB_SEARCH &&
                    context.observedResult.status == ToolStatus.Succeeded
                ) {
                    AgentObservationReplan(
                        request = nextRequest,
                        draft = nextDraft,
                        plannedByModel = false,
                        fallbackReason = "test replan",
                    )
                } else {
                    null
                }
            },
        )
        val planned = runtime.runOnce(
            input = "先搜 Kotlin，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val replanned = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开网页搜索",
            ),
        )

        requireNotNull(replanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, replanned.run.state)
        require(replanned.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(nextRequest.id, replanned.decision.plan.request.id)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, replanned.decision.plan.request.toolName)
        assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, replanned.decision.plan.skillRequest?.skillId)
        assertEquals(
            listOf(
                MobileActionFunctions.WEB_SEARCH,
                MobileActionFunctions.OPEN_WIFI_SETTINGS,
            ),
            replanned.steps.filterIsInstance<AgentStep.ToolRequested>().map { it.request.toolName },
        )
        assertEquals(2, replanned.steps.filterIsInstance<AgentStep.UserConfirmationRequested>().size)
        assertTrue(replanned.steps.any { step ->
            step is AgentStep.ObservationDecided &&
                step.decision is AgentObservationDecision.PlanNextTool
        })
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
                ToolAuditEventType.UserConfirmed,
                ToolAuditEventType.ToolObserved,
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
            ),
            auditSink.events.map { it.eventType },
        )

        runtime.confirmToolRequest(planned.run.id, nextRequest.id)
        val completed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = nextRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已打开 Wi-Fi 设置页",
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
    }

    @Test
    fun replannedToolCannotReuseExistingRequestId() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner { context ->
                AgentObservationReplan(
                    request = context.previousRequest,
                    draft = ActionDraft(
                        functionName = context.previousRequest.toolName,
                        title = "重复请求",
                        summary = "不应复用已有 request id。",
                        parameters = context.previousRequest.arguments,
                        requiresConfirmation = true,
                    ),
                )
            },
        )
        val planned = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开网页搜索",
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertTrue(observed.decision.reason.contains("already exists"))
        assertTrue(observed.steps.any { step ->
            step is AgentStep.ModelPlanned && step.plan is AgentPlan.RejectedTool
        })
        assertTrue(observed.steps.any { it is AgentStep.ToolRejected })
    }

    @Test
    fun retryableToolFailureSchedulesSingleRetryThenFailsAfterLimit() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            maxToolRetryAttempts = 1,
        )
        val planned = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val retrying = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "浏览器临时不可用",
                retryable = true,
            ),
        )

        requireNotNull(retrying)
        assertEquals(AgentRunState.RetryingTool, retrying.run.state)
        require(retrying.decision is AgentObservationDecision.RetryTool)
        assertEquals(1, retrying.decision.attempt)
        assertEquals(planned.plan.request, retrying.retryRequest)
        assertEquals(1, retrying.retryAttempt)
        assertTrue(retrying.assistantMessage.contains("正在重试"))
        val retryStep = retrying.steps.filterIsInstance<AgentStep.ToolRetryScheduled>().single()
        assertEquals(planned.plan.request.id, retryStep.request.id)
        assertEquals(1, retryStep.attempt)
        assertTrue(auditSink.events.any { event ->
            event.eventType == ToolAuditEventType.ToolRetryScheduled &&
                event.requestId == planned.plan.request.id
        })

        val failed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "重试后仍不可用",
                retryable = true,
            ),
        )

        requireNotNull(failed)
        assertEquals(AgentRunState.Failed, failed.run.state)
        require(failed.decision is AgentObservationDecision.Fail)
        assertNull(failed.retryRequest)
        assertEquals(0, failed.retryAttempt)
        assertEquals(1, failed.steps.filterIsInstance<AgentStep.ToolRetryScheduled>().size)
        assertTrue(!failed.assistantMessage.contains("正在重试"))
    }

    @Test
    fun permissionDeniedToolFailureDoesNotScheduleAutomaticRetry() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val failed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "用户拒绝了所需权限",
                error = ToolError(ToolErrorCode.PermissionDenied, "用户拒绝了所需权限"),
                retryable = true,
            ),
        )

        requireNotNull(failed)
        assertEquals(AgentRunState.Failed, failed.run.state)
        require(failed.decision is AgentObservationDecision.Fail)
        assertNull(failed.retryRequest)
        assertEquals(0, failed.retryAttempt)
        assertTrue(failed.steps.none { it is AgentStep.ToolRetryScheduled })
    }

    @Test
    fun pendingToolPermissionDenialIsObservedWithoutEnteringExecutionState() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val failed = runtime.failPendingToolRequest(
            runId = planned.run.id,
            requestId = planned.plan.request.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "用户拒绝了所需权限",
                error = ToolError(ToolErrorCode.PermissionDenied, "用户拒绝了所需权限"),
                retryable = false,
            ),
        )

        requireNotNull(failed)
        assertEquals(AgentRunState.Failed, failed.run.state)
        require(failed.decision is AgentObservationDecision.Fail)
        assertTrue(failed.steps.any { it is AgentStep.UserConfirmed })
        assertTrue(failed.steps.any { step ->
            step is AgentStep.ToolObserved &&
                step.result.error?.code == ToolErrorCode.PermissionDenied
        })
        assertTrue(failed.steps.none { it is AgentStep.ToolRetryScheduled })
        assertNull(runtime.latestPendingConfirmation())
        assertTrue(auditSink.events.any { event ->
            event.eventType == ToolAuditEventType.ToolObserved &&
                event.status == ToolStatus.Failed
        })
    }

    @Test
    fun nonRetryableToolFailureFailsWithoutRetryStep() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val failed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Failed,
                summary = "找不到可处理的浏览器",
                retryable = false,
            ),
        )

        requireNotNull(failed)
        assertEquals(AgentRunState.Failed, failed.run.state)
        require(failed.decision is AgentObservationDecision.Fail)
        assertNull(failed.retryRequest)
        assertTrue(failed.steps.none { it is AgentStep.ToolRetryScheduled })
    }

    @Test
    fun cancelledToolRequestIsObservedWithoutExecution() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)

        val cancelled = runtime.cancelToolRequest(
            runId = planned.run.id,
            requestId = planned.plan.request.id,
        )

        requireNotNull(cancelled)
        assertEquals(AgentRunState.Cancelled, cancelled.run.state)
        assertEquals(AgentObservationDecision.Cancel, cancelled.decision)
        assertEquals(ToolStatus.Cancelled, cancelled.result.status)
        assertTrue(cancelled.steps.any { it is AgentStep.UserRejected })
        assertTrue(cancelled.steps.any { it is AgentStep.ToolObserved })
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
                ToolAuditEventType.UserCancelled,
                ToolAuditEventType.ToolObserved,
            ),
            auditSink.events.map { it.eventType },
        )
    }

    @Test
    fun invalidActionDraftIsRejectedBeforeConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.COMPOSE_EMAIL,
                        title = "邮件草稿",
                        summary = "将打开邮件 App 并填入草稿内容。",
                        parameters = mapOf("subject" to "Hi"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = true,
                fallbackReason = null,
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "帮我写邮件",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        require(result.plan is AgentPlan.RejectedTool)
        assertEquals(ToolStatus.Rejected, result.plan.result.status)
        assertTrue(result.plan.result.summary.contains("body"))
        assertTrue(result.steps.any { step ->
            step is AgentStep.ToolRejected &&
                step.result.status == ToolStatus.Rejected
        })
    }

    @Test
    fun actionPlannerAttachedSkillPlanMustSatisfyManifestSchemaBeforeConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = InvalidSkillRuntime(
                invalidForTool = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "Kotlin"),
            ),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "搜一下 Kotlin",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        require(result.plan is AgentPlan.RejectedTool)
        assertTrue(result.plan.result.summary.contains("Invalid skill plan"))
        assertTrue(result.plan.result.summary.contains("requires argument(s): input"))
        assertTrue(result.plan.result.summary.contains("does not accept argument(s): query"))
        assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
        assertNull(runtime.latestPendingConfirmation())
    }

    @Test
    fun replannedToolAttachedSkillPlanMustSatisfyManifestSchemaBeforeConfirmation() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.WEB_SEARCH,
                        title = "Web 搜索",
                        summary = "将在浏览器中搜索：Kotlin",
                        parameters = mapOf("query" to "Kotlin"),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val nextDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "搜索完成后继续打开 Wi-Fi 设置页。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )
        val nextRequest = ToolRequest(
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = nextDraft.summary,
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = InvalidSkillRuntime(
                invalidForTool = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                arguments = emptyMap(),
            ),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner {
                AgentObservationReplan(
                    request = nextRequest,
                    draft = nextDraft,
                    fallbackReason = "test replan",
                )
            },
        )
        val planned = runtime.runOnce(
            input = "先搜 Kotlin，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开网页搜索",
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        require(observed.decision is AgentObservationDecision.Fail)
        assertTrue(observed.decision.reason.contains("Invalid skill plan"))
        assertTrue(observed.steps.any { it is AgentStep.ToolRejected })
        assertTrue(observed.steps.filterIsInstance<AgentStep.UserConfirmationRequested>().size == 1)
        assertNull(runtime.latestPendingConfirmation())
    }

    private class RecordingActionRuntime(
        private val likelyAction: Boolean,
        private val planningResult: ActionPlanningResult = ActionPlanningResult(
            plan = ActionPlan(ActionPlanKind.NoAction),
            usedModel = false,
            fallbackReason = null,
        ),
    ) : ActionPlanningRuntime {
        var planCallCount: Int = 0
            private set

        override fun isLikelyAction(input: String): Boolean = likelyAction

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            planCallCount += 1
            return planningResult
        }
    }

    private fun readClipboardPlanningResult(): ActionPlanningResult =
        ActionPlanningResult(
            plan = ActionPlan(
                kind = ActionPlanKind.Draft,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.READ_CLIPBOARD,
                    title = "读取剪贴板",
                    summary = "将读取当前剪贴板文本。",
                    parameters = emptyMap(),
                    requiresConfirmation = true,
                ),
            ),
            usedModel = false,
            fallbackReason = "test fallback",
        )

    private class InvalidSkillRuntime(
        private val invalidForTool: String,
        private val arguments: Map<String, String>,
    ) : SkillRuntime {
        private val manifest = SkillManifest(
            id = "invalid_attached_skill",
            version = 1,
            title = "Invalid attached skill",
            description = "Invalid skill for schema contract tests",
            triggerExamples = listOf("invalid skill"),
            requiredTools = listOf(invalidForTool),
            inputSchemaJson = """
                {
                  "type": "object",
                  "required": ["input"],
                  "properties": {
                    "input": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  "additionalProperties": false
                }
            """.trimIndent(),
            riskLevel = RiskLevel.MediumDraftOrNavigation,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? {
            if (request.toolName != invalidForTool) return null
            return SkillPlan(
                request = SkillRequest(
                    id = "invalid-skill-request",
                    skillId = manifest.id,
                    arguments = arguments,
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(SkillStep.ToolStep(request, draft)),
            )
        }
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

    private class ThrowingMemoryIndex : MemoryIndex {
        override var enabled: Boolean = true

        override fun rebuild(messages: List<com.bytedance.zgx.pocketmind.ChatMessage>) = Unit

        override fun index(id: String, text: String) = Unit

        override fun search(query: String, topK: Int): List<MemoryHit> {
            error("memory unavailable")
        }

        override fun buildContext(hits: List<MemoryHit>): String =
            error("memory context unavailable")
    }
}

/*
 * Boundary cases to enable once the production API exposes them:
 * - maxSteps: inject a continuing tool/plan and assert the run ends in Failed with
 *   an AgentStep.Failed trace when the limit is reached.
 * - cancellation: expose cancel(runId) or coroutine cancellation and assert the run
 *   moves to Cancelled without executing a pending tool.
 */
