package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.PendingRemoteSendMarker
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditLog
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.data.RemoteSendPendingStore
import com.bytedance.zgx.solin.orchestration.AgentModelObservationResult
import com.bytedance.zgx.solin.orchestration.ActiveRunPlacementPermit
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.ModelDispatchState
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.orchestration.testBinding
import com.bytedance.zgx.solin.runtime.ModelOutputQualityGuard
import com.bytedance.zgx.solin.runtime.RemoteChatEvent
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import com.bytedance.zgx.solin.testing.FakeLiteRtRuntime
import com.bytedance.zgx.solin.tool.ToolSpec
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote tool-result continuation carries the tool result, so it must pass the same
 * sensitive-content preflight as the first remote chat turn. A sensitive continuation stays on the
 * device (no consent path exists for it) instead of silently reaching the remote model.
 */
class ChatToolContinuationRemoteEgressTest {
    private val uiState = MutableStateFlow(
        ChatUiState(
            messages = listOf(
                ChatMessage(role = MessageRole.User, text = "总结剪贴板", privacy = MessagePrivacy.RemoteEligible),
                ChatMessage(role = MessageRole.Assistant, text = "", privacy = MessagePrivacy.RemoteEligible),
            ),
            remoteModelConfig = RemoteModelConfig(baseUrl = "https://example.com/v1", modelName = "model-a"),
        ),
    )
    private val auditStore = RecordingAuditStore()
    private val remoteRuntime = RecordingRemoteRuntime()
    private val localRuntime = FakeLiteRtRuntime()
    private val recordingRouter = RecordingRouter()

    private fun remoteSendSupport(): ChatRemoteSendSupport =
        ChatRemoteSendSupport(
            uiState = uiState,
            remoteSendAuditSink = auditStore,
            remoteSendAuditLog = auditStore,
            remoteSendPendingStore = NoopPendingStore,
            requireRemoteSendDisclosure = false,
            replaceActiveSessionMessages = { _, _ -> },
            persistActiveSessionFromUi = {},
            loadAgentTraceRuns = { emptyList() },
            failModelGeneration = { runId, reason -> recordingRouter.failed += runId to reason },
            takeSharedInputRestoreMatching = { null },
            clearSharedInputRestore = {},
            applyConfirmedRemoteSendDraftClear = { state, _ -> state },
            applyCancelRemoteSendDraftRestore = { state, _, _, _ -> state },
            onResumeSendAfterDisclosure = { _, _ -> },
            onDiscardPreparedSend = {},
            onResumeContinuationAfterDisclosure = { _, _, _, _ -> },
        )

    private fun continuationSupport(
        remoteSendSupport: ChatRemoteSendSupport,
        launched: MutableList<String>,
    ): ChatToolContinuationSupport {
        val permit = ActiveRunPlacementPermit.detached(
            testBinding(
                runId = "run-remote",
                placement = RunPlacement.Remote,
                dispatchState = ModelDispatchState.Idle,
                attempt = 1,
            ),
        )
        val generationSupport = ChatGenerationSupport(
            uiState = uiState,
            runtime = localRuntime,
            remoteRuntime = remoteRuntime,
            assistantOrchestrator = recordingRouter,
            outputQualityGuard = ModelOutputQualityGuard(),
            runtimeLock = Mutex(),
            persistActiveSessionFromUi = {},
            persistMessagesAndRebuildMemory = { _, _ -> },
            loadAgentTraceRuns = { emptyList() },
            activeRunTimelineFor = { emptyList() },
        )
        return ChatToolContinuationSupport(
            uiState = uiState,
            runtime = localRuntime,
            assistantOrchestrator = recordingRouter,
            generationSupport = generationSupport,
            remoteSendSupport = remoteSendSupport,
            launchGenerationJob = { runId, block ->
                launched += runId.orEmpty()
                runBlocking { block {} }
            },
            cancelActiveGenerationRun = {},
            executePublicEvidenceToolBatchAfterRunIsExecuting = { _, _ -> },
            handleNextToolPlan = { _, _, _ -> },
            persistActiveSessionFromUi = {},
            rebuildMemoryIndex = {},
            loadAuditEvents = { emptyList() },
            loadAgentTraceRuns = { emptyList() },
            activeRunTimelineFor = { emptyList() },
            activePublicWebEvidenceFor = { emptyList() },
            activeRunPlacement = { runId -> permit.takeIf { it.binding.runId == runId } },
        )
    }

