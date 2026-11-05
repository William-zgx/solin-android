package com.bytedance.zgx.solin.action

import java.time.ZoneId
import kotlin.math.roundToLong

private val HAS_ALARM_TRIGGER_SET_CREATE_REGEX =
    Regex("""\b(?:set|create|add)\s+(?:an?\s+)?alarm\b""")

private val HAS_ALARM_TRIGGER_ALARM_REGEX = Regex("""\balarm\b""")

private val CLEANED_ALARM_LABEL_ZH1_REGEX = Regex("""每天\s*(?:一次)?""")

private val CLEANED_ALARM_LABEL_PLEASE_CAN_REGEX =
    Regex("""(?i)^\s*(please\s+)?(?:(can|could)\s+you\s+)?(?:set|create|add)\s+(?:an?\s+)?alarm\s*(?:for)?\s*""")

private val CLEANED_ALARM_LABEL_ZH2_REGEX =
    Regex("""^(请|帮我|麻烦你?|给我)?\s*(设置|设定|设(?=[一个的]|\s|$)|定|创建|加|添加)?\s*""")

private val CLEANED_ALARM_LABEL_ALARM_REGEX =
    Regex("""(一个|个)?\s*的?\s*(闹钟|alarm)\s*""", RegexOption.IGNORE_CASE)

private val CLEANED_ALARM_LABEL_ZH3_REGEX = Regex("""^\s*叫醒我\s*""")

private val CLEANED_ALARM_LABEL_FOR_AT_REGEX = Regex("""(?i)^\s*(for|at)\s+""")

