package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageKind
import com.bytedance.zgx.solin.MessageRole

/**
 * Reason that triggered a context compaction pass.
 */
enum class CompactionTrigger {
    /** History fit within budget; no compaction performed. */
    None,

    /** Approaching budget threshold; proactive compaction to leave headroom. */
    ApproachingBudget,

    /** Already over budget; aggressive compaction/truncation required. */
    OverBudget,

    /** Caller requested compaction explicitly regardless of budget state. */
    Manual,
}

/**
 * Result of a compaction pass.
 *
 * @param messages Compacted message list ready to be fed back into the conversation.
 * @param compactionCount Number of original messages that were collapsed/removed into the summary.
 * @param tokensBefore Estimated tokens before compaction.
 * @param tokensAfter Estimated tokens after compaction.
 * @param triggerReason Why compaction ran.
 */
data class CompactionResult(
    val messages: List<ChatMessage>,
    val compactionCount: Int = 0,
    val tokensBefore: Int,
    val tokensAfter: Int,
    val triggerReason: CompactionTrigger = CompactionTrigger.None,
)

/**
 * Configuration knobs for [DefaultContextCompactor].
 *
 * Wave 2 uses a purely heuristic (non-LLM) summarizer; these values are chosen to be conservative
 * so future waves can swap in a model-based summarizer by subclassing or replacing impl.
 */
data class CompactionConfig(
    /**
     * When true, leading messages with role == System are preserved verbatim.
     *
     * Note: the current [ChatMessage] model has no System role (system prompts are supplied out-of
     * band via conversation config); this flag is kept for forward-compatibility and is a no-op
     * until a System role is introduced. When false, no leading-prefix preservation is performed.
     */
    val preserveSystemMessages: Boolean = true,

    /**
     * Always keep the last N user/assistant pairs (i.e. 2*N messages) untouched at the tail of
     * the conversation. 6 turns == 12 messages, which gives the model ~3 recent exchanges of
     * context. Compaction will only touch messages before this tail.
     */
    val preserveRecentTurns: Int = 6,

    /**
     * Start compacting proactively when estimated tokens exceed this ratio of the budget
     * (0.0–1.0). Using 0.85 leaves 15% headroom for the next user turn + tool output before we
     * risk an overflow.
     */
    val compactionThresholdRatio: Double = 0.85,

    /**
     * Format string for injecting the summary into the conversation. Must contain a single `%s`
     * placeholder which will be replaced by the summary text.
     */
    val summaryMessageTemplate: String = "Previous conversation summary: %s",

    /**
     * Hard cap on summary length in characters. The summary will be truncated with an ellipsis
     * if the heuristic extraction produces longer text.
     */
    val maxSummaryLengthChars: Int = 2000,
) {
    init {
        require(preserveRecentTurns >= 0) { "preserveRecentTurns must be >= 0" }
        require(compactionThresholdRatio in 0.0..1.0) {
            "compactionThresholdRatio must be in [0.0, 1.0]"
        }
        require(maxSummaryLengthChars > 0) { "maxSummaryLengthChars must be > 0" }
        require(summaryMessageTemplate.contains("%s")) {
            "summaryMessageTemplate must contain a %s placeholder"
        }
    }
}

/**
 * Policy object for shortening a conversation history to fit a token budget.
 *
 * Implementations must be pure (no side effects, no event bus, no telemetry) so they can be
 * unit-tested and composed freely. Telemetry/event emission is the caller's responsibility.
 */
interface ContextCompactor {
    /**
     * Compact [messages] to fit within [tokenBudget].
     *
     * @param messages Input history (chronological order: oldest first).
     * @param tokenBudget Maximum tokens the output must fit within.
     * @param estimatedTokens Caller-provided estimator; injected so the compactor can be used with
     *   model-specific tokenizers (LiteRT heuristic, remote tokenizer, etc.).
     */
    suspend fun compact(
        messages: List<ChatMessage>,
        tokenBudget: Int,
        estimatedTokens: (List<ChatMessage>) -> Int,
    ): CompactionResult
}

/**
 * Heuristic, non-LLM implementation of [ContextCompactor] for Wave 2.
 *
 * Strategy:
 *  1. If under threshold, return unchanged.
 *  2. Split history into [prefix (system) | middle (eligible) | tail (recent N turns)].
 *  3. Heuristic-summarize the middle: first 200 chars of each user message, first 100 chars of
 *     each assistant message (which in this codebase may be tool-result summaries), joined as
 *     bullet points. Truncated to [CompactionConfig.maxSummaryLengthChars].
 *  4. Replace the middle with a single assistant-role message containing the summary.
 *  5. Re-estimate; if still over budget, retry with [CompactionConfig.preserveRecentTurns]
 *     reduced by 2 per iteration (floor 2).
 *  6. If still over with min tail preserved, fall back to aggressive truncation: drop oldest
 *     non-prefix messages one by one until we fit (trigger reason becomes [CompactionTrigger.OverBudget]).
 */
