package com.bytedance.zgx.pocketmind.audit

import com.bytedance.zgx.pocketmind.data.ToolAuditDao
import com.bytedance.zgx.pocketmind.data.ToolAuditEventEntity
import com.bytedance.zgx.pocketmind.tool.ToolPermission

class ToolAuditRepository(
    private val dao: ToolAuditDao,
) : ToolAuditSink {
    override fun record(event: ToolAuditEvent) {
        dao.insert(event.toEntity())
    }

    fun recent(limit: Int = 100): List<ToolAuditEventEntity> =
        dao.recent(limit)

    private fun ToolAuditEvent.toEntity(): ToolAuditEventEntity =
        ToolAuditEventEntity(
            id = id,
            runId = runId,
            requestId = requestId,
            toolName = toolName,
            skillId = skillId,
            eventType = eventType.name,
            status = status?.name,
            riskLevel = riskLevel?.name,
            permissionsCsv = permissions
                .map(ToolPermission::name)
                .sorted()
                .joinToString(separator = ","),
            summary = sanitizedSummary(),
            createdAtMillis = createdAtMillis,
        )
}
