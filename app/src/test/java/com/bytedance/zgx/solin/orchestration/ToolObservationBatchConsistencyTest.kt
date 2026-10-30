package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.audit.InMemoryToolAuditSink
import com.bytedance.zgx.solin.audit.ToolAuditEventType
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.skill.SkillRequest
import com.bytedance.zgx.solin.tool.ToolError
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parallel public-evidence batch observation path must publish the same lifecycle side effects
 * as the single-tool path: per-tool terminal events, ToolCompleted telemetry, and the run
 * transition events (RunFailed / RunCancelled / TurnStarted) — and its audit must agree with the
 * event-bus copy.
 */
class ToolObservationBatchConsistencyTest {
    private val eventBus = RecordingSolinEventBus()
    private val telemetry = RecordingTelemetrySink()
    private val auditSink = InMemoryToolAuditSink()
    private val traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L })
    private val runtime = AgentLoopRuntime(
        memoryIndex = MemoryRepository(),
        actionPlanningRuntime = ScriptedActionRuntime(),
        traceStore = traceStore,
        auditSink = auditSink,
        eventBus = eventBus,
        telemetrySink = telemetry,
    )

    private val requests = listOf(
        ToolRequest(
            id = "call-a",
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to "A"),
            reason = "remote tool call",
        ),
        ToolRequest(
            id = "call-b",
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to "B"),
            reason = "remote tool call",
        ),
    )

    private fun plannedBatchRun(beforeBatch: (String) -> Unit = {}): String {
        val started = runtime.runOnce(
            input = "比较 A 和 B",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        beforeBatch(started.run.id)
        runtime.recordRemoteToolsExposed(
            runId = started.run.id,
            scope = RemoteToolScope.PublicEvidenceOnly,
            toolNames = setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val planned = runtime.observeModelToolRequests(started.run.id, requests)
        require(planned?.decision is AgentObservationDecision.PlanToolBatch)
        return started.run.id
    }

    private fun succeeded(request: ToolRequest) = ToolResult(
        requestId = request.id,
        status = ToolStatus.Succeeded,
        summary = "已搜索 ${request.arguments["query"]}",
        data = publicWebSearchResultData(query = request.arguments.getValue("query")),
    )

    private fun failed(request: ToolRequest) = ToolResult(
        requestId = request.id,
        status = ToolStatus.Failed,
        summary = "搜索失败",
        data = mapOf("toolName" to request.toolName),
        error = ToolError(ToolErrorCode.ExecutionFailed, "network down"),
        retryable = false,
    )

    @Test
    fun failedBatchPublishesRunFailedLikeTheSingleToolPath() {
        val runId = plannedBatchRun()

        val observed = runtime.observeToolResults(runId, requests.map(::failed))

        requireNotNull(observed)
        assertEquals(AgentRunState.Failed, observed.run.state)
        val runFailed = eventBus.ofType<SolinEvent.Agent.RunFailed>().filter { it.runId == runId }
        assertEquals(1, runFailed.size)
        assertEquals(AgentErrorCode.Unknown("tool_failure"), runFailed.single().code)
        assertEquals(2, eventBus.ofType<SolinEvent.Agent.ToolFailed>().count { it.runId == runId })
    }

    @Test
    fun cancelledBatchPublishesRunCancelled() {
        val runId = plannedBatchRun()

        val observed = runtime.observeToolResults(
            runId,
            listOf(
                succeeded(requests[0]),
                ToolResult(
                    requestId = requests[1].id,
                    status = ToolStatus.Cancelled,
                    summary = "已取消",
                    data = mapOf("toolName" to requests[1].toolName),
                ),
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.Cancelled, observed.run.state)
        assertEquals(1, eventBus.ofType<SolinEvent.Agent.RunCancelled>().count { it.runId == runId })
    }

    @Test
    fun successfulBatchStartsANewModelTurnAndRecordsToolCompletion() {
        val runId = plannedBatchRun()

        val observed = runtime.observeToolResults(runId, requests.map(::succeeded))

        requireNotNull(observed)
        assertEquals(AgentRunState.GeneratingAnswer, observed.run.state)
        val turns = eventBus.ofType<SolinEvent.Agent.TurnStarted>().filter { it.runId == runId }
        assertEquals(listOf(0, 1), turns.map { it.turnIndex })
        assertEquals(2, eventBus.ofType<SolinEvent.Agent.ToolSucceeded>().count { it.runId == runId })
        val completed = telemetry.samples.filterIsInstance<MetricSample.ToolCompleted>()
            .filter { it.runId == runId }
        assertEquals(setOf("call-a", "call-b"), completed.mapNotNull { it.requestId }.toSet())
        assertTrue(completed.all { it.succeeded })
    }

    @Test
    fun batchAuditSkillIdMatchesEventBusCopy() {
        val runId = plannedBatchRun { id ->
            traceStore.appendStep(
                id,
                AgentStep.SkillPlanned(
                    SkillRequest(id = "skill-request", skillId = "skill-x", arguments = emptyMap(), reason = "test"),
                ),
            )
        }

        runtime.observeToolResults(runId, requests.map(::succeeded))

        val audited = auditSink.events.filter { it.eventType == ToolAuditEventType.ToolObserved }
        val published = eventBus.ofType<SolinEvent.Audit.ToolAudited>()
            .filter { it.eventType == ToolAuditEventType.ToolObserved.name }
        assertEquals(2, audited.size)
        assertEquals(
            published.associate { it.requestId to it.skillId },
            audited.associate { it.requestId to it.skillId },
        )
        assertEquals("skill-x", audited.single { it.requestId == "call-a" }.skillId)
    }
}
