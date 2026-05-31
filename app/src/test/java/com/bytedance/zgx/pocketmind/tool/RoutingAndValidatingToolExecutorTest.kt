package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityProvider
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQuery
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityReadResult
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityWindow
import com.bytedance.zgx.pocketmind.device.ContactSummaryItem
import com.bytedance.zgx.pocketmind.device.ContactSummaryProvider
import com.bytedance.zgx.pocketmind.device.ContactSummaryReadResult
import com.bytedance.zgx.pocketmind.device.ForegroundAppInfo
import com.bytedance.zgx.pocketmind.device.ForegroundAppProvider
import com.bytedance.zgx.pocketmind.device.ForegroundAppReadResult
import com.bytedance.zgx.pocketmind.device.NotificationSummaryItem
import com.bytedance.zgx.pocketmind.device.NotificationSummaryProvider
import com.bytedance.zgx.pocketmind.device.NotificationSummaryReadResult
import com.bytedance.zgx.pocketmind.device.RecentFileItem
import com.bytedance.zgx.pocketmind.device.RecentFileProvider
import com.bytedance.zgx.pocketmind.device.RecentFileReadResult
import com.bytedance.zgx.pocketmind.device.RecentImageTextItem
import com.bytedance.zgx.pocketmind.device.RecentImageTextProvider
import com.bytedance.zgx.pocketmind.device.RecentImageTextReadResult
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingAndValidatingToolExecutorTest {
    @Test
    fun routingExecutorDispatchesDeviceContextToolsBeforeDelegate() {
        val delegate = RecordingDelegate()
        val executor = routingExecutor(delegate = delegate)

        val requests = listOf(
            ToolRequest(
                id = "calendar",
                toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                arguments = mapOf(
                    "start" to "2026-06-01T09:00:00Z",
                    "end" to "2026-06-01T10:00:00Z",
                ),
                reason = "test",
            ) to "busyBlockCount",
            ToolRequest(
                id = "foreground",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                reason = "test",
            ) to "packageName",
            ToolRequest(
                id = "contacts",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to "Alice"),
                reason = "test",
            ) to "contactsJson",
            ToolRequest(
                id = "notifications",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                reason = "test",
            ) to "notificationsJson",
            ToolRequest(
                id = "files",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                reason = "test",
            ) to "filesJson",
            ToolRequest(
                id = "screenshot-ocr",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                reason = "test",
            ) to "ocrText",
        )

        requests.forEach { (request, routedDataKey) ->
            val result = executor.execute(request)

            assertEquals(ToolStatus.Succeeded, result.status)
            assertEquals(request.toolName, result.data["toolName"])
            assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
            assertTrue(result.data.containsKey(routedDataKey))
        }
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorDelegatesOrdinaryTools() {
        val delegate = RecordingDelegate()
        val executor = routingExecutor(delegate = delegate)
        val request = ToolRequest(
            id = "wifi",
            toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
            reason = "test",
        )

        val result = executor.execute(request)

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(listOf(request), delegate.requests)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.data["toolName"])
    }

    @Test
    fun validatingExecutorRejectsInvalidRequestBeforeDelegate() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(delegate)

        val result = executor.execute(
            ToolRequest(
                id = "email",
                toolName = MobileActionFunctions.COMPOSE_EMAIL,
                arguments = mapOf("subject" to "Hi"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Rejected, result.status)
        assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
        assertFalse(result.retryable)
        assertTrue(result.summary.contains("requires argument"))
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun validatingExecutorRejectsWrongArgumentValuesBeforeDelegate() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(delegate)

        val invalidKind = executor.execute(
            ToolRequest(
                id = "files-kind",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "archives"),
                reason = "test",
            ),
        )
        val blankKind = executor.execute(
            ToolRequest(
                id = "files-blank-kind",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to " "),
                reason = "test",
            ),
        )
        val invalidRange = executor.execute(
            ToolRequest(
                id = "files-range",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("maxCount" to "51"),
                reason = "test",
            ),
        )
        val invalidMinimum = executor.execute(
            ToolRequest(
                id = "files-min-range",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("maxCount" to "0"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Rejected, invalidKind.status)
        assertEquals(ToolErrorCode.InvalidRequest, invalidKind.error?.code)
        assertFalse(invalidKind.retryable)
        assertTrue(invalidKind.summary.contains("invalid value"))
        assertEquals(ToolStatus.Rejected, blankKind.status)
        assertEquals(ToolErrorCode.InvalidRequest, blankKind.error?.code)
        assertFalse(blankKind.retryable)
        assertTrue(blankKind.summary.contains("invalid value"))
        assertEquals(ToolStatus.Rejected, invalidRange.status)
        assertEquals(ToolErrorCode.InvalidRequest, invalidRange.error?.code)
        assertFalse(invalidRange.retryable)
        assertTrue(invalidRange.summary.contains("at most"))
        assertEquals(ToolStatus.Rejected, invalidMinimum.status)
        assertEquals(ToolErrorCode.InvalidRequest, invalidMinimum.error?.code)
        assertFalse(invalidMinimum.retryable)
        assertTrue(invalidMinimum.summary.contains("at least"))
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun validatingExecutorAcceptsScreenshotsRecentFileKind() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(delegate)

        val result = executor.execute(
            ToolRequest(
                id = "files-screenshots",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "screenshots", "maxCount" to "3"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, delegate.requests.single().toolName)
        assertEquals("screenshots", delegate.requests.single().arguments["kind"])
    }

    @Test
    fun validatingExecutorAcceptsRecentScreenshotOcrMaxCountOneOnly() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(delegate)

        val accepted = executor.execute(
            ToolRequest(
                id = "screenshot-ocr",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                arguments = mapOf("maxCount" to "1"),
                reason = "test",
            ),
        )
        val rejectedRange = executor.execute(
            ToolRequest(
                id = "screenshot-ocr-range",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                arguments = mapOf("maxCount" to "2"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, accepted.status)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, delegate.requests.single().toolName)
        assertEquals(ToolStatus.Rejected, rejectedRange.status)
        assertEquals(ToolErrorCode.InvalidRequest, rejectedRange.error?.code)
        assertTrue(rejectedRange.summary.contains("at most"))
    }

    @Test
    fun validatingExecutorWrapsDelegateExceptionAsRetryableExecutionFailure() {
        val executor = ValidatingToolExecutor(
            ThrowingDelegate(IllegalStateException("boom")),
        )

        val result = executor.execute(
            ToolRequest(
                id = "wifi",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.retryable)
        assertTrue(result.summary.contains("boom"))
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.data["toolName"])
    }

    @Test
    fun validatingExecutorAddsToolContextToDelegateResult() {
        val executor = ValidatingToolExecutor(
            ContextlessSuccessDelegate(),
        )

        val result = executor.execute(
            ToolRequest(
                id = "wifi",
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.data["toolName"])
    }

    @Test
    fun validatingExecutorDoesNotLetUnknownToolReachRoutingDelegate() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(
            routingExecutor(delegate = delegate),
        )

        val result = executor.execute(
            ToolRequest(
                id = "unknown",
                toolName = "not_a_tool",
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Rejected, result.status)
        assertEquals(ToolErrorCode.UnknownTool, result.error?.code)
        assertFalse(result.retryable)
        assertTrue(delegate.requests.isEmpty())
    }

    private fun routingExecutor(delegate: ToolExecutor): RoutingToolExecutor =
        RoutingToolExecutor(
            calendarAvailabilityProvider = object : CalendarAvailabilityProvider {
                override fun queryAvailability(window: CalendarAvailabilityWindow): CalendarAvailabilityReadResult =
                    CalendarAvailabilityReadResult.Available(
                        CalendarAvailabilityQuery.snapshotFromBusyIntervals(
                            window = window,
                            busyIntervals = emptyList(),
                        ),
                    )
            },
            foregroundAppProvider = object : ForegroundAppProvider {
                override fun currentForegroundApp(): ForegroundAppReadResult =
                    ForegroundAppReadResult.Available(
                        ForegroundAppInfo(
                            packageName = "com.example.foreground",
                            appLabel = "Foreground",
                            lastTimeUsedMillis = 100L,
                        ),
                    )
            },
            contactSummaryProvider = object : ContactSummaryProvider {
                override fun queryContacts(query: String, maxCount: Int): ContactSummaryReadResult =
                    ContactSummaryReadResult.Available(
                        listOf(ContactSummaryItem(name = "Alice", phone = "+1 555 0100")),
                    )
            },
            notificationSummaryProvider = object : NotificationSummaryProvider {
                override fun recentNotifications(maxCount: Int): NotificationSummaryReadResult =
                    NotificationSummaryReadResult.Available(
                        listOf(
                            NotificationSummaryItem(
                                id = 1,
                                title = "Done",
                                isOngoing = false,
                                postTimeMillis = 1_000L,
                            ),
                        ),
                    )
            },
            recentFileProvider = object : RecentFileProvider {
                override fun recentFiles(kind: String, maxCount: Int): RecentFileReadResult =
                    RecentFileReadResult.Available(
                        listOf(
                            RecentFileItem(
                                id = 1L,
                                name = "brief.pdf",
                                mimeType = "application/pdf",
                                kind = "documents",
                                sizeBytes = 512L,
                                lastModifiedMillis = Instant.parse("2026-06-01T09:00:00Z").toEpochMilli(),
                            ),
                        ),
                    )
            },
            delegate = delegate,
            recentImageTextProvider = object : RecentImageTextProvider {
                override fun extractRecentImageText(kind: String, maxCount: Int): RecentImageTextReadResult =
                    RecentImageTextReadResult.Available(
                        item = RecentImageTextItem(
                            name = "Screenshot.png",
                            mimeType = "image/png",
                            kind = "screenshots",
                            sizeBytes = 512L,
                            lastModifiedMillis = Instant.parse("2026-06-01T09:00:00Z").toEpochMilli(),
                            text = "screen text",
                            truncated = false,
                        ),
                        scannedCount = 1,
                    )
            },
        )

    private class RecordingDelegate : ToolExecutor {
        val requests = mutableListOf<ToolRequest>()

        override fun execute(request: ToolRequest): ToolResult {
            requests += request
            return request.succeeded(
                summary = "delegated",
                data = mapOf("toolName" to request.toolName),
            )
        }
    }

    private class ThrowingDelegate(
        private val throwable: Throwable,
    ) : ToolExecutor {
        override fun execute(request: ToolRequest): ToolResult {
            throw throwable
        }
    }

    private class ContextlessSuccessDelegate : ToolExecutor {
        override fun execute(request: ToolRequest): ToolResult =
            ToolResult(
                requestId = request.id,
                status = ToolStatus.Succeeded,
                summary = "opened",
            )
    }
}
