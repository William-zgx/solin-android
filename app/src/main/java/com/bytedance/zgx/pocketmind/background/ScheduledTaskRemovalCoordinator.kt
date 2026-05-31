package com.bytedance.zgx.pocketmind.background

internal class ScheduledTaskRemovalCoordinator(
    private val repository: ScheduledTaskRepository,
    private val cancelReminderAlarm: (ScheduledTask) -> Result<Unit>,
    private val cancelPeriodicWork: () -> Result<Unit>,
) {
    fun cancelScheduled(taskId: String): Result<Unit> =
        removeScheduled(taskId, repository::cancelScheduled)

    fun deleteScheduled(taskId: String): Result<Unit> =
        removeScheduled(taskId, repository::deleteScheduled)

    private fun removeScheduled(
        taskId: String,
        markRemoved: (String) -> Boolean,
    ): Result<Unit> =
        runCatching {
            val task = repository.task(taskId) ?: return@runCatching
            if (task.status != ScheduledTaskStatus.Scheduled) return@runCatching
            cancelPlatformSchedule(task).getOrThrow()
            markRemoved(taskId)
        }

    private fun cancelPlatformSchedule(task: ScheduledTask): Result<Unit> =
        when (task.type) {
            ScheduledTaskType.Reminder -> cancelReminderAlarm(task)
            ScheduledTaskType.PeriodicCheck -> cancelPeriodicWork()
        }
}
