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
import com.bytedance.zgx.solin.device.AppInteractionProfiles
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
        // Each draft is computed at most once, and only when its branch is reached. The
        // absolute reminder reads the clock exactly once: matching and drafting against two
        // different readings could cross the trigger time and make the draft null.
        val systemAlarmDraft by lazy { SystemAlarmActionParser.draft(input) }
        val systemTimerDraft by lazy { SystemTimerActionParser.draft(input) }
        val absoluteReminderDraft by lazy { AbsoluteReminderActionParser.draft(input, clockMillis(), zoneId) }
        fun single(draft: ActionDraft): SkillPlan? = plan(input, draft, draft.toToolRequest())
        if (input.looksLikeCurrentScreenTextSummaryShareNonAction(input.lowercase())) return null
        // Every rule below handles a single action only; sequential requests are left to the
        // Agent loop. Branch ORDER is significant: the first matching rule wins.
        if (input.looksLikeSequentialAction()) return null
        return when {
            input.requestsClipboardSummaryShare() -> planClipboardSummaryShare(input)
            input.requestsCurrentScreenTextSummaryShare() -> planCurrentScreenTextSummaryShare(input)
            input.requestsCurrentSettingsUiControl() -> planCurrentSettingsUiControl(input)
            input.requestsCurrentBrowserUiSearch() -> planCurrentBrowserUiSearch(input)
            input.requestsCurrentMapsUiRoute() -> planCurrentMapsUiRoute(input)
            input.requestsCurrentDraftFormUiFill() -> planCurrentDraftFormUiFill(input)
            input.requestsOpenAppThenUiSearch() -> planOpenAppThenUiSearch(input)
            MapSearchActionParser.matches(input) -> single(MapSearchActionParser.draft(input))
            EmailDraftActionParser.matches(input) -> single(EmailDraftActionParser.draft(input))
            CalendarDraftActionParser.matches(input) -> single(CalendarDraftActionParser.draft(input))
            CalendarAvailabilityActionParser.matches(input) -> single(CalendarAvailabilityActionParser.draft(input))
            DeviceSettingsActionParser.matches(input) -> single(DeviceSettingsActionParser.draft(input))
            SystemSettingsActionParser.matches(input) -> single(SystemSettingsActionParser.draft(input))
            ContactQueryActionParser.matches(input) -> single(ContactQueryActionParser.draft(input))
            ContactDraftActionParser.matches(input) -> single(ContactDraftActionParser.draft(input))
            WebSearchActionParser.matches(input) -> single(WebSearchActionParser.draft(input))
            RecentScreenshotOcrActionParser.matches(input) -> single(RecentScreenshotOcrActionParser.draft(input))
            RecentImageOcrActionParser.matches(input) -> single(RecentImageOcrActionParser.draft(input))
            CurrentScreenshotOcrActionParser.matches(input) -> single(CurrentScreenshotOcrActionParser.draft())
            RecentFilesActionParser.matches(input) -> single(RecentFilesActionParser.draft(input))
            DeepLinkActionParser.matches(input) -> single(DeepLinkActionParser.draft(input))
            CameraActionParser.matches(input) -> single(CameraActionParser.draft())
            systemAlarmDraft != null -> single(requireNotNull(systemAlarmDraft))
            systemTimerDraft != null -> single(requireNotNull(systemTimerDraft))
            AppNavigationActionParser.matches(input) -> single(AppNavigationActionParser.draft(input))
            input.requestsCurrentAppUiSearch() -> planCurrentAppUiSearch(input)
            ForegroundAppActionParser.matches(input) -> single(ForegroundAppActionParser.draft())
            RecentNotificationsActionParser.matches(input) -> single(RecentNotificationsActionParser.draft(input))
            input.requestsCurrentScreenObservation() -> single(
                ActionDraft(
                    functionName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
                    title = "观察当前屏幕",
                    summary = "将读取当前屏幕的可访问状态快照；不会读取截图像素或发送远程。",
                    parameters = emptyMap(),
                ),
            )
            CurrentScreenTextActionParser.matches(input) -> single(CurrentScreenTextActionParser.draft(input))
            CancelReminderActionParser.matches(input) -> single(CancelReminderActionParser.draft(input))
            PeriodicCheckActionParser.matches(input) -> single(PeriodicCheckActionParser.draft(input))
            BackgroundTasksQueryActionParser.matches(input) -> single(BackgroundTasksQueryActionParser.draft(input))
            ShareTextActionParser.matches(input) -> single(ShareTextActionParser.draft(input))
            absoluteReminderDraft != null -> single(requireNotNull(absoluteReminderDraft))
            ReminderActionParser.matches(input) -> single(ReminderActionParser.draft(input))
            input.requestsClipboardContext() -> single(
                ActionDraft(
                    functionName = MobileActionFunctions.READ_CLIPBOARD,
                    title = "读取剪贴板",
                    summary = "将读取当前剪贴板文本。",
                    parameters = emptyMap(),
                ),
            )
            input.requestsCurrentPageSimpleInteraction() -> planCurrentPageSimpleInteraction(input)
            else -> null
        }
    }

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
        val resolvedReadRequest = readRequest ?: resolvedReadDraft.toToolRequest()
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
        val resolvedReadRequest = readRequest ?: resolvedReadDraft.toToolRequest()
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
                    request = shareDraft.toToolRequest(),
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
            steps = observeActVerifySteps(
                observeId = "observe_settings",
                act = { dependsOn ->
                    tapStep(
                        id = "tap_settings_target",
                        dependsOn = dependsOn,
                        target = target,
                        summary = "点击当前设置页中的可见设置项：$target。",
                    )
                },
                verifyId = "verify_settings_target",
                verifySummary = "等待设置页面变化并重新观察。",
            ),
        )
    }

    private fun planCurrentBrowserUiSearch(input: String): SkillPlan? {
        val query = input.extractCurrentBrowserUiQuery() ?: return null
        return deviceUiTemplatePlan(
            input = input,
            skillId = BROWSER_UI_SEARCH_SKILL,
            reason = "将在当前浏览器页观察、聚焦搜索框、输入关键词并提交搜索：$query。",
            steps = uiSearchSteps(
                observeId = "observe_browser",
                query = query,
                ids = UiSearchStepIds(
                    focus = "focus_browser_search",
                    waitField = "wait_browser_search_field",
                    type = "type_browser_query",
                    submit = "submit_browser_search",
                    verify = "verify_browser_results",
                ),
                focusTarget = "地址栏",
                focusSummary = "聚焦当前浏览器页面的地址栏或搜索栏。",
                waitFieldSummary = "等待浏览器搜索输入框获得焦点或搜索页稳定。",
                typeTarget = "地址栏",
                typeSummary = "在当前浏览器输入搜索关键词：$query。",
                submitSummary = "提交浏览器搜索：$query。",
                verifySummary = "等待浏览器搜索结果更新并重新观察。",
            ),
        )
    }

    private fun planCurrentMapsUiRoute(input: String): SkillPlan? {
        val query = input.extractCurrentMapsUiQuery() ?: return null
        return deviceUiTemplatePlan(
            input = input,
            skillId = MAPS_UI_ROUTE_SKILL,
            reason = "将在当前地图页观察、聚焦搜索框、输入地点或路线目标并提交搜索：$query。",
            steps = uiSearchSteps(
                observeId = "observe_maps",
                query = query,
                ids = UiSearchStepIds(
                    focus = "focus_maps_search",
                    waitField = "wait_maps_search_field",
                    type = "type_maps_query",
                    submit = "submit_maps_search",
                    verify = "verify_maps_results",
                ),
                focusTarget = "搜索入口",
                focusSummary = "聚焦当前地图页面的搜索输入框。",
                waitFieldSummary = "等待地图搜索输入框获得焦点或搜索页稳定。",
                typeTarget = "搜索输入框",
                typeSummary = "在当前地图页面输入路线或地点关键词：$query。",
                submitSummary = "提交地图搜索：$query。",
                verifySummary = "等待地图搜索结果或路线入口更新并重新观察。",
            ),
        )
    }

    private fun planCurrentDraftFormUiFill(input: String): SkillPlan? {
        val draft = input.extractCurrentDraftFormUiFill() ?: return null
        return deviceUiTemplatePlan(
            input = input,
            skillId = DRAFT_FORM_UI_SKILL,
            reason = "将在当前${draft.formTitle}草稿页观察并填写${draft.fieldTitle}；不会发送、保存或发布。",
            steps = observeActVerifySteps(
                observeId = "observe_draft_form",
                act = { dependsOn ->
                    typeTextStep(
                        id = "type_draft_field",
                        dependsOn = dependsOn,
                        target = draft.fieldTarget,
                        text = draft.text,
                        summary = "在当前${draft.formTitle}草稿页填写${draft.fieldTitle}。",
                    )
                },
                verifyId = "verify_draft_field",
                verifySummary = "等待草稿字段更新并重新观察。",
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
        val (reason, steps) = when (command) {
            is CurrentPageUiCommand.Tap ->
                "将在当前页面观察并点击可见元素：${command.target}。" to observeActVerifySteps(
                    observeId = "observe_current_page_tap",
                    act = { dependsOn ->
                        tapStep(
                            id = "tap_current_page_target",
                            dependsOn = dependsOn,
                            target = command.target,
                            summary = "点击当前页面中的可见元素：${command.target}。",
                        )
                    },
                    verifyId = "verify_current_page_tap",
                    verifySummary = "等待页面变化并重新观察。",
                )

            is CurrentPageUiCommand.Type ->
                "将在当前页面观察并输入文本。" to observeActVerifySteps(
                    observeId = "observe_current_page_input",
                    act = { dependsOn ->
                        typeTextStep(
                            id = "type_current_page_text",
                            dependsOn = dependsOn,
                            text = command.text,
                            target = command.target,
                            summary = "在当前页面输入文本。",
                        )
                    },
                    verifyId = "verify_current_page_input",
                    verifySummary = "等待输入结果并重新观察。",
                )

            is CurrentPageUiCommand.Scroll ->
                "将在当前页面观察并滚动。" to observeActVerifySteps(
                    observeId = "observe_current_page_scroll",
                    act = { dependsOn ->
                        scrollStep(
                            id = "scroll_current_page",
                            dependsOn = dependsOn,
                            direction = command.direction,
                            summary = "滚动当前页面。",
                        )
                    },
                    verifyId = "verify_current_page_scroll",
                    verifySummary = "等待滚动结果并重新观察。",
                )

            CurrentPageUiCommand.Back ->
                "将在当前页面执行系统返回。" to listOf(
                    pressBackStep(
                        id = "press_current_page_back",
                        summary = "执行系统返回。",
                    ),
                    waitStep(
                        id = "verify_current_page_back",
                        dependsOn = listOf("press_current_page_back"),
                        summary = "等待返回后的页面稳定。",
                    ),
                )

            CurrentPageUiCommand.Wait ->
                "将等待当前页面稳定并重新观察。" to listOf(
                    waitStep(
                        id = "wait_current_page",
                        dependsOn = emptyList(),
                        summary = "等待当前页面稳定并重新观察。",
                    ),
                )
        }
        return deviceUiTemplatePlan(
            input = input,
            skillId = CURRENT_PAGE_SIMPLE_INTERACTION_SKILL,
            reason = reason,
            steps = steps,
        )
    }

    /** observe → one UI action → wait-and-reobserve, each step depending on the previous one. */
    private fun observeActVerifySteps(
        observeId: String,
        act: (dependsOn: List<String>) -> SkillStep.ToolStep,
        verifyId: String,
        verifySummary: String,
    ): List<SkillStep> {
        val action = act(listOf(observeId))
        return listOf(
            observeScreenStep(observeId),
            action,
            waitStep(id = verifyId, dependsOn = listOf(action.id), summary = verifySummary),
        )
    }

    private fun currentAppSearchSteps(
        observeId: String,
        query: String,
        firstDependsOn: List<String> = emptyList(),
        expectedPackageName: String? = null,
        expectedAppName: String? = null,
    ): List<SkillStep> =
        uiSearchSteps(
            observeId = observeId,
            query = query,
            ids = UiSearchStepIds(
                focus = "focus_app_search",
                waitField = "wait_app_search_field",
                type = "type_app_search_query",
                submit = "submit_app_search",
                verify = "verify_app_search_results",
            ),
            focusTarget = "搜索入口",
            focusSummary = "聚焦当前应用中的搜索入口。",
            waitFieldSummary = "等待搜索输入框获得焦点或搜索页稳定。",
            typeTarget = "搜索输入框",
            typeSummary = "输入搜索关键词：$query。",
            submitSummary = "提交当前应用内搜索：$query。",
            verifySummary = "等待搜索结果页更新并重新观察。",
            firstDependsOn = firstDependsOn,
            expectedPackageName = expectedPackageName,
            expectedAppName = expectedAppName,
        )

    /**
     * observe → focus search → wait for field → type query → submit → verify results.
     *
     * Step IDs are passed in explicitly because they are persisted in skill-run checkpoints:
     * each template keeps the IDs it has always used.
     */
    private fun uiSearchSteps(
        observeId: String,
        query: String,
        ids: UiSearchStepIds,
        focusTarget: String,
        focusSummary: String,
        waitFieldSummary: String,
        typeTarget: String,
        typeSummary: String,
        submitSummary: String,
        verifySummary: String,
        firstDependsOn: List<String> = emptyList(),
        expectedPackageName: String? = null,
        expectedAppName: String? = null,
    ): List<SkillStep> =
        listOf(
            observeScreenStep(observeId).copy(dependsOn = firstDependsOn),
            tapStep(
                id = ids.focus,
                dependsOn = listOf(observeId),
                target = focusTarget,
                summary = focusSummary,
                expectedPackageName = expectedPackageName,
            ),
            waitStep(
                id = ids.waitField,
                dependsOn = listOf(ids.focus),
                summary = waitFieldSummary,
                expectedPackageName = expectedPackageName,
            ),
            typeTextStep(
                id = ids.type,
                dependsOn = listOf(ids.waitField),
                text = query,
                target = typeTarget,
                summary = typeSummary,
                expectedPackageName = expectedPackageName,
            ),
            submitSearchStep(
                id = ids.submit,
                dependsOn = listOf(ids.type),
                summary = submitSummary,
                expectedPackageName = expectedPackageName,
            ),
            waitStep(
                id = ids.verify,
                dependsOn = listOf(ids.submit),
                summary = verifySummary,
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
    ): SkillStep.ToolStep =
        uiToolStep(
            id = id,
            toolName = MobileActionFunctions.OPEN_APP_BY_NAME,
            title = "打开应用",
            summary = summary,
            parameters = mapOf("appName" to appName),
        )

    private fun observeScreenStep(id: String): SkillStep.ToolStep =
        uiToolStep(
            id = id,
            toolName = MobileActionFunctions.OBSERVE_CURRENT_SCREEN,
            title = "观察当前屏幕",
            summary = "先读取当前屏幕的本地 Accessibility 状态快照，作为 UI 动作前置观察。",
            parameters = mapOf(
                "maxTextChars" to "2000",
                "maxNodes" to "80",
            ),
        )

    private fun tapStep(
        id: String,
        dependsOn: List<String>,
        target: String,
        summary: String,
        expectedPackageName: String? = null,
    ): SkillStep.ToolStep =
        uiToolStep(
            id = id,
            dependsOn = dependsOn,
            toolName = MobileActionFunctions.UI_TAP,
            title = "点击屏幕元素",
            summary = summary,
            parameters = buildMap {
                put("target", target)
                put("timeoutMillis", "1500")
                expectedPackageName?.takeIf { it.isNotBlank() }?.let { put("expectedPackageName", it) }
            },
        )

    private fun typeTextStep(
        id: String,
        dependsOn: List<String>,
        text: String,
        summary: String,
        target: String? = null,
        expectedPackageName: String? = null,
    ): SkillStep.ToolStep =
        uiToolStep(
            id = id,
            dependsOn = dependsOn,
            toolName = MobileActionFunctions.UI_TYPE_TEXT,
            title = "输入文本",
            summary = summary,
            parameters = buildMap {
                put("text", text)
                target?.takeIf { it.isNotBlank() }?.let { put("target", it) }
                put("timeoutMillis", "1500")
                expectedPackageName?.takeIf { it.isNotBlank() }?.let { put("expectedPackageName", it) }
            },
        )

    private fun submitSearchStep(
        id: String,
        dependsOn: List<String>,
        summary: String,
        expectedPackageName: String? = null,
    ): SkillStep.ToolStep =
        uiToolStep(
            id = id,
            dependsOn = dependsOn,
            toolName = MobileActionFunctions.UI_SUBMIT_SEARCH,
            title = "提交搜索",
            summary = summary,
            parameters = buildMap {
                put("timeoutMillis", "1500")
                expectedPackageName?.takeIf { it.isNotBlank() }?.let { put("expectedPackageName", it) }
            },
        )

    private fun waitStep(
        id: String,
        dependsOn: List<String>,
        summary: String,
        verifySearchQuery: String? = null,
        expectedPackageName: String? = null,
        expectedAppName: String? = null,
        timeoutMillis: String = "800",
    ): SkillStep.ToolStep =
        uiToolStep(
            id = id,
            dependsOn = dependsOn,
            toolName = MobileActionFunctions.UI_WAIT,
            title = "等待并验证",
            summary = summary,
            parameters = buildMap {
                put("timeoutMillis", timeoutMillis)
                verifySearchQuery?.takeIf { it.isNotBlank() }?.let { put("verifySearchQuery", it) }
                expectedPackageName?.takeIf { it.isNotBlank() }?.let { put("expectedPackageName", it) }
                expectedAppName?.takeIf { it.isNotBlank() }?.let { put("expectedAppName", it) }
            },
        )

    private fun scrollStep(
        id: String,
        dependsOn: List<String>,
        direction: String,
        summary: String,
        expectedPackageName: String? = null,
    ): SkillStep.ToolStep =
        uiToolStep(
            id = id,
            dependsOn = dependsOn,
            toolName = MobileActionFunctions.UI_SCROLL,
            title = "滚动页面",
            summary = summary,
            parameters = buildMap {
                put("direction", direction)
                put("timeoutMillis", "1500")
                expectedPackageName?.takeIf { it.isNotBlank() }?.let { put("expectedPackageName", it) }
            },
        )

    private fun pressBackStep(
        id: String,
        summary: String,
    ): SkillStep.ToolStep =
        uiToolStep(
            id = id,
            toolName = MobileActionFunctions.UI_PRESS_BACK,
            title = "返回",
            summary = summary,
            parameters = mapOf("timeoutMillis" to "1000"),
        )

    /** One tool step whose draft and request carry the same tool, parameters and summary. */
    private fun uiToolStep(
        id: String,
        dependsOn: List<String> = emptyList(),
        toolName: String,
        title: String,
        summary: String,
        parameters: Map<String, String>,
    ): SkillStep.ToolStep =
        SkillStep.ToolStep(
            id = id,
            dependsOn = dependsOn,
            request = ToolRequest(
                toolName = toolName,
                arguments = parameters,
                reason = summary,
            ),
            draft = ActionDraft(
                functionName = toolName,
                title = title,
                summary = summary,
                parameters = parameters,
            ),
        )

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

/** Persisted step IDs of one search-UI template (see uiSearchSteps). */
private data class UiSearchStepIds(
    val focus: String,
    val waitField: String,
    val type: String,
    val submit: String,
    val verify: String,
)