    @Test
    fun sensitiveToolResultContinuationStaysLocalWithoutConsent() = runTest {
        val launched = mutableListOf<String>()
        continuationSupport(remoteSendSupport(), launched).continueAfterToolObservation(
            runId = "run-remote",
            promptForModel = "工具结果：我的身份证号 110101199003071234，请继续",
            responsePrivacy = MessagePrivacy.RemoteEligible,
            remoteToolScope = RemoteToolScope.PublicEvidenceOnly,
        )

        assertTrue("no model call may start for a blocked continuation", launched.isEmpty())
        assertEquals(0, remoteRuntime.sentPrompts.size)
        assertEquals(listOf("run-remote" to "工具结果疑似包含个人信息或密钥，未发送到远程模型。"), recordingRouter.failed)
        val blocked = auditStore.events.single()
        assertTrue(blocked.summary.contains("已拦截"))
        assertFalse("the audit entry must not carry the prompt", blocked.summary.contains("110101199003071234"))
        assertEquals("已保护工具结果", uiState.value.statusText)
    }

    @Test
    fun ordinaryToolResultContinuationStillReachesTheRemoteModel() = runTest {
        val launched = mutableListOf<String>()
        continuationSupport(remoteSendSupport(), launched).continueAfterToolObservation(
            runId = "run-remote",
            promptForModel = "工具结果：Kotlin 1.9 发布，请继续",
            responsePrivacy = MessagePrivacy.RemoteEligible,
            remoteToolScope = RemoteToolScope.PublicEvidenceOnly,
        )

        assertEquals(listOf("run-remote"), launched)
        assertEquals(listOf("工具结果：Kotlin 1.9 发布，请继续"), remoteRuntime.sentPrompts)
        assertTrue(recordingRouter.failed.isEmpty())
    }

    private class RecordingRouter : AssistantRouter by unusedRouter() {
        val failed = mutableListOf<Pair<String, String>>()

        override fun failModelGeneration(runId: String, reason: String): AgentModelObservationResult? {
            failed += runId to reason
            return null
        }

        override fun recordRemoteToolsExposed(runId: String, scope: RemoteToolScope, toolNames: Set<String>) = Unit

        override fun availableRemoteToolSpecs(scope: RemoteToolScope): List<ToolSpec> = emptyList()

        override suspend fun compactHistory(
            messages: List<ChatMessage>,
            tokenBudget: Int,
            runId: String?,
            force: Boolean,
            estimatedTokens: ((List<ChatMessage>) -> Int)?,
        ): List<ChatMessage> = messages

        override fun recordRunDataReceipt(runId: String, receipt: com.bytedance.zgx.solin.orchestration.RunDataReceipt) = Unit

        override fun observeModelResult(
            runId: String,
            text: String,
            allowInlineToolCalls: Boolean,
        ): AgentModelObservationResult? = null
    }

    private class RecordingAuditStore : RemoteSendAuditSink, RemoteSendAuditLog {
        val events = mutableListOf<RemoteSendAuditEvent>()

        override fun record(event: RemoteSendAuditEvent) {
            events += event.redactedForAudit()
        }

        override fun recentRemoteSends(limit: Int): List<RemoteSendAuditEvent> =
            if (limit <= 0) emptyList() else events.take(limit)
    }

    private class RecordingRemoteRuntime : RemoteChatRuntime {
        val sentPrompts = mutableListOf<String>()

        override fun send(
            prompt: String,
            history: List<ChatMessage>,
            parameters: GenerationParameters,
            config: RemoteModelConfig,
            imageAttachments: List<ChatImageAttachment>,
        ): Flow<String> {
            sentPrompts += prompt
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
            sentPrompts += prompt
            return flowOf(RemoteChatEvent.TextDelta("远程回复"))
        }

        override fun stop() = Unit
    }

    private object NoopPendingStore : RemoteSendPendingStore {
        override fun savePendingRemoteSend(marker: PendingRemoteSendMarker) = Unit

        override fun consumePendingRemoteSend(): PendingRemoteSendMarker? = null

        override fun clearPendingRemoteSend() = Unit
    }
}

private fun unusedRouter(): AssistantRouter =
    Proxy.newProxyInstance(
        AssistantRouter::class.java.classLoader,
        arrayOf(AssistantRouter::class.java),
    ) { _, method, _ -> error("unexpected AssistantRouter call: ${method.name}") } as AssistantRouter
