package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.AppDeepTargets
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSkillRuntimeTest {
    private val runtime = BuiltInSkillRuntime()

    @Test
    fun exposesVersionedManifestsForCoreSkills() {
        val manifests = runtime.manifests().associateBy { it.id }

        assertTrue(BuiltInSkillRuntime.EMAIL_DRAFT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CALENDAR_DRAFT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CONTACT_DRAFT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.MAP_SEARCH_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.INFORMATION_LOOKUP_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.REMINDER_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.SHARE_TEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.RECENT_IMAGE_OCR_CONTEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.RECENT_FILES_CONTEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.DEEP_LINK_NAVIGATION_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.APP_NAVIGATION_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.FOREGROUND_APP_CONTEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.RECENT_NOTIFICATIONS_CONTEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_CONTEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CONTACT_LOOKUP_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CALENDAR_AVAILABILITY_SKILL in manifests)
        assertTrue(manifests.values.all { it.version >= 1 })
        assertTrue(manifests.values.all { it.inputSchemaJson.contains("additionalProperties") })
    }

    @Test
    fun builtInManifestSchemasAreClosedTextInputContracts() {
        runtime.manifests().forEach { manifest ->
            val schema = JSONObject(manifest.inputSchemaJson)
            assertEquals("object", schema.getString("type"))
            assertEquals(false, schema.getBoolean("additionalProperties"))
            val required = schema.getJSONArray("required")
            assertEquals(1, required.length())
            assertEquals("input", required.getString(0))
            val inputSchema = schema.getJSONObject("properties").getJSONObject("input")
            assertEquals("string", inputSchema.getString("type"))
            assertEquals(1, inputSchema.getInt("minLength"))
        }
    }

    @Test
    fun builtInPlansUseSkillInputArgumentsAndValidateAgainstManifestSchema() {
        val plans = listOf(
            "帮我写封邮件" to requireNotNull(
                runtime.plan(
                    "帮我写封邮件",
                    draft(
                        toolName = MobileActionFunctions.COMPOSE_EMAIL,
                        parameters = mapOf("body" to "明天延期"),
                    ),
                    ToolRequest(
                        toolName = MobileActionFunctions.COMPOSE_EMAIL,
                        arguments = mapOf("body" to "明天延期"),
                    ),
                ),
            ),
            "帮我建个日程" to requireNotNull(
                runtime.plan(
                    "帮我建个日程",
                    draft(
                        toolName = MobileActionFunctions.CREATE_CALENDAR_EVENT,
                        parameters = mapOf("title" to "评审"),
                    ),
                    ToolRequest(
                        toolName = MobileActionFunctions.CREATE_CALENDAR_EVENT,
                        arguments = mapOf("title" to "评审"),
                    ),
                ),
            ),
            "新建联系人 Alice" to requireNotNull(runtime.plan("新建联系人 Alice")),
            "查去机场的路线" to requireNotNull(
                runtime.plan(
                    "查去机场的路线",
                    draft(
                        toolName = MobileActionFunctions.SEARCH_MAPS,
                        parameters = mapOf("query" to "机场"),
                    ),
                    ToolRequest(
                        toolName = MobileActionFunctions.SEARCH_MAPS,
                        arguments = mapOf("query" to "机场"),
                    ),
                ),
            ),
            "搜一下 Kotlin 协程" to requireNotNull(
                runtime.plan(
                    "搜一下 Kotlin 协程",
                    draft(
                        toolName = MobileActionFunctions.WEB_SEARCH,
                        parameters = mapOf("query" to "Kotlin 协程"),
                    ),
                    ToolRequest(
                        toolName = MobileActionFunctions.WEB_SEARCH,
                        arguments = mapOf("query" to "Kotlin 协程"),
                    ),
                ),
            ),
            "打开 Wi-Fi 设置" to requireNotNull(
                runtime.plan(
                    "打开 Wi-Fi 设置",
                    draft(MobileActionFunctions.OPEN_WIFI_SETTINGS),
                    ToolRequest(toolName = MobileActionFunctions.OPEN_WIFI_SETTINGS),
                ),
            ),
            "打开使用情况访问权限设置" to requireNotNull(runtime.plan("打开使用情况访问权限设置")),
            "提醒我 15 分钟后喝水" to requireNotNull(
                runtime.plan(
                    "提醒我 15 分钟后喝水",
                    draft(
                        toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                        parameters = mapOf("title" to "喝水", "delayMinutes" to "15"),
                    ),
                    ToolRequest(
                        toolName = MobileActionFunctions.SCHEDULE_REMINDER,
                        arguments = mapOf("title" to "喝水", "delayMinutes" to "15"),
                    ),
                ),
            ),
            "取消提醒 task-123" to requireNotNull(runtime.plan("取消提醒 task-123")),
            "读取剪贴板" to requireNotNull(runtime.plan("读取剪贴板")),
            "分享这段文字：明天十点开会" to requireNotNull(
                runtime.plan(
                    "分享这段文字：明天十点开会",
                    draft(
                        toolName = MobileActionFunctions.SHARE_TEXT,
                        parameters = mapOf("text" to "明天十点开会"),
                    ),
                    ToolRequest(
                        toolName = MobileActionFunctions.SHARE_TEXT,
                        arguments = mapOf("text" to "明天十点开会"),
                    ),
                ),
            ),
            "总结剪贴板并分享" to runtime.planClipboardSummaryShare("总结剪贴板并分享"),
            "总结当前屏幕文字并分享" to requireNotNull(runtime.plan("总结当前屏幕文字并分享")),
            "识别最近截图文字" to requireNotNull(runtime.plan("识别最近截图文字")),
            "识别最近图片文字" to requireNotNull(runtime.plan("识别最近图片文字")),
            "最近图片" to requireNotNull(runtime.plan("最近图片")),
            "打开链接 https://example.com" to requireNotNull(runtime.plan("打开链接 https://example.com")),
            "启动微信" to requireNotNull(runtime.plan("启动微信")),
            "打开微信应用详情设置" to requireNotNull(runtime.plan("打开微信应用详情设置")),
            "当前应用是什么" to requireNotNull(runtime.plan("当前应用是什么")),
            "最近通知" to requireNotNull(runtime.plan("最近通知")),
            "总结当前屏幕文字" to requireNotNull(runtime.plan("总结当前屏幕文字")),
            "查联系人 Alice" to requireNotNull(runtime.plan("查联系人 Alice")),
            "查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z" to requireNotNull(
                runtime.plan("查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z"),
            ),
        )

        plans.forEach { (input, plan) ->
            assertEquals(mapOf("input" to input), plan.request.arguments)
            assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)
        }
    }

    @Test
    fun validateStructureRejectsSkillRequestArgumentsOutsideManifestSchema() {
        val plan = runtime.planClipboardSummaryShare("总结剪贴板并分享")

        val missingInput = plan.copy(request = plan.request.copy(arguments = emptyMap()))
        val blankInput = plan.copy(request = plan.request.copy(arguments = mapOf("input" to " ")))
        val extraArgument = plan.copy(
            request = plan.request.copy(arguments = mapOf("input" to "总结剪贴板并分享", "toolName" to "read_clipboard")),
        )

        assertFalse(missingInput.validateStructure().isValid)
        assertTrue(missingInput.validateStructure().errors.any { it.contains("requires argument(s): input") })
        assertFalse(blankInput.validateStructure().isValid)
        assertTrue(blankInput.validateStructure().errors.any { it.contains("requires argument(s): input") })
        assertFalse(extraArgument.validateStructure().isValid)
        assertTrue(extraArgument.validateStructure().errors.any { it.contains("does not accept argument(s): toolName") })
    }

    @Test
    fun validateStructureRejectsSkillRequestThatDoesNotMatchManifest() {
        val plan = runtime.planClipboardSummaryShare("总结剪贴板并分享")

        val invalid = plan.copy(
            request = plan.request.copy(skillId = BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL),
        )

        assertFalse(invalid.validateStructure().isValid)
        assertTrue(invalid.validateStructure().errors.any { it.contains("does not match manifest") })
    }

    @Test
    fun plansEmailDraftAsToolStepWithoutExecutingIt() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.COMPOSE_EMAIL,
            title = "邮件草稿",
            summary = "将打开邮件 App 并填入草稿内容。",
            parameters = mapOf("subject" to "Hi", "body" to "明天聊"),
        )
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )

        val plan = runtime.plan("帮我写封邮件", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.EMAIL_DRAFT_SKILL, plan.request.skillId)
        assertEquals(mapOf("input" to "帮我写封邮件"), plan.request.arguments)
        assertEquals(listOf(MobileActionFunctions.COMPOSE_EMAIL), plan.manifest.requiredTools)
        assertEquals(1, plan.steps.size)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(request, step.request)
        assertEquals(draft, step.draft)
    }

    @Test
    fun plansReminderAsBackgroundToolStep() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.SCHEDULE_REMINDER,
            title = "后台提醒",
            summary = "将在 15 分钟后提醒：喝水",
            parameters = mapOf(
                "title" to "喝水",
                "body" to "提醒我 15 分钟后喝水",
                "delayMinutes" to "15",
            ),
        )
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )

        val plan = runtime.plan("提醒我 15 分钟后喝水", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, plan.request.skillId)
        assertTrue(MobileActionFunctions.SCHEDULE_REMINDER in plan.manifest.requiredTools)
        assertTrue(MobileActionFunctions.CANCEL_REMINDER in plan.manifest.requiredTools)
        assertEquals(1, plan.steps.size)
    }

    @Test
    fun plansReminderSkillFirstWithoutActionDraft() {
        val plan = runtime.plan("提醒我 15 分钟后喝水")

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, plan.request.skillId)
        assertTrue(plan.validateStructure().isValid)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, step.request.toolName)
        assertEquals("15", step.request.arguments["delayMinutes"])
        assertEquals("喝水", step.request.arguments["title"])
        assertEquals("提醒我 15 分钟后喝水", step.request.arguments["body"])
        assertEquals(step.request.arguments, step.draft.parameters)
    }

    @Test
    fun plansEnglishReminderSkillFirstWithoutActionDraft() {
        val plan = runtime.plan("remind me in 1 hour to check build status")

        requireNotNull(plan)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.SCHEDULE_REMINDER, step.request.toolName)
        assertEquals("60", step.request.arguments["delayMinutes"])
        assertEquals("check build status", step.request.arguments["title"])
    }

    @Test
    fun plansCancelReminderSkillFirstWithoutActionDraft() {
        val plan = runtime.plan("取消提醒 task-123")

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.REMINDER_SKILL, plan.request.skillId)
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, step.request.toolName)
        assertEquals("task-123", step.request.arguments["taskId"])
        assertEquals(step.request.arguments, step.draft.parameters)

        val englishPlan = runtime.plan("cancel reminder task-abc_123")
        requireNotNull(englishPlan)
        val englishStep = englishPlan.steps.single()
        require(englishStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.CANCEL_REMINDER, englishStep.request.toolName)
        assertEquals("task-abc_123", englishStep.request.arguments["taskId"])

        assertEquals(null, runtime.plan("取消提醒"))
        assertEquals(null, runtime.plan("不要取消提醒 task-123"))
        assertEquals(null, runtime.plan("取消提醒 API task-123"))
        assertEquals(null, runtime.plan("取消日程 task-123"))
    }

    @Test
    fun plansReminderSkillFirstWithVariantDelayPhrases() {
        val chinesePlan = runtime.plan("提醒我在15分钟以后喝水")
        requireNotNull(chinesePlan)
        val chineseStep = chinesePlan.steps.single()
        require(chineseStep is SkillStep.ToolStep)
        assertEquals("15", chineseStep.request.arguments["delayMinutes"])
        assertEquals("喝水", chineseStep.request.arguments["title"])

        val englishPlan = runtime.plan("please set a reminder in 1.5 hours to stretch")
        requireNotNull(englishPlan)
        val englishStep = englishPlan.steps.single()
        require(englishStep is SkillStep.ToolStep)
        assertEquals("90", englishStep.request.arguments["delayMinutes"])
        assertEquals("stretch", englishStep.request.arguments["title"])

        val politeEnglishPlan = runtime.plan("could you remind me in 10 minutes to stretch")
        requireNotNull(politeEnglishPlan)
        val politeEnglishStep = politeEnglishPlan.steps.single()
        require(politeEnglishStep is SkillStep.ToolStep)
        assertEquals("10", politeEnglishStep.request.arguments["delayMinutes"])
        assertEquals("stretch", politeEnglishStep.request.arguments["title"])
    }

    @Test
    fun reminderSkillFirstRejectsTimingDiscussionFalsePositives() {
        assertEquals(null, runtime.plan("提醒我一下，15 分钟英文怎么说"))
        assertEquals(null, runtime.plan("提醒我一下，“15 分钟后”是什么意思"))
        assertEquals(null, runtime.plan("请解释“提醒我 15 分钟后喝水”这句话"))
        assertEquals(null, runtime.plan("remind me what a 1 hour SLA means"))
        assertEquals(null, runtime.plan("remind me what \"in 15 minutes\" means"))
        assertEquals(null, runtime.plan("提醒我 10 分钟后喝水，然后提醒我 20 分钟后运动"))
    }

    @Test
    fun plansClipboardReadAsContextToolStep() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本。",
            parameters = emptyMap(),
        )
        val request = ToolRequest(toolName = draft.functionName, reason = draft.summary)

        val plan = runtime.plan("读取剪贴板", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL, plan.request.skillId)
        assertEquals(listOf(MobileActionFunctions.READ_CLIPBOARD), plan.manifest.requiredTools)
    }

    @Test
    fun routesClipboardSummaryShareInputToCompositePlan() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本。",
            parameters = emptyMap(),
        )
        val request = ToolRequest(toolName = draft.functionName, reason = draft.summary)

        val plan = runtime.plan("总结剪贴板并分享", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, plan.request.skillId)
        assertEquals(3, plan.steps.size)
        val readStep = plan.steps.first()
        require(readStep is SkillStep.ToolStep)
        assertEquals(request.id, readStep.request.id)
        assertEquals(draft, readStep.draft)
    }

    @Test
    fun plansClipboardSummaryShareWithoutActionDraft() {
        val plan = runtime.plan("总结剪贴板并分享")

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, plan.request.skillId)
        assertEquals(3, plan.steps.size)
        val readStep = plan.steps.first()
        require(readStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, readStep.request.toolName)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, readStep.draft.functionName)
        assertTrue(plan.validateStructure().isValid)
    }

    @Test
    fun clipboardSummaryShareSkillFirstRejectsSequentialFollowUp() {
        assertEquals(null, runtime.plan("总结剪贴板并分享，然后打开 Wi-Fi 设置"))
        assertEquals(null, runtime.plan("summarize clipboard and share it, then open Wi-Fi settings"))
    }

    @Test
    fun clipboardSummaryShareRejectsNegativeAndDiscussionRequests() {
        assertEquals(null, runtime.plan("不要总结剪贴板并分享"))
        assertEquals(null, runtime.plan("如何总结剪贴板并分享"))
        assertEquals(null, runtime.plan("do not summarize clipboard and share it"))
        assertEquals(null, runtime.plan("how to summarize clipboard and share it"))
    }

    @Test
    fun plansClipboardContextWithoutActionDraft() {
        val plan = runtime.plan("读取剪贴板")

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL, plan.request.skillId)
        assertEquals(1, plan.steps.size)
        val readStep = plan.steps.single()
        require(readStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, readStep.request.toolName)
        assertEquals("将读取当前剪贴板文本。", readStep.request.reason)
        assertTrue(readStep.draft.requiresConfirmation)
        assertTrue(plan.validateStructure().isValid)
    }

    @Test
    fun clipboardContextSkillFirstRejectsSequentialFollowUp() {
        assertEquals(null, runtime.plan("读取剪贴板，然后打开 Wi-Fi 设置"))
        assertEquals(null, runtime.plan("读取剪贴板再打开 Wi-Fi 设置"))
        assertEquals(null, runtime.plan("read clipboard, then open Wi-Fi settings"))
    }

    @Test
    fun clipboardContextSkillFirstRejectsNegativeAndDiscussionRequests() {
        assertEquals(null, runtime.plan("不要读取剪贴板"))
        assertEquals(null, runtime.plan("如何读取剪贴板"))
    }

    @Test
    fun skillFirstPlannerDoesNotTreatOrdinaryShareDiscussionAsShareTool() {
        val plan = runtime.plan("分享一下你对端侧 Agent 的看法")

        assertEquals(null, plan)
    }

    @Test
    fun shareTextSkillFirstRejectsNegativeRequests() {
        assertEquals(null, runtime.plan("不要分享这段文字：明天十点开会"))
        assertEquals(null, runtime.plan("别分享这段文字：明天十点开会"))
        assertEquals(null, runtime.plan("不要把这段文字分享出去：明天十点开会"))
        assertEquals(null, runtime.plan("我不想分享这段文字"))
        assertEquals(null, runtime.plan("不分享这段文字"))
        assertEquals(null, runtime.plan("不需要分享这段文字"))
        assertEquals(null, runtime.plan("don't share this text: meeting at ten"))
        assertEquals(null, runtime.plan("I don't want to share this text"))
        assertEquals(null, runtime.plan("never share this text"))
    }

    @Test
    fun shareTextSkillFirstRejectsQuestionAndDocumentationRequests() {
        assertEquals(null, runtime.plan("如何分享这段文字"))
        assertEquals(null, runtime.plan("Android 分享到微信怎么实现"))
        assertEquals(null, runtime.plan("share this text API"))
        assertEquals(null, runtime.plan("how to share this text"))
    }

    @Test
    fun plansParameterizedDraftSkillsWithoutActionDraftWhenCommandIsExplicit() {
        val mapPlan = requireNotNull(runtime.plan("查去机场的路线"))
        assertEquals(BuiltInSkillRuntime.MAP_SEARCH_SKILL, mapPlan.request.skillId)
        val mapStep = mapPlan.steps.single()
        require(mapStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.SEARCH_MAPS, mapStep.request.toolName)
        assertEquals("机场的路线", mapStep.request.arguments["query"])

        val emailPlan = requireNotNull(runtime.plan("帮我写邮件：明天延期到周五"))
        assertEquals(BuiltInSkillRuntime.EMAIL_DRAFT_SKILL, emailPlan.request.skillId)
        val emailStep = emailPlan.steps.single()
        require(emailStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.COMPOSE_EMAIL, emailStep.request.toolName)
        assertEquals("明天延期到周五", emailStep.request.arguments["body"])

        val calendarPlan = requireNotNull(runtime.plan("帮我建个日程：周五评审"))
        assertEquals(BuiltInSkillRuntime.CALENDAR_DRAFT_SKILL, calendarPlan.request.skillId)
        val calendarStep = calendarPlan.steps.single()
        require(calendarStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.CREATE_CALENDAR_EVENT, calendarStep.request.toolName)
        assertEquals("周五评审", calendarStep.request.arguments["title"])

        assertEquals(null, runtime.plan("地图是什么"))
        assertEquals(null, runtime.plan("不要导航到机场"))
        assertEquals(null, runtime.plan("查到这个错误原因了吗？"))
        assertEquals(null, runtime.plan("How do I navigate to a Compose screen?"))
        assertEquals(null, runtime.plan("邮件是什么意思"))
        assertEquals(null, runtime.plan("别发邮件：明天延期到周五"))
        assertEquals(null, runtime.plan("不要发邮件，只帮我总结这段话"))
        assertEquals(null, runtime.plan("Do not send email; summarize only"))
        assertEquals(null, runtime.plan("日程这个词怎么理解"))
        assertEquals(null, runtime.plan("不要添加日程：周五评审"))
        assertEquals(null, runtime.plan("do not create calendar event: review"))
        assertEquals(null, runtime.plan("add event listener to the button"))
    }

    @Test
    fun plansDeviceSettingsWithoutActionDraftWhenCommandIsExplicit() {
        val wifiPlan = requireNotNull(runtime.plan("打开 Wi-Fi 设置"))
        assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, wifiPlan.request.skillId)
        assertEquals(mapOf("input" to "打开 Wi-Fi 设置"), wifiPlan.request.arguments)
        val wifiStep = wifiPlan.steps.single()
        require(wifiStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, wifiStep.request.toolName)
        assertEquals(MobileActionFunctions.OPEN_WIFI_SETTINGS, wifiStep.draft.functionName)
        assertTrue(wifiStep.request.arguments.isEmpty())
        assertTrue(wifiPlan.validateStructure().errors.joinToString(), wifiPlan.validateStructure().isValid)

        val flashlightPlan = requireNotNull(runtime.plan("打开手电筒设置"))
        assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, flashlightPlan.request.skillId)
        assertEquals(mapOf("input" to "打开手电筒设置"), flashlightPlan.request.arguments)
        val flashlightStep = flashlightPlan.steps.single()
        require(flashlightStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, flashlightStep.request.toolName)
        assertEquals(MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS, flashlightStep.draft.functionName)
        assertTrue(flashlightStep.request.arguments.isEmpty())
        assertTrue(flashlightPlan.validateStructure().errors.joinToString(), flashlightPlan.validateStructure().isValid)

        val usageAccessPlan = requireNotNull(runtime.plan("打开使用情况访问权限设置"))
        assertEquals(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL, usageAccessPlan.request.skillId)
        assertEquals(mapOf("input" to "打开使用情况访问权限设置"), usageAccessPlan.request.arguments)
        val usageAccessStep = usageAccessPlan.steps.single()
        require(usageAccessStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, usageAccessStep.request.toolName)
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, usageAccessStep.draft.functionName)
        assertTrue(usageAccessStep.request.arguments.isEmpty())
        assertTrue(usageAccessPlan.validateStructure().errors.joinToString(), usageAccessPlan.validateStructure().isValid)

        val englishUsageAccessPlan = requireNotNull(runtime.plan("open usage access settings"))
        val englishUsageAccessStep = englishUsageAccessPlan.steps.single()
        require(englishUsageAccessStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS, englishUsageAccessStep.request.toolName)

        assertEquals(null, runtime.plan("Wi-Fi 是什么"))
        assertEquals(null, runtime.plan("Wi-Fi 设置页面怎么设计"))
        assertEquals(null, runtime.plan("不要打开 Wi-Fi 设置，只解释一下"))
        assertEquals(null, runtime.plan("Do not open Wi-Fi settings; explain only"))
        assertEquals(null, runtime.plan("手电筒 API 怎么用"))
        assertEquals(null, runtime.plan("打开手电筒"))
        assertEquals(null, runtime.plan("Usage Access API 怎么用"))
        assertEquals(null, runtime.plan("解释一下 PACKAGE_USAGE_STATS"))
        assertEquals(null, runtime.plan("Android 使用情况访问权限怎么实现"))
        assertEquals(null, runtime.plan("不要打开使用情况访问权限设置"))
        assertEquals(null, runtime.plan("不打开 Wi-Fi 设置"))
        assertEquals(null, runtime.plan("请勿打开 Wi-Fi 设置"))
        assertEquals(null, runtime.plan("不要设置 Wi-Fi"))
        assertEquals(null, runtime.plan("do not open usage access settings"))
    }

    @Test
    fun plansWebSearchWithoutActionDraftWhenCommandIsExplicit() {
        val chinesePlan = requireNotNull(runtime.plan("搜一下 Kotlin 协程"))
        assertEquals(BuiltInSkillRuntime.INFORMATION_LOOKUP_SKILL, chinesePlan.request.skillId)
        assertEquals(mapOf("input" to "搜一下 Kotlin 协程"), chinesePlan.request.arguments)
        val chineseStep = chinesePlan.steps.single()
        require(chineseStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.WEB_SEARCH, chineseStep.request.toolName)
        assertEquals("Kotlin 协程", chineseStep.request.arguments["query"])
        assertEquals(MobileActionFunctions.WEB_SEARCH, chineseStep.draft.functionName)
        assertTrue(chinesePlan.validateStructure().errors.joinToString(), chinesePlan.validateStructure().isValid)

        val englishPlan = requireNotNull(runtime.plan("look up Kotlin coroutines"))
        assertEquals(BuiltInSkillRuntime.INFORMATION_LOOKUP_SKILL, englishPlan.request.skillId)
        val englishStep = englishPlan.steps.single()
        require(englishStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.WEB_SEARCH, englishStep.request.toolName)
        assertEquals("Kotlin coroutines", englishStep.request.arguments["query"])
        assertTrue(englishPlan.validateStructure().errors.joinToString(), englishPlan.validateStructure().isValid)

        val onlineLookupPlan = requireNotNull(runtime.plan("网上查一下 Kotlin Flow debounce"))
        val onlineLookupStep = onlineLookupPlan.steps.single()
        require(onlineLookupStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.WEB_SEARCH, onlineLookupStep.request.toolName)
        assertEquals("Kotlin Flow debounce", onlineLookupStep.request.arguments["query"])

        assertEquals(null, runtime.plan("查一下 Kotlin 协程"))
        assertEquals(null, runtime.plan("网页搜索是什么"))
        assertEquals(null, runtime.plan("不要搜索 Kotlin，只解释一下"))
        assertEquals(null, runtime.plan("不要百度一下 Kotlin"))
        assertEquals(null, runtime.plan("what is web search"))
        assertEquals(null, runtime.plan("查一下这个错误原因了吗？"))
    }

    @Test
    fun plansRecentMediaFilesWithoutActionDraftWhenMetadataRequestIsExplicit() {
        val imagePlan = requireNotNull(runtime.plan("最近 3 张图片"))
        assertEquals(BuiltInSkillRuntime.RECENT_FILES_CONTEXT_SKILL, imagePlan.request.skillId)
        assertEquals(mapOf("input" to "最近 3 张图片"), imagePlan.request.arguments)
        val imageStep = imagePlan.steps.single()
        require(imageStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, imageStep.request.toolName)
        assertEquals("images", imageStep.request.arguments["kind"])
        assertEquals("3", imageStep.request.arguments["maxCount"])
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, imageStep.draft.functionName)
        assertTrue(imagePlan.validateStructure().errors.joinToString(), imagePlan.validateStructure().isValid)

        val screenshotPlan = requireNotNull(runtime.plan("recent screenshots"))
        assertEquals(BuiltInSkillRuntime.RECENT_FILES_CONTEXT_SKILL, screenshotPlan.request.skillId)
        val screenshotStep = screenshotPlan.steps.single()
        require(screenshotStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.QUERY_RECENT_FILES, screenshotStep.request.toolName)
        assertEquals("screenshots", screenshotStep.request.arguments["kind"])
        assertTrue(
            screenshotPlan.validateStructure().errors.joinToString(),
            screenshotPlan.validateStructure().isValid,
        )

        assertEquals(null, runtime.plan("查询最近5个文档"))
        assertEquals(null, runtime.plan("最近文件"))
        assertEquals(null, runtime.plan("不要查询最近图片"))
        assertEquals(null, runtime.plan("最近图片权限怎么申请"))
        assertEquals(null, runtime.plan("recent screenshots API"))
        assertEquals(null, runtime.plan("how to read recent images"))
        assertEquals(null, runtime.plan("do not read recent images"))
        assertEquals(null, runtime.plan("不要查询文件列表"))
        assertEquals(null, runtime.plan("文件列表怎么实现"))
        assertEquals(null, runtime.plan("查询文件 API"))
    }

    @Test
    fun plansRecentScreenshotOcrWithoutActionDraftWhenCommandIsExplicit() {
        val plan = requireNotNull(runtime.plan("识别最近截图文字"))

        assertEquals(BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL, plan.request.skillId)
        assertEquals(mapOf("input" to "识别最近截图文字"), plan.request.arguments)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, step.request.toolName)
        assertEquals("1", step.request.arguments["maxCount"])
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, step.draft.functionName)
        assertTrue(step.draft.requiresConfirmation)
        assertTrue(step.draft.summary.contains("最近 1 张截图"))
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)

        val explicitSinglePlan = requireNotNull(runtime.plan("识别最近1张截图文字"))
        assertEquals(BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL, explicitSinglePlan.request.skillId)

        val ocrFirstPlan = requireNotNull(runtime.plan("OCR 最近截图"))
        assertEquals(BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL, ocrFirstPlan.request.skillId)

        val englishPlan = requireNotNull(runtime.plan("read text from latest screenshot"))
        assertEquals(BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL, englishPlan.request.skillId)
        val englishStep = englishPlan.steps.single()
        require(englishStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR, englishStep.request.toolName)
        assertEquals("1", englishStep.request.arguments["maxCount"])

        assertEquals(null, runtime.plan("识别最近2张截图文字"))
        assertEquals(null, runtime.plan("识别最近两张截图文字"))
        assertEquals(null, runtime.plan("read text from recent 2 screenshots"))
        assertEquals(null, runtime.plan("读取所有截图 OCR"))
        assertEquals(null, runtime.plan("不要识别最近截图文字"))
        assertEquals(null, runtime.plan("别读最近截图文字"))
        assertEquals(null, runtime.plan("截图 OCR 怎么实现"))
        assertEquals(null, runtime.plan("screenshot OCR API"))
        assertEquals(null, runtime.plan("当前屏幕 OCR"))
    }

    @Test
    fun plansRecentImageOcrWithoutActionDraftWhenCommandIsExplicit() {
        val plan = requireNotNull(runtime.plan("识别最近图片文字"))

        assertEquals(BuiltInSkillRuntime.RECENT_IMAGE_OCR_CONTEXT_SKILL, plan.request.skillId)
        assertEquals(mapOf("input" to "识别最近图片文字"), plan.request.arguments)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, step.request.toolName)
        assertEquals("3", step.request.arguments["maxCount"])
        assertEquals(MobileActionFunctions.READ_RECENT_IMAGE_OCR, step.draft.functionName)
        assertTrue(step.draft.requiresConfirmation)
        assertTrue(step.draft.summary.contains("最近 3 张图片"))
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)

        val countPlan = requireNotNull(runtime.plan("识别最近2张照片文字"))
        assertEquals(BuiltInSkillRuntime.RECENT_IMAGE_OCR_CONTEXT_SKILL, countPlan.request.skillId)
        val countStep = countPlan.steps.single()
        require(countStep is SkillStep.ToolStep)
        assertEquals("2", countStep.request.arguments["maxCount"])

        val englishPlan = requireNotNull(runtime.plan("read text from recent photos"))
        assertEquals(BuiltInSkillRuntime.RECENT_IMAGE_OCR_CONTEXT_SKILL, englishPlan.request.skillId)

        assertEquals(null, runtime.plan("识别最近4张图片文字"))
        assertEquals(null, runtime.plan("识别最近四张照片文字"))
        assertEquals(null, runtime.plan("read text from recent 4 photos"))
        assertEquals(null, runtime.plan("读取所有图片 OCR"))
        assertEquals(null, runtime.plan("不要识别最近图片文字"))
        assertEquals(null, runtime.plan("不要 OCR 最近图片"))
        assertEquals(null, runtime.plan("图片 OCR API"))
        assertEquals(null, runtime.plan("描述最近图片"))
        assertEquals(null, runtime.plan("图片里有什么"))
    }

    @Test
    fun plansHttpsDeepLinkWithoutActionDraftWhenCommandIsExplicit() {
        val plan = requireNotNull(runtime.plan("打开链接 https://example.com/path?q=agent"))

        assertEquals(BuiltInSkillRuntime.DEEP_LINK_NAVIGATION_SKILL, plan.request.skillId)
        assertEquals(mapOf("input" to "打开链接 https://example.com/path?q=agent"), plan.request.arguments)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.OPEN_DEEP_LINK, step.request.toolName)
        assertEquals("https://example.com/path?q=agent", step.request.arguments["uri"])
        assertEquals(MobileActionFunctions.OPEN_DEEP_LINK, step.draft.functionName)
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)

        assertEquals(null, runtime.plan("https://example.com/path"))
        assertEquals(null, runtime.plan("解释 https://example.com/path 是什么"))
        assertEquals(null, runtime.plan("how do I open https://example.com/path"))
        assertEquals(null, runtime.plan("不要打开 https://example.com/path"))
        assertEquals(null, runtime.plan("不要跳转到 https://example.com/path"))
        assertEquals(null, runtime.plan("打开链接 http://example.com/path"))
        assertEquals(null, runtime.plan("打开链接 file:///tmp/a"))
        assertEquals(null, runtime.plan("打开链接 javascript:alert(1)"))
    }

    @Test
    fun plansAppNavigationWithoutActionDraftWhenCommandIsExplicit() {
        val launchPlan = requireNotNull(runtime.plan("启动微信"))

        assertEquals(BuiltInSkillRuntime.APP_NAVIGATION_SKILL, launchPlan.request.skillId)
        assertEquals(mapOf("input" to "启动微信"), launchPlan.request.arguments)
        val launchStep = launchPlan.steps.single()
        require(launchStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.OPEN_APP_INTENT, launchStep.request.toolName)
        assertEquals("com.tencent.mm", launchStep.request.arguments["packageName"])
        assertEquals(MobileActionFunctions.OPEN_APP_INTENT, launchStep.draft.functionName)
        assertTrue(launchPlan.validateStructure().errors.joinToString(), launchPlan.validateStructure().isValid)

        val packagePlan = requireNotNull(runtime.plan("打开 com.example.app"))
        val packageStep = packagePlan.steps.single()
        require(packageStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.OPEN_APP_INTENT, packageStep.request.toolName)
        assertEquals("com.example.app", packageStep.request.arguments["packageName"])

        val detailsPlan = requireNotNull(runtime.plan("打开微信应用详情设置"))
        assertEquals(BuiltInSkillRuntime.APP_NAVIGATION_SKILL, detailsPlan.request.skillId)
        val detailsStep = detailsPlan.steps.single()
        require(detailsStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.OPEN_APP_DEEP_TARGET, detailsStep.request.toolName)
        assertEquals(AppDeepTargets.APP_DETAILS_SETTINGS_ID, detailsStep.request.arguments["targetId"])
        assertEquals("com.tencent.mm", detailsStep.request.arguments["packageName"])
        assertEquals(MobileActionFunctions.OPEN_APP_DEEP_TARGET, detailsStep.draft.functionName)
        assertTrue(detailsPlan.validateStructure().errors.joinToString(), detailsPlan.validateStructure().isValid)

        assertEquals(null, runtime.plan("打开应用"))
        assertEquals(null, runtime.plan("启动 app"))
        assertEquals(null, runtime.plan("打开应用详情设置"))
        assertEquals(null, runtime.plan("不要打开微信"))
        assertEquals(null, runtime.plan("别启动微信"))
        assertEquals(null, runtime.plan("不启动微信"))
        assertEquals(null, runtime.plan("请勿打开微信"))
        assertEquals(null, runtime.plan("如何打开微信"))
        assertEquals(null, runtime.plan("怎么打开微信"))
        assertEquals(null, runtime.plan("打开微信小程序"))
        assertEquals(null, runtime.plan("打开微信支付收款码"))
        assertEquals(null, runtime.plan("打开微信应用设置"))
        assertEquals(null, runtime.plan("open WeChat app settings"))
        assertEquals(null, runtime.plan("open source WeChat SDK"))
        assertEquals(null, runtime.plan("start using WeChat"))
        assertEquals(null, runtime.plan("微信打不开怎么办"))
        assertEquals(null, runtime.plan("微信打开方式"))
        assertEquals(null, runtime.plan("打开微信扫一扫"))
        assertEquals(null, runtime.plan("打开微信聊天"))
        assertEquals(null, runtime.plan("打开微信朋友圈"))
        assertEquals(null, runtime.plan("打开微信权限页"))
        assertEquals(null, runtime.plan("打开微信通知设置"))
        assertEquals(null, runtime.plan("打开 package:com.example.app"))
        assertEquals(null, runtime.plan("打开 intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(null, runtime.plan("打开 file:///sdcard/private.txt"))
        assertEquals(null, runtime.plan("打开 com.example.app/.MainActivity 应用详情设置"))
        assertEquals(null, runtime.plan("打开微信应用详情设置然后发消息"))
    }

    @Test
    fun plansForegroundAppWithoutActionDraftWhenCommandIsExplicit() {
        val plan = requireNotNull(runtime.plan("当前应用是什么"))

        assertEquals(BuiltInSkillRuntime.FOREGROUND_APP_CONTEXT_SKILL, plan.request.skillId)
        assertEquals(mapOf("input" to "当前应用是什么"), plan.request.arguments)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.QUERY_FOREGROUND_APP, step.request.toolName)
        assertTrue(step.request.arguments.isEmpty())
        assertEquals(MobileActionFunctions.QUERY_FOREGROUND_APP, step.draft.functionName)
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)

        val englishPlan = requireNotNull(runtime.plan("what app is currently open"))
        assertEquals(BuiltInSkillRuntime.FOREGROUND_APP_CONTEXT_SKILL, englishPlan.request.skillId)
        val englishStep = englishPlan.steps.single()
        require(englishStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.QUERY_FOREGROUND_APP, englishStep.request.toolName)

        assertEquals(null, runtime.plan("前台服务限制是什么"))
        assertEquals(null, runtime.plan("current app architecture"))
        assertEquals(null, runtime.plan("how do I implement current app state"))
        assertEquals(null, runtime.plan("不要查询当前应用"))
        assertEquals(null, runtime.plan("don't tell me the current app"))
        assertEquals(null, runtime.plan("当前应用权限怎么申请"))
        assertEquals(null, runtime.plan("前台应用 API 怎么用"))
    }

    @Test
    fun plansRecentNotificationsWithoutActionDraftWhenCurrentAppRequestIsExplicit() {
        val plan = requireNotNull(runtime.plan("最近 3 条通知"))

        assertEquals(BuiltInSkillRuntime.RECENT_NOTIFICATIONS_CONTEXT_SKILL, plan.request.skillId)
        assertEquals(mapOf("input" to "最近 3 条通知"), plan.request.arguments)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS, step.request.toolName)
        assertEquals("3", step.request.arguments["maxCount"])
        assertEquals(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS, step.draft.functionName)
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)

        val englishPlan = requireNotNull(runtime.plan("current app last 2 notifications"))
        assertEquals(BuiltInSkillRuntime.RECENT_NOTIFICATIONS_CONTEXT_SKILL, englishPlan.request.skillId)
        val englishStep = englishPlan.steps.single()
        require(englishStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS, englishStep.request.toolName)
        assertEquals("2", englishStep.request.arguments["maxCount"])

        assertEquals(null, runtime.plan("notification"))
        assertEquals(null, runtime.plan("notifications"))
        assertEquals(null, runtime.plan("recent app notifications"))
        assertEquals(null, runtime.plan("notification permission"))
        assertEquals(null, runtime.plan("notification channel"))
        assertEquals(null, runtime.plan("push notification"))
        assertEquals(null, runtime.plan("系统通知"))
        assertEquals(null, runtime.plan("通知栏"))
        assertEquals(null, runtime.plan("不要读取最近通知"))
        assertEquals(null, runtime.plan("don't read current app notifications"))
        assertEquals(null, runtime.plan("current app notifications API"))
    }

    @Test
    fun plansCurrentScreenTextWithoutActionDraftWhenCommandIsExplicit() {
        val input = "总结当前屏幕文字，最多1200字"
        val plan = requireNotNull(runtime.plan(input))

        assertEquals(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_CONTEXT_SKILL, plan.request.skillId)
        assertEquals(mapOf("input" to input), plan.request.arguments)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, step.request.toolName)
        assertEquals("1200", step.request.arguments["maxChars"])
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, step.draft.functionName)
        assertTrue(step.draft.summary.contains("可访问文本快照"))
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)

        val englishPlan = requireNotNull(runtime.plan("summarize current screen text max 1200 chars"))
        assertEquals(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_CONTEXT_SKILL, englishPlan.request.skillId)
        val englishStep = englishPlan.steps.single()
        require(englishStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, englishStep.request.toolName)
        assertEquals("1200", englishStep.request.arguments["maxChars"])

        assertEquals(null, runtime.plan("看看当前屏幕"))
        assertEquals(null, runtime.plan("识别当前屏幕截图文字"))
        assertEquals(null, runtime.plan("当前屏幕 OCR"))
        assertEquals(null, runtime.plan("current screen screenshot text"))
        assertEquals(null, runtime.plan("不要读取当前屏幕文字"))
        assertEquals(null, runtime.plan("不要查看当前屏幕内容"))
        assertEquals(null, runtime.plan("总结这页内容"))
        assertEquals(null, runtime.plan("总结页面内容"))
        assertEquals(null, runtime.plan("what is current screen text"))
        assertEquals(null, runtime.plan("current screen text API"))
        assertEquals(null, runtime.plan("how to implement current screen state"))
    }

    @Test
    fun plansContactLookupWithoutActionDraftWhenQueryIsExplicit() {
        val plan = requireNotNull(runtime.plan("最多 3 个联系人里查 Alice"))

        assertEquals(BuiltInSkillRuntime.CONTACT_LOOKUP_SKILL, plan.request.skillId)
        assertEquals(mapOf("input" to "最多 3 个联系人里查 Alice"), plan.request.arguments)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, step.request.toolName)
        assertEquals("Alice", step.request.arguments["query"])
        assertEquals("3", step.request.arguments["maxCount"])
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, step.draft.functionName)
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)

        val englishPlan = requireNotNull(runtime.plan("look up Alice in contacts"))
        assertEquals(BuiltInSkillRuntime.CONTACT_LOOKUP_SKILL, englishPlan.request.skillId)
        val englishStep = englishPlan.steps.single()
        require(englishStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.QUERY_CONTACTS, englishStep.request.toolName)
        assertEquals("Alice", englishStep.request.arguments["query"])

        assertEquals(null, runtime.plan("联系人"))
        assertEquals(null, runtime.plan("contact"))
        assertEquals(null, runtime.plan("查询联系人"))
        assertEquals(null, runtime.plan("联系人权限"))
        assertEquals(null, runtime.plan("ContactsContract 怎么用"))
        assertEquals(null, runtime.plan("search contacts API"))
        assertEquals(null, runtime.plan("不要查联系人 Alice"))
        assertEquals(null, runtime.plan("不要搜索联系人 Alice"))
        assertEquals(null, runtime.plan("do not search contacts for Alice"))
    }

    @Test
    fun plansContactDraftWithoutActionDraftWhenCommandIsExplicit() {
        val plan = requireNotNull(runtime.plan("新建联系人 Alice"))

        assertEquals(BuiltInSkillRuntime.CONTACT_DRAFT_SKILL, plan.request.skillId)
        assertEquals(mapOf("input" to "新建联系人 Alice"), plan.request.arguments)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.CREATE_CONTACT_DRAFT, step.request.toolName)
        assertEquals("Alice", step.request.arguments["name"])
        assertEquals(MobileActionFunctions.CREATE_CONTACT_DRAFT, step.draft.functionName)
        assertTrue(step.draft.requiresConfirmation)
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)

        val englishPlan = requireNotNull(runtime.plan("create contact Bob bob@example.com +1 555 0100"))
        assertEquals(BuiltInSkillRuntime.CONTACT_DRAFT_SKILL, englishPlan.request.skillId)
        val englishStep = englishPlan.steps.single()
        require(englishStep is SkillStep.ToolStep)
        assertEquals("Bob", englishStep.request.arguments["name"])
        assertEquals("bob@example.com", englishStep.request.arguments["email"])
        assertEquals("+1 555 0100", englishStep.request.arguments["phone"])

        assertEquals(null, runtime.plan("新建联系人"))
        assertEquals(null, runtime.plan("不要新建联系人 Alice"))
        assertEquals(null, runtime.plan("联系人权限怎么申请"))
        assertEquals(null, runtime.plan("ContactsContract 怎么用"))
        assertEquals(null, runtime.plan("编辑联系人 Alice"))
        assertEquals(null, runtime.plan("删除联系人 Alice"))
        assertEquals(null, runtime.plan("导出联系人"))
    }

    @Test
    fun plansCalendarAvailabilityWithoutActionDraftWhenIsoWindowIsExplicit() {
        val input = "查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z"
        val plan = requireNotNull(runtime.plan(input))

        assertEquals(BuiltInSkillRuntime.CALENDAR_AVAILABILITY_SKILL, plan.request.skillId)
        assertEquals(mapOf("input" to input), plan.request.arguments)
        val step = plan.steps.single()
        require(step is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY, step.request.toolName)
        assertEquals("2026-06-01T09:00:00Z", step.request.arguments["start"])
        assertEquals("2026-06-01T10:00:00Z", step.request.arguments["end"])
        assertEquals(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY, step.draft.functionName)
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)

        val englishPlan = requireNotNull(
            runtime.plan("calendar availability 2026-06-01T09:00:00Z to 2026-06-01T10:00:00Z"),
        )
        assertEquals(BuiltInSkillRuntime.CALENDAR_AVAILABILITY_SKILL, englishPlan.request.skillId)

        assertEquals(null, runtime.plan("查一下忙闲"))
        assertEquals(null, runtime.plan("明天我有空吗"))
        assertEquals(null, runtime.plan("日历权限怎么申请"))
        assertEquals(null, runtime.plan("不要查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z"))
        assertEquals(null, runtime.plan("不要看我有空 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z"))
        assertEquals(null, runtime.plan("what is free/busy"))
        assertEquals(null, runtime.plan("API availability 2026-06-01T09:00:00Z to 2026-06-01T10:00:00Z"))
        assertEquals(null, runtime.plan("calendar availability API"))
        assertEquals(null, runtime.plan("free/busy schema 2026-06-01T09:00:00Z to 2026-06-01T10:00:00Z"))
        assertEquals(null, runtime.plan("how to implement free/busy"))
        assertEquals(null, runtime.plan("查忙闲 2026-06-01T10:00:00Z 到 2026-06-01T09:00:00Z"))
        assertEquals(null, runtime.plan("查忙闲 2026-06-01T09:00:00Z 到 2026-07-10T09:00:00Z"))
    }

    @Test
    fun plansShareTextWithoutActionDraftWhenTextIsExplicit() {
        val plan = runtime.plan("分享这段文字：明天十点开会")

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.SHARE_TEXT_SKILL, plan.request.skillId)
        assertEquals(1, plan.steps.size)
        val shareStep = plan.steps.single()
        require(shareStep is SkillStep.ToolStep)
        assertEquals(MobileActionFunctions.SHARE_TEXT, shareStep.request.toolName)
        assertEquals("明天十点开会", shareStep.request.arguments["text"])
        assertEquals("明天十点开会", shareStep.draft.parameters["text"])
        assertTrue(shareStep.draft.requiresConfirmation)
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)

        val negativePayloadPlan = requireNotNull(runtime.plan("分享这段文字：不要分享内部资料"))
        val negativePayloadStep = negativePayloadPlan.steps.single()
        require(negativePayloadStep is SkillStep.ToolStep)
        assertEquals("不要分享内部资料", negativePayloadStep.request.arguments["text"])

        val englishNegativePayloadPlan = requireNotNull(runtime.plan("share this text: don't share credentials"))
        val englishNegativePayloadStep = englishNegativePayloadPlan.steps.single()
        require(englishNegativePayloadStep is SkillStep.ToolStep)
        assertEquals("don't share credentials", englishNegativePayloadStep.request.arguments["text"])
    }

    @Test
    fun plansShareTextAsShareSheetToolStep() {
        val draft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "系统分享",
            summary = "将打开系统分享面板并填入文本。",
            parameters = mapOf("text" to "明天十点开会"),
        )
        val request = ToolRequest(
            toolName = draft.functionName,
            arguments = draft.parameters,
            reason = draft.summary,
        )

        val plan = runtime.plan("分享这段文字：明天十点开会", draft, request)

        requireNotNull(plan)
        assertEquals(BuiltInSkillRuntime.SHARE_TEXT_SKILL, plan.request.skillId)
        assertEquals(listOf(MobileActionFunctions.SHARE_TEXT), plan.manifest.requiredTools)
    }

    @Test
    fun plansClipboardSummaryShareAsOrderedCompositeSkill() {
        val plan = runtime.planClipboardSummaryShare("总结剪贴板并分享")

        assertEquals(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL, plan.request.skillId)
        assertEquals(
            listOf(MobileActionFunctions.READ_CLIPBOARD, MobileActionFunctions.SHARE_TEXT),
            plan.manifest.requiredTools,
        )
        assertTrue(plan.validateStructure().isValid)
        assertEquals(3, plan.steps.size)

        val readStep = plan.steps[0]
        require(readStep is SkillStep.ToolStep)
        assertEquals("read_clipboard", readStep.id)
        assertEquals(MobileActionFunctions.READ_CLIPBOARD, readStep.request.toolName)
        assertTrue(readStep.dependsOn.isEmpty())

        val summarizeStep = plan.steps[1]
        require(summarizeStep is SkillStep.ModelStep)
        assertEquals("summarize_clipboard", summarizeStep.id)
        assertEquals(listOf(readStep.id), summarizeStep.dependsOn)
        assertEquals(mapOf("clipboardText" to "read_clipboard.text"), summarizeStep.inputBindings)
        assertEquals("shareText", summarizeStep.outputKey)
        assertTrue(summarizeStep.keepsSensitiveInputLocal)

        val shareStep = plan.steps[2]
        require(shareStep is SkillStep.ToolStep)
        assertEquals("share_summary", shareStep.id)
        assertEquals(MobileActionFunctions.SHARE_TEXT, shareStep.request.toolName)
        assertEquals(listOf(summarizeStep.id), shareStep.dependsOn)
        assertEquals(mapOf("text" to "summarize_clipboard.shareText"), shareStep.argumentBindings)
    }

    @Test
    fun plansCurrentScreenTextSummaryShareAsOrderedCompositeSkill() {
        val plan = runtime.planCurrentScreenTextSummaryShare("总结当前屏幕文字并分享")

        assertEquals(BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL, plan.request.skillId)
        assertEquals(
            listOf(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, MobileActionFunctions.SHARE_TEXT),
            plan.manifest.requiredTools,
        )
        assertTrue(plan.validateStructure().errors.joinToString(), plan.validateStructure().isValid)
        assertEquals(3, plan.steps.size)

        val readStep = plan.steps[0]
        require(readStep is SkillStep.ToolStep)
        assertEquals("read_current_screen_text", readStep.id)
        assertEquals(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT, readStep.request.toolName)
        assertEquals(mapOf("maxChars" to "2000"), readStep.request.arguments)
        assertTrue(readStep.dependsOn.isEmpty())

        val summarizeStep = plan.steps[1]
        require(summarizeStep is SkillStep.ModelStep)
        assertEquals("summarize_current_screen_text", summarizeStep.id)
        assertEquals(listOf(readStep.id), summarizeStep.dependsOn)
        assertEquals(mapOf("screenText" to "read_current_screen_text.screenText"), summarizeStep.inputBindings)
        assertEquals("shareText", summarizeStep.outputKey)
        assertTrue(summarizeStep.keepsSensitiveInputLocal)

        val shareStep = plan.steps[2]
        require(shareStep is SkillStep.ToolStep)
        assertEquals("share_screen_summary", shareStep.id)
        assertEquals(MobileActionFunctions.SHARE_TEXT, shareStep.request.toolName)
        assertEquals(listOf(summarizeStep.id), shareStep.dependsOn)
        assertEquals(mapOf("text" to "summarize_current_screen_text.shareText"), shareStep.argumentBindings)
    }

    @Test
    fun currentScreenTextSummaryShareRejectsFalsePositiveQuestionsAndNegations() {
        assertEquals(null, runtime.plan("如何总结当前屏幕文字并分享"))
        assertEquals(null, runtime.plan("不要总结当前屏幕文字并分享"))
        assertEquals(null, runtime.plan("explain how to summarize current screen text and share it"))
    }

    @Test
    fun validateStructureRejectsUnorderedOrInvalidCompositePlan() {
        val plan = runtime.planClipboardSummaryShare("总结剪贴板并分享")
        val invalidPlan = plan.copy(
            steps = listOf(
                plan.steps[2],
                plan.steps[1],
                plan.steps[0],
            ),
        )

        val validation = invalidPlan.validateStructure()

        assertFalse(validation.isValid)
        assertTrue(validation.errors.any { it.contains("share_summary depends on missing or later step") })
        assertTrue(validation.errors.any { it.contains("summarize_clipboard depends on missing or later step") })
    }

    private fun draft(
        toolName: String,
        parameters: Map<String, String> = emptyMap(),
    ): ActionDraft =
        ActionDraft(
            functionName = toolName,
            title = "Test",
            summary = "Test summary",
            parameters = parameters,
        )
}
