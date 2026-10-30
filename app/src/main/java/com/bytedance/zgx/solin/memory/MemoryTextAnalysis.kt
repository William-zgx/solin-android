package com.bytedance.zgx.solin.memory

import java.security.MessageDigest
import java.util.Locale

/*
 * Memory text analysis: tokenization, search aliases, stop words and stable hashing. Split out of
 * MemoryRepository.kt; behaviour unchanged.
 */

/** Lowercase hex SHA-256 of [text] encoded as UTF-8. */
internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

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
        MemoryRecordType.Preference -> preferenceSearchAliases(text)
        MemoryRecordType.TaskState -> taskStateSearchAliases(text)
        MemoryRecordType.Conversation,
        MemoryRecordType.UserFact,
        MemoryRecordType.SuppressedTaskState -> emptySet()
    }

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

internal fun tokenize(text: String): List<String> {
    val normalized = text.lowercase()
    val latinTokens = normalized
        .split(TOKEN_SEPARATOR_REGEX)
        .map { it.trim() }
        .filter { it.length >= 2 && it !in LATIN_STOP_WORDS }
    val cjkChars = normalized
        .filter { it.isCjk() }
        .map { it.toString() }
    val cjkBigrams = cjkChars.zipWithNext { left, right -> left + right }
    return latinTokens + cjkChars + cjkBigrams
}

private fun Char.isCjk(): Boolean =
    this in '\u4E00'..'\u9FFF' ||
        this in '\u3400'..'\u4DBF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\uAC00'..'\uD7AF'

private val LATIN_STOP_WORDS = setOf(
    "a",
    "an",
    "and",
    "are",
    "for",
    "in",
    "is",
    "it",
    "my",
    "of",
    "ok",
    "on",
    "only",
    "or",
    "please",
    "remember",
    "reply",
    "the",
    "to",
    "what",
    "you",
    "your",
)

internal fun String.containsAny(terms: Set<String>): Boolean =
    terms.any { term -> containsTerm(term) }

private fun String.containsTerm(term: String): Boolean =
    if (term.all { char -> char.isAsciiLetterOrDigit() }) {
        Regex("""(?<![a-z0-9])${Regex.escape(term)}(?![a-z0-9])""")
            .containsMatchIn(this)
    } else {
        contains(term)
    }

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
