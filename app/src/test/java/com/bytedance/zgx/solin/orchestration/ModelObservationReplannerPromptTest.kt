package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolProvider
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelObservationReplannerPromptTest : ObservationReplannerTestFixtures() {
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
                        "ocrBlocksJson" to ocrBlocksJson(
                            ocrBlock("继续", left = 10, top = 20, right = 110, bottom = 70, includeEmptyLines = true),
                        ),
                        "screenObservationJson" to screenObservationJson(),
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        val prompt = runtime.lastInput.orEmpty()
        assertTrue(prompt.contains("App search progress: stage=unknown outcome=unknown reason=unknown"))
        assertTrue(prompt.contains("LocalOnly observation evidence"))
        assertTrue(prompt, prompt.contains("output only these local device-control tools"))
        assertTrue(prompt.contains("Do not output web_search"))
        assertTrue(prompt.contains("copy only the candidate target=... value into the tool target"))
        assertTrue(prompt.contains("Use only target values from targetShortlist(...)"))
        assertTrue(prompt.contains("Output exactly one call"))
        assertTrue(prompt, prompt.contains("tap -> type -> submit"))
        assertTrue(prompt.contains("screenObservationJson(id=screen-1"))
        assertTrue(prompt.contains("sources=accessibility,ocr"))
        assertTrue(prompt.contains("sourceCounts=accessibility=13,ocr=1"))
        assertTrue(prompt.contains("continue-button{accessibility/button,text=继续"))
        assertTrue(prompt.contains("ocr:block:0{ocr/ocr_block,text=继续"))
        assertTrue(prompt.contains("bounds=[10,20,110,70]"))
        assertTrue(prompt.contains("target=continue-button"))
        assertTrue(prompt.contains("target=ocr:block:0"))
        assertTrue(prompt.contains("targetShortlist(tap=continue-button,ocrFallback=ocr:block:0)"))
        assertTrue(prompt.contains("targetEvidence(id=continue-button,text=继续,mode=tap,role=button"))
        assertTrue(prompt.contains("id=ocr:block:0,text=继续,mode=ocrFallback,role=ocr_block"))
        assertTrue(prompt.contains("LocalOnly observation evidence"))
        assertFalse(prompt.contains("ocrBlocks(count="))
        assertFalse(prompt.contains("\"elements\""))
        assertFalse(prompt.contains("\"lines\""))
    }

    @Test
    fun observationPromptCompressionPreservesExecutableTargetsWithinBudget() {
        val noisyTargets = (0 until 24).joinToString(separator = "; ") { index ->
            "干扰$index{target=noise-$index,modeTags=tap,source=accessibility,role=button,bounds=[1,2,3,4],confidence=0.30}"
        }
        val prompt = """
            Decide if the user's request needs exactly one more mobile tool after the latest observation; if unclear, output ordinary text with no call.
            When LocalOnly observation evidence is present, output only these local device-control tools: observe_current_screen,ui_tap,ui_type_text,ui_submit_search,ui_scroll,ui_wait,ui_press_back.
            Do not output web_search or any other non-listed tool from LocalOnly observation evidence.
            Use only target values from targetShortlist(...) for tools that accept target.

            User intent preview: 打开淘宝搜索海河牛奶
            Prior request details: open_app_by_name{target=淘宝} -> observe_current_screen
            Previous tool: observe_current_screen
            Observation status: Succeeded
            Observation diagnostics: resultRetryable=true; verificationSummary=${"长诊断".repeat(120)}
            LocalOnly observation evidence: screenObservationJson(id=screen-1,package=com.taobao.taobao,sources=accessibility,sourceCounts=accessibility=24,elements=24,truncated=false) targetShortlist(type=search-input,tap=search-entry|submit-search) targetEvidence(id=search-input,text=搜索输入框,mode=type+tap,role=input; id=submit-search,text=搜索,mode=tap,role=button) elements=[$noisyTargets] targets=[搜索输入框{target=search-input,modeTags=type+tap,source=accessibility,role=input,bounds=[20,32,820,96],confidence=1.00}; 搜索{target=submit-search,modeTags=tap,source=accessibility,role=button,bounds=[900,32,1040,96],confidence=1.00}; $noisyTargets]
        """.trimIndent()

        val compressed = prompt.compressForObservationModelContext(maxChars = 1_200)

        assertTrue(compressed.length <= 1_200)
        assertTrue(compressed, compressed.contains("Use only target values from targetShortlist(...)"))
        assertTrue(compressed.contains("targetShortlist(type=search-input,tap=search-entry|submit-search)"))
        assertTrue(compressed.contains("targetEvidence(id=search-input,text=搜索输入框,mode=type+tap,role=input"))
        assertTrue(compressed, compressed.contains("target=search-input"))
        assertTrue(compressed, compressed.contains("target=submit-search"))
        assertFalse(compressed.contains("noise-23"))
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
        assertTrue(prompt.contains("targetShortlist(tap=icon-search-entry"))
        assertTrue(prompt.contains("target=icon-search-entry"))
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
        assertTrue(prompt.contains("ocrFallback=ocr:block:0"))
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
        assertTrue(prompt.shortlistDebugText(), prompt.contains("targetShortlist(tap=filter-button"))
        assertFalse(prompt.contains("targetShortlist(tap=search-entry"))
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
        assertTrue(prompt.contains("target=ocr:block:1"))
        assertTrue(prompt.contains("ocrFallback=ocr:block:0:line:0:element:0|ocr:block:1"))
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
        assertTrue(prompt, prompt.contains("id=ocr:block:0:line:0:element:1,text=继续,mode=ocrFallback,role=ocr_element"))
        assertTrue(prompt.contains("targetShortlist(ocrFallback=ocr:block:0:line:0:element:0|ocr:block:0:line:0:element:1"))
        assertTrue(prompt.contains("ocr:block:0:line:0:element:1|ocr:block:0:line:0|ocr:block:0"))
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
        assertTrue(prompt.contains("target=ocr:block:0:line:0:element:1"))
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
        assertTrue(prompt.contains("targetEvidence(id=late-search-input,text=搜索输入框,mode=type+tap,role=input"))
        assertTrue(prompt.contains("id=late-submit,text=搜索,mode=tap,role=button"))
        assertTrue(prompt.contains("targetShortlist(type=late-search-input"))
        assertTrue(prompt.contains("target=late-search-input"))
        assertTrue(prompt.contains("late-submit"))
        assertTrue(prompt.contains("ocrFallback=ocr:submit"))
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
        assertTrue(prompt.contains("targetShortlist(type=search-input"))
        assertTrue(prompt.contains("targetShortlist(type=search-input,tap=search-submit|search-input|cancel)"))
        assertTrue(prompt.contains("LocalOnly observation evidence"))
        assertFalse(prompt.contains("\"elements\""))
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
        assertTrue(prompt.contains("Prior request details: ui_type_text{target=search-input"))
        assertTrue(prompt.contains("textChars=17"))
        assertFalse(prompt.contains("alice@example.com"))
        assertFalse(prompt.contains("alice[at]example"))
    }
}
