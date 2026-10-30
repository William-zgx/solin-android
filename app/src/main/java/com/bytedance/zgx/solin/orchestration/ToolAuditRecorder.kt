package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.audit.ToolAuditEvent
import com.bytedance.zgx.solin.audit.ToolAuditEventType
import com.bytedance.zgx.solin.audit.ToolAuditSink
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolStatus

/**
 * Writes one tool audit event to the [ToolAuditSink] and publishes the identical event as
 * [SolinEvent.Audit.ToolAudited] (the direct record() stays until the bus path is verified). Risk level and permissions come from the tool's spec; an unknown or null tool name
 * records neither.
 *
 * AUDIT: callers pass only value-free summaries; this class never adds payload of its own. Both
 * copies are built from the same arguments so they cannot drift.
 */
internal class ToolAuditRecorder(
    private val toolRegistry: ToolRegistry,
    private val auditSink: ToolAuditSink,
    private val eventBus: SolinEventBus,
) {
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
