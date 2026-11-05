package com.bytedance.zgx.solin.memory

import com.bytedance.zgx.solin.common.sha256Hex
import java.util.Locale

/*
 * Memory text analysis: tokenization, search aliases, stop words and stable hashing. Split out of
 * MemoryRepository.kt; behaviour unchanged.
 */

internal fun deterministicMemoryRecordId(prefix: String, key: String): String =
    "$prefix-${sha256Hex(key).take(16)}"

/**
 * Whitespace-collapsing pattern shared by the memory normalizers.
 *
 * Hoisted to file level because these normalizers run per memory record on index rebuild and per
 * query; compiling an identical pattern on every call was pure allocation waste. Patterns unchanged.
 */
internal val WHITESPACE_RUN_REGEX = Regex("""\s+""")

/** Token separator used by [tokenize]; hoisted for the same reason as [WHITESPACE_RUN_REGEX]. */
private val TOKEN_SEPARATOR_REGEX = Regex("""[^\p{L}\p{N}]+""")

/** Unsafe-id sanitizer pattern; hoisted for the same reason. */
internal val UNSAFE_ID_CHARS_REGEX = Regex("""[^A-Za-z0-9_.:-]+""")

internal fun semanticSourceHash(text: String): String =
    sha256Hex(text.trim().replace(WHITESPACE_RUN_REGEX, " "))

internal fun memoryRecordTextHash(text: String): String =
    semanticSourceHash(text)

internal fun searchTextFor(type: MemoryRecordType, text: String): String {
    val aliases = memorySearchAliases(type, text)
    return if (aliases.isEmpty()) {
        text
    } else {
        "$text\n${aliases.joinToString(separator = " ")}"
    }
}

private fun memorySearchAliases(type: MemoryRecordType, text: String): Set<String> =
    when (type) {
        MemoryRecordType.Preference -> preferenceSearchAliases(text) + placeSearchAliases(text)
        MemoryRecordType.UserFact -> placeSearchAliases(text)
        MemoryRecordType.TaskState -> taskStateSearchAliases(text)
        MemoryRecordType.Conversation,
        MemoryRecordType.SuppressedTaskState -> emptySet()
    }

/**
 * Local aliases for explicit home / work location records so "我家住哪" or "导航回家" recall
 * "我家在…" and "公司在哪" recalls "我的公司地址是…". Aliases only extend the search text; the stored
 * text and the context sent to the local model are unchanged.
 */
private fun placeSearchAliases(text: String): Set<String> {
    val normalized = text.lowercase(Locale.ROOT)
    return buildSet {
        if (normalized.containsAny(HOME_PLACE_TERMS)) addAll(HOME_PLACE_ALIASES)
        if (normalized.containsAny(WORK_PLACE_TERMS)) addAll(WORK_PLACE_ALIASES)
    }
}

internal val HOME_PLACE_TERMS = setOf("家在", "住在", "家住", "家的地址", "家庭住址", "家庭地址", "住址", "my home", "home address", "i live in")
private val HOME_PLACE_ALIASES = setOf("住址", "家住", "回家", "home", "live")
internal val WORK_PLACE_TERMS = setOf("公司在", "公司地址", "单位在", "单位地址", "办公室在", "上班地点", "my office", "office address", "i work at")
private val WORK_PLACE_ALIASES = setOf("公司", "单位", "work", "office")

private fun preferenceSearchAliases(text: String): Set<String> {
    val normalized = text
        .trim()
        .removePrefix("用户偏好：")
        .trim()
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE_RUN_REGEX, " ")
    if (normalized.isBlank() || !normalized.containsAny(RESPONSE_PREFERENCE_TERMS)) {
        return emptySet()
    }
    return buildSet {
        if (normalized.containsAny(RESPONSE_CONCISE_TERMS)) addAll(RESPONSE_CONCISE_ALIASES)
        if (normalized.containsAny(RESPONSE_DETAILED_TERMS)) addAll(RESPONSE_DETAILED_ALIASES)
        if (normalized.containsAny(RESPONSE_CHINESE_TERMS)) addAll(RESPONSE_CHINESE_ALIASES)
        if (normalized.containsAny(RESPONSE_ENGLISH_TERMS)) addAll(RESPONSE_ENGLISH_ALIASES)
    }
}