class DefaultContextCompactor(
    private val config: CompactionConfig = CompactionConfig(),
) : ContextCompactor {

    override suspend fun compact(
        messages: List<ChatMessage>,
        tokenBudget: Int,
        estimatedTokens: (List<ChatMessage>) -> Int,
    ): CompactionResult {
        if (messages.isEmpty()) {
            return CompactionResult(
                messages = emptyList(),
                tokensBefore = 0,
                tokensAfter = 0,
                triggerReason = CompactionTrigger.None,
            )
        }
        val tokensBefore = estimatedTokens(messages)
        val thresholdTokens = (tokenBudget * config.compactionThresholdRatio).toInt()

        // Fast path: under threshold.
        if (tokensBefore <= thresholdTokens) {
            return CompactionResult(
                messages = messages,
                compactionCount = 0,
                tokensBefore = tokensBefore,
                tokensAfter = tokensBefore,
                triggerReason = CompactionTrigger.None,
            )
        }

        val initialTrigger = if (tokensBefore > tokenBudget) {
            CompactionTrigger.OverBudget
        } else {
            CompactionTrigger.ApproachingBudget
        }

        // Iterate: progressively shrink the preserved-tail window until we fit or hit the floor.
        var preserveTurns = config.preserveRecentTurns
        val minPreserveTurns = 2
        var candidate: List<ChatMessage> = messages
        var compactedCount = 0
        while (preserveTurns >= minPreserveTurns) {
            val split = splitSections(messages, preserveTurns)
            val (preservedMiddle, summarizableMiddle) = partitionGroundingCritical(split.middle)
            val summary = buildHeuristicSummary(summarizableMiddle)
            candidate = split.prefix.withSummary(summaryMessage(summary)) +
                preservedMiddle +
                split.tail
            compactedCount = summarizableMiddle.size
            val tokensAfter = estimatedTokens(candidate)
            if (tokensAfter <= thresholdTokens) {
                return CompactionResult(
                    messages = candidate,
                    compactionCount = compactedCount,
                    tokensBefore = tokensBefore,
                    tokensAfter = tokensAfter,
                    triggerReason = initialTrigger,
                )
            }
            preserveTurns -= 2
        }

        // Still over with min tail preserved: aggressive truncation — drop oldest messages one at
        // a time until we fit. Grounding-critical messages (failed tool results + the latest screen
        // observation) are dropped LAST so the preservation guarantee holds as long as possible: we
        // remove the oldest summarizable message each round, and only start dropping preserved ones
        // once nothing summarizable remains.
        val split = splitSections(messages, minPreserveTurns)
        val (preservedMiddleSet, _) = partitionGroundingCritical(split.middle)
        val preservedIds = preservedMiddleSet.mapTo(HashSet()) { it.id }
        val middleAndTail = ArrayList<ChatMessage>(split.middle.size + split.tail.size)
        middleAndTail.addAll(split.middle)
        middleAndTail.addAll(split.tail)
        var droppedCount = 0

        // Incremental token accounting. Re-estimating the whole list after every single drop makes
        // the fallback O(drops × history size). When the estimator is additive — i.e. the cost of a
        // list equals the sum of its per-message costs (true for every production estimator: remote
        // is sum(est(text)) + n·4, LiteRT is sum(est(text) + overhead)) — we can keep a running total
        // and subtract the dropped message's cost instead. Costs are tracked by list index parallel
        // to [middleAndTail], never by ChatMessage.id, so duplicate ids cannot corrupt accounting.
        val prefixCost = if (split.prefix.isEmpty()) 0 else estimatedTokens(split.prefix)
        val costs = ArrayList<Int>(middleAndTail.size)
        middleAndTail.forEach { costs.add(estimatedTokens(listOf(it))) }
        var runningTotal = prefixCost + costs.sum()
        // Probe additivity against the already-computed whole-list estimate. If they disagree the
        // estimator is non-additive (sequence framing, cross-message tokenization); fall back to
        // exact whole-list re-estimation so behavior is preserved bit-for-bit.
        val additive = runningTotal == tokensBefore
        runningTotal = tokensBefore

        fun tokensAfterCurrent(): Int =
            if (additive) runningTotal else estimatedTokens(split.prefix + middleAndTail)

        // Drop the oldest summarizable (non-grounding-critical) message before the protected tail
        // suffix. Returns false when nothing summarizable is left in range (so the caller stops
        // rather than sacrificing a preserved message).
        fun dropOneSummarizable(protectedFloor: Int): Boolean {
            val limit = middleAndTail.size - protectedFloor
            if (limit <= 0) return false
            val summarizableIndex = (0 until limit).firstOrNull { i -> middleAndTail[i].id !in preservedIds }
                ?: return false
            middleAndTail.removeAt(summarizableIndex)
            runningTotal -= costs.removeAt(summarizableIndex)
            droppedCount += 1
            return true
        }

        // Drop one message, preferring the oldest summarizable one but falling back to the oldest
        // overall (including preserved) so the tail-trim round always converges.
        fun dropOnePreferSummarizable(protectedFloor: Int) {
            val limit = middleAndTail.size - protectedFloor
            if (limit <= 0) return
            val summarizableIndex = (0 until limit).firstOrNull { i -> middleAndTail[i].id !in preservedIds }
            val removeIndex = summarizableIndex ?: 0
            middleAndTail.removeAt(removeIndex)
            runningTotal -= costs.removeAt(removeIndex)
            droppedCount += 1
        }

        // Round 1: keep the recent tail intact; drop summarizable middle messages only. If the
        // only remaining droppable messages are grounding-critical, stop here (do not sacrifice a
        // preserved failure/screen observation just to shrink the middle) and let the tail-trim
        // round below decide.
        while (middleAndTail.size > split.tail.size) {
            if (!dropOneSummarizable(protectedFloor = split.tail.size)) break
            val tokensAfter = tokensAfterCurrent()
            if (tokensAfter <= tokenBudget) {
                return CompactionResult(
                    messages = split.prefix + middleAndTail,
                    compactionCount = droppedCount,
                    tokensBefore = tokensBefore,
                    tokensAfter = tokensAfter,
                    triggerReason = CompactionTrigger.OverBudget,
                )
            }
        }

        // Tail-trim round: drop from the front, still preferring summarizable, but now allow
        // dropping a preserved message as a last resort so we can always converge to one message.
        while (middleAndTail.size > 1) {
            dropOnePreferSummarizable(protectedFloor = 1)
            val tokensAfter = tokensAfterCurrent()
            if (tokensAfter <= tokenBudget) {
                val current = split.prefix + middleAndTail
                return CompactionResult(
                    messages = current,
                    compactionCount = messages.size - current.size,
                    tokensBefore = tokensBefore,
                    tokensAfter = tokensAfter,
                    triggerReason = CompactionTrigger.OverBudget,
                )
            }
        }

        // Degenerate case: return whatever we have (single message) — caller can surface error.
        val finalMessages = split.prefix + middleAndTail
        return CompactionResult(
            messages = finalMessages,
            compactionCount = messages.size - finalMessages.size,
            tokensBefore = tokensBefore,
            tokensAfter = tokensAfterCurrent(),
            triggerReason = CompactionTrigger.OverBudget,
        )
    }

    private data class SectionSplit(
        val prefix: List<ChatMessage>,
        val middle: List<ChatMessage>,
        val tail: List<ChatMessage>,
    )

    /**
     * Split [messages] into (system-prefix, eligible-middle, recent-tail).
     *
     * Since [ChatMessage] currently only exposes User/Assistant roles, the system-prefix is always
     * empty today; the [CompactionConfig.preserveSystemMessages] flag is honored for future
     * compatibility should a System role be added.
     */
    private fun splitSections(
        messages: List<ChatMessage>,
        preserveTurns: Int,
    ): SectionSplit {
        val prefixEnd = if (config.preserveSystemMessages) countLeadingSystemMessages(messages) else 0
        val prefix = messages.subList(0, prefixEnd)
        val afterPrefix = messages.subList(prefixEnd, messages.size)

        val tailSize = (preserveTurns * 2).coerceAtMost(afterPrefix.size)
        val tailStart = afterPrefix.size - tailSize
        val middle = afterPrefix.subList(0, tailStart)
        val tail = afterPrefix.subList(tailStart, afterPrefix.size)

        return SectionSplit(
            prefix = prefix.toList(),
            middle = middle.toList(),
            tail = tail.toList(),
        )
    }

    /**
     * Count leading messages that should be treated as a system prefix.
     *
     * The current [MessageRole] enum does not include a System value (system prompts are supplied
     * via ConversationConfig.systemInstruction rather than inline in the history). This method
     * therefore returns 0 today. If a System role is added in the future, update this to scan
     * for leading System-role messages.
     */
    private fun countLeadingSystemMessages(messages: List<ChatMessage>): Int {
        var i = 0
        while (i < messages.size && messages[i].role.isSystemRole()) {
            i += 1
        }
        return i
    }

    private fun MessageRole.isSystemRole(): Boolean =
        // Reserved for future System role; currently no MessageRole value qualifies.
        false

    /**
     * Split the summarizable middle into (grounding-critical messages kept verbatim,
     * everything-else to be summarized), preserving original order in each list.
     *
     * Grounding-critical = any failed/blocked tool result (the model needs the exact failure to
     * recover) plus the single most-recent screen observation (the model taps based on current
     * screen text). Older screen observations are still summarized to reclaim tokens.
     */
    private fun partitionGroundingCritical(
        middle: List<ChatMessage>,
    ): Pair<List<ChatMessage>, List<ChatMessage>> {
        if (middle.isEmpty()) return emptyList<ChatMessage>() to emptyList()
        val latestScreenObservationId = middle
            .lastOrNull { it.kind == MessageKind.ScreenObservation }
            ?.id
        val preserved = ArrayList<ChatMessage>()
        val summarizable = ArrayList<ChatMessage>()
        for (msg in middle) {
            val keepVerbatim = when (msg.kind) {
                MessageKind.ToolFailure -> true
                MessageKind.ScreenObservation -> msg.id == latestScreenObservationId
                else -> false
            }
            if (keepVerbatim) preserved.add(msg) else summarizable.add(msg)
        }
        return preserved to summarizable
    }

    /**
     * Heuristic Wave-2 summarizer: extract bullet points from user queries and assistant
     * (possibly tool-result) messages in the middle section.
     *
     * - User messages: first 200 chars of content.
     * - Assistant chat messages: first 100 chars.
     * - Tool-result / screen-observation messages: first 100 chars, kept as compact bullets so a
     *   summarized older tool result still records that it happened.
     * - Joined with "• " bullet markers, newline-separated.
     * - Truncated to [CompactionConfig.maxSummaryLengthChars] with ellipsis if needed.
     *
     * Intentionally NOT model-based — Wave 2 only establishes the seam. Future waves can replace
     * this by overriding or passing a strategy.
     */
    private fun buildHeuristicSummary(middle: List<ChatMessage>): String {
        if (middle.isEmpty()) return ""
        val sb = StringBuilder()
        for (msg in middle) {
            val snippet = when (msg.role) {
                MessageRole.User -> msg.text.trim().take(USER_SNIPPET_CHARS)
                MessageRole.Assistant -> msg.text.trim().take(ASSISTANT_SNIPPET_CHARS)
            }
            if (snippet.isBlank()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("• ").append(snippet.replace('\n', ' '))
        }
        var summary = sb.toString()
        if (summary.length > config.maxSummaryLengthChars) {
            summary = summary.take(config.maxSummaryLengthChars - ELLIPSIS.length) + ELLIPSIS
        }
        return summary
    }

    private fun summaryMessage(summary: String): ChatMessage? {
        if (summary.isBlank()) return null
        val text = config.summaryMessageTemplate.format(summary)
        return ChatMessage(
            role = MessageRole.Assistant,
            text = text,
        )
    }

    private fun List<ChatMessage>.withSummary(summary: ChatMessage?): List<ChatMessage> =
        if (summary == null) this else buildList {
            addAll(this@withSummary)
            add(summary)
        }

    companion object {
        private const val USER_SNIPPET_CHARS = 200
        private const val ASSISTANT_SNIPPET_CHARS = 100
        private const val ELLIPSIS = "…"
    }
}

/**
 * No-op [ContextCompactor] that returns the input messages unchanged. Used as the default so
 * callers that don't configure compaction retain prior behavior (history is passed through
 * verbatim, and overflow will raise the model's native error).
 */
object NoOpContextCompactor : ContextCompactor {
    override suspend fun compact(
        messages: List<ChatMessage>,
        tokenBudget: Int,
        estimatedTokens: (List<ChatMessage>) -> Int,
    ): CompactionResult {
        val tokens = estimatedTokens(messages)
        return CompactionResult(
            messages = messages,
            compactionCount = 0,
            tokensBefore = tokens,
            tokensAfter = tokens,
            triggerReason = CompactionTrigger.None,
        )
    }
}
