package com.bytedance.zgx.solin.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

    private val removalCoordinator: ScheduledTaskRemovalCoordinator by lazy {
        ScheduledTaskRemovalCoordinator(
            repository = repository,
            cancelReminderAlarm = ::cancelReminderAlarm,
            cancelPeriodicWork = { periodicCheckScheduler.cancelPeriodicWork() },
        )
    }

    override fun scheduledTasks(limit: Int): List<ScheduledTask> =
        repository.scheduledOrRunning(limit)

    override fun recentTasks(limit: Int): List<ScheduledTask> =
        repository.recent(limit)

    override fun scheduleReminder(request: ReminderScheduleRequest): Result<ScheduledTask> =
        runCatching {
            val normalized = request.normalized()
            require((normalized.delayMinutes == null) != (normalized.triggerAtMillis == null)) {
                "Reminder request requires exactly one of delayMinutes or triggerAtMillis"
            }
            val now = clockMillis()
            val minimumTriggerAtMillis = now + ReminderScheduleRequest.MIN_DELAY_MINUTES * MILLIS_PER_MINUTE
            val triggerAtMillis = (
                normalized.triggerAtMillis
                    ?: (now + requireNotNull(normalized.delayMinutes) * MILLIS_PER_MINUTE)
                ).coerceAtLeast(minimumTriggerAtMillis)
            val task = repository.createReminder(
                title = normalized.title,
                body = normalized.body,
                triggerAtMillis = triggerAtMillis,
            )
            scheduleAlarm(task, task.triggerAtMillis)
                .onFailure { repository.markScheduledFailed(task.id) }
                .getOrThrow()
            task
        }

    override fun cancel(taskId: String): Result<Unit> =
        cancelScheduledTask(taskId)

    override fun cancelScheduledTask(taskId: String): Result<Unit> =
        removalCoordinator.cancelScheduled(taskId)

    override fun deleteScheduledTask(taskId: String): Result<Unit> =
        removalCoordinator.deleteScheduled(taskId)

    override fun periodicCheckPolicy(): PeriodicCheckPolicySummary =
        periodicCheckScheduler.periodicCheckPolicy()

    override fun setPeriodicCheckPolicy(request: PeriodicCheckScheduleRequest): Result<PeriodicCheckPolicySummary> =
        periodicCheckScheduler.setPeriodicCheckPolicy(request)

    override fun disablePeriodicCheckPolicy(): Result<PeriodicCheckPolicySummary> =
        periodicCheckScheduler.disablePeriodicCheckPolicy()

    override fun reconcilePeriodicCheckOnStartup(): Result<PeriodicCheckPolicySummary> =
        periodicCheckScheduler.reconcilePeriodicCheckOnStartup()

    override fun rescheduleScheduledReminders(limit: Int): Result<ReminderRescheduleReport> =
        runCatching {
            ReminderRescheduler(
                repository = repository,
                scheduleAlarm = ::scheduleAlarm,
                clockMillis = clockMillis,
                cleanupLegacyAlarm = ::cancelLegacyReminderAlarm,
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

    private fun cancelReminderAlarm(task: ScheduledTask): Result<Unit> =
        runCatching {
            val manager = alarmManager
            cancelPendingIntent(manager, pendingIntentFor(task, PendingIntent.FLAG_NO_CREATE))
            cancelPendingIntent(manager, legacyPendingIntentFor(task, PendingIntent.FLAG_NO_CREATE))
        }

    private fun cancelLegacyReminderAlarm(task: ScheduledTask): Result<Unit> =
        runCatching {
            cancelPendingIntent(alarmManager, legacyPendingIntentFor(task, PendingIntent.FLAG_NO_CREATE))
        }

    private fun cancelPendingIntent(
        manager: AlarmManager?,
        pendingIntent: PendingIntent?,
    ) {
        if (pendingIntent == null) return
        manager?.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun pendingIntentFor(task: ScheduledTask, flag: Int): PendingIntent? =
        pendingIntentFor(task = task, flag = flag, includeUniqueData = true)

    private fun legacyPendingIntentFor(task: ScheduledTask, flag: Int): PendingIntent? =
        pendingIntentFor(task = task, flag = flag, includeUniqueData = false)

    private fun pendingIntentFor(
        task: ScheduledTask,
        flag: Int,
        includeUniqueData: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_DELIVER_REMINDER
            if (includeUniqueData) {
                data = Uri.parse(reminderAlarmDataUriString(task.id))
            }
            putExtra(ReminderAlarmReceiver.EXTRA_TASK_ID, task.id)
        }
        return PendingIntent.getBroadcast(
            context,
            if (includeUniqueData) {
                reminderAlarmRequestCode()
            } else {
                legacyReminderAlarmRequestCode(task.id)
            },
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

internal fun reminderAlarmRequestCode(): Int =
    0

internal fun legacyReminderAlarmRequestCode(taskId: String): Int =
    taskId.hashCode()

internal fun reminderAlarmDataUriString(taskId: String): String =
    "solin://reminder?taskId=${URLEncoder.encode(taskId, StandardCharsets.UTF_8.name())}"
