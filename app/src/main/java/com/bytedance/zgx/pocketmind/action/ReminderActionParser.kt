package com.bytedance.zgx.pocketmind.action

import kotlin.math.roundToLong

internal object ReminderActionParser {
    private const val AMOUNT_PATTERN = """\d+(?:\.\d+)?"""
    private val chineseRelativeDelayPattern =
        Regex("""(?:在\s*)?(?<![\d.])($AMOUNT_PATTERN)\s*(分钟|小时)\s*(?:后|以后|之后)""")
    private val englishRelativeDelayPattern =
        Regex("""\b(?:in|after)\s+(?<![\d.])($AMOUNT_PATTERN)\s*(minutes?|mins?|hours?|hrs?)\b""", RegexOption.IGNORE_CASE)
    private val chineseTimingQuestionAfterDelayPattern =
        Regex("""^[\s，,。.!?？、:："'“”‘’（）()]*?(?:是?什么意思|啥意思|怎么说|如何表达|什么含义|有?什么含义|用法|语法)""")
    private val chineseReminderCommandPattern =
        Regex("""^\s*(?:请\s*)?(?:帮我\s*)?提醒我""")
    private val englishReminderCommandPattern =
        Regex("""^\s*(?:please\s+)?(?:(?:can|could)\s+you\s+)?(?:remind me|set (?:a )?reminder)\b""", RegexOption.IGNORE_CASE)
    private val englishWhatPattern =
        Regex("""\bwhat(?:'s|\s+is)?\b""", RegexOption.IGNORE_CASE)
    private val englishMeaningPattern =
        Regex("""\b(?:means?|meaning)\b""", RegexOption.IGNORE_CASE)

    fun matches(input: String): Boolean {
        val delay = relativeDelay(input) ?: return false
        return hasReminderCommand(input) && !looksLikeTimingDiscussion(input, delay)
    }

    fun parameters(input: String): Map<String, String> {
        val delayMinutes = relativeDelay(input)?.minutes ?: 1L
        val title = cleanedReminderTitle(input)
        return mapOf(
            "title" to title,
            "body" to input.trim(),
            "delayMinutes" to delayMinutes.toString(),
        )
    }

    fun draft(input: String): ActionDraft =
        parameters(input).let { parameters ->
            ActionDraft(
                functionName = MobileActionFunctions.SCHEDULE_REMINDER,
                title = "后台提醒",
                summary = "将在 ${parameters["delayMinutes"].orEmpty()} 分钟后提醒：${parameters["title"].orEmpty()}",
                parameters = parameters,
                requiresConfirmation = true,
            )
        }

    private fun relativeDelay(input: String): RelativeDelay? =
        (
            chineseRelativeDelayPattern.findAll(input).mapNotNull { match ->
                match.toRelativeDelay(amountGroupIndex = 1, unitGroupIndex = 2)
            } + englishRelativeDelayPattern.findAll(input).mapNotNull { match ->
                match.toRelativeDelay(amountGroupIndex = 1, unitGroupIndex = 2)
            }
            ).minByOrNull { delay -> delay.range.first }

    private fun cleanedReminderTitle(input: String): String {
        val withoutSelectedDelay = relativeDelay(input)?.let { delay ->
            input.removeRange(delay.range.first, delay.range.last + 1)
        } ?: input
        return withoutSelectedDelay.trim()
            .replace(Regex("""^(请\s*)?(帮我\s*)?提醒我\s*"""), "")
            .replace(
                Regex(
                    """(?i)^(please\s+)?(?:(can|could)\s+you\s+)?(?:remind me|set (?:a )?reminder)\s+""",
                ),
                "",
            )
            .replace(Regex("""^一下\s*"""), "")
            .replace(Regex("""(?i)^\s*to\s+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim { it <= ' ' || it == '，' || it == ',' || it == ':' || it == '：' }
            .ifBlank { cleanedObject(input) }
    }

    private fun cleanedObject(input: String): String =
        input.trim()
            .removePrefix("请")
            .removePrefix("帮我")
            .trim()
            .ifBlank { input.trim() }

    private fun hasReminderCommand(input: String): Boolean =
        chineseReminderCommandPattern.containsMatchIn(input) || englishReminderCommandPattern.containsMatchIn(input)

    private fun looksLikeTimingDiscussion(input: String, delay: RelativeDelay): Boolean {
        val afterDelay = input.substring(delay.range.last + 1)
        if (chineseTimingQuestionAfterDelayPattern.containsMatchIn(afterDelay)) return true

        val whatIndex = englishWhatPattern.find(input)?.range?.first ?: return false
        val meaningIndex = englishMeaningPattern.findAll(input)
            .firstOrNull { match -> match.range.first > whatIndex }
            ?.range
            ?.first
            ?: return false
        return delay.range.first in whatIndex..meaningIndex
    }

    private fun MatchResult.toRelativeDelay(amountGroupIndex: Int, unitGroupIndex: Int): RelativeDelay? {
        val amount = groupValues.getOrNull(amountGroupIndex)?.toDoubleOrNull() ?: return null
        val unit = groupValues.getOrNull(unitGroupIndex)?.lowercase().orEmpty()
        val minutes = when {
            unit == "小时" || unit.startsWith("hour") || unit.startsWith("hr") -> amount * 60.0
            else -> amount
        }.roundToLong().coerceAtLeast(1L)
        return RelativeDelay(minutes = minutes, range = range)
    }

    private data class RelativeDelay(
        val minutes: Long,
        val range: IntRange,
    )
}
