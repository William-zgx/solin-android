package com.bytedance.zgx.pocketmind.action

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.ReminderScheduleRequest
import com.bytedance.zgx.pocketmind.background.ScheduledTask
import com.bytedance.zgx.pocketmind.background.ScheduledTaskStatus
import com.bytedance.zgx.pocketmind.background.ScheduledTaskType
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("Scheduled", result.data["taskStatus"])
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, result.data["recoveryToolName"])
        assertEquals("task-1", result.data["recoveryTaskId"])
        assertEquals("喝水", scheduler.lastReminderRequest?.title)
        assertEquals(15L, scheduler.lastReminderRequest?.delayMinutes)
    }

    @Test
    fun cancelsReminderThroughBackgroundScheduler() {
        val scheduler = RecordingBackgroundTaskScheduler()
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = scheduler,
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-cancel-reminder",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "task-1"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("task-1", result.data["taskId"])
        assertEquals("Cancelled", result.data["taskStatus"])
        assertEquals("task-1", scheduler.lastCancelledTaskId)
    }

    @Test
    fun rejectsReminderCancellationWithoutTaskId() {
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(),
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-cancel-reminder",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to " "),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
    }

    @Test
    fun reportsReminderCancellationFailureAsStructuredToolResult() {
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(
                failure = IllegalStateException("cancel unavailable"),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-cancel-reminder",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "task-1"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.summary.contains("cancel unavailable"))
    }

    @Test
    fun reportsStaleReminderCancellationAsNonRetryableInvalidRequest() {
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = RecordingBackgroundTaskScheduler(
                failure = IllegalArgumentException("Scheduled task not found: task-1"),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-cancel-reminder",
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "task-1"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
        assertFalse(result.retryable)
        assertTrue(result.summary.contains("Scheduled task not found"))
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
    fun opensUsageAccessSettingsAsSystemSettingsIntent() {
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
                id = "request-usage-access",
                toolName = MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertExternalActivityOpened(result.data, "SystemSettings", Settings.ACTION_USAGE_ACCESS_SETTINGS)
        assertEquals("usage_stats", result.data["specialAccess"])
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, result.data["settingsAction"])
        assertFalse(result.data.containsKey("packageName"))
        assertFalse(result.data.containsKey("appLabel"))
        assertFalse(result.data.containsKey("lastTimeUsedMillis"))
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, launches.single().action)
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
        assertExternalActivityOpened(result.data, "HttpsUri", Intent.ACTION_VIEW)
        assertEquals("https", result.data["targetUriScheme"])
        assertEquals("example.com", result.data["targetUriHost"])
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
        assertExternalActivityOpened(result.data, "AndroidPackage", Intent.ACTION_MAIN)
        assertEquals("com.example.app", result.data["targetPackage"])
        val launch = launches.single()
        assertEquals(Intent.ACTION_MAIN, launch.action)
        assertEquals("com.example.app", launch.packageName)
        assertEquals(MobileActionFunctions.OPEN_APP_INTENT, launch.toolName)
        assertEquals(null, launch.uri)
    }

    @Test
    fun opensAllowlistedAppDeepTargetWithPinnedPackageAndUri() {
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
                id = "request-app-details",
                toolName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                arguments = mapOf(
                    "targetId" to AppDeepTargets.APP_DETAILS_SETTINGS_ID,
                    "packageName" to "com.example.app",
                ),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertExternalActivityOpened(result.data, "AppDeepTarget", Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertEquals(AppDeepTargets.APP_DETAILS_SETTINGS_ID, result.data["targetId"])
        assertEquals("com.example.app", result.data["targetPackage"])
        assertTrue(result.data["rawPayloadIncluded"] == "false")
        assertTrue(!result.data.containsKey("targetUriScheme"))
        val launch = launches.single()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, launch.action)
        assertEquals(AppDeepTargets.APP_DETAILS_SETTINGS_ID, launch.targetId)
        assertEquals("com.example.app", launch.packageName)
        assertEquals("package:com.example.app", launch.uri)
    }

    @Test
    fun reportsActivityNotFoundAsNotStartedExternalCompletion() {
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = {
                throw ActivityNotFoundException("missing activity")
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-deep-link-missing",
                toolName = MobileActionFunctions.OPEN_DEEP_LINK,
                arguments = mapOf("uri" to "https://example.com/path"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.NoActivityFound, result.error?.code)
        assertExternalActivityNotStarted(result.data, "HttpsUri", Intent.ACTION_VIEW)
        assertEquals("Unknown", result.data["externalOutcome"])
        assertEquals(null, result.data["exceptionType"])
    }

    @Test
    fun reportsExternalActivityExceptionWithExceptionType() {
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = {
                throw IllegalStateException("launcher unavailable")
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-app-exception",
                toolName = MobileActionFunctions.OPEN_APP_INTENT,
                arguments = mapOf("packageName" to "com.example.app"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertExternalActivityNotStarted(result.data, "AndroidPackage", Intent.ACTION_MAIN)
        assertEquals("com.example.app", result.data["targetPackage"])
        assertEquals("IllegalStateException", result.data["exceptionType"])
        assertTrue(result.summary.contains("launcher unavailable"))
    }

    @Test
    fun shareTextMetadataDoesNotIncludeRawPayload() {
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = { true },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-share",
                toolName = MobileActionFunctions.SHARE_TEXT,
                arguments = mapOf("text" to "private share payload"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertExternalActivityOpened(result.data, "ShareSheet", Intent.ACTION_CHOOSER)
        assertEquals("text/plain", result.data["payloadMimeType"])
        assertTrue(!result.data.toString().contains("private share payload"))
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

    @Test
    fun rejectsUnknownAppDeepTargetBeforeStartingActivity() {
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
                id = "request-app-target",
                toolName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                arguments = mapOf(
                    "targetId" to "arbitrary_activity",
                    "packageName" to "com.example.app",
                ),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
        assertTrue(launches.isEmpty())
    }

    @Test
    fun rejectsAppDeepTargetExtrasBeforeStartingActivity() {
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
                id = "request-app-target-extra",
                toolName = MobileActionFunctions.OPEN_APP_DEEP_TARGET,
                arguments = mapOf(
                    "targetId" to AppDeepTargets.APP_DETAILS_SETTINGS_ID,
                    "packageName" to "com.example.app",
                    "uri" to "package:com.example.app/private",
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

    private fun assertExternalActivityOpened(
        data: Map<String, String>,
        targetKind: String,
        intentAction: String,
    ) {
        assertEquals("ExternalActivityOpened", data["completionState"])
        assertEquals("false", data["completionVerified"])
        assertEquals("Unknown", data["externalOutcome"])
        assertEquals(targetKind, data["targetKind"])
        assertEquals(intentAction, data["intentAction"])
        assertEquals("AllowlistedCompletionMetadata", data["metadataPolicy"])
        assertEquals("false", data["rawPayloadIncluded"])
    }

    private fun assertExternalActivityNotStarted(
        data: Map<String, String>,
        targetKind: String,
        intentAction: String,
    ) {
        assertEquals("NotStarted", data["completionState"])
        assertEquals("false", data["completionVerified"])
        assertEquals(targetKind, data["targetKind"])
        assertEquals(intentAction, data["intentAction"])
        assertEquals("AllowlistedCompletionMetadata", data["metadataPolicy"])
        assertEquals("false", data["rawPayloadIncluded"])
    }

    private class RecordingBackgroundTaskScheduler(
        private val failure: Throwable? = null,
    ) : BackgroundTaskScheduler {
        var lastReminderRequest: ReminderScheduleRequest? = null
            private set
        var lastCancelledTaskId: String? = null
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
            if (failure == null) {
                lastCancelledTaskId = taskId
                Result.success(Unit)
            } else {
                Result.failure(failure)
            }
    }
}
