package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.CalendarDraftActionParser
import com.bytedance.zgx.pocketmind.action.DeviceSettingsActionParser
import com.bytedance.zgx.pocketmind.action.EmailDraftActionParser
import com.bytedance.zgx.pocketmind.action.MapSearchActionParser
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.action.ReminderActionParser
import com.bytedance.zgx.pocketmind.action.ShareTextActionParser
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import java.util.UUID

class BuiltInSkillRuntime : SkillRuntime {
    private val manifestsById = builtInSkillManifests.associateBy { it.id }
    private val skillByToolName = mapOf(
        MobileActionFunctions.COMPOSE_EMAIL to EMAIL_DRAFT_SKILL,
        MobileActionFunctions.CREATE_CALENDAR_EVENT to CALENDAR_DRAFT_SKILL,
        MobileActionFunctions.SEARCH_MAPS to MAP_SEARCH_SKILL,
        MobileActionFunctions.WEB_SEARCH to INFORMATION_LOOKUP_SKILL,
        MobileActionFunctions.OPEN_WIFI_SETTINGS to DEVICE_SETTINGS_SKILL,
        MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS to DEVICE_SETTINGS_SKILL,
        MobileActionFunctions.SCHEDULE_REMINDER to REMINDER_SKILL,
        MobileActionFunctions.READ_CLIPBOARD to CLIPBOARD_CONTEXT_SKILL,
        MobileActionFunctions.SHARE_TEXT to SHARE_TEXT_SKILL,
    )

    override fun manifests(): List<SkillManifest> = builtInSkillManifests

    override fun plan(input: String): SkillPlan? =
        when {
            input.requestsClipboardSummaryShare() -> planClipboardSummaryShare(input)
            MapSearchActionParser.matches(input) ->
                plan(input, MapSearchActionParser.draft(input).toRequestPair())

            EmailDraftActionParser.matches(input) ->
                plan(input, EmailDraftActionParser.draft(input).toRequestPair())

            CalendarDraftActionParser.matches(input) ->
                plan(input, CalendarDraftActionParser.draft(input).toRequestPair())

            DeviceSettingsActionParser.matches(input) ->
                plan(input, DeviceSettingsActionParser.draft(input).toRequestPair())

            ShareTextActionParser.matches(input) -> {
                val draft = ShareTextActionParser.draft(input)
                val request = ToolRequest(
                    toolName = draft.functionName,
                    arguments = draft.parameters,
                    reason = draft.summary,
                )
                plan(input, draft, request)
            }

            ReminderActionParser.matches(input) -> {
                val draft = ReminderActionParser.draft(input)
                val request = ToolRequest(
                    toolName = draft.functionName,
                    arguments = draft.parameters,
                    reason = draft.summary,
                )
                plan(input, draft, request)
            }

            input.requestsClipboardContext() -> {
                val draft = ActionDraft(
                    functionName = MobileActionFunctions.READ_CLIPBOARD,
                    title = "读取剪贴板",
                    summary = "将读取当前剪贴板文本。",
                    parameters = emptyMap(),
                )
                val request = ToolRequest(
                    toolName = MobileActionFunctions.READ_CLIPBOARD,
                    reason = draft.summary,
                )
                plan(input, draft, request)
            }

            else -> null
        }

    private fun plan(input: String, pair: DraftRequestPair): SkillPlan? =
        plan(input, pair.draft, pair.request)

    override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? {
        if (request.toolName == MobileActionFunctions.READ_CLIPBOARD && input.requestsClipboardSummaryShare()) {
            return planClipboardSummaryShare(
                input = input,
                readRequest = request,
                readDraft = draft,
            )
        }
        val skillId = skillByToolName[request.toolName] ?: return null
        val manifest = manifestsById.getValue(skillId)
        if (request.toolName !in manifest.requiredTools) return null
        return SkillPlan(
            request = SkillRequest(
                id = UUID.randomUUID().toString(),
                skillId = skillId,
                arguments = mapOf("input" to input),
                reason = draft.summary.ifBlank { input },
            ),
            manifest = manifest,
            steps = listOf(SkillStep.ToolStep(request, draft)),
        )
    }

