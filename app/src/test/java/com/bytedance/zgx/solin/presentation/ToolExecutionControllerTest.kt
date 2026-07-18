package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.orchestration.ActiveRunPlacementPermit
import com.bytedance.zgx.solin.orchestration.AgentObservationDecision
import com.bytedance.zgx.solin.orchestration.AgentObservationResult
import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.ModelDispatchState
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.orchestration.testBinding
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.succeeded
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolExecutionControllerTest {
    @Test
    fun remoteBindingWithUnknownObservationStartsNoContinuationRuntime() = runTest {
        val request = ToolRequest(id = "request-remote", toolName = "unknown_private_tool")
        val result = request.succeeded("private result")
        val observation = continuationObservation(
            runId = "run-remote",
            result = result,
            requiresLocalModel = false,
        )
        val router = observationRouter(observation)
        val uiState = MutableStateFlow(ChatUiState(inferenceMode = InferenceMode.Local))
        var localInvocations = 0
        var remoteInvocations = 0
        val controller = controller(
            router = router,
            result = result,
            uiState = uiState,
            activeRunPlacement = {
                permit("run-remote", RunPlacement.Remote, InferenceMode.Local)
            },
            continueAfterObservation = {
                if (uiState.value.inferenceMode == InferenceMode.Remote) {
                    remoteInvocations += 1
                } else {
                    localInvocations += 1
                }
            },
        )

        controller.executeToolRequestAfterRunIsExecuting(confirmation("run-remote", request), request)

        assertEquals(0, remoteInvocations)
        assertEquals(0, localInvocations)
        assertEquals(1, router.failModelGenerationCalls)
    }

    @Test
    fun localBindingContinuesLocallyEvenWhenPreferenceIsRemote() = runTest {
        val request = ToolRequest(id = "request-local", toolName = "local_tool")
        val result = request.succeeded(
            summary = "local result",
            data = mapOf("privacy" to MessagePrivacy.LocalOnly.name),
        )
        val observation = continuationObservation(
            runId = "run-local",
            result = result,
            requiresLocalModel = true,
        )
        val router = observationRouter(observation)
        val uiState = MutableStateFlow(ChatUiState(inferenceMode = InferenceMode.Remote))
        var localInvocations = 0
        val controller = controller(
            router = router,
            result = result,
            uiState = uiState,
            activeRunPlacement = {
                permit("run-local", RunPlacement.Local, InferenceMode.Remote)
            },
            continueAfterObservation = { localInvocations += 1 },
        )

        controller.executeToolRequestAfterRunIsExecuting(confirmation("run-local", request), request)

        assertEquals(1, localInvocations)
        assertEquals(0, router.failModelGenerationCalls)
    }

    private fun controller(
        router: ObservationRouter,
        result: ToolResult,
        uiState: MutableStateFlow<ChatUiState>,
        activeRunPlacement: (String) -> ActiveRunPlacementPermit?,
        continueAfterObservation: () -> Unit,
    ): ToolExecutionController = ToolExecutionController(
        assistantOrchestrator = router.router,
        actionExecutor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult = result
        },
        uiState = uiState,
        ioDispatcher = Dispatchers.Unconfined,
        isGenerationJobActive = { false },
        launchToolGenerationJob = { _, _ -> error("unexpected generation launch") },
        continueAfterToolObservation = { _, _, _, _, _ -> continueAfterObservation() },
        dispatchPersistenceWorkIfNeeded = { false },
        replaceActiveSessionMessages = { messages, _ ->
            uiState.update { state -> state.copy(messages = messages) }
        },
        persistActiveSessionFromUi = {},
        rebuildMemoryIndex = {},
        syncTaskStateMemories = {},
        loadAuditEvents = { emptyList() },
        loadAgentTraceRuns = { emptyList() },
        activeRunTimelineFor = { emptyList() },
        activePublicWebEvidenceFor = { emptyList() },
        loadBackgroundTasks = { emptyList() },
        loadBackgroundTaskHistory = { emptyList() },
        loadPeriodicCheckPolicy = { PeriodicCheckPolicySummary.disabled() },
        loadLongTermMemories = { emptyList() },
        activeSessionId = { "session-test" },
        activeRunPlacement = activeRunPlacement,
    )

    private fun continuationObservation(
        runId: String,
        result: ToolResult,
        requiresLocalModel: Boolean,
    ): AgentObservationResult = AgentObservationResult(
        run = AgentRun(
            id = runId,
            input = "test",
            state = AgentRunState.Observing,
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
        ),
        result = result,
        assistantMessage = result.summary,
        decision = AgentObservationDecision.ContinueWithModel(
            requiresLocalModel = requiresLocalModel,
            reason = result.summary,
        ),
        continuationPromptForModel = "continue",
        continuationRequiresLocalModel = requiresLocalModel,
        steps = emptyList(),
    )

    private fun confirmation(runId: String, request: ToolRequest): PendingAgentConfirmation =
        PendingAgentConfirmation(
            runId = runId,
            draft = ActionDraft(
                functionName = request.toolName,
                title = "Test",
                summary = "Test",
                parameters = emptyMap(),
            ),
            toolRequest = request,
            skillId = null,
            plannedByModel = true,
            fallbackReason = null,
        )

    private fun permit(
        runId: String,
        placement: RunPlacement,
        preference: InferenceMode,
    ): ActiveRunPlacementPermit = ActiveRunPlacementPermit.detached(
        testBinding(
            runId = runId,
            placement = placement,
            dispatchState = ModelDispatchState.Idle,
            attempt = 1,
        ).copy(preference = preference),
    )

    private fun observationRouter(observation: AgentObservationResult): ObservationRouter {
        val calls = ObservationRouter()
        calls.router = Proxy.newProxyInstance(
            AssistantRouter::class.java.classLoader,
            arrayOf(AssistantRouter::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "observeToolResult" -> observation
                "failModelGeneration" -> {
                    calls.failModelGenerationCalls += 1
                    null
                }
                "failStaleInFlightRuns" -> 0
                "close", "recordRemoteToolsExposed" -> Unit
                else -> null
            }
        } as AssistantRouter
        return calls
    }

    private class ObservationRouter {
        lateinit var router: AssistantRouter
        var failModelGenerationCalls: Int = 0
    }
}
