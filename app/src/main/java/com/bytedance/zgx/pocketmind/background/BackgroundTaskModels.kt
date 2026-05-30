package com.bytedance.zgx.pocketmind.background

data class ScheduledTask(
    val id: String,
    val type: ScheduledTaskType,
    val title: String,
    val body: String,
    val triggerAtMillis: Long,
    val status: ScheduledTaskStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

enum class ScheduledTaskType {
    Reminder,
    PeriodicCheck,
}

enum class ScheduledTaskStatus {
    Scheduled,
    Delivered,
    Cancelled,
    Failed,
}

data class ReminderScheduleRequest(
    val title: String,
    val body: String,
    val delayMinutes: Long,
) {
    fun normalized(): ReminderScheduleRequest =
        copy(
            title = title.trim().ifBlank { DEFAULT_TITLE },
            body = body.trim(),
            delayMinutes = delayMinutes.coerceAtLeast(MIN_DELAY_MINUTES),
        )

    companion object {
        const val DEFAULT_TITLE = "PocketMind 提醒"
        const val MIN_DELAY_MINUTES = 1L
    }
}

data class PeriodicCheckConstraints(
    val requiresBatteryNotLow: Boolean = true,
    val requiresCharging: Boolean = false,
)

data class PeriodicCheckScheduleRequest(
    val enabled: Boolean = true,
    val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
    val minNotificationSpacingMinutes: Long = DEFAULT_MIN_NOTIFICATION_SPACING_MINUTES,
    val overdueGraceMinutes: Long = DEFAULT_OVERDUE_GRACE_MINUTES,
    val constraints: PeriodicCheckConstraints = PeriodicCheckConstraints(),
) {
    fun normalized(): PeriodicCheckScheduleRequest =
        copy(
            intervalMinutes = intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES),
            minNotificationSpacingMinutes = minNotificationSpacingMinutes
                .coerceIn(MIN_NOTIFICATION_SPACING_MINUTES, MAX_NOTIFICATION_SPACING_MINUTES),
            overdueGraceMinutes = overdueGraceMinutes.coerceIn(MIN_OVERDUE_GRACE_MINUTES, MAX_OVERDUE_GRACE_MINUTES),
        )

    fun storageSummary(): String {
        val normalized = normalized()
        return listOf(
            "enabled=${normalized.enabled}",
            "intervalMinutes=${normalized.intervalMinutes}",
            "minNotificationSpacingMinutes=${normalized.minNotificationSpacingMinutes}",
            "overdueGraceMinutes=${normalized.overdueGraceMinutes}",
            "requiresBatteryNotLow=${normalized.constraints.requiresBatteryNotLow}",
            "requiresCharging=${normalized.constraints.requiresCharging}",
        ).joinToString(separator = ";")
    }

    companion object {
        const val TASK_ID = "periodic-check-local"
        const val UNIQUE_WORK_NAME = "pocketmind_periodic_local_check"
        const val TITLE = "PocketMind 后台检查"
        const val DEFAULT_INTERVAL_MINUTES = 360L
        const val MIN_INTERVAL_MINUTES = 60L
        const val MAX_INTERVAL_MINUTES = 24L * 60L
        const val DEFAULT_MIN_NOTIFICATION_SPACING_MINUTES = 360L
        const val MIN_NOTIFICATION_SPACING_MINUTES = 60L
        const val MAX_NOTIFICATION_SPACING_MINUTES = 24L * 60L
        const val DEFAULT_OVERDUE_GRACE_MINUTES = 30L
        const val MIN_OVERDUE_GRACE_MINUTES = 5L
        const val MAX_OVERDUE_GRACE_MINUTES = 7L * 24L * 60L
    }
}

data class PeriodicCheckNotification(
    val title: String,
    val body: String,
    val overdueReminderCount: Int,
)

sealed interface PeriodicCheckRunOutcome {
    data class Notified(
        val overdueReminderCount: Int,
        val nextAllowedRunAtMillis: Long,
    ) : PeriodicCheckRunOutcome

    data class NoLocalReminderNeeded(
        val nextAllowedRunAtMillis: Long,
    ) : PeriodicCheckRunOutcome

    data class Skipped(
        val reason: PeriodicCheckSkipReason,
        val nextAllowedRunAtMillis: Long? = null,
    ) : PeriodicCheckRunOutcome

    data class NotificationBlocked(
        val overdueReminderCount: Int,
        val nextAllowedRunAtMillis: Long,
    ) : PeriodicCheckRunOutcome
}

enum class PeriodicCheckSkipReason {
    Disabled,
    NotEnabledInStore,
    RateLimited,
}

interface BackgroundTaskScheduler {
    fun scheduleReminder(request: ReminderScheduleRequest): Result<ScheduledTask>
    fun cancel(taskId: String): Result<Unit>
}

data class ReminderRescheduleReport(
    val total: Int,
    val scheduled: Int,
    val failed: Int,
)
