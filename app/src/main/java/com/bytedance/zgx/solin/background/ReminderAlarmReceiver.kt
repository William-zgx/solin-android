package com.bytedance.zgx.solin.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.solin.data.SolinDatabase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELIVER_REMINDER) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        dispatchDelivery(
            finish = pendingResult::finish,
            launch = ::launchOnIo,
        ) {
            deliverReminder(appContext, taskId)
        }
    }

    internal fun dispatchDelivery(
        finish: () -> Unit,
        launch: (suspend () -> Unit) -> Unit,
        delivery: suspend () -> Unit,
    ) {
        val finished = AtomicBoolean(false)
        val finishOnce = {
            if (finished.compareAndSet(false, true)) {
                finish()
            }
        }
        try {
            launch {
                try {
                    delivery()
                } catch (_: Exception) {
                } finally {
                    finishOnce()
                }
            }
        } catch (_: Throwable) {
            finishOnce()
        }
    }

    companion object {
        const val ACTION_DELIVER_REMINDER = "com.bytedance.zgx.solin.action.DELIVER_REMINDER"
        const val EXTRA_TASK_ID = "task_id"

        private val deliveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun launchOnIo(delivery: suspend () -> Unit) {
            deliveryScope.launch {
                delivery()
            }
        }

        private fun deliverReminder(context: Context, taskId: String) {
            val repository = ScheduledTaskRepository(SolinDatabase.get(context).scheduledTaskDao())
            val notificationHelper = ReminderNotificationHelper(context)
            ReminderAlarmDeliveryHandler(
                repository = repository,
                postReminder = notificationHelper::postReminder,
            ).deliver(taskId)
        }
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
