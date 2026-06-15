package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.GenerationStats
import com.bytedance.zgx.pocketmind.LocalModelTokenLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveGenerationPolicyTest {
    @Test
    fun healthyRuntimeKeepsDefaultBudgets() {
        val decision = AdaptiveGenerationPolicy.decide(
            AdaptiveGenerationPolicyInput(
                preferredBackend = BackendChoice.GPU,
                lastGenerationStats = GenerationStats(
                    tokenCount = 120,
                    tokensPerSecond = 8.0,
                    backend = BackendChoice.GPU,
                    firstTokenMs = 700,
                ),
                requestedImageCount = 4,
            ),
        )

        assertEquals(BackendChoice.GPU, decision.backend)
        assertEquals(LocalModelTokenLimits.MAX_INPUT_TOKENS, decision.maxInputTokens)
        assertEquals(LocalModelTokenLimits.OUTPUT_TOKEN_RESERVE, decision.outputReserveTokens)
        assertEquals(4, decision.maxImages)
        assertTrue(decision.notices.isEmpty())
    }

    @Test
    fun fallbackSlowOrQualityIssueUsesConservativeBudgets() {
        val decision = AdaptiveGenerationPolicy.decide(
            AdaptiveGenerationPolicyInput(
                preferredBackend = BackendChoice.GPU,
                lastGenerationStats = GenerationStats(
                    tokenCount = 10,
                    tokensPerSecond = 1.2,
                    backend = BackendChoice.CPU,
                    firstTokenMs = 6_000,
                    usedFallbackBackend = true,
                ),
                qualityIssue = GenerationQualityIssue.RepetitionLoop,
                requestedImageCount = 5,
            ),
        )

        assertEquals(BackendChoice.CPU, decision.backend)
        assertEquals(4 * 1024, decision.maxInputTokens)
        assertEquals(1024, decision.outputReserveTokens)
        assertEquals(2, decision.maxImages)
        assertTrue(decision.notices.any { it.contains("GPU fallback") })
        assertTrue(decision.notices.any { it.contains("quality guard") })
    }
}
