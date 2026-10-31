package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapabilityProfile
import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.audit.NoOpToolAuditSink
import com.bytedance.zgx.solin.evidence.NoOpEvidenceBlobStore
import com.bytedance.zgx.solin.memory.PerRunScratchpad
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillRunProgressor
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The remote tool-call path ends a turn with a tool call instead of a model result, so the wall-clock
 * deadline checked in [AgentLoopRuntime.observeModelResult] never runs for it. Both
 * [ModelToolRequestCoordinator.observeModelToolRequest] and its batch variant must therefore enforce
 * the deadline themselves; otherwise a remote model can keep issuing tool calls with no time bound.
 */
class ModelToolRequestDeadlineTest {
    private val request = ToolRequest(
        id = "call-remote",
        toolName = MobileActionFunctions.WEB_SEARCH,
        arguments = mapOf("query" to "天气"),
        reason = "remote tool call",
    )

    @Test
    fun anExpiredSingleRemoteToolRequestFailsTheRunOnTheWallClockBudget() {
        val fixture = Fixture()

        fixture.coordinator.observeModelToolRequest(fixture.runId, request)

        assertEquals(listOf(RUN_WALL_CLOCK_DEADLINE_EXCEEDED_REASON), fixture.failedBudgetReasons)
    }

    @Test
    fun anExpiredRemoteToolBatchFailsTheRunOnTheWallClockBudget() {
        val fixture = Fixture()

        fixture.coordinator.observeModelToolRequests(
            fixture.runId,
            listOf(request, request.copy(id = "call-remote-b")),
        )

        assertEquals(listOf(RUN_WALL_CLOCK_DEADLINE_EXCEEDED_REASON), fixture.failedBudgetReasons)
    }

    @Test
    fun aRunInsideItsDeadlineStillPlansTheSingleRemoteToolRequest() {
        // Control for the two tests above: the same coordinator and run, only the clock differs.
        val fixture = Fixture(nowMillis = { START_MILLIS + 1_000L })

        val observed = fixture.coordinator.observeModelToolRequest(fixture.runId, request)

        assertEquals(emptyList<String>(), fixture.failedBudgetReasons)
        requireNotNull(observed)
        assertEquals(AgentRunState.ExecutingTool, observed.run.state)
    }

