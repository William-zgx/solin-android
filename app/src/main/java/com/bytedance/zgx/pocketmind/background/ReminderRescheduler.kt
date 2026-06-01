package com.bytedance.zgx.pocketmind.background

class ReminderRescheduler(
    private val repository: ScheduledTaskRepository,
    private val scheduleAlarm: (ScheduledTask, Long) -> Result<Unit>,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val catchUpDelayMillis: Long = DEFAULT_CATCH_UP_DELAY_MILLIS,
    private val cleanupLegacyAlarm: (ScheduledTask) -> Result<Unit> = { Result.success(Unit) },
) {
    fun reschedulePendingReminders(limit: Int = DEFAULT_LIMIT): ReminderRescheduleReport {
        val now = clockMillis()
        val tasks = repository.scheduledReminders(limit)
        var scheduled = 0
        var failed = 0

        tasks.forEach { task ->
            val triggerAtMillis = if (task.triggerAtMillis <= now) {
                now + catchUpDelayMillis
            } else {
                task.triggerAtMillis
            }
            scheduleAlarm(task, triggerAtMillis)
                .onSuccess {
                    scheduled += 1
                    if (triggerAtMillis != task.triggerAtMillis) {
                        repository.updateScheduledReminderTriggerAt(
                            taskId = task.id,
                            triggerAtMillis = triggerAtMillis,
                            updatedAtMillis = now,
                        )
                    }
                }
                .onFailure {
                    failed += 1
                    repository.markScheduledFailed(task.id)
                }
        }
        tasks.forEach { task ->
            cleanupLegacyAlarm(task)
        }

        return ReminderRescheduleReport(
            total = tasks.size,
            scheduled = scheduled,
            failed = failed,
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val DEFAULT_CATCH_UP_DELAY_MILLIS = 1_000L
    }
}
