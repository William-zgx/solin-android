package com.bytedance.zgx.solin.tool

import com.bytedance.zgx.solin.action.MobileActionFunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRetrieverTest {
    private val firstStepTools = defaultBuiltInToolRegistry.specs().filter { spec -> spec.isFirstStepPlanningCandidate() }

    private fun retrievedNames(request: String, limit: Int = DEFAULT_TOOL_RETRIEVAL_LIMIT): List<String> =
        ToolRetriever.retrieve(request, firstStepTools, limit).map { spec -> spec.name }

    @Test
    fun alarmRequestRanksSystemAlarmFirst() {
        assertEquals(MobileActionFunctions.SET_SYSTEM_ALARM, retrievedNames("明天早上7点叫我起床").first())
        assertEquals(MobileActionFunctions.SET_SYSTEM_ALARM, retrievedNames("帮我定一个早上六点半的闹钟").first())
    }

    @Test
    fun timerRequestRanksSystemTimerFirst() {
        assertEquals(MobileActionFunctions.SET_SYSTEM_TIMER, retrievedNames("倒计时10分钟").first())
    }

    @Test
    fun openAppRequestRanksOpenAppByNameFirst() {
        assertEquals(MobileActionFunctions.OPEN_APP_BY_NAME, retrievedNames("打开微信").first())
    }

    @Test
    fun inAppSearchRetrievesAppLaunchAndSearch() {
        val names = retrievedNames("在小红书里搜索咖啡店")

        assertTrue(names.toString(), MobileActionFunctions.OPEN_APP_BY_NAME in names)
        assertTrue(names.toString(), MobileActionFunctions.WEB_SEARCH in names)
    }

    @Test
    fun inAppSearchPhrasingsRetrieveOpenAppByName() {
        listOf("在淘宝搜索牛奶", "打开小红书搜咖啡", "去京东上搜一下耳机", "用抖音搜索猫咪视频").forEach { request ->
            val names = retrievedNames(request)
            assertTrue("$request -> $names", MobileActionFunctions.OPEN_APP_BY_NAME in names)
        }
    }

    @Test
    fun arithmeticAndConversionRetrieveCalculate() {
        listOf("23*17+5", "1.5 / 3", "3.5公里等于多少米", "23乘以47等于多少", "128除以4", "算一下 99+1").forEach { request ->
            val names = retrievedNames(request)
            assertTrue("$request -> $names", MobileActionFunctions.CALCULATE in names)
        }
        assertEquals(MobileActionFunctions.CALCULATE, retrievedNames("23*17+5").first())
    }

    @Test
    fun datesAndPhoneNumbersAreNotArithmetic() {
        listOf("2024-05-01那天发生了什么", "我的号码是138-1234-5678").forEach { request ->
            val names = retrievedNames(request)
            assertFalse("$request -> $names", MobileActionFunctions.CALCULATE in names)
        }
    }

    @Test
    fun alarmPhrasingsRetrieveSystemAlarm() {
        listOf("明天早上7点叫我起床", "定个6点半的闹钟", "set an alarm for 7am", "wake me up at 6").forEach { request ->
            val names = retrievedNames(request)
            assertTrue("$request -> $names", MobileActionFunctions.SET_SYSTEM_ALARM in names)
        }
    }

    @Test
    fun wifiAndBluetoothRequestsRankTheirSettingsPageFirst() {
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, retrievedNames("打开WiFi").first())
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, retrievedNames("连一下无线网").first())
        assertEquals(MobileActionFunctions.OPEN_SYSTEM_SETTINGS, retrievedNames("打开蓝牙").first())
    }

    @Test
    fun webSearchRequestRanksWebSearchFirst() {
        assertEquals(MobileActionFunctions.WEB_SEARCH, retrievedNames("搜索一下今天的新闻").first())
        assertEquals(MobileActionFunctions.WEB_SEARCH, retrievedNames("查一下北京明天天气").first())
    }

    @Test
    fun shareRequestRanksShareTextFirst() {
        assertEquals(MobileActionFunctions.SHARE_TEXT, retrievedNames("把这段文字分享出去").first())
    }

    @Test
    fun calendarRequestRanksCalendarDraftFirst() {
        assertEquals(
            MobileActionFunctions.CREATE_CALENDAR_EVENT,
            retrievedNames("帮我在日历里加一个明天下午三点的会议").first(),
        )
    }

    @Test
    fun navigationRequestRanksMapSearchFirst() {
        assertEquals(MobileActionFunctions.SEARCH_MAPS, retrievedNames("导航到北京南站").first())
    }

    @Test
    fun plainQuestionsRetrieveNothing() {
        listOf("为什么天空是蓝色的", "给我讲个笑话", "你好", "解释一下量子纠缠").forEach { question ->
            assertEquals(question, emptyList<String>(), retrievedNames(question))
        }
    }

    @Test
    fun firstStepScopeExcludesGesturesOrchestrationAndQuestions() {
        val names = firstStepTools.map { spec -> spec.name }.toSet()

        listOf(
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.NOTE,
            MobileActionFunctions.FINISH,
            MobileActionFunctions.TAKE_OVER,
            MobileActionFunctions.ASK_USER,
        ).forEach { excluded -> assertFalse(excluded, excluded in names) }
        assertTrue(firstStepTools.none { spec -> spec.capability == ToolCapability.DeviceControl })
        assertTrue(MobileActionFunctions.SET_SYSTEM_ALARM in names)
    }

    @Test
    fun neverReturnsToolsOutsideTheCandidates() {
        val candidates = firstStepTools.filter { spec ->
            spec.name == MobileActionFunctions.SET_SYSTEM_ALARM || spec.name == MobileActionFunctions.SET_SYSTEM_TIMER
        }

        assertEquals(emptyList<ToolSpec>(), ToolRetriever.retrieve("打开WiFi", candidates))
        assertEquals(
            listOf(MobileActionFunctions.SET_SYSTEM_TIMER),
            ToolRetriever.retrieve("倒计时10分钟", candidates).map { spec -> spec.name },
        )
    }

    @Test
    fun ignoresTheArgumentSchemaSoAnyPromptFormatGetsTheSameShortlist() {
        val schemaFree = firstStepTools.map { spec -> spec.copy(inputSchemaJson = "{}") }

        listOf("定个6点半的闹钟", "在淘宝搜索牛奶", "23*17+5", "导航到北京南站").forEach { request ->
            assertEquals(
                request,
                retrievedNames(request),
                ToolRetriever.retrieve(request, schemaFree).map { spec -> spec.name },
            )
        }
    }

    @Test
    fun respectsLimitAndIsDeterministic() {
        val request = "打开设置"

        assertTrue(retrievedNames(request, limit = 2).size <= 2)
        assertEquals(emptyList<String>(), retrievedNames(request, limit = 0))
        assertEquals(emptyList<String>(), retrievedNames("   "))
        assertEquals(retrievedNames(request), retrievedNames(request))
        assertTrue(retrievedNames(request).size <= DEFAULT_TOOL_RETRIEVAL_LIMIT)
    }
}
