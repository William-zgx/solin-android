package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ReasoningEffort

enum class RequestComplexity {
    Simple,
    Complex,
    Unknown,
}

data class RequestComplexityInput(
    val estimatedInputTokens: Int?,
    val localContextWindowTokens: Int?,
    val reasoningEffort: ReasoningEffort,
    val requiresMultiStepPlan: Boolean,
    val requiresToolLoop: Boolean,
    val requestedOutputTokens: Int?,
)

object RequestComplexityAggregator {
    const val HIGH_OUTPUT_TOKEN_THRESHOLD = 4_096
    private const val CONTEXT_PRESSURE_PERCENT = 70L

    fun aggregate(input: RequestComplexityInput): RequestComplexity {
        val hasHighContextPressure = input.estimatedInputTokens.isPositive() &&
            input.localContextWindowTokens.isPositive() &&
            input.estimatedInputTokens!!.toLong() * 100L >=
            input.localContextWindowTokens!!.toLong() * CONTEXT_PRESSURE_PERCENT
        if (
            hasHighContextPressure ||
            input.reasoningEffort == ReasoningEffort.Medium ||
            input.reasoningEffort == ReasoningEffort.High ||
            input.requiresMultiStepPlan ||
            input.requiresToolLoop ||
            input.requestedOutputTokens?.let { it >= HIGH_OUTPUT_TOKEN_THRESHOLD } == true
        ) {
            return RequestComplexity.Complex
        }

        return if (
            input.estimatedInputTokens.isPositive() &&
            input.localContextWindowTokens.isPositive() &&
            input.requestedOutputTokens.isPositive()
        ) {
            RequestComplexity.Simple
        } else {
            RequestComplexity.Unknown
        }
    }
}

private fun Int?.isPositive(): Boolean = this != null && this > 0
