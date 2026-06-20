package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.LocalModelTokenLimits
import com.bytedance.zgx.pocketmind.MessageRole
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtRuntimeConfigTest {
    @Test
    fun engineConfigUsesExplicitLocalContextWindow() {
        val config = defaultEngineConfigSpec(
            modelPath = "/tmp/model.litertlm",
            backend = BackendChoice.GPU,
            cacheDir = File("/tmp/cache"),
        )

        assertEquals(LocalModelTokenLimits.MAX_TOTAL_TOKENS, config.maxNumTokens)
        assertEquals("/tmp/model.litertlm", config.modelPath)
        assertEquals("/tmp/cache", config.cacheDir)
        assertEquals(null, config.visionBackend)
        assertEquals(null, config.maxNumImages)
    }

    @Test
    fun engineConfigUsesCapabilityProfileContextWindow() {
        val config = defaultEngineConfigSpec(
            modelPath = "/tmp/model.litertlm",
            backend = BackendChoice.CPU,
            cacheDir = File("/tmp/cache"),
            capabilities = LocalModelRuntimeCapabilities(
                supportsVisionInput = false,
                contextWindowTokens = 4096,
                preferredBackends = setOf(BackendChoice.CPU),
            ),
        )

        assertEquals(4096, config.maxNumTokens)
        assertEquals(null, config.visionBackend)
        assertEquals(null, config.maxNumImages)
    }

    @Test
    fun engineConfigEnablesVisionBackendOnlyForVisionCapableLocalModel() {
        val config = defaultEngineConfigSpec(
            modelPath = "/tmp/model.litertlm",
            backend = BackendChoice.GPU,
            cacheDir = File("/tmp/cache"),
            capabilities = LocalModelRuntimeCapabilities(
                supportsVisionInput = true,
                contextWindowTokens = 4096,
                preferredBackends = setOf(BackendChoice.GPU, BackendChoice.CPU),
            ),
        )

        assertTrue(config.visionBackend != null)
        assertEquals(4096, config.maxNumTokens)
        assertEquals(5, config.maxNumImages)
    }

    @Test
    fun localHistoryBudgetReservesOutputTokens() {
        val expectedBudget =
            LocalModelTokenLimits.MAX_TOTAL_TOKENS -
                LocalModelTokenLimits.OUTPUT_TOKEN_RESERVE -
                LocalModelTokenLimits.SYSTEM_PROMPT_TOKEN_RESERVE -
                LocalModelTokenLimits.CURRENT_PROMPT_TOKEN_RESERVE
        val longHistory = listOf(
            ChatMessage(MessageRole.User, "旧".repeat(LocalModelTokenLimits.MAX_TOTAL_TOKENS)),
            ChatMessage(MessageRole.Assistant, "最近的回答"),
            ChatMessage(MessageRole.User, "最近的问题"),
        )

        val budgeted = budgetLocalRuntimeHistory(longHistory, currentPrompt = "你好")

        assertTrue(budgeted.isNotEmpty())
        assertEquals("最近的问题", budgeted.last().text)
        assertTrue(
            budgeted.sumOf { estimateLocalRuntimeTokens(it.text) } <= expectedBudget,
        )
    }

    @Test
    fun localHistoryBudgetCanUseAdaptiveInputBudget() {
        val adaptiveInputBudget = 3 * 1024
        val expectedBudget =
            adaptiveInputBudget -
                LocalModelTokenLimits.SYSTEM_PROMPT_TOKEN_RESERVE -
                LocalModelTokenLimits.CURRENT_PROMPT_TOKEN_RESERVE
        val longHistory = listOf(
            ChatMessage(MessageRole.User, "旧".repeat(LocalModelTokenLimits.MAX_TOTAL_TOKENS)),
            ChatMessage(MessageRole.Assistant, "最近的回答"),
            ChatMessage(MessageRole.User, "最近的问题"),
        )

        val budgeted = budgetLocalRuntimeHistory(
            messages = longHistory,
            currentPrompt = "你好",
            maxInputTokens = adaptiveInputBudget,
        )

        assertTrue(budgeted.isNotEmpty())
        assertEquals("最近的问题", budgeted.last().text)
        assertTrue(
            budgeted.sumOf { estimateLocalRuntimeTokens(it.text) } <= expectedBudget,
        )
    }

    @Test
    fun tokenLimitDisplayShowsInputBudgetAndOutputReserve() {
        assertEquals("总窗口 8k tokens", LocalModelTokenLimits.totalDisplayText())
        assertEquals("输入预算 6k tokens", LocalModelTokenLimits.inputDisplayText())
        assertEquals("输出预留 2k tokens", LocalModelTokenLimits.outputDisplayText())
    }
}
