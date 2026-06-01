package com.bytedance.zgx.pocketmind.background

import com.bytedance.zgx.pocketmind.data.ScheduledTaskDao
import com.bytedance.zgx.pocketmind.data.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicCheckSchedulerTest {
    @Test
    fun scheduleRequestNormalizesAggressiveTiming() {
        val request = PeriodicCheckScheduleRequest(
            intervalMinutes = 1L,
            minNotificationSpacingMinutes = 1L,
            overdueGraceMinutes = 1L,
        ).normalized()

        assertEquals(PeriodicCheckScheduleRequest.MIN_INTERVAL_MINUTES, request.intervalMinutes)
        assertEquals(
            PeriodicCheckScheduleRequest.MIN_NOTIFICATION_SPACING_MINUTES,
            request.minNotificationSpacingMinutes,
        )
        assertEquals(PeriodicCheckScheduleRequest.MIN_OVERDUE_GRACE_MINUTES, request.overdueGraceMinutes)
    }

    @Test
    fun schedulerStoresSinglePeriodicTaskAndCanDisableIt() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 10_000L })
        val workClient = FakePeriodicCheckWorkClient()
        val scheduler = PeriodicCheckScheduler(repository, workClient)

        val first = scheduler.setPeriodicCheck(
            PeriodicCheckScheduleRequest(intervalMinutes = 1L),
        ).getOrThrow()
        val second = scheduler.setPeriodicCheck(
            PeriodicCheckScheduleRequest(intervalMinutes = 2L),
        ).getOrThrow()

        assertEquals(PeriodicCheckScheduleRequest.TASK_ID, first.id)
        assertEquals(PeriodicCheckScheduleRequest.TASK_ID, second.id)
        assertEquals(1, dao.tasks.values.count { it.type == ScheduledTaskType.PeriodicCheck.name })
        assertEquals(2, workClient.enqueuedRequests.size)
        assertEquals(PeriodicCheckScheduleRequest.MIN_INTERVAL_MINUTES, workClient.enqueuedRequests.last().intervalMinutes)
        assertTrue(repository.periodicCheck()?.body.orEmpty().contains("intervalMinutes=60"))

        val disabled = scheduler.setPeriodicCheck(
            PeriodicCheckScheduleRequest(enabled = false),
        ).getOrThrow()

        assertEquals(ScheduledTaskStatus.Cancelled, disabled.status)
        assertEquals(1, workClient.cancelCount)
        assertEquals(ScheduledTaskStatus.Cancelled, repository.periodicCheck()?.status)
        assertEquals(false, repository.periodicCheckPolicy().request.enabled)
        assertEquals(ScheduledTaskStatus.Cancelled, repository.periodicCheckPolicy().taskStatus)
    }

    @Test
    fun schedulerMarksPeriodicTaskFailedWhenWorkClientRejectsEnqueue() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 10_000L })
        val workClient = FakePeriodicCheckWorkClient(
            enqueueResult = Result.failure(IllegalStateException("work unavailable")),
        )
        val scheduler = PeriodicCheckScheduler(repository, workClient)

        val result = scheduler.setPeriodicCheck(PeriodicCheckScheduleRequest())

        assertTrue(result.isFailure)
        assertEquals(ScheduledTaskStatus.Failed, repository.periodicCheck()?.status)
    }

    @Test
    fun runnerPostsLocalSummaryForOverdueRemindersWithoutDeliveringThem() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 10_000L })
        val notifier = FakeLocalPeriodicCheckNotifier()
        val runner = PeriodicCheckRunner(
            repository = repository,
            notifier = notifier,
            clockMillis = { 10_000L },
        )
        repository.createOrUpdatePeriodicCheck(
            PeriodicCheckScheduleRequest(
                minNotificationSpacingMinutes = 60L,
                overdueGraceMinutes = 5L,
            ),
        )
        dao.upsert(
            entity(
                id = "overdue-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Scheduled,
                triggerAtMillis = 10_000L - 6L * 60_000L,
            ),
        )
        dao.upsert(
            entity(
                id = "future-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Scheduled,
                triggerAtMillis = 20_000L,
            ),
        )

        val outcome = runner.runOnce(
            PeriodicCheckScheduleRequest(
                minNotificationSpacingMinutes = 60L,
                overdueGraceMinutes = 5L,
            ),
        )

        assertEquals(PeriodicCheckRunOutcome.Notified(1, 10_000L + 60L * 60_000L), outcome)
        assertEquals(1, notifier.notifications.size)
        assertEquals(1, notifier.notifications.single().overdueReminderCount)
        assertEquals(ScheduledTaskStatus.Scheduled, repository.task("overdue-reminder")?.status)
        assertEquals(ScheduledTaskStatus.Scheduled, repository.task("future-reminder")?.status)
        assertEquals(10_000L + 60L * 60_000L, repository.periodicCheck()?.triggerAtMillis)
        assertEquals(60L, repository.periodicCheckPolicy().request.minNotificationSpacingMinutes)
        assertEquals(5L, repository.periodicCheckPolicy().request.overdueGraceMinutes)
        assertEquals("lastRun=notified;overdueReminderCount=1", repository.periodicCheckPolicy().lastRunSummary)
        assertEquals(
            listOf(
                ScheduledTaskStatus.Scheduled,
                ScheduledTaskStatus.Running,
                ScheduledTaskStatus.Scheduled,
            ),
            dao.statusHistory(PeriodicCheckScheduleRequest.TASK_ID),
        )
    }

    @Test
    fun runnerCompletionDoesNotRevivePeriodicCheckDisabledDuringNotification() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 10_000L })
        val notifier = FakeLocalPeriodicCheckNotifier(
            onPost = { repository.disablePeriodicCheck() },
        )
        val runner = PeriodicCheckRunner(
            repository = repository,
            notifier = notifier,
            clockMillis = { 10_000L },
        )
        repository.createOrUpdatePeriodicCheck(
            PeriodicCheckScheduleRequest(
                minNotificationSpacingMinutes = 60L,
                overdueGraceMinutes = 5L,
            ),
        )
        dao.upsert(
            entity(
                id = "overdue-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Scheduled,
                triggerAtMillis = 10_000L - 6L * 60_000L,
            ),
        )

        val outcome = runner.runOnce(
            PeriodicCheckScheduleRequest(
                minNotificationSpacingMinutes = 60L,
                overdueGraceMinutes = 5L,
            ),
        )

        assertEquals(PeriodicCheckRunOutcome.Notified(1, 10_000L + 60L * 60_000L), outcome)
        assertEquals(ScheduledTaskStatus.Cancelled, repository.periodicCheck()?.status)
        assertTrue(repository.scheduledOrRunning().none { it.id == PeriodicCheckScheduleRequest.TASK_ID })
        assertEquals(
            listOf(
                ScheduledTaskStatus.Scheduled,
                ScheduledTaskStatus.Running,
                ScheduledTaskStatus.Cancelled,
            ),
            dao.statusHistory(PeriodicCheckScheduleRequest.TASK_ID),
        )
    }

    @Test
    fun runnerMarksPeriodicTaskFailedWhenExecutionThrows() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 10_000L })
        val runner = PeriodicCheckRunner(
            repository = repository,
            notifier = FakeLocalPeriodicCheckNotifier(failure = IllegalStateException("notify unavailable")),
            clockMillis = { 10_000L },
        )
        repository.createOrUpdatePeriodicCheck(PeriodicCheckScheduleRequest(overdueGraceMinutes = 5L))
        dao.upsert(
            entity(
                id = "overdue-reminder",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Scheduled,
                triggerAtMillis = 10_000L - 6L * 60_000L,
            ),
        )

        val result = runCatching {
            runner.runOnce(PeriodicCheckScheduleRequest(overdueGraceMinutes = 5L))
        }

        assertTrue(result.isFailure)
        assertEquals(ScheduledTaskStatus.Failed, repository.periodicCheck()?.status)
        assertEquals(
            listOf(
                ScheduledTaskStatus.Scheduled,
                ScheduledTaskStatus.Running,
                ScheduledTaskStatus.Failed,
            ),
            dao.statusHistory(PeriodicCheckScheduleRequest.TASK_ID),
        )
    }

    @Test
    fun runnerSkipsWhenPeriodicCheckIsRateLimited() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 10_000L })
        val notifier = FakeLocalPeriodicCheckNotifier()
        val runner = PeriodicCheckRunner(
            repository = repository,
            notifier = notifier,
            clockMillis = { 10_000L },
        )
        repository.createOrUpdatePeriodicCheck(PeriodicCheckScheduleRequest())
        repository.recordPeriodicCheckRun(
            nextAllowedRunAtMillis = 20_000L,
            summary = "lastRun=ok;overdueReminderCount=0",
        )

        val outcome = runner.runOnce(PeriodicCheckScheduleRequest())

        assertEquals(
            PeriodicCheckRunOutcome.Skipped(
                reason = PeriodicCheckSkipReason.RateLimited,
                nextAllowedRunAtMillis = 20_000L,
            ),
            outcome,
        )
        assertEquals(emptyList<PeriodicCheckNotification>(), notifier.notifications)
    }

    private class FakePeriodicCheckWorkClient(
        private val enqueueResult: Result<Unit> = Result.success(Unit),
        private val cancelResult: Result<Unit> = Result.success(Unit),
    ) : PeriodicCheckWorkClient {
        val enqueuedRequests = mutableListOf<PeriodicCheckScheduleRequest>()
        var cancelCount = 0

        override fun enqueue(request: PeriodicCheckScheduleRequest): Result<Unit> {
            enqueuedRequests += request
            return enqueueResult
        }

        override fun cancel(): Result<Unit> {
            cancelCount += 1
            return cancelResult
        }
    }

    private class FakeLocalPeriodicCheckNotifier(
        private val failure: RuntimeException? = null,
        private val onPost: () -> Unit = {},
    ) : LocalPeriodicCheckNotifier {
        val notifications = mutableListOf<PeriodicCheckNotification>()

        override fun post(notification: PeriodicCheckNotification): Boolean {
            failure?.let { throw it }
            onPost()
            notifications += notification
            return true
        }
    }

    private class FakeScheduledTaskDao : ScheduledTaskDao {
        val tasks = linkedMapOf<String, ScheduledTaskEntity>()
        private val upsertedStatuses = mutableMapOf<String, MutableList<ScheduledTaskStatus>>()

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
            upsert(
                existing.copy(
                    status = ScheduledTaskStatus.Running.name,
                    updatedAtMillis = updatedAtMillis,
                ),
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
            upsert(
                existing.copy(
                    status = ScheduledTaskStatus.Running.name,
                    updatedAtMillis = updatedAtMillis,
                ),
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
            upsert(
                existing.copy(
                    body = body,
                    triggerAtMillis = triggerAtMillis,
                    status = status,
                    updatedAtMillis = updatedAtMillis,
                ),
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
            upsert(
                existing.copy(
                    status = status,
                    updatedAtMillis = updatedAtMillis,
                ),
            )
            return 1
        }

        override fun updateScheduledStatusIfScheduled(
            taskId: String,
            status: String,
            updatedAtMillis: Long,
        ): Int {
            val existing = tasks[taskId] ?: return 0
            if (existing.status != ScheduledTaskStatus.Scheduled.name) return 0
            upsert(
                existing.copy(
                    status = status,
                    updatedAtMillis = updatedAtMillis,
                ),
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
            upsert(
                existing.copy(
                    triggerAtMillis = triggerAtMillis,
                    updatedAtMillis = updatedAtMillis,
                ),
            )
            return 1
        }

        override fun upsert(task: ScheduledTaskEntity) {
            tasks[task.id] = task
            upsertedStatuses.getOrPut(task.id) { mutableListOf() } +=
                ScheduledTaskStatus.valueOf(task.status)
        }

        fun statusHistory(taskId: String): List<ScheduledTaskStatus> =
            upsertedStatuses[taskId].orEmpty()
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
