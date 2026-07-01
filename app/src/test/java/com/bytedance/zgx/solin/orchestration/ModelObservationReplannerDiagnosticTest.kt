package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.action.ActionPlan
import com.bytedance.zgx.solin.action.ActionPlanKind
import com.bytedance.zgx.solin.action.ActionPlanningResult
import com.bytedance.zgx.solin.action.ActionPlanningRuntime
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelObservationReplannerDiagnosticTest {
    @Test
    fun modelOutputWithoutToolCallIsRecordedAsDiagnostic() {
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = DiagnosticRuntime(),
            actionModelPathProvider = { "/tmp/action-model.litertlm" },
            maxModelReplans = 2,
        )

        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run-diagnostic",
                    input = "打开淘宝搜索海河牛奶",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                previousRequest = ToolRequest(toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN),
                observedResult = ToolResult(
                    requestId = "observe",
                    status = ToolStatus.Succeeded,
                    summary = "observed",
                    data = mapOf(
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to true.toString(),
                        "screenObservationJson" to observationJson(),
                    ),
                ),
                priorRequests = emptyList(),
            ),
        )

        assertNull(replan)
        val diagnostic = replanner.lastDiagnosticSnapshot()
        requireNotNull(diagnostic)
        assertTrue(diagnostic.attempted)
        assertFalse(diagnostic.usedModel)
        assertTrue(diagnostic.modelAttempted)
        assertEquals("missing_call_tool_output", diagnostic.reason)
        assertEquals("missing_call_tool_output", diagnostic.modelFailureReason)
        assertEquals("我会先点击搜索框。", diagnostic.modelOutputPreview)
        val diagnostics = replanner.diagnosticSnapshots()
        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.all { it.attempted })
        assertTrue(diagnostics.all { it.reason == "missing_call_tool_output" })
    }

    private class DiagnosticRuntime : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = false

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(ActionPlanKind.NoAction),
                usedModel = false,
                fallbackReason = "动作规划模型未产出可执行草稿",
                modelAttempted = true,
                modelOutputPreview = "我会先点击搜索框。",
                modelFailureReason = "missing_call_tool_output",
            )
    }

    private fun observationJson(): String =
        """
        {
          "schemaVersion": 1,
          "observationId": "screen-diagnostic",
          "capturedAtMillis": 1,
          "packageName": "com.taobao.taobao",
          "privacyLevel": "${MessagePrivacy.LocalOnly.name}",
          "sources": ["accessibility"],
          "elementCount": 1,
          "sourceCounts": {"accessibility": 1},
          "truncated": false,
          "elements": [{
            "id": "search-entry",
            "source": "accessibility",
            "bounds": {"left": 20, "top": 24, "right": 900, "bottom": 96},
            "text": "搜索商品",
            "role": "button",
            "clickability": {
              "clickable": true,
              "editable": false,
              "scrollable": false,
              "enabled": true
            },
            "confidence": 1.0,
            "sensitiveFlags": [],
            "privacyLevel": "${MessagePrivacy.LocalOnly.name}"
          }]
        }
        """.trimIndent()
}
