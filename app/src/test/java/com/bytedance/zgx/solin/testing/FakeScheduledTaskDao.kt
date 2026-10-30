package com.bytedance.zgx.solin.testing

import com.bytedance.zgx.solin.background.ScheduledTaskStatus
import com.bytedance.zgx.solin.background.ScheduledTaskType
import com.bytedance.zgx.solin.data.ScheduledTaskDao
import com.bytedance.zgx.solin.data.ScheduledTaskEntity

/** In-memory [ScheduledTaskDao] mirroring the Room queries' filters, guards and ordering. */
class FakeScheduledTaskDao : ScheduledTaskDao {
    val tasks = linkedMapOf<String, ScheduledTaskEntity>()
    private val upsertedStatuses = mutableMapOf<String, MutableList<ScheduledTaskStatus>>()

    /** One-shot hook run before [updateScheduledStatusIfScheduled] reads the row (race injection). */
    var beforeUpdateScheduledStatusIfScheduled: (() -> Unit)? = null

    /** Every status written for [taskId], in order, through [upsert] or a guarded update. */
    fun statusHistory(taskId: String): List<ScheduledTaskStatus> =
        upsertedStatuses[taskId].orEmpty()

    override fun task(taskId: String): ScheduledTaskEntity? = tasks[taskId]

    override fun scheduled(limit: Int): List<ScheduledTaskEntity> =
        tasks.values
            .filter { it.status == ScheduledTaskStatus.Scheduled.name }
            .sortedBy { it.triggerAtMillis }
            .take(limit)

    override fun scheduledOrRunning(limit: Int): List<ScheduledTaskEntity> =
        tasks.values
            .filter { it.status == ScheduledTaskStatus.Scheduled.name || it.status == ScheduledTaskStatus.Running.name }
            .sortedWith(byTriggerThenId)
            .take(limit)

    override fun scheduledByType(type: String, limit: Int): List<ScheduledTaskEntity> =
        scheduledByTypeAfter(type, afterTriggerAtMillis = null, afterId = null, limit = limit)

    override fun scheduledByTypeAfter(
        type: String,
        afterTriggerAtMillis: Long?,
        afterId: String?,
        limit: Int,
    ): List<ScheduledTaskEntity> =
        tasks.values
            .filter { it.status == ScheduledTaskStatus.Scheduled.name && it.type == type }
            .filter {
                afterTriggerAtMillis == null ||
                    it.triggerAtMillis > afterTriggerAtMillis ||
                    (it.triggerAtMillis == afterTriggerAtMillis && it.id > afterId.orEmpty())
            }
            .sortedWith(byTriggerThenId)
            .take(limit)

    override fun recent(limit: Int): List<ScheduledTaskEntity> =
        tasks.values
            .sortedWith(compareByDescending<ScheduledTaskEntity> { it.updatedAtMillis }.thenBy { it.id })
            .take(limit)

    override fun markReminderRunningIfScheduled(taskId: String, updatedAtMillis: Long): Int =
        updateIf(taskId, ScheduledTaskType.Reminder, ScheduledTaskStatus.Scheduled) {
            it.copy(status = ScheduledTaskStatus.Running.name, updatedAtMillis = updatedAtMillis)
        }

    override fun updateReminderStatusIfRunning(taskId: String, status: String, updatedAtMillis: Long): Int =
        updateIf(taskId, ScheduledTaskType.Reminder, ScheduledTaskStatus.Running) {
            it.copy(status = status, updatedAtMillis = updatedAtMillis)
        }

    override fun markPeriodicCheckRunningIfScheduled(taskId: String, updatedAtMillis: Long): Int =
        updateIf(taskId, ScheduledTaskType.PeriodicCheck, ScheduledTaskStatus.Scheduled) {
            it.copy(status = ScheduledTaskStatus.Running.name, updatedAtMillis = updatedAtMillis)
        }

    override fun recordPeriodicCheckRunIfRunning(
        taskId: String,
        body: String,
        triggerAtMillis: Long,
        status: String,
        updatedAtMillis: Long,
    ): Int =
        updateIf(taskId, ScheduledTaskType.PeriodicCheck, ScheduledTaskStatus.Running) {
            it.copy(body = body, triggerAtMillis = triggerAtMillis, status = status, updatedAtMillis = updatedAtMillis)
        }

    override fun updatePeriodicCheckStatusIfRunning(taskId: String, status: String, updatedAtMillis: Long): Int =
        updateIf(taskId, ScheduledTaskType.PeriodicCheck, ScheduledTaskStatus.Running) {
            it.copy(status = status, updatedAtMillis = updatedAtMillis)
        }

    override fun markStaleRunningTaskScheduled(
        taskId: String,
        type: String,
        staleUpdatedAtMillis: Long,
        updatedAtMillis: Long,
    ): Int {
        val existing = tasks[taskId] ?: return 0
        if (!existing.isStaleRunning(type, staleUpdatedAtMillis)) return 0
        upsert(existing.copy(status = ScheduledTaskStatus.Scheduled.name, updatedAtMillis = updatedAtMillis))
        return 1
    }

    override fun markStaleRunningTasksScheduledByType(
        type: String,
        staleUpdatedAtMillis: Long,
        updatedAtMillis: Long,
    ): Int {
        val staleTasks = tasks.values.filter { it.isStaleRunning(type, staleUpdatedAtMillis) }
        staleTasks.forEach {
            upsert(it.copy(status = ScheduledTaskStatus.Scheduled.name, updatedAtMillis = updatedAtMillis))
        }
        return staleTasks.size
    }

    override fun updateScheduledStatusIfScheduled(taskId: String, status: String, updatedAtMillis: Long): Int {
        val beforeUpdate = beforeUpdateScheduledStatusIfScheduled
        beforeUpdateScheduledStatusIfScheduled = null
        beforeUpdate?.invoke()
        return updateIf(taskId, type = null, ScheduledTaskStatus.Scheduled) {
            it.copy(status = status, updatedAtMillis = updatedAtMillis)
        }
    }

    override fun updateReminderTriggerAtIfScheduled(taskId: String, triggerAtMillis: Long, updatedAtMillis: Long): Int =
        updateIf(taskId, ScheduledTaskType.Reminder, ScheduledTaskStatus.Scheduled) {
            it.copy(triggerAtMillis = triggerAtMillis, updatedAtMillis = updatedAtMillis)
        }

    override fun upsert(task: ScheduledTaskEntity) {
        tasks[task.id] = task
        upsertedStatuses.getOrPut(task.id) { mutableListOf() } += ScheduledTaskStatus.valueOf(task.status)
    }

    private fun updateIf(
        taskId: String,
        type: ScheduledTaskType?,
        status: ScheduledTaskStatus,
        transform: (ScheduledTaskEntity) -> ScheduledTaskEntity,
    ): Int {
        val existing = tasks[taskId] ?: return 0
        if (type != null && existing.type != type.name) return 0
        if (existing.status != status.name) return 0
        upsert(transform(existing))
        return 1
    }

    private fun ScheduledTaskEntity.isStaleRunning(type: String, staleUpdatedAtMillis: Long): Boolean =
        this.type == type && status == ScheduledTaskStatus.Running.name && updatedAtMillis <= staleUpdatedAtMillis

    private companion object {
        val byTriggerThenId = compareBy<ScheduledTaskEntity> { it.triggerAtMillis }.thenBy { it.id }
    }
}
