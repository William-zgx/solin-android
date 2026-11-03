package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.MessageKind
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.SpecialAccessRequirement
import com.bytedance.zgx.solin.action.MobileActionFunctions
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
import kotlinx.coroutines.withContext
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
import com.bytedance.zgx.solin.tool.ToolStatus
import com.bytedance.zgx.solin.tool.failed
import com.bytedance.zgx.solin.tool.isEligibleForParallelBatch
import com.bytedance.zgx.solin.tool.isUnverifiedExternalLaunch
import com.bytedance.zgx.solin.tool.unverifiedExternalLaunchSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val PUBLIC_EVIDENCE_BATCH_TOOL_NAME = "public_evidence_batch"
private const val MISSING_TOOL_REQUEST_REASON =
    "Run-bound confirmation is missing its tool request; cancelled without executing."

/**
 * Owns tool confirmation, execution, public-evidence batching, and pending-action restore.
 *
 * The ViewModel keeps thin public wrappers and owns generation / remote-continuation coupling via callbacks.
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
    private val sessionHost: PresentationSessionHost,
    private val activeRunPlacement: (String) -> ActiveRunPlacementPermit? = { null },
) {
    private val compositeRunBindings = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val toolExecutionBoundary = TimeoutToolExecutionBoundary(
        // Needed so device-control tools get their longer boundary; without the registry the
        // boundary fails closed to the shorter shared default.
        toolRegistry = toolRegistry,
        executor = actionExecutor,
        dispatcher = ioDispatcher,
        publicEvidenceBatchRequestValidator = toolRegistry::validatePublicEvidenceBatchRequest,
        executionAuthorizer = SafetyPolicyToolExecutionAuthorizer(toolRegistry),
        compositeChildAuthorizer = { parent, child ->
            compositeRunBindings[parent.id]?.let { runId ->
                assistantOrchestrator.authorizeCompositeChild(runId, parent, child)
            } == true
        },
        executionScope = { request, execute ->
            val parentId = request.id.substringBefore(":child:")
            val runId = compositeRunBindings[parentId]
            val background = runId?.let { com.bytedance.zgx.solin.device.background.BackgroundPhoneSession.forRun(it) }
            if (background == null) execute() else background.withControl(execute)
        },
        compositeChildObserver = { parent, child, result ->
            compositeRunBindings[parent.id]?.let { runId ->
                assistantOrchestrator.observeCompositeChild(runId, parent, child, result)
            }
        },
    )

    fun requestRecoveryActionConfirmation(action: AgentRecoveryAction) {
        if (sessionHost.dispatchPersistenceWorkIfNeeded { requestRecoveryActionConfirmation(action) }) return
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
                        auditEvents = sessionHost.loadAuditEvents(),
                        agentTraceRuns = sessionHost.loadAgentTraceRuns(),
                        statusText = "撤销提醒待确认",
                    )
                }
            }

            is AssistantRoute.ToolRejected -> {
                uiState.update {
                    it.copy(
                        latestRecoveryAction = null,
                        auditEvents = sessionHost.loadAuditEvents(),
                        agentTraceRuns = sessionHost.loadAgentTraceRuns(),
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
        if (sessionHost.dispatchPersistenceWorkIfNeeded { confirmAgentConfirmation(confirmation) }) return
        val pendingConfirmation = uiState.value.pendingConfirmation
        if (pendingConfirmation == null || !pendingConfirmation.matchesExecution(confirmation)) {
            uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = pendingConfirmation.resolvedToolRequest() ?: run {
            failClosedForMissingToolRequest(
                pendingConfirmation = pendingConfirmation,
                statusText = "工具未执行",
                appendFailureMessage = true,
            )
            return
        }
        val confirmedRun = try {
            confirmation.runId?.let { runId ->
                assistantOrchestrator.confirmToolRequest(runId, request.id)
            }
        } catch (_: Exception) {
            uiState.update {
                sessionHost.finishedRun(it, runId = confirmation.runId, statusText = "工具确认失败，未执行动作。").copy(
                    pendingConfirmation = pendingConfirmation,
                    auditEvents = sessionHost.loadAuditEvents(),
                )
            }
            return
        }
        if (confirmation.runId != null && confirmedRun?.state != AgentRunState.ExecutingTool) {
            sessionHost.replaceActiveSessionMessages(
                uiState.value.messages + ChatMessage(
                    MessageRole.Assistant,
                    "工具确认失败，未执行动作。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                true,
            )
            uiState.update {
                sessionHost.finishedRun(it, runId = confirmation.runId, statusText = "工具未执行").copy(
                    pendingConfirmation = null,
                    auditEvents = sessionHost.loadAuditEvents(),
                )
            }
            return
        }
        uiState.update {
            it.copy(
                pendingConfirmation = null,
                isBusy = true,
                isGenerating = false,
                activeRunTimeline = sessionHost.activeRunTimelineFor(confirmation.runId),
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
        var executedRequest = request
        var result: ToolResult
        var observation: AgentObservationResult?
        var observationPrivacy: MessagePrivacy
        var messagesWithObservation: List<ChatMessage>
        while (true) {
            confirmation.runId?.let { compositeRunBindings[executedRequest.id] = it }
            result = try {
                executeToolWithBoundary(executedRequest, userConfirmed)
            } finally {
                compositeRunBindings.remove(executedRequest.id)
            }
            observation = confirmation.runId?.let { runId ->
                withContext(ioDispatcher) {
                    try {
                        val background = com.bytedance.zgx.solin.device.background.BackgroundPhoneSession.forRun(runId)
                        if (background == null) assistantOrchestrator.observeToolResult(runId, result)
                        else background.withControl { assistantOrchestrator.observeToolResult(runId, result) }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        assistantOrchestrator.cancelRun(runId, "后台屏幕会话失效，已停止；不会改用主屏幕")
                        null
                    }
                }
            }
            observationPrivacy = observation.privacyForObservation(fallbackToolName = executedRequest.toolName)
            messagesWithObservation = appendObservationMessage(
                text = observation?.assistantMessage ?: result.statusSummaryForUi(),
                privacy = observationPrivacy,
                kind = messageKindFor(result),
            )
            executedRequest = observation?.retryRequest ?: break
            uiState.update { it.copy(statusText = "工具重试中") }
        }
        val publicWebEvidence = sessionHost.activePublicWebEvidenceFor(confirmation.runId)
        if (observation != null) {
            val protectedContentName = request.protectedContinuationContentName()
            val handedOff = continueAfterObservation(
                observation = observation,
                timelineRunId = confirmation.runId,
                observationPrivacy = observationPrivacy,
                messagesWithObservation = messagesWithObservation,
                publicWebEvidence = publicWebEvidence,
                blocked = ContinuationBlockedCopy(
                    privacyFailure = "工具结果需要本地模型续写，未发送到远程模型",
                    bindingFailure = "运行绑定不可恢复，已停止工具结果续写",
                    privacyMessage = "已读取${protectedContentName}。当前运行绑定到远程模型，为保护隐私，我不会自动发送${protectedContentName}到远程模型。请重新发起一个本地运行，或手动粘贴你愿意发送的内容。",
                    bindingMessage = "本次运行的模型绑定不可恢复，已停止工具结果续写。请重新发起请求。",
                    privacyStatus = "已保护${protectedContentName}",
                ),
            )
            if (handedOff) return
        }
        sessionHost.syncTaskStateMemories()
        sessionHost.rebuildMemoryIndex()
        val pendingExternalOutcome = observation?.pendingExternalOutcomeFor(confirmation, request)
        uiState.update {
            finishedToolRun(
                state = it,
                runId = confirmation.runId,
                statusText = observation?.assistantMessage ?: result.statusSummaryForUi(),
                publicWebEvidence = publicWebEvidence,
            ).copy(
                pendingExternalOutcome = pendingExternalOutcome,
                latestRecoveryAction = if (pendingExternalOutcome == null) observation?.recoveryAction else null,
            )
        }
    }

    private fun appendObservationMessage(
        text: String,
        privacy: MessagePrivacy,
        kind: MessageKind,
    ): List<ChatMessage> {
        val messages = uiState.value.messages + ChatMessage(
            role = MessageRole.Assistant,
            text = text,
            privacy = privacy,
            kind = kind,
        )
        sessionHost.replaceActiveSessionMessages(messages, true)
        return messages
    }

    /** User-facing copy for a tool continuation the run binding refused. */
    private class ContinuationBlockedCopy(
        val privacyFailure: String,
        val bindingFailure: String,
        val privacyMessage: String,
        val bindingMessage: String,
        val privacyStatus: String,
    )

    /**
     * Hands an observed tool result to the next step: a planned next tool, or a model
     * continuation when the run binding allows it. Returns false when the run should finish here.
     */
    private fun continueAfterObservation(
        observation: AgentObservationResult,
        timelineRunId: String?,
        observationPrivacy: MessagePrivacy,
        messagesWithObservation: List<ChatMessage>,
        publicWebEvidence: List<PublicWebEvidencePack>,
        blocked: ContinuationBlockedCopy,
    ): Boolean {
        val nextToolPlan = (observation.decision as? AgentObservationDecision.PlanNextTool)?.plan
        if (nextToolPlan != null) {
            handleNextToolPlan(
                runId = observation.run.id,
                plan = nextToolPlan,
                pendingStatusText = "下一步动作待确认",
            )
            return true
        }
        val continuationPrompt = observation.continuationPromptForModel ?: return false
        val continuationResolution = resolveBoundToolContinuation(
            runId = observation.run.id,
            observationPrivacy = observationPrivacy,
            requiresLocalModel = observation.continuationRequiresLocalModel,
            activeRunPlacement = activeRunPlacement,
        )
        val continuing = continuationResolution !is BoundRunContinuationResolution.Blocked
        val privacyBlocked = !continuing &&
            (continuationResolution as BoundRunContinuationResolution.Blocked).reason ==
            PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED
        if (!continuing) {
            assistantOrchestrator.failModelGeneration(
                observation.run.id,
                if (privacyBlocked) blocked.privacyFailure else blocked.bindingFailure,
            )
        }
        sessionHost.replaceActiveSessionMessages(
            messagesWithObservation + if (continuing) {
                ChatMessage(role = MessageRole.Assistant, text = "", privacy = observationPrivacy)
            } else {
                ChatMessage(
                    role = MessageRole.Assistant,
                    text = if (privacyBlocked) blocked.privacyMessage else blocked.bindingMessage,
                    privacy = MessagePrivacy.LocalOnly,
                )
            },
            true,
        )
        if (!continuing) sessionHost.rebuildMemoryIndex()
        uiState.update {
            it.copy(
                pendingConfirmation = null,
                isBusy = continuing,
                isGenerating = continuing,
                auditEvents = sessionHost.loadAuditEvents(),
                agentTraceRuns = sessionHost.loadAgentTraceRuns(),
                activeRunTimeline = sessionHost.activeRunTimelineFor(timelineRunId),
                activePublicWebEvidence = publicWebEvidence,
                statusText = when {
                    continuing -> "生成中"
                    privacyBlocked -> blocked.privacyStatus
                    else -> "运行绑定不可恢复"
                },
            )
        }
        if (continuing) {
            continueAfterToolObservation(
                observation.run.id,
                continuationPrompt,
                observationPrivacy,
                observation.continuationRemoteToolScope,
                false,
            )
        }
        return true
    }

    /** The run finished on a tool result: refresh every read model the tool may have changed. */
    private fun finishedToolRun(
        state: ChatUiState,
        runId: String?,
        statusText: String,
        publicWebEvidence: List<PublicWebEvidencePack>,
    ): ChatUiState =
        sessionHost.finishedRun(state, runId = runId, statusText = statusText).copy(
            pendingConfirmation = null,
            backgroundTasks = sessionHost.loadBackgroundTasks(),
            backgroundTaskHistory = sessionHost.loadBackgroundTaskHistory(),
            periodicCheckPolicy = sessionHost.loadPeriodicCheckPolicy(),
            longTermMemories = sessionHost.loadLongTermMemories(),
            auditEvents = sessionHost.loadAuditEvents(),
            activePublicWebEvidence = publicWebEvidence,
        )

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
            uiState.updateLastAssistantLocalOnly("远程模型返回了空工具批次，已停止执行。")
            sessionHost.persistActiveSessionFromUi()
            uiState.update {
                sessionHost.finishedRun(it, runId = runId, statusText = "批量工具不可执行").copy(
                    auditEvents = sessionHost.loadAuditEvents(),
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
                auditEvents = sessionHost.loadAuditEvents(),
                agentTraceRuns = sessionHost.loadAgentTraceRuns(),
                activeRunTimeline = sessionHost.activeRunTimelineFor(runId),
                statusText = "工具并发执行中",
            )
        }
        val results = executePublicEvidenceToolPlans(plans)
        val observation = assistantOrchestrator.observeToolResults(runId, results)
        if (observation == null) {
            assistantOrchestrator.cancelRun(runId, "批量工具结果无法进入观察流程")
            uiState.updateLastAssistantLocalOnly("批量工具结果无法进入观察流程，已停止执行。")
            sessionHost.persistActiveSessionFromUi()
            uiState.update {
                sessionHost.finishedRun(it, runId = runId, statusText = "批量工具不可执行").copy(
                    pendingConfirmation = null,
                    auditEvents = sessionHost.loadAuditEvents(),
                )
            }
            return
        }
        val observationPrivacy = observation.privacyForObservation(fallbackToolName = PUBLIC_EVIDENCE_BATCH_TOOL_NAME)
        val publicWebEvidence = sessionHost.activePublicWebEvidenceFor(runId)
        val messagesWithObservation = appendObservationMessage(
            text = observation.assistantMessage,
            privacy = observationPrivacy,
            kind = messageKindFor(results),
        )
        val handedOff = continueAfterObservation(
            observation = observation,
            timelineRunId = runId,
            observationPrivacy = observationPrivacy,
            messagesWithObservation = messagesWithObservation,
            publicWebEvidence = publicWebEvidence,
            blocked = ContinuationBlockedCopy(
                privacyFailure = "批量工具结果需要本地模型续写，未发送到远程模型",
                bindingFailure = "运行绑定不可恢复，已停止批量工具结果续写",
                privacyMessage = "批量工具结果包含仅本地内容。当前运行绑定到远程模型，我不会把它发送到远程模型。",
                bindingMessage = "本次运行的模型绑定不可恢复，已停止批量工具结果续写。请重新发起请求。",
                privacyStatus = "已保护批量工具结果",
            ),
        )
        if (handedOff) return
        sessionHost.syncTaskStateMemories()
        sessionHost.rebuildMemoryIndex()
        uiState.update {
            finishedToolRun(
                state = it,
                runId = runId,
                statusText = observation.assistantMessage,
                publicWebEvidence = publicWebEvidence,
            ).copy(latestRecoveryAction = observation.recoveryAction)
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
        sessionHost.rebuildMemoryIndex()
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
                    auditEvents = sessionHost.loadAuditEvents(),
                    agentTraceRuns = sessionHost.loadAgentTraceRuns(),
                    activeRunTimeline = sessionHost.activeRunTimelineFor(runId),
                    statusText = "工具执行中",
                )
            }
            // Only plans SafetyPolicy already allowed reach here: the user confirmed the tool, or the
            // continuation bypass authorized it after the user's first confirmation. userConfirmed =
            // true keeps the execution-boundary re-authorizer in agreement with that decision.
            // Unauthorized Required tools carry RequireConfirmation and go to the sheet below.
            launchToolExecutionAfterRunIsExecuting(
                confirmation = confirmation,
                request = plan.request,
                userConfirmed = true,
            )
            return
        }
        uiState.update {
            sessionHost.finishedRun(it, runId = runId, statusText = pendingStatusText).copy(
                pendingConfirmation = confirmation,
                latestRecoveryAction = null,
                isReady = true,
                auditEvents = sessionHost.loadAuditEvents(),
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
        if (sessionHost.dispatchPersistenceWorkIfNeeded { recordExternalOutcome(pending, outcome) }) return
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
                    auditEvents = sessionHost.loadAuditEvents(),
                    agentTraceRuns = sessionHost.loadAgentTraceRuns(),
                    activeRunTimeline = sessionHost.activeRunTimelineFor(pending.runId),
                    statusText = "外部结果确认已过期",
                )
            }
            return
        }
        sessionHost.replaceActiveSessionMessages(
            uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = recorded.assistantMessage,
                privacy = MessagePrivacy.LocalOnly,
            ),
            true,
        )
        sessionHost.rebuildMemoryIndex()
        val nextToolPlan = (recorded.decision as? AgentObservationDecision.PlanNextTool)?.plan
        if (nextToolPlan != null) {
            uiState.update {
                it.copy(
                    pendingExternalOutcome = null,
                    latestRecoveryAction = null,
                    auditEvents = sessionHost.loadAuditEvents(),
                    agentTraceRuns = sessionHost.loadAgentTraceRuns(),
                    activeRunTimeline = sessionHost.activeRunTimelineFor(recorded.run.id),
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
                auditEvents = sessionHost.loadAuditEvents(),
                agentTraceRuns = sessionHost.loadAgentTraceRuns(),
                activeRunTimeline = sessionHost.activeRunTimelineFor(recorded.run.id),
                statusText = recorded.assistantMessage,
            )
        }
    }

    private fun ToolResult.statusSummaryForUi(): String =
        if (isUnverifiedExternalLaunch()) unverifiedExternalLaunchSummary() else summary

    /**
     * Classifies a folded tool observation so [ContextCompactor] can preserve it under budget
     * pressure: failed/blocked results and screen observations must not be truncated like chat.
     */
    private fun messageKindFor(result: ToolResult): MessageKind = when {
        result.status != ToolStatus.Succeeded -> MessageKind.ToolFailure
        result.data.containsKey("screenObservationJson") ||
            result.data.containsKey("afterScreenObservationJson") -> MessageKind.ScreenObservation
        else -> MessageKind.ToolResult
    }

    /** Batch variant: a failed member makes the whole folded observation a [MessageKind.ToolFailure]. */
    private fun messageKindFor(results: List<ToolResult>): MessageKind =
        if (results.any { it.status != ToolStatus.Succeeded }) MessageKind.ToolFailure else MessageKind.ToolResult

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
            sessionHost.dispatchPersistenceWorkIfNeeded {
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
        val request = pendingConfirmation.resolvedToolRequest() ?: run {
            failClosedForMissingToolRequest(
                pendingConfirmation = pendingConfirmation,
                statusText = statusText,
                appendFailureMessage = true,
            )
            return
        }
        val result = resultFor(request)
        val observation = confirmation.runId?.let { runId ->
            assistantOrchestrator.failPendingToolRequest(runId, request.id, result)
        }
        sessionHost.replaceActiveSessionMessages(
            uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = observation?.assistantMessage ?: "工具执行失败：${result.summary}",
                privacy = MessagePrivacy.LocalOnly,
            ),
            true,
        )
        sessionHost.rebuildMemoryIndex()
        uiState.update {
            sessionHost.finishedRun(it, runId = confirmation.runId, statusText = statusText).copy(
                pendingConfirmation = null,
                auditEvents = sessionHost.loadAuditEvents(),
            )
        }
    }

    /**
     * A run-bound confirmation lost its tool request, so there is no request id to confirm, cancel
     * or fail. Fail closed: never execute, and cancel the whole run (the orchestrator cancels
     * whatever request it is awaiting) so the run and the UI agree instead of the UI clearing the
     * confirmation while the run stays AwaitingUserConfirmation. If the cancellation itself fails
     * the run is still awaiting, so the confirmation stays visible.
     */
    private fun failClosedForMissingToolRequest(
        pendingConfirmation: PendingAgentConfirmation,
        statusText: String,
        appendFailureMessage: Boolean,
    ) {
        val runId = pendingConfirmation.runId
        val cancellation = runCatching {
            runId?.let { id -> assistantOrchestrator.cancelRun(id, MISSING_TOOL_REQUEST_REASON) }
        }
        if (cancellation.isFailure) {
            uiState.update {
                it.copy(
                    pendingConfirmation = pendingConfirmation,
                    isBusy = false,
                    isGenerating = false,
                    statusText = "工具确认失败，未执行动作。",
                )
            }
            return
        }
        if (appendFailureMessage) {
            sessionHost.replaceActiveSessionMessages(
                uiState.value.messages + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "工具确认缺少请求信息，已取消该动作，未执行。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                true,
            )
        }
        uiState.update {
            sessionHost.finishedRun(it, runId = runId, statusText = statusText).copy(
                pendingConfirmation = null,
                auditEvents = sessionHost.loadAuditEvents(),
            )
        }
    }

    fun dismissAgentConfirmation(confirmation: PendingAgentConfirmation? = uiState.value.pendingConfirmation) {
        if (sessionHost.dispatchPersistenceWorkIfNeeded { dismissAgentConfirmation(confirmation) }) return
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
        val request = pendingConfirmation.resolvedToolRequest() ?: run {
            failClosedForMissingToolRequest(
                pendingConfirmation = pendingConfirmation,
                statusText = "已取消动作草稿",
                appendFailureMessage = false,
            )
            return
        }
        val observation = if (pendingConfirmation.runId != null) {
            assistantOrchestrator.cancelToolRequest(pendingConfirmation.runId, request.id)
        } else {
            null
        }
        uiState.update {
            it.copy(
                pendingConfirmation = null,
                auditEvents = sessionHost.loadAuditEvents(),
                agentTraceRuns = sessionHost.loadAgentTraceRuns(),
                activeRunTimeline = sessionHost.activeRunTimelineFor(pendingConfirmation.runId),
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

    fun restorePendingAgentConfirmationIfAny(clearMissing: Boolean = false) {
        val route = assistantOrchestrator.restorePendingAction(sessionHost.activeSessionId())
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
        val pending = assistantOrchestrator.restorePendingExternalOutcome(sessionHost.activeSessionId())
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
}
