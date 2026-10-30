package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.audit.InMemoryToolAuditSink
import com.bytedance.zgx.solin.audit.ToolAuditEvent
import com.bytedance.zgx.solin.audit.ToolAuditEventType
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every tool audit row is dual-written to the audit sink and the event bus. The two copies must be
 * field-for-field identical (modulo the bus's string encoding) on every path that audits.
 */
class ToolAuditDualWriteParityTest {
    private val eventBus = RecordingSolinEventBus()
    private val auditSink = InMemoryToolAuditSink()

    private fun runtime(planningResult: ActionPlanningResult = clipboardPlan()) = AgentLoopRuntime(
        memoryIndex = MemoryRepository(),
        actionPlanningRuntime = ScriptedActionRuntime(planningResult = planningResult),
        traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
        auditSink = auditSink,
        eventBus = eventBus,
    )

    private fun clipboardPlan() = ActionPlanningResult(
        plan = ActionPlan(
            kind = ActionPlanKind.Draft,
            draft = ActionDraft(
                functionName = MobileActionFunctions.READ_CLIPBOARD,
                title = "读取剪贴板",
                summary = "将读取当前剪贴板文本。",
                parameters = emptyMap(),
                requiresConfirmation = true,
            ),
        ),
        usedModel = false,
        fallbackReason = "test fallback",
    )

    private fun ToolAuditEvent.asBusEvent(): List<Any?> = listOf(
        runId,
        requestId,
        toolName,
        skillId,
        eventType.name,
        status?.name,
        riskLevel?.name,
        permissions.map { it.name }.sorted().joinToString(","),
        summary,
    )

    private fun SolinEvent.Audit.ToolAudited.fields(): List<Any?> = listOf(
        runId,
        requestId,
        toolName,
        skillId,
        eventType,
        status,
        riskLevel,
        permissionsCsv,
        summary,
    )

    private fun assertParity(expectedTypes: Set<ToolAuditEventType>) {
        val sinkRows = auditSink.events.map { it.asBusEvent() }
        val busRows = eventBus.ofType<SolinEvent.Audit.ToolAudited>().map { it.fields() }
        assertEquals(sinkRows, busRows)
        assertTrue(
            "expected audit types $expectedTypes in ${auditSink.events.map { it.eventType }}",
            auditSink.events.map { it.eventType }.toSet().containsAll(expectedTypes),
        )
    }

    @Test
    fun plannedConfirmedObservedToolHasIdenticalAuditCopies() {
        val runtime = runtime()
        val planned = runtime.runOnce("读取剪贴板并总结", setOf(ModelCapability.Chat), memoryEnabled = false)
        val request = (planned.plan as AgentPlan.UseTool).request
        runtime.confirmToolRequest(planned.run.id, request.id)
        runtime.observeToolResult(
            planned.run.id,
            ToolResult(
                requestId = request.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "privacy" to MessagePrivacy.LocalOnly.name,
                    "requiresLocalModel" to "true",
                    "text" to "secret",
                    "truncated" to "false",
                ),
            ),
        )

        assertParity(
            setOf(
                ToolAuditEventType.ToolPlanned,
                ToolAuditEventType.ConfirmationRequested,
                ToolAuditEventType.UserConfirmed,
                ToolAuditEventType.ToolObserved,
            ),
        )
    }

    @Test
    fun cancelledToolHasIdenticalAuditCopies() {
        val runtime = runtime()
        val planned = runtime.runOnce("读取剪贴板并总结", setOf(ModelCapability.Chat), memoryEnabled = false)
        val request = (planned.plan as AgentPlan.UseTool).request

        runtime.cancelToolRequest(planned.run.id, request.id)

        assertParity(setOf(ToolAuditEventType.UserCancelled, ToolAuditEventType.ToolObserved))
    }

    @Test
    fun rejectedRemoteToolHasIdenticalAuditCopies() {
        val runtime = runtime(ActionPlanningResult(ActionPlan(ActionPlanKind.NoAction), false, null))
        val started = runtime.runOnce("普通问题", setOf(ModelCapability.Chat), memoryEnabled = false)

        runtime.observeModelToolRequest(
            started.run.id,
            ToolRequest(toolName = MobileActionFunctions.WEB_SEARCH, arguments = mapOf("query" to "x"), reason = "r"),
        )

        assertParity(setOf(ToolAuditEventType.ToolRejected))
    }

    @Test
    fun publicEvidenceBatchHasIdenticalAuditCopies() {
        val runtime = runtime(ActionPlanningResult(ActionPlan(ActionPlanKind.NoAction), false, null))
        val started = runtime.runOnce("比较 A 和 B", setOf(ModelCapability.Chat), memoryEnabled = false)
        runtime.recordRemoteToolsExposed(
            started.run.id,
            RemoteToolScope.PublicEvidenceOnly,
            setOf(MobileActionFunctions.WEB_SEARCH),
        )
        val requests = listOf("A", "B").map { query ->
            ToolRequest(
                id = "call-$query",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to query),
                reason = "remote tool call",
            )
        }
        runtime.observeModelToolRequests(started.run.id, requests)

        runtime.observeToolResults(
            started.run.id,
            requests.map { request ->
                ToolResult(
                    requestId = request.id,
                    status = ToolStatus.Succeeded,
                    summary = "ok",
                    data = publicWebSearchResultData(request.arguments.getValue("query")),
                )
            },
        )

        assertParity(setOf(ToolAuditEventType.ToolPlanned, ToolAuditEventType.ToolObserved))
    }
}
