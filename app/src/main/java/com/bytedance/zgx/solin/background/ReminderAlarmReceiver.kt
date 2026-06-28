package com.bytedance.zgx.solin.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.solin.data.SolinDatabase

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELIVER_REMINDER) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val repository = ScheduledTaskRepository(SolinDatabase.get(context).scheduledTaskDao())
        val notificationHelper = ReminderNotificationHelper(context)

        ReminderAlarmDeliveryHandler(
            repository = repository,
            postReminder = notificationHelper::postReminder,
        ).deliver(taskId)
    }

    companion object {
        const val ACTION_DELIVER_REMINDER = "com.bytedance.zgx.solin.action.DELIVER_REMINDER"
        const val EXTRA_TASK_ID = "task_id"
    }
}

internal class ReminderAlarmDeliveryHandler(
    private val repository: ScheduledTaskRepository,
    private val postReminder: (taskId: String, title: String, body: String) -> Boolean,
) {
    fun deliver(taskId: String) {
        val task = repository.startReminderDelivery(taskId) ?: return
        try {
            if (postReminder(task.id, task.title, task.body)) {
                repository.markReminderDeliveredIfRunning(taskId)
            } else {
                repository.markReminderFailedIfRunning(taskId)
            }
        } catch (_: Exception) {
            repository.markReminderFailedIfRunning(taskId)
        }
    }
}