private fun taskStateSearchAliases(text: String): Set<String> {
    val normalized = text.lowercase(Locale.ROOT)
    if (!normalized.hasStructuredTaskStateMemoryShape()) return emptySet()
    return buildSet {
        addAll(TASK_STATE_ALIASES)
        if ("reminder" in normalized) addAll(TASK_STATE_REMINDER_ALIASES)
        if ("periodiccheck" in normalized || "periodic-check" in normalized) {
            addAll(TASK_STATE_PERIODIC_CHECK_ALIASES)
        }
        if ("状态=scheduled" in normalized) addAll(TASK_STATE_SCHEDULED_ALIASES)
        if ("状态=running" in normalized) addAll(TASK_STATE_RUNNING_ALIASES)
    }
}

private fun String.hasStructuredTaskStateMemoryShape(): Boolean =
    startsWith("任务状态：") &&
        "后台任务=" in this &&
        "任务记录=" in this &&
        "状态=" in this &&
        "触发时间=" in this

/**
 * A lexical memory token. [strong] tokens (Latin words, CJK bigrams) can on their own justify a
 * lexical hit; weak tokens (single content CJK characters) only add recall for short queries and
 * need corroboration (see [MemoryLexicalMatch]).
 */
internal data class MemoryToken(val text: String, val strong: Boolean) {
    val weight: Float
        get() = if (strong) STRONG_TOKEN_WEIGHT else WEAK_TOKEN_WEIGHT
}

internal fun tokenize(text: String): List<String> =
    memoryTokens(text).map(MemoryToken::text)

/**
 * Tokenizes [text] for lexical memory recall.
 *
 * - Latin/digit words of length >= 2 that are not stop words (strong).
 * - CJK bigrams built only inside contiguous CJK runs (punctuation and Latin break a run), dropping
 *   bigrams that contain a grammatical particle, consist only of stop characters, or are generic
 *   request words such as "帮我" (strong).
 * - CJK unigrams that are not stop characters, so single-character queries ("茶") still recall
 *   (weak).
 */
internal fun memoryTokens(text: String): List<MemoryToken> {
    val normalized = text.lowercase(Locale.ROOT)
    val tokens = mutableListOf<MemoryToken>()
    normalized.split(TOKEN_SEPARATOR_REGEX).forEach { segment ->
        if (segment.isEmpty()) return@forEach
        var runStart = 0
        while (runStart < segment.length) {
            val cjk = segment[runStart].isCjk()
            var runEnd = runStart
            while (runEnd < segment.length && segment[runEnd].isCjk() == cjk) runEnd++
            val run = segment.substring(runStart, runEnd)
            if (cjk) {
                addCjkRunTokens(run, tokens)
            } else if (run.length >= 2 && run !in LATIN_STOP_WORDS) {
                tokens += MemoryToken(run, strong = true)
            }
            runStart = runEnd
        }
    }
    return tokens
}

private fun addCjkRunTokens(run: String, tokens: MutableList<MemoryToken>) {
    // Characters of a stop bigram ("推荐", "帮我") are stop words too, not weak unigrams.
    val stopBigramPositions = BooleanArray(run.length)
    for (index in 0 until run.length - 1) {
        if (run.substring(index, index + 2) in CJK_STOP_BIGRAMS) {
            stopBigramPositions[index] = true
            stopBigramPositions[index + 1] = true
        }
    }
    run.forEachIndexed { index, char ->
        if (char !in CJK_STOP_CHARS && !stopBigramPositions[index]) {
            tokens += MemoryToken(char.toString(), strong = false)
        }
    }
    for (index in 0 until run.length - 1) {
        val left = run[index]
        val right = run[index + 1]
        if (left in CJK_PARTICLE_CHARS || right in CJK_PARTICLE_CHARS) continue
        if (left in CJK_STOP_CHARS && right in CJK_STOP_CHARS) continue
        val bigram = run.substring(index, index + 2)
        if (bigram in CJK_STOP_BIGRAMS) continue
        tokens += MemoryToken(bigram, strong = true)
    }
}

private fun Char.isCjk(): Boolean =
    this in '\u4E00'..'\u9FFF' ||
        this in '\u3400'..'\u4DBF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\uAC00'..'\uD7AF'

internal const val STRONG_TOKEN_WEIGHT = 1f
internal const val WEAK_TOKEN_WEIGHT = 0.5f

/**
 * Grammatical particles, question words, personal pronouns and "一": any CJK bigram containing one
 * is dropped ("我老" would otherwise link "我老家" to "我老婆").
 */
private val CJK_PARTICLE_CHARS: Set<Char> = "的了吗呢吧啊呀哦嘛么什怎哪是个这那些一我你您他她它".toSet()

/** High-frequency function characters: never unigram tokens; bigrams made only of them are dropped. */
private val CJK_STOP_CHARS: Set<Char> = CJK_PARTICLE_CHARS +
    "们在和与及也都就还很有没不给帮请把被要会能想用对让去来说几又再将从".toSet()

