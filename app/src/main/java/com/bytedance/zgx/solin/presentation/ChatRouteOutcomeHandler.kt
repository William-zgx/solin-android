package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.modelProfile
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.requiresUserConfirmation
import com.bytedance.zgx.solin.tool.ToolRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Applies the non-chat outcomes of an initial route (action draft / auto-run tool, rejected tool,
 * missing model) and the not-ready send attempt.
 */
internal class ChatRouteOutcomeHandler(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val sessionHost: PresentationSessionHost,
    private val executeToolRequestAfterRunIsExecuting: suspend (
        confirmation: PendingAgentConfirmation,
        request: ToolRequest,
    ) -> Unit,
) {
    suspend fun handleAction(
        route: AssistantRoute.Action,
        userMessage: ChatMessage,
        stateBeforeSend: ChatUiState,
    ) {
        val localUserMessage = userMessage.copy(privacy = MessagePrivacy.LocalOnly)
        val planningLabel = if (route.plannedByModel) {
            "动作规划模型"
        } else {
            "规则回退"
        }
        if (!route.requiresUserConfirmation) {
            val request = route.toolRequest ?: ToolRequest(
                toolName = route.draft.functionName,
                arguments = route.draft.parameters,
                reason = route.draft.summary,
            )
            val assistantMessage = ChatMessage(
                role = MessageRole.Assistant,
                text = "正在使用工具：${route.draft.title}",
                privacy = MessagePrivacy.LocalOnly,
            )
            sessionHost.persistMessagesAndRebuildMemory(
                messages = stateBeforeSend.messages + localUserMessage + assistantMessage,
                memoryUserMessage = localUserMessage,
            )
            uiState.update {
                it.clearedEvidence().copy(
                    isBusy = true,
                    isGenerating = false,
                    pendingConfirmation = null,
                    activeRunTimeline = sessionHost.activeRunTimelineFor(route.runId),
                    statusText = "工具执行中",
                )
            }
            executeToolRequestAfterRunIsExecuting(
                PendingAgentConfirmation(
                    runId = route.runId,
                    draft = route.draft,
                    toolRequest = route.toolRequest,
                    skillId = route.skillId,
                    plannedByModel = route.plannedByModel,
                    fallbackReason = route.fallbackReason,
                ),
                request,
            )
            return
        }
        val assistantMessage = ChatMessage(
            role = MessageRole.Assistant,
            text = "已准备本地动作草稿（$planningLabel）：${route.draft.summary}\n请确认后再执行。",
            privacy = MessagePrivacy.LocalOnly,
        )
        sessionHost.persistMessagesAndRebuildMemory(
            messages = stateBeforeSend.messages + localUserMessage + assistantMessage,
            memoryUserMessage = localUserMessage,
        )
        uiState.update {
            it.clearedEvidence().copy(
                isBusy = false,
                isGenerating = false,
                pendingConfirmation = PendingAgentConfirmation(
                    runId = route.runId,
                    draft = route.draft,
                    toolRequest = route.toolRequest,
                    skillId = route.skillId,
                    plannedByModel = route.plannedByModel,
                    fallbackReason = route.fallbackReason,
                ),
                activeRunTimeline = sessionHost.activeRunTimelineFor(route.runId),
                statusText = "动作草稿待确认 · $planningLabel",
            )
        }
    }

    fun handleToolRejected(
        route: AssistantRoute.ToolRejected,
        userMessage: ChatMessage,
        stateBeforeSend: ChatUiState,
    ) {
        val localUserMessage = userMessage.copy(privacy = MessagePrivacy.LocalOnly)
        val assistantMessage = ChatMessage(
            role = MessageRole.Assistant,
            text = "无法准备这个动作：${route.summary}",
            privacy = MessagePrivacy.LocalOnly,
        )
        sessionHost.persistMessagesAndRebuildMemory(
            messages = stateBeforeSend.messages + localUserMessage + assistantMessage,
            memoryUserMessage = localUserMessage,
        )
        uiState.update {
            it.clearedEvidence().copy(
                isBusy = false,
                isGenerating = false,
                statusText = "动作不可执行",
            )
        }
    }

    fun handleMissingModel(
        route: AssistantRoute.MissingModel,
        userMessage: ChatMessage,
        stateBeforeSend: ChatUiState,
        effectiveMessagePrivacy: MessagePrivacy,
    ) {
        val capabilityName = when (route.capability) {
            ModelCapability.Chat -> "对话模型"
            ModelCapability.MemoryEmbedding -> "记忆模型"
            ModelCapability.MobileAction -> "动作规划模型"
        }
        val installHint = if (route.capability == ModelCapability.MobileAction) {
            "请到模型管理安装支持动作规划的本地 Chat 模型。"
        } else {
            "请到模型管理安装基础能力包。"
        }
        val assistantMessage = ChatMessage(
            role = MessageRole.Assistant,
            text = "需要先安装$capabilityName，才能完成这个请求。$installHint",
            privacy = effectiveMessagePrivacy,
        )
        sessionHost.persistMessagesAndRebuildMemory(
            messages = stateBeforeSend.messages + userMessage + assistantMessage,
            memoryUserMessage = userMessage,
        )
        uiState.update {
            it.clearedEvidence().copy(
                isBusy = false,
                isGenerating = false,
                statusText = "缺少$capabilityName",
            )
        }
    }

    /** Send attempted before a model is ready; a missing remote config gets a local-only notice. */
    fun handleNotReady() {
        val state = uiState.value
        if (state.inferenceMode == InferenceMode.Remote && !state.remoteModelConfig.isConfigured) {
            val notice = "请先在模型管理中配置远程模型地址和模型名；我还没有发送你的内容。"
            val messages = if (state.messages.lastOrNull()?.text == notice) {
                state.messages
            } else {
                state.messages + ChatMessage(
                    role = MessageRole.Assistant,
                    text = notice,
                    privacy = MessagePrivacy.LocalOnly,
                )
            }
            sessionHost.replaceActiveSessionMessages(messages, persistNow = true)
            uiState.update {
                it.copy(
                    isReady = false,
                    pendingRemoteSendDisclosure = null,
                    statusText = "请配置远程模型",
                    modelHealth = ModelHealth(
                        profileId = it.remoteModelConfig.modelProfile().id,
                        state = ModelHealthState.LoadFailed,
                        failureReason = "远程模型未配置",
                    ),
                )
            }
            return
        }
        uiState.update {
            it.copy(
                statusText = if (state.inferenceMode == InferenceMode.Remote) {
                    "远程模型未就绪"
                } else {
                    "请先下载或导入本地模型"
                },
            )
        }
    }
}