internal object SystemAlarmActionParser {
    fun draft(input: String): ActionDraft? {
        if (!input.hasAlarmTrigger() || input.looksLikeTimeActionDiscussion()) return null
        val parsedTime = ChineseTimeExpressions.parseClockTime(input) ?: return null
        val recurrence = if (input.hasDailyRecurrence()) "daily" else "once"
        // The system alarm intent only takes hh:mm and rings at its next occurrence, so a date
        // beyond today/tomorrow (后天, 周五, 6月20日) cannot be honoured: fail closed instead of
        // silently ringing on the wrong day.
        val date = parsedTime.date
        if (date != null && recurrence == "once" && !date.isTodayOrTomorrow) return null
        val parameters = buildMap {
            put("hour", parsedTime.hour.toString())
            put("minutes", parsedTime.minute.toString())
            put("recurrence", recurrence)
            cleanedAlarmLabel(input, parsedTime.removalRanges)?.let { put("message", it) }
        }
        val timeText = "%02d:%02d".format(parsedTime.hour, parsedTime.minute)
        val recurrenceText = if (recurrence == "daily") "每天" else "下次"
        return ActionDraft(
            functionName = MobileActionFunctions.SET_SYSTEM_ALARM,
            title = "系统闹钟",
            summary = "将打开系统闹钟界面设置$recurrenceText $timeText 的闹钟。",
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun String.hasAlarmTrigger(): Boolean {
        val normalized = lowercase()
        return !startsWithActionNegation() &&
            (
                listOf("闹钟", "叫醒").any { it in this } ||
                    normalized.contains(HAS_ALARM_TRIGGER_SET_CREATE_REGEX) ||
                    normalized.contains(HAS_ALARM_TRIGGER_ALARM_REGEX)
                )
    }

    private fun cleanedAlarmLabel(input: String, timeRanges: List<IntRange>): String? =
        input.removeRanges(timeRanges)
            .replace(CLEANED_ALARM_LABEL_ZH1_REGEX, "")
            .replace(
                CLEANED_ALARM_LABEL_PLEASE_CAN_REGEX,
                "",
            )
            .replace(CLEANED_ALARM_LABEL_ZH2_REGEX, "")
            .replace(CLEANED_ALARM_LABEL_ALARM_REGEX, "")
            .replace(CLEANED_ALARM_LABEL_ZH3_REGEX, "")
            .replace(CLEANED_ALARM_LABEL_FOR_AT_REGEX, "")
            .trimActionLabel()
            .takeIf { it.isNotBlank() }
}

private val HAS_TIMER_TRIGGER_SET_CREATE_REGEX =
    Regex("""\b(?:set|create|start)\s+(?:a\s+)?timer\b""")

private val HAS_TIMER_TRIGGER_TIMER_REGEX = Regex("""\btimer\b""")

private val CLEANED_TIMER_LABEL_SECONDS_SECS_REGEX = Regex(
    """(?i)(?<![\d.])\d+(?:\.\d+)?\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)""",
)

private val CLEANED_TIMER_LABEL_PLEASE_CAN_REGEX =
    Regex("""(?i)^\s*(please\s+)?(?:(can|could)\s+you\s+)?(?:set|create|start)\s+(?:a\s+)?timer\s*(?:for)?\s*""")

private val CLEANED_TIMER_LABEL_ZH1_REGEX =
    Regex("""^(请|帮我|麻烦你?)?\s*(设置|设定|设(?=[一个的]|\s|$)|开始|开|做|创建)?\s*""")

private val CLEANED_TIMER_LABEL_TIMER_REGEX =
    Regex("""(一个|个)?\s*的?\s*(倒计时|计时器|timer)\s*""", RegexOption.IGNORE_CASE)

private val CLEANED_TIMER_LABEL_FOR_REGEX = Regex("""(?i)^\s*for\s+""")

internal object SystemTimerActionParser {
    fun draft(input: String): ActionDraft? {
        if (!input.hasTimerTrigger() || input.looksLikeTimeActionDiscussion()) return null
        val durationSeconds = DurationParser.durationSeconds(input) ?: return null
        val parameters = buildMap {
            put("lengthSeconds", durationSeconds.toString())
            cleanedTimerLabel(input)?.let { put("message", it) }
        }
        return ActionDraft(
            functionName = MobileActionFunctions.SET_SYSTEM_TIMER,
            title = "系统倒计时",
            summary = "将打开系统计时器界面设置 ${durationSeconds} 秒倒计时。",
            parameters = parameters,
            requiresConfirmation = true,
        )
    }

    private fun String.hasTimerTrigger(): Boolean {
        val normalized = lowercase()
        return !startsWithActionNegation() &&
            (
                listOf("倒计时", "计时器").any { it in this } ||
                    normalized.contains(HAS_TIMER_TRIGGER_SET_CREATE_REGEX) ||
                    normalized.contains(HAS_TIMER_TRIGGER_TIMER_REGEX)
                )
    }

    private fun cleanedTimerLabel(input: String): String? =
        input.removeRanges(ChineseTimeExpressions.durations(input).map { it.range })
            .replace(
                CLEANED_TIMER_LABEL_SECONDS_SECS_REGEX,
                "",
            )
            .replace(
                CLEANED_TIMER_LABEL_PLEASE_CAN_REGEX,
                "",
            )
            .replace(CLEANED_TIMER_LABEL_ZH1_REGEX, "")
            .replace(CLEANED_TIMER_LABEL_TIMER_REGEX, "")
            .replace(CLEANED_TIMER_LABEL_FOR_REGEX, "")
            .trimActionLabel()
            .takeIf { it.isNotBlank() }
}

private val HAS_ABSOLUTE_REMINDER_TRIGGER_REGEX = Regex("""\bremind me\b""")

private val CLEANED_REMINDER_TITLE_ZH1_REGEX = Regex("""^(请\s*)?(帮我\s*)?提醒我\s*""")

private val CLEANED_REMINDER_TITLE_PLEASE_CAN_REGEX =
    Regex("""(?i)^(please\s+)?(?:(can|could)\s+you\s+)?remind me\s+""")

private val CLEANED_REMINDER_TITLE_ZH2_REGEX =
    Regex("""^(设置|设定|创建|加|添加)?\s*(一个|条)?\s*(备忘录?|备忘)\s*""")

private val CLEANED_REMINDER_TITLE_ZH3_REGEX = Regex("""^一下\s*""")

private val CLEANED_REMINDER_TITLE_TO_ABOUT_REGEX = Regex("""(?i)^\s*(to|about)\s+""")

internal object AbsoluteReminderActionParser {
    fun draft(input: String, nowMillis: Long, zoneId: ZoneId): ActionDraft? {
        if (!input.hasAbsoluteReminderTrigger() || input.looksLikeTimeActionDiscussion()) return null
        if (input.hasDailyRecurrence()) return null
        val parsedTime = ChineseTimeExpressions.parseClockTime(input) ?: return null
        val triggerAtMillis = parsedTime.triggerAtMillis(nowMillis, zoneId) ?: return null
        val title = cleanedReminderTitle(input, parsedTime.removalRanges)
        return ActionDraft(
            functionName = MobileActionFunctions.SCHEDULE_REMINDER,
            title = "后台提醒",
            summary = "将在 ${ChineseTimeExpressions.formatDateTime(triggerAtMillis, zoneId)} 提醒：$title",
            parameters = mapOf(
                "title" to title,
                "body" to input.trim(),
                "triggerAtMillis" to triggerAtMillis.toString(),
            ),
            requiresConfirmation = true,
        )
    }

    private fun String.hasAbsoluteReminderTrigger(): Boolean =
        !startsWithActionNegation() &&
            (
                "提醒我" in this ||
                    listOf("备忘", "备忘录").any { it in this } ||
                    lowercase().contains(HAS_ABSOLUTE_REMINDER_TRIGGER_REGEX)
                )

    private fun cleanedReminderTitle(input: String, timeRanges: List<IntRange>): String =
        input.removeRanges(timeRanges)
            .replace(CLEANED_REMINDER_TITLE_ZH1_REGEX, "")
            .replace(CLEANED_REMINDER_TITLE_PLEASE_CAN_REGEX, "")
            .replace(CLEANED_REMINDER_TITLE_ZH2_REGEX, "")
            .replace(CLEANED_REMINDER_TITLE_ZH3_REGEX, "")
            .replace(CLEANED_REMINDER_TITLE_TO_ABOUT_REGEX, "")
            .trimActionLabel()
            .ifBlank { "Solin提醒" }
}

private object DurationParser {
    private const val MAX_TIMER_SECONDS = 24 * 60 * 60
    private const val AMOUNT_PATTERN = """\d+(?:\.\d+)?"""
    private val englishDurationPattern = Regex(
        """(?<![\d.])($AMOUNT_PATTERN)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun durationSeconds(input: String): Long? {
        val seconds = (
            ChineseTimeExpressions.durations(input).map { ChineseTimeExpressions.roundSeconds(it.seconds) } +
                englishDurationPattern.findAll(input).mapNotNull { it.toSeconds(amountIndex = 1, unitIndex = 2) }
            ).sum()
        return seconds.takeIf { it in 1..MAX_TIMER_SECONDS }
    }

    private fun MatchResult.toSeconds(amountIndex: Int, unitIndex: Int): Long? {
        val amount = groupValues.getOrNull(amountIndex)?.toDoubleOrNull() ?: return null
        val unit = groupValues.getOrNull(unitIndex)?.lowercase().orEmpty()
        val seconds = when {
            unit.startsWith("hour") || unit.startsWith("hr") -> amount * 60.0 * 60.0
            unit.startsWith("minute") || unit.startsWith("min") -> amount * 60.0
            else -> amount
        }
        return seconds.roundToLong().coerceAtLeast(1L)
    }
}

/** Removes [ranges] (any order, non-overlapping) from the string. */
internal fun String.removeRanges(ranges: List<IntRange>): String =
    ranges.sortedByDescending { it.first }
        .fold(this) { text, range -> text.removeRange(range.first, range.last + 1) }

private val HAS_DAILY_RECURRENCE_REGEX = Regex("""\b(?:daily|every\s+day|each\s+day)\b""")

private fun String.hasDailyRecurrence(): Boolean {
    val normalized = lowercase()
    return listOf("每天", "每日", "天天", "每天一次").any { it in this } ||
        normalized.contains(HAS_DAILY_RECURRENCE_REGEX)
}

private val TIME_ACTION_DISCUSSION_REGEX =
    Regex("""\b(?:what\s+is|explain|describe|meaning|how\s+(?:do|to)|implement|design|api|docs?|tests?)\b""")

private fun String.looksLikeTimeActionDiscussion(): Boolean {
    val normalized = lowercase()
    return startsWithActionNegation() ||
        listOf(
            "是什么意思",
            "啥意思",
            "解释",
            "含义",
            "用法",
            "怎么说",
            "如何表达",
            "怎么实现",
            "如何实现",
            "怎么设计",
            "代码",
            "接口",
            "文档",
            "测试",
            "原理",
        ).any { it in this } ||
        normalized.contains(TIME_ACTION_DISCUSSION_REGEX)
}

private val TRIM_ACTION_LABEL_REGEX = Regex("""\s{2,}""")

private fun String.trimActionLabel(): String =
    replace(TRIM_ACTION_LABEL_REGEX, " ")
        .trim { it <= ' ' || it == '，' || it == ',' || it == ':' || it == '：' || it == '。' || it == '.' }
        // "设置明天7点的闹钟" leaves a dangling 的 once the time and trigger words are cut out.
        .removePrefix("的")
        .trim()
