package com.bytedance.zgx.pocketmind.background

import com.bytedance.zgx.pocketmind.data.ScheduledTaskDao
import com.bytedance.zgx.pocketmind.data.ScheduledTaskEntity
import java.util.UUID

class ScheduledTaskRepository(
    private val dao: ScheduledTaskDao,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val reminderIdFactory: () -> String = { "task-${UUID.randomUUID()}" },
) {
    fun createReminder(
        title: String,
        body: String,
        triggerAtMillis: Long,
    ): ScheduledTask {
        val now = clockMillis()
        val task = ScheduledTask(
            id = nextReminderTaskId(),
            type = ScheduledTaskType.Reminder,
            title = title,
            body = body,
            triggerAtMillis = triggerAtMillis,
            status = ScheduledTaskStatus.Scheduled,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        dao.upsert(task.toEntity())
        return task
    }

    private fun nextReminderTaskId(): String {
        repeat(MAX_TASK_ID_ATTEMPTS) {
            val candidate = reminderIdFactory().trim()
            if (candidate.isNotBlank() && dao.task(candidate) == null) {
                return candidate
            }
        }
        while (true) {
            val candidate = "task-${UUID.randomUUID()}"
            if (dao.task(candidate) == null) return candidate
        }
    }

    fun task(taskId: String): ScheduledTask? =
        dao.task(taskId)?.toModel()

    fun scheduled(limit: Int = 100): List<ScheduledTask> =
        dao.scheduled(limit).map { it.toModel() }

    fun scheduledOrRunning(limit: Int = 100): List<ScheduledTask> {
        if (limit <= 0) return emptyList()
        val scheduledTasks = scheduled(limit)
        val scheduledTaskIds = scheduledTasks.mapTo(mutableSetOf()) { it.id }
        val runningKnownTasks = listOfNotNull(periodicCheck())
            .filter { task ->
                task.status == ScheduledTaskStatus.Running && task.id !in scheduledTaskIds
            }
        return (scheduledTasks + runningKnownTasks)
            .sortedWith(compareBy<ScheduledTask> { it.triggerAtMillis }.thenBy { it.id })
            .take(limit)
    }

    fun scheduledReminders(limit: Int = 100): List<ScheduledTask> =
        dao.scheduledByType(ScheduledTaskType.Reminder.name, limit).map { it.toModel() }

    fun recent(limit: Int = 20): List<ScheduledTask> =
        if (limit <= 0) {
            emptyList()
        } else {
            dao.recent(limit).map { it.toModel() }
        }

    fun periodicCheck(): ScheduledTask? =
        dao.task(PeriodicCheckScheduleRequest.TASK_ID)?.toModel()

    fun periodicCheckPolicy(): PeriodicCheckPolicySummary =
        periodicCheck()?.toPeriodicCheckPolicySummary() ?: PeriodicCheckPolicySummary.disabled()

    fun createOrUpdatePeriodicCheck(request: PeriodicCheckScheduleRequest): ScheduledTask {
        val normalized = request.normalized()
        val now = clockMillis()
        val existing = periodicCheck()
        val task = ScheduledTask(
            id = PeriodicCheckScheduleRequest.TASK_ID,
            type = ScheduledTaskType.PeriodicCheck,
            title = PeriodicCheckScheduleRequest.TITLE,
            body = normalized.storageSummaryWithLastRun(existing?.lastRunSummary()),
            triggerAtMillis = existing?.triggerAtMillis?.coerceAtLeast(now) ?: now,
            status = ScheduledTaskStatus.Scheduled,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
        dao.upsert(task.toEntity())
        return task
    }

    fun recordPeriodicCheckRun(
        nextAllowedRunAtMillis: Long,
        summary: String,
        status: ScheduledTaskStatus = ScheduledTaskStatus.Scheduled,
    ): ScheduledTask {
        val now = clockMillis()
        val existing = periodicCheck()
        val request = existing?.periodicCheckRequest()
            ?: PeriodicCheckScheduleRequest()
        val task = ScheduledTask(
            id = PeriodicCheckScheduleRequest.TASK_ID,
            type = ScheduledTaskType.PeriodicCheck,
            title = PeriodicCheckScheduleRequest.TITLE,
            body = request.storageSummaryWithLastRun(summary),
            triggerAtMillis = nextAllowedRunAtMillis,
            status = status,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
        dao.upsert(task.toEntity())
        return task
    }

    fun disablePeriodicCheck(): ScheduledTask {
        val now = clockMillis()
        val existing = periodicCheck()
        val request = (existing?.periodicCheckRequest() ?: PeriodicCheckScheduleRequest())
            .copy(enabled = false)
        val task = ScheduledTask(
            id = PeriodicCheckScheduleRequest.TASK_ID,
            type = ScheduledTaskType.PeriodicCheck,
            title = PeriodicCheckScheduleRequest.TITLE,
            body = request.storageSummaryWithLastRun(existing?.lastRunSummary()),
            triggerAtMillis = now,
            status = ScheduledTaskStatus.Cancelled,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
        dao.upsert(task.toEntity())
        return task
    }

    fun markDelivered(taskId: String) {
        updateStatus(taskId, ScheduledTaskStatus.Delivered)
    }

    fun startReminderDelivery(taskId: String): ScheduledTask? {
        val existing = dao.task(taskId) ?: return null
        if (existing.type != ScheduledTaskType.Reminder.name) return null
        if (existing.status != ScheduledTaskStatus.Scheduled.name) return null
        val updatedAtMillis = clockMillis()
        val updatedRows = dao.markReminderRunningIfScheduled(taskId, updatedAtMillis)
        if (updatedRows <= 0) return null
        return existing.copy(
            status = ScheduledTaskStatus.Running.name,
            updatedAtMillis = updatedAtMillis,
        ).toModel()
    }

    fun markCancelled(taskId: String) {
        updateStatus(taskId, ScheduledTaskStatus.Cancelled)
    }

    fun cancelScheduled(taskId: String): Boolean =
        updateScheduledStatus(taskId, ScheduledTaskStatus.Cancelled)

    fun deleteScheduled(taskId: String): Boolean =
        updateScheduledStatus(taskId, ScheduledTaskStatus.Deleted)

    fun updateScheduledReminderTriggerAt(
        taskId: String,
        triggerAtMillis: Long,
        updatedAtMillis: Long = clockMillis(),
    ): Boolean =
        dao.updateReminderTriggerAtIfScheduled(
            taskId = taskId,
            triggerAtMillis = triggerAtMillis,
            updatedAtMillis = updatedAtMillis,
        ) > 0

    fun markRunning(taskId: String) {
        updateStatus(taskId, ScheduledTaskStatus.Running)
    }

    fun markFailed(taskId: String) {
        updateStatus(taskId, ScheduledTaskStatus.Failed)
    }

    private fun updateScheduledStatus(taskId: String, status: ScheduledTaskStatus): Boolean {
        val existing = dao.task(taskId) ?: return false
        if (existing.status != ScheduledTaskStatus.Scheduled.name) return false
        return dao.updateScheduledStatusIfScheduled(
            taskId = taskId,
            status = status.name,
            updatedAtMillis = clockMillis(),
        ) > 0
    }

    private fun updateStatus(taskId: String, status: ScheduledTaskStatus) {
        val existing = dao.task(taskId) ?: return
        dao.upsert(
            existing.copy(
                status = status.name,
                updatedAtMillis = clockMillis(),
            ),
        )
    }

    private fun ScheduledTask.toEntity(): ScheduledTaskEntity =
        ScheduledTaskEntity(
            id = id,
            type = type.name,
            title = title,
            body = body,
            triggerAtMillis = triggerAtMillis,
            status = status.name,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
        )

    private fun ScheduledTaskEntity.toModel(): ScheduledTask =
        ScheduledTask(
            id = id,
            type = ScheduledTaskType.valueOf(type),
            title = title,
            body = body,
            triggerAtMillis = triggerAtMillis,
            status = ScheduledTaskStatus.valueOf(status),
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
        )

    private fun ScheduledTask.toPeriodicCheckPolicySummary(): PeriodicCheckPolicySummary {
        val request = periodicCheckRequest()
        val activeRequest = if (status == ScheduledTaskStatus.Cancelled || status == ScheduledTaskStatus.Deleted) {
            request.copy(enabled = false).normalized()
        } else {
            request
        }
        return PeriodicCheckPolicySummary(
            request = activeRequest,
            taskStatus = status,
            nextAllowedRunAtMillis = triggerAtMillis,
            lastRunSummary = lastRunSummary(),
            updatedAtMillis = updatedAtMillis,
        )
    }

    private fun ScheduledTask.periodicCheckRequest(): PeriodicCheckScheduleRequest {
        val fallback = if (status == ScheduledTaskStatus.Cancelled || status == ScheduledTaskStatus.Deleted) {
            PeriodicCheckScheduleRequest(enabled = false)
        } else {
            PeriodicCheckScheduleRequest()
        }
        return PeriodicCheckScheduleRequest.fromStorageSummary(body, fallback)
    }

    private fun ScheduledTask.lastRunSummary(): String? =
        periodicCheckLastRunSummaryFromStorageSummary(body)

    private fun PeriodicCheckScheduleRequest.storageSummaryWithLastRun(lastRunSummary: String?): String =
        listOfNotNull(
            storageSummary(),
            lastRunSummary?.takeIf { it.isNotBlank() },
        ).joinToString(separator = ";")

    private companion object {
        const val MAX_TASK_ID_ATTEMPTS = 8
    }
}
