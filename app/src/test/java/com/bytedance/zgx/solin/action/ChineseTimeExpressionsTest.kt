package com.bytedance.zgx.solin.action

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Table-driven coverage for the shared Chinese time/number normalizer behind alarms, timers,
 * reminders and periodic checks. Clock: Wednesday 2026-06-17 10:00 Asia/Shanghai.
 */
class ChineseTimeExpressionsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.parse("2026-06-17T10:00:00").atZone(zone).toInstant().toEpochMilli()
    private val planner = MobileActionPlanner(clockMillis = { now }, zoneId = zone)

    @Test
    fun chineseNumeralsParse() {
        val cases = mapOf(
            "零" to 0, "〇" to 0, "一" to 1, "两" to 2, "九" to 9, "十" to 10, "十二" to 12,
            "二十" to 20, "二十五" to 25, "五十九" to 59, "一百" to 100, "一百零五" to 105,
            "一百二十" to 120, "两百" to 200, "两千" to 2000, "15" to 15,
        )
        cases.forEach { (text, expected) -> assertEquals(text, expected, ChineseNumerals.parseInt(text)) }
        listOf("", "二三", "十十", "百十百", "abc", "一点").forEach { text ->
            assertEquals(text, null, ChineseNumerals.parseInt(text))
        }
    }

    @Test
    fun alarmClockTimes() {
        val cases = listOf(
            "三点半的闹钟" to "03:30 once",
            "设一个三点一刻的闹钟" to "03:15 once",
            "三点三刻的闹钟" to "03:45 once",
            "差五分四点的闹钟" to "03:55 once",
            "四点差五分的闹钟" to "03:55 once",
            "十点二十的闹钟" to "10:20 once",
            "十点二十分的闹钟" to "10:20 once",
            "两点的闹钟" to "02:00 once",
            "十二点的闹钟" to "12:00 once",
            "零点的闹钟" to "00:00 once",
            "中午十二点的闹钟" to "12:00 once",
            "晚上十一点半叫醒我" to "23:30 once",
            "凌晨一点的闹钟" to "01:00 once",
            "下午三点零五分的闹钟" to "15:05 once",
            "三点整的闹钟" to "03:00 once",
            "明天早上七点叫醒我" to "07:00 once",
            "明早七点叫醒我" to "07:00 once",
            "每天早上七点叫醒我" to "07:00 daily",
            "每天7:30叫醒我" to "07:30 daily",
            "设置明天7点的闹钟" to "07:00 once",
            "set an alarm for 6:45" to "06:45 once",
        )
        cases.forEach { (input, expected) ->
            val draft = planner.plan(input).draft
            assertEquals(input, MobileActionFunctions.SET_SYSTEM_ALARM, draft?.functionName)
            val actual = "%02d:%02d %s".format(
                draft!!.parameters.getValue("hour").toInt(),
                draft.parameters.getValue("minutes").toInt(),
                draft.parameters.getValue("recurrence"),
            )
            assertEquals(input, expected, actual)
            assertEquals(input, null, draft.parameters["message"])
        }
    }

    @Test
    fun alarmFailsClosedForUnsupportedDatesAndAmbiguousTimes() {
        listOf(
            "后天早上七点叫醒我",
            "周五早上七点的闹钟",
            "6月20日早上七点的闹钟",
            "晚上十二点的闹钟",
            "二十五点的闹钟",
            "快一点设闹钟",
        ).forEach { input ->
            assertEquals(input, null, planner.plan(input).draft?.takeIf { it.functionName == MobileActionFunctions.SET_SYSTEM_ALARM })
        }
    }

    @Test
    fun absoluteReminderTriggerTimes() {
        val cases = listOf(
            "提醒我三点半开会" to ("2026-06-18T03:30" to "开会"),
            "提醒我下午三点半开会" to ("2026-06-17T15:30" to "开会"),
            "提醒我晚上十一点半睡觉" to ("2026-06-17T23:30" to "睡觉"),
            "提醒我中午十二点吃饭" to ("2026-06-17T12:00" to "吃饭"),
            "提醒我凌晨一点收衣服" to ("2026-06-18T01:00" to "收衣服"),
            "提醒我两点一起吃饭" to ("2026-06-18T02:00" to "一起吃饭"),
            "提醒我十点二十开会" to ("2026-06-17T10:20" to "开会"),
            "提醒我差五分四点开会" to ("2026-06-18T03:55" to "开会"),
            "提醒我周五下午三点开会" to ("2026-06-19T15:00" to "开会"),
            "星期五下午三点提醒我开会" to ("2026-06-19T15:00" to "开会"),
            "礼拜五下午三点提醒我开会" to ("2026-06-19T15:00" to "开会"),
            "提醒我周三上午九点开会" to ("2026-06-24T09:00" to "开会"),
            "提醒我周三下午三点开会" to ("2026-06-17T15:00" to "开会"),
            "提醒我下周一上午九点交报告" to ("2026-06-22T09:00" to "交报告"),
            "提醒我这周日晚上八点给妈妈打电话" to ("2026-06-21T20:00" to "给妈妈打电话"),
            "提醒我本周六上午十点去健身" to ("2026-06-20T10:00" to "去健身"),
            "提醒我6月20日下午三点交房租" to ("2026-06-20T15:00" to "交房租"),
            "提醒我六月二十号上午九点体检" to ("2026-06-20T09:00" to "体检"),
            "提醒我大后天早上八点出发" to ("2026-06-20T08:00" to "出发"),
            "明天提醒我早上八点开会" to ("2026-06-18T08:00" to "开会"),
            "今晚八点提醒我倒垃圾" to ("2026-06-17T20:00" to "倒垃圾"),
            "提醒我明天9点开会" to ("2026-06-18T09:00" to "开会"),
            "提醒我今天下午3点喝水" to ("2026-06-17T15:00" to "喝水"),
        )
        cases.forEach { (input, expected) ->
            val draft = planner.plan(input).draft
            assertEquals(input, MobileActionFunctions.SCHEDULE_REMINDER, draft?.functionName)
            val trigger = draft!!.parameters.getValue("triggerAtMillis").toLong()
            val local = Instant.ofEpochMilli(trigger).atZone(zone).toLocalDateTime().toString()
            assertEquals(input, expected.first, local)
            assertEquals(input, expected.second, draft.parameters.getValue("title"))
            assertEquals(input, null, draft.parameters["delayMinutes"])
        }
    }

    @Test
    fun absoluteReminderFailsClosedForPastOrImpossibleDates() {
        listOf(
            "提醒我今天早上八点开会",
            "提醒我这周一上午十点开会",
            "提醒我3月1日上午九点交税",
            "提醒我2月30日上午九点交税",
            "提醒我晚上十二点睡觉",
            "提醒我喝一点水",
        ).forEach { input ->
            val draft = planner.plan(input).draft
            assertEquals(input, null, draft?.parameters?.get("triggerAtMillis"))
        }
    }

    @Test
    fun relativeReminderDelays() {
        val cases = listOf(
            "提醒我五分钟后喝水" to ("5" to "喝水"),
            "半小时后提醒我喝水" to ("30" to "喝水"),
            "提醒我半个小时后喝水" to ("30" to "喝水"),
            "提醒我一个半小时后出门" to ("90" to "出门"),
            "提醒我一刻钟后关火" to ("15" to "关火"),
            "两个小时后提醒我开会" to ("120" to "开会"),
            "90秒后提醒我关火" to ("2" to "关火"),
            "提醒我1小时30分钟后出发" to ("90" to "出发"),
            "提醒我一小时二十分钟后出发" to ("80" to "出发"),
            "提醒我在十五分钟以后喝水" to ("15" to "喝水"),
            "提醒我 15 分钟后喝水" to ("15" to "喝水"),
            "提醒我 1.5 小时后喝水" to ("90" to "喝水"),
        )
        cases.forEach { (input, expected) ->
            val draft = planner.plan(input).draft
            assertEquals(input, MobileActionFunctions.SCHEDULE_REMINDER, draft?.functionName)
            assertEquals(input, expected.first, draft!!.parameters["delayMinutes"])
            assertEquals(input, expected.second, draft.parameters["title"])
        }
    }

    @Test
    fun timerDurations() {
        val cases = listOf(
            "倒计时五分钟" to "300",
            "倒计时十分钟" to "600",
            "倒计时一个半小时" to "5400",
            "设置半小时的倒计时" to "1800",
            "倒计时一刻钟" to "900",
            "倒计时90秒" to "90",
            "倒计时1分30秒" to "90",
            "倒计时一分三十秒" to "90",
            "倒计时两个小时" to "7200",
            "倒计时20分钟" to "1200",
            "set a timer for 10 minutes" to "600",
        )
        cases.forEach { (input, expected) ->
            val draft = planner.plan(input).draft
            assertEquals(input, MobileActionFunctions.SET_SYSTEM_TIMER, draft?.functionName)
            assertEquals(input, expected, draft!!.parameters["lengthSeconds"])
            assertEquals(input, null, draft.parameters["message"])
        }
        assertEquals(null, planner.plan("倒计时二十五小时").draft)
        assertEquals("开会", planner.plan("提醒我明天9点的开会").draft?.parameters?.get("title"))
    }

    @Test
    fun timerLabelKeepsNonDurationText() {
        val draft = planner.plan("倒计时十分钟煮鸡蛋").draft
        assertEquals("600", draft?.parameters?.get("lengthSeconds"))
        assertEquals("煮鸡蛋", draft?.parameters?.get("message"))
    }

    @Test
    fun periodicCheckIntervalAcceptsChineseNumerals() {
        val draft = planner.plan("开启周期检查，每两个小时一次").draft
        assertEquals(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK, draft?.functionName)
        assertEquals("120", draft?.parameters?.get("intervalMinutes"))
    }

    @Test
    fun calendarDraftPrefillsResolvedTimeOrAllDayDate() {
        val cases = listOf(
            "帮我建个日程：周五下午三点评审" to "2026-06-19T15:00..2026-06-19T16:00 allDay=false",
            "新建日程 明天上午十点半 周会" to "2026-06-18T10:30..2026-06-18T11:30 allDay=false",
            "帮我建个日程：周五评审" to "2026-06-19T00:00..2026-06-20T00:00 allDay=true",
            "添加日程 下周一 出差" to "2026-06-22T00:00..2026-06-23T00:00 allDay=true",
            "添加日程 6月30日 交付" to "2026-06-30T00:00..2026-07-01T00:00 allDay=true",
        )
        cases.forEach { (input, expected) ->
            val draft = planner.plan(input).draft
            assertEquals(input, MobileActionFunctions.CREATE_CALENDAR_EVENT, draft?.functionName)
            val begin = draft!!.parameters.getValue("beginTimeMillis").toLong()
            val end = draft.parameters.getValue("endTimeMillis").toLong()
            val actual = "${Instant.ofEpochMilli(begin).atZone(zone).toLocalDateTime()}.." +
                "${Instant.ofEpochMilli(end).atZone(zone).toLocalDateTime()} allDay=${draft.parameters["allDay"]}"
            assertEquals(input, expected, actual)
        }
        val timed = planner.plan("帮我建个日程：周五下午三点评审").draft!!
        assertEquals("将打开日历新建事件页面，时间：6月19日 周五 15:00–16:00。", timed.summary)
        listOf("帮我建个日程：评审", "添加日程 3月1日 交付", "添加日程 今天早上八点 晨会").forEach { input ->
            val draft = planner.plan(input).draft
            assertEquals(input, MobileActionFunctions.CREATE_CALENDAR_EVENT, draft?.functionName)
            assertEquals(input, null, draft!!.parameters["beginTimeMillis"])
            assertEquals(input, "将打开日历新建事件页面。", draft.summary)
        }
    }

    @Test
    fun absoluteReminderSummaryShowsResolvedLocalTime() {
        val draft = planner.plan("提醒我周五下午三点开会").draft
        assertEquals("将在 6月19日 周五 15:00 提醒：开会", draft?.summary)
    }

    @Test
    fun dateOnlyPhrasesResolveAgainstFixedWeek() {
        val today = LocalDateTime.parse("2026-06-17T10:00:00").toLocalDate()
        val cases = mapOf(
            "周五" to "2026-06-19",
            "星期三" to "2026-06-17",
            "下周一" to "2026-06-22",
            "下星期日" to "2026-06-28",
            "这周日" to "2026-06-21",
            "本周六" to "2026-06-20",
            "大后天" to "2026-06-20",
            "6月20日" to "2026-06-20",
            "十二月一号" to "2026-12-01",
        )
        cases.forEach { (text, expected) ->
            val parsed = ChineseTimeExpressions.parseDate("开会$text")
            assertEquals(text, expected, parsed?.spec?.resolveDate(today)?.toString())
        }
        assertEquals(null, ChineseTimeExpressions.parseDate("这个星期一直下雨"))
        assertEquals(null, ChineseTimeExpressions.parseDate("这周一")?.spec?.resolveDate(today))
    }
}
