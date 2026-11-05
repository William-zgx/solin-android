package com.bytedance.zgx.solin.orchestration

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageKind
import com.bytedance.zgx.solin.MessagePrivacy
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
 * User-text invariant: no compaction path truncates or drops user-authored text (the original
 * request, steer/queue messages, answers to ask_user — every [MessageRole.User] message). Only
 * assistant text, tool observations and summaries are shortened or dropped. When the user messages
 * alone exceed the budget, the first and the most recent user message are kept in full and every
 * user message in between is replaced by one explicit line, `[较早的用户消息已省略：N 条，共 M 字]`
 * (N messages, M characters); no message is ever cut mid-way. After every compaction
 * [restoreMissingUserMessages] re-inserts, at its original position, any user message that went
 * missing without being covered by that line. The result may then exceed the budget; the caller
 * surfaces the overflow instead of losing a user constraint.
 *
 * Strategy:
 *  1. If under threshold, return unchanged.
 *  2. Split history into [middle (eligible) | tail (recent N turns)].
 *  3. Keep middle user messages and grounding-critical observations verbatim; heuristic-summarize
 *     the remaining middle messages (first 100 chars of each assistant / tool-result message),
 *     joined as bullet points and truncated to [CompactionConfig.maxSummaryLengthChars].
 *  4. Put a single assistant-role summary message before the kept middle messages.
 *  5. Re-estimate; if still over threshold, retry with [CompactionConfig.preserveRecentTurns]
 *     reduced by 2 per iteration (floor 2).
 *  6. Otherwise fall back to dropping (trigger reason becomes [CompactionTrigger.OverBudget]), oldest
 *     first, until the history fits: summarizable middle messages, then summarizable tail messages,
 *     then — only if user text alone is over budget — the in-between user messages are replaced by
 *     the omission line, and last the grounding-critical observations.
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
        while (preserveTurns >= MIN_PRESERVE_TURNS) {
            val split = splitSections(messages, preserveTurns)
            val (preservedMiddle, summarizableMiddle) = partitionGroundingCritical(split.middle)
            val summary = buildHeuristicSummary(summarizableMiddle)
            val candidate = listOfNotNull(summaryMessage(summary, summarizableMiddle)) +
                preservedMiddle +
                split.tail
            val tokensAfter = estimatedTokens(candidate)
            if (tokensAfter <= thresholdTokens) {
                return finish(messages, candidate, emptySet(), initialTrigger, tokensBefore, tokensAfter, estimatedTokens)
            }
            preserveTurns -= 2
        }
        return dropUntilFits(messages, tokenBudget, tokensBefore, estimatedTokens)
    }

    private enum class SlotKind { User, Critical, Summarizable, OmissionLine }

    /** One message of the fallback working list; [index] points into the original history (-1 if synthetic). */
    private class Slot(val index: Int, val message: ChatMessage, val kind: SlotKind, val inTail: Boolean, val cost: Int)

    /**
     * Fallback when summarizing cannot reach the threshold: drop non-user messages oldest first,
     * collapse in-between user messages only when user text alone is over budget, and keep
     * grounding-critical messages (failed tool results + the latest screen observation) as long as
     * possible. User messages are never dropped one by one.
     */
    private fun dropUntilFits(
        messages: List<ChatMessage>,
        tokenBudget: Int,
        tokensBefore: Int,
        estimatedTokens: (List<ChatMessage>) -> Int,
    ): CompactionResult {
        val split = splitSections(messages, MIN_PRESERVE_TURNS)
        val middleKinds = slotKinds(split.middle)
        val slots = ArrayList<Slot>(split.middle.size + split.tail.size + 1)
        split.middle.forEachIndexed { i, message ->
            slots += Slot(i, message, middleKinds[i], inTail = false, estimatedTokens(listOf(message)))
        }
        split.tail.forEachIndexed { i, message ->
            val kind = if (message.role == MessageRole.User) SlotKind.User else SlotKind.Summarizable
            slots += Slot(split.middle.size + i, message, kind, inTail = true, estimatedTokens(listOf(message)))
        }

        // Incremental token accounting. Re-estimating the whole list after every single drop makes
        // the fallback O(drops × history size). When the estimator is additive — i.e. the cost of a
        // list equals the sum of its per-message costs (true for every production estimator: remote
        // is sum(est(text)) + n·4, LiteRT is sum(est(text) + overhead)) — we keep a running total
        // and subtract the dropped message's cost instead. Costs live on the slots, never keyed by
        // ChatMessage.id, so duplicate ids cannot corrupt accounting. If the probe below disagrees
        // with the whole-list estimate the estimator is non-additive (sequence framing, cross-message
        // tokenization) and every check re-estimates the whole list exactly.
        val additive = slots.sumOf { it.cost } == tokensBefore
        var runningTotal = tokensBefore
        var omittedIndices = emptySet<Int>()

        fun current(): List<ChatMessage> = slots.map { it.message }
        fun tokensAfterCurrent(): Int = if (additive) runningTotal else estimatedTokens(current())
        fun result(): CompactionResult =
            finish(messages, current(), omittedIndices, CompactionTrigger.OverBudget, tokensBefore, tokensAfterCurrent(), estimatedTokens)

        // Drop matching slots oldest first, always keeping one message; true once the history fits.
        fun dropWhileOver(predicate: (Slot) -> Boolean): Boolean {
            while (slots.size > 1) {
                val slot = slots.firstOrNull(predicate) ?: return false
                slots.remove(slot)
                runningTotal -= slot.cost
                if (tokensAfterCurrent() <= tokenBudget) return true
            }
            return false
        }

        if (dropWhileOver { !it.inTail && it.kind == SlotKind.Summarizable }) return result()
        if (dropWhileOver { it.kind == SlotKind.Summarizable }) return result()

        val userSlots = slots.filter { it.kind == SlotKind.User }
        val userTextAlone = if (additive) {
            userSlots.sumOf { it.cost }
        } else {
            estimatedTokens(userSlots.map { it.message })
        }
        if (userTextAlone > tokenBudget && userSlots.size >= 3) {
            val inBetween = userSlots.subList(1, userSlots.size - 1)
            val at = slots.indexOf(inBetween.first())
            inBetween.forEach { slot ->
                slots.remove(slot)
                runningTotal -= slot.cost
            }
            val line = omissionLineMessage(inBetween.map { it.message })
            val lineSlot = Slot(-1, line, SlotKind.OmissionLine, inTail = false, estimatedTokens(listOf(line)))
            slots.add(at, lineSlot)
            runningTotal += lineSlot.cost
            omittedIndices = inBetween.mapTo(HashSet()) { it.index }
            if (tokensAfterCurrent() <= tokenBudget) return result()
        }

        dropWhileOver { it.kind == SlotKind.Critical }
        // Fits, or only user text (plus at most the omission line) is left — the caller surfaces
        // the overflow; user text is never cut to make it fit.
        return result()
    }

    /** Runs the user-text recovery check on every compacted result and recounts if it changed anything. */
    private fun finish(
        original: List<ChatMessage>,
        compacted: List<ChatMessage>,
        omittedIndices: Set<Int>,
        trigger: CompactionTrigger,
        tokensBefore: Int,
        tokensAfter: Int,
        estimatedTokens: (List<ChatMessage>) -> Int,
    ): CompactionResult {
        val restored = restoreMissingUserMessages(original, compacted, omittedIndices)
        return CompactionResult(
            messages = restored,
            compactionCount = countMissingOriginals(original, restored),
            tokensBefore = tokensBefore,
            tokensAfter = if (restored === compacted) tokensAfter else estimatedTokens(restored),
            triggerReason = trigger,
        )
    }

    private data class SectionSplit(
        val middle: List<ChatMessage>,
        val tail: List<ChatMessage>,
    )

    /**
     * Split [messages] into (eligible-middle, recent-tail). There is no system prefix: system
     * prompts are supplied via ConversationConfig.systemInstruction, never inline in the history.
     */
    private fun splitSections(
        messages: List<ChatMessage>,
        preserveTurns: Int,
    ): SectionSplit {
        val tailStart = messages.size - (preserveTurns * 2).coerceAtMost(messages.size)
        return SectionSplit(
            middle = messages.subList(0, tailStart).toList(),
            tail = messages.subList(tailStart, messages.size).toList(),
        )
    }

    /**
     * Split the summarizable middle into (messages kept verbatim, everything-else to be
     * summarized), preserving original order in each list.
     *
     * Kept verbatim: every user message (user text is never shortened), any failed/blocked tool
     * result (the model needs the exact failure to recover) and the single most-recent screen
     * observation (the model taps based on current screen text). Older screen observations are
     * still summarized to reclaim tokens.
     */
    private fun partitionGroundingCritical(
        middle: List<ChatMessage>,
    ): Pair<List<ChatMessage>, List<ChatMessage>> {
        if (middle.isEmpty()) return emptyList<ChatMessage>() to emptyList()
        val kinds = slotKinds(middle)
        val preserved = ArrayList<ChatMessage>()
        val summarizable = ArrayList<ChatMessage>()
        middle.forEachIndexed { i, msg ->
            if (kinds[i] == SlotKind.Summarizable) summarizable.add(msg) else preserved.add(msg)
        }
        return preserved to summarizable
    }

    private fun slotKinds(middle: List<ChatMessage>): List<SlotKind> {
        val latestScreenObservationIndex = middle.indexOfLast { it.kind == MessageKind.ScreenObservation }
        return middle.mapIndexed { i, msg ->
            when {
                msg.role == MessageRole.User -> SlotKind.User
                msg.kind == MessageKind.ToolFailure -> SlotKind.Critical
                msg.kind == MessageKind.ScreenObservation && i == latestScreenObservationIndex -> SlotKind.Critical
                else -> SlotKind.Summarizable
            }
        }
    }

    /**
     * Heuristic Wave-2 summarizer: extract bullet points from the assistant (possibly tool-result
     * or screen-observation) messages in the middle section. User messages never reach it; they
     * stay verbatim.
     *
     * - First 100 chars of each message, so a summarized older tool result still records that it
     *   happened.
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
            if (msg.role == MessageRole.User) continue
            val snippet = msg.text.trim().take(ASSISTANT_SNIPPET_CHARS)
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

    /** The summary quotes its sources, so it is LocalOnly when any summarized message is (fail-closed). */
    private fun summaryMessage(summary: String, sources: List<ChatMessage>): ChatMessage? {
        if (summary.isBlank()) return null
        val text = config.summaryMessageTemplate.format(summary)
        return ChatMessage(
            role = MessageRole.Assistant,
            text = text,
            privacy = sources.strictestPrivacy(),
        )
    }

    companion object {
        private const val MIN_PRESERVE_TURNS = 2
        private const val ASSISTANT_SNIPPET_CHARS = 100
        private const val ELLIPSIS = "…"
    }
}

