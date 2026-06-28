package com.bytedance.zgx.solin.capability

import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.tool.ConfirmationPolicy
import com.bytedance.zgx.solin.tool.RiskLevel
import com.bytedance.zgx.solin.tool.ToolCapability
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolResultContinuationPolicy
import com.bytedance.zgx.solin.tool.ToolSpec
import com.bytedance.zgx.solin.tool.isRemoteModelPlanningEligible

enum class CapabilityOwnerAgent {
    Coordinator,
    EdgeModel,
    Multimodal,
    AgentRuntime,
    Memory,
    TrustPrivacy,
    PerformanceQa,
}

enum class CapabilityPrivacyLevel {
    UserProvided,
    PublicEvidence,
    LocalEvidence,
    ExternalAction,
    BackgroundTask,
}

data class CapabilityDescriptor(
    val capabilityId: String,
    val entrypoint: String,
    val toolName: String?,
    val modelCapability: ModelCapability?,
    val privacyLevel: CapabilityPrivacyLevel,
    val requiresLocalModel: Boolean,
    val remoteEligible: Boolean,
    val confirmationPolicy: ConfirmationPolicy,
    val failureBehavior: String,
    val requiredTests: List<String>,
    val ownerAgent: CapabilityOwnerAgent,
)

data class SensitiveCapabilityDisclosure(
    val capabilityId: String,
    val displayName: String,
    val dataAccessed: String,
    val consentBoundary: String,
    val remoteBoundary: String,
    val revokeOrClearControl: String,
    val requiredTests: List<String>,
)

object CapabilityMatrix {
    const val productPositioning: String =
        "让 AI 住在手机里：本地模型可下载或导入，远程多模态可选，手机操作默认保守确认，低风险连续步骤可按设置执行。"
    const val targetUserJob: String =
        "在手机上处理私人日常问答、记忆、图片/文件输入、App 打开和低风险搜索交互，同时默认让本地上下文留在本机。"

    fun toolDescriptors(registry: ToolRegistry = ToolRegistry()): List<CapabilityDescriptor> =
        registry.specs().map { spec -> spec.toCapabilityDescriptor() }

    val nextStageMvpScenarioIds: List<String> = listOf(
        "local_private_qa_and_memory",
        "local_screen_clipboard_summary_share",
        "low_risk_app_search_control",
        "local_reminders_background_tasks",
        "remote_public_evidence",
        "trust_center_capability_review",
    )

    val nextStageMvpScenarioTitles: Map<String, String> = linkedMapOf(
        "local_private_qa_and_memory" to "本地私密问答与记忆",
        "local_screen_clipboard_summary_share" to "屏幕/剪贴板总结分享",
        "low_risk_app_search_control" to "低风险 App 搜索控制",
        "local_reminders_background_tasks" to "本地提醒与后台任务",
        "remote_public_evidence" to "远程公开证据查询",
        "trust_center_capability_review" to "能力与信任中心",
    ).also { titles ->
        require(titles.keys.toList() == nextStageMvpScenarioIds) {
            "Next-stage MVP scenario titles must match nextStageMvpScenarioIds"
        }
        require(titles.values.all { title -> title.isNotBlank() }) {
            "Next-stage MVP scenario titles must be non-blank"
        }
    }

    fun nextStageMvpScenarioTitle(capabilityId: String): String =
        requireNotNull(nextStageMvpScenarioTitles[capabilityId]) {
            "Missing next-stage MVP scenario title: $capabilityId"
        }

    val requiredBehaviorEvalBoundaries: List<String> = listOf(
        "public_evidence_multi_search_batch_allowed",
    )

