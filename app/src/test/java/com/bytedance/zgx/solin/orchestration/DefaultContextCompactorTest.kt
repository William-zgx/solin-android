package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageKind
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Build a conversation of `turnCount` user-assistant pairs, each of length ~`charsPerMsg`.
     * [userChars] sizes user messages separately: user text is never shortened, so tests about
     * shrinking assistant/tool content use short user messages.
     */
    private fun history(turnCount: Int, charsPerMsg: Int = 80, userChars: Int = charsPerMsg): List<ChatMessage> = buildList {
        val payload = "x".repeat(charsPerMsg)
        val userPayload = "x".repeat(userChars)
        repeat(turnCount) { i ->
            add(user("[u$i] $userPayload"))
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

        // Build history with long assistant messages so compaction saves significant tokens.
        // 10 turns (20 msgs): assistant 600 chars ≈ 151 tokens, user ≈ 4 tokens (kept verbatim).
        val messages = history(turnCount = 10, charsPerMsg = 600, userChars = 10)
        val tailExpected = messages.takeLast(4)
        val before = charEstimator(messages)

        // Choose budget such that:
        //   - before (~1550) > 0.85*budget  => triggers compaction
        //   - after (summary + middle users + 4-tail, ~560 tokens) <= 0.85*budget  => fits
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
    fun failedToolResultInMiddleSurvivesCompactionVerbatim() = runTest {
        val config = CompactionConfig(preserveRecentTurns = 2)
        val compactor = DefaultContextCompactor(config)

        val failure = ChatMessage(
            role = MessageRole.Assistant,
            text = "工具执行失败：search_entry_not_found（在当前页找不到搜索入口）。" + "d".repeat(400),
            kind = MessageKind.ToolFailure,
        )
        // Failure sits in the middle (older than the preserved tail), surrounded by long chat.
        val messages = buildList {
            addAll(history(turnCount = 3, charsPerMsg = 500))
            add(failure)
            addAll(history(turnCount = 3, charsPerMsg = 500))
        }
        val budget = 1500
        val threshold = (budget * config.compactionThresholdRatio).toInt()
        assertTrue("setup must trigger compaction", charEstimator(messages) > threshold)

        val result = compactor.compact(messages, budget, charEstimator)

        assertTrue("compaction must have run", result.compactionCount > 0)
        assertTrue(
            "failed tool result must survive compaction verbatim",
            result.messages.any { it.kind == MessageKind.ToolFailure && it.text == failure.text },
        )
    }

    @Test
    fun onlyLatestScreenObservationSurvivesCompaction() = runTest {
        val config = CompactionConfig(preserveRecentTurns = 2)
        val compactor = DefaultContextCompactor(config)

        val olderScreen = ChatMessage(
            role = MessageRole.Assistant,
            text = "屏幕观测(旧)：" + "o".repeat(500),
            kind = MessageKind.ScreenObservation,
        )
        val latestScreen = ChatMessage(
            role = MessageRole.Assistant,
            text = "屏幕观测(新)：" + "n".repeat(500),
            kind = MessageKind.ScreenObservation,
        )
        // Both observations sit in the middle; only the latest should be kept verbatim.
        val messages = buildList {
            addAll(history(turnCount = 2, charsPerMsg = 500))
            add(olderScreen)
            addAll(history(turnCount = 2, charsPerMsg = 500))
            add(latestScreen)
            addAll(history(turnCount = 3, charsPerMsg = 500))
        }
        val budget = 1500
        val threshold = (budget * config.compactionThresholdRatio).toInt()
        assertTrue("setup must trigger compaction", charEstimator(messages) > threshold)

        val result = compactor.compact(messages, budget, charEstimator)

        assertTrue(
            "latest screen observation must survive verbatim",
            result.messages.any { it.kind == MessageKind.ScreenObservation && it.text == latestScreen.text },
        )
        assertTrue(
            "older screen observation must NOT survive verbatim (summarized to reclaim tokens)",
            result.messages.none { it.text == olderScreen.text },
        )
    }

    @Test
    fun aggressiveTruncationFallbackStillPreservesFailedToolResult() = runTest {
        val config = CompactionConfig(preserveRecentTurns = 2)
        val compactor = DefaultContextCompactor(config)

        val failure = ChatMessage(
            role = MessageRole.Assistant,
            text = "工具执行失败：dangerous_action（检测到支付控件）。",
            kind = MessageKind.ToolFailure,
        )
        // A failure in the middle, then MANY large chat turns so even after summarizing we are
        // still over budget and the aggressive-truncation fallback runs. The failure must survive.
        val messages = buildList {
            add(failure)
            addAll(history(turnCount = 20, charsPerMsg = 500, userChars = 10))
        }
        // Budget so tight that the summarize loop cannot fit -> fallback truncation path executes.
        val budget = 200

        val result = compactor.compact(messages, budget, charEstimator)

        assertEquals(CompactionTrigger.OverBudget, result.triggerReason)
        assertTrue(
            "failed tool result must survive even the aggressive-truncation fallback",
            result.messages.any { it.kind == MessageKind.ToolFailure && it.text == failure.text },
        )
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
        val messages = history(turnCount = 12, charsPerMsg = 160, userChars = 8)
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

    // ── P4: incremental token accounting ────────────────────────────────────────────────────────

    /**
     * Additive estimator that counts how many times it is invoked on a *whole-list* (size > 1)
     * argument. The incremental accounting path should call the estimator on single-message lists
     * (to seed per-message costs) but must NOT re-scan the whole list once per drop in the fallback.
     */
    private class CountingAdditiveEstimator : (List<ChatMessage>) -> Int {
        var wholeListCalls = 0
            private set

        override fun invoke(msgs: List<ChatMessage>): Int {
            if (msgs.size > 1) wholeListCalls += 1
            return msgs.sumOf { m -> (m.text.length / 4) + 1 }
        }
    }

    @Test
    fun incrementalAccountingMatchesWholeListAccountingUnderAggressiveFallback() = runTest {
        // Same tight-budget fallback scenario as the preservation test, but assert the exact
        // tokensAfter/compactionCount an additive estimator must yield. The running-total path is
        // only correct if it agrees with a from-scratch sum over the surviving messages.
        val config = CompactionConfig(preserveRecentTurns = 2, maxSummaryLengthChars = 50)
        val compactor = DefaultContextCompactor(config)
        val failure = ChatMessage(
            role = MessageRole.Assistant,
            text = "工具执行失败：dangerous_action。",
            kind = MessageKind.ToolFailure,
        )
        val messages = buildList {
            add(failure)
            addAll(history(turnCount = 25, charsPerMsg = 400, userChars = 10))
        }
        val budget = 150

        val result = compactor.compact(messages, budget, charEstimator)

        assertEquals(CompactionTrigger.OverBudget, result.triggerReason)
        // tokensAfter must equal an independent whole-list estimate of the surviving messages.
        assertEquals(
            "incremental running total must match a from-scratch estimate of the survivors",
            charEstimator(result.messages),
            result.tokensAfter,
        )
        // compactionCount must equal how many messages were actually removed.
        assertEquals(messages.size - result.messages.size, result.compactionCount)
        assertTrue(
            "failed tool result must survive",
            result.messages.any { it.kind == MessageKind.ToolFailure && it.text == failure.text },
        )
    }

    @Test
    fun incrementalAccountingAvoidsPerDropWholeListRescans() = runTest {
        // With an additive estimator, the fallback must not call the whole-list estimator once per
        // drop. A long history forcing many drops would incur dozens of whole-list scans under the
        // old code; the incremental path bounds whole-list calls to a small constant.
        val config = CompactionConfig(preserveRecentTurns = 2, maxSummaryLengthChars = 50)
        val compactor = DefaultContextCompactor(config)
        val messages = history(turnCount = 40, charsPerMsg = 300)
        val estimator = CountingAdditiveEstimator()

        val result = compactor.compact(messages, tokenBudget = 120, estimator)

        assertEquals(CompactionTrigger.OverBudget, result.triggerReason)
        assertTrue("compaction must have dropped many messages", result.compactionCount > 10)
        // Seeding costs uses single-message calls; the summarize loop uses a bounded number of
        // whole-list calls. The per-drop fallback must add none. Allow generous slack for the
        // summarize-window iterations while still catching an O(drops) regression.
        assertTrue(
            "whole-list estimator calls (${estimator.wholeListCalls}) must stay well below the drop count (${result.compactionCount})",
            estimator.wholeListCalls < result.compactionCount,
        )
    }

    @Test
    fun nonAdditiveEstimatorFallsBackToExactWholeListAccounting() = runTest {
        // A deliberately non-additive estimator: it adds a fixed framing surcharge that does not
        // decompose per message (sum of per-message costs != whole-list cost). The compactor must
        // detect this and fall back to exact whole-list re-estimation so tokensAfter stays truthful.
        val config = CompactionConfig(preserveRecentTurns = 2, maxSummaryLengthChars = 50)
        val compactor = DefaultContextCompactor(config)
        val framingSurcharge = 500
        val nonAdditive: (List<ChatMessage>) -> Int = { msgs ->
            if (msgs.isEmpty()) 0 else msgs.sumOf { m -> (m.text.length / 4) + 1 } + framingSurcharge
        }
        val messages = history(turnCount = 30, charsPerMsg = 300)
        val budget = 900

        val result = compactor.compact(messages, budget, nonAdditive)

        // tokensAfter must equal the non-additive estimator applied to the survivors (framing
        // surcharge included), proving the running-total shortcut was NOT used.
        assertEquals(
            "non-additive estimator result must match an exact whole-list estimate of survivors",
            nonAdditive(result.messages),
            result.tokensAfter,
        )
    }

    // ── User-text invariant ─────────────────────────────────────────────────────────────────────

    @Test
    fun constraintAtTheEndOfALongMiddleUserMessageSurvivesSummarization() = runTest {
        val config = CompactionConfig(preserveRecentTurns = 2)
        val request = user("帮我把这周的周报整理一下，" + "包括项目进展和风险。".repeat(30) + "但不要发送")
        val messages = buildList {
            add(request)
            add(asst("好的。" + "a".repeat(800)))
            addAll(history(turnCount = 8, charsPerMsg = 600, userChars = 10))
        }
        val budget = 1500
        assertTrue("setup must trigger compaction", charEstimator(messages) > (budget * 0.85).toInt())
        assertTrue("setup: request is longer than the old 200-char snippet", request.text.length > 200)

        val result = DefaultContextCompactor(config).compact(messages, budget, charEstimator)

        assertTrue("compaction must have run", result.compactionCount > 0)
        assertTrue("request must survive byte-identical", result.messages.contains(request))
        assertTrue(result.messages.first { it.role == MessageRole.User }.text.endsWith("但不要发送"))
        assertTrue(
            "the summary must not repeat user text",
            result.messages.none { it.role == MessageRole.Assistant && "但不要发送" in it.text },
        )
    }

    @Test
    fun userTextAloneOverBudgetKeepsFirstAndLatestAndReplacesTheRestWithOneLine() = runTest {
        val first = user("第一条请求：只看不要改。" + "q".repeat(300))
        val middle = (1..3).map { i -> user("中间消息$i " + "m".repeat(200 + i)) }
        val latest = user("最新消息：但不要发送。" + "z".repeat(300))
        val messages = buildList {
            add(first)
            add(asst("a".repeat(400)))
            middle.forEach { message ->
                add(message)
                add(asst("b".repeat(400)))
            }
            add(latest)
        }

        val result = DefaultContextCompactor(CompactionConfig(preserveRecentTurns = 2))
            .compact(messages, tokenBudget = 220, charEstimator)

        val expectedLine = omissionLineText(
            omittedCount = 3,
            omittedChars = middle.sumOf { it.text.codePointCount(0, it.text.length) },
        )
        assertEquals(listOf(first, result.messages[1], latest), result.messages)
        assertEquals(expectedLine, result.messages[1].text)
        assertEquals(MessageRole.User, result.messages[1].role)
        assertEquals(CompactionTrigger.OverBudget, result.triggerReason)
        assertEquals("every assistant message and the 3 middle user messages", 7, result.compactionCount)
    }

    @Test
    fun forcedZeroBudgetKeepsTheFirstRequestAndTheLatestUserMessageWhole() = runTest {
        val messages = history(turnCount = 8, charsPerMsg = 300)
        val firstUser = messages.first()
        val latestUser = messages.last { it.role == MessageRole.User }

        val result = DefaultContextCompactor().compact(messages, tokenBudget = 0, charEstimator)

        assertEquals(3, result.messages.size)
        assertSame(firstUser, result.messages[0])
        assertEquals(6, result.messages[1].omittedUserMessageCount())
        assertSame(latestUser, result.messages[2])
    }

    @Test
    fun omissionLineAndSummaryAreLocalOnlyWhenTheirSourcesAre() = runTest {
        val privateReply = ChatMessage(
            role = MessageRole.Assistant,
            text = "剪贴板内容：" + "p".repeat(600),
            privacy = MessagePrivacy.LocalOnly,
        )
        val summarized = buildList {
            add(user("hi"))
            add(privateReply)
            addAll(history(turnCount = 8, charsPerMsg = 600, userChars = 10))
        }
        val summary = DefaultContextCompactor(CompactionConfig(preserveRecentTurns = 2))
            .compact(summarized, tokenBudget = 1500, charEstimator)
            .messages
            .single { "summary" in it.text.lowercase() }
        assertEquals(MessagePrivacy.LocalOnly, summary.privacy)

        val privateUser = ChatMessage(role = MessageRole.User, text = "m".repeat(400), privacy = MessagePrivacy.LocalOnly)
        val omitted = listOf(user("first " + "f".repeat(400)), privateUser, user("last " + "l".repeat(400)))
        val line = DefaultContextCompactor().compact(omitted, tokenBudget = 0, charEstimator).messages[1]
        assertEquals(1, line.omittedUserMessageCount())
        assertEquals(MessagePrivacy.LocalOnly, line.privacy)
    }

    // ── Recovery check ──────────────────────────────────────────────────────────────────────────

    @Test
    fun recoveryReinsertsAMissingUserMessageAtItsOriginalPosition() {
        val original = listOf(user("u0"), asst("a0"), user("u1 但不要发送"), asst("a1"), user("u2"), asst("a2"))
        val compacted = listOf(original[0], original[1], original[3], original[4], original[5])

        val restored = restoreMissingUserMessages(original, compacted)

        assertEquals(original, restored)
    }

    @Test
    fun recoveryReplacesATruncatedCopyWithTheOriginal() {
        val steer = user("改成明天下午三点，只看不要改")
        val original = listOf(user("u0"), asst("a0"), steer, asst("a1"))
        val truncated = steer.copy(text = steer.text.take(5))
        val compacted = listOf(original[0], original[1], truncated, original[3])

        val restored = restoreMissingUserMessages(original, compacted)

        assertEquals(original, restored)
    }

    @Test
    fun recoveryLeavesUserMessagesCoveredByAMatchingOmissionLine() {
        val original = listOf(user("u0"), user("u1"), user("u2"), user("u3"))
        val line = ChatMessage(role = MessageRole.User, text = omissionLineText(2, 4))
        val compacted = listOf(original[0], line, original[3])

        assertSame(compacted, restoreMissingUserMessages(original, compacted, omittedIndices = setOf(1, 2)))
    }

    @Test
    fun recoveryFailsClosedWhenTheOmissionLineIsAbsentOrDisagrees() {
        val original = listOf(user("u0"), user("u1"), user("u2"), user("u3"))

        val withoutLine = listOf(original[0], original[3])
        assertEquals(original, restoreMissingUserMessages(original, withoutLine, omittedIndices = setOf(1, 2)))

        val wrongCount = listOf(original[0], ChatMessage(role = MessageRole.User, text = omissionLineText(1, 2)), original[3])
        val restored = restoreMissingUserMessages(original, wrongCount, omittedIndices = setOf(1, 2))
        assertTrue(restored.containsAll(original))
    }

    @Test
    fun recoveryNeverExcusesTheFirstOrLatestUserMessage() {
        val original = listOf(user("first"), asst("a"), user("mid"), user("latest"))
        val line = ChatMessage(role = MessageRole.User, text = omissionLineText(2, 9))
        val compacted = listOf(line, original[1], original[2])

        val restored = restoreMissingUserMessages(original, compacted, omittedIndices = setOf(0, 3))

        assertTrue("first request restored", original[0] in restored)
        assertTrue("first request back before what followed it", restored.indexOf(original[0]) < restored.indexOf(original[1]))
        assertEquals(original[3], restored.last())
    }

    @Test
    fun recoveryReturnsTheSameListWhenNothingIsMissing() {
        val original = listOf(user("u0"), asst("a0"), user("u1"))
        val compacted = listOf(ChatMessage(role = MessageRole.Assistant, text = "Previous conversation summary: a0"), original[0], original[2])

        assertSame(compacted, restoreMissingUserMessages(original, compacted))
        assertFalse(restoreMissingUserMessages(original, compacted).contains(original[1]))
    }
}
