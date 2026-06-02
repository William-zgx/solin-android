package com.bytedance.zgx.pocketmind.skill

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.AppNavigationActionParser
import com.bytedance.zgx.pocketmind.action.BackgroundTasksQueryActionParser
import com.bytedance.zgx.pocketmind.action.CalendarAvailabilityActionParser
import com.bytedance.zgx.pocketmind.action.CalendarDraftActionParser
import com.bytedance.zgx.pocketmind.action.CancelReminderActionParser
import com.bytedance.zgx.pocketmind.action.ContactDraftActionParser
import com.bytedance.zgx.pocketmind.action.ContactQueryActionParser
import com.bytedance.zgx.pocketmind.action.CurrentScreenTextActionParser
import com.bytedance.zgx.pocketmind.action.CurrentScreenshotOcrActionParser
import com.bytedance.zgx.pocketmind.action.DeepLinkActionParser
import com.bytedance.zgx.pocketmind.action.DeviceSettingsActionParser
import com.bytedance.zgx.pocketmind.action.EmailDraftActionParser
import com.bytedance.zgx.pocketmind.action.ForegroundAppActionParser
import com.bytedance.zgx.pocketmind.action.MapSearchActionParser
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.action.PeriodicCheckActionParser
import com.bytedance.zgx.pocketmind.action.RecentFilesActionParser
import com.bytedance.zgx.pocketmind.action.RecentImageOcrActionParser
import com.bytedance.zgx.pocketmind.action.RecentNotificationsActionParser
import com.bytedance.zgx.pocketmind.action.RecentScreenshotOcrActionParser
import com.bytedance.zgx.pocketmind.action.ReminderActionParser
import com.bytedance.zgx.pocketmind.action.ShareTextActionParser
import com.bytedance.zgx.pocketmind.action.WebSearchActionParser
import com.bytedance.zgx.pocketmind.tool.RiskLevel
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import java.util.UUID

class BuiltInSkillRuntime : SkillRuntime {
    internal val catalog: SkillCatalog = builtInSkillCatalog
    private val manifestsById = catalog.manifestsById
    private val skillByToolName = catalog.skillIdByToolName

    override fun manifests(): List<SkillManifest> = catalog.manifests()

