package com.bytedance.zgx.pocketmind.action

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.pocketmind.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.pocketmind.background.ReminderScheduleRequest
import com.bytedance.zgx.pocketmind.background.ScheduledTask
import com.bytedance.zgx.pocketmind.background.ScheduledTaskStatus
import com.bytedance.zgx.pocketmind.background.ScheduledTaskType
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.MAX_SHARE_TEXT_CHARS
import com.bytedance.zgx.pocketmind.tool.MAX_SHARE_TITLE_CHARS
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
    fun configuresPeriodicCheckThroughBackgroundScheduler() {
        val scheduler = RecordingBackgroundTaskScheduler()
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = scheduler,
            canPostReminderNotifications = { true },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-periodic-check",
                toolName = MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                arguments = mapOf(
                    "enabled" to "true",
                    "intervalMinutes" to "120",
                    "minNotificationSpacingMinutes" to "180",
                    "overdueGraceMinutes" to "15",
                    "requiresBatteryNotLow" to "false",
                    "requiresCharging" to "true",
                ),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("true", result.data["enabled"])
        assertEquals("Scheduled", result.data["taskStatus"])
        assertEquals("120", result.data["intervalMinutes"])
        assertEquals("180", result.data["minNotificationSpacingMinutes"])
        assertEquals("15", result.data["overdueGraceMinutes"])
        assertEquals("false", result.data["requiresBatteryNotLow"])
        assertEquals("true", result.data["requiresCharging"])
        assertEquals(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK, result.data["recoveryToolName"])
        assertEquals("false", result.data["recoveryEnabled"])
        assertEquals(120L, scheduler.lastPeriodicCheckRequest?.intervalMinutes)
        assertEquals(180L, scheduler.lastPeriodicCheckRequest?.minNotificationSpacingMinutes)
        assertEquals(15L, scheduler.lastPeriodicCheckRequest?.overdueGraceMinutes)
        assertEquals(false, scheduler.lastPeriodicCheckRequest?.constraints?.requiresBatteryNotLow)
        assertEquals(true, scheduler.lastPeriodicCheckRequest?.constraints?.requiresCharging)
    }

    @Test
    fun disablesPeriodicCheckThroughBackgroundScheduler() {
        val scheduler = RecordingBackgroundTaskScheduler()
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = scheduler,
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-disable-periodic-check",
                toolName = MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                arguments = mapOf("enabled" to "false"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("false", result.data["enabled"])
        assertEquals("Cancelled", result.data["taskStatus"])
        assertEquals(1, scheduler.disablePeriodicCheckCount)
    }

    @Test
    fun rejectsPeriodicCheckWhenNotificationPermissionIsMissing() {
        val scheduler = RecordingBackgroundTaskScheduler()
        val executor = ActionExecutor(
            context = null,
            backgroundTaskScheduler = scheduler,
            canPostReminderNotifications = { false },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-periodic-check",
                toolName = MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
                arguments = mapOf("enabled" to "true"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertTrue(result.summary.contains("通知权限"))
        assertEquals(null, scheduler.lastPeriodicCheckRequest)
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
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
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
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
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
    fun opensCameraAsSystemCameraIntent() {
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
                id = "request-open-camera",
                toolName = MobileActionFunctions.OPEN_CAMERA,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MobileActionFunctions.OPEN_CAMERA, result.data["toolName"])
        assertExternalActivityOpened(result.data, MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        val launch = launches.single()
        assertEquals(MobileActionFunctions.OPEN_CAMERA, launch.toolName)
        assertEquals(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, launch.action)
        assertEquals(null, launch.uri)
        assertEquals(null, launch.packageName)
        assertEquals(null, launch.targetId)
    }

    @Test
    fun opensAllowlistedSystemSettingsTargetAsSettingsIntent() {
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
                id = "request-open-bluetooth-settings",
                toolName = MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
                arguments = mapOf("target" to SystemSettingsTargets.BLUETOOTH),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MobileActionFunctions.OPEN_SYSTEM_SETTINGS, result.data["toolName"])
        assertExternalActivityOpened(result.data, "SystemSettings", Settings.ACTION_BLUETOOTH_SETTINGS)
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, result.data["settingsAction"])
        assertEquals(SystemSettingsTargets.BLUETOOTH, result.data["targetId"])
        val launch = launches.single()
        assertEquals(MobileActionFunctions.OPEN_SYSTEM_SETTINGS, launch.toolName)
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, launch.action)
        assertEquals(SystemSettingsTargets.BLUETOOTH, launch.targetId)
    }

    @Test
    fun rejectsUnknownSystemSettingsTarget() {
        val executor = ActionExecutor(context = null)

        val result = executor.execute(
            ToolRequest(
                id = "request-open-unknown-settings",
                toolName = MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
                arguments = mapOf("target" to "developer_options"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
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
    fun opensAppByNameThroughResolvedLauncherPackage() {
        val launches = mutableListOf<ExternalActivityLaunch>()
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = { launch ->
                launches += launch
                true
            },
            appLauncherResolver = { appName ->
                assertEquals("淘宝", appName)
                AppLaunchResolution.Resolved(label = "淘宝", packageName = "com.taobao.taobao")
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-app-by-name",
                toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                arguments = mapOf("appName" to "淘宝"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertExternalActivityOpened(result.data, "AndroidPackage", Intent.ACTION_MAIN)
        assertEquals("com.taobao.taobao", result.data["targetPackage"])
        val launch = launches.single()
        assertEquals(MobileActionFunctions.OPEN_APP_BY_NAME, launch.toolName)
        assertEquals(Intent.ACTION_MAIN, launch.action)
        assertEquals("com.taobao.taobao", launch.packageName)
        assertEquals(null, launch.uri)
    }

    @Test
    fun reportsAppByNameResolutionFailureWithoutLeakingCandidates() {
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = { false },
            appLauncherResolver = { AppLaunchResolution.NotFound },
        )

        val result = executor.execute(
            ToolRequest(
                id = "request-app-by-name-missing",
                toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                arguments = mapOf("appName" to "不存在的应用"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.NoActivityFound, result.error?.code)
        assertTrue(result.summary.contains("没有找到匹配的可启动应用"))
        assertExternalActivityNotStarted(result.data, "AndroidPackage", Intent.ACTION_MAIN)
        assertTrue(!result.data.containsKey("appName"))
    }

    @Test
    fun matchesLaunchableAppsByVisibleNameAliasesAndRejectsAmbiguity() {
        val entries = listOf(
            LaunchableAppEntry(label = "淘宝", packageName = "com.taobao.taobao"),
            LaunchableAppEntry(label = "拼多多", packageName = "com.xunmeng.pinduoduo"),
            LaunchableAppEntry(label = "拼多多商家版", packageName = "com.xunmeng.merchant"),
        )

        val taobao = matchLaunchableApp("taobao", entries)
        require(taobao is AppLaunchResolution.Resolved)
        assertEquals("com.taobao.taobao", taobao.packageName)

        val pinduoduo = matchLaunchableApp("拼多多", entries)
        require(pinduoduo is AppLaunchResolution.Resolved)
        assertEquals("com.xunmeng.pinduoduo", pinduoduo.packageName)

        assertEquals(AppLaunchResolution.Ambiguous, matchLaunchableApp("拼", entries))
        assertEquals(AppLaunchResolution.NotFound, matchLaunchableApp("微信", entries))
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
        assertEquals("外部应用或系统页面启动失败", result.summary)
        assertEquals(result.summary, result.error?.message)
        assertFalse(result.summary.contains("launcher unavailable"))
        assertFalse(result.data.toString().contains("launcher unavailable"))
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
    fun rejectsOversizedShareTextBeforeStartingActivity() {
        val launches = mutableListOf<ExternalActivityLaunch>()
        val executor = ActionExecutor(
            context = null,
            externalActivityStarter = { launch ->
                launches += launch
                true
            },
        )

        val oversizedText = executor.execute(
            ToolRequest(
                id = "request-share-text-too-long",
                toolName = MobileActionFunctions.SHARE_TEXT,
                arguments = mapOf("text" to "x".repeat(MAX_SHARE_TEXT_CHARS + 1)),
                reason = "test",
            ),
        )
        val oversizedTitle = executor.execute(
            ToolRequest(
                id = "request-share-title-too-long",
                toolName = MobileActionFunctions.SHARE_TEXT,
                arguments = mapOf(
                    "text" to "hello",
                    "title" to "x".repeat(MAX_SHARE_TITLE_CHARS + 1),
                ),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, oversizedText.status)
        assertEquals(ToolErrorCode.InvalidRequest, oversizedText.error?.code)
        assertFalse(oversizedText.retryable)
        assertEquals(ToolStatus.Failed, oversizedTitle.status)
        assertEquals(ToolErrorCode.InvalidRequest, oversizedTitle.error?.code)
        assertFalse(oversizedTitle.retryable)
        assertTrue(launches.isEmpty())
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
        assertEquals("Unknown", data["externalOutcomeSource"])
        assertEquals(targetKind, data["targetKind"])
        assertEquals(intentAction, data["intentAction"])
        assertEquals("AllowlistedCompletionMetadata", data["metadataPolicy"])
        assertEquals("false", data["rawPayloadIncluded"])
    }

    private fun assertExternalActivityOpened(
        data: Map<String, String>,
        intentAction: String,
    ) {
        assertEquals("ExternalActivityOpened", data["completionState"])
        assertEquals("false", data["completionVerified"])
        assertEquals("Unknown", data["externalOutcome"])
        assertEquals("Unknown", data["externalOutcomeSource"])
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
        assertEquals("Unknown", data["externalOutcome"])
        assertEquals("Unknown", data["externalOutcomeSource"])
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
        var lastPeriodicCheckRequest: PeriodicCheckScheduleRequest? = null
            private set
        var disablePeriodicCheckCount: Int = 0
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

        override fun setPeriodicCheckPolicy(
            request: PeriodicCheckScheduleRequest,
        ): Result<PeriodicCheckPolicySummary> {
            lastPeriodicCheckRequest = request
            failure?.let { return Result.failure(it) }
            val normalized = request.normalized()
            return Result.success(
                PeriodicCheckPolicySummary(
                    request = normalized,
                    taskStatus = ScheduledTaskStatus.Scheduled,
                    nextAllowedRunAtMillis = 10_000L,
                    lastRunSummary = null,
                    updatedAtMillis = 2_000L,
                ),
            )
        }

        override fun disablePeriodicCheckPolicy(): Result<PeriodicCheckPolicySummary> {
            disablePeriodicCheckCount += 1
            failure?.let { return Result.failure(it) }
            return Result.success(
                PeriodicCheckPolicySummary(
                    request = PeriodicCheckScheduleRequest(enabled = false).normalized(),
                    taskStatus = ScheduledTaskStatus.Cancelled,
                    nextAllowedRunAtMillis = null,
                    lastRunSummary = null,
                    updatedAtMillis = 3_000L,
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
