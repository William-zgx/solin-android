package com.bytedance.zgx.pocketmind.background

import com.bytedance.zgx.pocketmind.data.ScheduledTaskDao
import com.bytedance.zgx.pocketmind.data.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun createReminderDoesNotOverwriteSameMillisecondSameTitleTasks() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(
            dao = dao,
            clockMillis = { 1_000L },
            reminderIdFactory = { "task-fixed" },
        )

        val first = repository.createReminder(
            title = "喝水",
            body = "第一次",
            triggerAtMillis = 2_000L,
        )
        val second = repository.createReminder(
            title = "喝水",
            body = "第二次",
            triggerAtMillis = 3_000L,
        )

        assertNotEquals(first.id, second.id)
        assertEquals(
            listOf("第一次", "第二次"),
            repository.scheduled().sortedBy { it.triggerAtMillis }.map { it.body },
        )
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
    fun recentReturnsAllStatusesByUpdatedTime() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        dao.upsert(
            entity(
                id = "old-delivered",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Delivered,
                updatedAtMillis = 2_000L,
            ),
        )
        dao.upsert(
            entity(
                id = "new-failed",
                type = ScheduledTaskType.PeriodicCheck,
                status = ScheduledTaskStatus.Failed,
                updatedAtMillis = 3_000L,
            ),
        )
        dao.upsert(
            entity(
                id = "scheduled",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Scheduled,
                updatedAtMillis = 1_000L,
            ),
        )

        assertEquals(listOf("new-failed", "old-delivered"), repository.recent(limit = 2).map { it.id })
        assertTrue(repository.recent(limit = 0).isEmpty())
    }

    @Test
    fun scheduledOrRunningIncludesKnownRunningPeriodicTask() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        dao.upsert(
            entity(
                id = PeriodicCheckScheduleRequest.TASK_ID,
                type = ScheduledTaskType.PeriodicCheck,
                status = ScheduledTaskStatus.Running,
                triggerAtMillis = 2_000L,
            ),
        )
        dao.upsert(
            entity(
                id = "scheduled-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Scheduled,
                triggerAtMillis = 3_000L,
            ),
        )

        assertEquals(
            listOf(PeriodicCheckScheduleRequest.TASK_ID, "scheduled-reminder"),
            repository.scheduledOrRunning().map { it.id },
        )
    }

    @Test
    fun scheduledOrRunningIncludesRunningReminderTasks() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        dao.upsert(
            entity(
                id = "running-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Running,
                triggerAtMillis = 2_000L,
            ),
        )
        dao.upsert(
            entity(
                id = "scheduled-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Scheduled,
                triggerAtMillis = 3_000L,
            ),
        )

        assertEquals(
            listOf("running-reminder", "scheduled-reminder"),
            repository.scheduledOrRunning().map { it.id },
        )
    }

    @Test
    fun cancellingScheduledTaskRemovesItFromRunningLists() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 1_000L })
        val task = repository.createReminder(
            title = "喝水",
            body = "提醒我喝水",
            triggerAtMillis = 5_000L,
        )

        repository.markCancelled(task.id)

        assertEquals(ScheduledTaskStatus.Cancelled, repository.task(task.id)?.status)
        assertEquals(emptyList<ScheduledTask>(), repository.scheduled())
        assertEquals(emptyList<ScheduledTask>(), repository.scheduledReminders())
    }

    @Test
    fun cancelScheduledRejectsCompletedTasks() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        dao.upsert(
            entity(
                id = "delivered-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Delivered,
                triggerAtMillis = 5_000L,
            ),
        )

        assertFalse(repository.cancelScheduled("delivered-reminder"))
        assertEquals(ScheduledTaskStatus.Delivered, repository.task("delivered-reminder")?.status)
    }

    @Test
    fun startReminderDeliveryOnlyStartsScheduledReminders() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 2_000L })
        val scheduled = repository.createReminder(
            title = "投递",
            body = "使用持久化正文",
            triggerAtMillis = 5_000L,
        )
        dao.upsert(
            entity(
                id = "periodic",
                type = ScheduledTaskType.PeriodicCheck,
                status = ScheduledTaskStatus.Scheduled,
            ),
        )
        dao.upsert(
            entity(
                id = "cancelled",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Cancelled,
            ),
        )

        val started = repository.startReminderDelivery(scheduled.id)

        assertEquals(scheduled.id, started?.id)
        assertEquals("投递", started?.title)
        assertEquals("使用持久化正文", started?.body)
        assertEquals(ScheduledTaskStatus.Running, started?.status)
        assertEquals(ScheduledTaskStatus.Running, repository.task(scheduled.id)?.status)
        assertEquals(null, repository.startReminderDelivery("periodic"))
        assertEquals(null, repository.startReminderDelivery("cancelled"))
        assertEquals(null, repository.startReminderDelivery("missing"))
        assertEquals(ScheduledTaskStatus.Scheduled, repository.task("periodic")?.status)
        assertEquals(ScheduledTaskStatus.Cancelled, repository.task("cancelled")?.status)
    }

    @Test
    fun scheduledOrRunningReturnsScheduledTasksAndKnownRunningPeriodicCheck() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        dao.upsert(
            entity(
                id = PeriodicCheckScheduleRequest.TASK_ID,
                type = ScheduledTaskType.PeriodicCheck,
                status = ScheduledTaskStatus.Running,
                triggerAtMillis = 2_000L,
            ),
        )
        dao.upsert(
            entity(
                id = "scheduled-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Scheduled,
                triggerAtMillis = 3_000L,
            ),
        )
        dao.upsert(
            entity(
                id = "delivered-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Delivered,
                triggerAtMillis = 1_000L,
            ),
        )

        assertEquals(
            listOf(PeriodicCheckScheduleRequest.TASK_ID, "scheduled-reminder"),
            repository.scheduledOrRunning().map { it.id },
        )
        assertEquals(
            listOf(PeriodicCheckScheduleRequest.TASK_ID),
            repository.scheduledOrRunning(limit = 1).map { it.id },
        )
    }

    @Test
    fun cancelAndDeleteOnlyApplyToScheduledTasks() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 2_000L })
        val scheduledToCancel = repository.createReminder(
            title = "取消",
            body = "只取消待调度任务",
            triggerAtMillis = 10_000L,
        )
        val scheduledToDelete = repository.createReminder(
            title = "删除",
            body = "只删除待调度任务",
            triggerAtMillis = 20_000L,
        )
        val delivered = repository.createReminder(
            title = "已触达",
            body = "不能被 Scheduled-only API 改写",
            triggerAtMillis = 30_000L,
        )
        repository.markDelivered(delivered.id)

        assertTrue(repository.cancelScheduled(scheduledToCancel.id))
        assertTrue(repository.deleteScheduled(scheduledToDelete.id))
        assertFalse(repository.cancelScheduled(delivered.id))
        assertFalse(repository.deleteScheduled("missing"))

        assertEquals(ScheduledTaskStatus.Cancelled, repository.task(scheduledToCancel.id)?.status)
        assertEquals(ScheduledTaskStatus.Deleted, repository.task(scheduledToDelete.id)?.status)
        assertEquals(ScheduledTaskStatus.Delivered, repository.task(delivered.id)?.status)
        assertEquals(emptyList<ScheduledTask>(), repository.scheduled())
    }

    @Test
    fun cancelScheduledDoesNotOverwriteRunningTaskWhenConditionalUpdateLosesRace() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 2_000L })
        val task = repository.createReminder(
            title = "取消竞争",
            body = "旧快照不能覆盖投递中状态",
            triggerAtMillis = 10_000L,
        )
        dao.beforeUpdateScheduledStatusIfScheduled = {
            dao.markReminderRunningIfScheduled(task.id, updatedAtMillis = 1_500L)
        }

        assertFalse(repository.cancelScheduled(task.id))
        assertEquals(ScheduledTaskStatus.Running, repository.task(task.id)?.status)
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
        val events = mutableListOf<String>()
        val rescheduler = ReminderRescheduler(
            repository = repository,
            scheduleAlarm = { task, triggerAtMillis ->
                events += "schedule:${task.id}"
                scheduledAlarms[task.id] = triggerAtMillis
                Result.success(Unit)
            },
            cleanupLegacyAlarm = { task ->
                events += "cleanup:${task.id}"
                Result.success(Unit)
            },
            clockMillis = { 2_000L },
            catchUpDelayMillis = 250L,
        )

        val report = rescheduler.reschedulePendingReminders()

        assertEquals(ReminderRescheduleReport(total = 2, scheduled = 2, failed = 0), report)
        assertEquals(2_250L, scheduledAlarms[pastDue.id])
        assertEquals(5_000L, scheduledAlarms[future.id])
        assertEquals(2_250L, repository.task(pastDue.id)?.triggerAtMillis)
        assertEquals(5_000L, repository.task(future.id)?.triggerAtMillis)
        assertEquals(2_000L, repository.task(pastDue.id)?.updatedAtMillis)
        assertEquals(
            listOf(
                "schedule:${pastDue.id}",
                "schedule:${future.id}",
                "cleanup:${pastDue.id}",
                "cleanup:${future.id}",
            ),
            events,
        )
    }

    @Test
    fun reschedulerMarksTaskFailedWhenAlarmSchedulingFails() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        val task = repository.createReminder(
            title = "失败提醒",
            body = "无法重排",
            triggerAtMillis = 1_500L,
        )
        val rescheduler = ReminderRescheduler(
            repository = repository,
            scheduleAlarm = { _, _ -> Result.failure(IllegalStateException("alarm unavailable")) },
            clockMillis = { 2_000L },
            catchUpDelayMillis = 250L,
        )

        val report = rescheduler.reschedulePendingReminders()

        assertEquals(ReminderRescheduleReport(total = 1, scheduled = 0, failed = 1), report)
        assertEquals(ScheduledTaskStatus.Failed, repository.task(task.id)?.status)
        assertEquals(1_500L, repository.task(task.id)?.triggerAtMillis)
        assertEquals(emptyList<ScheduledTask>(), repository.scheduledReminders())
    }

    @Test
    fun reschedulerFailureDoesNotOverwriteReminderCancelledAfterSnapshot() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        val task = repository.createReminder(
            title = "取消后失败",
            body = "旧快照不能覆盖取消",
            triggerAtMillis = 1_500L,
        )
        val rescheduler = ReminderRescheduler(
            repository = repository,
            scheduleAlarm = { scheduledTask, _ ->
                repository.cancelScheduled(scheduledTask.id)
                Result.failure(IllegalStateException("alarm unavailable"))
            },
            clockMillis = { 2_000L },
            catchUpDelayMillis = 250L,
        )

        val report = rescheduler.reschedulePendingReminders()

        assertEquals(ReminderRescheduleReport(total = 1, scheduled = 0, failed = 1), report)
        assertEquals(ScheduledTaskStatus.Cancelled, repository.task(task.id)?.status)
    }

    private class FakeScheduledTaskDao : ScheduledTaskDao {
        private val tasks = linkedMapOf<String, ScheduledTaskEntity>()
        var beforeUpdateScheduledStatusIfScheduled: (() -> Unit)? = null

        override fun task(taskId: String): ScheduledTaskEntity? =
            tasks[taskId]

        override fun scheduled(limit: Int): List<ScheduledTaskEntity> =
            tasks.values
                .filter { it.status == ScheduledTaskStatus.Scheduled.name }
                .sortedBy { it.triggerAtMillis }
                .take(limit)

        override fun scheduledOrRunning(limit: Int): List<ScheduledTaskEntity> =
            tasks.values
                .filter {
                    it.status == ScheduledTaskStatus.Scheduled.name ||
                        it.status == ScheduledTaskStatus.Running.name
                }
                .sortedWith(compareBy<ScheduledTaskEntity> { it.triggerAtMillis }.thenBy { it.id })
                .take(limit)

        override fun scheduledByType(type: String, limit: Int): List<ScheduledTaskEntity> =
            tasks.values
                .filter { it.status == ScheduledTaskStatus.Scheduled.name && it.type == type }
                .sortedBy { it.triggerAtMillis }
                .take(limit)

        override fun recent(limit: Int): List<ScheduledTaskEntity> =
            tasks.values
                .sortedWith(compareByDescending<ScheduledTaskEntity> { it.updatedAtMillis }.thenBy { it.id })
                .take(limit)

        override fun markReminderRunningIfScheduled(taskId: String, updatedAtMillis: Long): Int {
            val existing = tasks[taskId] ?: return 0
            if (existing.type != ScheduledTaskType.Reminder.name ||
                existing.status != ScheduledTaskStatus.Scheduled.name
            ) {
                return 0
            }
            tasks[taskId] = existing.copy(
                status = ScheduledTaskStatus.Running.name,
                updatedAtMillis = updatedAtMillis,
            )
            return 1
        }

        override fun updateReminderStatusIfRunning(
            taskId: String,
            status: String,
            updatedAtMillis: Long,
        ): Int {
            val existing = tasks[taskId] ?: return 0
            if (existing.type != ScheduledTaskType.Reminder.name ||
                existing.status != ScheduledTaskStatus.Running.name
            ) {
                return 0
            }
            tasks[taskId] = existing.copy(
                status = status,
                updatedAtMillis = updatedAtMillis,
            )
            return 1
        }

        override fun markPeriodicCheckRunningIfScheduled(taskId: String, updatedAtMillis: Long): Int {
            val existing = tasks[taskId] ?: return 0
            if (existing.type != ScheduledTaskType.PeriodicCheck.name ||
                existing.status != ScheduledTaskStatus.Scheduled.name
            ) {
                return 0
            }
            tasks[taskId] = existing.copy(
                status = ScheduledTaskStatus.Running.name,
                updatedAtMillis = updatedAtMillis,
            )
            return 1
        }

        override fun recordPeriodicCheckRunIfRunning(
            taskId: String,
            body: String,
            triggerAtMillis: Long,
            status: String,
            updatedAtMillis: Long,
        ): Int {
            val existing = tasks[taskId] ?: return 0
            if (existing.type != ScheduledTaskType.PeriodicCheck.name ||
                existing.status != ScheduledTaskStatus.Running.name
            ) {
                return 0
            }
            tasks[taskId] = existing.copy(
                body = body,
                triggerAtMillis = triggerAtMillis,
                status = status,
                updatedAtMillis = updatedAtMillis,
            )
            return 1
        }

        override fun updatePeriodicCheckStatusIfRunning(
            taskId: String,
            status: String,
            updatedAtMillis: Long,
        ): Int {
            val existing = tasks[taskId] ?: return 0
            if (existing.type != ScheduledTaskType.PeriodicCheck.name ||
                existing.status != ScheduledTaskStatus.Running.name
            ) {
                return 0
            }
            tasks[taskId] = existing.copy(
                status = status,
                updatedAtMillis = updatedAtMillis,
            )
            return 1
        }

        override fun updateScheduledStatusIfScheduled(
            taskId: String,
            status: String,
            updatedAtMillis: Long,
        ): Int {
            val beforeUpdate = beforeUpdateScheduledStatusIfScheduled
            beforeUpdateScheduledStatusIfScheduled = null
            beforeUpdate?.invoke()
            val existing = tasks[taskId] ?: return 0
            if (existing.status != ScheduledTaskStatus.Scheduled.name) return 0
            tasks[taskId] = existing.copy(
                status = status,
                updatedAtMillis = updatedAtMillis,
            )
            return 1
        }

        override fun updateReminderTriggerAtIfScheduled(
            taskId: String,
            triggerAtMillis: Long,
            updatedAtMillis: Long,
        ): Int {
            val existing = tasks[taskId] ?: return 0
            if (existing.type != ScheduledTaskType.Reminder.name ||
                existing.status != ScheduledTaskStatus.Scheduled.name
            ) {
                return 0
            }
            tasks[taskId] = existing.copy(
                triggerAtMillis = triggerAtMillis,
                updatedAtMillis = updatedAtMillis,
            )
            return 1
        }

        override fun upsert(task: ScheduledTaskEntity) {
            tasks[task.id] = task
        }
    }

    private fun entity(
        id: String,
        type: ScheduledTaskType,
        status: ScheduledTaskStatus,
        triggerAtMillis: Long = 1_000L,
        updatedAtMillis: Long = 1_000L,
    ): ScheduledTaskEntity =
        ScheduledTaskEntity(
            id = id,
            type = type.name,
            title = id,
            body = "",
            triggerAtMillis = triggerAtMillis,
            status = status.name,
            createdAtMillis = 1_000L,
            updatedAtMillis = updatedAtMillis,
        )
}
