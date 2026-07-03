package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactorWiringTest {

    @Test
    fun noOpContextCompactorReturnsMessagesUnchanged() = runTest {
        val messages = listOf(
            ChatMessage(role = MessageRole.User, text = "hello"),
            ChatMessage(role = MessageRole.Assistant, text = "hi"),
        )
        val result = NoOpContextCompactor.compact(
            messages = messages,
            tokenBudget = 1000,
            estimatedTokens = { it.size * 4 },
        )
        assertSame(messages, result.messages)
        assertEquals(0, result.compactionCount)
        assertEquals(CompactionTrigger.None, result.triggerReason)
        assertEquals(8, result.tokensBefore)
        assertEquals(8, result.tokensAfter)
    }

    @Test
    fun defaultCompactorUnderThresholdReturnsUnchanged() = runTest {
        val compactor = DefaultContextCompactor()
        val messages = listOf(
            ChatMessage(role = MessageRole.User, text = "hi"),
            ChatMessage(role = MessageRole.Assistant, text = "hello"),
        )
        val result = compactor.compact(
            messages = messages,
            tokenBudget = 10_000,
            estimatedTokens = { it.size * 4 },
        )
        assertEquals(CompactionTrigger.None, result.triggerReason)
        assertSame(messages, result.messages)
        assertEquals(0, result.compactionCount)
    }

    @Test
    fun contextCompactorInterfaceAcceptsSuspendImpls() = runTest {
        // Verify the interface can be implemented with a suspend lambda-capturing fake
        // and that the wiring signature is compatible with AgentLoopRuntime's default.
        var called = false
        val fake = object : ContextCompactor {
            override suspend fun compact(
                messages: List<ChatMessage>,
                tokenBudget: Int,
                estimatedTokens: (List<ChatMessage>) -> Int,
            ): CompactionResult {
                called = true
                return CompactionResult(
                    messages = messages,
                    tokensBefore = estimatedTokens(messages),
                    tokensAfter = estimatedTokens(messages),
                    triggerReason = CompactionTrigger.None,
                )
            }
        }
        val input = listOf(ChatMessage(role = MessageRole.User, text = "x"))
        val out = fake.compact(input, 100) { it.size }
        assertTrue(called)
        assertEquals(1, out.tokensBefore)
    }

    @Test
    fun defaultCompactorCompactsWhenOverBudget() = runTest {
        // Force over-budget: many messages, tiny budget, so the aggressive-truncation
        // path fires and returns strictly fewer messages than we fed in.
        val compactor = DefaultContextCompactor(
            config = CompactionConfig(preserveRecentTurns = 2, compactionThresholdRatio = 0.5),
        )
        val messages = (0 until 50).flatMap { i ->
            listOf(
                ChatMessage(role = MessageRole.User, text = "user message $i with a bunch of words to inflate token count"),
                ChatMessage(role = MessageRole.Assistant, text = "assistant reply $i also quite a few words here to use budget"),
            )
        }
        val tokenBudget = 30
        val result = compactor.compact(
            messages = messages,
            tokenBudget = tokenBudget,
            // Simple estimator: each message = 10 tokens so 50*2*10 = 1000 tokens; way over budget.
            estimatedTokens = { it.size * 10 },
        )
        assertTrue(
            "Expected compaction to shrink messages: before=${messages.size} after=${result.messages.size}",
            result.messages.size < messages.size,
        )
        assertTrue("tokensAfter must be within budget", result.tokensAfter <= tokenBudget)
    }
}
