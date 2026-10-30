package com.bytedance.zgx.solin.background

import com.bytedance.zgx.solin.data.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.bytedance.zgx.solin.testing.FakeScheduledTaskDao

class ReminderAlarmReceiverTest {
    @Test
    fun asyncDeliveryFinishesExactlyOnceAfterSuccessfulDelivery() {
        val events = mutableListOf<String>()

        ReminderAlarmReceiver().dispatchDelivery(
            finish = { events += "finish" },
            launch = { delivery -> runBlocking { delivery() } },
            delivery = { events += "delivery" },
        )

        assertEquals(listOf("delivery", "finish"), events)
    }

    @Test
    fun asyncDeliveryFinishesExactlyOnceWhenDeliveryFails() {
        val events = mutableListOf<String>()

        ReminderAlarmReceiver().dispatchDelivery(
            finish = { events += "finish" },
            launch = { delivery -> runBlocking { delivery() } },
            delivery = {
                events += "delivery"
                throw IllegalStateException("database unavailable")
            },
        )

        assertEquals(listOf("delivery", "finish"), events)
    }

    @Test
    fun asyncDeliveryFinishesWhenIoDispatchCannotStart() {
        var finishCount = 0

        ReminderAlarmReceiver().dispatchDelivery(
            finish = { finishCount += 1 },
            launch = { throw IllegalStateException("dispatcher unavailable") },
            delivery = { error("Delivery must not run when dispatch fails") },
        )

        assertEquals(1, finishCount)
    }

    @Test
    fun deliveryMarksReminderRunningBeforeDelivered() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        val task = repository.createReminder(
            title = "喝水",
            body = "提醒我喝水",
            triggerAtMillis = 2_000L,
        )
        val deliveredTaskIds = mutableListOf<String>()
        val handler = ReminderAlarmDeliveryHandler(
            repository = repository,
            postReminder = { taskId, _, _ ->
                deliveredTaskIds += taskId
                true
            },
        )

        handler.deliver(task.id)

