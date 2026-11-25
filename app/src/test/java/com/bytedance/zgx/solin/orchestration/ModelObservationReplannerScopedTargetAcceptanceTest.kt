package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scoped tap/scroll drafts resolve against the latest after/current observation only. */
class ModelObservationReplannerScopedTargetAcceptanceTest : ObservationReplannerTestFixtures() {
    private val afterId = "screen_1731000000222_3f9a1c7e"
    private val beforeId = "screen_1731000000111_0b1c2d3e"
    private val tapNode = "n3_ab12cd34_3f9a1c7e"
    private val scrollNode = "n5_cd56ef78_3f9a1c7e"
    private val dangerNode = "n7_99aa00bb_3f9a1c7e"

    private fun observation(id: String) = screenObservationFixtureJson(
        observationId = id,
        elements = arrayOf(
            accessibilityElement(tapNode, "搜索", "button", 10, 20, 110, 70, clickable = true),
            accessibilityElement(scrollNode, "列表", "list", 10, 100, 410, 800, scrollable = true),
            accessibilityElement(dangerNode, "立即支付", "button", 10, 900, 410, 960, clickable = true)
        )
    )

    private fun plan(tool: String, target: String, vararg data: Pair<String, String>): Pair<AgentObservationReplan?, String?> {
        val runtime = RecordingModelActionRuntime(tool, mapOf("target" to target))
        val replanner = ModelObservationReplanner(
            actionPlanningRuntime = runtime,
            actionModelPathProvider = { "/tmp/action-model.litertlm" }
        )
        val previous = ToolRequest(id = "observe-1", toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        val replan = replanner.planNext(
            AgentObservationReplanContext(
                run = AgentRun(
                    id = "run",
                    input = "看当前屏幕继续操作",
                    state = AgentRunState.Observing,
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L
                ),
                previousRequest = previous,
                observedResult = ToolResult(
                    requestId = previous.id,
                    status = ToolStatus.Succeeded,
                    summary = "已观察当前屏幕。",
                    data = mapOf(
                        "toolName" to previous.toolName,
                        "privacy" to "LocalOnly",
                        "requiresLocalModel" to true.toString()
                    ) + data
                ),
                priorRequests = listOf(previous)
            )
        )
        return replan to replanner.lastDiagnosticSnapshot()?.reason
    }

    @Test
    fun scopedTapOnLatestAfterObservationIsAcceptedAndKeepsScopedTarget() {
        val (replan, reason) = plan(
            MobileActionFunctions.UI_TAP,
            "$afterId/$tapNode",
            "beforeScreenObservationJson" to observation(beforeId),
            "afterScreenObservationJson" to observation(afterId)
        )
        assertNotNull(reason, replan)
        assertEquals("$afterId/$tapNode", replan?.request?.arguments?.get("target"))
    }

    @Test
    fun scopedTapOnCurrentObservationIsAccepted() {
        val (replan, reason) = plan(MobileActionFunctions.UI_TAP, "$afterId/$tapNode", "screenObservationJson" to observation(afterId))
        assertNotNull(reason, replan)
    }

    @Test
    fun scopedScrollOnLatestObservationIsAccepted() {
        val (replan, reason) = plan(MobileActionFunctions.UI_SCROLL, "$afterId/$scrollNode", "afterScreenObservationJson" to observation(afterId))
        assertNotNull(reason, replan)
    }

    @Test
    fun beforeAndForeignSnapshotRefsAreRejected() {
        val data = arrayOf(
            "beforeScreenObservationJson" to observation(beforeId),
            "afterScreenObservationJson" to observation(afterId)
        )
        val before = plan(MobileActionFunctions.UI_TAP, "$beforeId/$tapNode", *data)
        assertNull(before.first)
        assertEquals("unsupported_observed_target", before.second)
        val foreign = plan(MobileActionFunctions.UI_TAP, "screen_1731000009999_deadbeef/$tapNode", *data)
        assertNull(foreign.first)
        assertEquals("unsupported_observed_target", foreign.second)
    }

    @Test
    fun scopedRefToUnknownNodeIsRejected() {
        val (replan, reason) = plan(MobileActionFunctions.UI_TAP, "$afterId/n9_00000000_3f9a1c7e", "afterScreenObservationJson" to observation(afterId))
        assertNull(replan)
        assertEquals("unsupported_observed_target", reason)
    }

    @Test
    fun scopedRefToDangerousTextNodeIsRejectedAsDangerous() {
        val (replan, reason) = plan(MobileActionFunctions.UI_TAP, "$afterId/$dangerNode", "afterScreenObservationJson" to observation(afterId))
        assertNull(replan)
        assertEquals("dangerous_observation_action", reason)
    }

    @Test
    fun scopedRefToBlankAccessibilityNodeOverDangerousOcrTextIsRejectedAsDangerous() {
        val blankPayNode = "n8_cafe1234_3f9a1c7e"
        val obs = screenObservationFixtureJson(
            observationId = afterId,
            elements = arrayOf(
                accessibilityElement(blankPayNode, "", "button", 10, 900, 410, 960, clickable = true),
                ocrElement("ocr:block:0", "立即支付", left = 20, top = 910, right = 400, bottom = 950)
            )
        )
        val (replan, reason) = plan(MobileActionFunctions.UI_TAP, "$afterId/$blankPayNode", "afterScreenObservationJson" to obs)
        assertNull(replan)
        assertEquals("dangerous_observation_action", reason)
    }

    @Test
    fun scopedTypingStaysFailClosed() {
        val (replan, reason) = plan(MobileActionFunctions.UI_TYPE_TEXT, "$afterId/$tapNode", "afterScreenObservationJson" to observation(afterId))
        assertNull(replan)
        assertEquals("unsupported_observed_target", reason)
    }

    @Test
    fun bareLegacyTargetStillAccepted() {
        val (replan, reason) = plan(MobileActionFunctions.UI_TAP, tapNode, "afterScreenObservationJson" to observation(afterId))
        assertNotNull(reason, replan)
    }

    @Test
    fun correctivePromptShowsFullBareNodeIdNotTruncatedScopedRef() {
        val context = AgentObservationReplanContext(
            run = AgentRun(
                id = "run",
                input = "看当前屏幕继续操作",
                state = AgentRunState.Observing,
                createdAtMillis = 1L,
                updatedAtMillis = 1L
            ),
            previousRequest = ToolRequest(id = "o", toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN),
            observedResult = ToolResult(
                requestId = "o",
                status = ToolStatus.Succeeded,
                summary = "ok",
                data = mapOf("privacy" to "LocalOnly", "afterScreenObservationJson" to observation(afterId))
            ),
            priorRequests = emptyList()
        )
        val prompt = context.correctiveObservationModelPrompt(
            ToolRegistry(),
            ActionDraft(
                functionName = MobileActionFunctions.UI_TAP,
                title = "t",
                summary = "s",
                parameters = mapOf("target" to "bad")
            ),
            "unsupported_observed_target"
        )
        val list = prompt.lines().single { it.startsWith("可用 target") }
        assertTrue(list, list.contains(tapNode))
        assertTrue(list, list.contains(scrollNode))
        assertFalse(list, list.contains("$afterId/"))
        assertFalse(list, list.contains("..."))
        assertTrue(prompt.length.toString(), prompt.length <= MAX_CORRECTIVE_PROMPT_CHARS)
    }
}
