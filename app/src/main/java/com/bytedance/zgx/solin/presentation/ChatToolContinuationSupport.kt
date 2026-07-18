package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.AuditEventSummary
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.StreamingAssistantUpdateCoalescer
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.logging.solinE
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_LIFECYCLE
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_MODEL
import com.bytedance.zgx.solin.modelProfile
import com.bytedance.zgx.solin.orchestration.ActiveRunPlacementPermit
import com.bytedance.zgx.solin.orchestration.AgentModelObservationResult
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
import com.bytedance.zgx.solin.orchestration.requiresUserConfirmation
import com.bytedance.zgx.solin.runtime.GenerationQualityDecision
import com.bytedance.zgx.solin.runtime.GenerationRuntimeKind
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.ModelOutputQualityGuard
import com.bytedance.zgx.solin.runtime.RemoteChatEvent
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
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
 * Extracted from [ChatController] (Wave 6 Track C6). Generation I/O helpers live on
 * [ChatGenerationSupport]; remote disclosure/audit on [ChatRemoteSendSupport]. Job lifecycle
 * and tool-plan handoff stay injected so this collaborator does not own generation jobs.
 */
internal class ChatToolContinuationSupport(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val runtime: LiteRtRuntime,
    private val remoteRuntime: RemoteChatRuntime,
    private val assistantOrchestrator: AssistantRouter,
    private val outputQualityGuard: ModelOutputQualityGuard,
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
            )
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
                if (!useRemoteModel && !runtime.isLoaded) {
                    runId?.let { id ->
                        assistantOrchestrator.failModelGeneration(id, "本地模型尚未就绪")
                    }
                    uiState.updateLastAssistant("模型尚未就绪，无法继续处理工具结果")
                    persistActiveSessionFromUi()
                    uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            isReady = false,
                            pendingConfirmation = null,
                            agentTraceRuns = loadAgentTraceRuns(),
                            activeRunTimeline = activeRunTimelineFor(runId),
                            statusText = "未加载模型",
                        )
                    }
                    return@launchGenerationJob
                }
                if (useRemoteModel && !remoteConfig.isConfigured) {
                    runId?.let { id ->
                        assistantOrchestrator.failModelGeneration(id, "远程模型未配置")
                    }
                    uiState.updateLastAssistant("请先在模型管理中配置远程模型地址和模型名")
                    persistActiveSessionFromUi()
                    uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            isReady = false,
                            pendingConfirmation = null,
                            agentTraceRuns = loadAgentTraceRuns(),
                            activeRunTimeline = activeRunTimelineFor(runId),
                            statusText = "请配置远程模型",
                        )
                    }
                    return@launchGenerationJob
                }

                val partial = StringBuilder()
                val streamingUpdates = StreamingAssistantUpdateCoalescer(
                    publish = { text -> uiState.updateLastAssistant(text) },
                )
                streamingAssistantUpdates = streamingUpdates
                var modelObservation: AgentModelObservationResult? = null
                var outputQualityDecision: GenerationQualityDecision? = null
                if (useRemoteModel) {
                    val remoteTools = assistantOrchestrator.availableRemoteToolSpecs(remoteToolScope)
                    runId?.let { id ->
                        assistantOrchestrator.recordRemoteToolsExposed(
                            runId = id,
                            scope = remoteToolScope,
                            toolNames = remoteTools.mapTo(linkedSetOf()) { tool -> tool.name },
                        )
                        val continuationReceipt = RunDataReceipt(
                            destination = RunDataDestination.Remote,
                            currentPromptPrivacy = responsePrivacy.name,
                            remoteHistoryCount = remoteHistory.size,
                            localOnlyHistoryFilteredCount = stateAtStart.messages.count { message ->
                                message.privacy == MessagePrivacy.LocalOnly
                            },
                            memoryHitCount = 0,
                            memoryContextIncluded = false,
                            deviceContextIncluded = false,
                            imageAttachmentCount = 0,
                            protectedSourceCount = stateAtStart.messages.count { message ->
                                message.privacy == MessagePrivacy.LocalOnly
                            },
                            rawContentPersisted = false,
                            protectedContentTypes = listOf(
                                "本地记忆",
                                "设备上下文",
                                "LocalOnly 历史",
                                "本地工具结果",
                            ),
                            deletableRecordTypes = listOf("对话消息", "Agent 轨迹"),
                        )
                        assistantOrchestrator.recordRunDataReceipt(
                            runId = id,
                            receipt = continuationReceipt,
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
                    val remoteTokenBudget = remoteConfig.modelProfile().contextWindowTokens
                        ?.let { window -> (window * SolinConstants.Ui.REMOTE_COMPACTION_BUDGET_RATIO).toInt() }
                        ?: Int.MAX_VALUE
                    val result = generationSupport.sendRemoteWithOverflowRetry(
                        runId = runId,
                        history = remoteHistory,
                        tokenBudget = remoteTokenBudget,
                    ) { historyToSend ->
                        remoteRuntime.sendWithTools(
                            prompt = promptForModel,
                            history = historyToSend,
                            parameters = uiState.value.generationParameters,
                            config = remoteConfig,
                            tools = remoteTools,
                        ).collect { event ->
                            if (outputQualityDecision != null) return@collect
                            when (event) {
                                is RemoteChatEvent.TextDelta -> {
                                    if (modelObservation == null) {
                                        val decision = generationSupport.appendGuardedGenerationChunk(
                                            partial = partial,
                                            chunk = event.text,
                                            runtimeKind = GenerationRuntimeKind.Remote,
                                            modelId = remoteConfig.modelProfile().id,
                                            backend = null,
                                            parameters = uiState.value.generationParameters,
                                            streamingUpdates = streamingUpdates,
                                        )
                                        if (decision !is GenerationQualityDecision.Continue) {
                                            outputQualityDecision = decision
                                            remoteRuntime.stop()
                                        }
                                    }
                                }

                                is RemoteChatEvent.ToolCall -> {
                                    streamingUpdates.flush()
                                    val id = runId ?: error("远程工具调用缺少 Agent run")
                                    modelObservation =
                                        assistantOrchestrator.observeModelToolRequest(id, event.request)
                                            ?: error("远程工具调用无法进入确认流程")
                                }

                                is RemoteChatEvent.ToolCalls -> {
                                    streamingUpdates.flush()
                                    val id = runId ?: error("远程工具调用缺少 Agent run")
                                    modelObservation =
                                        assistantOrchestrator.observeModelToolRequests(id, event.requests)
                                            ?: error("远程批量工具调用无法进入执行流程")
                                }

                                is RemoteChatEvent.ParseError -> {
                                    val decision = outputQualityGuard.failClosedForFormatViolation(
                                        summary = event.summary,
                                        accumulatedText = partial.toString(),
                                        runtimeKind = GenerationRuntimeKind.Remote,
                                        modelId = remoteConfig.modelProfile().id,
                                        backend = null,
                                    )
                                    generationSupport.applyOutputQualityDecisionToAssistant(
                                        partial = partial,
                                        decision = decision,
                                        streamingUpdates = streamingUpdates,
                                    )
                                    outputQualityDecision = decision
                                    remoteRuntime.stop()
                                }
                            }
                        }
                    }
                    remoteHistory = result.first
                } else {
                    generationSupport.collectLocalRuntimeResponse(
                        promptForModel = promptForModel,
                        history = stateAtStart.messages.dropLast(1),
                        parameters = uiState.value.generationParameters,
                    ) { chunk ->
                        if (outputQualityDecision == null) {
                            val decision = generationSupport.appendGuardedGenerationChunk(
                                partial = partial,
                                chunk = chunk,
                                runtimeKind = GenerationRuntimeKind.Local,
                                modelId = uiState.value.activeModelProfileId(),
                                backend = uiState.value.backend,
                                parameters = uiState.value.generationParameters,
                                streamingUpdates = streamingUpdates,
                            )
                            if (decision !is GenerationQualityDecision.Continue) {
                                outputQualityDecision = decision
                                runtime.stop()
                            }
                        }
                    }
                }
                if (outputQualityDecision == null && modelObservation == null) {
                    val finalDecision = outputQualityGuard.evaluateCompleted(
                        output = partial.toString(),
                        runtimeKind = if (useRemoteModel) GenerationRuntimeKind.Remote else GenerationRuntimeKind.Local,
                        modelId = if (useRemoteModel) {
                            remoteConfig.modelProfile().id
                        } else {
                            uiState.value.activeModelProfileId()
                        },
                        backend = if (useRemoteModel) null else uiState.value.backend,
                    )
                    if (finalDecision !is GenerationQualityDecision.Continue) {
                        generationSupport.applyOutputQualityDecisionToAssistant(
                            partial = partial,
                            decision = finalDecision,
                            streamingUpdates = streamingUpdates,
                        )
                        outputQualityDecision = finalDecision
                    }
                }
                outputQualityDecision?.let { decision ->
                    val continuationReceipt = if (useRemoteModel) {
                        RunDataReceipt(
                            destination = RunDataDestination.Remote,
                            currentPromptPrivacy = responsePrivacy.name,
                            remoteHistoryCount = remoteHistory.size,
                            localOnlyHistoryFilteredCount = stateAtStart.messages.count { message ->
                                message.privacy == MessagePrivacy.LocalOnly
                            },
                            memoryHitCount = 0,
                            memoryContextIncluded = false,
                            deviceContextIncluded = false,
                            imageAttachmentCount = 0,
                            protectedSourceCount = stateAtStart.messages.count { message ->
                                message.privacy == MessagePrivacy.LocalOnly
                            },
                            rawContentPersisted = false,
                            protectedContentTypes = listOf(
                                "本地记忆",
                                "设备上下文",
                                "LocalOnly 历史",
                                "本地工具结果",
                            ),
                            deletableRecordTypes = listOf("对话消息", "Agent 轨迹"),
                        )
                    } else {
                        RunDataReceipt(
                            destination = RunDataDestination.Local,
                            currentPromptPrivacy = responsePrivacy.name,
                            remoteHistoryCount = 0,
                            localOnlyHistoryFilteredCount = 0,
                            memoryHitCount = 0,
                            memoryContextIncluded = false,
                            deviceContextIncluded = false,
                            imageAttachmentCount = 0,
                            protectedSourceCount = 0,
                            rawContentPersisted = false,
                            protectedContentTypes = emptyList(),
                            deletableRecordTypes = listOf("对话消息", "Agent 轨迹"),
                        )
                    }
                    generationSupport.finishOutputQualityGuardedGeneration(
                        runId = runId,
                        decision = decision,
                        receipt = continuationReceipt,
                        useRemoteModel = useRemoteModel,
                    )
                    return@launchGenerationJob
                }
                streamingUpdates.flush()
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
                    val remotePlan = (modelObservation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
                    val remoteToolBatch =
                        (modelObservation?.decision as? AgentObservationDecision.PlanToolBatch)?.plans
                    if (remotePlan == null && remoteToolBatch == null) {
                        uiState.updateLastAssistantLocalOnly(
                            "无法准备这个动作：${(modelObservation?.decision as? AgentObservationDecision.Fail)?.reason.orEmpty()}",
                        )
                    }
                }
                persistActiveSessionFromUi()
                rebuildMemoryIndex()
                val toolBatch = (modelObservation?.decision as? AgentObservationDecision.PlanToolBatch)?.plans
                if (toolBatch != null) {
                    val observedForBatch = modelObservation ?: error("远程批量工具调用缺少 Agent observation")
                    val assistantText = buildString {
                        if (partial.isNotBlank()) {
                            append(partial)
                            append("\n\n")
                        }
                        append("正在并行使用工具：${toolBatch.batchToolTitle()}")
                    }
                    uiState.updateLastAssistantLocalOnly(assistantText)
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
                    val assistantText = buildString {
                        if (partial.isNotBlank()) {
                            append(partial)
                            append("\n\n")
                        }
                        if (nextToolPlan.requiresUserConfirmation()) {
                            val modelLabel = if (useRemoteModel) "远程" else "模型"
                            append("已准备${modelLabel}动作草稿：${nextToolPlan.draft.title}\n请确认后再执行。")
                        } else {
                            append("正在使用工具：${nextToolPlan.draft.title}")
                        }
                    }
                    uiState.updateLastAssistantLocalOnly(assistantText)
                    persistActiveSessionFromUi()
                    handleNextToolPlan(
                        observedForPending.run.id,
                        nextToolPlan,
                        "下一步动作待确认",
                    )
                    return@launchGenerationJob
                }
                val observedForContinuation = modelObservation
                val continuationPrompt = observedForContinuation?.continuationPromptForModel
                if (observedForContinuation?.decision is AgentObservationDecision.ContinueWithModel &&
                    continuationPrompt != null
                ) {
                    uiState.updateLastAssistant("正在根据公开来源补充引用…")
                    persistActiveSessionFromUi()
                    continueAfterToolObservation(
                        runId = observedForContinuation.run.id,
                        promptForModel = continuationPrompt,
                        responsePrivacy = responsePrivacy,
                        remoteToolScope = observedForContinuation.continuationRemoteToolScope,
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
                val errorMessage = generationSupport.generationFailureMessage(throwable, useRemoteModel)
                runId?.let { id ->
                    assistantOrchestrator.failModelGeneration(id, errorMessage)
                }
                uiState.updateLastAssistant("出错了：$errorMessage")
                persistActiveSessionFromUi()
                uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = useRemoteModel && remoteConfig.isConfigured,
                        pendingConfirmation = null,
                        agentTraceRuns = loadAgentTraceRuns(),
                        activeRunTimeline = activeRunTimelineFor(runId),
                        modelHealth = it.failedGenerationModelHealth(
                            useRemoteModel = useRemoteModel,
                            reason = errorMessage,
                        ),
                        statusText = if (useRemoteModel) {
                            "远程生成失败"
                        } else {
                            "生成失败，建议重新加载"
                        },
                    )
                }
            }
        }
    }
}