    val productDescriptors: List<CapabilityDescriptor> =
        listOf(
            CapabilityDescriptor(
                capabilityId = "local_offline_chat",
                entrypoint = "chat_input",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = true,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "本地模型未就绪时停止生成并提示先准备模型。",
                requiredTests = listOf("SolinViewModelTest"),
                ownerAgent = CapabilityOwnerAgent.EdgeModel,
            ),
            CapabilityDescriptor(
                capabilityId = "explicit_memory",
                entrypoint = "remember_forget_commands",
                toolName = null,
                modelCapability = ModelCapability.MemoryEmbedding,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "删除失败时保留现有记忆；会话回忆只从用户消息重建，不索引助手输出，也不把记忆写入远端请求。",
                requiredTests = listOf("MemoryRepositoryTest", "MemoryQualityContractTest"),
                ownerAgent = CapabilityOwnerAgent.Memory,
            ),
            CapabilityDescriptor(
                capabilityId = "shared_file_text_input",
                entrypoint = "share_or_document_picker",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.UserProvided,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "远程模式保护分享文本和非图片附件；本地模式读取受限文本、PDF 扫描页 OCR 或元数据；已验证视觉本地模型可读取受限图片字节，不支持视觉时不自动 OCR。",
                requiredTests = listOf("SharedInputTest", "SolinViewModelTest", "MainActivitySharedIntentTest"),
                ownerAgent = CapabilityOwnerAgent.Multimodal,
            ),
            CapabilityDescriptor(
                capabilityId = "local_vision_image_input",
                entrypoint = "share_or_attachment_image",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.UserProvided,
                requiresLocalModel = true,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "仅已验证且声明支持视觉的本地模型可接收图片字节；每张图片限 8 MB，不写入 prompt、历史或审计；不支持或校验失败时直接提示不支持，不强制 OCR。",
                requiredTests = listOf(
                    "SolinViewModelTest",
                    "MainActivitySharedInputModeTest",
                    "SharedInputTest",
                    "LiteRtRuntimeConfigTest",
                ),
                ownerAgent = CapabilityOwnerAgent.Multimodal,
            ),
            CapabilityDescriptor(
                capabilityId = "remote_vision_image_input",
                entrypoint = "share_or_attachment_image",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.UserProvided,
                requiresLocalModel = false,
                remoteEligible = true,
                confirmationPolicy = ConfirmationPolicy.Required,
                failureBehavior = "图片只在远程发送预览确认后随请求发送；远程模型还必须显式启用图片输入并支持 OpenAI image_url 内容块，不支持图片或接口拒绝时直接提示不支持，不强制 OCR；远程视觉 prompt 不包含附件文件名、MIME、大小或 OCR。",
                requiredTests = listOf(
                    "RemoteChatRuntimeTest",
                    "SolinViewModelTest",
                    "MainActivitySharedInputModeTest",
                    "SharedInputTest",
                ),
                ownerAgent = CapabilityOwnerAgent.Multimodal,
            ),
            CapabilityDescriptor(
                capabilityId = "voice_transcript_input",
                entrypoint = "composer_voice_input",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.UserProvided,
                requiresLocalModel = false,
                remoteEligible = true,
                confirmationPolicy = ConfirmationPolicy.Required,
                failureBehavior = "语音识别失败或权限拒绝时只更新本地状态，不读取音频文件或自动发送消息。",
                requiredTests = listOf(
                    "SolinViewModelTest",
                    "MainActivitySmokeTest",
                    "SolinVoiceInputConsentUiTest",
                    "MainActivityVoicePermissionUiTest",
                ),
                ownerAgent = CapabilityOwnerAgent.Multimodal,
            ),
            CapabilityDescriptor(
                capabilityId = "confirmed_device_tools",
                entrypoint = "agent_tool_confirmation",
                toolName = null,
                modelCapability = ModelCapability.MobileAction,
                privacyLevel = CapabilityPrivacyLevel.ExternalAction,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.Required,
                failureBehavior = "默认保守确认；开启减少确认后仅低风险手机导航/搜索/点击/滚动可连续执行；发送、删除、支付、发布、敏感输入、权限授权和批量混合风险仍 fail-closed。",
                requiredTests = listOf("ToolRegistryTest", "SafetyPolicyTest", "AgentLoopRuntimeTest"),
                ownerAgent = CapabilityOwnerAgent.AgentRuntime,
            ),
            CapabilityDescriptor(
                capabilityId = "auditable_agent_trace",
                entrypoint = "agent_trace_and_audit_surfaces",
                toolName = null,
                modelCapability = null,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "损坏、过期或越界的 pending/trace 数据在恢复时清理或标记失败，不恢复敏感 payload。",
                requiredTests = listOf("AgentTraceStoreTest", "RunDataReceiptTraceTest", "ToolAuditRepositoryTest"),
                ownerAgent = CapabilityOwnerAgent.AgentRuntime,
            ),
            CapabilityDescriptor(
                capabilityId = "model_management",
                entrypoint = "model_manager",
                toolName = null,
                modelCapability = null,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "下载、导入、校验、加载或 fallback 失败时更新 ModelHealth，不把模型已下载误报为所有能力可用。",
                requiredTests = listOf("ModelCatalogTest", "SolinViewModelTest", "SolinScreenDisplayTest"),
                ownerAgent = CapabilityOwnerAgent.EdgeModel,
            ),
            CapabilityDescriptor(
                capabilityId = "run_data_receipt",
                entrypoint = "run_completion_receipt",
                toolName = null,
                modelCapability = null,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "回执只记录本地/远端边界、受保护来源和计数，不记录原始 prompt、文件内容或图片数据。",
                requiredTests = listOf("RunDataReceiptTraceTest", "SolinScreenDisplayTest"),
                ownerAgent = CapabilityOwnerAgent.TrustPrivacy,
            ),
            CapabilityDescriptor(
                capabilityId = "local_private_qa_and_memory",
                entrypoint = "chat_input_and_remember_forget_commands",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = true,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "本地问答、显式偏好/事实记忆和自动任务状态默认 LocalOnly；本地模型不可用时停止生成，记忆控制命令仍留在本机执行。",
                requiredTests = listOf("SolinViewModelTest", "MemoryRepositoryTest", "MemoryQualityContractTest"),
                ownerAgent = CapabilityOwnerAgent.Memory,
            ),
            CapabilityDescriptor(
                capabilityId = "local_screen_clipboard_summary_share",
                entrypoint = "clipboard_or_current_screen_summary_share_skill",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = true,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.Required,
                failureBehavior = "剪贴板或当前屏幕文本先单次确认读取并保持 LocalOnly，再由本地模型生成摘要；打开分享前必须二次确认，重启到 payload 确认点时 fail-closed。",
                requiredTests = listOf("AgentLoopRuntimeTest", "BuiltInSkillRuntimeTest", "MainActivitySkillUiTest", "SolinViewModelTest"),
                ownerAgent = CapabilityOwnerAgent.TrustPrivacy,
            ),
            CapabilityDescriptor(
                capabilityId = "low_risk_app_search_control",
                entrypoint = "app_search_skill_and_accessibility_control",
                toolName = null,
                modelCapability = ModelCapability.MobileAction,
                privacyLevel = CapabilityPrivacyLevel.ExternalAction,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.Required,
                failureBehavior = "打开 App 后只允许低风险 observe/tap/type/search/scroll/back/wait 连续步骤；发送、删除、支付、发布、敏感输入和权限变更仍回到确认或 fail-closed。",
                requiredTests = listOf("ToolRegistryTest", "ActionExecutorTest", "MainActivitySkillUiTest", "SolinAccessibilityServiceDeviceControlTest"),
                ownerAgent = CapabilityOwnerAgent.AgentRuntime,
            ),
            CapabilityDescriptor(
                capabilityId = "local_reminders_background_tasks",
                entrypoint = "schedule_reminder_and_background_tasks",
                toolName = null,
                modelCapability = ModelCapability.MobileAction,
                privacyLevel = CapabilityPrivacyLevel.BackgroundTask,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.Required,
                failureBehavior = "提醒和周期检查必须由用户确认后写入本地任务表；后台只做本地提醒巡检/通知，取消、过期、重启恢复和失败都以本地状态为准。",
                requiredTests = listOf("ScheduledTaskRepositoryTest", "ReminderAlarmReceiverTest", "BackgroundSkillSpecTest", "MainActivitySkillUiTest"),
                ownerAgent = CapabilityOwnerAgent.AgentRuntime,
            ),
            CapabilityDescriptor(
                capabilityId = "remote_public_evidence",
                entrypoint = "remote_tool_calls_web_search",
                toolName = null,
                modelCapability = ModelCapability.Chat,
                privacyLevel = CapabilityPrivacyLevel.PublicEvidence,
                requiresLocalModel = false,
                remoteEligible = true,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "远程模型只能免确认调用公开只读 evidence；多工具批次必须全部 public/read-only/side-effect-free，混入私密读取或动作工具时整批拒绝。",
                requiredTests = listOf("RemoteChatRuntimeTest", "AgentLoopRuntimeTest", "SolinViewModelTest", "WebSearchProviderTest"),
                ownerAgent = CapabilityOwnerAgent.AgentRuntime,
            ),
            CapabilityDescriptor(
                capabilityId = "trust_center_capability_review",
                entrypoint = "model_manager_privacy_tab",
                toolName = null,
                modelCapability = null,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "能力与信任中心只展示 CapabilityMatrix、脱敏审计摘要、权限边界和人工发布事项；不展示原始 prompt、工具参数、截图、剪贴板或 API Key。",
                requiredTests = listOf("CapabilityMatrixDocumentationTest", "SolinScreenDisplayTest", "ToolAuditRepositoryTest"),
                ownerAgent = CapabilityOwnerAgent.TrustPrivacy,
            ),
            CapabilityDescriptor(
                capabilityId = "release_gate",
                entrypoint = "scripts/verify_release_gate.sh",
                toolName = null,
                modelCapability = null,
                privacyLevel = CapabilityPrivacyLevel.LocalEvidence,
                requiresLocalModel = false,
                remoteEligible = false,
                confirmationPolicy = ConfirmationPolicy.NotRequired,
                failureBehavior = "缺少 perf baseline、签名、AAB、审批、验证记录或 artifact 绑定时 release gate 失败。",
                requiredTests = listOf(
                    "CapabilityMatrixDocumentationTest",
                    "AgentCoreDocumentationTest",
                    "ModelManifestDocumentationTest",
                ),
                ownerAgent = CapabilityOwnerAgent.PerformanceQa,
            ),
        )

