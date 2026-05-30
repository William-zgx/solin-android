package com.bytedance.zgx.pocketmind.background

import com.bytedance.zgx.pocketmind.data.ScheduledTaskDao
import com.bytedance.zgx.pocketmind.data.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledTaskRepositoryTest {
    @Test
    fun createReminderPersistsScheduledTaskAndStatusTransitions() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })

        val task = repository.createReminder(
            title = "喝水",
            body = "提醒我 15 分钟后喝水",
            triggerAtMillis = 901_000L,
        )

        assertEquals(ScheduledTaskType.Reminder, task.type)
        assertEquals(ScheduledTaskStatus.Scheduled, repository.task(task.id)?.status)
        assertEquals(listOf(task.id), repository.scheduled().map { it.id })

        repository.markDelivered(task.id)

        assertEquals(ScheduledTaskStatus.Delivered, repository.task(task.id)?.status)
        assertEquals(emptyList<ScheduledTask>(), repository.scheduled())
    }

    @Test
    fun missingTaskStatusUpdateIsNoop() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 1_000L })

        repository.markCancelled("missing")

        assertNull(repository.task("missing"))
    }

    @Test
    fun scheduledRemindersOnlyReturnsPendingReminderTasks() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        dao.upsert(
            entity(
                id = "periodic",
                type = ScheduledTaskType.PeriodicCheck,
                status = ScheduledTaskStatus.Scheduled,
                triggerAtMillis = 2_000L,
            ),
        )
        dao.upsert(
            entity(
                id = "delivered-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Delivered,
                triggerAtMillis = 3_000L,
            ),
        )
        dao.upsert(
            entity(
                id = "pending-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Scheduled,
                triggerAtMillis = 4_000L,
            ),
        )

        assertEquals(listOf("pending-reminder"), repository.scheduledReminders().map { it.id })
    }

    @Test
    fun reschedulerSchedulesFutureAndCatchUpPastDueReminders() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        val pastDue = repository.createReminder(
            title = "补触发",
            body = "开机后补触发",
            triggerAtMillis = 1_500L,
        )
        val future = repository.createReminder(
            title = "未来提醒",
            body = "保持原触发时间",
            triggerAtMillis = 5_000L,
        )
        val scheduledAlarms = linkedMapOf<String, Long>()
        val rescheduler = ReminderRescheduler(
            repository = repository,
            scheduleAlarm = { task, triggerAtMillis ->
                scheduledAlarms[task.id] = triggerAtMillis
                Result.success(Unit)
            },
            clockMillis = { 2_000L },
            catchUpDelayMillis = 250L,
        )

        val report = rescheduler.reschedulePendingReminders()

        assertEquals(ReminderRescheduleReport(total = 2, scheduled = 2, failed = 0), report)
        assertEquals(2_250L, scheduledAlarms[pastDue.id])
        assertEquals(5_000L, scheduledAlarms[future.id])
    }

    @Test
    fun reschedulerMarksTaskFailedWhenAlarmSchedulingFails() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        val task = repository.createReminder(
            title = "失败提醒",
            body = "无法重排",
            triggerAtMillis = 5_000L,
        )
        val rescheduler = ReminderRescheduler(
            repository = repository,
            scheduleAlarm = { _, _ -> Result.failure(IllegalStateException("alarm unavailable")) },
            clockMillis = { 2_000L },
        )

        val report = rescheduler.reschedulePendingReminders()

        assertEquals(ReminderRescheduleReport(total = 1, scheduled = 0, failed = 1), report)
        assertEquals(ScheduledTaskStatus.Failed, repository.task(task.id)?.status)
        assertEquals(emptyList<ScheduledTask>(), repository.scheduledReminders())
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

    private fun entity(
        id: String,
        type: ScheduledTaskType,
        status: ScheduledTaskStatus,
        triggerAtMillis: Long,
    ): ScheduledTaskEntity =
        ScheduledTaskEntity(
            id = id,
            type = type.name,
            title = id,
            body = "",
            triggerAtMillis = triggerAtMillis,
            status = status.name,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )
}
