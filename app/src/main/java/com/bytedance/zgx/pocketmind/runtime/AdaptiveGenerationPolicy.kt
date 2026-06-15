package com.bytedance.zgx.pocketmind.runtime

import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.GenerationStats
import com.bytedance.zgx.pocketmind.LocalModelTokenLimits

data class AdaptiveGenerationPolicyInput(
    val preferredBackend: BackendChoice,
    val lastGenerationStats: GenerationStats? = null,
    val qualityIssue: GenerationQualityIssue? = null,
    val requestedImageCount: Int = 0,
)

data class AdaptiveGenerationPolicyDecision(
    val backend: BackendChoice,
    val maxInputTokens: Int,
    val outputReserveTokens: Int,
    val maxImages: Int,
    val notices: List<String> = emptyList(),
)

object AdaptiveGenerationPolicy {
    private const val SLOW_TOKENS_PER_SECOND = 2.0
    private const val SLOW_FIRST_TOKEN_MS = 5_000L
    private const val MIN_INPUT_TOKENS = 4 * 1024
    private const val MIN_OUTPUT_RESERVE_TOKENS = 1024
    private const val DEFAULT_MAX_IMAGES = 5
    private const val DEGRADED_MAX_IMAGES = 2

    fun decide(input: AdaptiveGenerationPolicyInput): AdaptiveGenerationPolicyDecision {
        val stats = input.lastGenerationStats
        val slowDecode = stats?.tokensPerSecond?.let { it < SLOW_TOKENS_PER_SECOND } == true
        val slowFirstToken = stats?.firstTokenMs?.let { it > SLOW_FIRST_TOKEN_MS } == true
        val fallbackObserved = stats?.usedFallbackBackend == true
        val qualityStopped = input.qualityIssue != null
        val shouldConserve = slowDecode || slowFirstToken || fallbackObserved || qualityStopped

        val notices = buildList {
            if (fallbackObserved) add("GPU fallback observed; prefer conservative local budget.")
            if (slowDecode || slowFirstToken) add("Slow local generation observed; reduce context pressure.")
            if (qualityStopped) add("Previous output quality guard triggered; reserve a shorter answer budget.")
            if (input.requestedImageCount > DEFAULT_MAX_IMAGES) add("Image count exceeds local model image cap.")
        }
        return AdaptiveGenerationPolicyDecision(
            backend = stats?.backend ?: input.preferredBackend,
            maxInputTokens = if (shouldConserve) MIN_INPUT_TOKENS else LocalModelTokenLimits.MAX_INPUT_TOKENS,
            outputReserveTokens = if (qualityStopped) {
                MIN_OUTPUT_RESERVE_TOKENS
            } else {
                LocalModelTokenLimits.OUTPUT_TOKEN_RESERVE
            },
            maxImages = if (shouldConserve) {
                minOf(input.requestedImageCount.coerceAtLeast(0), DEGRADED_MAX_IMAGES)
            } else {
                minOf(input.requestedImageCount.coerceAtLeast(0), DEFAULT_MAX_IMAGES)
            },
            notices = notices,
        )
    }
}
