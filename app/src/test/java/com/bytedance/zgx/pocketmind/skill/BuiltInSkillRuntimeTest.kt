package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
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
        assertTrue(BuiltInSkillRuntime.MAP_SEARCH_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.INFORMATION_LOOKUP_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.REMINDER_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.SHARE_TEXT_SKILL in manifests)
        assertTrue(BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL in manifests)
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
        assertEquals(listOf(MobileActionFunctions.SCHEDULE_REMINDER), plan.manifest.requiredTools)
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
    fun skillFirstPlannerDoesNotTreatOrdinaryShareDiscussionAsShareTool() {
        val plan = runtime.plan("分享一下你对端侧 Agent 的看法")

        assertEquals(null, plan)
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
