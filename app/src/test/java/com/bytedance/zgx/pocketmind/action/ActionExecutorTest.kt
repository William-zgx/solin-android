package com.bytedance.zgx.pocketmind.action

import android.content.Intent
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

    @Test
    fun opensAllowedDeepLinkAsActionViewIntent() {
        val launches = mutableListOf<ExternalActivityLaunch>()
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = { launch ->
                launches += launch
                true
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-deep-link",
                toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                arguments = mapOf("uri" to "https://example.com/path?q=agent"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        val launch = launches.single()
        assertEquals(Intent.ACTION_VIEW, launch.action)
        assertEquals("https://example.com/path?q=agent", launch.uri)
        assertEquals(MobileActionFunctions.OPEN_DEEP_LINK, launch.toolName)
    }

    @Test
    fun rejectsUnsafeDeepLinkSchemeBeforeStartingActivity() {
        val launches = mutableListOf<ExternalActivityLaunch>()
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = { launch ->
                launches += launch
                true
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-deep-link-unsafe",
                toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                arguments = mapOf("uri" to "javascript:alert(1)"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
        assertTrue(launches.isEmpty())
    }

    @Test
    fun opensPackageIntentWithoutExtras() {
        val launches = mutableListOf<ExternalActivityLaunch>()
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = { launch ->
                launches += launch
                true
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-app",
                toolName = MobileActionFunctions.OPEN_APP_INTENT,
                arguments = mapOf("packageName" to "com.example.app"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        val launch = launches.single()
        assertEquals(Intent.ACTION_MAIN, launch.action)
        assertEquals("com.example.app", launch.packageName)
        assertEquals(MobileActionFunctions.OPEN_APP_INTENT, launch.toolName)
        assertEquals(null, launch.uri)
    }

    @Test
    fun rejectsAppIntentExtrasBeforeStartingActivity() {
        val launches = mutableListOf<ExternalActivityLaunch>()
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = { launch ->
                launches += launch
                true
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-app-explicit",
                toolName = MobileActionFunctions.OPEN_APP_INTENT,
                arguments = mapOf(
                    "packageName" to "com.example.app",
                    "action" to Intent.ACTION_VIEW,
                ),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
        assertTrue(launches.isEmpty())
    }

    @Test
    fun rejectsUnsafeAppIntentDataBeforeStartingActivity() {
        val launches = mutableListOf<ExternalActivityLaunch>()
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = { launch ->
                launches += launch
                true
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-app-unsafe",
                toolName = MobileActionFunctions.OPEN_APP_INTENT,
                arguments = mapOf(
                    "packageName" to "com.example.app",
                    "data" to "file:///sdcard/private.txt",
                ),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
        assertTrue(launches.isEmpty())
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
