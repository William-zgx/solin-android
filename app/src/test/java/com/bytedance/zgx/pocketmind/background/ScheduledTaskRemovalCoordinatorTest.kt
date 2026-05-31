package com.bytedance.zgx.pocketmind.background

import com.bytedance.zgx.pocketmind.data.ScheduledTaskDao
import com.bytedance.zgx.pocketmind.data.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskRemovalCoordinatorTest {
    @Test
    fun cancelRoutesReminderToAlarmAndPeriodicCheckToWorkManager() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 1_000L })
        val reminder = repository.createReminder(
            title = "喝水",
            body = "提醒我喝水",
            triggerAtMillis = 10_000L,
        )
        repository.createOrUpdatePeriodicCheck(PeriodicCheckScheduleRequest())
        val alarmCancellations = mutableListOf<String>()
        var workCancellations = 0
        val coordinator = ScheduledTaskRemovalCoordinator(
            repository = repository,
            cancelReminderAlarm = { task ->
                alarmCancellations += task.id
                Result.success(Unit)
            },
            cancelPeriodicWork = {
                workCancellations += 1
                Result.success(Unit)
            },
        )

        coordinator.cancelScheduled(reminder.id).getOrThrow()
        coordinator.deleteScheduled(PeriodicCheckScheduleRequest.TASK_ID).getOrThrow()

        assertEquals(listOf(reminder.id), alarmCancellations)
        assertEquals(1, workCancellations)
        assertEquals(ScheduledTaskStatus.Cancelled, repository.task(reminder.id)?.status)
        assertEquals(ScheduledTaskStatus.Deleted, repository.periodicCheck()?.status)
    }

    @Test
    fun nonScheduledTaskDoesNotCancelPlatformSchedule() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 1_000L })
        val reminder = repository.createReminder(
            title = "已触达",
            body = "不能再取消平台任务",
            triggerAtMillis = 10_000L,
        )
        repository.markDelivered(reminder.id)
        repository.createOrUpdatePeriodicCheck(PeriodicCheckScheduleRequest())
        repository.markRunning(PeriodicCheckScheduleRequest.TASK_ID)
        val alarmCancellations = mutableListOf<String>()
        var workCancellations = 0
        val coordinator = ScheduledTaskRemovalCoordinator(
            repository = repository,
            cancelReminderAlarm = { task ->
                alarmCancellations += task.id
                Result.success(Unit)
            },
            cancelPeriodicWork = {
                workCancellations += 1
                Result.success(Unit)
            },
        )

        coordinator.cancelScheduled(reminder.id).getOrThrow()
        coordinator.deleteScheduled(PeriodicCheckScheduleRequest.TASK_ID).getOrThrow()

        assertEquals(emptyList<String>(), alarmCancellations)
        assertEquals(0, workCancellations)
        assertEquals(ScheduledTaskStatus.Delivered, repository.task(reminder.id)?.status)
        assertEquals(ScheduledTaskStatus.Running, repository.periodicCheck()?.status)
    }

    @Test
    fun platformCancellationFailureLeavesTaskScheduled() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 1_000L })
        repository.createOrUpdatePeriodicCheck(PeriodicCheckScheduleRequest())
        val coordinator = ScheduledTaskRemovalCoordinator(
            repository = repository,
            cancelReminderAlarm = { Result.success(Unit) },
            cancelPeriodicWork = { Result.failure(IllegalStateException("work unavailable")) },
        )

        val result = coordinator.cancelScheduled(PeriodicCheckScheduleRequest.TASK_ID)

        assertTrue(result.isFailure)
        assertEquals(ScheduledTaskStatus.Scheduled, repository.periodicCheck()?.status)
    }

    private class FakeScheduledTaskDao : ScheduledTaskDao {
        private val tasks = linkedMapOf<String, ScheduledTaskEntity>()

        override fun task(taskId: String): ScheduledTaskEntity? =
            tasks[taskId]

        override fun scheduled(limit: Int): List<ScheduledTaskEntity> =
            tasks.values
                .filter { it.status == ScheduledTaskStatus.Scheduled.name }
                .sortedBy { it.triggerAtMillis }
                .take(limit)

        override fun scheduledByType(type: String, limit: Int): List<ScheduledTaskEntity> =
            tasks.values
                .filter { it.status == ScheduledTaskStatus.Scheduled.name && it.type == type }
                .sortedBy { it.triggerAtMillis }
                .take(limit)

        override fun upsert(task: ScheduledTaskEntity) {
            tasks[task.id] = task
        }
    }
}
