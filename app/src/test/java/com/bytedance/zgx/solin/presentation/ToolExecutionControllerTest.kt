package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.AuditEventSummary
import com.bytedance.zgx.solin.BackgroundTaskSummary
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.MemoryEvidenceUiSummary
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.safety.SafetyDecision
import com.bytedance.zgx.solin.safety.SafetyOutcome
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // Regression: a low-risk app-control continuation plan that the orchestrator already authorized
    // (safetyDecision=Allow, draft.requiresConfirmation=false via the continuation bypass) must
    // AUTO-EXECUTE. The auto-execute branch of handleNextToolPlan previously dispatched with
    // userConfirmed=false, so the execution-boundary re-authorizer (SafetyPolicyToolExecutionAuthorizer)
    // statelessly re-imposed confirmation on the Required tool (ui_wait) and rejected it — killing
    // in-app continuation on-device at its first action. handleNextToolPlan must pass userConfirmed=true
    // for the already-authorized plan so the boundary agrees and the tool actually runs.
    @Test
    fun handleNextToolPlanAutoExecutesBypassAuthorizedContinuationTool() = runTest {
        val request = ToolRequest(
            id = "continuation-wait",
            toolName = MobileActionFunctions.UI_WAIT,
            arguments = mapOf("timeoutMillis" to "500"),
            reason = "post-launch in-app continuation",
        )
        var executedRequestId: String? = null
        val executor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult {
                executedRequestId = request.id
                return request.succeeded(
                    summary = "waited",
                    data = mapOf("privacy" to MessagePrivacy.LocalOnly.name),
                )
            }
        }
        val uiState = MutableStateFlow(ChatUiState(inferenceMode = InferenceMode.Local))
        val jobs = mutableListOf<suspend () -> Unit>()
        val controller = controller(
            router = observationRouter(
                continuationObservation(
                    runId = "run-continuation",
                    result = request.succeeded("waited"),
                    requiresLocalModel = true,
                ),
            ),
            result = request.succeeded("waited"),
            uiState = uiState,
            activeRunPlacement = { permit("run-continuation", RunPlacement.Local, InferenceMode.Local) },
            continueAfterObservation = {},
            actionExecutor = executor,
            launchToolGenerationJob = { _, job -> jobs += job },
        )

        // Plan the orchestrator already downgraded to Allow (the low-risk continuation bypass fired
        // after the user's initial open_app confirmation): requiresConfirmation=false + outcome=Allow.
        val plan = AgentPlan.UseTool(
            request = request,
            draft = ActionDraft(
                functionName = MobileActionFunctions.UI_WAIT,
                title = "等待屏幕稳定",
                summary = "等待并重新观察当前屏幕",
                parameters = request.arguments,
                requiresConfirmation = false,
            ),
            plannedByModel = false,
            fallbackReason = "post-launch in-app continuation",
            safetyDecision = SafetyDecision(
                outcome = SafetyOutcome.Allow,
                reason = "Low-risk app control continuation was allowed after the user's initial confirmation.",
            ),
        )

        controller.handleNextToolPlan(
            runId = "run-continuation",
            plan = plan,
            pendingStatusText = "待确认",
        )
        // The auto-execute branch schedules the tool job; run it.
        jobs.forEach { it() }

        // The Required tool must actually execute (boundary re-authorizer agreed because the
        // controller passed userConfirmed=true for the already-authorized plan). Before the fix it
        // was Rejected before reaching the executor.
        assertEquals("continuation-wait", executedRequestId)
        assertEquals(null, uiState.value.pendingConfirmation)
    }

    @Test
    fun dismissingRunBoundConfirmationWithoutRequestCancelsTheRunInsteadOfAFabricatedRequest() = runTest {
        val router = RecordingRouter()
        val pending = runBoundConfirmationWithoutRequest()
        val uiState = MutableStateFlow(ChatUiState(pendingConfirmation = pending))
        val controller = controller(
            router = router.asObservationRouter(),
            result = ToolRequest(toolName = "unused").succeeded("unused"),
            uiState = uiState,
            activeRunPlacement = { null },
            continueAfterObservation = {},
        )

        controller.dismissAgentConfirmation(pending)

        assertEquals(listOf("cancelRun:run-missing-request"), router.runMutations)
        assertNull(uiState.value.pendingConfirmation)
    }

    @Test
    fun confirmingRunBoundConfirmationWithoutRequestFailsClosedWithoutExecuting() = runTest {
        val router = RecordingRouter()
        val pending = runBoundConfirmationWithoutRequest()
        val uiState = MutableStateFlow(ChatUiState(pendingConfirmation = pending))
        var executed = false
        val controller = controller(
            router = router.asObservationRouter(),
            result = ToolRequest(toolName = "unused").succeeded("unused"),
            uiState = uiState,
            activeRunPlacement = { null },
            continueAfterObservation = {},
            actionExecutor = object : ToolExecutor {
                override fun execute(request: ToolRequest): ToolResult {
                    executed = true
                    return request.succeeded("executed")
                }
            },
        )

        controller.confirmAgentConfirmation(pending)

        assertEquals(listOf("cancelRun:run-missing-request"), router.runMutations)
        assertFalse(executed)
        val state = uiState.value
        assertNull(state.pendingConfirmation)
        assertFalse(state.isBusy)
        assertEquals(MessagePrivacy.LocalOnly, state.messages.last().privacy)
    }

    @Test
    fun rejectingRunBoundConfirmationWithoutRequestCancelsTheRun() = runTest {
        val router = RecordingRouter()
        val pending = runBoundConfirmationWithoutRequest()
        val uiState = MutableStateFlow(ChatUiState(pendingConfirmation = pending))
        val controller = controller(
            router = router.asObservationRouter(),
            result = ToolRequest(toolName = "unused").succeeded("unused"),
            uiState = uiState,
            activeRunPlacement = { null },
            continueAfterObservation = {},
        )

        controller.rejectAgentConfirmationForMediaProjectionDenial(pending)

        assertEquals(listOf("cancelRun:run-missing-request"), router.runMutations)
        assertNull(uiState.value.pendingConfirmation)
    }

    @Test
    fun runBoundConfirmationWithoutRequestStaysPendingWhenRunCancellationFails() = runTest {
        val router = RecordingRouter(cancelRunFailure = IllegalStateException("db unavailable"))
        val pending = runBoundConfirmationWithoutRequest()
        val uiState = MutableStateFlow(ChatUiState(pendingConfirmation = pending))
        val controller = controller(
            router = router.asObservationRouter(),
            result = ToolRequest(toolName = "unused").succeeded("unused"),
            uiState = uiState,
            activeRunPlacement = { null },
            continueAfterObservation = {},
        )

        controller.dismissAgentConfirmation(pending)

        // The run is still awaiting confirmation, so the UI keeps the confirmation visible rather
        // than diverging from the run state.
        assertEquals(pending, uiState.value.pendingConfirmation)
    }

    @Test
    fun runlessDraftConfirmationStillBuildsItsOwnRequest() {
        val draft = ActionDraft(
            functionName = "local_tool",
            title = "Test",
            summary = "reason",
            parameters = mapOf("a" to "b"),
        )
        val runless = PendingAgentConfirmation(
            runId = null,
            draft = draft,
            toolRequest = null,
            skillId = null,
            plannedByModel = false,
            fallbackReason = null,
        )

        val request = requireNotNull(runless.resolvedToolRequest())
        assertEquals("local_tool", request.toolName)
        assertEquals(mapOf("a" to "b"), request.arguments)
        assertEquals("reason", request.reason)
        assertNull(runBoundConfirmationWithoutRequest().resolvedToolRequest())
        val bound = ToolRequest(id = "bound", toolName = "local_tool")
        assertEquals(bound, runless.copy(runId = "run", toolRequest = bound).resolvedToolRequest())
    }

    private fun runBoundConfirmationWithoutRequest(): PendingAgentConfirmation =
        PendingAgentConfirmation(
            runId = "run-missing-request",
            draft = ActionDraft(
                functionName = "local_tool",
                title = "Test",
                summary = "Test",
                parameters = emptyMap(),
            ),
            toolRequest = null,
            skillId = null,
            plannedByModel = true,
            fallbackReason = null,
        )

    private class RecordingRouter(private val cancelRunFailure: Throwable? = null) {
        val runMutations = mutableListOf<String>()

        fun asObservationRouter(): ObservationRouter {
            val calls = ObservationRouter()
            calls.router = Proxy.newProxyInstance(
                AssistantRouter::class.java.classLoader,
                arrayOf(AssistantRouter::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "cancelRun" -> {
                        cancelRunFailure?.let { throw it }
                        runMutations += "cancelRun:${args[0]}"
                        null
                    }
                    "confirmToolRequest", "cancelToolRequest", "failPendingToolRequest" -> {
                        runMutations += "${method.name}:${args[0]}:${args[1]}"
                        null
                    }
                    "failStaleInFlightRuns" -> 0
                    else -> null
                }
            } as AssistantRouter
            return calls
        }
    }

    private fun controller(
        router: ObservationRouter,
        result: ToolResult,
        uiState: MutableStateFlow<ChatUiState>,
        activeRunPlacement: (String) -> ActiveRunPlacementPermit?,
        continueAfterObservation: () -> Unit,
        actionExecutor: ToolExecutor = object : ToolExecutor {
            override fun execute(request: ToolRequest): ToolResult = result
        },
        launchToolGenerationJob: (String?, suspend () -> Unit) -> Unit = { _, _ ->
            error("unexpected generation launch")
        },
    ): ToolExecutionController = ToolExecutionController(
        assistantOrchestrator = router.router,
        actionExecutor = actionExecutor,
        uiState = uiState,
        ioDispatcher = Dispatchers.Unconfined,
        isGenerationJobActive = { false },
        launchToolGenerationJob = launchToolGenerationJob,
        continueAfterToolObservation = { _, _, _, _, _ -> continueAfterObservation() },
        sessionHost = FakeSessionHost(uiState),
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

    private class FakeSessionHost(
        private val uiState: MutableStateFlow<ChatUiState>,
    ) : PresentationSessionHost {
        override fun dispatchPersistenceWorkIfNeeded(work: () -> Unit): Boolean = false

        override fun replaceActiveSessionMessages(messages: List<ChatMessage>, persistNow: Boolean) {
            uiState.update { state -> state.copy(messages = messages) }
        }

        override fun persistActiveSessionFromUi() = Unit

        override fun persistMessagesAndRebuildMemory(
            messages: List<ChatMessage>,
            memoryUserMessage: ChatMessage?,
        ) {
            replaceActiveSessionMessages(messages, persistNow = true)
        }

        override fun activeSessionId(): String = "session-test"

        override fun rebuildMemoryIndex() = Unit

        override fun syncTaskStateMemories() = Unit

        override fun persistExplicitPreferenceMemory(message: ChatMessage): Boolean = false

        override fun handleExplicitPreferenceCommand(trimmed: String) = Unit

        override fun handleExplicitUserFactCommand(trimmed: String) = Unit

        override fun handleExplicitMemoryForgetCommand(trimmed: String) = Unit

        override fun loadLongTermMemories(): List<LongTermMemorySummary> = emptyList()

        override fun loadAuditEvents(): List<AuditEventSummary> = emptyList()

        override fun loadAgentTraceRuns(): List<AgentTraceRunUiSummary> = emptyList()

        override fun loadBackgroundTasks(): List<BackgroundTaskSummary> = emptyList()

        override fun loadBackgroundTaskHistory(): List<BackgroundTaskSummary> = emptyList()

        override fun loadPeriodicCheckPolicy(): PeriodicCheckPolicySummary = PeriodicCheckPolicySummary.disabled()

        override fun activeRunTimelineFor(runId: String?): List<RunTimelineItemUiSummary> = emptyList()

        override fun activePublicWebEvidenceFor(runId: String?): List<PublicWebEvidencePack> = emptyList()

        override fun activeMemoryEvidenceFor(
            memoryHits: List<MemoryHit>,
            includePrivateLocalContext: Boolean,
        ): List<MemoryEvidenceUiSummary> = emptyList()
    }
}