    val nextStageMvpDescriptors: List<CapabilityDescriptor>
        get() {
            val descriptorsById = productDescriptors.associateBy { descriptor -> descriptor.capabilityId }
            return nextStageMvpScenarioIds.map { capabilityId ->
                requireNotNull(descriptorsById[capabilityId]) {
                    "Missing next-stage MVP capability descriptor: $capabilityId"
                }
            }
        }

    val sensitiveCapabilityDisclosures: List<SensitiveCapabilityDisclosure> =
        listOf(
            SensitiveCapabilityDisclosure(
                capabilityId = "remote_model_send",
                displayName = "远程模型发送",
                dataAccessed = "当前输入、可远程发送历史和用户主动附加的图片；不包含本地记忆、设备上下文或 LocalOnly 历史。",
                consentBoundary = "切换远程模型并点击发送后展示远程发送预览；确认前不请求远程接口。",
                remoteBoundary = "远程请求只发往用户配置的 HTTPS 或本机调试 endpoint；图片字节只有在主动附加、配置显式启用图片输入且接口支持 OpenAI image_url 视觉输入时发送。",
                revokeOrClearControl = "可取消发送预览、清除远程服务地址/模型名/API Key，并删除会话记录。",
                requiredTests = listOf("SolinViewModelTest", "SolinScreenDisplayTest", "RemoteChatRuntimeTest"),
            ),
            SensitiveCapabilityDisclosure(
                capabilityId = "voice_transcript_input",
                displayName = "语音输入和麦克风",
                dataAccessed = "实时麦克风音频交由 Android 系统语音识别服务处理；Solin不读取本地音频文件、不保存音频文件，转写结果只进入输入框且不自动发送。",
                consentBoundary = "用户点击语音按钮后必须先在 App 内同意语音输入说明；确认后才请求麦克风权限或开始收音，发送仍需要用户显式点击。",
                remoteBoundary = "转写文本只有在用户确认远程发送预览后才可进入远程请求。",
                revokeOrClearControl = "可取消语音输入、删除输入框草稿，并在 Android 设置中撤销麦克风权限。",
                requiredTests = listOf(
                    "SolinViewModelTest",
                    "SolinScreenDisplayTest",
                    "MainActivityAdaptiveUiTest",
                    "SolinVoiceInputConsentUiTest",
                    "MainActivityVoicePermissionUiTest",
                ),
            ),
            SensitiveCapabilityDisclosure(
                capabilityId = "share_and_file_picker_input",
                displayName = "分享和文件选择",
                dataAccessed = "用户通过分享入口或系统文件选择器主动提供的文本、文件元数据、受限文本摘录，以及已验证本地视觉模型可使用的受限图片字节。",
                consentBoundary = "仅处理用户主动分享或通过系统文件选择器确认的内容；本地模式才读取受支持文本/PDF/OCR 摘录；已验证本地视觉模型可在发送时读取受限图片字节，不支持视觉时不自动 OCR。",
                remoteBoundary = "远程模式保护分享文本、非图片附件、文本摘录和 OCR 摘录；图片只有在远程模式、视觉开启、接口支持 OpenAI image_url 且用户确认发送后才走远程视觉路径；本地视觉图片字节不进入远程请求。",
                revokeOrClearControl = "可取消草稿、删除当前会话，并通过系统 picker/分享入口重新选择范围。",
                requiredTests = listOf(
                    "SharedInputTest",
                    "MainActivitySharedIntentTest",
                    "SolinScreenDisplayTest",
                    "MainActivityAdaptiveUiTest",
                ),
            ),
            SensitiveCapabilityDisclosure(
                capabilityId = "confirmed_device_actions",
                displayName = "设备动作和外部 App",
                dataAccessed = "用户请求、动作草稿、目标 App/链接/提醒参数、屏幕状态摘要、动作后结构化观测和外部打开结果；不在未确认结果前宣称高风险动作完成。",
                consentBoundary = "默认保守确认；中高风险设备动作、发送、删除、支付、发布、敏感输入、权限授权、后台提醒和批量混合风险都必须先确认；低风险 App 搜索可按设置连续执行。",
                remoteBoundary = "设备动作结果和私密参数默认 LocalOnly，不作为远程模型自动上下文；只读公共 evidence 工具才可进入远程规划。",
                revokeOrClearControl = "可取消动作确认、标记外部操作未完成、撤销可撤销提醒，并删除相关会话记录。",
                requiredTests = listOf("AgentLoopRuntimeTest", "SafetyPolicyTest", "MainActivitySkillUiTest"),
            ),
            SensitiveCapabilityDisclosure(
                capabilityId = "contacts_calendar_reads",
                displayName = "联系人和日历忙闲",
                dataAccessed = "联系人名称/号码匹配和日历忙闲时间段；不读取日历标题、地点或参与人。",
                consentBoundary = "工具确认后才请求 READ_CONTACTS 或 READ_CALENDAR；权限拒绝会作为结构化失败返回。",
                remoteBoundary = "读取结果标记为 LocalOnly，要求本地模型续写，不进入远程模型规划或远程历史。",
                revokeOrClearControl = "可取消工具确认、在 Android 设置撤销联系人/日历权限，并删除相关会话记录；审计仅保留脱敏摘要并按保留策略裁剪。",
                requiredTests = listOf(
                    "AgentRuntimePermissionPolicyTest",
                    "ToolRegistryTest",
                    "CalendarAvailabilityProviderTest",
                    "MainActivityRuntimePermissionUiTest",
                ),
            ),
            SensitiveCapabilityDisclosure(
                capabilityId = "media_and_recent_ocr",
                displayName = "媒体、最近图片和截图 OCR",
                dataAccessed = "用户授权的图片/视频媒体范围、最近截图或最近图片的 OCR 摘录；不持久化原始像素。",
                consentBoundary = "显式 OCR 工具确认后才请求媒体权限或 selected visual media；普通图片输入不强制 OCR。",
                remoteBoundary = "OCR 摘录是 LocalOnly；远程视觉图片发送与 OCR 工具分离。",
                revokeOrClearControl = "可取消工具确认、撤销媒体权限、删除相关会话记录，并重新选择 selected visual media 范围；审计仅保留脱敏摘要并按保留策略裁剪。",
                requiredTests = listOf(
                    "AgentRuntimePermissionPolicyTest",
                    "ToolRegistryTest",
                    "AgentLoopRuntimeTest",
                    "MainActivityRuntimePermissionUiTest",
                ),
            ),
            SensitiveCapabilityDisclosure(
                capabilityId = "usage_stats_foreground_app",
                displayName = "Usage Stats 前台应用估计",
                dataAccessed = "前台应用包名/应用名估计；不读取屏幕内容、输入内容或使用历史详情。",
                consentBoundary = "用户确认工具并授予系统 Usage Access 后才可读取；结果标注为 UsageStats estimate。",
                remoteBoundary = "前台应用结果是 LocalOnly 设备上下文，不自动发送到远程模型。",
                revokeOrClearControl = "可取消工具确认，并通过使用情况访问权限设置撤销授权。",
                requiredTests = listOf("AgentRuntimePermissionPolicyTest", "ActionPlannerTest", "MainActivitySpecialAccessUiTest"),
            ),
            SensitiveCapabilityDisclosure(
                capabilityId = "accessibility_current_screen_text",
                displayName = "Accessibility 当前屏幕文本",
                dataAccessed = "当前 active window 暴露的 Accessibility 文本节点；不是截图、像素读取或视觉理解。",
                consentBoundary = "用户打开系统无障碍服务并确认单次工具读取后才处理当前屏幕文本。",
                remoteBoundary = "Accessibility 文本标记为 LocalOnly，只进入本地续写和审计边界，不进入远程模型。",
                revokeOrClearControl = "可取消工具确认，在系统无障碍设置关闭服务，并删除相关会话记录；审计仅保留脱敏摘要并按保留策略裁剪。",
                requiredTests = listOf(
                    "ToolRegistryTest",
                    "AgentLoopRuntimeTest",
                    "MainActivitySkillUiTest",
                    "MainActivitySpecialAccessUiTest",
                ),
            ),
            SensitiveCapabilityDisclosure(
                capabilityId = "media_projection_screenshot_ocr",
                displayName = "当前屏幕截图 OCR",
                dataAccessed = "前台一次性 MediaProjection 同意后的当前屏幕截图 OCR 文字；可包含临时 Accessibility 节点、bounds 与 OCR bounds 融合出的 LocalOnly 结构化 observation；不把原始截图作为长期记录。",
                consentBoundary = "每次当前屏幕截图 OCR 都需要前台 MediaProjection 同意和工具确认；取消即不执行。",
                remoteBoundary = "截图 OCR 结果是 LocalOnly，不自动发送给远程模型。",
                revokeOrClearControl = "可拒绝 MediaProjection 弹窗、取消工具确认，并删除相关会话记录；审计仅保留脱敏摘要并按保留策略裁剪。",
                requiredTests = listOf("AgentRuntimePermissionPolicyTest", "AgentLoopRuntimeTest", "MainActivitySkillUiTest"),
            ),
        )

