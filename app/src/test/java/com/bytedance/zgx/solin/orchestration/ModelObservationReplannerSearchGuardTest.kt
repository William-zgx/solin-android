package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelObservationReplannerSearchGuardTest : ObservationReplannerTestFixtures() {
    @Test
    fun modelReplannerRejectsTargetlessTypeTextFromScreenTextOnlyEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            parameters = mapOf("text" to "数据线"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "read-current-screen-text-only",
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-screen-text-only",
                    input = "在搜索框输入数据线",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已读取当前屏幕文本。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "screenText" to "搜索商品",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertTrue(runtime.lastInput.orEmpty().contains("screenText=搜索商品"))
    }

    @Test
    fun modelReplannerRejectsTargetlessTypeTextWithoutSearchEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            parameters = mapOf("text" to "hello"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-current-targetless-type",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-targetless-type",
                    input = "根据当前屏幕输入 hello",
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
                        "screenObservationJson" to accessibilityOnlyObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerAllowsTargetlessTypeTextWithSearchEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            parameters = mapOf("text" to "数据线"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-current-targetless-search-type",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-targetless-search-type",
                    input = "在搜索框输入数据线",
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
                        "screenObservationJson" to searchInputObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TYPE_TEXT, replan?.request?.toolName)
        assertEquals("数据线", replan?.request?.arguments?.get("text"))
        assertFalse(replan?.request?.arguments.orEmpty().containsKey("target"))
    }

    @Test
    fun modelReplannerAllowsTargetlessTypeTextWithOcrSearchEntryEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            parameters = mapOf("text" to "数据线"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-current-targetless-ocr-search-type",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-targetless-ocr-search-type",
                    input = "在搜索框输入数据线",
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
                            ocrBlock("搜索商品", left = 20, top = 520, right = 720, bottom = 588),
                        ),
                        "screenObservationJson" to ocrOnlyObservationJson(
                            observationId = "screen-ocr-search-entry",
                            texts = listOf("搜索商品"),
                        ),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TYPE_TEXT, replan?.request?.toolName)
        assertEquals("数据线", replan?.request?.arguments?.get("text"))
        assertFalse(replan?.request?.arguments.orEmpty().containsKey("target"))
    }

    @Test
    fun modelReplannerRejectsTargetlessTypeTextWithOnlySearchButtonOcrEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            parameters = mapOf("text" to "数据线"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-current-targetless-ocr-search-button",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-targetless-ocr-search-button",
                    input = "在搜索框输入数据线",
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
                            ocrBlock("搜索", left = 820, top = 520, right = 980, bottom = 588),
                        ),
                        "screenObservationJson" to ocrOnlyObservationJson(
                            observationId = "screen-ocr-search-button",
                            texts = listOf("搜索"),
                        ),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerRejectsSubmitSearchWithoutSearchEvidence() {
        val runtime = RecordingModelActionRuntime(MobileActionFunctions.UI_SUBMIT_SEARCH)
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-no-search",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-no-search-evidence",
                    input = "根据当前屏幕继续",
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
                        "screenObservationJson" to accessibilityOnlyObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerRejectsSubmitSearchWithOnlyConfirmOcrEvidence() {
        val runtime = RecordingModelActionRuntime(MobileActionFunctions.UI_SUBMIT_SEARCH)
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-confirm-ocr",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-confirm-ocr-submit",
                    input = "根据当前屏幕继续",
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
                            ocrBlock("确定", left = 780, top = 520, right = 980, bottom = 588),
                        ),
                        "screenObservationJson" to ocrOnlyObservationJson(
                            observationId = "screen-confirm-ocr",
                            texts = listOf("确定"),
                        ),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerRejectsSubmitSearchWithOnlySearchButtonOcrEvidence() {
        val runtime = RecordingModelActionRuntime(MobileActionFunctions.UI_SUBMIT_SEARCH)
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-search-button-ocr",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-search-button-ocr-submit",
                    input = "提交当前搜索",
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
                            ocrBlock("搜索", left = 780, top = 520, right = 980, bottom = 588),
                        ),
                        "screenObservationJson" to ocrOnlyObservationJson(
                            observationId = "screen-search-button-ocr",
                            texts = listOf("搜索"),
                        ),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerAllowsSubmitSearchWithOcrSearchContextAndSubmitEvidence() {
        val runtime = RecordingModelActionRuntime(MobileActionFunctions.UI_SUBMIT_SEARCH)
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-search-ocr",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-search-ocr-submit",
                    input = "提交当前搜索",
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
                            ocrBlock("搜索商品", left = 20, top = 520, right = 720, bottom = 588),
                            ocrBlock("确定", left = 780, top = 520, right = 980, bottom = 588),
                        ),
                        "screenObservationIncluded" to "true",
                        "screenObservationJson" to ocrOnlyObservationJson(
                            observationId = "screen-search-ocr-submit",
                            texts = listOf("搜索商品", "确定"),
                        ),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_SUBMIT_SEARCH, replan?.request?.toolName)
    }

    @Test
    fun modelReplannerAllowsSubmitSearchFromObservationDiffEvidence() {
        val runtime = RecordingModelActionRuntime(MobileActionFunctions.UI_SUBMIT_SEARCH)
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "type-search-text",
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            arguments = mapOf("target" to "search-input", "text" to "Kotlin"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-submit-from-diff",
                    input = "输入关键词后提交搜索",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已输入关键词。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "afterScreenObservationJson" to emptyObservationJson("after-submit-diff"),
                        "screenObservationDiffSummary" to
                            "changed=true;addedText=搜索输入框|搜索;" +
                            "addedActionable=editable:搜索输入框|clickable:搜索",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_SUBMIT_SEARCH, replan?.request?.toolName)
    }

    @Test
    fun modelReplannerConvertsTypeTextOnSearchTapOnlyTargetIntoTap() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            parameters = mapOf(
                "target" to "search-entry",
                "text" to "海河牛奶",
            ),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-search-entry",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-type-tap-only",
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
                        "screenObservationJson" to searchEntryObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("search-entry", replan?.request?.arguments?.get("target"))
        assertFalse(replan?.request?.arguments?.containsKey("text") == true)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerRejectsTypeTextOnNonSearchTapOnlyTargetEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            parameters = mapOf(
                "target" to "continue-button",
                "text" to "海河牛奶",
            ),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-continue-button",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-type-non-search-tap-only",
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
                        "screenObservationJson" to screenObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerConvertsTypeTextOnOcrLabeledSearchTapTargetIntoTap() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            parameters = mapOf(
                "target" to "icon-search-entry",
                "text" to "海河牛奶",
            ),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-ocr-search-entry",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-type-ocr-search-tap-only",
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
                        "screenObservationJson" to ocrLabeledBlankAccessibilityObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("icon-search-entry", replan?.request?.arguments?.get("target"))
        assertFalse(replan?.request?.arguments?.containsKey("text") == true)
    }

    @Test
    fun modelReplannerAllowsTypeTextOnEditableTargetEvidence() {
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
            id = "observe-search-input",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-type-editable",
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
                        "screenObservationJson" to searchInputObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TYPE_TEXT, replan?.request?.toolName)
        assertEquals("search-input", replan?.request?.arguments?.get("target"))
    }
}
