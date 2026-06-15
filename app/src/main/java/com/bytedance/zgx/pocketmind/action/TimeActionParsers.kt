package com.bytedance.zgx.pocketmind.action

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

internal object SystemAlarmActionParser {
    fun matches(input: String): Boolean =
        draft(input) != null

    fun draft(input: String): ActionDraft? {
        if (!input.hasAlarmTrigger() || input.looksLikeTimeActionDiscussion()) return null
        val parsedTime = ClockTimeParser.parse(input) ?: return null
        val recurrence = if (input.hasDailyRecurrence()) "daily" else "once"
        val parameters = buildMap {
            put("hour", parsedTime.hour.toString())
            put("minutes", parsedTime.minute.toString())
            put("recurrence", recurrence)
            cleanedAlarmLabel(input, parsedTime.range)?.let { put("message", it) }
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
                    normalized.contains(Regex("""\b(?:set|create|add)\s+(?:an?\s+)?alarm\b""")) ||
                    normalized.contains(Regex("""\balarm\b"""))
                )
    }

    private fun cleanedAlarmLabel(input: String, timeRange: IntRange): String? =
        input.removeRange(timeRange.first, timeRange.last + 1)
            .replace(Regex("""每天\s*(?:一次)?"""), "")
            .replace(
                Regex("""(?i)^\s*(please\s+)?(?:(can|could)\s+you\s+)?(?:set|create|add)\s+(?:an?\s+)?alarm\s*(?:for)?\s*"""),
                "",
            )
            .replace(Regex("""^(请|帮我|麻烦你?|给我)?\s*(设置|设定|定|创建|加|添加)?\s*"""), "")
            .replace(Regex("""(一个|个)?\s*(闹钟|alarm)\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?i)^\s*(for|at)\s+"""), "")
            .trimActionLabel()
            .takeIf { it.isNotBlank() }
}

internal object SystemTimerActionParser {
    fun matches(input: String): Boolean =
        draft(input) != null

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
                    normalized.contains(Regex("""\b(?:set|create|start)\s+(?:a\s+)?timer\b""")) ||
                    normalized.contains(Regex("""\btimer\b"""))
                )
    }

    private fun cleanedTimerLabel(input: String): String? =
        input.replace(Regex("""(?<![\d.])\d+(?:\.\d+)?\s*(秒钟?|分钟|小时)"""), "")
            .replace(
                Regex(
                    """(?i)(?<![\d.])\d+(?:\.\d+)?\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)""",
                ),
                "",
            )
            .replace(
                Regex("""(?i)^\s*(please\s+)?(?:(can|could)\s+you\s+)?(?:set|create|start)\s+(?:a\s+)?timer\s*(?:for)?\s*"""),
                "",
            )
            .replace(Regex("""^(请|帮我|麻烦你?)?\s*(设置|设定|开始|开|做|创建)?\s*"""), "")
            .replace(Regex("""(一个|个)?\s*(倒计时|计时器|timer)\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?i)^\s*for\s+"""), "")
            .trimActionLabel()
            .takeIf { it.isNotBlank() }
}

internal object AbsoluteReminderActionParser {
    fun matches(input: String, nowMillis: Long, zoneId: ZoneId): Boolean =
        draft(input, nowMillis, zoneId) != null

    fun draft(input: String, nowMillis: Long, zoneId: ZoneId): ActionDraft? {
        if (!input.hasAbsoluteReminderTrigger() || input.looksLikeTimeActionDiscussion()) return null
        if (input.hasDailyRecurrence()) return null
        val parsedTime = ClockTimeParser.parse(input) ?: return null
        val triggerAtMillis = parsedTime.triggerAtMillis(nowMillis, zoneId) ?: return null
        val title = cleanedReminderTitle(input, parsedTime.range)
        return ActionDraft(
            functionName = MobileActionFunctions.SCHEDULE_REMINDER,
            title = "后台提醒",
            summary = "将在指定时间提醒：$title",
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
                    lowercase().contains(Regex("""\bremind me\b"""))
                )

    private fun cleanedReminderTitle(input: String, timeRange: IntRange): String =
        input.removeRange(timeRange.first, timeRange.last + 1)
            .replace(Regex("""^(请\s*)?(帮我\s*)?提醒我\s*"""), "")
            .replace(Regex("""(?i)^(please\s+)?(?:(can|could)\s+you\s+)?remind me\s+"""), "")
            .replace(Regex("""^(设置|设定|创建|加|添加)?\s*(一个|条)?\s*(备忘录?|备忘)\s*"""), "")
            .replace(Regex("""^一下\s*"""), "")
            .replace(Regex("""(?i)^\s*(to|about)\s+"""), "")
            .trimActionLabel()
            .ifBlank { "PocketMind 提醒" }
}

private object ClockTimeParser {
    private val clockTimePattern = Regex(
        """(?:(今天|明天|后天|今晚|明早|明晚)\s*)?""" +
            """(?:(凌晨|清晨|早上|上午|中午|下午|傍晚|晚上|夜里|夜晚)\s*)?""" +
            """(?<!\d)(\d{1,2})(?:(?:[:：](\d{1,2}))|(?:点(?:钟)?\s*(?:(\d{1,2})\s*分?)?))""",
    )