    private class Fixture(
        nowMillis: () -> Long = { START_MILLIS + SolinConstants.AgentLoop.MAX_RUN_WALL_CLOCK_MILLIS },
    ) {
        val failedBudgetReasons = mutableListOf<String>()
        private val eventBus = RecordingSolinEventBus()
        private val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
        private val toolRegistry = ToolRegistry()
        private val traceQueries = AgentTraceQueries(traceStore)
        private val auditRecorder = ToolAuditRecorder(toolRegistry, NoOpToolAuditSink, eventBus, traceQueries)
        private val skillRuntime = BuiltInSkillRuntime()
        private val safetyPolicy = SafetyPolicy()
        private val runScopedState = RunScopedState()
        private val runEvents = RunTransitionEventPublisher(eventBus, runScopedState) { _, _ -> }
        private val scratchpad = PerRunScratchpad()
        private val publicEvidenceContinuation = PublicEvidenceContinuation(
            toolRegistry = toolRegistry,
            traceStore = traceStore,
            traceQueries = traceQueries,
            evidenceBlobStore = NoOpEvidenceBlobStore,
        )
        private val budget = AgentRunBudget(
            maxRunToolSteps = 4,
            maxObservationDecisions = 4,
            profilesByRunId = ConcurrentHashMap(),
            toolRequestsFor = { emptyList() },
            observationDecidedCount = { 0 },
            sessionPlanStore = null,
            // A run whose start time is known and whose working time has crossed the deadline.
            runStartedAtMillis = { START_MILLIS },
            nowMillis = nowMillis,
        )
        private val initialToolPlanner = InitialToolPlanner(
            toolRegistry = toolRegistry,
            skillRuntime = skillRuntime,
            safetyPolicy = safetyPolicy,
            actionPlanningRuntime = ScriptedActionRuntime(),
            traceStore = traceStore,
        )
        private val confirmationBypassPolicy = ConfirmationBypassPolicy(
            toolRegistry = toolRegistry,
            traceQueries = traceQueries,
            traceStore = traceStore,
            isCriticalOnlyConfirmationEnabled = { false },
        )
        private val pendingConfirmationSupport = PendingConfirmationSupport(
            toolRegistry = toolRegistry,
            traceStore = traceStore,
            traceQueries = traceQueries,
            initialToolPlanner = initialToolPlanner,
            valueFreeCompletedStepFrontiersByRunId = runScopedState.valueFreeCompletedStepFrontiersByRunId,
            host = object : PendingConfirmationSupport.Host {
                override fun clearEphemeralRunState(runId: String) = Unit
            },
            auditRecorder = auditRecorder,
        )
        private val toolPlanCoordinator = ToolPlanCoordinator(
            toolRegistry = toolRegistry,
            skillRuntime = skillRuntime,
            skillProgressor = SkillRunProgressor(toolRegistry = toolRegistry),
            observationReplanner = NoOpAgentObservationReplanner,
            actionPlanningRuntime = ScriptedActionRuntime(),
            traceStore = traceStore,
            traceQueries = traceQueries,
            safetyPolicy = safetyPolicy,
            runBudget = budget,
            initialToolPlanner = initialToolPlanner,
            confirmationBypassPolicy = confirmationBypassPolicy,
            host = object : ToolPlanCoordinator.Host {
                override fun installedCapabilityProfiles(runId: String): List<ModelCapabilityProfile> = emptyList()

                override fun remoteGuiDrivingEnabled(runId: String): Boolean = false

                override fun runHasUserConfirmedStep(runId: String): Boolean = false

                override fun valueFreeCompletedStepFrontiers(runId: String): Set<String> = emptySet()
            },
            auditRecorder = auditRecorder,
        )
        private val toolObservationCoordinator = ToolObservationCoordinator(
            toolRegistry = toolRegistry,
            auditRecorder = auditRecorder,
            runEvents = runEvents,
            eventBus = eventBus,
            traceStore = traceStore,
            traceQueries = traceQueries,
            toolPlanCoordinator = toolPlanCoordinator,
            runBudget = budget,
            maxToolRetryAttempts = 0,
            confirmationBypassPolicy = confirmationBypassPolicy,
            pendingConfirmationSupport = pendingConfirmationSupport,
            publicEvidenceContinuation = publicEvidenceContinuation,
            continuationBuilder = ToolObservationContinuationBuilder(
                toolRegistry = toolRegistry,
                toolPlanCoordinator = toolPlanCoordinator,
                initialToolPlanner = initialToolPlanner,
                skillProgressor = SkillRunProgressor(toolRegistry = toolRegistry),
                traceQueries = traceQueries,
                publicEvidenceContinuation = publicEvidenceContinuation,
                scratchpad = scratchpad,
                evidenceBlobStore = NoOpEvidenceBlobStore,
            ),
            host = object : ToolObservationCoordinator.Host {
                override fun failObservationBudget(
                    runId: String,
                    result: ToolResult,
                    assistantMessage: String,
                    reason: String,
                ): AgentObservationResult? = null

                override fun parkForAskUserIfNeeded(runId: String, plan: AgentPlan.UseTool): Boolean = false

                override fun parkForTakeOver(runId: String, prompt: String?, result: ToolResult) = Unit

                override fun shouldAwaitExternalOutcomeConfirmation(
                    runId: String,
                    request: ToolRequest,
                    result: ToolResult,
                ): Boolean = false

                override fun clearEphemeralRunState(runId: String) = Unit

                override fun safeRecordTelemetry(sample: MetricSample, label: String) = Unit

                override fun recordStepLatency(stepType: String, runId: String?) = Unit

                override fun failInitialPlanBudget(run: AgentRun, request: ToolRequest): AgentLoopResult =
                    error("the initial-plan budget is not reached on the remote tool-request path")

                override fun addScratchpadNote(runId: String, noteContent: String) = Unit

                override fun markToolDispatchStarted(requestId: String) = Unit

                override fun consumeToolDispatchLatencyMs(requestId: String): Long = 0L

                override fun setRemoteToolScope(runId: String, scope: RemoteToolScope) = Unit
            },
        )

        val runId: String = traceStore.createRun("普通问题").let { run ->
            traceStore.updateState(run.id, AgentRunState.GeneratingAnswer)
            run.id
        }

        val coordinator = ModelToolRequestCoordinator(
            toolRegistry = toolRegistry,
            skillRuntime = skillRuntime,
            safetyPolicy = safetyPolicy,
            traceStore = traceStore,
            traceQueries = traceQueries,
            toolObservationCoordinator = toolObservationCoordinator,
            runBudget = budget,
            initialToolPlanner = initialToolPlanner,
            confirmationBypassPolicy = confirmationBypassPolicy,
            pendingConfirmationSupport = pendingConfirmationSupport,
            host = object : ModelToolRequestCoordinator.Host {
                override fun rejectRemoteToolIfNotExposedInCurrentScope(
                    runId: String,
                    request: ToolRequest,
                ): ToolResult? = null

                override fun failRunBudget(runId: String, reason: String): AgentModelObservationResult? {
                    failedBudgetReasons += reason
                    return null
                }

                override fun publishRunFailed(runId: String, code: AgentErrorCode, message: String) = Unit

                override fun parkForAskUserIfNeeded(runId: String, plan: AgentPlan.UseTool): Boolean = false

                override fun clearEphemeralRunState(runId: String) = Unit

                override fun markStepStart(runId: String, stepType: String) = Unit
            },
            auditRecorder = auditRecorder,
        )
    }
}

private const val START_MILLIS = 1_000L
