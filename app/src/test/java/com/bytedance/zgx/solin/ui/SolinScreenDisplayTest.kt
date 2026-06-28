package com.bytedance.zgx.solin.ui

import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalModelTokenLimits
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.PendingRemoteSendDisclosure
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import com.bytedance.zgx.solin.RunDataReceiptUiSummary
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.capability.CapabilityMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolinScreenDisplayTest {
    @Test
    fun remoteAttachmentProtectionNoticeNamesVisionImagePath() {
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("远程图片"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("发送前逐次确认"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("视觉模型"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("非图片附件"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("分享文本"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("OCR 摘录"))
        assertTrue(REMOTE_ATTACHMENT_PROTECTION_NOTICE.contains("不会自动读取或发送"))
    }

    @Test
    fun trustBoundaryCopyNamesLocalRemotePermissionAndDeletionControls() {
        assertTrue(PRODUCT_POSITIONING_TEXT.contains("让 AI 住在手机里"))
        assertTrue(PRODUCT_POSITIONING_TEXT.contains("本地模型"))
        assertTrue(PRODUCT_POSITIONING_TEXT.contains("下载或导入"))
        assertTrue(PRODUCT_POSITIONING_TEXT.contains("远程多模态可选"))
        assertTrue(PRODUCT_POSITIONING_TEXT.contains("必须确认执行"))
        assertTrue(PRODUCT_POSITIONING_TEXT.contains("能力与信任中心"))
        assertTrue(PRODUCT_POSITIONING_SHORT_TEXT.contains("让 AI 住在手机里"))
        assertTrue(PRODUCT_HOME_TITLE_TEXT.contains("让 AI 住在手机里"))
        assertTrue(PRODUCT_HOME_DESCRIPTION_TEXT.contains("本地模型"))
        assertTrue(PRODUCT_HOME_DESCRIPTION_TEXT.contains("下载或导入"))
        assertTrue(PRODUCT_HOME_DESCRIPTION_TEXT.contains("远程多模态可选"))
        assertTrue(PRODUCT_HOME_DESCRIPTION_TEXT.contains("必须确认执行"))
        assertTrue(PRODUCT_HOME_DESCRIPTION_TEXT.contains("不读取本地数据"))
        assertTrue(PRODUCT_HOME_DESCRIPTION_TEXT.contains("不会自动发送远程请求"))
        val homeValueText = HOME_VALUE_PROPOSITIONS.joinToString("\n") { "${it.title}\n${it.body}" }
        assertEquals(3, HOME_VALUE_PROPOSITIONS.size)
        assertTrue(homeValueText.contains("本地可用"))
        assertTrue(homeValueText.contains("显式记忆"))
        assertTrue(homeValueText.contains("远程多模态可选"))
        assertTrue(homeValueText.contains("切换时提醒一次"))
        assertTrue(homeValueText.contains("动作确认执行"))
        assertTrue(homeValueText.contains("权限与风险"))
        assertTrue(MODEL_STARTUP_BANNER_TITLE.contains("模型未就绪"))
        assertTrue(MODEL_STARTUP_BANNER_DESCRIPTION.contains("配置远程模型"))
        assertTrue(MODEL_STARTUP_BANNER_DESCRIPTION.contains("离线问答"))
        assertTrue(MODEL_STARTUP_BANNER_DESCRIPTION.contains("设备动作"))
        assertTrue(MODEL_STARTUP_BANNER_DESCRIPTION.contains("确认"))
        assertTrue(HOME_CAPABILITY_PILLS.contains("离线问答"))
        assertTrue(HOME_CAPABILITY_PILLS.contains("显式记忆"))
        assertTrue(HOME_CAPABILITY_PILLS.contains("图片/文件"))
        assertTrue(HOME_CAPABILITY_PILLS.contains("确认动作"))
        assertTrue(LOCAL_SETUP_PANEL_TITLE.contains("离线基础问答"))
        assertTrue(LOCAL_SETUP_PANEL_DESCRIPTION.contains("留在本机"))
        assertTrue(LOCAL_SETUP_PANEL_DESCRIPTION.contains("配置远程模型"))
        assertTrue(MODEL_MANAGER_POSITIONING_TEXT.contains("下载或导入本地模型"))
        assertTrue(MODEL_MANAGER_POSITIONING_TEXT.contains("离线使用"))
        assertTrue(MODEL_MANAGER_POSITIONING_TEXT.contains("远程多模态可选"))
        assertTrue(MODEL_MANAGER_POSITIONING_TEXT.contains("切换远程会提醒"))
        assertTrue(PRODUCT_PROMPT_SUGGESTIONS.any { it.contains("留在本机") })
        assertTrue(PRODUCT_PROMPT_SUGGESTIONS.any { it.contains("切换远程模型") })
        assertTrue(PRODUCT_LOCAL_VALUE_TEXT.contains("基础问答"))
        assertTrue(PRODUCT_LOCAL_VALUE_TEXT.contains("主动选择的图片"))
        assertTrue(PRODUCT_REMOTE_VALUE_TEXT.contains("图片"))
        assertTrue(PRODUCT_ACTION_VALUE_TEXT.contains("确认或取消"))
        assertTrue(PRIVACY_POLICY_ENTRY_TEXT.contains("留在本机"))
        assertTrue(PRIVACY_POLICY_ENTRY_TEXT.contains("发送到远程"))
        assertTrue(PRIVACY_POLICY_ENTRY_TEXT.contains("确认后才执行"))
        assertTrue(REMOTE_MODE_DISCLOSURE_TEXT.contains("可远程发送的对话上下文"))
        assertTrue(REMOTE_MODE_DISCLOSURE_TEXT.contains("切换到远程时提醒一次"))
        assertTrue(MODEL_DOWNLOAD_RATIONALE_TEXT.contains("离线可用"))
        assertTrue(MODEL_DOWNLOAD_RATIONALE_TEXT.contains("2.4 GB"))
        assertTrue(MODEL_DOWNLOAD_RATIONALE_TEXT.contains("远程模型"))
        assertTrue(VOICE_INPUT_PRIVACY_DESCRIPTION.contains("系统语音转写"))
        assertTrue(VOICE_INPUT_PRIVACY_DESCRIPTION.contains("只进入输入框"))
        assertTrue(VOICE_INPUT_PRIVACY_DESCRIPTION.contains("不自动发送"))
        assertTrue(VOICE_INPUT_PRIVACY_DESCRIPTION.contains("不读取本地音频文件"))
        assertTrue(VOICE_INPUT_PRIVACY_DESCRIPTION.contains("开启前会先确认"))
        assertTrue(VOICE_INPUT_PERMISSION_DISCLOSURE_TITLE.contains("语音输入"))
        assertTrue(VOICE_INPUT_PERMISSION_DISCLOSURE_BODY.contains("Android 系统语音识别"))
        assertTrue(VOICE_INPUT_PERMISSION_DISCLOSURE_BODY.contains("不保存音频文件"))
        assertTrue(VOICE_INPUT_PERMISSION_DISCLOSURE_BODY.contains("确认后才会请求麦克风权限"))
        assertTrue(TRUST_LOCAL_BOUNDARY_TEXT.contains("留在本机"))
        assertTrue(TRUST_LOCAL_BOUNDARY_TEXT.contains("仅本机"))
        assertTrue(TRUST_REMOTE_BOUNDARY_TEXT.contains("对话上下文"))
        assertTrue(TRUST_REMOTE_BOUNDARY_TEXT.contains("逐次确认后随请求发送"))
        assertTrue(TRUST_REMOTE_BOUNDARY_TEXT.contains("OCR 摘录"))
        assertTrue(TRUST_PERMISSION_BOUNDARY_TEXT.contains("Accessibility 文本"))
        assertTrue(TRUST_PERMISSION_BOUNDARY_TEXT.contains("前台一次性确认"))
        assertTrue(trustDeletionBoundaryText(ChatUiState()).contains("清空长期记忆"))
        assertTrue(trustDeletionBoundaryText(ChatUiState()).contains("删除当前会话"))
        assertTrue(trustDeletionBoundaryText(ChatUiState()).contains("清除远程服务地址"))
        assertTrue(trustDeletionBoundaryText(ChatUiState()).contains("API Key"))
        assertTrue(SENSITIVE_CAPABILITY_DISCLOSURE_TEXT.contains("麦克风"))
        assertTrue(SENSITIVE_CAPABILITY_DISCLOSURE_TEXT.contains("Usage Stats"))
        assertTrue(SENSITIVE_CAPABILITY_DISCLOSURE_TEXT.contains("设备动作"))
        assertTrue(SENSITIVE_CAPABILITY_DISCLOSURE_TEXT.contains("取消、撤销或清除路径"))
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
        assertTrue(!allText.contains("sk-"))
        assertTrue(!allText.contains("Bearer "))
        assertTrue(!allText.contains("chat_input_and_remember_forget_commands"))
        assertTrue(!allText.contains("model_manager_privacy_tab"))
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
        assertTrue(!allText.contains("清理本地审计"))
        assertTrue(!allText.contains("删除审计"))
    }

    @Test
    fun modelPathGuidanceNamesLocalRemoteAndLightweightFallback() {
        val text = modelPathGuidanceRows(DEFAULT_CHAT_MODEL).joinToString("\n") {
            "${it.label}: ${it.body}"
        }

        assertTrue(text.contains("本地"))
        assertTrue(text.contains("离线问答"))
        assertTrue(text.contains("重新下载"))
        assertTrue(text.contains("空间不足"))
        assertTrue(text.contains("远程"))
        assertTrue(text.contains("切换到远程模型时会提醒一次"))
        assertTrue(text.contains("主动附加"))
        assertTrue(text.contains("轻量"))
        assertTrue(text.contains("没有更小的官方推荐聊天模型"))
        assertTrue(text.contains(".litertlm"))
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
        assertTrue(externalRows.joinToString().contains("外部 App"))
        assertTrue(externalRows.joinToString().contains("未确认结果前宣称已完成"))

        val localRows = actionDataBoundaryDisplayRows(MobileActionFunctions.READ_CLIPBOARD)
        assertTrue(localRows.joinToString().contains("本机内容"))
        assertTrue(localRows.joinToString().contains("仅留在本机"))
        assertTrue(localRows.joinToString().contains("不会自动发送给远程模型"))

        val reminderRows = actionDataBoundaryDisplayRows(MobileActionFunctions.SCHEDULE_REMINDER)
        assertTrue(reminderRows.joinToString().contains("后台任务"))
        assertTrue(reminderRows.joinToString().contains("默认留在本机"))
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

        assertTrue(status.contains("GPU"))
        assertTrue(status.contains("Token 8k"))
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

        assertTrue(status.contains("remote-test-model"))
        assertTrue(status.contains("远程"))
        assertTrue(!status.contains("上下文"))
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

        assertTrue(text.contains("去向：远端"))
        assertTrue(text.contains("远端历史：3"))
        assertTrue(text.contains("隐私：可远程发送"))
        assertTrue(text.contains("过滤仅本机历史：2"))
        assertTrue(text.contains("保护：本地记忆、设备上下文、仅本机历史"))
        assertTrue(text.contains("可删除：对话消息、Agent 轨迹、显式记忆"))
        assertTrue(text.contains("原文持久化：否"))
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

        assertTrue(text.contains("api.example.com"))
        assertTrue(text.contains("model-a"))
        assertTrue(text.contains("可远程发送的对话上下文"))
        assertTrue(text.contains("当前输入"))
        assertTrue(text.contains("主动选择的图片"))
        assertTrue(text.contains("预览确认"))
        assertTrue(text.contains("仅本机历史"))
        assertTrue(text.contains("本地记忆"))
        assertTrue(text.contains("设备上下文"))
        assertTrue(text.contains("非图片附件正文或 OCR 摘录"))
        assertTrue(text.contains("已配置 API Key"))
        assertTrue(text.contains("连接状态：可达"))
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

        assertTrue(text.contains("api.example.com"))
        assertTrue(text.contains("model-a"))
        assertTrue(text.contains("可远程发送历史 2 条"))
        assertTrue(text.contains("图片 1 张"))
        assertTrue(text.contains("图片字节会发往该远程地址"))
        assertTrue(text.contains("仅本机历史 3 条"))
        assertTrue(text.contains("本地记忆"))
        assertTrue(text.contains("设备上下文"))
        assertTrue(text.contains("记录或保留请求"))
        assertTrue(text.contains("图片和响应"))
        assertTrue(text.contains("已配置 API Key"))
        assertTrue(text.contains("连接状态：可达"))
        assertTrue(!text.contains("不要展示密钥"))
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

        assertTrue(text.contains("工具结果续写提示"))
        assertTrue(!text.contains("当前输入"))
        assertTrue(text.contains("记录或保留请求"))
        assertTrue(text.contains("本次没有图片字节发送"))
        assertTrue(!text.contains("图片字节会发往该远程地址"))
        assertTrue(!text.contains("图片和响应"))
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

        assertTrue(text.contains("当前输入"))
        assertTrue(text.contains("本次没有图片字节发送"))
        assertTrue(!text.contains("图片 0 张"))
        assertTrue(!text.contains("图片字节会发往该远程地址"))
        assertTrue(text.contains("记录或保留请求和响应"))
        assertTrue(!text.contains("图片和响应"))
        assertTrue(text.contains("未配置 API Key"))
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

        assertTrue(text.contains("将发送内容预览：帮我总结这段会议纪要"))
        assertTrue(!text.contains("检测到疑似敏感内容"))
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

        assertTrue(text.contains("检测到疑似敏感内容"))
        assertTrue(text.contains("疑似手机号/电话"))
        assertTrue(text.contains("疑似个人身份信息"))
        assertTrue(text.contains("命中片段：13800001111"))
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

        assertTrue(!text.contains("将发送内容预览"))
        assertTrue(!text.contains("检测到疑似敏感内容"))
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

        assertTrue(text.contains("健康：Fallback"))
        assertTrue(text.contains("backend=CPU"))
        assertTrue(text.contains("fallback=CPU"))
        assertTrue(text.contains("load=1234ms"))
        assertTrue(text.contains("first=456ms"))
        assertTrue(text.contains("tokens=42"))
        assertTrue(text.contains("speed=7.3 tok/s"))
        assertTrue(text.contains("reason=GPU 初始化失败"))
    }
}
