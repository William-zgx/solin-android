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
import com.bytedance.zgx.pocketmind.device.CurrentScreenTextProvider
import com.bytedance.zgx.pocketmind.device.CurrentScreenTextReadResult
import com.bytedance.zgx.pocketmind.device.CurrentScreenTextSnapshot
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingAndValidatingToolExecutorTest {
    private val registry = ToolRegistry()

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
            ToolRequest(
                id = "image-ocr",
                toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                arguments = mapOf("maxCount" to "3"),
                reason = "test",
            ) to "ocrText",
            ToolRequest(
                id = "screen-text",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                arguments = mapOf("maxChars" to "1000"),
                reason = "test",
            ) to "screenText",
        )

        requests.forEach { (request, routedDataKey) ->
            val result = executor.execute(request)

            assertEquals(ToolStatus.Succeeded, result.status)
            assertEquals(request.toolName, result.data["toolName"])
            assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
            assertEquals(true.toString(), result.data["requiresLocalModel"])
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
    fun validatingExecutorAcceptsRecentImageOcrMaxCountOneToThreeOnly() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(delegate)

        val accepted = executor.execute(
            ToolRequest(
                id = "image-ocr",
                toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                arguments = mapOf("maxCount" to "3"),
                reason = "test",
            ),
        )
        val rejectedRange = executor.execute(
            ToolRequest(
                id = "image-ocr-range",
                toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                arguments = mapOf("maxCount" to "4"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, accepted.status)
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, delegate.requests.single().toolName)
        assertEquals(ToolStatus.Rejected, rejectedRange.status)
        assertEquals(ToolErrorCode.InvalidRequest, rejectedRange.error?.code)
        assertTrue(rejectedRange.summary.contains("at most"))
    }

    @Test
    fun validatingExecutorAcceptsCurrentScreenTextMaxCharsOneToFourThousandOnly() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(delegate)

        val accepted = executor.execute(
            ToolRequest(
                id = "screen-text",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                arguments = mapOf("maxChars" to "4000"),
                reason = "test",
            ),
        )
        val rejectedRange = executor.execute(
            ToolRequest(
                id = "screen-text-range",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                arguments = mapOf("maxChars" to "4001"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, accepted.status)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, delegate.requests.single().toolName)
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
    fun validatingExecutorRejectsSucceededDelegateResultMissingRequiredOutputField() {
        val executor = ValidatingToolExecutor(
            StaticResultDelegate { request ->
                request.succeeded(
                    summary = "read clipboard",
                    data = mapOf(
                        "toolName" to request.toolName,
                        "truncated" to "false",
                    ),
                )
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "clipboard-missing-output",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidResult, result.error?.code)
        assertFalse(result.retryable)
        assertTrue(result.summary.contains("output") || result.summary.contains("result"))
        assertTrue(result.summary.contains("text"))
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.data["toolName"])
    }

    @Test
    fun validatingExecutorRejectsSucceededDelegateResultWithWrongOutputFieldType() {
        val executor = ValidatingToolExecutor(
            StaticResultDelegate { request ->
                request.succeeded(
                    summary = "read clipboard",
                    data = mapOf(
                        "toolName" to request.toolName,
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to "true",
                        "text" to "clipboard text",
                        "truncated" to "maybe",
                    ),
                )
            },
        )

        val result = executor.execute(
            ToolRequest(
                id = "clipboard-wrong-output-type",
                toolName = MobileActionFunctions.READ_CLIPBOARD,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.InvalidResult, result.error?.code)
        assertFalse(result.retryable)
        assertTrue(result.summary.contains("output") || result.summary.contains("result"))
        assertTrue(result.summary.contains("truncated"))
        assertTrue(result.summary.contains("true or false"))
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, result.data["toolName"])
    }

    @Test
    fun validatingExecutorDoesNotRequireSuccessOutputSchemaForNonSucceededDelegateResults() {
        val cases = listOf(
            ToolStatus.Rejected to ToolErrorCode.InvalidRequest,
            ToolStatus.Failed to ToolErrorCode.ExecutionFailed,
            ToolStatus.Cancelled to ToolErrorCode.UserCancelled,
        )

        cases.forEach { (status, errorCode) ->
            val executor = ValidatingToolExecutor(
                StaticResultDelegate { request ->
                    ToolResult(
                        requestId = request.id,
                        status = status,
                        summary = "delegate $status",
                        data = emptyMap(),
                        error = ToolError(errorCode, "delegate $status"),
                        retryable = status == ToolStatus.Failed,
                    )
                },
            )

            val result = executor.execute(
                ToolRequest(
                    id = "wifi-non-success-$status",
                    toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    reason = "test",
                ),
            )

            assertEquals(status, result.status)
            assertEquals(errorCode, result.error?.code)
            assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, result.data["toolName"])
            assertFalse(result.summary.contains("output"))
        }
    }

    @Test
    fun validatingRoutingExecutorAcceptsPrivateDeviceContextOutputsAndKeepsPrivateKeyBoundary() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(routingExecutor(delegate))
        val privateDeviceRequests = listOf(
            ToolRequest(
                id = "calendar-private-output",
                toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                arguments = mapOf(
                    "start" to "2026-06-01T09:00:00Z",
                    "end" to "2026-06-01T10:00:00Z",
                ),
                reason = "test",
            ),
            ToolRequest(
                id = "foreground-private-output",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                reason = "test",
            ),
            ToolRequest(
                id = "contacts-private-output",
                toolName = MobileActionFunctions.QUERY_CONTACTS,
                arguments = mapOf("query" to "Alice"),
                reason = "test",
            ),
            ToolRequest(
                id = "notifications-private-output",
                toolName = MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS,
                reason = "test",
            ),
            ToolRequest(
                id = "files-private-output",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                reason = "test",
            ),
            ToolRequest(
                id = "screenshot-ocr-private-output",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                reason = "test",
            ),
            ToolRequest(
                id = "image-ocr-private-output",
                toolName = MobileActionFunctions.READ_RECENT_IMAGE_OCR,
                arguments = mapOf("maxCount" to "3"),
                reason = "test",
            ),
            ToolRequest(
                id = "screen-text-private-output",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                arguments = mapOf("maxChars" to "1000"),
                reason = "test",
            ),
        )

        privateDeviceRequests.forEach { request ->
            val result = executor.execute(request)
            val privateKeys = registry.privateOutputKeysFor(request.toolName)

            assertEquals(ToolStatus.Succeeded, result.status)
            assertEquals(request.toolName, result.data["toolName"])
            assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
            assertEquals(true.toString(), result.data["requiresLocalModel"])
            assertNotNull(registry.redactedResultSummaryFor(request.toolName))
            assertTrue("$request should have private output keys", privateKeys.isNotEmpty())
            privateKeys.forEach { privateKey ->
                assertTrue("${request.toolName} result must keep private output key $privateKey", result.data.containsKey(privateKey))
            }
        }
        assertTrue(delegate.requests.isEmpty())
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
            currentScreenTextProvider = object : CurrentScreenTextProvider {
                override fun currentScreenText(maxChars: Int): CurrentScreenTextReadResult =
                    CurrentScreenTextReadResult.Available(
                        CurrentScreenTextSnapshot(
                            text = "current screen text",
                            packageName = "com.example.app",
                            capturedAtMillis = Instant.parse("2026-06-01T09:00:00Z").toEpochMilli(),
                            nodeCount = 3,
                            truncated = false,
                        ),
                    )
            },
        )

    private class RecordingDelegate(
        private val registry: ToolRegistry = ToolRegistry(),
    ) : ToolExecutor {
        val requests = mutableListOf<ToolRequest>()

        override fun execute(request: ToolRequest): ToolResult {
            requests += request
            return request.succeeded(
                summary = "delegated",
                data = validOutputDataFor(request),
            )
        }

        private fun validOutputDataFor(request: ToolRequest): Map<String, String> {
            val schema = JSONObject(registry.specFor(request.toolName)?.outputSchemaJson.orEmpty())
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val requiredKeys = stringSet(schema, "required")
            return buildMap {
                requiredKeys.forEach { key ->
                    val property = properties.optJSONObject(key) ?: JSONObject()
                    put(key, validOutputValueFor(request, key, property))
                }
                putIfAbsent("toolName", request.toolName)
            }
        }

        private fun validOutputValueFor(
            request: ToolRequest,
            key: String,
            property: JSONObject,
        ): String {
            stringSet(property, "enum").firstOrNull()?.let { return it }
            if (key == "toolName") return request.toolName
            return when (property.optString("type")) {
                "boolean" -> "false"
                "integer" -> (intOrNull(property, "minimum") ?: 1).toString()
                "number" -> (intOrNull(property, "minimum") ?: 1).toString()
                "array" -> "[]"
                "object" -> "{}"
                else -> "value"
            }
        }

        private fun stringSet(json: JSONObject, name: String): Set<String> {
            val array = json.optJSONArray(name) ?: return emptySet()
            return buildSet {
                for (index in 0 until array.length()) {
                    add(array.getString(index))
                }
            }
        }

        private fun intOrNull(json: JSONObject, name: String): Int? =
            if (json.has(name)) json.optInt(name) else null
    }

    private class ThrowingDelegate(
        private val throwable: Throwable,
    ) : ToolExecutor {
        override fun execute(request: ToolRequest): ToolResult {
            throw throwable
        }
    }

    private class StaticResultDelegate(
        private val resultForRequest: (ToolRequest) -> ToolResult,
    ) : ToolExecutor {
        override fun execute(request: ToolRequest): ToolResult =
            resultForRequest(request)
    }

}
