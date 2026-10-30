package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.AuditEventSummary
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.StreamingAssistantUpdateCoalescer
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.logging.solinE
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_LIFECYCLE
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_MODEL
import com.bytedance.zgx.solin.orchestration.ActiveRunPlacementPermit
import com.bytedance.zgx.solin.orchestration.AgentObservationDecision
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.BoundRunContinuationResolution
import com.bytedance.zgx.solin.orchestration.BoundRunContinuationResolver
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.PromptPrivacyPlanner
import com.bytedance.zgx.solin.orchestration.PromptPrivacySegment
import com.bytedance.zgx.solin.orchestration.PromptSegmentSource
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.orchestration.RunDataDestination
import com.bytedance.zgx.solin.orchestration.RunDataReceipt
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal fun resolveBoundToolContinuation(
    runId: String?,
    observationPrivacy: MessagePrivacy?,
    requiresLocalModel: Boolean?,
    activeRunPlacement: (String) -> ActiveRunPlacementPermit?,
): BoundRunContinuationResolution {
    val permit = runId
        ?.takeIf(String::isNotBlank)
        ?.let { id -> runCatching { activeRunPlacement(id) }.getOrNull() }
        ?.takeIf { candidate -> candidate.binding.runId == runId }
    val privacyPlan = PromptPrivacyPlanner.build(
        listOf(
            PromptPrivacySegment(
                source = PromptSegmentSource.ToolObservation,
                privacy = observationPrivacy,
                requiresLocalModel = requiresLocalModel,
            ),
        ),
    )
    return BoundRunContinuationResolver.resolve(permit, privacyPlan)
}

/**
 * Owns post-tool-observation model continuation (local + remote tool-result re-entry).
 *
 * Generation I/O helpers live on
 * [ChatGenerationSupport]; remote disclosure/audit on [ChatRemoteSendSupport]. Job lifecycle
 * and tool-plan handoff stay injected so this collaborator does not own generation jobs.
 */