private val omissionLinePattern = Regex("""^\[较早的用户消息已省略：(\d+) 条，共 (\d+) 字]$""")

/** The explicit line that replaces in-between user messages when user text alone is over budget. */
internal fun omissionLineText(omittedCount: Int, omittedChars: Int): String =
    "[较早的用户消息已省略：$omittedCount 条，共 $omittedChars 字]"

/** Number of user messages an omission line stands for, or null when [this] is not one. */
internal fun ChatMessage.omittedUserMessageCount(): Int? =
    if (role != MessageRole.User) null else omissionLinePattern.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()

/** Character count used by the omission line (code points, so an emoji counts once). */
internal fun String.omissionCharCount(): Int = codePointCount(0, length)

private fun omissionLineMessage(omitted: List<ChatMessage>): ChatMessage =
    ChatMessage(
        role = MessageRole.User,
        text = omissionLineText(omitted.size, omitted.sumOf { it.text.omissionCharCount() }),
        privacy = omitted.strictestPrivacy(),
    )

private fun List<ChatMessage>.strictestPrivacy(): MessagePrivacy =
    if (any { it.privacy == MessagePrivacy.LocalOnly }) MessagePrivacy.LocalOnly else MessagePrivacy.RemoteEligible

/**
 * Recovery check run after every compaction: every user message of [original] must appear in the
 * output byte-identical (same [ChatMessage]) or be covered by an omission line. A user message that
 * is missing — or present only as an altered copy — is put back from [original] at its original
 * position (before the first kept message that came after it), never appended blindly.
 *
 * [omittedIndices] are the [original] indices the compactor replaced with an omission line. They are
 * excused only when [compacted] holds omission lines whose counts add up to exactly that many; the
 * first and the most recent user message are never excused. Anything else fails closed: the message
 * is restored. Returns [compacted] itself when nothing was missing.
 */
