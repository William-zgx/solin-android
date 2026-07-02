package com.bytedance.zgx.solin.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolSpec
import java.util.concurrent.TimeUnit

class PeriodicCheckScheduler(
    private val repository: ScheduledTaskRepository,
    private val enqueuePeriodicWork: (PeriodicCheckScheduleRequest) -> Result<Unit>,
    private val cancelPeriodicWorkRequest: () -> Result<Unit>,
    private val backgroundSkillSpec: BackgroundSkillSpec = RegisteredBackgroundSkillSpecs.PeriodicLocalReminderPatrol,
    private val toolSpecs: Map<String, ToolSpec> = ToolRegistry().specs().associateBy { spec -> spec.name },
) {
    fun periodicCheckPolicy(): PeriodicCheckPolicySummary =
        repository.periodicCheckPolicy()

    fun setPeriodicCheckPolicy(request: PeriodicCheckScheduleRequest): Result<PeriodicCheckPolicySummary> =
        setPeriodicCheck(request).map { repository.periodicCheckPolicy() }

    fun disablePeriodicCheckPolicy(): Result<PeriodicCheckPolicySummary> =
        disablePeriodicCheck().map { repository.periodicCheckPolicy() }

    fun reconcilePeriodicCheckOnStartup(): Result<PeriodicCheckPolicySummary> =
        runCatching {
            repository.recoverStaleRunningPeriodicCheck()
            val policy = repository.periodicCheckPolicy()
            if (policy.request.enabled && policy.taskStatus == ScheduledTaskStatus.Scheduled) {
                enqueuePeriodicWork(policy.request)
                    .onFailure {
                        repository.markScheduledFailed(PeriodicCheckScheduleRequest.TASK_ID)
                    }
            }
            repository.periodicCheckPolicy()
        }

    fun setPeriodicCheck(request: PeriodicCheckScheduleRequest): Result<ScheduledTask> =
        runCatching {
            val normalized = request.normalized()
            if (!normalized.enabled) {
                val task = repository.disablePeriodicCheck()
                cancelPeriodicWork().getOrThrow()
                return@runCatching task
            }
            validateRegisteredBackgroundSpec().getOrElse { throwable ->
                val task = repository.createOrUpdatePeriodicCheck(normalized)
                repository.markScheduledFailed(task.id)
                throw throwable
            }

            val task = repository.createOrUpdatePeriodicCheck(normalized)
            enqueuePeriodicWork(normalized)
                .onFailure {
                    repository.markScheduledFailed(PeriodicCheckScheduleRequest.TASK_ID)
                }
                .getOrThrow()
            task
        }

    fun disablePeriodicCheck(): Result<Unit> =
        runCatching {
            repository.disablePeriodicCheck()
            cancelPeriodicWork().getOrThrow()
        }

    fun cancelPeriodicWork(): Result<Unit> =
        cancelPeriodicWorkRequest()

    private fun validateRegisteredBackgroundSpec(): Result<Unit> {
        val validation = backgroundSkillSpec.validate(toolSpecs)
        return if (validation.isValid) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "Invalid background skill ${backgroundSkillSpec.id}: " +
                        validation.errors.joinToString(separator = "; "),
                ),
            )
        }
    }
}

class WorkManagerPeriodicCheckClient(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) {
    fun enqueue(request: PeriodicCheckScheduleRequest): Result<Unit> =
        runCatching {
            val normalized = request.normalized()
            val workRequest = PeriodicWorkRequestBuilder<PeriodicCheckWorker>(
                normalized.intervalMinutes,
                TimeUnit.MINUTES,
            )
                .setConstraints(normalized.toWorkConstraints())
                .setInputData(normalized.toWorkData())
                .build()

            workManager.enqueueUniquePeriodicWork(
                PeriodicCheckScheduleRequest.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest,
            ).result.get()
            Unit
        }

    fun cancel(): Result<Unit> =
        runCatching {
            workManager.cancelUniqueWork(PeriodicCheckScheduleRequest.UNIQUE_WORK_NAME).result.get()
            Unit
        }

    private fun PeriodicCheckScheduleRequest.toWorkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(constraints.requiresBatteryNotLow)
            .setRequiresCharging(constraints.requiresCharging)
            .build()

    private fun PeriodicCheckScheduleRequest.toWorkData() =
        workDataOf(
            PeriodicCheckWorker.KEY_ENABLED to enabled,
            PeriodicCheckWorker.KEY_INTERVAL_MINUTES to intervalMinutes,
            PeriodicCheckWorker.KEY_MIN_NOTIFICATION_SPACING_MINUTES to minNotificationSpacingMinutes,
            PeriodicCheckWorker.KEY_OVERDUE_GRACE_MINUTES to overdueGraceMinutes,
            PeriodicCheckWorker.KEY_REQUIRES_BATTERY_NOT_LOW to constraints.requiresBatteryNotLow,
            PeriodicCheckWorker.KEY_REQUIRES_CHARGING to constraints.requiresCharging,
        )
}