internal class ChatToolContinuationSupport(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val runtime: LiteRtRuntime,
    private val assistantOrchestrator: AssistantRouter,
    private val generationSupport: ChatGenerationSupport,
    private val remoteSendSupport: ChatRemoteSendSupport,
    private val launchGenerationJob: (runId: String?, block: suspend () -> Unit) -> Unit,
    private val cancelActiveGenerationRun: (runId: String?) -> Unit,
    private val executePublicEvidenceToolBatchAfterRunIsExecuting: suspend (
        runId: String,
        plans: List<AgentPlan.UseTool>,
    ) -> Unit,
    private val handleNextToolPlan: (
        runId: String,
        plan: AgentPlan.UseTool,
        pendingStatusText: String,
    ) -> Unit,
    private val persistActiveSessionFromUi: () -> Unit,
    private val rebuildMemoryIndex: () -> Unit,
    private val loadAuditEvents: () -> List<AuditEventSummary>,
    private val loadAgentTraceRuns: () -> List<AgentTraceRunUiSummary>,
    private val activeRunTimelineFor: (String?) -> List<RunTimelineItemUiSummary>,
    private val activePublicWebEvidenceFor: (String?) -> List<PublicWebEvidencePack>,
    private val activeRunPlacement: (String) -> ActiveRunPlacementPermit? = { null },
    private val onRemoteServingStarted: (String?) -> Unit = {},
    private val discardDeferredRemoteSend: (String?) -> Unit = {},
) {
    fun continueAfterToolObservation(
        runId: String?,
        promptForModel: String,
        responsePrivacy: MessagePrivacy,
        remoteToolScope: RemoteToolScope,
        remoteSendConfirmed: Boolean = false,
    ) {
        val stateAtStart = uiState.value
        val continuationResolution = resolveBoundToolContinuation(
            runId = runId,
            observationPrivacy = responsePrivacy,
            requiresLocalModel = responsePrivacy == MessagePrivacy.LocalOnly,
            activeRunPlacement = activeRunPlacement,
        )
        if (continuationResolution is BoundRunContinuationResolution.Blocked) {
            discardDeferredRemoteSend(runId)
            val privacyBlocked =
                continuationResolution.reason == PlacementReasonCode.PLACEMENT_LOCAL_CONTINUATION_REQUIRED
            runId?.let { id ->
                assistantOrchestrator.failModelGeneration(
                    id,
                    if (privacyBlocked) {
                        "工具结果需要本地模型续写，未发送到远程模型"
                    } else {
                        "运行绑定不可恢复，已停止工具结果续写"
                    },
                )
            }
            uiState.updateLastAssistantLocalOnly(
                if (privacyBlocked) {
                    "工具结果包含仅本地内容。当前运行绑定到远程模型，我不会把它发送到远程模型。"
                } else {
                    "本次运行的模型绑定不可恢复，已停止工具结果续写。请重新发起请求。"
                },
            )
            persistActiveSessionFromUi()
            rebuildMemoryIndex()
            uiState.update {
                it.copy(
                    isBusy = false,
                    isGenerating = false,
                    pendingConfirmation = null,
                    pendingRemoteSendDisclosure = null,
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = if (privacyBlocked) {
                        "已保护工具结果"
                    } else {
                        "运行绑定不可恢复"
                    },
                )
            }
            return
        }
        val continuationPermit =
            (continuationResolution as BoundRunContinuationResolution.Dispatch).permit
        val useRemoteModel = continuationPermit.binding.placement == RunPlacement.Remote
        val remoteConfig = stateAtStart.remoteModelConfig
        var remoteHistory: List<ChatMessage> =
            remoteSendSupport.remoteHistoryForRemoteSend(stateAtStart.messages.dropLast(1))
        if (useRemoteModel &&
            responsePrivacy == MessagePrivacy.RemoteEligible &&
            remoteConfig.isConfigured &&
            remoteConfig.hasKnownConnectivityFailure
        ) {
            discardDeferredRemoteSend(runId)
            runId?.let { id ->
                assistantOrchestrator.failModelGeneration(id, "远程连接状态为${remoteConfig.connectivityStatus.label}")
            }
            remoteSendSupport.recordRemoteSendAuditEvent(
                decision = RemoteSendDecision.Blocked,
                modelName = remoteConfig.normalized().modelName,
                prompt = promptForModel,
                imageCount = 0,
                remoteHistoryCount = remoteHistory.size,
            )
            uiState.updateLastAssistantLocalOnly(
                "远程连接状态为${remoteConfig.connectivityStatus.label}，工具结果续写没有发送。请在模型管理中测试连接或更新远程配置。",
            )
            persistActiveSessionFromUi()
            uiState.update {
                it.copy(
                    isBusy = false,
                    isGenerating = false,
                    isReady = true,
                    pendingConfirmation = null,
                    pendingRemoteSendDisclosure = null,
                    agentTraceRuns = loadAgentTraceRuns(),
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = "远程连接不可用",
                )
            }
            return
        }
        if (useRemoteModel &&
            responsePrivacy == MessagePrivacy.RemoteEligible &&
            remoteConfig.isConfigured &&
            !remoteSendConfirmed &&
            remoteSendSupport.shouldRequireRemoteSendDisclosure()
        ) {
            remoteSendSupport.setPendingRemoteContinuation(
                PendingRemoteContinuation(
                    runId = runId,
                    promptForModel = promptForModel,
                    responsePrivacy = responsePrivacy,
                    remoteToolScope = remoteToolScope,
                ),
            )
            val disclosure = remoteSendSupport.buildPendingRemoteSendDisclosure(
                kind = RemoteSendDisclosureKind.ToolResultContinuation,
                prompt = promptForModel,
                messagePrivacy = responsePrivacy,
                remoteConfig = remoteConfig,
                remoteHistory = remoteHistory,
                imageAttachments = emptyList(),
                stateBeforeSend = stateAtStart,
            ).copy(runId = runId)
            remoteSendSupport.savePendingRemoteSendMarker(disclosure, runId = runId)
            uiState.update {
                it.copy(
                    pendingRemoteSendDisclosure = disclosure,
                    isBusy = false,
                    isGenerating = false,
                    activeRunTimeline = activeRunTimelineFor(runId),
                    statusText = "远程续写待确认",
                )
            }
            return
        }
        launchGenerationJob(runId) {
            var streamingAssistantUpdates: StreamingAssistantUpdateCoalescer? = null
            try {
                if (generationSupport.abortIfServingUnavailable(
                        runId = runId,
                        useRemoteModel = useRemoteModel,
                        remoteConfigured = remoteConfig.isConfigured,
                        localNotReadyText = "模型尚未就绪，无法继续处理工具结果",
                    )
                ) {
                    return@launchGenerationJob
                }
                val streamingUpdates = StreamingAssistantUpdateCoalescer(
                    publish = { text -> uiState.updateLastAssistant(text) },
                )
                streamingAssistantUpdates = streamingUpdates
                val turn = GuardedTurn(streamingUpdates)
                val parameters = uiState.value.generationParameters
                if (useRemoteModel) {
                    val remoteTools = assistantOrchestrator.availableRemoteToolSpecs(remoteToolScope)
                    runId?.let { id ->
                        assistantOrchestrator.recordRemoteToolsExposed(
                            runId = id,
                            scope = remoteToolScope,
                            toolNames = remoteTools.mapTo(linkedSetOf()) { tool -> tool.name },
                        )
                        assistantOrchestrator.recordRunDataReceipt(
                            runId = id,
                            receipt = continuationReceipt(stateAtStart, responsePrivacy, remoteHistory.size, remote = true),
                        )
                    }
                    if (!remoteSendConfirmed) {
                        remoteSendSupport.recordRemoteSendAuditEvent(
                            decision = RemoteSendDecision.Confirmed,
                            modelName = remoteConfig.normalized().modelName,
                            prompt = promptForModel,
                            imageCount = 0,
                            remoteHistoryCount = remoteHistory.size,
                        )
                    }
                    remoteHistory = generationSupport.streamRemoteTurn(
                        turn = turn,
                        runId = runId,
                        prompt = promptForModel,
                        history = remoteHistory,
                        parameters = parameters,
                        config = remoteConfig,
                        tools = remoteTools,
                        onServingStarted = { onRemoteServingStarted(runId) },
                    )
                } else {
                    generationSupport.streamLocalTurn(
                        turn = turn,
                        prompt = promptForModel,
                        history = stateAtStart.messages.dropLast(1),
                        parameters = parameters,
                    )
                }
                generationSupport.guardCompletedTurn(turn, useRemoteModel, remoteConfig)
                turn.qualityDecision?.let { decision ->
                    generationSupport.finishOutputQualityGuardedGeneration(
                        runId = runId,
                        decision = decision,
                        receipt = continuationReceipt(stateAtStart, responsePrivacy, remoteHistory.size, useRemoteModel),
                        useRemoteModel = useRemoteModel,
                    )
                    return@launchGenerationJob
                }
                streamingUpdates.flush()
                val partial = turn.partial
                var modelObservation = turn.observation
                if (modelObservation == null) {
                    if (partial.isBlank()) {
                        uiState.updateLastAssistant("没有生成内容")
                    } else if (!useRemoteModel) {
                        uiState.updateLastAssistantStats(
                            runCatching { runtime.lastGenerationStats() }.getOrNull(),
                        )
                    }
                    modelObservation = runId?.let { id ->
                        assistantOrchestrator.observeModelResult(
                            runId = id,
                            text = partial.toString(),
                            allowInlineToolCalls = !useRemoteModel,
                        )
                    }
                } else {
                    val remotePlan = (modelObservation.decision as? AgentObservationDecision.PlanNextTool)?.plan
                    val remoteToolBatch =
                        (modelObservation.decision as? AgentObservationDecision.PlanToolBatch)?.plans
                    if (remotePlan == null && remoteToolBatch == null) {
                        uiState.updateLastAssistantLocalOnly(
                            "无法准备这个动作：${(modelObservation.decision as? AgentObservationDecision.Fail)?.reason.orEmpty()}",
                        )
                    }
                }
                persistActiveSessionFromUi()
                rebuildMemoryIndex()
                val toolBatch = (modelObservation?.decision as? AgentObservationDecision.PlanToolBatch)?.plans
                if (toolBatch != null) {
                    val observedForBatch = modelObservation ?: error("远程批量工具调用缺少 Agent observation")
                    uiState.updateLastAssistantLocalOnly(toolBatchAssistantText(partial, toolBatch))
                    persistActiveSessionFromUi()
                    executePublicEvidenceToolBatchAfterRunIsExecuting(
                        observedForBatch.run.id,
                        toolBatch,
                    )
                    return@launchGenerationJob
                }
                val nextToolPlan = (modelObservation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
                if (nextToolPlan != null) {
                    val observedForPending = modelObservation ?: error("远程工具调用缺少 Agent observation")
                    val draftSource = if (useRemoteModel) "远程" else "模型"
                    uiState.updateLastAssistantLocalOnly(nextToolAssistantText(partial, nextToolPlan, draftSource))
                    persistActiveSessionFromUi()
                    handleNextToolPlan(
                        observedForPending.run.id,
                        nextToolPlan,
                        "下一步动作待确认",
                    )
                    return@launchGenerationJob
                }
                val continuationPrompt = modelObservation?.continuationPromptForModel
                if (modelObservation?.decision is AgentObservationDecision.ContinueWithModel &&
                    continuationPrompt != null
                ) {
                    uiState.updateLastAssistant("正在根据公开来源补充引用…")
                    persistActiveSessionFromUi()
                    continueAfterToolObservation(
                        runId = modelObservation.run.id,
                        promptForModel = continuationPrompt,
                        responsePrivacy = responsePrivacy,
                        remoteToolScope = modelObservation.continuationRemoteToolScope,
                    )
                    return@launchGenerationJob
                }
                uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = true,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        activeRunTimeline = activeRunTimelineFor(runId),
                        activePublicWebEvidence = activePublicWebEvidenceFor(runId),
                        statusText = when (modelObservation?.decision) {
                            is AgentObservationDecision.Fail -> "后续动作不可执行"
                            else -> if (useRemoteModel) {
                                "就绪 · 远程"
                            } else {
                                "就绪 · ${it.backend.label()}"
                            }
                        },
                    )
                }
            } catch (cancellation: CancellationException) {
                solinD(TAG_LIFECYCLE, "generation(tool-continuation): cancelled")
                streamingAssistantUpdates?.flush()
                cancelActiveGenerationRun(runId)
                generationSupport.finishStoppedGeneration(runId)
                throw cancellation
            } catch (throwable: Throwable) {
                solinE(TAG_MODEL, "generation(tool-continuation): failed useRemoteModel=$useRemoteModel", throwable)
                streamingAssistantUpdates?.flush()
                generationSupport.failGeneration(
                    runId = runId,
                    throwable = throwable,
                    useRemoteModel = useRemoteModel,
                    remoteConfigured = remoteConfig.isConfigured,
                )
            } finally {
                discardDeferredRemoteSend(runId)
            }
        }
    }

    /** What a tool-result continuation sends: never memory, device context or images. */
    private fun continuationReceipt(
        stateAtStart: ChatUiState,
        responsePrivacy: MessagePrivacy,
        remoteHistoryCount: Int,
        remote: Boolean,
    ): RunDataReceipt {
        val localOnlyCount = if (remote) {
            stateAtStart.messages.count { message -> message.privacy == MessagePrivacy.LocalOnly }
        } else {
            0
        }
        return RunDataReceipt(
            destination = if (remote) RunDataDestination.Remote else RunDataDestination.Local,
            currentPromptPrivacy = responsePrivacy.name,
            remoteHistoryCount = if (remote) remoteHistoryCount else 0,
            localOnlyHistoryFilteredCount = localOnlyCount,
            memoryHitCount = 0,
            memoryContextIncluded = false,
            deviceContextIncluded = false,
            imageAttachmentCount = 0,
            protectedSourceCount = localOnlyCount,
            rawContentPersisted = false,
            protectedContentTypes = if (remote) {
                listOf("本地记忆", "设备上下文", "LocalOnly 历史", "本地工具结果")
            } else {
                emptyList()
            },
            deletableRecordTypes = listOf("对话消息", "Agent 轨迹"),
        )
    }
}
