package com.bytedance.zgx.pocketmind.background

import com.bytedance.zgx.pocketmind.data.ScheduledTaskDao
import com.bytedance.zgx.pocketmind.data.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderAlarmReceiverTest {
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
                ScheduledTaskStatus.Failed -> repository.markFailed(task.id)
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

    private class FakeScheduledTaskDao : ScheduledTaskDao {
        private val tasks = linkedMapOf<String, ScheduledTaskEntity>()
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

        override fun upsert(task: ScheduledTaskEntity) {
            tasks[task.id] = task
            upsertedStatuses.getOrPut(task.id) { mutableListOf() } +=
                ScheduledTaskStatus.valueOf(task.status)
        }

        fun statusHistory(taskId: String): List<ScheduledTaskStatus> =
            upsertedStatuses[taskId].orEmpty()
    }
}