    fun allDescriptors(registry: ToolRegistry = ToolRegistry()): List<CapabilityDescriptor> =
        productDescriptors + toolDescriptors(registry)
}

private fun ToolSpec.toCapabilityDescriptor(): CapabilityDescriptor {
    val privacyLevel = when {
        resultContinuationPolicy == ToolResultContinuationPolicy.PublicEvidence ->
            CapabilityPrivacyLevel.PublicEvidence

        resultContinuationPolicy == ToolResultContinuationPolicy.LocalEvidence ||
            privateOutputKeys.isNotEmpty() ->
            CapabilityPrivacyLevel.LocalEvidence

        capability == ToolCapability.BackgroundTask -> CapabilityPrivacyLevel.BackgroundTask
        else -> CapabilityPrivacyLevel.ExternalAction
    }
    return CapabilityDescriptor(
        capabilityId = "tool_$name",
        entrypoint = "tool_registry",
        toolName = name,
        modelCapability = if (resultContinuationPolicy == ToolResultContinuationPolicy.PublicEvidence) {
            ModelCapability.Chat
        } else {
            ModelCapability.MobileAction
        },
        privacyLevel = privacyLevel,
        requiresLocalModel = privacyLevel == CapabilityPrivacyLevel.LocalEvidence,
        remoteEligible = isRemoteModelPlanningEligible(),
        confirmationPolicy = confirmationPolicy,
        failureBehavior = if (riskLevel == RiskLevel.LowReadOnly) {
            "返回失败摘要并阻止无效结果进入模型上下文。"
        } else {
            "拒绝、取消或权限失败时不执行外部动作。"
        },
        requiredTests = listOf("ToolSchemaContractTest", "SafetyPolicyTest", "AgentLoopRuntimeTest"),
        ownerAgent = CapabilityOwnerAgent.AgentRuntime,
    )
}
