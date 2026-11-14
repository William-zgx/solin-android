package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.MOBILE_ACTION_MODEL_ID
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.memory.MemoryRepository
import com.bytedance.zgx.solin.skill.AppSearchPlanningMode
import com.bytedance.zgx.solin.skill.BuiltInSkillRuntime
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedObservationCoordinatorTest {
    @Test
    fun typedCandidatesReduceCoordinatorModelCallsAndStillCompleteVerifiedSearch() {
        val actionRuntime = QueuedModelActionRuntime(
            listOf(
                ActionDraft(
                    functionName = MobileActionFunctions.UI_TAP,
                    title = "点击搜索入口",
                    summary = "点击淘宝搜索入口。",
                    parameters = mapOf("target" to "search_entry")
                ),
                ActionDraft(
                    functionName = MobileActionFunctions.UI_WAIT,
                    title = "等待搜索结果",
                    summary = "等待搜索结果验证。",
                    parameters = mapOf("verifySearchQuery" to "耳机")
                )
            )
        )
        val runtime = AgentLoopRuntime(
            memoryIndex = MemoryRepository(),
            actionPlanningRuntime = actionRuntime,
            skillRuntime = BuiltInSkillRuntime(
                appSearchPlanningModeProvider = { AppSearchPlanningMode.ModelDrivenBootstrap }
            ),
            traceStore = InMemoryAgentTraceStore(clockMillis = { 1_000L }),
            observationReplanner = CompositeAgentObservationReplanner(
                TypedObservationCandidateReplanner(enabled = true),
                ModelObservationReplanner(
                    actionPlanningRuntime = actionRuntime,
                    actionModelPathProvider = { "/verified/mobile-action.litertlm" },
                    maxModelReplans = 5
                )
            )
        )
        val planned = runtime.runOnce(
            input = "打开淘宝搜索耳机",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
            installedCapabilityProfiles = listOf(ModelCatalog.profileForModelId(MOBILE_ACTION_MODEL_ID))
        )
        require(planned.plan is AgentPlan.UseTool)
        runtime.confirmToolRequest(planned.run.id, planned.plan.request.id)
        var plan = runtime.observeToolResult(
            runId = planned.run.id,
            result = ToolResult(
                requestId = planned.plan.request.id,
                status = ToolStatus.Succeeded,
                summary = "已打开淘宝",
                data = unverifiedExternalActivityResultData(MobileActionFunctions.OPEN_APP_BY_NAME)
            )
        ).requireNextTool(MobileActionFunctions.UI_WAIT)
        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(plan.request, actionType = "wait")
        ).requireNextTool(MobileActionFunctions.OBSERVE_CURRENT_SCREEN)
        plan = runtime.observeToolResult(
            planned.run.id,
            observeScreenResult(
                request = plan.request,
                rawScreenObservationJson = searchEntryScreenObservationJson("screen-${plan.request.id}")
            )
        ).requireNextTool(MobileActionFunctions.UI_TAP)

        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(
                plan.request,
                actionType = "tap",
                extraData = mapOf("afterScreenObservationJson" to searchInputScreenObservationJson("after-tap"))
            )
        ).requireNextTool(MobileActionFunctions.UI_TYPE_TEXT)
        assertFalse(plan.plannedByModel)
        assertEquals(TYPED_OBSERVATION_REPLAN_REQUEST_REASON, plan.request.reason)
        assertEquals("search_edit_text", plan.request.arguments["target"])
        assertEquals("耳机", plan.request.arguments["text"])
        assertFalse(plan.request.arguments.containsKey("verifySearchQuery"))

        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(
                plan.request,
                actionType = "type_text",
                extraData = mapOf("afterScreenObservationJson" to searchInputScreenObservationJson("after-type"))
            )
        ).requireNextTool(MobileActionFunctions.UI_SUBMIT_SEARCH)
        assertFalse(plan.plannedByModel)
        assertEquals(TYPED_OBSERVATION_REPLAN_REQUEST_REASON, plan.request.reason)

        plan = runtime.observeToolResult(
            planned.run.id,
            uiActionResult(plan.request, actionType = "submit_search")
        ).requireNextTool(MobileActionFunctions.UI_WAIT)
        assertTrue(plan.plannedByModel)

        val verified = requireNotNull(
            runtime.observeToolResult(
                runId = planned.run.id,
                result = uiActionResult(
                    request = plan.request,
                    actionType = "wait",
                    extraData = mapOf("searchVerificationStatus" to "verified")
                )
            )
        )

        assertEquals(AgentRunState.Completed, verified.run.state)
        assertEquals(AgentObservationDecision.Complete, verified.decision)
        assertEquals(2, actionRuntime.plannedInputs.size)
        assertEquals(List(2) { "/verified/mobile-action.litertlm" }, actionRuntime.actionModelPaths)
        assertNull(runtime.latestPendingConfirmation())
    }

    private fun AgentObservationResult?.requireNextTool(toolName: String): AgentPlan.UseTool {
        assertNotNull(this)
        assertEquals(AgentRunState.ExecutingTool, this?.run?.state)
        val decision = this?.decision
        assertTrue(decision is AgentObservationDecision.PlanNextTool)
        val plan = (decision as AgentObservationDecision.PlanNextTool).plan
        assertEquals(toolName, plan.request.toolName)
        return plan
    }

}
