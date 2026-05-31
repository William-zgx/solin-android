package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.device.ContactSummaryItem
import com.bytedance.zgx.pocketmind.device.ContactSummaryProvider
import com.bytedance.zgx.pocketmind.device.ContactSummaryReadResult
import com.bytedance.zgx.pocketmind.device.ForegroundAppInfo
import com.bytedance.zgx.pocketmind.device.ForegroundAppProvider
import com.bytedance.zgx.pocketmind.device.ForegroundAppReadResult
import com.bytedance.zgx.pocketmind.device.NotificationSummaryItem
import com.bytedance.zgx.pocketmind.device.NotificationSummaryProvider
import com.bytedance.zgx.pocketmind.device.NotificationSummaryReadResult
import com.bytedance.zgx.pocketmind.device.RecentFileProvider
import com.bytedance.zgx.pocketmind.device.RecentFileReadResult
import org.json.JSONArray
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
    }

    @Test
    fun recentScreenshotsSuccessStaysLocalOnlyAndMinimalJson() {
        val provider = RecordingRecentFileProvider(
            RecentFileReadResult.Available(
                listOf(
                    com.bytedance.zgx.pocketmind.device.RecentFileItem(
                        id = 42L,
                        name = "Screenshot_20260531.png",
                        mimeType = "image/jpeg",
                        kind = "screenshots",
                        sizeBytes = 2_048L,
                        lastModifiedMillis = 7_000L,
                    ),
                ),
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
}

private fun org.json.JSONObject.keysSet(): Set<String> {
    val result = linkedSetOf<String>()
    val iterator = keys()
    while (iterator.hasNext()) {
        result += iterator.next()
    }
    return result
}
