package com.bytedance.zgx.pocketmind.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.pocketmind.data.PocketMindDatabase

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELIVER_REMINDER) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val repository = ScheduledTaskRepository(PocketMindDatabase.get(context).scheduledTaskDao())
        val notificationHelper = ReminderNotificationHelper(context)

        ReminderAlarmDeliveryHandler(
            repository = repository,
            postReminder = notificationHelper::postReminder,
        ).deliver(taskId, title, body)
    }

    companion object {
        const val ACTION_DELIVER_REMINDER = "com.bytedance.zgx.pocketmind.action.DELIVER_REMINDER"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
    }
}

internal class ReminderAlarmDeliveryHandler(
    private val repository: ScheduledTaskRepository,
    private val postReminder: (taskId: String, title: String, body: String) -> Boolean,
) {
    fun deliver(taskId: String, title: String, body: String) {
        repository.markRunning(taskId)
        try {
            if (postReminder(taskId, title, body)) {
                repository.markDelivered(taskId)
            } else {
                repository.markFailed(taskId)
            }
        } catch (_: Exception) {
            repository.markFailed(taskId)
        }
    }
}
