package com.bytedance.zgx.solin.background

import com.bytedance.zgx.solin.BackgroundTaskSummary

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
        scheduler.cancelScheduledTask(taskId).fold(
            onSuccess = {
                BackgroundTaskActionResult(
                    succeeded = true,
                    statusText = "后台任务已取消",
                    snapshot = snapshot(),
                )
            },
            onFailure = { throwable ->
                BackgroundTaskActionResult(
                    succeeded = false,
                    statusText = "后台任务取消失败：${throwable.cleanMessage()}",
                    snapshot = snapshot(),
                )
            },
        )

    fun setPeriodicCheckPolicy(request: PeriodicCheckScheduleRequest): BackgroundTaskActionResult =
        scheduler.setPeriodicCheckPolicy(request).fold(
            onSuccess = {
                BackgroundTaskActionResult(
                    succeeded = true,
                    statusText = "周期检查策略已保存",
                    snapshot = snapshot(),
                )
            },
            onFailure = { throwable ->
                BackgroundTaskActionResult(
                    succeeded = false,
                    statusText = "周期检查策略保存失败：${throwable.cleanMessage()}",
                    snapshot = snapshot(),
                )
            },
        )

    fun disablePeriodicCheckPolicy(): BackgroundTaskActionResult =
        scheduler.disablePeriodicCheckPolicy().fold(
            onSuccess = {
                BackgroundTaskActionResult(
                    succeeded = true,
                    statusText = "周期检查已关闭",
                    snapshot = snapshot(),
                )
            },
            onFailure = { throwable ->
                BackgroundTaskActionResult(
                    succeeded = false,
                    statusText = "周期检查关闭失败：${throwable.cleanMessage()}",
                    snapshot = snapshot(),
                )
            },
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

private fun Throwable.cleanMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
