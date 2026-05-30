package com.bytedance.zgx.pocketmind.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.pocketmind.data.PocketMindDatabase
import kotlin.concurrent.thread

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        thread(name = "pocketmind-reminder-boot-reschedule") {
            try {
                reschedulePendingReminders(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal fun reschedulePendingReminders(context: Context): Result<ReminderRescheduleReport> =
        runCatching {
            val repository = ScheduledTaskRepository(PocketMindDatabase.get(context).scheduledTaskDao())
            AndroidBackgroundTaskScheduler(context, repository)
                .rescheduleScheduledReminders()
                .getOrThrow()
        }
}
