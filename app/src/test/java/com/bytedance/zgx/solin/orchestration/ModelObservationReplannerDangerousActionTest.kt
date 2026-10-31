package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelObservationReplannerDangerousActionTest : ObservationReplannerTestFixtures() {
    @Test
    fun modelReplannerAllowsTypeTextWhenSeparateDangerousControlExists() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            parameters = mapOf(
                "target" to "search-input",
                "text" to "海河牛奶",
            ),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-search-input-with-delete",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-type-with-delete",
                    input = "打开淘宝搜索海河牛奶",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已观察当前屏幕。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "requiresLocalModel" to true.toString(),
                        "screenObservationJson" to searchInputWithDangerousSiblingObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TYPE_TEXT, replan?.request?.toolName)
        assertEquals("search-input", replan?.request?.arguments?.get("target"))
        assertEquals("accepted", replanner.lastDiagnosticSnapshot()?.reason)
    }

    @Test
    fun modelReplannerRejectsTypeTextAimedAtACriticalControl() {
        // Finding the field taps a non-editable target, so typing "into" 发送 would tap it.
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            parameters = mapOf("target" to "发送", "text" to "x"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-type-into-send",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-type-into-send",
                    input = "看当前屏幕继续操作",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已观察当前屏幕。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "requiresLocalModel" to true.toString(),
                        "screenObservationJson" to searchInputWithDangerousSiblingObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertEquals("dangerous_observation_action", replanner.lastDiagnosticSnapshot()?.reason)
    }

    @Test
    fun modelReplannerRejectsTargetedDangerousActionControl() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "delete-recent"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-delete-control",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-delete-control",
                    input = "看当前屏幕继续操作",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已观察当前屏幕。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "requiresLocalModel" to true.toString(),
                        "screenObservationJson" to searchInputWithDangerousSiblingObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertEquals("dangerous_observation_action", replanner.lastDiagnosticSnapshot()?.reason)
    }

    @Test
    fun modelReplannerRejectsDangerousOcrActionEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "确认支付"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "ocr-danger",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-dangerous-ocr",
                    input = "看当前屏幕继续操作",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已从当前屏幕单次截图提取 OCR 摘录。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "ocrText" to "确认支付",
                        "ocrBlocksJson" to ocrBlocksJson(
                            ocrBlock("确认支付", left = 10, top = 20, right = 150, bottom = 70),
                        ),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("payment, sending, deletion, publishing"))
    }

    @Test
    fun modelReplannerAllowsStaticDangerousTextWithoutActionControl() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "continue-button"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-static-payment-copy",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-static-copy",
                    input = "阅读说明后继续",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已观察当前屏幕。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "screenObservationJson" to staticPaymentCopyObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("continue-button", replan?.request?.arguments?.get("target"))
    }

    @Test
    fun modelReplannerAllowsStaticDangerousOcrCopyWithoutActionControl() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "continue-button"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "ocr-static-payment-copy",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-static-ocr-copy",
                    input = "阅读说明后继续",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已从当前屏幕单次截图提取 OCR 摘录。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "ocrText" to "支付说明",
                        "ocrBlocksJson" to ocrBlocksJson(
                            ocrBlock("支付说明", left = 10, top = 20, right = 150, bottom = 70),
                        ),
                        "screenObservationJson" to staticPaymentOcrCopyObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("continue-button", replan?.request?.arguments?.get("target"))
    }

    @Test
    fun modelReplannerRejectsStandaloneDangerousOcrButtonText() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "删除"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "ocr-delete",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-delete-ocr",
                    input = "看当前屏幕继续操作",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已从当前屏幕单次截图提取 OCR 摘录。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "ocrBlocksJson" to ocrBlocksJson(
                            ocrBlock("删除", left = 10, top = 20, right = 150, bottom = 70),
                        ),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
    }
}
