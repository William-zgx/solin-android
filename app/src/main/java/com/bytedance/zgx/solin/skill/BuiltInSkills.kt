package com.bytedance.zgx.solin.skill

import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.AppNavigationActionParser
import com.bytedance.zgx.solin.action.AbsoluteReminderActionParser
import com.bytedance.zgx.solin.action.BackgroundTasksQueryActionParser
import com.bytedance.zgx.solin.action.CalendarAvailabilityActionParser
import com.bytedance.zgx.solin.action.CalendarDraftActionParser
import com.bytedance.zgx.solin.action.CancelReminderActionParser
import com.bytedance.zgx.solin.action.CameraActionParser
import com.bytedance.zgx.solin.action.ContactDraftActionParser
import com.bytedance.zgx.solin.action.ContactQueryActionParser
import com.bytedance.zgx.solin.action.CurrentScreenTextActionParser
import com.bytedance.zgx.solin.action.CurrentScreenshotOcrActionParser
import com.bytedance.zgx.solin.action.DeepLinkActionParser
import com.bytedance.zgx.solin.action.DeviceSettingsActionParser
import com.bytedance.zgx.solin.action.EmailDraftActionParser
import com.bytedance.zgx.solin.action.ForegroundAppActionParser
import com.bytedance.zgx.solin.action.MapSearchActionParser
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.action.PeriodicCheckActionParser
import com.bytedance.zgx.solin.action.RecentFilesActionParser
import com.bytedance.zgx.solin.action.RecentImageOcrActionParser
import com.bytedance.zgx.solin.action.RecentNotificationsActionParser
import com.bytedance.zgx.solin.action.RecentScreenshotOcrActionParser
import com.bytedance.zgx.solin.action.ReminderActionParser
import com.bytedance.zgx.solin.action.ShareTextActionParser
import com.bytedance.zgx.solin.action.SystemAlarmActionParser
import com.bytedance.zgx.solin.action.SystemSettingsActionParser
import com.bytedance.zgx.solin.action.SystemTimerActionParser
import com.bytedance.zgx.solin.action.WebSearchActionParser
import com.bytedance.zgx.solin.action.looksLikeSequentialAction
import com.bytedance.zgx.solin.action.startsWithActionNegation
import com.bytedance.zgx.solin.device.AppInteractionProfiles
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolRequest
import java.time.ZoneId
import java.util.UUID

enum class AppSearchPlanningMode {
    StaticSkill,
    ModelDrivenBootstrap,
}

