package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionPlan
import com.bytedance.zgx.pocketmind.action.ActionPlanKind
import com.bytedance.zgx.pocketmind.action.ActionPlanningResult
import com.bytedance.zgx.pocketmind.action.ActionPlanningRuntime
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.memory.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopRuntimeCompatibilityTest {
    @Test
    fun pureChatInputKeepsMemoryHitsAndDoesNotEnterActionPlanning() {
        val memoryRepository = MemoryRepository()
        memoryRepository.index("preference", "用户喜欢端侧离线聊天")
        val actionRuntime = RecordingActionRuntime(likelyAction = false)
        val orchestrator = AssistantOrchestrator(memoryRepository, actionRuntime)

        val route = orchestrator.route(
            input = "端侧聊天偏好是什么",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        require(route is AssistantRoute.Chat)
        assertTrue(route.promptForModel.contains("用户当前输入的语言"))
        assertTrue(route.promptForModel.contains("用户喜欢端侧离线聊天"))
        assertEquals(listOf("preference"), route.memoryHits.map { it.id })
        assertEquals(0, actionRuntime.planCallCount)
    }

    @Test
    fun wifiActionInputCreatesPendingConfirmationDraftWithoutExecution() {
        val actionRuntime = RecordingActionRuntime(
            likelyAction = true,
            planningResult = ActionPlanningResult(
                plan = ActionPlan(
                    kind = ActionPlanKind.Draft,
                    draft = ActionDraft(
                        functionName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                        title = "打开 Wi-Fi 设置",
                        summary = "将打开系统 Wi-Fi 设置页。",
                        parameters = emptyMap(),
                        requiresConfirmation = true,
                    ),
                ),
                usedModel = false,
                fallbackReason = "test fallback",
            ),
        )
        val orchestrator = AssistantOrchestrator(MemoryRepository(), actionRuntime)

        val route = orchestrator.route(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        require(route is AssistantRoute.Action)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, route.draft.functionName)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, route.toolRequest?.toolName)
        assertTrue(route.draft.requiresConfirmation)
        assertTrue(!route.plannedByModel)
        assertEquals("skill-first", route.fallbackReason)
        assertEquals(0, actionRuntime.planCallCount)
    }

    private class RecordingActionRuntime(
        private val likelyAction: Boolean,
        private val planningResult: ActionPlanningResult = ActionPlanningResult(
            plan = ActionPlan(ActionPlanKind.NoAction),
            usedModel = false,
            fallbackReason = null,
        ),
    ) : ActionPlanningRuntime {
        var planCallCount: Int = 0
            private set

        override fun isLikelyAction(input: String): Boolean = likelyAction

        override fun plan(input: String, actionModelPath: String?): ActionPlanningResult {
            planCallCount += 1
            return planningResult
        }
    }
}
