package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.AuditEventSummary
import com.bytedance.zgx.solin.BackgroundTaskSummary
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.SpecialAccessRequirement
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.MobileActionFunctions
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_TOOL
import com.bytedance.zgx.solin.matchesExecution
import com.bytedance.zgx.solin.multimodal.CurrentScreenshotOcrContract
import com.bytedance.zgx.solin.orchestration.ActiveRunPlacementPermit
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import com.bytedance.zgx.solin.orchestration.AgentObservationDecision
import com.bytedance.zgx.solin.orchestration.AgentObservationResult
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.BoundRunContinuationResolution
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.PendingExternalOutcomeSnapshot
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.orchestration.requiresUserConfirmation
import com.bytedance.zgx.solin.runtimePermissionDenialSummary
import com.bytedance.zgx.solin.specialAccessDenialSummary
import com.bytedance.zgx.solin.tool.SafetyPolicyToolExecutionAuthorizer
import com.bytedance.zgx.solin.tool.TimeoutToolExecutionBoundary
import com.bytedance.zgx.solin.tool.ToolErrorCode
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.ToolRequest
import com.bytedance.zgx.solin.tool.ToolResult
import com.bytedance.zgx.solin.tool.failed
import com.bytedance.zgx.solin.tool.isEligibleForParallelBatch
import com.bytedance.zgx.solin.tool.isUnverifiedExternalLaunch
import com.bytedance.zgx.solin.tool.unverifiedExternalLaunchSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val PUBLIC_EVIDENCE_BATCH_TOOL_NAME = "public_evidence_batch"

/**
 * Owns tool confirmation, execution, public-evidence batching, and pending-action restore.
 *
 * Extracted from [com.bytedance.zgx.solin.SolinViewModel] (Wave 3 Track C3b). The ViewModel keeps
 * thin public wrappers and owns generation / remote-continuation coupling via callbacks.
 */
