package com.bytedance.zgx.pocketmind.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytedance.zgx.pocketmind.data.PocketMindDatabase
import kotlin.concurrent.thread

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in REMINDER_RECOVERY_ACTIONS) return

        val pendingResult = goAsync()
        thread(name = "pocketmind-reminder-recovery-reschedule") {
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

    private companion object {
        val REMINDER_RECOVERY_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
