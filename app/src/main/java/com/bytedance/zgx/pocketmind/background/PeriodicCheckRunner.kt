package com.bytedance.zgx.pocketmind.background

interface LocalPeriodicCheckNotifier {
    fun post(notification: PeriodicCheckNotification): Boolean
}

class AndroidLocalPeriodicCheckNotifier(
    private val notificationHelper: ReminderNotificationHelper,
) : LocalPeriodicCheckNotifier {
    override fun post(notification: PeriodicCheckNotification): Boolean =
        notificationHelper.postReminder(
            taskId = PeriodicCheckScheduleRequest.TASK_ID,
            title = notification.title,
            body = notification.body,
        )
}

class PeriodicCheckRunner(
    private val repository: ScheduledTaskRepository,
    private val notifier: LocalPeriodicCheckNotifier,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val maxReminderScanCount: Int = DEFAULT_MAX_REMINDER_SCAN_COUNT,
) {
    fun runOnce(request: PeriodicCheckScheduleRequest): PeriodicCheckRunOutcome {
        val normalized = request.normalized()
        if (!normalized.enabled) {
            return PeriodicCheckRunOutcome.Skipped(PeriodicCheckSkipReason.Disabled)
        }

        val state = repository.periodicCheck()
        if (state?.status != ScheduledTaskStatus.Scheduled) {
            return PeriodicCheckRunOutcome.Skipped(PeriodicCheckSkipReason.NotEnabledInStore)
        }

        val now = clockMillis()
        if (state.triggerAtMillis > now) {
            return PeriodicCheckRunOutcome.Skipped(
                reason = PeriodicCheckSkipReason.RateLimited,
                nextAllowedRunAtMillis = state.triggerAtMillis,
            )
        }

        val nextAllowedRunAtMillis = now + normalized.minNotificationSpacingMinutes * MILLIS_PER_MINUTE
        val overdueCutoffMillis = now - normalized.overdueGraceMinutes * MILLIS_PER_MINUTE
        val overdueReminderCount = repository.scheduledReminders(maxReminderScanCount)
            .count { it.triggerAtMillis <= overdueCutoffMillis }

        if (overdueReminderCount == 0) {
            repository.recordPeriodicCheckRun(
                nextAllowedRunAtMillis = nextAllowedRunAtMillis,
                summary = "lastRun=ok;overdueReminderCount=0",
            )
            return PeriodicCheckRunOutcome.NoLocalReminderNeeded(nextAllowedRunAtMillis)
        }

        val notification = PeriodicCheckNotification(
            title = PeriodicCheckScheduleRequest.TITLE,
            body = "发现 $overdueReminderCount 个可能未触达的本地提醒，请打开应用确认。",
            overdueReminderCount = overdueReminderCount,
        )

        val posted = notifier.post(notification)
        repository.recordPeriodicCheckRun(
            nextAllowedRunAtMillis = nextAllowedRunAtMillis,
            summary = "lastRun=${if (posted) "notified" else "notificationBlocked"};" +
                "overdueReminderCount=$overdueReminderCount",
        )

        return if (posted) {
            PeriodicCheckRunOutcome.Notified(overdueReminderCount, nextAllowedRunAtMillis)
        } else {
            PeriodicCheckRunOutcome.NotificationBlocked(overdueReminderCount, nextAllowedRunAtMillis)
        }
    }

    private companion object {
        const val DEFAULT_MAX_REMINDER_SCAN_COUNT = 100
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
