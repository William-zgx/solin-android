package com.bytedance.zgx.pocketmind.orchestration

import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.action.MobileActionPlanner
import com.bytedance.zgx.pocketmind.memory.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantOrchestratorTest {
    @Test
    fun routesActionInputToMissingModelWhenActionCapabilityIsUnavailable() {
        val orchestrator = AssistantOrchestrator(MemoryRepository(), MobileActionPlanner())

        val route = orchestrator.route(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat),
            memoryEnabled = true,
        )

        assertEquals(AssistantRoute.MissingModel(ModelCapability.MobileAction), route)
    }

    @Test
    fun routesActionInputToDraftWhenActionCapabilityIsInstalled() {
        val orchestrator = AssistantOrchestrator(MemoryRepository(), MobileActionPlanner())

        val route = orchestrator.route(
            input = "打开 Wi-Fi 设置",
            installedCapabilities = setOf(ModelCapability.Chat, ModelCapability.MobileAction),
            memoryEnabled = true,
        )

        assertTrue(route is AssistantRoute.Action)
    }

    @Test
    fun injectsMemoryContextOnlyWhenMemoryIsEnabledAndInstalled() {
        val memoryRepository = MemoryRepository()
        memoryRepository.index("one", "用户喜欢端侧离线聊天")
        val orchestrator = AssistantOrchestrator(memoryRepository, MobileActionPlanner())

        val route = orchestrator.route(
            input = "端侧聊天偏好是什么",
            installedCapabilities = setOf(ModelCapability.Chat, ModelCapability.MemoryEmbedding),
            memoryEnabled = true,
        )

        require(route is AssistantRoute.Chat)
        assertTrue(route.promptForModel.contains("本地记忆"))
        assertEquals(1, route.memoryHits.size)
    }

    @Test
    fun skipsMemoryContextWhenDisabled() {
        val memoryRepository = MemoryRepository()
        memoryRepository.index("one", "用户喜欢端侧离线聊天")
        val orchestrator = AssistantOrchestrator(memoryRepository, MobileActionPlanner())

        val route = orchestrator.route(
            input = "端侧聊天偏好是什么",
            installedCapabilities = setOf(ModelCapability.Chat, ModelCapability.MemoryEmbedding),
            memoryEnabled = false,
        )

        require(route is AssistantRoute.Chat)
        assertEquals("端侧聊天偏好是什么", route.promptForModel)
        assertTrue(route.memoryHits.isEmpty())
    }
}
