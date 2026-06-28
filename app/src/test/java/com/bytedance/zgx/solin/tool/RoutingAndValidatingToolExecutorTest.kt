package com.bytedance.zgx.solin.tool

import android.provider.MediaStore
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.SPECIAL_ACCESS_ACCESSIBILITY_DEVICE_CONTROL
import com.bytedance.zgx.solin.action.ActionExecutor
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.ExternalActivityLaunch
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.background.ReminderScheduleRequest
import com.bytedance.zgx.solin.background.ScheduledTask
import com.bytedance.zgx.solin.background.ScheduledTaskStatus
import com.bytedance.zgx.solin.background.ScheduledTaskType
import com.bytedance.zgx.solin.device.CalendarAvailabilityProvider
import com.bytedance.zgx.solin.device.CalendarAvailabilityQuery
import com.bytedance.zgx.solin.device.CalendarAvailabilityReadResult
import com.bytedance.zgx.solin.device.CalendarAvailabilityWindow
import com.bytedance.zgx.solin.device.ContactSummaryItem
import com.bytedance.zgx.solin.device.ContactSummaryProvider
import com.bytedance.zgx.solin.device.ContactSummaryReadResult
import com.bytedance.zgx.solin.device.CurrentScreenControlProvider
import com.bytedance.zgx.solin.device.CurrentScreenTextProvider
import com.bytedance.zgx.solin.device.CurrentScreenTextReadResult
import com.bytedance.zgx.solin.device.CurrentScreenTextSnapshot
import com.bytedance.zgx.solin.device.ForegroundAppInfo
import com.bytedance.zgx.solin.device.ForegroundAppProvider
import com.bytedance.zgx.solin.device.ForegroundAppReadResult
import com.bytedance.zgx.solin.device.NotificationSummaryItem
import com.bytedance.zgx.solin.device.NotificationSummaryProvider
import com.bytedance.zgx.solin.device.NotificationSummaryReadResult
import com.bytedance.zgx.solin.device.RecentFileItem
import com.bytedance.zgx.solin.device.RecentFileProvider
import com.bytedance.zgx.solin.device.RecentFileReadResult
import com.bytedance.zgx.solin.device.RecentImageTextItem
import com.bytedance.zgx.solin.device.RecentImageTextProvider
import com.bytedance.zgx.solin.device.RecentImageTextReadResult
import com.bytedance.zgx.solin.device.ScreenBounds
import com.bytedance.zgx.solin.device.ScreenNode
import com.bytedance.zgx.solin.device.ScreenStateReadResult
import com.bytedance.zgx.solin.device.ScreenStateSnapshot
import com.bytedance.zgx.solin.device.UiActionExecutionResult
import com.bytedance.zgx.solin.device.UiActionFailureKind
import com.bytedance.zgx.solin.device.UiActionReadResult
import com.bytedance.zgx.solin.device.UiActionStatus
import com.bytedance.zgx.solin.device.UiOcrGroundingHint
import com.bytedance.zgx.solin.device.UiScrollDirection
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrProvider
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrReadResult
import com.bytedance.zgx.solin.multimodal.OcrTextBlock
import com.bytedance.zgx.solin.multimodal.OcrTextBounds
import com.bytedance.zgx.solin.multimodal.OcrTextElement
import com.bytedance.zgx.solin.multimodal.OcrTextLine
import com.bytedance.zgx.solin.orchestration.AgentObservationReplanContext
import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.ModelObservationReplanner
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
            if (request.toolName == MobileActionFunctions.OBSERVE_CURRENT_SCREEN) {
                assertTrue(result.data.containsKey("screenObservationJson"))
                assertTrue(result.data.containsKey("nodesJson"))
                assertTrue(result.data.containsKey("textSummary"))
            }
            if (request.toolName == MobileActionFunctions.UI_TAP ||
                request.toolName == MobileActionFunctions.UI_SUBMIT_SEARCH
            ) {
                assertTrue(result.data.containsKey("beforeScreenObservationJson"))
                assertTrue(result.data.containsKey("afterScreenObservationJson"))
                assertTrue(result.data.containsKey("screenObservationDiffSummary"))
                val beforeObservation = JSONObject(result.data.getValue("beforeScreenObservationJson"))
                assertEquals("screen-before", beforeObservation.getString("observationId"))
                val afterObservation = JSONObject(result.data.getValue("afterScreenObservationJson"))
                assertEquals("screen-after", afterObservation.getString("observationId"))
                assertEquals("com.example.app", afterObservation.getString("packageName"))
                assertEquals(1, afterObservation.getJSONObject("sourceCounts").getInt("accessibility"))
                assertTrue(result.data.getValue("screenObservationDiffSummary").contains("changed="))
            }
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
    fun routingExecutorRejectsDirectUiActionWhenDangerousControlIsVisible() {
        val delegate = RecordingDelegate()
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "dangerous-before",
                    textSummary = "确认支付",
                    nodes = listOf(
                        staticNode(
                            id = "pay-button",
                            text = "确认支付",
                            clickable = true,
                        ),
                    ),
                ),
            ),
        )
        val executor = routingExecutor(
            delegate = delegate,
            currentScreenControlProvider = screenProvider,
        )

        val result = executor.execute(
            ToolRequest(
                id = "direct-dangerous-tap",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "确认支付"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertFalse(result.retryable)
        assertEquals("dangerous_action", result.data["failureKind"])
        assertEquals("tap", result.data["actionType"])
        assertEquals("确认支付", result.data["target"])
        assertEquals("screen-dangerous-before", result.data["beforeObservationId"])
        assertTrue(result.data.getValue("beforeScreenObservationJson").contains("确认支付"))
        assertTrue(screenProvider.tapTargets.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorRejectsSubmitSearchWithoutSearchContext() {
        val delegate = RecordingDelegate()
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "message-input",
                    textSummary = "消息 发送",
                    nodes = listOf(
                        staticNode(
                            id = "message-field",
                            text = "消息",
                            className = "android.widget.EditText",
                            editable = true,
                        ),
                        staticNode(
                            id = "close-button",
                            text = "关闭",
                            clickable = true,
                        ),
                    ),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenControlProvider = screenProvider,
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "submit-message-input",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertEquals("submit_not_found", result.data["failureKind"])
        assertEquals("screen-message-input", result.data["beforeObservationId"])
        assertEquals("screen-message-input", result.data["afterObservationId"])
        assertTrue(result.data.getValue("beforeScreenObservationJson").contains("message-input"))
        assertTrue(result.data.getValue("afterScreenObservationJson").contains("message-field"))
        assertTrue(result.data.getValue("screenObservationDiffSummary").contains("changed=false"))
        assertTrue(screenProvider.submitSearchOcrGroundingHints.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorRejectsSubmitSearchWhenScreenContextUnavailable() {
        val delegate = RecordingDelegate()
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Failed(
                reason = "当前屏幕状态读取超时",
                failureKind = UiActionFailureKind.Timeout,
            ),
        )
        val executor = routingExecutor(
            delegate = delegate,
            currentScreenControlProvider = screenProvider,
        )

        val result = executor.execute(
            ToolRequest(
                id = "submit-without-screen-context",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("submit_not_found", result.data["failureKind"])
        assertTrue(screenProvider.submitSearchOcrGroundingHints.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorAllowsSubmitSearchWithSearchContext() {
        val delegate = RecordingDelegate()
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "search-input",
                    textSummary = "搜索商品 搜索",
                    nodes = listOf(
                        staticNode(
                            id = "search-field",
                            text = "搜索商品",
                            className = "android.widget.EditText",
                            editable = true,
                        ),
                        staticNode(
                            id = "search-submit",
                            text = "搜索",
                            clickable = true,
                        ),
                    ),
                ),
            ),
        )
        val executor = routingExecutor(
            delegate = delegate,
            currentScreenControlProvider = screenProvider,
        )

        val result = executor.execute(
            ToolRequest(
                id = "submit-search-input",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(listOf(null), screenProvider.submitSearchOcrGroundingHints)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorRejectsTargetlessTypeTextWithoutSearchContext() {
        val delegate = RecordingDelegate()
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "message-type-input",
                    textSummary = "消息 关闭",
                    nodes = listOf(
                        staticNode(
                            id = "message-field",
                            text = "消息",
                            className = "android.widget.EditText",
                            editable = true,
                        ),
                        staticNode(
                            id = "close-button",
                            text = "关闭",
                            clickable = true,
                        ),
                    ),
                ),
            ),
        )
        val executor = routingExecutor(
            delegate = delegate,
            currentScreenControlProvider = screenProvider,
        )

        val result = executor.execute(
            ToolRequest(
                id = "type-message-without-target",
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = mapOf("text" to "hello", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("editable_not_found", result.data["failureKind"])
        assertEquals("screen-message-type-input", result.data["beforeObservationId"])
        assertEquals("screen-message-type-input", result.data["afterObservationId"])
        assertTrue(result.data.getValue("beforeScreenObservationJson").contains("message-type-input"))
        assertTrue(result.data.getValue("afterScreenObservationJson").contains("message-field"))
        assertTrue(result.data.getValue("screenObservationDiffSummary").contains("changed=false"))
        assertTrue(screenProvider.typeTextValues.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun targetlessTypeGuardFailureCanDriveModelReplanWithCurrentObservation() {
        val delegate = RecordingDelegate()
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "search-entry-only",
                    textSummary = "搜索入口 关闭",
                    nodes = listOf(
                        staticNode(
                            id = "search-entry",
                            text = "搜索入口",
                            clickable = true,
                        ),
                        staticNode(
                            id = "close-button",
                            text = "关闭",
                            clickable = true,
                        ),
                    ),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenControlProvider = screenProvider,
            ),
        )
        val typeRequest = ToolRequest(
            id = "type-before-search-entry-focus",
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            arguments = mapOf("text" to "hello", "timeoutMillis" to "500"),
            reason = "test",
        )

        val typeResult = executor.execute(typeRequest)
        val actionRuntime = RecordingActionPlanningRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "搜索入口", "timeoutMillis" to "500"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = actionRuntime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-targetless-type-guard-replan",
                    input = "在当前应用搜索 hello",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = typeRequest,
                observedResult = typeResult,
                priorRequests = listOf(typeRequest),
            ),
        )
        assertNotNull(replan)
        requireNotNull(replan)
        val tapResult = executor.execute(replan.request)

        assertEquals(ToolStatus.Failed, typeResult.status)
        assertEquals("editable_not_found", typeResult.data["failureKind"])
        assertEquals("screen-search-entry-only", typeResult.data["beforeObservationId"])
        assertEquals("screen-search-entry-only", typeResult.data["afterObservationId"])
        assertTrue(typeResult.data.getValue("afterScreenObservationJson").contains("search-entry"))
        assertFalse(typeResult.data.containsKey("afterNodesJson"))
        assertTrue(actionRuntime.lastInput.orEmpty().contains("afterScreenObservationJson(id=screen-search-entry-only"))
        assertTrue(actionRuntime.lastInput.orEmpty().contains("targetShortlist(tap=search-entry|close-button)"))
        assertEquals(MobileActionFunctions.UI_TAP, replan.request.toolName)
        assertEquals("搜索入口", replan.request.arguments["target"])
        assertEquals(ToolStatus.Succeeded, tapResult.status)
        assertEquals(listOf("搜索入口"), screenProvider.tapTargets)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorRejectsTargetlessTypeTextWhenScreenContextUnavailable() {
        val delegate = RecordingDelegate()
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Failed(
                reason = "当前屏幕状态读取超时",
                failureKind = UiActionFailureKind.Timeout,
            ),
        )
        val executor = routingExecutor(
            delegate = delegate,
            currentScreenControlProvider = screenProvider,
        )

        val result = executor.execute(
            ToolRequest(
                id = "type-without-screen-context",
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = mapOf("text" to "hello", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("editable_not_found", result.data["failureKind"])
        assertTrue(screenProvider.typeTextValues.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorAllowsTargetlessTypeTextWithSearchContext() {
        val delegate = RecordingDelegate()
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "search-type-input",
                    textSummary = "搜索商品 搜索",
                    nodes = listOf(
                        staticNode(
                            id = "search-field",
                            text = "搜索商品",
                            className = "android.widget.EditText",
                            editable = true,
                        ),
                        staticNode(
                            id = "search-submit",
                            text = "搜索",
                            clickable = true,
                        ),
                    ),
                ),
            ),
        )
        val executor = routingExecutor(
            delegate = delegate,
            currentScreenControlProvider = screenProvider,
        )

        val result = executor.execute(
            ToolRequest(
                id = "type-search-without-target",
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = mapOf("text" to "数据线", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(listOf("数据线"), screenProvider.typeTextValues)
        assertEquals(listOf(null), screenProvider.typeTextTargets)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun routingExecutorRoutesOpenCameraThroughActionExecutor() {
        val launches = mutableListOf<ExternalActivityLaunch>()
        val actionExecutor = ActionExecutor(
            context = null,
            externalActivityStarter = { launch ->
                launches += launch
                true
            },
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(delegate = actionExecutor),
        )

        val result = executor.execute(
            ToolRequest(
                id = "open-camera-routing",
                toolName = MobileActionFunctions.OPEN_CAMERA,
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals(MobileActionFunctions.OPEN_CAMERA, result.data["toolName"])
        assertEquals("ExternalActivityOpened", result.data["completionState"])
        assertEquals("false", result.data["completionVerified"])
        assertEquals("Unknown", result.data["externalOutcome"])
        assertEquals("Unknown", result.data["externalOutcomeSource"])
        assertEquals(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, result.data["intentAction"])
        assertEquals("AllowlistedCompletionMetadata", result.data["metadataPolicy"])
        assertEquals("false", result.data["rawPayloadIncluded"])
        val launch = launches.single()
        assertEquals(MobileActionFunctions.OPEN_CAMERA, launch.toolName)
        assertEquals(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, launch.action)
    }

    @Test
    fun currentScreenshotOcrFailsClosedUntilMediaProjectionConsent() {
        val delegate = RecordingDelegate()
        val provider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.MissingConsent,
        )
        val screenProvider = StaticCurrentScreenControlProvider()
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = provider,
                currentScreenControlProvider = screenProvider,
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
        assertEquals(0, screenProvider.observeCallCount)
    }

    @Test
    fun currentScreenshotOcrUsesOneShotProviderResultAfterConsent() {
        val delegate = RecordingDelegate()
        val provider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "当前屏幕 OCR 文本",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "当前屏幕 OCR 文本",
                        bounds = OcrTextBounds(left = 1, top = 2, right = 120, bottom = 42),
                        lines = listOf(OcrTextLine(text = "当前屏幕 OCR 文本")),
                    ),
                ),
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
        val ocrBlocks = JSONArray(result.data.getValue("ocrBlocksJson"))
        assertEquals("当前屏幕 OCR 文本", ocrBlocks.getJSONObject(0).getString("text"))
        assertEquals(1, ocrBlocks.getJSONObject(0).getJSONObject("bounds").getInt("left"))
        assertEquals("true", result.data["ocrTextIncluded"])
        assertEquals("false", result.data["truncated"])
        assertEquals("false", result.data["rawPayloadIncluded"])
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals(true.toString(), result.data["requiresLocalModel"])
        assertEquals(CurrentScreenshotOcrContract.OUTPUT_METADATA_POLICY, result.data["metadataPolicy"])
        assertEquals("true", result.data["screenObservationIncluded"])
        assertFalse(result.data.containsKey("screenObservationFailureKind"))
        val observation = JSONObject(result.data.getValue("screenObservationJson"))
        assertEquals("screen-before", observation.getString("observationId"))
        assertEquals(MessagePrivacy.LocalOnly.name, observation.getString("privacyLevel"))
        assertTrue(observation.getJSONArray("sources").toString().contains("accessibility"))
        assertTrue(observation.getJSONArray("sources").toString().contains("ocr"))
        assertEquals(1, observation.getJSONObject("sourceCounts").getInt("accessibility"))
        assertEquals(2, observation.getJSONObject("sourceCounts").getInt("ocr"))
        val observationElements = observation.getJSONArray("elements")
        assertEquals("n0_button", observationElements.getJSONObject(0).getString("id"))
        assertEquals("Continue", observationElements.getJSONObject(0).getString("text"))
        assertEquals("ocr:block:0", observationElements.getJSONObject(1).getString("id"))
        assertEquals("当前屏幕 OCR 文本", observationElements.getJSONObject(1).getString("text"))
        assertEquals(listOf("current-screenshot-ocr"), provider.capturedRequestIds)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrSucceedsWhenScreenObservationPermissionIsMissing() {
        val delegate = RecordingDelegate()
        val provider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "当前屏幕 OCR 文本",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "当前屏幕 OCR 文本",
                        bounds = OcrTextBounds(left = 1, top = 2, right = 120, bottom = 42),
                        lines = listOf(OcrTextLine(text = "当前屏幕 OCR 文本")),
                    ),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = provider,
                currentScreenControlProvider = StaticCurrentScreenControlProvider(
                    observeResult = ScreenStateReadResult.PermissionDenied("accessibility disabled"),
                ),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-denied-observation",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("当前屏幕 OCR 文本", result.data["ocrText"])
        assertTrue(result.data.containsKey("ocrBlocksJson"))
        assertEquals("false", result.data["screenObservationIncluded"])
        assertEquals("permission_missing", result.data["screenObservationFailureKind"])
        assertFalse(result.data.containsKey("screenObservationJson"))
        assertEquals(listOf("current-screenshot-ocr-denied-observation"), provider.capturedRequestIds)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintIsConsumedByNextUiTap() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "继续",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "继续",
                        bounds = OcrTextBounds(left = 10, top = 20, right = 110, bottom = 70),
                        lines = listOf(OcrTextLine(text = "继续")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "无可点击文本",
                    nodes = listOf(
                        staticNode(
                            id = "container",
                            text = "",
                            bounds = ScreenBounds(0, 0, 1080, 2200),
                        ),
                    ),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        val ocrResult = executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-grounding",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val tapResult = executor.execute(
            ToolRequest(
                id = "tap-ocr-target",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "继续", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )
        executor.execute(
            ToolRequest(
                id = "tap-ocr-target-again",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "继续", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, ocrResult.status)
        assertEquals(ToolStatus.Succeeded, tapResult.status)
        assertEquals(2, screenProvider.tapTargets.size)
        val firstHint = screenProvider.tapOcrGroundingHints.first()
        assertNotNull(firstHint)
        requireNotNull(firstHint)
        assertEquals("screen-before", firstHint.observationId)
        assertEquals("com.example.app", firstHint.packageName)
        assertEquals("ocr:block:0", firstHint.elementId)
        assertEquals("继续", firstHint.text)
        assertEquals(ScreenBounds(10, 20, 110, 70), firstHint.bounds)
        assertEquals(null, screenProvider.tapOcrGroundingHints[1])
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrDangerousGroundingHintBlocksNextUiTap() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "立即购买",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "立即购买",
                        bounds = OcrTextBounds(left = 760, top = 1800, right = 1040, bottom = 1900),
                        lines = listOf(OcrTextLine(text = "立即购买")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "商品详情",
                    nodes = listOf(
                        staticNode(
                            id = "container",
                            text = "",
                            bounds = ScreenBounds(0, 0, 1080, 2200),
                        ),
                    ),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-dangerous-grounding",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val tapResult = executor.execute(
            ToolRequest(
                id = "tap-dangerous-ocr-target",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "ocr:block:0", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, tapResult.status)
        assertEquals("dangerous_action", tapResult.data["failureKind"])
        assertEquals("tap", tapResult.data["actionType"])
        assertTrue(screenProvider.tapTargets.isEmpty())
        assertTrue(screenProvider.tapOcrGroundingHints.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintMatchesOcrElementIdTarget() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "继续\n继续",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "继续",
                        bounds = OcrTextBounds(left = 10, top = 20, right = 110, bottom = 70),
                        lines = listOf(OcrTextLine(text = "继续")),
                    ),
                    OcrTextBlock(
                        text = "继续",
                        bounds = OcrTextBounds(left = 20, top = 260, right = 220, bottom = 328),
                        lines = listOf(OcrTextLine(text = "继续")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "无可点击文本",
                    nodes = listOf(
                        staticNode(
                            id = "container",
                            text = "",
                            bounds = ScreenBounds(0, 0, 1080, 2200),
                        ),
                    ),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        val ocrResult = executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-repeated-target",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val tapResult = executor.execute(
            ToolRequest(
                id = "tap-ocr-element-id-target",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "ocr:block:1", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, ocrResult.status)
        assertEquals(ToolStatus.Succeeded, tapResult.status)
        assertEquals(listOf("ocr:block:1"), screenProvider.tapTargets)
        val hint = screenProvider.tapOcrGroundingHints.single()
        assertNotNull(hint)
        requireNotNull(hint)
        assertEquals("ocr:block:1", hint.elementId)
        assertEquals("继续", hint.text)
        assertEquals(ScreenBounds(20, 260, 220, 328), hint.bounds)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintRequiresElementIdForRepeatedOcrText() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "继续\n继续",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "继续",
                        bounds = OcrTextBounds(left = 10, top = 20, right = 110, bottom = 70),
                        lines = listOf(OcrTextLine(text = "继续")),
                    ),
                    OcrTextBlock(
                        text = "继续",
                        bounds = OcrTextBounds(left = 20, top = 260, right = 220, bottom = 328),
                        lines = listOf(OcrTextLine(text = "继续")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "无可点击文本",
                    nodes = listOf(
                        staticNode(
                            id = "container",
                            text = "",
                            bounds = ScreenBounds(0, 0, 1080, 2200),
                        ),
                    ),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-repeated-text",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val textTapResult = executor.execute(
            ToolRequest(
                id = "tap-repeated-ocr-text-target",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "继续", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )
        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-repeated-id",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val idTapResult = executor.execute(
            ToolRequest(
                id = "tap-repeated-ocr-element-id-target",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "ocr:block:1", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, textTapResult.status)
        assertEquals(ToolStatus.Succeeded, idTapResult.status)
        assertEquals(listOf("继续", "ocr:block:1"), screenProvider.tapTargets)
        assertNull(screenProvider.tapOcrGroundingHints[0])
        val hint = screenProvider.tapOcrGroundingHints[1]
        assertNotNull(hint)
        requireNotNull(hint)
        assertEquals("ocr:block:1", hint.elementId)
        assertEquals(ScreenBounds(20, 260, 220, 328), hint.bounds)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintMatchesNestedOcrElementIdTarget() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "取消 继续",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "取消 继续",
                        bounds = OcrTextBounds(left = 20, top = 20, right = 230, bottom = 70),
                        lines = listOf(
                            OcrTextLine(
                                text = "取消 继续",
                                bounds = OcrTextBounds(left = 20, top = 20, right = 230, bottom = 70),
                                elements = listOf(
                                    OcrTextElement(
                                        text = "取消",
                                        bounds = OcrTextBounds(left = 20, top = 20, right = 110, bottom = 70),
                                    ),
                                    OcrTextElement(
                                        text = "继续",
                                        bounds = OcrTextBounds(left = 130, top = 20, right = 230, bottom = 70),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "无可点击文本",
                    nodes = listOf(
                        staticNode(
                            id = "container",
                            text = "",
                            bounds = ScreenBounds(0, 0, 1080, 2200),
                        ),
                    ),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        val ocrResult = executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-nested-element",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val tapResult = executor.execute(
            ToolRequest(
                id = "tap-nested-ocr-element-id-target",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "ocr:block:0:line:0:element:1", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, ocrResult.status)
        assertEquals(ToolStatus.Succeeded, tapResult.status)
        assertEquals(listOf("ocr:block:0:line:0:element:1"), screenProvider.tapTargets)
        val hint = screenProvider.tapOcrGroundingHints.single()
        assertNotNull(hint)
        requireNotNull(hint)
        assertEquals("ocr:block:0:line:0:element:1", hint.elementId)
        assertEquals("继续", hint.text)
        assertEquals(ScreenBounds(130, 20, 230, 70), hint.bounds)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun modelObservationReplanConsumesCurrentScreenshotOcrGroundingHint() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "继续",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "继续",
                        bounds = OcrTextBounds(left = 10, top = 20, right = 110, bottom = 70),
                        lines = listOf(OcrTextLine(text = "继续")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "无可点击文本",
                    nodes = listOf(
                        staticNode(
                            id = "container",
                            text = "",
                            bounds = ScreenBounds(0, 0, 1080, 2200),
                        ),
                    ),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )
        val actionRuntime = RecordingActionPlanningRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "ocr:block:0", "timeoutMillis" to "500"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = actionRuntime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val ocrRequest = ToolRequest(
            id = "current-screenshot-ocr-model-loop",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
            reason = "test",
        )

        val ocrResult = executor.execute(ocrRequest)
        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-model-ocr-grounding-loop",
                    input = "看当前屏幕，帮我点击继续",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = ocrRequest,
                observedResult = ocrResult,
                priorRequests = listOf(ocrRequest),
            ),
        )
        assertNotNull(replan)
        requireNotNull(replan)
        val tapResult = executor.execute(replan.request)

        assertEquals(ToolStatus.Succeeded, ocrResult.status)
        assertEquals("true", ocrResult.data["screenObservationIncluded"])
        assertTrue(actionRuntime.lastInput.orEmpty().contains("ocrFallback=ocr:block:0"))
        assertEquals(ToolStatus.Succeeded, tapResult.status)
        assertEquals(listOf("ocr:block:0"), screenProvider.tapTargets)
        val hint = screenProvider.tapOcrGroundingHints.single()
        assertNotNull(hint)
        requireNotNull(hint)
        assertEquals("ocr:block:0", hint.elementId)
        assertEquals("继续", hint.text)
        assertEquals(ScreenBounds(10, 20, 110, 70), hint.bounds)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintIsRejectedAfterScreenSignatureChanges() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "继续",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "继续",
                        bounds = OcrTextBounds(left = 10, top = 20, right = 110, bottom = 70),
                        lines = listOf(OcrTextLine(text = "继续")),
                    ),
                ),
            ),
        )
        val beforeScreen = ScreenStateReadResult.Available(
            staticSnapshot(
                id = "before",
                packageName = "com.example.app",
                textSummary = "无可点击文本",
                nodes = listOf(
                    staticNode(
                        id = "container",
                        text = "",
                        bounds = ScreenBounds(0, 0, 1080, 2200),
                    ),
                ),
            ),
        )
        val changedScreen = ScreenStateReadResult.Available(
            staticSnapshot(
                id = "changed",
                packageName = "com.example.app",
                textSummary = "设置",
                nodes = listOf(
                    staticNode(
                        id = "settings-button",
                        text = "设置",
                        clickable = true,
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResults = listOf(
                beforeScreen,
                beforeScreen,
                changedScreen,
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        val ocrResult = executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-stale-grounding",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val tapResult = executor.execute(
            ToolRequest(
                id = "tap-stale-ocr-target",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "继续", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, ocrResult.status)
        assertEquals("true", ocrResult.data["screenObservationIncluded"])
        assertTrue(ocrResult.data.containsKey("screenObservationJson"))
        assertEquals(ToolStatus.Succeeded, tapResult.status)
        assertEquals(listOf("继续"), screenProvider.tapTargets)
        assertEquals(listOf(null), screenProvider.tapOcrGroundingHints)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintRejectedWhenScreenChangesBetweenCaptureAndObservation() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "继续",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "继续",
                        bounds = OcrTextBounds(left = 10, top = 20, right = 110, bottom = 70),
                        lines = listOf(OcrTextLine(text = "继续")),
                    ),
                ),
            ),
        )
        val beforeScreen = ScreenStateReadResult.Available(
            staticSnapshot(
                id = "before",
                packageName = "com.example.app",
                textSummary = "无可点击文本",
                nodes = listOf(
                    staticNode(
                        id = "container",
                        text = "",
                        bounds = ScreenBounds(0, 0, 1080, 2200),
                    ),
                ),
            ),
        )
        val changedScreen = ScreenStateReadResult.Available(
            staticSnapshot(
                id = "changed",
                packageName = "com.example.app",
                textSummary = "设置",
                nodes = listOf(
                    staticNode(
                        id = "settings-button",
                        text = "设置",
                        clickable = true,
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResults = listOf(
                beforeScreen,
                changedScreen,
                changedScreen,
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        val ocrResult = executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-between-capture-observe",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val tapResult = executor.execute(
            ToolRequest(
                id = "tap-between-capture-observe-ocr-target",
                toolName = MobileActionFunctions.UI_TAP,
                arguments = mapOf("target" to "继续", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, ocrResult.status)
        assertEquals("false", ocrResult.data["screenObservationIncluded"])
        assertEquals("page_changed", ocrResult.data["screenObservationFailureKind"])
        assertFalse(ocrResult.data.containsKey("screenObservationJson"))
        assertEquals(ToolStatus.Succeeded, tapResult.status)
        assertEquals(listOf("继续"), screenProvider.tapTargets)
        assertEquals(listOf(null), screenProvider.tapOcrGroundingHints)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintCanFocusNextUiTypeTextTarget() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "搜索",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "搜索",
                        bounds = OcrTextBounds(left = 20, top = 32, right = 420, bottom = 96),
                        lines = listOf(OcrTextLine(text = "搜索")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "搜索",
                    nodes = listOf(staticNode(id = "container", bounds = ScreenBounds(0, 0, 1080, 2200))),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-type-grounding",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val typeResult = executor.execute(
            ToolRequest(
                id = "type-ocr-target",
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = mapOf("target" to "搜索输入框", "text" to "数据线", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )
        executor.execute(
            ToolRequest(
                id = "type-ocr-target-again",
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = mapOf("target" to "搜索输入框", "text" to "耳机", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, typeResult.status)
        assertEquals(2, screenProvider.typeTextTargets.size)
        assertEquals("搜索输入框", screenProvider.typeTextTargets[0])
        assertEquals("数据线", screenProvider.typeTextValues[0])
        val firstHint = screenProvider.typeTextOcrGroundingHints.first()
        assertNotNull(firstHint)
        requireNotNull(firstHint)
        assertEquals("ocr:block:0", firstHint.elementId)
        assertEquals("搜索", firstHint.text)
        assertEquals(ScreenBounds(20, 32, 420, 96), firstHint.bounds)
        assertEquals(null, screenProvider.typeTextOcrGroundingHints[1])
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintCanFocusTargetlessUiTypeTextSearchEntry() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "搜索商品\n搜索",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "搜索商品",
                        bounds = OcrTextBounds(left = 20, top = 32, right = 720, bottom = 96),
                        lines = listOf(OcrTextLine(text = "搜索商品")),
                    ),
                    OcrTextBlock(
                        text = "搜索",
                        bounds = OcrTextBounds(left = 820, top = 32, right = 980, bottom = 96),
                        lines = listOf(OcrTextLine(text = "搜索")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "无搜索语义的容器",
                    nodes = listOf(staticNode(id = "container", bounds = ScreenBounds(0, 0, 1080, 2200))),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-targetless-type-grounding",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val typeResult = executor.execute(
            ToolRequest(
                id = "type-targetless-ocr-search-entry",
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = mapOf("text" to "数据线", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, typeResult.status)
        assertEquals(listOf(null), screenProvider.typeTextTargets)
        assertEquals(listOf("数据线"), screenProvider.typeTextValues)
        val hint = screenProvider.typeTextOcrGroundingHints.single()
        assertNotNull(hint)
        requireNotNull(hint)
        assertEquals("ocr:block:0", hint.elementId)
        assertEquals("搜索商品", hint.text)
        assertEquals(ScreenBounds(20, 32, 720, 96), hint.bounds)
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintExpiresBeforeTargetlessUiTypeText() {
        val delegate = RecordingDelegate()
        var nowMillis = 1_000L
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "搜索商品",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "搜索商品",
                        bounds = OcrTextBounds(left = 20, top = 32, right = 720, bottom = 96),
                        lines = listOf(OcrTextLine(text = "搜索商品")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "无搜索语义的容器",
                    nodes = listOf(staticNode(id = "container", bounds = ScreenBounds(0, 0, 1080, 2200))),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
                clockMillis = { nowMillis },
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-expiring-type",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        nowMillis += 15_001L
        val typeResult = executor.execute(
            ToolRequest(
                id = "type-expired-ocr-search-entry",
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = mapOf("text" to "数据线", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, typeResult.status)
        assertEquals("editable_not_found", typeResult.data["failureKind"])
        assertTrue(screenProvider.typeTextTargets.isEmpty())
        assertTrue(screenProvider.typeTextValues.isEmpty())
        assertTrue(screenProvider.typeTextOcrGroundingHints.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintDoesNotTargetlessTypeWithOnlySearchButtonText() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "搜索",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "搜索",
                        bounds = OcrTextBounds(left = 820, top = 32, right = 980, bottom = 96),
                        lines = listOf(OcrTextLine(text = "搜索")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "无搜索语义的容器",
                    nodes = listOf(staticNode(id = "container", bounds = ScreenBounds(0, 0, 1080, 2200))),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-targetless-type-button-only",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val typeResult = executor.execute(
            ToolRequest(
                id = "type-targetless-button-only",
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = mapOf("text" to "数据线", "timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, typeResult.status)
        assertEquals("editable_not_found", typeResult.data["failureKind"])
        assertTrue(screenProvider.typeTextTargets.isEmpty())
        assertTrue(screenProvider.typeTextValues.isEmpty())
        assertTrue(screenProvider.typeTextOcrGroundingHints.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintCanSubmitSearchButton() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "搜索商品\n搜索",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "搜索商品",
                        bounds = OcrTextBounds(left = 20, top = 32, right = 820, bottom = 96),
                        lines = listOf(OcrTextLine(text = "搜索商品")),
                    ),
                    OcrTextBlock(
                        text = "搜索",
                        bounds = OcrTextBounds(left = 900, top = 32, right = 1040, bottom = 96),
                        lines = listOf(OcrTextLine(text = "搜索")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "搜索商品 搜索",
                    nodes = listOf(
                        staticNode(
                            id = "search-field",
                            text = "搜索商品",
                            className = "android.widget.EditText",
                            bounds = ScreenBounds(20, 32, 820, 96),
                            editable = true,
                        ),
                        staticNode(
                            id = "search-submit",
                            text = "搜索",
                            bounds = ScreenBounds(900, 32, 1040, 96),
                            clickable = true,
                        ),
                    ),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-submit-grounding",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val submitResult = executor.execute(
            ToolRequest(
                id = "submit-search-ocr-target",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )
        executor.execute(
            ToolRequest(
                id = "submit-search-ocr-target-again",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, submitResult.status)
        assertEquals(2, screenProvider.submitSearchOcrGroundingHints.size)
        val firstHint = screenProvider.submitSearchOcrGroundingHints.first()
        assertNotNull(firstHint)
        requireNotNull(firstHint)
        assertEquals("ocr:block:1", firstHint.elementId)
        assertEquals("搜索", firstHint.text)
        assertEquals(ScreenBounds(900, 32, 1040, 96), firstHint.bounds)
        assertEquals(null, screenProvider.submitSearchOcrGroundingHints[1])
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintDoesNotTreatGoogleLogoAsSubmitSearch() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "Google",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "Google",
                        bounds = OcrTextBounds(left = 120, top = 120, right = 360, bottom = 210),
                        lines = listOf(OcrTextLine(text = "Google")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "Google",
                    nodes = listOf(staticNode(id = "container", bounds = ScreenBounds(0, 0, 1080, 2200))),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-submit-google",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        executor.execute(
            ToolRequest(
                id = "submit-search-google-logo",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertTrue(screenProvider.submitSearchOcrGroundingHints.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintDoesNotTreatSearchPlaceholderAsSubmitSearch() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "搜索商品",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "搜索商品",
                        bounds = OcrTextBounds(left = 20, top = 32, right = 820, bottom = 96),
                        lines = listOf(OcrTextLine(text = "搜索商品")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "搜索商品",
                    nodes = listOf(staticNode(id = "container", bounds = ScreenBounds(0, 0, 1080, 2200))),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-submit-placeholder",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        executor.execute(
            ToolRequest(
                id = "submit-search-placeholder",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertTrue(screenProvider.submitSearchOcrGroundingHints.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintDoesNotSubmitWithOnlyConfirmOcr() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "确定",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "确定",
                        bounds = OcrTextBounds(left = 900, top = 32, right = 1040, bottom = 96),
                        lines = listOf(OcrTextLine(text = "确定")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "confirm-dialog",
                    packageName = "com.example.app",
                    textSummary = "确定",
                    nodes = listOf(staticNode(id = "container", bounds = ScreenBounds(0, 0, 1080, 2200))),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-confirm-only",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val result = executor.execute(
            ToolRequest(
                id = "submit-search-confirm-only",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("submit_not_found", result.data["failureKind"])
        assertTrue(screenProvider.submitSearchOcrGroundingHints.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintDoesNotPairSearchContextWithFarConfirmForSubmitSearch() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "搜索商品\n确定",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "搜索商品",
                        bounds = OcrTextBounds(left = 20, top = 32, right = 820, bottom = 96),
                        lines = listOf(OcrTextLine(text = "搜索商品")),
                    ),
                    OcrTextBlock(
                        text = "确定",
                        bounds = OcrTextBounds(left = 760, top = 1500, right = 1040, bottom = 1580),
                        lines = listOf(OcrTextLine(text = "确定")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "confirm-dialog-with-search-placeholder",
                    packageName = "com.example.app",
                    textSummary = "搜索商品 确定",
                    nodes = listOf(staticNode(id = "container", bounds = ScreenBounds(0, 0, 1080, 2200))),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-search-context-confirm",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val result = executor.execute(
            ToolRequest(
                id = "submit-search-search-context-confirm",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals("submit_not_found", result.data["failureKind"])
        assertTrue(screenProvider.submitSearchOcrGroundingHints.isEmpty())
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun currentScreenshotOcrGroundingHintClearedByInterveningNonDeviceTool() {
        val delegate = RecordingDelegate()
        val ocrProvider = StaticCurrentScreenshotOcrProvider(
            CurrentScreenshotOcrReadResult.Available(
                text = "搜索",
                truncated = false,
                ocrBlocks = listOf(
                    OcrTextBlock(
                        text = "搜索",
                        bounds = OcrTextBounds(left = 900, top = 32, right = 1040, bottom = 96),
                        lines = listOf(OcrTextLine(text = "搜索")),
                    ),
                ),
            ),
        )
        val screenProvider = StaticCurrentScreenControlProvider(
            observeResult = ScreenStateReadResult.Available(
                staticSnapshot(
                    id = "before",
                    packageName = "com.example.app",
                    textSummary = "搜索",
                    nodes = listOf(staticNode(id = "container", bounds = ScreenBounds(0, 0, 1080, 2200))),
                ),
            ),
        )
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenshotOcrProvider = ocrProvider,
                currentScreenControlProvider = screenProvider,
            ),
        )

        executor.execute(
            ToolRequest(
                id = "current-screenshot-ocr-submit-interrupted",
                toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                arguments = mapOf("captureMode" to "current_screen"),
                reason = "test",
            ),
        )
        val foregroundResult = executor.execute(
            ToolRequest(
                id = "intervening-foreground-app",
                toolName = MobileActionFunctions.QUERY_FOREGROUND_APP,
                reason = "test",
            ),
        )
        executor.execute(
            ToolRequest(
                id = "submit-search-after-intervening-tool",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, foregroundResult.status)
        assertTrue(screenProvider.submitSearchOcrGroundingHints.isEmpty())
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
        assertFalse(result.data.containsKey("screenObservationJson"))
        assertFalse(result.data.containsKey("textSummary"))
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun submitSearchFailureKeepsRecoverableFailureKind() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenControlProvider = StaticCurrentScreenControlProvider(
                    actionResult = UiActionReadResult.Available(
                        UiActionExecutionResult(
                            status = UiActionStatus.Failed,
                            before = staticSnapshot("before"),
                            after = staticSnapshot("after"),
                            summary = "未找到可提交搜索的输入法动作或按钮",
                            retryable = true,
                            failureKind = UiActionFailureKind.SubmitNotFound,
                        ),
                    ),
                ),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "submit-search-failed",
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = mapOf("timeoutMillis" to "500"),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertEquals("submit_search", result.data["actionType"])
        assertEquals("failed", result.data["status"])
        assertEquals("submit_not_found", result.data["failureKind"])
        assertEquals("screen-before", result.data["beforeObservationId"])
        assertEquals("screen-before", result.data["afterObservationId"])
        assertTrue(result.data.getValue("beforeScreenObservationJson").contains("screen-before"))
        assertTrue(result.data.getValue("afterScreenObservationJson").contains("screen-before"))
        assertTrue(result.data.getValue("screenObservationDiffSummary").contains("changed=false"))
        assertFalse(result.data.containsKey("beforeNodesJson"))
        assertFalse(result.data.containsKey("afterNodesJson"))
        assertEquals(MessagePrivacy.LocalOnly.name, result.data["privacy"])
        assertEquals(true.toString(), result.data["requiresLocalModel"])
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun waitSearchVerificationKeepsLocalResultEvidence() {
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenControlProvider = StaticCurrentScreenControlProvider(
                    actionResult = UiActionReadResult.Available(
                        UiActionExecutionResult(
                            status = UiActionStatus.Succeeded,
                            before = staticSnapshot(
                                id = "before",
                                packageName = "com.taobao.taobao",
                                textSummary = "搜索商品",
                            ),
                            after = staticSnapshot(
                                id = "after",
                                packageName = "com.taobao.taobao",
                                textSummary = "海河牛奶 综合 销量 筛选",
                                nodes = listOf(
                                    staticNode(id = "result", text = "海河牛奶旗舰店", clickable = true),
                                    staticNode(id = "filter", text = "筛选", clickable = true),
                                ),
                            ),
                            summary = "已等待屏幕稳定",
                            retryable = false,
                        ),
                    ),
                ),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "wait-search-verified",
                toolName = MobileActionFunctions.UI_WAIT,
                arguments = mapOf(
                    "timeoutMillis" to "500",
                    "verifySearchQuery" to "海河牛奶",
                    "expectedPackageName" to "com.taobao.taobao",
                ),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Succeeded, result.status)
        assertEquals("wait", result.data["actionType"])
        assertEquals("verified", result.data["searchVerificationStatus"])
        assertEquals("query_visible_after_change", result.data["searchVerificationEvidence"])
        assertFalse(result.data.containsKey("failureKind"))
        assertTrue(delegate.requests.isEmpty())
    }

    @Test
    fun waitSearchVerificationFailureReturnsRecoverableResultNotVerified() {
        val unchanged = staticSnapshot(
            id = "same",
            packageName = "com.taobao.taobao",
            textSummary = "搜索商品",
        )
        val delegate = RecordingDelegate()
        val executor = ValidatingToolExecutor(
            routingExecutor(
                delegate = delegate,
                currentScreenControlProvider = StaticCurrentScreenControlProvider(
                    actionResult = UiActionReadResult.Available(
                        UiActionExecutionResult(
                            status = UiActionStatus.Succeeded,
                            before = unchanged,
                            after = unchanged,
                            summary = "已等待屏幕稳定",
                            retryable = false,
                        ),
                    ),
                ),
            ),
        )

        val result = executor.execute(
            ToolRequest(
                id = "wait-search-not-verified",
                toolName = MobileActionFunctions.UI_WAIT,
                arguments = mapOf(
                    "timeoutMillis" to "500",
                    "verifySearchQuery" to "海河牛奶",
                    "expectedPackageName" to "com.taobao.taobao",
                ),
                reason = "test",
            ),
        )

        assertEquals(ToolStatus.Failed, result.status)
        assertEquals(ToolErrorCode.ExecutionFailed, result.error?.code)
        assertEquals("result_not_verified", result.data["failureKind"])
        assertEquals("not_verified", result.data["searchVerificationStatus"])
        assertEquals("page_not_changed", result.data["searchVerificationEvidence"])
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
        clockMillis: () -> Long = { System.currentTimeMillis() },
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
            clockMillis = clockMillis,
        )

    private class StaticCurrentScreenControlProvider(
        observeResult: ScreenStateReadResult = ScreenStateReadResult.Available(staticSnapshot("before")),
        private val observeResults: List<ScreenStateReadResult> = listOf(observeResult),
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
        private var observeIndex = 0
        var observeCallCount = 0
            private set
        val tapTargets = mutableListOf<String>()
        val tapOcrGroundingHints = mutableListOf<UiOcrGroundingHint?>()
        val typeTextTargets = mutableListOf<String?>()
        val typeTextValues = mutableListOf<String>()
        val typeTextOcrGroundingHints = mutableListOf<UiOcrGroundingHint?>()
        val submitSearchOcrGroundingHints = mutableListOf<UiOcrGroundingHint?>()

        override fun observeCurrentScreen(maxTextChars: Int, maxNodes: Int): ScreenStateReadResult {
            observeCallCount += 1
            val result = observeResults.getOrElse(observeIndex) { observeResults.last() }
            if (observeIndex < observeResults.lastIndex) observeIndex += 1
            return result
        }

        override fun tap(target: String, timeoutMillis: Long): UiActionReadResult {
            tapTargets += target
            tapOcrGroundingHints += null
            return actionResult
        }

        override fun tapWithOcrGrounding(
            target: String,
            ocrGroundingHint: UiOcrGroundingHint?,
            timeoutMillis: Long,
        ): UiActionReadResult {
            tapTargets += target
            tapOcrGroundingHints += ocrGroundingHint
            return actionResult
        }

        override fun typeText(text: String, target: String?, timeoutMillis: Long): UiActionReadResult {
            typeTextValues += text
            typeTextTargets += target
            typeTextOcrGroundingHints += null
            return actionResult
        }

        override fun typeTextWithOcrGrounding(
            text: String,
            target: String?,
            ocrGroundingHint: UiOcrGroundingHint?,
            timeoutMillis: Long,
        ): UiActionReadResult {
            typeTextValues += text
            typeTextTargets += target
            typeTextOcrGroundingHints += ocrGroundingHint
            return actionResult
        }

        override fun submitSearch(timeoutMillis: Long): UiActionReadResult {
            submitSearchOcrGroundingHints += null
            return actionResult
        }

        override fun submitSearchWithOcrGrounding(
            ocrGroundingHint: UiOcrGroundingHint?,
            timeoutMillis: Long,
        ): UiActionReadResult {
            submitSearchOcrGroundingHints += ocrGroundingHint
            return actionResult
        }

        override fun scroll(direction: UiScrollDirection, target: String?, timeoutMillis: Long): UiActionReadResult =
            actionResult

        override fun pressBack(timeoutMillis: Long): UiActionReadResult =
            actionResult

        override fun waitForScreen(timeoutMillis: Long): UiActionReadResult =
            actionResult
    }

    private companion object {
        fun staticSnapshot(
            id: String,
            packageName: String = "com.example.app",
            textSummary: String = "Continue",
            nodes: List<ScreenNode> = listOf(staticNode(id = "n0_button", text = "Continue", clickable = true)),
        ): ScreenStateSnapshot =
            ScreenStateSnapshot(
                id = "screen-$id",
                packageName = packageName,
                capturedAtMillis = Instant.parse("2026-06-01T09:00:00Z").toEpochMilli(),
                nodes = nodes,
                textSummary = textSummary,
                truncated = false,
            )

        fun staticNode(
            id: String,
            text: String = "",
            contentDescription: String = "",
            className: String = "android.widget.Button",
            bounds: ScreenBounds? = ScreenBounds(0, 0, 100, 48),
            clickable: Boolean = false,
            editable: Boolean = false,
            scrollable: Boolean = false,
            enabled: Boolean = true,
        ): ScreenNode =
            ScreenNode(
                id = id,
                text = text,
                contentDescription = contentDescription,
                className = className,
                bounds = bounds,
                clickable = clickable,
                editable = editable,
                scrollable = scrollable,
                enabled = enabled,
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

        override fun hasOneShotConsent(requestId: String, nowMillis: Long): Boolean =
            result !is CurrentScreenshotOcrReadResult.MissingConsent

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

    private class RecordingActionPlanningRuntime(
        private val toolName: String,
        private val parameters: Map<String, String>,
    ) : ActionPlanningRuntime {
        var lastInput: String? = null

        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            lastInput = input
            return ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = toolName,
                        title = "Test action",
                        summary = "Run test action",
                        parameters = parameters,
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = true,
                fallbackReason = null,
            )
        }
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