        assertEquals(listOf(task.id), deliveredTaskIds)
        assertEquals(ScheduledTaskStatus.Delivered, repository.task(task.id)?.status)
        assertEquals(
            listOf(
                ScheduledTaskStatus.Scheduled,
                ScheduledTaskStatus.Running,
                ScheduledTaskStatus.Delivered,
            ),
            dao.statusHistory(task.id),
        )
    }

    @Test
    fun deliveryMarksReminderFailedWhenNotificationThrows() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        val task = repository.createReminder(
            title = "喝水",
            body = "提醒我喝水",
            triggerAtMillis = 2_000L,
        )
        val handler = ReminderAlarmDeliveryHandler(
            repository = repository,
            postReminder = { _, _, _ -> throw IllegalStateException("notification unavailable") },
        )

        handler.deliver(task.id)

        assertEquals(ScheduledTaskStatus.Failed, repository.task(task.id)?.status)
        assertEquals(
            listOf(
                ScheduledTaskStatus.Scheduled,
                ScheduledTaskStatus.Running,
                ScheduledTaskStatus.Failed,
            ),
            dao.statusHistory(task.id),
        )
    }

    @Test
    fun deliveryMarksReminderFailedWhenNotificationIsBlocked() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        val task = repository.createReminder(
            title = "喝水",
            body = "提醒我喝水",
            triggerAtMillis = 2_000L,
        )
        val handler = ReminderAlarmDeliveryHandler(
            repository = repository,
            postReminder = { _, _, _ -> false },
        )

        handler.deliver(task.id)

        assertEquals(ScheduledTaskStatus.Failed, repository.task(task.id)?.status)
        assertEquals(
            listOf(
                ScheduledTaskStatus.Scheduled,
                ScheduledTaskStatus.Running,
                ScheduledTaskStatus.Failed,
            ),
            dao.statusHistory(task.id),
        )
    }

    @Test
    fun deliveryCompletionDoesNotOverwriteTerminalStateWrittenDuringNotification() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        val task = repository.createReminder(
            title = "喝水",
            body = "提醒我喝水",
            triggerAtMillis = 2_000L,
        )
        val handler = ReminderAlarmDeliveryHandler(
            repository = repository,
            postReminder = { taskId, _, _ ->
                // Simulates a concurrent cancel that bypasses the Scheduled-only guard.
                dao.upsert(dao.tasks.getValue(taskId).copy(status = ScheduledTaskStatus.Cancelled.name))
                true
            },
        )

        handler.deliver(task.id)

        assertEquals(ScheduledTaskStatus.Cancelled, repository.task(task.id)?.status)
        assertEquals(
            listOf(
                ScheduledTaskStatus.Scheduled,
                ScheduledTaskStatus.Running,
                ScheduledTaskStatus.Cancelled,
            ),
            dao.statusHistory(task.id),
        )
    }

    @Test
    fun staleAlarmForMissingTaskDoesNotPostOrCreateState() {
        val repository = ScheduledTaskRepository(FakeScheduledTaskDao(), clockMillis = { 1_000L })
        val deliveredTaskIds = mutableListOf<String>()
        val handler = ReminderAlarmDeliveryHandler(
            repository = repository,
            postReminder = { taskId, _, _ ->
                deliveredTaskIds += taskId
                true
            },
        )

        handler.deliver("missing-task")

        assertEquals(emptyList<String>(), deliveredTaskIds)
        assertEquals(null, repository.task("missing-task"))
    }

    @Test
    fun staleAlarmForTerminalTaskDoesNotPostOrChangeState() {
        val terminalStatuses = listOf(
            ScheduledTaskStatus.Cancelled,
            ScheduledTaskStatus.Deleted,
            ScheduledTaskStatus.Failed,
        )
        terminalStatuses.forEach { terminalStatus ->
            val dao = FakeScheduledTaskDao()
            val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
            val task = repository.createReminder(
                title = "喝水",
                body = "提醒我喝水",
                triggerAtMillis = 2_000L,
            )
            when (terminalStatus) {
                ScheduledTaskStatus.Cancelled -> repository.cancelScheduled(task.id)
                ScheduledTaskStatus.Deleted -> repository.deleteScheduled(task.id)
                ScheduledTaskStatus.Failed -> repository.markScheduledFailed(task.id)
                else -> error("Unexpected terminal status: $terminalStatus")
            }
            val deliveredTaskIds = mutableListOf<String>()
            val handler = ReminderAlarmDeliveryHandler(
                repository = repository,
                postReminder = { taskId, _, _ ->
                    deliveredTaskIds += taskId
                    true
                },
            )

            handler.deliver(task.id)

            assertEquals("No post for $terminalStatus", emptyList<String>(), deliveredTaskIds)
            assertEquals(terminalStatus, repository.task(task.id)?.status)
            assertEquals(
                "No stale transition for $terminalStatus",
                listOf(ScheduledTaskStatus.Scheduled, terminalStatus),
                dao.statusHistory(task.id),
            )
        }
    }

    @Test
    fun staleRunningReminderIsRecoveredAndDelivered() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000_000L })
        dao.upsert(
            entity(
                id = "stale-running",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Running,
                triggerAtMillis = 2_000L,
                updatedAtMillis = 1_000L,
            ),
        )
        val deliveredTaskIds = mutableListOf<String>()
        val handler = ReminderAlarmDeliveryHandler(
            repository = repository,
            postReminder = { taskId, _, _ ->
                deliveredTaskIds += taskId
                true
            },
        )

        handler.deliver("stale-running")

        assertEquals(listOf("stale-running"), deliveredTaskIds)
        assertEquals(ScheduledTaskStatus.Delivered, repository.task("stale-running")?.status)
        assertEquals(
            listOf(
                ScheduledTaskStatus.Running,
                ScheduledTaskStatus.Scheduled,
                ScheduledTaskStatus.Running,
                ScheduledTaskStatus.Delivered,
            ),
            dao.statusHistory("stale-running"),
        )
    }

    @Test
    fun freshRunningReminderIsNotDeliveredTwice() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000_000L })
        dao.upsert(
            entity(
                id = "fresh-running",
                type = ScheduledTaskType.Reminder,
                status = ScheduledTaskStatus.Running,
                triggerAtMillis = 2_000L,
                updatedAtMillis = 999_000L,
            ),
        )
        val deliveredTaskIds = mutableListOf<String>()
        val handler = ReminderAlarmDeliveryHandler(
            repository = repository,
            postReminder = { taskId, _, _ ->
                deliveredTaskIds += taskId
                true
            },
        )

        handler.deliver("fresh-running")

        assertEquals(emptyList<String>(), deliveredTaskIds)
        assertEquals(ScheduledTaskStatus.Running, repository.task("fresh-running")?.status)
        assertEquals(listOf(ScheduledTaskStatus.Running), dao.statusHistory("fresh-running"))
    }

    @Test
    fun deliveryUsesStoredTaskPayloadInsteadOfAlarmExtras() {
        val dao = FakeScheduledTaskDao()
        val repository = ScheduledTaskRepository(dao, clockMillis = { 1_000L })
        val task = repository.createReminder(
            title = "数据库标题",
            body = "数据库正文",
            triggerAtMillis = 2_000L,
        )
        val deliveredPayloads = mutableListOf<Triple<String, String, String>>()
        val handler = ReminderAlarmDeliveryHandler(
            repository = repository,
            postReminder = { taskId, title, body ->
                deliveredPayloads += Triple(taskId, title, body)
                true
            },
        )

        handler.deliver(task.id)

        assertEquals(listOf(Triple(task.id, "数据库标题", "数据库正文")), deliveredPayloads)
        assertEquals(ScheduledTaskStatus.Delivered, repository.task(task.id)?.status)
    }


    private fun entity(
        id: String,
        type: ScheduledTaskType,
        status: ScheduledTaskStatus,
        triggerAtMillis: Long,
        updatedAtMillis: Long,
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