    fun parse(input: String): ParsedClockTime? =
        clockTimePattern.findAll(input)
            .mapNotNull { match -> match.toParsedClockTime() }
            .firstOrNull()

    private fun MatchResult.toParsedClockTime(): ParsedClockTime? {
        val dateWord = groupValues[1].takeIf { it.isNotBlank() }
        val periodWord = groupValues[2].takeIf { it.isNotBlank() } ?: periodFromDateWord(dateWord)
        val rawHour = groupValues[3].toIntOrNull() ?: return null
        val minute = groupValues[4].ifBlank { groupValues[5] }.ifBlank { "0" }.toIntOrNull() ?: return null
        if (minute !in 0..59 || rawHour !in 0..23) return null
        val hour = normalizedHour(rawHour, periodWord) ?: return null
        return ParsedClockTime(
            hour = hour,
            minute = minute,
            explicitDayOffset = dayOffset(dateWord),
            range = range,
        )
    }

    private fun periodFromDateWord(dateWord: String?): String? =
        when (dateWord) {
            "今晚", "明晚" -> "晚上"
            "明早" -> "早上"
            else -> null
        }

    private fun dayOffset(dateWord: String?): Int? =
        when (dateWord) {
            "今天", "今晚" -> 0
            "明天", "明早", "明晚" -> 1
            "后天" -> 2
            else -> null
        }

    private fun normalizedHour(rawHour: Int, periodWord: String?): Int? =
        when (periodWord) {
            "下午", "傍晚", "晚上", "夜里", "夜晚" -> when (rawHour) {
                in 1..11 -> rawHour + 12
                in 12..23 -> rawHour
                else -> null
            }

            "中午" -> when (rawHour) {
                in 1..10 -> rawHour + 12
                in 11..12 -> rawHour
                else -> null
            }

            "凌晨", "清晨", "早上", "上午" -> when (rawHour) {
                12 -> 0
                in 0..11 -> rawHour
                else -> null
            }

            else -> rawHour
        }
}

private data class ParsedClockTime(
    val hour: Int,
    val minute: Int,
    val explicitDayOffset: Int?,
    val range: IntRange,
) {
    fun triggerAtMillis(nowMillis: Long, zoneId: ZoneId): Long? {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val baseDate = now.toLocalDate().plusDays((explicitDayOffset ?: 0).toLong())
        var target = baseDate.atTime(hour, minute).atZone(zoneId)
        if (explicitDayOffset == null && !target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return target.takeIf { it.isAfter(now) }?.toInstant()?.toEpochMilli()
    }
}

private object DurationParser {
    private const val MAX_TIMER_SECONDS = 24 * 60 * 60
    private const val AMOUNT_PATTERN = """\d+(?:\.\d+)?"""
    private val chineseDurationPattern = Regex("""(?<![\d.])($AMOUNT_PATTERN)\s*(秒钟?|分钟|小时)""")
    private val englishDurationPattern = Regex(
        """(?<![\d.])($AMOUNT_PATTERN)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun durationSeconds(input: String): Long? {
        val seconds = (
            chineseDurationPattern.findAll(input).mapNotNull { it.toSeconds(amountIndex = 1, unitIndex = 2) } +
                englishDurationPattern.findAll(input).mapNotNull { it.toSeconds(amountIndex = 1, unitIndex = 2) }
            ).sum()
        return seconds.takeIf { it in 1..MAX_TIMER_SECONDS }
    }

    private fun MatchResult.toSeconds(amountIndex: Int, unitIndex: Int): Long? {
        val amount = groupValues.getOrNull(amountIndex)?.toDoubleOrNull() ?: return null
        val unit = groupValues.getOrNull(unitIndex)?.lowercase().orEmpty()
        val seconds = when {
            unit.startsWith("小时") || unit.startsWith("hour") || unit.startsWith("hr") -> amount * 60.0 * 60.0
            unit.startsWith("分钟") || unit.startsWith("minute") || unit.startsWith("min") -> amount * 60.0
            else -> amount
        }
        return seconds.roundToLong().coerceAtLeast(1L)
    }
}

private fun String.hasDailyRecurrence(): Boolean {
    val normalized = lowercase()
    return listOf("每天", "每日", "天天", "每天一次").any { it in this } ||
        normalized.contains(Regex("""\b(?:daily|every\s+day|each\s+day)\b"""))
}

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
        normalized.contains(Regex("""\b(?:what\s+is|explain|describe|meaning|how\s+(?:do|to)|implement|design|api|docs?|tests?)\b"""))
}

private fun String.trimActionLabel(): String =
    replace(Regex("""\s{2,}"""), " ")
        .trim { it <= ' ' || it == '，' || it == ',' || it == ':' || it == '：' || it == '。' || it == '.' }
