package com.bytedance.zgx.solin.ui

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.MemoryEvidenceUiSummary
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.PendingRemoteSendDisclosure
import com.bytedance.zgx.solin.PublicWebEvidenceItem
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.RunDataReceiptUiSummary
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.capability.CapabilityMatrix
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.ui.components.ACTION_SUMMARY_COLLAPSE_CHARS
import com.bytedance.zgx.solin.ui.components.RunStepVisual
import com.bytedance.zgx.solin.ui.components.actionDataBoundaryDisplayRows
import com.bytedance.zgx.solin.ui.components.actionDataLeavesPhone
import com.bytedance.zgx.solin.ui.components.actionParameterDisplayRows
import com.bytedance.zgx.solin.ui.components.actionTextDisplay
import com.bytedance.zgx.solin.ui.components.attachmentProtectionNotice
import com.bytedance.zgx.solin.ui.components.compactModelStatusShort
import com.bytedance.zgx.solin.ui.components.homeOffersLoadModel
import com.bytedance.zgx.solin.ui.components.meteredDownloadActionLabel
import com.bytedance.zgx.solin.ui.components.composerNotice
import com.bytedance.zgx.solin.ui.components.VOICE_CONSENT_BODY
import com.bytedance.zgx.solin.ui.components.VOICE_CONSENT_TITLE
import com.bytedance.zgx.solin.ui.components.hasAnySavedValue
import com.bytedance.zgx.solin.ui.components.inferencePreferenceDescription
import com.bytedance.zgx.solin.ui.components.memoryEvidenceDisplayText
import com.bytedance.zgx.solin.ui.components.modelPathGuidanceRows
import com.bytedance.zgx.solin.ui.components.parseRemoteContextWindowTokens
import com.bytedance.zgx.solin.ui.components.publicWebEvidenceDisplayRows
import com.bytedance.zgx.solin.ui.components.publicWebSourceDisplayText
import com.bytedance.zgx.solin.ui.components.recoveryActionButtonLabel
import com.bytedance.zgx.solin.ui.components.remoteModeDisclosureDisplayRows
import com.bytedance.zgx.solin.ui.components.remoteSendDisclosureCanSuppressForSession
import com.bytedance.zgx.solin.ui.components.remoteSendDisclosureDisplayRows
import com.bytedance.zgx.solin.ui.components.runTimelineItemDisplayText
import com.bytedance.zgx.solin.ui.components.runTimelineStepVisuals
import com.bytedance.zgx.solin.ui.components.selectableInferencePreferences
import com.bytedance.zgx.solin.ui.components.semanticMemoryIndexStatusText
import com.bytedance.zgx.solin.ui.components.trustDeletionBoundaryText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolinScreenDisplayTest {
    @Test
    fun remoteAttachmentProtectionNoticeNamesVisionImagePath() {
        assertContainsAll(
            REMOTE_ATTACHMENT_PROTECTION_NOTICE,
            "远程模式",
            "图片发送前会确认",
            "非图片附件",
            "分享文本",
            "OCR",
            "不会自动发送",
        )
    }

    @Test
    fun trustBoundaryCopyNamesLocalRemotePermissionAndDeletionControls() {
        assertContainsAll(
            PRODUCT_POSITIONING_TEXT,
            "隐私优先",
            "远程可选",
            "设备动作必须确认",
        )
        assertContainsAll(PRODUCT_POSITIONING_SHORT_TEXT, "隐私优先的随身 AI 助手")
        // Not-ready home: three placements, and the promise that nothing is read or sent first.
        assertContainsAll(PRODUCT_HOME_NOT_READY_TITLE, "先选一个大脑")
        assertContainsAll(PRODUCT_HOME_NOT_READY_LINE, "不读取手机内容", "不向外发送")
        assertContainsAll(PRODUCT_HOME_LOCAL_CHOICE_TITLE, "在本机运行")
        assertContainsAll(PRODUCT_HOME_LOCAL_CHOICE_LINE, "不离开手机")
        assertContainsAll(PRODUCT_HOME_REMOTE_CHOICE_TITLE, "连接远程模型")
        assertContainsAll(PRODUCT_HOME_REMOTE_CHOICE_LINE, "加密保存在本机", "图片发送前会确认")
        assertContainsAll(PRODUCT_HOME_IMPORT_CHOICE_TITLE, ".litertlm")
        assertContainsAll(PRODUCT_HOME_SKIP_ACTION, "稍后")
        // Ready home: a greeting, the placement of this run, and four task rows.
        assertContainsAll(PRODUCT_HOME_GREETING, "帮你做什么")
        assertContainsAll(PRODUCT_HOME_PLACEMENT_LOCAL, "留在手机上")
        assertContainsAll(PRODUCT_HOME_PLACEMENT_REMOTE, "远程")
        assertContainsAll(PRODUCT_HOME_PLACEMENT_AUTO, "自动")
        assertContainsAll(PRODUCT_HOME_PRIVACY_LINK, "留在本机")
        assertEquals(4, HOME_SUGGESTIONS.size)
        assertContainsAll(
            HOME_SUGGESTIONS.joinToString("\n") { "${it.text}\n${it.category}" },
            "闹钟",
            "日历",
            "操作手机",
            "记忆",
        )
        assertContainsAll(MODEL_MANAGER_POSITIONING_TEXT, "下载或导入本地模型", "离线使用", "远程多模态可选", "切换远程会提醒")
        assertContainsAll(PRODUCT_LOCAL_VALUE_TEXT, "基础问答", "主动选择的图片")
        assertContainsAll(PRODUCT_REMOTE_VALUE_TEXT, "图片")
        assertContainsAll(PRODUCT_ACTION_VALUE_TEXT, "确认或取消")
        assertContainsAll(PRIVACY_POLICY_ENTRY_TEXT, "留在本机", "发送到远程", "确认后才执行")
        assertContainsAll(REMOTE_MODE_DISCLOSURE_TEXT, "OpenAI 兼容", "API Key 加密保存在本机", "逐次确认")
        assertContainsAll(MODEL_DOWNLOAD_RATIONALE_TEXT, "离线可用", "2.4 GB", "远程模型")
        assertContainsAll(VOICE_INPUT_PRIVACY_DESCRIPTION, "系统语音转写", "只进入输入框", "不自动发送", "不读取本地音频文件", "开启前会先确认")
        assertContainsAll(VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE, "语音输入")
        assertContainsAll(VOICE_INPUT_PERMISSION_DISCLOSURE_BODY, "Android 系统语音识别", "不保存音频文件", "确认后才会请求麦克风权限")
        assertContainsAll(TRUST_LOCAL_BOUNDARY_TEXT, "留在本机", "仅本机")
        assertContainsAll(TRUST_REMOTE_BOUNDARY_TEXT, "对话上下文", "逐次确认后随请求发送", "OCR 摘录")
        assertContainsAll(TRUST_PERMISSION_BOUNDARY_TEXT, "Accessibility 文本", "前台一次性确认")
        assertContainsAll(trustDeletionBoundaryText(ChatUiState()), "清空长期记忆", "删除当前会话", "清除远程服务地址", "API Key")
        assertContainsAll(SENSITIVE_CAPABILITY_DISCLOSURE_TEXT, "麦克风", "Usage Stats", "设备动作", "取消、撤销或清除路径")
        CapabilityMatrix.nextStageMvpScenarioTitles.values.forEach { title ->
            assertTrue("missing trust center summary title: $title", TRUST_CENTER_CAPABILITY_TEXT.contains(title))
        }
    }

    @Test
    fun trustCenterCapabilityRowsCoverNextStageScenarios() {
        val rows = trustCenterCapabilityDisplayRows()
        val allText = rows.joinToString("\n") { "${it.title}\n${it.body}" }

        assertEquals(CapabilityMatrix.nextStageMvpScenarioIds.size, rows.size)
        listOf(
            "本地私密问答与记忆",
            "屏幕/剪贴板总结分享",
            "低风险 App 搜索控制",
            "本地提醒与后台任务",
            "远程公开证据查询",
            "能力与信任中心",
        ).forEach { expectedTitle ->
            assertTrue("missing trust center row: $expectedTitle", rows.any { it.title == expectedTitle })
        }
        listOf(
            "聊天输入、记住/忘记指令",
            "剪贴板或当前屏幕总结分享",
            "App 搜索技能与无障碍控制",
            "提醒创建与后台任务",
            "远程公开搜索工具",
            "隐私与权限页",
            "仅本机数据",
            "本地后台任务",
            "公开信息",
            "确认：必须确认",
            "远程：可用",
            "远程：不可自动发送",
            "停止执行并提示",
            "整批拒绝",
        ).forEach { requiredCopy ->
            assertTrue("missing trust center copy: $requiredCopy", allText.contains(requiredCopy))
        }
        assertContainsNone(allText, "sk-", "Bearer ", "chat_input_and_remember_forget_commands", "model_manager_privacy_tab")
    }

    @Test
    fun sensitiveDisclosureRowsCoverAllHighRiskCapabilities() {
        val rows = sensitiveCapabilityDisclosureDisplayRows()
        val allText = rows.joinToString("\n") { "${it.title}\n${it.body}" }

        assertEquals(CapabilityMatrix.sensitiveCapabilityDisclosures.size, rows.size)
        listOf(
            "远程模型发送",
            "语音输入和麦克风",
            "分享和文件选择",
            "设备动作和外部 App",
            "联系人和日历忙闲",
            "媒体、最近图片和截图 OCR",
            "Usage Stats 前台应用估计",
            "Accessibility 当前屏幕文本",
            "当前屏幕截图 OCR",
        ).forEach { expectedTitle ->
            assertTrue("missing disclosure row: $expectedTitle", rows.any { it.title == expectedTitle })
        }
        listOf(
            "数据：",
            "同意：",
            "远程：",
            "撤销/清除：",
            "确认前不请求远程接口",
            "不读取本地音频文件",
            "Android 系统语音识别",
            "必须先确认",
            "外部打开结果",
            "不读取日历标题、地点或参与人",
            "不读取屏幕内容",
            "不是截图、像素读取或视觉理解",
            "MediaProjection",
            "仅本机",
            "审计仅保留脱敏摘要",
        ).forEach { requiredCopy ->
            assertTrue("missing disclosure copy: $requiredCopy", allText.contains(requiredCopy))
        }
        rows.forEach { row ->
            assertTrue("${row.title} missing data boundary", row.body.contains("数据："))
            assertTrue("${row.title} missing consent boundary", row.body.contains("同意："))
            assertTrue("${row.title} missing remote boundary", row.body.contains("远程："))
            assertTrue("${row.title} missing revoke/clear boundary", row.body.contains("撤销/清除："))
        }
        assertContainsNone(allText, "清理本地审计", "删除审计")
    }

    @Test
    fun modelPathGuidanceNamesLocalRemoteAndLightweightFallback() {
        val text = modelPathGuidanceRows(DEFAULT_CHAT_MODEL).joinToString("\n") {
            "${it.label}: ${it.body}"
        }

        assertContainsAll(
            text,
            "本地",
            "离线问答",
            "重新下载",
            "空间不足",
            "远程",
            "切换到远程模型时会提醒一次",
            "主动附加",
            "轻量",
            "没有更小的官方推荐聊天模型",
            ".litertlm",
        )
    }

    @Test
    fun actionParametersDisplayLinkDomainAndTargetPackage() {
        val linkRows = actionParameterDisplayRows(
            key = "uri",
            value = "https://example.com/private/path?ref=demo",
        )

        assertEquals("链接域名", linkRows[0].label)
        assertEquals("example.com", linkRows[0].value)
        assertEquals("完整链接", linkRows[1].label)
        assertEquals("https://example.com/private/path?ref=demo", linkRows[1].value)

        val packageRows = actionParameterDisplayRows(
            key = "packageName",
            value = "com.example.target",
        )

        assertEquals(1, packageRows.size)
        assertEquals("目标包", packageRows.single().label)
        assertEquals("com.example.target", packageRows.single().value)
    }

    @Test
    fun actionParametersUseReadableLabelsForDialSmsMediaCalculatorAndTimes() {
        assertEquals("电话号码", actionParameterDisplayRows("phoneNumber", "10086").single().label)
        assertEquals("短信内容", actionParameterDisplayRows("body", "到了").single().label)
        assertEquals("算式", actionParameterDisplayRows("expression", "1+2").single().label)
        val media = actionParameterDisplayRows("command", "next").single()
        assertEquals("媒体操作", media.label)
        assertEquals("下一首", media.value)
        val time = actionParameterDisplayRows("beginTimeMillis", "1700000000000").single()
        assertEquals("开始时间", time.label)
        assertTrue(time.value, Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(time.value))
        assertEquals("customKey", actionParameterDisplayRows("customKey", "v").single().label)
    }

    @Test
    fun actionDataBoundaryNamesExternalLocalAndBackgroundDestinations() {
        val externalRows = actionDataBoundaryDisplayRows(MobileActionFunctions.SHARE_TEXT)
        assertContainsAll(externalRows.joinToString(), "外部 App", "不会替你确认外部结果")

        val localRows = actionDataBoundaryDisplayRows(MobileActionFunctions.READ_CLIPBOARD)
        assertContainsAll(localRows.joinToString(), "本机内容", "只留在本机", "不会自动发送给远程模型")

        val reminderRows = actionDataBoundaryDisplayRows(MobileActionFunctions.SCHEDULE_REMINDER)
        assertContainsAll(reminderRows.joinToString(), "后台任务", "默认留在本机")

        // The placement mark next to the boundary line follows the same classification.
        assertTrue(actionDataLeavesPhone(MobileActionFunctions.SHARE_TEXT))
        assertTrue(actionDataLeavesPhone(MobileActionFunctions.OPEN_APP_BY_NAME))
        assertFalse(actionDataLeavesPhone(MobileActionFunctions.READ_CLIPBOARD))
        assertFalse(actionDataLeavesPhone(MobileActionFunctions.SCHEDULE_REMINDER))
    }

    @Test
    fun actionTextDisplayCollapsesLongTextAndKeepsLength() {
        val longText = "a".repeat(ACTION_SUMMARY_COLLAPSE_CHARS + 24)

        val collapsed = actionTextDisplay(
            text = longText,
            collapsedMaxChars = ACTION_SUMMARY_COLLAPSE_CHARS,
            expanded = false,
        )
        val expanded = actionTextDisplay(
            text = longText,
            collapsedMaxChars = ACTION_SUMMARY_COLLAPSE_CHARS,
            expanded = true,
        )

        assertTrue(collapsed.canToggle)
        assertEquals(longText.length, collapsed.totalChars)
        assertTrue(collapsed.text.endsWith("..."))
        assertTrue(collapsed.text.length <= ACTION_SUMMARY_COLLAPSE_CHARS)
        assertEquals(longText, expanded.text)
    }

    @Test
    fun localModelStatusDisplaysConfiguredContextWindow() {
        val status = currentModelStatus(
            ChatUiState(
                modelPath = "/tmp/model.litertlm",
                backend = BackendChoice.GPU,
                localMaxTotalTokens = LocalModelTokenLimits.MAX_TOTAL_TOKENS,
            ),
        )

        assertContainsAll(status, "GPU", "Token 8k")
        assertTrue(status.endsWith("待加载"))
    }

    @Test
    fun remoteModelStatusDoesNotShowLocalContextWindow() {
        val status = currentModelStatus(
            ChatUiState(
                inferenceMode = InferenceMode.Remote,
                remoteModelConfig = RemoteModelConfig(modelName = "remote-test-model"),
                isReady = true,
            ),
        )

        assertContainsAll(status, "remote-test-model", "远程")
        assertContainsNone(status, "上下文")
    }

    @Test
    fun remotePreferenceStatusDoesNotReuseLocalLoadState() {
        val status = currentModelStatus(
            ChatUiState(
                inferenceMode = InferenceMode.Remote,
                remoteModelConfig = RemoteModelConfig(modelName = "remote-test-model"),
                modelPath = "/tmp/local-model.litertlm",
                statusText = "远程模型未配置",
            ),
        )

        assertContainsAll(status, "偏好：远程", "远程模型未配置")
        assertContainsNone(status, "待加载")
    }

    @Test
    fun modelManagerPreferenceOptionsRespectRolloutVisibility() {
        assertEquals(
            listOf(InferenceMode.Local, InferenceMode.Remote),
            selectableInferencePreferences(autoSelectable = false),
        )
        assertEquals(
            listOf(InferenceMode.Local, InferenceMode.Auto, InferenceMode.Remote),
            selectableInferencePreferences(autoSelectable = true),
        )
        assertContainsAll(
            inferencePreferenceDescription(InferenceMode.Local),
            "只用手机模型",
            "不可用时明确失败",
        )
        assertContainsAll(
            inferencePreferenceDescription(InferenceMode.Auto),
            "允许远程",
            "任务和设备状态",
            "显式选择",
        )
        assertContainsAll(
            inferencePreferenceDescription(InferenceMode.Remote),
            "强制使用",
            "仅本机数据仍会阻断",
        )
    }

    @Test
    fun remoteCapabilityFieldsParsePositiveContextAndCountAsSavedConfig() {
        assertEquals(8_192, parseRemoteContextWindowTokens("8192"))
        assertEquals(null, parseRemoteContextWindowTokens(""))
        assertEquals(null, parseRemoteContextWindowTokens("0"))
        assertEquals(null, parseRemoteContextWindowTokens("-1"))
        assertEquals(null, parseRemoteContextWindowTokens("not-a-number"))

        assertFalse(RemoteModelConfig().hasAnySavedValue())
        assertTrue(RemoteModelConfig(supportsToolCalls = true).hasAnySavedValue())
        assertTrue(RemoteModelConfig(contextWindowTokens = 8_192).hasAnySavedValue())
    }

    @Test
    fun homeOffersLoadModelOnlyForAnInstalledUnloadedLocalModel() {
        // Regression: after a restart the chat model is on disk but not loaded; the new home used
        // to show only a disabled download button there.
        assertTrue(ChatUiState(modelPath = "/models/chat.litertlm", isReady = false).homeOffersLoadModel())
        assertFalse(ChatUiState(modelPath = null, isReady = false).homeOffersLoadModel())
        assertFalse(ChatUiState(modelPath = "/models/chat.litertlm", isReady = true).homeOffersLoadModel())
        assertFalse(
            ChatUiState(
                modelPath = "/models/chat.litertlm",
                isReady = false,
                inferenceMode = InferenceMode.Remote,
            ).homeOffersLoadModel(),
        )
    }

    @Test
    fun meteredDownloadLabelStatesTheDataStillToFetch() {
        assertEquals(
            "用移动数据继续（约 ${ModelCatalog.formatBytes(1_500_000_000L)}）",
            meteredDownloadActionLabel(ChatUiState(downloadedBytes = 500_000_000L, totalBytes = 2_000_000_000L)),
        )
        assertEquals("用移动数据继续", meteredDownloadActionLabel(ChatUiState()))
    }

    @Test
    fun firstRunSetupVisibilityUsesExplicitStateFlag() {
        assertFalse(ChatUiState(showFirstRunSetup = false).showFirstRunSetup)
        assertTrue(ChatUiState(showFirstRunSetup = true).showFirstRunSetup)
    }

    @Test
    fun autoStatusShowsPreferenceWithoutClaimingAnActualPlacement() {
        val status = currentModelStatus(
            ChatUiState(
                inferenceMode = InferenceMode.Auto,
                remoteModelConfig = RemoteModelConfig(modelName = "remote-candidate"),
            ),
        )

        assertContainsAll(status, "偏好：自动", "按请求评估")
        assertContainsNone(status, "本次使用本地模型", "本次使用远程模型")
    }

    @Test
    fun attachmentNoticeUsesActualPlacementWhenAvailableAndPreferenceOtherwise() {
        assertEquals(
            AUTO_ATTACHMENT_PROTECTION_NOTICE,
            attachmentProtectionNotice(InferenceMode.Auto, null, null),
        )
        assertEquals(
            ACTUAL_REMOTE_ATTACHMENT_PROTECTION_NOTICE,
            attachmentProtectionNotice(
                preference = InferenceMode.Auto,
                actualPlacement = RunPlacement.Remote,
                actualReason = PlacementReasonCode.AUTO_COMPLEX_REMOTE,
            ),
        )
        assertEquals(
            null,
            attachmentProtectionNotice(
                preference = InferenceMode.Remote,
                actualPlacement = RunPlacement.Local,
                actualReason = PlacementReasonCode.PRIVACY_REQUIRES_LOCAL,
            ),
        )
    }

    @Test
    fun voiceConsentCopyKeepsTheFourConsentFacts() {
        assertEquals("开启语音输入", VOICE_CONSENT_TITLE)
        assertContainsAll(
            VOICE_CONSENT_BODY,
            "系统语音识别服务",
            "不保存音频",
            "只填入输入框",
            "不会自动发送",
            "请求麦克风权限",
        )
    }

    @Test
    fun composerNoticeShowsOneShortLineAndKeepsTheFullWording() {
        val remote = composerNotice(InferenceMode.Remote, null, null)
        assertEquals("远程：图片发送前确认，其他附件不发", remote?.text)
        assertEquals(REMOTE_ATTACHMENT_PROTECTION_NOTICE, remote?.fullText)

        val actualRemote = composerNotice(
            preference = InferenceMode.Auto,
            actualPlacement = RunPlacement.Remote,
            actualReason = PlacementReasonCode.AUTO_COMPLEX_REMOTE,
        )
        assertEquals("本次远程：图片发送前确认，其他附件不发", actualRemote?.text)
        assertEquals(ACTUAL_REMOTE_ATTACHMENT_PROTECTION_NOTICE, actualRemote?.fullText)

        val auto = composerNotice(InferenceMode.Auto, null, null)
        assertEquals("自动：远程时图片发送前确认，其他附件不发", auto?.text)
        assertEquals(AUTO_ATTACHMENT_PROTECTION_NOTICE, auto?.fullText)

        // Every notice line stays within the one-line copy limit and keeps both safety facts.
        listOfNotNull(remote, actualRemote, auto).forEach { notice ->
            assertTrue(
                "Composer notice must stay one short line: ${notice.text}",
                notice.text.length <= 24,
            )
            assertContainsAll(notice.text, "图片发送前确认", "附件不发")
        }

        assertEquals(null, composerNotice(InferenceMode.Local, null, null))
        assertEquals(
            null,
            composerNotice(
                preference = InferenceMode.Remote,
                actualPlacement = RunPlacement.Local,
                actualReason = PlacementReasonCode.PRIVACY_REQUIRES_LOCAL,
            ),
        )
    }

    @Test
    fun placementDisplaySeparatesPreferenceFromActualRun() {
        assertEquals("偏好：自动", inferencePreferenceDisplayText(InferenceMode.Auto))
        assertEquals(null, activePlacementDisplayText(null, null))
        assertEquals(
            "本次使用本地模型：任务较轻，手机状态正常。",
            activePlacementDisplayText(
                placement = RunPlacement.Local,
                reason = PlacementReasonCode.AUTO_SIMPLE_LOCAL,
            ),
        )
        assertEquals(
            "本次使用远程模型：任务较复杂，且远程连接已验证。",
            activePlacementDisplayText(
                placement = RunPlacement.Remote,
                reason = PlacementReasonCode.AUTO_COMPLEX_REMOTE,
            ),
        )
        assertEquals(
            "无法执行：没有同时满足隐私、能力和可用性要求的模型。",
            activePlacementDisplayText(
                placement = null,
                reason = PlacementReasonCode.NO_ELIGIBLE_TARGET,
            ),
        )
    }

    @Test
    fun compactBusyStatusKeepsPreferenceAndActualPlacementVisible() {
        val busyAuto = ChatUiState(
            inferenceMode = InferenceMode.Auto,
            isBusy = true,
        )

        assertEquals(
            "自动→本地",
            compactModelStatusShort(
                busyAuto.copy(
                    activeRunPlacement = RunPlacement.Local,
                    activeRunPlacementReason = PlacementReasonCode.AUTO_SIMPLE_LOCAL,
                ),
            ),
        )
        assertEquals(
            "自动→远程",
            compactModelStatusShort(
                busyAuto.copy(
                    activeRunPlacement = RunPlacement.Remote,
                    activeRunPlacementReason = PlacementReasonCode.AUTO_COMPLEX_REMOTE,
                ),
            ),
        )
        assertEquals("自动·处理中", compactModelStatusShort(busyAuto))
    }

    @Test
    fun placementDisplayDoesNotExposeConnectionDetailsOrInferActualFromPreference() {
        val text = buildList {
            InferenceMode.entries.forEach { add(inferencePreferenceDisplayText(it)) }
            add(AUTO_ATTACHMENT_PROTECTION_NOTICE)
            add(ACTUAL_REMOTE_ATTACHMENT_PROTECTION_NOTICE)
            PlacementReasonCode.entries.forEach { reason ->
                listOf(null, RunPlacement.Local, RunPlacement.Remote).forEach { placement ->
                    activePlacementDisplayText(placement, reason)?.let(::add)
                }
            }
        }.joinToString("\n")

        assertContainsNone(
            text,
            "https://",
            "http://",
            "127.0.0.1",
            "10.0.2.2",
            "localhost",
            "endpoint",
            "revision",
        )
        assertEquals(null, activePlacementDisplayText(null, null))
    }

    @Test
    fun statusSurfacesReadActualRunPlacementFromUiState() {
        // FirstRunSetupPanel.kt was merged into ChatEmptyState.kt, which no longer shows the
        // long run-status text; the placement strings live in the composer and top bar only.
        val source = listOf("ChatComposer.kt", "ChatTopBar.kt").joinToString("\n") { name ->
            listOf(
                File("src/main/java/com/bytedance/zgx/solin/ui/components/$name"),
                File("app/src/main/java/com/bytedance/zgx/solin/ui/components/$name"),
            ).first { it.isFile }.readText()
        }

        assertContainsAll(
            source,
            "actualPlacement = state.activeRunPlacement",
            "actualReason = state.activeRunPlacementReason",
            "activePlacementDisplayText(state.activeRunPlacement, state.activeRunPlacementReason)",
        )
    }

    @Test
    fun runDataReceiptDisplayNamesDestinationProtectionDeletionAndPersistence() {
        val text = runDataReceiptDisplayText(
            RunDataReceiptUiSummary(
                destination = "Remote",
                currentPromptPrivacy = "RemoteEligible",
                remoteHistoryCount = 3,
                localOnlyHistoryFilteredCount = 2,
                memoryHitCount = 1,
                memoryContextIncluded = false,
                deviceContextIncluded = false,
                imageAttachmentCount = 1,
                protectedSourceCount = 4,
                rawContentPersisted = false,
                protectedContentTypes = listOf("本地记忆", "设备上下文", "LocalOnly 历史"),
                deletableRecordTypes = listOf("对话消息", "Agent 轨迹", "显式记忆"),
            ),
        )

        assertContainsAll(
            text,
            "去向：远端",
            "远端历史：3",
            "隐私：可远程发送",
            "过滤仅本机历史：2",
            "保护：本地记忆、设备上下文、仅本机历史",
            "可删除：对话消息、Agent 轨迹、显式记忆",
            "原文持久化：否",
        )
    }

    @Test
    fun remoteModeDisclosureRowsNameOneTimeBoundaryReminder() {
        val text = remoteModeDisclosureDisplayRows(
            PendingRemoteModeDisclosure(
                remoteHost = "api.example.com",
                remoteModelName = "model-a",
                apiKeyConfigured = true,
                connectivityStatus = RemoteModelConnectivityStatus.Reachable,
                supportsVisionInput = true,
                isConfigured = true,
            ),
        ).joinToString("\n")

        assertContainsAll(
            text,
            "api.example.com",
            "model-a",
            "可远程发送的对话上下文",
            "当前输入",
            "主动选择的图片",
            "预览确认",
            "仅本机历史",
            "本地记忆",
            "设备上下文",
            "非图片附件正文或 OCR 摘录",
            "已配置 API Key",
            "连接状态：可达",
        )
    }

    @Test
    fun remoteSendDisclosureRowsNameDestinationAndProtectedData() {
        val text = remoteSendDisclosureDisplayRows(
            PendingRemoteSendDisclosure(
                prompt = "不要展示密钥",
                messagePrivacy = MessagePrivacy.RemoteEligible,
                remoteHost = "api.example.com",
                remoteModelName = "model-a",
                remoteHistoryCount = 2,
                localOnlyHistoryFilteredCount = 3,
                imageAttachmentCount = 1,
                protectedSourceCount = 3,
                apiKeyConfigured = true,
                connectivityStatus = RemoteModelConnectivityStatus.Reachable,
            ),
        ).joinToString("\n")

        assertContainsAll(
            text,
            "api.example.com",
            "model-a",
            "可远程发送历史 2 条",
            "图片 1 张",
            "图片字节会发往该远程地址",
            "仅本机历史 3 条",
            "本地记忆",
            "设备上下文",
            "记录或保留请求",
            "图片和响应",
        )
        assertContainsNone(text, "API Key", "连接状态", "不要展示密钥")
    }

    @Test
    fun remoteSendDisclosureSuppressSessionOnlyAppliesToOncePerSessionQuietText() {
        val quietTextDisclosure = PendingRemoteSendDisclosure(
            kind = RemoteSendDisclosureKind.CurrentInput,
            prompt = "普通问题",
            messagePrivacy = MessagePrivacy.RemoteEligible,
            remoteHost = "api.example.com",
            remoteModelName = "model-a",
            remoteHistoryCount = 0,
            localOnlyHistoryFilteredCount = 0,
            imageAttachmentCount = 0,
            protectedSourceCount = 0,
            apiKeyConfigured = true,
        )

        assertTrue(
            remoteSendDisclosureCanSuppressForSession(
                policy = RemoteSendDisclosurePolicy.OncePerSession,
                disclosure = quietTextDisclosure,
            ),
        )
        assertFalse(
            remoteSendDisclosureCanSuppressForSession(
                policy = RemoteSendDisclosurePolicy.EveryMessage,
                disclosure = quietTextDisclosure,
            ),
        )
        assertFalse(
            remoteSendDisclosureCanSuppressForSession(
                policy = RemoteSendDisclosurePolicy.OncePerSession,
                disclosure = quietTextDisclosure.copy(imageAttachmentCount = 1),
            ),
        )
        assertFalse(
            remoteSendDisclosureCanSuppressForSession(
                policy = RemoteSendDisclosurePolicy.OncePerSession,
                disclosure = quietTextDisclosure.copy(sensitiveHitCategories = listOf("疑似密钥")),
            ),
        )
    }

    @Test
    fun remoteSendDisclosureRowsNameToolContinuationWithoutCurrentInput() {
        val text = remoteSendDisclosureDisplayRows(
            PendingRemoteSendDisclosure(
                kind = RemoteSendDisclosureKind.ToolResultContinuation,
                prompt = "工具执行后续写提示",
                messagePrivacy = MessagePrivacy.RemoteEligible,
                remoteHost = "api.example.com",
                remoteModelName = "model-a",
                remoteHistoryCount = 1,
                localOnlyHistoryFilteredCount = 0,
                imageAttachmentCount = 0,
                protectedSourceCount = 0,
                apiKeyConfigured = true,
            ),
        ).joinToString("\n")

        assertContainsAll(text, "工具结果续写提示", "记录或保留请求", "本次没有图片字节发送")
        assertContainsNone(text, "当前输入", "图片字节会发往该远程地址", "图片和响应")
    }

    @Test
    fun remoteSendDisclosureRowsDoNotMentionImageBytesWhenNoImages() {
        val text = remoteSendDisclosureDisplayRows(
            PendingRemoteSendDisclosure(
                kind = RemoteSendDisclosureKind.CurrentInput,
                prompt = "普通问题",
                messagePrivacy = MessagePrivacy.RemoteEligible,
                remoteHost = "api.example.com",
                remoteModelName = "model-a",
                remoteHistoryCount = 0,
                localOnlyHistoryFilteredCount = 1,
                imageAttachmentCount = 0,
                protectedSourceCount = 0,
                apiKeyConfigured = false,
            ),
        ).joinToString("\n")

        assertContainsAll(text, "当前输入", "本次没有图片字节发送", "记录或保留请求和响应")
        assertContainsNone(text, "图片 0 张", "图片字节会发往该远程地址", "图片和响应", "API Key")
    }

    @Test
    fun remoteSendDisclosureRowsShowPromptPreviewWhenPresent() {
        val text = remoteSendDisclosureDisplayRows(
            PendingRemoteSendDisclosure(
                kind = RemoteSendDisclosureKind.CurrentInput,
                prompt = "帮我总结这段会议纪要",
                messagePrivacy = MessagePrivacy.RemoteEligible,
                remoteHost = "api.example.com",
                remoteModelName = "model-a",
                remoteHistoryCount = 0,
                localOnlyHistoryFilteredCount = 0,
                imageAttachmentCount = 0,
                protectedSourceCount = 0,
                apiKeyConfigured = true,
                promptPreview = "帮我总结这段会议纪要",
            ),
        ).joinToString("\n")

        assertContainsAll(text, "将发送内容预览：帮我总结这段会议纪要")
        assertContainsNone(text, "检测到疑似敏感内容")
    }

    @Test
    fun remoteSendDisclosureRowsExplainSensitiveHitCategories() {
        val text = remoteSendDisclosureDisplayRows(
            PendingRemoteSendDisclosure(
                kind = RemoteSendDisclosureKind.CurrentInput,
                prompt = "我的手机号是 13800001111",
                messagePrivacy = MessagePrivacy.RemoteEligible,
                remoteHost = "api.example.com",
                remoteModelName = "model-a",
                remoteHistoryCount = 0,
                localOnlyHistoryFilteredCount = 0,
                imageAttachmentCount = 0,
                protectedSourceCount = 0,
                apiKeyConfigured = true,
                forcedBySensitiveContent = true,
                promptPreview = "我的手机号是 13800001111",
                sensitiveHitCategories = listOf("疑似手机号/电话", "疑似个人身份信息"),
                sensitiveHitSnippets = listOf("13800001111"),
            ),
        ).joinToString("\n")

        assertContainsAll(text, "检测到疑似敏感内容", "疑似手机号/电话", "疑似个人身份信息", "命中片段：13800001111")
    }

    @Test
    fun runTimelineAndMemoryEvidenceDisplayUseSummariesWithoutRawPrivatePayload() {
        val timelineText = runTimelineItemDisplayText(
            RunTimelineItemUiSummary(
                id = "t1",
                label = "执行工具",
                state = "Executing",
                detail = "打开设置",
                privacyLabel = "仅本机",
                riskLabel = "需要确认",
            ),
        )
        val memoryText = memoryEvidenceDisplayText(
            MemoryEvidenceUiSummary(
                id = "m1",
                typeLabel = "偏好",
                recallLabel = "语义召回",
                reasonLabel = "alice@example.com password=secret",
                scoreLabel = "高相关",
            ),
        )

        assertContainsAll(timelineText, "执行工具", "打开设置", "仅本机", "需要确认")
        assertContainsAll(memoryText, "偏好", "语义召回", "高相关", "[redacted]")
        assertContainsNone(memoryText, "alice@example.com", "secret")
    }

    @Test
    fun runTimelineStepVisualsMarkDoneCurrentAndPendingSteps() {
        val running = listOf("Completed", "Completed", "Executing")
            .mapIndexed { index, state ->
                RunTimelineItemUiSummary(
                    id = "t$index",
                    label = "步骤 $index",
                    state = state,
                    detail = "",
                )
            }

        assertEquals(
            listOf(RunStepVisual.Done, RunStepVisual.Done, RunStepVisual.Current),
            runTimelineStepVisuals(running),
        )

        // A finished run leaves every step done and nothing amber.
        assertEquals(
            listOf(RunStepVisual.Done, RunStepVisual.Done, RunStepVisual.Done),
            runTimelineStepVisuals(running.map { it.copy(state = "Completed") }),
        )
        assertEquals(emptyList<RunStepVisual>(), runTimelineStepVisuals(emptyList()))
    }

    @Test
    fun recoveryActionEntryLabelsUndoAsUndoAndNamesOtherActions() {
        val undo = AgentRecoveryAction(
            sourceRequestId = "r1",
            sourceToolName = MobileActionFunctions.SCHEDULE_REMINDER,
            request = ToolRequest(
                toolName = MobileActionFunctions.CANCEL_REMINDER,
                arguments = mapOf("taskId" to "42"),
            ),
            draft = ActionDraft(
                functionName = MobileActionFunctions.CANCEL_REMINDER,
                title = "撤销提醒",
                summary = "将取消提醒任务：42",
                parameters = mapOf("taskId" to "42"),
            ),
        )
        val other = undo.copy(
            request = ToolRequest(toolName = MobileActionFunctions.DIAL_PHONE_NUMBER),
            draft = ActionDraft(
                functionName = MobileActionFunctions.DIAL_PHONE_NUMBER,
                title = "拨打号码",
                summary = "打开拨号盘",
                parameters = emptyMap(),
            ),
        )

        assertEquals("撤销", recoveryActionButtonLabel(undo))
        assertEquals("拨打号码", recoveryActionButtonLabel(other))
    }

    @Test
    fun publicWebEvidenceDisplayRowsShowSourcesWithoutRawQuery() {
        val rows = publicWebEvidenceDisplayRows(
            listOf(
                PublicWebEvidencePack(
                    query = "alice@example.com password=secret",
                    retrievedAt = "2026-07-02T10:00:00Z",
                    freshness = "current",
                    quality = "High",
                    items = listOf(
                        PublicWebEvidenceItem(
                            sourceId = "S1",
                            title = "Solin search quality update",
                            url = "https://example.com/private/path?email=alice@example.com",
                            snippet = "Search now shows source cards with citations.",
                            sourceName = "",
                            qualityLabel = "High",
                        ),
                    ),
                ),
            ),
        )

        val text = rows.joinToString("\n") { row -> publicWebSourceDisplayText(row) }

        assertContainsAll(text, "Solin search quality update", "example.com", "2026-07-02T10:00:00Z", "High", "Search now shows source cards")
        assertContainsNone(text, "alice@example.com", "password", "secret")
    }

    @Test
    fun publicWebEvidenceDisplayRowsRenumberSourcesAcrossPacksAfterDeduplication() {
        val rows = publicWebEvidenceDisplayRows(
            listOf(
                PublicWebEvidencePack(
                    query = "first query",
                    retrievedAt = "2026-07-02T10:00:00Z",
                    freshness = "current",
                    quality = "High",
                    items = listOf(
                        PublicWebEvidenceItem(
                            sourceId = "S1",
                            title = "First source",
                            url = "https://first.example.com/story",
                            snippet = "First snippet.",
                            sourceName = "First Example",
                            qualityLabel = "High",
                        ),
                    ),
                ),
                PublicWebEvidencePack(
                    query = "second query",
                    retrievedAt = "2026-07-02T10:01:00Z",
                    freshness = "current",
                    quality = "High",
                    items = listOf(
                        PublicWebEvidenceItem(
                            sourceId = "S1",
                            title = "Second source",
                            url = "https://second.example.com/story",
                            snippet = "Second snippet.",
                            sourceName = "Second Example",
                            qualityLabel = "High",
                        ),
                        PublicWebEvidenceItem(
                            sourceId = "S7",
                            title = "First source",
                            url = "https://first.example.com/duplicate",
                            snippet = "Duplicate snippet.",
                            sourceName = "First Example",
                            qualityLabel = "High",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("S1", "S2"), rows.map { row -> row.sourceId })
        assertEquals(listOf("First source", "Second source"), rows.map { row -> row.title })
    }

    @Test
    fun publicWebEvidenceDisplayRowsRedactRawJsonLookingFields() {
        val rows = publicWebEvidenceDisplayRows(
            listOf(
                PublicWebEvidencePack(
                    query = "private query token=secret",
                    retrievedAt = "2026-07-02T10:00:00Z",
                    freshness = "current",
                    quality = "Low",
                    items = listOf(
                        PublicWebEvidenceItem(
                            sourceId = "S1",
                            title = """{"resultsJson":"should-not-render"}""",
                            url = "https://news.example.com/story",
                            snippet = """{"query":"private query token=secret"}""",
                            sourceName = "News Example",
                            qualityLabel = "Low",
                        ),
                    ),
                ),
            ),
        )

        val text = rows.joinToString("\n") { row -> publicWebSourceDisplayText(row) }

        assertContainsAll(text, "News Example", "[redacted]")
        assertContainsNone(text, "resultsJson", "private query", "token=secret", """{"query"""")
    }

    @Test
    fun remoteSendDisclosureRowsOmitPreviewAndSensitiveRowsWhenAbsent() {
        val text = remoteSendDisclosureDisplayRows(
            PendingRemoteSendDisclosure(
                kind = RemoteSendDisclosureKind.ToolResultContinuation,
                prompt = "工具续写",
                messagePrivacy = MessagePrivacy.RemoteEligible,
                remoteHost = "api.example.com",
                remoteModelName = "model-a",
                remoteHistoryCount = 0,
                localOnlyHistoryFilteredCount = 0,
                imageAttachmentCount = 0,
                protectedSourceCount = 0,
                apiKeyConfigured = true,
            ),
        ).joinToString("\n")

        assertContainsNone(text, "将发送内容预览", "检测到疑似敏感内容")
    }

    @Test
    fun modelHealthDisplayShowsStructuredFallbackAndTimingMetrics() {
        val text = modelHealthDisplayText(
            ChatUiState(
                backend = BackendChoice.CPU,
                modelHealth = ModelHealth(
                    profileId = "chat-e2b",
                    state = ModelHealthState.FallbackActive,
                    backend = BackendChoice.CPU,
                    loadMs = 1234,
                    firstTokenMs = 456,
                    tokenCount = 42,
                    tokensPerSecond = 7.25,
                    fallbackBackend = BackendChoice.CPU,
                    failureReason = "GPU 初始化失败",
                ),
            ),
        )

        assertContainsAll(
            text,
            "健康：Fallback",
            "backend=CPU",
            "fallback=CPU",
            "load=1234ms",
            "first=456ms",
            "tokens=42",
            "speed=7.3 tok/s",
            "reason=GPU 初始化失败",
        )
    }

}
