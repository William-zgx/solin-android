package com.bytedance.zgx.solin.action

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.roundToLong

/*
 * Shared, pure time-expression parsing for the rule planners (alarms, timers, reminders,
 * periodic checks and calendar drafts).
 *
 * Everything here works on the ORIGINAL input string and reports [IntRange]s into it, so callers
 * can cut the time phrase out of a label without rewriting the rest of the user's text (e.g. a
 * reminder title "给一个客户打电话" keeps its Chinese numeral).
 *
 * Fail-closed contract: anything ambiguous (晚上十二点, hour 24, 一点 used as "a little"), any
 * explicit date that is already in the past, and any impossible calendar date returns null rather
 * than a best guess. Callers then fall through to "no action" exactly as for unparseable input.
 */

internal object ChineseNumerals {
    const val NUMERAL_CLASS = "零〇一二两三四五六七八九十百千"

    /** Parses ASCII digits or a Chinese integer numeral up to 9999 (十五, 二十, 一百零五, 两千). */
    fun parseInt(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.all { it.isDigit() }) return trimmed.toIntOrNull()
        var total = 0
        var pending: Int? = null
        var lastUnit = Int.MAX_VALUE
        var previousWasDigit = false
        for (character in trimmed) {
            val digit = digitValue(character)
            if (digit != null) {
                // "二三" is not a numeral; "一百零五" is (零 is a placeholder before a digit).
                if (previousWasDigit && pending != 0) return null
                pending = digit
                previousWasDigit = true
                continue
            }
            val unit = unitValue(character) ?: return null
            if (unit >= lastUnit) return null
            total += (pending ?: 1) * unit
            pending = null
            lastUnit = unit
            previousWasDigit = false
        }
        return total + (pending ?: 0)
    }

    /** Parses a non-negative amount: ASCII decimal ("1.5") or Chinese integer numeral. */
    fun parseAmount(text: String): Double? {
        val trimmed = text.trim()
        trimmed.toDoubleOrNull()?.let { return it.takeIf { value -> value >= 0.0 } }
        return parseInt(trimmed)?.toDouble()
    }

    private fun digitValue(character: Char): Int? =
        when (character) {
            '零', '〇' -> 0
            '一' -> 1
            '二', '两' -> 2
            '三' -> 3
            '四' -> 4
            '五' -> 5
            '六' -> 6
            '七' -> 7
            '八' -> 8
            '九' -> 9
            else -> null
        }

    private fun unitValue(character: Char): Int? =
        when (character) {
            '十' -> 10
            '百' -> 100
            '千' -> 1000
            else -> null
        }
}

/** A calendar date phrase, resolved against "now" only when needed. */
internal sealed class DateSpec {
    data class RelativeDays(val days: Int, val word: String) : DateSpec()

    data class Weekday(val dayOfWeek: DayOfWeek, val week: WeekScope) : DateSpec()

    data class MonthDay(val month: Int, val day: Int) : DateSpec()

    enum class WeekScope { Nearest, This, Next }

    /** True for 今天/今晚/今早/明天/明早/明晚 — the only dates a next-occurrence system alarm can honour. */
    val isTodayOrTomorrow: Boolean
        get() = this is RelativeDays && days in 0..1

    /**
     * Resolves the calendar date. Returns null for impossible dates or for explicit dates that are
     * already before today. [Nearest] weekdays pick today when [allowToday] is true.
     */
    fun resolveDate(today: LocalDate, allowToday: Boolean = true): LocalDate? =
        when (this) {
            is RelativeDays -> today.plusDays(days.toLong())
            is Weekday -> when (week) {
                WeekScope.Nearest -> {
                    val candidate = today.with(TemporalAdjusters.nextOrSame(dayOfWeek))
                    if (!allowToday && candidate == today) candidate.plusWeeks(1) else candidate
                }

                WeekScope.This -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .plusDays((dayOfWeek.value - 1).toLong())
                    .takeIf { !it.isBefore(today) }

                WeekScope.Next -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .plusWeeks(1)
                    .plusDays((dayOfWeek.value - 1).toLong())
            }

            is MonthDay -> runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
                ?.takeIf { !it.isBefore(today) }
        }
}

internal data class ParsedDate(
    val spec: DateSpec,
    val range: IntRange,
)

