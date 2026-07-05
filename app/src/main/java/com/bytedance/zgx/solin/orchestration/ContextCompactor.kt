package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
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

    fun compactImages(messages: List<ChatMessage>): List<ChatMessage> = messages
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
            val summary = buildHeuristicSummary(split.middle)
            candidate = split.prefix.withSummary(summaryMessage(summary)) +
                split.tail
            compactedCount = split.middle.size
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

        // Still over with min tail preserved: aggressive truncation — drop oldest non-prefix
        // messages one at a time until we fit.
        val split = splitSections(messages, minPreserveTurns)
        val middleAndTail = ArrayList<ChatMessage>(split.middle.size + split.tail.size)
        middleAndTail.addAll(split.middle)
        middleAndTail.addAll(split.tail)
        var droppedCount = 0
        while (middleAndTail.size > split.tail.size) {
            middleAndTail.removeAt(0)
            droppedCount += 1
            val current = split.prefix + middleAndTail
            val tokensAfter = estimatedTokens(current)
            if (tokensAfter <= tokenBudget) {
                return CompactionResult(
                    messages = current,
                    compactionCount = split.middle.size + droppedCount,
                    tokensBefore = tokensBefore,
                    tokensAfter = tokensAfter,
                    triggerReason = CompactionTrigger.OverBudget,
                )
            }
        }

        // Even the tail alone won't fit: trim from the head of the tail until under budget.
        while (middleAndTail.size > 1) {
            middleAndTail.removeAt(0)
            droppedCount += 1
            val current = split.prefix + middleAndTail
            val tokensAfter = estimatedTokens(current)
            if (tokensAfter <= tokenBudget) {
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
            tokensAfter = estimatedTokens(finalMessages),
            triggerReason = CompactionTrigger.OverBudget,
        )
    }

    override fun compactImages(messages: List<ChatMessage>): List<ChatMessage> =
        stripEarlierScreenshotReferences(messages)

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
     * Heuristic Wave-2 summarizer: extract bullet points from user queries and assistant
     * (possibly tool-result) messages in the middle section.
     *
     * - User messages: first 200 chars of content.
     * - Assistant messages: first 100 chars (these often carry tool-result summaries).
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

fun stripEarlierScreenshotReferences(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.size <= 1) return messages
    val screenshotPattern = Regex("\\[(?:screenshot|screen_capture|屏幕截图)[^\\]]*\\]", RegexOption.IGNORE_CASE)
    val indicesWithScreenshots = messages.mapIndexedNotNull { i, msg ->
        if (screenshotPattern.containsMatchIn(msg.text)) i else null
    }
    if (indicesWithScreenshots.size <= 1) return messages
    val toStrip = indicesWithScreenshots.dropLast(1).toSet()
    return messages.mapIndexed { i, msg ->
        if (i in toStrip) msg.copy(text = screenshotPattern.replace(msg.text, "[早期截图已省略]"))
        else msg
    }
}
