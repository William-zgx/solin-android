package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultContextCompactorTest {

    /** Rough token estimator: ~4 chars per token, summed across all messages (plus a small overhead per msg). */
    private val charEstimator: (List<ChatMessage>) -> Int = { msgs ->
        msgs.sumOf { m -> (m.text.length / 4) + 1 }
    }

    private fun msg(role: MessageRole, text: String) = ChatMessage(role = role, text = text)
    private fun user(text: String) = msg(MessageRole.User, text)
    private fun asst(text: String) = msg(MessageRole.Assistant, text)

    /** Build a conversation of `turnCount` user-assistant pairs, each of length ~`charsPerMsg`. */
    private fun history(turnCount: Int, charsPerMsg: Int = 80): List<ChatMessage> = buildList {
        val payload = "x".repeat(charsPerMsg)
        repeat(turnCount) { i ->
            add(user("[u$i] $payload"))
            add(asst("[a$i] $payload"))
        }
    }

    @Test
    fun underThresholdReturnsMessagesUnchanged() = runTest {
        val compactor = DefaultContextCompactor()
        val messages = history(4, charsPerMsg = 40) // small history
        val estimated = charEstimator(messages)

        // Budget large enough that we are well under the 0.85 threshold.
        val budget = estimated * 3
        val result = compactor.compact(messages, budget, charEstimator)

        assertEquals(CompactionTrigger.None, result.triggerReason)
        assertEquals(0, result.compactionCount)
        assertEquals(estimated, result.tokensBefore)
        assertEquals(estimated, result.tokensAfter)
        // Identity: returned list should be the same reference (fast path).
        assertSame(messages, result.messages)
    }

    @Test
    fun emptyMessageListDoesNotCrash() = runTest {
        val compactor = DefaultContextCompactor()
        val result = compactor.compact(emptyList(), tokenBudget = 1000, charEstimator)
        assertTrue(result.messages.isEmpty())
        assertEquals(0, result.tokensBefore)
        assertEquals(0, result.tokensAfter)
        assertEquals(CompactionTrigger.None, result.triggerReason)
    }

    @Test
    fun singleMessageDoesNotCrash() = runTest {
        val compactor = DefaultContextCompactor()
        val single = listOf(user("hello"))
        val estimated = charEstimator(single)
        // Budget smaller than even the single message, triggering the "even tail won't fit" path.
        val result = compactor.compact(single, tokenBudget = 1, charEstimator)
        // Should still return a non-empty list with one message (the degenerate tail case).
        assertEquals(1, result.messages.size)
        assertTrue(result.tokensAfter <= estimated)
        assertEquals(CompactionTrigger.OverBudget, result.triggerReason)
    }

    @Test
    fun recentNTurnsArePreservedAfterCompaction() = runTest {
        // preserveRecentTurns=2 so tail = 4 messages (2 pairs).
        val config = CompactionConfig(preserveRecentTurns = 2)
        val compactor = DefaultContextCompactor(config)

        // Build history with long middle messages so compaction saves significant tokens.
        // 10 turns (20 msgs), payload length 500 chars each ≈ 126 tokens/msg.
        val messages = history(turnCount = 10, charsPerMsg = 500)
        val tailExpected = messages.takeLast(4)
        val before = charEstimator(messages)

        // Choose budget such that:
        //   - before (~2520) > 0.85*budget  => triggers compaction
        //   - after (summary + 4-tail, ~1100 tokens) <= 0.85*budget  => fits
        val budget = 1500
        val threshold = (budget * config.compactionThresholdRatio).toInt()
        assertTrue(
            "setup: before ($before) must exceed threshold ($threshold)",
            before > threshold,
        )
        val result = compactor.compact(messages, budget, charEstimator)
        assertTrue(
            "after compaction tokensAfter (${result.tokensAfter}) must be <= threshold ($threshold)",
            result.tokensAfter <= threshold,
        )

        // Tail of result must equal the expected tail (verbatim).
        val actualTail = result.messages.takeLast(4)
        assertEquals("tail must preserve the last 4 messages verbatim", tailExpected, actualTail)

        // The head of the result should contain a summary message (assistant role).
        val head = result.messages.dropLast(4)
        assertTrue("expected a summary in the head, got $head", head.isNotEmpty())
        assertTrue(
            "summary message should contain the template word 'summary'",
            head.any { it.role == MessageRole.Assistant && "summary" in it.text.lowercase() },
        )
        assertTrue("compactionCount must be > 0", result.compactionCount > 0)
    }

    @Test
    fun compactionReducesTokenCountWhenOverBudget() = runTest {
        val compactor = DefaultContextCompactor(
            CompactionConfig(preserveRecentTurns = 2, compactionThresholdRatio = 0.5)
        )
        // Heavy history.
        val messages = history(turnCount = 30, charsPerMsg = 200)
        val before = charEstimator(messages)

        // Force a small budget so compaction triggers aggressively.
        val budget = (before * 0.2).toInt().coerceAtLeast(50)
        val result = compactor.compact(messages, budget, charEstimator)

        assertTrue(
            "tokensAfter (${result.tokensAfter}) must be <= tokensBefore ($before)",
            result.tokensAfter <= result.tokensBefore,
        )
        assertTrue(
            "tokensAfter (${result.tokensAfter}) must be <= budget ($budget) after compaction",
            result.tokensAfter <= budget,
        )
        assertTrue("must have collapsed messages", result.compactionCount > 0)
    }

    @Test
    fun overflowTriggerFiresWhenEvenMinTailExceedsBudget() = runTest {
        // Configure with a generous threshold so any overshoot goes straight to OverBudget path,
        // and set maxSummaryLength tiny so the summary itself stays small (forcing aggressive drop).
        val config = CompactionConfig(
            preserveRecentTurns = 6,
            compactionThresholdRatio = 0.99,
            maxSummaryLengthChars = 50,
        )
        val compactor = DefaultContextCompactor(config)

        // Build a history where even 2 turns of tail messages are very large.
        val big = "y".repeat(500)
        val messages = buildList {
            repeat(20) { i ->
                add(user("[u$i] $big"))
                add(asst("[a$i] $big"))
            }
        }
        val singleMsgTokens = charEstimator(listOf(user("[u] $big")))
        // Budget smaller than a single big message: even the degenerate tail path will engage.
        val budget = (singleMsgTokens / 2).coerceAtLeast(1)
        val result = compactor.compact(messages, budget, charEstimator)

        // The trigger reason must be OverBudget.
        assertEquals(CompactionTrigger.OverBudget, result.triggerReason)
        // Result must not throw and must return *some* list.
        assertNotNull(result.messages)
    }

    @Test
    fun systemPreservationFlagIsAcceptedAndDoesNotCrash() = runTest {
        // Documenting current (Wave 2) behavior: ChatMessage has no System role, so the
        // preserveSystemMessages flag is a forward-compat no-op. The prefix is always empty
        // today, but setting the flag must not throw and both settings must produce the same
        // result for User/Assistant-only histories.
        val messages = history(turnCount = 10, charsPerMsg = 80)
        val before = charEstimator(messages)
        val budget = (before * 2) // under threshold
        val resultOn = DefaultContextCompactor(CompactionConfig(preserveSystemMessages = true))
            .compact(messages, budget, charEstimator)
        val resultOff = DefaultContextCompactor(CompactionConfig(preserveSystemMessages = false))
            .compact(messages, budget, charEstimator)
        // Both paths should return messages unchanged (under threshold) and both succeed.
        assertEquals(CompactionTrigger.None, resultOn.triggerReason)
        assertEquals(CompactionTrigger.None, resultOff.triggerReason)
        assertEquals(messages.size, resultOn.messages.size)
        assertEquals(messages.size, resultOff.messages.size)
    }

    @Test
    fun compactionProducesApproachingBudgetTriggerWhenBetweenThresholdAndBudget() = runTest {
        // thresholdRatio=0.5 so threshold = budget/2. We craft history whose tokens are
        // 0.75*budget (between threshold and budget) -> ApproachingBudget.
        val config = CompactionConfig(
            preserveRecentTurns = 2,
            compactionThresholdRatio = 0.5,
            maxSummaryLengthChars = 500,
        )
        val compactor = DefaultContextCompactor(config)

        // Build a medium history.
        val messages = history(turnCount = 12, charsPerMsg = 80)
        val estimated = charEstimator(messages)
        // Set budget so estimated ~= 0.75 * budget (above 0.5 threshold, below budget).
        val budget = (estimated / 0.75).toInt()
        val result = compactor.compact(messages, budget, charEstimator)

        assertEquals(
            "tokens before ($estimated) must be in (threshold, budget] for ApproachingBudget",
            CompactionTrigger.ApproachingBudget,
            result.triggerReason,
        )
        assertTrue("tokensAfter must be <= threshold", result.tokensAfter <= (budget * config.compactionThresholdRatio).toInt())
    }
}
