package com.bytedance.zgx.gemmalocalqa

import com.bytedance.zgx.gemmalocalqa.data.normalized
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationParametersRulesTest {
    @Test
    fun normalized_clampsGenerationParametersToUiSupportedRange() {
        val normalized = GenerationParameters(
            temperature = 2.0f,
            topP = 0.01f,
            topK = 200,
        ).normalized()

        assertEquals(GenerationParameters.MAX_TEMPERATURE, normalized.temperature, 0.001f)
        assertEquals(GenerationParameters.MIN_TOP_P, normalized.topP, 0.001f)
        assertEquals(GenerationParameters.MAX_TOP_K, normalized.topK)
    }

    @Test
    fun normalized_roundsFloatParametersForStablePersistence() {
        val normalized = GenerationParameters(
            temperature = 0.73f,
            topP = 0.944f,
            topK = 41,
        ).normalized()

        assertEquals(0.75f, normalized.temperature, 0.001f)
        assertEquals(0.94f, normalized.topP, 0.001f)
        assertEquals(41, normalized.topK)
    }
}
