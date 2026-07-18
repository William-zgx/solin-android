package com.bytedance.zgx.solin

import android.Manifest
import android.app.DownloadManager
import android.net.Uri
import android.provider.Settings
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.MobileActionPlanner
import com.bytedance.zgx.solin.action.ModelToolOutputParseResult
import com.bytedance.zgx.solin.audit.ToolAuditLog
import com.bytedance.zgx.solin.audit.ToolAuditRecord
import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditLog
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.data.BundledModelInstallResult
import com.bytedance.zgx.solin.data.BundledModelInstaller
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.background.ReminderScheduleRequest
import com.bytedance.zgx.solin.background.ReminderRescheduleReport
import com.bytedance.zgx.solin.background.ScheduledTask
import com.bytedance.zgx.solin.background.ScheduledTaskStatus
import com.bytedance.zgx.solin.background.ScheduledTaskType
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.GenerationParametersStore
import com.bytedance.zgx.solin.data.HuggingFaceAuthStore
import com.bytedance.zgx.solin.data.ModelDownloadSource
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.ModelSelectionResult
import com.bytedance.zgx.solin.data.ModelSelectionState
import com.bytedance.zgx.solin.data.ModelVerificationStatus
import com.bytedance.zgx.solin.data.RemoteModelStore
import com.bytedance.zgx.solin.data.RemoteSendPendingStore
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.data.TransferProgress
import com.bytedance.zgx.solin.device.DeviceContextAuthorizationSnapshot
import com.bytedance.zgx.solin.device.DeviceContextToolReadinessState
import com.bytedance.zgx.solin.download.DownloadInfo
import com.bytedance.zgx.solin.download.ModelDownloadClient
import com.bytedance.zgx.solin.memory.EmbeddingRuntime
import com.bytedance.zgx.solin.memory.FakeMemoryDeletionEventStore
import com.bytedance.zgx.solin.memory.FakeMemoryRecordStore
import com.bytedance.zgx.solin.memory.LongTermMemoryControls
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.memory.MemoryRecallMode
import com.bytedance.zgx.solin.memory.MemoryRecordType
import com.bytedance.zgx.solin.memory.MemoryRecordStore
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.memory.PersistedMemoryRecord
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeStatus
import com.bytedance.zgx.solin.memory.explicitUserFactRecordId
import com.bytedance.zgx.solin.memory.explicitUserPreferenceRecordId
import com.bytedance.zgx.solin.memory.taskStateMemoryRecordId
import com.bytedance.zgx.solin.multimodal.SharedAttachment
import com.bytedance.zgx.solin.multimodal.SharedAttachmentKind
import com.bytedance.zgx.solin.multimodal.SharedInput
import com.bytedance.zgx.solin.multimodal.SharedInputSource
import com.bytedance.zgx.solin.multimodal.SharedInputSourcePrivacy
import com.bytedance.zgx.solin.multimodal.SharedTextPreview
import com.bytedance.zgx.solin.multimodal.SharedTextPreviewSource
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcomeResult
import com.bytedance.zgx.solin.orchestration.AgentModelObservationResult
import com.bytedance.zgx.solin.orchestration.AgentObservationDecision
import com.bytedance.zgx.solin.orchestration.AgentObservationResult
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.orchestration.AgentRunEvent
import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunOptions
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.ActiveRunPlacementPermit
import com.bytedance.zgx.solin.orchestration.AgentTraceRunSummary
import com.bytedance.zgx.solin.orchestration.AgentTraceStepSummary
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.AssistantOrchestrator
import com.bytedance.zgx.solin.orchestration.InMemoryAgentTraceStore
import com.bytedance.zgx.solin.orchestration.InitialPlanningMode
import com.bytedance.zgx.solin.orchestration.ModelOutputQualityTrace
import com.bytedance.zgx.solin.orchestration.PreparedChatRun
import com.bytedance.zgx.solin.orchestration.PendingExternalOutcomeSnapshot
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.orchestration.RunDataDestination
import com.bytedance.zgx.solin.orchestration.RunDataReceipt
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.orchestration.RunPlacementBinding
import com.bytedance.zgx.solin.presentation.ChatPlacementRuntime
import com.bytedance.zgx.solin.presentation.ChatServingCall
import com.bytedance.zgx.solin.resource.StableResourceBand
import com.bytedance.zgx.solin.resource.StableResourceState
import com.bytedance.zgx.solin.resource.ThermalPressure
import com.bytedance.zgx.solin.runtime.LocalModelRequest
import com.bytedance.zgx.solin.runtime.LocalModelRuntimeCapabilities
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.RemoteModelConnectivityProbe
import com.bytedance.zgx.solin.runtime.RemoteChatEvent
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillRequest
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX
import com.bytedance.zgx.solin.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val TEST_IMAGE_DATA_URL = "data:image/png;base64,AA=="
private const val TEST_LOCAL_MODEL_PATH = "/tmp/model.litertlm"
private const val TEST_MEMORY_EMBEDDING_MODEL_PATH = "/verified/memory.litertlm"
private const val TEST_REMOTE_REVISION = "00000000-0000-0000-0000-000000000001"
private const val TEST_REMOTE_REVISION_2 = "00000000-0000-0000-0000-000000000002"