    override fun plan(input: String): SkillPlan? =
        when {
            input.looksLikeCurrentScreenTextSummaryShareNonAction(input.lowercase()) -> null
            !input.looksLikeSequentialAction() && input.requestsClipboardSummaryShare() -> planClipboardSummaryShare(input)
            !input.looksLikeSequentialAction() && input.requestsCurrentScreenTextSummaryShare() ->
                planCurrentScreenTextSummaryShare(input)

            !input.looksLikeSequentialAction() && MapSearchActionParser.matches(input) ->
                plan(input, MapSearchActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && EmailDraftActionParser.matches(input) ->
                plan(input, EmailDraftActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && CalendarDraftActionParser.matches(input) ->
                plan(input, CalendarDraftActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && CalendarAvailabilityActionParser.matches(input) ->
                plan(input, CalendarAvailabilityActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && DeviceSettingsActionParser.matches(input) ->
                plan(input, DeviceSettingsActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && ContactQueryActionParser.matches(input) ->
                plan(input, ContactQueryActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && ContactDraftActionParser.matches(input) ->
                plan(input, ContactDraftActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && WebSearchActionParser.matches(input) ->
                plan(input, WebSearchActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && RecentScreenshotOcrActionParser.matches(input) ->
                plan(input, RecentScreenshotOcrActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && RecentImageOcrActionParser.matches(input) ->
                plan(input, RecentImageOcrActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && CurrentScreenshotOcrActionParser.matches(input) ->
                plan(input, CurrentScreenshotOcrActionParser.draft().toRequestPair())

            !input.looksLikeSequentialAction() && RecentFilesActionParser.matches(input) ->
                plan(input, RecentFilesActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && DeepLinkActionParser.matches(input) ->
                plan(input, DeepLinkActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && AppNavigationActionParser.matches(input) ->
                plan(input, AppNavigationActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && ForegroundAppActionParser.matches(input) ->
                plan(input, ForegroundAppActionParser.draft().toRequestPair())

            !input.looksLikeSequentialAction() && RecentNotificationsActionParser.matches(input) ->
                plan(input, RecentNotificationsActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && CurrentScreenTextActionParser.matches(input) ->
                plan(input, CurrentScreenTextActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && CancelReminderActionParser.matches(input) ->
                plan(input, CancelReminderActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && PeriodicCheckActionParser.matches(input) ->
                plan(input, PeriodicCheckActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && BackgroundTasksQueryActionParser.matches(input) ->
                plan(input, BackgroundTasksQueryActionParser.draft(input).toRequestPair())

            !input.looksLikeSequentialAction() && ShareTextActionParser.matches(input) -> {
                val draft = ShareTextActionParser.draft(input)
                val request = ToolRequest(
                    toolName = draft.functionName,
                    arguments = draft.parameters,
                    reason = draft.summary,
                )
                plan(input, draft, request)
            }

            !input.looksLikeSequentialAction() && ReminderActionParser.matches(input) -> {
                val draft = ReminderActionParser.draft(input)
                val request = ToolRequest(
                    toolName = draft.functionName,
                    arguments = draft.parameters,
                    reason = draft.summary,
                )
                plan(input, draft, request)
            }

            !input.looksLikeSequentialAction() && input.requestsClipboardContext() -> {
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
        if (
            request.toolName == MobileActionFunctions.READ_CLIPBOARD &&
            !input.looksLikeSequentialAction() &&
            input.requestsClipboardSummaryShare()
        ) {
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
            steps = listOf(
                SkillStep.ToolStep(
                    id = request.toolName,
                    request = request,
                    draft = draft,
                ),
            ),
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

    fun planCurrentScreenTextSummaryShare(
        input: String,
        readRequest: ToolRequest? = null,
        readDraft: ActionDraft? = null,
    ): SkillPlan {
        val manifest = manifestsById.getValue(CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL)
        val readStepId = "read_current_screen_text"
        val summarizeStepId = "summarize_current_screen_text"

        val resolvedReadDraft = readDraft ?: CurrentScreenTextActionParser.draft(input).copy(
            summary = "将读取当前屏幕的可访问文本快照，用于生成可分享摘要；不会读取截图、像素、坐标或完整节点树。",
        )
        val resolvedReadRequest = readRequest ?: ToolRequest(
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            arguments = resolvedReadDraft.parameters,
            reason = resolvedReadDraft.summary,
        )
        val shareDraft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = "分享屏幕摘要",
            summary = "将打开系统分享面板并填入上一步生成的屏幕文本摘要。",
            parameters = emptyMap(),
        )
        val shareRequest = ToolRequest(
            toolName = MobileActionFunctions.SHARE_TEXT,
            reason = shareDraft.summary,
        )

        return SkillPlan(
            request = SkillRequest(
                id = UUID.randomUUID().toString(),
                skillId = CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL,
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
                    title = "摘要当前屏幕文本",
                    instruction = "把用户确认读取的当前屏幕 Accessibility 文本整理成适合分享的简短摘要，语言尽量跟随用户请求。",
                    inputBindings = mapOf("screenText" to "$readStepId.screenText"),
                    outputKey = "shareText",
                    keepsSensitiveInputLocal = true,
                ),
                SkillStep.ToolStep(
                    id = "share_screen_summary",
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
        const val CONTACT_DRAFT_SKILL = "contact_draft_skill"
        const val MAP_SEARCH_SKILL = "map_search_skill"
        const val INFORMATION_LOOKUP_SKILL = "information_lookup_skill"
        const val DEVICE_SETTINGS_SKILL = "device_settings_skill"
        const val REMINDER_SKILL = "reminder_skill"
        const val PERIODIC_CHECK_SKILL = "periodic_check_skill"
        const val BACKGROUND_TASKS_CONTEXT_SKILL = "background_tasks_context_skill"
        const val CLIPBOARD_CONTEXT_SKILL = "clipboard_context_skill"
        const val SHARE_TEXT_SKILL = "share_text_skill"
        const val CLIPBOARD_SUMMARY_SHARE_SKILL = "clipboard_summary_share_skill"
        const val CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL = "current_screen_text_summary_share_skill"
        const val RECENT_SCREENSHOT_OCR_CONTEXT_SKILL = "recent_screenshot_ocr_context_skill"
        const val RECENT_IMAGE_OCR_CONTEXT_SKILL = "recent_image_ocr_context_skill"
        const val CURRENT_SCREENSHOT_OCR_CONTEXT_SKILL = "current_screenshot_ocr_context_skill"
        const val RECENT_FILES_CONTEXT_SKILL = "recent_files_context_skill"
        const val DEEP_LINK_NAVIGATION_SKILL = "deep_link_navigation_skill"
        const val FOREGROUND_APP_CONTEXT_SKILL = "foreground_app_context_skill"
        const val RECENT_NOTIFICATIONS_CONTEXT_SKILL = "recent_notifications_context_skill"
        const val CURRENT_SCREEN_TEXT_CONTEXT_SKILL = "current_screen_text_context_skill"
        const val CONTACT_LOOKUP_SKILL = "contact_lookup_skill"
        const val CALENDAR_AVAILABILITY_SKILL = "calendar_availability_skill"
        const val APP_NAVIGATION_SKILL = "app_navigation_skill"
    }
}

private fun String.requestsCurrentScreenTextSummaryShare(): Boolean {
    val normalized = lowercase()
    if (looksLikeCurrentScreenTextSummaryShareNonAction(normalized)) return false
    val referencesCurrentScreen = currentScreenTextReferences(normalized)
    val asksForSummary = listOf("总结", "摘要", "概括", "归纳").any { it in this } ||
        Regex("""\b(summarize|summary|brief|recap)\b""").containsMatchIn(normalized)
    val asksToShare = "分享" in this ||
        Regex("""\bshare\b""").containsMatchIn(normalized)
    return referencesCurrentScreen && asksForSummary && asksToShare
}

private fun String.currentScreenTextReferences(normalized: String): Boolean =
    Regex("""(?:当前屏幕|当前界面|现在屏幕|这个界面|屏幕)(?:\s|的|上|里|中|内)*(?:可访问|无障碍)?(?:文字|文本)|(?:文字|文本)(?:\s|来自|取自|读取自|在)*(?:当前屏幕|当前界面|现在屏幕|这个界面|屏幕)""")
        .containsMatchIn(this) ||
        Regex("""(?:可访问|无障碍)(?:文字|文本)""").containsMatchIn(this) ||
        Regex("""\b(?:current\s+)?screen\s+text\b|\b(?:current|active|this)\s+(?:screen|page|window|view)\s+(?:visible\s+|accessibility\s+|accessible\s+)?text\b|\btext\s+(?:from|on|in|of)\s+(?:the\s+)?(?:current|active|this)\s+(?:screen|page|window|view)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized) ||
        Regex("""\b(?:current\s+)?(?:visible|accessibility|accessible)\s+text\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)

private fun String.looksLikeCurrentScreenTextSummaryShareNonAction(normalized: String): Boolean =
    (
        Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍)""").containsMatchIn(this) ||
            Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b""").containsMatchIn(normalized)
        ) &&
        currentScreenTextReferences(normalized) &&
        ("分享" in this || Regex("""\bshare\b""").containsMatchIn(normalized)) ||
        Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不).*(?:总结|摘要|概括|归纳).*(?:当前屏幕|当前界面|现在屏幕|屏幕内容|屏幕文字|屏幕文本|这个界面).*(?:分享)""")
        .containsMatchIn(this) ||
        Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍).*(?:总结|摘要|概括|归纳).*(?:当前屏幕|当前界面|现在屏幕|屏幕内容|屏幕文字|屏幕文本|这个界面).*(?:分享)""")
            .containsMatchIn(this) ||
        Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b.*\b(?:summarize|summary|brief|recap)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b.*\bshare\b""")
            .containsMatchIn(normalized) ||
        Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b.*\b(?:summarize|summary|brief|recap)\b.*\b(?:current|active|this)\s+(?:screen|page|window|view)\b.*\bshare\b""")
            .containsMatchIn(normalized)

private fun String.requestsClipboardSummaryShare(): Boolean {
    val normalized = lowercase()
    if (looksLikeClipboardSummaryShareNonAction(normalized)) return false
    val referencesClipboard = "剪贴板" in this || "clipboard" in normalized
    val asksForSummary = listOf("总结", "摘要", "概括", "归纳").any { it in this } ||
        Regex("""\b(summarize|summary|brief|recap)\b""").containsMatchIn(normalized)
    val asksToShare = "分享" in this ||
        Regex("""\bshare\b""").containsMatchIn(normalized)
    return referencesClipboard && asksForSummary && asksToShare
}

private fun String.looksLikeClipboardSummaryShareNonAction(normalized: String): Boolean =
    Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不).*(?:总结|摘要|概括|归纳).*(?:剪贴板).*(?:分享)""")
        .containsMatchIn(this) ||
        Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍).*(?:总结|摘要|概括|归纳).*(?:剪贴板).*(?:分享)""")
            .containsMatchIn(this) ||
        Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b.*\b(?:summarize|summary|brief|recap)\b.*\bclipboard\b.*\bshare\b""")
            .containsMatchIn(normalized) ||
        Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b.*\b(?:summarize|summary|brief|recap)\b.*\bclipboard\b.*\bshare\b""")
            .containsMatchIn(normalized)

private fun String.requestsClipboardContext(): Boolean {
    val normalized = lowercase()
    if (looksLikeClipboardContextNonAction(normalized)) return false
    val referencesClipboard = "剪贴板" in this || "clipboard" in normalized
    val asksToRead = listOf("读取", "读一下", "看看", "查看", "总结", "摘要", "概括").any { it in this } ||
        Regex("""\b(read|summarize|summary|recap)\b""").containsMatchIn(normalized)
    val asksToShare = "分享" in this || Regex("""\bshare\b""").containsMatchIn(normalized)
    return referencesClipboard && asksToRead && !asksToShare
}

private fun String.looksLikeClipboardContextNonAction(normalized: String): Boolean =
    Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:我\s*)?(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不)\s*""")
        .containsMatchIn(this) ||
        Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b""")
            .containsMatchIn(normalized) ||
        listOf(
            "剪贴板权限",
            "剪贴板接口",
            "剪贴板 API",
            "剪贴板api",
            "剪贴板怎么",
            "如何读取剪贴板",
            "怎么读取剪贴板",
            "剪贴板是什么",
        ).any { it in this } ||
        normalized.contains(Regex("""\b(?:clipboard)\s+(?:permissions?|api|implementation|docs?|documentation|schema|tests?)\b""")) ||
        normalized.contains(Regex("""\b(?:how\s+(?:do|can|to)|what\s+is|explain)\b.*\bclipboard\b"""))

private fun String.looksLikeSequentialAction(): Boolean {
    val normalized = lowercase()
    return Regex(""".+(?:然后|接着|随后|之后|再)\s*(?:打开|进入|启动|发|发送|写|建|创建|添加|查询|查|搜索|读取|读|总结|分享|导航|跳转|访问|取消|提醒|设置提醒).+""")
        .containsMatchIn(this) ||
        Regex("""\b(then|after\s+that)\b""").containsMatchIn(normalized)
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
        id = BuiltInSkillRuntime.CONTACT_DRAFT_SKILL,
        version = 1,
        title = "联系人草稿",
        description = "把自然语言请求整理成联系人新建草稿工具调用；不读取通讯录。",
        triggerExamples = listOf("新建联系人 Alice", "create contact Alice"),
        requiredTools = listOf(MobileActionFunctions.CREATE_CONTACT_DRAFT),
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
        description = "把需要外部信息的请求整理成低风险只读 Web 搜索工具调用。",
        triggerExamples = listOf("搜一下 Kotlin", "北京天气怎么样", "look up Kotlin"),
        requiredTools = listOf(MobileActionFunctions.WEB_SEARCH),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.DEVICE_SETTINGS_SKILL,
        version = 1,
        title = "设备设置入口",
        description = "打开受控系统设置入口，由用户在系统页面内继续操作。",
        triggerExamples = listOf("打开 Wi-Fi 设置", "打开使用情况访问权限设置", "打开手电筒设置"),
        requiredTools = listOf(
            MobileActionFunctions.OPEN_WIFI_SETTINGS,
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
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
        triggerExamples = listOf("提醒我 10 分钟后喝水", "取消提醒 task-123", "remind me in 1 hour"),
        requiredTools = listOf(
            MobileActionFunctions.SCHEDULE_REMINDER,
            MobileActionFunctions.CANCEL_REMINDER,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.PERIODIC_CHECK_SKILL,
        version = 1,
        title = "周期检查",
        description = "开启或关闭本地提醒周期检查；不执行后台聊天、屏幕扫描或文件内容扫描。",
        triggerExamples = listOf("开启周期检查", "关闭周期检查", "enable periodic check"),
        requiredTools = listOf(MobileActionFunctions.CONFIGURE_PERIODIC_CHECK),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.BACKGROUND_TASKS_CONTEXT_SKILL,
        version = 1,
        title = "后台任务上下文",
        description = "只读查询本地后台提醒、任务历史与周期检查策略；不创建、取消或配置后台任务。",
        triggerExamples = listOf("查看后台任务", "周期检查状态", "list background tasks"),
        requiredTools = listOf(MobileActionFunctions.QUERY_BACKGROUND_TASKS),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
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
    SkillManifest(
        id = BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL,
        version = 1,
        title = "当前屏幕文本摘要分享",
        description = "读取当前屏幕 Accessibility 文本，先由本地模型生成摘要，再通过系统分享面板外发。",
        triggerExamples = listOf("总结当前屏幕文字并分享", "summarize current screen text and share it"),
        requiredTools = listOf(
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            MobileActionFunctions.SHARE_TEXT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.HighExternalSend,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.RECENT_SCREENSHOT_OCR_CONTEXT_SKILL,
        version = 1,
        title = "最近截图 OCR 上下文",
        description = "在用户确认后读取最近 1 张截图像素并本地提取 OCR 文本；不保存 URI、路径、原图或像素。",
        triggerExamples = listOf("识别最近截图文字", "read text from latest screenshot"),
        requiredTools = listOf(MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.RECENT_IMAGE_OCR_CONTEXT_SKILL,
        version = 1,
        title = "最近图片 OCR 上下文",
        description = "在用户确认后扫描最近最多 3 张图片像素并本地提取第一条 OCR 文本；不保存 URI、路径、原图或像素。",
        triggerExamples = listOf("识别最近图片文字", "read text from recent photos"),
        requiredTools = listOf(MobileActionFunctions.READ_RECENT_IMAGE_OCR),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CURRENT_SCREENSHOT_OCR_CONTEXT_SKILL,
        version = 1,
        title = "当前屏幕截图 OCR 上下文",
        description = "在用户确认并完成 MediaProjection 前台同意后，单次截取当前屏幕并本地提取 OCR 文本；不保存图片、像素、URI、路径或窗口标题。",
        triggerExamples = listOf("识别当前屏幕截图文字", "current screen screenshot text"),
        requiredTools = listOf(MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.RECENT_FILES_CONTEXT_SKILL,
        version = 1,
        title = "最近文件上下文",
        description = "在用户明确要求时读取最近媒体文件的最小元数据摘要，不读取文件内容。",
        triggerExamples = listOf("最近图片", "recent screenshots"),
        requiredTools = listOf(MobileActionFunctions.QUERY_RECENT_FILES),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.DEEP_LINK_NAVIGATION_SKILL,
        version = 1,
        title = "安全链接跳转",
        description = "在用户明确要求时打开 HTTPS 外部链接，拒绝非 HTTPS scheme。",
        triggerExamples = listOf("打开链接 https://example.com", "open https://example.com"),
        requiredTools = listOf(MobileActionFunctions.OPEN_DEEP_LINK),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.APP_NAVIGATION_SKILL,
        version = 1,
        title = "应用导航",
        description = "打开指定应用启动页或 allowlisted 应用详情设置，不接受任意 Intent 参数。",
        triggerExamples = listOf("启动微信", "打开微信应用详情设置"),
        requiredTools = listOf(
            MobileActionFunctions.OPEN_APP_INTENT,
            MobileActionFunctions.OPEN_APP_DEEP_TARGET,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.FOREGROUND_APP_CONTEXT_SKILL,
        version = 1,
        title = "当前前台应用上下文",
        description = "在用户明确要求时读取当前前台应用的应用名与包名。",
        triggerExamples = listOf("当前应用是什么", "what app is currently open"),
        requiredTools = listOf(MobileActionFunctions.QUERY_FOREGROUND_APP),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.RECENT_NOTIFICATIONS_CONTEXT_SKILL,
        version = 1,
        title = "当前应用最近通知上下文",
        description = "在用户明确要求时读取当前应用最近通知的最小摘要。",
        triggerExamples = listOf("最近通知", "current app notifications"),
        requiredTools = listOf(MobileActionFunctions.QUERY_RECENT_NOTIFICATIONS),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_CONTEXT_SKILL,
        version = 1,
        title = "当前屏幕可访问文本上下文",
        description = "在用户确认后读取当前屏幕的 Accessibility 可访问文本快照；不读取截图、OCR、像素或视觉语义内容。",
        triggerExamples = listOf("读取当前屏幕文字", "summarize current screen text"),
        requiredTools = listOf(MobileActionFunctions.READ_CURRENT_SCREEN_TEXT),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CONTACT_LOOKUP_SKILL,
        version = 1,
        title = "联系人查询",
        description = "在用户明确提供查询词时读取联系人最小摘要。",
        triggerExamples = listOf("查联系人 Alice", "find contact Alice"),
        requiredTools = listOf(MobileActionFunctions.QUERY_CONTACTS),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CALENDAR_AVAILABILITY_SKILL,
        version = 1,
        title = "日历忙闲查询",
        description = "在用户提供明确 ISO 时间窗口时只读查询本机日历忙闲。",
        triggerExamples = listOf(
            "查忙闲 2026-06-01T09:00:00Z 到 2026-06-01T10:00:00Z",
            "calendar availability 2026-06-01T09:00:00Z to 2026-06-01T10:00:00Z",
        ),
        requiredTools = listOf(MobileActionFunctions.QUERY_CALENDAR_AVAILABILITY),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.LowReadOnly,
    ),
)

private val builtInCompositeSkillIds = setOf(
    BuiltInSkillRuntime.CLIPBOARD_SUMMARY_SHARE_SKILL,
    BuiltInSkillRuntime.CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL,
)

private val builtInSkillDefinitions = builtInSkillManifests.map { manifest ->
    SkillDefinition(
        manifest = manifest,
        directToolNames = if (manifest.id in builtInCompositeSkillIds) {
            emptyList()
        } else {
            manifest.requiredTools
        },
    )
}

private val builtInSkillCatalog = SkillCatalog(builtInSkillDefinitions)
