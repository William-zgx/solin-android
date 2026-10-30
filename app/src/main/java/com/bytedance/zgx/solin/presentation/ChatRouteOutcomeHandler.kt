package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.requiresUserConfirmation
import com.bytedance.zgx.solin.tool.ToolRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Applies the non-chat outcomes of an initial route (action draft / auto-run tool, rejected tool,
 * missing model). Moved verbatim from `ChatController.sendMessageInternal`.
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
            "请到模型管理安装本地 Chat 模型，或可选低资源动作模型。"
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
}
