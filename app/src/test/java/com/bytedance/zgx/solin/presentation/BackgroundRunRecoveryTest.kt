package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.AuditEventSummary
import com.bytedance.zgx.solin.BackgroundTaskSummary
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.MemoryEvidenceUiSummary
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.device.background.BackgroundPhoneSession
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.succeeded
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** T2/T3: process-restored background work must never execute on the user's main display. */
class BackgroundRunRecoveryTest {
    private val runId = "background-recovery-${UUID.randomUUID()}"

    @Suppress("UNCHECKED_CAST")
    private fun markers(): MutableSet<String> = BackgroundPhoneSession::class.java
        .getDeclaredField("backgroundRunMarkers")
        .apply { isAccessible = true }
        .get(null) as MutableSet<String>

    @After
    fun clearOnlyThisTestsMarker() {
        synchronized(BackgroundPhoneSession.Companion) { markers().remove(runId) }
    }

    @Test
    fun restoredMarkerWithoutLiveSessionCancelsOnceWithoutExecutorOrRetry() = runTest {
        // Initialize only the companion; no Context, preferences, socket, Looper or live session.
        BackgroundPhoneSession.currentBinding()
        synchronized(BackgroundPhoneSession.Companion) { markers().add(runId) }
        var executorCalls = 0
        var observationCalls = 0
        var continuationCalls = 0
        val cancellations = mutableListOf<String>()
        val router = Proxy.newProxyInstance(
            AssistantRouter::class.java.classLoader,
            arrayOf(AssistantRouter::class.java),
        ) { _, method, args ->
            when (method.name) {
                "cancelRun" -> { cancellations += args[0] as String; null }
                "observeToolResult" -> { observationCalls++; null }
                "runState" -> if (cancellations.isEmpty()) AgentRunState.ExecutingTool else AgentRunState.Cancelled
                "failStaleInFlightRuns" -> 0
                "close", "recordRemoteToolsExposed" -> Unit
                else -> null
            }
        } as AssistantRouter
        val state = MutableStateFlow(ChatUiState(isBusy = true, isGenerating = false))
        val request = ToolRequest(
            id = "request-$runId",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )
        val controller = ToolExecutionController(
            assistantOrchestrator = router,
            actionExecutor = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult {
                    executorCalls++
                    return request.succeeded("unexpected main-screen execution")
                }
            },
            uiState = state,
            ioDispatcher = Dispatchers.Unconfined,
            isGenerationJobActive = { false },
            launchToolGenerationJob = { _, _ -> error("recovery must not schedule a retry") },
            continueAfterToolObservation = { _, _, _, _, _ -> continuationCalls++ },
            sessionHost = FakeSessionHost(state),
        )
        val confirmation = PendingAgentConfirmation(
            runId = runId,
            draft = ActionDraft(
                functionName = request.toolName,
                title = "Observe background screen",
                summary = "Restore test",
                parameters = request.arguments,
            ),
            toolRequest = request,
            skillId = null,
            plannedByModel = false,
            fallbackReason = null,
        )

        // User confirmation makes the request eligible at the ordinary action gate; only the
        // missing background session may refuse it. This prevents an authorization false positive.
        controller.executeToolRequestAfterRunIsExecuting(confirmation, request, userConfirmed = true)
        testScheduler.advanceUntilIdle()

        assertEquals("Never fall back to the main-display executor", 0, executorCalls)
        assertEquals("The failed background observation must not enter normal replanning", 0, observationCalls)
        assertEquals(listOf(runId), cancellations)
        assertEquals(0, continuationCalls)
        assertFalse(state.value.isBusy)
    }

    private class FakeSessionHost(private val state: MutableStateFlow<ChatUiState>) : PresentationSessionHost {
        override fun dispatchPersistenceWorkIfNeeded(work: () -> Unit) = false
        override fun replaceActiveSessionMessages(messages: List<ChatMessage>, persistNow: Boolean) {
            state.update { it.copy(messages = messages) }
        }
        override fun persistActiveSessionFromUi() = Unit
        override fun persistMessagesAndRebuildMemory(messages: List<ChatMessage>, memoryUserMessage: ChatMessage?) {
            replaceActiveSessionMessages(messages, true)
        }
        override fun activeSessionId() = "session-background-recovery"
        override fun rebuildMemoryIndex() = Unit
        override fun syncTaskStateMemories() = Unit
        override fun persistExplicitPreferenceMemory(message: ChatMessage) = false
        override fun handleExplicitPreferenceCommand(trimmed: String) = Unit
        override fun handleExplicitUserFactCommand(trimmed: String) = Unit
        override fun handleExplicitMemoryForgetCommand(trimmed: String) = Unit
        override fun loadLongTermMemories(): List<LongTermMemorySummary> = emptyList()
        override fun loadAuditEvents(): List<AuditEventSummary> = emptyList()
        override fun loadAgentTraceRuns(): List<AgentTraceRunUiSummary> = emptyList()
        override fun loadBackgroundTasks(): List<BackgroundTaskSummary> = emptyList()
        override fun loadBackgroundTaskHistory(): List<BackgroundTaskSummary> = emptyList()
        override fun loadPeriodicCheckPolicy() = PeriodicCheckPolicySummary.disabled()
        override fun activeRunTimelineFor(runId: String?): List<RunTimelineItemUiSummary> = emptyList()
        override fun activePublicWebEvidenceFor(runId: String?): List<PublicWebEvidencePack> = emptyList()
        override fun activeMemoryEvidenceFor(memoryHits: List<MemoryHit>, includePrivateLocalContext: Boolean): List<MemoryEvidenceUiSummary> = emptyList()
    }
}
