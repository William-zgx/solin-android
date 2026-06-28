package com.bytedance.zgx.solin.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTaskUseCasesTest {
    @Test
    fun snapshotSeparatesActiveAndHistoryTasks() {
        val useCases = BackgroundTaskUseCases(
            scheduler = FakeScheduler(
                tasks = listOf(
                    task("scheduled", ScheduledTaskStatus.Scheduled),
                    task("done", ScheduledTaskStatus.Delivered),
                    task("cancelled", ScheduledTaskStatus.Cancelled),
                ),
            ),
        )

        val snapshot = useCases.snapshot()

        assertEquals(listOf("scheduled"), snapshot.activeTasks.map { it.id })
        assertEquals(listOf("done", "cancelled"), snapshot.history.map { it.id })
        assertEquals(PeriodicCheckPolicySummary.disabled(), snapshot.periodicCheckPolicy)
    }

    @Test
    fun cancelScheduledTaskReturnsRefreshedSnapshotAndStatusText() {
        val scheduler = FakeScheduler(tasks = listOf(task("task-1", ScheduledTaskStatus.Scheduled)))
        val useCases = BackgroundTaskUseCases(scheduler)

        val result = useCases.cancelScheduledTask("task-1")

        assertTrue(result.succeeded)
        assertEquals("后台任务已取消", result.statusText)
        assertEquals(emptyList<String>(), result.snapshot.activeTasks.map { it.id })
        assertEquals(listOf("task-1"), result.snapshot.history.map { it.id })
    }

    @Test
    fun cancelFailureReturnsSafeErrorMessageAndRefreshedSnapshot() {
        val useCases = BackgroundTaskUseCases(
            scheduler = FakeScheduler(
                tasks = listOf(task("task-1", ScheduledTaskStatus.Scheduled)),
                cancelFailure = IllegalStateException("alarm unavailable"),
            ),
        )

        val result = useCases.cancelScheduledTask("task-1")

        assertTrue(!result.succeeded)
        assertEquals("后台任务取消失败：alarm unavailable", result.statusText)
        assertEquals(listOf("task-1"), result.snapshot.activeTasks.map { it.id })
    }

    @Test
    fun periodicPolicyActionsReturnRefreshedStatus() {
        val scheduler = FakeScheduler()
        val useCases = BackgroundTaskUseCases(scheduler)

        val saved = useCases.setPeriodicCheckPolicy(PeriodicCheckScheduleRequest())
        val disabled = useCases.disablePeriodicCheckPolicy()

        assertEquals("周期检查策略已保存", saved.statusText)
        assertEquals(ScheduledTaskStatus.Scheduled, saved.snapshot.periodicCheckPolicy.taskStatus)
        assertEquals("周期检查已关闭", disabled.statusText)
        assertEquals(ScheduledTaskStatus.Cancelled, disabled.snapshot.periodicCheckPolicy.taskStatus)
    }

    private class FakeScheduler(
        tasks: List<ScheduledTask> = emptyList(),
        private val cancelFailure: Throwable? = null,
    ) : BackgroundTaskScheduler {
        private val tasksById = linkedMapOf<String, ScheduledTask>()

        init {
            tasks.forEach { tasksById[it.id] = it }
        }

        override fun scheduledTasks(limit: Int): List<ScheduledTask> =
            tasksById.values.take(limit)

        override fun recentTasks(limit: Int): List<ScheduledTask> =
            tasksById.values.take(limit)

        override fun periodicCheckPolicy(): PeriodicCheckPolicySummary =
            tasksById[PeriodicCheckScheduleRequest.TASK_ID]?.toPolicy()
                ?: PeriodicCheckPolicySummary.disabled()

        override fun setPeriodicCheckPolicy(
            request: PeriodicCheckScheduleRequest,
        ): Result<PeriodicCheckPolicySummary> {
            tasksById[PeriodicCheckScheduleRequest.TASK_ID] = task(
                id = PeriodicCheckScheduleRequest.TASK_ID,
                status = ScheduledTaskStatus.Scheduled,
                type = ScheduledTaskType.PeriodicCheck,
                body = request.normalized().storageSummary(),
            )
            return Result.success(periodicCheckPolicy())
        }

        override fun disablePeriodicCheckPolicy(): Result<PeriodicCheckPolicySummary> {
            tasksById[PeriodicCheckScheduleRequest.TASK_ID] = task(
                id = PeriodicCheckScheduleRequest.TASK_ID,
                status = ScheduledTaskStatus.Cancelled,
                type = ScheduledTaskType.PeriodicCheck,
                body = PeriodicCheckScheduleRequest(enabled = false).normalized().storageSummary(),
            )
            return Result.success(periodicCheckPolicy())
        }

        override fun scheduleReminder(request: ReminderScheduleRequest): Result<ScheduledTask> =
            error("not needed")

        override fun cancel(taskId: String): Result<Unit> {
            cancelFailure?.let { return Result.failure(it) }
            tasksById[taskId]?.let { tasksById[taskId] = it.copy(status = ScheduledTaskStatus.Cancelled) }
            return Result.success(Unit)
        }
    }

    private companion object {
        fun task(
            id: String,
            status: ScheduledTaskStatus,
            type: ScheduledTaskType = ScheduledTaskType.Reminder,
            body: String = "",
        ) = ScheduledTask(
            id = id,
            type = type,
            title = id,
            body = body,
            triggerAtMillis = 1_000L,
            status = status,
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
        )

        fun ScheduledTask.toPolicy(): PeriodicCheckPolicySummary =
            PeriodicCheckPolicySummary(
                request = PeriodicCheckScheduleRequest.fromStorageSummary(body),
                taskStatus = status,
                nextAllowedRunAtMillis = null,
                lastRunSummary = null,
                updatedAtMillis = updatedAtMillis,
            )
    }
}
