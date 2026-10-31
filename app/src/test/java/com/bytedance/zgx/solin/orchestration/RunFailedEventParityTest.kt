package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.skill.SkillManifest
import com.bytedance.zgx.solin.skill.SkillPlan
import com.bytedance.zgx.solin.skill.SkillRuntime
import com.bytedance.zgx.solin.testing.RecordingActionRuntime
import com.bytedance.zgx.solin.tool.ToolRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every path that moves a run into [AgentRunState.Failed] must publish exactly one
 * [SolinEvent.Agent.RunFailed], so RunStarted always has a terminal counterpart for telemetry
 * (RunFailures counter) and other bus subscribers.
 */
class RunFailedEventParityTest {
    private val eventBus = RecordingSolinEventBus()
    private val runtime = AgentLoopRuntime(
        memoryIndex = MemoryRepository(),
        actionPlanningRuntime = ScriptedActionRuntime(),
        traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        eventBus = eventBus,
    )

    private fun generatingRun(): String =
        runtime.runOnce(
            input = "普通问题",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        ).run.id

    private fun runFailedCount(runId: String): Int =
        eventBus.ofType<SolinEvent.Agent.RunFailed>().count { it.runId == runId }

    @Test
    fun remoteToolRequestWithoutExposureSnapshotPublishesRunFailed() {
        val runId = generatingRun()

        val observed = runtime.observeModelToolRequest(
            runId,
            ToolRequest(
                id = "call-unexposed",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "天气"),
                reason = "remote tool call",
            ),
        )

        assertEquals(AgentRunState.Failed, observed?.run?.state)
        assertEquals(1, runFailedCount(runId))
    }

    @Test
    fun emptyRemoteToolBatchPublishesRunFailed() {
        val runId = generatingRun()

        val observed = runtime.observeModelToolRequests(runId, emptyList())

        assertEquals(AgentRunState.Failed, observed?.run?.state)
        assertEquals(1, runFailedCount(runId))
    }

    @Test
    fun rejectedRemoteToolPlanPublishesRunFailed() {
        val runId = generatingRun()

        runtime.recordRemoteToolsExposed(
            runId = runId,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        // A remote request the planner rejects (missing required argument) fails the run from
        // GeneratingAnswer without ever reaching observeModelResult.
        val observed = runtime.observeModelToolRequest(
            runId,
            ToolRequest(
                id = "call-invalid",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = emptyMap(),
                reason = "remote tool call",
            ),
        )

        assertEquals(AgentRunState.Failed, observed?.run?.state)
        assertEquals(1, runFailedCount(runId))
    }

    @Test
    fun missingModelOnTheInitialPlanPublishesRunFailed() {
        val bus = RecordingSolinEventBus()
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
                usedModel = true,
                fallbackReason = null,
            ),
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = NoInitialSkillRuntime(),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            eventBus = bus,
        )

        val result = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            actionModelPath = "/verified/action-model.task",
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        assertEquals(1, bus.ofType<SolinEvent.Agent.RunFailed>().count { it.runId == result.run.id })
    }

    @Test
    fun exhaustedInitialToolStepBudgetPublishesRunFailed() {
        val bus = RecordingSolinEventBus()
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = RecordingActionRuntime(likelyAction = true),
            // Zero tool steps: any initial tool plan is already over budget.
            maxRunToolSteps = 0,
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            eventBus = bus,
        )

        val result = runtime.runOnce(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        assertEquals(AgentRunState.Failed, result.run.state)
        assertEquals(1, bus.ofType<SolinEvent.Agent.RunFailed>().count { it.runId == result.run.id })
    }

    /** Plans no initial skill, so model-backed planning reaches the MissingModel gate. */
    private class NoInitialSkillRuntime : SkillRuntime {
        private val delegate = BuiltInSkillRuntime()

        override fun manifests(): List<SkillManifest> = delegate.manifests()

        override fun plan(input: String): SkillPlan? = null

        override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? =
            delegate.plan(input, draft, request)
    }
}
