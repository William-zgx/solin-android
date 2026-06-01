package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlan
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningResult
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.action.MobileActionPlanner
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.memory.MemoryRepository
import com.bytedance.zgx.pocketmind.skill.BuiltInSkillRuntime
import com.bytedance.zgx.pocketmind.tool.ToolResult
import com.bytedance.zgx.pocketmind.tool.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantOrchestratorTest {
    @Test
    fun routesActionInputToDraftWithoutRequiringActionModel() {
        val orchestrator = AssistantOrchestrator(MemoryRepository(), RuleActionRuntime())

        val route = orchestrator.route(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        assertTrue(route is AssistantRoute.Action)
        require(route is AssistantRoute.Action)
        assertTrue(!route.plannedByModel)
    }

    @Test
    fun injectsMemoryContextWhenMemoryIsEnabledWithoutRequiringEmbeddingModel() {
        val memoryRepository = MemoryRepository()
        memoryRepository.index("one", "用户喜欢端侧离线聊天")
        val orchestrator = AssistantOrchestrator(memoryRepository, RuleActionRuntime())

        val route = orchestrator.route(
            input = "端侧聊天偏好是什么",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        require(route is AssistantRoute.Chat)
        assertTrue(route.promptForModel.contains("本地记忆"))
        assertTrue(route.promptForModel.contains("用户当前输入的语言"))
        assertEquals(1, route.memoryHits.size)
    }

    @Test
    fun skipsMemoryContextWhenDisabled() {
        val memoryRepository = MemoryRepository()
        memoryRepository.index("one", "用户喜欢端侧离线聊天")
        val orchestrator = AssistantOrchestrator(memoryRepository, RuleActionRuntime())

        val route = orchestrator.route(
            input = "端侧聊天偏好是什么",
            installedCapabilities = setOf(ModelCapability.Chat, ModelCapability.MemoryEmbedding),
            memoryEnabled = false,
        )

        require(route is AssistantRoute.Chat)
        assertEquals("端侧聊天偏好是什么", route.promptForModel)
        assertTrue(route.memoryHits.isEmpty())
    }

    @Test
    fun defaultSequentialReplannerPlansExplicitNextActionAfterObservation() {
        val orchestrator = AssistantOrchestrator(MemoryRepository(), SearchThenWifiActionRuntime())
        val route = orchestrator.route(
            input = "先搜 Kotlin，然后打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(route is AssistantRoute.Action)

        orchestrator.confirmToolRequest(requireNotNull(route.runId), requireNotNull(route.toolRequest).id)
        val observed = orchestrator.observeToolResult(
            runId = route.runId,
            result = ToolResult(
                requestId = route.toolRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已打开网页搜索",
            ),
        )

        requireNotNull(observed)
        assertEquals(AgentRunState.AwaitingUserConfirmation, observed.run.state)
        require(observed.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(
            MobileActionFunctions.OPEN_WIFI_SETTINGS,
            observed.decision.plan.request.toolName,
        )
    }

    @Test
    fun clipboardSummaryShareAdvancesFromModelOutputToShareConfirmation() {
        val orchestrator = AssistantOrchestrator(MemoryRepository(), RuleActionRuntime())
        val route = orchestrator.route(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )
        require(route is AssistantRoute.Action)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, route.toolRequest?.toolName)

        orchestrator.confirmToolRequest(requireNotNull(route.runId), requireNotNull(route.toolRequest).id)
        val observed = orchestrator.observeToolResult(
            runId = route.runId,
            result = ToolResult(
                requestId = route.toolRequest.id,
                status = ToolStatus.Succeeded,
                summary = "已读取剪贴板文本",
                data = mapOf(
                    "toolName" to MobileActionFunctions.READ_CLIPBOARD,
                    "text" to "需要总结的剪贴板文本",
                    "truncated" to "false",
                ),
            ),
        )
        requireNotNull(observed)
        require(observed.decision is AgentObservationDecision.ContinueWithModel)

        val modelObserved = orchestrator.observeModelResult(route.runId, "摘要文本")

        requireNotNull(modelObserved)
        assertEquals(AgentRunState.AwaitingUserConfirmation, modelObserved.run.state)
        require(modelObserved.decision is AgentObservationDecision.PlanNextTool)
        assertEquals(MobileActionFunctions.SHARE_TEXT, modelObserved.decision.plan.request.toolName)
        assertEquals("摘要文本", modelObserved.decision.plan.request.arguments["text"])
        val restored = orchestrator.restorePendingAction()
        requireNotNull(restored)
        assertEquals(MobileActionFunctions.SHARE_TEXT, restored.toolRequest?.toolName)
        assertEquals("摘要文本", restored.toolRequest?.arguments?.get("text"))
        assertTrue(restored.toolRequest?.id != route.toolRequest.id)
    }

    @Test
    fun skillFirstClipboardSummaryShareRoutesEvenWhenActionRuntimeDoesNotClassifyAction() {
        val orchestrator = AssistantOrchestrator(MemoryRepository(), NeverActionRuntime())

        val route = orchestrator.route(
            input = "总结剪贴板并分享",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = false,
        )

        require(route is AssistantRoute.Action)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, route.toolRequest?.toolName)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, route.skillId)
        assertEquals("skill-first", route.fallbackReason)
        assertTrue(route.runId != null)

        val restored = orchestrator.restorePendingAction()
        requireNotNull(restored)
        assertEquals(route.runId, restored.runId)
        assertEquals(route.toolRequest, restored.toolRequest)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, restored.skillId)
    }

    private class RuleActionRuntime : ActionPlanningRuntime {
        private val planner = MobileActionPlanner()

        override fun isLikelyAction(input: String): Boolean =
            planner.isLikelyAction(input)

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            val plan: ActionPlan = planner.plan(input)
            return ActionPlanningResult(
                plan = plan,
                usedModel = false,
                fallbackReason = "test fallback",
            )
        }
    }

    private class SearchThenWifiActionRuntime : ActionPlanningRuntime {
        private var planCallCount = 0

        override fun isLikelyAction(input: String): Boolean = true

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            planCallCount += 1
            val draft = if (planCallCount == 1) {
                ActionDraft(
                    functionName = MobileActionFunctions.WEB_SEARCH,
                    title = "Web 搜索",
                    summary = "将在浏览器中搜索：Kotlin",
                    parameters = mapOf("query" to "Kotlin"),
                    requiresConfirmation = true,
                )
            } else {
                ActionDraft(
                    functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                    title = "打开 Wi-Fi 设置",
                    summary = "将打开系统 Wi-Fi 设置页。",
                    parameters = emptyMap(),
                    requiresConfirmation = true,
                )
            }
            return ActionPlanningResult(
                plan = ActionPlan(kind = ActionPlanKind.Draft, draft = draft),
                usedModel = false,
                fallbackReason = "test fallback",
            )
        }
    }

    private class NeverActionRuntime : ActionPlanningRuntime {
        override fun isLikelyAction(input: String): Boolean = false

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult =
            ActionPlanningResult(
                plan = ActionPlan(ActionPlanKind.NoAction),
                usedModel = false,
                fallbackReason = null,
            )
    }
}