    fun planClipboardSummaryShare(
        input: String,
        readRequest: ToolRequest? = null,
        readDraft: ActionDraft? = null,
    ): SkillPlan {
        val manifest = manifestsById.getValue(CLIPBOARD_SUMMARY_SHARE_SKILL)
        val readStepId = "read_clipboard"
        val summarizeStepId = "summarize_clipboard"

        val resolvedReadDraft = readDraft ?: ActionDraft(
            functionName = MobileActionFunctions.READ_CLIPBOARD,
            title = "读取剪贴板",
            summary = "将读取当前剪贴板文本，用于生成可分享摘要。",
            parameters = emptyMap(),
        )
        val resolvedReadRequest = readRequest ?: ToolRequest(
            toolName = MobileActionFunctions.READ_CLIPBOARD,
            reason = resolvedReadDraft.summary,
        )
        val shareDraft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "分享摘要",
            summary = "将打开系统分享面板并填入上一步生成的摘要。",
            parameters = emptyMap(),
        )
        val shareRequest = ToolRequest(
            toolName = MobileActionFunctions.SHARE_TEXT,
            reason = shareDraft.summary,
        )

        return SkillPlan(
            request = SkillRequest(
                id = UUID.randomUUID().toString(),
                skillId = CLIPBOARD_SUMMARY_SHARE_SKILL,
                arguments = mapOf("input" to input),
                reason = input,
            ),
            manifest = manifest,
            steps = listOf(
                SkillStep.ToolStep(
                    id = readStepId,
                    request = resolvedReadRequest,
                    draft = resolvedReadDraft,
                ),
                SkillStep.ModelStep(
                    id = summarizeStepId,
                    dependsOn = listOf(readStepId),
                    title = "摘要剪贴板内容",
                    instruction = "把用户确认读取的剪贴板文本整理成适合分享的简短摘要，语言尽量跟随用户请求。",
                    inputBindings = mapOf("clipboardText" to "$readStepId.text"),
                    outputKey = "shareText",
                    keepsSensitiveInputLocal = true,
                ),
                SkillStep.ToolStep(
                    id = "share_summary",
                    dependsOn = listOf(summarizeStepId),
                    request = shareRequest,
                    draft = shareDraft,
                    argumentBindings = mapOf("text" to "$summarizeStepId.shareText"),
                ),
            ),
        )
    }

    companion object {
        const val EMAIL_DRAFT_SKILL = "email_draft_skill"
        const val CALENDAR_DRAFT_SKILL = "calendar_draft_skill"
        const val MAP_SEARCH_SKILL = "map_search_skill"
        const val INFORMATION_LOOKUP_SKILL = "information_lookup_skill"
        const val DEVICE_SETTINGS_SKILL = "device_settings_skill"
        const val REMINDER_SKILL = "reminder_skill"
        const val CLIPBOARD_CONTEXT_SKILL = "clipboard_context_skill"
        const val SHARE_TEXT_SKILL = "share_text_skill"
        const val CLIPBOARD_SUMMARY_SHARE_SKILL = "clipboard_summary_share_skill"
    }
}

private fun String.requestsClipboardSummaryShare(): Boolean {
    val normalized = lowercase()
    val referencesClipboard = "剪贴板" in this || "clipboard" in normalized
    val asksForSummary = listOf("总结", "摘要", "概括", "归纳").any { it in this } ||
        Regex("""\b(summarize|summary|brief|recap)\b""").containsMatchIn(normalized)
    val asksToShare = "分享" in this ||
        Regex("""\bshare\b""").containsMatchIn(normalized)
    return referencesClipboard && asksForSummary && asksToShare
}

private fun String.requestsClipboardContext(): Boolean {
    val normalized = lowercase()
    val referencesClipboard = "剪贴板" in this || "clipboard" in normalized
    val asksToRead = listOf("读取", "读一下", "看看", "查看", "总结", "摘要", "概括").any { it in this } ||
        Regex("""\b(read|summarize|summary|recap)\b""").containsMatchIn(normalized)
    val asksToShare = "分享" in this || Regex("""\bshare\b""").containsMatchIn(normalized)
    return referencesClipboard && asksToRead && !asksToShare
}