/** Generic request / time words that carry no memory topic on their own. */
private val CJK_STOP_BIGRAMS = setOf(
    "帮我",
    "给我",
    "帮忙",
    "一下",
    "请问",
    "告诉",
    "推荐",
    "现在",
    "今天",
    "明天",
    "时候",
    "可以",
    "知道",
    "一点",
    "用户",
)

private val LATIN_STOP_WORDS = setOf(
    "a",
    "about",
    "am",
    "an",
    "and",
    "are",
    "at",
    "be",
    "can",
    "could",
    "did",
    "do",
    "does",
    "for",
    "from",
    "have",
    "how",
    "in",
    "is",
    "it",
    "me",
    "my",
    "of",
    "ok",
    "on",
    "only",
    "or",
    "please",
    "remember",
    "reply",
    "that",
    "the",
    "this",
    "to",
    "was",
    "were",
    "what",
    "when",
    "where",
    "which",
    "who",
    "will",
    "with",
    "would",
    "you",
    "your",
)

/**
 * Lexical match between a query and one memory entry.
 *
 * [coverage] is the fraction of the query's distinct token weight found in the entry.
 * [qualifies] encodes the overlap gate: at least one strong token in common, or at least two weak
 * tokens, or the single weak token of a genuinely short query such as "茶". A long query that only
 * reduces to one content character after stop-word removal ("现在几点了" -> "点") does not qualify.
 */
internal class MemoryLexicalMatch(
    val coverage: Float,
    val strongOverlap: Int,
    val weakOverlap: Int,
    singleWeakTokenQuery: Boolean,
) {
    val qualifies: Boolean =
        strongOverlap > 0 || weakOverlap >= 2 || (singleWeakTokenQuery && weakOverlap == 1)
}

/**
 * Exact sparse term vector (token -> summed weight). Lexical similarity uses this instead of a
 * fixed-width hashed vector so unrelated tokens never collide into the same bucket.
 */
internal class MemoryTermVector private constructor(
    val weights: Map<String, Float>,
) {
    private val norm: Float = kotlin.math.sqrt(weights.values.sumOf { (it * it).toDouble() }).toFloat()

    val tokens: Set<String> get() = weights.keys

    fun cosine(other: MemoryTermVector): Float {
        if (norm == 0f || other.norm == 0f) return 0f
        val (small, large) = if (weights.size <= other.weights.size) this to other else other to this
        var dot = 0f
        small.weights.forEach { (token, weight) ->
            large.weights[token]?.let { otherWeight -> dot += weight * otherWeight }
        }
        return dot / (norm * other.norm)
    }

    companion object {
        fun of(text: String): MemoryTermVector {
            val weights = linkedMapOf<String, Float>()
            memoryTokens(text).forEach { token ->
                weights[token.text] = (weights[token.text] ?: 0f) + token.weight
            }
            return MemoryTermVector(weights)
        }
    }
}

internal class MemoryQueryTokens(text: String) {
    private val distinct: Map<String, MemoryToken> = memoryTokens(text).associateBy(MemoryToken::text)
    private val totalWeight: Float = distinct.values.sumOf { it.weight.toDouble() }.toFloat()
    private val singleWeakTokenQuery: Boolean =
        distinct.size == 1 &&
            distinct.values.none(MemoryToken::strong) &&
            text.count(Char::isLetterOrDigit) <= SHORT_QUERY_MAX_CHARS
    val vector: MemoryTermVector = MemoryTermVector.of(text)

    val isEmpty: Boolean get() = distinct.isEmpty()

    fun match(entryTokens: Set<String>): MemoryLexicalMatch {
        var matchedWeight = 0f
        var strong = 0
        var weak = 0
        distinct.values.forEach { token ->
            if (token.text in entryTokens) {
                matchedWeight += token.weight
                if (token.strong) strong++ else weak++
            }
        }
        return MemoryLexicalMatch(
            coverage = if (totalWeight > 0f) matchedWeight / totalWeight else 0f,
            strongOverlap = strong,
            weakOverlap = weak,
            singleWeakTokenQuery = singleWeakTokenQuery,
        )
    }
}

/** A one-content-character query may match on that character alone only if it is this short. */
private const val SHORT_QUERY_MAX_CHARS = 3

/** Record-label prefixes added at storage time; they are bookkeeping, not searchable content. */
private val MEMORY_RECORD_LABEL_PREFIXES = listOf("用户偏好：", "用户事实：", "用户：", "助手：")