class BuiltInSkillRuntime(
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val appSearchPlanningModeProvider: () -> AppSearchPlanningMode = { AppSearchPlanningMode.StaticSkill },
) : SkillRuntime {
    internal val catalog: SkillCatalog = builtInSkillCatalog
    private val manifestsById = catalog.manifestsById
    private val skillByToolName = catalog.skillIdByToolName

    override fun manifests(): List<SkillManifest> = catalog.manifests()

    override fun plan(input: String): SkillPlan? {
        val isSingleActionRequest = !input.looksLikeSequentialAction()
        return when {
            input.looksLikeCurrentScreenTextSummaryShareNonAction(input.lowercase()) -> null
            isSingleActionRequest && input.requestsClipboardSummaryShare() -> planClipboardSummaryShare(input)
            isSingleActionRequest && input.requestsCurrentScreenTextSummaryShare() ->
                planCurrentScreenTextSummaryShare(input)

            isSingleActionRequest && input.requestsCurrentSettingsUiControl() ->
                planCurrentSettingsUiControl(input)

            isSingleActionRequest && input.requestsCurrentBrowserUiSearch() ->
                planCurrentBrowserUiSearch(input)

            isSingleActionRequest && input.requestsCurrentMapsUiRoute() ->
                planCurrentMapsUiRoute(input)

            isSingleActionRequest && input.requestsCurrentDraftFormUiFill() ->
                planCurrentDraftFormUiFill(input)

            isSingleActionRequest && input.requestsOpenAppThenUiSearch() ->
                planOpenAppThenUiSearch(input)

            isSingleActionRequest && MapSearchActionParser.matches(input) ->
                plan(input, MapSearchActionParser.draft(input).toRequestPair())

            isSingleActionRequest && EmailDraftActionParser.matches(input) ->
                plan(input, EmailDraftActionParser.draft(input).toRequestPair())

            isSingleActionRequest && CalendarDraftActionParser.matches(input) ->
                plan(input, CalendarDraftActionParser.draft(input).toRequestPair())

            isSingleActionRequest && CalendarAvailabilityActionParser.matches(input) ->
                plan(input, CalendarAvailabilityActionParser.draft(input).toRequestPair())

            isSingleActionRequest && DeviceSettingsActionParser.matches(input) ->
                plan(input, DeviceSettingsActionParser.draft(input).toRequestPair())

            isSingleActionRequest && SystemSettingsActionParser.matches(input) ->
                plan(input, SystemSettingsActionParser.draft(input).toRequestPair())

            isSingleActionRequest && ContactQueryActionParser.matches(input) ->
                plan(input, ContactQueryActionParser.draft(input).toRequestPair())

            isSingleActionRequest && ContactDraftActionParser.matches(input) ->
                plan(input, ContactDraftActionParser.draft(input).toRequestPair())

            isSingleActionRequest && WebSearchActionParser.matches(input) ->
                plan(input, WebSearchActionParser.draft(input).toRequestPair())

            isSingleActionRequest && RecentScreenshotOcrActionParser.matches(input) ->
                plan(input, RecentScreenshotOcrActionParser.draft(input).toRequestPair())

            isSingleActionRequest && RecentImageOcrActionParser.matches(input) ->
                plan(input, RecentImageOcrActionParser.draft(input).toRequestPair())

            isSingleActionRequest && CurrentScreenshotOcrActionParser.matches(input) ->
                plan(input, CurrentScreenshotOcrActionParser.draft().toRequestPair())

            isSingleActionRequest && RecentFilesActionParser.matches(input) ->
                plan(input, RecentFilesActionParser.draft(input).toRequestPair())

            isSingleActionRequest && DeepLinkActionParser.matches(input) ->
                plan(input, DeepLinkActionParser.draft(input).toRequestPair())

            isSingleActionRequest && CameraActionParser.matches(input) ->
                plan(input, CameraActionParser.draft().toRequestPair())

            isSingleActionRequest && SystemAlarmActionParser.matches(input) ->
                plan(input, requireNotNull(SystemAlarmActionParser.draft(input)).toRequestPair())

            isSingleActionRequest && SystemTimerActionParser.matches(input) ->
                plan(input, requireNotNull(SystemTimerActionParser.draft(input)).toRequestPair())

            isSingleActionRequest && AppNavigationActionParser.matches(input) ->
                plan(input, AppNavigationActionParser.draft(input).toRequestPair())

            isSingleActionRequest && input.requestsCurrentAppUiSearch() ->
                planCurrentAppUiSearch(input)

            isSingleActionRequest && ForegroundAppActionParser.matches(input) ->
                plan(input, ForegroundAppActionParser.draft().toRequestPair())

            isSingleActionRequest && RecentNotificationsActionParser.matches(input) ->
                plan(input, RecentNotificationsActionParser.draft(input).toRequestPair())

            isSingleActionRequest && input.requestsCurrentScreenObservation() -> {
                val draft = ActionDraft(
                    functionName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                    title = "观察当前屏幕",
                    summary = "将读取当前屏幕的可访问状态快照；不会读取截图像素或发送远程。",
                    parameters = emptyMap(),
                )
                plan(input, draft.toRequestPair())
            }

            isSingleActionRequest && CurrentScreenTextActionParser.matches(input) ->
                plan(input, CurrentScreenTextActionParser.draft(input).toRequestPair())

            isSingleActionRequest && CancelReminderActionParser.matches(input) ->
                plan(input, CancelReminderActionParser.draft(input).toRequestPair())

            isSingleActionRequest && PeriodicCheckActionParser.matches(input) ->
                plan(input, PeriodicCheckActionParser.draft(input).toRequestPair())

            isSingleActionRequest && BackgroundTasksQueryActionParser.matches(input) ->
                plan(input, BackgroundTasksQueryActionParser.draft(input).toRequestPair())

            isSingleActionRequest && ShareTextActionParser.matches(input) -> {
                val draft = ShareTextActionParser.draft(input)
                val request = ToolRequest(
                    toolName = draft.functionName,
                    arguments = draft.parameters,
                    reason = draft.summary,
                )
                plan(input, draft, request)
            }

            isSingleActionRequest && AbsoluteReminderActionParser.matches(input, clockMillis(), zoneId) -> {
                val draft = requireNotNull(AbsoluteReminderActionParser.draft(input, clockMillis(), zoneId))
                val request = ToolRequest(
                    toolName = draft.functionName,
                    arguments = draft.parameters,
                    reason = draft.summary,
                )
                plan(input, draft, request)
            }

            isSingleActionRequest && ReminderActionParser.matches(input) -> {
                val draft = ReminderActionParser.draft(input)
                val request = ToolRequest(
                    toolName = draft.functionName,
                    arguments = draft.parameters,
                    reason = draft.summary,
                )
                plan(input, draft, request)
            }

            isSingleActionRequest && input.requestsClipboardContext() -> {
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

            isSingleActionRequest && input.requestsCurrentPageSimpleInteraction() ->
                planCurrentPageSimpleInteraction(input)

            else -> null
        }
    }

    private fun plan(input: String, pair: DraftRequestPair): SkillPlan? =
        plan(input, pair.draft, pair.request)

    override fun plan(input: String, draft: ActionDraft, request: ToolRequest): SkillPlan? {
        val isSingleActionRequest = !input.looksLikeSequentialAction()
        if (
            request.toolName == MobileActionFunctions.READ_CLIPBOARD &&
            isSingleActionRequest &&
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
        return localSummarySharePlan(
            input = input,
            skillId = CLIPBOARD_SUMMARY_SHARE_SKILL,
            readStepId = "read_clipboard",
            readRequest = resolvedReadRequest,
            readDraft = resolvedReadDraft,
            summarizeStepId = "summarize_clipboard",
            summarizeTitle = "摘要剪贴板内容",
            summarizeInstruction = "把用户确认读取的剪贴板文本整理成适合分享的简短摘要，语言尽量跟随用户请求。",
            summarizeInputBindings = mapOf("clipboardText" to "read_clipboard.text"),
            shareStepId = "share_summary",
            shareTitle = "分享摘要",
            shareSummary = "将打开系统分享面板并填入上一步生成的摘要。",
        )
    }

    fun planCurrentScreenTextSummaryShare(
        input: String,
        readRequest: ToolRequest? = null,
        readDraft: ActionDraft? = null,
    ): SkillPlan {
        val resolvedReadDraft = readDraft ?: CurrentScreenTextActionParser.draft(input).copy(
            summary = "将读取当前屏幕的可访问文本快照，用于生成可分享摘要；不会读取截图、像素、坐标或完整节点树。",
        )
        val resolvedReadRequest = readRequest ?: ToolRequest(
            toolName = MobileActionFunctions.READ_CURRENT_SCREEN_TEXT,
            arguments = resolvedReadDraft.parameters,
            reason = resolvedReadDraft.summary,
        )
        return localSummarySharePlan(
            input = input,
            skillId = CURRENT_SCREEN_TEXT_SUMMARY_SHARE_SKILL,
            readStepId = "read_current_screen_text",
            readRequest = resolvedReadRequest,
            readDraft = resolvedReadDraft,
            summarizeStepId = "summarize_current_screen_text",
            summarizeTitle = "摘要当前屏幕文本",
            summarizeInstruction = "把用户确认读取的当前屏幕 Accessibility 文本整理成适合分享的简短摘要，语言尽量跟随用户请求。",
            summarizeInputBindings = mapOf("screenText" to "read_current_screen_text.screenText"),
            shareStepId = "share_screen_summary",
            shareTitle = "分享屏幕摘要",
            shareSummary = "将打开系统分享面板并填入上一步生成的屏幕文本摘要。",
        )
    }

    private fun localSummarySharePlan(
        input: String,
        skillId: String,
        readStepId: String,
        readRequest: ToolRequest,
        readDraft: ActionDraft,
        summarizeStepId: String,
        summarizeTitle: String,
        summarizeInstruction: String,
        summarizeInputBindings: Map<String, String>,
        shareStepId: String,
        shareTitle: String,
        shareSummary: String,
    ): SkillPlan {
        val manifest = manifestsById.getValue(skillId)
        val shareDraft = ActionDraft(
            functionName = MobileActionFunctions.SHARE_TEXT,
            title = shareTitle,
            summary = shareSummary,
            parameters = emptyMap(),
        )
        val shareRequest = ToolRequest(
            toolName = MobileActionFunctions.SHARE_TEXT,
            reason = shareDraft.summary,
        )

        return SkillPlan(
            request = SkillRequest(
                id = UUID.randomUUID().toString(),
                skillId = skillId,
                arguments = mapOf("input" to input),
                reason = input,
            ),
            manifest = manifest,
            steps = listOf(
                SkillStep.ToolStep(
                    id = readStepId,
                    request = readRequest,
                    draft = readDraft,
                ),
                SkillStep.ModelStep(
                    id = summarizeStepId,
                    dependsOn = listOf(readStepId),
                    title = summarizeTitle,
                    instruction = summarizeInstruction,
                    inputBindings = summarizeInputBindings,
                    outputKey = "shareText",
                    keepsSensitiveInputLocal = true,
                ),
                SkillStep.ToolStep(
                    id = shareStepId,
                    dependsOn = listOf(summarizeStepId),
                    request = shareRequest,
                    draft = shareDraft,
                    argumentBindings = mapOf("text" to "$summarizeStepId.shareText"),
                ),
            ),
        )
    }

    private fun planCurrentSettingsUiControl(input: String): SkillPlan? {
        val target = input.extractCurrentSettingsUiTarget() ?: return null
        return deviceUiTemplatePlan(
            input = input,
            skillId = SETTINGS_UI_CONTROL_SKILL,
            reason = "将在当前设置页先观察屏幕，再点击可见设置项：$target。",
            steps = listOf(
                observeScreenStep("observe_settings"),
                tapStep(
                    id = "tap_settings_target",
                    dependsOn = listOf("observe_settings"),
                    target = target,
                    summary = "点击当前设置页中的可见设置项：$target。",
                ),
                waitStep(
                    id = "verify_settings_target",
                    dependsOn = listOf("tap_settings_target"),
                    summary = "等待设置页面变化并重新观察。",
                ),
            ),
        )
    }

    private fun planCurrentBrowserUiSearch(input: String): SkillPlan? {
        val query = input.extractCurrentBrowserUiQuery() ?: return null
        return deviceUiTemplatePlan(
            input = input,
            skillId = BROWSER_UI_SEARCH_SKILL,
            reason = "将在当前浏览器页观察、聚焦搜索框、输入关键词并提交搜索：$query。",
            steps = listOf(
                observeScreenStep("observe_browser"),
                tapStep(
                    id = "focus_browser_search",
                    dependsOn = listOf("observe_browser"),
                    target = "地址栏",
                    summary = "聚焦当前浏览器页面的地址栏或搜索栏。",
                ),
                waitStep(
                    id = "wait_browser_search_field",
                    dependsOn = listOf("focus_browser_search"),
                    summary = "等待浏览器搜索输入框获得焦点或搜索页稳定。",
                ),
                typeTextStep(
                    id = "type_browser_query",
                    dependsOn = listOf("wait_browser_search_field"),
                    text = query,
                    target = "地址栏",
                    summary = "在当前浏览器输入搜索关键词：$query。",
                ),
                submitSearchStep(
                    id = "submit_browser_search",
                    dependsOn = listOf("type_browser_query"),
                    summary = "提交浏览器搜索：$query。",
                ),
                waitStep(
                    id = "verify_browser_results",
                    dependsOn = listOf("submit_browser_search"),
                    summary = "等待浏览器搜索结果更新并重新观察。",
                    verifySearchQuery = query,
                ),
            ),
        )
    }

    private fun planCurrentMapsUiRoute(input: String): SkillPlan? {
        val query = input.extractCurrentMapsUiQuery() ?: return null
        return deviceUiTemplatePlan(
            input = input,
            skillId = MAPS_UI_ROUTE_SKILL,
            reason = "将在当前地图页观察、聚焦搜索框、输入地点或路线目标并提交搜索：$query。",
            steps = listOf(
                observeScreenStep("observe_maps"),
                tapStep(
                    id = "focus_maps_search",
                    dependsOn = listOf("observe_maps"),
                    target = "搜索入口",
                    summary = "聚焦当前地图页面的搜索输入框。",
                ),
                waitStep(
                    id = "wait_maps_search_field",
                    dependsOn = listOf("focus_maps_search"),
                    summary = "等待地图搜索输入框获得焦点或搜索页稳定。",
                ),
                typeTextStep(
                    id = "type_maps_query",
                    dependsOn = listOf("wait_maps_search_field"),
                    text = query,
                    target = "搜索输入框",
                    summary = "在当前地图页面输入路线或地点关键词：$query。",
                ),
                submitSearchStep(
                    id = "submit_maps_search",
                    dependsOn = listOf("type_maps_query"),
                    summary = "提交地图搜索：$query。",
                ),
                waitStep(
                    id = "verify_maps_results",
                    dependsOn = listOf("submit_maps_search"),
                    summary = "等待地图搜索结果或路线入口更新并重新观察。",
                    verifySearchQuery = query,
                ),
            ),
        )
    }

    private fun planCurrentDraftFormUiFill(input: String): SkillPlan? {
        val draft = input.extractCurrentDraftFormUiFill() ?: return null
        return deviceUiTemplatePlan(
            input = input,
            skillId = DRAFT_FORM_UI_SKILL,
            reason = "将在当前${draft.formTitle}草稿页观察并填写${draft.fieldTitle}；不会发送、保存或发布。",
            steps = listOf(
                observeScreenStep("observe_draft_form"),
                typeTextStep(
                    id = "type_draft_field",
                    dependsOn = listOf("observe_draft_form"),
                    target = draft.fieldTarget,
                    text = draft.text,
                    summary = "在当前${draft.formTitle}草稿页填写${draft.fieldTitle}。",
                ),
                waitStep(
                    id = "verify_draft_field",
                    dependsOn = listOf("type_draft_field"),
                    summary = "等待草稿字段更新并重新观察。",
                ),
            ),
        )
    }

    private fun planCurrentAppUiSearch(input: String): SkillPlan? {
        val query = input.extractCurrentAppUiSearchQuery() ?: return null
        if (appSearchPlanningModeProvider() == AppSearchPlanningMode.ModelDrivenBootstrap) {
            return deviceUiTemplatePlan(
                input = input,
                skillId = MODEL_DRIVEN_CURRENT_APP_UI_SEARCH_SKILL,
                reason = "将在当前应用内先观察屏幕；后续由本地动作规划模型逐步规划低风险搜索动作：$query。",
                steps = listOf(observeScreenStep("observe_current_app_search")),
            )
        }
        return deviceUiTemplatePlan(
            input = input,
            skillId = CURRENT_APP_UI_SEARCH_SKILL,
            reason = "将在当前应用内观察、聚焦搜索入口、输入关键词、提交搜索并验证结果：$query。",
            steps = currentAppSearchSteps(
                observeId = "observe_current_app_search",
                query = query,
            ),
        )
    }

    private fun planOpenAppThenUiSearch(input: String): SkillPlan? {
        val request = input.extractOpenAppUiSearchRequest() ?: return null
        val expectedPackageName = AppInteractionProfiles.forAppName(request.appName)
            ?.packageNames
            ?.firstOrNull()
        if (appSearchPlanningModeProvider() == AppSearchPlanningMode.ModelDrivenBootstrap) {
            return deviceUiTemplatePlan(
                input = input,
                skillId = MODEL_DRIVEN_OPEN_APP_UI_SEARCH_SKILL,
                reason = "将打开${request.appName}并观察前台界面；后续由本地动作规划模型逐步规划低风险搜索动作：${request.query}。",
                steps = listOf(
                    openAppByNameStep(
                        id = "open_target_app",
                        appName = request.appName,
                        summary = "打开目标应用：${request.appName}。",
                    ),
                    waitStep(
                        id = "wait_target_app",
                        dependsOn = listOf("open_target_app"),
                        summary = "等待${request.appName}前台界面稳定。",
                        expectedPackageName = expectedPackageName,
                        timeoutMillis = "4000",
                    ),
                    observeScreenStep("observe_target_app").copy(dependsOn = listOf("wait_target_app")),
                ),
            )
        }
        return deviceUiTemplatePlan(
            input = input,
            skillId = OPEN_APP_UI_SEARCH_SKILL,
            reason = "将打开${request.appName}，再在应用内搜索并验证结果：${request.query}。",
            steps = listOf(
                openAppByNameStep(
                    id = "open_target_app",
                    appName = request.appName,
                    summary = "打开目标应用：${request.appName}。",
                ),
                waitStep(
                    id = "wait_target_app",
                    dependsOn = listOf("open_target_app"),
                    summary = "等待${request.appName}前台界面稳定。",
                    expectedPackageName = expectedPackageName,
                ),
            ) + currentAppSearchSteps(
                observeId = "observe_target_app",
                query = request.query,
                firstDependsOn = listOf("wait_target_app"),
                expectedPackageName = expectedPackageName,
                expectedAppName = request.appName,
            ),
        )
    }

    private fun planCurrentPageSimpleInteraction(input: String): SkillPlan? {
        val command = input.extractCurrentPageUiCommand() ?: return null
        return when (command) {
            is CurrentPageUiCommand.Tap -> deviceUiTemplatePlan(
                input = input,
                skillId = CURRENT_PAGE_SIMPLE_INTERACTION_SKILL,
                reason = "将在当前页面观察并点击可见元素：${command.target}。",
                steps = listOf(
                    observeScreenStep("observe_current_page_tap"),
                    tapStep(
                        id = "tap_current_page_target",
                        dependsOn = listOf("observe_current_page_tap"),
                        target = command.target,
                        summary = "点击当前页面中的可见元素：${command.target}。",
                    ),
                    waitStep(
                        id = "verify_current_page_tap",
                        dependsOn = listOf("tap_current_page_target"),
                        summary = "等待页面变化并重新观察。",
                    ),
                ),
            )

            is CurrentPageUiCommand.Type -> deviceUiTemplatePlan(
                input = input,
                skillId = CURRENT_PAGE_SIMPLE_INTERACTION_SKILL,
                reason = "将在当前页面观察并输入文本。",
                steps = listOf(
                    observeScreenStep("observe_current_page_input"),
                    typeTextStep(
                        id = "type_current_page_text",
                        dependsOn = listOf("observe_current_page_input"),
                        text = command.text,
                        target = command.target,
                        summary = "在当前页面输入文本。",
                    ),
                    waitStep(
                        id = "verify_current_page_input",
                        dependsOn = listOf("type_current_page_text"),
                        summary = "等待输入结果并重新观察。",
                    ),
                ),
            )

            is CurrentPageUiCommand.Scroll -> deviceUiTemplatePlan(
                input = input,
                skillId = CURRENT_PAGE_SIMPLE_INTERACTION_SKILL,
                reason = "将在当前页面观察并滚动。",
                steps = listOf(
                    observeScreenStep("observe_current_page_scroll"),
                    scrollStep(
                        id = "scroll_current_page",
                        dependsOn = listOf("observe_current_page_scroll"),
                        direction = command.direction,
                        summary = "滚动当前页面。",
                    ),
                    waitStep(
                        id = "verify_current_page_scroll",
                        dependsOn = listOf("scroll_current_page"),
                        summary = "等待滚动结果并重新观察。",
                    ),
                ),
            )

            CurrentPageUiCommand.Back -> deviceUiTemplatePlan(
                input = input,
                skillId = CURRENT_PAGE_SIMPLE_INTERACTION_SKILL,
                reason = "将在当前页面执行系统返回。",
                steps = listOf(
                    pressBackStep(
                        id = "press_current_page_back",
                        summary = "执行系统返回。",
                    ),
                    waitStep(
                        id = "verify_current_page_back",
                        dependsOn = listOf("press_current_page_back"),
                        summary = "等待返回后的页面稳定。",
                    ),
                ),
            )

            CurrentPageUiCommand.Wait -> deviceUiTemplatePlan(
                input = input,
                skillId = CURRENT_PAGE_SIMPLE_INTERACTION_SKILL,
                reason = "将等待当前页面稳定并重新观察。",
                steps = listOf(
                    waitStep(
                        id = "wait_current_page",
                        dependsOn = emptyList(),
                        summary = "等待当前页面稳定并重新观察。",
                    ),
                ),
            )
        }
    }

    private fun currentAppSearchSteps(
        observeId: String,
        query: String,
        firstDependsOn: List<String> = emptyList(),
        expectedPackageName: String? = null,
        expectedAppName: String? = null,
    ): List<SkillStep> =
        listOf(
            observeScreenStep(observeId).copy(dependsOn = firstDependsOn),
            tapStep(
                id = "focus_app_search",
                dependsOn = listOf(observeId),
                target = "搜索入口",
                summary = "聚焦当前应用中的搜索入口。",
                expectedPackageName = expectedPackageName,
            ),
            waitStep(
                id = "wait_app_search_field",
                dependsOn = listOf("focus_app_search"),
                summary = "等待搜索输入框获得焦点或搜索页稳定。",
                expectedPackageName = expectedPackageName,
            ),
            typeTextStep(
                id = "type_app_search_query",
                dependsOn = listOf("wait_app_search_field"),
                text = query,
                target = "搜索输入框",
                summary = "输入搜索关键词：$query。",
                expectedPackageName = expectedPackageName,
            ),
            submitSearchStep(
                id = "submit_app_search",
                dependsOn = listOf("type_app_search_query"),
                summary = "提交当前应用内搜索：$query。",
                expectedPackageName = expectedPackageName,
            ),
            waitStep(
                id = "verify_app_search_results",
                dependsOn = listOf("submit_app_search"),
                summary = "等待搜索结果页更新并重新观察。",
                verifySearchQuery = query,
                expectedPackageName = expectedPackageName,
                expectedAppName = expectedAppName,
            ),
        )

    private fun deviceUiTemplatePlan(
        input: String,
        skillId: String,
        reason: String,
        steps: List<SkillStep>,
    ): SkillPlan {
        val manifest = manifestsById.getValue(skillId)
        return SkillPlan(
            request = SkillRequest(
                id = UUID.randomUUID().toString(),
                skillId = skillId,
                arguments = mapOf("input" to input),
                reason = reason,
            ),
            manifest = manifest,
            steps = steps,
        )
    }

    private fun openAppByNameStep(
        id: String,
        appName: String,
        summary: String,
    ): SkillStep.ToolStep {
        val parameters = mapOf("appName" to appName)
        val draft = ActionDraft(
            functionName = MobileActionFunctions.OPEN_APP_BY_NAME,
            title = "打开应用",
            summary = summary,
            parameters = parameters,
        )
        return SkillStep.ToolStep(
            id = id,
            request = ToolRequest(
                toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
                arguments = parameters,
                reason = summary,
            ),
            draft = draft,
        )
    }

    private fun observeScreenStep(id: String): SkillStep.ToolStep {
        val parameters = mapOf(
            "maxTextChars" to "2000",
            "maxNodes" to "80",
        )
        val draft = ActionDraft(
            functionName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            title = "观察当前屏幕",
            summary = "先读取当前屏幕的本地 Accessibility 状态快照，作为 UI 动作前置观察。",
            parameters = parameters,
        )
        return SkillStep.ToolStep(
            id = id,
            request = ToolRequest(
                toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                arguments = parameters,
                reason = draft.summary,
            ),
            draft = draft,
        )
    }

    private fun tapStep(
        id: String,
        dependsOn: List<String>,
        target: String,
        summary: String,
        expectedPackageName: String? = null,
    ): SkillStep.ToolStep {
        val parameters = buildMap {
            put("target", target)
            put("timeoutMillis", "1500")
            expectedPackageName?.takeIf { it.isNotBlank() }?.let { put("expectedPackageName", it) }
        }
        val draft = ActionDraft(
            functionName = MobileActionFunctions.UI_TAP,
            title = "点击屏幕元素",
            summary = summary,
            parameters = parameters,
        )
        return SkillStep.ToolStep(
            id = id,
            dependsOn = dependsOn,
            request = ToolRequest(
                toolName = MobileActionFunctions.UI_TAP,
                arguments = parameters,
                reason = summary,
            ),
            draft = draft,
        )
    }

    private fun typeTextStep(
        id: String,
        dependsOn: List<String>,
        text: String,
        summary: String,
        target: String? = null,
        expectedPackageName: String? = null,
    ): SkillStep.ToolStep {
        val parameters = buildMap {
            put("text", text)
            target?.takeIf { it.isNotBlank() }?.let { put("target", it) }
            put("timeoutMillis", "1500")
            expectedPackageName?.takeIf { it.isNotBlank() }?.let { put("expectedPackageName", it) }
        }
        val draft = ActionDraft(
            functionName = MobileActionFunctions.UI_TYPE_TEXT,
            title = "输入文本",
            summary = summary,
            parameters = parameters,
        )
        return SkillStep.ToolStep(
            id = id,
            dependsOn = dependsOn,
            request = ToolRequest(
                toolName = MobileActionFunctions.UI_TYPE_TEXT,
                arguments = parameters,
                reason = summary,
            ),
            draft = draft,
        )
    }

    private fun submitSearchStep(
        id: String,
        dependsOn: List<String>,
        summary: String,
        expectedPackageName: String? = null,
    ): SkillStep.ToolStep {
        val parameters = buildMap {
            put("timeoutMillis", "1500")
            expectedPackageName?.takeIf { it.isNotBlank() }?.let { put("expectedPackageName", it) }
        }
        val draft = ActionDraft(
            functionName = MobileActionFunctions.UI_SUBMIT_SEARCH,
            title = "提交搜索",
            summary = summary,
            parameters = parameters,
        )
        return SkillStep.ToolStep(
            id = id,
            dependsOn = dependsOn,
            request = ToolRequest(
                toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
                arguments = parameters,
                reason = summary,
            ),
            draft = draft,
        )
    }

    private fun waitStep(
        id: String,
        dependsOn: List<String>,
        summary: String,
        verifySearchQuery: String? = null,
        expectedPackageName: String? = null,
        expectedAppName: String? = null,
        timeoutMillis: String = "800",
    ): SkillStep.ToolStep {
        val parameters = buildMap {
            put("timeoutMillis", timeoutMillis)
            verifySearchQuery?.takeIf { it.isNotBlank() }?.let { put("verifySearchQuery", it) }
            expectedPackageName?.takeIf { it.isNotBlank() }?.let { put("expectedPackageName", it) }
            expectedAppName?.takeIf { it.isNotBlank() }?.let { put("expectedAppName", it) }
        }
        val draft = ActionDraft(
            functionName = MobileActionFunctions.UI_WAIT,
            title = "等待并验证",
            summary = summary,
            parameters = parameters,
        )
        return SkillStep.ToolStep(
            id = id,
            dependsOn = dependsOn,
            request = ToolRequest(
                toolName = MobileActionFunctions.UI_WAIT,
                arguments = parameters,
                reason = summary,
            ),
            draft = draft,
        )
    }

    private fun scrollStep(
        id: String,
        dependsOn: List<String>,
        direction: String,
        summary: String,
        expectedPackageName: String? = null,
    ): SkillStep.ToolStep {
        val parameters = buildMap {
            put("direction", direction)
            put("timeoutMillis", "1500")
            expectedPackageName?.takeIf { it.isNotBlank() }?.let { put("expectedPackageName", it) }
        }
        val draft = ActionDraft(
            functionName = MobileActionFunctions.UI_SCROLL,
            title = "滚动页面",
            summary = summary,
            parameters = parameters,
        )
        return SkillStep.ToolStep(
            id = id,
            dependsOn = dependsOn,
            request = ToolRequest(
                toolName = MobileActionFunctions.UI_SCROLL,
                arguments = parameters,
                reason = summary,
            ),
            draft = draft,
        )
    }

    private fun pressBackStep(
        id: String,
        summary: String,
    ): SkillStep.ToolStep {
        val parameters = mapOf("timeoutMillis" to "1000")
        val draft = ActionDraft(
            functionName = MobileActionFunctions.UI_PRESS_BACK,
            title = "返回",
            summary = summary,
            parameters = parameters,
        )
        return SkillStep.ToolStep(
            id = id,
            request = ToolRequest(
                toolName = MobileActionFunctions.UI_PRESS_BACK,
                arguments = parameters,
                reason = summary,
            ),
            draft = draft,
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
        const val TIME_ACTION_SKILL = "time_action_skill"
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
        const val DEVICE_CONTROL_SKILL = "device_control_skill"
        const val SETTINGS_UI_CONTROL_SKILL = "settings_ui_control_skill"
        const val BROWSER_UI_SEARCH_SKILL = "browser_ui_search_skill"
        const val MAPS_UI_ROUTE_SKILL = "maps_ui_route_skill"
        const val DRAFT_FORM_UI_SKILL = "draft_form_ui_skill"
        const val CURRENT_APP_UI_SEARCH_SKILL = "current_app_ui_search_skill"
        const val OPEN_APP_UI_SEARCH_SKILL = "open_app_ui_search_skill"
        const val MODEL_DRIVEN_CURRENT_APP_UI_SEARCH_SKILL = "model_driven_current_app_ui_search_skill"
        const val MODEL_DRIVEN_OPEN_APP_UI_SEARCH_SKILL = "model_driven_open_app_ui_search_skill"
        const val CURRENT_PAGE_SIMPLE_INTERACTION_SKILL = "current_page_simple_interaction_skill"
        const val CONTACT_LOOKUP_SKILL = "contact_lookup_skill"
        const val CALENDAR_AVAILABILITY_SKILL = "calendar_availability_skill"
        const val APP_NAVIGATION_SKILL = "app_navigation_skill"
    }
}

private data class DraftFormUiFill(
    val formTitle: String,
    val fieldTitle: String,
    val fieldTarget: String,
    val text: String,
)

private data class OpenAppUiSearchRequest(
    val appName: String,
    val query: String,
)

private sealed class CurrentPageUiCommand {
    data class Tap(val target: String) : CurrentPageUiCommand()
    data class Type(val text: String, val target: String?) : CurrentPageUiCommand()
    data class Scroll(val direction: String) : CurrentPageUiCommand()
    data object Back : CurrentPageUiCommand()
    data object Wait : CurrentPageUiCommand()
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

private fun String.requestsCurrentScreenObservation(): Boolean {
    val normalized = lowercase()
    if (
        Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍)""").containsMatchIn(this) ||
        Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b""").containsMatchIn(normalized) ||
        Regex("""^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:不想|不需要|不用|不必|不要|别|请勿|请不要|先别|暂时别|不)\s*""")
            .containsMatchIn(this) ||
        Regex("""^\s*(?:(?:please\s+)?(?:do\s+not|don't|dont|never)|i\s+(?:do\s+not|don't|dont)\s+want\s+to)\b""")
            .containsMatchIn(normalized)
    ) {
        return false
    }
    val referencesScreen = listOf("当前屏幕", "当前界面", "现在屏幕", "这个界面").any { it in this } ||
        Regex("""\b(?:current|active|this)\s+(?:screen|page|window|view)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val asksObserve = listOf("观察", "查看", "看看", "读取状态", "屏幕状态").any { it in this } ||
        Regex("""\b(?:observe|inspect|read)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    return referencesScreen && asksObserve
}

private fun String.requestsCurrentSettingsUiControl(): Boolean =
    extractCurrentSettingsUiTarget() != null

private fun String.extractCurrentSettingsUiTarget(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentSettings = listOf("当前设置页", "当前设置页面", "当前系统设置", "设置页里", "设置页面里")
        .any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:settings?|preferences?)\s+(?:screen|page)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    if (!referencesCurrentSettings) return null
    val target = replace(Regex("""^.*?(?:点击|点开|打开|进入|选择)\s*"""), "")
        .replace(Regex("""(?i)^.*?\b(?:tap|open|select|choose)\s+"""), "")
        .replace(Regex("""(?:这个)?(?:设置项|选项|入口|按钮)?\s*$"""), "")
        .replace(Regex("""(?i)\s+(?:option|entry|button)$"""), "")
        .trim()
    return target.takeIf { it.length in 1..80 }
}

private fun String.requestsCurrentBrowserUiSearch(): Boolean =
    extractCurrentBrowserUiQuery() != null

private fun String.extractCurrentBrowserUiQuery(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentBrowser = listOf("当前浏览器", "当前网页", "浏览器页面", "网页页面")
        .any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:browser|web\s+page|webpage)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val asksSearch = listOf("搜索", "查询", "搜", "查", "输入").any { it in this } ||
        Regex("""\b(?:search|look\s+up|type)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    if (!referencesCurrentBrowser || !asksSearch) return null
    val query = replace(Regex("""^.*?(?:搜索|查询|搜|查|输入)\s*[:：]?"""), "")
        .replace(Regex("""(?i)^.*?\b(?:search|look\s+up|type)\s+(?:for\s+)?"""), "")
        .trim()
    return query.takeIf { it.length in 1..120 }
}

private fun String.requestsCurrentMapsUiRoute(): Boolean =
    extractCurrentMapsUiQuery() != null

private fun String.extractCurrentMapsUiQuery(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentMaps = listOf("当前地图", "地图页面", "地图里", "地图内").any { it in this } ||
        Regex("""\b(?:current|this)\s+maps?\b|\bmaps?\s+(?:screen|page)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val asksRouteOrSearch = listOf("路线", "导航", "搜索", "查", "输入").any { it in this } ||
        Regex("""\b(?:route|directions?|navigate|search|type)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    if (!referencesCurrentMaps || !asksRouteOrSearch) return null
    val query = replace(Regex("""^.*?(?:查(?:去|到)?|搜索|导航(?:到|去)?|输入)\s*[:：]?"""), "")
        .replace(Regex("""(?i)^.*?\b(?:route|directions?\s+to|navigate\s+to|search|type)\s+"""), "")
        .replace(Regex("""(?:的)?路线\s*$"""), "")
        .trim()
    return query.takeIf { it.length in 1..120 }
}

private fun String.requestsCurrentAppUiSearch(): Boolean =
    extractCurrentAppUiSearchQuery() != null

private fun String.extractCurrentAppUiSearchQuery(): String? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentApp = listOf(
        "当前应用",
        "当前 app",
        "当前app",
        "当前页面",
        "当前页",
        "当前界面",
        "这个应用",
        "这个页面",
        "这个界面",
        "应用里",
        "app 里",
        "app里",
    ).any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:app|application|page|screen)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val asksSearch = containsSearchVerb()
    if (!referencesCurrentApp || !asksSearch) return null
    return extractQueryAfterSearchVerb()
}

private fun String.requestsOpenAppThenUiSearch(): Boolean =
    extractOpenAppUiSearchRequest() != null

private fun String.extractOpenAppUiSearchRequest(): OpenAppUiSearchRequest? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val trimmed = trim()
    val chineseMatch = Regex(
        """^\s*(?:请|帮我|麻烦|麻烦你)?\s*(?:打开|启动|进入)\s*(.+?)\s*(?:后|之后|然后|接着|并|，|,|\s)*\s*(?:搜索|搜一下|搜|查询|查找|查)\s*[:：]?\s*(.+?)\s*[。.!！]?\s*$""",
    ).find(trimmed)
    if (chineseMatch != null) {
        val appName = chineseMatch.groupValues[1].cleanAppSearchSlot()
        val query = chineseMatch.groupValues[2].cleanSearchQuerySlot()
        if (appName != null && query != null) return OpenAppUiSearchRequest(appName, query)
    }

    val englishMatch = Regex(
        """^\s*(?:please\s+)?(?:open|launch|start)\s+(?:the\s+)?(.+?)\s+(?:(?:and\s+then|then|after\s+that|and)\s+)?(?:search|look\s+up|find)\s+(?:for\s+)?(.+?)\s*$""",
        RegexOption.IGNORE_CASE,
    ).find(trimmed)
    if (englishMatch != null) {
        val appName = englishMatch.groupValues[1].cleanAppSearchSlot()
        val query = englishMatch.groupValues[2].cleanSearchQuerySlot()
        if (appName != null && query != null) return OpenAppUiSearchRequest(appName, query)
    }
    return null
}

private fun String.requestsCurrentPageSimpleInteraction(): Boolean =
    extractCurrentPageUiCommand() != null

private fun String.extractCurrentPageUiCommand(): CurrentPageUiCommand? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val referencesCurrentPage = listOf(
        "当前页面",
        "当前页",
        "当前界面",
        "当前屏幕",
        "这个页面",
        "这个界面",
        "这个屏幕",
        "当前应用",
        "当前 app",
        "当前app",
    ).any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:page|screen|app|application)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val asksBack = Regex("""\b(?:back|go\s+back)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
        listOf("返回", "后退").any { it in this }
    if (!referencesCurrentPage && !asksBack) return null

    if (asksBack) {
        return CurrentPageUiCommand.Back
    }
    if (Regex("""\bwait\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
        listOf("等一下", "等待", "稍等").any { it in this }
    ) {
        return CurrentPageUiCommand.Wait
    }
    if (Regex("""\bscroll\s+(down|up|left|right|forward|backward)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized) ||
        listOf("滚动", "滑动", "往下翻", "往上翻", "下滑", "上滑").any { it in this }
    ) {
        return CurrentPageUiCommand.Scroll(direction = scrollDirectionForCurrentPageCommand())
    }

    extractCurrentPageTypeCommand()?.let { return it }
    if (hasCurrentPageTapIntent()) {
        extractCurrentPageTapTarget()?.let { target -> return CurrentPageUiCommand.Tap(target) }
    }
    return null
}

private fun String.hasCurrentPageTapIntent(): Boolean {
    val normalized = lowercase()
    return listOf("点击", "点开", "点一下", "选择", "打开", "进入", "筛选").any { it in this } ||
        Regex("""\b(?:tap|click|select|choose|open|filter)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
}

private fun String.extractCurrentPageTapTarget(): String? {
    val target = replace(Regex("""^.*?(?:点击|点开|点一下|选择|打开|进入|筛选)\s*"""), "")
        .replace(Regex("""(?i)^.*?\b(?:tap|click|select|choose|open|filter)\s+"""), "")
        .stripCurrentPageReference()
        .replace(Regex("""^\s*(?:的|上|里|内|中)\s*"""), "")
        .replace(Regex("""(?:这个)?(?:按钮|入口|选项|筛选项|标签)?\s*$"""), "")
        .replace(Regex("""(?i)\s+(?:button|entry|option|filter|tab)$"""), "")
        .cleanUiTargetSlot()
    return target?.takeIf { it.length <= 80 && it.hasActionableUiTarget() }
}

private fun String.hasActionableUiTarget(): Boolean =
    this !in setOf("当前页面", "当前页", "当前界面", "当前屏幕", "当前应用", "这个页面", "这个界面", "这个屏幕") &&
        !contains(Regex("""(?:是什么|什么意思|怎么|如何|说明|解释|总结|摘要|识别|读取|文字|文本|截图|通知|前台应用|当前应用是什么)"""))

private fun String.extractCurrentPageTypeCommand(): CurrentPageUiCommand.Type? {
    val chineseMatch = Regex("""^.*?(?:输入|填写)\s*[:：]?\s*(.+?)\s*$""").find(this)
    if (chineseMatch != null) {
        val text = chineseMatch.groupValues[1].cleanSearchQuerySlot() ?: return null
        return CurrentPageUiCommand.Type(text = text, target = null)
    }
    val englishMatch = Regex("""^.*?\b(?:type|enter|fill)\s+(.+?)\s*$""", RegexOption.IGNORE_CASE).find(this)
    if (englishMatch != null) {
        val text = englishMatch.groupValues[1].cleanSearchQuerySlot() ?: return null
        return CurrentPageUiCommand.Type(text = text, target = null)
    }
    return null
}

private fun String.stripCurrentPageReference(): String =
    replace(
        Regex("""^\s*(?:当前页面|当前页|当前界面|当前屏幕|这个页面|这个界面|这个屏幕|当前应用|当前\s*app)(?:上|里|内|中|的|上的|里的|中的)?\s*"""),
        "",
    )

private fun String.scrollDirectionForCurrentPageCommand(): String {
    val normalized = lowercase()
    return when {
        "往上" in this || "上滑" in this || "向上" in this ||
            Regex("""\b(up|backward)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "up"
        "向左" in this || Regex("""\bleft\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "left"
        "向右" in this || Regex("""\bright\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "right"
        Regex("""\bforward\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized) -> "forward"
        else -> "down"
    }
}

private fun String.containsSearchVerb(): Boolean {
    val normalized = lowercase()
    return listOf("搜索", "搜一下", "搜", "查询", "查找", "查").any { it in this } ||
        Regex("""\b(?:search|look\s+up|find)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
}

private fun String.extractQueryAfterSearchVerb(): String? {
    val query = replace(Regex("""^.*?(?:搜索|搜一下|搜|查询|查找|查)\s*[:：]?"""), "")
        .replace(Regex("""(?i)^.*?\b(?:search|look\s+up|find)\s+(?:for\s+)?"""), "")
        .cleanSearchQuerySlot()
    return query?.takeIf { it.length <= 120 }
}

private fun String.cleanAppSearchSlot(): String? =
    trim()
        .removePrefix("应用")
        .removePrefix("app")
        .trim()
        .trimEnd('。', '.', '！', '!', '，', ',')
        .takeIf { candidate ->
            candidate.length in 1..80 &&
                candidate !in setOf("应用", "app", "应用程序", "软件") &&
                !candidate.contains(Regex("""[/:\\]""")) &&
                !candidate.looksLikeSystemOrWebTarget()
        }

private fun String.cleanSearchQuerySlot(): String? =
    trim()
        .trim('"', '\'', '“', '”', '‘', '’')
        .trimEnd('。', '.', '！', '!', '，', ',')
        .trim()
        .takeIf { candidate ->
            candidate.isNotBlank() &&
                candidate.length <= 120 &&
                !candidate.contains(Regex("""^\s*(?:搜索|查询|查找|筛选)\s*$"""))
        }

private fun String.cleanUiTargetSlot(): String? =
    trim()
        .trim('"', '\'', '“', '”', '‘', '’')
        .trimEnd('。', '.', '！', '!', '，', ',')
        .trim()
        .takeIf { candidate ->
            candidate.isNotBlank() &&
                candidate.length <= 120
        }

private fun String.looksLikeSystemOrWebTarget(): Boolean {
    val normalized = lowercase()
    return listOf("设置", "权限", "网页", "网站", "链接", "网址").any { it in this } ||
        Regex("""\b(?:wi[-\s]?fi|wifi|wlan|settings?|preferences?|permission|web\s*page|website|link|url)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
}

private fun String.requestsCurrentDraftFormUiFill(): Boolean =
    extractCurrentDraftFormUiFill() != null

private fun String.extractCurrentDraftFormUiFill(): DraftFormUiFill? {
    if (startsWithActionNegation() || looksLikeUiControlDiscussion()) return null
    val normalized = lowercase()
    val isEmail = listOf("当前邮件表单", "当前邮件页面", "当前邮件草稿", "邮件草稿页").any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:email|mail)\s+(?:draft|form|compose)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    val isCalendar = listOf("当前日历表单", "当前日程页面", "当前日程草稿", "日历草稿页").any { it in this } ||
        Regex("""\b(?:current|this)\s+(?:calendar|event)\s+(?:draft|form)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    if (!isEmail && !isCalendar) return null
    val asksFill = listOf("填写", "填入", "输入", "写入").any { it in this } ||
        Regex("""\b(?:fill|type|enter)\b""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    if (!asksFill) return null
    val text = replace(Regex("""^.*?(?:填写|填入|输入|写入)\s*[:：]?"""), "")
        .replace(Regex("""(?i)^.*?\b(?:fill|type|enter)\s+"""), "")
        .trim()
    if (text.isBlank() || text.length > 500) return null
    return if (isEmail) {
        DraftFormUiFill(
            formTitle = "邮件",
            fieldTitle = "正文",
            fieldTarget = "正文",
            text = text,
        )
    } else {
        DraftFormUiFill(
            formTitle = "日历",
            fieldTitle = "标题",
            fieldTarget = "标题",
            text = text,
        )
    }
}

private fun String.looksLikeUiControlDiscussion(): Boolean {
    val normalized = lowercase()
    return Regex("""^\s*(?:请问|问一下|如何|怎么|怎样|为什么|解释|说明|介绍)""").containsMatchIn(this) ||
        Regex("""^\s*(?:how\s+(?:do|can|to)|what\s+is|explain)\b""").containsMatchIn(normalized) ||
        listOf(" API", "api", "接口", "怎么实现", "如何实现", "怎么设计").any { it in this }
}

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
        triggerExamples = listOf("打开 Wi-Fi 设置", "打开蓝牙设置", "打开使用情况访问权限设置"),
        requiredTools = listOf(
            MobileActionFunctions.OPEN_WIFI_SETTINGS,
            MobileActionFunctions.OPEN_USAGE_ACCESS_SETTINGS,
            MobileActionFunctions.OPEN_SYSTEM_SETTINGS,
            MobileActionFunctions.OPEN_FLASHLIGHT_SETTINGS,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.REMINDER_SKILL,
        version = 1,
        title = "后台提醒",
        description = "把自然语言提醒请求整理成本地一次性后台提醒工具调用。",
        triggerExamples = listOf("提醒我 10 分钟后喝水", "明天早上8点提醒我开会", "取消提醒 task-123"),
        requiredTools = listOf(
            MobileActionFunctions.SCHEDULE_REMINDER,
            MobileActionFunctions.CANCEL_REMINDER,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.TIME_ACTION_SKILL,
        version = 1,
        title = "系统时间工具",
        description = "打开系统时钟应用设置闹钟或倒计时；不跳过系统 UI，不验证外部应用内最终保存结果。",
        triggerExamples = listOf("每天晚上11:25设置闹钟", "倒计时20分钟", "set a timer for 10 minutes"),
        requiredTools = listOf(
            MobileActionFunctions.SET_SYSTEM_ALARM,
            MobileActionFunctions.SET_SYSTEM_TIMER,
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
        backgroundExecution = SkillBackgroundExecution(
            requiredTools = listOf(
                MobileActionFunctions.QUERY_BACKGROUND_TASKS,
                MobileActionFunctions.CONFIGURE_PERIODIC_CHECK,
            ),
            userConfigured = true,
            minimumIntervalMinutes = 60L,
            localOnly = true,
            allowedWork = setOf(
                SkillBackgroundWork.ReadOnlyLocalState,
                SkillBackgroundWork.PostLocalNotification,
            ),
        ),
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
        triggerExamples = listOf("启动微信", "打开微信应用详情设置", "打开相机"),
        requiredTools = listOf(
            MobileActionFunctions.OPEN_CAMERA,
            MobileActionFunctions.OPEN_APP_BY_NAME,
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
        id = BuiltInSkillRuntime.DEVICE_CONTROL_SKILL,
        version = 1,
        title = "屏幕观察与确认控制",
        description = "通过通用 Accessibility UI 工具观察当前屏幕，并在确认流程内执行点击、输入、滚动、返回或等待；不读取截图像素，不自动完成发送、删除、支付、转账或公开发布。",
        triggerExamples = listOf("观察当前屏幕", "observe current screen"),
        requiredTools = listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_PRESS_BACK,
            MobileActionFunctions.UI_WAIT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.SETTINGS_UI_CONTROL_SKILL,
        version = 1,
        title = "设置页 UI 控制",
        description = "在当前系统设置页内先观察屏幕，再通过通用 UI 原语点击可见设置项并等待验证；不绕过确认，不自动修改关键系统设置。",
        triggerExamples = listOf("在当前设置页点击 Wi-Fi", "tap Wi-Fi on the current settings screen"),
        requiredTools = listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_WAIT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.BROWSER_UI_SEARCH_SKILL,
        version = 1,
        title = "浏览器页面搜索",
        description = "在当前浏览器或网页内先观察屏幕，再聚焦搜索/地址框、输入关键词、提交搜索并等待结果更新；只使用通用 UI 原语。",
        triggerExamples = listOf("在当前浏览器搜索 Kotlin 协程", "search Kotlin coroutines in the current browser"),
        requiredTools = listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_WAIT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        lowRiskAppControlEligible = true,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.MAPS_UI_ROUTE_SKILL,
        version = 1,
        title = "地图页路线搜索",
        description = "在当前地图页内先观察屏幕，再聚焦搜索框、输入地点或路线目标、提交搜索并等待结果更新；只使用通用 UI 原语。",
        triggerExamples = listOf("在当前地图查去机场的路线", "route to the airport in the current maps page"),
        requiredTools = listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_WAIT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        lowRiskAppControlEligible = true,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.DRAFT_FORM_UI_SKILL,
        version = 1,
        title = "草稿表单填写",
        description = "在当前邮件或日历草稿页先观察屏幕，再填写正文或标题字段并等待验证；不发送、不保存、不发布。",
        triggerExamples = listOf("在当前邮件草稿填写 明天延期", "fill current calendar draft with project review"),
        requiredTools = listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_WAIT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CURRENT_APP_UI_SEARCH_SKILL,
        version = 1,
        title = "当前应用搜索",
        description = "在当前应用或页面内先观察屏幕，再聚焦搜索入口、输入关键词、提交搜索并等待结果更新；只使用通用 UI 原语。",
        triggerExamples = listOf("在当前应用搜索 海河牛奶", "search Kotlin in the current app"),
        requiredTools = listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_WAIT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        lowRiskAppControlEligible = true,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.OPEN_APP_UI_SEARCH_SKILL,
        version = 1,
        title = "打开应用后搜索",
        description = "按本机应用名打开目标 App，等待前台界面稳定后，通过通用 UI 原语搜索关键词；不下单、不支付、不发送、不发布。",
        triggerExamples = listOf("打开淘宝搜索海河牛奶", "open Pinduoduo and search milk"),
        requiredTools = listOf(
            MobileActionFunctions.OPEN_APP_BY_NAME,
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_WAIT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        lowRiskAppControlEligible = true,
        continuesAfterUnverifiedOpenAppLaunch = true,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.MODEL_DRIVEN_CURRENT_APP_UI_SEARCH_SKILL,
        version = 1,
        title = "当前应用本地模型搜索",
        description = "在当前应用或页面内先观察屏幕，随后只允许本地动作规划模型逐步选择一个低风险 UI 搜索工具；不下单、不支付、不发送、不删除、不授权、不发布。",
        triggerExamples = listOf("在当前应用搜索 海河牛奶", "search Kotlin in the current app"),
        requiredTools = listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_WAIT,
            MobileActionFunctions.UI_PRESS_BACK,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        lowRiskAppControlEligible = true,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.MODEL_DRIVEN_OPEN_APP_UI_SEARCH_SKILL,
        version = 1,
        title = "打开应用后本地模型搜索",
        description = "按本机应用名打开目标 App、等待并观察前台界面，随后只允许本地动作规划模型逐步选择一个低风险 UI 搜索工具；不下单、不支付、不发送、不删除、不授权、不发布。",
        triggerExamples = listOf("打开淘宝搜索海河牛奶", "open Pinduoduo and search milk"),
        requiredTools = listOf(
            MobileActionFunctions.OPEN_APP_BY_NAME,
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SUBMIT_SEARCH,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_WAIT,
            MobileActionFunctions.UI_PRESS_BACK,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        lowRiskAppControlEligible = true,
        continuesAfterUnverifiedOpenAppLaunch = true,
    ),
    SkillManifest(
        id = BuiltInSkillRuntime.CURRENT_PAGE_SIMPLE_INTERACTION_SKILL,
        version = 1,
        title = "当前页面轻交互",
        description = "在当前页面执行观察后的点击、输入、滚动、返回或等待；不自动完成发送、删除、支付、转账、下单或公开发布。",
        triggerExamples = listOf("点击当前页面的筛选", "scroll down on the current page"),
        requiredTools = listOf(
            MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            MobileActionFunctions.UI_TAP,
            MobileActionFunctions.UI_TYPE_TEXT,
            MobileActionFunctions.UI_SCROLL,
            MobileActionFunctions.UI_PRESS_BACK,
            MobileActionFunctions.UI_WAIT,
        ),
        inputSchemaJson = simpleTextInputSchema,
        riskLevel = RiskLevel.MediumDraftOrNavigation,
        lowRiskAppControlEligible = true,
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
    BuiltInSkillRuntime.SETTINGS_UI_CONTROL_SKILL,
    BuiltInSkillRuntime.BROWSER_UI_SEARCH_SKILL,
    BuiltInSkillRuntime.MAPS_UI_ROUTE_SKILL,
    BuiltInSkillRuntime.DRAFT_FORM_UI_SKILL,
    BuiltInSkillRuntime.CURRENT_APP_UI_SEARCH_SKILL,
    BuiltInSkillRuntime.OPEN_APP_UI_SEARCH_SKILL,
    BuiltInSkillRuntime.MODEL_DRIVEN_CURRENT_APP_UI_SEARCH_SKILL,
    BuiltInSkillRuntime.MODEL_DRIVEN_OPEN_APP_UI_SEARCH_SKILL,
    BuiltInSkillRuntime.CURRENT_PAGE_SIMPLE_INTERACTION_SKILL,
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

private class BuiltInSkillSource : SkillSource {
    override fun manifests(): List<SkillManifest> = builtInSkillManifests
}

/** Built-in skills exposed as a SolinModule. */
class BuiltInSkillsModule : com.bytedance.zgx.solin.module.SolinModule {
    override val moduleId: String get() = "builtin:skills"
    override fun register(registry: com.bytedance.zgx.solin.module.SolinModuleRegistry) {
        registry.addSkillSource(BuiltInSkillSource())
    }
}