internal data class ParsedClockTime(
    val hour: Int,
    val minute: Int,
    val date: DateSpec?,
    /** Range of the (optional adjacent date +) period + clock phrase. */
    val range: IntRange,
    /** Range of a date phrase that was found elsewhere in the input, if not adjacent. */
    val detachedDateRange: IntRange? = null,
) {
    /** Ranges to cut out of a label, in descending order so removal does not shift indices. */
    val removalRanges: List<IntRange>
        get() = listOfNotNull(range, detachedDateRange).sortedByDescending { it.first }

    /**
     * Resolves the trigger instant. Without a date the next occurrence of hh:mm is used (today if
     * still ahead, else tomorrow). With an explicit date the instant must lie in the future;
     * otherwise null (fail closed — never silently move an explicit date).
     */
    fun triggerAtMillis(nowMillis: Long, zoneId: ZoneId): Long? {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val today = now.toLocalDate()
        if (date == null) {
            var target = today.atTime(hour, minute).atZone(zoneId)
            if (!target.isAfter(now)) target = target.plusDays(1)
            return target.takeIf { it.isAfter(now) }?.toInstant()?.toEpochMilli()
        }
        val target = resolve(now) ?: return null
        return target.takeIf { it.isAfter(now) }?.toInstant()?.toEpochMilli()
    }

    private fun resolve(now: ZonedDateTime): ZonedDateTime? {
        val today = now.toLocalDate()
        val spec = date ?: return null
        val todayStillAhead = today.atTime(hour, minute).atZone(now.zone).isAfter(now)
        val day = spec.resolveDate(today, allowToday = todayStillAhead) ?: return null
        return day.atTime(hour, minute).atZone(now.zone)
    }
}

/** Calendar draft timing: an exact start (one-hour default slot) or an all-day date. */
internal data class CalendarDraftTiming(
    val beginTimeMillis: Long,
    val endTimeMillis: Long,
    val allDay: Boolean,
) {
    fun displayText(zoneId: ZoneId): String =
        if (allDay) {
            "${ChineseTimeExpressions.formatDate(beginTimeMillis, zoneId)} 全天"
        } else {
            "${ChineseTimeExpressions.formatDateTime(beginTimeMillis, zoneId)}–" +
                ChineseTimeExpressions.formatTime(endTimeMillis, zoneId)
        }

    companion object {
        private const val DEFAULT_EVENT_MILLIS = 60L * 60L * 1000L

        /**
         * Clock time (with optional date) → one-hour slot; date only → all-day. An explicit date
         * in the past yields null so the draft opens without a pre-filled (wrong) time.
         */
        fun parse(input: String, nowMillis: Long, zoneId: ZoneId): CalendarDraftTiming? {
            ChineseTimeExpressions.parseClockTime(input)?.let { clock ->
                val begin = clock.triggerAtMillis(nowMillis, zoneId) ?: return null
                return CalendarDraftTiming(begin, begin + DEFAULT_EVENT_MILLIS, allDay = false)
            }
            val date = ChineseTimeExpressions.parseDate(input) ?: return null
            val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
            val day = date.spec.resolveDate(today) ?: return null
            val begin = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            return CalendarDraftTiming(begin, end, allDay = true)
        }
    }
}

internal data class ParsedDuration(
    val seconds: Double,
    val range: IntRange,
)

internal object ChineseTimeExpressions {
    private const val N = ChineseNumerals.NUMERAL_CLASS
    private const val SMALL_NUMBER = """(?:\d{1,2}|[$N]{1,3})"""
    private const val DATE_WORD = """大后天|后天|明天|今天|今晚|今早|明早|明晚"""
    private const val WEEKDAY = """(?:下个?|这个?|本)?(?:周|星期|礼拜)(?:[二三四五六日天]|一(?![直定起样般些共切致]))"""
    private const val MONTH_DAY = """$SMALL_NUMBER\s*月\s*$SMALL_NUMBER\s*[日号]"""
    private const val PERIOD = """凌晨|清晨|早晨|早上|上午|中午|下午|傍晚|晚上|夜里|夜晚|半夜"""
    private const val CHINESE_MINUTE_TENS = """零[一二三四五六七八九]|[一二三四五]?十[一二三四五六七八九]?"""

    /** Characters that turn a following "一点/两点" into a quantity ("快一点", "喝一点"), not a time. */
    private const val QUANTITY_PREFIX = """多少快慢有好再大小高低早晚近远轻重些这那差喝吃买拿放加减用看说写第每"""

    private val datePattern = Regex("""($DATE_WORD)|($WEEKDAY)|($MONTH_DAY)""")

