package com.bytedance.zgx.pocketmind.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlannerTest {
    private val planner = MobileActionPlanner()

    @Test
    fun intentCandidateRequiresMediumConfidenceBeforePlanning() {
        assertFalse(
            IntentCandidate(
                toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS,
                confidence = ActionIntentConfidence.Low,
                reason = "ambiguous",
            ).isAction,
        )
        assertTrue(
            IntentCandidate(
                toolName = null,
                confidence = ActionIntentConfidence.Medium,
                reason = "runtime classifier accepted",
            ).isAction,
        )
    }

    @Test
    fun parsesWhitelistedCallOutputIntoConfirmedDraft() {
        val draft = planner.parseModelOutput(
            """call:compose_email{"subject":"Hi","body":"明天聊"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.COMPOSE_EMAIL, draft.functionName)
        assertEquals("Hi", draft.parameters["subject"])
        assertEquals("明天聊", draft.parameters["body"])
        assertTrue(draft.requiresConfirmation)
    }

    @Test
    fun strictModelToolOutputParsesSinglePrimitiveJsonObject() {
        val parsed = planner.parseModelToolOutput(
            """call:query_recent_files{"kind":"images","maxCount":2,"includeHidden":false}""",
        )

        val draft = (parsed as ModelToolOutputParseResult.Parsed).draft
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, draft.functionName)
        assertEquals("images", draft.parameters["kind"])
        assertEquals("2", draft.parameters["maxCount"])
        assertEquals("false", draft.parameters["includeHidden"])
    }

    @Test
    fun strictModelToolOutputIgnoresOrdinaryAnswers() {
        assertEquals(ModelToolOutputParseResult.None, planner.parseModelToolOutput("可以用 web_search，但需要确认。"))
    }

    @Test
    fun strictModelToolOutputRejectsBadOrNestedCalls() {
        val badJson = planner.parseModelToolOutput("""call:web_search{"query":}""")
        assertTrue(badJson is ModelToolOutputParseResult.Rejected)

        val nested = planner.parseModelToolOutput("""call:web_search{"query":{"text":"Kotlin"}}""")
        assertTrue(nested is ModelToolOutputParseResult.Rejected)

        val multi = planner.parseModelToolOutput(
            """
            call:web_search{"query":"Kotlin"}
            call:open_wifi_settings{}
            """.trimIndent(),
        )
        assertTrue(multi is ModelToolOutputParseResult.Rejected)
    }

    @Test
    fun strictModelToolOutputRejectsUnknownTools() {
        val rejected = planner.parseModelToolOutput("""call:delete_contact{}""")

        rejected as ModelToolOutputParseResult.Rejected
        assertEquals("delete_contact", rejected.toolName)
        assertTrue(rejected.reason.contains("Unknown tool"))
    }

    @Test
    fun parsesHermesStyleWebSearchCallOutput() {
        val draft = planner.parseModelOutput(
            """call:web_search{"query":"Kotlin coroutines Android"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.WEB_SEARCH, draft.functionName)
        assertEquals("Kotlin coroutines Android", draft.parameters["query"])
        assertTrue(draft.summary.contains("Web 搜索工具"))
        assertFalse(draft.requiresConfirmation)
    }

    @Test
    fun parsesCalendarAvailabilityCallOutput() {
        val draft = planner.parseModelOutput(
            """call:query_calendar_availability{"start":"2026-06-01T09:00:00Z","end":"2026-06-01T10:00:00Z"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY, draft.functionName)
        assertEquals("2026-06-01T09:00:00Z", draft.parameters["start"])
        assertEquals("2026-06-01T10:00:00Z", draft.parameters["end"])
        assertTrue(draft.summary.contains("只读查询"))
    }

    @Test
    fun parsesRecentFilesCallOutput() {
        val draft = planner.parseModelOutput(
            """call:query_recent_files{"kind":"images","maxCount":"4"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, draft.functionName)
        assertEquals("images", draft.parameters["kind"])
        assertEquals("4", draft.parameters["maxCount"])
        assertTrue(draft.summary.contains("最近"))
        assertTrue(draft.summary.contains("文件名"))
    }

    @Test
    fun parsesRecentScreenshotOcrCallOutput() {
        val draft = planner.parseModelOutput(
            """call:read_recent_screenshot_ocr{"maxCount":"1"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, draft.functionName)
        assertEquals("1", draft.parameters["maxCount"])
        assertTrue(draft.summary.contains("本地提取 OCR"))
    }

    @Test
    fun parsesRecentImageOcrCallOutput() {
        val draft = planner.parseModelOutput(
            """call:read_recent_image_ocr{"maxCount":"3"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, draft.functionName)
        assertEquals("3", draft.parameters["maxCount"])
        assertTrue(draft.summary.contains("最近 3 张图片"))
        assertTrue(draft.summary.contains("本地提取"))
    }

    @Test
    fun parsesCurrentScreenshotOcrCallOutput() {
        val draft = planner.parseModelOutput(
            """call:capture_current_screenshot_ocr{"captureMode":"current_screen"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR, draft.functionName)
        assertEquals("current_screen", draft.parameters["captureMode"])
        assertTrue(draft.summary.contains("MediaProjection"))
        assertTrue(draft.summary.contains("不会保存图片"))
    }

    @Test
    fun parsesCurrentScreenTextCallOutput() {
        val draft = planner.parseModelOutput(
            """call:read_current_screen_text{"maxChars":"1200"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, draft.functionName)
        assertEquals("1200", draft.parameters["maxChars"])
        assertTrue(draft.summary.contains("当前屏幕"))
        assertTrue(draft.summary.contains("不会读取截图"))
    }

    @Test
    fun parsesContactQueryCallOutput() {
        val draft = planner.parseModelOutput(
            """call:query_contacts{"query":"Alice","maxCount":"2"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, draft.functionName)
        assertEquals("Alice", draft.parameters["query"])
        assertEquals("2", draft.parameters["maxCount"])
        assertTrue(draft.summary.contains("查询最多 2 个联系人"))
    }

    @Test
    fun parsesUsageAccessSettingsCallOutput() {
        val draft = planner.parseModelOutput("""call:open_usage_access_settings{}""")

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, draft.functionName)
        assertTrue(draft.summary.contains("使用情况访问权限"))
        assertTrue(draft.parameters.isEmpty())
    }

    @Test
    fun parsesPeriodicCheckCallOutput() {
        val draft = planner.parseModelOutput(
            """call:configure_periodic_check{"enabled":"true","intervalMinutes":"120"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK, draft.functionName)
        assertEquals("true", draft.parameters["enabled"])
        assertEquals("120", draft.parameters["intervalMinutes"])
        assertTrue(draft.summary.contains("周期检查"))
        assertTrue(draft.requiresConfirmation)
    }

    @Test
    fun parsesBackgroundTasksQueryCallOutput() {
        val draft = planner.parseModelOutput(
            """call:query_background_tasks{"scope":"all","maxCount":"5"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.QUERY_BACKGROUND_TASKS, draft.functionName)
        assertEquals("all", draft.parameters["scope"])
        assertEquals("5", draft.parameters["maxCount"])
        assertTrue(draft.summary.contains("只读查询"))
        assertTrue(draft.summary.contains("不返回提醒正文"))
        assertTrue(draft.requiresConfirmation)
    }

    @Test
    fun strictModelToolOutputParsesPeriodicCheckBooleans() {
        val parsed = planner.parseModelToolOutput(
            """call:configure_periodic_check{"enabled":true,"intervalMinutes":120,"requiresCharging":false}""",
        )

        val draft = (parsed as ModelToolOutputParseResult.Parsed).draft
        assertEquals(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK, draft.functionName)
        assertEquals("true", draft.parameters["enabled"])
        assertEquals("120", draft.parameters["intervalMinutes"])
        assertEquals("false", draft.parameters["requiresCharging"])
    }

    @Test
    fun rejectsUnsupportedFunctionCalls() {
        assertNull(planner.parseModelOutput("""call:delete_contact{"name":"A"}"""))
    }

    @Test
    fun infersDraftForNaturalLanguageAction() {
        val plan = planner.plan("帮我打开 Wi-Fi 设置")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, plan.draft?.functionName)
    }

    @Test
    fun classifiesIntentBeforeSlotFillingBoundary() {
        val action = planner.classifyIntent("帮我打开 Wi-Fi 设置")
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, action.toolName)
        assertEquals(ActionIntentConfidence.High, action.confidence)
        assertTrue(action.reason.contains("rule"))

        val chat = planner.classifyIntent("解释一下 Wi-Fi 设置 API 怎么实现")
        assertFalse(chat.isAction)
        assertEquals(ActionIntentConfidence.None, chat.confidence)
    }

    @Test
    fun infersPeriodicCheckDraftsAndRejectsDiscussionInputs() {
        val enablePlan = planner.plan("开启周期检查，每 2 小时")

        assertEquals(ActionPlanKind.Draft, enablePlan.kind)
        assertEquals(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK, enablePlan.draft?.functionName)
        assertEquals("true", enablePlan.draft?.parameters?.get("enabled"))
        assertEquals("120", enablePlan.draft?.parameters?.get("intervalMinutes"))

        val disablePlan = planner.plan("disable periodic check")
        assertEquals(ActionPlanKind.Draft, disablePlan.kind)
        assertEquals(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK, disablePlan.draft?.functionName)
        assertEquals("false", disablePlan.draft?.parameters?.get("enabled"))

        assertEquals(ActionPlanKind.NoAction, planner.plan("周期检查是什么").kind)
        assertFalse(planner.isLikelyAction("how to implement periodic check"))
    }

    @Test
    fun infersBackgroundTasksQueryDraftsAndRejectsMutationsOrDiscussion() {
        val activePlan = planner.plan("查看后台任务")
        assertEquals(ActionPlanKind.Draft, activePlan.kind)
        assertEquals(MobileActionFunctions.QUERY_BACKGROUND_TASKS, activePlan.draft?.functionName)
        assertEquals("active", activePlan.draft?.parameters?.get("scope"))

        val historyPlan = planner.plan("查看最近 3 条后台任务历史")
        assertEquals(ActionPlanKind.Draft, historyPlan.kind)
        assertEquals(MobileActionFunctions.QUERY_BACKGROUND_TASKS, historyPlan.draft?.functionName)
        assertEquals("history", historyPlan.draft?.parameters?.get("scope"))
        assertEquals("3", historyPlan.draft?.parameters?.get("maxCount"))

        val policyPlan = planner.plan("周期检查状态")
        assertEquals(ActionPlanKind.Draft, policyPlan.kind)
        assertEquals(MobileActionFunctions.QUERY_BACKGROUND_TASKS, policyPlan.draft?.functionName)
        assertEquals("policy", policyPlan.draft?.parameters?.get("scope"))

        val englishPlan = planner.plan("list background tasks")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.QUERY_BACKGROUND_TASKS, englishPlan.draft?.functionName)

        assertEquals(ActionPlanKind.Draft, planner.plan("开启周期检查").kind)
        assertEquals(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK, planner.plan("开启周期检查").draft?.functionName)
        assertEquals(ActionPlanKind.Draft, planner.plan("取消提醒 task-123").kind)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, planner.plan("取消提醒 task-123").draft?.functionName)
        assertEquals(ActionPlanKind.NoAction, planner.plan("后台任务怎么实现").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("background tasks API").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("取消后台任务").kind)
        assertFalse(planner.isLikelyAction("how to implement background tasks"))
    }

    @Test
    fun infersDeviceSettingsDraftsOnlyForExplicitSettingsCommands() {
        val wifiPlan = planner.plan("帮我打开 Wi-Fi 设置")
        assertEquals(ActionPlanKind.Draft, wifiPlan.kind)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, wifiPlan.draft?.functionName)
        assertTrue(wifiPlan.draft?.parameters.orEmpty().isEmpty())

        val wirelessPlan = planner.plan("进入无线设置")
        assertEquals(ActionPlanKind.Draft, wirelessPlan.kind)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, wirelessPlan.draft?.functionName)

        val flashlightPlan = planner.plan("打开手电筒设置")
        assertEquals(ActionPlanKind.Draft, flashlightPlan.kind)
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, flashlightPlan.draft?.functionName)

        val usageAccessPlan = planner.plan("打开使用情况访问权限设置")
        assertEquals(ActionPlanKind.Draft, usageAccessPlan.kind)
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, usageAccessPlan.draft?.functionName)
        assertTrue(usageAccessPlan.draft?.parameters.orEmpty().isEmpty())

        val englishWifiPlan = planner.plan("open Wi-Fi settings")
        assertEquals(ActionPlanKind.Draft, englishWifiPlan.kind)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, englishWifiPlan.draft?.functionName)

        val englishFlashlightPlan = planner.plan("open flashlight settings")
        assertEquals(ActionPlanKind.Draft, englishFlashlightPlan.kind)
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, englishFlashlightPlan.draft?.functionName)

        val englishUsageAccessPlan = planner.plan("open usage access settings")
        assertEquals(ActionPlanKind.Draft, englishUsageAccessPlan.kind)
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, englishUsageAccessPlan.draft?.functionName)

        assertEquals(ActionPlanKind.NoAction, planner.plan("Wi-Fi 是什么").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("Wi-Fi 设置页面怎么设计").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要打开 Wi-Fi 设置，只解释一下").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("Do not open Wi-Fi settings; explain only").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("手电筒 API 怎么用").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开手电筒").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("Usage Access API 怎么用").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("解释一下 PACKAGE_USAGE_STATS").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("Android 使用情况访问权限怎么实现").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要打开使用情况访问权限设置").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不打开 Wi-Fi 设置").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("请勿打开 Wi-Fi 设置").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要设置 Wi-Fi").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("do not open usage access settings").kind)
    }

    @Test
    fun infersMapEmailAndCalendarDraftsOnlyForExplicitCommands() {
        val mapPlan = planner.plan("查去机场的路线")
        assertEquals(ActionPlanKind.Draft, mapPlan.kind)
        assertEquals(MobileActionFunctions.SEARCH_MAPS, mapPlan.draft?.functionName)
        assertEquals("机场的路线", mapPlan.draft?.parameters?.get("query"))

        val emailPlan = planner.plan("帮我写邮件：明天延期到周五")
        assertEquals(ActionPlanKind.Draft, emailPlan.kind)
        assertEquals(MobileActionFunctions.COMPOSE_EMAIL, emailPlan.draft?.functionName)
        assertEquals("明天延期到周五", emailPlan.draft?.parameters?.get("body"))

        val calendarPlan = planner.plan("帮我建个日程：周五评审")
        assertEquals(ActionPlanKind.Draft, calendarPlan.kind)
        assertEquals(MobileActionFunctions.CREATE_CALENDAR_EVENT, calendarPlan.draft?.functionName)
        assertEquals("周五评审", calendarPlan.draft?.parameters?.get("title"))

        assertEquals(ActionPlanKind.NoAction, planner.plan("地图是什么").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("查到这个错误原因了吗？").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("How do I navigate to a Compose screen?").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("邮件是什么意思").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要发邮件，只帮我总结这段话").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("Do not send email; summarize only").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("日程这个词怎么理解").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("add event listener to the button").kind)
    }

    @Test
    fun infersUsageAccessSettingsDraft() {
        val plan = planner.plan("授权应用使用情况权限")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, plan.draft?.functionName)
        assertTrue(plan.draft?.summary.orEmpty().contains("使用情况访问权限"))
    }

    @Test
    fun treatsEnglishUsageStatsPermissionAsLikelyAction() {
        assertTrue(planner.isLikelyAction("open usage stats permission settings"))
    }

    @Test
    fun likelyActionRequiresExplicitToolIntentForGenericAppFileAndMediaWords() {
        assertFalse(planner.isLikelyAction("帮我写一份文档"))
        assertFalse(planner.isLikelyAction("这张图片是什么"))
        assertFalse(planner.isLikelyAction("视频编码是什么"))
        assertFalse(planner.isLikelyAction("音频格式怎么选"))
        assertFalse(planner.isLikelyAction("这个 app 架构怎么设计"))
        assertFalse(planner.isLikelyAction("应用权限怎么申请"))
        assertFalse(planner.isLikelyAction("文件列表怎么实现"))

        assertTrue(planner.isLikelyAction("查询最近5个图片文件列表"))
        assertTrue(planner.isLikelyAction("当前应用是什么"))
        assertTrue(planner.isLikelyAction("读取剪贴板"))
    }

    @Test
    fun infersDraftForNaturalLanguageWebSearch() {
        val plan = planner.plan("帮我搜一下 Kotlin 协程最新用法")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.WEB_SEARCH, plan.draft?.functionName)
        assertEquals("Kotlin 协程最新用法", plan.draft?.parameters?.get("query"))

        val englishPlan = planner.plan("look up Kotlin coroutines")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.WEB_SEARCH, englishPlan.draft?.functionName)
        assertEquals("Kotlin coroutines", englishPlan.draft?.parameters?.get("query"))

        val onlineLookupPlan = planner.plan("网上查一下 Kotlin Flow debounce")
        assertEquals(ActionPlanKind.Draft, onlineLookupPlan.kind)
        assertEquals(MobileActionFunctions.WEB_SEARCH, onlineLookupPlan.draft?.functionName)
        assertEquals("Kotlin Flow debounce", onlineLookupPlan.draft?.parameters?.get("query"))
        assertEquals(false, onlineLookupPlan.draft?.requiresConfirmation)

        val weatherPlan = planner.plan("北京天气怎么样")
        assertEquals(ActionPlanKind.Draft, weatherPlan.kind)
        assertEquals(MobileActionFunctions.WEB_SEARCH, weatherPlan.draft?.functionName)
        assertEquals("北京天气怎么样", weatherPlan.draft?.parameters?.get("query"))
        assertEquals(false, weatherPlan.draft?.requiresConfirmation)

        assertEquals(ActionPlanKind.NoAction, planner.plan("网页搜索是什么").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("天气是什么").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要搜索 Kotlin，只解释一下").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("what is web search").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("查一下这个错误原因了吗？").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("查一下 Kotlin 协程最新用法").kind)
    }

    @Test
    fun infersReminderDraftWithDelayMinutes() {
        val plan = planner.plan("提醒我 15 分钟后喝水")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, plan.draft?.functionName)
        assertEquals("15", plan.draft?.parameters?.get("delayMinutes"))
        assertTrue(plan.draft?.parameters?.get("title").orEmpty().contains("喝水"))
    }

    @Test
    fun infersReminderDelayFromMatchedRelativeDelayPhrase() {
        val plan = planner.plan("提醒我 30 分钟后检查 2 小时后开始的会议资料")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, plan.draft?.functionName)
        assertEquals("30", plan.draft?.parameters?.get("delayMinutes"))
        assertEquals("检查 2 小时后开始的会议资料", plan.draft?.parameters?.get("title"))
    }

    @Test
    fun infersReminderDraftWithChineseVariantDelay() {
        val plan = planner.plan("提醒我在15分钟以后喝水")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, plan.draft?.functionName)
        assertEquals("15", plan.draft?.parameters?.get("delayMinutes"))
        assertEquals("喝水", plan.draft?.parameters?.get("title"))
    }

    @Test
    fun infersReminderDraftWithDecimalHourDelay() {
        val plan = planner.plan("提醒我 1.5 小时后喝水")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, plan.draft?.functionName)
        assertEquals("90", plan.draft?.parameters?.get("delayMinutes"))
        assertEquals("喝水", plan.draft?.parameters?.get("title"))
    }

    @Test
    fun infersReminderDraftForPoliteEnglishCommand() {
        val plan = planner.plan("could you remind me in 10 minutes to stretch")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, plan.draft?.functionName)
        assertEquals("10", plan.draft?.parameters?.get("delayMinutes"))
        assertEquals("stretch", plan.draft?.parameters?.get("title"))
    }

    @Test
    fun rejectsReminderTimingDiscussionsAsNoAction() {
        assertEquals(ActionPlanKind.NoAction, planner.plan("提醒我一下，15 分钟英文怎么说").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("提醒我一下，“15 分钟后”是什么意思").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("请解释“提醒我 15 分钟后喝水”这句话").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("remind me what a 1 hour SLA means").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("remind me what \"in 15 minutes\" means").kind)
        assertEquals(false, planner.isLikelyAction("请解释“提醒我 15 分钟后喝水”这句话"))
        assertEquals(false, planner.isLikelyAction("remind me what \"in 15 minutes\" means"))
    }

    @Test
    fun infersCancelReminderDraftWithTaskId() {
        val plan = planner.plan("取消提醒 task-123")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, plan.draft?.functionName)
        assertEquals("task-123", plan.draft?.parameters?.get("taskId"))

        val englishPlan = planner.plan("cancel reminder task-abc_123")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, englishPlan.draft?.functionName)
        assertEquals("task-abc_123", englishPlan.draft?.parameters?.get("taskId"))

        assertEquals(ActionPlanKind.NoAction, planner.plan("取消提醒").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要取消提醒 task-123").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("取消提醒 API task-123").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("取消日程 task-123").kind)
    }

    @Test
    fun infersClipboardReadDraftOnlyWhenClipboardIsNamed() {
        val plan = planner.plan("读取剪贴板并总结")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, plan.draft?.functionName)
        assertTrue(plan.draft?.parameters.orEmpty().isEmpty())
    }

    @Test
    fun infersCurrentScreenTextOnlyForAccessibleTextRequests() {
        val plan = planner.plan("总结当前屏幕文字，最多1200字")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, plan.draft?.functionName)
        assertEquals("1200", plan.draft?.parameters?.get("maxChars"))
        val englishPlan = planner.plan("summarize current screen text max 1200 chars")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, englishPlan.draft?.functionName)
        assertEquals("1200", englishPlan.draft?.parameters?.get("maxChars"))
        assertEquals(ActionPlanKind.NoAction, planner.plan("看看当前屏幕").kind)
        val screenshotOcrPlan = planner.plan("识别当前屏幕截图文字")
        assertEquals(ActionPlanKind.Draft, screenshotOcrPlan.kind)
        assertEquals(MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR, screenshotOcrPlan.draft?.functionName)
        assertEquals("current_screen", screenshotOcrPlan.draft?.parameters?.get("captureMode"))
        val currentOcrPlan = planner.plan("当前屏幕 OCR")
        assertEquals(ActionPlanKind.Draft, currentOcrPlan.kind)
        assertEquals(MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR, currentOcrPlan.draft?.functionName)
        assertEquals(ActionPlanKind.NoAction, planner.plan("当前屏幕截图").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("current screen screenshot").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要读取当前屏幕文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("总结当前屏幕内容").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("总结这个界面").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("总结这页内容").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("总结页面内容").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("summarize current screen").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("summarize current screen content").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("summarize current content").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("summarize this page").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("describe current screen").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("what is on my screen").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("what is current screen text").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("current screen text API").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("how to implement current screen state").kind)
    }

    @Test
    fun rejectsNegativeDiscussionAndSequentialSensitiveActions() {
        val noActionInputs = listOf(
            "不要读取剪贴板",
            "如何读取剪贴板",
            "总结剪贴板并分享，然后打开 Wi-Fi 设置",
            "summarize clipboard and share it, then open Wi-Fi settings",
            "不要导航到机场",
            "别发邮件：明天延期到周五",
            "不要添加日程：周五评审",
            "do not create calendar event: review",
            "不要百度一下 Kotlin",
            "不要跳转到 https://example.com",
            "不要查看当前屏幕内容",
            "不要搜索联系人 Alice",
            "不要查询当前应用",
            "当前应用权限怎么申请",
            "不要读取最近通知",
            "current app notifications API",
            "don't read current app notifications",
            "don't tell me the current app",
            "提醒我 10 分钟后喝水，然后提醒我 20 分钟后运动",
            "不要看我有空 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z",
            "别读最近截图文字",
            "不要 OCR 最近图片",
        )

        noActionInputs.forEach { input ->
            assertEquals(input, ActionPlanKind.NoAction, planner.plan(input).kind)
        }
    }

    @Test
    fun infersShareTextDraft() {
        val plan = planner.plan("分享这段文字：明天十点开会")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.SHARE_TEXT, plan.draft?.functionName)
        assertEquals("明天十点开会", plan.draft?.parameters?.get("text"))

        val negativePayloadPlan = planner.plan("分享这段文字：不要分享内部资料")
        assertEquals(ActionPlanKind.Draft, negativePayloadPlan.kind)
        assertEquals("不要分享内部资料", negativePayloadPlan.draft?.parameters?.get("text"))

        val englishNegativePayloadPlan = planner.plan("share this text: don't share credentials")
        assertEquals(ActionPlanKind.Draft, englishNegativePayloadPlan.kind)
        assertEquals("don't share credentials", englishNegativePayloadPlan.draft?.parameters?.get("text"))
    }

    @Test
    fun shareTextRejectsNegativeRequests() {
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要分享这段文字：明天十点开会").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("别分享这段文字：明天十点开会").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要把这段文字分享出去：明天十点开会").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("我不想分享这段文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不分享这段文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不需要分享这段文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("don't share this text: meeting at ten").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("I don't want to share this text").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("never share this text").kind)
    }

    @Test
    fun shareTextRejectsQuestionAndDocumentationRequests() {
        assertEquals(ActionPlanKind.NoAction, planner.plan("如何分享这段文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("Android 分享到微信怎么实现").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("share this text API").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("how to share this text").kind)
    }

    @Test
    fun infersCalendarAvailabilityDraftWhenIsoWindowIsExplicit() {
        val plan = planner.plan(
            "查一下忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z",
        )

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY, plan.draft?.functionName)
        assertEquals("2026-06-01T09:00:00Z", plan.draft?.parameters?.get("start"))
        assertEquals("2026-06-01T10:00:00Z", plan.draft?.parameters?.get("end"))

        val englishPlan = planner.plan(
            "calendar availability 2026-06-01T09:00:00Z to 2026-06-01T10:00:00Z",
        )
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY, englishPlan.draft?.functionName)

        assertEquals(ActionPlanKind.NoAction, planner.plan("查一下忙闲").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("明天我有空吗").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("日历权限怎么申请").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("what is free/busy").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("API availability 2026-06-01T09:00:00Z to 2026-06-01T10:00:00Z").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("calendar availability API").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("free/busy schema 2026-06-01T09:00:00Z to 2026-06-01T10:00:00Z").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("how to implement free/busy").kind)
        assertEquals(
            ActionPlanKind.NoAction,
            planner.plan("查忙闲 2026-06-01T10:00:00Z 到 2026-06-01T09:00:00Z").kind,
        )
        assertEquals(
            ActionPlanKind.NoAction,
            planner.plan("查忙闲 2026-06-01T09:00:00Z 到 2026-07-10T09:00:00Z").kind,
        )
    }

    @Test
    fun contactQueryRequiresExplicitQueryAndRejectsNonLookupInputs() {
        val chinesePlan = planner.plan("最多 3 个联系人里查 Alice")
        assertEquals(ActionPlanKind.Draft, chinesePlan.kind)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, chinesePlan.draft?.functionName)
        assertEquals("Alice", chinesePlan.draft?.parameters?.get("query"))
        assertEquals("3", chinesePlan.draft?.parameters?.get("maxCount"))

        val englishPlan = planner.plan("look up Alice in contacts")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, englishPlan.draft?.functionName)
        assertEquals("Alice", englishPlan.draft?.parameters?.get("query"))

        val phonePlan = planner.plan("find Alice contact number")
        assertEquals(ActionPlanKind.Draft, phonePlan.kind)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, phonePlan.draft?.functionName)
        assertEquals("Alice", phonePlan.draft?.parameters?.get("query"))

        assertEquals(ActionPlanKind.NoAction, planner.plan("联系人").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("contact").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("查询联系人").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("联系人权限").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("ContactsContract 怎么用").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("search contacts API").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要查联系人 Alice").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("do not search contacts for Alice").kind)
        assertFalse(planner.isLikelyAction("联系人权限怎么申请"))
    }

    @Test
    fun contactDraftRequiresExplicitNameAndRejectsNonDraftInputs() {
        val plan = planner.plan("新建联系人 Alice")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.CREATE_CONTACT_DRAFT, plan.draft?.functionName)
        assertEquals("Alice", plan.draft?.parameters?.get("name"))

        val englishPlan = planner.plan("create contact Bob bob@example.com +1 555 0100")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.CREATE_CONTACT_DRAFT, englishPlan.draft?.functionName)
        assertEquals("Bob", englishPlan.draft?.parameters?.get("name"))
        assertEquals("bob@example.com", englishPlan.draft?.parameters?.get("email"))
        assertEquals("+1 555 0100", englishPlan.draft?.parameters?.get("phone"))

        assertEquals(ActionPlanKind.NoAction, planner.plan("新建联系人").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要新建联系人 Alice").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("联系人权限怎么申请").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("ContactsContract 怎么用").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("编辑联系人 Alice").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("删除联系人 Alice").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("导出联系人").kind)
    }

    @Test
    fun recentNotificationSummaryMatchesCurrentAppOnlyBoundary() {
        val plan = planner.plan("最近通知")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS, plan.draft?.functionName)
        assertTrue(plan.draft?.summary.orEmpty().contains("当前应用最近通知"))
        assertFalse(plan.draft?.summary.orEmpty().contains("未读"))

        val countPlan = planner.plan("最近 3 条通知")
        assertEquals(ActionPlanKind.Draft, countPlan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS, countPlan.draft?.functionName)
        assertEquals("3", countPlan.draft?.parameters?.get("maxCount"))
        assertTrue(countPlan.draft?.summary.orEmpty().contains("当前应用最近 3 条通知"))

        val englishPlan = planner.plan("current app last 2 notifications")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS, englishPlan.draft?.functionName)
        assertEquals("2", englishPlan.draft?.parameters?.get("maxCount"))

        assertEquals(ActionPlanKind.NoAction, planner.plan("notification").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("notifications").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("recent app notifications").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("notification permission").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("notification channel").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("push notification").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("系统通知").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("通知栏").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要读取最近通知").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("don't read current app notifications").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("current app notifications API").kind)
        assertFalse(planner.isLikelyAction("通知栏"))
    }

    @Test
    fun infersForegroundAppOnlyForExplicitCurrentAppRequests() {
        val plan = planner.plan("当前应用是什么")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_FOREGROUND_APP, plan.draft?.functionName)
        assertTrue(plan.draft?.parameters.orEmpty().isEmpty())

        val englishPlan = planner.plan("what app is currently open")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.QUERY_FOREGROUND_APP, englishPlan.draft?.functionName)

        assertEquals(ActionPlanKind.NoAction, planner.plan("前台服务限制是什么").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("current app architecture").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("how do I implement current app state").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要查询当前应用").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("don't tell me the current app").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("当前应用权限怎么申请").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("前台应用 API 怎么用").kind)
    }

    @Test
    fun infersRecentFilesDraftWithKindAndCount() {
        val plan = planner.plan("查询最近5个图片文件列表")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, plan.draft?.functionName)
        assertEquals("images", plan.draft?.parameters?.get("kind"))
        assertEquals("5", plan.draft?.parameters?.get("maxCount"))
        assertTrue(plan.draft?.summary.orEmpty().contains("文件名"))
    }

    @Test
    fun infersRecentDocumentsDraftWithFilePickerBoundary() {
        val plan = planner.plan("查询最近5个文档")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, plan.draft?.functionName)
        assertEquals("documents", plan.draft?.parameters?.get("kind"))
        assertEquals("5", plan.draft?.parameters?.get("maxCount"))
        assertTrue(plan.draft?.summary.orEmpty().contains("系统文件选择器"))
    }

    @Test
    fun infersRecentFilesDraftForCountedChineseMediaPhrase() {
        val plan = planner.plan("最近 3 张图片")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, plan.draft?.functionName)
        assertEquals("images", plan.draft?.parameters?.get("kind"))
        assertEquals("3", plan.draft?.parameters?.get("maxCount"))
    }

    @Test
    fun recentFilesRejectsNegativeAndDiscussionRequests() {
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要查询最近图片").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("最近图片权限怎么申请").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("recent screenshots API").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("how to read recent images").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("do not read recent images").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要查询文件列表").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("文件列表怎么实现").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("查询文件 API").kind)
    }

    @Test
    fun infersRecentScreenshotsDraftWithCount() {
        val plan = planner.plan("查询最近3张截图")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, plan.draft?.functionName)
        assertEquals("screenshots", plan.draft?.parameters?.get("kind"))
        assertEquals("3", plan.draft?.parameters?.get("maxCount"))
    }

    @Test
    fun infersRecentScreenshotsDraftForEnglishPhrase() {
        val plan = planner.plan("recent screenshots")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, plan.draft?.functionName)
        assertEquals("screenshots", plan.draft?.parameters?.get("kind"))
    }

    @Test
    fun infersRecentScreenshotOcrOnlyWhenTextExtractionIsExplicit() {
        val plan = planner.plan("识别最近1张截图文字")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, plan.draft?.functionName)
        assertEquals("1", plan.draft?.parameters?.get("maxCount"))
        assertTrue(plan.draft?.summary.orEmpty().contains("不会保存图片"))

        val implicitSinglePlan = planner.plan("识别最近截图文字")
        assertEquals(ActionPlanKind.Draft, implicitSinglePlan.kind)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, implicitSinglePlan.draft?.functionName)
        assertEquals("1", implicitSinglePlan.draft?.parameters?.get("maxCount"))

        val englishPlan = planner.plan("read text from latest screenshot")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, englishPlan.draft?.functionName)

        assertEquals(ActionPlanKind.NoAction, planner.plan("识别最近2张截图文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("识别最近两张截图文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("read text from recent 2 screenshots").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("读取所有截图 OCR").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要识别最近截图文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("截图 OCR 怎么实现").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("screenshot OCR API").kind)
    }

    @Test
    fun infersRecentImageOcrOnlyWhenTextExtractionIsExplicit() {
        val plan = planner.plan("识别最近2张照片文字")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, plan.draft?.functionName)
        assertEquals("2", plan.draft?.parameters?.get("maxCount"))
        assertTrue(plan.draft?.summary.orEmpty().contains("最近 2 张图片"))
        assertTrue(plan.draft?.summary.orEmpty().contains("不会保存图片"))

        val defaultPlan = planner.plan("识别最近图片文字")
        assertEquals(ActionPlanKind.Draft, defaultPlan.kind)
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, defaultPlan.draft?.functionName)
        assertEquals("3", defaultPlan.draft?.parameters?.get("maxCount"))

        val englishPlan = planner.plan("read text from recent photos")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, englishPlan.draft?.functionName)
        assertEquals("3", englishPlan.draft?.parameters?.get("maxCount"))

        assertEquals(ActionPlanKind.NoAction, planner.plan("识别最近4张图片文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("识别最近四张照片文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("read text from recent 4 photos").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("读取所有图片 OCR").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要识别最近图片文字").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("图片 OCR API").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("描述最近图片").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("图片里有什么").kind)
    }

    @Test
    fun keepsPlainRecentImagesAsMetadataQuery() {
        val plan = planner.plan("最近图片")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, plan.draft?.functionName)
        assertEquals("images", plan.draft?.parameters?.get("kind"))
    }

    @Test
    fun keepsPlainRecentScreenshotsAsMetadataQuery() {
        val plan = planner.plan("最近截图")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, plan.draft?.functionName)
        assertEquals("screenshots", plan.draft?.parameters?.get("kind"))
    }

    @Test
    fun infersDeepLinkDraftForExplicitUri() {
        val plan = planner.plan("打开链接 https://example.com/path?q=agent")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.OPEN_DEEP_LINK, plan.draft?.functionName)
        assertEquals("https://example.com/path?q=agent", plan.draft?.parameters?.get("uri"))

        val englishPlan = planner.plan("open https://example.com/path.")
        assertEquals(ActionPlanKind.Draft, englishPlan.kind)
        assertEquals(MobileActionFunctions.OPEN_DEEP_LINK, englishPlan.draft?.functionName)
        assertEquals("https://example.com/path", englishPlan.draft?.parameters?.get("uri"))

        assertEquals(ActionPlanKind.NoAction, planner.plan("https://example.com/path").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("解释 https://example.com/path 是什么").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("how do I open https://example.com/path").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要打开 https://example.com/path").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开链接 http://example.com/path").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开链接 javascript:alert(1)").kind)
    }

    @Test
    fun infersAppIntentDraftForKnownAppAlias() {
        val plan = planner.plan("启动微信")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.OPEN_APP_INTENT, plan.draft?.functionName)
        assertEquals("com.tencent.mm", plan.draft?.parameters?.get("packageName"))

        val packagePlan = planner.plan("打开 com.example.app")
        assertEquals(ActionPlanKind.Draft, packagePlan.kind)
        assertEquals(MobileActionFunctions.OPEN_APP_INTENT, packagePlan.draft?.functionName)
        assertEquals("com.example.app", packagePlan.draft?.parameters?.get("packageName"))
    }

    @Test
    fun infersAppDeepTargetDraftForKnownAlias() {
        val plan = planner.plan("打开微信应用详情设置")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.OPEN_APP_DEEP_TARGET, plan.draft?.functionName)
        assertEquals(AppDeepTargets.APP_DETAILS_SETTINGS_ID, plan.draft?.parameters?.get("targetId"))
        assertEquals("com.tencent.mm", plan.draft?.parameters?.get("packageName"))
        assertTrue(plan.draft?.summary.orEmpty().contains("应用详情设置"))

        val packagePlan = planner.plan("打开 com.example.app 应用详情设置")
        assertEquals(ActionPlanKind.Draft, packagePlan.kind)
        assertEquals(MobileActionFunctions.OPEN_APP_DEEP_TARGET, packagePlan.draft?.functionName)
        assertEquals(AppDeepTargets.APP_DETAILS_SETTINGS_ID, packagePlan.draft?.parameters?.get("targetId"))
        assertEquals("com.example.app", packagePlan.draft?.parameters?.get("packageName"))
    }

    @Test
    fun doesNotInferAppNavigationForAmbiguousOrUnsupportedTargets() {
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开应用").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("启动 app").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开应用详情设置").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要打开微信").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("别启动微信").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不启动微信").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("请勿打开微信").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("如何打开微信").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("怎么打开微信").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开微信小程序").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开微信支付收款码").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开微信应用设置").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("open WeChat app settings").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("open source WeChat SDK").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("start using WeChat").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("微信打不开怎么办").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("微信打开方式").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开微信扫一扫").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开微信聊天").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开微信朋友圈").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开微信权限页").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开微信通知设置").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开 package:com.example.app").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开 intent://scan/#Intent;scheme=zxing;end").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开 file:///sdcard/private.txt").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开 com.example.app/.MainActivity 应用详情设置").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开微信应用详情设置然后发消息").kind)
    }

    @Test
    fun parsesAppIntentCallOutputWithPackageName() {
        val draft = planner.parseModelOutput(
            """call:open_app_intent{"packageName":"com.example.app"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.OPEN_APP_INTENT, draft.functionName)
        assertEquals("com.example.app", draft.parameters["packageName"])
        assertEquals(setOf("packageName"), draft.parameters.keys)
    }

    @Test
    fun parsesAppDeepTargetCallOutputWithAllowlistedTargetId() {
        val draft = planner.parseModelOutput(
            """call:open_app_deep_target{"targetId":"android_app_details_settings","packageName":"com.example.app"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.OPEN_APP_DEEP_TARGET, draft.functionName)
        assertEquals(AppDeepTargets.APP_DETAILS_SETTINGS_ID, draft.parameters["targetId"])
        assertEquals("com.example.app", draft.parameters["packageName"])
        assertEquals(setOf("targetId", "packageName"), draft.parameters.keys)
    }

    @Test
    fun doesNotTreatShareOpinionAsShareSheetAction() {
        val plan = planner.plan("分享一下你对端侧 Agent 的看法")

        assertEquals(ActionPlanKind.NoAction, plan.kind)
    }
}