internal fun restoreMissingUserMessages(
    original: List<ChatMessage>,
    compacted: List<ChatMessage>,
    omittedIndices: Set<Int> = emptySet(),
): List<ChatMessage> {
    val userIndices = original.indices.filter { original[it].role == MessageRole.User }
    if (userIndices.isEmpty()) return compacted
    val pending = HashMap<ChatMessage, ArrayDeque<Int>>()
    original.forEachIndexed { i, message -> pending.getOrPut(message) { ArrayDeque() }.addLast(i) }

    val out = ArrayList<ChatMessage>(compacted.size + 1)
    val origins = ArrayList<Int?>(compacted.size + 1)
    var droppedAlteredCopy = false
    var coveredByLines = 0
    for (message in compacted) {
        val origin = pending[message]?.removeFirstOrNull()
        val lineCount = if (origin == null) message.omittedUserMessageCount() else null
        if (origin == null && lineCount == null && message.role == MessageRole.User) {
            // An altered copy of user text (e.g. truncated); the original is restored below.
            droppedAlteredCopy = true
            continue
        }
        if (lineCount != null) coveredByLines += lineCount
        out += message
        origins += origin
    }

    val anchors = setOf(userIndices.first(), userIndices.last())
    val excused = omittedIndices.takeIf { it.none(anchors::contains) && it.size == coveredByLines }.orEmpty()
    val kept = origins.filterNotNullTo(HashSet())
    val missing = userIndices.filter { it !in kept && it !in excused }
    if (missing.isEmpty() && !droppedAlteredCopy) return compacted
    for (i in missing) {
        val position = origins.indexOfFirst { it != null && it > i }.let { if (it < 0) out.size else it }
        out.add(position, original[i])
        origins.add(position, i)
    }
    return out
}

/** How many messages of [original] no longer appear in [compacted] (multiset, so duplicates count). */
private fun countMissingOriginals(original: List<ChatMessage>, compacted: List<ChatMessage>): Int {
    val present = HashMap<ChatMessage, Int>()
    compacted.forEach { present[it] = (present[it] ?: 0) + 1 }
    return original.count { message ->
        val left = present[message] ?: 0
        if (left > 0) present[message] = left - 1
        left == 0
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
