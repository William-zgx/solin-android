package com.bytedance.zgx.solin.background

import com.bytedance.zgx.solin.BackgroundTaskSummary
import com.bytedance.zgx.solin.logging.cleanMessage

data class BackgroundTaskSnapshot(
    val activeTasks: List<BackgroundTaskSummary>,
    val history: List<BackgroundTaskSummary>,
    val periodicCheckPolicy: PeriodicCheckPolicySummary,
)

data class BackgroundTaskActionResult(
    val succeeded: Boolean,
    val statusText: String,
    val snapshot: BackgroundTaskSnapshot,
)

class BackgroundTaskUseCases(
    private val scheduler: BackgroundTaskScheduler,
) {
    fun snapshot(): BackgroundTaskSnapshot =
        BackgroundTaskSnapshot(
            activeTasks = scheduler.scheduledTasks()
                .filter { task -> task.status == ScheduledTaskStatus.Scheduled }
                .map { task -> task.toSummary() },
            history = scheduler.recentTasks()
                .filter { task ->
                    task.status == ScheduledTaskStatus.Delivered ||
                        task.status == ScheduledTaskStatus.Cancelled ||
                        task.status == ScheduledTaskStatus.Deleted ||
                        task.status == ScheduledTaskStatus.Failed
                }
                .map { task -> task.toSummary() },
            periodicCheckPolicy = loadPeriodicCheckPolicy(),
        )

    fun cancelScheduledTask(taskId: String): BackgroundTaskActionResult =
        scheduler.cancelScheduledTask(taskId).toActionResult("后台任务已取消", "后台任务取消失败")

    fun setPeriodicCheckPolicy(request: PeriodicCheckScheduleRequest): BackgroundTaskActionResult =
        scheduler.setPeriodicCheckPolicy(request).toActionResult("周期检查策略已保存", "周期检查策略保存失败")

    fun disablePeriodicCheckPolicy(): BackgroundTaskActionResult =
        scheduler.disablePeriodicCheckPolicy().toActionResult("周期检查已关闭", "周期检查关闭失败")

    /** The snapshot is taken after the scheduler call so it reflects the change. */
    private fun Result<*>.toActionResult(successText: String, failurePrefix: String): BackgroundTaskActionResult =
        BackgroundTaskActionResult(
            succeeded = isSuccess,
            statusText = exceptionOrNull()?.let { throwable -> "$failurePrefix：${throwable.cleanMessage()}" }
                ?: successText,
            snapshot = snapshot(),
        )

    private fun loadPeriodicCheckPolicy(): PeriodicCheckPolicySummary =
        runCatching { scheduler.periodicCheckPolicy() }
            .getOrDefault(PeriodicCheckPolicySummary.disabled())
}

fun PeriodicCheckPolicySummary.isActivePeriodicCheck(): Boolean =
    taskStatus == ScheduledTaskStatus.Scheduled || taskStatus == ScheduledTaskStatus.Running

fun ScheduledTask.toSummary(): BackgroundTaskSummary =
    BackgroundTaskSummary(
        id = id,
        type = type,
        title = title,
        body = body,
        triggerAtMillis = triggerAtMillis,
        status = status,
    )