    private val clockPattern = Regex(
        """(?:(?<date>$DATE_WORD|$WEEKDAY|$MONTH_DAY)\s*的?\s*)?""" +
            """(?:(?<period>$PERIOD)\s*)?""" +
            """(?:""" +
            // 差五分四点
            """差\s*(?<beforeMin>$SMALL_NUMBER)\s*分(?:钟)?\s*(?<beforeHour>$SMALL_NUMBER)\s*[点點](?:钟)?""" +
            // 15:30
            """|(?<![\d.])(?<colonHour>\d{1,2})[:：](?<colonMin>\d{1,2})(?!\d)""" +
            // 三点 / 3点 + optional minute tail
            """|(?<![\d.$N])(?<hour>\d{1,2}|[$N]{1,3})\s*[点點](?![点些儿])(?:钟)?""" +
            """(?:\s*(?:""" +
            """(?<half>半)""" +
            """|(?<quarter>一刻|1刻)""" +
            """|(?<threeQuarters>三刻|3刻)""" +
            """|(?<whole>整)""" +
            """|差\s*(?<toMin>$SMALL_NUMBER)\s*分(?:钟)?""" +
            """|过?\s*(?<digitMin>\d{1,2})(?:\s*分(?:钟)?)?""" +
            """|过?\s*(?<tensMin>$CHINESE_MINUTE_TENS)(?![$N])(?:\s*分(?:钟)?)?""" +
            """|过?\s*(?<unitMin>[一二三四五六七八九])\s*分(?:钟)?""" +
            """))?""" +
            """)""",
    )

    private const val AMOUNT = """\d+(?:\.\d+)?|[$N]+"""
    private val durationComponentPattern = Regex(
        """(?:(?<![\d.$N])(?<amount>$AMOUNT)\s*(?:个)?\s*(?<plusHalf>半)?|(?<half>半)\s*(?:个)?)\s*""" +
            """(?<unit>小时|钟头|分钟|秒钟|秒|刻钟|分(?=\s*(?:$AMOUNT)\s*秒)|分(?=\s*(?:以后|之后|后)))""",
    )
    private val durationJoinerPattern = Regex("""^\s*(?:零|又)?\s*$""")
    private val afterSuffixPattern = Regex("""^\s*(?:以后|之后|后)""")
    private val relativePrefixPattern = Regex("""(?:在|过)\s*$""")

    /** First parseable clock time in [input], with an adjacent or detached date when present. */
    fun parseClockTime(input: String): ParsedClockTime? {
        val clock = clockPattern.findAll(input)
            .mapNotNull { match -> match.toClockTime(input) }
            .firstOrNull()
            ?: return null
        if (clock.date != null) return clock.asParsed()
        val detached = datePattern.findAll(input)
            .filter { dateMatch -> dateMatch.range.last < clock.range.first || dateMatch.range.first > clock.range.last }
            .mapNotNull { dateMatch -> parseDateSpec(dateMatch.value)?.let { ParsedDate(it, dateMatch.range) } }
            .firstOrNull()
            ?: return clock.asParsed()
        val impliedPeriod = periodFromDateWord(detached.spec)
        val hour = if (impliedPeriod != null && !clock.hasExplicitPeriod) {
            normalizedHour(clock.rawHour, impliedPeriod) ?: return null
        } else {
            clock.hour
        }
        return clock.copy(hour = hour, date = detached.spec, detachedDateRange = detached.range).asParsed()
    }

    /** First date phrase (今天/周五/下周一/6月20日 …) without a clock time. */
    fun parseDate(input: String): ParsedDate? =
        datePattern.findAll(input)
            .mapNotNull { match -> parseDateSpec(match.value)?.let { ParsedDate(it, match.range) } }
            .firstOrNull()

    /**
     * Every duration phrase in [input] (五分钟, 一个半小时, 一刻钟, 两个小时, 90秒, 1分30秒),
     * with directly adjacent components merged into one duration.
     */
    fun durations(input: String): List<ParsedDuration> {
        val merged = mutableListOf<ParsedDuration>()
        durationComponentPattern.findAll(input).forEach { match ->
            val seconds = match.componentSeconds() ?: return@forEach
            val previous = merged.lastOrNull()
            if (previous != null &&
                durationJoinerPattern.matches(input.substring(previous.range.last + 1, match.range.first))
            ) {
                merged[merged.lastIndex] = ParsedDuration(
                    seconds = previous.seconds + seconds,
                    range = previous.range.first..match.range.last,
                )
            } else {
                merged += ParsedDuration(seconds = seconds, range = match.range)
            }
        }
        return merged
    }

