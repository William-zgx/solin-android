package com.bytedance.zgx.pocketmind

import android.net.Uri
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.audit.ToolAuditLog
import com.bytedance.zgx.pocketmind.audit.ToolAuditRecord
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.pocketmind.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.pocketmind.background.ReminderScheduleRequest
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
import com.bytedance.zgx.pocketmind.memory.MemoryRecordType
import com.bytedance.zgx.pocketmind.memory.MemoryRecordStore
import com.bytedance.zgx.pocketmind.memory.MemoryRepository
import com.bytedance.zgx.pocketmind.memory.PersistedMemoryRecord
import com.bytedance.zgx.pocketmind.multimodal.SharedAttachment
import com.bytedance.zgx.pocketmind.multimodal.SharedAttachmentKind
import com.bytedance.zgx.pocketmind.multimodal.SharedInput
import com.bytedance.zgx.pocketmind.multimodal.SharedTextPreview
import com.bytedance.zgx.pocketmind.orchestration.AgentModelObservationResult
import com.bytedance.zgx.pocketmind.orchestration.AgentObservationDecision
import com.bytedance.zgx.pocketmind.orchestration.AgentObservationResult
import com.bytedance.zgx.pocketmind.orchestration.AgentPlan
import com.bytedance.zgx.pocketmind.orchestration.AgentRun
import com.bytedance.zgx.pocketmind.orchestration.AgentRunState
import com.bytedance.zgx.pocketmind.orchestration.AssistantRoute
import com.bytedance.zgx.pocketmind.orchestration.AssistantRouter
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
                MessagePrivacy.RemoteEligible,
                MessagePrivacy.RemoteEligible,
                MessagePrivacy.LocalOnly,
                MessagePrivacy.LocalOnly,
            ),
            sessionStore.messages.map { it.privacy },
        )
        assertTrue(sessionStore.messages.last().text.contains("摘要：适合分享的本地摘要"))
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
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertTrue(sessionStore.messages.last().text.contains("权限"))
        assertEquals("权限被拒，工具未执行", viewModel.uiState.value.statusText)
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
    fun restoreStartupStateLoadsRunningBackgroundTasksWithoutRemoteWork() = runTest(dispatcher) {
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask("task-1", ScheduledTaskType.Reminder, ScheduledTaskStatus.Scheduled),
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
            backgroundTaskScheduler = scheduler,
            remoteRuntime = remoteRuntime,
            actionExecutor = executor,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertEquals(listOf("task-1"), viewModel.uiState.value.backgroundTasks.map { it.id })
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
        assistantRouter: FakeAssistantRouter = FakeAssistantRouter(),
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
        private val cancelObservation: AgentObservationResult? = null,
        private val toolObservation: AgentObservationResult? = null,
        private val modelObservation: AgentModelObservationResult? = null,
        private val restoredPendingRoute: AssistantRoute.Action? = null,
    ) : AssistantRouter {
        var routeCallCount: Int = 0
            private set
        var confirmCallCount: Int = 0
            private set
        var observeToolCallCount: Int = 0
            private set
        var failPendingCallCount: Int = 0
            private set
        var lastObservedResult: ToolResult? = null
            private set
        var lastFailedPendingResult: ToolResult? = null
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
                    promptForModel = input,
                    memoryHits = emptyList(),
                    deviceContext = deviceContext,
                )
        }

        override fun confirmToolRequest(runId: String, requestId: String): AgentRun? {
            confirmCallCount += 1
            return confirmedRun
        }

        override fun cancelToolRequest(runId: String, requestId: String): AgentObservationResult? = cancelObservation

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
            return toolObservation
        }

        override fun observeModelResult(runId: String, text: String): AgentModelObservationResult? = modelObservation

        override fun restorePendingAction(): AssistantRoute.Action? = restoredPendingRoute

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

    private class FakeBackgroundTaskScheduler(
        scheduledTasks: List<ScheduledTask> = emptyList(),
        private val cancelFailure: Throwable? = null,
        private val periodicSetFailure: Throwable? = null,
        private val periodicDisableFailure: Throwable? = null,
    ) : BackgroundTaskScheduler {
        private val tasks = linkedMapOf<String, ScheduledTask>()
        val cancelledTaskIds = mutableListOf<String>()
        val periodicPolicyRequests = mutableListOf<PeriodicCheckScheduleRequest>()
        var disablePeriodicCheckCount = 0

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
            cancelFailure?.let { return Result.failure(it) }
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