private data class DraftRequestPair(
    val draft: ActionDraft,
    val request: ToolRequest,
)

private fun ActionDraft.toRequestPair(): DraftRequestPair =
    DraftRequestPair(
        draft = this,
        request = ToolRequest(
            toolName = functionName,
            arguments = parameters,
            reason = summary,
        ),
    )

private val simpleTextInputSchema = """
    {
      "type": "object",
      "required": ["input"],
      "properties": {
        "input": {
          "type": "string",
          "minLength": 1
        }
      },
      "additionalProperties": false
    }
""".trimIndent()

private val builtInSkillManifests = listOf(
    SkillManifest(
        id = BuiltInSkillRuntime.EMAIL_DRAFT_SKILL,
        version = 1,
        title = "邮件草稿",
        description = "把自然语言请求整理成邮件草稿工具调用，不直接发送邮件。",
        triggerExamples = listOf("帮我写封邮件", "draft an email"),
        requiredTools = listOf(MobileActionFunctions.COMPOSE_EMAIL),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CALENDAR_DRAFT_SKILL,
        version = 1,
        title = "日程草稿",
        description = "把自然语言请求整理成日历新建事件工具调用。",
        triggerExamples = listOf("帮我建个日程", "add a calendar event"),
        requiredTools = listOf(MobileActionFunctions.CREATE_CALENDAR_EVENT),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.MAP_SEARCH_SKILL,
        version = 1,
        title = "路线查询",
        description = "提取地点或路线关键词并交给地图搜索工具。",
        triggerExamples = listOf("查去机场的路线", "search maps for coffee nearby"),
        requiredTools = listOf(MobileActionFunctions.SEARCH_MAPS),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.INFORMATION_LOOKUP_SKILL,
        version = 1,
        title = "信息查找",
        description = "把需要外部信息的请求整理成受确认保护的网页搜索工具调用。",
        triggerExamples = listOf("帮我查一下", "look up Kotlin"),
        requiredTools = listOf(MobileActionFunctions.WEB_SEARCH),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL,
        version = 1,
        title = "设备设置入口",
        description = "打开受控系统设置入口，由用户在系统页面内继续操作。",
        triggerExamples = listOf("打开 Wi-Fi 设置", "打开手电筒设置"),
        requiredTools = listOf(
            MobileActionFunctions.OPEN_WIFI_SETTINGS,
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.REMINDER_SKILL,
        version = 1,
        title = "后台提醒",
        description = "把自然语言提醒请求整理成本地后台提醒工具调用。",
        triggerExamples = listOf("提醒我 10 分钟后喝水", "remind me in 1 hour"),
        requiredTools = listOf(MobileActionFunctions.SCHEDULE_REMINDER),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CLIPBOARD_CONTEXT_SKILL,
        version = 1,
        title = "剪贴板上下文",
        description = "在用户明确要求时读取当前剪贴板文本。",
        triggerExamples = listOf("读取剪贴板", "summarize my clipboard"),
        requiredTools = listOf(MobileActionFunctions.READ_CLIPBOARD),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.SHARE_TEXT_SKILL,
        version = 1,
        title = "系统分享",
        description = "把文本放入 Android 系统分享面板，由用户选择目标应用。",
        triggerExamples = listOf("分享这段文字", "share this text"),
        requiredTools = listOf(MobileActionFunctions.SHARE_TEXT),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL,
        version = 1,
        title = "剪贴板摘要分享",
        description = "读取剪贴板文本，先由本地模型生成摘要，再通过系统分享面板外发。",
        triggerExamples = listOf("总结剪贴板并分享", "summarize my clipboard and share it"),
        requiredTools = listOf(
            MobileActionFunctions.READ_CLIPBOARD,
            MobileActionFunctions.SHARE_TEXT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.HighExternalSend,
    ),
)
