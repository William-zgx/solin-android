package com.bytedance.zgx.pocketmind.tool

import com.bytedance.zgx.pocketmind.MessagePrivacy
import com.bytedance.zgx.pocketmind.SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.ReminderScheduleRequest
import com.bytedance.zgx.pocketmind.background.ScheduledTask
import com.bytedance.zgx.pocketmind.background.ScheduledTaskStatus
import com.bytedance.zgx.pocketmind.background.ScheduledTaskType
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityProvider
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityQuery
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityReadResult
import com.bytedance.zgx.pocketmind.device.CalendarAvailabilityWindow
import com.bytedance.zgx.pocketmind.device.ContactSummaryItem
import com.bytedance.zgx.pocketmind.device.ContactSummaryProvider
import com.bytedance.zgx.pocketmind.device.ContactSummaryReadResult
import com.bytedance.zgx.pocketmind.device.CurrentScreenControlProvider
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
import com.bytedance.zgx.pocketmind.device.ScreenBounds
import com.bytedance.zgx.pocketmind.device.ScreenNode
import com.bytedance.zgx.pocketmind.device.ScreenStateReadResult
import com.bytedance.zgx.pocketmind.device.ScreenStateSnapshot
import com.bytedance.zgx.pocketmind.device.UiActionExecutionResult
import com.bytedance.zgx.pocketmind.device.UiActionReadResult
import com.bytedance.zgx.pocketmind.device.UiActionStatus
import com.bytedance.zgx.pocketmind.device.UiScrollDirection
import com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrContract
import com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.pocketmind.multimodal.CurrentScreenshotOcrReadResult
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
                id = "background-tasks",
                toolName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
                reason = "test",
            ) to "tasksJson",
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
            ToolRequest(
                id = "current-screenshot-ocr",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ) to "ocrText",
            ToolRequest(
                id = "observe-current-screen",
                toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                arguments = mapOf("maxTextChars" to "1000", "maxNodes" to "50"),
                reason = "test",
            ) to "nodesJson",
            ToolRequest(
                id = "ui-tap",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "n0_button", "timeoutMillis" to "500"),
                reason = "test",
            ) to "afterNodesJson",
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
    fun currentScreenshotOcrFailsClosedUntilMediaProjectionConsent() {
        val delegate = RecordingDelegate()
        val provider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.MissingConsent,
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = provider,
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertFalse(result.retryable)
        assertEquals(MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR, result.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals(true.toString(), result.data["requiresLocalModel"])
        assertEquals(CurrentScreenshotOcrContract.CONSENT_REASON, result.data["specialAccess"])
        assertTrue(delegate.requests.isEmpty())
        assertEquals(listOf("current-screenshot-ocr"), provider.capturedRequestIds)
    }

    @Test
    fun currentScreenshotOcrUsesOneShotProviderResultAfterConsent() {
        val delegate = RecordingDelegate()
        val provider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "当前屏幕 OCR 文本",
                truncated = false,
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = provider,
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR, result.data["toolName"])
        assertEquals(CurrentScreenshotOcrContract.SOURCE, result.data["source"])
        assertEquals(CurrentScreenshotOcrContract.CAPTURE_MODE, result.data["captureMode"])
        assertEquals("当前屏幕 OCR 文本", result.data["ocrText"])
        assertEquals("true", result.data["ocrTextIncluded"])
        assertEquals("false", result.data["truncated"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals(true.toString(), result.data["requiresLocalModel"])
        assertEquals(CurrentScreenshotOcrContract.OUTPUT_METADATA_POLICY, result.data["metadataPolicy"])
        assertEquals(listOf("current-screenshot-ocr"), provider.capturedRequestIds)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorFailsBackgroundTaskQueryWhenSchedulerIsMissing() {
        val delegate = RecordingDelegate()
        val executor = routingExecutor(
            delegate = delegate,
            backgroundTaskScheduler = null,
        )

        val result = executor.execute(
            ToolRequest(
                id = "background-tasks",
                toolName = MobileActionFunctions.QUERY_BACKGROUND_TASKS,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertTrue(result.retryable)
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertTrue(delegate.requests.isEmpty())
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
        val nonMediaKind = executor.execute(
            ToolRequest(
                id = "files-non-media-kind",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("kind" to "documents"),
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
        assertEquals(ToolStatus.Rejected, nonMediaKind.status)
        assertEquals(ToolErrorCode.InvalidRequest, nonMediaKind.error?.code)
        assertFalse(nonMediaKind.retryable)
        assertTrue(nonMediaKind.summary.contains("invalid value"))
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
    fun validatingExecutorRejectsInvalidDeviceControlArguments() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(delegate)
        val cases = listOf(
            ToolRequest(
                id = "tap-empty",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to " "),
                reason = "test",
            ),
            ToolRequest(
                id = "type-too-long",
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = mapOf("text" to "x".repeat(2001)),
                reason = "test",
            ),
            ToolRequest(
                id = "scroll-invalid",
                toolName = MobileActionFunctions.UI_SCROLL,
                arguments = mapOf("direction" to "diagonal"),
                reason = "test",
            ),
            ToolRequest(
                id = "wait-too-long",
                toolName = MobileActionFunctions.UI_WAIT,
                arguments = mapOf("timeoutMillis" to "10001"),
                reason = "test",
            ),
        )

        cases.forEach { request ->
            val result = executor.execute(request)

            assertEquals(ToolStatus.Rejected, result.status)
            assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
            assertFalse(result.retryable)
            assertEquals(request.toolName, result.data["toolName"])
            assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
            assertEquals(true.toString(), result.data["requiresLocalModel"])
        }
        assertTrue(delegate.requests.isEmpty())
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
    fun validatingExecutorSanitizesSensitiveNonSucceededDelegateResults() {
        val cases = listOf(
            ToolStatus.Rejected to ToolErrorCode.InvalidRequest,
            ToolStatus.Failed to ToolErrorCode.ExecutionFailed,
            ToolStatus.Cancelled to ToolErrorCode.UserCancelled,
        )

        cases.forEach { (status, errorCode) ->
            val executor = ValidatingToolExecutor(
                StaticResultDelegate { request ->
                    ToolResult(
                        requestId = "unexpected-${request.id}",
                        status = status,
                        summary = "delegate $status with com.example.private",
                        data = mapOf(
                            "toolName" to "wrong-tool",
                            "privacy" to "Remote",
                            "requiresLocalModel" to "false",
                            "packageName" to "com.example.private",
                            "appLabel" to "Private App",
                            "failureKind" to "permission",
                            "debugPayload" to "raw private detail",
                            "specialAccess" to "usage_stats",
                            "settingsAction" to "android.settings.USAGE_ACCESS_SETTINGS",
                            "recoveryToolName" to MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
                        ),
                        error = ToolError(errorCode, "delegate $status with com.example.private"),
                        retryable = status == ToolStatus.Failed,
                    )
                },
            )

            val result = executor.execute(
                ToolRequest(
                    id = "foreground-non-success-$status",
                    toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                    reason = "test",
                ),
            )

            assertEquals(status, result.status)
            assertEquals(errorCode, result.error?.code)
            assertEquals(MobileActionFunctions.QUERY_FOREGROUND_APP, result.data["toolName"])
            assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
            assertEquals(true.toString(), result.data["requiresLocalModel"])
            assertEquals("usage_stats", result.data["specialAccess"])
            assertEquals("android.settings.USAGE_ACCESS_SETTINGS", result.data["settingsAction"])
            assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, result.data["recoveryToolName"])
            assertFalse(result.data.containsKey("packageName"))
            assertFalse(result.data.containsKey("appLabel"))
            assertFalse(result.data.containsKey("failureKind"))
            assertFalse(result.data.containsKey("debugPayload"))
            assertFalse(result.summary.contains("output"))
            assertFalse(result.summary.contains("com.example.private"))
            assertFalse(result.error?.message.orEmpty().contains("com.example.private"))
        }
    }

    @Test
    fun validatingExecutorRejectsSensitiveInvalidRequestsAsLocalOnly() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(delegate)
        val requests = listOf(
            ToolRequest(
                id = "files-invalid-max-count",
                toolName = MobileActionFunctions.QUERY_RECENT_FILES,
                arguments = mapOf("maxCount" to "51"),
                reason = "test",
            ),
            ToolRequest(
                id = "screenshot-invalid-max-count",
                toolName = MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR,
                arguments = mapOf("maxCount" to "2"),
                reason = "test",
            ),
            ToolRequest(
                id = "screen-text-invalid-max-chars",
                toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
                arguments = mapOf("maxChars" to "4001"),
                reason = "test",
            ),
            ToolRequest(
                id = "calendar-missing-end",
                toolName = MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY,
                arguments = mapOf("start" to "2026-06-01T09:00:00Z"),
                reason = "test",
            ),
        )

        requests.forEach { request ->
            val result = executor.execute(request)
            val privateKeys = registry.privateOutputKeysFor(request.toolName)

            assertEquals(ToolStatus.Rejected, result.status)
            assertEquals(ToolErrorCode.InvalidRequest, result.error?.code)
            assertFalse(result.retryable)
            assertEquals(request.toolName, result.data["toolName"])
            assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
            assertEquals(true.toString(), result.data["requiresLocalModel"])
            assertTrue("$request should have private output keys", privateKeys.isNotEmpty())
            privateKeys.forEach { privateKey ->
                assertFalse("${request.toolName} rejection must not include private key $privateKey", result.data.containsKey(privateKey))
            }
        }
        assertTrue(delegate.requests.isEmpty())
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
            ToolRequest(
                id = "screen-state-private-output",
                toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                arguments = mapOf("maxTextChars" to "1000", "maxNodes" to "50"),
                reason = "test",
            ),
            ToolRequest(
                id = "ui-scroll-private-output",
                toolName = MobileActionFunctions.UI_SCROLL,
                arguments = mapOf("direction" to "down", "timeoutMillis" to "500"),
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
            assertTrue(
                "${request.toolName} result must include at least one declared private output key",
                privateKeys.any { privateKey -> result.data.containsKey(privateKey) },
            )
        }
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorHandlesWebSearchWithoutDelegatingToExternalActivity() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                webSearchProvider = StaticWebSearchProvider { request ->
                    WebSearchReadResult.Available(
                        query = request.query,
                        source = "open_meteo",
                        summaryText = "北京 当前天气：晴，气温 24℃。",
                        resultsJson = """{"kind":"weather","provider":"Open-Meteo"}""",
                        searchMode = request.searchMode,
                        freshness = request.freshness,
                        maxResults = request.maxResults,
                    )
                },
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "web-search-routed",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "北京天气怎么样"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MobileActionFunctions.WEB_SEARCH, result.data["toolName"])
        assertEquals("北京天气怎么样", result.data["query"])
        assertEquals("open_meteo", result.data["source"])
        assertTrue(result.data["summaryText"].orEmpty().contains("北京 当前天气"))
        assertFalse(result.data.containsKey("completionState"))
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorInfersCurrentFreshnessForTemporalWebSearchQuery() {
        val delegate = RecordingDelegate()
        var capturedRequest: WebSearchRequest? = null
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                webSearchProvider = StaticWebSearchProvider { request ->
                    capturedRequest = request
                    WebSearchReadResult.Available(
                        query = request.query,
                        source = "duckduckgo_html",
                        summaryText = "OpenAI latest model summary",
                        resultsJson = """{"kind":"web_search_evidence","freshness":"${request.freshness.schemaValue}"}""",
                        searchMode = request.searchMode,
                        freshness = request.freshness,
                        maxResults = request.maxResults,
                    )
                },
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "web-search-current-freshness",
                toolName = MobileActionFunctions.WEB_SEARCH,
                arguments = mapOf("query" to "OpenAI latest model"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(WebSearchFreshness.Current, capturedRequest?.freshness)
        assertEquals("current", result.data["freshness"])
        assertEquals("OpenAI latest model", result.data["query"])
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun deviceControlPermissionDeniedKeepsRecoverableLocalOnlyBoundary() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenControlProvider = StaticCurrentScreenControlProvider(
                    observeResult = ScreenStateReadResult.PermissionDenied("accessibility disabled"),
                ),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "observe-denied",
                toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.PermissionDenied, result.error?.code)
        assertTrue(result.retryable)
        assertEquals(MobileActionFunctions.OBSERVE_CURRENT_SCREEN, result.data["toolName"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals(true.toString(), result.data["requiresLocalModel"])
        assertEquals(SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL, result.data["specialAccess"])
        assertEquals("android.settings.ACCESSIBILITY_SETTINGS", result.data["settingsAction"])
        assertFalse(result.data.containsKey("nodesJson"))
        assertFalse(result.data.containsKey("textSummary"))
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

    private fun routingExecutor(
        delegate: ToolExecutor,
        backgroundTaskScheduler: BackgroundTaskScheduler? = StaticBackgroundTaskScheduler(),
        currentScreenshotOcrProvider: CurrentScreenshotOcrProvider? = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "current screenshot text",
                truncated = false,
            ),
        ),
        webSearchProvider: WebSearchProvider = StaticWebSearchProvider(),
        currentScreenControlProvider: CurrentScreenControlProvider? = StaticCurrentScreenControlProvider(),
    ): RoutingToolExecutor =
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
            webSearchProvider = webSearchProvider,
            delegate = delegate,
            backgroundTaskScheduler = backgroundTaskScheduler,
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
            currentScreenshotOcrProvider = currentScreenshotOcrProvider,
            currentScreenControlProvider = currentScreenControlProvider,
        )

    private class StaticCurrentScreenControlProvider(
        private val observeResult: ScreenStateReadResult = ScreenStateReadResult.Available(staticSnapshot("before")),
        private val actionResult: UiActionReadResult = UiActionReadResult.Available(
            UiActionExecutionResult(
                status = UiActionStatus.Succeeded,
                before = staticSnapshot("before"),
                after = staticSnapshot("after"),
                summary = "action completed",
                retryable = false,
            ),
        ),
    ) : CurrentScreenControlProvider {
        override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult =
            observeResult

        override fun tap(target: String, timeoutMillis: Long): UiActionReadResult =
            actionResult

        override fun typeText(text: String, target: String?, timeoutMillis: Long): UiActionReadResult =
            actionResult

        override fun scroll(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult =
            actionResult

        override fun pressBack(timeoutMillis: Long): UiActionReadResult =
            actionResult

        override fun waitForScreen(timeoutMillis: Long): UiActionReadResult =
            actionResult
    }

    private companion object {
        fun staticSnapshot(id: String): ScreenStateSnapshot =
            ScreenStateSnapshot(
                id = "screen-$id",
                packageName = "com.example.app",
                capturedAtMillis = Instant.parse("2026-06-01T09:00:00Z").toEpochMilli(),
                nodes = listOf(
                    ScreenNode(
                        id = "n0_button",
                        text = "Continue",
                        contentDescription = "",
                        className = "android.widget.Button",
                        bounds = ScreenBounds(0, 0, 100, 48),
                        clickable = true,
                        editable = false,
                        scrollable = false,
                        enabled = true,
                    ),
                ),
                textSummary = "Continue",
                truncated = false,
            )
    }

    private class StaticCurrentScreenshotOcrProvider(
        private val result: CurrentScreenshotOcrReadResult,
    ) : CurrentScreenshotOcrProvider {
        val capturedRequestIds = mutableListOf<String>()

        override fun setOneShotConsent(
            requestId: String,
            resultCode: Int,
            data: android.content.Intent?,
            issuedAtMillis: Long,
        ) = Unit

        override fun clearOneShotConsent(requestId: String) = Unit

        override fun captureCurrentScreenshotOcr(requestId: String, nowMillis: Long): CurrentScreenshotOcrReadResult {
            capturedRequestIds += requestId
            return result
        }
    }

    private class StaticWebSearchProvider(
        private val resultForRequest: (WebSearchRequest) -> WebSearchReadResult = { request ->
            WebSearchReadResult.Available(
                query = request.query,
                source = "duckduckgo",
                summaryText = "Kotlin search summary",
                resultsJson = """{"kind":"instant_answer","results":[]}""",
                searchMode = request.searchMode,
                freshness = request.freshness,
                maxResults = request.maxResults,
            )
        },
    ) : WebSearchProvider {
        override fun search(request: WebSearchRequest): WebSearchReadResult =
            resultForRequest(request)
    }

    private class StaticBackgroundTaskScheduler : BackgroundTaskScheduler {
        override fun scheduledTasks(limit: Int): List<ScheduledTask> =
            listOf(
                ScheduledTask(
                    id = "task-route",
                    type = ScheduledTaskType.Reminder,
                    title = "Routed task",
                    body = "private body",
                    triggerAtMillis = 1_000L,
                    status = ScheduledTaskStatus.Scheduled,
                    createdAtMillis = 100L,
                    updatedAtMillis = 200L,
                ),
            )

        override fun scheduleReminder(request: ReminderScheduleRequest): Result<ScheduledTask> =
            Result.failure(UnsupportedOperationException("not used"))

        override fun cancel(taskId: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("not used"))
    }

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
            val spec = registry.specFor(request.toolName)
            val schema = JSONObject(spec?.outputSchemaJson.orEmpty())
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val requiredKeys = stringSet(schema, "required")
            return buildMap {
                requiredKeys.forEach { key ->
                    val property = properties.optJSONObject(key) ?: JSONObject()
                    put(key, validOutputValueFor(request, key, property))
                }
                putIfAbsent("toolName", request.toolName)
                if (spec?.permissions?.contains(ToolPermission.StartsExternalActivity) == true) {
                    put("completionVerified", "false")
                    put("externalOutcome", "Unknown")
                    put("externalOutcomeSource", "Unknown")
                }
            }
        }

        private fun validOutputValueFor(
            request: ToolRequest,
            key: String,
            property: JSONObject,
        ): String {
            constValueFor(property)?.let { return it }
            stringSet(property, "enum").firstOrNull()?.let { return it }
            if (key == "toolName") return request.toolName
            if (key == "requiresLocalModel") return true.toString()
            return when (property.optString("type")) {
                "boolean" -> true.toString()
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

        private fun constValueFor(json: JSONObject): String? {
            if (!json.has("const") || json.isNull("const")) return null
            return when (val value = json.get("const")) {
                is String -> value
                is Boolean -> value.toString()
                is Number -> value.toString()
                else -> null
            }
        }
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
