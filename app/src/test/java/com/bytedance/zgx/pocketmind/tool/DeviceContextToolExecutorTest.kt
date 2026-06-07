package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.pocketmind.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.pocketmind.background.ReminderScheduleRequest
import com.bytedance.zgx.pocketmind.background.ScheduledTask
import com.bytedance.zgx.pocketmind.background.ScheduledTaskStatus
import com.bytedance.zgx.pocketmind.background.ScheduledTaskType
import com.bytedance.zgx.pocketmind.device.ContactSummaryItem
import com.bytedance.zgx.pocketmind.device.ContactSummaryProvider
import com.bytedance.zgx.pocketmind.device.ContactSummaryReadResult
import com.bytedance.zgx.pocketmind.device.CurrentScreenTextProvider
import com.bytedance.zgx.pocketmind.device.CurrentScreenTextReadResult
import com.bytedance.zgx.pocketmind.device.CurrentScreenTextSnapshot
import com.bytedance.zgx.pocketmind.device.ForegroundAppInfo
import com.bytedance.zgx.pocketmind.device.ForegroundAppProvider
import com.bytedance.zgx.pocketmind.device.ForegroundAppReadResult
import com.bytedance.zgx.pocketmind.device.NotificationSummaryItem
import com.bytedance.zgx.pocketmind.device.NotificationSummaryProvider
import com.bytedance.zgx.pocketmind.device.NotificationSummaryReadResult
import com.bytedance.zgx.pocketmind.device.RecentFileProvider
import com.bytedance.zgx.pocketmind.device.RecentFileReadResult
import com.bytedance.zgx.pocketmind.device.RecentImageTextItem
import com.bytedance.zgx.pocketmind.device.RecentImageTextProvider
import com.bytedance.zgx.pocketmind.device.RecentImageTextReadResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceContextToolExecutorTest {
    @Test
    fun foregroundAppSuccessReturnsLocalOnlyMinimalFields() {
        val executor = ForegroundAppToolExecutor(
            StaticForegroundAppProvider(
                ForegroundAppReadResult.Available(
                    ForegroundAppInfo(
                        packageName = "com.example.mail",
                        appLabel = "Mail",
                        lastTimeUsedMillis = 1_234L,
                    ),
                ),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "foreground",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(null, result.error)
        assertFalse(result.retryable)
        assertEquals(MobileActionFunctions.QUERY_FOREGROUND_APP, result.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals("usage_stats_estimate", result.data["source"])
        assertEquals("estimate", result.data["confidence"])
        assertEquals("com.example.mail", result.data["packageName"])
        assertEquals("Mail", result.data["appLabel"])
        assertEquals("1234", result.data["lastTimeUsedMillis"])
        assertFalse(result.data.containsKey("activity"))
        assertFalse(result.data.containsKey("intent"))
    }

    @Test
    fun foregroundAppPermissionDeniedAndFailureAreRetryableLocalFailures() {
        val executor = ForegroundAppToolExecutor(
            StaticForegroundAppProvider(
                ForegroundAppReadResult.PermissionDenied("usage access missing"),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "foreground",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertTrue(result.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("usage_stats", result.data["specialAccess"])
        assertEquals("android.settings.USAGE_ACCESS_SETTINGS", result.data["settingsAction"])
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, result.data["recoveryToolName"])
        assertFalse(result.data.containsKey("packageName"))

        val failed = ForegroundAppToolExecutor(
            StaticForegroundAppProvider(
                ForegroundAppReadResult.Failed("usage stats unavailable"),
            ),
        ).execute(
            ToolRequest(
                id = "foreground",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, failed.status)
        assertEquals(ToolErrorCode.ExecutionFailed, failed.error?.code)
        assertTrue(failed.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, failed.data["privacy"])
        assertFalse(failed.data.containsKey("packageName"))
    }

    @Test
    fun notificationSummarySuccessReturnsLocalOnlyMetadataOnlyJson() {
        val provider = RecordingNotificationSummaryProvider(
            NotificationSummaryReadResult.Available(
                listOf(
                    NotificationSummaryItem(
                        id = 7,
                        title = "Sync finished",
                        isOngoing = false,
                        postTimeMillis = 5_000L,
                    ),
                ),
            ),
        )
        val executor = NotificationSummaryToolExecutor(
            provider,
        )

        val result = executor.execute(
            ToolRequest(
                id = "notifications",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                arguments = mapOf("maxCount" to "3"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(null, result.error)
        assertFalse(result.retryable)
        assertEquals(3, provider.lastMaxCount)
        assertEquals(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS, result.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals("3", result.data["maxCount"])
        assertEquals("1", result.data["notificationCount"])
        val notifications = JSONArray(result.data.getValue("notificationsJson"))
        val notification = notifications.getJSONObject(0)
        assertEquals(setOf("id", "title", "isOngoing", "postTimeMillis"), notification.keysSet())
        assertEquals(7, notification.getInt("id"))
        assertEquals("Sync finished", notification.getString("title"))
        assertFalse(notification.has("text"))
        assertFalse(notification.has("extras"))

        val clampedProvider = RecordingNotificationSummaryProvider(
            NotificationSummaryReadResult.Available(emptyList()),
        )
        val clamped = NotificationSummaryToolExecutor(clampedProvider).execute(
            ToolRequest(
                id = "notifications-too-many",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                arguments = mapOf("maxCount" to "99"),
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Succeeded, clamped.status)
        assertEquals(20, clampedProvider.lastMaxCount)
        assertEquals("20", clamped.data["maxCount"])
    }

    @Test
    fun notificationSummaryPermissionDeniedAndFailureAreStructured() {
        val denied = NotificationSummaryToolExecutor(
            StaticNotificationSummaryProvider(
                NotificationSummaryReadResult.PermissionDenied("notifications disabled"),
            ),
        ).execute(
            ToolRequest(
                id = "notifications",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Failed, denied.status)
        assertEquals(ToolErrorCode.PermissionDenied, denied.error?.code)
        assertTrue(denied.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, denied.data["privacy"])
        assertFalse(denied.data.containsKey("notificationsJson"))

        val failed = NotificationSummaryToolExecutor(
            StaticNotificationSummaryProvider(
                NotificationSummaryReadResult.Failed("provider unavailable"),
            ),
        ).execute(
            ToolRequest(
                id = "notifications",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Failed, failed.status)
        assertEquals(ToolErrorCode.ExecutionFailed, failed.error?.code)
        assertTrue(failed.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, failed.data["privacy"])
        assertFalse(failed.data.containsKey("notificationsJson"))
    }

    @Test
    fun contactSummarySuccessReturnsMinimalLocalOnlyFields() {
        val provider = RecordingContactSummaryProvider(
            ContactSummaryReadResult.Available(
                listOf(ContactSummaryItem(name = "Alice", phone = "+1 555 0100")),
            ),
        )
        val executor = ContactSummaryToolExecutor(provider)

        val result = executor.execute(
            ToolRequest(
                id = "contacts",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to " Alice ", "maxCount" to "2"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(null, result.error)
        assertFalse(result.retryable)
        assertEquals("Alice", provider.lastQuery)
        assertEquals(2, provider.lastMaxCount)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, result.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals("Alice", result.data["query"])
        assertEquals("2", result.data["maxCount"])
        assertEquals("1", result.data["contactCount"])
        val contacts = JSONArray(result.data.getValue("contactsJson"))
        val contact = contacts.getJSONObject(0)
        assertEquals(setOf("name", "phone"), contact.keysSet())
        assertEquals("Alice", contact.getString("name"))
        assertEquals("+1 555 0100", contact.getString("phone"))
        assertFalse(contact.has("email"))
        assertFalse(contact.has("id"))

        val clampedProvider = RecordingContactSummaryProvider(
            ContactSummaryReadResult.Available(emptyList()),
        )
        val clamped = ContactSummaryToolExecutor(clampedProvider).execute(
            ToolRequest(
                id = "contacts-too-many",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to "Alice", "maxCount" to "99"),
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Succeeded, clamped.status)
        assertEquals(20, clampedProvider.lastMaxCount)
        assertEquals("20", clamped.data["maxCount"])
    }

    @Test
    fun contactSummaryFailureIsRetryableAndLocalOnly() {
        val executor = ContactSummaryToolExecutor(
            RecordingContactSummaryProvider(
                ContactSummaryReadResult.Failed("contacts provider down"),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "contacts",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to "Alice"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertFalse(result.data.containsKey("contactsJson"))
    }

    @Test
    fun recentFilesSuccessDefaultsToAll() {
        val provider = RecordingRecentFileProvider(RecentFileReadResult.Available(emptyList()))
        val executor = RecentFilesToolExecutor(provider)

        val result = executor.execute(
            ToolRequest(
                id = "files",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("all", provider.lastKind)
        assertEquals(5, provider.lastMaxCount)
        assertEquals("all", result.data["kind"])
        assertEquals("5", result.data["maxCount"])
        assertEquals("granted_media_only", result.data["mediaAccessScope"])
    }

    @Test
    fun recentScreenshotsSuccessStaysLocalOnlyAndMinimalJson() {
        val provider = RecordingRecentFileProvider(
            RecentFileReadResult.Available(
                items = listOf(
                    com.bytedance.zgx.pocketmind.device.RecentFileItem(
                        id = 42L,
                        name = "Screenshot_20260531.png",
                        mimeType = "image/jpeg",
                        kind = "screenshots",
                        sizeBytes = 2_048L,
                        lastModifiedMillis = 7_000L,
                    ),
                ),
                mediaAccessScope = "user_selected_visual_media",
            ),
        )
        val executor = RecentFilesToolExecutor(provider)

        val result = executor.execute(
            ToolRequest(
                id = "files",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "screenshots", "maxCount" to "1"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(null, result.error)
        assertFalse(result.retryable)
        assertEquals("screenshots", provider.lastKind)
        assertEquals(1, provider.lastMaxCount)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, result.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals("screenshots", result.data["kind"])
        assertEquals("1", result.data["maxCount"])
        assertEquals("user_selected_visual_media", result.data["mediaAccessScope"])
        val files = JSONArray(result.data.getValue("filesJson"))
        val file = files.getJSONObject(0)
        assertEquals(setOf("name", "mimeType", "kind", "sizeBytes", "lastModifiedMillis"), file.keysSet())
        assertEquals("screenshots", file.getString("kind"))
        assertFalse(file.has("id"))
        assertFalse(file.has("path"))
        assertFalse(file.has("uri"))
        assertFalse(file.has("content"))
    }

    @Test
    fun recentFilesExecutionFailureIsRetryableAndLocalOnly() {
        val executor = RecentFilesToolExecutor(
            StaticRecentFileProvider(
                RecentFileReadResult.Failed("media store unavailable"),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "files",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "images"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertFalse(result.data.containsKey("filesJson"))
    }

    @Test
    fun recentFilesPermissionDeniedPreservesProviderReason() {
        val executor = RecentFilesToolExecutor(
            StaticRecentFileProvider(
                RecentFileReadResult.PermissionDenied(
                    reason = "当前 Android 版本需要通过系统文件选择器授权非媒体文件",
                    retryable = false,
                ),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "files",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "documents"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertEquals("无法读取最近文件：当前 Android 版本需要通过系统文件选择器授权非媒体文件", result.summary)
        assertEquals(result.summary, result.error?.message)
        assertFalse(result.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertFalse(result.data.containsKey("filesJson"))
    }

    @Test
    fun backgroundTasksQueryReturnsLocalOnlyTaskAndPolicyMetadataWithoutReminderContent() {
        val scheduler = RecordingBackgroundTaskScheduler(
            activeTasks = listOf(
                ScheduledTask(
                    id = "task-active",
                    type = ScheduledTaskType.Reminder,
                    title = "Doctor appointment",
                    body = "secret reminder body",
                    triggerAtMillis = 10_000L,
                    status = ScheduledTaskStatus.Scheduled,
                    createdAtMillis = 1_000L,
                    updatedAtMillis = 2_000L,
                ),
            ),
            historyTasks = listOf(
                ScheduledTask(
                    id = "task-history",
                    type = ScheduledTaskType.Reminder,
                    title = "Paid invoice",
                    body = "private completed body",
                    triggerAtMillis = 8_000L,
                    status = ScheduledTaskStatus.Delivered,
                    createdAtMillis = 3_000L,
                    updatedAtMillis = 4_000L,
                ),
            ),
            policy = PeriodicCheckPolicySummary(
                request = PeriodicCheckScheduleRequest(
                    enabled = true,
                    intervalMinutes = 120L,
                    minNotificationSpacingMinutes = 180L,
                    overdueGraceMinutes = 30L,
                ),
                taskStatus = ScheduledTaskStatus.Scheduled,
                nextAllowedRunAtMillis = 12_000L,
                lastRunSummary = "lastRun=secret",
                updatedAtMillis = 5_000L,
            ),
        )
        val executor = BackgroundTasksToolExecutor(scheduler)

        val result = executor.execute(
            ToolRequest(
                id = "background-tasks",
                toolName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
                arguments = mapOf("scope" to "all", "maxCount" to "3"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(null, result.error)
        assertFalse(result.retryable)
        assertEquals(3, scheduler.scheduledLimit)
        assertEquals(3, scheduler.recentLimit)
        assertTrue(scheduler.policyRead)
        assertTrue(scheduler.mutationCalls.isEmpty())
        assertEquals(MobileActionFunctions.QUERY_BACKGROUND_TASKS, result.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals("all", result.data["scope"])
        assertEquals("local_store", result.data["source"])
        assertEquals("false", result.data["rawPayloadIncluded"])
        assertEquals("background_tasks_local_only_no_reminder_body", result.data["metadataPolicy"])
        assertEquals("1", result.data["activeTaskCount"])
        assertEquals("1", result.data["historyTaskCount"])
        assertTrue(result.summary.contains("元数据"))
        assertFalse(result.summary.contains("Doctor appointment"))
        assertFalse(result.summary.contains("Paid invoice"))
        assertFalse(result.summary.contains("secret reminder body"))
        assertFalse(result.summary.contains("private completed body"))
        assertFalse(result.data.toString().contains("Doctor appointment"))
        assertFalse(result.data.toString().contains("Paid invoice"))
        assertFalse(result.data.toString().contains("secret reminder body"))
        assertFalse(result.data.toString().contains("private completed body"))
        val tasks = JSONArray(result.data.getValue("tasksJson"))
        assertEquals(2, tasks.length())
        val task = tasks.getJSONObject(0)
        assertEquals(
            setOf("scope", "id", "type", "status", "triggerAtMillis", "createdAtMillis", "updatedAtMillis"),
            task.keysSet(),
        )
        assertFalse(task.has("title"))
        assertFalse(task.has("body"))
        assertFalse(task.has("prompt"))
        assertFalse(task.has("text"))
        val policy = JSONObject(result.data.getValue("policyJson"))
        assertEquals(true, policy.getBoolean("enabled"))
        assertEquals(120, policy.getInt("intervalMinutes"))
        assertEquals(false, policy.getBoolean("lastRunSummaryIncluded"))
        assertFalse(policy.has("lastRunSummary"))
    }

    @Test
    fun backgroundTasksPolicyScopeDoesNotReadTaskLists() {
        val scheduler = RecordingBackgroundTaskScheduler(
            policy = PeriodicCheckPolicySummary.disabled(),
        )

        val result = BackgroundTasksToolExecutor(scheduler).execute(
            ToolRequest(
                id = "background-policy",
                toolName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
                arguments = mapOf("scope" to "policy"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(null, scheduler.scheduledLimit)
        assertEquals(null, scheduler.recentLimit)
        assertTrue(scheduler.policyRead)
        assertFalse(result.data.containsKey("tasksJson"))
        assertTrue(result.data.containsKey("policyJson"))
        assertTrue(scheduler.mutationCalls.isEmpty())
    }

    @Test
    fun recentScreenshotOcrSuccessReturnsLocalOnlyTextWithoutImageIdentifiers() {
        val provider = RecordingRecentImageTextProvider(
            RecentImageTextReadResult.Available(
                item = RecentImageTextItem(
                    name = "Screenshot_20260531.png",
                    mimeType = "image/png",
                    kind = "screenshots",
                    sizeBytes = 2_048L,
                    lastModifiedMillis = 7_000L,
                    text = "标题\n正文\n\n页脚",
                    truncated = true,
                ),
                scannedCount = 1,
                mediaAccessScope = "user_selected_visual_media",
            ),
        )
        val executor = RecentScreenshotOcrToolExecutor(provider)

        val result = executor.execute(
            ToolRequest(
                id = "screenshot-ocr",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                arguments = mapOf("maxCount" to "1"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(null, result.error)
        assertFalse(result.retryable)
        assertEquals("screenshots", provider.lastKind)
        assertEquals(1, provider.lastMaxCount)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, result.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals("标题\n正文\n\n页脚", result.data["ocrText"])
        assertEquals("true", result.data["truncated"])
        assertEquals("true", result.data["ocrTextIncluded"])
        assertEquals("false", result.data["rawPayloadIncluded"])
        assertEquals("user_selected_visual_media", result.data["mediaAccessScope"])
        assertEquals("ocr_text_local_only_no_uri_path_or_pixels_persisted", result.data["metadataPolicy"])
        assertFalse(result.data.containsKey("id"))
        assertFalse(result.data.containsKey("path"))
        assertFalse(result.data.containsKey("uri"))
        assertFalse(result.data.containsKey("content"))
        assertFalse(result.data.containsKey("pixels"))
    }

    @Test
    fun recentImageOcrSuccessScansImagesAndReturnsLocalOnlyTextWithoutImageIdentifiers() {
        val provider = RecordingRecentImageTextProvider(
            RecentImageTextReadResult.Available(
                item = RecentImageTextItem(
                    name = "IMG_20260531.jpg",
                    mimeType = "image/jpeg",
                    kind = "images",
                    sizeBytes = 4_096L,
                    lastModifiedMillis = 8_000L,
                    text = "照片标题\n\n照片里的文字",
                    truncated = false,
                ),
                scannedCount = 2,
                mediaAccessScope = "full_visual_media",
            ),
        )
        val executor = RecentScreenshotOcrToolExecutor(provider)

        val result = executor.execute(
            ToolRequest(
                id = "image-ocr",
                toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                arguments = mapOf("maxCount" to "3"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("images", provider.lastKind)
        assertEquals(3, provider.lastMaxCount)
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, result.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals("照片标题\n\n照片里的文字", result.data["ocrText"])
        assertEquals("false", result.data["truncated"])
        assertEquals("true", result.data["ocrTextIncluded"])
        assertEquals("full_visual_media", result.data["mediaAccessScope"])
        assertEquals("ocr_text_local_only_no_uri_path_or_pixels_persisted", result.data["metadataPolicy"])
        assertFalse(result.data.containsKey("id"))
        assertFalse(result.data.containsKey("path"))
        assertFalse(result.data.containsKey("uri"))
        assertFalse(result.data.containsKey("content"))
        assertFalse(result.data.containsKey("pixels"))
    }

    @Test
    fun recentScreenshotOcrNoTextIsSuccessfulLocalOnlyMetadata() {
        val executor = RecentScreenshotOcrToolExecutor(
            StaticRecentImageTextProvider(
                RecentImageTextReadResult.Available(item = null, scannedCount = 1),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "screenshot-ocr",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("false", result.data["ocrTextIncluded"])
        assertFalse(result.data.containsKey("ocrText"))
    }

    @Test
    fun recentScreenshotOcrPermissionDeniedAndFailureAreStructured() {
        val denied = RecentScreenshotOcrToolExecutor(
            StaticRecentImageTextProvider(
                RecentImageTextReadResult.PermissionDenied("images permission missing"),
            ),
        ).execute(
            ToolRequest(
                id = "screenshot-ocr",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Failed, denied.status)
        assertEquals(ToolErrorCode.PermissionDenied, denied.error?.code)
        assertTrue(denied.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, denied.data["privacy"])
        assertFalse(denied.data.containsKey("ocrText"))

        val failed = RecentScreenshotOcrToolExecutor(
            StaticRecentImageTextProvider(
                RecentImageTextReadResult.Failed(
                    "content://media/external/images/media/1 /sdcard/DCIM/Screenshots/private.png",
                ),
            ),
        ).execute(
            ToolRequest(
                id = "screenshot-ocr",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Failed, failed.status)
        assertEquals(ToolErrorCode.ExecutionFailed, failed.error?.code)
        assertTrue(failed.retryable)
        assertFalse(failed.summary.contains("content://"))
        assertFalse(failed.summary.contains("/sdcard"))
        assertEquals(MessagePrivacy.LocalOnly.name, failed.data["privacy"])
        assertFalse(failed.data.containsKey("ocrText"))
    }

    @Test
    fun currentScreenTextSuccessReturnsLocalOnlyTextWithoutNodeTree() {
        val provider = RecordingCurrentScreenTextProvider(
            CurrentScreenTextReadResult.Available(
                CurrentScreenTextSnapshot(
                    text = "标题\n按钮",
                    packageName = "com.example.app",
                    capturedAtMillis = 9_000L,
                    nodeCount = 4,
                    truncated = true,
                    structureSummary = "nodeCount=4; visibleTextItemCount=2; textSnapshotIncluded=true",
                ),
            ),
        )
        val executor = CurrentScreenTextToolExecutor(provider)

        val result = executor.execute(
            ToolRequest(
                id = "screen-text",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                arguments = mapOf("maxChars" to "1200"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(null, result.error)
        assertFalse(result.retryable)
        assertEquals(1200, provider.lastMaxChars)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, result.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals("true", result.data["requiresLocalModel"])
        assertEquals("accessibility_active_window", result.data["source"])
        assertEquals("com.example.app", result.data["packageName"])
        assertEquals("9000", result.data["capturedAtMillis"])
        assertEquals("4", result.data["nodeCount"])
        assertEquals("标题\n按钮", result.data["screenText"])
        assertEquals("true", result.data["truncated"])
        assertEquals("true", result.data["screenTextIncluded"])
        assertEquals("nodeCount=4; visibleTextItemCount=2; textSnapshotIncluded=true", result.data["structureSummary"])
        assertEquals("true", result.data["structureSummaryIncluded"])
        assertEquals("false", result.data["rawTreeIncluded"])
        assertEquals(
            "accessibility_text_local_only_no_node_ids_bounds_or_hierarchy_persisted",
            result.data["metadataPolicy"],
        )
        assertFalse(result.data.containsKey("nodeTree"))
        assertFalse(result.data.containsKey("viewId"))
        assertFalse(result.data.containsKey("bounds"))
        assertFalse(result.data.containsKey("pixels"))
    }

    @Test
    fun currentScreenTextNoTextPermissionDeniedAndFailureAreStructured() {
        val empty = CurrentScreenTextToolExecutor(
            StaticCurrentScreenTextProvider(
                CurrentScreenTextReadResult.Available(
                    CurrentScreenTextSnapshot(
                        text = "",
                        packageName = null,
                        capturedAtMillis = 10_000L,
                        nodeCount = 0,
                        truncated = false,
                    ),
                ),
            ),
        ).execute(
            ToolRequest(
                id = "screen-empty",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Succeeded, empty.status)
        assertEquals(MessagePrivacy.LocalOnly.name, empty.data["privacy"])
        assertEquals("false", empty.data["screenTextIncluded"])
        assertEquals("false", empty.data["structureSummaryIncluded"])
        assertFalse(empty.data.containsKey("screenText"))

        val denied = CurrentScreenTextToolExecutor(
            StaticCurrentScreenTextProvider(
                CurrentScreenTextReadResult.PermissionDenied("accessibility disabled"),
            ),
        ).execute(
            ToolRequest(
                id = "screen-denied",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Failed, denied.status)
        assertEquals(ToolErrorCode.PermissionDenied, denied.error?.code)
        assertTrue(denied.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, denied.data["privacy"])
        assertEquals("accessibility_screen_text", denied.data["specialAccess"])
        assertEquals("android.settings.ACCESSIBILITY_SETTINGS", denied.data["settingsAction"])
        assertFalse(denied.data.containsKey("screenText"))

        val failed = CurrentScreenTextToolExecutor(
            StaticCurrentScreenTextProvider(
                CurrentScreenTextReadResult.Failed("node dump private text"),
            ),
        ).execute(
            ToolRequest(
                id = "screen-failed",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                reason = "test",
            ),
        )
        assertEquals(ToolStatus.Failed, failed.status)
        assertEquals(ToolErrorCode.ExecutionFailed, failed.error?.code)
        assertTrue(failed.retryable)
        assertFalse(failed.summary.contains("node dump private text"))
        assertEquals(MessagePrivacy.LocalOnly.name, failed.data["privacy"])
        assertFalse(failed.data.containsKey("screenText"))
    }

    @Test
    fun directDeviceContextExecutorsRejectWrongToolName() {
        val request = ToolRequest(
            id = "wrong",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = "test",
        )

        val results = listOf(
            ForegroundAppToolExecutor(
                StaticForegroundAppProvider(
                    ForegroundAppReadResult.Failed("not used"),
                ),
            ).execute(request),
            NotificationSummaryToolExecutor(
                StaticNotificationSummaryProvider(
                    NotificationSummaryReadResult.Failed("not used"),
                ),
            ).execute(request),
            ContactSummaryToolExecutor(
                RecordingContactSummaryProvider(
                    ContactSummaryReadResult.Failed("not used"),
                ),
            ).execute(request),
            RecentFilesToolExecutor(
                StaticRecentFileProvider(
                    RecentFileReadResult.Failed("not used"),
                ),
            ).execute(request),
            BackgroundTasksToolExecutor(
                RecordingBackgroundTaskScheduler(),
            ).execute(request),
            RecentScreenshotOcrToolExecutor(
                StaticRecentImageTextProvider(
                    RecentImageTextReadResult.Failed("not used"),
                ),
            ).execute(request),
            CurrentScreenTextToolExecutor(
                StaticCurrentScreenTextProvider(
                    CurrentScreenTextReadResult.Failed("not used"),
                ),
            ).execute(request),
        )

        results.forEach { result ->
            assertEquals(ToolStatus.Failed, result.status)
            assertEquals(ToolErrorCode.UnknownTool, result.error?.code)
            assertFalse(result.retryable)
        }
    }

    private class StaticForegroundAppProvider(
        private val result: ForegroundAppReadResult,
    ) : ForegroundAppProvider {
        override fun currentForegroundApp(): ForegroundAppReadResult = result
    }

    private class StaticNotificationSummaryProvider(
        private val result: NotificationSummaryReadResult,
    ) : NotificationSummaryProvider {
        override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult = result
    }

    private class RecordingNotificationSummaryProvider(
        private val result: NotificationSummaryReadResult,
    ) : NotificationSummaryProvider {
        var lastMaxCount: Int? = null
            private set

        override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult {
            lastMaxCount = maxCount
            return result
        }
    }

    private class RecordingContactSummaryProvider(
        private val result: ContactSummaryReadResult,
    ) : ContactSummaryProvider {
        var lastQuery: String? = null
            private set
        var lastMaxCount: Int? = null
            private set

        override fun queryContacts(query: String, maxCount: Int): ContactSummaryReadResult {
            lastQuery = query
            lastMaxCount = maxCount
            return result
        }
    }

    private class StaticRecentFileProvider(
        private val result: RecentFileReadResult,
    ) : RecentFileProvider {
        override fun recentFiles(kind: String, maxCount: Int): RecentFileReadResult = result
    }

    private class RecordingRecentFileProvider(
        private val result: RecentFileReadResult,
    ) : RecentFileProvider {
        var lastKind: String? = null
            private set
        var lastMaxCount: Int? = null
            private set

        override fun recentFiles(kind: String, maxCount: Int): RecentFileReadResult {
            lastKind = kind
            lastMaxCount = maxCount
            return result
        }
    }

    private class RecordingBackgroundTaskScheduler(
        private val activeTasks: List<ScheduledTask> = emptyList(),
        private val historyTasks: List<ScheduledTask> = emptyList(),
        private val policy: PeriodicCheckPolicySummary = PeriodicCheckPolicySummary.disabled(),
    ) : BackgroundTaskScheduler {
        var scheduledLimit: Int? = null
            private set
        var recentLimit: Int? = null
            private set
        var policyRead: Boolean = false
            private set
        val mutationCalls = mutableListOf<String>()

        override fun scheduledTasks(limit: Int): List<ScheduledTask> {
            scheduledLimit = limit
            return activeTasks.take(limit)
        }

        override fun recentTasks(limit: Int): List<ScheduledTask> {
            recentLimit = limit
            return historyTasks.take(limit)
        }

        override fun periodicCheckPolicy(): PeriodicCheckPolicySummary {
            policyRead = true
            return policy
        }

        override fun setPeriodicCheckPolicy(request: PeriodicCheckScheduleRequest): Result<PeriodicCheckPolicySummary> {
            mutationCalls += "setPeriodicCheckPolicy"
            return Result.success(policy)
        }

        override fun disablePeriodicCheckPolicy(): Result<PeriodicCheckPolicySummary> {
            mutationCalls += "disablePeriodicCheckPolicy"
            return Result.success(policy)
        }

        override fun scheduleReminder(request: ReminderScheduleRequest): Result<ScheduledTask> {
            mutationCalls += "scheduleReminder"
            return Result.failure(UnsupportedOperationException("not used"))
        }

        override fun cancel(taskId: String): Result<Unit> {
            mutationCalls += "cancel"
            return Result.success(Unit)
        }
    }

    private class StaticRecentImageTextProvider(
        private val result: RecentImageTextReadResult,
    ) : RecentImageTextProvider {
        override fun extractRecentImageText(kind: String, maxCount: Int): RecentImageTextReadResult = result
    }

    private class RecordingRecentImageTextProvider(
        private val result: RecentImageTextReadResult,
    ) : RecentImageTextProvider {
        var lastKind: String? = null
            private set
        var lastMaxCount: Int? = null
            private set

        override fun extractRecentImageText(kind: String, maxCount: Int): RecentImageTextReadResult {
            lastKind = kind
            lastMaxCount = maxCount
            return result
        }
    }

    private class StaticCurrentScreenTextProvider(
        private val result: CurrentScreenTextReadResult,
    ) : CurrentScreenTextProvider {
        override fun currentScreenText(maxChars: Int): CurrentScreenTextReadResult = result
    }

    private class RecordingCurrentScreenTextProvider(
        private val result: CurrentScreenTextReadResult,
    ) : CurrentScreenTextProvider {
        var lastMaxChars: Int? = null
            private set

        override fun currentScreenText(maxChars: Int): CurrentScreenTextReadResult {
            lastMaxChars = maxChars
            return result
        }
    }
}

private fun org.json.JSONObject.keysSet(): Set<String> {
    val result = linkedSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        result += iterator.next()
    }
    return result
}
