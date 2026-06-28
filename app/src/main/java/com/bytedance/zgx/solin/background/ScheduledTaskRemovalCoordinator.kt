package com.bytedance.zgx.solin.background

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
            val task = repository.task(taskId)
                ?: throw IllegalArgumentException("Scheduled task not found: $taskId")
            if (task.status != ScheduledTaskStatus.Scheduled) {
                throw IllegalArgumentException("Scheduled task is not cancellable: $taskId (${task.status.name})")
            }
            if (!markRemoved(taskId)) {
                throw IllegalArgumentException("Scheduled task was not updated: $taskId")
            }
            cancelPlatformSchedule(task).getOrThrow()
        }

    private fun cancelPlatformSchedule(task: ScheduledTask): Result<Unit> =
        when (task.type) {
            ScheduledTaskType.Reminder -> cancelReminderAlarm(task)
            ScheduledTaskType.PeriodicCheck -> cancelPeriodicWork()
        }
}