internal fun withoutMemoryRecordLabel(text: String): String {
    val prefix = MEMORY_RECORD_LABEL_PREFIXES.firstOrNull { text.startsWith(it) } ?: return text
    return text.removePrefix(prefix)
}

internal fun String.containsAny(terms: Set<String>): Boolean =
    terms.any { term -> containsTerm(term) }

private fun String.containsTerm(term: String): Boolean =
    if (term.all { char -> char.isAsciiLetterOrDigit() }) {
        asciiTermRegex(term).containsMatchIn(this)
    } else {
        contains(term)
    }

/** Word-bounded regex per ASCII term; the term sets are small constants, so the cache stays bounded. */
private val asciiTermRegexes = java.util.concurrent.ConcurrentHashMap<String, Regex>()

private fun asciiTermRegex(term: String): Regex =
    asciiTermRegexes.getOrPut(term) { Regex("""(?<![a-z0-9])${Regex.escape(term)}(?![a-z0-9])""") }

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in '0'..'9'

internal val RESPONSE_PREFERENCE_TERMS = setOf(
    "answer",
    "answers",
    "reply",
    "replies",
    "respond",
    "response",
    "responses",
    "答案",
    "回答",
    "回复",
)

internal val RESPONSE_LENGTH_TERMS = setOf(
    "brief",
    "concise",
    "detailed",
    "length",
    "long",
    "short",
    "succinct",
    "terse",
    "verbose",
    "展开",
    "长度",
    "长短",
    "简洁",
    "简短",
    "精炼",
    "详细",
    "详尽",
)

private val RESPONSE_CONCISE_TERMS = setOf(
    "brief",
    "concise",
    "short",
    "succinct",
    "terse",
    "简洁",
    "简短",
    "精炼",
)

private val RESPONSE_DETAILED_TERMS = setOf(
    "detailed",
    "expanded",
    "long",
    "verbose",
    "elaborate",
    "full",
    "thorough",
    "展开",
    "详细",
    "详尽",
)

internal val RESPONSE_LANGUAGE_TERMS = setOf(
    "chinese",
    "english",
    "language",
    "mandarin",
    "中文",
    "汉语",
    "英文",
    "英语",
    "语言",
    "语种",
)

internal val PREFERENCE_CONTROL_TERMS = setOf(
    "preference",
    "preferences",
    "setting",
    "settings",
    "偏好",
    "设置",
)

private val RESPONSE_CHINESE_TERMS = setOf(
    "chinese",
    "mandarin",
    "普通话",
    "中文",
    "汉语",
)

private val RESPONSE_ENGLISH_TERMS = setOf(
    "english",
    "英文",
    "英语",
)

private val RESPONSE_CONCISE_ALIASES = setOf(
    "brief",
    "concise",
    "short",
    "succinct",
    "terse",
    "简洁",
    "简短",
    "精炼",
)

private val RESPONSE_DETAILED_ALIASES = setOf(
    "detailed",
    "expanded",
    "long",
    "verbose",
    "elaborate",
    "thorough",
    "展开",
    "详细",
    "详尽",
)

private val RESPONSE_CHINESE_ALIASES = setOf(
    "chinese",
    "mandarin",
    "普通话",
    "中文",
    "汉语",
)

private val RESPONSE_ENGLISH_ALIASES = setOf(
    "english",
    "英文",
    "英语",
)

private val TASK_STATE_ALIASES = setOf(
    "taskstate",
    "待办事项",
    "任务状态",
)

private val TASK_STATE_REMINDER_ALIASES = setOf(
    "reminder",
    "reminders",
    "alarm",
    "alert",
    "提醒",
    "提醒事项",
    "后台提醒",
    "本地提醒",
    "闹钟",
)

private val TASK_STATE_PERIODIC_CHECK_ALIASES = setOf(
    "periodic",
    "periodiccheck",
    "patrol",
    "周期检查",
    "定期检查",
    "后台检查",
    "本地巡检",
)

private val TASK_STATE_SCHEDULED_ALIASES = setOf(
    "scheduled",
    "pending",
    "queued",
    "upcoming",
    "已安排",
    "待执行",
    "已计划",
    "待触发",
)

private val TASK_STATE_RUNNING_ALIASES = setOf(
    "running",
    "executing",
    "inprogress",
    "运行中",
    "进行中",
    "执行中",
)

internal val TASK_STATE_TERMINAL_QUERY_TERMS = setOf(
    "cancelled",
    "canceled",
    "deleted",
    "delivered",
    "done",
    "failed",
    "finished",
    "取消",
    "已取消",
    "删除",
    "已删除",
    "失败",
    "已失败",
    "完成",
    "已完成",
    "送达",
    "已送达",
)
