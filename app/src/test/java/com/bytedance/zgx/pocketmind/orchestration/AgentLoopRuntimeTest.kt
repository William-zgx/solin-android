package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlan
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningResult
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.action.MobileActionPlanner
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
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
import com.bytedance.zgx.pocketmind.tool.ToolError
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            skillRuntime = NoDirectPlanSkillRuntime(),
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
    fun skillFirstReminderBypassesActionPlannerAndRequestsConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
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
        assertEquals("15", result.plan.request.arguments["delayMinutes"])
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertTrue(result.steps.any { step ->
            step is AgentStep.SkillPlanned &&
                step.plan?.request?.skillId == BuiltInSkillRuntime.REMINDER_SKILL
        })
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.SCHEDULE_REMINDER &&
                event.skillId == BuiltInSkillRuntime.REMINDER_SKILL &&
                ToolPermission.SchedulesBackgroundWork in event.permissions &&
                ToolPermission.PostsNotification in event.permissions &&
                ToolPermission.RequiresAndroidRuntimePermission in event.permissions
        })
    }

    @Test
    fun skillFirstEnglishReminderBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "remind me in 1 hour to check build status",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, result.plan.request.toolName)
        assertEquals("60", result.plan.request.arguments["delayMinutes"])
        assertEquals("check build status", result.plan.request.arguments["title"])
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
    }

    @Test
    fun reminderTimingDiscussionFallsBackToAnswerWithoutConfirmation() {
        val input = "提醒我一下，“15 分钟后”是什么意思"
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = MobileActionPlanner().isLikelyAction(input),
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.SCHEDULE_REMINDER,
                        title = "should not be used",
                        summary = "should not be used",
                        parameters = mapOf(
                            "title" to "wrong",
                            "body" to "wrong",
                            "delayMinutes" to "15",
                        ),
                    ),
                ),
                usedModel = true,
                fallbackReason = null,
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = input,
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
        assertTrue(result.plan is AgentPlan.Answer)
        assertEquals(1, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertTrue(result.steps.none { it is AgentStep.UserConfirmationRequested })
        assertTrue(auditSink.events.isEmpty())
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
    fun skillFirstShareTextBypassesActionPlannerAndRequestsConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val shareText = "明天十点开会"

        val result = runtime.runOnce(
            input = "分享这段文字：$shareText",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.SHARE_TEXT, result.plan.request.toolName)
        assertEquals(shareText, result.plan.request.arguments["text"])
        assertEquals(BuiltInSkillRuntime.SHARE_TEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertTrue(auditSink.events.none { event -> event.summary.contains(shareText) })
    }

    @Test
    fun skillFirstDeviceSettingsBypassActionPlannerAndRequestConfirmation() {
        val cases = listOf(
            "打开 Wi-Fi 设置" to MobileActionFunctions.OPEN_WIFI_SETTINGS,
            "打开手电筒设置" to MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
        )

        cases.forEach { (input, toolName) ->
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertEquals(toolName, result.plan.request.toolName)
            assertTrue(result.plan.request.arguments.isEmpty())
            assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, result.plan.skillRequest?.skillId)
            assertEquals("skill-first", result.plan.fallbackReason)
            assertEquals(0, actionRuntime.isLikelyActionCallCount)
            assertEquals(0, actionRuntime.planCallCount)
            assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, runtime.latestPendingConfirmation()?.skillId)
            assertTrue(result.steps.any { step ->
                step is AgentStep.SkillPlanned &&
                    step.request.skillId == BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL
            })
        }
    }

    @Test
    fun skillFirstWebSearchBypassesActionPlannerAndRequestsConfirmation() {
        val cases = listOf(
            "搜一下 Kotlin 协程" to "Kotlin 协程",
            "look up Kotlin coroutines" to "Kotlin coroutines",
        )

        cases.forEach { (input, query) ->
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertEquals(MobileActionFunctions.WEB_SEARCH, result.plan.request.toolName)
            assertEquals(query, result.plan.request.arguments["query"])
            assertEquals(BuiltInSkillRuntime.INFORMATION_LOOKUP_SKILL, result.plan.skillRequest?.skillId)
            assertEquals("skill-first", result.plan.fallbackReason)
            assertEquals(0, actionRuntime.isLikelyActionCallCount)
            assertEquals(0, actionRuntime.planCallCount)
            assertEquals(BuiltInSkillRuntime.INFORMATION_LOOKUP_SKILL, runtime.latestPendingConfirmation()?.skillId)
        }
    }

    @Test
    fun skillFirstRecentMediaFilesBypassesActionPlannerAndRequestsConfirmation() {
        val cases = listOf(
            Triple("最近 3 张图片", "images", "3"),
            Triple("recent screenshots", "screenshots", null),
        )

        cases.forEach { (input, kind, maxCount) ->
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, result.plan.request.toolName)
            assertEquals(kind, result.plan.request.arguments["kind"])
            if (maxCount == null) {
                assertNull(result.plan.request.arguments["maxCount"])
            } else {
                assertEquals(maxCount, result.plan.request.arguments["maxCount"])
            }
            assertEquals(BuiltInSkillRuntime.RECENT_FILES_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
            assertEquals("skill-first", result.plan.fallbackReason)
            assertEquals(0, actionRuntime.isLikelyActionCallCount)
            assertEquals(0, actionRuntime.planCallCount)
            assertEquals(BuiltInSkillRuntime.RECENT_FILES_CONTEXT_SKILL, runtime.latestPendingConfirmation()?.skillId)
        }
    }

    @Test
    fun skillFirstHttpsDeepLinkBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "打开链接 https://example.com/path?q=agent",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.OPEN_DEEP_LINK, result.plan.request.toolName)
        assertEquals("https://example.com/path?q=agent", result.plan.request.arguments["uri"])
        assertEquals(BuiltInSkillRuntime.DEEP_LINK_NAVIGATION_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.DEEP_LINK_NAVIGATION_SKILL, runtime.latestPendingConfirmation()?.skillId)
    }

    @Test
    fun skillFirstForegroundAppBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "当前应用是什么",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_FOREGROUND_APP, result.plan.request.toolName)
        assertTrue(result.plan.request.arguments.isEmpty())
        assertEquals(BuiltInSkillRuntime.FOREGROUND_APP_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.FOREGROUND_APP_CONTEXT_SKILL, runtime.latestPendingConfirmation()?.skillId)
    }

    @Test
    fun skillFirstRecentNotificationsBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "最近 3 条通知",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS, result.plan.request.toolName)
        assertEquals("3", result.plan.request.arguments["maxCount"])
        assertEquals(BuiltInSkillRuntime.RECENT_NOTIFICATIONS_CONTEXT_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.RECENT_NOTIFICATIONS_CONTEXT_SKILL, runtime.latestPendingConfirmation()?.skillId)
    }

    @Test
    fun skillFirstContactLookupBypassesActionPlannerAndRequestsConfirmation() {
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )

        val result = runtime.runOnce(
            input = "查联系人 Alice",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
        require(result.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, result.plan.request.toolName)
        assertEquals("Alice", result.plan.request.arguments["query"])
        assertEquals(BuiltInSkillRuntime.CONTACT_LOOKUP_SKILL, result.plan.skillRequest?.skillId)
        assertEquals("skill-first", result.plan.fallbackReason)
        assertEquals(0, actionRuntime.isLikelyActionCallCount)
        assertEquals(0, actionRuntime.planCallCount)
        assertEquals(BuiltInSkillRuntime.CONTACT_LOOKUP_SKILL, runtime.latestPendingConfirmation()?.skillId)
    }

    @Test
    fun contactObservationRedactsPrivateTraceFields() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "查联系人 Alice",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val rawContactsJson = """[{"name":"Alice","phone":"+1 555 0100"}]"""
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已查询到 1 个联系人。",
                data = mapOf(
                    "toolName" to MobileActionFunctions.QUERY_CONTACTS,
                    "query" to "Alice",
                    "maxCount" to "5",
                    "contactCount" to "1",
                    "contactsJson" to rawContactsJson,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertTrue(observed.decision is AgentObservationDecision.Complete)
        assertEquals(null, observed.continuationPromptForModel)
        assertEquals("[redacted]", observed.result.data["query"])
        assertEquals("[redacted]", observed.result.data["contactsJson"])
        assertEquals("已读取联系人摘要", observed.result.summary)
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["contactsJson"])
        assertFalse(observed.steps.toString().contains("+1 555 0100"))
    }

    @Test
    fun skillFirstMapEmailAndCalendarBypassActionPlannerAndRequestConfirmation() {
        val cases = listOf(
            SkillFirstDraftCase(
                input = "查去机场的路线",
                toolName = MobileActionFunctions.SEARCH_MAPS,
                skillId = BuiltInSkillRuntime.MAP_SEARCH_SKILL,
                argumentName = "query",
                argumentValue = "机场的路线",
            ),
            SkillFirstDraftCase(
                input = "帮我写邮件：明天延期到周五",
                toolName = MobileActionFunctions.COMPOSE_EMAIL,
                skillId = BuiltInSkillRuntime.EMAIL_DRAFT_SKILL,
                argumentName = "body",
                argumentValue = "明天延期到周五",
            ),
            SkillFirstDraftCase(
                input = "帮我建个日程：周五评审",
                toolName = MobileActionFunctions.CREATE_CALENDAR_EVENT,
                skillId = BuiltInSkillRuntime.CALENDAR_DRAFT_SKILL,
                argumentName = "title",
                argumentValue = "周五评审",
            ),
        )

        cases.forEach { testCase ->
            val auditSink = InMemoryToolAuditSink()
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                auditSink = auditSink,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = testCase.input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.AwaitingUserConfirmation, result.run.state)
            require(result.plan is AgentPlan.UseTool)
            assertEquals(testCase.toolName, result.plan.request.toolName)
            assertEquals(testCase.argumentValue, result.plan.request.arguments[testCase.argumentName])
            assertEquals(testCase.skillId, result.plan.skillRequest?.skillId)
            assertEquals("skill-first", result.plan.fallbackReason)
            assertEquals(0, actionRuntime.isLikelyActionCallCount)
            assertEquals(0, actionRuntime.planCallCount)
            if (testCase.toolName != MobileActionFunctions.SEARCH_MAPS) {
                assertTrue(auditSink.events.none { event -> event.summary.contains(testCase.argumentValue) })
            }
        }
    }

    @Test
    fun parameterizedSkillFirstDiscussionInputsRemainAnswersWithoutToolAudit() {
        val inputs = listOf(
            "查到这个错误原因了吗？",
            "How do I navigate to a Compose screen?",
            "不要发邮件，只帮我总结这段话",
            "Do not send email; summarize only",
            "add event listener to the button",
            "Wi-Fi 设置页面怎么设计",
            "不要打开 Wi-Fi 设置，只解释一下",
            "Do not open Wi-Fi settings; explain only",
            "手电筒 API 怎么用",
            "打开手电筒",
            "网页搜索是什么",
            "不要搜索 Kotlin，只解释一下",
            "what is web search",
            "查一下这个错误原因了吗？",
            "识别最近图片文字",
            "识别最近截图文字",
            "查询最近5个文档",
            "最近文件",
            "https://example.com/path",
            "解释 https://example.com/path 是什么",
            "how do I open https://example.com/path",
            "不要打开 https://example.com/path",
            "打开链接 http://example.com/path",
            "前台服务限制是什么",
            "current app architecture",
            "how do I implement current app state",
            "notification",
            "notifications",
            "recent app notifications",
            "notification permission",
            "notification channel",
            "push notification",
            "系统通知",
            "通知栏",
            "联系人",
            "contact",
            "查询联系人",
            "联系人权限",
            "ContactsContract 怎么用",
            "search contacts API",
            "不要查联系人 Alice",
            "do not search contacts for Alice",
            "新建联系人 Alice",
        )

        inputs.forEach { input ->
            val auditSink = InMemoryToolAuditSink()
            val actionRuntime = RecordingActionRuntime(likelyAction = false)
            val runtime = AgentLoopRuntime(
                memoryIndex = MemoryRepository(),
                actionPlanningRuntime = actionRuntime,
                auditSink = auditSink,
                traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            )

            val result = runtime.runOnce(
                input = input,
                installedCapabilities = setOf(ModelCapability.Chat),
                memoryEnabled = false,
            )

            assertEquals(AgentRunState.GeneratingAnswer, result.run.state)
            assertTrue(result.plan is AgentPlan.Answer)
            assertEquals(null, runtime.latestPendingConfirmation())
            assertTrue(auditSink.events.isEmpty())
        }
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
    fun recentScreenshotOcrObservationBuildsLocalPromptAndRedactsTrace() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                        title = "读取最近截图 OCR",
                        summary = "将读取最近 1 张截图的像素并在本地提取 OCR 文本。",
                        parameters = mapOf("maxCount" to "1"),
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
        val rawOcrText = "截图里的私密验证码 123456"
        val planned = runtime.runOnce(
            input = "识别最近1张截图文字并总结",
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
                summary = "已从最近截图提取 14 个字符的本地 OCR 摘录。",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                    "privacy" to "LocalOnly",
                    "ocrText" to rawOcrText,
                    "truncated" to "false",
                    "ocrTextIncluded" to "true",
                    "rawPayloadIncluded" to "false",
                    "metadataPolicy" to "ocr_text_local_only_no_uri_path_or_pixels_persisted",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawOcrText))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("不是当前屏幕捕获"))
        assertEquals("[redacted]", observed.result.data["ocrText"])
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["ocrText"])
        assertTrue(!observed.steps.toString().contains(rawOcrText))
        assertTrue(!auditSink.events.toString().contains(rawOcrText))
    }

    @Test
    fun recentImageOcrObservationBuildsLocalPromptAndRedactsTrace() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                        title = "读取最近图片 OCR",
                        summary = "将扫描最近图片并在本地提取 OCR 文本。",
                        parameters = mapOf("maxCount" to "3"),
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
        val rawOcrText = "照片里的私密订单号 ABC123"
        val planned = runtime.runOnce(
            input = "识别最近图片文字并总结",
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
                summary = "已从最近图片提取 18 个字符的本地 OCR 摘录。",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                    "privacy" to "LocalOnly",
                    "ocrText" to rawOcrText,
                    "truncated" to "false",
                    "ocrTextIncluded" to "true",
                    "rawPayloadIncluded" to "false",
                    "metadataPolicy" to "ocr_text_local_only_no_uri_path_or_pixels_persisted",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawOcrText))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("不是当前屏幕捕获"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("最近图片 OCR 文本"))
        assertEquals("[redacted]", observed.result.data["ocrText"])
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["ocrText"])
        assertTrue(!observed.steps.toString().contains(rawOcrText))
        assertTrue(!auditSink.events.toString().contains(rawOcrText))
    }

    @Test
    fun currentScreenTextObservationBuildsLocalPromptAndRedactsTrace() {
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                        title = "读取当前屏幕文本",
                        summary = "将读取当前屏幕的可访问文本快照。",
                        parameters = mapOf("maxChars" to "1200"),
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
        val rawScreenText = "当前屏幕里的私密验证码 654321"
        val planned = runtime.runOnce(
            input = "总结当前屏幕文字",
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
                summary = "已读取当前屏幕 17 个字符的可访问文本快照。",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                    "privacy" to "LocalOnly",
                    "requiresLocalModel" to "true",
                    "screenText" to rawScreenText,
                    "truncated" to "false",
                    "screenTextIncluded" to "true",
                    "rawTreeIncluded" to "false",
                    "metadataPolicy" to "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawScreenText))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("Accessibility"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("不是截图捕获"))
        assertEquals("[redacted]", observed.result.data["screenText"])
        val toolObserved = observed.steps.filterIsInstance<AgentStep.ToolObserved>().last()
        assertEquals("[redacted]", toolObserved.result.data["screenText"])
        assertTrue(!observed.steps.toString().contains(rawScreenText))
        assertTrue(!auditSink.events.toString().contains(rawScreenText))
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
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("摘要剪贴板内容"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("适合分享的简短摘要"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawClipboardText))

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
    fun modelStepOutputBindsToDependentToolStepAndRequestsConfirmation() {
        val customSkillRuntime = CustomClipboardTransformSkillRuntime()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = customSkillRuntime,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val modelText = "自定义 Skill 生成的可分享文本"
        val planned = runtime.runOnce(
            input = "custom clipboard transform",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(CustomClipboardTransformSkillRuntime.SKILL_ID, planned.plan.skillRequest?.skillId)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, planned.plan.request.toolName)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf(
                    "text" to "不应持久化的剪贴板原文",
                    "truncated" to "false",
                ),
            ),
        )
        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("转换剪贴板内容"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("转换用户确认读取的剪贴板内容"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("不应持久化的剪贴板原文"))

        val modelObserved = runtime.observeModelResult(planned.run.id, modelText)

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val sharePlan = modelObserved.decision.plan
        assertEquals(MobileActionFunctions.SHARE_TEXT, sharePlan.request.toolName)
        assertEquals(modelText, sharePlan.request.arguments["text"])
        assertEquals(modelText, sharePlan.draft.parameters["text"])
        assertEquals(CustomClipboardTransformSkillRuntime.SKILL_ID, sharePlan.skillRequest?.skillId)
        assertEquals("skill model step", sharePlan.fallbackReason)
        assertTrue(modelObserved.steps.any { step ->
            step is AgentStep.SafetyChecked &&
                step.decision.outcome == SafetyOutcome.RequireConfirmation
        })
        assertTrue(modelObserved.steps.any { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.id == sharePlan.request.id
        })
    }

    @Test
    fun ocrSkillModelStepTakesPrecedenceOverPrivateReadFallbackPrompt() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = OcrModelSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val rawOcrText = "截图 OCR 里的本地待办"
        val planned = runtime.runOnce(
            input = "ocr model skill",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, planned.plan.request.toolName)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取最近截图 OCR 摘录",
                data = mapOf(
                    "ocrText" to rawOcrText,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationRequiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("整理截图 OCR"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("只保留 OCR 里的待办线索"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains(rawOcrText))
        assertFalse(observed.continuationPromptForModel.orEmpty().contains("不是当前屏幕捕获"))
        assertEquals("[redacted]", observed.result.data["ocrText"])
    }

    @Test
    fun localOnlyToolResultMetadataForcesGenericModelContinuationLocal() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = LocalOnlyResultSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "local only contacts skill",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, planned.plan.request.toolName)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)

        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "联系人 Alice：仅本地摘要",
                data = mapOf(
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)
        assertTrue(observed.decision.requiresLocalModel)
        assertTrue(observed.continuationRequiresLocalModel)
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("整理联系人本地结果"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("联系人 Alice：仅本地摘要"))
    }

    @Test
    fun modelStepBindingRejectsMissingOutputBeforeConfirmation() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = CustomClipboardTransformSkillRuntime(shareBinding = "custom_model.missingOutput"),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "custom clipboard transform",
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
                    "text" to "不应持久化的剪贴板原文",
                    "truncated" to "false",
                ),
            ),
        )
        requireNotNull(observed)

        val modelObserved = runtime.observeModelResult(planned.run.id, "模型输出")

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.Failed, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.Fail)
        assertTrue(modelObserved.decision.reason.contains("Missing model output binding"))
        assertTrue(modelObserved.steps.any { it is AgentStep.ToolRejected })
        assertTrue(modelObserved.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.SHARE_TEXT
        })
    }

    @Test
    fun modelStepBindingCannotDirectlyExposePrivateToolOutputToShare() {
        val auditSink = InMemoryToolAuditSink()
        val secretClipboardText = "secret clipboard text should not leave the model boundary"
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            skillRuntime = CustomClipboardTransformSkillRuntime(shareBinding = "custom_read.text"),
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "custom clipboard transform",
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
                    "text" to secretClipboardText,
                    "truncated" to "false",
                ),
            ),
        )
        requireNotNull(observed)

        val modelObserved = runtime.observeModelResult(planned.run.id, "模型安全摘要")

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.Failed, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.Fail)
        assertTrue(modelObserved.decision.reason.contains("private tool output cannot be bound directly"))
        assertTrue(modelObserved.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.SHARE_TEXT
        })
        assertNull(runtime.latestPendingConfirmation())
        assertTrue(!modelObserved.steps.toString().contains(secretClipboardText))
        assertTrue(!auditSink.events.toString().contains(secretClipboardText))
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
    fun reminderObservationSurfacesBoundedRecoveryHint() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "提醒我 10 分钟后喝水",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, planned.plan.request.toolName)

        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val observed = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已安排 10000 触发的后台提醒",
                data = mapOf(
                    "toolName" to MobileActionFunctions.SCHEDULE_REMINDER,
                    "taskId" to "task-1",
                    "taskStatus" to "Scheduled",
                    "triggerAtMillis" to "10000",
                    "recoveryToolName" to MobileActionFunctions.CANCEL_REMINDER,
                    "recoveryTaskId" to "task-1",
                    "title" to "喝水",
                    "body" to "提醒我喝水",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        val recoveryAction = observed.recoveryAction
        requireNotNull(recoveryAction)
        assertEquals(planned.plan.request.id, recoveryAction.sourceRequestId)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, recoveryAction.sourceToolName)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, recoveryAction.request.toolName)
        assertEquals(mapOf("taskId" to "task-1"), recoveryAction.request.arguments)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, recoveryAction.draft.functionName)
        assertTrue(observed.assistantMessage.contains("如需撤销该提醒"))
        assertTrue(observed.assistantMessage.contains(MobileActionFunctions.CANCEL_REMINDER))
        assertTrue(observed.assistantMessage.contains("taskId=task-1"))
        assertFalse(observed.assistantMessage.contains("提醒我喝水"))
        assertTrue(observed.steps.any { step ->
            step is AgentStep.AssistantResponded &&
                step.text.contains("taskId=task-1") &&
                !step.text.contains("提醒我喝水")
        })
    }

    @Test
    fun reminderRecoveryActionRequestsAuditedCancelConfirmation() {
        val auditSink = InMemoryToolAuditSink()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val action = AgentRecoveryAction(
            sourceRequestId = "request-reminder",
            sourceToolName = MobileActionFunctions.SCHEDULE_REMINDER,
            request = ToolRequest(
                id = "request-recovery",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "task-1"),
                reason = "unsafe source reason should be normalized",
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.CANCEL_REMINDER,
                title = "unsafe title",
                summary = "unsafe reminder body: 喝水",
                parameters = mapOf("taskId" to "task-1"),
                requiresConfirmation = true,
            ),
        )

        val requested = runtime.requestRecoveryAction(action)

        requireNotNull(requested)
        assertEquals(AgentRunState.AwaitingUserConfirmation, requested.run.state)
        require(requested.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, requested.plan.request.toolName)
        assertEquals(mapOf("taskId" to "task-1"), requested.plan.request.arguments)
        assertEquals("撤销提醒", requested.plan.draft.title)
        assertEquals("将取消提醒任务：task-1", requested.plan.draft.summary)
        assertFalse(requested.plan.draft.summary.contains("喝水"))
        val pending = runtime.latestPendingConfirmation()
        requireNotNull(pending)
        assertEquals(requested.run.id, pending.run.id)
        assertEquals("request-recovery", pending.request.id)
        assertEquals(
            listOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ConfirmationRequested),
            auditSink.events.map { it.eventType },
        )
        assertTrue(auditSink.events.all { event ->
            event.toolName == MobileActionFunctions.CANCEL_REMINDER &&
                event.riskLevel == RiskLevel.MediumDraftOrNavigation &&
                event.permissions.isEmpty()
        })

        val executing = runtime.confirmToolRequest(requested.run.id, requested.plan.request.id)
        requireNotNull(executing)
        assertEquals(AgentRunState.ExecutingTool, executing.state)
        val observed = runtime.observeToolResult(
            runId = requested.run.id,
            result = ToolResult(
                requestId = requested.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已取消后台任务：task-1",
                data = mapOf(
                    "toolName" to MobileActionFunctions.CANCEL_REMINDER,
                    "taskId" to "task-1",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(
            listOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
                ToolAuditEventType.UserConfirmed,
                ToolAuditEventType.ToolObserved,
            ),
            auditSink.events.map { it.eventType },
        )
    }

    @Test
    fun reminderObservationIgnoresUnsafeRecoveryMetadata() {
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        )
        val planned = runtime.runOnce(
            input = "提醒我 10 分钟后喝水",
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
                summary = "已安排 10000 触发的后台提醒",
                data = mapOf(
                    "toolName" to MobileActionFunctions.SCHEDULE_REMINDER,
                    "recoveryToolName" to MobileActionFunctions.CANCEL_REMINDER,
                    "recoveryTaskId" to "token=secret",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(null, observed.recoveryAction)
        assertFalse(observed.assistantMessage.contains("如需撤销"))
        assertFalse(observed.assistantMessage.contains("token=secret"))
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
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("摘要剪贴板内容"))
        assertTrue(observed.continuationPromptForModel.orEmpty().contains("总结剪贴板并分享"))
        assertFalse(observed.continuationPromptForModel.orEmpty().contains("[redacted]"))

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
    fun restoredClipboardSummarySharePendingIgnoresOldReadRequestAndCompletesShare() {
        val dao = FakeAgentTraceDao()
        val auditSink = InMemoryToolAuditSink()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-restored-share" },
            ),
        )
        val rawClipboardText = "重启后不应重新进入 trace 的剪贴板原文"
        val modelSummary = "摘要：恢复后可分享的安全内容"
        val planned = initialRuntime.runOnce(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        val readRequestId = planned.plan.request.id
        initialRuntime.confirmToolRequest(planned.run.id, readRequestId)
        val readObserved = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = readRequestId,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "text" to rawClipboardText,
                    "truncated" to "false",
                ),
            ),
        )
        requireNotNull(readObserved)
        assertEquals(AgentRunState.GeneratingAnswer, readObserved.run.state)

        val modelObserved = initialRuntime.observeModelResult(planned.run.id, modelSummary)

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        val shareRequestId = modelObserved.decision.plan.request.id
        assertEquals(MobileActionFunctions.SHARE_TEXT, modelObserved.decision.plan.request.toolName)
        assertEquals(modelSummary, modelObserved.decision.plan.request.arguments["text"])
        val readConfirmCountBeforeRestart = dao.steps(planned.run.id).count { step ->
            step.type == "UserConfirmed" && step.json.contains(readRequestId)
        }

        val restoredTraceStore = RoomAgentTraceStore(
            traceDao = dao,
            clockMillis = { 2_000L },
        )
        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = restoredTraceStore,
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(restoredPending)
        assertEquals(planned.run.id, restoredPending.run.id)
        assertEquals(shareRequestId, restoredPending.request.id)
        assertEquals(MobileActionFunctions.SHARE_TEXT, restoredPending.request.toolName)
        assertEquals(modelSummary, restoredPending.request.arguments["text"])
        assertEquals(modelSummary, restoredPending.draft.parameters["text"])
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, restoredPending.skillId)
        assertEquals(false, restoredPending.plannedByModel)
        assertEquals("skill model step", restoredPending.fallbackReason)
        val restoredSkillPlan = requireNotNull(restoredPending.skillPlan)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, restoredSkillPlan.request.skillId)
        assertEquals(3, restoredSkillPlan.steps.size)
        val restoredShareStep = restoredSkillPlan.steps[2] as? SkillStep.ToolStep
        requireNotNull(restoredShareStep)
        assertEquals(MobileActionFunctions.SHARE_TEXT, restoredShareStep.request.toolName)
        assertEquals(mapOf("text" to "summarize_clipboard.shareText"), restoredShareStep.argumentBindings)
        assertTrue(!restoredTraceStore.clearPendingConfirmation(planned.run.id, readRequestId))
        assertEquals(shareRequestId, restoredRuntime.latestPendingConfirmation()?.request?.id)

        val staleConfirm = restoredRuntime.confirmToolRequest(planned.run.id, readRequestId)

        assertEquals(AgentRunState.AwaitingUserConfirmation, staleConfirm?.state)
        val stillPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(stillPending)
        assertEquals(shareRequestId, stillPending.request.id)
        assertEquals(
            readConfirmCountBeforeRestart,
            dao.steps(planned.run.id).count { step ->
                step.type == "UserConfirmed" && step.json.contains(readRequestId)
            },
        )
        val staleObservationWhileAwaiting = restoredRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = readRequestId,
                status = ToolStatus.Succeeded,
                summary = "旧剪贴板观察不应生效",
                data = mapOf("text" to "不应重新进入模型"),
            ),
        )
        assertNull(staleObservationWhileAwaiting)
        assertEquals(shareRequestId, restoredRuntime.latestPendingConfirmation()?.request?.id)

        val executing = restoredRuntime.confirmToolRequest(planned.run.id, shareRequestId)

        assertEquals(AgentRunState.ExecutingTool, executing?.state)
        assertNull(restoredRuntime.latestPendingConfirmation())
        val staleObservationWhileExecuting = restoredRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = readRequestId,
                status = ToolStatus.Succeeded,
                summary = "执行分享时旧剪贴板观察仍不应生效",
                data = mapOf("text" to "仍不应重新进入模型"),
            ),
        )
        assertNull(staleObservationWhileExecuting)

        val completed = restoredRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = shareRequestId,
                status = ToolStatus.Succeeded,
                summary = "已打开系统分享面板",
            ),
        )

        requireNotNull(completed)
        assertEquals(AgentRunState.Completed, completed.run.state)
        assertEquals(AgentObservationDecision.Complete, completed.decision)
        assertNull(restoredRuntime.latestPendingConfirmation())
        assertTrue(dao.pendingConfirmations().isEmpty())
        assertTrue(completed.steps.any { it is AgentStep.UserConfirmed && it.requestId == shareRequestId })
        assertTrue(completed.steps.any { it is AgentStep.ToolObserved && it.result.requestId == shareRequestId })
        assertTrue(!completed.steps.toString().contains(rawClipboardText))
        val persistedTrace = dao.steps(planned.run.id).joinToString("\n") { step ->
            "${step.summary}\n${step.json}"
        }
        assertTrue(!persistedTrace.contains(rawClipboardText))
        assertTrue(!persistedTrace.contains(modelSummary))
        assertTrue(!auditSink.events.toString().contains(rawClipboardText))
        assertTrue(!auditSink.events.toString().contains(modelSummary))
    }

    @Test
    fun restoredMultiModelSkillPendingContinuesToNextModelAndToolConfirmation() {
        val dao = FakeAgentTraceDao()
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val initialRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = MultiModelSkillRuntime(),
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 1_000L },
                runIdFactory = { "run-multi-model-skill" },
            ),
        )
        val planned = initialRuntime.runOnce(
            input = "multi model skill",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(planned.plan is AgentPlan.UseTool)
        assertEquals(MobileActionFunctions.WEB_SEARCH, planned.plan.request.toolName)

        initialRuntime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        val searchObserved = initialRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "搜索结果摘要",
            ),
        )
        requireNotNull(searchObserved)
        assertEquals(AgentRunState.GeneratingAnswer, searchObserved.run.state)
        require(searchObserved.decision is AgentObservationDecision.ContinueWithModel)
        assertFalse(searchObserved.decision.requiresLocalModel)
        assertFalse(searchObserved.continuationRequiresLocalModel)
        assertTrue(searchObserved.continuationPromptForModel.orEmpty().contains("整理搜索结果"))
        assertTrue(searchObserved.continuationPromptForModel.orEmpty().contains("搜索结果摘要"))

        val sharePlanned = initialRuntime.observeModelResult(
            runId = planned.run.id,
            text = "可分享的搜索摘要",
        )
        requireNotNull(sharePlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, sharePlanned.run.state)
        require(sharePlanned.decision is AgentObservationDecision.PlanNextTool)
        val shareRequest = sharePlanned.decision.plan.request
        assertEquals(MobileActionFunctions.SHARE_TEXT, shareRequest.toolName)
        assertEquals("可分享的搜索摘要", shareRequest.arguments["text"])

        val restoredRuntime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = MultiModelSkillRuntime(),
            traceStore = RoomAgentTraceStore(
                traceDao = dao,
                clockMillis = { 2_000L },
            ),
        )
        val restoredPending = restoredRuntime.latestPendingConfirmation()
        requireNotNull(restoredPending)
        assertEquals(shareRequest.id, restoredPending.request.id)
        assertEquals(MobileActionFunctions.SHARE_TEXT, restoredPending.request.toolName)
        assertEquals(MultiModelSkillRuntime.SKILL_ID, restoredPending.skillId)

        restoredRuntime.confirmToolRequest(planned.run.id, shareRequest.id)
        val shareObserved = restoredRuntime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = shareRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已打开系统分享面板",
            ),
        )
        requireNotNull(shareObserved)
        assertEquals(AgentRunState.GeneratingAnswer, shareObserved.run.state)
        require(shareObserved.decision is AgentObservationDecision.ContinueWithModel)
        assertFalse(shareObserved.decision.requiresLocalModel)
        assertFalse(shareObserved.continuationRequiresLocalModel)
        assertTrue(shareObserved.continuationPromptForModel.orEmpty().contains("决定是否打开设置"))
        assertTrue(shareObserved.continuationPromptForModel.orEmpty().contains("已打开系统分享面板"))

        val wifiPlanned = restoredRuntime.observeModelResult(
            runId = planned.run.id,
            text = "继续打开设置",
        )

        requireNotNull(wifiPlanned)
        assertEquals(AgentRunState.AwaitingUserConfirmation, wifiPlanned.run.state)
        require(wifiPlanned.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, wifiPlanned.decision.plan.request.toolName)
        assertEquals(MultiModelSkillRuntime.SKILL_ID, wifiPlanned.decision.plan.skillRequest?.skillId)
        val persistedTrace = dao.steps(planned.run.id).joinToString("\n") { step ->
            "${step.summary}\n${step.json}"
        }
        assertFalse(persistedTrace.contains("可分享的搜索摘要"))
        assertFalse(persistedTrace.contains("继续打开设置"))
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
    fun unverifiedExternalLaunchDoesNotAutoPlanNextTool() {
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
            summary = "不应自动规划。",
            parameters = emptyMap(),
            requiresConfirmation = true,
        )
        var replanCallCount = 0
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            auditSink = auditSink,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = AgentObservationReplanner {
                replanCallCount += 1
                AgentObservationReplan(
                    request = ToolRequest(
                        toolName = nextDraft.functionName,
                        reason = nextDraft.summary,
                    ),
                    draft = nextDraft,
                    fallbackReason = "should not run",
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
                data = mapOf(
                    "completionState" to "ExternalActivityOpened",
                    "completionVerified" to "false",
                    "externalOutcome" to "Unknown",
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Completed, observed.run.state)
        assertEquals(AgentObservationDecision.Complete, observed.decision)
        assertEquals(0, replanCallCount)
        assertTrue(observed.assistantMessage.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX))
        assertEquals(1, observed.steps.filterIsInstance<AgentStep.ToolRequested>().size)
        assertTrue(observed.steps.none { step ->
            step is AgentStep.UserConfirmationRequested &&
                step.request.toolName == MobileActionFunctions.OPEN_WIFI_SETTINGS
        })
        val observedAudit = auditSink.events.last { event ->
            event.eventType == ToolAuditEventType.ToolObserved
        }
        assertTrue(observedAudit.summary.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX))
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
            skillRuntime = NoDirectPlanSkillRuntime(),
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

    private data class SkillFirstDraftCase(
        val input: String,
        val toolName: String,
        val skillId: String,
        val argumentName: String,
        val argumentValue: String,
    )

    private class NoDirectPlanSkillRuntime : SkillRuntime {
        private val delegate = BuiltInSkillRuntime()

        override fun manifests(): List<SkillManifest> = delegate.manifests()

        override fun plan(input: String): SkillPlan? = null

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? =
            delegate.plan(input, draft, request)
    }

    private class RecordingActionRuntime(
        private val likelyAction: Boolean,
        private val planningResult: ActionPlanningResult = ActionPlanningResult(
            plan = ActionPlan(ActionPlanKind.NoAction),
            usedModel = false,
            fallbackReason = null,
        ),
    ) : ActionPlanningRuntime {
        var isLikelyActionCallCount: Int = 0
            private set
        var planCallCount: Int = 0
            private set

        override fun isLikelyAction(input: String): Boolean {
            isLikelyActionCallCount += 1
            return likelyAction
        }

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

    private class CustomClipboardTransformSkillRuntime(
        private val shareBinding: String = "custom_model.shareText",
    ) : SkillRuntime {
        private val manifest = SkillManifest(
            id = SKILL_ID,
            version = 1,
            title = "Custom clipboard transform",
            description = "Test-only composite skill",
            triggerExamples = listOf("custom clipboard transform"),
            requiredTools = listOf(MobileActionFunctions.READ_CLIPBOARD, MobileActionFunctions.SHARE_TEXT),
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
            riskLevel = RiskLevel.HighExternalSend,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan {
            val readDraft = ActionDraft(
                functionName = MobileActionFunctions.READ_CLIPBOARD,
                title = "读取剪贴板",
                summary = "将读取当前剪贴板文本。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            val readRequest = ToolRequest(
                id = "custom-read-request",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = readDraft.summary,
            )
            val shareDraft = ActionDraft(
                functionName = MobileActionFunctions.SHARE_TEXT,
                title = "分享转换结果",
                summary = "将分享模型转换后的文本。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            val shareRequest = ToolRequest(
                id = "custom-share-request",
                toolName = MobileActionFunctions.SHARE_TEXT,
                reason = shareDraft.summary,
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "custom-skill-request",
                    skillId = SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(
                    SkillStep.ToolStep(
                        id = "custom_read",
                        request = readRequest,
                        draft = readDraft,
                    ),
                    SkillStep.ModelStep(
                        id = "custom_model",
                        dependsOn = listOf("custom_read"),
                        title = "转换剪贴板内容",
                        instruction = "转换用户确认读取的剪贴板内容。",
                        inputBindings = mapOf("clipboardText" to "custom_read.text"),
                        outputKey = "shareText",
                    ),
                    SkillStep.ToolStep(
                        id = "custom_share",
                        dependsOn = listOf("custom_model"),
                        request = shareRequest,
                        draft = shareDraft,
                        argumentBindings = mapOf("text" to shareBinding),
                    ),
                ),
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val SKILL_ID = "custom_clipboard_transform_skill"
        }
    }

    private class OcrModelSkillRuntime : SkillRuntime {
        private val manifest = SkillManifest(
            id = SKILL_ID,
            version = 1,
            title = "OCR model skill",
            description = "Test-only OCR model skill",
            triggerExamples = listOf("ocr model skill"),
            requiredTools = listOf(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR),
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

        override fun plan(input: String): SkillPlan? {
            if (input != "ocr model skill") return null
            val draft = ActionDraft(
                functionName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                title = "读取截图 OCR",
                summary = "将读取最近截图并提取 OCR。",
                parameters = mapOf("maxCount" to "1"),
                requiresConfirmation = true,
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "ocr-skill-request",
                    skillId = SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(
                    SkillStep.ToolStep(
                        id = "ocr_read",
                        request = ToolRequest(
                            id = "ocr-read-request",
                            toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                            arguments = draft.parameters,
                            reason = draft.summary,
                        ),
                        draft = draft,
                    ),
                    SkillStep.ModelStep(
                        id = "summarize_ocr",
                        dependsOn = listOf("ocr_read"),
                        title = "整理截图 OCR",
                        instruction = "只保留 OCR 里的待办线索。",
                        inputBindings = mapOf("ocrText" to "ocr_read.ocrText"),
                        outputKey = "summary",
                        keepsSensitiveInputLocal = false,
                    ),
                ),
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val SKILL_ID = "ocr_model_skill"
        }
    }

    private class LocalOnlyResultSkillRuntime : SkillRuntime {
        private val manifest = SkillManifest(
            id = SKILL_ID,
            version = 1,
            title = "Local only result skill",
            description = "Test-only local-only result skill",
            triggerExamples = listOf("local only contacts skill"),
            requiredTools = listOf(MobileActionFunctions.QUERY_CONTACTS),
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
            riskLevel = RiskLevel.LowReadOnly,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan? {
            if (input != "local only contacts skill") return null
            val draft = ActionDraft(
                functionName = MobileActionFunctions.QUERY_CONTACTS,
                title = "查询联系人",
                summary = "将读取联系人摘要。",
                parameters = mapOf("query" to "Alice"),
                requiresConfirmation = true,
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "local-only-skill-request",
                    skillId = SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(
                    SkillStep.ToolStep(
                        id = "contacts",
                        request = ToolRequest(
                            id = "local-only-contacts-request",
                            toolName = MobileActionFunctions.QUERY_CONTACTS,
                            arguments = draft.parameters,
                            reason = draft.summary,
                        ),
                        draft = draft,
                    ),
                    SkillStep.ModelStep(
                        id = "summarize_contacts",
                        dependsOn = listOf("contacts"),
                        title = "整理联系人本地结果",
                        instruction = "根据联系人摘要回答用户。",
                        inputBindings = mapOf("contactSummary" to "contacts.summary"),
                        outputKey = "answer",
                        keepsSensitiveInputLocal = false,
                    ),
                ),
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val SKILL_ID = "local_only_result_skill"
        }
    }

    private class MultiModelSkillRuntime : SkillRuntime {
        private val manifest = SkillManifest(
            id = SKILL_ID,
            version = 1,
            title = "Multi model skill",
            description = "Test-only multi model skill",
            triggerExamples = listOf("multi model skill"),
            requiredTools = listOf(
                MobileActionFunctions.WEB_SEARCH,
                MobileActionFunctions.SHARE_TEXT,
                MobileActionFunctions.OPEN_WIFI_SETTINGS,
            ),
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
            riskLevel = RiskLevel.HighExternalSend,
        )

        override fun manifests(): List<SkillManifest> = listOf(manifest)

        override fun plan(input: String): SkillPlan? {
            if (input != "multi model skill") return null
            val searchDraft = ActionDraft(
                functionName = MobileActionFunctions.WEB_SEARCH,
                title = "Web 搜索",
                summary = "将在浏览器中搜索：Kotlin",
                parameters = mapOf("query" to "Kotlin"),
                requiresConfirmation = true,
            )
            val shareDraft = ActionDraft(
                functionName = MobileActionFunctions.SHARE_TEXT,
                title = "分享摘要",
                summary = "将分享搜索摘要。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            val wifiDraft = ActionDraft(
                functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                title = "打开 Wi-Fi 设置",
                summary = "将打开系统 Wi-Fi 设置页。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            )
            return SkillPlan(
                request = SkillRequest(
                    id = "multi-model-skill-request",
                    skillId = SKILL_ID,
                    arguments = mapOf("input" to input),
                    reason = input,
                ),
                manifest = manifest,
                steps = listOf(
                    SkillStep.ToolStep(
                        id = "search",
                        request = ToolRequest(
                            id = "multi-search-request",
                            toolName = MobileActionFunctions.WEB_SEARCH,
                            arguments = mapOf("query" to "Kotlin"),
                            reason = searchDraft.summary,
                        ),
                        draft = searchDraft,
                    ),
                    SkillStep.ModelStep(
                        id = "summarize_search",
                        dependsOn = listOf("search"),
                        title = "整理搜索结果",
                        instruction = "把搜索结果整理成适合分享的一句话。",
                        inputBindings = mapOf("searchSummary" to "search.summary"),
                        outputKey = "shareText",
                        keepsSensitiveInputLocal = false,
                    ),
                    SkillStep.ToolStep(
                        id = "share_search",
                        dependsOn = listOf("summarize_search"),
                        request = ToolRequest(
                            id = "multi-share-request",
                            toolName = MobileActionFunctions.SHARE_TEXT,
                            reason = shareDraft.summary,
                        ),
                        draft = shareDraft,
                        argumentBindings = mapOf("text" to "summarize_search.shareText"),
                    ),
                    SkillStep.ModelStep(
                        id = "decide_settings",
                        dependsOn = listOf("share_search"),
                        title = "决定是否打开设置",
                        instruction = "根据分享工具结果输出下一步确认说明。",
                        inputBindings = mapOf("shareSummary" to "share_search.summary"),
                        outputKey = "nextStep",
                        keepsSensitiveInputLocal = false,
                    ),
                    SkillStep.ToolStep(
                        id = "open_wifi",
                        dependsOn = listOf("decide_settings"),
                        request = ToolRequest(
                            id = "multi-wifi-request",
                            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                            reason = wifiDraft.summary,
                        ),
                        draft = wifiDraft,
                    ),
                ),
            )
        }

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? = null

        companion object {
            const val SKILL_ID = "multi_model_skill"
        }
    }

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
