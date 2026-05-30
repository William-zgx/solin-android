package com.bytedance.zgx.pocketmind.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class AndroidBackgroundTaskScheduler(
    private val context: Context,
    private val repository: ScheduledTaskRepository,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : BackgroundTaskScheduler {
    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    private val periodicCheckScheduler: PeriodicCheckScheduler by lazy {
        PeriodicCheckScheduler(
            repository = repository,
            workClient = WorkManagerPeriodicCheckClient(context),
        )
    }

    override fun scheduleReminder(request: ReminderScheduleRequest): Result<ScheduledTask> =
        runCatching {
            val normalized = request.normalized()
            val triggerAtMillis = clockMillis() + normalized.delayMinutes * MILLIS_PER_MINUTE
            val task = repository.createReminder(
                title = normalized.title,
                body = normalized.body,
                triggerAtMillis = triggerAtMillis,
            )
            scheduleAlarm(task, task.triggerAtMillis)
                .onFailure { repository.markFailed(task.id) }
                .getOrThrow()
            task
        }

    override fun cancel(taskId: String): Result<Unit> =
        runCatching {
            val task = repository.task(taskId) ?: return@runCatching
            pendingIntentFor(task, PendingIntent.FLAG_NO_CREATE)?.let { pendingIntent ->
                alarmManager?.cancel(pendingIntent)
            }
            repository.markCancelled(taskId)
        }

    fun rescheduleScheduledReminders(limit: Int = 100): Result<ReminderRescheduleReport> =
        runCatching {
            ReminderRescheduler(
                repository = repository,
                scheduleAlarm = ::scheduleAlarm,
                clockMillis = clockMillis,
            ).reschedulePendingReminders(limit)
        }

    fun setPeriodicCheck(request: PeriodicCheckScheduleRequest): Result<ScheduledTask> =
        periodicCheckScheduler.setPeriodicCheck(request)

    fun disablePeriodicCheck(): Result<Unit> =
        periodicCheckScheduler.disablePeriodicCheck()

    private fun scheduleAlarm(task: ScheduledTask, triggerAtMillis: Long): Result<Unit> =
        runCatching {
            val pendingIntent = pendingIntentFor(task, PendingIntent.FLAG_UPDATE_CURRENT)
                ?: error("Unable to create reminder PendingIntent")
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent,
            ) ?: error("AlarmManager unavailable")
        }

    private fun pendingIntentFor(task: ScheduledTask, flag: Int): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_DELIVER_REMINDER
            putExtra(ReminderAlarmReceiver.EXTRA_TASK_ID, task.id)
            putExtra(ReminderAlarmReceiver.EXTRA_TITLE, task.title)
            putExtra(ReminderAlarmReceiver.EXTRA_BODY, task.body)
        }
        return PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            flag or immutableFlag(),
        )
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
