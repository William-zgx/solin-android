package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.audit.ToolAuditEvent
import com.bytedance.zgx.solin.audit.ToolAuditEventType
import com.bytedance.zgx.solin.audit.ToolAuditSink
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus

/**
 * Writes one tool audit event to the [ToolAuditSink] and publishes the identical event as
 * [SolinEvent.Audit.ToolAudited] (the direct record() stays until the bus path is verified). Risk
 * level and permissions come from the tool's spec; an unknown or null tool name records neither.
 * When no skill id is given, it is resolved from the run's trace.
 *
 * AUDIT: callers pass only value-free summaries; this class never adds payload of its own. Both
 * copies are built from the same arguments so they cannot drift.
 */
internal class ToolAuditRecorder(
    private val toolRegistry: ToolRegistry,
    private val auditSink: ToolAuditSink,
    private val eventBus: SolinEventBus,
    private val traceQueries: AgentTraceQueries,
) {
    fun request(
        runId: String,
        request: ToolRequest,
        eventType: ToolAuditEventType,
        status: ToolStatus?,
        summary: String,
        skillId: String? = traceQueries.skillIdForRequest(runId, request.id),
    ) = record(runId, request.id, request.toolName, skillId, eventType, status, summary)

    fun plan(
        runId: String,
        plan: AgentPlan.UseTool,
        eventType: ToolAuditEventType,
        status: ToolStatus?,
        summary: String,
    ) = request(runId, plan.request, eventType, status, summary, skillId = plan.skillRequest?.skillId)

    fun rejected(runId: String, result: ToolResult) =
        record(
            runId = runId,
            requestId = result.requestId,
            toolName = result.data["toolName"],
            skillId = result.requestId.takeIf { it.isNotBlank() }
                ?.let { requestId -> traceQueries.skillIdForRequest(runId, requestId) },
            eventType = ToolAuditEventType.ToolRejected,
            status = result.status,
            summary = result.summary,
        )

    fun record(
        runId: String,
        requestId: String,
        toolName: String?,
        skillId: String?,
        eventType: ToolAuditEventType,
        status: ToolStatus?,
        summary: String,
    ) {
        val spec = toolName?.let(toolRegistry::specFor)
        val riskLevel = spec?.riskLevel
        val permissions = spec?.permissions.orEmpty()
        auditSink.record(
            ToolAuditEvent(
                runId = runId,
                requestId = requestId,
                toolName = toolName,
                skillId = skillId,
                eventType = eventType,
                status = status,
                riskLevel = riskLevel,
                permissions = permissions,
                summary = summary,
            ),
        )
        eventBus.publish(
            SolinEvent.Audit.ToolAudited(
                runId = runId,
                requestId = requestId,
                toolName = toolName,
                skillId = skillId,
                eventType = eventType.name,
                status = status?.name,
                riskLevel = riskLevel?.name,
                permissionsCsv = permissions
                    .map { it.name }
                    .sorted()
                    .joinToString(separator = ","),
                summary = summary,
            ),
        )
    }
}