    /**
     * Relative delays such as "五分钟后", "在15分钟以后", "过半小时": a duration followed by 后/以后/之后
     * or preceded by 过. The returned range includes the 在/过 prefix and the 后 suffix.
     */
    fun relativeDelays(input: String): List<ParsedDuration> =
        durations(input).mapNotNull { duration ->
            val before = input.substring(0, duration.range.first)
            val after = input.substring(duration.range.last + 1)
            val prefix = relativePrefixPattern.find(before)
            val suffix = afterSuffixPattern.find(after)
            val prefixIsGuo = prefix?.value?.trim()?.startsWith("过") == true
            if (suffix == null && !prefixIsGuo) return@mapNotNull null
            val start = prefix?.range?.first ?: duration.range.first
            val end = suffix?.let { duration.range.last + it.range.last + 1 } ?: duration.range.last
            ParsedDuration(seconds = duration.seconds, range = start..end)
        }

    /** "6月19日 周五" in [zoneId]; confirmation summaries show this instead of raw epoch millis. */
    fun formatDate(millis: Long, zoneId: ZoneId): String {
        val dateTime = Instant.ofEpochMilli(millis).atZone(zoneId)
        val weekday = "一二三四五六日"[dateTime.dayOfWeek.value - 1]
        return "${dateTime.monthValue}月${dateTime.dayOfMonth}日 周$weekday"
    }

    fun formatTime(millis: Long, zoneId: ZoneId): String {
        val dateTime = Instant.ofEpochMilli(millis).atZone(zoneId)
        return "%02d:%02d".format(dateTime.hour, dateTime.minute)
    }

    /** "6月19日 周五 15:00". */
    fun formatDateTime(millis: Long, zoneId: ZoneId): String =
        "${formatDate(millis, zoneId)} ${formatTime(millis, zoneId)}"

    /** Rounds a delay up to whole minutes (a reminder must never fire early), minimum one minute. */
    fun ceilMinutes(seconds: Double): Long = ceil(seconds / 60.0).toLong().coerceAtLeast(1L)

    fun roundSeconds(seconds: Double): Long = seconds.roundToLong().coerceAtLeast(1L)

    private fun MatchResult.componentSeconds(): Double? {
        val unit = groups["unit"]?.value ?: return null
        val amount = when {
            groups["half"] != null -> 0.5
            else -> {
                val base = ChineseNumerals.parseAmount(groups["amount"]?.value.orEmpty()) ?: return null
                base + if (groups["plusHalf"] != null) 0.5 else 0.0
            }
        }
        val unitSeconds = when (unit) {
            "小时", "钟头" -> 3600.0
            "分钟", "分" -> 60.0
            "刻钟" -> 900.0
            else -> 1.0
        }
        return (amount * unitSeconds).takeIf { it > 0.0 }
    }

    private data class RawClock(
        val rawHour: Int,
        val hour: Int,
        val minute: Int,
        val date: DateSpec?,
        val hasExplicitPeriod: Boolean,
        val range: IntRange,
        val detachedDateRange: IntRange? = null,
    ) {
        fun asParsed(): ParsedClockTime =
            ParsedClockTime(hour = hour, minute = minute, date = date, range = range, detachedDateRange = detachedDateRange)
    }

