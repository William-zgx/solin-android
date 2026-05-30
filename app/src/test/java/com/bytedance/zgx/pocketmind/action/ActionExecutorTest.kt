package com.bytedance.zgx.pocketmind.action

import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.ReminderScheduleRequest
import com.bytedance.zgx.pocketmind.background.ScheduledTask
import com.bytedance.zgx.pocketmind.background.ScheduledTaskStatus
import com.bytedance.zgx.pocketmind.background.ScheduledTaskType
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorTest {
    @Test
    fun schedulesReminderThroughBackgroundScheduler() {
        val scheduler = RecordingBackgroundTaskScheduler()
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = scheduler,
            canPostReminderNotifications = { true },
        )

        val result = executor.execute(reminderRequest())

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("task-1", result.data["taskId"])
        assertEquals("喝水", scheduler.lastReminderRequest?.title)
        assertEquals(15L, scheduler.lastReminderRequest?.delayMinutes)
    }

    @Test
    fun rejectsReminderWhenNotificationPermissionIsMissing() {
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(),
            canPostReminderNotifications = { false },
        )

        val result = executor.execute(reminderRequest())

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertTrue(result.summary.contains("通知权限"))
    }

    @Test
    fun reportsSchedulerFailureAsStructuredToolResult() {
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(
                failure = IllegalStateException("alarm unavailable"),
            ),
            canPostReminderNotifications = { true },
        )

        val result = executor.execute(reminderRequest())

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.summary.contains("alarm unavailable"))
    }

    @Test
    fun readsClipboardTextThroughInjectedProvider() {
        val executor = ActionExecutor(
            context = null,
            clipboardTextProvider = { "  需要总结的剪贴板内容  " },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-clipboard",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("需要总结的剪贴板内容", result.data["text"])
        assertEquals("false", result.data["truncated"])
        assertTrue(result.summary.contains("剪贴板"))
    }

    @Test
    fun reportsEmptyClipboardAsStructuredFailure() {
        val executor = ActionExecutor(
            context = null,
            clipboardTextProvider = { " " },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-clipboard-empty",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.summary.contains("剪贴板"))
    }

    private fun reminderRequest(): ToolRequest =
        ToolRequest(
            id = "request-reminder",
            toolName = MobileActionFunctions.SCHEDULE_REMINDER,
            arguments = mapOf(
                "title" to "喝水",
                "body" to "提醒我 15 分钟后喝水",
                "delayMinutes" to "15",
            ),
            reason = "test",
        )

    private class RecordingBackgroundTaskScheduler(
        private val failure: Throwable? = null,
    ) : BackgroundTaskScheduler {
        var lastReminderRequest: ReminderScheduleRequest? = null
            private set

        override fun scheduleReminder(request: ReminderScheduleRequest): Result<ScheduledTask> {
            lastReminderRequest = request
            failure?.let { return Result.failure(it) }
            return Result.success(
                ScheduledTask(
                    id = "task-1",
                    type = ScheduledTaskType.Reminder,
                    title = request.title,
                    body = request.body,
                    triggerAtMillis = 901_000L,
                    status = ScheduledTaskStatus.Scheduled,
                    createdAtMillis = 1_000L,
                    updatedAtMillis = 1_000L,
                ),
            )
        }

        override fun cancel(taskId: String): Result<Unit> =
            Result.success(Unit)
    }
}
