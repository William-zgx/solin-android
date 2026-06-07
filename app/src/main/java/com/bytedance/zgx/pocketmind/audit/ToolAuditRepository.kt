package com.bytedance.zgx.pocketmind.audit

import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.data.ToolAuditDao
import com.bytedance.zgx.pocketmind.data.ToolAuditEventEntity
import com.bytedance.zgx.pocketmind.tool.ToolPermission
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import com.bytedance.zgx.pocketmind.tool.EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX
import com.bytedance.zgx.pocketmind.tool.UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX

class ToolAuditRepository(
    private val dao: ToolAuditDao,
    private val maxStoredEvents: Int = DEFAULT_MAX_STORED_EVENTS,
) : ToolAuditSink, ToolAuditLog {
    override fun record(event: ToolAuditEvent) {
        dao.insert(event.toEntity())
        if (maxStoredEvents > 0) {
            dao.pruneToMostRecent(maxStoredEvents)
        }
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
            summary = persistedSummary(),
            createdAtMillis = createdAtMillis,
        )

    private fun ToolAuditEvent.persistedSummary(): String =
        when {
            eventType == ToolAuditEventType.ToolPlanned ->
                "Tool request planned."

            eventType == ToolAuditEventType.ConfirmationRequested ->
                "Tool confirmation requested."

            eventType == ToolAuditEventType.ToolObserved &&
                status == ToolStatus.Succeeded &&
                toolName.isReminderAuditTool() ->
                reminderMetadataFor(summary, toolName).joinToString(separator = "; ")

            eventType == ToolAuditEventType.ToolObserved &&
                summary.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX) ->
                UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX

            eventType == ToolAuditEventType.ToolObserved &&
                summary.startsWith(EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX) ->
                EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX

            eventType == ToolAuditEventType.ToolObserved ->
                TOOL_OBSERVATION_AUDIT_SUMMARY

            eventType == ToolAuditEventType.ExternalOutcomeConfirmed ->
                EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX

            eventType == ToolAuditEventType.ToolRetryScheduled ->
                "Tool retry scheduled."

            else -> sanitizedSummary()
        }

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

            ToolAuditEventType.ExternalOutcomeConfirmed.name ->
                "用户已确认外部动作结果。"

            ToolAuditEventType.ToolRetryScheduled.name ->
                "工具失败后已安排有界重试。"

            else ->
                "工具审计事件已记录。"
        }

    private fun ToolAuditEventEntity.toolObservedDisplaySummary(): String =
        when (status) {
            ToolStatus.Succeeded.name -> if (summary.startsWith(UNVERIFIED_EXTERNAL_LAUNCH_SUMMARY_PREFIX)) {
                "外部界面已打开，最终结果未验证。"
            } else if (summary.startsWith(EXTERNAL_OUTCOME_CONFIRMED_SUMMARY_PREFIX)) {
                "外部动作结果已由用户确认。"
            } else if (toolName == MobileActionFunctions.SCHEDULE_REMINDER ||
                toolName == MobileActionFunctions.CANCEL_REMINDER
            ) {
                reminderDisplaySummary()
            } else {
                "工具执行成功，结果详情不在审计视图中展示。"
            }
            ToolStatus.Failed.name -> "工具执行失败，错误详情不在审计视图中展示。"
            ToolStatus.Rejected.name -> "工具请求被拒绝。"
            ToolStatus.Cancelled.name -> "工具执行已取消。"
            else -> "工具执行结果已记录，详情不在审计视图中展示。"
        }

    private fun ToolAuditEventEntity.reminderDisplaySummary(): String {
        val metadata = reminderMetadataFor(summary, toolName)
        val prefix = if (toolName == MobileActionFunctions.SCHEDULE_REMINDER) {
            "后台提醒已安排"
        } else {
            "后台提醒已取消"
        }
        return if (metadata.isEmpty()) {
            "$prefix，任务 metadata 不在审计视图中展示。"
        } else {
            "$prefix (${metadata.joinToString(separator = "; ")})"
        }
    }

    private fun reminderMetadataFor(summary: String, toolName: String?): List<String> =
        metadataKeysFor(toolName).mapNotNull { key ->
            safeMetadataValue(summary, key)?.let { value -> "$key=$value" }
        }

    private fun safeMetadataValue(summary: String, key: String): String? {
        val pattern = Regex("""(?:^|[\s(;])${Regex.escape(key)}=([A-Za-z0-9_.:-]{1,120})(?=$|[\s;)])""")
        return pattern.find(summary)?.groupValues?.getOrNull(1)
    }

    private fun metadataKeysFor(toolName: String?): List<String> =
        if (toolName == MobileActionFunctions.SCHEDULE_REMINDER) {
            REMINDER_AUDIT_METADATA_KEYS
        } else {
            CANCEL_REMINDER_AUDIT_METADATA_KEYS
        }

    private fun String?.isReminderAuditTool(): Boolean =
        this == MobileActionFunctions.SCHEDULE_REMINDER ||
            this == MobileActionFunctions.CANCEL_REMINDER

    private companion object {
        val REMINDER_AUDIT_METADATA_KEYS = listOf(
            "taskId",
            "taskStatus",
            "triggerAtMillis",
            "recoveryToolName",
            "recoveryTaskId",
        )
        val CANCEL_REMINDER_AUDIT_METADATA_KEYS = listOf("taskId", "taskStatus")
        const val TOOL_OBSERVATION_AUDIT_SUMMARY = "Tool observation recorded."
        const val DEFAULT_MAX_STORED_EVENTS = 500
    }
}