    private fun MatchResult.toClockTime(input: String): RawClock? {
        val dateText = groups["date"]?.value
        val date = dateText?.let { parseDateSpec(it) ?: return null }
        val explicitPeriod = groups["period"]?.value
        val hourGroup = groups["hour"]
        if (hourGroup != null && dateText == null && explicitPeriod == null &&
            hourGroup.value.none { it.isDigit() } &&
            hourGroup.range.first > 0 && input[hourGroup.range.first - 1] in QUANTITY_PREFIX
        ) {
            // "快一点"/"喝一点"/"有三点": a quantity, not a clock time.
            return null
        }
        val period = explicitPeriod ?: date?.let(::periodFromDateWord)
        val (rawHour, minute) = when {
            groups["beforeHour"] != null -> {
                val hour = ChineseNumerals.parseInt(groups["beforeHour"]!!.value) ?: return null
                val before = ChineseNumerals.parseInt(groups["beforeMin"]!!.value) ?: return null
                if (before !in 1..59) return null
                (hour - 1) to (60 - before)
            }

            groups["colonHour"] != null -> {
                val hour = groups["colonHour"]!!.value.toIntOrNull() ?: return null
                val minute = groups["colonMin"]!!.value.toIntOrNull() ?: return null
                hour to minute
            }

            else -> {
                val hourText = groups["hour"]?.value ?: return null
                val hour = ChineseNumerals.parseInt(hourText) ?: return null
                val toMinutes = groups["toMin"]?.value?.let { ChineseNumerals.parseInt(it) ?: return null }
                if (toMinutes != null) {
                    if (toMinutes !in 1..59) return null
                    (hour - 1) to (60 - toMinutes)
                } else {
                    val minute = when {
                        groups["half"] != null -> 30
                        groups["quarter"] != null -> 15
                        groups["threeQuarters"] != null -> 45
                        groups["digitMin"] != null -> groups["digitMin"]!!.value.toIntOrNull() ?: return null
                        groups["tensMin"] != null -> ChineseNumerals.parseInt(groups["tensMin"]!!.value) ?: return null
                        groups["unitMin"] != null -> ChineseNumerals.parseInt(groups["unitMin"]!!.value) ?: return null
                        else -> 0
                    }
                    hour to minute
                }
            }
        }
        if (minute !in 0..59 || rawHour !in 0..23) return null
        val hour = normalizedHour(rawHour, period) ?: return null
        return RawClock(
            rawHour = rawHour,
            hour = hour,
            minute = minute,
            date = date,
            hasExplicitPeriod = explicitPeriod != null,
            range = range,
        )
    }

    fun parseDateSpec(text: String): DateSpec? {
        val compact = text.replace(Regex("""\s+"""), "")
        when (compact) {
            "今天", "今晚", "今早" -> return DateSpec.RelativeDays(0, compact)
            "明天", "明早", "明晚" -> return DateSpec.RelativeDays(1, compact)
            "后天" -> return DateSpec.RelativeDays(2, compact)
            "大后天" -> return DateSpec.RelativeDays(3, compact)
        }
        Regex("""^(下个?|这个?|本)?(?:周|星期|礼拜)([一二三四五六日天])$""").matchEntire(compact)?.let { match ->
            val scope = when (match.groupValues[1].take(1)) {
                "下" -> DateSpec.WeekScope.Next
                "这", "本" -> DateSpec.WeekScope.This
                else -> DateSpec.WeekScope.Nearest
            }
            val dayOfWeek = when (match.groupValues[2]) {
                "一" -> DayOfWeek.MONDAY
                "二" -> DayOfWeek.TUESDAY
                "三" -> DayOfWeek.WEDNESDAY
                "四" -> DayOfWeek.THURSDAY
                "五" -> DayOfWeek.FRIDAY
                "六" -> DayOfWeek.SATURDAY
                else -> DayOfWeek.SUNDAY
            }
            return DateSpec.Weekday(dayOfWeek, scope)
        }
        Regex("""^([^月]+)月([^日号]+)[日号]$""").matchEntire(compact)?.let { match ->
            val month = ChineseNumerals.parseInt(match.groupValues[1]) ?: return null
            val day = ChineseNumerals.parseInt(match.groupValues[2]) ?: return null
            if (month !in 1..12 || day !in 1..31) return null
            return DateSpec.MonthDay(month, day)
        }
        return null
    }

    private fun periodFromDateWord(date: DateSpec): String? =
        when ((date as? DateSpec.RelativeDays)?.word) {
            "今晚", "明晚" -> "晚上"
            "今早", "明早" -> "早上"
            else -> null
        }

    /**
     * Applies a day-period word to a raw hour. 晚上/夜里 + 12 is ambiguous (noon vs. midnight) and
     * fails closed; the clock-face hour 0..23 is used verbatim when no period is given.
     */
    private fun normalizedHour(rawHour: Int, periodWord: String?): Int? =
        when (periodWord) {
            "下午", "傍晚" -> when (rawHour) {
                in 1..11 -> rawHour + 12
                in 12..23 -> rawHour
                else -> null
            }

            "晚上", "夜里", "夜晚" -> when (rawHour) {
                in 1..11 -> rawHour + 12
                in 13..23 -> rawHour
                else -> null
            }

            "中午" -> when (rawHour) {
                in 1..10 -> rawHour + 12
                in 11..12 -> rawHour
                else -> null
            }

            "凌晨", "半夜", "清晨", "早晨", "早上", "上午" -> when (rawHour) {
                12 -> 0
                in 0..11 -> rawHour
                else -> null
            }

            else -> rawHour
        }
}
