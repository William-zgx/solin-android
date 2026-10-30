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

class ModelObservationReplannerTargetGuardTest : ObservationReplannerTestFixtures() {
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
        assertTrue(runtime.firstInput.orEmpty().contains("ocrText=继续"))
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
        val prompt = runtime.firstInput.orEmpty()
        assertTrue(prompt.contains("stop if no scroll candidate/evidence exists"))
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
}
