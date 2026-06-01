package com.bytedance.zgx.pocketmind.audit

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.data.ToolAuditDao
import com.bytedance.zgx.pocketmind.data.ToolAuditEventEntity
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import com.bytedance.zgx.pocketmind.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX

class ToolAuditRepository(
    private val dao: ToolAuditDao,
) : ToolAuditSink, ToolAuditLog {
    override fun record(event: ToolAuditEvent) {
        dao.insert(event.toEntity())
    }

    override fun recentAuditEvents(limit: Int): List<ToolAuditRecord> =
        dao.recent(limit).map { entity -> entity.toRecord() }

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

    private fun ToolAuditEventEntity.toRecord(): ToolAuditRecord =
        ToolAuditRecord(
            id = id,
            runId = runId,
            requestId = requestId,
            toolName = toolName,
            skillId = skillId,
            eventType = eventType,
            status = status,
            riskLevel = riskLevel,
            permissions = permissionsCsv
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() },
            summary = displaySummary(),
            createdAtMillis = createdAtMillis,
        )

    private fun ToolAuditEventEntity.displaySummary(): String =
        when (eventType) {
            ToolAuditEventType.ToolPlanned.name ->
                "工具请求已记录，参数内容不在审计视图中展示。"

            ToolAuditEventType.ToolRejected.name ->
                "工具请求被安全策略拒绝。"

            ToolAuditEventType.ConfirmationRequested.name ->
                "已请求用户确认工具执行。"

            ToolAuditEventType.UserConfirmed.name ->
                "用户已确认工具执行。"

            ToolAuditEventType.UserCancelled.name ->
                "用户已取消工具执行。"

            ToolAuditEventType.ToolObserved.name ->
                toolObservedDisplaySummary()

            ToolAuditEventType.ToolRetryScheduled.name ->
                "工具失败后已安排有界重试。"

            else ->
                "工具审计事件已记录。"
        }

    private fun ToolAuditEventEntity.toolObservedDisplaySummary(): String =
        when (status) {
            ToolStatus.Succeeded.name -> if (summary.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX)) {
                "外部界面已打开，最终结果未验证。"
            } else if (toolName == MobileActionFunctions.SCHEDULE_REMINDER ||
                toolName == MobileActionFunctions.CANCEL_REMINDER
            ) {
                summary
            } else {
                "工具执行成功，结果详情不在审计视图中展示。"
            }
            ToolStatus.Failed.name -> "工具执行失败，错误详情不在审计视图中展示。"
            ToolStatus.Rejected.name -> "工具请求被拒绝。"
            ToolStatus.Cancelled.name -> "工具执行已取消。"
            else -> "工具执行结果已记录，详情不在审计视图中展示。"
        }
}
