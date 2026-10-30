package com.bytedance.zgx.solin.memory

import java.util.Locale

/*
 * Parsing of explicit user memory commands (remember a preference/fact, forget a preference),
 * their conflict keys and deterministic record ids. Split out of MemoryRepository.kt; behaviour
 * unchanged.
 */

internal fun explicitUserPreferenceFrom(text: String): String? {
    val remembered = explicitRememberPayloadFrom(text) ?: return null
    return remembered
        .takeUnless(::isExplicitUserFactPayload)
        ?.takeIf { it.isNotBlank() }
}

internal fun explicitUserFactFrom(text: String): String? {
    val remembered = explicitRememberPayloadFrom(text) ?: return null
    return remembered.takeIf(::isExplicitUserFactPayload)
}

internal fun explicitUserPreferenceForgetFrom(text: String): String? {
    val trimmed = text.trim()
    val chinesePrefix = Regex("""^(?:请)?(?:忘记|遗忘|删除|移除|不要再记住|别再记住)[:：]?\s*(.+)$""")
    chinesePrefix.find(trimmed)?.groupValues?.getOrNull(1)?.toPreferenceForgetTarget()?.let { preference ->
        if (preference.isNotBlank()) return preference
    }

    val englishPrefix = Regex(
        pattern = """^(?:please\s+)?(?:forget|delete|remove)\s+(?:that\s+)?(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )
    return englishPrefix.find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.toPreferenceForgetTarget()
        ?.takeIf { it.isNotBlank() }
}

internal fun explicitUserPreferenceRecordId(preference: String): String {
    val key = normalizedMemoryText(preference)
    return deterministicMemoryRecordId("preference", key)
}

internal fun explicitUserFactRecordId(fact: String): String {
    val key = userFactKeyFrom(fact) ?: normalizedMemoryText(fact)
    return deterministicMemoryRecordId("user-fact", key)
}

private fun String.toPreferenceForgetTarget(): String =
    trim()
        .removePrefix("用户偏好：")
        .removePrefix("用户事实：")
        .removePrefix("user preference:")
        .removePrefix("user fact:")
        .trim()

private fun explicitRememberPayloadFrom(text: String): String? {
    val trimmed = text.trim()
    val chinesePrefix = Regex("""^(请)?记住[:：]?\s*(.+)$""")
    chinesePrefix.find(trimmed)?.groupValues?.getOrNull(2)?.trim()?.let { payload ->
        if (payload.isNotBlank()) return payload
    }

    val englishPrefix = Regex(
        pattern = """^(please\s+)?remember\s+(that\s+)?(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )
    return englishPrefix.find(trimmed)
        ?.groupValues
        ?.getOrNull(3)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

internal fun explicitPreferenceConflictKey(preference: String): String? {
    return explicitPreferenceConflictKeys(preference).firstOrNull()
}

internal fun explicitPreferenceForgetConflictKeys(target: String): Set<String> {
    val conflictKeys = explicitPreferenceConflictKeys(target)
    if (conflictKeys.isNotEmpty()) return conflictKeys
    val normalized = normalizedPreferenceText(target)
    return if (
        normalized.containsAny(RESPONSE_PREFERENCE_TERMS) &&
        normalized.containsAny(PREFERENCE_CONTROL_TERMS)
    ) {
        setOf("response-length", "response-language")
    } else {
        emptySet()
    }
}

internal fun explicitPreferenceConflictKeys(preference: String): Set<String> {
    val normalized = normalizedPreferenceText(preference)
    if (normalized.isBlank() || !normalized.containsAny(RESPONSE_PREFERENCE_TERMS)) {
        return emptySet()
    }
    return buildSet {
        if (normalized.containsAny(RESPONSE_LENGTH_TERMS)) add("response-length")
        if (normalized.containsAny(RESPONSE_LANGUAGE_TERMS)) add("response-language")
    }
}

private fun normalizedPreferenceText(text: String): String =
    text
        .trim()
        .removePrefix("用户偏好：")
        .removePrefix("user preference:")
        .trim()
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE_RUN_REGEX, " ")

private fun normalizedMemoryText(text: String): String =
    text.trim().replace(WHITESPACE_RUN_REGEX, " ").lowercase(Locale.ROOT)

internal fun userFactKeyFrom(text: String): String? {
    val normalized = normalizedUserFactText(text)
    val chineseMatch = Regex("""^(?:我(?:的)?)(.+?)(?:是|为)(.+)$""")
        .find(normalized)
    if (chineseMatch != null) {
        return chineseMatch.groupValues.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "zh:$it" }
    }
    val englishMatch = Regex("""^my\s+(.+?)\s+(?:is|are)\s+(.+)$""")
        .find(normalized)
    return englishMatch
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { key -> "en:$key" }
}

private fun isExplicitUserFactPayload(text: String): Boolean =
    userFactKeyFrom(text) != null && explicitPreferenceConflictKeys(text).isEmpty()

private fun normalizedUserFactText(text: String): String =
    text.trim()
        .removePrefix("用户事实：")
        .removePrefix("user fact:")
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE_RUN_REGEX, " ")

internal const val TASK_STATE_MEMORY_RECORD_PREFIX = "task-state-background:"

internal fun taskStateMemoryRecordId(taskId: String): String {
    val normalized = taskId
        .trim()
        .replace(UNSAFE_ID_CHARS_REGEX, "-")
        .trim('-')
        .take(64)
        .takeIf { it.isNotBlank() }
        ?: "unknown"
    return "$TASK_STATE_MEMORY_RECORD_PREFIX$normalized"
}

internal fun suppressedTaskStateMemoryRecordId(taskStateMemoryId: String): String =
    "suppressed-$taskStateMemoryId"
