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
    fun infersRecentFilesDraftWithKindAndCount() {
        val plan = planner.plan("查询最近5个图片文件列表")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, plan.draft?.functionName)
        assertEquals("images", plan.draft?.parameters?.get("kind"))
        assertEquals("5", plan.draft?.parameters?.get("maxCount"))
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
    fun infersDeepLinkDraftForExplicitUri() {
        val plan = planner.plan("打开链接 https://example.com/path?q=agent")

        assertEquals(ActionPlanKind.Draft, plan.kind)
        assertEquals(MobileActionFunctions.OPEN_DEEP_LINK, plan.draft?.functionName)
        assertEquals("https://example.com/path?q=agent", plan.draft?.parameters?.get("uri"))
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
