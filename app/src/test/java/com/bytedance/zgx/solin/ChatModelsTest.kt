package com.bytedance.zgx.solin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelsTest {

    @Test
    fun `GenerationParameters defaults reasoningEffort to Off`() {
        val params = GenerationParameters()
        assertEquals(ReasoningEffort.Off, params.reasoningEffort)
    }

    @Test
    fun `copy with reasoningEffort High round trips`() {
        val params = GenerationParameters().copy(reasoningEffort = ReasoningEffort.High)
        assertEquals(ReasoningEffort.High, params.reasoningEffort)
    }

    @Test
    fun `all ReasoningEffort entries have non-empty names`() {
        ReasoningEffort.entries.forEach {
            assertTrue("${it.name} should be non-empty", it.name.isNotEmpty())
        }
        assertTrue(ReasoningEffort.entries.size >= 5)
    }
}
