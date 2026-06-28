package com.bytedance.zgx.solin.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.bytedance.zgx.solin.data.SolinDatabase
import kotlinx.coroutines.CancellationException

class PeriodicCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): ListenableWorker.Result =
        try {
            val request = inputData.toPeriodicCheckScheduleRequest()
            val repository = ScheduledTaskRepository(
                SolinDatabase.get(applicationContext).scheduledTaskDao(),
            )
            PeriodicCheckRunner(
                repository = repository,
                notifier = AndroidLocalPeriodicCheckNotifier(ReminderNotificationHelper(applicationContext)),
            ).runOnce(request)
            ListenableWorker.Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            markPeriodicCheckFailed()
            ListenableWorker.Result.failure()
        }

    private fun androidx.work.Data.toPeriodicCheckScheduleRequest(): PeriodicCheckScheduleRequest =
        PeriodicCheckScheduleRequest(
            enabled = getBoolean(KEY_ENABLED, true),
            intervalMinutes = getLong(
                KEY_INTERVAL_MINUTES,
                PeriodicCheckScheduleRequest.DEFAULT_INTERVAL_MINUTES,
            ),
            minNotificationSpacingMinutes = getLong(
                KEY_MIN_NOTIFICATION_SPACING_MINUTES,
                PeriodicCheckScheduleRequest.DEFAULT_MIN_NOTIFICATION_SPACING_MINUTES,
            ),
            overdueGraceMinutes = getLong(
                KEY_OVERDUE_GRACE_MINUTES,
                PeriodicCheckScheduleRequest.DEFAULT_OVERDUE_GRACE_MINUTES,
            ),
            constraints = PeriodicCheckConstraints(
                requiresBatteryNotLow = getBoolean(KEY_REQUIRES_BATTERY_NOT_LOW, true),
                requiresCharging = getBoolean(KEY_REQUIRES_CHARGING, false),
            ),
        ).normalized()

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_INTERVAL_MINUTES = "interval_minutes"
        const val KEY_MIN_NOTIFICATION_SPACING_MINUTES = "min_notification_spacing_minutes"
        const val KEY_OVERDUE_GRACE_MINUTES = "overdue_grace_minutes"
        const val KEY_REQUIRES_BATTERY_NOT_LOW = "requires_battery_not_low"
        const val KEY_REQUIRES_CHARGING = "requires_charging"
    }

    private fun markPeriodicCheckFailed() {
        runCatching {
            ScheduledTaskRepository(
                SolinDatabase.get(applicationContext).scheduledTaskDao(),
            ).markPeriodicCheckFailedIfRunning()
        }
    }
}
