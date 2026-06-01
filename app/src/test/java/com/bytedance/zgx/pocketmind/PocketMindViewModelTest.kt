package com.bytedance.zgx.pocketmind

import android.net.Uri
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlan
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningResult
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.audit.ToolAuditLog
import com.bytedance.zgx.pocketmind.audit.ToolAuditRecord
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.pocketmind.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.pocketmind.background.ReminderScheduleRequest
import com.bytedance.zgx.pocketmind.background.ReminderRescheduleReport
import com.bytedance.zgx.pocketmind.background.ScheduledTask
import com.bytedance.zgx.pocketmind.background.ScheduledTaskStatus
import com.bytedance.zgx.pocketmind.background.ScheduledTaskType
import com.bytedance.zgx.pocketmind.data.FirstRunSetupStore
import com.bytedance.zgx.pocketmind.data.GenerationParametersStore
import com.bytedance.zgx.pocketmind.data.ModelDownloadSource
import com.bytedance.zgx.pocketmind.data.ModelRepositoryFacade
import com.bytedance.zgx.pocketmind.data.ModelSelectionResult
import com.bytedance.zgx.pocketmind.data.ModelSelectionState
import com.bytedance.zgx.pocketmind.data.ModelVerificationStatus
import com.bytedance.zgx.pocketmind.data.RemoteModelStore
import com.bytedance.zgx.pocketmind.data.SessionStore
import com.bytedance.zgx.pocketmind.data.TransferProgress
import com.bytedance.zgx.pocketmind.download.DownloadInfo
import com.bytedance.zgx.pocketmind.download.ModelDownloadClient
import com.bytedance.zgx.pocketmind.memory.EmbeddingRuntime
import com.bytedance.zgx.pocketmind.memory.MemoryRecallMode
import com.bytedance.zgx.pocketmind.memory.MemoryRecordType
import com.bytedance.zgx.pocketmind.memory.MemoryRecordStore
import com.bytedance.zgx.pocketmind.memory.MemoryRepository
import com.bytedance.zgx.pocketmind.memory.PersistedMemoryRecord
import com.bytedance.zgx.pocketmind.memory.taskStateMemoryRecordId
import com.bytedance.zgx.pocketmind.multimodal.SharedAttachment
import com.bytedance.zgx.pocketmind.multimodal.SharedAttachmentKind
import com.bytedance.zgx.pocketmind.multimodal.SharedInput
import com.bytedance.zgx.pocketmind.multimodal.SharedTextPreview
import com.bytedance.zgx.pocketmind.multimodal.SharedTextPreviewSource
import com.bytedance.zgx.pocketmind.orchestration.AgentModelObservationResult
import com.bytedance.zgx.pocketmind.orchestration.AgentObservationDecision
import com.bytedance.zgx.pocketmind.orchestration.AgentObservationResult
import com.bytedance.zgx.pocketmind.orchestration.AgentPlan
import com.bytedance.zgx.pocketmind.orchestration.AgentRecoveryAction
import com.bytedance.zgx.pocketmind.orchestration.AgentRun
import com.bytedance.zgx.pocketmind.orchestration.AgentRunState
import com.bytedance.zgx.pocketmind.orchestration.AgentTraceRunSummary
import com.bytedance.zgx.pocketmind.orchestration.AgentTraceStepSummary
import com.bytedance.zgx.pocketmind.orchestration.AssistantRoute
import com.bytedance.zgx.pocketmind.orchestration.AssistantRouter
import com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestrator
import com.bytedance.zgx.pocketmind.orchestration.InMemoryAgentTraceStore
import com.bytedance.zgx.pocketmind.runtime.LiteRtRuntime
import com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntime
import com.bytedance.zgx.pocketmind.safety.SafetyDecision
import com.bytedance.zgx.pocketmind.safety.SafetyOutcome
import com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntime
import com.bytedance.zgx.pocketmind.skill.SkillRequest
import com.bytedance.zgx.pocketmind.tool.ToolExecutor
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import com.bytedance.zgx.pocketmind.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PocketMindViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun remoteModeRejectsLocalOnlyPromptBeforeCallingRemoteRuntime() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("私密输入", messagePrivacy = MessagePrivacy.LocalOnly)
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            sessionStore.messages.map { it.privacy },
        )
        assertTrue(sessionStore.messages.first().text.contains("私密输入"))
    }

    @Test
    fun remoteModeRejectsSharedTextPreviewBeforeBuildingPrompt() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.ingestSharedInput(
            SharedInput(
                text = "",
                attachments = listOf(
                    SharedAttachment(
                        kind = SharedAttachmentKind.Document,
                        mimeType = "text/plain",
                        displayName = "private.txt",
                        sizeBytes = 12L,
                        textPreview = SharedTextPreview(
                            text = "private excerpt",
                            truncated = false,
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(1, sessionStore.messages.size)
        assertEquals(MessageRole.Assistant, sessionStore.messages.single().role)
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.single().privacy)
        assertFalse(sessionStore.messages.single().text.contains("private excerpt"))
        assertFalse(sessionStore.messages.single().text.contains("private.txt"))
        assertTrue(sessionStore.messages.single().text.contains("不会自动发送分享文本、文本摘录或附件元数据"))
    }

    @Test
    fun remoteModeRejectsSharedImageOcrPreviewBeforeBuildingPrompt() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.ingestSharedInput(
            SharedInput(
                text = "",
                attachments = listOf(
                    SharedAttachment(
                        kind = SharedAttachmentKind.Image,
                        mimeType = "image/png",
                        displayName = "private-screen.png",
                        sizeBytes = 12L,
                        textPreview = SharedTextPreview(
                            text = "private screenshot text",
                            truncated = false,
                            source = SharedTextPreviewSource.ImageOcr,
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(1, sessionStore.messages.size)
        assertEquals(MessageRole.Assistant, sessionStore.messages.single().role)
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.single().privacy)
        assertFalse(sessionStore.messages.single().text.contains("private screenshot text"))
        assertFalse(sessionStore.messages.single().text.contains("private-screen.png"))
        assertTrue(sessionStore.messages.single().text.contains("不会自动发送分享文本、文本摘录或附件元数据"))
        assertTrue(sessionStore.messages.single().text.contains("OCR 摘录同样不会自动发送"))
    }

    @Test
    fun localSharedInputDoesNotEnterLaterRemoteHistory() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val localRuntime = FakeLiteRtRuntime(localResponse = "本地回复：私密分享摘要")
        val remoteStore = FakeRemoteModelStore(mode = InferenceMode.Local)
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            remoteStore = remoteStore,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.ingestSharedInput(
            SharedInput(
                text = "私密分享内容",
                attachments = emptyList(),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            sessionStore.messages.map { it.privacy },
        )

        viewModel.updateRemoteModelConfig(configuredRemoteModel())
        viewModel.selectInferenceMode(InferenceMode.Remote)
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val call = remoteRuntime.calls.single()
        assertFalse(call.history.toString().contains("私密分享内容"))
        assertFalse(call.history.toString().contains("本地回复：私密分享摘要"))
        assertEquals("普通远程问题", sessionStore.messages.dropLast(1).last().text)
    }

    @Test
    fun voiceTranscriptDraftIsOneShotAndDoesNotSendMessage() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val executor = RecordingToolExecutor()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            actionExecutor = executor,
        )

        viewModel.acceptVoiceTranscript("  帮我\n总结今天会议  ")
        advanceUntilIdle()

        val draft = viewModel.uiState.value.voiceInputDraft
        assertEquals("帮我 总结今天会议", draft?.text)
        assertTrue((draft?.id ?: 0L) > 0L)
        assertTrue(sessionStore.messages.isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())
        assertTrue(executor.executedRequests.isEmpty())

        viewModel.consumeVoiceInputDraft(draft!!.id)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.voiceInputDraft)
        viewModel.consumeVoiceInputDraft(draft.id)
        assertEquals(null, viewModel.uiState.value.voiceInputDraft)
    }

    @Test
    fun newVoiceTranscriptReplacesUnconsumedDraftAndOldConsumeDoesNotClearIt() = runTest(dispatcher) {
        val viewModel = createViewModel()

        viewModel.acceptVoiceTranscript("第一段")
        val first = viewModel.uiState.value.voiceInputDraft!!
        viewModel.acceptVoiceTranscript("第二段")
        val second = viewModel.uiState.value.voiceInputDraft!!

        assertTrue(second.id > first.id)
        assertEquals("第二段", second.text)

        viewModel.consumeVoiceInputDraft(first.id)
        assertEquals(second, viewModel.uiState.value.voiceInputDraft)

        viewModel.consumeVoiceInputDraft(second.id)
        assertEquals(null, viewModel.uiState.value.voiceInputDraft)
    }

    @Test
    fun specialAccessReturnUpdatesStatusTextWithoutExecutingTools() = runTest(dispatcher) {
        val executor = RecordingToolExecutor()
        val viewModel = createViewModel(actionExecutor = executor)
        val requirement = SpecialAccessRequirement(
            id = SPECIAL_ACCESS_USAGE_STATS,
            title = "使用情况访问权限",
            rationale = "用于只读识别当前前台应用。",
            settingsAction = "android.settings.USAGE_ACCESS_SETTINGS",
        )

        viewModel.reportSpecialAccessResult(requirement, granted = false)
        assertEquals("返回后仍未开启使用情况访问权限", viewModel.uiState.value.statusText)
        assertTrue(executor.executedRequests.isEmpty())

        viewModel.reportSpecialAccessResult(requirement, granted = true)
        assertEquals("使用情况访问权限已开启", viewModel.uiState.value.statusText)
        assertTrue(executor.executedRequests.isEmpty())
    }

    @Test
    fun clipboardSummaryShareShowsSecondConfirmationAfterLocalSummary() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val localRuntime = FakeLiteRtRuntime(localResponse = "摘要：适合分享的本地摘要")
        val readRequest = ToolRequest(toolName = MobileActionFunctions.READ_CLIPBOARD)
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本。",
            parameters = emptyMap(),
        )
        val shareRequest = ToolRequest(
            toolName = MobileActionFunctions.SHARE_TEXT,
            arguments = mapOf("text" to "摘要：适合分享的本地摘要"),
            reason = "将打开系统分享面板并填入上一步生成的摘要。",
        )
        val shareDraft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "分享摘要",
            summary = "将打开系统分享面板并填入上一步生成的摘要。",
            parameters = shareRequest.arguments,
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-share",
                toolRequest = readRequest,
                draft = readDraft,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL,
            ),
            confirmedRun = AgentRun("run-share", "总结剪贴板并分享", AgentRunState.ExecutingTool, 1L, 2L),
            toolObservation = AgentObservationResult(
                run = AgentRun("run-share", "总结剪贴板并分享", AgentRunState.GeneratingAnswer, 1L, 3L),
                result = ToolResult(
                    requestId = readRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取剪贴板文本",
                    data = mapOf("text" to "[redacted]"),
                ),
                assistantMessage = "工具执行结果：已读取剪贴板文本",
                decision = AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = true,
                    reason = "已读取剪贴板文本",
                ),
                continuationPromptForModel = "请总结剪贴板文本",
                continuationRequiresLocalModel = true,
                steps = emptyList(),
            ),
            modelObservation = AgentModelObservationResult(
                run = AgentRun("run-share", "总结剪贴板并分享", AgentRunState.AwaitingUserConfirmation, 1L, 4L),
                decision = AgentObservationDecision.PlanNextTool(
                    plan = AgentPlan.UseTool(
                        request = shareRequest,
                        draft = shareDraft,
                        plannedByModel = false,
                        fallbackReason = "skill model step",
                        skillRequest = SkillRequest(
                            id = "skill-run",
                            skillId = BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL,
                            arguments = mapOf("input" to "总结剪贴板并分享"),
                            reason = "总结剪贴板并分享",
                        ),
                        safetyDecision = SafetyDecision(
                            outcome = SafetyOutcome.RequireConfirmation,
                            reason = "Tool share_text requires user confirmation before execution.",
                        ),
                    ),
                    reason = "Model output satisfied the next skill step.",
                ),
                steps = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("总结剪贴板并分享")
        advanceUntilIdle()
        val firstConfirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(firstConfirmation)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, firstConfirmation.draft.functionName)

        viewModel.confirmAgentConfirmation(firstConfirmation)
        advanceUntilIdle()

        val secondConfirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(secondConfirmation)
        assertEquals(MobileActionFunctions.SHARE_TEXT, secondConfirmation.draft.functionName)
        assertEquals("摘要：适合分享的本地摘要", secondConfirmation.toolRequest?.arguments?.get("text"))
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(
            listOf(
                MessagePrivacy.LocalOnly,
                MessagePrivacy.LocalOnly,
                MessagePrivacy.LocalOnly,
                MessagePrivacy.LocalOnly,
            ),
            sessionStore.messages.map { it.privacy },
        )
        assertTrue(sessionStore.messages.last().text.contains("摘要：适合分享的本地摘要"))
    }

    @Test
    fun remoteModeProtectsRecentScreenshotOcrBeforeRemoteContinuation() = runTest(dispatcher) {
        val rawOcrText = "截图里的私密验证码 123456"
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val readRequest = ToolRequest(
            toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            arguments = mapOf("maxCount" to "1"),
        )
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
            title = "读取最近截图 OCR",
            summary = "将读取最近 1 张截图并在本地提取文字。",
            parameters = readRequest.arguments,
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-screenshot-ocr",
                toolRequest = readRequest,
                draft = readDraft,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = null,
            ),
            confirmedRun = AgentRun(
                "run-screenshot-ocr",
                "识别最近截图文字",
                AgentRunState.ExecutingTool,
                1L,
                2L,
            ),
            toolObservation = AgentObservationResult(
                run = AgentRun(
                    "run-screenshot-ocr",
                    "识别最近截图文字",
                    AgentRunState.GeneratingAnswer,
                    1L,
                    3L,
                ),
                result = ToolResult(
                    requestId = readRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取最近截图 OCR 摘录",
                    data = mapOf(
                        "ocrText" to "[redacted]",
                        "ocrTextIncluded" to "true",
                        "privacy" to "LocalOnly",
                    ),
                ),
                assistantMessage = "工具执行结果：已读取最近截图 OCR 摘录",
                decision = AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = true,
                    reason = "截图 OCR 内容仅可在本地继续处理。",
                ),
                continuationPromptForModel = "请根据 OCR 文本回答：$rawOcrText",
                continuationRequiresLocalModel = true,
                steps = emptyList(),
            ),
        )
        val actionExecutor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult =
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取最近截图 OCR 摘录",
                    data = mapOf(
                        "ocrText" to rawOcrText,
                        "ocrTextIncluded" to "true",
                        "privacy" to "LocalOnly",
                    ),
                )
        }
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("识别最近截图文字")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, confirmation.draft.functionName)

        viewModel.confirmAgentConfirmation(confirmation)
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals("已保护截图 OCR 内容", viewModel.uiState.value.statusText)
        assertTrue(sessionStore.messages.last().text.contains("不会自动发送截图 OCR 内容到远程模型"))
        assertTrue(sessionStore.messages.none { message -> message.text.contains(rawOcrText) })
    }

    @Test
    fun remoteModeProtectsRecentImageOcrBeforeRemoteContinuation() = runTest(dispatcher) {
        val rawOcrText = "照片里的私密订单号 ABC123"
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val readRequest = ToolRequest(
            toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            arguments = mapOf("maxCount" to "3"),
        )
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
            title = "读取最近图片 OCR",
            summary = "将扫描最近图片并在本地提取文字。",
            parameters = readRequest.arguments,
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-image-ocr",
                toolRequest = readRequest,
                draft = readDraft,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = null,
            ),
            confirmedRun = AgentRun(
                "run-image-ocr",
                "识别最近图片文字",
                AgentRunState.ExecutingTool,
                1L,
                2L,
            ),
            toolObservation = AgentObservationResult(
                run = AgentRun(
                    "run-image-ocr",
                    "识别最近图片文字",
                    AgentRunState.GeneratingAnswer,
                    1L,
                    3L,
                ),
                result = ToolResult(
                    requestId = readRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取最近图片 OCR 摘录",
                    data = mapOf(
                        "ocrText" to "[redacted]",
                        "ocrTextIncluded" to "true",
                        "privacy" to "LocalOnly",
                    ),
                ),
                assistantMessage = "工具执行结果：已读取最近图片 OCR 摘录",
                decision = AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = true,
                    reason = "图片 OCR 内容仅可在本地继续处理。",
                ),
                continuationPromptForModel = "请根据 OCR 文本回答：$rawOcrText",
                continuationRequiresLocalModel = true,
                steps = emptyList(),
            ),
        )
        val actionExecutor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult =
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取最近图片 OCR 摘录",
                    data = mapOf(
                        "ocrText" to rawOcrText,
                        "ocrTextIncluded" to "true",
                        "privacy" to "LocalOnly",
                    ),
                )
        }
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("识别最近图片文字")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, confirmation.draft.functionName)

        viewModel.confirmAgentConfirmation(confirmation)
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals("已保护图片 OCR 内容", viewModel.uiState.value.statusText)
        assertTrue(sessionStore.messages.last().text.contains("不会自动发送图片 OCR 内容到远程模型"))
        assertTrue(sessionStore.messages.none { message -> message.text.contains(rawOcrText) })
    }

    @Test
    fun remoteModeProtectsCurrentScreenTextBeforeRemoteContinuation() = runTest(dispatcher) {
        val rawScreenText = "当前屏幕里的私密验证码 654321"
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val readRequest = ToolRequest(
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            arguments = mapOf("maxChars" to "1200"),
        )
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            title = "读取当前屏幕文本",
            summary = "将读取当前屏幕的可访问文本快照。",
            parameters = readRequest.arguments,
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-screen-text",
                toolRequest = readRequest,
                draft = readDraft,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = null,
            ),
            confirmedRun = AgentRun(
                "run-screen-text",
                "总结当前屏幕文字",
                AgentRunState.ExecutingTool,
                1L,
                2L,
            ),
            toolObservation = AgentObservationResult(
                run = AgentRun(
                    "run-screen-text",
                    "总结当前屏幕文字",
                    AgentRunState.GeneratingAnswer,
                    1L,
                    3L,
                ),
                result = ToolResult(
                    requestId = readRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取当前屏幕可访问文本快照",
                    data = mapOf(
                        "screenText" to "[redacted]",
                        "screenTextIncluded" to "true",
                        "privacy" to "LocalOnly",
                    ),
                ),
                assistantMessage = "工具执行结果：已读取当前屏幕可访问文本快照",
                decision = AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = true,
                    reason = "当前屏幕文本仅可在本地继续处理。",
                ),
                continuationPromptForModel = "请根据当前屏幕文本回答：$rawScreenText",
                continuationRequiresLocalModel = true,
                steps = emptyList(),
            ),
        )
        val actionExecutor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult =
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取当前屏幕可访问文本快照",
                    data = mapOf(
                        "screenText" to rawScreenText,
                        "screenTextIncluded" to "true",
                        "privacy" to "LocalOnly",
                    ),
                )
        }
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("总结当前屏幕文字")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, confirmation.draft.functionName)

        viewModel.confirmAgentConfirmation(confirmation)
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals("已保护当前屏幕文本", viewModel.uiState.value.statusText)
        assertTrue(sessionStore.messages.last().text.contains("不会自动发送当前屏幕文本到远程模型"))
        assertTrue(sessionStore.messages.none { message -> message.text.contains(rawScreenText) })
    }

    @Test
    fun remoteModeProtectsGenericLocalOnlyContinuationAsLocalToolResult() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val request = ToolRequest(
            toolName = MobileActionFunctions.QUERY_CONTACTS,
            arguments = mapOf("query" to "Alice"),
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.QUERY_CONTACTS,
            title = "查询联系人",
            summary = "将读取联系人摘要。",
            parameters = request.arguments,
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-local-tool",
                toolRequest = request,
                draft = draft,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = null,
            ),
            confirmedRun = AgentRun("run-local-tool", "查联系人 Alice", AgentRunState.ExecutingTool, 1L, 2L),
            toolObservation = AgentObservationResult(
                run = AgentRun("run-local-tool", "查联系人 Alice", AgentRunState.GeneratingAnswer, 1L, 3L),
                result = ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "联系人 Alice：仅本地摘要",
                    data = mapOf(
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to "true",
                    ),
                ),
                assistantMessage = "工具执行结果：联系人 Alice：仅本地摘要",
                decision = AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = true,
                    reason = "联系人摘要仅可在本地继续处理。",
                ),
                continuationPromptForModel = "请根据联系人摘要回答",
                continuationRequiresLocalModel = true,
                steps = emptyList(),
            ),
        )
        val actionExecutor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult =
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "联系人 Alice：仅本地摘要",
                    data = mapOf(
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to "true",
                    ),
                )
        }
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("查联系人 Alice")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, confirmation.draft.functionName)

        viewModel.confirmAgentConfirmation(confirmation)
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals("已保护本地工具结果", viewModel.uiState.value.statusText)
        assertTrue(sessionStore.messages.last().text.contains("不会自动发送本地工具结果到远程模型"))
        assertFalse(sessionStore.messages.last().text.contains("剪贴板内容"))
    }

    @Test
    fun unknownToolResultPrivacyIsTreatedAsLocalOnlyBeforeRemoteContinuation() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val request = ToolRequest(
            toolName = MobileActionFunctions.QUERY_CONTACTS,
            arguments = mapOf("query" to "Alice"),
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.QUERY_CONTACTS,
            title = "查询联系人",
            summary = "将读取联系人摘要。",
            parameters = request.arguments,
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-unknown-privacy-tool",
                toolRequest = request,
                draft = draft,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = null,
            ),
            confirmedRun = AgentRun(
                "run-unknown-privacy-tool",
                "查联系人 Alice",
                AgentRunState.ExecutingTool,
                1L,
                2L,
            ),
            toolObservation = AgentObservationResult(
                run = AgentRun(
                    "run-unknown-privacy-tool",
                    "查联系人 Alice",
                    AgentRunState.GeneratingAnswer,
                    1L,
                    3L,
                ),
                result = ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "联系人 Alice：仅本地摘要",
                    data = mapOf("privacy" to "UnknownFutureValue"),
                ),
                assistantMessage = "工具执行结果：联系人 Alice：仅本地摘要",
                decision = AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = false,
                    reason = "继续处理工具结果。",
                ),
                continuationPromptForModel = "请根据联系人 Alice 的摘要回答",
                continuationRequiresLocalModel = false,
                steps = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
            assistantRouter = assistantRouter,
            actionExecutor = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult =
                    ToolResult(
                        requestId = request.id,
                        status = ToolStatus.Succeeded,
                        summary = "联系人 Alice：仅本地摘要",
                        data = mapOf("privacy" to "UnknownFutureValue"),
                    )
            },
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("查联系人 Alice")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)

        viewModel.confirmAgentConfirmation(confirmation)
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals("已保护工具结果", viewModel.uiState.value.statusText)
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
        assertTrue(sessionStore.messages.last().text.contains("不会把它发送到远程模型"))
    }

    @Test
    fun pureChatAnswerCompletesAgentTraceRun() = runTest(dispatcher) {
        val traceStore = InMemoryAgentTraceStore()
        val orchestrator = AssistantOrchestrator(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = traceStore,
        )
        val viewModel = createViewModel(
            assistantRouter = orchestrator,
            runtime = FakeLiteRtRuntime(localResponse = "完成"),
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("普通问题")
        advanceUntilIdle()

        assertEquals(
            AgentRunState.Completed,
            orchestrator.recentTraceRuns(limit = 1).single().run.state,
        )
    }

    @Test
    fun pendingConfirmationBlocksNewMessageUntilHandled() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-1",
                toolRequest = ToolRequest(toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS),
                draft = ActionDraft(
                    functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    title = "打开 Wi-Fi 设置",
                    summary = "将打开系统 Wi-Fi 设置页。",
                    parameters = emptyMap(),
                ),
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL,
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("打开 Wi-Fi 设置")
        advanceUntilIdle()
        val messagesAfterPending = sessionStore.messages
        requireNotNull(viewModel.uiState.value.pendingConfirmation)

        viewModel.sendMessage("新的问题不应越过待确认动作")
        advanceUntilIdle()

        assertEquals(messagesAfterPending, sessionStore.messages)
        assertEquals(1, assistantRouter.routeCallCount)
        assertEquals("请先确认或取消待执行动作", viewModel.uiState.value.statusText)
    }

    @Test
    fun duplicatePendingConfirmationExecutesOnlyOnce() = runTest(dispatcher) {
        val request = ToolRequest(toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS)
        val executor = RecordingToolExecutor()
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-1",
                toolRequest = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    title = "打开 Wi-Fi 设置",
                    summary = "将打开系统 Wi-Fi 设置页。",
                    parameters = emptyMap(),
                ),
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL,
            ),
            confirmedRun = AgentRun("run-1", "打开 Wi-Fi 设置", AgentRunState.ExecutingTool, 1L, 2L),
        )
        val viewModel = createViewModel(
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("打开 Wi-Fi 设置")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)

        viewModel.confirmAgentConfirmation(confirmation)
        viewModel.confirmAgentConfirmation(confirmation)
        advanceUntilIdle()

        assertEquals(1, executor.executedRequests.size)
        assertEquals(request.id, executor.executedRequests.single().id)
        assertEquals(1, assistantRouter.confirmCallCount)
    }

    @Test
    fun confirmAgentConfirmationWhenConfirmToolRequestThrowsClearsBusyAndKeepsPending() = runTest(dispatcher) {
        val request = ToolRequest(toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS)
        val executor = RecordingToolExecutor()
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-throws",
                toolRequest = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    title = "打开 Wi-Fi 设置",
                    summary = "将打开系统 Wi-Fi 设置页。",
                    parameters = emptyMap(),
                ),
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL,
            ),
            confirmFailure = IllegalStateException("trace store unavailable"),
        )
        val viewModel = createViewModel(
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("打开 Wi-Fi 设置")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)

        viewModel.confirmAgentConfirmation(confirmation)
        advanceUntilIdle()

        assertEquals(confirmation, viewModel.uiState.value.pendingConfirmation)
        assertFalse(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.isGenerating)
        assertTrue(executor.executedRequests.isEmpty())
        assertEquals(1, assistantRouter.confirmCallCount)
        assertEquals("工具确认失败，未执行动作。", viewModel.uiState.value.statusText)
    }

    @Test
    fun dismissAgentConfirmationWithNullPendingDoesNotReportCancellation() = runTest(dispatcher) {
        val assistantRouter = FakeAssistantRouter()
        val viewModel = createViewModel(assistantRouter = assistantRouter)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        val previousStatus = viewModel.uiState.value.statusText

        viewModel.dismissAgentConfirmation(null)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals(0, assistantRouter.cancelCallCount)
        assertEquals("工具确认已处理", viewModel.uiState.value.statusText)
        assertTrue(previousStatus != "已取消动作草稿")
    }

    @Test
    fun staleDismissAgentConfirmationDoesNotClearCurrentPending() = runTest(dispatcher) {
        val request = ToolRequest(
            id = "request-current",
            toolName = MobileActionFunctions.SHARE_TEXT,
            arguments = mapOf("text" to "current summary"),
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-current",
                toolRequest = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.SHARE_TEXT,
                    title = "分享摘要",
                    summary = "将分享当前摘要。",
                    parameters = request.arguments,
                ),
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL,
            ),
        )
        val viewModel = createViewModel(
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("分享摘要")
        advanceUntilIdle()
        val current = viewModel.uiState.value.pendingConfirmation
        requireNotNull(current)
        val stale = current.copy(
            toolRequest = current.toolRequest?.copy(arguments = mapOf("text" to "stale summary")),
            draft = current.draft.copy(parameters = mapOf("text" to "stale summary")),
        )

        viewModel.dismissAgentConfirmation(stale)
        advanceUntilIdle()

        assertEquals(current, viewModel.uiState.value.pendingConfirmation)
        assertEquals(0, assistantRouter.cancelCallCount)
        assertEquals("工具确认已处理", viewModel.uiState.value.statusText)
    }

    @Test
    fun staleConfirmAgentConfirmationDoesNotExecuteOrClearCurrentPending() = runTest(dispatcher) {
        val request = ToolRequest(
            id = "request-current",
            toolName = MobileActionFunctions.SHARE_TEXT,
            arguments = mapOf("text" to "current summary"),
        )
        val executor = RecordingToolExecutor()
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-current",
                toolRequest = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.SHARE_TEXT,
                    title = "分享摘要",
                    summary = "将分享当前摘要。",
                    parameters = request.arguments,
                ),
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL,
            ),
        )
        val viewModel = createViewModel(
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("分享摘要")
        advanceUntilIdle()
        val current = viewModel.uiState.value.pendingConfirmation
        requireNotNull(current)
        val stale = current.copy(
            toolRequest = current.toolRequest?.copy(arguments = mapOf("text" to "stale summary")),
            draft = current.draft.copy(parameters = mapOf("text" to "stale summary")),
        )

        viewModel.confirmAgentConfirmation(stale)
        advanceUntilIdle()

        assertEquals(current, viewModel.uiState.value.pendingConfirmation)
        assertEquals(0, assistantRouter.confirmCallCount)
        assertTrue(executor.executedRequests.isEmpty())
        assertEquals("工具确认已处理", viewModel.uiState.value.statusText)
    }

    @Test
    fun remoteModeLocalActionDraftMessagesAreLocalOnlyAndExcludedFromLaterRemoteHistory() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val remoteStore = FakeRemoteModelStore(
            mode = InferenceMode.Remote,
            config = configuredRemoteModel(),
        )
        val actionRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-local-action",
                toolRequest = ToolRequest(toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS),
                draft = ActionDraft(
                    functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    title = "打开 Wi-Fi 设置",
                    summary = "将打开系统 Wi-Fi 设置页。",
                    parameters = emptyMap(),
                ),
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL,
            ),
        )
        val actionViewModel = createViewModel(
            sessionStore = sessionStore,
            remoteStore = remoteStore,
            assistantRouter = actionRouter,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        actionViewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        actionViewModel.sendMessage("打开 Wi-Fi 设置")
        advanceUntilIdle()

        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            sessionStore.messages.map { it.privacy },
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val chatViewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = remoteStore,
            assistantRouter = FakeAssistantRouter(),
        )
        chatViewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        chatViewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val call = remoteRuntime.calls.single()
        assertTrue(call.history.isEmpty())
        assertFalse(call.history.toString().contains("打开 Wi-Fi 设置"))
        assertFalse(call.history.toString().contains("动作草稿"))
    }

    @Test
    fun remoteModeRejectedLocalActionMessagesAreLocalOnlyAndExcludedFromLaterRemoteHistory() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val remoteStore = FakeRemoteModelStore(
            mode = InferenceMode.Remote,
            config = configuredRemoteModel(),
        )
        val rejectedRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.ToolRejected(
                summary = "Unknown tool: open_wifi_settings",
            ),
        )
        val rejectedViewModel = createViewModel(
            sessionStore = sessionStore,
            remoteStore = remoteStore,
            assistantRouter = rejectedRouter,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        rejectedViewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        rejectedViewModel.sendMessage("打开 Wi-Fi 设置")
        advanceUntilIdle()

        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            sessionStore.messages.map { it.privacy },
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val chatViewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = remoteStore,
            assistantRouter = FakeAssistantRouter(),
        )
        chatViewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        chatViewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val call = remoteRuntime.calls.single()
        assertTrue(call.history.isEmpty())
        assertFalse(call.history.toString().contains("打开 Wi-Fi 设置"))
        assertFalse(call.history.toString().contains("Unknown tool"))
    }

    @Test
    fun deniedRuntimePermissionFailsPendingToolWithoutExecutingIt() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val request = ToolRequest(
            id = "request-contacts",
            toolName = MobileActionFunctions.QUERY_CONTACTS,
            arguments = mapOf("query" to "Alice"),
        )
        val executor = RecordingToolExecutor()
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-contacts",
                toolRequest = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.QUERY_CONTACTS,
                    title = "查询联系人",
                    summary = "将读取联系人摘要。",
                    parameters = request.arguments,
                ),
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = null,
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("查联系人 Alice")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)

        viewModel.rejectAgentConfirmationForRuntimePermissionDenial(
            confirmation = confirmation,
            deniedPermissions = listOf("android.permission.READ_CONTACTS"),
        )
        advanceUntilIdle()

        assertTrue(executor.executedRequests.isEmpty())
        assertEquals(0, assistantRouter.confirmCallCount)
        assertEquals(1, assistantRouter.failPendingCallCount)
        assertEquals(request.id, assistantRouter.lastFailedPendingResult?.requestId)
        assertEquals(ToolErrorCode.PermissionDenied, assistantRouter.lastFailedPendingResult?.error?.code)
        assertTrue(assistantRouter.lastFailedPendingResult?.summary.orEmpty().contains("联系人权限"))
        assertEquals(
            "android.permission.READ_CONTACTS",
            assistantRouter.lastFailedPendingResult?.data?.get("deniedPermissions"),
        )
        assertEquals("联系人权限", assistantRouter.lastFailedPendingResult?.data?.get("deniedPermissionLabels"))
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertTrue(sessionStore.messages.last().text.contains("权限"))
        assertEquals("权限被拒，工具未执行", viewModel.uiState.value.statusText)
    }

    @Test
    fun unverifiedExternalLaunchShowsLaunchOnlyStatus() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val request = ToolRequest(
            id = "request-share",
            toolName = MobileActionFunctions.SHARE_TEXT,
            arguments = mapOf("text" to "明天十点开会"),
            reason = "将打开系统分享面板并填入文本。",
        )
        val result = ToolResult(
            requestId = request.id,
            status = ToolStatus.Succeeded,
            summary = "已打开系统分享面板",
            data = mapOf(
                "completionState" to "ExternalActivityOpened",
                "completionVerified" to "false",
                "externalOutcome" to "Unknown",
            ),
        )
        val assistantMessage = "$UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX：${result.summary}"
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-share",
                toolRequest = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.SHARE_TEXT,
                    title = "系统分享",
                    summary = request.reason,
                    parameters = request.arguments,
                ),
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.SHARE_TEXT_SKILL,
            ),
            confirmedRun = AgentRun("run-share", "分享这段文字", AgentRunState.ExecutingTool, 1L, 2L),
            toolObservation = AgentObservationResult(
                run = AgentRun("run-share", "分享这段文字", AgentRunState.Completed, 1L, 3L),
                result = result,
                assistantMessage = assistantMessage,
                decision = AgentObservationDecision.Complete,
                steps = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            assistantRouter = assistantRouter,
            actionExecutor = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult =
                    result.copy(requestId = request.id)
            },
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("分享这段文字：明天十点开会")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)

        viewModel.confirmAgentConfirmation(confirmation)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertTrue(sessionStore.messages.last().text.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX))
        assertTrue(viewModel.uiState.value.statusText.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX))
    }

    @Test
    fun reminderObservationStoresTypedRecoveryActionForUi() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val request = ToolRequest(
            id = "request-reminder",
            toolName = MobileActionFunctions.SCHEDULE_REMINDER,
            arguments = mapOf(
                "title" to "喝水",
                "delayMinutes" to "10",
            ),
            reason = "将在 10 分钟后提醒：喝水",
        )
        val recoveryRequest = ToolRequest(
            id = "request-recovery",
            toolName = MobileActionFunctions.CANCEL_REMINDER,
            arguments = mapOf("taskId" to "task-1"),
            reason = "撤销提醒任务：task-1",
        )
        val recoveryDraft = ActionDraft(
            functionName = MobileActionFunctions.CANCEL_REMINDER,
            title = "撤销提醒",
            summary = "将取消提醒任务：task-1",
            parameters = recoveryRequest.arguments,
        )
        val recoveryAction = AgentRecoveryAction(
            sourceRequestId = request.id,
            sourceToolName = MobileActionFunctions.SCHEDULE_REMINDER,
            request = recoveryRequest,
            draft = recoveryDraft,
        )
        val result = ToolResult(
            requestId = request.id,
            status = ToolStatus.Succeeded,
            summary = "已安排 10000 触发的后台提醒",
            data = mapOf(
                "toolName" to MobileActionFunctions.SCHEDULE_REMINDER,
                "taskId" to "task-1",
                "recoveryToolName" to MobileActionFunctions.CANCEL_REMINDER,
                "recoveryTaskId" to "task-1",
            ),
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-reminder",
                toolRequest = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.SCHEDULE_REMINDER,
                    title = "后台提醒",
                    summary = request.reason,
                    parameters = request.arguments,
                ),
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.REMINDER_SKILL,
            ),
            confirmedRun = AgentRun("run-reminder", "提醒我 10 分钟后喝水", AgentRunState.ExecutingTool, 1L, 2L),
            toolObservation = AgentObservationResult(
                run = AgentRun("run-reminder", "提醒我 10 分钟后喝水", AgentRunState.Completed, 1L, 3L),
                result = result,
                assistantMessage = "工具执行结果：已安排 10000 触发的后台提醒\n如需撤销该提醒，请再次确认执行 cancel_reminder，taskId=task-1。",
                decision = AgentObservationDecision.Complete,
                recoveryAction = recoveryAction,
                steps = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            assistantRouter = assistantRouter,
            actionExecutor = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult =
                    result.copy(requestId = request.id)
            },
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("提醒我 10 分钟后喝水")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)

        viewModel.confirmAgentConfirmation(confirmation)
        advanceUntilIdle()

        assertEquals(recoveryAction, viewModel.uiState.value.latestRecoveryAction)
        assertTrue(sessionStore.messages.last().text.contains("taskId=task-1"))
    }

    @Test
    fun reminderUndoEntryCreatesPendingCancelConfirmationAndDoesNotExecuteUntilConfirmed() =
        runTest(dispatcher) {
            val sessionStore = FakeSessionStore()
            val request = ToolRequest(
                id = "request-reminder",
                toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                arguments = mapOf(
                    "title" to "喝水",
                    "delayMinutes" to "10",
                ),
                reason = "将在 10 分钟后提醒：喝水",
            )
            val recoveryRequest = ToolRequest(
                id = "request-recovery",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "task-1"),
                reason = "撤销提醒任务：task-1",
            )
            val recoveryDraft = ActionDraft(
                functionName = MobileActionFunctions.CANCEL_REMINDER,
                title = "撤销提醒",
                summary = "将取消提醒任务：task-1",
                parameters = recoveryRequest.arguments,
            )
            val recoveryAction = AgentRecoveryAction(
                sourceRequestId = request.id,
                sourceToolName = MobileActionFunctions.SCHEDULE_REMINDER,
                request = recoveryRequest,
                draft = recoveryDraft,
            )
            val scheduleResult = ToolResult(
                requestId = request.id,
                status = ToolStatus.Succeeded,
                summary = "已安排 10000 触发的后台提醒",
                data = mapOf(
                    "toolName" to MobileActionFunctions.SCHEDULE_REMINDER,
                    "taskId" to "task-1",
                    "recoveryToolName" to MobileActionFunctions.CANCEL_REMINDER,
                    "recoveryTaskId" to "task-1",
                ),
            )
            val cancelResult = ToolResult(
                requestId = recoveryRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已取消后台任务：task-1",
                data = mapOf(
                    "toolName" to MobileActionFunctions.CANCEL_REMINDER,
                    "taskId" to "task-1",
                ),
            )
            val assistantRouter = FakeAssistantRouter(
                routeResult = AssistantRoute.Action(
                    runId = "run-reminder",
                    toolRequest = request,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.SCHEDULE_REMINDER,
                        title = "后台提醒",
                        summary = request.reason,
                        parameters = request.arguments,
                    ),
                    plannedByModel = false,
                    fallbackReason = "test fallback",
                    skillId = BuiltInSkillRuntime.REMINDER_SKILL,
                ),
                confirmedRunsById = mapOf(
                    "run-reminder" to AgentRun(
                        "run-reminder",
                        "提醒我 10 分钟后喝水",
                        AgentRunState.ExecutingTool,
                        1L,
                        2L,
                    ),
                    "run-recovery" to AgentRun(
                        "run-recovery",
                        "撤销提醒任务：task-1",
                        AgentRunState.ExecutingTool,
                        4L,
                        5L,
                    ),
                ),
                toolObservationsByRunId = mapOf(
                    "run-reminder" to AgentObservationResult(
                        run = AgentRun(
                            "run-reminder",
                            "提醒我 10 分钟后喝水",
                            AgentRunState.Completed,
                            1L,
                            3L,
                        ),
                        result = scheduleResult,
                        assistantMessage = "工具执行结果：已安排 10000 触发的后台提醒\n" +
                            "如需撤销该提醒，请再次确认执行 cancel_reminder，taskId=task-1。",
                        decision = AgentObservationDecision.Complete,
                        recoveryAction = recoveryAction,
                        steps = emptyList(),
                    ),
                    "run-recovery" to AgentObservationResult(
                        run = AgentRun(
                            "run-recovery",
                            "撤销提醒任务：task-1",
                            AgentRunState.Completed,
                            4L,
                            6L,
                        ),
                        result = cancelResult,
                        assistantMessage = "工具执行结果：已取消后台任务：task-1",
                        decision = AgentObservationDecision.Complete,
                        steps = emptyList(),
                    ),
                ),
            )
            val executedRequests = mutableListOf<ToolRequest>()
            val viewModel = createViewModel(
                sessionStore = sessionStore,
                assistantRouter = assistantRouter,
                actionExecutor = object : ToolExecutor {
                    override fun execute(request: ToolRequest): ToolResult {
                        executedRequests += request
                        return when (request.toolName) {
                            MobileActionFunctions.SCHEDULE_REMINDER -> scheduleResult.copy(requestId = request.id)
                            MobileActionFunctions.CANCEL_REMINDER -> cancelResult.copy(requestId = request.id)
                            else -> ToolResult(
                                requestId = request.id,
                                status = ToolStatus.Succeeded,
                                summary = "executed",
                            )
                        }
                    }
                },
                modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
            )
            viewModel.restoreStartupState()
            advanceUntilIdle()

            viewModel.sendMessage("提醒我 10 分钟后喝水")
            advanceUntilIdle()
            val confirmation = viewModel.uiState.value.pendingConfirmation
            requireNotNull(confirmation)
            viewModel.confirmAgentConfirmation(confirmation)
            advanceUntilIdle()

            assertEquals(listOf(MobileActionFunctions.SCHEDULE_REMINDER), executedRequests.map { it.toolName })
            assertEquals(recoveryAction, viewModel.uiState.value.latestRecoveryAction)

            viewModel.requestRecoveryActionConfirmation(recoveryAction)
            advanceUntilIdle()

            val pendingRecovery = viewModel.uiState.value.pendingConfirmation
            requireNotNull(pendingRecovery) {
                "status=${viewModel.uiState.value.statusText}, isBusy=${viewModel.uiState.value.isBusy}"
            }
            assertEquals("run-recovery", pendingRecovery.runId)
            assertEquals(MobileActionFunctions.CANCEL_REMINDER, pendingRecovery.draft.functionName)
            assertEquals(MobileActionFunctions.CANCEL_REMINDER, pendingRecovery.toolRequest?.toolName)
            assertEquals(mapOf("taskId" to "task-1"), pendingRecovery.toolRequest?.arguments)
            assertFalse(pendingRecovery.draft.summary.contains("喝水"))
            assertEquals(null, viewModel.uiState.value.latestRecoveryAction)
            assertEquals("撤销提醒待确认", viewModel.uiState.value.statusText)
            assertEquals(listOf(MobileActionFunctions.SCHEDULE_REMINDER), executedRequests.map { it.toolName })
            assertEquals(1, assistantRouter.requestRecoveryCallCount)
            assertEquals(1, assistantRouter.confirmCallCount)

            viewModel.confirmAgentConfirmation(pendingRecovery)
            advanceUntilIdle()

            assertEquals(
                listOf(MobileActionFunctions.SCHEDULE_REMINDER, MobileActionFunctions.CANCEL_REMINDER),
                executedRequests.map { it.toolName },
            )
            assertEquals(null, viewModel.uiState.value.pendingConfirmation)
            assertEquals(null, viewModel.uiState.value.latestRecoveryAction)
            assertEquals(2, assistantRouter.confirmCallCount)
            assertEquals(2, assistantRouter.observeToolCallCount)
            assertTrue(sessionStore.messages.last().text.contains("已取消后台任务：task-1"))
        }

    @Test
    fun restoreStartupStateRestoresPendingAgentConfirmationWithoutExecutingTool() = runTest(dispatcher) {
        val request = ToolRequest(
            id = "request-restored",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = "Open Wi-Fi",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "将打开系统 Wi-Fi 设置页。",
            parameters = emptyMap(),
        )
        val executor = RecordingToolExecutor()
        val assistantRouter = FakeAssistantRouter(
            restoredPendingRoute = AssistantRoute.Action(
                runId = "run-restored",
                toolRequest = request,
                draft = draft,
                plannedByModel = false,
                fallbackReason = "restored",
                skillId = BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL,
            ),
        )
        val viewModel = createViewModel(
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        val restored = viewModel.uiState.value.pendingConfirmation
        requireNotNull(restored)
        assertEquals("run-restored", restored.runId)
        assertEquals("request-restored", restored.toolRequest?.id)
        assertTrue(executor.executedRequests.isEmpty())

        viewModel.sendMessage("不应越过恢复的待确认动作")
        advanceUntilIdle()

        assertEquals("请先确认或取消待执行动作", viewModel.uiState.value.statusText)
        assertTrue(executor.executedRequests.isEmpty())
    }

    @Test
    fun restoredPendingConfirmationExecutesAndObservesOnlyOnce() = runTest(dispatcher) {
        val request = ToolRequest(
            id = "request-restored-once",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = "Open Wi-Fi",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "将打开系统 Wi-Fi 设置页。",
            parameters = emptyMap(),
        )
        val sessionStore = FakeSessionStore()
        val executor = RecordingToolExecutor()
        val assistantRouter = FakeAssistantRouter(
            restoredPendingRoute = AssistantRoute.Action(
                runId = "run-restored-once",
                toolRequest = request,
                draft = draft,
                plannedByModel = false,
                fallbackReason = "restored",
                skillId = BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL,
            ),
            confirmedRun = AgentRun(
                id = "run-restored-once",
                input = "打开 Wi-Fi 设置",
                state = AgentRunState.ExecutingTool,
                createdAtMillis = 1L,
                updatedAtMillis = 2L,
            ),
            toolObservation = AgentObservationResult(
                run = AgentRun(
                    id = "run-restored-once",
                    input = "打开 Wi-Fi 设置",
                    state = AgentRunState.Completed,
                    createdAtMillis = 1L,
                    updatedAtMillis = 3L,
                ),
                result = ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已打开 Wi-Fi 设置页",
                ),
                assistantMessage = "工具执行结果：已打开 Wi-Fi 设置页",
                decision = AgentObservationDecision.Complete,
                steps = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        val restored = viewModel.uiState.value.pendingConfirmation
        requireNotNull(restored)

        viewModel.confirmAgentConfirmation(restored)
        viewModel.confirmAgentConfirmation(restored)
        advanceUntilIdle()

        assertEquals(1, executor.executedRequests.size)
        assertEquals(request.id, executor.executedRequests.single().id)
        assertEquals(1, assistantRouter.confirmCallCount)
        assertEquals(1, assistantRouter.observeToolCallCount)
        assertEquals(request.id, assistantRouter.lastObservedResult?.requestId)
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertTrue(sessionStore.messages.last().text.contains("已打开 Wi-Fi 设置页"))
    }

    @Test
    fun restoredSharePendingPreviewDoesNotExecuteUntilCurrentConfirmation() = runTest(dispatcher) {
        val modelSummary = "摘要待分享 canary"
        val stalePayload = "旧剪贴板 canary"
        val request = ToolRequest(
            id = "request-restored-share",
            toolName = MobileActionFunctions.SHARE_TEXT,
            arguments = mapOf("text" to modelSummary),
            reason = "分享本地摘要",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "分享摘要",
            summary = "将打开系统分享面板。",
            parameters = request.arguments,
        )
        val sessionStore = FakeSessionStore()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val executor = RecordingToolExecutor()
        val assistantRouter = FakeAssistantRouter(
            restoredPendingRoute = AssistantRoute.Action(
                runId = "run-restored-share",
                toolRequest = request,
                draft = draft,
                plannedByModel = false,
                fallbackReason = "skill model step",
                skillId = BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL,
            ),
            confirmedRun = AgentRun(
                id = "run-restored-share",
                input = "总结剪贴板并分享",
                state = AgentRunState.ExecutingTool,
                createdAtMillis = 1L,
                updatedAtMillis = 2L,
            ),
            toolObservation = AgentObservationResult(
                run = AgentRun(
                    id = "run-restored-share",
                    input = "总结剪贴板并分享",
                    state = AgentRunState.Completed,
                    createdAtMillis = 1L,
                    updatedAtMillis = 3L,
                ),
                result = ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已打开系统分享面板",
                ),
                assistantMessage = "外部界面已打开，最终结果未验证。",
                decision = AgentObservationDecision.Complete,
                steps = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = "/tmp/model.litertlm"),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        val restored = viewModel.uiState.value.pendingConfirmation
        requireNotNull(restored)
        assertEquals(modelSummary, restored.draft.parameters["text"])
        assertEquals(modelSummary, restored.toolRequest?.arguments?.get("text"))
        assertTrue(executor.executedRequests.isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())

        val staleConfirmation = restored.copy(
            draft = restored.draft.copy(parameters = mapOf("text" to stalePayload)),
            toolRequest = restored.toolRequest?.copy(arguments = mapOf("text" to stalePayload)),
        )
        viewModel.confirmAgentConfirmation(staleConfirmation)
        advanceUntilIdle()

        assertEquals("工具确认已处理", viewModel.uiState.value.statusText)
        assertEquals(restored, viewModel.uiState.value.pendingConfirmation)
        assertTrue(executor.executedRequests.isEmpty())
        assertEquals(0, assistantRouter.confirmCallCount)

        viewModel.confirmAgentConfirmation(restored)
        advanceUntilIdle()

        assertEquals(1, executor.executedRequests.size)
        assertEquals(request.id, executor.executedRequests.single().id)
        assertEquals(modelSummary, executor.executedRequests.single().arguments["text"])
        assertEquals(1, assistantRouter.confirmCallCount)
        assertEquals(1, assistantRouter.observeToolCallCount)
        assertEquals(request.id, assistantRouter.lastObservedResult?.requestId)
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
    }

    @Test
    fun restoreStartupStateLoadsLongTermMemoryRecordsWithoutRemoteWork() = runTest(dispatcher) {
        val memoryRepository = MemoryRepository(recordStore = FakeMemoryRecordStore())
        memoryRepository.indexPreference("pref-1", "回答尽量简洁")
        memoryRepository.indexTaskState("task-1", "等待确认分享摘要")
        val remoteRuntime = RecordingRemoteChatRuntime()
        val executor = RecordingToolExecutor()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            actionExecutor = executor,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertEquals(
            listOf("pref-1", "task-1"),
            viewModel.uiState.value.longTermMemories.map { it.id },
        )
        assertTrue(remoteRuntime.calls.isEmpty())
        assertTrue(executor.executedRequests.isEmpty())
    }

    @Test
    fun restoreStartupStateSyncsVerifiedMemoryModelBeforeRebuildingMemoryIndex() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(
            recordStore = store,
            semanticRuntimeFactory = { path ->
                check(path == "/verified/memory.litertlm")
                ConciseSemanticRuntime()
            },
        )
        memoryRepository.indexPreference("pref-1", "I prefer concise answers")
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            modelRepository = FakeModelRepository(memoryEmbeddingModelPath = "/verified/memory.litertlm"),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertTrue(memoryRepository.semanticMemoryEnabled)
        assertEquals("/verified/memory.litertlm", memoryRepository.activeMemoryModelPath)
        val hits = memoryRepository.search("brief replies")
        assertEquals(listOf("pref-1"), hits.map { it.id })
        assertEquals(MemoryRecallMode.Semantic, hits.first().recallMode)
    }

    @Test
    fun restoreStartupStateIndexesScheduledTasksAsForgettableTaskState() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask(
                    id = "task-1",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Scheduled,
                    title = "喝水 私密prompt 私密toolArg 私密remoteResponse",
                    body = "不要写入长期记忆的提醒正文",
                ),
                scheduledTask(
                    id = PeriodicCheckScheduleRequest.TASK_ID,
                    type = ScheduledTaskType.PeriodicCheck,
                    status = ScheduledTaskStatus.Running,
                    title = "后台检查",
                    body = "enabled=true;lastRun=secret",
                ),
                scheduledTask(
                    id = "done-task",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Delivered,
                    title = "已完成提醒",
                    body = "已完成正文",
                ),
            ),
        )
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            backgroundTaskScheduler = scheduler,
            remoteRuntime = remoteRuntime,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        val taskRecords = store.records().filter { record -> record.type == MemoryRecordType.TaskState }
        assertEquals(
            listOf(taskStateMemoryRecordId("task-1"), taskStateMemoryRecordId(PeriodicCheckScheduleRequest.TASK_ID)),
            taskRecords.map { record -> record.id },
        )
        assertTrue(taskRecords.first().text.contains("Reminder"))
        assertTrue(taskRecords.first().text.contains("Scheduled"))
        assertTrue(taskRecords.first().text.contains(taskStateMemoryRecordId("task-1")))
        assertFalse(taskRecords.first().text.contains("喝水"))
        assertFalse(taskRecords.first().text.contains("私密prompt"))
        assertFalse(taskRecords.first().text.contains("私密toolArg"))
        assertFalse(taskRecords.first().text.contains("私密remoteResponse"))
        assertFalse(taskRecords.first().text.contains("不要写入长期记忆"))
        assertTrue(taskRecords.none { record -> record.id == taskStateMemoryRecordId("done-task") })
        assertEquals(taskRecords.map { it.id }, viewModel.uiState.value.longTermMemories.map { it.id })
        assertTrue(viewModel.uiState.value.longTermMemories.none { memory -> memory.text.contains("喝水") })
        assertTrue(memoryRepository.search("喝水").isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun restoreStartupStateReschedulesReminderAlarmsBeforeLoadingBackgroundTasks() = runTest(dispatcher) {
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask(
                    id = "task-1",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Scheduled,
                    title = "喝水",
                ),
            ),
        )
        val viewModel = createViewModel(backgroundTaskScheduler = scheduler)

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertEquals(1, scheduler.rescheduleCount)
        assertEquals(listOf("task-1"), viewModel.uiState.value.backgroundTasks.map { it.id })
    }

    @Test
    fun backgroundTaskStateMemoryDoesNotEnterRemotePromptOrHistory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask(
                    id = "task-1",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Scheduled,
                    title = "喝水",
                    body = "不要进入远程的提醒正文",
                ),
            ),
        )
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            backgroundTaskScheduler = scheduler,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val taskMemoryId = taskStateMemoryRecordId("task-1")
        val taskRecords = store.records().filter { it.type == MemoryRecordType.TaskState }
        assertEquals(listOf(taskMemoryId), taskRecords.map { it.id })
        assertTrue(viewModel.uiState.value.longTermMemories.any { it.id == taskMemoryId })
        assertTrue(viewModel.uiState.value.backgroundTasks.any { it.id == "task-1" })

        val call = remoteRuntime.calls.single()
        assertEquals("普通远程问题", call.prompt)
        assertTrue(call.history.isEmpty())
        assertFalse(call.prompt.contains("喝水"))
        assertFalse(call.prompt.contains("不要进入远程的提醒正文"))
        assertFalse(call.history.toString().contains("喝水"))
        assertFalse(call.history.toString().contains("不要进入远程的提醒正文"))
    }

    @Test
    fun cancelBackgroundTaskForgetsTaskStateMemory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask(
                    id = "task-1",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Scheduled,
                    title = "喝水",
                ),
            ),
        )
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            backgroundTaskScheduler = scheduler,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        assertTrue(store.records().any { record -> record.id == taskStateMemoryRecordId("task-1") })

        viewModel.cancelBackgroundTask("task-1")
        advanceUntilIdle()

        assertTrue(store.records().none { record -> record.id == taskStateMemoryRecordId("task-1") })
        assertTrue(viewModel.uiState.value.longTermMemories.none { memory -> memory.id == taskStateMemoryRecordId("task-1") })
        assertTrue(memoryRepository.search("喝水").isEmpty())
        assertEquals("后台任务已取消", viewModel.uiState.value.statusText)
    }

    @Test
    fun refreshBackgroundTasksDropsTerminalTaskStateMemory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val taskMemoryId = taskStateMemoryRecordId("done-task")
        memoryRepository.indexTaskState(taskMemoryId, "旧的后台任务状态")
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask(
                    id = "done-task",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Failed,
                    title = "失败提醒",
                    body = "失败正文",
                ),
            ),
        )
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            backgroundTaskScheduler = scheduler,
        )

        viewModel.refreshBackgroundTasks()
        advanceUntilIdle()

        assertTrue(store.records().none { record -> record.id == taskMemoryId })
        assertTrue(viewModel.uiState.value.longTermMemories.none { memory -> memory.id == taskMemoryId })
        assertTrue(memoryRepository.search("后台任务状态").isEmpty())
    }

    @Test
    fun forgetActiveTaskStateMemoryDoesNotReappearOnRefreshOrChat() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask(
                    id = "task-1",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Scheduled,
                    title = "喝水",
                ),
            ),
        )
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            backgroundTaskScheduler = scheduler,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        val taskMemoryId = taskStateMemoryRecordId("task-1")
        assertTrue(store.hasRecord(taskMemoryId, MemoryRecordType.TaskState))

        viewModel.forgetLongTermMemory(taskMemoryId)
        advanceUntilIdle()

        assertTrue(store.records().none { it.id == taskMemoryId && it.type == MemoryRecordType.TaskState })
        assertTrue(store.hasSuppressedTaskState(taskMemoryId))
        assertTrue(viewModel.uiState.value.longTermMemories.none { memory -> memory.id == taskMemoryId })
        assertTrue(memoryRepository.search("后台任务 Reminder").isEmpty())

        viewModel.refreshBackgroundTasks()
        advanceUntilIdle()

        assertTrue(store.records().none { it.id == taskMemoryId && it.type == MemoryRecordType.TaskState })
        assertTrue(store.hasSuppressedTaskState(taskMemoryId))
        assertTrue(viewModel.uiState.value.longTermMemories.none { memory -> memory.id == taskMemoryId })

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)
        assertTrue(store.records().none { it.id == taskMemoryId && it.type == MemoryRecordType.TaskState })
        assertTrue(store.hasSuppressedTaskState(taskMemoryId))
        assertTrue(viewModel.uiState.value.longTermMemories.none { memory -> memory.id == taskMemoryId })
        assertTrue(memoryRepository.search("后台任务 Reminder").isEmpty())
    }

    @Test
    fun memoryStoreFailureDoesNotBlockStartupOrRemoteChat() = runTest(dispatcher) {
        val memoryRepository = MemoryRepository(
            recordStore = FakeMemoryRecordStore(failure = IllegalStateException("memory db unavailable")),
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)
        assertEquals("普通远程问题", sessionStore.messages.first().text)
        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
    }

    @Test
    fun rememberCommandPersistsPreferenceMemoryOnceForDuplicateCommands() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("记住：我喜欢简洁回答")
        advanceUntilIdle()
        viewModel.sendMessage("记住：我喜欢简洁回答")
        advanceUntilIdle()

        val record = store.records().single()
        assertTrue(record.id.startsWith("preference-"))
        assertEquals(MemoryRecordType.Preference, record.type)
        assertEquals("用户偏好：我喜欢简洁回答", record.text)
        assertTrue(memoryRepository.search("简洁回答").any { it.id == record.id })
        assertEquals(listOf(record.id), viewModel.uiState.value.longTermMemories.map { it.id })
        assertEquals("记住：我喜欢简洁回答", viewModel.uiState.value.messages.first().text)
        assertEquals(2, remoteRuntime.calls.size)
    }

    @Test
    fun rememberCommandReplacesConflictingPreferenceMemory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("记住：回答尽量简洁")
        advanceUntilIdle()
        viewModel.sendMessage("记住：回答要详细")
        advanceUntilIdle()

        val record = store.records().single()
        assertEquals(MemoryRecordType.Preference, record.type)
        assertEquals("用户偏好：回答要详细", record.text)
        assertEquals(listOf(record.id), viewModel.uiState.value.longTermMemories.map { it.id })
        assertTrue(memoryRepository.search("简洁").isEmpty())
        assertEquals(record.id, memoryRepository.search("详细回答").first().id)
        assertEquals(2, remoteRuntime.calls.size)
    }

    @Test
    fun rememberCommandPersistsEnglishPreferenceMemory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("remember that I prefer concise answers")
        advanceUntilIdle()

        val record = store.records().single()
        assertEquals(MemoryRecordType.Preference, record.type)
        assertEquals("用户偏好：I prefer concise answers", record.text)
        assertTrue(memoryRepository.search("concise answers").any { it.id == record.id })
        assertEquals(listOf(record.id), viewModel.uiState.value.longTermMemories.map { it.id })
    }

    @Test
    fun forgetRememberCommandMemoryDoesNotReindexFromHistory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("记住：我喜欢简洁回答")
        advanceUntilIdle()
        val recordId = store.records().single().id

        viewModel.forgetLongTermMemory(recordId)
        advanceUntilIdle()

        assertTrue(store.records().isEmpty())
        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
        val hitsAfterForget = memoryRepository.search("简洁回答")
        assertTrue(hitsAfterForget.joinToString { "${it.id}:${it.text}" }, hitsAfterForget.isEmpty())
    }

    @Test
    fun rememberCommandDoesNotPersistWhenRouteFailsBeforeMessageIsSaved() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            memoryRepository = memoryRepository,
            assistantRouter = FakeAssistantRouter(routeFailure = IllegalStateException("planner unavailable")),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("记住：我喜欢简洁回答")
        advanceUntilIdle()

        assertTrue(store.records().isEmpty())
        assertTrue(sessionStore.messages.isEmpty())
        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
    }

    @Test
    fun forgetLongTermMemoryRefreshesUiAndMemoryIndex() = runTest(dispatcher) {
        val memoryRepository = MemoryRepository(recordStore = FakeMemoryRecordStore())
        memoryRepository.indexPreference("pref-1", "回答尽量简洁")
        memoryRepository.indexTaskState("task-1", "等待确认分享摘要")
        val viewModel = createViewModel(memoryRepository = memoryRepository)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.forgetLongTermMemory("pref-1")
        advanceUntilIdle()

        assertEquals(listOf("task-1"), viewModel.uiState.value.longTermMemories.map { it.id })
        assertTrue(memoryRepository.search("简洁回答").isEmpty())
        assertEquals("已遗忘这条记忆", viewModel.uiState.value.statusText)
    }

    @Test
    fun clearLongTermMemoryDoesNotDeleteSessionOrCallRemoteRuntime() = runTest(dispatcher) {
        val memoryRepository = MemoryRepository(recordStore = FakeMemoryRecordStore())
        memoryRepository.indexPreference("pref-1", "回答尽量简洁")
        val sessionStore = FakeSessionStore().apply {
            messages = listOf(
                ChatMessage(
                    role = MessageRole.User,
                    text = "普通会话内容",
                    id = 10L,
                ),
            )
        }
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = configuredRemoteModel(),
            ),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        val messagesBeforeClear = sessionStore.messages
        viewModel.clearLongTermMemory()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
        assertTrue(memoryRepository.search("简洁回答").isEmpty())
        assertEquals(messagesBeforeClear, sessionStore.messages)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals("长期记忆已清空", viewModel.uiState.value.statusText)
    }

    @Test
    fun clearLongTermMemorySuppressesActiveTaskStateMemoryResync() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask(
                    id = "task-1",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Running,
                    title = "喝水",
                ),
            ),
        )
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            backgroundTaskScheduler = scheduler,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        val taskMemoryId = taskStateMemoryRecordId("task-1")
        assertTrue(store.hasRecord(taskMemoryId, MemoryRecordType.TaskState))

        viewModel.clearLongTermMemory()
        advanceUntilIdle()

        assertTrue(store.records().none { it.id == taskMemoryId && it.type == MemoryRecordType.TaskState })
        assertTrue(store.hasSuppressedTaskState(taskMemoryId))
        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
        assertTrue(memoryRepository.search("后台任务 Reminder").isEmpty())

        viewModel.refreshBackgroundTasks()
        advanceUntilIdle()

        assertTrue(store.records().none { it.id == taskMemoryId && it.type == MemoryRecordType.TaskState })
        assertTrue(store.hasSuppressedTaskState(taskMemoryId))
        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
        assertTrue(memoryRepository.search("后台任务 Reminder").isEmpty())
    }

    @Test
    fun restoreStartupStateLoadsRunningBackgroundTasksWithoutRemoteWork() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask("task-1", ScheduledTaskType.Reminder, ScheduledTaskStatus.Scheduled),
                scheduledTask("task-running", ScheduledTaskType.Reminder, ScheduledTaskStatus.Running),
                scheduledTask(
                    id = "task-2",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Delivered,
                    updatedAtMillis = 2_000L,
                ),
                scheduledTask(
                    id = "task-3",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Failed,
                    updatedAtMillis = 3_000L,
                ),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val executor = RecordingToolExecutor()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            backgroundTaskScheduler = scheduler,
            remoteRuntime = remoteRuntime,
            actionExecutor = executor,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertEquals(listOf("task-1", "task-running"), viewModel.uiState.value.backgroundTasks.map { it.id })
        assertEquals(listOf("task-3", "task-2"), viewModel.uiState.value.backgroundTaskHistory.map { it.id })
        assertEquals(
            listOf(taskStateMemoryRecordId("task-1"), taskStateMemoryRecordId("task-running")),
            store.records()
                .filter { record -> record.type == MemoryRecordType.TaskState }
                .map { record -> record.id },
        )

        viewModel.refreshBackgroundTasks()
        advanceUntilIdle()

        assertEquals(listOf("task-1", "task-running"), viewModel.uiState.value.backgroundTasks.map { it.id })
        assertEquals(listOf("task-3", "task-2"), viewModel.uiState.value.backgroundTaskHistory.map { it.id })
        assertTrue(remoteRuntime.calls.isEmpty())
        assertTrue(executor.executedRequests.isEmpty())
    }

    @Test
    fun restoreStartupStateLoadsRecentAuditEventsWithoutRemoteWork() = runTest(dispatcher) {
        val rawApiKey = "sk-" + "a".repeat(32)
        val auditLog = FakeToolAuditLog(
            records = listOf(
                toolAuditRecord(
                    id = "audit-newest",
                    requestId = "request-newest",
                    toolName = MobileActionFunctions.READ_CLIPBOARD,
                    summary = "工具观察：sk-[redacted]",
                    createdAtMillis = 3_000L,
                ),
                toolAuditRecord(
                    id = "audit-older",
                    requestId = "request-older",
                    toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                    summary = "已创建提醒",
                    createdAtMillis = 1_000L,
                ),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val executor = RecordingToolExecutor()
        val viewModel = createViewModel(
            toolAuditLog = auditLog,
            remoteRuntime = remoteRuntime,
            actionExecutor = executor,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertEquals(listOf("audit-newest", "audit-older"), viewModel.uiState.value.auditEvents.map { it.id })
        assertTrue(auditLog.requestedLimits.isNotEmpty())
        assertTrue(auditLog.requestedLimits.all { it == 50 })
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, viewModel.uiState.value.auditEvents.first().toolName)
        assertFalse(viewModel.uiState.value.auditEvents.toString().contains(rawApiKey))
        assertTrue(remoteRuntime.calls.isEmpty())
        assertTrue(executor.executedRequests.isEmpty())
    }

    @Test
    fun refreshAuditEventsUpdatesUiState() = runTest(dispatcher) {
        val auditLog = FakeToolAuditLog()
        val viewModel = createViewModel(toolAuditLog = auditLog)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.auditEvents.isEmpty())

        auditLog.records = listOf(
            toolAuditRecord(
                id = "audit-1",
                requestId = "request-1",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                status = "Succeeded",
                riskLevel = "LowReadOnly",
                permissions = listOf("ReadsFiles"),
                summary = "工具执行成功，结果详情不在审计视图中展示。",
                createdAtMillis = 2_000L,
            ),
        )

        viewModel.refreshAuditEvents()
        advanceUntilIdle()

        val event = viewModel.uiState.value.auditEvents.single()
        assertEquals("audit-1", event.id)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, event.toolName)
        assertEquals("Succeeded", event.status)
        assertEquals("LowReadOnly", event.riskLevel)
        assertEquals(listOf("ReadsFiles"), event.permissions)
        assertEquals("工具执行成功，结果详情不在审计视图中展示。", event.summary)
    }

    @Test
    fun refreshAuditEventsAlsoLoadsAgentTraceSummaries() = runTest(dispatcher) {
        val assistantRouter = FakeAssistantRouter(
            recentTraceRuns = listOf(
                agentTraceRunSummary(
                    runId = "run-1",
                    input = "打开 Wi-Fi 设置",
                    state = AgentRunState.Completed,
                    stepSummary = "Requested confirmation for open_wifi_settings.",
                ),
            ),
        )
        val viewModel = createViewModel(assistantRouter = assistantRouter)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.refreshAuditEvents()
        advanceUntilIdle()

        assertEquals(5, assistantRouter.recentTraceRunLimit)
        assertEquals(8, assistantRouter.recentTraceStepLimit)
        val traceRun = viewModel.uiState.value.agentTraceRuns.single()
        assertEquals("run-1", traceRun.id)
        assertEquals(AgentRunState.Completed, traceRun.state)
        assertEquals("Requested confirmation for open_wifi_settings.", traceRun.steps.single().summary)
    }

    @Test
    fun cancelRunningBackgroundTaskRefreshesUiAndCancelsScheduler() = runTest(dispatcher) {
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(scheduledTask("task-1", ScheduledTaskType.Reminder, ScheduledTaskStatus.Scheduled)),
        )
        val viewModel = createViewModel(backgroundTaskScheduler = scheduler)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.cancelBackgroundTask("task-1")
        advanceUntilIdle()

        assertEquals(listOf("task-1"), scheduler.cancelledTaskIds)
        assertTrue(viewModel.uiState.value.backgroundTasks.isEmpty())
        assertEquals(listOf("task-1"), viewModel.uiState.value.backgroundTaskHistory.map { it.id })
        assertEquals("后台任务已取消", viewModel.uiState.value.statusText)
    }

    @Test
    fun cancelRunningBackgroundTaskFailureKeepsTaskVisible() = runTest(dispatcher) {
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(scheduledTask("task-1", ScheduledTaskType.Reminder, ScheduledTaskStatus.Scheduled)),
            cancelFailure = IllegalStateException("alarm unavailable"),
        )
        val viewModel = createViewModel(backgroundTaskScheduler = scheduler)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.cancelBackgroundTask("task-1")
        advanceUntilIdle()

        assertEquals(listOf("task-1"), scheduler.cancelledTaskIds)
        assertEquals(listOf("task-1"), viewModel.uiState.value.backgroundTasks.map { it.id })
        assertTrue(viewModel.uiState.value.statusText.contains("后台任务取消失败"))
    }

    @Test
    fun cancelRunningBackgroundTaskFailureRefreshesStaleTaskLists() = runTest(dispatcher) {
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(scheduledTask("task-1", ScheduledTaskType.Reminder, ScheduledTaskStatus.Scheduled)),
            cancelFailure = IllegalStateException("already running"),
            cancelFailureStatus = ScheduledTaskStatus.Running,
        )
        val viewModel = createViewModel(backgroundTaskScheduler = scheduler)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.cancelBackgroundTask("task-1")
        advanceUntilIdle()

        assertEquals(listOf("task-1"), scheduler.cancelledTaskIds)
        assertEquals(listOf("task-1"), viewModel.uiState.value.backgroundTasks.map { it.id })
        assertEquals(ScheduledTaskStatus.Running, viewModel.uiState.value.backgroundTasks.single().status)
        assertTrue(viewModel.uiState.value.statusText.contains("后台任务取消失败"))
    }

    @Test
    fun setPeriodicCheckPolicySchedulesDefaultPolicyAndRefreshesUi() = runTest(dispatcher) {
        val scheduler = FakeBackgroundTaskScheduler()
        val viewModel = createViewModel(backgroundTaskScheduler = scheduler)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.setPeriodicCheckPolicy(PeriodicCheckScheduleRequest())
        advanceUntilIdle()

        assertEquals(listOf(PeriodicCheckScheduleRequest.TASK_ID), viewModel.uiState.value.backgroundTasks.map { it.id })
        val request = scheduler.periodicPolicyRequests.single()
        assertEquals(PeriodicCheckScheduleRequest.DEFAULT_INTERVAL_MINUTES, request.intervalMinutes)
        assertEquals(
            PeriodicCheckScheduleRequest.DEFAULT_MIN_NOTIFICATION_SPACING_MINUTES,
            request.minNotificationSpacingMinutes,
        )
        assertEquals(PeriodicCheckScheduleRequest.DEFAULT_OVERDUE_GRACE_MINUTES, request.overdueGraceMinutes)
        assertTrue(viewModel.uiState.value.periodicCheckPolicy.request.enabled)
        assertEquals(ScheduledTaskStatus.Scheduled, viewModel.uiState.value.periodicCheckPolicy.taskStatus)
        assertEquals("周期检查策略已保存", viewModel.uiState.value.statusText)
    }

    @Test
    fun setPeriodicCheckPolicyFailureDoesNotShowHealthyRunningTask() = runTest(dispatcher) {
        val scheduler = FakeBackgroundTaskScheduler(
            periodicSetFailure = IllegalStateException("work enqueue unavailable"),
        )
        val viewModel = createViewModel(backgroundTaskScheduler = scheduler)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.setPeriodicCheckPolicy(PeriodicCheckScheduleRequest())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.backgroundTasks.isEmpty())
        assertEquals(
            listOf(PeriodicCheckScheduleRequest.TASK_ID),
            viewModel.uiState.value.backgroundTaskHistory.map { it.id },
        )
        assertEquals(ScheduledTaskStatus.Failed, viewModel.uiState.value.periodicCheckPolicy.taskStatus)
        assertTrue(viewModel.uiState.value.statusText.contains("周期检查策略保存失败"))
    }

    @Test
    fun disablePeriodicCheckPolicyMovesTaskToHistory() = runTest(dispatcher) {
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask(
                    id = PeriodicCheckScheduleRequest.TASK_ID,
                    type = ScheduledTaskType.PeriodicCheck,
                    status = ScheduledTaskStatus.Scheduled,
                    title = PeriodicCheckScheduleRequest.TITLE,
                    body = PeriodicCheckScheduleRequest().storageSummary(),
                ),
            ),
        )
        val viewModel = createViewModel(backgroundTaskScheduler = scheduler)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.disablePeriodicCheckPolicy()
        advanceUntilIdle()

        assertEquals(1, scheduler.disablePeriodicCheckCount)
        assertTrue(viewModel.uiState.value.backgroundTasks.isEmpty())
        assertEquals(
            listOf(PeriodicCheckScheduleRequest.TASK_ID),
            viewModel.uiState.value.backgroundTaskHistory.map { it.id },
        )
        assertFalse(viewModel.uiState.value.periodicCheckPolicy.request.enabled)
        assertEquals(ScheduledTaskStatus.Cancelled, viewModel.uiState.value.periodicCheckPolicy.taskStatus)
        assertEquals("周期检查已关闭", viewModel.uiState.value.statusText)
    }

    @Test
    fun disablePeriodicCheckPolicyFailureKeepsRunningTaskVisible() = runTest(dispatcher) {
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask(
                    id = PeriodicCheckScheduleRequest.TASK_ID,
                    type = ScheduledTaskType.PeriodicCheck,
                    status = ScheduledTaskStatus.Scheduled,
                    title = PeriodicCheckScheduleRequest.TITLE,
                    body = PeriodicCheckScheduleRequest().storageSummary(),
                ),
            ),
            periodicDisableFailure = IllegalStateException("work cancel unavailable"),
        )
        val viewModel = createViewModel(backgroundTaskScheduler = scheduler)
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.disablePeriodicCheckPolicy()
        advanceUntilIdle()

        assertEquals(listOf(PeriodicCheckScheduleRequest.TASK_ID), viewModel.uiState.value.backgroundTasks.map { it.id })
        assertEquals(ScheduledTaskStatus.Scheduled, viewModel.uiState.value.periodicCheckPolicy.taskStatus)
        assertTrue(viewModel.uiState.value.statusText.contains("周期检查关闭失败"))
    }

    private fun createViewModel(
        sessionStore: FakeSessionStore = FakeSessionStore(),
        modelRepository: FakeModelRepository = FakeModelRepository(),
        generationStore: FakeGenerationParametersStore = FakeGenerationParametersStore(),
        remoteStore: FakeRemoteModelStore = FakeRemoteModelStore(),
        firstRunStore: FakeFirstRunSetupStore = FakeFirstRunSetupStore(),
        downloadClient: FakeModelDownloadClient = FakeModelDownloadClient(),
        runtime: FakeLiteRtRuntime = FakeLiteRtRuntime(),
        remoteRuntime: RecordingRemoteChatRuntime = RecordingRemoteChatRuntime(),
        memoryRepository: MemoryRepository = MemoryRepository(),
        backgroundTaskScheduler: BackgroundTaskScheduler = FakeBackgroundTaskScheduler(),
        toolAuditLog: ToolAuditLog = FakeToolAuditLog(),
        assistantRouter: AssistantRouter = FakeAssistantRouter(),
        actionExecutor: ToolExecutor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult =
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "executed",
                )
        },
    ): PocketMindViewModel =
        PocketMindViewModel(
            modelRepository = modelRepository,
            sessionRepository = sessionStore,
            generationParametersRepository = generationStore,
            remoteModelRepository = remoteStore,
            firstRunSetupRepository = firstRunStore,
            downloadService = downloadClient,
            runtime = runtime,
            remoteRuntime = remoteRuntime,
            memoryRepository = memoryRepository,
            longTermMemoryControls = memoryRepository,
            backgroundTaskScheduler = backgroundTaskScheduler,
            toolAuditLog = toolAuditLog,
            actionExecutor = actionExecutor,
            assistantOrchestrator = assistantRouter,
            isArm64DeviceProvider = { true },
            ioDispatcher = dispatcher,
        )

    private fun configuredRemoteModel(): RemoteModelConfig =
        RemoteModelConfig(
            baseUrl = "https://api.example.com/v1",
            modelName = "model-a",
        )

    private data class RemoteCall(
        val prompt: String,
        val history: List<ChatMessage>,
    )

    private fun agentTraceRunSummary(
        runId: String,
        input: String,
        state: AgentRunState,
        stepSummary: String,
    ): AgentTraceRunSummary =
        AgentTraceRunSummary(
            run = AgentRun(
                id = runId,
                input = input,
                state = state,
                createdAtMillis = 1_000L,
                updatedAtMillis = 2_000L,
            ),
            steps = listOf(
                AgentTraceStepSummary(
                    runId = runId,
                    position = 0,
                    type = "UserConfirmationRequested",
                    summary = stepSummary,
                    json = "{}",
                    createdAtMillis = 1_500L,
                ),
            ),
        )

    private fun MemoryRecordStore.hasRecord(id: String, type: MemoryRecordType): Boolean =
        records().any { record -> record.id == id && record.type == type }

    private fun MemoryRecordStore.hasSuppressedTaskState(taskStateMemoryId: String): Boolean =
        records().any { record ->
            record.type == MemoryRecordType.SuppressedTaskState &&
                record.text == taskStateMemoryId
        }

    private class RecordingRemoteChatRuntime : RemoteChatRuntime {
        val calls = mutableListOf<RemoteCall>()

        override fun send(
            prompt: String,
            history: List<ChatMessage>,
            parameters: GenerationParameters,
            config: RemoteModelConfig,
        ): Flow<String> {
            calls += RemoteCall(prompt, history)
            return flowOf("远程回复")
        }

        override fun stop() = Unit
    }

    private class RecordingToolExecutor : ToolExecutor {
        val executedRequests = mutableListOf<ToolRequest>()

        override fun execute(request: ToolRequest): ToolResult {
            executedRequests += request
            return ToolResult(
                requestId = request.id,
                status = ToolStatus.Succeeded,
                summary = "executed",
            )
        }
    }

    private class FakeLiteRtRuntime(
        private val localResponse: String = "本地回复",
    ) : LiteRtRuntime {
        override var isLoaded: Boolean = false

        override fun load(
            modelPath: String,
            backend: BackendChoice,
            history: List<ChatMessage>,
            parameters: GenerationParameters,
        ) {
            isLoaded = true
        }

        override fun recreateConversation(
            history: List<ChatMessage>,
            parameters: GenerationParameters,
        ) {
            isLoaded = true
        }

        override fun send(prompt: String): Flow<String> = flowOf(localResponse)

        override fun lastGenerationStats(): GenerationStats? = null

        override fun stop() = Unit

        override fun close() {
            isLoaded = false
        }
    }

    private class FakeAssistantRouter(
        private val routeResult: AssistantRoute? = null,
        private val routeFailure: Throwable? = null,
        private val confirmedRun: AgentRun? = null,
        private val confirmFailure: Throwable? = null,
        private val confirmedRunsById: Map<String, AgentRun> = emptyMap(),
        private val cancelObservation: AgentObservationResult? = null,
        private val toolObservation: AgentObservationResult? = null,
        private val toolObservationsByRunId: Map<String, AgentObservationResult> = emptyMap(),
        private val modelObservation: AgentModelObservationResult? = null,
        private val restoredPendingRoute: AssistantRoute.Action? = null,
        private val recoveryRoute: AssistantRoute? = null,
        private val recentTraceRuns: List<AgentTraceRunSummary> = emptyList(),
    ) : AssistantRouter {
        var routeCallCount: Int = 0
            private set
        var requestRecoveryCallCount: Int = 0
            private set
        var confirmCallCount: Int = 0
            private set
        var cancelCallCount: Int = 0
            private set
        var observeToolCallCount: Int = 0
            private set
        var failPendingCallCount: Int = 0
            private set
        var lastObservedResult: ToolResult? = null
            private set
        var lastFailedPendingResult: ToolResult? = null
            private set
        var recentTraceRunLimit: Int? = null
            private set
        var recentTraceStepLimit: Int? = null
            private set

        override fun route(
            input: String,
            installedCapabilities: Set<ModelCapability>,
            memoryEnabled: Boolean,
            actionModelPath: String?,
            deviceContext: com.bytedance.zgx.pocketmind.device.DeviceContextSnapshot?,
        ): AssistantRoute {
            routeCallCount += 1
            routeFailure?.let { throw it }
            return routeResult ?:
                AssistantRoute.Chat(
                    runId = null,
                    promptForModel = input,
                    memoryHits = emptyList(),
                    deviceContext = deviceContext,
                )
        }

        override fun requestRecoveryAction(action: AgentRecoveryAction): AssistantRoute {
            requestRecoveryCallCount += 1
            return recoveryRoute ?: AssistantRoute.Action(
                runId = "run-recovery",
                toolRequest = action.request,
                draft = action.draft,
                plannedByModel = false,
                fallbackReason = "typed recovery action",
            )
        }

        override fun failStaleInFlightRuns(reason: String): Int = 0

        override fun confirmToolRequest(runId: String, requestId: String): AgentRun? {
            confirmCallCount += 1
            confirmFailure?.let { throw it }
            return confirmedRunsById[runId] ?: confirmedRun
        }

        override fun cancelToolRequest(runId: String, requestId: String): AgentObservationResult? {
            cancelCallCount += 1
            return cancelObservation
        }

        override fun failPendingToolRequest(
            runId: String,
            requestId: String,
            result: ToolResult,
        ): AgentObservationResult? {
            failPendingCallCount += 1
            lastFailedPendingResult = result
            return AgentObservationResult(
                run = AgentRun(runId, "", AgentRunState.Failed, 1L, 2L),
                result = result,
                assistantMessage = "工具执行失败：${result.summary}",
                decision = AgentObservationDecision.Fail(result.summary),
                steps = emptyList(),
            )
        }

        override fun observeToolResult(runId: String, result: ToolResult): AgentObservationResult? {
            observeToolCallCount += 1
            lastObservedResult = result
            return toolObservationsByRunId[runId] ?: toolObservation
        }

        override fun observeModelResult(runId: String, text: String): AgentModelObservationResult? = modelObservation

        override fun restorePendingAction(): AssistantRoute.Action? = restoredPendingRoute

        override fun recentTraceRuns(limit: Int, stepLimit: Int): List<AgentTraceRunSummary> {
            recentTraceRunLimit = limit
            recentTraceStepLimit = stepLimit
            return recentTraceRuns
        }

        override fun close() = Unit
    }

    private class FakeSessionStore : SessionStore {
        override var activeSessionId: String = "session-1"
        var messages: List<ChatMessage> = emptyList()

        override fun summaries(): List<ChatSessionSummary> =
            listOf(ChatSessionSummary(activeSessionId, "测试会话", 1L, messages.size))

        override fun activeMessages(): List<ChatMessage> = messages

        override fun allMessages(limit: Int): List<ChatMessage> = messages.takeLast(limit)

        override fun createNewSession(): List<ChatMessage> {
            messages = emptyList()
            return messages
        }

        override fun selectSession(sessionId: String): List<ChatMessage>? {
            activeSessionId = sessionId
            return messages
        }

        override fun deleteActiveSession(): List<ChatMessage>? = null

        override fun replaceActiveSessionMessages(messages: List<ChatMessage>, persistNow: Boolean) {
            this.messages = messages
        }

        override fun persistActiveSessionFrom(messages: List<ChatMessage>) {
            this.messages = messages
        }
    }

    private class RecordingActionRuntime(
        private val likelyAction: Boolean,
    ) : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = likelyAction

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(ActionPlanKind.NoAction),
                usedModel = false,
                fallbackReason = null,
            )
    }

    private class FakeMemoryRecordStore(
        private val failure: Throwable? = null,
    ) : MemoryRecordStore {
        private val records = linkedMapOf<String, PersistedMemoryRecord>()

        override fun records(): List<PersistedMemoryRecord> {
            failure?.let { throw it }
            return records.values.toList()
        }

        override fun upsert(record: PersistedMemoryRecord) {
            failure?.let { throw it }
            records[record.id] = record
        }

        override fun delete(id: String): Boolean {
            failure?.let { throw it }
            return records.remove(id) != null
        }

        override fun clear() {
            failure?.let { throw it }
            records.clear()
        }
    }

    private class ConciseSemanticRuntime : EmbeddingRuntime {
        override val supportsSemanticRecall: Boolean = true
        override val semanticScoreThreshold: Float = 0.9f

        override fun embed(text: String): FloatArray {
            val lower = text.lowercase()
            return when {
                "concise" in lower -> floatArrayOf(1f, 0f)
                "brief" in lower -> floatArrayOf(1f, 0f)
                else -> floatArrayOf(0f, 1f)
            }
        }
    }

    private class FakeBackgroundTaskScheduler(
        scheduledTasks: List<ScheduledTask> = emptyList(),
        private val cancelFailure: Throwable? = null,
        private val cancelFailureStatus: ScheduledTaskStatus? = null,
        private val periodicSetFailure: Throwable? = null,
        private val periodicDisableFailure: Throwable? = null,
    ) : BackgroundTaskScheduler {
        private val tasks = linkedMapOf<String, ScheduledTask>()
        val cancelledTaskIds = mutableListOf<String>()
        val periodicPolicyRequests = mutableListOf<PeriodicCheckScheduleRequest>()
        var disablePeriodicCheckCount = 0
        var rescheduleCount = 0

        init {
            scheduledTasks.forEach { task -> tasks[task.id] = task }
        }

        override fun scheduledTasks(limit: Int): List<ScheduledTask> =
            tasks.values.sortedBy { it.triggerAtMillis }.take(limit)

        override fun recentTasks(limit: Int): List<ScheduledTask> =
            tasks.values
                .sortedWith(compareByDescending<ScheduledTask> { it.updatedAtMillis }.thenBy { it.id })
                .take(limit)

        override fun periodicCheckPolicy(): PeriodicCheckPolicySummary =
            tasks[PeriodicCheckScheduleRequest.TASK_ID]?.toPeriodicCheckPolicySummary()
                ?: PeriodicCheckPolicySummary.disabled()

        override fun setPeriodicCheckPolicy(
            request: PeriodicCheckScheduleRequest,
        ): Result<PeriodicCheckPolicySummary> {
            val normalized = request.normalized()
            periodicPolicyRequests += normalized
            if (!normalized.enabled) return disablePeriodicCheckPolicy()

            val task = periodicCheckTask(
                request = normalized,
                status = ScheduledTaskStatus.Scheduled,
            )
            tasks[task.id] = task
            periodicSetFailure?.let { throwable ->
                tasks[task.id] = task.copy(status = ScheduledTaskStatus.Failed)
                return Result.failure(throwable)
            }
            return Result.success(periodicCheckPolicy())
        }

        override fun disablePeriodicCheckPolicy(): Result<PeriodicCheckPolicySummary> {
            disablePeriodicCheckCount += 1
            periodicDisableFailure?.let { return Result.failure(it) }
            val existingRequest = tasks[PeriodicCheckScheduleRequest.TASK_ID]
                ?.let { PeriodicCheckScheduleRequest.fromStorageSummary(it.body) }
                ?: PeriodicCheckScheduleRequest()
            val task = periodicCheckTask(
                request = existingRequest.copy(enabled = false).normalized(),
                status = ScheduledTaskStatus.Cancelled,
            )
            tasks[task.id] = task
            return Result.success(periodicCheckPolicy())
        }

        override fun rescheduleScheduledReminders(limit: Int): Result<ReminderRescheduleReport> {
            rescheduleCount += 1
            val total = tasks.values.count { it.type == ScheduledTaskType.Reminder }
            return Result.success(
                ReminderRescheduleReport(
                    total = total,
                    scheduled = total,
                    failed = 0,
                ),
            )
        }

        override fun scheduleReminder(request: ReminderScheduleRequest): Result<ScheduledTask> {
            val task = ScheduledTask(
                id = "task-${tasks.size + 1}",
                type = ScheduledTaskType.Reminder,
                title = request.title,
                body = request.body,
                triggerAtMillis = 2_000L,
                status = ScheduledTaskStatus.Scheduled,
                createdAtMillis = 1_000L,
                updatedAtMillis = 1_000L,
            )
            tasks[task.id] = task
            return Result.success(task)
        }

        override fun cancel(taskId: String): Result<Unit> {
            cancelledTaskIds += taskId
            cancelFailure?.let { throwable ->
                cancelFailureStatus?.let { status ->
                    tasks[taskId]?.let { existing -> tasks[taskId] = existing.copy(status = status) }
                }
                return Result.failure(throwable)
            }
            tasks[taskId]?.let { existing ->
                tasks[taskId] = existing.copy(status = ScheduledTaskStatus.Cancelled)
            }
            return Result.success(Unit)
        }

        private fun periodicCheckTask(
            request: PeriodicCheckScheduleRequest,
            status: ScheduledTaskStatus,
        ): ScheduledTask {
            val existing = tasks[PeriodicCheckScheduleRequest.TASK_ID]
            return ScheduledTask(
                id = PeriodicCheckScheduleRequest.TASK_ID,
                type = ScheduledTaskType.PeriodicCheck,
                title = PeriodicCheckScheduleRequest.TITLE,
                body = request.storageSummary(),
                triggerAtMillis = existing?.triggerAtMillis ?: 2_000L,
                status = status,
                createdAtMillis = existing?.createdAtMillis ?: 1_000L,
                updatedAtMillis = (existing?.updatedAtMillis ?: 1_000L) + 1L,
            )
        }

        private fun ScheduledTask.toPeriodicCheckPolicySummary(): PeriodicCheckPolicySummary {
            val request = PeriodicCheckScheduleRequest.fromStorageSummary(
                body,
                fallback = if (status == ScheduledTaskStatus.Cancelled) {
                    PeriodicCheckScheduleRequest(enabled = false)
                } else {
                    PeriodicCheckScheduleRequest()
                },
            )
            return PeriodicCheckPolicySummary(
                request = if (status == ScheduledTaskStatus.Cancelled) {
                    request.copy(enabled = false).normalized()
                } else {
                    request
                },
                taskStatus = status,
                nextAllowedRunAtMillis = triggerAtMillis,
                lastRunSummary = null,
                updatedAtMillis = updatedAtMillis,
            )
        }
    }

    private class FakeToolAuditLog(
        var records: List<ToolAuditRecord> = emptyList(),
    ) : ToolAuditLog {
        val requestedLimits = mutableListOf<Int>()

        override fun recentAuditEvents(limit: Int): List<ToolAuditRecord> {
            requestedLimits += limit
            return records.take(limit)
        }
    }

    private class FakeModelRepository(
        activeModelPath: String? = null,
        private var memoryEmbeddingModelPath: String? = null,
    ) : ModelRepositoryFacade {
        private var state = ModelSelectionState(
            selectedModelId = DEFAULT_CHAT_MODEL_ID,
            activeInstalledModelId = null,
            activeModelPath = activeModelPath,
            installedModels = emptyList(),
        )

        override fun currentState(): ModelSelectionState = state

        override fun selectedRecommendedModel(): RecommendedModel =
            ModelCatalog.recommendedChatModelById(state.selectedModelId)

        override fun selectRecommendedModel(modelId: String): ModelSelectionResult {
            state = state.copy(selectedModelId = ModelCatalog.recommendedChatModelById(modelId).id)
            return ModelSelectionResult(state, null)
        }

        override fun selectInstalledModel(modelId: String): InstalledModelSummary? = null

        override fun registerInstalledModel(
            path: String,
            displayName: String,
            recommendedModelId: String?,
            verifiedSha256: String?,
            verificationStatus: ModelVerificationStatus,
        ): InstalledModelSummary {
            val summary = InstalledModelSummary(
                id = recommendedModelId ?: "local-test",
                displayName = displayName,
                path = path,
                fileBytes = 1L,
                recommendedModelId = recommendedModelId,
                verifiedSha256 = verifiedSha256,
                verificationStatus = verificationStatus,
            )
            state = state.copy(
                activeInstalledModelId = summary.id,
                activeModelPath = path,
                installedModels = state.installedModels + summary,
            )
            return summary
        }

        override fun createCustomDownloadSource(downloadUrl: String): ModelDownloadSource? = null

        override fun downloadedModelFile(fileName: String): File? = File("/tmp/$fileName")

        override fun resolveModelStorageBytes(): Long = 1024L * 1024L * 1024L

        override fun verifiedActionModelPath(): String? = null

        override fun verifiedMemoryEmbeddingModelPath(): String? = memoryEmbeddingModelPath

        override fun verifyLegacyRecommendedModels(): Boolean = false

        override fun importModel(uri: Uri, onProgress: (TransferProgress) -> Unit): String =
            "/tmp/imported.litertlm"

        override fun pendingDownloadId(): Long = -1L

        override fun savePendingDownload(downloadId: Long, source: ModelDownloadSource) = Unit

        override fun clearPendingDownload() = Unit

        override fun loadPendingDownloadSource(): ModelDownloadSource? = null
    }

    private class FakeGenerationParametersStore : GenerationParametersStore {
        private var parameters = GenerationParameters()
        private var backend = BackendChoice.CPU

        override fun load(): GenerationParameters = parameters

        override fun save(parameters: GenerationParameters): GenerationParameters {
            this.parameters = parameters
            return parameters
        }

        override fun reset(): GenerationParameters {
            parameters = GenerationParameters()
            return parameters
        }

        override fun loadBackend(): BackendChoice = backend

        override fun saveBackend(backend: BackendChoice) {
            this.backend = backend
        }
    }

    private class FakeRemoteModelStore(
        private var mode: InferenceMode = InferenceMode.Local,
        private var config: RemoteModelConfig = RemoteModelConfig(),
    ) : RemoteModelStore {
        override fun loadMode(): InferenceMode = mode

        override fun saveMode(mode: InferenceMode): InferenceMode {
            this.mode = mode
            return mode
        }

        override fun loadConfig(): RemoteModelConfig = config

        override fun saveConfig(config: RemoteModelConfig): Result<RemoteModelConfig> {
            this.config = config.normalized()
            return Result.success(this.config)
        }
    }

    private class FakeFirstRunSetupStore : FirstRunSetupStore {
        override fun isSetupDismissed(): Boolean = true

        override fun markSetupDismissed() = Unit

        override fun isMemoryEnabled(): Boolean = true

        override fun setMemoryEnabled(enabled: Boolean) = Unit
    }

    private class FakeModelDownloadClient : ModelDownloadClient {
        override fun enqueue(source: ModelDownloadSource, targetFile: File): Result<Long> =
            Result.success(1L)

        override fun cancel(downloadId: Long) = Unit

        override fun query(downloadId: Long): DownloadInfo? = null
    }

    private fun scheduledTask(
        id: String,
        type: ScheduledTaskType,
        status: ScheduledTaskStatus,
        title: String = id,
        body: String = "测试后台任务",
        updatedAtMillis: Long = 1_000L,
    ): ScheduledTask =
        ScheduledTask(
            id = id,
            type = type,
            title = title,
            body = body,
            triggerAtMillis = 2_000L,
            status = status,
            createdAtMillis = 1_000L,
            updatedAtMillis = updatedAtMillis,
        )

    private fun toolAuditRecord(
        id: String,
        requestId: String,
        toolName: String,
        eventType: String = "ToolObserved",
        status: String? = "Succeeded",
        riskLevel: String? = "LowReadOnly",
        permissions: List<String> = emptyList(),
        summary: String,
        createdAtMillis: Long,
    ): ToolAuditRecord =
        ToolAuditRecord(
            id = id,
            runId = "run-$id",
            requestId = requestId,
            toolName = toolName,
            skillId = null,
            eventType = eventType,
            status = status,
            riskLevel = riskLevel,
            permissions = permissions,
            summary = summary,
            createdAtMillis = createdAtMillis,
        )
}
