package com.bytedance.zgx.solin.debug

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.device.AppSearchProgressEvidence
import com.bytedance.zgx.solin.orchestration.AgentObservationReplanContext
import com.bytedance.zgx.solin.orchestration.AgentRun
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelDrivenAppSearchRecoveryPlannerTest {
    @Test
    fun recoveryTapsSearchEntryBeforeTypingWhenModelContinuesWithText() {
        val plan = ModelDrivenAppSearchRecoveryPlanner.plan(
            observedResult = observationResult(
                elements = listOf(
                    element(
                        id = "search-entry",
                        text = "搜索商品",
                        clickable = true,
                        bounds = "[20,24,900,96]",
                    ),
                    element(
                        id = "search-submit",
                        text = "搜索",
                        clickable = true,
                        bounds = "[920,24,1060,96]",
                    ),
                ),
            ),
            verifySearchQuery = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
            attemptedRecoveryKinds = emptySet(),
        )

        requireNotNull(plan)
        assertEquals(ModelDrivenAppSearchRecoveryPlanner.KIND_TAP_SEARCH_ENTRY, plan.kind)
        assertEquals(MobileActionFunctions.UI_TAP, plan.request.toolName)
        assertEquals("搜索商品", plan.request.arguments["target"])
    }

    @Test
    fun recoveryTypesQueryAfterSearchEntryTapExposesEditableField() {
        val plan = ModelDrivenAppSearchRecoveryPlanner.plan(
            observedResult = observationResult(
                elements = listOf(
                    element(
                        id = "search-input",
                        text = "搜索商品",
                        editable = true,
                        bounds = "[20,24,900,96]",
                    ),
                ),
            ),
            verifySearchQuery = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
            attemptedRecoveryKinds = setOf(ModelDrivenAppSearchRecoveryPlanner.KIND_TAP_SEARCH_ENTRY),
        )

        requireNotNull(plan)
        assertEquals(ModelDrivenAppSearchRecoveryPlanner.KIND_TYPE_SEARCH_QUERY, plan.kind)
        assertEquals(MobileActionFunctions.UI_TYPE_TEXT, plan.request.toolName)
        assertEquals("搜索输入框", plan.request.arguments["target"])
        assertEquals("海河牛奶", plan.request.arguments["text"])
    }

    @Test
    fun recoverySubmitsSearchOnlyAfterQueryIsVisible() {
        val plan = ModelDrivenAppSearchRecoveryPlanner.plan(
            observedResult = observationResult(
                elements = listOf(
                    element(
                        id = "search-input",
                        text = "海河牛奶",
                        editable = true,
                        bounds = "[20,24,900,96]",
                    ),
                    element(
                        id = "search-submit",
                        text = "搜索",
                        clickable = true,
                        bounds = "[920,24,1060,96]",
                    ),
                ),
            ),
            verifySearchQuery = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
            attemptedRecoveryKinds = setOf(
                ModelDrivenAppSearchRecoveryPlanner.KIND_TAP_SEARCH_ENTRY,
                ModelDrivenAppSearchRecoveryPlanner.KIND_TYPE_SEARCH_QUERY,
            ),
        )

        requireNotNull(plan)
        assertEquals(ModelDrivenAppSearchRecoveryPlanner.KIND_SUBMIT_SEARCH, plan.kind)
        assertEquals(MobileActionFunctions.UI_SUBMIT_SEARCH, plan.request.toolName)
    }

    @Test
    fun recoveryVerifiesSearchResultsAfterModelSubmitSearch() {
        val plan = ModelDrivenAppSearchRecoveryPlanner.plan(
            observedResult = observationResult(
                elements = listOf(
                    element(
                        id = "search-input",
                        text = "海河牛奶",
                        editable = true,
                        bounds = "[20,24,900,96]",
                    ),
                ),
            ),
            verifySearchQuery = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
            expectedAppName = "淘宝",
            previousToolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
            attemptedRecoveryKinds = setOf(
                ModelDrivenAppSearchRecoveryPlanner.KIND_TAP_SEARCH_ENTRY,
                ModelDrivenAppSearchRecoveryPlanner.KIND_TYPE_SEARCH_QUERY,
            ),
        )

        requireNotNull(plan)
        assertEquals(ModelDrivenAppSearchRecoveryPlanner.KIND_VERIFY_SEARCH_RESULTS, plan.kind)
        assertEquals(MobileActionFunctions.UI_WAIT, plan.request.toolName)
        assertEquals("海河牛奶", plan.request.arguments["verifySearchQuery"])
        assertEquals("com.taobao.taobao", plan.request.arguments["expectedPackageName"])
        assertEquals("淘宝", plan.request.arguments["expectedAppName"])
    }

    @Test
    fun recoveryDoesNotPlanAcrossExpectedPackageMismatch() {
        val plan = ModelDrivenAppSearchRecoveryPlanner.plan(
            observedResult = observationResult(
                packageName = "com.miui.securitycenter",
                elements = listOf(
                    element(
                        id = "search-entry",
                        text = "搜索商品",
                        clickable = true,
                        bounds = "[20,24,900,96]",
                    ),
                ),
            ),
            verifySearchQuery = "海河牛奶",
            expectedPackageName = "com.taobao.taobao",
            attemptedRecoveryKinds = emptySet(),
        )

        assertNull(plan)
    }

    @Test
    fun recoveryObservationReplannerMarksRequestWithRecoveryKind() {
        val replanner = ModelDrivenAppSearchRecoveryObservationReplanner(
            verifySearchQueryProvider = { "海河牛奶" },
            expectedPackageNameProvider = { "com.taobao.taobao" },
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-recovery",
                    input = "打开淘宝搜索海河牛奶",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = ToolRequest(toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN),
                observedResult = observationResult(
                    elements = listOf(
                        element(
                            id = "search-entry",
                            text = "搜索商品",
                            clickable = true,
                            bounds = "[20,24,900,96]",
                        ),
                    ),
                ),
                priorRequests = emptyList(),
            ),
        )

        requireNotNull(replan)
        assertEquals(false, replan.plannedByModel)
        assertEquals(MobileActionFunctions.UI_TAP, replan.request.toolName)
        assertEquals(
            ModelDrivenAppSearchRecoveryPlanner.KIND_TAP_SEARCH_ENTRY,
            replan.request.modelDrivenAppSearchRecoveryKind(),
        )
    }

    @Test
    fun recoveryTapProgressEvidenceMarksEntryTapped() {
        val data = AppSearchProgressEvidence.fromData(
            mapOf(
                "actionType" to "tap",
                "status" to "succeeded",
            ),
        ).toData()

        assertEquals("advanced", data["uiActionOutcome"])
        assertEquals("status_succeeded", data["uiActionOutcomeReason"])
        assertEquals("entry_tapped", data["appSearchProgressStage"])
    }

    private fun observationResult(
        packageName: String = "com.taobao.taobao",
        elements: List<String>,
    ): ToolResult =
        ToolResult(
            requestId = "observe",
            status = ToolStatus.Succeeded,
            summary = "observed",
            data = mapOf(
                "privacy" to MessagePrivacy.LocalOnly.name,
                "requiresLocalModel" to true.toString(),
                "screenObservationJson" to screenObservationJson(packageName, elements),
            ),
        )

    private fun screenObservationJson(packageName: String, elements: List<String>): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-recovery",
          "capturedAtMillis": 1,
          "packageName": "$packageName",
          "privacyLevel": "${MessagePrivacy.LocalOnly.name}",
          "sources": ["accessibility"],
          "elementCount": ${elements.size},
          "sourceCounts": {"accessibility": ${elements.size}},
          "truncated": false,
          "elements": [${elements.joinToString(separator = ",")}]
        }
        """.trimIndent()

    private fun element(
        id: String,
        text: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
        bounds: String,
    ): String {
        val (left, top, right, bottom) = bounds
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { value -> value.trim().toInt() }
        return """
        {
          "id": "$id",
          "source": "accessibility",
          "bounds": {"left": $left, "top": $top, "right": $right, "bottom": $bottom},
          "text": "$text",
          "role": "${if (editable) "editable" else "button"}",
          "clickability": {
            "clickable": $clickable,
            "editable": $editable,
            "scrollable": $scrollable,
            "enabled": $enabled
          },
          "confidence": 1.0,
          "sensitiveFlags": [],
          "privacyLevel": "${MessagePrivacy.LocalOnly.name}"
        }
        """.trimIndent()
    }
}
