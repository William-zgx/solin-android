package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.orchestration.AgentModelObservationResult
import com.bytedance.zgx.solin.orchestration.AgentObservationDecision
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.orchestration.requiresUserConfirmation
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** End of a streamed chat generation, extracted from `ChatController.sendMessageInternal`. */
internal class ChatGenerationCompletion(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val sessionHost: PresentationSessionHost,
    private val assistantOrchestrator: AssistantRouter,
    private val runtime: LiteRtRuntime,
    private val executePublicEvidenceToolBatchAfterRunIsExecuting: suspend (
        runId: String,
        plans: List<AgentPlan.UseTool>,
    ) -> Unit,
    private val handleNextToolPlan: (runId: String, plan: AgentPlan.UseTool, pendingStatusText: String) -> Unit,
    private val continueAfterToolObservation: (
        runId: String?,
        promptForModel: String,
        responsePrivacy: MessagePrivacy,
        remoteToolScope: RemoteToolScope,
    ) -> Unit,
) {
    /**
     * Finishes a chat generation whose stream completed without an output-quality stop: hands a
     * remote tool call / tool batch or a model-planned next tool to the tool flow, otherwise
     * records the model result and settles the UI. Moved verbatim from `sendMessageInternal`.
     */
    suspend fun complete(
        route: AssistantRoute.Chat,
        observation: AgentModelObservationResult?,
        partial: StringBuilder,
        useRemoteModel: Boolean,
        includePrivateLocalContext: Boolean,
    ) {
        if (observation != null) {
            val toolBatch =
                (observation.decision as? AgentObservationDecision.PlanToolBatch)?.plans
            if (toolBatch != null) {
                val assistantText = buildString {
                    if (partial.isNotBlank()) {
                        append(partial)
                        append("\n\n")
                    }
                    append("正在并行使用工具：${toolBatch.batchToolTitle()}")
                }
                uiState.updateLastAssistantLocalOnly(assistantText)
                sessionHost.persistActiveSessionFromUi()
                executePublicEvidenceToolBatchAfterRunIsExecuting(observation.run.id, toolBatch)
                return
            }
            val nextToolPlan =
                (observation.decision as? AgentObservationDecision.PlanNextTool)?.plan
            if (nextToolPlan != null) {
                val assistantText = buildString {
                    if (partial.isNotBlank()) {
                        append(partial)
                        append("\n\n")
                    }
                    if (nextToolPlan.requiresUserConfirmation()) {
                        append("已准备远程动作草稿：${nextToolPlan.draft.title}\n请确认后再执行。")
                    } else {
                        append("正在使用工具：${nextToolPlan.draft.title}")
                    }
                }
                uiState.updateLastAssistantLocalOnly(assistantText)
                sessionHost.persistActiveSessionFromUi()
                handleNextToolPlan(observation.run.id, nextToolPlan, "动作草稿待确认 · 远程模型")
                return
            }
            uiState.updateLastAssistantLocalOnly(
                "无法准备这个动作：${(observation.decision as? AgentObservationDecision.Fail)?.reason.orEmpty()}",
            )
            sessionHost.persistActiveSessionFromUi()
            sessionHost.rebuildMemoryIndex()
            uiState.update {
                it.copy(
                    isBusy = false,
                    isGenerating = false,
                    isReady = true,
                    auditEvents = sessionHost.loadAuditEvents(),
                    agentTraceRuns = sessionHost.loadAgentTraceRuns(),
                    activeRunTimeline = sessionHost.activeRunTimelineFor(route.runId),
                    statusText = "动作不可执行",
                )
            }
            return
        } else {
            if (partial.isBlank()) {
                uiState.updateLastAssistant("没有生成内容")
            } else if (!useRemoteModel) {
                uiState.updateLastAssistantStats(
                    runCatching { runtime.lastGenerationStats() }.getOrNull(),
                )
            }
            route.runId?.let { runId ->
                val modelObservation = assistantOrchestrator.observeModelResult(
                    runId = runId,
                    text = partial.toString(),
                    allowInlineToolCalls = !useRemoteModel,
                )
                val nextToolPlan =
                    (modelObservation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
                if (nextToolPlan != null) {
                    val assistantText = if (nextToolPlan.requiresUserConfirmation()) {
                        "已准备模型动作草稿：${nextToolPlan.draft.title}\n请确认后再执行。"
                    } else {
                        "正在使用工具：${nextToolPlan.draft.title}"
                    }
                    uiState.updateLastAssistantLocalOnly(assistantText)
                    sessionHost.persistActiveSessionFromUi()
                    handleNextToolPlan(modelObservation.run.id, nextToolPlan, "动作草稿待确认 · 本地模型")
                    return
                }
                if (modelObservation?.decision is AgentObservationDecision.Fail) {
                    uiState.updateLastAssistantLocalOnly(
                        "无法准备这个动作：${modelObservation.decision.reason}",
                    )
                    sessionHost.persistActiveSessionFromUi()
                    sessionHost.rebuildMemoryIndex()
                    uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            isReady = true,
                            auditEvents = sessionHost.loadAuditEvents(),
                            agentTraceRuns = sessionHost.loadAgentTraceRuns(),
                            activeRunTimeline = sessionHost.activeRunTimelineFor(route.runId),
                            statusText = "动作不可执行",
                        )
                    }
                    return
                }
                val observedForContinuation = modelObservation
                val continuationPrompt = observedForContinuation?.continuationPromptForModel
                if (observedForContinuation?.decision is AgentObservationDecision.ContinueWithModel &&
                    continuationPrompt != null
                ) {
                    uiState.updateLastAssistant("正在根据公开来源补充引用…")
                    sessionHost.persistActiveSessionFromUi()
                    continueAfterToolObservation(
                        observedForContinuation.run.id,
                        continuationPrompt,
                        MessagePrivacy.RemoteEligible,
                        observedForContinuation.continuationRemoteToolScope,
                    )
                    return
                }
            }
        }
        sessionHost.persistActiveSessionFromUi()
        sessionHost.rebuildMemoryIndex()
        uiState.update {
            it.copy(
                isBusy = false,
                isGenerating = false,
                isReady = true,
                activeRunTimeline = sessionHost.activeRunTimelineFor(route.runId),
                activePublicWebEvidence = sessionHost.activePublicWebEvidenceFor(route.runId),
                activeMemoryEvidence = sessionHost.activeMemoryEvidenceFor(
                    memoryHits = route.memoryHits,
                    includePrivateLocalContext = includePrivateLocalContext,
                ),
                statusText = if (useRemoteModel) {
                    "就绪 · 远程"
                } else {
                    "就绪 · ${it.backend.label()}"
                },
            )
        }
    }
}
