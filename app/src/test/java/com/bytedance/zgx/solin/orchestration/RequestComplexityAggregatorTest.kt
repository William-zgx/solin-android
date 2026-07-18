package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestComplexityAggregatorTest {
    @Test
    fun contextRatioUsesExactSeventyPercentBoundaryWithoutOverflow() {
        assertEquals(RequestComplexity.Simple, complexity(tokens = 699, window = 1_000))
        assertEquals(RequestComplexity.Complex, complexity(tokens = 700, window = 1_000))
        assertEquals(RequestComplexity.Simple, complexity(tokens = 716, window = 1_024))
        assertEquals(RequestComplexity.Complex, complexity(tokens = 717, window = 1_024))
        assertEquals(
            RequestComplexity.Complex,
            complexity(tokens = Int.MAX_VALUE, window = Int.MAX_VALUE),
        )
    }

    @Test
    fun mediumHighPlanningAndToolLoopAreHardComplexSignals() {
        assertEquals(RequestComplexity.Simple, complexity(reasoning = ReasoningEffort.Minimal))
        assertEquals(RequestComplexity.Simple, complexity(reasoning = ReasoningEffort.Low))
        assertEquals(RequestComplexity.Complex, complexity(reasoning = ReasoningEffort.Medium))
        assertEquals(RequestComplexity.Complex, complexity(reasoning = ReasoningEffort.High))
        assertEquals(RequestComplexity.Complex, complexity(requiresMultiStepPlan = true))
        assertEquals(RequestComplexity.Complex, complexity(requiresToolLoop = true))
    }

    @Test
    fun explicitOutputBudgetUsesFourThousandNinetySixThreshold() {
        assertEquals(RequestComplexity.Simple, complexity(output = 2_048))
        assertEquals(RequestComplexity.Simple, complexity(output = 4_095))
        assertEquals(RequestComplexity.Complex, complexity(output = 4_096))
    }

    @Test
    fun missingOrInvalidRequiredValuesAreUnknownWithoutHardSignal() {
        assertEquals(RequestComplexity.Unknown, complexity(tokens = null))
        assertEquals(RequestComplexity.Unknown, complexity(window = null))
        assertEquals(RequestComplexity.Unknown, complexity(output = null))
        assertEquals(RequestComplexity.Unknown, complexity(tokens = 0))
        assertEquals(RequestComplexity.Unknown, complexity(tokens = -1))
        assertEquals(RequestComplexity.Unknown, complexity(window = 0))
        assertEquals(RequestComplexity.Unknown, complexity(window = -1))
        assertEquals(RequestComplexity.Unknown, complexity(output = 0))
        assertEquals(RequestComplexity.Unknown, complexity(output = -1))
    }

    @Test
    fun hardSignalsWinOverMissingOrInvalidMetadata() {
        assertEquals(
            RequestComplexity.Complex,
            complexity(tokens = null, window = null, output = null, reasoning = ReasoningEffort.High),
        )
        assertEquals(
            RequestComplexity.Complex,
            complexity(tokens = null, window = 0, output = null, requiresMultiStepPlan = true),
        )
        assertEquals(
            RequestComplexity.Complex,
            complexity(tokens = -1, window = null, output = null, requiresToolLoop = true),
        )
        assertEquals(
            RequestComplexity.Complex,
            complexity(tokens = null, window = null, output = 4_096),
        )
        assertEquals(
            RequestComplexity.Complex,
            complexity(tokens = 700, window = 1_000, output = null),
        )
    }

    private fun complexity(
        tokens: Int? = 100,
        window: Int? = 1_000,
        reasoning: ReasoningEffort = ReasoningEffort.Off,
        requiresMultiStepPlan: Boolean = false,
        requiresToolLoop: Boolean = false,
        output: Int? = 2_048,
    ): RequestComplexity = RequestComplexityAggregator.aggregate(
        RequestComplexityInput(
            estimatedInputTokens = tokens,
            localContextWindowTokens = window,
            reasoningEffort = reasoning,
            requiresMultiStepPlan = requiresMultiStepPlan,
            requiresToolLoop = requiresToolLoop,
            requestedOutputTokens = output,
        ),
    )
}
