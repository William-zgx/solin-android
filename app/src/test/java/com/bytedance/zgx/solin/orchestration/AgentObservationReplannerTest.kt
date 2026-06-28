package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentObservationReplannerTest {
    @Test
    fun modelReplannerPromptIncludesBoundedLocalObservationEvidence() {
        val runtime = RecordingModelActionRuntime(MobileActionFunctions.UI_TAP)
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    specFor(
                        toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                        privateOutputKeys = setOf("ocrText", "ocrBlocksJson", "screenObservationJson"),
                        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
                    ),
                ) + localOnlyAllowedToolSpecs()
            },
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            toolRegistry = registry,
        )
        val previousRequest = ToolRequest(
            id = "ocr-1",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr",
                    input = "看当前屏幕，帮我点击继续",
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
                        "ocrText" to "继续",
                        "ocrBlocksJson" to """[{"text":"继续","bounds":{"left":10,"top":20,"right":110,"bottom":70},"lines":[]}]""",
                        "screenObservationJson" to screenObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("LocalOnly observation evidence"))
        assertTrue(
            prompt.contains(
                "output only these local device-control tools: " +
                    "observe_current_screen,ui_tap,ui_type_text,ui_submit_search,ui_scroll,ui_wait",
            ),
        )
        assertTrue(prompt.contains("Do not output web_search"))
        assertTrue(prompt.contains("copy only the candidate target=... value into the tool target"))
        assertTrue(prompt.contains("Use targetShortlist(...) as the exact target string shortlist"))
        assertTrue(prompt.contains("never output mode, bounds, confidence, or other candidate metadata"))
        assertTrue(prompt.contains("Prefer a type-tagged target for ui_type_text"))
        assertTrue(prompt.contains("Use an ocrFallback target only when no Accessibility target fits"))
        assertTrue(prompt.contains("screenObservationJson(id=screen-1"))
        assertTrue(prompt.contains("sources=accessibility,ocr"))
        assertTrue(prompt.contains("sourceCounts=accessibility=13,ocr=1"))
        assertTrue(prompt.contains("continue-button{accessibility/button,text=继续"))
        assertTrue(prompt.contains("ocr:block:0{ocr/ocr_block,text=继续"))
        assertTrue(prompt.contains("bounds=[10,20,110,70]"))
        assertTrue(
            prompt.contains(
                "targets=[继续{target=continue-button,modeTags=tap,source=accessibility,role=button,bounds=[10,20,110,70],confidence=1.00}",
            ),
        )
        assertTrue(
            prompt.contains(
                "继续{target=ocr:block:0,modeTags=ocrFallback,source=ocr,role=ocr_block,bounds=[10,20,110,70],confidence=0.72}",
            ),
        )
        assertTrue(prompt.contains("targetShortlist(tap=continue-button,ocrFallback=ocr:block:0)"))
        assertTrue(prompt.contains("Observation private data keys omitted: ocrBlocksJson, ocrText, screenObservationJson"))
        assertFalse(prompt.contains("ocrBlocks(count="))
        assertFalse(prompt.contains("\"elements\""))
        assertFalse(prompt.contains("\"lines\""))
    }

    @Test
    fun modelReplannerPromptPromotesOcrLabelOnBlankAccessibilityTarget() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "icon-search-entry", "timeoutMillis" to "500"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-ocr-labeled-icon",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr-labeled-icon",
                    input = "点当前屏幕上的搜索入口",
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
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(
            prompt.contains(
                "搜索入口{target=icon-search-entry,modeTags=tap,source=accessibility+ocr,role=button,bounds=[20,32,420,96],confidence=1.00}",
            ),
        )
        assertTrue(prompt.contains("targetShortlist(tap=icon-search-entry"))
        assertFalse(prompt.contains("icon-search-entry{accessibility/button,text=搜索入口"))
    }

    @Test
    fun modelReplannerPromptDoesNotFuseSparseOcrEdgeOverlapIntoAccessibilityTarget() {
        val runtime = RecordingModelActionRuntime(toolName = MobileActionFunctions.UI_WAIT)
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-sparse-ocr-labeled-icon",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-sparse-ocr-labeled-icon",
                    input = "点当前屏幕上的搜索入口",
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
                        "screenObservationJson" to sparseOcrLabeledBlankAccessibilityObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        val prompt = runtime.lastInput.orEmpty()
        assertFalse(prompt.contains("搜索入口{target=icon-search-entry"))
        assertFalse(prompt.contains("source=accessibility+ocr"))
        assertTrue(prompt.contains("搜索入口{target=ocr:block:0,modeTags=ocrFallback"))
    }

    @Test
    fun modelReplannerTargetShortlistUsesResolverRankingForSearchEntryNoise() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "real-search-entry", "timeoutMillis" to "500"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-search-noise",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-search-noise",
                    input = "点搜索入口",
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
                        "screenObservationJson" to resolverRankedSearchEntryObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("real-search-entry", replan?.request?.arguments?.get("target"))
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("拍照搜索{target=camera-search"))
        assertTrue(prompt.contains("搜索商品{target=real-search-entry"))
        assertTrue(prompt.shortlistDebugText(), prompt.contains("targetShortlist(tap=real-search-entry"))
        assertFalse(prompt.contains("targetShortlist(tap=camera-search"))
    }

    @Test
    fun modelReplannerTargetShortlistPrioritizesIntentKindForFilterEntry() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "filter-button", "timeoutMillis" to "500"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-filter-intent",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-filter-intent",
                    input = "点筛选",
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
                        "screenObservationJson" to intentRankedFilterObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("filter-button", replan?.request?.arguments?.get("target"))
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("搜索商品{target=search-entry"))
        assertTrue(prompt.contains("筛选{target=filter-button"))
        assertTrue(prompt.shortlistDebugText(), prompt.contains("targetShortlist(tap=filter-button"))
        assertFalse(prompt.contains("targetShortlist(tap=search-entry"))
    }

    @Test
    fun modelReplannerRejectsWebSearchFromLocalOnlyOcrObservation() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.WEB_SEARCH,
            parameters = mapOf("query" to "继续"),
        )
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    specFor(
                        toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                        privateOutputKeys = setOf("ocrText", "ocrBlocksJson", "screenObservationJson"),
                        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
                    ),
                    specFor(toolName = MobileActionFunctions.WEB_SEARCH),
                ) + localOnlyAllowedToolSpecs()
            },
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            toolRegistry = registry,
        )
        val previousRequest = ToolRequest(
            id = "ocr-local-only",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr-local-only",
                    input = "看当前屏幕，告诉我下一步",
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
                        "requiresLocalModel" to "true",
                        "ocrText" to "继续",
                        "screenObservationJson" to screenObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertTrue(runtime.lastInput.orEmpty().contains("LocalOnly observation evidence"))
        assertTrue(runtime.lastInput.orEmpty().contains("Do not output web_search"))
    }

    @Test
    fun modelReplannerFallsBackToOcrBlocksWhenObservationLacksOcrSource() {
        val runtime = RecordingModelActionRuntime(MobileActionFunctions.UI_TAP)
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    specFor(
                        toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                        privateOutputKeys = setOf("ocrText", "ocrBlocksJson", "screenObservationJson"),
                        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
                    ),
                    specFor(toolName = MobileActionFunctions.UI_TAP),
                )
            },
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            toolRegistry = registry,
        )
        val previousRequest = ToolRequest(
            id = "ocr-2",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr-fallback",
                    input = "看当前屏幕，帮我点击继续",
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
                        "ocrText" to "继续",
                        "ocrBlocksJson" to """[{"text":"继续","bounds":{"left":12,"top":22,"right":112,"bottom":72},"lines":[]}]""",
                        "screenObservationIncluded" to "true",
                        "screenObservationJson" to accessibilityOnlyObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("screenObservationJson(id=screen-accessibility-only"))
        assertTrue(prompt.contains("sourceCounts=accessibility=1"))
        assertTrue(
            prompt.contains(
                "ocrBlocks(count=1) targetShortlist(ocrFallback=ocr:block:0)=[block0{text=继续,bounds=[12,22,112,72]}]",
            ),
        )
        assertTrue(
            prompt,
            prompt.indexOf("ocrBlocks(count=1) targetShortlist(ocrFallback=ocr:block:0)") <
                prompt.indexOf("targets=[继续{target=ocr:block:0"),
        )
        assertTrue(
            prompt.contains(
                "targets=[继续{target=ocr:block:0,modeTags=ocrFallback,source=ocr,role=ocr_block,bounds=[12,22,112,72]}]",
            ),
        )
        assertTrue(prompt.contains("targetShortlist(ocrFallback=ocr:block:0)"))
        assertFalse(prompt.contains("\"lines\""))
    }

    @Test
    fun modelReplannerRejectsOcrTargetsWhenCurrentScreenshotObservationNotIncluded() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "继续"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-current-ocr-page-changed",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr-page-changed",
                    input = "看当前屏幕，帮我点击继续",
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
                        "ocrText" to "继续",
                        "ocrBlocksJson" to """[{"text":"继续","bounds":{"left":12,"top":22,"right":112,"bottom":72},"lines":[]}]""",
                        "screenObservationIncluded" to "false",
                        "screenObservationFailureKind" to "page_changed",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("ocrBlocks(count=1)=[block0{text=继续,bounds=[12,22,112,72]}]"))
        assertFalse(prompt.contains("继续{target=ocr:block:0,modeTags=ocrFallback"))
        assertFalse(prompt.contains("targetShortlist(ocrFallback=ocr:block:0)"))
    }

    @Test
    fun modelReplannerRejectsUiTapFromOcrTextOnlyEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "继续"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-current-ocr-text-only",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr-text-only",
                    input = "看当前屏幕，帮我点击继续",
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
                        "ocrText" to "继续",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertTrue(runtime.lastInput.orEmpty().contains("ocrText=继续"))
    }

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
    fun modelReplannerKeepsLateOcrFallbackFromFusedObservation() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "ocr:block:1"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "ocr-fused-late-target",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr-fused-late-target",
                    input = "看当前屏幕，帮我点击继续",
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
                        "screenObservationJson" to fusedRepeatedOcrObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals("ocr:block:1", replan?.request?.arguments?.get("target"))
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("ocr:block:1{ocr/ocr_block,text=继续"))
        assertTrue(prompt.contains("ocrFallback=ocr:block:0:line:0:element:0|ocr:block:1"))
    }

    @Test
    fun modelReplannerUsesOcrElementIdsForRepeatedOcrFallbackTargets() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "ocr:block:1"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "ocr-repeated-fallback-targets",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr-repeated-fallback-targets",
                    input = "看当前屏幕，点击下面的继续",
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
                        "ocrText" to "继续\n继续",
                        "ocrBlocksJson" to repeatedContinueOcrBlocksJson(),
                        "screenObservationIncluded" to "true",
                        "screenObservationJson" to emptyObservationJson("screen-repeated-ocr-text"),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("ocr:block:1", replan?.request?.arguments?.get("target"))
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("继续{target=ocr:block:0,modeTags=ocrFallback"))
        assertTrue(prompt.contains("继续{target=ocr:block:1,modeTags=ocrFallback"))
        assertTrue(prompt.contains("targetShortlist(ocrFallback=ocr:block:0|ocr:block:1)"))
        assertFalse(prompt.contains("targetShortlist(ocrFallback=继续)"))
    }

    @Test
    fun modelReplannerRejectsAmbiguousRepeatedOcrTextTarget() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "继续"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "ocr-repeated-text-target",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr-repeated-text-target",
                    input = "看当前屏幕，点击下面的继续",
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
                        "ocrText" to "继续\n继续",
                        "ocrBlocksJson" to repeatedContinueOcrBlocksJson(),
                        "screenObservationIncluded" to "true",
                        "screenObservationJson" to emptyObservationJson("screen-repeated-ocr-text"),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerExposesStandaloneOcrElementTargetsForMultiTokenBlock() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "ocr:block:0:line:0:element:1"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "ocr-multitoken-targets",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr-multitoken-targets",
                    input = "看当前屏幕，点击继续",
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
                        "ocrText" to "取消 继续",
                        "ocrBlocksJson" to multiTokenOcrBlocksJson(),
                        "screenObservationIncluded" to "true",
                        "screenObservationJson" to emptyObservationJson("screen-multitoken-ocr-targets"),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals("ocr:block:0:line:0:element:1", replan?.request?.arguments?.get("target"))
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(
            prompt.contains(
                "继续{target=ocr:block:0:line:0:element:1,modeTags=ocrFallback,source=ocr,role=ocr_element,bounds=[130,20,230,70]}",
            ),
        )
        assertTrue(prompt.contains("targetShortlist(ocrFallback=ocr:block:0:line:0:element:0|ocr:block:0:line:0:element:1"))
        assertTrue(prompt.indexOf("target=ocr:block:0:line:0:element:1") < prompt.indexOf("target=ocr:block:0,modeTags=ocrFallback"))
    }

    @Test
    fun modelReplannerRejectsNestedOcrTextTargetWhenBlockAndElementBothMatch() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "继续"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "ocr-multitoken-text-target",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-ocr-multitoken-text-target",
                    input = "看当前屏幕，点击继续",
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
                        "ocrText" to "取消 继续",
                        "ocrBlocksJson" to multiTokenOcrBlocksJson(),
                        "screenObservationIncluded" to "true",
                        "screenObservationJson" to emptyObservationJson("screen-multitoken-ocr-text-target"),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerExposesNestedOcrElementTargetsFromScreenObservationJsonBeforeBlockTargets() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "ocr:block:0:line:0:element:1"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-fused-nested-ocr-targets",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-fused-nested-ocr-targets",
                    input = "看当前屏幕，点击继续",
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
                        "screenObservationJson" to nestedOcrScreenObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals("ocr:block:0:line:0:element:1", replan?.request?.arguments?.get("target"))
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(
            prompt.contains(
                "targetShortlist(ocrFallback=ocr:block:0:line:0:element:0|ocr:block:0:line:0:element:1|ocr:block:0:line:0|ocr:block:0)",
            ),
        )
        assertTrue(prompt.indexOf("target=ocr:block:0:line:0:element:1") < prompt.indexOf("target=ocr:block:0,modeTags=ocrFallback"))
    }

    @Test
    fun modelReplannerDoesNotExposeBoundlessOcrObservationTargets() {
        val runtime = RecordingModelActionRuntime(toolName = MobileActionFunctions.UI_WAIT)
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-boundless-ocr-targets",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
        )

        replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-boundless-ocr-targets",
                    input = "看当前屏幕，点击继续",
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
                        "screenObservationJson" to boundlessOcrScreenObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        val prompt = runtime.lastInput.orEmpty()
        assertFalse(prompt.contains("targetShortlist(ocrFallback=ocr:block:0)"))
        assertFalse(prompt.contains("继续{target=ocr:block:0,modeTags=ocrFallback"))
    }

    @Test
    fun modelReplannerPrioritizesActionableTargetsAfterStaticText() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "late-search-input"),
        )
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    specFor(
                        toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
                        privateOutputKeys = setOf("ocrText", "screenObservationJson"),
                        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
                    ),
                    specFor(toolName = MobileActionFunctions.UI_TAP),
                )
            },
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            toolRegistry = registry,
        )
        val previousRequest = ToolRequest(
            id = "ocr-actionable-late",
            toolName = MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR,
            arguments = mapOf("captureMode" to "current_screen"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-actionable-late",
                    input = "看当前屏幕，点击搜索输入框",
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
                        "ocrText" to "搜索输入框 搜索",
                        "screenObservationJson" to delayedActionableObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("late-search-input{accessibility/input,text=搜索输入框"))
        assertTrue(prompt.contains("late-submit{accessibility/button,text=搜索"))
        assertTrue(
            prompt.contains(
                "targets=[搜索输入框{target=late-search-input,modeTags=type+tap,source=accessibility,role=input,bounds=[24,520,820,588],confidence=1.00}",
            ),
        )
        assertTrue(
            prompt.contains(
                "搜索{target=late-submit,modeTags=tap,source=accessibility,role=button,bounds=[860,520,1040,588],confidence=1.00}",
            ),
        )
        assertTrue(
            prompt.contains(
                "搜索{target=ocr:submit,modeTags=ocrFallback,source=ocr,role=ocr_block,bounds=[860,520,1040,588],confidence=0.68}",
            ),
        )
        assertTrue(prompt.contains("targetShortlist(type=late-search-input,tap=late-search-input|late-submit,ocrFallback=ocr:submit)"))
    }

    @Test
    fun modelReplannerPromptIncludesActionObservationDiffEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "search-input"),
        )
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    specFor(
                        toolName = MobileActionFunctions.UI_TAP,
                        privateOutputKeys = setOf(
                            "beforeScreenObservationJson",
                            "afterScreenObservationJson",
                            "screenObservationDiffSummary",
                        ),
                        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
                    ),
                    specFor(toolName = MobileActionFunctions.UI_SUBMIT_SEARCH),
                )
            },
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            toolRegistry = registry,
        )
        val previousRequest = ToolRequest(
            id = "tap-1",
            toolName = MobileActionFunctions.UI_TAP,
            arguments = mapOf("target" to "搜索入口"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-action-diff",
                    input = "打开搜索并提交搜索",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已点击目标：搜索入口",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "beforeScreenObservationJson" to searchEntryObservationJson(),
                        "afterScreenObservationJson" to searchInputObservationJson(),
                        "screenObservationDiffSummary" to
                            "changed=true;package=com.example.app->com.example.app;nodes=2->3;" +
                            "actionable=1->2;addedText=搜索输入框|搜索;removedText=搜索入口;" +
                            "addedActionable=editable:搜索输入框|clickable:搜索;removedActionable=clickable:搜索入口",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("beforeScreenObservationJson(id=screen-search-entry"))
        assertTrue(prompt.contains("afterScreenObservationJson(id=screen-search-input"))
        assertTrue(prompt.contains("screenObservationDiffSummary=changed=true"))
        assertTrue(prompt.contains("addedText=搜索输入框|搜索"))
        assertTrue(prompt.contains("addedActionable=editable:搜索输入框|clickable:搜索"))
        assertTrue(
            prompt.contains(
                "搜索输入框{target=search-input,modeTags=type+tap,source=accessibility,role=input,bounds=[20,32,820,96],confidence=1.00}",
            ),
        )
        assertTrue(
            prompt.contains(
                "搜索{target=search-submit,modeTags=tap,source=accessibility,role=button,bounds=[900,32,1040,96],confidence=1.00}",
            ),
        )
        assertTrue(prompt.contains("targetShortlist(type=search-input,tap=search-submit|search-input|cancel)"))
        assertTrue(
            prompt.contains(
                "Observation private data keys omitted: afterScreenObservationJson, beforeScreenObservationJson, screenObservationDiffSummary",
            ),
        )
        assertFalse(prompt.contains("\"elements\""))
    }

    @Test
    fun modelReplannerRejectsTargetMissingFromCurrentObservationEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "missing-checkout-button"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-current",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-hallucinated-target",
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
    fun modelReplannerAllowsExactTargetFromDiffEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "搜索输入框"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "tap-opened-search",
            toolName = MobileActionFunctions.UI_TAP,
            arguments = mapOf("target" to "搜索入口"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-diff-exact-target",
                    input = "点击搜索输入框",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "搜索入口打开后出现输入框。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "screenObservationDiffSummary" to
                            "changed=true;addedText=none;addedActionable=editable:搜索输入框",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals("搜索输入框", replan?.request?.arguments?.get("target"))
    }

    @Test
    fun modelReplannerRejectsSubstringTargetFromDiffEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "搜索"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "tap-opened-search-input",
            toolName = MobileActionFunctions.UI_TAP,
            arguments = mapOf("target" to "搜索入口"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-diff-substring-target",
                    input = "点击搜索",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "搜索入口打开后出现搜索输入框。",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "screenObservationDiffSummary" to
                            "changed=true;addedText=搜索输入框;addedActionable=editable:搜索输入框",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerNormalizesTargetCandidateMetadataBeforeEvidenceGuards() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf(
                "target" to
                    "继续{target=continue-button,modeTags=tap,source=accessibility," +
                    "role=button,bounds=[10,20,110,70],confidence=1.00}",
            ),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-current-metadata-target",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-metadata-target",
                    input = "根据当前屏幕点击继续",
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
                        "screenObservationJson" to screenObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals("continue-button", replan?.request?.arguments?.get("target"))
        assertEquals("continue-button", replan?.draft?.parameters?.get("target"))
    }

    @Test
    fun modelReplannerRejectsTargetlessTapDraft() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = emptyMap(),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-current-targetless-tap",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-targetless-tap",
                    input = "根据当前屏幕点击",
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
                        "ocrBlocksJson" to
                            """[{"text":"搜索商品","bounds":{"left":20,"top":520,"right":720,"bottom":588}}]""",
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
                        "ocrBlocksJson" to
                            """[{"text":"搜索","bounds":{"left":820,"top":520,"right":980,"bottom":588}}]""",
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
    fun modelReplannerAllowsTargetFromObservationDiffEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "搜索输入框"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "tap-search-entry",
            toolName = MobileActionFunctions.UI_TAP,
            arguments = mapOf("target" to "搜索入口"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-diff-target",
                    input = "点击搜索入口后继续",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Failed,
                    summary = "未找到可点击目标：搜索入口",
                    retryable = true,
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "failureKind" to "node_not_found",
                        "afterScreenObservationJson" to emptyObservationJson("after-diff-target"),
                        "screenObservationDiffSummary" to
                            "changed=true;addedText=搜索输入框|搜索;" +
                            "addedActionable=editable:搜索输入框|clickable:搜索",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals("搜索输入框", replan?.request?.arguments?.get("target"))
    }

    @Test
    fun modelReplannerRejectsUntargetedScrollWithoutScrollableEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_SCROLL,
            parameters = mapOf("direction" to "down"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-no-scroll",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-no-scroll-evidence",
                    input = "继续浏览当前页面",
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
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("if no scroll candidate or scrollable evidence exists, stop"))
    }

    @Test
    fun modelReplannerAllowsUntargetedScrollWithScrollableEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_SCROLL,
            parameters = mapOf("direction" to "down"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "observe-scrollable",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = emptyMap(),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-scrollable-evidence",
                    input = "继续浏览当前页面",
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
                        "screenObservationJson" to scrollableObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_SCROLL, replan?.request?.toolName)
        assertEquals("down", replan?.request?.arguments?.get("direction"))
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
                        "ocrBlocksJson" to """[{"text":"确定","bounds":{"left":780,"top":520,"right":980,"bottom":588}}]""",
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
                        "ocrBlocksJson" to """[{"text":"搜索","bounds":{"left":780,"top":520,"right":980,"bottom":588}}]""",
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
                        "ocrBlocksJson" to
                            """[{"text":"搜索商品","bounds":{"left":20,"top":520,"right":720,"bottom":588}},""" +
                            """{"text":"确定","bounds":{"left":780,"top":520,"right":980,"bottom":588}}]""",
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
    fun modelReplannerUsesRecoverableFailedObservationEvidence() {
        val runtime = RecordingModelActionRuntime(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    specFor(
                        toolName = MobileActionFunctions.UI_TAP,
                        privateOutputKeys = setOf(
                            "beforeScreenObservationJson",
                            "afterScreenObservationJson",
                            "screenObservationDiffSummary",
                        ),
                        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
                    ),
                    specFor(toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN),
                )
            },
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            toolRegistry = registry,
        )
        val previousRequest = ToolRequest(
            id = "tap-failed",
            toolName = MobileActionFunctions.UI_TAP,
            arguments = mapOf("target" to "搜索入口"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-failed-action",
                    input = "点击搜索入口，如果失败就根据当前屏幕继续",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Failed,
                    summary = "未找到可点击目标：搜索入口",
                    retryable = true,
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "actionType" to "tap",
                        "target" to "搜索入口",
                        "status" to "failed",
                        "retryable" to "true",
                        "failureKind" to "node_not_found",
                        "verificationSummary" to "动作失败后屏幕出现搜索输入框，owner test@example.com",
                        "beforeScreenObservationJson" to searchEntryObservationJson(),
                        "afterScreenObservationJson" to searchInputObservationJson(),
                        "screenObservationDiffSummary" to
                            "changed=true;addedText=搜索输入框|搜索;" +
                            "addedActionable=editable:搜索输入框|clickable:搜索",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.OBSERVE_CURRENT_SCREEN, replan?.request?.toolName)
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("Observation status: Failed"))
        assertTrue(prompt.contains("Observation diagnostics:"))
        assertTrue(prompt.contains("resultRetryable=true"))
        assertTrue(prompt.contains("actionType=tap"))
        assertTrue(prompt.contains("target=搜索入口"))
        assertTrue(prompt.contains("do not repeat the same prior target unless new evidence supports it"))
        assertTrue(prompt.contains("Prior request details: ui_tap{target=搜索入口}"))
        assertTrue(prompt.contains("failureKind=node_not_found"))
        assertTrue(prompt.contains("verificationSummary=动作失败后屏幕出现搜索输入框，owner [email]"))
        assertFalse(prompt.contains("test@example.com"))
        assertTrue(prompt.contains("screenObservationDiffSummary=changed=true"))
        assertTrue(prompt.contains("afterScreenObservationJson(id=screen-search-input"))
        assertTrue(
            prompt.contains(
                "搜索输入框{target=search-input,modeTags=type+tap,source=accessibility,role=input,bounds=[20,32,820,96],confidence=1.00}",
            ),
        )
        assertTrue(prompt.contains("targetShortlist(type=search-input,tap=search-input|search-submit|cancel)"))
    }

    @Test
    fun modelReplannerSkipsPermissionMissingFailure() {
        val runtime = RecordingModelActionRuntime(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "tap-permission-missing",
            toolName = MobileActionFunctions.UI_TAP,
            arguments = mapOf("target" to "搜索入口"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-permission-missing",
                    input = "点击搜索入口",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Failed,
                    summary = "未开启无障碍服务",
                    retryable = true,
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "failureKind" to "permission_missing",
                        "afterScreenObservationJson" to searchInputObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerPriorRequestDetailsOmitTypedText() {
        val runtime = RecordingModelActionRuntime(MobileActionFunctions.UI_SUBMIT_SEARCH)
        val registry = ToolRegistry(
            ToolProvider {
                listOf(
                    specFor(
                        toolName = MobileActionFunctions.UI_TYPE_TEXT,
                        privateOutputKeys = setOf("afterScreenObservationJson"),
                        resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
                    ),
                    specFor(toolName = MobileActionFunctions.UI_SUBMIT_SEARCH),
                )
            },
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            toolRegistry = registry,
        )
        val previousRequest = ToolRequest(
            id = "type-sensitive",
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            arguments = mapOf(
                "target" to "search-input",
                "text" to "alice@example.com",
                "timeoutMillis" to "1500",
            ),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-type-sensitive",
                    input = "输入邮箱后提交搜索",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已向输入框写入 17 个字符",
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "afterScreenObservationJson" to searchInputObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(
            prompt.contains(
                "Prior request details: ui_type_text{target=search-input,textChars=17,timeoutMillis=1500}",
            ),
        )
        assertFalse(prompt.contains("alice@example.com"))
        assertFalse(prompt.contains("alice[at]example"))
    }

    @Test
    fun modelReplannerRejectsRepeatedFailedTargetWithoutCurrentEvidence() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "搜索入口"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "tap-missing-target",
            toolName = MobileActionFunctions.UI_TAP,
            arguments = mapOf("target" to "搜索入口"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-repeat-target",
                    input = "点击搜索入口",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Failed,
                    summary = "未找到可点击目标：搜索入口",
                    retryable = true,
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "failureKind" to "node_not_found",
                        "screenObservationDiffSummary" to "changed=false;addedText=none;addedActionable=none",
                        "afterScreenObservationJson" to accessibilityOnlyObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        assertNotNull(runtime.lastInput)
    }

    @Test
    fun modelReplannerAllowsRepeatedFailedTargetWhenCurrentEvidenceSupportsIt() {
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "search-entry"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
        )
        val previousRequest = ToolRequest(
            id = "tap-visible-target",
            toolName = MobileActionFunctions.UI_TAP,
            arguments = mapOf("target" to "search-entry"),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-supported-repeat",
                    input = "点击搜索入口",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = previousRequest,
                observedResult = ToolResult(
                    requestId = previousRequest.id,
                    status = ToolStatus.Failed,
                    summary = "点击失败，但当前观测仍显示目标可点击",
                    retryable = true,
                    data = mapOf(
                        "toolName" to previousRequest.toolName,
                        "privacy" to "LocalOnly",
                        "failureKind" to "gesture_failed",
                        "screenObservationDiffSummary" to "changed=false",
                        "afterScreenObservationJson" to searchEntryObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals("search-entry", replan?.request?.arguments?.get("target"))
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
                        "ocrBlocksJson" to
                            """[{"text":"确认支付","bounds":{"left":10,"top":20,"right":150,"bottom":70}}]""",
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
                        "ocrBlocksJson" to
                            """[{"text":"支付说明","bounds":{"left":10,"top":20,"right":150,"bottom":70}}]""",
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
                        "ocrBlocksJson" to
                            """[{"text":"删除","bounds":{"left":10,"top":20,"right":150,"bottom":70}}]""",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
    }

    @Test
    fun sequentialReplannerSkipsRegistryLocalEvidenceToolWhenTailRemains() {
        val replanner = replannerFor(
            specFor(
                toolName = LOCAL_EVIDENCE_TOOL,
                resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            ),
        )

        val replan = replanner.planNext(
            contextFor(
                input = "first search Kotlin then inspect local evidence then open Wi-Fi settings",
            ),
        )

        assertNull(replan)
    }

    @Test
    fun sequentialReplannerSkipsPrivateOutputToolWhenTailRemains() {
        val replanner = replannerFor(
            specFor(
                toolName = PRIVATE_OUTPUT_TOOL,
                privateOutputKeys = setOf("rawText"),
            ),
        )

        val replan = replanner.planNext(
            contextFor(
                input = "first search Kotlin then read private evidence then open Wi-Fi settings",
            ),
        )

        assertNull(replan)
    }

    @Test
    fun sequentialReplannerAllowsRegistryLocalEvidenceToolAsFinalSegment() {
        val replanner = replannerFor(
            specFor(
                toolName = LOCAL_EVIDENCE_TOOL,
                resultContinuationPolicy = ToolResultContinuationPolicy.LocalEvidence,
            ),
        )

        val replan = replanner.planNext(
            contextFor(
                input = "first search Kotlin then inspect local evidence",
            ),
        )

        assertNotNull(replan)
        requireNotNull(replan)
        assertEquals(LOCAL_EVIDENCE_TOOL, replan.request.toolName)
    }

    private fun replannerFor(spec: ToolSpec): SequentialActionObservationReplanner =
        SequentialActionObservationReplanner(
            actionPlanningRuntime = DraftActionRuntime(spec.name),
            toolRegistry = ToolRegistry(ToolProvider { listOf(spec) }),
        )

    private fun contextFor(input: String): AgentObservationReplanContext {
        val previousRequest = ToolRequest(
            id = "previous-request",
            toolName = MobileActionFunctions.WEB_SEARCH,
            arguments = mapOf("query" to "Kotlin"),
        )
        return AgentObservationReplanContext(
            run = AgentRun(
                id = "run-1",
                input = input,
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
            previousRequest = previousRequest,
            observedResult = ToolResult(
                requestId = previousRequest.id,
                status = ToolStatus.Succeeded,
                summary = "searched",
                data = mapOf("toolName" to previousRequest.toolName),
            ),
            priorRequests = listOf(previousRequest),
        )
    }

    private fun localOnlyAllowedToolSpecs(): List<ToolSpec> =
        listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_WAIT,
        ).map { toolName -> specFor(toolName = toolName) }

    private fun specFor(
        toolName: String,
        privateOutputKeys: Set<String> = emptySet(),
        resultContinuationPolicy: ToolResultContinuationPolicy = ToolResultContinuationPolicy.None,
        capability: ToolCapability = when (toolName) {
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_PRESS_BACK,
            MobileActionFunctions.UI_WAIT -> ToolCapability.DeviceControl

            MobileActionFunctions.WEB_SEARCH -> ToolCapability.WebSearch
            else -> ToolCapability.DeviceContext
        },
    ): ToolSpec =
        ToolSpec(
            name = toolName,
            title = "Test tool",
            description = "Test tool",
            inputSchemaJson = EMPTY_OBJECT_SCHEMA_JSON,
            capability = capability,
            riskLevel = RiskLevel.LowReadOnly,
            confirmationPolicy = ConfirmationPolicy.Required,
            privateOutputKeys = privateOutputKeys,
            resultContinuationPolicy = resultContinuationPolicy,
        )

    private class DraftActionRuntime(
        private val toolName: String,
    ) : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = toolName,
                        title = "Test tool",
                        summary = "Run test tool",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test",
            )
    }

    private class RecordingModelActionRuntime(
        private val toolName: String,
        private val parameters: Map<String, String> = mapOf("target" to "继续"),
    ) : ActionPlanningRuntime {
        var lastInput: String? = null
        var lastActionModelPath: String? = null

        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            lastInput = input
            lastActionModelPath = actionModelPath
            return ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = toolName,
                        title = "Tap",
                        summary = "Tap target",
                        parameters = parameters,
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = true,
                fallbackReason = null,
            )
        }
    }

    private fun screenObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-1",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility", "ocr"],
          "elementCount": 14,
          "sourceCounts": {"accessibility": 13, "ocr": 1},
          "truncated": false,
          "elements": [
            {
              "id": "continue-button",
              "source": "accessibility",
              "bounds": {"left": 10, "top": 20, "right": 110, "bottom": 70},
              "text": "继续",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            ${accessibilityFillerElementsJson()},
            {
              "id": "ocr:block:0",
              "source": "ocr",
              "bounds": {"left": 10, "top": 20, "right": 110, "bottom": 70},
              "text": "继续",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.72,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun String.shortlistDebugText(): String {
        val index = indexOf("screenObservationJson(")
        return if (index < 0) {
            take(500)
        } else {
            substring(index, minOf(length, index + 640))
        }
    }

    private fun accessibilityFillerElementsJson(): String =
        (1..12).joinToString(separator = ",\n") { index ->
            """
            {
              "id": "label-$index",
              "source": "accessibility",
              "bounds": {"left": 0, "top": ${100 + index}, "right": 10, "bottom": ${120 + index}},
              "text": "列表项 $index",
              "role": "text",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
            """.trimIndent()
        }

    private fun ocrLabeledBlankAccessibilityObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-ocr-labeled-icon",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility", "ocr"],
          "elementCount": 14,
          "sourceCounts": {"accessibility": 13, "ocr": 1},
          "truncated": false,
          "elements": [
            ${actionableFillerElementsJson(count = 12)},
            {
              "id": "icon-search-entry",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 32, "right": 420, "bottom": 96},
              "text": "",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0",
              "source": "ocr",
              "bounds": {"left": 48, "top": 44, "right": 220, "bottom": 84},
              "text": "搜索入口",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.72,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun sparseOcrLabeledBlankAccessibilityObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-sparse-ocr-labeled-icon",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility", "ocr"],
          "elementCount": 14,
          "sourceCounts": {"accessibility": 13, "ocr": 1},
          "truncated": false,
          "elements": [
            ${actionableFillerElementsJson(count = 12)},
            {
              "id": "icon-search-entry",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 32, "right": 420, "bottom": 96},
              "text": "",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0",
              "source": "ocr",
              "bounds": {"left": 398, "top": 44, "right": 760, "bottom": 84},
              "text": "搜索入口",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.72,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun resolverRankedSearchEntryObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-search-noise",
          "capturedAtMillis": 1,
          "packageName": "com.taobao.taobao",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": 4,
          "sourceCounts": {"accessibility": 4},
          "truncated": false,
          "elements": [
            {
              "id": "camera-search",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 32, "right": 220, "bottom": 96},
              "text": "拍照搜索",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "home-feed",
              "source": "accessibility",
              "bounds": {"left": 0, "top": 220, "right": 1080, "bottom": 1800},
              "text": "综合 销量 筛选 商品列表 旗舰店 ￥29 已售1000 评价",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": true, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "promo-search",
              "source": "accessibility",
              "bounds": {"left": 240, "top": 32, "right": 420, "bottom": 96},
              "text": "搜索好物 推荐 商品图片",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "real-search-entry",
              "source": "accessibility",
              "bounds": {"left": 36, "top": 84, "right": 1044, "bottom": 168},
              "text": "搜索商品",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun intentRankedFilterObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-filter-intent",
          "capturedAtMillis": 1,
          "packageName": "com.taobao.taobao",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": 3,
          "sourceCounts": {"accessibility": 3},
          "truncated": false,
          "elements": [
            {
              "id": "search-entry",
              "source": "accessibility",
              "bounds": {"left": 36, "top": 84, "right": 760, "bottom": 168},
              "text": "搜索商品",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "result-list",
              "source": "accessibility",
              "bounds": {"left": 0, "top": 220, "right": 1080, "bottom": 1800},
              "text": "综合 销量 筛选 商品列表 旗舰店 ￥29 已售1000 评价",
              "role": "scrollable",
              "clickability": {"clickable": true, "editable": false, "scrollable": true, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "filter-button",
              "source": "accessibility",
              "bounds": {"left": 860, "top": 84, "right": 1044, "bottom": 168},
              "text": "筛选",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun delayedActionableObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-actionable-late",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility", "ocr"],
          "elementCount": 15,
          "sourceCounts": {"accessibility": 14, "ocr": 1},
          "truncated": false,
          "elements": [
            ${accessibilityFillerElementsJson()},
            {
              "id": "late-search-input",
              "source": "accessibility",
              "bounds": {"left": 24, "top": 520, "right": 820, "bottom": 588},
              "text": "搜索输入框",
              "role": "input",
              "clickability": {"clickable": true, "editable": true, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "late-submit",
              "source": "accessibility",
              "bounds": {"left": 860, "top": 520, "right": 1040, "bottom": 588},
              "text": "搜索",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:submit",
              "source": "ocr",
              "bounds": {"left": 860, "top": 520, "right": 1040, "bottom": 588},
              "text": "搜索",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.68,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun searchEntryObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-search-entry",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": 2,
          "sourceCounts": {"accessibility": 2},
          "truncated": false,
          "elements": [
            {
              "id": "search-entry",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 32, "right": 420, "bottom": 96},
              "text": "搜索入口",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "home-title",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 120, "right": 220, "bottom": 160},
              "text": "首页",
              "role": "text",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun searchInputObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-search-input",
          "capturedAtMillis": 2,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": 3,
          "sourceCounts": {"accessibility": 3},
          "truncated": false,
          "elements": [
            {
              "id": "search-input",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 32, "right": 820, "bottom": 96},
              "text": "搜索输入框",
              "role": "input",
              "clickability": {"clickable": true, "editable": true, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "search-submit",
              "source": "accessibility",
              "bounds": {"left": 900, "top": 32, "right": 1040, "bottom": 96},
              "text": "搜索",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "cancel",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 120, "right": 120, "bottom": 160},
              "text": "取消",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun accessibilityOnlyObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-accessibility-only",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": 1,
          "sourceCounts": {"accessibility": 1},
          "truncated": false,
          "elements": [
            {
              "id": "accessibility-button",
              "source": "accessibility",
              "bounds": {"left": 1, "top": 2, "right": 101, "bottom": 52},
              "text": "继续",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun repeatedContinueOcrBlocksJson(): String =
        """
        [
          {"text":"继续","bounds":{"left":12,"top":22,"right":112,"bottom":72},"lines":[]},
          {"text":"继续","bounds":{"left":12,"top":220,"right":112,"bottom":270},"lines":[]}
        ]
        """.trimIndent()

    private fun nestedOcrScreenObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-nested-ocr",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["ocr"],
          "elementCount": 4,
          "sourceCounts": {"ocr": 4},
          "truncated": false,
          "elements": [
            {
              "id": "ocr:block:0",
              "source": "ocr",
              "bounds": {"left": 20, "top": 20, "right": 230, "bottom": 70},
              "text": "底部操作 取消 继续",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.72,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0:line:0",
              "source": "ocr",
              "bounds": {"left": 20, "top": 20, "right": 230, "bottom": 70},
              "text": "取消 继续",
              "role": "ocr_line",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.72,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0:line:0:element:0",
              "source": "ocr",
              "bounds": {"left": 20, "top": 20, "right": 120, "bottom": 70},
              "text": "取消",
              "role": "ocr_element",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.72,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0:line:0:element:1",
              "source": "ocr",
              "bounds": {"left": 130, "top": 20, "right": 230, "bottom": 70},
              "text": "继续",
              "role": "ocr_element",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.72,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun boundlessOcrScreenObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-boundless-ocr",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["ocr"],
          "elementCount": 1,
          "sourceCounts": {"ocr": 1},
          "truncated": false,
          "elements": [
            {
              "id": "ocr:block:0",
              "source": "ocr",
              "text": "继续",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.72,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun multiTokenOcrBlocksJson(): String =
        """
        [
          {
            "text":"取消 继续",
            "bounds":{"left":20,"top":20,"right":230,"bottom":70},
            "lines":[
              {
                "text":"取消 继续",
                "bounds":{"left":20,"top":20,"right":230,"bottom":70},
                "elements":[
                  {"text":"取消","bounds":{"left":20,"top":20,"right":110,"bottom":70}},
                  {"text":"继续","bounds":{"left":130,"top":20,"right":230,"bottom":70}}
                ]
              }
            ]
          }
        ]
        """.trimIndent()

    private fun fusedRepeatedOcrObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-fused-repeated-ocr",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility", "ocr"],
          "elementCount": 11,
          "sourceCounts": {"accessibility": 6, "ocr": 5},
          "truncated": false,
          "elements": [
            ${actionableFillerElementsJson()},
            {
              "id": "ocr:block:0",
              "source": "ocr",
              "bounds": {"left": 20, "top": 160, "right": 220, "bottom": 228},
              "text": "噪声",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.80,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0:line:0",
              "source": "ocr",
              "bounds": {"left": 20, "top": 160, "right": 220, "bottom": 228},
              "text": "噪声",
              "role": "ocr_line",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.78,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0:line:0:element:0",
              "source": "ocr",
              "bounds": {"left": 20, "top": 160, "right": 110, "bottom": 228},
              "text": "噪声",
              "role": "ocr_element",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.76,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:0:line:0:element:1",
              "source": "ocr",
              "bounds": {"left": 110, "top": 160, "right": 220, "bottom": 228},
              "text": "噪声",
              "role": "ocr_element",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.74,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "ocr:block:1",
              "source": "ocr",
              "bounds": {"left": 20, "top": 260, "right": 220, "bottom": 328},
              "text": "继续",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.84,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun actionableFillerElementsJson(count: Int = 6): String =
        (0 until count).joinToString(separator = ",\n") { index ->
            val top = 20 + (index * 20)
            """
            {
              "id": "action-$index",
              "source": "accessibility",
              "bounds": {"left": 0, "top": $top, "right": 10, "bottom": ${top + 16}},
              "text": "操作 $index",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
            """.trimIndent()
        }

    private fun scrollableObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-scrollable-list",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": 2,
          "sourceCounts": {"accessibility": 2},
          "truncated": false,
          "elements": [
            {
              "id": "results-list",
              "source": "accessibility",
              "bounds": {"left": 0, "top": 180, "right": 1080, "bottom": 1880},
              "text": "搜索结果",
              "role": "list",
              "clickability": {"clickable": false, "editable": false, "scrollable": true, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "result-title",
              "source": "accessibility",
              "bounds": {"left": 24, "top": 220, "right": 620, "bottom": 280},
              "text": "结果标题",
              "role": "text",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun staticPaymentCopyObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-static-payment-copy",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": 2,
          "sourceCounts": {"accessibility": 2},
          "truncated": false,
          "elements": [
            {
              "id": "payment-description",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 32, "right": 620, "bottom": 96},
              "text": "支付说明",
              "role": "text",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "continue-button",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 120, "right": 220, "bottom": 180},
              "text": "继续",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private fun emptyObservationJson(observationId: String): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "$observationId",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility"],
          "elementCount": 0,
          "sourceCounts": {},
          "truncated": false,
          "elements": []
        }
        """.trimIndent()

    private fun ocrOnlyObservationJson(
        observationId: String,
        texts: List<String>,
    ): String {
        val elements = texts.mapIndexed { index, text ->
            val top = 520 + (index * 80)
            val bottom = top + 68
            """
            {
              "id": "ocr:submit:$index",
              "source": "ocr",
              "bounds": {"left": 20, "top": $top, "right": 220, "bottom": $bottom},
              "text": "$text",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.80,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
            """.trimIndent()
        }
        return """
        {
          "schemaVersion": 1,
          "observationId": "$observationId",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["ocr"],
          "elementCount": ${texts.size},
          "sourceCounts": {"ocr": ${texts.size}},
          "truncated": false,
          "elements": [
            ${elements.joinToString(separator = ",\n")}
          ]
        }
        """.trimIndent()
    }

    private fun staticPaymentOcrCopyObservationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-static-payment-ocr-copy",
          "capturedAtMillis": 1,
          "packageName": "com.example.app",
          "privacyLevel": "LocalOnly",
          "sources": ["accessibility", "ocr"],
          "elementCount": 2,
          "sourceCounts": {"accessibility": 1, "ocr": 1},
          "truncated": false,
          "elements": [
            {
              "id": "ocr:payment-description",
              "source": "ocr",
              "bounds": {"left": 20, "top": 32, "right": 620, "bottom": 96},
              "text": "支付说明",
              "role": "ocr_block",
              "clickability": {"clickable": false, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 0.82,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            },
            {
              "id": "continue-button",
              "source": "accessibility",
              "bounds": {"left": 20, "top": 120, "right": 220, "bottom": 180},
              "text": "继续",
              "role": "button",
              "clickability": {"clickable": true, "editable": false, "scrollable": false, "enabled": true},
              "confidence": 1.0,
              "sensitiveFlags": [],
              "privacyLevel": "LocalOnly"
            }
          ]
        }
        """.trimIndent()

    private companion object {
        private const val LOCAL_EVIDENCE_TOOL = "test_local_evidence_tool"
        private const val PRIVATE_OUTPUT_TOOL = "test_private_output_tool"
        private const val EMPTY_OBJECT_SCHEMA_JSON =
            """{"type":"object","properties":{},"additionalProperties":false}"""
    }
}
