package com.bytedance.zgx.solin.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTermVectorTest {
    @Test
    fun tokenListVectorPreservesFirstSeenOrderAndRepeatedTokenWeights() {
        val tokens = listOf(
            MemoryToken("alpha", strong = true),
            MemoryToken("茶", strong = false),
            MemoryToken("alpha", strong = true)
        )

        val vector = MemoryTermVector.of(tokens)

        assertEquals(listOf("alpha", "茶"), vector.tokens.toList())
        assertEquals(2f, vector.weights.getValue("alpha"), 0f)
        assertEquals(0.5f, vector.weights.getValue("茶"), 0f)
        assertEquals(MemoryTermVector.of("alpha 茶 alpha").weights, vector.weights)
        assertEquals(vector.weights, MemoryQueryTokens("alpha 茶 alpha").vector.weights)
    }
}
