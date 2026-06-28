package com.bytedance.zgx.solin.background

import com.bytedance.zgx.solin.data.ScheduledTaskDao
import com.bytedance.zgx.solin.data.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun nonScheduledTaskFailsWithoutCancellingPlatformSchedule() {
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

        val reminderResult = coordinator.cancelScheduled(reminder.id)
        val periodicResult = coordinator.deleteScheduled(PeriodicCheckScheduleRequest.TASK_ID)

        assertTrue(reminderResult.isFailure)
        assertTrue(periodicResult.isFailure)
        assertEquals(emptyList<String>(), alarmCancellations)
        assertEquals(0, workCancellations)
        assertEquals(ScheduledTaskStatus.Delivered, repository.task(reminder.id)?.status)
        assertEquals(ScheduledTaskStatus.Running, repository.periodicCheck()?.status)
    }

    @Test
    fun missingTaskFailsWithoutCancellingPlatformSchedule() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 1_000L })
        val alarmCancellations = mutableListOf<String>()
        val coordinator = ScheduledTaskRemovalCoordinator(
            repository = repository,
            cancelReminderAlarm = { task ->
                alarmCancellations += task.id
                Result.success(Unit)
            },
            cancelPeriodicWork = { Result.success(Unit) },
        )

        val result = coordinator.cancelScheduled("missing-task")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyList<String>(), alarmCancellations)
    }

    @Test
    fun localStateIsRemovedBeforePlatformCancellation() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 1_000L })
        val reminder = repository.createReminder(
            title = "喝水",
            body = "提醒我喝水",
            triggerAtMillis = 10_000L,
        )
        val alarmCancellations = mutableListOf<String>()
        val deliveredTaskIds = mutableListOf<String>()
        val coordinator = ScheduledTaskRemovalCoordinator(
            repository = repository,
            cancelReminderAlarm = { task ->
                alarmCancellations += task.id
                ReminderAlarmDeliveryHandler(
                    repository = repository,
                    postReminder = { taskId, _, _ ->
                        deliveredTaskIds += taskId
                        true
                    },
                ).deliver(task.id)
                Result.success(Unit)
            },
            cancelPeriodicWork = { Result.success(Unit) },
        )

        val result = coordinator.cancelScheduled(reminder.id)

        assertTrue(result.isSuccess)
        assertEquals(listOf(reminder.id), alarmCancellations)
        assertEquals(emptyList<String>(), deliveredTaskIds)
        assertEquals(ScheduledTaskStatus.Cancelled, repository.task(reminder.id)?.status)
        assertFalse(repository.scheduled().any { task -> task.id == reminder.id })
    }

    @Test
    fun platformCancellationFailureLeavesTaskLocallyRemoved() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 1_000L })
        repository.createOrUpdatePeriodicCheck(PeriodicCheckScheduleRequest())
        val coordinator = ScheduledTaskRemovalCoordinator(
            repository = repository,
            cancelReminderAlarm = { Result.success(Unit) },
            cancelPeriodicWork = { Result.failure(IllegalStateException("work unavailable")) },
        )

        val result = coordinator.cancelScheduled(PeriodicCheckScheduleRequest.TASK_ID)

        assertTrue(result.isFailure)
        assertEquals(ScheduledTaskStatus.Cancelled, repository.periodicCheck()?.status)
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
                .sortedWith(compareBy<ScheduledTaskEntity> { it.triggerAtMillis }.thenBy { it.id })
                .take(limit)

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
                .sortedWith(compareBy<ScheduledTaskEntity> { it.triggerAtMillis }.thenBy { it.id })
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

        override fun markStaleRunningTaskScheduled(
            taskId: String,
            type: String,
            staleUpdatedAtMillis: Long,
            updatedAtMillis: Long,
        ): Int {
            val existing = tasks[taskId] ?: return 0
            if (existing.type != type ||
                existing.status != ScheduledTaskStatus.Running.name ||
                existing.updatedAtMillis > staleUpdatedAtMillis
            ) {
                return 0
            }
            tasks[taskId] = existing.copy(
                status = ScheduledTaskStatus.Scheduled.name,
                updatedAtMillis = updatedAtMillis,
            )
            return 1
        }

        override fun markStaleRunningTasksScheduledByType(
            type: String,
            staleUpdatedAtMillis: Long,
            updatedAtMillis: Long,
        ): Int {
            val staleTasks = tasks.values
                .filter {
                    it.type == type &&
                        it.status == ScheduledTaskStatus.Running.name &&
                        it.updatedAtMillis <= staleUpdatedAtMillis
                }
            staleTasks.forEach { existing ->
                tasks[existing.id] = existing.copy(
                    status = ScheduledTaskStatus.Scheduled.name,
                    updatedAtMillis = updatedAtMillis,
                )
            }
            return staleTasks.size
        }

        override fun updateScheduledStatusIfScheduled(
            taskId: String,
            status: String,
            updatedAtMillis: Long,
        ): Int {
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
}
