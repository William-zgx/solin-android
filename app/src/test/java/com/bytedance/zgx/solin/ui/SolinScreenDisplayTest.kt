package com.bytedance.zgx.solin.ui

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL
import com.bytedance.zgx.solin.InferenceMode
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
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.capability.CapabilityMatrix
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.ui.components.ACTION_SUMMARY_COLLAPSE_CHARS
import com.bytedance.zgx.solin.ui.components.actionDataBoundaryDisplayRows
import com.bytedance.zgx.solin.ui.components.actionParameterDisplayRows
import com.bytedance.zgx.solin.ui.components.actionTextDisplay
import com.bytedance.zgx.solin.ui.components.compactModelStatusShort
import com.bytedance.zgx.solin.ui.components.hasAnySavedValue
import com.bytedance.zgx.solin.ui.components.inferencePreferenceDescription
import com.bytedance.zgx.solin.ui.components.memoryEvidenceDisplayText
import com.bytedance.zgx.solin.ui.components.modelPathGuidanceRows
import com.bytedance.zgx.solin.ui.components.parseRemoteContextWindowTokens
import com.bytedance.zgx.solin.ui.components.publicWebEvidenceDisplayRows
import com.bytedance.zgx.solin.ui.components.publicWebSourceDisplayText
import com.bytedance.zgx.solin.ui.components.remoteModeDisclosureDisplayRows
import com.bytedance.zgx.solin.ui.components.remoteSendDisclosureCanSuppressForSession
import com.bytedance.zgx.solin.ui.components.remoteSendDisclosureDisplayRows
import com.bytedance.zgx.solin.ui.components.runTimelineItemDisplayText
import com.bytedance.zgx.solin.ui.components.selectableInferencePreferences
import com.bytedance.zgx.solin.ui.components.semanticMemoryIndexStatusText
import com.bytedance.zgx.solin.ui.components.shouldShowFirstRunSetupPanel
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
            "隐私优先的随身 AI 助手",
            "本地模型",
            "下载或导入",
            "远程多模态可选",
            "必须确认执行",
            "能力与信任中心",
        )
        assertContainsAll(PRODUCT_POSITIONING_SHORT_TEXT, "隐私优先的随身 AI 助手")
        assertContainsAll(PRODUCT_HOME_TITLE_TEXT, "隐私优先的随身 AI 助手")
        assertContainsAll(
            PRODUCT_HOME_DESCRIPTION_TEXT,
            "本地模型",
            "下载或导入",
            "远程多模态可选",
            "必须确认执行",
            "不读取本地数据",
            "不会自动发送远程请求",
        )
        val homeValueText = HOME_VALUE_PROPOSITIONS.joinToString("\n") { "${it.title}\n${it.body}" }
        assertEquals(3, HOME_VALUE_PROPOSITIONS.size)
        assertContainsAll(
            homeValueText,
            "本地可用",
            "显式记忆",
            "远程多模态可选",
            "切换时提醒一次",
            "动作确认执行",
            "权限与风险",
        )
        assertContainsAll(MODEL_STARTUP_BANNER_TITLE, "模型未就绪")
        assertContainsAll(MODEL_STARTUP_BANNER_DESCRIPTION, "配置远程模型", "离线问答", "设备动作", "确认")
        assertContainsAll(HOME_CAPABILITY_PILLS.joinToString("\n"), "离线问答", "显式记忆", "图片/文件", "确认动作")
        assertContainsAll(LOCAL_SETUP_PANEL_TITLE, "离线基础问答")
        assertContainsAll(LOCAL_SETUP_PANEL_DESCRIPTION, "留在本机", "配置远程模型")
        assertContainsAll(MODEL_MANAGER_POSITIONING_TEXT, "下载或导入本地模型", "离线使用", "远程多模态可选", "切换远程会提醒")
        assertTrue(PRODUCT_PROMPT_SUGGESTIONS.any { it.contains("留在本机") })
        assertTrue(PRODUCT_PROMPT_SUGGESTIONS.any { it.contains("切换远程模型") })
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
            "模型管理的隐私页",
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
    fun actionDataBoundaryNamesExternalLocalAndBackgroundDestinations() {
        val externalRows = actionDataBoundaryDisplayRows(MobileActionFunctions.SHARE_TEXT)
        assertContainsAll(externalRows.joinToString(), "外部 App", "未确认结果前宣称已完成")

        val localRows = actionDataBoundaryDisplayRows(MobileActionFunctions.READ_CLIPBOARD)
        assertContainsAll(localRows.joinToString(), "本机内容", "仅留在本机", "不会自动发送给远程模型")

        val reminderRows = actionDataBoundaryDisplayRows(MobileActionFunctions.SCHEDULE_REMINDER)
        assertContainsAll(reminderRows.joinToString(), "后台任务", "默认留在本机")
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
    fun firstRunSetupVisibilityUsesExplicitStateFlag() {
        assertFalse(ChatUiState(showFirstRunSetup = false).shouldShowFirstRunSetupPanel())
        assertTrue(ChatUiState(showFirstRunSetup = true).shouldShowFirstRunSetupPanel())
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
                state = busyAuto,
                activeRunPlacement = RunPlacement.Local,
                activeRunPlacementReason = PlacementReasonCode.AUTO_SIMPLE_LOCAL,
            ),
        )
        assertEquals(
            "自动→远程",
            compactModelStatusShort(
                state = busyAuto,
                activeRunPlacement = RunPlacement.Remote,
                activeRunPlacementReason = PlacementReasonCode.AUTO_COMPLEX_REMOTE,
            ),
        )
        assertEquals("自动·处理中", compactModelStatusShort(busyAuto, null, null))
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
    fun mainActivityPassesActualRunPlacementIntoSolinScreen() {
        val source = listOf(
            File("src/main/java/com/bytedance/zgx/solin/MainActivity.kt"),
            File("app/src/main/java/com/bytedance/zgx/solin/MainActivity.kt"),
        ).first { it.isFile }.readText()

        assertContainsAll(
            source,
            "activeRunPlacement = state.activeRunPlacement",
            "activeRunPlacementReason = state.activeRunPlacementReason",
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