class ToolExecutionController(
    private val assistantOrchestrator: AssistantRouter,
    actionExecutor: ToolExecutor,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val uiState: MutableStateFlow<ChatUiState>,
    private val ioDispatcher: CoroutineDispatcher,
    private val isGenerationJobActive: () -> Boolean,
    private val launchToolGenerationJob: (runId: String?, block: suspend () -> Unit) -> Unit,
    private val continueAfterToolObservation: (
        runId: String?,
        promptForModel: String,
        responsePrivacy: MessagePrivacy,
        remoteToolScope: RemoteToolScope,
        remoteSendConfirmed: Boolean,
    ) -> Unit,
    private val dispatchPersistenceWorkIfNeeded: (work: () -> Unit) -> Boolean,
    private val replaceActiveSessionMessages: (messages: List<ChatMessage>, persistNow: Boolean) -> Unit,
    private val persistActiveSessionFromUi: () -> Unit,
    private val rebuildMemoryIndex: () -> Unit,
    private val syncTaskStateMemories: () -> Unit,
    private val loadAuditEvents: () -> List<AuditEventSummary>,
    private val loadAgentTraceRuns: () -> List<AgentTraceRunUiSummary>,
    private val activeRunTimelineFor: (String?) -> List<RunTimelineItemUiSummary>,
    private val activePublicWebEvidenceFor: (String?) -> List<PublicWebEvidencePack>,
    private val loadBackgroundTasks: () -> List<BackgroundTaskSummary>,
    private val loadBackgroundTaskHistory: () -> List<BackgroundTaskSummary>,
    private val loadPeriodicCheckPolicy: () -> PeriodicCheckPolicySummary,
    private val loadLongTermMemories: () -> List<LongTermMemorySummary>,
    private val activeSessionId: () -> String,
    private val activeRunPlacement: (String) -> ActiveRunPlacementPermit? = { null },
) {
    private val toolExecutionBoundary = TimeoutToolExecutionBoundary(
        executor = actionExecutor,
        dispatcher = ioDispatcher,
        publicEvidenceBatchRequestValidator = toolRegistry::validatePublicEvidenceBatchRequest,
        executionAuthorizer = SafetyPolicyToolExecutionAuthorizer(toolRegistry),
    )

    fun requestRecoveryActionConfirmation(action: AgentRecoveryAction) {
        if (dispatchPersistenceWorkIfNeeded { requestRecoveryActionConfirmation(action) }) return
        val state = uiState.value
        if (state.pendingConfirmation != null) {
            uiState.update {
                it.copy(statusText = "请先确认或取消待执行动作")
            }
            return
        }
        if (state.isBusy || isGenerationJobActive()) {
            uiState.update {
                it.copy(statusText = "请稍后再撤销提醒")
            }
            return
        }
        if (state.latestRecoveryAction != action) {
            uiState.update {
                it.copy(statusText = "撤销入口已过期")
            }
            return
        }
        when (val route = assistantOrchestrator.requestRecoveryAction(action, state.activeSessionId)) {
            is AssistantRoute.Action -> {
                val runId = route.runId
                val request = route.toolRequest
                if (runId == null || request == null) {
                    uiState.update {
                        it.copy(
                            latestRecoveryAction = null,
                            statusText = "撤销动作不可执行",
                        )
                    }
                    return
                }
                uiState.update {
                    it.copy(
                        pendingConfirmation = PendingAgentConfirmation(
                            runId = runId,
                            draft = route.draft,
                            toolRequest = request,
                            skillId = route.skillId,
                            plannedByModel = route.plannedByModel,
                            fallbackReason = route.fallbackReason,
                        ),
                        latestRecoveryAction = null,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        statusText = "撤销提醒待确认",
                    )
                }
            }

            is AssistantRoute.ToolRejected -> {
                uiState.update {
                    it.copy(
                        latestRecoveryAction = null,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        statusText = "撤销动作不可执行：${route.summary}",
                    )
                }
            }

            else -> {
                uiState.update {
                    it.copy(statusText = "撤销动作不可执行")
                }
            }
        }
    }

    fun confirmAgentConfirmation(confirmation: PendingAgentConfirmation) {
        if (dispatchPersistenceWorkIfNeeded { confirmAgentConfirmation(confirmation) }) return
        val pendingConfirmation = uiState.value.pendingConfirmation
        if (pendingConfirmation == null || !pendingConfirmation.matchesExecution(confirmation)) {
            uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = pendingConfirmation.toolRequest ?: ToolRequest(
            toolName = pendingConfirmation.draft.functionName,
            arguments = pendingConfirmation.draft.parameters,
            reason = pendingConfirmation.draft.summary,
        )
        val confirmedRun = try {
            confirmation.runId?.let { runId ->
                assistantOrchestrator.confirmToolRequest(runId, request.id)
            }
        } catch (_: Exception) {
            uiState.update {
                it.copy(
                    pendingConfirmation = pendingConfirmation,
                    isBusy = false,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                    statusText = "工具确认失败，未执行动作。",
                )
            }
            return
        }
        if (confirmation.runId != null && confirmedRun?.state != AgentRunState.ExecutingTool) {
            replaceActiveSessionMessages(
                uiState.value.messages + ChatMessage(
                    MessageRole.Assistant,
                    "工具确认失败，未执行动作。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                true,
            )
            uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    isBusy = false,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                    statusText = "工具未执行",
                )
            }
            return
        }
        uiState.update {
            it.copy(
                pendingConfirmation = null,
                isBusy = true,
                isGenerating = false,
                activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                statusText = "工具执行中",
            )
        }
        launchToolExecutionAfterRunIsExecuting(
            confirmation = pendingConfirmation,
            request = request,
            userConfirmed = true,
        )
    }

    private fun launchToolExecutionAfterRunIsExecuting(
        confirmation: PendingAgentConfirmation,
        request: ToolRequest,
        userConfirmed: Boolean,
    ) {
        launchToolGenerationJob(confirmation.runId) {
            executeToolRequestAfterRunIsExecuting(
                confirmation = confirmation,
                request = request,
                userConfirmed = userConfirmed,
            )
        }
    }

    suspend fun executeToolRequestAfterRunIsExecuting(
        confirmation: PendingAgentConfirmation,
        request: ToolRequest,
        userConfirmed: Boolean = false,
    ) {
        var result = executeToolWithBoundary(request, userConfirmed)
        var observation = confirmation.runId?.let { runId ->
            assistantOrchestrator.observeToolResult(runId, result)
        }
        var assistantText = observation?.assistantMessage ?: result.statusSummaryForUi()
        var observationPrivacy = observation.privacyForObservation(fallbackToolName = request.toolName)
        var messagesWithObservation = uiState.value.messages + ChatMessage(
            role = MessageRole.Assistant,
            text = assistantText,
            privacy = observationPrivacy,
        )
        replaceActiveSessionMessages(
            messagesWithObservation,
            true,
        )
        var retryRequest = observation?.retryRequest
        while (retryRequest != null) {
            uiState.update {
                it.copy(statusText = "工具重试中")
            }
            result = executeToolWithBoundary(retryRequest, userConfirmed)
            observation = confirmation.runId?.let { runId ->
                assistantOrchestrator.observeToolResult(runId, result)
            }
            assistantText = observation?.assistantMessage ?: result.statusSummaryForUi()
            observationPrivacy = observation.privacyForObservation(fallbackToolName = retryRequest.toolName)
            messagesWithObservation = uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = assistantText,
                privacy = observationPrivacy,
            )
            replaceActiveSessionMessages(
                messagesWithObservation,
                true,
            )
            retryRequest = observation?.retryRequest
        }
        val publicWebEvidence = activePublicWebEvidenceFor(confirmation.runId)
        val nextToolPlan = (observation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
        if (nextToolPlan != null) {
            handleNextToolPlan(
                runId = observation.run.id,
                plan = nextToolPlan,
                pendingStatusText = "下一步动作待确认",
            )
            return
        }
        observation?.continuationPromptForModel?.let { continuationPrompt ->
            val continuationResolution = resolveBoundToolContinuation(
                runId = observation.run.id,
                observationPrivacy = observationPrivacy,
                requiresLocalModel = observation.continuationRequiresLocalModel,
                activeRunPlacement = activeRunPlacement,
            )
            if (continuationResolution is BoundRunContinuationResolution.Blocked) {
                val protectedContentName = request.protectedContinuationContentName()
                val privacyBlocked = continuationResolution.reason ==
                    PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED
                assistantOrchestrator.failModelGeneration(
                    observation.run.id,
                    if (privacyBlocked) {
                        "工具结果需要本地模型续写，未发送到远程模型"
                    } else {
                        "运行绑定不可恢复，已停止工具结果续写"
                    },
                )
                replaceActiveSessionMessages(
                    messagesWithObservation + ChatMessage(
                        role = MessageRole.Assistant,
                        text = if (privacyBlocked) {
                            "已读取${protectedContentName}。当前运行绑定到远程模型，为保护隐私，我不会自动发送${protectedContentName}到远程模型。请重新发起一个本地运行，或手动粘贴你愿意发送的内容。"
                        } else {
                            "本次运行的模型绑定不可恢复，已停止工具结果续写。请重新发起请求。"
                        },
                        privacy = MessagePrivacy.LocalOnly,
                    ),
                    true,
                )
                rebuildMemoryIndex()
                uiState.update {
                    it.copy(
                        pendingConfirmation = null,
                        isBusy = false,
                        isGenerating = false,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                        activePublicWebEvidence = publicWebEvidence,
                        statusText = if (privacyBlocked) {
                            "已保护${protectedContentName}"
                        } else {
                            "运行绑定不可恢复"
                        },
                    )
                }
                return
            }
            replaceActiveSessionMessages(
                messagesWithObservation + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "",
                    privacy = observationPrivacy,
                ),
                true,
            )
            uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    isBusy = true,
                    isGenerating = true,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                    activePublicWebEvidence = publicWebEvidence,
                    statusText = "生成中",
                )
            }
            continueAfterToolObservation(
                observation.run.id,
                continuationPrompt,
                observationPrivacy,
                observation.continuationRemoteToolScope,
                false,
            )
            return
        }
        syncTaskStateMemories()
        rebuildMemoryIndex()
        val pendingExternalOutcome = observation?.pendingExternalOutcomeFor(confirmation, request)
        uiState.update {
            it.copy(
                pendingConfirmation = null,
                pendingExternalOutcome = pendingExternalOutcome,
                backgroundTasks = loadBackgroundTasks(),
                backgroundTaskHistory = loadBackgroundTaskHistory(),
                periodicCheckPolicy = loadPeriodicCheckPolicy(),
                longTermMemories = loadLongTermMemories(),
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                activePublicWebEvidence = publicWebEvidence,
                latestRecoveryAction = if (pendingExternalOutcome == null) observation?.recoveryAction else null,
                isBusy = false,
                isGenerating = false,
                statusText = observation?.assistantMessage ?: result.statusSummaryForUi(),
            )
        }
    }

    private suspend fun executeToolWithBoundary(
        request: ToolRequest,
        userConfirmed: Boolean,
    ): ToolResult {
        solinD(TAG_TOOL, "executeTool: start tool=${request.toolName}")
        val startMs = System.currentTimeMillis()
        val result = toolExecutionBoundary.execute(request, userConfirmed)
        val durationMs = System.currentTimeMillis() - startMs
        val success = result.error == null
        solinD(
            TAG_TOOL,
            "executeTool: end tool=${request.toolName} success=$success durationMs=$durationMs",
        )
        return result
    }

    suspend fun executePublicEvidenceToolBatchAfterRunIsExecuting(
        runId: String,
        plans: List<AgentPlan.UseTool>,
    ) {
        if (plans.isEmpty()) {
            assistantOrchestrator.failModelGeneration(runId, "远程模型返回了空工具批次")
            updateLastAssistantLocalOnly("远程模型返回了空工具批次，已停止执行。")
            persistActiveSessionFromUi()
            uiState.update {
                it.copy(
                    isBusy = false,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = "批量工具不可执行",
                )
            }
            return
        }
        uiState.update {
            it.copy(
                pendingConfirmation = null,
                latestRecoveryAction = null,
                isBusy = true,
                isGenerating = false,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                statusText = "工具并发执行中",
            )
        }
        val results = executePublicEvidenceToolPlans(plans)
        val observation = assistantOrchestrator.observeToolResults(runId, results)
        if (observation == null) {
            assistantOrchestrator.cancelRun(runId, "批量工具结果无法进入观察流程")
            updateLastAssistantLocalOnly("批量工具结果无法进入观察流程，已停止执行。")
            persistActiveSessionFromUi()
            uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    isBusy = false,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = "批量工具不可执行",
                )
            }
            return
        }
        val observationPrivacy = observation.privacyForObservation(fallbackToolName = PUBLIC_EVIDENCE_BATCH_TOOL_NAME)
        val publicWebEvidence = activePublicWebEvidenceFor(runId)
        val messagesWithObservation = uiState.value.messages + ChatMessage(
            role = MessageRole.Assistant,
            text = observation.assistantMessage,
            privacy = observationPrivacy,
        )
        replaceActiveSessionMessages(
            messagesWithObservation,
            true,
        )
        val nextToolPlan = (observation.decision as? AgentObservationDecision.PlanNextTool)?.plan
        if (nextToolPlan != null) {
            handleNextToolPlan(
                runId = observation.run.id,
                plan = nextToolPlan,
                pendingStatusText = "下一步动作待确认",
            )
            return
        }
        observation.continuationPromptForModel?.let { continuationPrompt ->
            val continuationResolution = resolveBoundToolContinuation(
                runId = observation.run.id,
                observationPrivacy = observationPrivacy,
                requiresLocalModel = observation.continuationRequiresLocalModel,
                activeRunPlacement = activeRunPlacement,
            )
            if (continuationResolution is BoundRunContinuationResolution.Blocked) {
                val privacyBlocked = continuationResolution.reason ==
                    PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED
                assistantOrchestrator.failModelGeneration(
                    observation.run.id,
                    if (privacyBlocked) {
                        "批量工具结果需要本地模型续写，未发送到远程模型"
                    } else {
                        "运行绑定不可恢复，已停止批量工具结果续写"
                    },
                )
                replaceActiveSessionMessages(
                    messagesWithObservation + ChatMessage(
                        role = MessageRole.Assistant,
                        text = if (privacyBlocked) {
                            "批量工具结果包含仅本地内容。当前运行绑定到远程模型，我不会把它发送到远程模型。"
                        } else {
                            "本次运行的模型绑定不可恢复，已停止批量工具结果续写。请重新发起请求。"
                        },
                        privacy = MessagePrivacy.LocalOnly,
                    ),
                    true,
                )
                rebuildMemoryIndex()
                uiState.update {
                    it.copy(
                        pendingConfirmation = null,
                        isBusy = false,
                        isGenerating = false,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        activeRunTimeline = activeRunTimelineFor(runId),
                        activePublicWebEvidence = publicWebEvidence,
                        statusText = if (privacyBlocked) {
                            "已保护批量工具结果"
                        } else {
                            "运行绑定不可恢复"
                        },
                    )
                }
                return
            }
            replaceActiveSessionMessages(
                messagesWithObservation + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "",
                    privacy = observationPrivacy,
                ),
                true,
            )
            uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    isBusy = true,
                    isGenerating = true,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    activePublicWebEvidence = publicWebEvidence,
                    statusText = "生成中",
                )
            }
            continueAfterToolObservation(
                observation.run.id,
                continuationPrompt,
                observationPrivacy,
                observation.continuationRemoteToolScope,
                false,
            )
            return
        }
        syncTaskStateMemories()
        rebuildMemoryIndex()
        uiState.update {
            it.copy(
                pendingConfirmation = null,
                backgroundTasks = loadBackgroundTasks(),
                backgroundTaskHistory = loadBackgroundTaskHistory(),
                periodicCheckPolicy = loadPeriodicCheckPolicy(),
                longTermMemories = loadLongTermMemories(),
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                activePublicWebEvidence = publicWebEvidence,
                latestRecoveryAction = observation.recoveryAction,
                isBusy = false,
                isGenerating = false,
                statusText = observation.assistantMessage,
            )
        }
    }

    private suspend fun executePublicEvidenceToolPlans(
        plans: List<AgentPlan.UseTool>,
    ): List<ToolResult> =
        toolExecutionBoundary.executePublicEvidenceBatch(
            requests = plans.map { plan -> plan.request },
        ) {
            uiState.update {
                it.copy(statusText = "工具批量重试中")
            }
        }

    fun handleNextToolPlan(
        runId: String,
        plan: AgentPlan.UseTool,
        pendingStatusText: String,
    ) {
        rebuildMemoryIndex()
        val confirmation = PendingAgentConfirmation(
            runId = runId,
            draft = plan.draft,
            toolRequest = plan.request,
            skillId = plan.skillRequest?.skillId,
            plannedByModel = plan.plannedByModel,
            fallbackReason = plan.fallbackReason,
        )
        if (!plan.requiresUserConfirmation()) {
            uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    latestRecoveryAction = null,
                    isBusy = true,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = "工具执行中",
                )
            }
            launchToolExecutionAfterRunIsExecuting(
                confirmation = confirmation,
                request = plan.request,
                userConfirmed = false,
            )
            return
        }
        uiState.update {
            it.copy(
                pendingConfirmation = confirmation,
                latestRecoveryAction = null,
                isBusy = false,
                isGenerating = false,
                isReady = true,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(runId),
                statusText = pendingStatusText,
            )
        }
    }

    private fun AgentObservationResult.pendingExternalOutcomeFor(
        confirmation: PendingAgentConfirmation,
        request: ToolRequest,
    ): PendingExternalOutcomeConfirmation? {
        if (!result.isUnverifiedExternalLaunch()) return null
        if (run.state != AgentRunState.AwaitingExternalOutcome) return null
        val runId = confirmation.runId ?: return null
        return PendingExternalOutcomeConfirmation(
            runId = runId,
            requestId = result.requestId,
            toolName = request.toolName,
            title = confirmation.draft.title,
            summary = assistantMessage,
        )
    }

    fun recordExternalOutcome(
        pending: PendingExternalOutcomeConfirmation,
        outcome: AgentExternalOutcome,
    ) {
        if (dispatchPersistenceWorkIfNeeded { recordExternalOutcome(pending, outcome) }) return
        val current = uiState.value.pendingExternalOutcome
        if (current == null || current != pending) {
            uiState.update {
                it.copy(statusText = "外部结果确认已处理")
            }
            return
        }
        val recorded = runCatching {
            assistantOrchestrator.recordExternalOutcome(
                runId = pending.runId,
                requestId = pending.requestId,
                outcome = outcome,
            )
        }.getOrNull()
        if (recorded == null) {
            uiState.update {
                it.copy(
                    pendingExternalOutcome = null,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(pending.runId),
                    statusText = "外部结果确认已过期",
                )
            }
            return
        }
        replaceActiveSessionMessages(
            uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = recorded.assistantMessage,
                privacy = MessagePrivacy.LocalOnly,
            ),
            true,
        )
        rebuildMemoryIndex()
        val nextToolPlan = (recorded.decision as? AgentObservationDecision.PlanNextTool)?.plan
        if (nextToolPlan != null) {
            uiState.update {
                it.copy(
                    pendingExternalOutcome = null,
                    latestRecoveryAction = null,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(recorded.run.id),
                )
            }
            handleNextToolPlan(
                runId = recorded.run.id,
                plan = nextToolPlan,
                pendingStatusText = "下一步动作待确认",
            )
            return
        }
        uiState.update {
            it.copy(
                pendingExternalOutcome = null,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(recorded.run.id),
                statusText = recorded.assistantMessage,
            )
        }
    }

    private fun ToolResult.statusSummaryForUi(): String =
        if (isUnverifiedExternalLaunch()) unverifiedExternalLaunchSummary() else summary

    fun rejectAgentConfirmationForRuntimePermissionDenial(
        confirmation: PendingAgentConfirmation,
        deniedPermissions: List<String>,
    ) {
        val deniedSummary = runtimePermissionDenialSummary(deniedPermissions)
        val deniedPermissionNames = deniedPermissions.distinct().joinToString()
        rejectAgentConfirmationWithFailure(
            confirmation = confirmation,
            statusText = "权限被拒，工具未执行",
            resultFor = { request ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "用户拒绝了所需权限，工具未执行：$deniedSummary",
                    retryable = false,
                    data = mapOf(
                        "toolName" to request.toolName,
                        "deniedPermissions" to deniedPermissionNames,
                        "deniedPermissionLabels" to deniedSummary,
                    ),
                )
            },
        )
    }

    fun rejectAgentConfirmationForSpecialAccessDenial(
        confirmation: PendingAgentConfirmation,
        deniedRequirements: List<SpecialAccessRequirement>,
    ) {
        val deniedSummary = specialAccessDenialSummary(deniedRequirements)
        rejectAgentConfirmationWithFailure(
            confirmation = confirmation,
            statusText = "特殊权限未开启，工具未执行",
            resultFor = { request ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "未开启所需系统特殊权限，工具未执行：$deniedSummary",
                    retryable = false,
                    data = mapOf(
                        "toolName" to request.toolName,
                        "specialAccess" to deniedRequirements.joinToString { it.id },
                        "specialAccessLabels" to deniedSummary,
                        "settingsAction" to deniedRequirements.joinToString { it.settingsAction },
                    ),
                )
            },
        )
    }

    fun rejectAgentConfirmationForMediaProjectionDenial(
        confirmation: PendingAgentConfirmation,
    ) {
        rejectAgentConfirmationWithFailure(
            confirmation = confirmation,
            statusText = "屏幕截图同意已取消，工具未执行",
            resultFor = { request ->
                request.failed(
                    code = ToolErrorCode.PermissionDenied,
                    summary = "用户取消了当前屏幕截图 OCR 的 Android MediaProjection 前台同意，工具未执行。",
                    retryable = false,
                    data = mapOf(
                        "toolName" to request.toolName,
                        "privacy" to MessagePrivacy.LocalOnly.name,
                        "requiresLocalModel" to true.toString(),
                        "specialAccess" to CurrentScreenshotOcrContract.CONSENT_REASON,
                    ),
                )
            },
        )
    }

    private fun rejectAgentConfirmationWithFailure(
        confirmation: PendingAgentConfirmation,
        statusText: String,
        resultFor: (ToolRequest) -> ToolResult,
    ) {
        if (
            dispatchPersistenceWorkIfNeeded {
                rejectAgentConfirmationWithFailure(
                    confirmation = confirmation,
                    statusText = statusText,
                    resultFor = resultFor,
                )
            }
        ) {
            return
        }
        val pendingConfirmation = uiState.value.pendingConfirmation
        if (pendingConfirmation == null || !pendingConfirmation.matchesExecution(confirmation)) {
            uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = pendingConfirmation.toolRequest ?: ToolRequest(
            toolName = pendingConfirmation.draft.functionName,
            arguments = pendingConfirmation.draft.parameters,
            reason = pendingConfirmation.draft.summary,
        )
        val result = resultFor(request)
        val observation = confirmation.runId?.let { runId ->
            assistantOrchestrator.failPendingToolRequest(runId, request.id, result)
        }
        replaceActiveSessionMessages(
            uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = observation?.assistantMessage ?: "工具执行失败：${result.summary}",
                privacy = MessagePrivacy.LocalOnly,
            ),
            true,
        )
        rebuildMemoryIndex()
        uiState.update {
            it.copy(
                pendingConfirmation = null,
                isBusy = false,
                isGenerating = false,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(confirmation.runId),
                statusText = statusText,
            )
        }
    }


    fun dismissAgentConfirmation(confirmation: PendingAgentConfirmation? = uiState.value.pendingConfirmation) {
        if (dispatchPersistenceWorkIfNeeded { dismissAgentConfirmation(confirmation) }) return
        val pendingConfirmation = uiState.value.pendingConfirmation
        if (confirmation == null || pendingConfirmation == null) {
            uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        if (!pendingConfirmation.matchesExecution(confirmation)) {
            uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = pendingConfirmation.toolRequest ?: ToolRequest(
            toolName = pendingConfirmation.draft.functionName,
            arguments = pendingConfirmation.draft.parameters,
            reason = pendingConfirmation.draft.summary,
        )
        val observation = if (pendingConfirmation.runId != null) {
            assistantOrchestrator.cancelToolRequest(pendingConfirmation.runId, request.id)
        } else {
            null
        }
        uiState.update {
            it.copy(
                pendingConfirmation = null,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                activeRunTimeline = activeRunTimelineFor(pendingConfirmation.runId),
                statusText = observation?.assistantMessage ?: "已取消动作草稿",
            )
        }
    }

    private fun AgentObservationResult?.privacyForObservation(
        fallbackToolName: String? = null,
    ): MessagePrivacy {
        if (this == null) return privacyForPublicEvidenceToolName(fallbackToolName)
        if (continuationRequiresLocalModel) return MessagePrivacy.LocalOnly

        result.data["privacy"]?.let { declaredPrivacy ->
            return runCatching { MessagePrivacy.valueOf(declaredPrivacy) }
                .getOrDefault(MessagePrivacy.LocalOnly)
        }

        return privacyForPublicEvidenceToolName(result.data["toolName"] ?: fallbackToolName)
    }

    private fun privacyForPublicEvidenceToolName(toolName: String?): MessagePrivacy {
        val isInternalPublicEvidenceBatch = toolName == PUBLIC_EVIDENCE_BATCH_TOOL_NAME
        val isRegisteredPublicEvidence =
            toolName != null && toolRegistry.specFor(toolName)?.isEligibleForParallelBatch() == true
        return if (isInternalPublicEvidenceBatch || isRegisteredPublicEvidence) {
            MessagePrivacy.RemoteEligible
        } else {
            MessagePrivacy.LocalOnly
        }
    }

    private fun ToolRequest.protectedContinuationContentName(): String =
        when (toolName) {
            MobileActionFunctions.READ_CLIPBOARD -> "剪贴板内容"
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR -> "截图 OCR 内容"
            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> "图片 OCR 内容"
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> "当前屏幕文本"
            MobileActionFunctions.CAPTURE_CURRENT_SCREENSHOT_OCR -> "当前屏幕截图 OCR 内容"
            else -> "本地工具结果"
        }

    fun confirmActionDraft(draft: ActionDraft) {
        confirmAgentConfirmation(
            PendingAgentConfirmation(
                runId = null,
                draft = draft,
                toolRequest = null,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
            ),
        )
    }

    fun dismissActionDraft() {
        dismissAgentConfirmation()
    }

    fun restorePendingAgentConfirmationIfAny(clearMissing: Boolean = false) {
        val route = assistantOrchestrator.restorePendingAction(activeSessionId())
        if (route == null) {
            if (clearMissing) {
                uiState.update {
                    it.copy(pendingConfirmation = null)
                }
            }
            return
        }
        uiState.update {
            it.copy(
                pendingConfirmation = PendingAgentConfirmation(
                    runId = route.runId,
                    draft = route.draft,
                    toolRequest = route.toolRequest,
                    skillId = route.skillId,
                    plannedByModel = route.plannedByModel,
                    fallbackReason = route.fallbackReason,
                ),
                isBusy = false,
                isGenerating = false,
                statusText = "动作草稿待确认 · 已恢复",
            )
        }
    }

    fun restorePendingExternalOutcomeIfAny(clearMissing: Boolean = false) {
        if (uiState.value.pendingConfirmation != null) {
            if (clearMissing) {
                uiState.update { it.copy(pendingExternalOutcome = null) }
            }
            return
        }
        val pending = assistantOrchestrator.restorePendingExternalOutcome(activeSessionId())
        if (pending == null) {
            if (clearMissing) {
                uiState.update { it.copy(pendingExternalOutcome = null) }
            }
            return
        }
        uiState.update {
            it.copy(
                pendingExternalOutcome = pending.toUiPendingExternalOutcome(),
                latestRecoveryAction = null,
                isBusy = false,
                isGenerating = false,
                statusText = "外部动作结果待确认 · 已恢复",
            )
        }
    }

    private fun PendingExternalOutcomeSnapshot.toUiPendingExternalOutcome(): PendingExternalOutcomeConfirmation =
        PendingExternalOutcomeConfirmation(
            runId = runId,
            requestId = requestId,
            toolName = toolName,
            title = title,
            summary = summary,
        )

    private fun updateLastAssistantLocalOnly(text: String) {
        uiState.update { state ->
            val updatedMessages = state.messages.toMutableList()
            val index = updatedMessages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                updatedMessages[index] = updatedMessages[index].copy(
                    text = text,
                    generationStats = null,
                    privacy = MessagePrivacy.LocalOnly,
                )
            }
            state.copy(messages = updatedMessages)
        }
    }
}
