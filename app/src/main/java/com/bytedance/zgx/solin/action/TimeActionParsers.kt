package com.bytedance.zgx.solin.action

import java.time.ZoneId
import kotlin.math.roundToLong

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
                    normalized.contains(Regex("""\b(?:set|create|add)\s+(?:an?\s+)?alarm\b""")) ||
                    normalized.contains(Regex("""\balarm\b"""))
                )
    }

    private fun cleanedAlarmLabel(input: String, timeRanges: List<IntRange>): String? =
        input.removeRanges(timeRanges)
            .replace(Regex("""每天\s*(?:一次)?"""), "")
            .replace(
                Regex("""(?i)^\s*(please\s+)?(?:(can|could)\s+you\s+)?(?:set|create|add)\s+(?:an?\s+)?alarm\s*(?:for)?\s*"""),
                "",
            )
            .replace(Regex("""^(请|帮我|麻烦你?|给我)?\s*(设置|设定|设(?=[一个的]|\s|$)|定|创建|加|添加)?\s*"""), "")
            .replace(Regex("""(一个|个)?\s*的?\s*(闹钟|alarm)\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*叫醒我\s*"""), "")
            .replace(Regex("""(?i)^\s*(for|at)\s+"""), "")
            .trimActionLabel()
            .takeIf { it.isNotBlank() }
}

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
                    normalized.contains(Regex("""\b(?:set|create|start)\s+(?:a\s+)?timer\b""")) ||
                    normalized.contains(Regex("""\btimer\b"""))
                )
    }

    private fun cleanedTimerLabel(input: String): String? =
        input.removeRanges(ChineseTimeExpressions.durations(input).map { it.range })
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
            .replace(Regex("""^(请|帮我|麻烦你?)?\s*(设置|设定|设(?=[一个的]|\s|$)|开始|开|做|创建)?\s*"""), "")
            .replace(Regex("""(一个|个)?\s*的?\s*(倒计时|计时器|timer)\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?i)^\s*for\s+"""), "")
            .trimActionLabel()
            .takeIf { it.isNotBlank() }
}

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
                    lowercase().contains(Regex("""\bremind me\b"""))
                )

    private fun cleanedReminderTitle(input: String, timeRanges: List<IntRange>): String =
        input.removeRanges(timeRanges)
            .replace(Regex("""^(请\s*)?(帮我\s*)?提醒我\s*"""), "")
            .replace(Regex("""(?i)^(please\s+)?(?:(can|could)\s+you\s+)?remind me\s+"""), "")
            .replace(Regex("""^(设置|设定|创建|加|添加)?\s*(一个|条)?\s*(备忘录?|备忘)\s*"""), "")
            .replace(Regex("""^一下\s*"""), "")
            .replace(Regex("""(?i)^\s*(to|about)\s+"""), "")
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
        // "设置明天7点的闹钟" leaves a dangling 的 once the time and trigger words are cut out.
        .removePrefix("的")
        .trim()
