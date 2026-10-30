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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AgentObservationReplannerTest : ObservationReplannerTestFixtures() {
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
        assertTrue(runtime.firstInput.orEmpty().contains("LocalOnly observation evidence"))
        assertTrue(runtime.firstInput.orEmpty().contains("Do not output web_search"))
    }

    @Test
    fun modelReplannerTreatsMissingOrMalformedObservationPrivacyAsLocalOnly() {
        val missingPrivacy = JSONObject(screenObservationJson()).apply {
            remove("privacyLevel")
        }.toString()

        listOf(missingPrivacy, "{malformed").forEachIndexed { index, observationJson ->
            val runtime = RecordingModelActionRuntime(
                toolName = MobileActionFunctions.WEB_SEARCH,
                parameters = mapOf("query" to "continue"),
            )
            val registry = ToolRegistry(
                ToolProvider {
                    listOf(specFor(toolName = MobileActionFunctions.WEB_SEARCH)) +
                        localOnlyAllowedToolSpecs()
                },
            )
            val replanner = ModelObservationReplanner(
                actionPlanningRuntime = runtime,
                actionModelPathProvider = { "/tmp/action-model.litertlm" },
                toolRegistry = registry,
            )
            val previousRequest = ToolRequest(
                id = "privacy-$index",
                toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            )

            val replan = replanner.planNext(
                AgentObservationReplanContext(
                    run = AgentRun(
                        id = "run-privacy-$index",
                        input = "continue",
                        state = AgentRunState.Observing,
                        createdAtMillis = 1L,
                        updatedAtMillis = 1L,
                    ),
                    previousRequest = previousRequest,
                    observedResult = ToolResult(
                        requestId = previousRequest.id,
                        status = ToolStatus.Succeeded,
                        summary = "observed",
                        data = mapOf(
                            "privacy" to "RemoteEligible",
                            "requiresLocalModel" to "false",
                            "screenObservationJson" to observationJson,
                        ),
                    ),
                    priorRequests = listOf(previousRequest),
                ),
            )

            assertNull(replan)
            assertTrue(runtime.firstInput.orEmpty().contains("Do not output web_search"))
        }
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
                        "ocrBlocksJson" to ocrBlocksJson(
                            ocrBlock("继续", left = 12, top = 22, right = 112, bottom = 72, includeEmptyLines = true),
                        ),
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
        assertTrue(prompt.contains("ocrBlocks(count=1) targetShortlist(ocrFallback=ocr:block:0)"))
        assertTrue(prompt.contains("targetEvidence(id=ocr:block:0,text=继续,mode=ocrFallback,role=ocr_block)"))
        assertTrue(prompt.contains("block0{text=继续,bounds=[12,22,112,72]}"))
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
                        "ocrBlocksJson" to ocrBlocksJson(
                            ocrBlock("继续", left = 12, top = 22, right = 112, bottom = 72, includeEmptyLines = true),
                        ),
                        "screenObservationIncluded" to "false",
                        "screenObservationFailureKind" to "page_changed",
                    ),
                ),
                priorRequests = listOf(previousRequest),
            ),
        )

        assertNull(replan)
        val prompt = runtime.firstInput.orEmpty()
        assertTrue(prompt.contains("ocrBlocks(count=1)=[block0{text=继续,bounds=[12,22,112,72]}]"))
        assertFalse(prompt.contains("继续{target=ocr:block:0,modeTags=ocrFallback"))
        assertFalse(prompt.contains("targetShortlist(ocrFallback=ocr:block:0)"))
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
        assertTrue(prompt.contains("Prior request details: ui_tap{target=搜索入口}"))
        assertTrue(prompt.contains("failureKind=node_not_found"))
        assertTrue(prompt.contains("verificationSummary=动作失败后屏幕出现搜索输入框，owner [email]"))
        assertFalse(prompt.contains("test@example.com"))
        assertTrue(prompt.contains("screenObservationDiffSummary=changed=true"))
        assertTrue(prompt.contains("afterScreenObservationJson(id=screen-search-input"))
        assertTrue(prompt.contains("targetShortlist(type=search-input"))
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

    @Test
    fun modelReplanInheritsExpectedForegroundPackageFromContinuationObserve() {
        // Regression (B): the post-launch continuation observe carries expectedPackageName so the
        // executor's foreground-package preflight can fail closed. That binding must be threaded onto
        // the model-planned UI action, or the fail-closed guarantee is a no-op on the actual tap.
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "继续"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            toolRegistry = ToolRegistry(ToolProvider { localOnlyAllowedToolSpecs() }),
        )
        val observeRequest = ToolRequest(
            id = "observe-after-open",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = mapOf(
                "maxTextChars" to "2000",
                "maxNodes" to "50",
                "expectedPackageName" to "com.xingin.xhs",
            ),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-continue",
                    input = "打开小红书并点击继续",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = observeRequest,
                observedResult = ToolResult(
                    requestId = observeRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已观察当前屏幕。",
                    data = mapOf(
                        "toolName" to observeRequest.toolName,
                        "privacy" to "LocalOnly",
                        "screenObservationJson" to screenObservationJson(),
                    ),
                ),
                priorRequests = listOf(observeRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(MobileActionFunctions.UI_TAP, replan?.request?.toolName)
        assertEquals(
            "replanned UI action must inherit the continuation's expected foreground package",
            "com.xingin.xhs",
            replan?.request?.arguments?.get("expectedPackageName"),
        )
    }

    @Test
    fun modelReplanDoesNotOverrideModelSuppliedExpectedForegroundPackage() {
        // The model's own expectedPackageName wins over the inherited one.
        val runtime = RecordingModelActionRuntime(
            toolName = MobileActionFunctions.UI_TAP,
            parameters = mapOf("target" to "继续", "expectedPackageName" to "com.model.supplied"),
        )
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            toolRegistry = ToolRegistry(ToolProvider { localOnlyAllowedToolSpecs() }),
        )
        val observeRequest = ToolRequest(
            id = "observe-after-open-2",
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            arguments = mapOf(
                "maxTextChars" to "2000",
                "maxNodes" to "50",
                "expectedPackageName" to "com.xingin.xhs",
            ),
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-continue-2",
                    input = "打开小红书并点击继续",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = observeRequest,
                observedResult = ToolResult(
                    requestId = observeRequest.id,
                    status = ToolStatus.Succeeded,
                    summary = "已观察当前屏幕。",
                    data = mapOf(
                        "toolName" to observeRequest.toolName,
                        "privacy" to "LocalOnly",
                        "screenObservationJson" to screenObservationJson(),
                    ),
                ),
                priorRequests = listOf(observeRequest),
            ),
        )

        assertNotNull(replan)
        assertEquals(
            "com.model.supplied",
            replan?.request?.arguments?.get("expectedPackageName"),
        )
    }
}