@OptIn(ExperimentalCoroutinesApi::class)
class SolinViewModelTest {
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
            remoteStore = configuredRemoteStore(),
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
    fun initialChatRouteFailureIsFailClosedBeforeAnyServingRuntime() = runTest(dispatcher) {
        val runtime = FakeLiteRtRuntime()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            runtime = runtime,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = FakeAssistantRouter(routeFailure = IllegalStateException("route failed")),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertTrue(runtime.prompts.isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(null, viewModel.uiState.value.activeRunPlacement)
        assertEquals(PlacementReasonCode.PLACEMENT_DECISION_MISSING, viewModel.uiState.value.activeRunPlacementReason)
    }

    @Test
    fun defaultRemoteModeSendsOrdinaryTextWithoutPerSendDisclosure() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)
    }

    @Test
    fun remoteSendDisclosurePersistsNonSensitiveMarkerUntilDecision() = runTest(dispatcher) {
        val pendingStore = FakeRemoteSendPendingStore()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            remoteSendPendingStore = pendingStore,
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.EveryMessage)

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val marker = requireNotNull(pendingStore.pending)
        assertEquals(RemoteSendDisclosureKind.CurrentInput, marker.kind)
        assertEquals("model-a", marker.remoteModelName)
        assertEquals(0, marker.imageAttachmentCount)
        assertEquals(0, remoteRuntime.calls.size)

        viewModel.confirmRemoteSendDisclosure()
        advanceUntilIdle()

        assertEquals(null, pendingStore.pending)
        assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)
    }

    @Test
    fun remoteDisclosureConfirmsTheBoundRunWithoutRoutingAgain() = runTest(dispatcher) {
        val router = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-bound-remote",
                promptForModel = "普通远程问题",
                memoryHits = emptyList(),
            ),
        )
        val placementRuntime = FakeChatPlacementRuntime()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            assistantRouter = router,
            chatPlacementRuntime = placementRuntime,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(
                configuredRemoteModel().copy(profileRevision = TEST_REMOTE_REVISION),
            ),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.EveryMessage)

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val pending = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals("run-bound-remote", pending.runId)
        assertEquals(TEST_REMOTE_REVISION, pending.remoteProfileRevision)
        assertEquals(1, router.routeCallCount)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(RunPlacement.Remote, viewModel.uiState.value.activeRunPlacement)
        assertEquals(PlacementReasonCode.USER_FORCED_REMOTE, viewModel.uiState.value.activeRunPlacementReason)

        viewModel.confirmRemoteSendDisclosure()
        advanceUntilIdle()

        assertEquals(1, router.routeCallCount)
        assertEquals(1, placementRuntime.dispatchCount)
        assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)
    }

    @Test
    fun selectingInstalledLocalModelClearsPendingRemoteSendMarker() = runTest(dispatcher) {
        val pendingStore = FakeRemoteSendPendingStore()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val installed = installedModelSummary(
            id = "local-chat",
            displayName = "本地对话模型",
            path = "/tmp/local-chat.litertlm",
        )
        val viewModel = createViewModel(
            modelRepository = FakeModelRepository(initialInstalledModels = listOf(installed)),
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            remoteSendPendingStore = pendingStore,
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.EveryMessage)

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertNotNull(pendingStore.pending)

        viewModel.selectInstalledModel("local-chat")
        advanceUntilIdle()

        assertEquals(null, pendingStore.pending)
        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals(InferenceMode.Local, viewModel.uiState.value.inferenceMode)
        assertEquals("local-chat", viewModel.uiState.value.activeInstalledModelId)
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun startupConsumesPendingRemoteSendMarkerFailClosedWithoutPromptLeak() = runTest(dispatcher) {
        val pendingStore = FakeRemoteSendPendingStore(
            pending = pendingRemoteSendMarker(
                localOnlyHistoryFilteredCount = 2,
                imageAttachmentCount = 1,
                protectedSourceCount = 2,
            ),
        )
        val sessionStore = FakeSessionStore(
            initialSessions = mapOf(
                "session-1" to listOf(
                    ChatMessage(
                        role = MessageRole.User,
                        text = "之前的本地会话内容",
                        privacy = MessagePrivacy.RemoteEligible,
                    ),
                ),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            remoteSendPendingStore = pendingStore,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertEquals(null, pendingStore.pending)
        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertTrue(remoteRuntime.calls.isEmpty())
        val note = sessionStore.messages.last()
        assertEquals(MessagePrivacy.LocalOnly, note.privacy)
        assertTrue(note.text.contains("远程发送确认"))
        assertTrue(note.text.contains("没有发送"))
        assertFalse(note.text.contains("model-a"))
        assertFalse(note.text.contains("之前的本地会话内容"))
    }

    @Test
    fun startupConsumesToolContinuationMarkerAndFailsModelRun() = runTest(dispatcher) {
        val pendingStore = FakeRemoteSendPendingStore(
            pending = pendingRemoteSendMarker(
                kind = RemoteSendDisclosureKind.ToolResultContinuation,
                runId = "run-pending-continuation",
            ),
        )
        val assistantRouter = FakeAssistantRouter()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            remoteSendPendingStore = pendingStore,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertEquals(null, pendingStore.pending)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(1, assistantRouter.failModelGenerationCallCount)
        assertEquals("run-pending-continuation", assistantRouter.lastFailedModelRunId)
        assertTrue(assistantRouter.lastFailedModelReason.orEmpty().contains("应用重启"))
        assertTrue(sessionStore.messages.last().text.contains("工具结果没有发送到远程模型"))
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
    }

    @Test
    fun switchingToRemoteShowsModeDisclosureOnceAndDismissAllowsSend() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Local,
                config = configuredRemoteModel(),
            ),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.selectInferenceMode(InferenceMode.Remote)
        advanceUntilIdle()

        val modeDisclosure = requireNotNull(viewModel.uiState.value.pendingRemoteModeDisclosure)
        assertEquals("api.example.com", modeDisclosure.remoteHost)
        assertEquals("model-a", modeDisclosure.remoteModelName)
        assertEquals("远程模式待确认", viewModel.uiState.value.statusText)

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals("请先确认远程模式提醒", viewModel.uiState.value.statusText)

        viewModel.dismissRemoteModeDisclosure()
        advanceUntilIdle()
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingRemoteModeDisclosure)
        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)

        viewModel.selectInferenceMode(InferenceMode.Local)
        advanceUntilIdle()
        viewModel.selectInferenceMode(InferenceMode.Remote)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingRemoteModeDisclosure)
    }

    @Test
    fun offRolloutDowngradesPersistedAutoToLocalOnStartup() = runTest(dispatcher) {
        val remoteStore = FakeRemoteModelStore(
            mode = InferenceMode.Auto,
            config = configuredRemoteModel(),
        )

        val viewModel = createViewModel(remoteStore = remoteStore)

        assertEquals(InferenceMode.Local, remoteStore.loadMode())
        assertEquals(InferenceMode.Local, viewModel.uiState.value.inferenceMode)
    }

    @Test
    fun selectingAutoWaitsForSameRevisionConfirmationAndKeepsLocalRuntimeLoaded() =
        runTest(dispatcher) {
            val runtime = FakeLiteRtRuntime().apply { isLoaded = true }
            val remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Local,
                config = configuredRemoteModel(),
            )
            val viewModel = createViewModel(
                runtime = runtime,
                remoteStore = remoteStore,
                adaptiveInferenceRollout = AdaptiveInferenceRollout.OptIn,
            )

            viewModel.selectInferenceMode(InferenceMode.Auto)

            val disclosure = requireNotNull(viewModel.uiState.value.pendingRemoteModeDisclosure)
            assertEquals(InferenceMode.Local, remoteStore.loadMode())
            assertEquals(InferenceMode.Local, viewModel.uiState.value.inferenceMode)
            assertEquals(0, runtime.closeCallCount)
            assertTrue(runtime.isLoaded)

            viewModel.confirmRemoteModeDisclosure(disclosure)
            advanceUntilIdle()

            assertEquals(InferenceMode.Auto, remoteStore.loadMode())
            assertEquals(InferenceMode.Auto, viewModel.uiState.value.inferenceMode)
            assertEquals(0, runtime.closeCallCount)
            assertTrue(runtime.isLoaded)
        }

    @Test
    fun cancellingAutoDisclosureDoesNotChangePersistedPreference() = runTest(dispatcher) {
        val remoteStore = FakeRemoteModelStore(
            mode = InferenceMode.Local,
            config = configuredRemoteModel(),
        )
        val viewModel = createViewModel(
            remoteStore = remoteStore,
            adaptiveInferenceRollout = AdaptiveInferenceRollout.OptIn,
        )

        viewModel.selectInferenceMode(InferenceMode.Auto)
        viewModel.dismissRemoteModeDisclosure()

        assertEquals(InferenceMode.Local, remoteStore.loadMode())
        assertEquals(InferenceMode.Local, viewModel.uiState.value.inferenceMode)
        assertNull(viewModel.uiState.value.pendingRemoteModeDisclosure)
    }

    @Test
    fun remoteConfigRevisionChangeInvalidatesPendingAutoAuthorization() = runTest(dispatcher) {
        val remoteStore = FakeRemoteModelStore(
            mode = InferenceMode.Local,
            config = configuredRemoteModel(),
        )
        val viewModel = createViewModel(
            remoteStore = remoteStore,
            adaptiveInferenceRollout = AdaptiveInferenceRollout.OptIn,
        )

        viewModel.selectInferenceMode(InferenceMode.Auto)
        val staleDisclosure = requireNotNull(viewModel.uiState.value.pendingRemoteModeDisclosure)
        viewModel.updateRemoteModelConfig(configuredRemoteModel().copy(modelName = "model-b"))
        advanceUntilIdle()
        viewModel.confirmRemoteModeDisclosure(staleDisclosure)

        assertEquals(InferenceMode.Local, remoteStore.loadMode())
        assertEquals(InferenceMode.Local, viewModel.uiState.value.inferenceMode)
        assertNull(viewModel.uiState.value.pendingRemoteModeDisclosure)
    }

    @Test
    fun blankRemoteRevisionCannotAuthorizeAuto() = runTest(dispatcher) {
        val remoteStore = FakeRemoteModelStore(
            mode = InferenceMode.Local,
            config = configuredRemoteModel().copy(profileRevision = ""),
        )
        val viewModel = createViewModel(
            remoteStore = remoteStore,
            adaptiveInferenceRollout = AdaptiveInferenceRollout.OptIn,
        )

        viewModel.selectInferenceMode(InferenceMode.Auto)
        val disclosure = requireNotNull(viewModel.uiState.value.pendingRemoteModeDisclosure)
        viewModel.confirmRemoteModeDisclosure(disclosure)

        assertEquals(InferenceMode.Local, remoteStore.loadMode())
        assertEquals(InferenceMode.Local, viewModel.uiState.value.inferenceMode)
    }

    @Test
    fun remoteRevisionChangeAfterAutoValidationSerializesAndRevokesAuto() {
        val autoSaveEntered = CountDownLatch(1)
        val allowAutoSave = CountDownLatch(1)
        val configSaveEntered = CountDownLatch(1)
        val remoteStore = FakeRemoteModelStore(
            mode = InferenceMode.Local,
            config = configuredRemoteModel(),
            beforeAutoModeSave = {
                autoSaveEntered.countDown()
                check(allowAutoSave.await(5, TimeUnit.SECONDS))
            },
            beforeConfigSave = configSaveEntered::countDown,
        )
        val viewModel = createViewModel(
            remoteStore = remoteStore,
            adaptiveInferenceRollout = AdaptiveInferenceRollout.OptIn,
        )
        viewModel.selectInferenceMode(InferenceMode.Auto)
        val disclosure = requireNotNull(viewModel.uiState.value.pendingRemoteModeDisclosure)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val confirm = executor.submit { viewModel.confirmRemoteModeDisclosure(disclosure) }
            assertTrue(autoSaveEntered.await(5, TimeUnit.SECONDS))
            val update = executor.submit {
                viewModel.updateRemoteModelConfig(
                    configuredRemoteModel().copy(modelName = "model-after-confirm"),
                )
            }

            assertFalse(configSaveEntered.await(200, TimeUnit.MILLISECONDS))
            allowAutoSave.countDown()
            confirm.get(5, TimeUnit.SECONDS)
            update.get(5, TimeUnit.SECONDS)

            assertEquals(InferenceMode.Local, remoteStore.loadMode())
            assertEquals(InferenceMode.Local, viewModel.uiState.value.inferenceMode)
            assertEquals(TEST_REMOTE_REVISION_2, remoteStore.loadConfig().profileRevision)
        } finally {
            allowAutoSave.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun configUpdateCannotProjectLocalBeforeConfirmedAutoProjectsItsUi() {
        val confirmReadyToProject = CountDownLatch(1)
        val allowConfirmProjection = CountDownLatch(1)
        val configSaveEntered = CountDownLatch(1)
        val remoteStore = FakeRemoteModelStore(
            mode = InferenceMode.Local,
            config = configuredRemoteModel(),
            afterAutoModeSaveBeforeConfigLoadReturns = {
                confirmReadyToProject.countDown()
                check(allowConfirmProjection.await(5, TimeUnit.SECONDS))
            },
            beforeConfigSave = configSaveEntered::countDown,
        )
        val viewModel = createViewModel(
            remoteStore = remoteStore,
            adaptiveInferenceRollout = AdaptiveInferenceRollout.OptIn,
        )
        viewModel.selectInferenceMode(InferenceMode.Auto)
        val disclosure = requireNotNull(viewModel.uiState.value.pendingRemoteModeDisclosure)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val confirm = executor.submit { viewModel.confirmRemoteModeDisclosure(disclosure) }
            assertTrue(confirmReadyToProject.await(5, TimeUnit.SECONDS))
            val update = executor.submit {
                viewModel.updateRemoteModelConfig(
                    configuredRemoteModel().copy(modelName = "model-after-ui-barrier"),
                )
            }

            assertFalse(configSaveEntered.await(200, TimeUnit.MILLISECONDS))
            allowConfirmProjection.countDown()
            confirm.get(5, TimeUnit.SECONDS)
            update.get(5, TimeUnit.SECONDS)

            assertEquals(InferenceMode.Local, remoteStore.loadMode())
            assertEquals(InferenceMode.Local, viewModel.uiState.value.inferenceMode)
            assertEquals(TEST_REMOTE_REVISION_2, remoteStore.loadConfig().profileRevision)
        } finally {
            allowConfirmProjection.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun remoteSendDisclosureOncePerSessionGoesSilentForTextButStillConfirmsImage() =
        runTest(dispatcher) {
            val remoteRuntime = RecordingRemoteChatRuntime()
            val viewModel = createViewModel(
                remoteRuntime = remoteRuntime,
                remoteStore = configuredRemoteStore(),
                requireRemoteSendDisclosure = true,
            )
            viewModel.restoreStartupState(skipModelRuntimeWork = true)
            advanceUntilIdle()

            viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.OncePerSession)

            // First text send still requires confirmation.
            viewModel.sendMessage("第一条远程问题")
            advanceUntilIdle()
            val first = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
            assertFalse(first.forcedBySensitiveContent)
            assertTrue(remoteRuntime.calls.isEmpty())

            // Confirm with "don't ask again this session".
            viewModel.confirmRemoteSendDisclosure(suppressForSession = true)
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
            assertEquals(1, remoteRuntime.calls.size)

            // Second text send is now silent — no disclosure, goes straight through.
            viewModel.sendMessage("第二条远程问题")
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
            assertEquals(2, remoteRuntime.calls.size)
            assertEquals("第二条远程问题", remoteRuntime.calls.last().prompt)

            // Image sends still cross the local/remote byte boundary, so session suppression
            // for ordinary text must not silence their per-send preview confirmation.
            viewModel.stageSharedInput(sharedImageInput())
            advanceUntilIdle()
            viewModel.sendPendingSharedInput("描述这张图")
            advanceUntilIdle()

            val imageDisclosure = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
            assertEquals(RemoteSendDisclosureKind.CurrentInput, imageDisclosure.kind)
            assertEquals(1, imageDisclosure.imageAttachmentCount)
            assertEquals(2, remoteRuntime.calls.size)

            viewModel.confirmRemoteSendDisclosure(suppressForSession = true)
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
            assertEquals(3, remoteRuntime.calls.size)
            assertEquals(1, remoteRuntime.calls.last().imageAttachments.size)
        }

    @Test
    fun remoteSendDisclosureEveryMessageIgnoresSuppressForSession() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.EveryMessage)

        viewModel.sendMessage("第一条远程问题")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)

        viewModel.confirmRemoteSendDisclosure(suppressForSession = true)
        advanceUntilIdle()
        assertEquals(1, remoteRuntime.calls.size)

        viewModel.sendMessage("第二条远程问题")
        advanceUntilIdle()

        val second = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals("第二条远程问题", second.prompt)
        assertEquals(1, remoteRuntime.calls.size)
    }

    @Test
    fun deleteActiveSessionResetsRemoteSendSuppression() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore(
            initialSessions = mapOf(
                "session-1" to emptyList(),
                "session-2" to emptyList(),
            ),
            initialActiveSessionId = "session-1",
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.OncePerSession)

        viewModel.sendMessage("第一条普通远程问题")
        advanceUntilIdle()
        viewModel.confirmRemoteSendDisclosure(suppressForSession = true)
        advanceUntilIdle()
        assertEquals(1, remoteRuntime.calls.size)

        viewModel.deleteActiveSession()
        advanceUntilIdle()
        viewModel.sendMessage("第二条普通远程问题")
        advanceUntilIdle()

        val pending = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals("第二条普通远程问题", pending.prompt)
        assertEquals(1, remoteRuntime.calls.size)
    }

    @Test
    fun remoteSendDisclosureOnlyWhenSensitiveStillConfirmsImageSend() =
        runTest(dispatcher) {
            val remoteRuntime = RecordingRemoteChatRuntime()
            val viewModel = createViewModel(
                remoteRuntime = remoteRuntime,
                remoteStore = configuredRemoteStore(),
                requireRemoteSendDisclosure = true,
            )
            viewModel.restoreStartupState(skipModelRuntimeWork = true)
            advanceUntilIdle()

            viewModel.setRemoteSendDisclosurePolicy(
                RemoteSendDisclosurePolicy.OnlyWhenSensitive,
            )

            // Non-sensitive text send goes through silently (no disclosure).
            viewModel.sendMessage("普通远程问题")
            advanceUntilIdle()
            assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
            assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)

            // Image bytes require a per-send preview even when ordinary text is silent.
            viewModel.stageSharedInput(sharedImageInput())
            advanceUntilIdle()
            viewModel.sendPendingSharedInput("描述这张图")
            advanceUntilIdle()

            val imageDisclosure = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
            assertEquals(RemoteSendDisclosureKind.CurrentInput, imageDisclosure.kind)
            assertEquals(1, imageDisclosure.imageAttachmentCount)
            assertEquals(1, remoteRuntime.calls.size)

            viewModel.confirmRemoteSendDisclosure()
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
            assertEquals(2, remoteRuntime.calls.size)
            assertEquals(1, remoteRuntime.calls.last().imageAttachments.size)
        }

    @Test
    fun remoteConnectivityPreflightUpdatesConfigStatus() = runTest(dispatcher) {
        val probe = FakeRemoteModelConnectivityProbe(RemoteModelConnectivityStatus.AuthenticationFailed)
        val viewModel = createViewModel(
            remoteConnectivityProbe = probe,
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.testRemoteModelConnectivity()
        advanceUntilIdle()

        assertEquals(RemoteModelConnectivityStatus.AuthenticationFailed, viewModel.uiState.value.remoteModelConfig.connectivityStatus)
        assertEquals(listOf(configuredRemoteModel()), probe.checkedConfigs)
        assertTrue(viewModel.uiState.value.statusText.contains("鉴权失败"))
    }

    @Test
    fun knownRemoteConnectivityFailureBlocksSendBeforeRuntime() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val remoteStore = configuredRemoteStore()
        remoteStore.recordConnectivity(
            configuredRemoteModel(),
            RemoteModelConnectivityStatus.Unreachable,
        )
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = remoteStore,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals("没有可用的模型执行目标", viewModel.uiState.value.statusText)
        assertEquals(
            PlacementReasonCode.REMOTE_CONNECTIVITY_UNAVAILABLE,
            viewModel.uiState.value.activeRunPlacementReason,
        )
        assertTrue(viewModel.uiState.value.remoteSendAuditEvents.isEmpty())
    }

    @Test
    fun silentRemoteTextSendRecordsAuditEvent() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.OnlyWhenSensitive)

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)
        val audit = viewModel.uiState.value.remoteSendAuditEvents.single()
        assertEquals("已确认发送", audit.decisionLabel)
        assertEquals("model-a", audit.modelName)
    }

    @Test
    fun setRemoteSendDisclosurePolicyResetsSessionSuppression() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.OncePerSession)
        viewModel.sendMessage("第一条")
        advanceUntilIdle()
        viewModel.confirmRemoteSendDisclosure(suppressForSession = true)
        advanceUntilIdle()
        assertEquals(1, remoteRuntime.calls.size)

        // Re-applying the policy clears the session suppression, so the next send re-confirms.
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.OncePerSession)
        viewModel.sendMessage("第二条")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pendingRemoteSendDisclosure != null)
        assertEquals(1, remoteRuntime.calls.size)
    }

    @Test
    fun remoteModePlansCalendarAvailabilityLocallyWithoutPerSendDisclosure() = runTest(dispatcher) {
        val traceStore = InMemoryAgentTraceStore()
        val orchestrator = AssistantOrchestrator(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = false),
            traceStore = traceStore,
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = orchestrator,
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z")
        advanceUntilIdle()

        val confirmedState = viewModel.uiState.value
        val confirmation = requireNotNull(confirmedState.pendingConfirmation)
        assertEquals(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY, confirmation.draft.functionName)
        assertEquals(
            MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
            confirmation.toolRequest?.toolName,
        )
        assertEquals(
            listOf(Manifest.permission.READ_CALENDAR),
            confirmation.runtimePermissionRequirementsFor().flatMap { it.permissions },
        )
        assertEquals("动作草稿待确认 · 规则回退", confirmedState.statusText)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(
            AgentRunState.AwaitingUserConfirmation,
            orchestrator.recentTraceRuns(limit = 1).single().run.state,
        )
    }

    @Test
    fun remoteSendDisclosureCancelKeepsRuntimeIdle() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.EveryMessage)

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.pendingRemoteSendDisclosure != null)

        viewModel.dismissRemoteSendDisclosure()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals("普通远程问题", viewModel.uiState.value.voiceInputDraft?.text)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals("已取消远程发送", viewModel.uiState.value.statusText)
    }

    @Test
    fun remoteModeDoesNotEnableMemoryContextOrReceiptForRemoteSend() = runTest(dispatcher) {
        val memoryRepository = MemoryRepository(recordStore = FakeMemoryRecordStore())
        memoryRepository.indexPreference("pref-1", "I prefer concise answers")
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-memory-boundary",
                promptForModel = "ordinary remote question",
                memoryHits = emptyList(),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val placementRuntime = FakeChatPlacementRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            memoryRepository = memoryRepository,
            assistantRouter = assistantRouter,
            chatPlacementRuntime = placementRuntime,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("ordinary remote question")
        advanceUntilIdle()

        assertEquals(false, assistantRouter.lastRouteMemoryEnabled)
        assertEquals(null, assistantRouter.lastRouteDeviceContext)
        val receipt = placementRuntime.receipts.single()
        assertEquals(RunDataDestination.Remote, receipt.destination)
        assertEquals(MessagePrivacy.RemoteEligible.name, receipt.currentPromptPrivacy)
        assertEquals(false, receipt.memoryContextIncluded)
        assertEquals(false, receipt.deviceContextIncluded)
        assertTrue(receipt.protectedContentTypes.contains("本地记忆"))
        assertTrue(receipt.protectedContentTypes.contains("设备上下文"))
        val call = remoteRuntime.calls.single()
        assertEquals("ordinary remote question", call.prompt)
        assertTrue(call.history.isEmpty())
        assertFalse(call.prompt.contains("I prefer concise answers"))
        assertFalse(call.history.toString().contains("I prefer concise answers"))
    }

    @Test
    fun remoteImageSendRequiresPreviewDisclosureBeforeRuntimeCall() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.stageSharedInput(sharedImageInput())
        advanceUntilIdle()

        viewModel.sendPendingSharedInput("描述这张图")
        advanceUntilIdle()

        val disclosure = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals(RemoteSendDisclosureKind.CurrentInput, disclosure.kind)
        assertEquals(1, disclosure.imageAttachmentCount)
        assertEquals(TEST_IMAGE_DATA_URL, disclosure.imageAttachments.single().dataUrl)
        assertNotNull(viewModel.uiState.value.pendingSharedInputDraft)
        assertTrue(remoteRuntime.calls.isEmpty())

        viewModel.confirmRemoteSendDisclosure()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingSharedInputDraft)
        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        val call = remoteRuntime.calls.single()
        assertTrue(call.prompt.contains("描述这张图"))
        assertEquals(TEST_IMAGE_DATA_URL, call.imageAttachments.single().dataUrl)
    }

    @Test
    fun remoteSharedImageSendPreviewCancelKeepsRuntimeIdle() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.stageSharedInput(sharedImageInput())
        advanceUntilIdle()

        viewModel.sendPendingSharedInput("描述这张图")
        advanceUntilIdle()

        val disclosure = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals(1, disclosure.imageAttachmentCount)
        assertNotNull(viewModel.uiState.value.pendingSharedInputDraft)
        assertTrue(remoteRuntime.calls.isEmpty())

        viewModel.dismissRemoteSendDisclosure()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        val restoredDraft = requireNotNull(viewModel.uiState.value.pendingSharedInputDraft)
        assertEquals("screen.png · 图片", restoredDraft.summary)
        assertEquals(TEST_IMAGE_DATA_URL, restoredDraft.imageAttachments.single().dataUrl)
        assertEquals("描述这张图", viewModel.uiState.value.voiceInputDraft?.text)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals("已取消远程发送", viewModel.uiState.value.statusText)
        assertTrue(sessionStore.messages.isEmpty())
    }

    @Test
    fun startupShowsSetupOnFreshInstallWhenNoLocalOrRemoteModelIsAvailable() = runTest(dispatcher) {
        val viewModel = createViewModel(
            modelRepository = FakeModelRepository(activeModelPath = null),
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Local,
                config = RemoteModelConfig(),
            ),
            firstRunStore = FakeFirstRunSetupStore(setupDismissed = false),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showFirstRunSetup)
        assertFalse(viewModel.uiState.value.isReady)
        assertEquals(
            NO_MODEL_READY_STATUS_TEXT,
            viewModel.uiState.value.statusText,
        )
    }

    @Test
    fun startupKeepsSetupDismissedWhenNoLocalOrRemoteModelIsAvailable() = runTest(dispatcher) {
        val viewModel = createViewModel(
            modelRepository = FakeModelRepository(activeModelPath = null),
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Local,
                config = RemoteModelConfig(),
            ),
            firstRunStore = FakeFirstRunSetupStore(setupDismissed = true),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showFirstRunSetup)
        assertFalse(viewModel.uiState.value.isReady)
        assertEquals(
            NO_MODEL_READY_STATUS_TEXT,
            viewModel.uiState.value.statusText,
        )
    }

    @Test
    fun startupDoesNotReopenSetupWhenRemoteModelIsConfigured() = runTest(dispatcher) {
        val viewModel = createViewModel(
            modelRepository = FakeModelRepository(activeModelPath = null),
            remoteStore = configuredRemoteStore(),
            firstRunStore = FakeFirstRunSetupStore(setupDismissed = false),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showFirstRunSetup)
        assertTrue(viewModel.uiState.value.isReady)
        assertEquals("远程模型已就绪", viewModel.uiState.value.statusText)
    }

    @Test
    fun savingRemoteConfigDismissesFreshSetup() = runTest(dispatcher) {
        val firstRunStore = FakeFirstRunSetupStore(setupDismissed = false)
        val viewModel = createViewModel(
            modelRepository = FakeModelRepository(activeModelPath = null),
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Local,
                config = RemoteModelConfig(),
            ),
            firstRunStore = firstRunStore,
        )

        assertTrue(viewModel.uiState.value.showFirstRunSetup)

        viewModel.updateRemoteModelConfig(configuredRemoteModel())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showFirstRunSetup)
        assertTrue(firstRunStore.isSetupDismissed())
    }

    @Test
    fun deleteActiveInstalledModelClosesRuntimeAndFallsBackToNextUsableModel() = runTest(dispatcher) {
        val active = installedModelSummary(id = "active-chat", displayName = "当前对话", path = "/tmp/active.litertlm")
        val fallback = installedModelSummary(id = "fallback-chat", displayName = "备用对话", path = "/tmp/fallback.litertlm")
        val modelRepository = FakeModelRepository(
            activeInstalledModelId = active.id,
            initialInstalledModels = listOf(active, fallback),
        )
        val runtime = FakeLiteRtRuntime()
        val viewModel = createViewModel(
            modelRepository = modelRepository,
            runtime = runtime,
        )

        viewModel.loadModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isReady)

        viewModel.deleteInstalledModel(active.id)
        advanceUntilIdle()

        assertEquals(listOf(active.id), modelRepository.deletedModelIds)
        assertEquals(1, runtime.closeCallCount)
        assertEquals(fallback.id, viewModel.uiState.value.activeInstalledModelId)
        assertEquals(fallback.path, viewModel.uiState.value.modelPath)
        assertEquals(listOf(fallback.id), viewModel.uiState.value.installedModels.map { it.id })
        assertFalse(viewModel.uiState.value.isReady)
        assertTrue(viewModel.uiState.value.statusText.contains("已删除 当前对话"))
        assertTrue(viewModel.uiState.value.statusText.contains("已切换到 备用对话"))
    }

    @Test
    fun loadModelConfiguresRuntimeFromActiveModelCapabilityProfile() = runTest(dispatcher) {
        val recommendedModelId = DEFAULT_CHAT_MODEL_ID
        val profile = ModelCatalog.profileForModelId(recommendedModelId)
        val active = installedModelSummary(
            id = "active-chat",
            displayName = "当前对话",
            path = "/tmp/active.litertlm",
            recommendedModelId = recommendedModelId,
            verificationStatus = ModelVerificationStatus.VerifiedRecommended,
        )
        val modelRepository = FakeModelRepository(
            activeInstalledModelId = active.id,
            initialInstalledModels = listOf(active),
        )
        val runtime = FakeLiteRtRuntime()
        val viewModel = createViewModel(
            modelRepository = modelRepository,
            runtime = runtime,
        )

        viewModel.loadModel()
        advanceUntilIdle()

        val capabilities = runtime.configuredCapabilities.single()
        assertEquals(profile.supportsVisionInput, capabilities.supportsVisionInput)
        assertEquals(profile.contextWindowTokens, capabilities.contextWindowTokens)
        assertEquals(profile.preferredLocalBackends, capabilities.preferredBackends)
    }

    @Test
    fun loadModelFailsClosedWhenActiveModelVerificationFails() = runTest(dispatcher) {
        val active = installedModelSummary(
            id = "active-chat",
            displayName = "当前对话",
            path = "/tmp/active.litertlm",
            recommendedModelId = DEFAULT_CHAT_MODEL_ID,
            verificationStatus = ModelVerificationStatus.VerifiedRecommended,
        )
        val modelRepository = FakeModelRepository(
            activeInstalledModelId = active.id,
            initialInstalledModels = listOf(active),
            activeModelVerificationResult = false,
        )
        val runtime = FakeLiteRtRuntime()
        val viewModel = createViewModel(
            modelRepository = modelRepository,
            runtime = runtime,
        )

        viewModel.loadModel()
        advanceUntilIdle()

        assertTrue(runtime.loadCalls.isEmpty())
        assertFalse(viewModel.uiState.value.isReady)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(ModelHealthState.LoadFailed, viewModel.uiState.value.modelHealth.state)
        assertTrue(viewModel.uiState.value.statusText.contains("模型校验失败"))
    }

    @Test
    fun loadCustomImportedModelConfiguresTextOnlyRuntimeCapabilities() = runTest(dispatcher) {
        val active = installedModelSummary(
            id = "custom-chat",
            displayName = "导入模型",
            path = "/tmp/custom.litertlm",
            recommendedModelId = null,
            verificationStatus = ModelVerificationStatus.UnverifiedCustom,
        )
        val modelRepository = FakeModelRepository(
            activeInstalledModelId = active.id,
            initialInstalledModels = listOf(active),
        )
        val runtime = FakeLiteRtRuntime()
        val viewModel = createViewModel(
            modelRepository = modelRepository,
            runtime = runtime,
        )

        assertEquals(CUSTOM_LOCAL_CHAT_PROFILE_ID, viewModel.uiState.value.modelHealth.profileId)

        viewModel.loadModel()
        advanceUntilIdle()

        val capabilities = runtime.configuredCapabilities.single()
        assertFalse(capabilities.supportsVisionInput)
        assertEquals(LocalModelTokenLimits.MAX_TOTAL_TOKENS, capabilities.contextWindowTokens)
        assertTrue(capabilities.preferredBackends.isEmpty())
        assertEquals(CUSTOM_LOCAL_CHAT_PROFILE_ID, viewModel.uiState.value.modelHealth.profileId)
        assertEquals(ModelHealthState.Loaded, viewModel.uiState.value.modelHealth.state)
    }

    @Test
    fun unknownRecommendedActiveModelDoesNotReportDefaultOrVerifiedProfile() = runTest(dispatcher) {
        val active = installedModelSummary(
            id = "unknown-recommended",
            displayName = "未知推荐模型",
            path = "/tmp/unknown.litertlm",
            recommendedModelId = "unknown-model-id",
            verificationStatus = ModelVerificationStatus.VerifiedRecommended,
        )
        val modelRepository = FakeModelRepository(
            activeInstalledModelId = active.id,
            initialInstalledModels = listOf(active),
        )

        val viewModel = createViewModel(modelRepository = modelRepository)

        assertEquals("unknown-model-id", viewModel.uiState.value.modelHealth.profileId)
        assertEquals(ModelHealthState.InstalledUnverified, viewModel.uiState.value.modelHealth.state)
        assertEquals(null, viewModel.uiState.value.activeLocalCapabilityProfile)
        assertFalse(viewModel.uiState.value.activeLocalModelSupportsVisionInput)
    }

    @Test
    fun sendMessagePassesVerifiedInstalledCapabilityProfilesToAgentRoute() = runTest(dispatcher) {
        val chat = installedModelSummary(
            id = "chat-model",
            displayName = "当前对话",
            path = "/tmp/chat.litertlm",
            recommendedModelId = DEFAULT_CHAT_MODEL_ID,
            verificationStatus = ModelVerificationStatus.VerifiedRecommended,
        )
        val action = installedModelSummary(
            id = "action-model",
            displayName = "低资源动作模型",
            path = "/tmp/action.litertlm",
            recommendedModelId = MOBILE_ACTION_MODEL_ID,
            verificationStatus = ModelVerificationStatus.VerifiedRecommended,
        )
        val unverifiedAction = installedModelSummary(
            id = "unverified-action",
            displayName = "未验证低资源动作模型",
            path = "/tmp/unverified-action.litertlm",
            recommendedModelId = MOBILE_ACTION_MODEL_ID,
            verificationStatus = ModelVerificationStatus.UnverifiedCustom,
        )
        val assistantRouter = FakeAssistantRouter()
        val viewModel = createViewModel(
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(
                activeInstalledModelId = chat.id,
                initialInstalledModels = listOf(chat, action, unverifiedAction),
            ),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("普通问题")
        advanceUntilIdle()

        assertEquals(1, assistantRouter.routeCallCount)
        assertEquals(
            listOf(DEFAULT_CHAT_MODEL_ID, MOBILE_ACTION_MODEL_ID),
            assistantRouter.lastRouteCapabilityProfiles.map { profile -> profile.id },
        )
        assertTrue(assistantRouter.lastRouteCapabilityProfiles.any { profile ->
            profile.supportsMobileActionPlanning
        })
    }

    @Test
    fun deleteInactiveInstalledModelKeepsActiveRuntimeReady() = runTest(dispatcher) {
        val active = installedModelSummary(id = "active-chat", displayName = "当前对话", path = "/tmp/active.litertlm")
        val extra = installedModelSummary(id = "extra-chat", displayName = "额外对话", path = "/tmp/extra.litertlm")
        val modelRepository = FakeModelRepository(
            activeInstalledModelId = active.id,
            initialInstalledModels = listOf(active, extra),
        )
        val runtime = FakeLiteRtRuntime()
        val viewModel = createViewModel(
            modelRepository = modelRepository,
            runtime = runtime,
        )

        viewModel.loadModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isReady)

        viewModel.deleteInstalledModel(extra.id)
        advanceUntilIdle()

        assertEquals(listOf(extra.id), modelRepository.deletedModelIds)
        assertEquals(0, runtime.closeCallCount)
        assertEquals(active.id, viewModel.uiState.value.activeInstalledModelId)
        assertEquals(active.path, viewModel.uiState.value.modelPath)
        assertEquals(listOf(active.id), viewModel.uiState.value.installedModels.map { it.id })
        assertTrue(viewModel.uiState.value.isReady)
        assertEquals("已删除 额外对话", viewModel.uiState.value.statusText)
    }

    @Test
    fun deleteActiveInstalledModelWithoutFallbackClearsLocalEndpoint() = runTest(dispatcher) {
        val active = installedModelSummary(id = "active-chat", displayName = "当前对话", path = "/tmp/active.litertlm")
        val modelRepository = FakeModelRepository(
            activeInstalledModelId = active.id,
            initialInstalledModels = listOf(active),
        )
        val runtime = FakeLiteRtRuntime()
        val viewModel = createViewModel(
            modelRepository = modelRepository,
            runtime = runtime,
        )

        viewModel.loadModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isReady)

        viewModel.deleteInstalledModel(active.id)
        advanceUntilIdle()

        assertEquals(listOf(active.id), modelRepository.deletedModelIds)
        assertEquals(1, runtime.closeCallCount)
        assertNull(viewModel.uiState.value.activeInstalledModelId)
        assertNull(viewModel.uiState.value.modelPath)
        assertTrue(viewModel.uiState.value.installedModels.isEmpty())
        assertFalse(viewModel.uiState.value.isReady)
        assertFalse(viewModel.uiState.value.isBusy)
        assertTrue(viewModel.uiState.value.statusText.contains("请下载或导入本地模型"))
    }

    @Test
    fun deleteInstalledModelFailureClearsBusyAndKeepsInstalledRecord() = runTest(dispatcher) {
        val active = installedModelSummary(id = "active-chat", displayName = "当前对话", path = "/tmp/active.litertlm")
        val modelRepository = FakeModelRepository(
            activeInstalledModelId = active.id,
            initialInstalledModels = listOf(active),
            deleteResult = false,
        )
        val runtime = FakeLiteRtRuntime()
        val viewModel = createViewModel(
            modelRepository = modelRepository,
            runtime = runtime,
        )

        viewModel.loadModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isReady)

        viewModel.deleteInstalledModel(active.id)
        advanceUntilIdle()

        assertEquals(listOf(active.id), modelRepository.deletedModelIds)
        assertEquals(1, runtime.closeCallCount)
        assertEquals(active.id, viewModel.uiState.value.activeInstalledModelId)
        assertEquals(listOf(active.id), viewModel.uiState.value.installedModels.map { it.id })
        assertFalse(viewModel.uiState.value.isReady)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals("删除 当前对话 失败", viewModel.uiState.value.statusText)
    }

    @Test
    fun startModelDownloadReportsUnavailableDirectoryWithoutEnqueueing() = runTest(dispatcher) {
        val modelRepository = FakeModelRepository(downloadedModelFileProvider = { null })
        val downloadClient = FakeModelDownloadClient()
        val viewModel = createViewModel(
            modelRepository = modelRepository,
            downloadClient = downloadClient,
        )

        viewModel.startModelDownload()
        advanceUntilIdle()

        assertEquals("下载目录不可用，请导入已有模型", viewModel.uiState.value.statusText)
        assertFalse(viewModel.uiState.value.isDownloading)
        assertTrue(downloadClient.enqueuedDownloads.isEmpty())
        assertTrue(modelRepository.savedPendingDownloads.isEmpty())
    }

    @Test
    fun startSetupModelDownloadKeepsFirstRunOpenWhenPreflightFails() = runTest(dispatcher) {
        val firstRunStore = FakeFirstRunSetupStore(setupDismissed = false)
        val modelRepository = FakeModelRepository(
            activeModelPath = null,
            downloadedModelFileProvider = { null },
        )
        val downloadClient = FakeModelDownloadClient()
        val viewModel = createViewModel(
            modelRepository = modelRepository,
            firstRunStore = firstRunStore,
            downloadClient = downloadClient,
        )

        assertTrue(viewModel.uiState.value.showFirstRunSetup)

        viewModel.startSetupModelDownload()
        advanceUntilIdle()

        assertEquals("下载目录不可用，请导入已有模型", viewModel.uiState.value.statusText)
        assertTrue(viewModel.uiState.value.showFirstRunSetup)
        assertFalse(firstRunStore.isSetupDismissed())
        assertFalse(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.isDownloading)
        assertTrue(downloadClient.enqueuedDownloads.isEmpty())
        assertTrue(modelRepository.savedPendingDownloads.isEmpty())
    }

    @Test
    fun startCustomModelDownloadRejectsInvalidUrlWithoutEnqueueing() = runTest(dispatcher) {
        val modelRepository = FakeModelRepository(customDownloadSource = null)
        val downloadClient = FakeModelDownloadClient()
        val viewModel = createViewModel(
            modelRepository = modelRepository,
            downloadClient = downloadClient,
        )

        viewModel.startCustomModelDownload("https://models.example.com/model.bin")
        advanceUntilIdle()

        assertEquals(
            "请输入有效的 HTTPS .litertlm 模型下载链接；HTTP 仅支持本地调试地址",
            viewModel.uiState.value.statusText,
        )
        assertFalse(viewModel.uiState.value.isDownloading)
        assertTrue(downloadClient.enqueuedDownloads.isEmpty())
        assertTrue(modelRepository.savedPendingDownloads.isEmpty())
    }

    @Test
    fun monitorDownloadFailureClearsPendingDeletesTargetAndShowsReason() = runTest(dispatcher) {
        withTempDownloadTarget { target ->
            val modelRepository = FakeModelRepository(downloadedModelFileProvider = { target })
            val downloadClient = FakeModelDownloadClient(
                queryResults = mutableListOf(
                    DownloadInfo(
                        status = DownloadManager.STATUS_FAILED,
                        reason = DownloadManager.ERROR_INSUFFICIENT_SPACE,
                        downloadedBytes = 0L,
                        totalBytes = 100L,
                    ),
                ),
                onEnqueue = { _, file -> file.writeText("partial", Charsets.UTF_8) },
            )
            val viewModel = createViewModel(
                modelRepository = modelRepository,
                downloadClient = downloadClient,
            )

            viewModel.startModelDownload()
            advanceUntilIdle()

            assertEquals("下载失败：存储空间不足", viewModel.uiState.value.statusText)
            assertFalse(viewModel.uiState.value.isBusy)
            assertFalse(viewModel.uiState.value.isDownloading)
            assertEquals(0L, viewModel.uiState.value.downloadedBytes)
            assertEquals(0L, viewModel.uiState.value.totalBytes)
            assertFalse(target.exists())
            assertEquals(1, modelRepository.clearPendingDownloadCount)
            assertTrue(modelRepository.registeredModels.isEmpty())
        }
    }

    @Test
    fun setupModelDownloadFailureAfterEnqueueRestoresFirstRunRecovery() = runTest(dispatcher) {
        withTempDownloadTarget { target ->
            val firstRunStore = FakeFirstRunSetupStore(setupDismissed = false)
            val modelRepository = FakeModelRepository(
                activeModelPath = null,
                downloadedModelFileProvider = { target },
            )
            val downloadClient = FakeModelDownloadClient(
                queryResults = mutableListOf(
                    DownloadInfo(
                        status = DownloadManager.STATUS_FAILED,
                        reason = DownloadManager.ERROR_INSUFFICIENT_SPACE,
                        downloadedBytes = 0L,
                        totalBytes = 100L,
                    ),
                ),
                onEnqueue = { _, file -> file.writeText("partial", Charsets.UTF_8) },
            )
            val viewModel = createViewModel(
                modelRepository = modelRepository,
                firstRunStore = firstRunStore,
                downloadClient = downloadClient,
            )

            assertTrue(viewModel.uiState.value.showFirstRunSetup)

            viewModel.startSetupModelDownload()
            advanceUntilIdle()

            assertEquals("下载失败：存储空间不足", viewModel.uiState.value.statusText)
            assertTrue(viewModel.uiState.value.showFirstRunSetup)
            assertFalse(firstRunStore.isSetupDismissed())
            assertFalse(viewModel.uiState.value.isBusy)
            assertFalse(viewModel.uiState.value.isDownloading)
            assertEquals(0L, viewModel.uiState.value.downloadedBytes)
            assertEquals(0L, viewModel.uiState.value.totalBytes)
            assertFalse(target.exists())
            assertEquals(1, modelRepository.clearPendingDownloadCount)
            assertEquals(1, modelRepository.savedPendingDownloads.size)
            assertTrue(modelRepository.registeredModels.isEmpty())
        }
    }

    @Test
    fun memoryModelDownloadRequiresHuggingFaceAuthorizationBeforeEnqueue() = runTest(dispatcher) {
        withTempDownloadTarget { target ->
            val modelRepository = FakeModelRepository(downloadedModelFileProvider = { target })
            val downloadClient = FakeModelDownloadClient()
            val viewModel = createViewModel(
                modelRepository = modelRepository,
                downloadClient = downloadClient,
                huggingFaceAuthStore = FakeHuggingFaceAuthStore(),
            )

            viewModel.startRecommendedModelDownload(MEMORY_EMBEDDING_MODEL_ID)

            assertTrue(viewModel.uiState.value.statusText.contains("需要先登录 Hugging Face"))
            assertEquals(MEMORY_EMBEDDING_MODEL_ID, viewModel.uiState.value.pendingHuggingFaceAuthorizationModelId)
            assertFalse(viewModel.uiState.value.isDownloading)
            assertTrue(downloadClient.enqueuedDownloads.isEmpty())
            assertFalse(target.exists())
        }
    }

    @Test
    fun savingHuggingFaceTokenAllowsMemoryModelDownloadToStart() = runTest(dispatcher) {
        withTempDownloadTarget { target ->
            val modelRepository = FakeModelRepository(downloadedModelFileProvider = { target })
            val downloadClient = FakeModelDownloadClient()
            val authStore = FakeHuggingFaceAuthStore()
            val viewModel = createViewModel(
                modelRepository = modelRepository,
                downloadClient = downloadClient,
                huggingFaceAuthStore = authStore,
            )

            viewModel.saveHuggingFaceAccessToken("Bearer hf_test_read_token")
            viewModel.startRecommendedModelDownload(MEMORY_EMBEDDING_MODEL_ID)

            assertTrue(viewModel.uiState.value.huggingFaceAccessTokenConfigured)
            assertEquals("Bearer hf_test_read_token", authStore.authorizationHeader())
            assertEquals(1, downloadClient.enqueuedDownloads.size)
            assertTrue(downloadClient.enqueuedDownloads.single().first.requiresHuggingFaceAuthorization)
        }
    }

    @Test
    fun cancelModelDownloadDuringPreflightCancelsPreparingDownloadBeforeEnqueue() = runTest {
        withTempDownloadTarget { target ->
            val modelRepository = FakeModelRepository(downloadedModelFileProvider = { target })
            val downloadClient = FakeModelDownloadClient()
            val authStore = FakeHuggingFaceAuthStore("hf_test_read_token")
            val downloadDispatcher = StandardTestDispatcher(testScheduler)
            val viewModel = createViewModel(
                modelRepository = modelRepository,
                downloadClient = downloadClient,
                huggingFaceAuthStore = authStore,
                ioDispatcher = downloadDispatcher,
            )

            viewModel.startRecommendedModelDownload(MEMORY_EMBEDDING_MODEL_ID)

            assertTrue(viewModel.uiState.value.isPreparingDownload)
            assertTrue(viewModel.uiState.value.isBusy)
            assertFalse(viewModel.uiState.value.isDownloading)

            viewModel.cancelModelDownload()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isPreparingDownload)
            assertFalse(viewModel.uiState.value.isBusy)
            assertFalse(viewModel.uiState.value.isDownloading)
            assertEquals("下载已取消", viewModel.uiState.value.statusText)
            assertEquals(1, downloadClient.preflightCancelCount)
            assertTrue(downloadClient.enqueuedDownloads.isEmpty())
            assertTrue(modelRepository.savedPendingDownloads.isEmpty())
            assertEquals(1, modelRepository.clearPendingDownloadCount)
        }
    }

    @Test
    fun cancelModelDownloadDuringBlockingPreflightCancelsLateDownloadId() = runTest {
        withTempDownloadTarget { target ->
            val modelRepository = FakeModelRepository(downloadedModelFileProvider = { target })
            val enteredPreflight = CountDownLatch(1)
            val releasePreflight = CountDownLatch(1)
            val lateDownloadCancelled = CountDownLatch(1)
            val downloadClient = FakeModelDownloadClient(
                onEnqueue = { _, _ ->
                    enteredPreflight.countDown()
                    assertTrue(releasePreflight.await(5, TimeUnit.SECONDS))
                },
                onCancel = { downloadId ->
                    if (downloadId == 1L) lateDownloadCancelled.countDown()
                },
            )
            val authStore = FakeHuggingFaceAuthStore("hf_test_read_token")
            val downloadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                val viewModel = createViewModel(
                    modelRepository = modelRepository,
                    downloadClient = downloadClient,
                    huggingFaceAuthStore = authStore,
                    ioDispatcher = downloadDispatcher,
                )

                viewModel.startRecommendedModelDownload(MEMORY_EMBEDDING_MODEL_ID)

                assertTrue(enteredPreflight.await(5, TimeUnit.SECONDS))
                assertTrue(viewModel.uiState.value.isPreparingDownload)
                assertTrue(viewModel.uiState.value.isBusy)
                assertFalse(viewModel.uiState.value.isDownloading)
                assertEquals("正在验证 Hugging Face 授权", viewModel.uiState.value.statusText)

                viewModel.cancelModelDownload()
                releasePreflight.countDown()

                assertTrue(lateDownloadCancelled.await(5, TimeUnit.SECONDS))
                assertFalse(viewModel.uiState.value.isPreparingDownload)
                assertFalse(viewModel.uiState.value.isBusy)
                assertFalse(viewModel.uiState.value.isDownloading)
                assertNull(viewModel.uiState.value.downloadProgressPercent)
                assertEquals(0L, viewModel.uiState.value.downloadedBytes)
                assertEquals(0L, viewModel.uiState.value.totalBytes)
                assertEquals("下载已取消", viewModel.uiState.value.statusText)
                assertEquals(1, downloadClient.preflightCancelCount)
                assertTrue(modelRepository.savedPendingDownloads.isEmpty())
            } finally {
                releasePreflight.countDown()
                downloadDispatcher.close()
            }
        }
    }

    @Test
    fun monitorDownloadShaFailureDeletesFileClearsPendingAndStopsDownloading() = runTest(dispatcher) {
        withTempDownloadTarget { target ->
            val source = ModelDownloadSource(
                title = "自定义模型",
                fileName = target.name,
                downloadUrl = "https://models.example.com/custom.litertlm",
                expectedBytes = 5L,
                expectedSha256 = "0".repeat(64),
                modelId = null,
            )
            val modelRepository = FakeModelRepository(
                customDownloadSource = source,
                downloadedModelFileProvider = { target },
            )
            val downloadClient = FakeModelDownloadClient(
                queryResults = mutableListOf(
                    DownloadInfo(
                        status = DownloadManager.STATUS_SUCCESSFUL,
                        reason = 0,
                        downloadedBytes = 5L,
                        totalBytes = 5L,
                    ),
                ),
                onEnqueue = { _, file -> file.writeText("model", Charsets.UTF_8) },
            )
            val viewModel = createViewModel(
                modelRepository = modelRepository,
                downloadClient = downloadClient,
            )

            viewModel.startCustomModelDownload(source.downloadUrl)
            advanceUntilIdle()

            assertEquals("模型校验失败，请重新下载", viewModel.uiState.value.statusText)
            assertFalse(viewModel.uiState.value.isBusy)
            assertFalse(viewModel.uiState.value.isDownloading)
            assertFalse(target.exists())
            assertEquals(1, modelRepository.clearPendingDownloadCount)
            assertTrue(modelRepository.registeredModels.isEmpty())
        }
    }

    @Test
    fun restoreStartupStateClearsPendingDownloadWhenDownloadTaskMissing() = runTest(dispatcher) {
        withTempDownloadTarget { target ->
            val source = ModelDownloadSource(
                title = "自定义模型",
                fileName = target.name,
                downloadUrl = "https://models.example.com/custom.litertlm",
                expectedBytes = null,
                expectedSha256 = null,
                modelId = null,
            )
            val modelRepository = FakeModelRepository(
                activeModelPath = null,
                downloadedModelFileProvider = { target },
                pendingDownloadId = 42L,
                pendingDownloadSource = source,
            )
            val downloadClient = FakeModelDownloadClient()
            val viewModel = createViewModel(
                modelRepository = modelRepository,
                downloadClient = downloadClient,
            )

            viewModel.restoreStartupState()
            advanceUntilIdle()

            assertEquals("下载任务不存在", viewModel.uiState.value.statusText)
            assertFalse(viewModel.uiState.value.isDownloading)
            assertEquals(1, modelRepository.clearPendingDownloadCount)
            assertEquals(listOf(42L), downloadClient.queriedDownloadIds)
        }
    }

    @Test
    fun remoteModeProtectsSensitiveDirectPromptBeforeCallingRemoteRuntime() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val assistantRouter = FakeAssistantRouter()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        // Tiered handling (P1): a configured-remote sensitive send no longer hard-rejects;
        // it surfaces a forced disclosure offering graded choices, and never calls the runtime
        // until the user explicitly chooses.
        listOf(
            "我的手机号是 13800138000，帮我总结一下",
            "AWS key AKIA1234567890ABCDEF 帮我分析",
            "client_secret = superSecret123 帮我检查",
        ).forEachIndexed { index, sensitivePrompt ->
            viewModel.sendMessage(sensitivePrompt)
            advanceUntilIdle()

            assertTrue(remoteRuntime.calls.isEmpty())
            assertEquals(index + 1, assistantRouter.routeCallCount)
            assertEquals("敏感内容待确认", viewModel.uiState.value.statusText)
            val disclosure = viewModel.uiState.value.pendingRemoteSendDisclosure
            assertNotNull(disclosure)
            // Sensitive disclosures are forced (can never be silenced) and offer graded handling.
            assertTrue(disclosure!!.forcedBySensitiveContent)
            assertTrue(disclosure.allowMaskedSend)
            assertTrue(disclosure.sensitiveHitCategories.isNotEmpty())

            viewModel.dismissRemoteSendDisclosure()
            advanceUntilIdle()
        }
    }

    @Test
    fun confirmRemoteSendWithMaskingSendsRedactedPromptAndRecordsAudit() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("我的手机号是 13800138000，帮我总结一下")
        advanceUntilIdle()
        val disclosure = viewModel.uiState.value.pendingRemoteSendDisclosure
        assertNotNull(disclosure)
        assertTrue(disclosure!!.allowMaskedSend)

        viewModel.confirmRemoteSendWithMasking()
        advanceUntilIdle()

        // The prompt actually sent to the remote runtime is the masked form, not the raw number.
        assertEquals(1, remoteRuntime.calls.size)
        val sentPrompt = remoteRuntime.calls.single().prompt
        assertFalse(sentPrompt.contains("13800138000"))
        // An audit note recording the masked egress was persisted as LocalOnly.
        assertTrue(
            sessionStore.messages.any { message ->
                message.privacy == MessagePrivacy.LocalOnly && message.text.contains("打码后发送")
            },
        )
    }

    @Test
    fun plainRemoteDisclosureConfirmCannotBypassSensitiveDisclosure() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("我的手机号是 13800138000，帮我总结一下")
        advanceUntilIdle()
        val disclosure = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertTrue(disclosure.requiresSensitiveConsent)

        viewModel.confirmRemoteSendDisclosure()
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(disclosure, viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals("敏感内容待确认", viewModel.uiState.value.statusText)
    }

    @Test
    fun confirmRemoteSendDespiteSensitiveSendsRawPromptAndRecordsAudit() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("我的手机号是 13800138000，帮我总结一下")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)

        viewModel.confirmRemoteSendDespiteSensitive()
        advanceUntilIdle()

        // "Send anyway" transmits the original prompt unchanged.
        assertEquals(1, remoteRuntime.calls.size)
        assertTrue(remoteRuntime.calls.single().prompt.contains("13800138000"))
        // The override decision is audited as LocalOnly.
        assertTrue(
            sessionStore.messages.any { message ->
                message.privacy == MessagePrivacy.LocalOnly && message.text.contains("仍原样发送")
            },
        )
    }

    @Test
    fun confirmRemoteSendDespiteSensitiveWorksWhenSensitiveHitCannotBeMasked() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        val prompt = "my medical diagnosis is asthma, summarize the next steps"
        viewModel.sendMessage(prompt)
        advanceUntilIdle()
        val disclosure = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertTrue(disclosure.requiresSensitiveConsent)
        assertFalse(disclosure.allowMaskedSend)
        assertTrue(disclosure.sensitiveHitCategories.contains("疑似敏感领域信息"))

        viewModel.confirmRemoteSendDespiteSensitive()
        advanceUntilIdle()

        assertEquals(1, remoteRuntime.calls.size)
        assertEquals(prompt, remoteRuntime.calls.single().prompt)
        assertTrue(
            sessionStore.messages.any { message ->
                message.privacy == MessagePrivacy.LocalOnly && message.text.contains("仍原样发送")
            },
        )
    }

    @Test
    fun remoteModeUnconfiguredSendAttemptShowsLocalNoticeWithoutCallingRuntime() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val assistantRouter = FakeAssistantRouter()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = RemoteModelConfig(),
            ),
            assistantRouter = assistantRouter,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(0, assistantRouter.routeCallCount)
        assertEquals("请配置远程模型", viewModel.uiState.value.statusText)
        assertEquals(ModelHealthState.LoadFailed, viewModel.uiState.value.modelHealth.state)
        assertEquals("远程模型未配置", viewModel.uiState.value.modelHealth.failureReason)
        val notice = sessionStore.messages.single()
        assertEquals(MessageRole.Assistant, notice.role)
        assertEquals(MessagePrivacy.LocalOnly, notice.privacy)
        assertTrue(notice.text.contains("配置远程模型地址和模型名"))
        assertTrue(notice.text.contains("还没有发送"))
        assertFalse(notice.text.contains("普通远程问题"))
    }

    @Test
    fun clearingRemoteConfigWhileInRemoteModeBlocksRemoteSend() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val assistantRouter = FakeAssistantRouter()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isReady)
        assertEquals(InferenceMode.Remote, viewModel.uiState.value.inferenceMode)

        viewModel.updateRemoteModelConfig(RemoteModelConfig())
        advanceUntilIdle()
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(0, assistantRouter.routeCallCount)
        assertEquals("请配置远程模型", viewModel.uiState.value.statusText)
        assertEquals(ModelHealthState.LoadFailed, viewModel.uiState.value.modelHealth.state)
        assertEquals("远程模型未配置", viewModel.uiState.value.modelHealth.failureReason)
        val notice = sessionStore.messages.single()
        assertEquals(MessageRole.Assistant, notice.role)
        assertEquals(MessagePrivacy.LocalOnly, notice.privacy)
        assertTrue(notice.text.contains("配置远程模型地址和模型名"))
        assertTrue(notice.text.contains("还没有发送"))
        assertFalse(notice.text.contains("普通远程问题"))
    }

    @Test
    fun remoteModeUnconfiguredSharedImageSignalShowsConfigNoticeWithoutReadingOrSending() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val assistantRouter = FakeAssistantRouter()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(
                mode = InferenceMode.Remote,
                config = RemoteModelConfig(),
            ),
            assistantRouter = assistantRouter,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.ingestSharedInput(
            SharedInput(
                text = "",
                attachments = emptyList(),
                protectedSourceCount = 1,
                protectedImageSourceCount = 1,
            ),
        )
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(0, assistantRouter.routeCallCount)
        assertEquals(null, viewModel.uiState.value.pendingSharedInputDraft)
        assertEquals("请配置远程模型", viewModel.uiState.value.statusText)
        assertEquals(ModelHealthState.LoadFailed, viewModel.uiState.value.modelHealth.state)
        assertEquals("远程模型未配置", viewModel.uiState.value.modelHealth.failureReason)
        val notice = sessionStore.messages.single()
        assertEquals(MessageRole.Assistant, notice.role)
        assertEquals(MessagePrivacy.LocalOnly, notice.privacy)
        assertTrue(notice.text.contains("配置远程模型地址和模型名"))
        assertTrue(notice.text.contains("没有发送这次分享内容"))
        assertTrue(notice.text.contains("确认发送"))
        assertFalse(notice.text.contains("1"))
        assertFalse(notice.text.contains("data:image"))
    }

    @Test
    fun remoteModeFiltersSensitiveRemoteEligibleHistoryBeforeCallingRemoteRuntime() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore(
            initialSessions = mapOf(
                "session-1" to listOf(
                    ChatMessage(
                        role = MessageRole.User,
                        text = "公开上下文：我在比较城市天气",
                        privacy = MessagePrivacy.RemoteEligible,
                    ),
                    ChatMessage(
                        role = MessageRole.User,
                        text = "历史误标内容：我的手机号是 13800138000",
                        privacy = MessagePrivacy.RemoteEligible,
                    ),
                ),
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val historyText = remoteRuntime.calls.single().history.joinToString("\n") { it.text }
        assertTrue(historyText.contains("公开上下文"))
        assertFalse(historyText.contains("13800138000"))
        assertFalse(historyText.contains("历史误标内容"))
    }

    @Test
    fun remoteModeProtectsSharedInputBeforeBuildingPrompt() = runTest(dispatcher) {
        data class ProtectedShareCase(
            val sharedInput: SharedInput,
            val forbiddenText: List<String>,
            val verifyNextRemoteSend: Boolean = false,
        )

        val cases = listOf(
            ProtectedShareCase(
                sharedInput = SharedInput(text = "私密分享正文", attachments = emptyList()),
                forbiddenText = listOf("私密分享正文"),
                verifyNextRemoteSend = true,
            ),
            ProtectedShareCase(
                sharedInput = SharedInput(
                    text = "",
                    attachments = listOf(
                        SharedAttachment(
                            kind = SharedAttachmentKind.Document,
                            mimeType = "application/pdf",
                            displayName = "private-report.pdf",
                            sizeBytes = 12_000L,
                        ),
                    ),
                ),
                forbiddenText = listOf("private-report.pdf", "application/pdf", "12000", "已分享附件"),
            ),
            ProtectedShareCase(
                sharedInput = SharedInput(text = "", attachments = emptyList(), protectedSourceCount = 2),
                forbiddenText = listOf("已收到受保护分享", "2"),
            ),
            ProtectedShareCase(
                sharedInput = sharedTextPreviewInput(
                    mimeType = "text/plain",
                    displayName = "private.txt",
                    sizeBytes = 12L,
                    text = "private excerpt",
                ),
                forbiddenText = listOf("private excerpt", "private.txt", "text/plain", "12"),
            ),
            ProtectedShareCase(
                sharedInput = sharedTextPreviewInput(
                    mimeType = "application/json",
                    displayName = "private-data.json",
                    sizeBytes = 34L,
                    text = "private json excerpt",
                    source = SharedTextPreviewSource.TextFile,
                ),
                forbiddenText = listOf("private json excerpt", "private-data.json", "application/json", "34"),
            ),
            ProtectedShareCase(
                sharedInput = sharedTextPreviewInput(
                    kind = SharedAttachmentKind.Image,
                    mimeType = "image/png",
                    displayName = "private-screen.png",
                    sizeBytes = 12L,
                    text = "private screenshot text",
                    source = SharedTextPreviewSource.ImageOcr,
                ),
                forbiddenText = listOf("private screenshot text", "private-screen.png", "image/png", "12"),
            ),
            ProtectedShareCase(
                sharedInput = sharedTextPreviewInput(
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    displayName = "private-plan.docx",
                    sizeBytes = 12L,
                    text = "private office document excerpt",
                    source = SharedTextPreviewSource.OfficeDocument,
                ),
                forbiddenText = listOf(
                    "private office document excerpt",
                    "private-plan.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "12",
                ),
            ),
            ProtectedShareCase(
                sharedInput = sharedTextPreviewInput(
                    mimeType = "application/rtf",
                    displayName = "private-notes.rtf",
                    sizeBytes = 12L,
                    text = "private rich text excerpt",
                    source = SharedTextPreviewSource.RichTextDocument,
                ),
                forbiddenText = listOf("private rich text excerpt", "private-notes.rtf", "application/rtf", "12"),
            ),
            ProtectedShareCase(
                sharedInput = sharedTextPreviewInput(
                    mimeType = "application/pdf",
                    displayName = "private-plan.pdf",
                    sizeBytes = 12L,
                    text = "private pdf text layer excerpt",
                    source = SharedTextPreviewSource.PdfTextLayer,
                ),
                forbiddenText = listOf("private pdf text layer excerpt", "private-plan.pdf", "application/pdf", "12"),
            ),
            ProtectedShareCase(
                sharedInput = sharedTextPreviewInput(
                    mimeType = "application/pdf",
                    displayName = "private-scan.pdf",
                    sizeBytes = 12L,
                    text = "private pdf scanned page OCR",
                    source = SharedTextPreviewSource.PdfImageOcr,
                ),
                forbiddenText = listOf("private pdf scanned page OCR", "private-scan.pdf", "application/pdf", "12"),
            ),
        )

        cases.forEach { case ->
            val remoteRuntime = RecordingRemoteChatRuntime()
            val sessionStore = FakeSessionStore()
            val viewModel = createViewModel(
                sessionStore = sessionStore,
                remoteRuntime = remoteRuntime,
                remoteStore = configuredRemoteStore(),
            )
            viewModel.restoreStartupState(skipModelRuntimeWork = true)
            advanceUntilIdle()

            viewModel.ingestSharedInput(case.sharedInput)
            advanceUntilIdle()

            assertRemoteProtectedSharedInput(
                remoteRuntime = remoteRuntime,
                sessionStore = sessionStore,
                statusText = viewModel.uiState.value.statusText,
                forbiddenText = case.forbiddenText,
            )

            if (case.verifyNextRemoteSend) {
                viewModel.sendMessage("普通远程问题")
                advanceUntilIdle()

                assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)
                assertTrue(remoteRuntime.calls.single().history.isEmpty())
            }
        }
    }

    @Test
    fun remoteModeSendsSharedImageAttachmentToVisionRuntime() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.stageSharedInput(sharedImageInput())
        advanceUntilIdle()

        val draft = requireNotNull(viewModel.uiState.value.pendingSharedInputDraft)
        assertEquals("screen.png · 图片", draft.summary)
        assertEquals(MessagePrivacy.RemoteEligible, draft.privacy)
        assertEquals(TEST_IMAGE_DATA_URL, draft.imageAttachments.single().dataUrl)
        assertEquals(1, draft.localImageAttachments.size)

        viewModel.sendPendingSharedInput("描述这张图")
        advanceUntilIdle()

        val disclosure = requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals(RemoteSendDisclosureKind.CurrentInput, disclosure.kind)
        assertEquals(1, disclosure.imageAttachmentCount)
        assertNotNull(viewModel.uiState.value.pendingSharedInputDraft)
        assertTrue(remoteRuntime.calls.isEmpty())

        viewModel.confirmRemoteSendDisclosure()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingSharedInputDraft)
        val call = remoteRuntime.calls.single()
        assertTrue(call.prompt.contains("描述这张图"))
        assertTrue(call.prompt.contains("已附加 1 张图片"))
        assertTrue(call.prompt.contains("不支持图片输入"))
        assertFalse(call.prompt.contains("screen.png"))
        assertFalse(call.prompt.contains("image/png"))
        assertFalse(call.prompt.contains("12"))
        assertEquals(TEST_IMAGE_DATA_URL, call.imageAttachments.single().dataUrl)
        assertFalse(sessionStore.messages.first().text.contains("screen.png"))
        assertFalse(sessionStore.messages.first().text.contains("image/png"))
        assertFalse(sessionStore.messages.first().text.contains("12"))
        assertEquals(
            listOf(MessagePrivacy.RemoteEligible, MessagePrivacy.RemoteEligible),
            sessionStore.messages.map { it.privacy },
        )
    }

    @Test
    fun remoteImageDraftWhenRemoteIsNotReadyDoesNotEnterLaterRemoteHistory() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.stageSharedInput(sharedImageInput())
        advanceUntilIdle()

        assertEquals(MessagePrivacy.RemoteEligible, viewModel.uiState.value.pendingSharedInputDraft?.privacy)
        viewModel.updateRemoteModelConfig(RemoteModelConfig())
        advanceUntilIdle()

        viewModel.sendPendingSharedInput("描述这张图")
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(
            listOf(MessagePrivacy.LocalOnly),
            sessionStore.messages.map { it.privacy },
        )
        assertEquals(MessageRole.Assistant, sessionStore.messages.single().role)
        assertTrue(sessionStore.messages.single().text.contains("配置远程模型地址和模型名"))
        assertTrue(sessionStore.messages.single().text.contains("没有发送这次分享内容"))
        assertTrue(sessionStore.messages.single().text.contains("不会被自动 OCR"))
        assertFalse(sessionStore.messages.single().text.contains("screen.png"))
        assertFalse(sessionStore.messages.single().text.contains("image/png"))
        assertFalse(sessionStore.messages.single().text.contains("12"))
        assertEquals("请配置远程模型", viewModel.uiState.value.statusText)

        viewModel.updateRemoteModelConfig(configuredRemoteModel())
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val call = remoteRuntime.calls.single()
        assertFalse(call.history.toString().contains("screen.png"))
        assertFalse(call.history.toString().contains("data:image/png"))
    }

    @Test
    fun remoteImageDraftIsDiscardedWhenSwitchingToReadyLocalMode() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val runtime = FakeLiteRtRuntime().apply { isLoaded = true }
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = runtime,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.stageSharedInput(sharedImageInput(displayName = "private-screen.png"))
        advanceUntilIdle()

        assertEquals(MessagePrivacy.RemoteEligible, viewModel.uiState.value.pendingSharedInputDraft?.privacy)
        runtime.isLoaded = true
        viewModel.selectInferenceMode(InferenceMode.Local)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingSharedInputDraft)
        viewModel.sendPendingSharedInput("描述这张图")
        advanceUntilIdle()

        assertTrue(runtime.localRequests.isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())
        assertTrue(sessionStore.messages.isEmpty())

        viewModel.selectInferenceMode(InferenceMode.Remote)
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val call = remoteRuntime.calls.single()
        assertFalse(call.prompt.contains("private-screen.png"))
        assertFalse(call.prompt.contains("data:image/png"))
        assertFalse(call.history.toString().contains("private-screen.png"))
        assertFalse(call.history.toString().contains("data:image/png"))
    }

    @Test
    fun remoteModeRejectsSharedImageAttachmentWhenVisionIsDisabled() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(
                config = configuredRemoteModel().copy(supportsVisionInput = false),
            ),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.stageSharedInput(sharedImageInput())
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingSharedInputDraft)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(1, sessionStore.messages.size)
        val message = sessionStore.messages.single()
        assertEquals(MessageRole.Assistant, message.role)
        assertEquals(MessagePrivacy.LocalOnly, message.privacy)
        assertTrue(message.text.contains("未启用图片输入能力"))
        assertTrue(message.text.contains("未执行 OCR 或发送图片"))
        assertFalse(message.text.contains("screen.png"))
        assertFalse(message.text.contains("data:image/png"))
        assertEquals("当前远程模型不支持图片输入", viewModel.uiState.value.statusText)
    }

    @Test
    fun remoteModeRejectsProtectedImageSignalWhenVisionIsDisabled() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(
                config = configuredRemoteModel().copy(supportsVisionInput = false),
            ),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.ingestSharedInput(
            SharedInput(
                text = "",
                attachments = emptyList(),
                protectedSourceCount = 1,
                protectedImageSourceCount = 1,
            ),
        )
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingSharedInputDraft)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(1, sessionStore.messages.size)
        val message = sessionStore.messages.single()
        assertEquals(MessageRole.Assistant, message.role)
        assertEquals(MessagePrivacy.LocalOnly, message.privacy)
        assertTrue(message.text.contains("未启用图片输入能力"))
        assertTrue(message.text.contains("其他内容也未读取或发送"))
        assertFalse(message.text.contains("1"))
        assertEquals("当前远程模型不支持图片输入", viewModel.uiState.value.statusText)
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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

        assertEquals("已接收分享内容", viewModel.uiState.value.statusText)
        assertEquals("文本", viewModel.uiState.value.pendingSharedInputDraft?.summary)
        assertTrue(sessionStore.messages.isEmpty())
        assertTrue(localRuntime.prompts.isEmpty())

        viewModel.sendPendingSharedInput()
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
    fun explicitLocalOnlyPromptDoesNotEnterLaterRemoteHistory() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val localRuntime = FakeLiteRtRuntime(localResponse = "本地回复：普通本地回答")
        val remoteStore = FakeRemoteModelStore(mode = InferenceMode.Local)
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            remoteStore = remoteStore,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("本地普通问题", messagePrivacy = MessagePrivacy.LocalOnly)
        advanceUntilIdle()

        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            sessionStore.messages.map { it.privacy },
        )
        assertTrue(sessionStore.messages.last().text.contains("本地回复：普通本地回答"))

        viewModel.updateRemoteModelConfig(configuredRemoteModel())
        viewModel.selectInferenceMode(InferenceMode.Remote)
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val call = remoteRuntime.calls.single()
        assertFalse(call.history.toString().contains("本地普通问题"))
        assertFalse(call.history.toString().contains("本地回复：普通本地回答"))
        assertEquals("普通远程问题", sessionStore.messages.dropLast(1).last().text)
    }

    @Test
    fun stagedSharedImageWaitsForExplicitSendAndStaysLocalOnly() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val runtime = FakeLiteRtRuntime(localResponse = "本地回复：图片文字摘要")
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = runtime,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.stageSharedInput(
            sharedTextPreviewInput(
                kind = SharedAttachmentKind.Image,
                mimeType = "image/png",
                displayName = "receipt.png",
                sizeBytes = 128L,
                text = "private receipt text",
                source = SharedTextPreviewSource.ImageOcr,
            ),
        )
        advanceUntilIdle()

        assertEquals("已选择附件", viewModel.uiState.value.statusText)
        assertEquals("receipt.png · OCR", viewModel.uiState.value.pendingSharedInputDraft?.summary)
        assertTrue(sessionStore.messages.isEmpty())
        assertTrue(runtime.prompts.isEmpty())

        viewModel.sendPendingSharedInput("帮我总结这张图")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingSharedInputDraft)
        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("帮我总结这张图"))
        assertTrue(prompt.contains("receipt.png"))
        assertTrue(prompt.contains("private receipt text"))
        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            sessionStore.messages.map { it.privacy },
        )
    }

    @Test
    fun localSharedPdfImageOcrTruncationIsRecordedInRunDataReceipt() = runTest(dispatcher) {
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-local-pdf-ocr-truncated",
                promptForModel = "本地 PDF OCR 摘录",
                memoryHits = emptyList(),
            ),
        )
        val placementRuntime = FakeChatPlacementRuntime()
        val viewModel = createViewModel(
            runtime = FakeLiteRtRuntime(localResponse = "本地回复：PDF 摘要"),
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
            assistantRouter = assistantRouter,
            chatPlacementRuntime = placementRuntime,
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.stageSharedInput(
            sharedTextPreviewInput(
                mimeType = "application/pdf",
                displayName = "scan.pdf",
                sizeBytes = 512L,
                text = "private scanned pdf OCR excerpt",
                truncated = true,
                source = SharedTextPreviewSource.PdfImageOcr,
            ),
        )
        advanceUntilIdle()

        viewModel.sendPendingSharedInput("总结这份扫描件")
        advanceUntilIdle()

        val receipt = placementRuntime.receipts.single()
        assertEquals(RunDataDestination.Local, receipt.destination)
        assertEquals(MessagePrivacy.LocalOnly.name, receipt.currentPromptPrivacy)
        assertEquals(1, receipt.evidenceCardCount)
        assertEquals(1, receipt.localOnlyEvidenceCardCount)
        assertEquals(1, receipt.truncatedEvidenceCardCount)
        assertTrue(receipt.evidenceSourceTypes.contains("OcrText"))
        assertEquals(false, receipt.rawContentPersisted)
    }

    @Test
    fun stagedSharedImageWithoutOcrWarnsLocalModelThatVisualContentIsUnavailable() = runTest(dispatcher) {
        val runtime = FakeLiteRtRuntime(localResponse = "本地回复：我无法看到图片视觉内容")
        val viewModel = createViewModel(
            runtime = runtime,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.stageSharedInput(
            localSharedImageInput(displayName = "photo.jpg", mimeType = "image/jpeg", sizeBytes = 256L),
        )
        advanceUntilIdle()

        assertEquals("photo.jpg · 不支持视觉", viewModel.uiState.value.pendingSharedInputDraft?.summary)

        viewModel.sendPendingSharedInput("这张图里有什么")
        advanceUntilIdle()

        val prompt = runtime.prompts.single()
        assertTrue(prompt.contains("这张图里有什么"))
        assertTrue(prompt.contains("photo.jpg"))
        assertTrue(prompt.contains("当前模型不支持视觉输入"))
        assertTrue(prompt.contains("不会自动 OCR"))
        assertFalse(prompt.contains("data:image"))
        assertFalse(prompt.contains("base64"))
    }

    @Test
    fun localVisionSharedImageIsSentToLocalRuntimeAndStaysLocalOnly() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val runtime = FakeLiteRtRuntime(localResponse = "本地回复：图片里有收据")
        val modelRepository = FakeModelRepository().apply {
            registerInstalledModel(
                path = "/tmp/gemma-4-E2B-it.litertlm",
                displayName = DEFAULT_CHAT_MODEL.shortName,
                recommendedModelId = DEFAULT_CHAT_MODEL_ID,
                verifiedSha256 = DEFAULT_CHAT_MODEL.sha256Hex,
                verificationStatus = ModelVerificationStatus.VerifiedRecommended,
            )
        }
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            runtime = runtime,
            modelRepository = modelRepository,
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.stageSharedInput(
            localSharedImageInput(displayName = "receipt.png", bytes = byteArrayOf(1, 2, 3, 4)),
        )
        advanceUntilIdle()

        assertEquals("receipt.png · 图片", viewModel.uiState.value.pendingSharedInputDraft?.summary)

        viewModel.sendPendingSharedInput("这张图里有什么")
        advanceUntilIdle()

        val request = runtime.localRequests.single()
        assertEquals(1, request.imageAttachments.size)
        assertEquals("image/png", request.imageAttachments.single().mimeType)
        assertTrue(request.prompt.contains("这张图里有什么"))
        assertTrue(request.prompt.contains("已附加 1 张图片"))
        assertFalse(request.prompt.contains("data:image"))
        assertFalse(request.prompt.contains("base64"))
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            sessionStore.messages.map { it.privacy },
        )
        val persistedText = sessionStore.messages.joinToString(separator = "\n") { it.text }
        assertFalse(persistedText.contains("data:image"))
        assertFalse(persistedText.contains("base64"))
        assertTrue(persistedText.contains("这张图里有什么"))
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
    fun voiceCaptureStateShowsPartialTextAndClearsAfterTranscript() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
        )

        viewModel.startVoiceInputCapture()
        viewModel.updateVoiceInputLevel(7f)
        viewModel.updateVoiceInputPartialTranscript("  第一段\n语音  ")
        advanceUntilIdle()

        val capture = viewModel.uiState.value.voiceCapture
        assertTrue(capture.isListening)
        assertTrue(capture.level > 0.5f)
        assertEquals(9, capture.waveformLevels.size)
        assertTrue(capture.waveformLevels.any { it > 0.5f })
        assertEquals("第一段 语音", capture.partialText)
        assertTrue(sessionStore.messages.isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())

        viewModel.acceptVoiceTranscript("第一段语音")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.voiceCapture.isListening)
        assertEquals("第一段语音", viewModel.uiState.value.voiceInputDraft?.text)
        assertTrue(sessionStore.messages.isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun voiceInputLevelUpdatesAdvanceWaveformSamples() = runTest(dispatcher) {
        val viewModel = createViewModel()

        viewModel.startVoiceInputCapture()
        advanceUntilIdle()
        val initialSamples = viewModel.uiState.value.voiceCapture.waveformLevels

        viewModel.updateVoiceInputLevel(-2f)
        advanceUntilIdle()
        val quietSamples = viewModel.uiState.value.voiceCapture.waveformLevels

        viewModel.updateVoiceInputLevel(8f)
        advanceUntilIdle()
        val loudSamples = viewModel.uiState.value.voiceCapture.waveformLevels

        assertEquals(9, initialSamples.size)
        assertEquals(9, quietSamples.size)
        assertEquals(9, loudSamples.size)
        assertTrue(quietSamples != initialSamples)
        assertTrue((loudSamples.maxOrNull() ?: 0f) > (quietSamples.maxOrNull() ?: 0f))
    }

    @Test
    fun voiceCaptureStaysVisibleWhileTranscribingAfterSpeechEnds() = runTest(dispatcher) {
        val viewModel = createViewModel()

        viewModel.startVoiceInputCapture()
        viewModel.updateVoiceInputPartialTranscript("第一段")
        viewModel.finishVoiceInputCapture()
        advanceUntilIdle()

        val transcribing = viewModel.uiState.value.voiceCapture
        assertFalse(transcribing.isListening)
        assertTrue(transcribing.isTranscribing)
        assertTrue(transcribing.isActive)
        assertEquals("第一段", transcribing.partialText)
        assertEquals("正在转写", viewModel.uiState.value.statusText)

        viewModel.updateVoiceInputPartialTranscript("第一段 第二句")
        advanceUntilIdle()

        assertEquals("第一段 第二句", viewModel.uiState.value.voiceCapture.partialText)

        viewModel.acceptVoiceTranscript("第一段 第二句")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.voiceCapture.isActive)
        assertEquals("第一段 第二句", viewModel.uiState.value.voiceInputDraft?.text)
    }

    @Test
    fun voicePermissionFailureClearsCaptureAndCanRecoverWithoutSending() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val executor = RecordingToolExecutor()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            actionExecutor = executor,
        )

        viewModel.startVoiceInputCapture()
        viewModel.updateVoiceInputPartialTranscript("临时语音")
        viewModel.reportVoiceInputUnavailable("未授权麦克风权限")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.voiceCapture.isActive)
        assertEquals("未授权麦克风权限", viewModel.uiState.value.statusText)
        assertEquals(null, viewModel.uiState.value.voiceInputDraft)
        assertTrue(sessionStore.messages.isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())
        assertTrue(executor.executedRequests.isEmpty())

        viewModel.startVoiceInputCapture()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.voiceCapture.isListening)
        assertEquals("正在收音", viewModel.uiState.value.statusText)
        assertEquals(null, viewModel.uiState.value.voiceInputDraft)
        assertTrue(sessionStore.messages.isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())
        assertTrue(executor.executedRequests.isEmpty())
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
    fun accessibilitySpecialAccessReturnUpdatesStatusTextWithoutExecutingTools() = runTest(dispatcher) {
        val executor = RecordingToolExecutor()
        val viewModel = createViewModel(actionExecutor = executor)
        val requirement = SpecialAccessRequirement(
            id = SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
            title = "无障碍屏幕文本权限",
            rationale = "用于只读获取当前屏幕可访问文本。",
            settingsAction = "android.settings.ACCESSIBILITY_SETTINGS",
        )

        viewModel.reportSpecialAccessResult(requirement, granted = false)
        assertEquals("返回后仍未开启无障碍屏幕文本权限", viewModel.uiState.value.statusText)
        assertTrue(executor.executedRequests.isEmpty())

        viewModel.reportSpecialAccessResult(requirement, granted = true)
        assertEquals("无障碍屏幕文本权限已开启", viewModel.uiState.value.statusText)
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
    fun clipboardSummaryShareLocalContinuationFailureFailsAgentRunWithoutSecondConfirmation() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val localRuntime = FakeLiteRtRuntime(failure = IllegalStateException("local model crashed"))
        val readRequest = ToolRequest(toolName = MobileActionFunctions.READ_CLIPBOARD)
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本。",
            parameters = emptyMap(),
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-share-failure",
                toolRequest = readRequest,
                draft = readDraft,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL,
            ),
            confirmedRun = AgentRun("run-share-failure", "总结剪贴板并分享", AgentRunState.ExecutingTool, 1L, 2L),
            toolObservation = AgentObservationResult(
                run = AgentRun("run-share-failure", "总结剪贴板并分享", AgentRunState.GeneratingAnswer, 1L, 3L),
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
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("总结剪贴板并分享")
        advanceUntilIdle()
        val firstConfirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(firstConfirmation)

        viewModel.confirmAgentConfirmation(firstConfirmation)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals("生成失败，建议重新加载", viewModel.uiState.value.statusText)
        assertEquals(1, assistantRouter.failModelGenerationCallCount)
        assertEquals("run-share-failure", assistantRouter.lastFailedModelRunId)
        assertEquals("local model crashed", assistantRouter.lastFailedModelReason)
        assertEquals(listOf("请总结剪贴板文本"), localRuntime.prompts)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(AgentRunState.Failed, viewModel.uiState.value.agentTraceRuns.single().state)
        assertTrue(sessionStore.messages.last().text.contains("local model crashed"))
        assertEquals(ModelHealthState.LoadFailed, viewModel.uiState.value.modelHealth.state)
        assertTrue(viewModel.uiState.value.modelHealth.failureReason.orEmpty().contains("local model crashed"))
    }

    @Test
    fun localGenerationFailureUpdatesModelHealth() = runTest(dispatcher) {
        val localRuntime = FakeLiteRtRuntime(failure = IllegalStateException("local model crashed"))
        val viewModel = createViewModel(
            runtime = localRuntime,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        assertEquals(ModelHealthState.Loaded, viewModel.uiState.value.modelHealth.state)

        viewModel.sendMessage("普通问题")
        advanceUntilIdle()

        assertEquals("生成失败，建议重新加载", viewModel.uiState.value.statusText)
        assertEquals(ModelHealthState.LoadFailed, viewModel.uiState.value.modelHealth.state)
        assertEquals(BackendChoice.CPU, viewModel.uiState.value.modelHealth.backend)
        assertTrue(viewModel.uiState.value.modelHealth.failureReason.orEmpty().contains("local model crashed"))
    }

    @Test
    fun localGenerationUsesAdaptiveInputBudgetAfterSlowFallbackStats() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore(
            initialSessions = mapOf(
                "session-1" to listOf(
                    ChatMessage(
                        role = MessageRole.Assistant,
                        text = "上一轮回复",
                        generationStats = GenerationStats(
                            tokenCount = 8,
                            tokensPerSecond = 1.0,
                            backend = BackendChoice.CPU,
                            firstTokenMs = 6_000,
                            usedFallbackBackend = true,
                        ),
                    ),
                ),
            ),
        )
        val localRuntime = FakeLiteRtRuntime(localResponse = "本地回复")
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = localRuntime,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("普通问题")
        advanceUntilIdle()

        assertEquals(listOf(4 * 1024), localRuntime.preparedForSendMaxInputTokens)
    }

    @Test
    fun loadModelFallsBackToCpuWhenGpuInitializationFails() = runTest(dispatcher) {
        val generationStore = FakeGenerationParametersStore().apply {
            saveBackend(BackendChoice.GPU)
        }
        val runtime = FakeLiteRtRuntime(
            loadFailures = mapOf(BackendChoice.GPU to IllegalStateException("gpu unavailable")),
        )
        val viewModel = createViewModel(
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
            generationStore = generationStore,
            runtime = runtime,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)

        viewModel.loadModel()
        advanceUntilIdle()

        assertEquals(listOf(BackendChoice.GPU, BackendChoice.CPU), runtime.loadCalls)
        assertEquals(BackendChoice.CPU, viewModel.uiState.value.backend)
        assertTrue(viewModel.uiState.value.isReady)
        assertEquals(ModelHealthState.FallbackActive, viewModel.uiState.value.modelHealth.state)
        assertEquals(BackendChoice.CPU, viewModel.uiState.value.modelHealth.backend)
        assertEquals(BackendChoice.CPU, viewModel.uiState.value.modelHealth.fallbackBackend)
        assertTrue(viewModel.uiState.value.modelHealth.failureReason.orEmpty().contains("gpu unavailable"))
    }

    @Test
    fun loadModelRecordsBothGpuAndCpuFailureReasonsWhenFallbackFails() = runTest(dispatcher) {
        val generationStore = FakeGenerationParametersStore().apply {
            saveBackend(BackendChoice.GPU)
        }
        val runtime = FakeLiteRtRuntime(
            loadFailures = mapOf(
                BackendChoice.GPU to IllegalStateException("gpu unavailable"),
                BackendChoice.CPU to IllegalStateException("cpu unavailable"),
            ),
        )
        val viewModel = createViewModel(
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
            generationStore = generationStore,
            runtime = runtime,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)

        viewModel.loadModel()
        advanceUntilIdle()

        assertEquals(listOf(BackendChoice.GPU, BackendChoice.CPU), runtime.loadCalls)
        assertFalse(viewModel.uiState.value.isReady)
        assertEquals(ModelHealthState.LoadFailed, viewModel.uiState.value.modelHealth.state)
        assertTrue(viewModel.uiState.value.statusText.contains("GPU: gpu unavailable"))
        assertTrue(viewModel.uiState.value.statusText.contains("CPU: cpu unavailable"))
        assertTrue(viewModel.uiState.value.modelHealth.failureReason.orEmpty().contains("CPU: cpu unavailable"))
    }

    @Test
    fun currentScreenTextSummaryShareShowsSecondConfirmationAfterLocalSummary() = runTest(dispatcher) {
        val rawScreenText = "当前屏幕里的私密验证码 654321"
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val localRuntime = FakeLiteRtRuntime(localResponse = "屏幕摘要：验证码仅用于本地确认")
        val readRequest = ToolRequest(
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            arguments = mapOf("maxChars" to "2000"),
        )
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            title = "读取当前屏幕文本",
            summary = "将读取当前屏幕的可访问文本快照，用于生成可分享摘要。",
            parameters = readRequest.arguments,
        )
        val shareRequest = ToolRequest(
            toolName = MobileActionFunctions.SHARE_TEXT,
            arguments = mapOf("text" to "屏幕摘要：验证码仅用于本地确认"),
            reason = "将打开系统分享面板并填入上一步生成的屏幕文本摘要。",
        )
        val shareDraft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "分享屏幕摘要",
            summary = "将打开系统分享面板并填入上一步生成的屏幕文本摘要。",
            parameters = shareRequest.arguments,
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-screen-share",
                toolRequest = readRequest,
                draft = readDraft,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL,
            ),
            confirmedRun = AgentRun("run-screen-share", "总结当前屏幕文字并分享", AgentRunState.ExecutingTool, 1L, 2L),
            toolObservation = AgentObservationResult(
                run = AgentRun("run-screen-share", "总结当前屏幕文字并分享", AgentRunState.GeneratingAnswer, 1L, 3L),
                result = ToolResult(
                    requestId = readRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取当前屏幕可访问文本快照",
                    data = mapOf(
                        "screenText" to "[redacted]",
                        "screenTextIncluded" to "true",
                        "privacy" to MessagePrivacy.LocalOnly.name,
                    ),
                ),
                assistantMessage = "工具执行结果：已读取当前屏幕可访问文本快照",
                decision = AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = true,
                    reason = "当前屏幕文本仅可在本地继续处理。",
                ),
                continuationPromptForModel = "请摘要当前屏幕文本：$rawScreenText",
                continuationRequiresLocalModel = true,
                steps = emptyList(),
            ),
            modelObservation = AgentModelObservationResult(
                run = AgentRun("run-screen-share", "总结当前屏幕文字并分享", AgentRunState.AwaitingUserConfirmation, 1L, 4L),
                decision = AgentObservationDecision.PlanNextTool(
                    plan = AgentPlan.UseTool(
                        request = shareRequest,
                        draft = shareDraft,
                        plannedByModel = false,
                        fallbackReason = "skill model step",
                        skillRequest = SkillRequest(
                            id = "skill-screen-share",
                            skillId = BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL,
                            arguments = mapOf("input" to "总结当前屏幕文字并分享"),
                            reason = "总结当前屏幕文字并分享",
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
        val actionExecutor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult =
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取当前屏幕可访问文本快照",
                    data = mapOf(
                        "screenText" to rawScreenText,
                        "screenTextIncluded" to "true",
                        "privacy" to MessagePrivacy.LocalOnly.name,
                    ),
                )
        }
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("总结当前屏幕文字并分享")
        advanceUntilIdle()
        val firstConfirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(firstConfirmation)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, firstConfirmation.draft.functionName)

        viewModel.confirmAgentConfirmation(firstConfirmation)
        advanceUntilIdle()

        val secondConfirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(secondConfirmation)
        assertEquals(MobileActionFunctions.SHARE_TEXT, secondConfirmation.draft.functionName)
        assertEquals("屏幕摘要：验证码仅用于本地确认", secondConfirmation.toolRequest?.arguments?.get("text"))
        assertEquals(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL, secondConfirmation.skillId)
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
        assertTrue(sessionStore.messages.last().text.contains("屏幕摘要：验证码仅用于本地确认"))
        assertTrue(sessionStore.messages.none { message -> message.text.contains(rawScreenText) })
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
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
    fun remoteModeProtectsCurrentScreenshotOcrBeforeRemoteContinuation() = runTest(dispatcher) {
        val rawOcrText = "当前屏幕截图里的私密验证码 246810"
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val readRequest = ToolRequest(
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            title = "当前屏幕截图 OCR",
            summary = "将请求 Android MediaProjection 前台同意，单次截取当前屏幕并在本地提取 OCR 文本。",
            parameters = readRequest.arguments,
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-current-screenshot-ocr",
                toolRequest = readRequest,
                draft = readDraft,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.CURRENT_SCREENSHOT_OCR_CONTEXT_SKILL,
            ),
            confirmedRun = AgentRun(
                "run-current-screenshot-ocr",
                "识别当前屏幕截图文字",
                AgentRunState.ExecutingTool,
                1L,
                2L,
            ),
            toolObservation = AgentObservationResult(
                run = AgentRun(
                    "run-current-screenshot-ocr",
                    "识别当前屏幕截图文字",
                    AgentRunState.GeneratingAnswer,
                    1L,
                    3L,
                ),
                result = ToolResult(
                    requestId = readRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取当前屏幕截图 OCR 摘录",
                    data = mapOf(
                        "ocrText" to "[redacted]",
                        "ocrTextIncluded" to "true",
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to "true",
                    ),
                ),
                assistantMessage = "工具执行结果：已读取当前屏幕截图 OCR 摘录",
                decision = AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = true,
                    reason = "当前屏幕截图 OCR 内容仅可在本地继续处理。",
                ),
                continuationPromptForModel = "请根据当前屏幕截图 OCR 文本回答：$rawOcrText",
                continuationRequiresLocalModel = true,
                steps = emptyList(),
            ),
        )
        val actionExecutor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult =
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取当前屏幕截图 OCR 摘录",
                    data = mapOf(
                        "ocrText" to rawOcrText,
                        "ocrTextIncluded" to "true",
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to "true",
                    ),
                )
        }
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("识别当前屏幕截图文字")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)
        assertEquals(MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR, confirmation.draft.functionName)
        assertEquals(BuiltInSkillRuntime.CURRENT_SCREENSHOT_OCR_CONTEXT_SKILL, confirmation.skillId)

        viewModel.confirmAgentConfirmation(confirmation)
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals("已保护当前屏幕截图 OCR 内容", viewModel.uiState.value.statusText)
        assertEquals(1, assistantRouter.failModelGenerationCallCount)
        assertEquals("run-current-screenshot-ocr", assistantRouter.lastFailedModelRunId)
        assertTrue(sessionStore.messages.last().text.contains("不会自动发送当前屏幕截图 OCR 内容到远程模型"))
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
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
        assertEquals(1, assistantRouter.failModelGenerationCallCount)
        assertEquals("run-screen-text", assistantRouter.lastFailedModelRunId)
        assertEquals(AgentRunState.Failed, viewModel.uiState.value.agentTraceRuns.single().state)
        assertTrue(sessionStore.messages.last().text.contains("不会自动发送当前屏幕文本到远程模型"))
        assertTrue(sessionStore.messages.none { message -> message.text.contains(rawScreenText) })
    }

    @Test
    fun remoteModeProtectsCurrentScreenTextSummaryShareBeforeRemoteContinuation() = runTest(dispatcher) {
        val rawScreenText = "当前屏幕里的私密验证码 654321"
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val readRequest = ToolRequest(
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            arguments = mapOf("maxChars" to "2000"),
        )
        val readDraft = ActionDraft(
            functionName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            title = "读取当前屏幕文本",
            summary = "将读取当前屏幕的可访问文本快照，用于生成可分享摘要。",
            parameters = readRequest.arguments,
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-screen-share",
                toolRequest = readRequest,
                draft = readDraft,
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL,
            ),
            confirmedRun = AgentRun(
                "run-screen-share",
                "总结当前屏幕文字并分享",
                AgentRunState.ExecutingTool,
                1L,
                2L,
            ),
            toolObservation = AgentObservationResult(
                run = AgentRun(
                    "run-screen-share",
                    "总结当前屏幕文字并分享",
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
                        "privacy" to MessagePrivacy.LocalOnly.name,
                    ),
                ),
                assistantMessage = "工具执行结果：已读取当前屏幕可访问文本快照",
                decision = AgentObservationDecision.ContinueWithModel(
                    requiresLocalModel = true,
                    reason = "当前屏幕文本仅可在本地继续处理。",
                ),
                continuationPromptForModel = "请摘要当前屏幕文本并分享：$rawScreenText",
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
                        "privacy" to MessagePrivacy.LocalOnly.name,
                    ),
                )
        }
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("总结当前屏幕文字并分享")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, confirmation.draft.functionName)
        assertEquals(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL, confirmation.skillId)

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
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = actionExecutor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
            remoteStore = configuredRemoteStore(),
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
        assertEquals("已保护本地工具结果", viewModel.uiState.value.statusText)
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
        assertTrue(sessionStore.messages.last().text.contains("不会自动发送本地工具结果到远程模型"))
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
    fun sendMessagePassesActiveSessionIdToAgentRoute() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore(initialActiveSessionId = "session-1")
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("打开 Wi-Fi 设置")
        advanceUntilIdle()

        assertEquals("session-1", assistantRouter.lastRouteSessionId)
    }

    @Test
    fun selectingSessionRestoresLocalConversationWithoutGlobalBusy() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val selectedMessage = ChatMessage(MessageRole.User, "切换后的会话")
        val runtime = FakeLiteRtRuntime().apply { isLoaded = true }
        val sessionStore = FakeSessionStore(
            initialSessions = linkedMapOf(
                "session-1" to listOf(ChatMessage(MessageRole.User, "旧会话")),
                "session-2" to listOf(selectedMessage),
            ),
            initialActiveSessionId = "session-1",
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = runtime,
            ioDispatcher = ioDispatcher,
        )

        viewModel.selectSession("session-2")

        assertEquals("session-2", viewModel.uiState.value.activeSessionId)
        assertEquals(listOf(selectedMessage), viewModel.uiState.value.messages)
        assertFalse(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.isReady)
        assertEquals("正在恢复会话", viewModel.uiState.value.statusText)
        assertEquals(0, runtime.recreateCallCount)

        advanceUntilIdle()

        assertEquals(1, runtime.recreateCallCount)
        assertEquals(listOf(selectedMessage), runtime.recreatedHistories.single())
        assertFalse(viewModel.uiState.value.isBusy)
        assertTrue(viewModel.uiState.value.isReady)
        assertEquals("已恢复会话 · CPU", viewModel.uiState.value.statusText)
    }

    @Test
    fun localDeviceContextToolReadinessUsesLatestAuthorizationSnapshot() = runTest(dispatcher) {
        val assistantRouter = FakeAssistantRouter()
        val viewModel = createViewModel(
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()
        viewModel.updateDeviceContextAuthorizationSnapshot(
            DeviceContextAuthorizationSnapshot(
                grantedRuntimePermissions = setOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ),
                grantedSpecialAccessIds = setOf(SPECIAL_ACCESS_USAGE_STATS),
            ),
        )

        viewModel.sendMessage("检查设备上下文")
        advanceUntilIdle()

        val readiness = requireNotNull(assistantRouter.lastRouteDeviceContext)
            .toolReadiness
            .associateBy { it.toolName }
        assertEquals(
            DeviceContextToolReadinessState.Available,
            readiness[MobileActionFunctions.QUERY_CONTACTS]?.state,
        )
        assertEquals(
            DeviceContextToolReadinessState.Available,
            readiness[MobileActionFunctions.QUERY_FOREGROUND_APP]?.state,
        )
        assertEquals(
            DeviceContextToolReadinessState.Available,
            readiness[MobileActionFunctions.QUERY_RECENT_FILES]?.state,
        )
        assertEquals(
            DeviceContextToolReadinessState.Available,
            readiness[MobileActionFunctions.READ_RECENT_IMAGE_OCR]?.state,
        )
        assertEquals(
            DeviceContextToolReadinessState.RequiresRuntimePermission,
            readiness[MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY]?.state,
        )
        assertEquals(
            DeviceContextToolReadinessState.RequiresSpecialAccess,
            readiness[MobileActionFunctions.READ_CURRENT_SCREEN_TEXT]?.state,
        )
    }

    @Test
    fun deleteActiveSessionClearsSessionAgentTraceAndPendingConfirmation() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore(
            initialSessions = linkedMapOf(
                "session-delete" to listOf(ChatMessage(MessageRole.User, "旧会话")),
                "session-keep" to listOf(ChatMessage(MessageRole.User, "保留会话")),
            ),
            initialActiveSessionId = "session-delete",
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-delete",
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()
        viewModel.sendMessage("打开 Wi-Fi 设置")
        advanceUntilIdle()
        requireNotNull(viewModel.uiState.value.pendingConfirmation)

        viewModel.deleteActiveSession()
        advanceUntilIdle()

        assertEquals(listOf("session-delete"), assistantRouter.deletedTraceSessionIds)
        assertEquals("session-keep", viewModel.uiState.value.activeSessionId)
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals("session-keep", assistantRouter.lastRestorePendingSessionId)
        assertEquals("保留会话", viewModel.uiState.value.messages.single().text)
    }

    @Test
    fun deleteOnlyActiveSessionClearsMessagesAndPendingSharedDraft() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore(
            initialSessions = linkedMapOf(
                "session-only" to listOf(ChatMessage(MessageRole.User, "要清掉的聊天记录")),
            ),
            initialActiveSessionId = "session-only",
        )
        val assistantRouter = FakeAssistantRouter()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            assistantRouter = assistantRouter,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.stageSharedInput(SharedInput(text = "待发送附件内容", attachments = emptyList()))
        advanceUntilIdle()
        requireNotNull(viewModel.uiState.value.pendingSharedInputDraft)

        viewModel.deleteActiveSession()
        advanceUntilIdle()

        assertEquals(listOf("session-only"), assistantRouter.deletedTraceSessionIds)
        assertEquals("session-2", viewModel.uiState.value.activeSessionId)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertEquals(null, viewModel.uiState.value.pendingSharedInputDraft)
        assertEquals(1, viewModel.uiState.value.sessions.size)
        assertEquals(0, viewModel.uiState.value.sessions.single().messageCount)
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
    fun tamperedPendingConfirmationToolArgumentsDoNotExecute() = runTest(dispatcher) {
        val request = ToolRequest(
            id = "share-request",
            toolName = MobileActionFunctions.SHARE_TEXT,
            arguments = mapOf("text" to "original share text"),
            reason = "分享文本",
        )
        val executor = RecordingToolExecutor()
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-share",
                toolRequest = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.SHARE_TEXT,
                    title = "系统分享",
                    summary = "分享文本",
                    parameters = request.arguments,
                ),
                plannedByModel = false,
                fallbackReason = "test fallback",
                skillId = BuiltInSkillRuntime.SHARE_TEXT_SKILL,
            ),
            confirmedRun = AgentRun("run-share", "分享文本", AgentRunState.ExecutingTool, 1L, 2L),
        )
        val viewModel = createViewModel(
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("分享这段文字：original share text")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)
        val tampered = confirmation.copy(
            toolRequest = request.copy(arguments = mapOf("text" to "tampered share text")),
            draft = confirmation.draft.copy(parameters = request.arguments),
        )

        viewModel.confirmAgentConfirmation(tampered)
        advanceUntilIdle()

        assertEquals(0, assistantRouter.confirmCallCount)
        assertTrue(executor.executedRequests.isEmpty())
        assertEquals(confirmation, viewModel.uiState.value.pendingConfirmation)
        assertEquals("工具确认已处理", viewModel.uiState.value.statusText)
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
        val remoteStore = configuredRemoteStore()
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
    fun stopGenerationCancelsActiveAgentRunForLocalChat() = runTest(dispatcher) {
        val localRuntime = FakeLiteRtRuntime(hangDuringSend = true)
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-stop",
                promptForModel = "本地生成 prompt",
                memoryHits = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            runtime = localRuntime,
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("普通问题")

        assertTrue(viewModel.uiState.value.isGenerating)
        assertEquals(listOf("本地生成 prompt"), localRuntime.preparedForSendPrompts)
        assertEquals(listOf("本地生成 prompt"), localRuntime.prompts)

        viewModel.stopGeneration()
        advanceUntilIdle()

        assertEquals(1, localRuntime.stopCallCount)
        assertEquals(1, assistantRouter.cancelRunCallCount)
        assertEquals("run-stop", assistantRouter.lastCancelledRunId)
        assertTrue(assistantRouter.lastCancelledRunReason.orEmpty().contains("stopped"))
        assertFalse(viewModel.uiState.value.isGenerating)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(listOf("run-stop"), viewModel.uiState.value.agentTraceRuns.map { it.id })
        assertEquals(AgentRunState.Cancelled, viewModel.uiState.value.agentTraceRuns.single().state)
    }

    @Test
    fun stopGenerationCancelsActiveAgentRunForRemoteChat() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime(hangDuringSend = true)
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-stop",
                promptForModel = "远程生成 prompt",
                memoryHits = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("远程生成 prompt")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isGenerating)
        assertEquals("远程生成 prompt", remoteRuntime.calls.single().prompt)

        viewModel.stopGeneration()
        advanceUntilIdle()

        assertEquals(1, remoteRuntime.stopCallCount)
        assertEquals(1, assistantRouter.cancelRunCallCount)
        assertEquals("run-remote-stop", assistantRouter.lastCancelledRunId)
        assertTrue(assistantRouter.lastCancelledRunReason.orEmpty().contains("stopped"))
        assertFalse(viewModel.uiState.value.isGenerating)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(listOf("run-remote-stop"), viewModel.uiState.value.agentTraceRuns.map { it.id })
        assertEquals(AgentRunState.Cancelled, viewModel.uiState.value.agentTraceRuns.single().state)
    }

    @Test
    fun stopGenerationUsesBoundRemoteAfterPreferenceProjectionChanges() = runTest(dispatcher) {
        val localRuntime = FakeLiteRtRuntime()
        val remoteRuntime = RecordingRemoteChatRuntime(hangDuringSend = true)
        val placementRuntime = FakeChatPlacementRuntime()
        val viewModel = createViewModel(
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            chatPlacementRuntime = placementRuntime,
            assistantRouter = FakeAssistantRouter(
                routeResult = AssistantRoute.Chat(
                    runId = "run-bound-remote-stop",
                    promptForModel = "remote prompt",
                    memoryHits = emptyList(),
                ),
            ),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("remote prompt")
        advanceUntilIdle()

        forceInferenceMode(viewModel, InferenceMode.Local)
        viewModel.stopGeneration()
        advanceUntilIdle()

        assertEquals(0, localRuntime.stopCallCount)
        assertEquals(1, remoteRuntime.stopCallCount)
        assertEquals(listOf("run-bound-remote-stop"), placementRuntime.stoppedRunIds)
    }

    @Test
    fun remoteServingFailureNeverFallsBackToLocalRuntime() = runTest(dispatcher) {
        val localRuntime = FakeLiteRtRuntime()
        val remoteRuntime = RecordingRemoteChatRuntime(failure = IOException("remote failed"))
        val viewModel = createViewModel(
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("remote only")
        advanceUntilIdle()

        assertEquals(1, remoteRuntime.calls.size)
        assertTrue(localRuntime.prompts.isEmpty())
    }

    @Test
    fun oneBoundChatRunInvokesExactlyOneServingRuntime() = runTest(dispatcher) {
        val localRuntime = FakeLiteRtRuntime()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val localViewModel = createViewModel(
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        localViewModel.restoreStartupState()
        advanceUntilIdle()

        localViewModel.sendMessage("local only")
        advanceUntilIdle()

        assertEquals(1, localRuntime.prompts.size)
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun missingPlacementBindingInvokesNeitherServingRuntime() = runTest(dispatcher) {
        val localRuntime = FakeLiteRtRuntime()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            chatPlacementRuntime = FakeChatPlacementRuntime(rejectBinding = true),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("must fail closed")
        advanceUntilIdle()

        assertTrue(localRuntime.prompts.isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(PlacementReasonCode.PLACEMENT_NOT_RESTORABLE, viewModel.uiState.value.activeRunPlacementReason)
    }

    @Test
    fun sessionSwitchAbortsAwaitingDisclosureBinding() = runTest(dispatcher) {
        val placementRuntime = FakeChatPlacementRuntime()
        val viewModel = createViewModel(
            remoteStore = configuredRemoteStore(),
            chatPlacementRuntime = placementRuntime,
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.EveryMessage)
        viewModel.sendMessage("remote pending")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)

        viewModel.createNewSession()
        advanceUntilIdle()

        assertEquals(1, placementRuntime.abortCount)
        assertNull(viewModel.uiState.value.pendingRemoteSendDisclosure)
    }

    @Test
    fun disclosureResumeBecomesBusyBeforeRemoteRuntimeCompletes() = runTest(dispatcher) {
        val viewModel = createViewModel(
            remoteRuntime = RecordingRemoteChatRuntime(hangDuringSend = true),
            remoteStore = configuredRemoteStore(),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.EveryMessage)
        viewModel.sendMessage("remote pending")
        advanceUntilIdle()

        viewModel.confirmRemoteSendDisclosure()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isBusy)
        viewModel.stopGeneration()
        advanceUntilIdle()
    }

    @Test
    fun stopBeforePreparedServingAwaitNeverStartsRuntime() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            chatPlacementRuntime = FakeChatPlacementRuntime(stopBeforeAwait = true),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("must never start")
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun failedDispatchDoesNotRecordSensitiveSendAsSent() = runTest(dispatcher) {
        val auditStore = FakeRemoteSendAuditStore()
        val viewModel = createViewModel(
            remoteStore = configuredRemoteStore(),
            chatPlacementRuntime = FakeChatPlacementRuntime(
                dispatchFailure = IllegalStateException("claim failed"),
            ),
            remoteSendAuditStore = auditStore,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("我的手机号是 13800138000")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)

        viewModel.confirmRemoteSendWithMasking()
        advanceUntilIdle()

        assertTrue(auditStore.recentRemoteSends().isEmpty())
    }

    @Test
    fun preparedInitialDispatchPublishesOneReceiptThroughPlacementClaim() = runTest(dispatcher) {
        val placementRuntime = FakeChatPlacementRuntime()
        val assistantRouter = FakeAssistantRouter()
        val viewModel = createViewModel(
            remoteStore = configuredRemoteStore(),
            chatPlacementRuntime = placementRuntime,
            assistantRouter = assistantRouter,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("one receipt")
        advanceUntilIdle()

        assertEquals(1, placementRuntime.receipts.size)
        assertNull(assistantRouter.lastRecordedRunDataReceipt)
    }

    @Test
    fun disclosureConfirmationUsesFrozenGenerationParameters() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime()
        val frozen = GenerationParameters(temperature = 0.2f, topP = 0.8f, topK = 20)
        val changed = GenerationParameters(temperature = 1.1f, topP = 0.4f, topK = 80)
        val viewModel = createViewModel(
            generationStore = FakeGenerationParametersStore(frozen),
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.EveryMessage)
        viewModel.sendMessage("frozen parameters")
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure)

        viewModel.updateGenerationParameters(changed)
        viewModel.confirmRemoteSendDisclosure()
        advanceUntilIdle()

        assertEquals(frozen, remoteRuntime.calls.single().parameters)
    }

    @Test
    fun onClearedTerminatesActiveRunOnlyOnce() = runTest(dispatcher) {
        val localRuntime = FakeLiteRtRuntime(hangDuringSend = true)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-clear",
                promptForModel = "本地生成 prompt",
                memoryHits = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()
        viewModel.sendMessage("普通问题")

        invokeOnCleared(viewModel)
        invokeOnCleared(viewModel)
        advanceUntilIdle()

        assertEquals(1, assistantRouter.terminateRunCallCount)
        assertEquals("run-clear", assistantRouter.lastTerminatedRunId)
        assertTrue(assistantRouter.lastTerminatedRunReason.orEmpty().contains("ViewModel cleared"))
        assertEquals(1, localRuntime.stopCallCount)
        assertEquals(0, remoteRuntime.stopCallCount)
    }

    @Test
    fun remoteWebSearchToolCallExecutesWithoutPendingConfirmation() = runTest(dispatcher) {
        val request = ToolRequest(
            id = "call-1",
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to "Kotlin"),
            reason = "remote tool call",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.WEB_SEARCH,
            title = "Web 搜索",
            summary = "将使用 Web 搜索工具查询并整理结果：Kotlin",
            parameters = request.arguments,
        )
        val plan = AgentPlan.UseTool(
            request = request,
            draft = draft,
            plannedByModel = true,
            fallbackReason = "remote tool call",
            safetyDecision = SafetyDecision(
                outcome = SafetyOutcome.Allow,
                reason = "Read-only web search can execute without confirmation.",
            ),
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-tool",
                promptForModel = "搜索 Kotlin",
                memoryHits = emptyList(),
            ),
            modelToolObservation = AgentModelObservationResult(
                run = AgentRun("run-remote-tool", "搜索 Kotlin", AgentRunState.ExecutingTool, 1L, 2L),
                decision = AgentObservationDecision.PlanNextTool(plan, "Remote model requested a tool call."),
                steps = emptyList(),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime(
            events = listOf(RemoteChatEvent.ToolCall(request)),
        )
        val executor = RecordingToolExecutor()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("搜索 Kotlin")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals(1, assistantRouter.observeModelToolCallCount)
        assertEquals(request, assistantRouter.lastObservedModelToolRequest)
        assertEquals(1, executor.executedRequests.size)
        assertEquals(request, executor.executedRequests.single())
        assertEquals(1, assistantRouter.observeToolCallCount)
        assertTrue(remoteRuntime.calls.single().tools.any { tool -> tool.name == MobileActionFunctions.WEB_SEARCH })
        assertEquals(MessagePrivacy.RemoteEligible, sessionStore.messages.first().privacy)
        val toolStatusMessage = sessionStore.messages.single { message ->
            message.text.contains("正在使用工具：Web 搜索")
        }
        assertEquals(MessagePrivacy.LocalOnly, toolStatusMessage.privacy)
        assertEquals(MessagePrivacy.RemoteEligible, sessionStore.messages.last().privacy)
        assertEquals("executed", viewModel.uiState.value.statusText)
    }

    @Test
    fun remoteModeUsesModelFirstPlanningAndExposesSafePlanningToolsToRemoteRuntime() = runTest(dispatcher) {
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-filter",
                promptForModel = "北京天气怎么样？",
                memoryHits = emptyList(),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("北京天气怎么样？")
        advanceUntilIdle()

        val tools = remoteRuntime.calls.single().tools
        assertEquals(InitialPlanningMode.ModelFirstRemoteTools, assistantRouter.lastRouteOptions?.initialPlanningMode)
        assertEquals(RemoteToolScope.ModelPlanning, assistantRouter.lastRouteOptions?.remoteToolScope)
        assertTrue(tools.isNotEmpty())
        assertTrue(tools.any { spec -> spec.name == MobileActionFunctions.WEB_SEARCH })
        assertTrue(tools.any { spec -> spec.name == MobileActionFunctions.COMPOSE_EMAIL })
        assertTrue(tools.any { spec -> spec.name == MobileActionFunctions.SEARCH_MAPS })
        assertTrue(tools.any { spec -> spec.name == MobileActionFunctions.SHARE_TEXT })
        assertTrue(tools.none { spec -> spec.privateOutputKeys.isNotEmpty() })
        assertFalse(tools.any { spec -> spec.name == MobileActionFunctions.READ_CLIPBOARD })
        assertFalse(tools.any { spec -> spec.name == MobileActionFunctions.QUERY_CONTACTS })
        assertFalse(tools.any { spec -> spec.name == MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR })
    }

    @Test
    fun remoteModeFailsClosedWhenRouteIncludesMemoryContext() = runTest(dispatcher) {
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-memory-boundary",
                promptForModel = "普通远程问题",
                memoryHits = listOf(
                    MemoryHit(
                        id = "pref-secret",
                        text = "用户偏好：secret local preference",
                        score = 1f,
                    ),
                ),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(1, assistantRouter.failModelGenerationCallCount)
        assertEquals("run-remote-memory-boundary", assistantRouter.lastFailedModelRunId)
        assertEquals("已阻止远程发送", viewModel.uiState.value.statusText)
        assertEquals(MessagePrivacy.RemoteEligible, sessionStore.messages.first().privacy)
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
        assertTrue(sessionStore.messages.last().text.contains("本地记忆上下文"))
        assertTrue(sessionStore.messages.none { message -> message.text.contains("secret local preference") })
    }

    @Test
    fun remoteModeFailsClosedWhenRouteRewritesPrompt() = runTest(dispatcher) {
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-prompt-boundary",
                promptForModel = "本地记忆：secret\n\n普通远程问题",
                memoryHits = emptyList(),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(1, assistantRouter.failModelGenerationCallCount)
        assertEquals("run-remote-prompt-boundary", assistantRouter.lastFailedModelRunId)
        assertEquals("已阻止远程发送", viewModel.uiState.value.statusText)
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
        assertTrue(sessionStore.messages.last().text.contains("修改了远程 prompt"))
        assertTrue(sessionStore.messages.none { message -> message.text.contains("本地记忆：secret") })
    }

    @Test
    fun remotePublicEvidenceToolCallBatchExecutesAndContinuesWithModel() = runTest(dispatcher) {
        val requests = publicWeatherBatchRequests()
        val publicEvidencePack = PublicWebEvidencePack(
            query = "北京 上海 天气",
            retrievedAt = "2026-07-02T10:00:00Z",
            freshness = "current",
            quality = "High",
            items = listOf(
                PublicWebEvidenceItem(
                    sourceId = "S1",
                    title = "北京上海天气",
                    url = "https://weather.example.com/beijing-shanghai",
                    snippet = "北京和上海当前天气。",
                    sourceName = "Weather Example",
                    qualityLabel = "High",
                ),
            ),
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-tool-batch",
                promptForModel = "北京和上海今天温差多少？",
                memoryHits = emptyList(),
            ),
            modelToolBatchObservation = publicEvidenceBatchModelObservation("run-remote-tool-batch", requests),
            toolBatchObservation = publicEvidenceBatchToolObservation("run-remote-tool-batch"),
            modelObservation = publicEvidenceBatchCompletedObservation("run-remote-tool-batch"),
            publicWebEvidenceByRunId = mapOf("run-remote-tool-batch" to listOf(publicEvidencePack)),
        )
        val remoteRuntime = RecordingRemoteChatRuntime(
            eventBatches = listOf(
                listOf(RemoteChatEvent.ToolCalls(requests)),
                listOf(RemoteChatEvent.TextDelta("北京和上海今天温差约 3 度。")),
            ),
        )
        val executor = RecordingToolExecutor()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("北京和上海今天温差多少？")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertEquals("北京和上海今天温差多少？", remoteRuntime.calls.first().prompt)

        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals(1, assistantRouter.observeModelToolBatchCallCount)
        assertEquals(requests, assistantRouter.lastObservedModelToolRequests)
        assertEquals(0, assistantRouter.observeModelToolCallCount)
        assertEquals(2, executor.executedRequests.size)
        assertEquals(requests.map { request -> request.id }.toSet(), executor.executedRequests.map { request -> request.id }.toSet())
        assertEquals(1, assistantRouter.observeToolBatchCallCount)
        assertEquals(requests.map { request -> request.id }.toSet(), assistantRouter.lastObservedResults.map { result -> result.requestId }.toSet())
        assertEquals(0, assistantRouter.observeToolCallCount)
        assertEquals(2, remoteRuntime.calls.size)
        assertEquals("请综合北京和上海的天气结果计算温差。", remoteRuntime.calls.last().prompt)
        assertEquals(false, assistantRouter.lastObserveModelResultAllowInlineToolCalls)
        val continuationTools = remoteRuntime.calls.last().tools
        assertTrue(continuationTools.any { tool -> tool.name == MobileActionFunctions.WEB_SEARCH })
        assertFalse(continuationTools.any { tool -> tool.name == MobileActionFunctions.COMPOSE_EMAIL })
        assertFalse(continuationTools.any { tool -> tool.name == MobileActionFunctions.SEARCH_MAPS })
        assertFalse(continuationTools.any { tool -> tool.name == MobileActionFunctions.SHARE_TEXT })
        assertTrue(sessionStore.messages.any { message -> message.text.contains("正在并行使用工具：Web 搜索 x2") })
        assertTrue(sessionStore.messages.any { message -> message.text.contains("已完成 2 个公开只读工具调用") })
        assertEquals("北京和上海今天温差约 3 度。", sessionStore.messages.last().text)
        assertEquals(MessagePrivacy.RemoteEligible, sessionStore.messages.last().privacy)
        assertEquals(listOf(publicEvidencePack), viewModel.uiState.value.activePublicWebEvidence)
        assertEquals("就绪 · 远程", viewModel.uiState.value.statusText)
    }

    @Test
    fun publicEvidenceCitationRetryKeepsFinalAnswerRemoteEligible() = runTest(dispatcher) {
        val runId = "run-public-citation-retry"
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = runId,
                promptForModel = "北京今天气温是多少？",
                memoryHits = emptyList(),
            ),
            modelObservations = listOf(
                AgentModelObservationResult(
                    run = publicEvidenceBatchRun(runId, AgentRunState.GeneratingAnswer, 2L),
                    decision = AgentObservationDecision.ContinueWithModel(
                        requiresLocalModel = false,
                        reason = "public_evidence_citation_retry",
                    ),
                    continuationPromptForModel = "请补充公开来源引用。",
                    continuationRemoteToolScope = RemoteToolScope.PublicEvidenceOnly,
                    steps = emptyList(),
                ),
                publicEvidenceBatchCompletedObservation(runId),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime(
            eventBatches = listOf(
                listOf(RemoteChatEvent.TextDelta("北京今天气温 26 度。")),
                listOf(RemoteChatEvent.TextDelta("北京今天气温 26 度。[S1]")),
            ),
        )
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("北京今天气温是多少？")
        advanceUntilIdle()

        assertEquals(2, remoteRuntime.calls.size)
        assertEquals("请补充公开来源引用。", remoteRuntime.calls.last().prompt)
        assertEquals("北京今天气温 26 度。[S1]", sessionStore.messages.last().text)
        assertEquals(MessagePrivacy.RemoteEligible, sessionStore.messages.last().privacy)
        assertEquals("就绪 · 远程", viewModel.uiState.value.statusText)
    }

    @Test
    fun remoteContinuationDisclosureCancelFailsRunWithoutSecondRemoteCall() = runTest(dispatcher) {
        val requests = publicWeatherBatchRequests()
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-tool-batch-cancel",
                promptForModel = "北京和上海今天温差多少？",
                memoryHits = emptyList(),
            ),
            modelToolBatchObservation = publicEvidenceBatchModelObservation("run-remote-tool-batch-cancel", requests),
            toolBatchObservation = publicEvidenceBatchToolObservation("run-remote-tool-batch-cancel"),
        )
        val remoteRuntime = RecordingRemoteChatRuntime(
            eventBatches = listOf(
                listOf(RemoteChatEvent.ToolCalls(requests)),
                listOf(RemoteChatEvent.TextDelta("不应发起第二次远程续写。")),
            ),
        )
        val executor = RecordingToolExecutor()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.setRemoteSendDisclosurePolicy(RemoteSendDisclosurePolicy.EveryMessage)

        viewModel.sendMessage("北京和上海今天温差多少？")
        advanceUntilIdle()
        viewModel.confirmRemoteSendDisclosure()
        advanceUntilIdle()

        assertEquals("远程续写待确认", viewModel.uiState.value.statusText)
        assertEquals(
            RemoteSendDisclosureKind.ToolResultContinuation,
            requireNotNull(viewModel.uiState.value.pendingRemoteSendDisclosure).kind,
        )
        assertEquals(1, remoteRuntime.calls.size)

        viewModel.dismissRemoteSendDisclosure()
        advanceUntilIdle()

        assertEquals(1, remoteRuntime.calls.size)
        assertEquals(1, assistantRouter.failModelGenerationCallCount)
        assertEquals("run-remote-tool-batch-cancel", assistantRouter.lastFailedModelRunId)
        assertTrue(assistantRouter.lastFailedModelReason.orEmpty().contains("用户取消远程工具结果续写"))
        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertFalse(viewModel.uiState.value.isGenerating)
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals("已取消远程发送", viewModel.uiState.value.statusText)
        assertTrue(sessionStore.messages.last().text.contains("工具结果未发送到远程模型"))
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
    }

    @Test
    fun remotePublicEvidenceToolCallBatchRetriesOnlyRetryableFailures() = runTest(dispatcher) {
        val requests = publicWeatherBatchRequests()
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-tool-batch-retry",
                promptForModel = "北京和上海今天温差多少？",
                memoryHits = emptyList(),
            ),
            modelToolBatchObservation = publicEvidenceBatchModelObservation("run-remote-tool-batch-retry", requests),
            toolBatchObservation = publicEvidenceBatchToolObservation("run-remote-tool-batch-retry"),
            modelObservation = publicEvidenceBatchCompletedObservation("run-remote-tool-batch-retry"),
        )
        val remoteRuntime = RecordingRemoteChatRuntime(
            eventBatches = listOf(
                listOf(RemoteChatEvent.ToolCalls(requests)),
                listOf(RemoteChatEvent.TextDelta("已综合两地天气。")),
            ),
        )
        val executor = RetryableFailureThenSuccessToolExecutor(failOnceRequestId = "call-shanghai")
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("北京和上海今天温差多少？")
        advanceUntilIdle()

        assertEquals(3, executor.executedRequests.size)
        assertEquals(1, executor.executedRequests.count { request -> request.id == "call-beijing" })
        assertEquals(2, executor.executedRequests.count { request -> request.id == "call-shanghai" })
        assertEquals(1, assistantRouter.observeToolBatchCallCount)
        assertEquals(
            setOf(ToolStatus.Succeeded),
            assistantRouter.lastObservedResults.map { result -> result.status }.toSet(),
        )
        assertEquals("已综合两地天气。", sessionStore.messages.last().text)
    }

    @Test
    fun remotePublicEvidenceToolCallBatchExecutorFailureIsObservedAsToolFailure() = runTest(dispatcher) {
        val requests = publicWeatherBatchRequests()
        val assistantMessage = "工具批量执行失败：Tool execution failed before completion: network unavailable"
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-tool-batch-failed",
                promptForModel = "北京和上海今天温差多少？",
                memoryHits = emptyList(),
            ),
            modelToolBatchObservation = publicEvidenceBatchModelObservation("run-remote-tool-batch-failed", requests),
            toolBatchObservation = publicEvidenceBatchToolObservation(
                runId = "run-remote-tool-batch-failed",
                state = AgentRunState.Failed,
                resultStatus = ToolStatus.Failed,
                summary = assistantMessage,
                decision = AgentObservationDecision.Fail(assistantMessage),
                continuationPrompt = null,
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime(
            eventBatches = listOf(listOf(RemoteChatEvent.ToolCalls(requests))),
        )
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = ThrowingToolExecutor(IllegalStateException("network unavailable")),
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("北京和上海今天温差多少？")
        advanceUntilIdle()

        assertEquals(1, assistantRouter.observeToolBatchCallCount)
        assertEquals(
            setOf(ToolStatus.Failed),
            assistantRouter.lastObservedResults.map { result -> result.status }.toSet(),
        )
        assertTrue(
            assistantRouter.lastObservedResults.all { result ->
                result.summary.contains("network unavailable")
            },
        )
        assertEquals(0, assistantRouter.failModelGenerationCallCount)
        assertTrue(sessionStore.messages.any { message -> message.text.contains("正在并行使用工具：Web 搜索 x2") })
        assertEquals(assistantMessage, sessionStore.messages.last().text)
        assertEquals(assistantMessage, viewModel.uiState.value.statusText)
        assertEquals(false, viewModel.uiState.value.isBusy)
        assertEquals(false, viewModel.uiState.value.isGenerating)
    }

    @Test
    fun rejectedRemoteToolCallShowsActionFailureAndRefreshesTrace() = runTest(dispatcher) {
        val request = ToolRequest(
            id = "call-unknown",
            toolName = "unknown_tool",
            arguments = emptyMap(),
            reason = "remote tool call",
        )
        val traceSummary = agentTraceRunSummary(
            runId = "run-remote-rejected",
            input = "执行未知工具",
            state = AgentRunState.Failed,
            stepSummary = "Unknown tool: unknown_tool",
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-rejected",
                promptForModel = "执行未知工具",
                memoryHits = emptyList(),
            ),
            modelToolObservation = AgentModelObservationResult(
                run = AgentRun("run-remote-rejected", "执行未知工具", AgentRunState.Failed, 1L, 2L),
                decision = AgentObservationDecision.Fail("Unknown tool: unknown_tool"),
                steps = emptyList(),
            ),
            recentTraceRuns = listOf(traceSummary),
        )
        val remoteRuntime = RecordingRemoteChatRuntime(
            events = listOf(RemoteChatEvent.ToolCall(request)),
        )
        val executor = RecordingToolExecutor()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("执行未知工具")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals("动作不可执行", viewModel.uiState.value.statusText)
        assertEquals(1, assistantRouter.observeModelToolCallCount)
        assertEquals(request, assistantRouter.lastObservedModelToolRequest)
        assertTrue(executor.executedRequests.isEmpty())
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
        assertTrue(sessionStore.messages.last().text.contains("Unknown tool: unknown_tool"))
        val traceRun = viewModel.uiState.value.agentTraceRuns.single()
        assertEquals(traceSummary.run.id, traceRun.id)
        assertEquals(AgentRunState.Failed, traceRun.state)
        assertEquals("Unknown tool: unknown_tool", traceRun.steps.single().summary)
    }

    @Test
    fun malformedRemoteToolCallFailsClosedBeforeConfirmationOrExecution() = runTest(dispatcher) {
        val parseError = "远程模型工具参数不是有效 JSON 对象"
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-malformed-tool",
                promptForModel = "执行远程工具",
                memoryHits = emptyList(),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime(
            events = listOf(RemoteChatEvent.ParseError(parseError)),
        )
        val executor = RecordingToolExecutor()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("执行远程工具")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertEquals("模型输出格式已拦截", viewModel.uiState.value.statusText)
        assertEquals(1, remoteRuntime.stopCallCount)
        assertEquals(1, assistantRouter.failModelGenerationCallCount)
        assertEquals("run-remote-malformed-tool", assistantRouter.lastFailedModelRunId)
        assertTrue(assistantRouter.lastFailedModelReason.orEmpty().contains("工具调用格式无效"))
        assertEquals(0, assistantRouter.observeModelToolCallCount)
        assertEquals(0, assistantRouter.observeModelResultCallCount)
        assertTrue(executor.executedRequests.isEmpty())
        assertEquals(AgentRunState.Failed, viewModel.uiState.value.agentTraceRuns.single().state)
        assertTrue(viewModel.uiState.value.agentTraceRuns.single().steps.single().summary.contains("工具调用格式无效"))
        assertTrue(remoteRuntime.calls.single().tools.any { tool -> tool.name == MobileActionFunctions.WEB_SEARCH })
        assertEquals(MessagePrivacy.RemoteEligible, sessionStore.messages.first().privacy)
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
        assertTrue(sessionStore.messages.last().text.contains("工具调用格式无效"))
        assertFalse(sessionStore.messages.last().text.contains(parseError))
        assertFalse(sessionStore.messages.last().text.contains("动作草稿"))
        assertEquals("FormatViolation", assistantRouter.lastOutputQualityTrace?.issue)
        assertEquals("tool_protocol_parse_error", assistantRouter.lastOutputQualityTrace?.triggeredRule)
        assertEquals(true, assistantRouter.lastRecordedRunDataReceipt?.outputQualityGuardTriggered)
        assertEquals("FormatViolation", viewModel.uiState.value.modelHealth.lastOutputQualityIssue)
        assertEquals("tool_protocol_parse_error", viewModel.uiState.value.modelHealth.lastOutputQualityRule)
    }

    @Test
    fun localRepetitionLoopStopsRuntimeAndSkipsModelObservation() = runTest(dispatcher) {
        val runtime = FakeLiteRtRuntime(
            localChunks = listOf(
                "正常开头。",
                "好".repeat(32),
                "不应继续出现",
            ),
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-local-repetition",
                promptForModel = "普通问题",
                memoryHits = emptyList(),
            ),
        )
        val memoryRepository = CountingMemoryRepository()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = runtime,
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
            memoryIndex = memoryRepository,
            longTermMemoryControls = memoryRepository,
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()
        val rebuildsAfterStartup = memoryRepository.rebuildCallCount

        viewModel.sendMessage("普通问题")
        advanceUntilIdle()

        val assistantText = sessionStore.messages.last().text
        assertEquals("模型输出已停止", viewModel.uiState.value.statusText)
        assertEquals(1, runtime.stopCallCount)
        assertEquals(1, assistantRouter.cancelRunCallCount)
        assertEquals(0, assistantRouter.observeModelResultCallCount)
        assertEquals(rebuildsAfterStartup + 1, memoryRepository.rebuildCallCount)
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
        assertTrue(assistantText.contains("正常开头。"))
        assertTrue(assistantText.contains("模型输出出现重复"))
        assertFalse(assistantText.contains("好".repeat(32)))
        assertFalse(assistantText.contains("不应继续出现"))
        assertEquals("RepetitionLoop", assistantRouter.lastOutputQualityTrace?.issue)
        assertEquals("same_character_run>=32", assistantRouter.lastOutputQualityTrace?.triggeredRule)
        assertEquals(true, assistantRouter.lastRecordedRunDataReceipt?.outputQualityGuardTriggered)
        assertEquals("RepetitionLoop", viewModel.uiState.value.modelHealth.lastOutputQualityIssue)
    }

    @Test
    fun remoteRepetitionLoopStopsRuntimeAndSkipsModelObservation() = runTest(dispatcher) {
        val remoteRuntime = RecordingRemoteChatRuntime(
            events = listOf(
                RemoteChatEvent.TextDelta("远程正常开头。"),
                RemoteChatEvent.TextDelta("0".repeat(32)),
                RemoteChatEvent.TextDelta("不应继续出现"),
            ),
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-repetition",
                promptForModel = "普通远程问题",
                memoryHits = emptyList(),
            ),
        )
        val memoryRepository = CountingMemoryRepository()
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            memoryIndex = memoryRepository,
            longTermMemoryControls = memoryRepository,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        val rebuildsAfterStartup = memoryRepository.rebuildCallCount

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val assistantText = sessionStore.messages.last().text
        assertEquals("模型输出已停止", viewModel.uiState.value.statusText)
        assertEquals(1, remoteRuntime.stopCallCount)
        assertEquals(1, assistantRouter.cancelRunCallCount)
        assertEquals(0, assistantRouter.observeModelResultCallCount)
        assertEquals(rebuildsAfterStartup + 1, memoryRepository.rebuildCallCount)
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
        assertTrue(assistantText.contains("远程正常开头。"))
        assertTrue(assistantText.contains("模型输出出现重复"))
        assertFalse(assistantText.contains("0".repeat(32)))
        assertFalse(assistantText.contains("不应继续出现"))
        assertEquals("RepetitionLoop", assistantRouter.lastOutputQualityTrace?.issue)
        assertEquals(true, assistantRouter.lastRecordedRunDataReceipt?.outputQualityGuardTriggered)
        assertEquals("RepetitionLoop", viewModel.uiState.value.modelHealth.lastOutputQualityIssue)
    }

    @Test
    fun remoteNetworkFailureShowsReadableFailureAndFailsTrace() = runTest(dispatcher) {
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Chat(
                runId = "run-remote-network-failure",
                promptForModel = "普通远程问题",
                memoryHits = emptyList(),
            ),
        )
        val remoteRuntime = RecordingRemoteChatRuntime(failure = IOException())
        val sessionStore = FakeSessionStore()
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
            requireRemoteSendDisclosure = true,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val readableFailure = "远程模型网络连接失败，请检查网络或远程模型配置后重试"
        assertEquals("远程生成失败", viewModel.uiState.value.statusText)
        assertEquals(1, remoteRuntime.calls.size)
        assertEquals(1, assistantRouter.failModelGenerationCallCount)
        assertEquals("run-remote-network-failure", assistantRouter.lastFailedModelRunId)
        assertEquals(readableFailure, assistantRouter.lastFailedModelReason)
        assertEquals(AgentRunState.Failed, viewModel.uiState.value.agentTraceRuns.single().state)
        assertTrue(viewModel.uiState.value.agentTraceRuns.single().steps.single().summary.contains(readableFailure))
        assertTrue(sessionStore.messages.last().text.contains(readableFailure))
        assertFalse(sessionStore.messages.last().text.contains("IOException"))
        assertEquals(ModelHealthState.LoadFailed, viewModel.uiState.value.modelHealth.state)
        assertEquals(readableFailure, viewModel.uiState.value.modelHealth.failureReason)
        assertFalse(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.isGenerating)
        assertTrue(viewModel.uiState.value.isReady)
        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)

        remoteRuntime.failure = null
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertEquals(2, remoteRuntime.calls.size)
        assertEquals("普通远程问题", remoteRuntime.calls.last().prompt)
        assertEquals("就绪 · 远程", viewModel.uiState.value.statusText)
        assertFalse(viewModel.uiState.value.isBusy)
        assertFalse(viewModel.uiState.value.isGenerating)
        assertEquals(null, viewModel.uiState.value.pendingRemoteSendDisclosure)
        assertTrue(sessionStore.messages.last().text.contains("远程回复"))
        assertEquals(1, assistantRouter.failModelGenerationCallCount)
    }

    @Test
    fun localModelCallOutputBecomesPendingConfirmationWithoutLeakingToRemoteHistory() = runTest(dispatcher) {
        val secretPayload = "SECRET_SHARE_TEXT"
        val localCall = """call:share_text{"text":"$secretPayload"}"""
        val actionRuntime = RecordingActionRuntime(
            likelyAction = false,
            modelOutputResult = MobileActionPlanner().parseModelToolOutput(localCall),
        )
        val orchestrator = AssistantOrchestrator(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            traceStore = InMemoryAgentTraceStore(),
        )
        val sessionStore = FakeSessionStore()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val executor = RecordingToolExecutor()
        val chat = installedModelSummary(
            id = "chat-model",
            displayName = "当前对话",
            path = "/tmp/chat.litertlm",
            recommendedModelId = DEFAULT_CHAT_MODEL_ID,
            verificationStatus = ModelVerificationStatus.VerifiedRecommended,
        )
        val action = installedModelSummary(
            id = "action-model",
            displayName = "低资源动作模型",
            path = "/tmp/action.litertlm",
            recommendedModelId = MOBILE_ACTION_MODEL_ID,
            verificationStatus = ModelVerificationStatus.VerifiedRecommended,
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            runtime = FakeLiteRtRuntime(localResponse = localCall),
            remoteRuntime = remoteRuntime,
            assistantRouter = orchestrator,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(
                activeInstalledModelId = chat.id,
                initialInstalledModels = listOf(chat, action),
            ),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("准备分享一段模型生成文本")
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingConfirmation
        requireNotNull(pending)
        assertEquals(MobileActionFunctions.SHARE_TEXT, pending.toolRequest?.toolName)
        assertEquals(secretPayload, pending.toolRequest?.arguments?.get("text"))
        assertEquals(true, pending.plannedByModel)
        assertEquals("local model tool call", pending.fallbackReason)
        assertTrue(executor.executedRequests.isEmpty())
        assertEquals(MessagePrivacy.LocalOnly, sessionStore.messages.last().privacy)
        assertTrue(sessionStore.messages.last().text.contains("请确认后再执行"))
        assertFalse(sessionStore.messages.last().text.contains(secretPayload))
        assertFalse(sessionStore.messages.last().text.contains("call:"))
        assertEquals("动作草稿待确认 · 本地模型", viewModel.uiState.value.statusText)

        viewModel.dismissAgentConfirmation(pending)
        advanceUntilIdle()
        viewModel.updateRemoteModelConfig(configuredRemoteModel())
        viewModel.selectInferenceMode(InferenceMode.Remote)
        advanceUntilIdle()

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val remoteCall = remoteRuntime.calls.single()
        assertFalse(remoteCall.history.toString().contains(secretPayload))
        assertFalse(remoteCall.history.toString().contains("call:share_text"))
    }

    @Test
    fun remoteModeRejectedLocalActionMessagesAreLocalOnlyAndExcludedFromLaterRemoteHistory() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val remoteStore = configuredRemoteStore()
        val rejectedRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.ToolRejected(
                summary = "Unknown tool: open_wifi_settings",
            ),
        )
        val rejectedViewModel = createViewModel(
            sessionStore = sessionStore,
            remoteStore = remoteStore,
            assistantRouter = rejectedRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
    fun toolConfirmationRoutePopulatesActiveTimeline() = runTest(dispatcher) {
        val request = ToolRequest(
            id = "request-open-settings",
            toolName = MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
            arguments = emptyMap(),
        )
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-settings",
                toolRequest = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
                    title = "打开应用设置",
                    summary = "将打开应用设置页。",
                    parameters = request.arguments,
                ),
                plannedByModel = true,
                fallbackReason = null,
            ),
            runEventsById = mapOf(
                "run-settings" to listOf(
                    AgentRunEvent.InputReceived(
                        eventId = "run-settings:input",
                        runId = "run-settings",
                        inputId = "run-settings:input",
                        sourceLabel = "typed",
                    ),
                    AgentRunEvent.ConfirmationRequested(
                        eventId = "run-settings:confirmation",
                        runId = "run-settings",
                        confirmationId = request.id,
                        toolCallId = request.id,
                        actionLabel = "打开应用设置",
                    ),
                ),
            ),
        )
        val viewModel = createViewModel(
            assistantRouter = assistantRouter,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("打开这个应用的设置")
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.pendingConfirmation)
        assertTrue(viewModel.uiState.value.activeRunTimeline.any { it.label == "等待确认" })
        assertTrue(viewModel.uiState.value.activeRunTimeline.any { it.detail.contains("打开应用设置") })
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
    fun deniedSpecialAccessFailsPendingToolWithoutExecutingIt() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val request = ToolRequest(
            id = "request-screen-text",
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            arguments = mapOf("maxChars" to "1200"),
        )
        val executor = RecordingToolExecutor()
        val assistantRouter = FakeAssistantRouter(
            routeResult = AssistantRoute.Action(
                runId = "run-screen-text",
                toolRequest = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                    title = "读取屏幕文本",
                    summary = "将读取当前屏幕可访问文本。",
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )
        viewModel.restoreStartupState()
        advanceUntilIdle()

        viewModel.sendMessage("总结当前屏幕文字")
        advanceUntilIdle()
        val confirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(confirmation)

        viewModel.rejectAgentConfirmationForSpecialAccessDenial(
            confirmation = confirmation,
            deniedRequirements = confirmation.specialAccessRequirementsFor(),
        )
        advanceUntilIdle()

        assertTrue(executor.executedRequests.isEmpty())
        assertEquals(0, assistantRouter.confirmCallCount)
        assertEquals(1, assistantRouter.failPendingCallCount)
        assertEquals(request.id, assistantRouter.lastFailedPendingResult?.requestId)
        assertEquals(ToolErrorCode.PermissionDenied, assistantRouter.lastFailedPendingResult?.error?.code)
        assertTrue(assistantRouter.lastFailedPendingResult?.summary.orEmpty().contains("无障碍屏幕文本权限"))
        assertEquals(
            SPECIAL_ACCESS_ACCESSIBILITY_SCREEN_TEXT,
            assistantRouter.lastFailedPendingResult?.data?.get("specialAccess"),
        )
        assertEquals(
            "无障碍屏幕文本权限",
            assistantRouter.lastFailedPendingResult?.data?.get("specialAccessLabels"),
        )
        assertEquals(
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            assistantRouter.lastFailedPendingResult?.data?.get("settingsAction"),
        )
        assertFalse(assistantRouter.lastFailedPendingResult?.data?.containsKey("screenText") == true)
        assertEquals(null, viewModel.uiState.value.pendingConfirmation)
        assertTrue(sessionStore.messages.last().text.contains("权限"))
        assertEquals("特殊权限未开启，工具未执行", viewModel.uiState.value.statusText)
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
                "externalOutcomeSource" to "Unknown",
            ),
        )
        val assistantMessage = "$UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX：${result.summary}"
        val confirmedMessage = "$EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX：目标应用中的操作已完成"
        val confirmedResult = result.copy(
            summary = confirmedMessage,
            data = result.data + mapOf(
                "completionVerified" to "true",
                "externalOutcome" to "Completed",
                "externalOutcomeSource" to "UserConfirmed",
            ),
        )
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
                run = AgentRun("run-share", "分享这段文字", AgentRunState.AwaitingExternalOutcome, 1L, 3L),
                result = result,
                assistantMessage = assistantMessage,
                decision = AgentObservationDecision.Complete,
                steps = emptyList(),
            ),
            externalOutcomeResult = AgentExternalOutcomeResult(
                run = AgentRun("run-share", "分享这段文字", AgentRunState.Completed, 1L, 4L),
                result = confirmedResult,
                assistantMessage = confirmedMessage,
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
        val pendingOutcome = viewModel.uiState.value.pendingExternalOutcome
        requireNotNull(pendingOutcome)
        assertEquals("run-share", pendingOutcome.runId)
        assertEquals(request.id, pendingOutcome.requestId)
        assertEquals(MobileActionFunctions.SHARE_TEXT, pendingOutcome.toolName)
        assertTrue(sessionStore.messages.last().text.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX))
        assertTrue(viewModel.uiState.value.statusText.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX))

        viewModel.recordExternalOutcome(pendingOutcome, AgentExternalOutcome.Completed)
        advanceUntilIdle()

        assertEquals(1, assistantRouter.recordExternalOutcomeCallCount)
        assertEquals(AgentExternalOutcome.Completed, assistantRouter.lastExternalOutcome)
        assertEquals(null, viewModel.uiState.value.pendingExternalOutcome)
        assertTrue(sessionStore.messages.last().text.startsWith(EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX))
        assertTrue(viewModel.uiState.value.statusText.startsWith(EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX))
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
                modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
            assertEquals("session-1", assistantRouter.lastRecoverySessionId)
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
    fun restoreStartupStateRestoresPendingExternalOutcomeWithoutExecutingTool() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val executor = RecordingToolExecutor()
        val pending = PendingExternalOutcomeSnapshot(
            runId = "run-external",
            requestId = "request-external",
            toolName = MobileActionFunctions.SHARE_TEXT,
            title = "系统分享",
            summary = "$UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX：已打开系统分享面板",
        )
        val assistantRouter = FakeAssistantRouter(
            restoredPendingExternalOutcome = pending,
            externalOutcomeResult = AgentExternalOutcomeResult(
                run = AgentRun("run-external", "分享", AgentRunState.Completed, 1L, 2L),
                result = ToolResult(
                    requestId = pending.requestId,
                    status = ToolStatus.Succeeded,
                    summary = "$EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX：只确认外部界面已打开",
                    data = mapOf(
                        "completionState" to "ExternalActivityOpened",
                        "completionVerified" to "false",
                        "externalOutcome" to "OpenedOnly",
                        "externalOutcomeSource" to "UserConfirmed",
                    ),
                ),
                assistantMessage = "$EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX：只确认外部界面已打开",
                decision = AgentObservationDecision.Complete,
                steps = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        val restored = viewModel.uiState.value.pendingExternalOutcome
        requireNotNull(restored)
        assertEquals("run-external", restored.runId)
        assertEquals("request-external", restored.requestId)
        assertEquals(MobileActionFunctions.SHARE_TEXT, restored.toolName)
        assertEquals("session-1", assistantRouter.lastRestorePendingExternalOutcomeSessionId)
        assertTrue(executor.executedRequests.isEmpty())

        viewModel.sendMessage("不应越过外部结果确认")
        advanceUntilIdle()

        assertEquals("请先确认外部动作结果", viewModel.uiState.value.statusText)
        assertEquals(0, assistantRouter.routeCallCount)
        assertTrue(executor.executedRequests.isEmpty())

        viewModel.recordExternalOutcome(restored, AgentExternalOutcome.OpenedOnly)
        advanceUntilIdle()

        assertEquals(1, assistantRouter.recordExternalOutcomeCallCount)
        assertEquals(AgentExternalOutcome.OpenedOnly, assistantRouter.lastExternalOutcome)
        assertEquals(null, viewModel.uiState.value.pendingExternalOutcome)
        assertTrue(sessionStore.messages.last().text.startsWith(EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX))
    }

    @Test
    fun restoredPendingExternalOutcomeCompletedCanShowNextPendingConfirmation() = runTest(dispatcher) {
        val sessionStore = FakeSessionStore()
        val executor = RecordingToolExecutor()
        val pending = PendingExternalOutcomeSnapshot(
            runId = "run-external-next",
            requestId = "request-external-next",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            title = "打开 Wi-Fi 设置",
            summary = "$UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX：已打开 Wi-Fi 设置页",
        )
        val nextRequest = ToolRequest(
            id = "request-flashlight",
            toolName = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            reason = "Open flashlight settings after external completion",
        )
        val nextDraft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
            title = "打开手电筒设置",
            summary = "将打开手电筒设置页。",
            parameters = emptyMap(),
        )
        val assistantRouter = FakeAssistantRouter(
            restoredPendingExternalOutcome = pending,
            externalOutcomeResult = AgentExternalOutcomeResult(
                run = AgentRun("run-external-next", "打开 Wi-Fi 设置，然后打开手电筒设置", AgentRunState.AwaitingUserConfirmation, 1L, 2L),
                result = ToolResult(
                    requestId = pending.requestId,
                    status = ToolStatus.Succeeded,
                    summary = "$EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX：目标应用中的操作已完成",
                    data = mapOf(
                        "completionState" to "ExternalActivityOpened",
                        "completionVerified" to "true",
                        "externalOutcome" to "Completed",
                        "externalOutcomeSource" to "UserConfirmed",
                    ),
                ),
                assistantMessage = "$EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX：目标应用中的操作已完成",
                decision = AgentObservationDecision.PlanNextTool(
                    plan = AgentPlan.UseTool(
                        request = nextRequest,
                        draft = nextDraft,
                        plannedByModel = false,
                        fallbackReason = "restored continuation cursor",
                        safetyDecision = SafetyDecision(
                            outcome = SafetyOutcome.RequireConfirmation,
                            reason = "Next tool requires confirmation.",
                        ),
                    ),
                    reason = "external outcome completed",
                ),
                steps = emptyList(),
            ),
        )
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            assistantRouter = assistantRouter,
            actionExecutor = executor,
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        val restored = viewModel.uiState.value.pendingExternalOutcome
        requireNotNull(restored)

        viewModel.recordExternalOutcome(restored, AgentExternalOutcome.Completed)
        advanceUntilIdle()

        assertEquals(1, assistantRouter.recordExternalOutcomeCallCount)
        assertEquals(AgentExternalOutcome.Completed, assistantRouter.lastExternalOutcome)
        assertEquals(null, viewModel.uiState.value.pendingExternalOutcome)
        val nextConfirmation = viewModel.uiState.value.pendingConfirmation
        requireNotNull(nextConfirmation)
        assertEquals("run-external-next", nextConfirmation.runId)
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, nextConfirmation.toolRequest?.toolName)
        assertEquals("下一步动作待确认", viewModel.uiState.value.statusText)
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
            modelRepository = FakeModelRepository(activeModelPath = TEST_LOCAL_MODEL_PATH),
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
    fun restoreStartupStateDefersVerifiedMemoryModelUntilChatModelLoads() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(
            recordStore = store,
            semanticRuntimeFactory = { path ->
                check(path == TEST_MEMORY_EMBEDDING_MODEL_PATH)
                ConciseSemanticRuntime()
            },
        )
        memoryRepository.indexPreference("pref-1", "I prefer concise answers")
        val active = installedModelSummary(
            id = "active-chat",
            displayName = "当前对话",
            path = TEST_LOCAL_MODEL_PATH,
        )
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            modelRepository = FakeModelRepository(
                activeInstalledModelId = active.id,
                initialInstalledModels = listOf(active),
                memoryEmbeddingModelPath = TEST_MEMORY_EMBEDDING_MODEL_PATH,
            ),
        )

        assertFalse(viewModel.uiState.value.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.NoVerifiedModel, viewModel.uiState.value.semanticMemoryRuntimeStatus)

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertFalse(memoryRepository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.NoVerifiedModel, memoryRepository.semanticMemoryRuntimeStatus)

        viewModel.loadModel()
        advanceUntilIdle()

        assertTrue(memoryRepository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.Active, memoryRepository.semanticMemoryRuntimeStatus)
        assertEquals(SemanticMemoryRuntimeStatus.Active, viewModel.uiState.value.semanticMemoryRuntimeStatus)
        assertEquals(TEST_MEMORY_EMBEDDING_MODEL_PATH, memoryRepository.activeMemoryModelPath)
        val hits = memoryRepository.search("compressed responses")
        assertEquals(listOf("pref-1"), hits.map { it.id })
        assertEquals(MemoryRecallMode.Semantic, hits.first().recallMode)
    }

    @Test
    fun startupRuntimeWorkSuppressionKeepsSemanticMemoryRuntimeUnloaded() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        var semanticRuntimeFactoryCalls = 0
        val memoryRepository = MemoryRepository(
            recordStore = store,
            semanticRuntimeFactory = {
                semanticRuntimeFactoryCalls += 1
                error("Semantic runtime must stay unloaded while startup runtime work is suppressed.")
            },
        )
        memoryRepository.indexPreference("pref-1", "I prefer concise answers")
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            modelRepository = FakeModelRepository(memoryEmbeddingModelPath = TEST_MEMORY_EMBEDDING_MODEL_PATH),
            skipStartupModelRuntimeWork = true,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertEquals(0, semanticRuntimeFactoryCalls)
        assertFalse(viewModel.uiState.value.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.NoVerifiedModel, viewModel.uiState.value.semanticMemoryRuntimeStatus)
        assertEquals(null, memoryRepository.activeMemoryModelPath)
        val lexicalHits = memoryRepository.search("concise answers")
        assertEquals(listOf("pref-1"), lexicalHits.map { it.id })
        assertEquals(MemoryRecallMode.Lexical, lexicalHits.single().recallMode)
    }

    @Test
    fun chatModelLoadReportsUnavailableSemanticRuntimeWhenFactoryIsMissing() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        memoryRepository.indexPreference("pref-1", "I prefer concise answers")
        val active = installedModelSummary(
            id = "active-chat",
            displayName = "当前对话",
            path = TEST_LOCAL_MODEL_PATH,
        )
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            modelRepository = FakeModelRepository(
                activeInstalledModelId = active.id,
                initialInstalledModels = listOf(active),
                memoryEmbeddingModelPath = TEST_MEMORY_EMBEDDING_MODEL_PATH,
            ),
        )

        assertFalse(viewModel.uiState.value.semanticMemoryEnabled)
        assertEquals(
            SemanticMemoryRuntimeStatus.NoVerifiedModel,
            viewModel.uiState.value.semanticMemoryRuntimeStatus,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertEquals(SemanticMemoryRuntimeStatus.NoVerifiedModel, memoryRepository.semanticMemoryRuntimeStatus)

        viewModel.loadModel()
        advanceUntilIdle()

        assertFalse(memoryRepository.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.RuntimeUnavailable, memoryRepository.semanticMemoryRuntimeStatus)
        assertEquals(null, memoryRepository.activeMemoryModelPath)
        assertFalse(viewModel.uiState.value.semanticMemoryEnabled)
        assertEquals(
            SemanticMemoryRuntimeStatus.RuntimeUnavailable,
            viewModel.uiState.value.semanticMemoryRuntimeStatus,
        )
        assertTrue(memoryRepository.search("laconic replies").isEmpty())
        val lexicalHits = memoryRepository.search("concise answers")
        assertEquals(listOf("pref-1"), lexicalHits.map { it.id })
        assertEquals(MemoryRecallMode.Lexical, lexicalHits.single().recallMode)
    }

    @Test
    fun localModeSemanticMemoryStatusAndPromptUseSemanticHit() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(
            recordStore = store,
            semanticRuntimeFactory = { path ->
                check(path == TEST_MEMORY_EMBEDDING_MODEL_PATH)
                ConciseSemanticRuntime()
            },
        )
        memoryRepository.indexPreference("pref-1", "I prefer concise answers")
        val localRuntime = FakeLiteRtRuntime(localResponse = "本地回复：简洁回答")
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            memoryRepository = memoryRepository,
            assistantRouter = AssistantOrchestrator(
                memoryRepository,
                RecordingActionRuntime(likelyAction = false),
            ),
            modelRepository = FakeModelRepository(
                activeModelPath = TEST_LOCAL_MODEL_PATH,
                memoryEmbeddingModelPath = TEST_MEMORY_EMBEDDING_MODEL_PATH,
            ),
        )

        viewModel.restoreStartupState()
        advanceUntilIdle()
        viewModel.sendMessage("compressed responses")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.Active, viewModel.uiState.value.semanticMemoryRuntimeStatus)
        val prompt = localRuntime.prompts.single()
        assertTrue(prompt.contains("本地记忆"))
        assertTrue(prompt.contains("I prefer concise answers"))
        assertEquals(listOf("pref-1"), viewModel.uiState.value.memoryHits.map { it.id })
        assertEquals(MemoryRecallMode.Semantic, viewModel.uiState.value.memoryHits.single().recallMode)
        assertTrue(viewModel.uiState.value.activeRunTimeline.any { it.label == "加载上下文" })
        assertTrue(viewModel.uiState.value.activeRunTimeline.any { it.label == "生成回答" })
        assertEquals(listOf("语义召回"), viewModel.uiState.value.activeMemoryEvidence.map { it.recallLabel })
        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            viewModel.uiState.value.messages.map { it.privacy },
        )
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun localSemanticMemoryResponseDoesNotEnterLaterRemoteHistory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(
            recordStore = store,
            semanticRuntimeFactory = { path ->
                check(path == TEST_MEMORY_EMBEDDING_MODEL_PATH)
                ConciseSemanticRuntime()
            },
        )
        memoryRepository.indexPreference("pref-1", "I prefer concise answers")
        memoryRepository.indexUserFact(
            explicitUserFactRecordId("my rcode is xb83"),
            "my rcode is xb83",
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            runtime = FakeLiteRtRuntime(localResponse = "本地回复：I prefer concise answers"),
            remoteRuntime = remoteRuntime,
            remoteStore = FakeRemoteModelStore(mode = InferenceMode.Local),
            memoryRepository = memoryRepository,
            assistantRouter = AssistantOrchestrator(
                memoryRepository,
                RecordingActionRuntime(likelyAction = false),
            ),
            modelRepository = FakeModelRepository(
                activeModelPath = TEST_LOCAL_MODEL_PATH,
                memoryEmbeddingModelPath = TEST_MEMORY_EMBEDDING_MODEL_PATH,
            ),
        )

        viewModel.restoreStartupState()
        advanceUntilIdle()
        viewModel.sendMessage("compressed responses")
        advanceUntilIdle()

        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            viewModel.uiState.value.messages.map { it.privacy },
        )

        viewModel.updateRemoteModelConfig(configuredRemoteModel())
        viewModel.selectInferenceMode(InferenceMode.Remote)
        viewModel.sendMessage("ordinary remote question")
        advanceUntilIdle()

        val call = remoteRuntime.calls.single()
        assertEquals("ordinary remote question", call.prompt)
        assertTrue(call.history.isEmpty())
        assertFalse(call.history.toString().contains("compressed responses"))
        assertFalse(call.history.toString().contains("I prefer concise answers"))
        assertFalse(call.history.toString().contains("本地回复"))
    }

    @Test
    fun remoteModeKeepsSemanticMemoryRuntimeButDoesNotSendMemoryContext() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(
            recordStore = store,
            semanticRuntimeFactory = { path ->
                check(path == TEST_MEMORY_EMBEDDING_MODEL_PATH)
                ConciseSemanticRuntime()
            },
        )
        memoryRepository.indexPreference("pref-1", "I prefer concise answers")
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            memoryRepository = memoryRepository,
            assistantRouter = AssistantOrchestrator(
                memoryRepository,
                RecordingActionRuntime(likelyAction = false),
            ),
            modelRepository = FakeModelRepository(memoryEmbeddingModelPath = TEST_MEMORY_EMBEDDING_MODEL_PATH),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("compressed responses")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.semanticMemoryEnabled)
        assertEquals(SemanticMemoryRuntimeStatus.Active, viewModel.uiState.value.semanticMemoryRuntimeStatus)
        assertTrue(viewModel.uiState.value.memoryHits.isEmpty())
        assertTrue(viewModel.uiState.value.activeMemoryEvidence.isEmpty())
        val call = remoteRuntime.calls.single()
        assertEquals("compressed responses", call.prompt)
        assertFalse(call.prompt.contains("本地记忆"))
        assertFalse(call.prompt.contains("I prefer concise answers"))
        assertFalse(call.prompt.contains("用户偏好"))
        assertFalse(call.prompt.contains("xb83"))
        assertFalse(call.prompt.contains("用户事实"))
        assertFalse(call.history.toString().contains("I prefer concise answers"))
        assertFalse(call.history.toString().contains("用户偏好"))
        assertFalse(call.history.toString().contains("xb83"))
        assertFalse(call.history.toString().contains("用户事实"))
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
    fun memoryDisabledDoesNotIndexScheduledTaskStateOnStartupRefreshOrSend() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val taskMemoryId = taskStateMemoryRecordId("task-1")
        memoryRepository.indexTaskState(taskMemoryId, "旧的后台任务状态")
        memoryRepository.indexPreference("pref-1", "回答尽量简洁")
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
            firstRunStore = FakeFirstRunSetupStore(memoryEnabled = false),
            memoryRepository = memoryRepository,
            backgroundTaskScheduler = scheduler,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.memoryEnabled)
        assertTrue(store.records().none { it.type == MemoryRecordType.TaskState })
        assertEquals(listOf("pref-1"), viewModel.uiState.value.longTermMemories.map { it.id })

        viewModel.refreshBackgroundTasks()
        advanceUntilIdle()
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)
        assertTrue(store.records().none { it.type == MemoryRecordType.TaskState })
        assertEquals(listOf("pref-1"), viewModel.uiState.value.longTermMemories.map { it.id })
        assertTrue(memoryRepository.search("后台任务").isEmpty())
        assertTrue(memoryRepository.search("简洁回答").isEmpty())
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
    fun restoreStartupStateReconcilesPeriodicCheckBeforeLoadingBackgroundTasks() = runTest(dispatcher) {
        val scheduler = FakeBackgroundTaskScheduler(
            scheduledTasks = listOf(
                scheduledTask(
                    id = "task-1",
                    type = ScheduledTaskType.Reminder,
                    status = ScheduledTaskStatus.Scheduled,
                    title = "喝水",
                ),
                scheduledTask(
                    id = PeriodicCheckScheduleRequest.TASK_ID,
                    type = ScheduledTaskType.PeriodicCheck,
                    status = ScheduledTaskStatus.Scheduled,
                    title = PeriodicCheckScheduleRequest.TITLE,
                    body = PeriodicCheckScheduleRequest().storageSummary(),
                ),
            ),
            onReconcilePeriodicCheckOnStartup = { tasks ->
                val existing = tasks.getValue(PeriodicCheckScheduleRequest.TASK_ID)
                tasks[existing.id] = existing.copy(
                    status = ScheduledTaskStatus.Failed,
                    updatedAtMillis = existing.updatedAtMillis + 1L,
                )
            },
        )
        val viewModel = createViewModel(backgroundTaskScheduler = scheduler)

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertEquals(1, scheduler.reconcilePeriodicCheckCount)
        assertEquals(listOf("task-1"), viewModel.uiState.value.backgroundTasks.map { it.id })
        assertEquals(
            listOf(PeriodicCheckScheduleRequest.TASK_ID),
            viewModel.uiState.value.backgroundTaskHistory.map { it.id },
        )
        assertEquals(ScheduledTaskStatus.Failed, viewModel.uiState.value.periodicCheckPolicy.taskStatus)
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
            remoteStore = configuredRemoteStore(),
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
    fun updateMemoryDisabledRemovesActiveTaskStateMemoryAndPreventsResync() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val taskMemoryId = taskStateMemoryRecordId("task-1")
        memoryRepository.indexPreference("pref-1", "回答尽量简洁")
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
            remoteStore = configuredRemoteStore(),
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        assertTrue(store.hasRecord(taskMemoryId, MemoryRecordType.TaskState))

        viewModel.updateMemoryEnabled(false)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.memoryEnabled)
        assertTrue(store.records().none { it.id == taskMemoryId && it.type == MemoryRecordType.TaskState })
        assertEquals(listOf("pref-1"), viewModel.uiState.value.longTermMemories.map { it.id })

        viewModel.refreshBackgroundTasks()
        advanceUntilIdle()
        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        assertEquals("普通远程问题", remoteRuntime.calls.single().prompt)
        assertTrue(store.records().none { it.id == taskMemoryId && it.type == MemoryRecordType.TaskState })
        assertEquals(listOf("pref-1"), viewModel.uiState.value.longTermMemories.map { it.id })
        assertTrue(memoryRepository.search("后台任务").isEmpty())
        assertTrue(memoryRepository.search("简洁回答").isEmpty())
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
        val deletionStore = FakeMemoryDeletionEventStore()
        val memoryRepository = MemoryRepository(
            recordStore = store,
            deletionEventStore = deletionStore,
        )
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
        assertTrue(deletionStore.events().isEmpty())
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
            remoteStore = configuredRemoteStore(),
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
            remoteStore = configuredRemoteStore(),
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
            remoteStore = configuredRemoteStore(),
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
        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            viewModel.uiState.value.messages.map { it.privacy },
        )
        assertEquals("记住：我喜欢简洁回答", viewModel.uiState.value.messages.first().text)
        assertTrue(viewModel.uiState.value.messages.last().text.contains("已记住这条本地偏好"))
        assertEquals("长期记忆已更新", viewModel.uiState.value.statusText)
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun rememberCommandReplacesConflictingPreferenceMemory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
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
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun rememberCommandPersistsEnglishPreferenceMemory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
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
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun rememberCommandPersistsUserFactMemory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("remember that my rcode is xb83")
        advanceUntilIdle()

        val record = store.records().single()
        assertTrue(record.id.startsWith("user-fact-"))
        assertEquals(MemoryRecordType.UserFact, record.type)
        assertEquals("用户事实：my rcode is xb83", record.text)
        assertTrue(memoryRepository.search("rcode xb83").any { it.id == record.id })
        assertEquals(listOf(record.id), viewModel.uiState.value.longTermMemories.map { it.id })
        assertTrue(viewModel.uiState.value.messages.last().text.contains("已记住这条本地事实"))
        assertEquals("长期记忆已更新", viewModel.uiState.value.statusText)
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun rememberCommandDoesNotPersistWhenMemoryDisabled() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            firstRunStore = FakeFirstRunSetupStore(memoryEnabled = false),
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("记住：我喜欢简洁回答")
        advanceUntilIdle()
        viewModel.sendMessage("remember that my rcode is xb83")
        advanceUntilIdle()

        assertTrue(store.records().isEmpty())
        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
        assertTrue(memoryRepository.search("简洁回答").isEmpty())
        assertEquals("本地记忆已关闭", viewModel.uiState.value.statusText)
        assertTrue(viewModel.uiState.value.messages.last().text.contains("本地记忆已关闭"))
        assertEquals(
            List(4) { MessagePrivacy.LocalOnly },
            viewModel.uiState.value.messages.map { it.privacy },
        )
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun forgetCommandStillDeletesMemoryWhenMemoryDisabled() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        memoryRepository.indexPreference(explicitUserPreferenceRecordId("我喜欢简洁回答"), "我喜欢简洁回答")
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            firstRunStore = FakeFirstRunSetupStore(memoryEnabled = false),
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("忘记：我喜欢简洁回答")
        advanceUntilIdle()

        assertTrue(store.records().isEmpty())
        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
        assertEquals("长期记忆已更新", viewModel.uiState.value.statusText)
        assertTrue(viewModel.uiState.value.messages.last().text.contains("已遗忘这条本地记忆"))
        assertTrue(remoteRuntime.calls.isEmpty())
    }

    @Test
    fun forgetRememberCommandMemoryDoesNotReindexFromHistory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteStore = configuredRemoteStore(),
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
    fun rememberCommandBypassesRouterAndRemoteRuntime() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val sessionStore = FakeSessionStore()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val assistantRouter = FakeAssistantRouter(routeFailure = IllegalStateException("planner unavailable"))
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("记住：我喜欢简洁回答")
        advanceUntilIdle()

        val record = store.records().single()
        assertEquals("用户偏好：我喜欢简洁回答", record.text)
        assertEquals(0, assistantRouter.routeCallCount)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            sessionStore.messages.map { it.privacy },
        )
        assertTrue(sessionStore.messages.last().text.contains("已记住这条本地偏好"))
    }

    @Test
    fun remoteRememberCommandDoesNotEnterLaterRemoteHistory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("记住：我喜欢简洁回答")
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())

        viewModel.sendMessage("普通远程问题")
        advanceUntilIdle()

        val call = remoteRuntime.calls.single()
        assertEquals("普通远程问题", call.prompt)
        assertTrue(call.history.isEmpty())
        assertFalse(call.history.toString().contains("记住"))
        assertFalse(call.history.toString().contains("简洁回答"))
        assertFalse(call.history.toString().contains("已记住这条本地偏好"))
        assertEquals("用户偏好：我喜欢简洁回答", store.records().single().text)
    }

    @Test
    fun forgetPreferenceCommandDeletesMemoryAndBypassesRouterAndRemoteRuntime() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val sessionStore = FakeSessionStore()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val assistantRouter = FakeAssistantRouter(routeFailure = IllegalStateException("planner unavailable"))
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("记住：我喜欢简洁回答")
        advanceUntilIdle()
        assertEquals("用户偏好：我喜欢简洁回答", store.records().single().text)

        viewModel.sendMessage("忘记：我喜欢简洁回答")
        advanceUntilIdle()

        assertTrue(store.records().isEmpty())
        assertTrue(memoryRepository.search("简洁回答").isEmpty())
        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
        assertEquals("长期记忆已更新", viewModel.uiState.value.statusText)
        assertEquals(0, assistantRouter.routeCallCount)
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
        assertTrue(sessionStore.messages.last().text.contains("已遗忘这条本地记忆"))
    }

    @Test
    fun forgetUserFactCommandDeletesMemoryAndBypassesRouterAndRemoteRuntime() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val sessionStore = FakeSessionStore()
        val remoteRuntime = RecordingRemoteChatRuntime()
        val assistantRouter = FakeAssistantRouter(routeFailure = IllegalStateException("planner unavailable"))
        val viewModel = createViewModel(
            sessionStore = sessionStore,
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("remember that my rcode is xb83")
        advanceUntilIdle()
        assertEquals("用户事实：my rcode is xb83", store.records().single().text)

        viewModel.sendMessage("forget that my rcode is xb83")
        advanceUntilIdle()

        assertTrue(store.records().isEmpty())
        assertTrue(memoryRepository.search("rcode xb83").isEmpty())
        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
        assertEquals("长期记忆已更新", viewModel.uiState.value.statusText)
        assertEquals(0, assistantRouter.routeCallCount)
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
        assertTrue(sessionStore.messages.last().text.contains("已遗忘这条本地记忆"))
    }

    @Test
    fun forgetPreferenceFamilyCommandDeletesMatchingPreferenceAndBypassesRemoteRuntime() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val assistantRouter = FakeAssistantRouter(routeFailure = IllegalStateException("planner unavailable"))
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
            assistantRouter = assistantRouter,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("记住：回答尽量简洁")
        advanceUntilIdle()
        viewModel.sendMessage("记住：请用中文回答")
        advanceUntilIdle()
        assertEquals(2, store.records().size)

        viewModel.sendMessage("忘记：回答语言偏好")
        advanceUntilIdle()

        assertEquals(listOf("用户偏好：回答尽量简洁"), store.records().map { it.text })
        assertEquals(listOf("用户偏好：回答尽量简洁"), viewModel.uiState.value.longTermMemories.map { it.text })
        assertTrue(memoryRepository.search("Mandarin replies").isEmpty())
        assertEquals(listOf("用户偏好：回答尽量简洁"), memoryRepository.search("简洁回答").map { it.text })
        assertEquals(0, assistantRouter.routeCallCount)
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals("长期记忆已更新", viewModel.uiState.value.statusText)
    }

    @Test
    fun remoteForgetPreferenceCommandDoesNotEnterLaterRemoteHistory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("remember that I prefer concise answers")
        advanceUntilIdle()
        viewModel.sendMessage("forget that I prefer concise answers")
        advanceUntilIdle()

        assertTrue(store.records().isEmpty())
        assertTrue(remoteRuntime.calls.isEmpty())

        viewModel.sendMessage("ordinary remote question")
        advanceUntilIdle()

        val call = remoteRuntime.calls.single()
        assertEquals("ordinary remote question", call.prompt)
        assertTrue(call.history.isEmpty())
        assertFalse(call.history.toString().contains("remember"))
        assertFalse(call.history.toString().contains("forget"))
        assertFalse(call.history.toString().contains("concise answers"))
        assertFalse(call.history.toString().contains("已遗忘这条本地记忆"))
    }

    @Test
    fun rememberCommandMemoryStoreFailureDoesNotFallbackToRemote() = runTest(dispatcher) {
        val memoryRepository = MemoryRepository(
            recordStore = FakeMemoryRecordStore(failure = IllegalStateException("memory unavailable")),
        )
        val remoteRuntime = RecordingRemoteChatRuntime()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            remoteRuntime = remoteRuntime,
            remoteStore = configuredRemoteStore(),
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        viewModel.sendMessage("记住：我喜欢简洁回答")
        advanceUntilIdle()

        assertTrue(remoteRuntime.calls.isEmpty())
        assertTrue(viewModel.uiState.value.messages.last().text.contains("本地记忆暂不可用"))
        assertEquals("本地记忆暂不可用", viewModel.uiState.value.statusText)
        assertEquals(
            listOf(MessagePrivacy.LocalOnly, MessagePrivacy.LocalOnly),
            viewModel.uiState.value.messages.map { it.privacy },
        )
    }

    @Test
    fun rememberCommandWorksBeforeModelIsReady() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        val runtime = FakeLiteRtRuntime()
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            runtime = runtime,
        )

        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isReady)

        viewModel.sendMessage("记住：我喜欢简洁回答")
        advanceUntilIdle()

        assertEquals("用户偏好：我喜欢简洁回答", store.records().single().text)
        assertTrue(runtime.prompts.isEmpty())
        assertEquals("长期记忆已更新", viewModel.uiState.value.statusText)
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
    fun memoryDisabledKeepsSavedRecordsVisibleAndClearable() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
        memoryRepository.indexPreference("pref-1", "回答尽量简洁")
        memoryRepository.indexUserFact("fact-1", "我的项目是 Solin")
        val viewModel = createViewModel(
            firstRunStore = FakeFirstRunSetupStore(memoryEnabled = false),
            memoryRepository = memoryRepository,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.memoryEnabled)
        assertEquals(
            listOf("pref-1", "fact-1"),
            viewModel.uiState.value.longTermMemories.map { it.id },
        )
        assertTrue(memoryRepository.search("Solin").isEmpty())

        viewModel.forgetLongTermMemory("pref-1")
        advanceUntilIdle()

        assertEquals(listOf("fact-1"), viewModel.uiState.value.longTermMemories.map { it.id })
        assertTrue(store.records().none { it.id == "pref-1" })

        viewModel.clearLongTermMemory()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())
        assertTrue(store.records().isEmpty())
        assertEquals("长期记忆已清空", viewModel.uiState.value.statusText)
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
            remoteStore = configuredRemoteStore(),
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
    fun reenabledPeriodicCheckUnsuppressesNewTaskStateMemory() = runTest(dispatcher) {
        val store = FakeMemoryRecordStore()
        val memoryRepository = MemoryRepository(recordStore = store)
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
        val viewModel = createViewModel(
            memoryRepository = memoryRepository,
            backgroundTaskScheduler = scheduler,
        )
        viewModel.restoreStartupState(skipModelRuntimeWork = true)
        advanceUntilIdle()
        val taskMemoryId = taskStateMemoryRecordId(PeriodicCheckScheduleRequest.TASK_ID)
        assertTrue(store.hasRecord(taskMemoryId, MemoryRecordType.TaskState))

        viewModel.clearLongTermMemory()
        advanceUntilIdle()
        assertTrue(store.hasSuppressedTaskState(taskMemoryId))
        assertTrue(store.records().none { it.id == taskMemoryId && it.type == MemoryRecordType.TaskState })

        viewModel.disablePeriodicCheckPolicy()
        advanceUntilIdle()
        assertTrue(store.hasSuppressedTaskState(taskMemoryId))
        assertTrue(viewModel.uiState.value.longTermMemories.isEmpty())

        viewModel.setPeriodicCheckPolicy(PeriodicCheckScheduleRequest())
        advanceUntilIdle()

        assertFalse(store.hasSuppressedTaskState(taskMemoryId))
        assertTrue(store.hasRecord(taskMemoryId, MemoryRecordType.TaskState))
        assertEquals(listOf(taskMemoryId), viewModel.uiState.value.longTermMemories.map { it.id })
        assertTrue(memoryRepository.search("后台任务 PeriodicCheck").any { hit -> hit.id == taskMemoryId })
    }

    @Test
    fun restoreStartupStateLoadsScheduledBackgroundTasksAndIndexesRunningTaskStateWithoutRemoteWork() = runTest(
        dispatcher,
    ) {
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

        assertEquals(listOf("task-1"), viewModel.uiState.value.backgroundTasks.map { it.id })
        assertEquals(listOf("task-3", "task-2"), viewModel.uiState.value.backgroundTaskHistory.map { it.id })
        assertEquals(
            listOf(taskStateMemoryRecordId("task-1"), taskStateMemoryRecordId("task-running")),
            store.records()
                .filter { record -> record.type == MemoryRecordType.TaskState }
                .map { record -> record.id },
        )

        viewModel.refreshBackgroundTasks()
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
    fun cancelScheduledBackgroundTaskRefreshesUiAndCancelsScheduler() = runTest(dispatcher) {
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
    fun cancelScheduledBackgroundTaskFailureKeepsTaskVisible() = runTest(dispatcher) {
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
    fun cancelScheduledBackgroundTaskFailureHidesConcurrentlyRunningTask() = runTest(dispatcher) {
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
        assertTrue(viewModel.uiState.value.backgroundTasks.isEmpty())
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
    fun disablePeriodicCheckPolicyFailureKeepsScheduledTaskVisible() = runTest(dispatcher) {
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
        memoryIndex: MemoryIndex = memoryRepository,
        longTermMemoryControls: LongTermMemoryControls = memoryRepository,
        backgroundTaskScheduler: BackgroundTaskScheduler = FakeBackgroundTaskScheduler(),
        toolAuditLog: ToolAuditLog = FakeToolAuditLog(),
        assistantRouter: AssistantRouter = FakeAssistantRouter(),
        ioDispatcher: CoroutineDispatcher = dispatcher,
        requireRemoteSendDisclosure: Boolean = false,
        adaptiveInferenceRollout: AdaptiveInferenceRollout = AdaptiveInferenceRollout.Off,
        chatPlacementRuntime: ChatPlacementRuntime = FakeChatPlacementRuntime(),
        remoteConnectivityProbe: RemoteModelConnectivityProbe = FakeRemoteModelConnectivityProbe(),
        huggingFaceAuthStore: HuggingFaceAuthStore = FakeHuggingFaceAuthStore(),
        remoteSendAuditStore: FakeRemoteSendAuditStore = FakeRemoteSendAuditStore(),
        remoteSendPendingStore: RemoteSendPendingStore = FakeRemoteSendPendingStore(),
        bundledModelInstaller: BundledModelInstaller = FakeBundledModelInstaller(),
        skipStartupModelRuntimeWork: Boolean = false,
        actionExecutor: ToolExecutor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult =
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "executed",
                )
        },
    ): SolinViewModel =
        SolinViewModel(
            modelRepository = modelRepository,
            sessionRepository = sessionStore,
            generationParametersRepository = generationStore,
            remoteModelRepository = remoteStore,
            huggingFaceAuthStore = huggingFaceAuthStore,
            firstRunSetupRepository = firstRunStore,
            downloadService = downloadClient,
            runtime = runtime,
            remoteRuntime = remoteRuntime,
            memoryRepository = memoryIndex,
            longTermMemoryControls = longTermMemoryControls,
            backgroundTaskScheduler = backgroundTaskScheduler,
            toolAuditLog = toolAuditLog,
            actionExecutor = actionExecutor,
            assistantOrchestrator = assistantRouter,
            isArm64DeviceProvider = { true },
            ioDispatcher = ioDispatcher,
            requireRemoteSendDisclosure = requireRemoteSendDisclosure,
            adaptiveInferenceRollout = adaptiveInferenceRollout,
            chatPlacementRuntime = chatPlacementRuntime,
            stableResourceStateProvider = {
                StableResourceState(
                    band = StableResourceBand.Normal,
                    stableLowMemory = false,
                    latestLowMemory = false,
                    localHardBlocked = false,
                    thermalPressure = ThermalPressure.Normal,
                )
            },
            bootCountProvider = { 1L },
            elapsedRealtimeMillis = { 10L },
            remoteConnectivityProbe = remoteConnectivityProbe,
            remoteSendAuditSink = remoteSendAuditStore,
            remoteSendAuditLog = remoteSendAuditStore,
            remoteSendPendingStore = remoteSendPendingStore,
            bundledModelInstaller = bundledModelInstaller,
            skipStartupModelRuntimeWork = skipStartupModelRuntimeWork,
        )

    private class FakeChatPlacementRuntime(
        private val rejectBinding: Boolean = false,
        private val stopBeforeAwait: Boolean = false,
        private val dispatchFailure: Throwable? = null,
    ) : ChatPlacementRuntime {
        var dispatchCount: Int = 0
            private set
        var abortCount: Int = 0
            private set
        val stoppedRunIds = mutableListOf<String>()
        val receipts = mutableListOf<RunDataReceipt>()
        private val activeCalls = mutableMapOf<String, ChatServingCall>()
        private val permits = mutableMapOf<String, ActiveRunPlacementPermit>()

        override fun activeBinding(runId: String): ActiveRunPlacementPermit? = permits[runId]

        override fun bindAndReserve(binding: RunPlacementBinding): ActiveRunPlacementPermit? =
            if (rejectBinding) {
                null
            } else {
                ActiveRunPlacementPermit.detached(binding).also { permit ->
                    permits[binding.runId] = permit
                }
            }

        override fun abort(permit: ActiveRunPlacementPermit) {
            abortCount += 1
            permits.remove(permit.binding.runId)
            activeCalls.remove(permit.binding.runId)?.stop()
        }

        override suspend fun dispatch(
            prepared: PreparedChatRun,
            receipt: RunDataReceipt,
            local: ChatServingCall,
            remote: ChatServingCall,
        ) {
            dispatchCount += 1
            receipts += receipt
            dispatchFailure?.let { throw it }
            activeCalls[prepared.runId] = when (prepared.placement) {
                RunPlacement.Local -> local
                RunPlacement.Remote -> remote
            }
            if (stopBeforeAwait) activeCalls.getValue(prepared.runId).stop()
            try {
                when (prepared.placement) {
                    RunPlacement.Local -> local.await()
                    RunPlacement.Remote -> remote.await()
                }
            } finally {
                activeCalls.remove(prepared.runId)
            }
        }

        override fun stop(runId: String, state: AgentRunState): Boolean {
            val active = activeCalls.remove(runId) ?: return false
            permits.remove(runId)
            stoppedRunIds += runId
            active.stop()
            return true
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun forceInferenceMode(viewModel: SolinViewModel, mode: InferenceMode) {
        val field = SolinViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        val state = field.get(viewModel) as MutableStateFlow<ChatUiState>
        state.value = state.value.copy(inferenceMode = mode)
    }

    private fun invokeOnCleared(viewModel: SolinViewModel) {
        SolinViewModel::class.java.getDeclaredMethod("onCleared").apply {
            isAccessible = true
            invoke(viewModel)
        }
    }

    private fun installedModelSummary(
        id: String,
        displayName: String,
        path: String,
        recommendedModelId: String? = null,
        verificationStatus: ModelVerificationStatus = ModelVerificationStatus.UnverifiedCustom,
    ): InstalledModelSummary =
        InstalledModelSummary(
            id = id,
            displayName = displayName,
            path = path,
            fileBytes = 1L,
            recommendedModelId = recommendedModelId,
            verificationStatus = verificationStatus,
        )

    private fun pendingRemoteSendMarker(
        kind: RemoteSendDisclosureKind = RemoteSendDisclosureKind.CurrentInput,
        remoteModelName: String = "model-a",
        remoteHistoryCount: Int = 1,
        localOnlyHistoryFilteredCount: Int = 0,
        imageAttachmentCount: Int = 0,
        protectedSourceCount: Int = 0,
        runId: String? = null,
    ): PendingRemoteSendMarker =
        PendingRemoteSendMarker(
            kind = kind,
            remoteModelName = remoteModelName,
            remoteHistoryCount = remoteHistoryCount,
            localOnlyHistoryFilteredCount = localOnlyHistoryFilteredCount,
            imageAttachmentCount = imageAttachmentCount,
            protectedSourceCount = protectedSourceCount,
            runId = runId,
        )

    private fun sharedTextPreviewInput(
        kind: SharedAttachmentKind = SharedAttachmentKind.Document,
        mimeType: String,
        displayName: String,
        sizeBytes: Long,
        text: String,
        truncated: Boolean = false,
        source: SharedTextPreviewSource = SharedTextPreviewSource.TextFile,
    ): SharedInput =
        SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = kind,
                    mimeType = mimeType,
                    displayName = displayName,
                    sizeBytes = sizeBytes,
                    textPreview = SharedTextPreview(
                        text = text,
                        truncated = truncated,
                        source = source,
                    ),
                ),
            ),
        )

    private fun sharedImageInput(
        displayName: String = "screen.png",
        dataUrl: String = TEST_IMAGE_DATA_URL,
        sizeBytes: Long = 12L,
        mimeType: String = "image/png",
    ): SharedInput =
        SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Image,
                    mimeType = mimeType,
                    displayName = displayName,
                    sizeBytes = sizeBytes,
                    imageAttachment = ChatImageAttachment(
                        mimeType = mimeType,
                        dataUrl = dataUrl,
                    ),
                    localImageAttachment = LocalImageAttachment(
                        mimeType = mimeType,
                        bytes = byteArrayOf(0),
                        sizeBytes = 1,
                    ),
                ),
            ),
            sourcePrivacy = listOf(
                SharedInputSourcePrivacy(
                    source = SharedInputSource.Image,
                    privacy = MessagePrivacy.RemoteEligible,
                    requiresLocalModel = false,
                ),
            ),
        )

    private fun localSharedImageInput(
        displayName: String,
        mimeType: String = "image/png",
        sizeBytes: Long = 4L,
        bytes: ByteArray? = null,
    ): SharedInput =
        SharedInput(
            text = "",
            attachments = listOf(
                SharedAttachment(
                    kind = SharedAttachmentKind.Image,
                    mimeType = mimeType,
                    displayName = displayName,
                    sizeBytes = sizeBytes,
                    localImageAttachment = bytes?.let {
                        LocalImageAttachment(
                            mimeType = mimeType,
                            bytes = it,
                            sizeBytes = sizeBytes,
                        )
                    },
                ),
            ),
        )

    private fun publicWeatherBatchRequests(): List<ToolRequest> =
        listOf(
            publicWeatherRequest(id = "call-beijing", query = "北京 今天 天气"),
            publicWeatherRequest(id = "call-shanghai", query = "上海 今天 天气"),
        )

    private fun publicWeatherRequest(id: String, query: String): ToolRequest =
        ToolRequest(
            id = id,
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to query),
            reason = "remote tool call",
        )

    private fun publicEvidenceBatchPlans(requests: List<ToolRequest>): List<AgentPlan.UseTool> =
        requests.map { request ->
            AgentPlan.UseTool(
                request = request,
                draft = ActionDraft(
                    functionName = MobileActionFunctions.WEB_SEARCH,
                    title = "Web 搜索",
                    summary = "将使用 Web 搜索工具查询并整理结果：${request.arguments["query"]}",
                    parameters = request.arguments,
                ),
                plannedByModel = true,
                fallbackReason = "remote tool batch",
                safetyDecision = SafetyDecision(
                    outcome = SafetyOutcome.Allow,
                    reason = "Read-only web search can execute without confirmation.",
                ),
            )
        }

    private fun publicEvidenceBatchModelObservation(
        runId: String,
        requests: List<ToolRequest>,
    ): AgentModelObservationResult =
        AgentModelObservationResult(
            run = publicEvidenceBatchRun(runId, AgentRunState.ExecutingTool, 2L),
            decision = AgentObservationDecision.PlanToolBatch(
                plans = publicEvidenceBatchPlans(requests),
                reason = "Remote model requested 2 parallel public evidence tool calls.",
            ),
            steps = emptyList(),
        )

    private fun publicEvidenceBatchToolObservation(
        runId: String,
        state: AgentRunState = AgentRunState.GeneratingAnswer,
        resultStatus: ToolStatus = ToolStatus.Succeeded,
        summary: String = "工具执行结果：已完成 2 个公开只读工具调用。",
        decision: AgentObservationDecision = AgentObservationDecision.ContinueWithModel(
            requiresLocalModel = false,
            reason = "Parallel public evidence tools completed.",
        ),
        continuationPrompt: String? = "请综合北京和上海的天气结果计算温差。",
        retryable: Boolean = false,
    ): AgentObservationResult =
        AgentObservationResult(
            run = publicEvidenceBatchRun(runId, state, 3L),
            result = ToolResult(
                requestId = "public-evidence-batch",
                status = resultStatus,
                summary = summary,
                data = mapOf("toolName" to "public_evidence_batch", "toolCount" to "2"),
                retryable = retryable,
            ),
            assistantMessage = summary,
            decision = decision,
            continuationPromptForModel = continuationPrompt,
            steps = emptyList(),
        )

    private fun publicEvidenceBatchCompletedObservation(runId: String): AgentModelObservationResult =
        AgentModelObservationResult(
            run = publicEvidenceBatchRun(runId, AgentRunState.Completed, 4L),
            decision = AgentObservationDecision.Complete,
            steps = emptyList(),
        )

    private fun publicEvidenceBatchRun(
        runId: String,
        state: AgentRunState,
        updatedAtMillis: Long,
    ): AgentRun =
        AgentRun(
            id = runId,
            input = "北京和上海今天温差多少？",
            state = state,
            createdAtMillis = 1L,
            updatedAtMillis = updatedAtMillis,
        )

    private fun assertRemoteProtectedSharedInput(
        remoteRuntime: RecordingRemoteChatRuntime,
        sessionStore: FakeSessionStore,
        statusText: String,
        forbiddenText: List<String>,
    ) {
        assertTrue(remoteRuntime.calls.isEmpty())
        assertEquals(1, sessionStore.messages.size)
        val message = sessionStore.messages.single()
        assertEquals(MessageRole.Assistant, message.role)
        assertEquals(MessagePrivacy.LocalOnly, message.privacy)
        forbiddenText.forEach { forbidden ->
            assertFalse("Protected share notice leaked `$forbidden`", message.text.contains(forbidden))
        }
        assertTrue(message.text.contains("只在本机处理，不会自动发送"))
        assertTrue(message.text.contains("附件元数据"))
        assertTrue(message.text.contains("JSON/XML/YAML 文本摘录"))
        assertEquals("已保护分享内容", statusText)
    }

    private fun configuredRemoteModel(): RemoteModelConfig =
        RemoteModelConfig(
            baseUrl = "https://api.example.com/v1",
            modelName = "model-a",
            // Vision-capable test model: supportsVisionInput now defaults to false (fail-closed),
            // so image-sending tests must opt in explicitly.
            supportsVisionInput = true,
            profileRevision = TEST_REMOTE_REVISION,
        )

    private fun configuredRemoteStore(
        config: RemoteModelConfig = configuredRemoteModel(),
    ): FakeRemoteModelStore =
        FakeRemoteModelStore(
            mode = InferenceMode.Remote,
            config = config,
        )

    private data class RemoteCall(
        val prompt: String,
        val history: List<ChatMessage>,
        val parameters: GenerationParameters,
        val tools: List<ToolSpec> = emptyList(),
        val imageAttachments: List<ChatImageAttachment> = emptyList(),
    )

    private class FakeRemoteModelConnectivityProbe(
        private val status: RemoteModelConnectivityStatus = RemoteModelConnectivityStatus.Reachable,
    ) : RemoteModelConnectivityProbe {
        val checkedConfigs = mutableListOf<RemoteModelConfig>()

        override suspend fun check(config: RemoteModelConfig): RemoteModelConnectivityStatus {
            checkedConfigs += config.normalized()
            return status
        }
    }

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

    private class RecordingRemoteChatRuntime(
        private val events: List<RemoteChatEvent> = listOf(RemoteChatEvent.TextDelta("远程回复")),
        private val eventBatches: List<List<RemoteChatEvent>> = emptyList(),
        var failure: Throwable? = null,
        private val hangDuringSend: Boolean = false,
    ) : RemoteChatRuntime {
        val calls = mutableListOf<RemoteCall>()
        var stopCallCount: Int = 0
            private set

        override fun send(
            prompt: String,
            history: List<ChatMessage>,
            parameters: GenerationParameters,
            config: RemoteModelConfig,
            imageAttachments: List<ChatImageAttachment>,
        ): Flow<String> {
            calls += RemoteCall(prompt, history, parameters, imageAttachments = imageAttachments)
            failure?.let { throwable ->
                return flow { throw throwable }
            }
            if (hangDuringSend) {
                return flow { awaitCancellation() }
            }
            return flowOf("远程回复")
        }

        override fun sendWithTools(
            prompt: String,
            history: List<ChatMessage>,
            parameters: GenerationParameters,
            config: RemoteModelConfig,
            tools: List<ToolSpec>,
            imageAttachments: List<ChatImageAttachment>,
        ): Flow<RemoteChatEvent> {
            val callIndex = calls.size
            calls += RemoteCall(prompt, history, parameters, tools, imageAttachments)
            failure?.let { throwable ->
                return flow { throw throwable }
            }
            if (hangDuringSend) {
                return flow { awaitCancellation() }
            }
            val eventsForCall = eventBatches.getOrNull(callIndex) ?: events
            return flowOf(*eventsForCall.toTypedArray())
        }

        override fun stop() {
            stopCallCount += 1
        }
    }

    private class RecordingToolExecutor : ToolExecutor {
        val executedRequests: MutableList<ToolRequest> = Collections.synchronizedList(mutableListOf())

        override fun execute(request: ToolRequest): ToolResult {
            executedRequests += request
            return ToolResult(
                requestId = request.id,
                status = ToolStatus.Succeeded,
                summary = "executed",
            )
        }
    }

    private class RetryableFailureThenSuccessToolExecutor(
        private val failOnceRequestId: String,
    ) : ToolExecutor {
        val executedRequests: MutableList<ToolRequest> = Collections.synchronizedList(mutableListOf())
        private val attemptsByRequestId = mutableMapOf<String, Int>()

        override fun execute(request: ToolRequest): ToolResult {
            executedRequests += request
            val attempt = synchronized(attemptsByRequestId) {
                val nextAttempt = attemptsByRequestId.getOrDefault(request.id, 0) + 1
                attemptsByRequestId[request.id] = nextAttempt
                nextAttempt
            }
            return if (request.id == failOnceRequestId && attempt == 1) {
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Failed,
                    summary = "temporary network unavailable",
                    retryable = true,
                )
            } else {
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "executed",
                )
            }
        }
    }

    private class ThrowingToolExecutor(
        private val throwable: Throwable,
    ) : ToolExecutor {
        override fun execute(request: ToolRequest): ToolResult {
            throw throwable
        }
    }

    private class FakeLiteRtRuntime(
        private val localResponse: String = "本地回复",
        private val localChunks: List<String> = listOf(localResponse),
        private val failure: Throwable? = null,
        private val hangDuringSend: Boolean = false,
        private val loadFailures: Map<BackendChoice, Throwable> = emptyMap(),
    ) : LiteRtRuntime {
        val prompts = mutableListOf<String>()
        val localRequests = mutableListOf<LocalModelRequest>()
        val recreatedHistories = mutableListOf<List<ChatMessage>>()
        val preparedForSendPrompts = mutableListOf<String>()
        val preparedForSendMaxInputTokens = mutableListOf<Int?>()
        val loadCalls = mutableListOf<BackendChoice>()
        val configuredCapabilities = mutableListOf<LocalModelRuntimeCapabilities>()
        var stopCallCount: Int = 0
            private set
        var closeCallCount: Int = 0
            private set
        var recreateCallCount: Int = 0
            private set
        override var isLoaded: Boolean = false

        override fun configureModelCapabilities(capabilities: LocalModelRuntimeCapabilities) {
            configuredCapabilities += capabilities
        }

        override fun load(
            modelPath: String,
            backend: BackendChoice,
            history: List<ChatMessage>,
            parameters: GenerationParameters,
        ) {
            loadCalls += backend
            loadFailures[backend]?.let { throwable ->
                isLoaded = false
                throw throwable
            }
            isLoaded = true
        }

        override fun recreateConversation(
            history: List<ChatMessage>,
            parameters: GenerationParameters,
        ) {
            recreateCallCount += 1
            recreatedHistories += history
            isLoaded = true
        }

        override fun recreateConversationForSend(
            history: List<ChatMessage>,
            prompt: String,
            parameters: GenerationParameters,
            maxInputTokens: Int?,
        ) {
            preparedForSendPrompts += prompt
            preparedForSendMaxInputTokens += maxInputTokens
            recreateConversation(history, parameters)
        }

        override fun send(prompt: String): Flow<String> {
            prompts += prompt
            failure?.let { throw it }
            if (hangDuringSend) {
                return flow { awaitCancellation() }
            }
            return flow {
                localChunks.forEach { chunk -> emit(chunk) }
            }
        }

        override fun send(request: LocalModelRequest): Flow<String> {
            localRequests += request
            return send(request.prompt)
        }

        override fun lastGenerationStats(): GenerationStats? = null

        override fun stop() {
            stopCallCount += 1
        }

        override fun close() {
            closeCallCount += 1
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
        private val toolBatchObservation: AgentObservationResult? = null,
        private val toolObservationsByRunId: Map<String, AgentObservationResult> = emptyMap(),
        private val modelObservation: AgentModelObservationResult? = null,
        private val modelObservations: List<AgentModelObservationResult> = emptyList(),
        private val modelToolObservation: AgentModelObservationResult? = null,
        private val modelToolBatchObservation: AgentModelObservationResult? = null,
        private val externalOutcomeResult: AgentExternalOutcomeResult? = null,
        private val restoredPendingRoute: AssistantRoute.Action? = null,
        private val restoredPendingExternalOutcome: PendingExternalOutcomeSnapshot? = null,
        private val recoveryRoute: AssistantRoute? = null,
        private val recentTraceRuns: List<AgentTraceRunSummary> = emptyList(),
        private val runEventsById: Map<String, List<AgentRunEvent>> = emptyMap(),
        private val publicWebEvidenceByRunId: Map<String, List<PublicWebEvidencePack>> = emptyMap(),
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
        var observeToolBatchCallCount: Int = 0
            private set
        var observeModelToolCallCount: Int = 0
            private set
        var observeModelToolBatchCallCount: Int = 0
            private set
        var observeModelResultCallCount: Int = 0
            private set
        var failModelGenerationCallCount: Int = 0
            private set
        var cancelRunCallCount: Int = 0
            private set
        var terminateRunCallCount: Int = 0
            private set
        var failPendingCallCount: Int = 0
            private set
        var recordExternalOutcomeCallCount: Int = 0
            private set
        var lastObservedResult: ToolResult? = null
            private set
        var lastObservedResults: List<ToolResult> = emptyList()
            private set
        var lastExternalOutcome: AgentExternalOutcome? = null
            private set
        var lastObservedModelToolRequest: ToolRequest? = null
            private set
        var lastObservedModelToolRequests: List<ToolRequest> = emptyList()
            private set
        var lastFailedModelRunId: String? = null
            private set
        var lastFailedModelReason: String? = null
            private set
        var lastCancelledRunId: String? = null
            private set
        var lastCancelledRunReason: String? = null
            private set
        var lastTerminatedRunId: String? = null
            private set
        var lastTerminatedRunReason: String? = null
            private set
        var lastFailedPendingResult: ToolResult? = null
            private set
        var recentTraceRunLimit: Int? = null
            private set
        var recentTraceStepLimit: Int? = null
            private set
        var lastRouteSessionId: String? = null
            private set
        var lastRouteMemoryEnabled: Boolean? = null
            private set
        var lastRouteOptions: AgentRunOptions? = null
            private set
        var lastRouteDeviceContext: com.bytedance.zgx.solin.device.DeviceContextSnapshot? = null
            private set
        var lastRouteCapabilityProfiles: List<ModelCapabilityProfile> = emptyList()
            private set
        var lastRecordedRunDataReceiptRunId: String? = null
            private set
        var lastRecordedRunDataReceipt: RunDataReceipt? = null
            private set
        var lastOutputQualityTraceRunId: String? = null
            private set
        var lastOutputQualityTrace: ModelOutputQualityTrace? = null
            private set
        var lastRecoverySessionId: String? = null
            private set
        var lastRestorePendingSessionId: String? = null
            private set
        var lastRestorePendingExternalOutcomeSessionId: String? = null
            private set
        var lastObserveModelResultAllowInlineToolCalls: Boolean? = null
            private set
        var lastRemoteToolsExposedScope: RemoteToolScope? = null
            private set
        var lastRemoteToolsExposedNames: Set<String> = emptySet()
            private set
        private val knownRunStates = linkedMapOf<String, AgentRunState>()
        private var failedModelTraceRun: AgentTraceRunSummary? = null
        private var cancelledTraceRun: AgentTraceRunSummary? = null
        val deletedTraceSessionIds = mutableListOf<String>()

        override fun route(
            input: String,
            installedCapabilities: Set<ModelCapability>,
            memoryEnabled: Boolean,
            actionModelPath: String?,
            deviceContext: com.bytedance.zgx.solin.device.DeviceContextSnapshot?,
            sessionId: String?,
            options: AgentRunOptions,
            installedCapabilityProfiles: List<ModelCapabilityProfile>,
        ): AssistantRoute {
            routeCallCount += 1
            lastRouteSessionId = sessionId
            lastRouteMemoryEnabled = memoryEnabled
            lastRouteOptions = options
            lastRouteDeviceContext = deviceContext
            lastRouteCapabilityProfiles = installedCapabilityProfiles
            routeFailure?.let { throw it }
            val route = routeResult ?:
                AssistantRoute.Chat(
                    runId = "run-route-$routeCallCount",
                    promptForModel = input,
                    memoryHits = emptyList(),
                    deviceContext = deviceContext,
                )
            recordRoute(route)
            return route
        }

        override fun requestRecoveryAction(action: AgentRecoveryAction, sessionId: String?): AssistantRoute {
            requestRecoveryCallCount += 1
            lastRecoverySessionId = sessionId
            val route = recoveryRoute ?: AssistantRoute.Action(
                runId = "run-recovery",
                toolRequest = action.request,
                draft = action.draft,
                plannedByModel = false,
                fallbackReason = "typed recovery action",
            )
            recordRoute(route)
            return route
        }

        override fun failStaleInFlightRuns(reason: String): Int = 0

        override fun failModelGeneration(runId: String, reason: String): AgentModelObservationResult? {
            failModelGenerationCallCount += 1
            lastFailedModelRunId = runId
            lastFailedModelReason = reason
            if (knownRunStates[runId] != AgentRunState.GeneratingAnswer) return null
            knownRunStates[runId] = AgentRunState.Failed
            failedModelTraceRun = AgentTraceRunSummary(
                run = AgentRun(runId, "", AgentRunState.Failed, 1L, 2L),
                steps = listOf(
                    AgentTraceStepSummary(
                        runId = runId,
                        position = 0,
                        type = "Failed",
                        summary = reason,
                        json = "{}",
                        createdAtMillis = 2L,
                    ),
                ),
            )
            return AgentModelObservationResult(
                run = AgentRun(runId, "", AgentRunState.Failed, 1L, 2L),
                decision = AgentObservationDecision.Fail(reason),
                steps = emptyList(),
            )
        }

        override fun cancelRun(runId: String, reason: String): AgentModelObservationResult? {
            cancelRunCallCount += 1
            lastCancelledRunId = runId
            lastCancelledRunReason = reason
            val existingState = knownRunStates[runId]
            if (
                existingState == AgentRunState.Completed ||
                existingState == AgentRunState.Cancelled ||
                existingState == AgentRunState.Failed
            ) {
                return null
            }
            knownRunStates[runId] = AgentRunState.Cancelled
            cancelledTraceRun = AgentTraceRunSummary(
                run = AgentRun(runId, "", AgentRunState.Cancelled, 1L, 2L),
                steps = listOf(
                    AgentTraceStepSummary(
                        runId = runId,
                        position = 0,
                        type = "ObservationDecided",
                        summary = "Observation cancelled.",
                        json = """{"type":"ObservationDecided","decision":{"type":"Cancel"}}""",
                        createdAtMillis = 2L,
                    ),
                ),
            )
            return AgentModelObservationResult(
                run = AgentRun(runId, "", AgentRunState.Cancelled, 1L, 2L),
                decision = AgentObservationDecision.Cancel,
                steps = emptyList(),
            )
        }

        override fun terminateRun(runId: String, reason: String): AgentModelObservationResult? {
            terminateRunCallCount += 1
            lastTerminatedRunId = runId
            lastTerminatedRunReason = reason
            return cancelRun(runId, reason)
        }

        override fun confirmToolRequest(runId: String, requestId: String): AgentRun? {
            confirmCallCount += 1
            confirmFailure?.let { throw it }
            return (confirmedRunsById[runId] ?: confirmedRun).also { run -> recordRun(run) }
        }

        override fun cancelToolRequest(runId: String, requestId: String): AgentObservationResult? {
            cancelCallCount += 1
            return cancelObservation
        }

        override fun answerUserQuestion(
            runId: String,
            questionId: String,
            answer: String,
        ): AgentObservationResult? = null

        override fun cancelUserQuestion(runId: String, questionId: String): AgentObservationResult? =
            null

        override fun failPendingToolRequest(
            runId: String,
            requestId: String,
            result: ToolResult,
        ): AgentObservationResult? {
            failPendingCallCount += 1
            lastFailedPendingResult = result
            knownRunStates[runId] = AgentRunState.Failed
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
            return (toolObservationsByRunId[runId] ?: toolObservation).also { observation ->
                recordRun(observation?.run)
            }
        }

        override fun observeToolResults(runId: String, results: List<ToolResult>): AgentObservationResult? {
            observeToolBatchCallCount += 1
            lastObservedResults = results
            return toolBatchObservation.also { observation ->
                recordRun(observation?.run)
            }
        }

        override fun recordExternalOutcome(
            runId: String,
            requestId: String,
            outcome: AgentExternalOutcome,
        ): AgentExternalOutcomeResult? {
            recordExternalOutcomeCallCount += 1
            lastExternalOutcome = outcome
            return externalOutcomeResult.also { result ->
                recordRun(result?.run)
            }
        }

        override fun observeModelResult(
            runId: String,
            text: String,
            allowInlineToolCalls: Boolean,
        ): AgentModelObservationResult? {
            observeModelResultCallCount += 1
            lastObserveModelResultAllowInlineToolCalls = allowInlineToolCalls
            return (
                modelObservations.getOrNull(observeModelResultCallCount - 1)
                    ?: modelObservation
                ).also { observation -> recordRun(observation?.run) }
        }

        override fun recordRemoteToolsExposed(
            runId: String,
            scope: RemoteToolScope,
            toolNames: Set<String>,
        ) {
            lastRemoteToolsExposedScope = scope
            lastRemoteToolsExposedNames = toolNames
        }

        override fun recordRunDataReceipt(runId: String, receipt: RunDataReceipt) {
            lastRecordedRunDataReceiptRunId = runId
            lastRecordedRunDataReceipt = receipt
        }

        override fun recordModelOutputQualityGuardTriggered(runId: String, trace: ModelOutputQualityTrace) {
            lastOutputQualityTraceRunId = runId
            lastOutputQualityTrace = trace
        }

        override fun observeModelToolRequest(runId: String, request: ToolRequest): AgentModelObservationResult? {
            observeModelToolCallCount += 1
            lastObservedModelToolRequest = request
            return modelToolObservation.also { observation -> recordRun(observation?.run) }
        }

        override fun observeModelToolRequests(
            runId: String,
            requests: List<ToolRequest>,
        ): AgentModelObservationResult? {
            observeModelToolBatchCallCount += 1
            lastObservedModelToolRequests = requests
            return modelToolBatchObservation.also { observation -> recordRun(observation?.run) }
        }

        override fun restorePendingAction(sessionId: String?): AssistantRoute.Action? {
            lastRestorePendingSessionId = sessionId
            return restoredPendingRoute
        }

        override fun restorePendingExternalOutcome(sessionId: String?): PendingExternalOutcomeSnapshot? {
            lastRestorePendingExternalOutcomeSessionId = sessionId
            return restoredPendingExternalOutcome
        }

        override fun recentTraceRuns(limit: Int, stepLimit: Int): List<AgentTraceRunSummary> {
            recentTraceRunLimit = limit
            recentTraceStepLimit = stepLimit
            val failedTraceRun = failedModelTraceRun
            val cancelledRun = cancelledTraceRun
            return listOfNotNull(cancelledRun, failedTraceRun) +
                recentTraceRuns.filterNot { trace ->
                    trace.run.id == failedTraceRun?.run?.id || trace.run.id == cancelledRun?.run?.id
                }
        }

        override fun runEvents(runId: String): List<AgentRunEvent> =
            runEventsById[runId].orEmpty()

        override fun publicWebEvidence(runId: String): List<PublicWebEvidencePack> =
            publicWebEvidenceByRunId[runId].orEmpty()

        override fun deleteRunsForSession(sessionId: String): Int {
            deletedTraceSessionIds += sessionId
            return 1
        }

        override fun availableToolSpecs(): List<ToolSpec> =
            ToolRegistry().specs()

        private fun recordRoute(route: AssistantRoute) {
            when (route) {
                is AssistantRoute.Action -> route.runId?.let { runId ->
                    knownRunStates[runId] = AgentRunState.AwaitingUserConfirmation
                }

                is AssistantRoute.Chat -> route.runId?.let { runId ->
                    knownRunStates[runId] = AgentRunState.GeneratingAnswer
                }

                is AssistantRoute.MissingModel,
                is AssistantRoute.ToolRejected -> Unit
            }
        }

        private fun recordRun(run: AgentRun?) {
            if (run != null) {
                knownRunStates[run.id] = run.state
            }
        }

        override fun close() = Unit
    }

    private class FakeSessionStore(
        initialSessions: Map<String, List<ChatMessage>> = mapOf("session-1" to emptyList()),
        initialActiveSessionId: String = initialSessions.keys.first(),
    ) : SessionStore {
        private val sessionsById = linkedMapOf<String, List<ChatMessage>>().apply {
            putAll(initialSessions)
        }
        private var nextSessionId = sessionsById.size + 1
        override var activeSessionId: String = initialActiveSessionId
            private set
        var messages: List<ChatMessage>
            get() = sessionsById[activeSessionId].orEmpty()
            set(value) {
                sessionsById[activeSessionId] = value
            }

        override fun summaries(): List<ChatSessionSummary> =
            sessionsById.map { (sessionId, messages) ->
                ChatSessionSummary(sessionId, "测试会话", 1L, messages.size)
            }

        override fun activeMessages(): List<ChatMessage> = messages

        override fun allMessages(limit: Int): List<ChatMessage> =
            sessionsById.values.flatten().takeLast(limit)

        override fun createNewSession(): List<ChatMessage> {
            val sessionId = "session-${nextSessionId++}"
            sessionsById[sessionId] = emptyList()
            activeSessionId = sessionId
            return messages
        }

        override fun selectSession(sessionId: String): List<ChatMessage>? {
            if (sessionId !in sessionsById) return null
            activeSessionId = sessionId
            return messages
        }

        override fun deleteActiveSession(): List<ChatMessage>? {
            sessionsById.remove(activeSessionId)
            if (sessionsById.isEmpty()) {
                sessionsById["session-${nextSessionId++}"] = emptyList()
            }
            activeSessionId = sessionsById.keys.first()
            return messages
        }

        override fun replaceActiveSessionMessages(messages: List<ChatMessage>, persistNow: Boolean) {
            this.messages = messages
        }

        override fun persistActiveSessionFrom(messages: List<ChatMessage>) {
            this.messages = messages
        }
    }

    private class RecordingActionRuntime(
        private val likelyAction: Boolean,
        private val modelOutputResult: ModelToolOutputParseResult = ModelToolOutputParseResult.None,
    ) : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = likelyAction

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(ActionPlanKind.NoAction),
                usedModel = false,
                fallbackReason = null,
            )

        override fun parseModelToolOutput(output: String): ModelToolOutputParseResult =
            modelOutputResult
    }

    private class CountingMemoryRepository : MemoryIndex, LongTermMemoryControls {
        override var enabled: Boolean = true
        var rebuildCallCount: Int = 0
            private set

        override fun rebuild(messages: List<ChatMessage>) {
            rebuildCallCount += 1
        }

        override fun index(id: String, text: String) = Unit

        override fun search(query: String, topK: Int): List<MemoryHit> = emptyList()

        override fun buildContext(hits: List<MemoryHit>): String = ""

        override fun savedRecords(): List<PersistedMemoryRecord> = emptyList()

        override fun indexPreference(id: String, text: String) = Unit

        override fun indexUserFact(id: String, text: String) = Unit

        override fun indexTaskState(id: String, text: String) = Unit

        override fun suppressAutoManagedTaskState(id: String) = Unit

        override fun unsuppressAutoManagedTaskState(id: String) = Unit

        override fun isAutoManagedTaskStateSuppressed(id: String): Boolean = false

        override fun forget(id: String): Boolean = false

        override fun forgetAutoManagedTaskState(id: String): Boolean = false

        override fun forgetPreference(target: String): Boolean = false

        override fun forgetUserFact(target: String): Boolean = false

        override fun clear() = Unit
    }

    private class ConciseSemanticRuntime : EmbeddingRuntime {
        override val modelId: String = "concise-test"
        override val dimension: Int = 2
        override val supportsSemanticRecall: Boolean = true
        override val semanticScoreThreshold: Float = 0.9f

        override fun embed(text: String): FloatArray {
            val lower = text.lowercase()
            return when {
                "concise" in lower -> floatArrayOf(1f, 0f)
                "brief" in lower -> floatArrayOf(1f, 0f)
                "compressed" in lower -> floatArrayOf(1f, 0f)
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
        private val onReconcilePeriodicCheckOnStartup: (MutableMap<String, ScheduledTask>) -> Unit = {},
    ) : BackgroundTaskScheduler {
        private val tasks = linkedMapOf<String, ScheduledTask>()
        val cancelledTaskIds = mutableListOf<String>()
        val periodicPolicyRequests = mutableListOf<PeriodicCheckScheduleRequest>()
        var disablePeriodicCheckCount = 0
        var rescheduleCount = 0
        var reconcilePeriodicCheckCount = 0

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

        override fun reconcilePeriodicCheckOnStartup(): Result<PeriodicCheckPolicySummary> {
            reconcilePeriodicCheckCount += 1
            onReconcilePeriodicCheckOnStartup(tasks)
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
        activeInstalledModelId: String? = null,
        initialInstalledModels: List<InstalledModelSummary> = emptyList(),
        private var memoryEmbeddingModelPath: String? = null,
        private val customDownloadSource: ModelDownloadSource? = null,
        private val downloadedModelFileProvider: ((String) -> File?)? = null,
        private var pendingDownloadId: Long = -1L,
        private var pendingDownloadSource: ModelDownloadSource? = null,
        private val deleteResult: Boolean = true,
        private val activeModelVerificationResult: Boolean = true,
    ) : ModelRepositoryFacade {
        val registeredModels = mutableListOf<InstalledModelSummary>()
        val deletedModelIds = mutableListOf<String>()
        val savedPendingDownloads = mutableListOf<Pair<Long, ModelDownloadSource>>()
        var clearPendingDownloadCount = 0

        private var state = ModelSelectionState(
            selectedModelId = DEFAULT_CHAT_MODEL_ID,
            activeInstalledModelId = activeInstalledModelId,
            activeModelPath = activeModelPath ?: initialInstalledModels
                .firstOrNull { it.id == activeInstalledModelId }
                ?.path,
            installedModels = initialInstalledModels,
        )

        override fun currentState(): ModelSelectionState = state

        override fun verifyActiveModelPath(path: String): Boolean =
            activeModelVerificationResult && state.activeModelPath == path

        override fun selectedRecommendedModel(): RecommendedModel =
            ModelCatalog.recommendedChatModelById(state.selectedModelId)

        override fun selectRecommendedModel(modelId: String): ModelSelectionResult {
            state = state.copy(selectedModelId = ModelCatalog.recommendedChatModelById(modelId).id)
            return ModelSelectionResult(state, null)
        }

        override fun selectInstalledModel(modelId: String): InstalledModelSummary? {
            val selected = state.installedModels.firstOrNull { it.id == modelId } ?: return null
            state = state.copy(
                activeInstalledModelId = selected.id,
                activeModelPath = selected.path,
            )
            return selected
        }

        override fun deleteInstalledModel(modelId: String): Boolean {
            deletedModelIds += modelId
            if (!deleteResult) return false
            val deleted = state.installedModels.firstOrNull { it.id == modelId } ?: return false
            val remaining = state.installedModels.filterNot { it.id == modelId }
            val fallback = if (state.activeInstalledModelId == deleted.id) {
                remaining.firstOrNull { model ->
                    model.capability == ModelCapability.Chat && model.isUsable
                }
            } else {
                remaining.firstOrNull { it.id == state.activeInstalledModelId }
            }
            state = state.copy(
                activeInstalledModelId = fallback?.id,
                activeModelPath = fallback?.path,
                installedModels = remaining,
            )
            return true
        }

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
            registeredModels += summary
            state = state.copy(
                activeInstalledModelId = summary.id,
                activeModelPath = path,
                installedModels = state.installedModels + summary,
            )
            return summary
        }

        override fun createCustomDownloadSource(downloadUrl: String): ModelDownloadSource? = customDownloadSource

        override fun downloadedModelFile(fileName: String): File? =
            if (downloadedModelFileProvider != null) {
                downloadedModelFileProvider.invoke(fileName)
            } else {
                File("/tmp/$fileName")
            }

        override fun resolveModelStorageBytes(): Long = 1024L * 1024L * 1024L

        override fun verifiedActionModelPath(): String? = null

        override fun verifiedObservationActionModelPath(): String? = null

        override fun verifiedMemoryEmbeddingModelPath(): String? = memoryEmbeddingModelPath

        override fun verifyLegacyRecommendedModels(): Boolean = false

        override fun importModel(uri: Uri, onProgress: (TransferProgress) -> Unit): String =
            "/tmp/imported.litertlm"

        override fun pendingDownloadId(): Long = pendingDownloadId

        override fun savePendingDownload(downloadId: Long, source: ModelDownloadSource) {
            pendingDownloadId = downloadId
            pendingDownloadSource = source
            savedPendingDownloads += downloadId to source
        }

        override fun clearPendingDownload() {
            clearPendingDownloadCount += 1
            pendingDownloadId = -1L
            pendingDownloadSource = null
        }

        override fun loadPendingDownloadSource(): ModelDownloadSource? = pendingDownloadSource
    }

    private class FakeGenerationParametersStore(
        private var parameters: GenerationParameters = GenerationParameters(),
    ) : GenerationParametersStore {
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
        private val beforeAutoModeSave: () -> Unit = {},
        private val afterAutoModeSaveBeforeConfigLoadReturns: () -> Unit = {},
        private val beforeConfigSave: () -> Unit = {},
    ) : RemoteModelStore {
        private var nextRevision: Int = 2
        private var connectivitySnapshot: RemoteConnectivitySnapshot? = null
        private val blockNextConfigLoadAfterAutoSave = AtomicBoolean(false)

        override fun loadMode(): InferenceMode = mode

        override fun saveMode(mode: InferenceMode): InferenceMode {
            if (mode == InferenceMode.Auto) beforeAutoModeSave()
            this.mode = mode
            if (mode == InferenceMode.Auto) blockNextConfigLoadAfterAutoSave.set(true)
            return mode
        }

        override fun loadConfig(): RemoteModelConfig {
            if (blockNextConfigLoadAfterAutoSave.compareAndSet(true, false)) {
                afterAutoModeSaveBeforeConfigLoadReturns()
            }
            return config
        }

        override fun saveConfig(config: RemoteModelConfig): Result<RemoteModelConfig> {
            beforeConfigSave()
            val requested = config.normalized()
            val revisionChanged = this.config.profileRevision.isBlank() ||
                !requested.hasSameConnectivityTarget(this.config)
            this.config = requested.copy(
                profileRevision = if (revisionChanged) {
                    "00000000-0000-0000-0000-${nextRevision++.toString().padStart(12, '0')}"
                } else {
                    this.config.profileRevision
                },
                connectivityStatus = RemoteModelConnectivityStatus.Unknown,
            )
            if (revisionChanged) connectivitySnapshot = null
            return Result.success(this.config)
        }

        override fun recordConnectivity(
            config: RemoteModelConfig,
            status: RemoteModelConnectivityStatus,
        ) {
            if (config.profileRevision.isBlank() || config.profileRevision != this.config.profileRevision) return
            connectivitySnapshot = RemoteConnectivitySnapshot(
                configRevision = config.profileRevision,
                status = status,
                checkedAtElapsedRealtimeMs = 1_000L,
            )
            this.config = this.config.copy(connectivityStatus = status)
        }

        override fun currentConnectivity(config: RemoteModelConfig): RemoteConnectivitySnapshot? =
            connectivitySnapshot?.takeIf { it.configRevision == config.profileRevision }

        override fun invalidateConnectivity() {
            connectivitySnapshot = null
            config = config.copy(connectivityStatus = RemoteModelConnectivityStatus.Unknown)
        }
    }

    private class FakeRemoteSendPendingStore(
        var pending: PendingRemoteSendMarker? = null,
    ) : RemoteSendPendingStore {
        override fun savePendingRemoteSend(marker: PendingRemoteSendMarker) {
            pending = marker
        }

        override fun consumePendingRemoteSend(): PendingRemoteSendMarker? =
            pending.also { pending = null }

        override fun clearPendingRemoteSend() {
            pending = null
        }
    }

    private class FakeRemoteSendAuditStore : RemoteSendAuditSink, RemoteSendAuditLog {
        private val events = mutableListOf<RemoteSendAuditEvent>()

        override fun record(event: RemoteSendAuditEvent) {
            events.add(0, event.redactedForAudit())
        }

        override fun recentRemoteSends(limit: Int): List<RemoteSendAuditEvent> =
            if (limit <= 0) emptyList() else events.take(limit)
    }

    private class FakeBundledModelInstaller(
        override val isEnabled: Boolean = false,
        private val result: BundledModelInstallResult = BundledModelInstallResult(available = false),
    ) : BundledModelInstaller {
        var installCount = 0

        override fun install(): BundledModelInstallResult {
            installCount += 1
            return result
        }
    }

    private class FakeFirstRunSetupStore(
        private var memoryEnabled: Boolean = true,
        private var setupDismissed: Boolean = true,
    ) : FirstRunSetupStore {
        override fun isSetupDismissed(): Boolean = setupDismissed

        override fun markSetupDismissed() {
            setupDismissed = true
        }

        override fun isMemoryEnabled(): Boolean = memoryEnabled

        override fun setMemoryEnabled(enabled: Boolean) {
            memoryEnabled = enabled
        }
    }

    private class FakeModelDownloadClient(
        private val enqueueResult: Result<Long> = Result.success(1L),
        private val queryResults: MutableList<DownloadInfo?> = mutableListOf(),
        private val onEnqueue: (ModelDownloadSource, File) -> Unit = { _, _ -> },
        private val onCancel: (Long) -> Unit = {},
    ) : ModelDownloadClient {
        val enqueuedDownloads = mutableListOf<Pair<ModelDownloadSource, File>>()
        val queriedDownloadIds = mutableListOf<Long>()
        val cancelledDownloadIds = mutableListOf<Long>()
        var preflightCancelCount = 0

        override fun enqueue(source: ModelDownloadSource, targetFile: File): Result<Long> {
            enqueuedDownloads += source to targetFile
            onEnqueue(source, targetFile)
            return enqueueResult
        }

        override fun cancelPreflight() {
            preflightCancelCount += 1
        }

        override fun cancel(downloadId: Long) {
            cancelledDownloadIds += downloadId
            onCancel(downloadId)
        }

        override fun query(downloadId: Long): DownloadInfo? {
            queriedDownloadIds += downloadId
            return if (queryResults.isEmpty()) null else queryResults.removeAt(0)
        }
    }

    private class FakeHuggingFaceAuthStore(
        private var token: String = "",
    ) : HuggingFaceAuthStore {
        override fun hasAccessToken(): Boolean = token.isNotBlank()

        override fun authorizationHeader(): String? =
            token.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

        override fun saveAccessToken(token: String): Result<Unit> {
            this.token = token.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
            return Result.success(Unit)
        }

        override fun clearAccessToken(): Result<Unit> {
            token = ""
            return Result.success(Unit)
        }
    }

    private fun withTempDownloadTarget(block: (File) -> Unit) {
        val file = File.createTempFile("solin-download", ".litertlm")
        file.delete()
        try {
            block(file)
        } finally {
            file.delete()
        }
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
