package com.bytedance.zgx.pocketmind.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlannerTest {
    private val planner = MobileActionPlanner()

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
    fun parsesHermesStyleWebSearchCallOutput() {
        val draft = planner.parseModelOutput(
            """call:web_search{"query":"Kotlin coroutines Android"}""",
        )

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.WEB_SEARCH, draft.functionName)
        assertEquals("Kotlin coroutines Android", draft.parameters["query"])
        assertTrue(draft.summary.contains("浏览器"))
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
    fun parsesUsageAccessSettingsCallOutput() {
        val draft = planner.parseModelOutput("""call:open_usage_access_settings{}""")

        requireNotNull(draft)
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, draft.functionName)
        assertTrue(draft.summary.contains("使用情况访问权限"))
        assertTrue(draft.parameters.isEmpty())
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

        val englishWifiPlan = planner.plan("open Wi-Fi settings")
        assertEquals(ActionPlanKind.Draft, englishWifiPlan.kind)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, englishWifiPlan.draft?.functionName)

        val englishFlashlightPlan = planner.plan("open flashlight settings")
        assertEquals(ActionPlanKind.Draft, englishFlashlightPlan.kind)
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, englishFlashlightPlan.draft?.functionName)

        assertEquals(ActionPlanKind.NoAction, planner.plan("Wi-Fi 是什么").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("Wi-Fi 设置页面怎么设计").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("不要打开 Wi-Fi 设置，只解释一下").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("Do not open Wi-Fi settings; explain only").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("手电筒 API 怎么用").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("打开手电筒").kind)
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
        val plan = planner.plan("打开使用情况访问权限设置")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, plan.draft?.functionName)
        assertTrue(plan.draft?.summary.orEmpty().contains("使用情况访问权限"))
    }

    @Test
    fun treatsEnglishUsageStatsPermissionAsLikelyAction() {
        assertTrue(planner.isLikelyAction("open usage stats permission settings"))
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

        assertEquals(ActionPlanKind.NoAction, planner.plan("网页搜索是什么").kind)
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
        assertEquals(ActionPlanKind.NoAction, planner.plan("看看当前屏幕").kind)
        assertEquals(ActionPlanKind.NoAction, planner.plan("识别当前屏幕截图文字").kind)
    }

    @Test
    fun infersShareTextDraft() {
        val plan = planner.plan("分享这段文字：明天十点开会")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.SHARE_TEXT, plan.draft?.functionName)
        assertEquals("明天十点开会", plan.draft?.parameters?.get("text"))
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
    }

    @Test
    fun recentNotificationSummaryMatchesCurrentAppOnlyBoundary() {
        val plan = planner.plan("最近通知")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS, plan.draft?.functionName)
        assertTrue(plan.draft?.summary.orEmpty().contains("当前应用最近通知"))
        assertFalse(plan.draft?.summary.orEmpty().contains("未读"))
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
    }

    @Test
    fun infersRecentImageOcrOnlyWhenTextExtractionIsExplicit() {
        val plan = planner.plan("识别最近2张照片文字")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, plan.draft?.functionName)
        assertEquals("2", plan.draft?.parameters?.get("maxCount"))
        assertTrue(plan.draft?.summary.orEmpty().contains("最近 2 张图片"))
        assertTrue(plan.draft?.summary.orEmpty().contains("不会保存图片"))
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
    }

    @Test
    fun infersAppDeepTargetDraftForKnownAlias() {
        val plan = planner.plan("打开微信应用详情设置")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.OPEN_APP_DEEP_TARGET, plan.draft?.functionName)
        assertEquals(AppDeepTargets.APP_DETAILS_SETTINGS_ID, plan.draft?.parameters?.get("targetId"))
        assertEquals("com.tencent.mm", plan.draft?.parameters?.get("packageName"))
        assertTrue(plan.draft?.summary.orEmpty().contains("应用详情设置"))
    }

    @Test
    fun doesNotInferAppDeepTargetForAmbiguousTargetPhrase() {
        val plan = planner.plan("打开应用详情设置")

        assertEquals(ActionPlanKind.NoAction, plan.kind)
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
