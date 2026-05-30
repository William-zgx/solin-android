package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionPlan
import com.bytedance.zgx.pocketmind.action.ActionPlanningResult
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.action.MobileActionPlanner
import com.bytedance.zgx.pocketmind.memory.MemoryRepository
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
}
