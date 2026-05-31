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

        handler.deliver(task.id, task.title, task.body)

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

        handler.deliver(task.id, task.title, task.body)

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

        handler.deliver(task.id, task.title, task.body)

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

        override fun upsert(task: ScheduledTaskEntity) {
            tasks[task.id] = task
            upsertedStatuses.getOrPut(task.id) { mutableListOf() } +=
                ScheduledTaskStatus.valueOf(task.status)
        }

        fun statusHistory(taskId: String): List<ScheduledTaskStatus> =
            upsertedStatuses[taskId].orEmpty()
    }
}
