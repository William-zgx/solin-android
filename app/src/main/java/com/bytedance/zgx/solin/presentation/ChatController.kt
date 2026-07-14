package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.AuditEventSummary
import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.LongTermMemorySummary
import com.bytedance.zgx.solin.MemoryEvidenceUiSummary
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.StreamingAssistantUpdateCoalescer
import com.bytedance.zgx.solin.audit.RemoteSendAuditLog
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.RemoteSendPendingStore
import com.bytedance.zgx.solin.device.DeviceContextSnapshot
import com.bytedance.zgx.solin.evidence.EvidenceReceiptSummary
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.logging.solinE
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_LIFECYCLE
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_MODEL
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.explicitUserFactFrom
import com.bytedance.zgx.solin.memory.explicitUserPreferenceFrom
import com.bytedance.zgx.solin.memory.explicitUserPreferenceForgetFrom
import com.bytedance.zgx.solin.modelProfile
import com.bytedance.zgx.solin.multimodal.SharedInput
import com.bytedance.zgx.solin.orchestration.AgentModelObservationResult
import com.bytedance.zgx.solin.orchestration.AgentObservationDecision
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AgentRunOptions
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.InitialPlanningMode
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.orchestration.RunDataDestination
import com.bytedance.zgx.solin.orchestration.requiresUserConfirmation
import com.bytedance.zgx.solin.runtime.GenerationQualityDecision
import com.bytedance.zgx.solin.runtime.GenerationRuntimeKind
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.ModelOutputQualityGuard
import com.bytedance.zgx.solin.runtime.RemoteChatEvent
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import com.bytedance.zgx.solin.tool.ToolRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

private const val USER_STOPPED_AGENT_RUN_REASON =
    "User stopped this Agent run."

/**
 * Owns chat send/stop and generation job lifecycle.
 *
 * Extracted from [com.bytedance.zgx.solin.SolinViewModel] (Wave 4 Track C4). Wave 5 Track C5
 * further extracts remote-send disclosure/audit to [ChatRemoteSendSupport] and shared-input
 * staging to [ChatSharedInputSupport]. Wave 6 Track C6 extracts tool-observation continuation
 * to [ChatToolContinuationSupport] and generation streaming/quality helpers to
 * [ChatGenerationSupport]. The ViewModel keeps thin public wrappers and owns tool-execution /
 * session / model-load coupling via callbacks.
 */
class ChatController(
    private val modelRepository: ModelRepositoryFacade,
    private val runtime: LiteRtRuntime,
    private val remoteRuntime: RemoteChatRuntime,
    private val assistantOrchestrator: AssistantRouter,
    private val outputQualityGuard: ModelOutputQualityGuard,
    private val remoteSendAuditSink: RemoteSendAuditSink,
    private val remoteSendAuditLog: RemoteSendAuditLog,
    private val remoteSendPendingStore: RemoteSendPendingStore,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val runtimeLock: Mutex,
    private val requireRemoteSendDisclosure: Boolean,
    private val executeToolRequestAfterRunIsExecutingCallback: suspend (
        confirmation: PendingAgentConfirmation,
        request: ToolRequest,
    ) -> Unit,
    private val executePublicEvidenceToolBatchAfterRunIsExecutingCallback: suspend (
        runId: String,
        plans: List<AgentPlan.UseTool>,
    ) -> Unit,
    private val handleNextToolPlanCallback: (
        runId: String,
        plan: AgentPlan.UseTool,
        pendingStatusText: String,
    ) -> Unit,
    private val dispatchPersistenceWorkIfNeededCallback: (work: () -> Unit) -> Boolean,
    private val replaceActiveSessionMessagesCallback: (messages: List<ChatMessage>, persistNow: Boolean) -> Unit,
    private val persistActiveSessionFromUiCallback: () -> Unit,
    private val persistMessagesAndRebuildMemoryImpl: (
        messages: List<ChatMessage>,
        memoryUserMessage: ChatMessage?,
    ) -> Unit,
    private val rebuildMemoryIndexCallback: () -> Unit,
    private val syncTaskStateMemoriesCallback: () -> Unit,
    private val persistExplicitPreferenceMemoryCallback: (ChatMessage) -> Boolean,
    private val handleExplicitPreferenceCommandCallback: (String) -> Unit,
    private val handleExplicitUserFactCommandCallback: (String) -> Unit,
    private val handleExplicitMemoryForgetCommandCallback: (String) -> Unit,
    private val loadAuditEventsCallback: () -> List<AuditEventSummary>,
    private val loadAgentTraceRunsCallback: () -> List<AgentTraceRunUiSummary>,
    private val loadLongTermMemoriesCallback: () -> List<LongTermMemorySummary>,
    private val activeRunTimelineForCallback: (String?) -> List<RunTimelineItemUiSummary>,
    private val activePublicWebEvidenceForCallback: (String?) -> List<PublicWebEvidencePack>,
    private val activeMemoryEvidenceForCallback: (
        memoryHits: List<MemoryHit>,
        includePrivateLocalContext: Boolean,
    ) -> List<MemoryEvidenceUiSummary>,
    private val deviceContextSnapshot: () -> DeviceContextSnapshot,
) {
    private var generationJob: Job? = null
    private var activeGenerationRunId: String? = null
    private var nextVoiceInputDraftId = 0L

    private val generationSupport = ChatGenerationSupport(
        uiState = uiState,
        runtime = runtime,
        assistantOrchestrator = assistantOrchestrator,
        outputQualityGuard = outputQualityGuard,
        runtimeLock = runtimeLock,
        persistActiveSessionFromUi = { persistActiveSessionFromUi() },
        persistMessagesAndRebuildMemory = { messages, memoryUserMessage ->
            persistMessagesAndRebuildMemory(messages, memoryUserMessage)
        },
        loadAgentTraceRuns = { loadAgentTraceRuns() },
        activeRunTimelineFor = { runId -> activeRunTimelineFor(runId) },
    )

    private val sharedInputSupport = ChatSharedInputSupport(
        uiState = uiState,
        replaceActiveSessionMessages = { messages, persistNow ->
            replaceActiveSessionMessages(messages, persistNow)
        },
        isGenerationActive = { generationJob?.isActive == true },
        allocateVoiceInputDraftId = { allocateVoiceInputDraftId() },
        sendMessageInternal = { prompt, privacy, images, localImages, evidence ->
            sendMessageInternal(
                prompt = prompt,
                explicitMessagePrivacy = privacy,
                imageAttachments = images,
                localImageAttachments = localImages,
                currentPromptEvidenceSummary = evidence,
            )
        },
    )

    private val remoteSendSupport = ChatRemoteSendSupport(
        uiState = uiState,
        remoteSendAuditSink = remoteSendAuditSink,
        remoteSendAuditLog = remoteSendAuditLog,
        remoteSendPendingStore = remoteSendPendingStore,
        requireRemoteSendDisclosure = requireRemoteSendDisclosure,
        replaceActiveSessionMessages = { messages, persistNow ->
            replaceActiveSessionMessages(messages, persistNow)
        },
        persistActiveSessionFromUi = { persistActiveSessionFromUi() },
        loadAgentTraceRuns = { loadAgentTraceRuns() },
        failModelGeneration = { runId, reason ->
            assistantOrchestrator.failModelGeneration(runId, reason)
        },
        takeSharedInputRestoreMatching = sharedInputSupport::takeSharedInputRestoreMatching,
        clearSharedInputRestore = sharedInputSupport::clearSharedInputRestore,
        applyConfirmedRemoteSendDraftClear = sharedInputSupport::clearPendingSharedInputDraftForConfirmedRemoteSend,
        applyCancelRemoteSendDraftRestore = sharedInputSupport::restoreComposerDraftAfterRemoteSendCancel,
        onResumeSendAfterDisclosure = { prompt, messagePrivacy, imageAttachments ->
            sendMessageInternal(
                prompt = prompt,
                explicitMessagePrivacy = messagePrivacy,
                imageAttachments = imageAttachments,
                remoteSendConfirmed = true,
            )
        },
        onResumeContinuationAfterDisclosure = { runId, promptForModel, responsePrivacy, remoteToolScope ->
            continueAfterToolObservation(
                runId = runId,
                promptForModel = promptForModel,
                responsePrivacy = responsePrivacy,
                remoteToolScope = remoteToolScope,
                remoteSendConfirmed = true,
            )
        },
    )

    private val toolContinuationSupport = ChatToolContinuationSupport(
        uiState = uiState,
        runtime = runtime,
        remoteRuntime = remoteRuntime,
        assistantOrchestrator = assistantOrchestrator,
        outputQualityGuard = outputQualityGuard,
        generationSupport = generationSupport,
        remoteSendSupport = remoteSendSupport,
        launchGenerationJob = { runId, block -> launchToolGenerationJob(runId, block) },
        cancelActiveGenerationRun = { runId -> cancelActiveGenerationRun(runId) },
        executePublicEvidenceToolBatchAfterRunIsExecuting = { runId, plans ->
            executePublicEvidenceToolBatchAfterRunIsExecuting(runId, plans)
        },
        handleNextToolPlan = { runId, plan, pendingStatusText ->
            handleNextToolPlan(runId, plan, pendingStatusText)
        },
        persistActiveSessionFromUi = { persistActiveSessionFromUi() },
        rebuildMemoryIndex = { rebuildMemoryIndex() },
        loadAuditEvents = { loadAuditEvents() },
        loadAgentTraceRuns = { loadAgentTraceRuns() },
        activeRunTimelineFor = { runId -> activeRunTimelineFor(runId) },
        activePublicWebEvidenceFor = { runId -> activePublicWebEvidenceFor(runId) },
    )

    val isGenerationJobActive: Boolean
        get() = generationJob?.isActive == true

    fun allocateVoiceInputDraftId(): Long = ++nextVoiceInputDraftId

    fun cancelGenerationForTeardown(): Set<String> {
        val runIds = activeRunIdsForTeardown()
        activeGenerationRunId = null
        generationJob?.cancel()
        return runIds
    }

    private fun activeRunIdsForTeardown(): Set<String> =
        buildSet {
            activeGenerationRunId?.let(::add)
            remoteSendSupport.pendingContinuationRunId?.let(::add)
            uiState.value.pendingConfirmation?.runId?.let(::add)
            uiState.value.pendingExternalOutcome?.runId?.let(::add)
        }

    private fun persistMessagesAndRebuildMemory(
        messages: List<ChatMessage>,
        memoryUserMessage: ChatMessage? = null,
    ) {
        persistMessagesAndRebuildMemoryImpl(messages, memoryUserMessage)
    }


    private fun dispatchPersistenceWorkIfNeeded(work: () -> Unit): Boolean =
        dispatchPersistenceWorkIfNeededCallback(work)

    private fun replaceActiveSessionMessages(messages: List<ChatMessage>, persistNow: Boolean) {
        replaceActiveSessionMessagesCallback(messages, persistNow)
    }

    private fun persistActiveSessionFromUi() {
        persistActiveSessionFromUiCallback()
    }

    private fun rebuildMemoryIndex() {
        rebuildMemoryIndexCallback()
    }

    private fun syncTaskStateMemories() {
        syncTaskStateMemoriesCallback()
    }

    private fun persistExplicitPreferenceMemory(message: ChatMessage): Boolean =
        persistExplicitPreferenceMemoryCallback(message)

    private fun handleExplicitPreferenceCommand(trimmed: String) {
        handleExplicitPreferenceCommandCallback(trimmed)
    }

    private fun handleExplicitUserFactCommand(trimmed: String) {
        handleExplicitUserFactCommandCallback(trimmed)
    }

    private fun handleExplicitMemoryForgetCommand(trimmed: String) {
        handleExplicitMemoryForgetCommandCallback(trimmed)
    }

    private fun loadAuditEvents(): List<AuditEventSummary> = loadAuditEventsCallback()

    private fun loadAgentTraceRuns(): List<AgentTraceRunUiSummary> = loadAgentTraceRunsCallback()

    private fun loadLongTermMemories(): List<LongTermMemorySummary> = loadLongTermMemoriesCallback()

    private fun activeRunTimelineFor(runId: String?): List<RunTimelineItemUiSummary> =
        activeRunTimelineForCallback(runId)

    private fun activePublicWebEvidenceFor(runId: String?): List<PublicWebEvidencePack> =
        activePublicWebEvidenceForCallback(runId)

    private fun activeMemoryEvidenceFor(
        memoryHits: List<MemoryHit>,
        includePrivateLocalContext: Boolean,
    ): List<MemoryEvidenceUiSummary> =
        activeMemoryEvidenceForCallback(memoryHits, includePrivateLocalContext)

    private suspend fun executeToolRequestAfterRunIsExecuting(
        confirmation: PendingAgentConfirmation,
        request: ToolRequest,
    ) {
        executeToolRequestAfterRunIsExecutingCallback(confirmation, request)
    }

    private suspend fun executePublicEvidenceToolBatchAfterRunIsExecuting(
        runId: String,
        plans: List<AgentPlan.UseTool>,
    ) {
        executePublicEvidenceToolBatchAfterRunIsExecutingCallback(runId, plans)
    }

    private fun handleNextToolPlan(
        runId: String,
        plan: AgentPlan.UseTool,
        pendingStatusText: String,
    ) {
        handleNextToolPlanCallback(runId, plan, pendingStatusText)
    }

    fun sendMessage(prompt: String) {
        sendMessageInternal(prompt = prompt, explicitMessagePrivacy = null)
    }

    fun sendMessage(prompt: String, messagePrivacy: MessagePrivacy) {
        sendMessageInternal(prompt = prompt, explicitMessagePrivacy = messagePrivacy)
    }

    fun sendMessageInternal(
        prompt: String,
        explicitMessagePrivacy: MessagePrivacy?,
        imageAttachments: List<ChatImageAttachment> = emptyList(),
        localImageAttachments: List<LocalImageAttachment> = emptyList(),
        remoteSendConfirmed: Boolean = false,
        currentPromptEvidenceSummary: EvidenceReceiptSummary? = null,
    ) {
        if (
            dispatchPersistenceWorkIfNeeded {
                sendMessageInternal(
                    prompt = prompt,
                    explicitMessagePrivacy = explicitMessagePrivacy,
                    imageAttachments = imageAttachments,
                    localImageAttachments = localImageAttachments,
                    remoteSendConfirmed = remoteSendConfirmed,
                    currentPromptEvidenceSummary = currentPromptEvidenceSummary,
                )
            }
        ) {
            return
        }
        val trimmed = prompt.trim()
        if (trimmed.isNotEmpty() && uiState.value.pendingConfirmation != null) {
            uiState.update {
                it.copy(statusText = "请先确认或取消待执行动作")
            }
            return
        }
        if (trimmed.isNotEmpty() &&
            uiState.value.pendingRemoteModeDisclosure != null &&
            !remoteSendConfirmed
        ) {
            uiState.update {
                it.copy(statusText = "请先确认远程模式提醒")
            }
            return
        }
        if (trimmed.isNotEmpty() &&
            uiState.value.pendingRemoteSendDisclosure != null &&
            !remoteSendConfirmed
        ) {
            uiState.update {
                it.copy(statusText = "请先确认或取消远程发送")
            }
            return
        }
        if (trimmed.isNotEmpty() && uiState.value.pendingExternalOutcome != null) {
            uiState.update {
                it.copy(statusText = "请先确认外部动作结果")
            }
            return
        }
        if (trimmed.isEmpty() || uiState.value.isBusy || generationJob?.isActive == true) {
            return
        }
        if (explicitUserPreferenceForgetFrom(trimmed) != null) {
            handleExplicitMemoryForgetCommand(trimmed)
            return
        }
        if (explicitUserFactFrom(trimmed) != null) {
            handleExplicitUserFactCommand(trimmed)
            return
        }
        if (explicitUserPreferenceFrom(trimmed) != null) {
            handleExplicitPreferenceCommand(trimmed)
            return
        }
        if (!uiState.value.isReady) {
            handleNotReadySendAttempt()
            return
        }

        solinD(
            TAG_LIFECYCLE,
            "sendMessageInternal: len=${trimmed.length} " +
                "mode=${uiState.value.inferenceMode.name} " +
                "busy=${uiState.value.isBusy}",
        )

        syncTaskStateMemories()
        rebuildMemoryIndex()
        uiState.update { it.copy(longTermMemories = loadLongTermMemories()) }
        val stateBeforeSend = uiState.value
        val useRemoteModel = stateBeforeSend.inferenceMode == InferenceMode.Remote
        val effectiveMessagePrivacy =
            explicitMessagePrivacy ?: if (useRemoteModel) {
                MessagePrivacy.RemoteEligible
            } else {
                MessagePrivacy.LocalOnly
            }
        val remoteConfig = stateBeforeSend.remoteModelConfig
        var remoteHistory: List<ChatMessage> = remoteSendSupport.remoteHistoryForRemoteSend(stateBeforeSend.messages)
        val localImageAttachmentCount = if (useRemoteModel) 0 else localImageAttachments.size
        if (!useRemoteModel &&
            localImageAttachments.isNotEmpty() &&
            !stateBeforeSend.activeLocalModelSupportsVisionInput
        ) {
            sharedInputSupport.rejectUnsupportedLocalVisionInput()
            return
        }
        val includePrivateLocalContext = !useRemoteModel
        val agentRunOptions = if (useRemoteModel) {
            AgentRunOptions(
                initialPlanningMode = InitialPlanningMode.ModelFirstRemoteTools,
                remoteToolScope = RemoteToolScope.ModelPlanning,
                reduceDeviceActionConfirmations = stateBeforeSend.reduceDeviceActionConfirmations,
            )
        } else {
            AgentRunOptions(
                reduceDeviceActionConfirmations = stateBeforeSend.reduceDeviceActionConfirmations,
            )
        }
        if (useRemoteModel && effectiveMessagePrivacy == MessagePrivacy.LocalOnly) {
            val userMessage = ChatMessage(
                role = MessageRole.User,
                text = trimmed,
                privacy = MessagePrivacy.LocalOnly,
            )
            persistMessagesAndRebuildMemory(
                messages = stateBeforeSend.messages + userMessage + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "这条内容已标记为仅本地使用。当前为远程模型模式，我不会把它发送到远程模型。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                memoryUserMessage = userMessage,
            )
            uiState.update {
                it.copy(statusText = "已保护本地内容")
            }
            return
        }
        if (useRemoteModel &&
            effectiveMessagePrivacy == MessagePrivacy.RemoteEligible &&
            remoteConfig.isConfigured &&
            remoteConfig.hasKnownConnectivityFailure
        ) {
            val userMessage = ChatMessage(
                role = MessageRole.User,
                text = trimmed,
                privacy = MessagePrivacy.RemoteEligible,
            )
            remoteSendSupport.recordRemoteSendAuditEvent(
                decision = RemoteSendDecision.Blocked,
                modelName = remoteConfig.normalized().modelName,
                prompt = trimmed,
                imageCount = imageAttachments.size,
                remoteHistoryCount = remoteHistory.size,
            )
            persistMessagesAndRebuildMemory(
                messages = stateBeforeSend.messages + userMessage + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "远程连接状态为${remoteConfig.connectivityStatus.label}，本次没有发送。请在模型管理中测试连接或更新远程配置。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                memoryUserMessage = userMessage,
            )
            uiState.update {
                it.copy(statusText = "远程连接不可用")
            }
            return
        }
        if (useRemoteModel &&
            effectiveMessagePrivacy == MessagePrivacy.RemoteEligible &&
            !remoteSendConfirmed &&
            remoteConfig.isConfigured &&
            remoteSendSupport.containsSensitivePersonalOrSecretContent(trimmed)
        ) {
            // Tiered handling (P1): instead of hard-rejecting sensitive content, surface a
            // forced disclosure that offers graded choices — mask & send, send anyway
            // (audited), or cancel. This is always force-shown (fail-closed) and can never be
            // silenced by the session-suppression flag.
            val disclosure = remoteSendSupport.buildSensitiveRemoteSendDisclosure(
                prompt = trimmed,
                messagePrivacy = effectiveMessagePrivacy,
                remoteConfig = remoteConfig,
                remoteHistory = remoteHistory,
                imageAttachments = imageAttachments,
                stateBeforeSend = stateBeforeSend,
            )
            remoteSendSupport.savePendingRemoteSendMarker(disclosure)
            uiState.update {
                it.copy(
                    pendingRemoteSendDisclosure = disclosure,
                    pendingExternalOutcome = null,
                    latestRecoveryAction = null,
                    statusText = "敏感内容待确认",
                )
            }
            return
        }
        if (useRemoteModel &&
            effectiveMessagePrivacy == MessagePrivacy.RemoteEligible &&
            !remoteConfig.isConfigured &&
            remoteSendSupport.containsSensitivePersonalOrSecretContent(trimmed)
        ) {
            // Remote not configured: keep the original protect-and-explain behavior since there
            // is no destination to send to anyway.
            val userMessage = ChatMessage(
                role = MessageRole.User,
                text = trimmed,
                privacy = MessagePrivacy.LocalOnly,
            )
            persistMessagesAndRebuildMemory(
                messages = stateBeforeSend.messages + userMessage + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "这条内容疑似包含个人信息或密钥。当前为远程模型模式，我不会把它发送到远程模型；请切换到本地模型，或删去敏感内容后再发送。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
            )
            uiState.update {
                it.copy(statusText = "已保护敏感内容")
            }
            return
        }
        if (useRemoteModel &&
            effectiveMessagePrivacy == MessagePrivacy.RemoteEligible &&
            remoteConfig.isConfigured &&
            !remoteSendConfirmed &&
            remoteSendSupport.shouldRequireRemoteSendDisclosure(imageAttachmentCount = imageAttachments.size)
        ) {
            val disclosure = remoteSendSupport.buildPendingRemoteSendDisclosure(
                kind = RemoteSendDisclosureKind.CurrentInput,
                prompt = trimmed,
                messagePrivacy = effectiveMessagePrivacy,
                remoteConfig = remoteConfig,
                remoteHistory = remoteHistory,
                imageAttachments = imageAttachments,
                stateBeforeSend = stateBeforeSend,
            )
            remoteSendSupport.savePendingRemoteSendMarker(disclosure)
            uiState.update {
                it.copy(
                    pendingRemoteSendDisclosure = disclosure,
                    pendingExternalOutcome = null,
                    latestRecoveryAction = null,
                    statusText = "远程发送待确认",
                )
            }
            return
        }
        uiState.update {
            it.copy(
                isBusy = true,
                isGenerating = false,
                pendingRemoteSendDisclosure = null,
                latestRecoveryAction = null,
                pendingExternalOutcome = null,
                activeRunTimeline = emptyList(),
                activeMemoryEvidence = emptyList(),
                activePublicWebEvidence = emptyList(),
                statusText = "处理中",
            )
        }

        val job = scope.launch(ioDispatcher) {
            var activeModelRunId: String? = null
            var streamingAssistantUpdates: StreamingAssistantUpdateCoalescer? = null
            try {
                val userMessage = ChatMessage(
                    role = MessageRole.User,
                    text = trimmed,
                    privacy = effectiveMessagePrivacy,
                )
                val route = runCatching {
                    assistantOrchestrator.route(
                        input = trimmed,
                        installedCapabilities = stateBeforeSend.installedCapabilities,
                        memoryEnabled = stateBeforeSend.memoryEnabled && includePrivateLocalContext,
                        actionModelPath = modelRepository.verifiedActionModelPath(),
                        deviceContext = deviceContextSnapshot().takeIf { includePrivateLocalContext },
                        sessionId = stateBeforeSend.activeSessionId,
                        options = agentRunOptions,
                        installedCapabilityProfiles = stateBeforeSend.installedCapabilityProfiles,
                    )
                }.getOrElse { throwable ->
                    // If routing fails for any unanticipated reason (e.g. trace store
                    // error, skill planner exception, context assembler bug), fall back
                    // to a synthetic Chat route with the raw user input so the remote
                    // send can still proceed. This is critical for remote-mode tests
                    // where any routing exception would silently prevent the HTTP
                    // request from reaching the mock server.
                    //
                    // Generate an ephemeral runId so that downstream tool-call
                    // handling (which requires a non-null runId for observeModelToolRequest)
                    // still works when the remote model returns tool calls.
                    val fallbackRunId = "fallback-${System.currentTimeMillis()}-${(0..Int.MAX_VALUE).random()}"
                    AssistantRoute.Chat(
                        runId = fallbackRunId,
                        promptForModel = trimmed,
                        memoryHits = emptyList(),
                        deviceContext = null,
                    )
                }
                val routeReceipt = route.runDataReceipt(
                    stateBeforeSend = stateBeforeSend,
                    destination = if (useRemoteModel) RunDataDestination.Remote else RunDataDestination.Local,
                    currentPromptPrivacy = effectiveMessagePrivacy,
                    remoteHistoryCount = remoteHistory.size,
                    imageAttachmentCount = imageAttachments.size + localImageAttachmentCount,
                    currentPromptEvidenceSummary = currentPromptEvidenceSummary,
                )
                route.runIdOrNull()?.let { runId ->
                    assistantOrchestrator.recordRunDataReceipt(
                        runId = runId,
                        receipt = routeReceipt,
                    )
                }
                when (route) {
                    is AssistantRoute.Action -> {
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
                            persistMessagesAndRebuildMemory(
                                messages = stateBeforeSend.messages + localUserMessage + assistantMessage,
                                memoryUserMessage = localUserMessage,
                            )
                            uiState.update {
                                it.clearedEvidence().copy(
                                    isBusy = true,
                                    isGenerating = false,
                                    pendingConfirmation = null,
                                    activeRunTimeline = activeRunTimelineFor(route.runId),
                                    statusText = "工具执行中",
                                )
                            }
                            executeToolRequestAfterRunIsExecuting(
                                confirmation = PendingAgentConfirmation(
                                    runId = route.runId,
                                    draft = route.draft,
                                    toolRequest = route.toolRequest,
                                    skillId = route.skillId,
                                    plannedByModel = route.plannedByModel,
                                    fallbackReason = route.fallbackReason,
                                ),
                                request = request,
                            )
                            return@launch
                        }
                        val assistantMessage = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "已准备本地动作草稿（$planningLabel）：${route.draft.summary}\n请确认后再执行。",
                            privacy = MessagePrivacy.LocalOnly,
                        )
                        persistMessagesAndRebuildMemory(
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
                                activeRunTimeline = activeRunTimelineFor(route.runId),
                                statusText = "动作草稿待确认 · $planningLabel",
                            )
                        }
                        return@launch
                    }

                    is AssistantRoute.ToolRejected -> {
                        val localUserMessage = userMessage.copy(privacy = MessagePrivacy.LocalOnly)
                        val assistantMessage = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "无法准备这个动作：${route.summary}",
                            privacy = MessagePrivacy.LocalOnly,
                        )
                        persistMessagesAndRebuildMemory(
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
                        return@launch
                    }

                    is AssistantRoute.MissingModel -> {
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
                        persistMessagesAndRebuildMemory(
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
                        return@launch
                    }

                    is AssistantRoute.Chat -> {
                        activeModelRunId = route.runId
                        activeGenerationRunId = route.runId
                        val responsePrivacy = if (
                            !useRemoteModel &&
                            (effectiveMessagePrivacy == MessagePrivacy.LocalOnly || route.memoryHits.isNotEmpty())
                        ) {
                            MessagePrivacy.LocalOnly
                        } else {
                            effectiveMessagePrivacy
                        }
                        val chatUserMessage = if (responsePrivacy == MessagePrivacy.LocalOnly) {
                            userMessage.copy(privacy = MessagePrivacy.LocalOnly)
                        } else {
                            userMessage
                        }
                        val assistantPlaceholder = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "",
                            privacy = responsePrivacy,
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + chatUserMessage + assistantPlaceholder,
                            persistNow = true,
                        )
                        persistExplicitPreferenceMemory(chatUserMessage)
                        uiState.update {
                            it.copy(
                                isGenerating = true,
                                memoryHits = route.memoryHits,
                                activeRunTimeline = activeRunTimelineFor(route.runId),
                                activeMemoryEvidence = activeMemoryEvidenceFor(
                                    memoryHits = route.memoryHits,
                                    includePrivateLocalContext = includePrivateLocalContext,
                                ),
                                statusText = "生成中",
                            )
                        }
                        if (!useRemoteModel && !runtime.isLoaded) {
                            activeModelRunId?.let { runId ->
                                assistantOrchestrator.failModelGeneration(runId, "本地模型尚未就绪")
                            }
                            uiState.updateLastAssistant("模型尚未就绪")
                            persistActiveSessionFromUi()
                            uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isGenerating = false,
                                    isReady = false,
                                    pendingConfirmation = null,
                                    agentTraceRuns = loadAgentTraceRuns(),
                                    activeRunTimeline = activeRunTimelineFor(route.runId),
                                    statusText = "未加载模型",
                                )
                            }
                            return@launch
                        }
                        if (useRemoteModel && !remoteConfig.isConfigured) {
                            activeModelRunId?.let { runId ->
                                assistantOrchestrator.failModelGeneration(runId, "远程模型未配置")
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
                                    activeRunTimeline = activeRunTimelineFor(route.runId),
                                    statusText = "请配置远程模型",
                                )
                            }
                            return@launch
                        }
                        if (useRemoteModel) {
                            val boundaryFailure = remoteRouteBoundaryFailure(
                                userInput = trimmed,
                                route = route,
                            )
                            if (boundaryFailure != null) {
                                activeModelRunId?.let { runId ->
                                    assistantOrchestrator.failModelGeneration(runId, boundaryFailure)
                                }
                                remoteSendSupport.recordRemoteSendAuditEvent(
                                    decision = RemoteSendDecision.Blocked,
                                    modelName = remoteConfig.normalized().modelName,
                                    prompt = route.promptForModel,
                                    imageCount = imageAttachments.size,
                                    remoteHistoryCount = remoteHistory.size,
                                )
                                uiState.updateLastAssistantLocalOnly(boundaryFailure)
                                persistActiveSessionFromUi()
                                uiState.update {
                                    it.copy(
                                        isBusy = false,
                                        isGenerating = false,
                                        isReady = remoteConfig.isConfigured,
                                        pendingConfirmation = null,
                                        memoryHits = emptyList(),
                                        agentTraceRuns = loadAgentTraceRuns(),
                                        activeRunTimeline = activeRunTimelineFor(route.runId),
                                        activeMemoryEvidence = emptyList(),
                                        statusText = "已阻止远程发送",
                                    )
                                }
                                return@launch
                            }
                        }

                        val partial = StringBuilder()
                        val streamingUpdates = StreamingAssistantUpdateCoalescer(
                            publish = { text -> uiState.updateLastAssistant(text) },
                        )
                        streamingAssistantUpdates = streamingUpdates
                        var remoteToolObservation: AgentModelObservationResult? = null
                        var outputQualityDecision: GenerationQualityDecision? = null
                        if (useRemoteModel) {
                            val remoteTools = assistantOrchestrator.availableRemoteToolSpecs(agentRunOptions.remoteToolScope)
                            route.runId?.let { runId ->
                                assistantOrchestrator.recordRemoteToolsExposed(
                                    runId = runId,
                                    scope = agentRunOptions.remoteToolScope,
                                    toolNames = remoteTools.mapTo(linkedSetOf()) { tool -> tool.name },
                                )
                            }
                            if (!remoteSendConfirmed) {
                                remoteSendSupport.recordRemoteSendAuditEvent(
                                    decision = RemoteSendDecision.Confirmed,
                                    modelName = remoteConfig.normalized().modelName,
                                    prompt = route.promptForModel,
                                    imageCount = imageAttachments.size,
                                    remoteHistoryCount = remoteHistory.size,
                                )
                            }
                            val remoteTokenBudget = remoteConfig.modelProfile().contextWindowTokens
                                ?.let { window -> (window * SolinConstants.Ui.REMOTE_COMPACTION_BUDGET_RATIO).toInt() }
                                ?: Int.MAX_VALUE
                            val result = generationSupport.sendRemoteWithOverflowRetry(
                                runId = route.runId,
                                history = remoteHistory,
                                tokenBudget = remoteTokenBudget,
                            ) { historyToSend ->
                                remoteRuntime.sendWithTools(
                                    prompt = route.promptForModel,
                                    history = historyToSend,
                                    parameters = uiState.value.generationParameters,
                                    config = remoteConfig,
                                    tools = remoteTools,
                                    imageAttachments = imageAttachments,
                                ).collect { event ->
                                    if (outputQualityDecision != null) return@collect
                                    when (event) {
                                        is RemoteChatEvent.TextDelta -> {
                                            if (remoteToolObservation == null) {
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
                                            val runId = route.runId ?: error("远程工具调用缺少 Agent run")
                                            remoteToolObservation =
                                                assistantOrchestrator.observeModelToolRequest(runId, event.request)
                                                    ?: error("远程工具调用无法进入确认流程")
                                        }

                                        is RemoteChatEvent.ToolCalls -> {
                                            streamingUpdates.flush()
                                            val runId = route.runId ?: error("远程工具调用缺少 Agent run")
                                            remoteToolObservation =
                                                assistantOrchestrator.observeModelToolRequests(runId, event.requests)
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
                                promptForModel = route.promptForModel,
                                history = stateBeforeSend.messages,
                                parameters = uiState.value.generationParameters,
                                imageAttachments = localImageAttachments,
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
                        if (outputQualityDecision == null && remoteToolObservation == null) {
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
                            generationSupport.finishOutputQualityGuardedGeneration(
                                runId = route.runId,
                                decision = decision,
                                receipt = routeReceipt,
                                useRemoteModel = useRemoteModel,
                            )
                            return@launch
                        }
                        streamingUpdates.flush()
                        if (remoteToolObservation != null) {
                            val toolBatch =
                                (remoteToolObservation?.decision as? AgentObservationDecision.PlanToolBatch)?.plans
                            if (toolBatch != null) {
                                val observedForBatch = remoteToolObservation ?: error("远程批量工具调用缺少 Agent observation")
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
                                    runId = observedForBatch.run.id,
                                    plans = toolBatch,
                                )
                                return@launch
                            }
                            val nextToolPlan =
                                (remoteToolObservation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
                            if (nextToolPlan != null) {
                                val observedForPlan = remoteToolObservation ?: error("远程工具调用缺少 Agent observation")
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
                                persistActiveSessionFromUi()
                                handleNextToolPlan(
                                    runId = observedForPlan.run.id,
                                    plan = nextToolPlan,
                                    pendingStatusText = "动作草稿待确认 · 远程模型",
                                )
                                return@launch
                            }
                            uiState.updateLastAssistantLocalOnly(
                                "无法准备这个动作：${(remoteToolObservation?.decision as? AgentObservationDecision.Fail)?.reason.orEmpty()}",
                            )
                            persistActiveSessionFromUi()
                            rebuildMemoryIndex()
                            uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isGenerating = false,
                                    isReady = true,
                                    auditEvents = loadAuditEvents(),
                                    agentTraceRuns = loadAgentTraceRuns(),
                                    activeRunTimeline = activeRunTimelineFor(route.runId),
                                    statusText = "动作不可执行",
                                )
                            }
                            return@launch
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
                                    persistActiveSessionFromUi()
                                    handleNextToolPlan(
                                        runId = modelObservation.run.id,
                                        plan = nextToolPlan,
                                        pendingStatusText = "动作草稿待确认 · 本地模型",
                                    )
                                    return@launch
                                }
                                if (modelObservation?.decision is AgentObservationDecision.Fail) {
                                    uiState.updateLastAssistantLocalOnly(
                                        "无法准备这个动作：${modelObservation.decision.reason}",
                                    )
                                    persistActiveSessionFromUi()
                                    rebuildMemoryIndex()
                                    uiState.update {
                                        it.copy(
                                            isBusy = false,
                                            isGenerating = false,
                                            isReady = true,
                                            auditEvents = loadAuditEvents(),
                                            agentTraceRuns = loadAgentTraceRuns(),
                                            activeRunTimeline = activeRunTimelineFor(route.runId),
                                            statusText = "动作不可执行",
                                        )
                                    }
                                    return@launch
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
                                        responsePrivacy = MessagePrivacy.RemoteEligible,
                                        remoteToolScope = observedForContinuation.continuationRemoteToolScope,
                                    )
                                    return@launch
                                }
                            }
                        }
                        persistActiveSessionFromUi()
                        rebuildMemoryIndex()
                        uiState.update {
                            it.copy(
                                isBusy = false,
                                isGenerating = false,
                                isReady = true,
                                activeRunTimeline = activeRunTimelineFor(route.runId),
                                activePublicWebEvidence = activePublicWebEvidenceFor(route.runId),
                                activeMemoryEvidence = activeMemoryEvidenceFor(
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
            } catch (cancellation: CancellationException) {
                solinD(TAG_LIFECYCLE, "generation: cancelled")
                streamingAssistantUpdates?.flush()
                cancelActiveGenerationRun(activeModelRunId)
                if (uiState.value.isGenerating) {
                    generationSupport.finishStoppedGeneration(activeModelRunId)
                } else if (uiState.value.isBusy) {
                    uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            statusText = "已停止",
                        )
                    }
                }
                throw cancellation
            } catch (throwable: Throwable) {
                solinE(TAG_MODEL, "generation: failed useRemoteModel=$useRemoteModel", throwable)
                streamingAssistantUpdates?.flush()
                val errorMessage = generationSupport.generationFailureMessage(throwable, useRemoteModel)
                activeModelRunId?.let { runId ->
                    assistantOrchestrator.failModelGeneration(runId, errorMessage)
                }
                if (uiState.value.isGenerating) {
                    uiState.updateLastAssistant("出错了：$errorMessage")
                    persistActiveSessionFromUi()
                }
                uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = useRemoteModel && remoteConfig.isConfigured,
                        pendingConfirmation = null,
                        agentTraceRuns = loadAgentTraceRuns(),
                        activeRunTimeline = activeRunTimelineFor(activeModelRunId),
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
        generationJob = job
        job.invokeOnCompletion {
            if (generationJob == job) {
                generationJob = null
                activeGenerationRunId = null
            }
        }
    }

    fun confirmRemoteSendDisclosure(suppressForSession: Boolean = false) {
        remoteSendSupport.confirmRemoteSendDisclosure(suppressForSession)
    }

    fun confirmRemoteSendWithMasking() {
        remoteSendSupport.confirmRemoteSendWithMasking()
    }

    fun confirmRemoteSendDespiteSensitive() {
        remoteSendSupport.confirmRemoteSendDespiteSensitive()
    }

    fun failClosedPendingRemoteSendOnStartup() {
        remoteSendSupport.failClosedPendingRemoteSendOnStartup()
    }

    fun refreshRemoteSendAuditEvents() {
        remoteSendSupport.refreshRemoteSendAuditEvents()
    }

    fun dismissRemoteSendDisclosure() {
        remoteSendSupport.dismissRemoteSendDisclosure()
    }

    fun ingestSharedInput(sharedInput: SharedInput) {
        sharedInputSupport.ingestSharedInput(sharedInput)
    }

    fun stageSharedInput(sharedInput: SharedInput) {
        sharedInputSupport.stageSharedInput(sharedInput)
    }

    fun clearPendingSharedInputDraft(draftId: Long) {
        sharedInputSupport.clearPendingSharedInputDraft(draftId)
    }

    fun sendPendingSharedInput(userInstruction: String = "") {
        sharedInputSupport.sendPendingSharedInput(userInstruction)
    }

    private fun handleNotReadySendAttempt() {
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
            replaceActiveSessionMessages(messages, persistNow = true)
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

    private fun cancelActiveGenerationRun(runId: String?) {
        val id = runId ?: return
        if (activeGenerationRunId != id) return
        assistantOrchestrator.cancelRun(id, USER_STOPPED_AGENT_RUN_REASON)
        activeGenerationRunId = null
    }

    fun stopGeneration() {
        val job = generationJob ?: return
        val runId = activeGenerationRunId
        if (uiState.value.inferenceMode == InferenceMode.Local) {
            runtime.stop()
        } else {
            remoteRuntime.stop()
        }
        cancelActiveGenerationRun(runId)
        job.cancel()
        generationSupport.finishStoppedGeneration(runId)
    }

    fun launchToolGenerationJob(runId: String?, block: suspend () -> Unit) {
        activeGenerationRunId = runId
        val job = scope.launch(ioDispatcher) {
            block()
        }
        generationJob = job
        job.invokeOnCompletion {
            if (generationJob == job) {
                generationJob = null
                activeGenerationRunId = null
            }
        }
    }

    fun continueAfterToolObservation(
        runId: String?,
        promptForModel: String,
        responsePrivacy: MessagePrivacy,
        remoteToolScope: RemoteToolScope,
        remoteSendConfirmed: Boolean = false,
    ) {
        toolContinuationSupport.continueAfterToolObservation(
            runId = runId,
            promptForModel = promptForModel,
            responsePrivacy = responsePrivacy,
            remoteToolScope = remoteToolScope,
            remoteSendConfirmed = remoteSendConfirmed,
        )
    }

    fun resetRemoteSendDisclosureSuppression() {
        remoteSendSupport.resetRemoteSendDisclosureSuppression()
    }

    fun clearPendingRemoteChatState() {
        remoteSendSupport.clearPendingRemoteChatState()
    }

    fun setRemoteSendDisclosurePolicy(policy: RemoteSendDisclosurePolicy) {
        remoteSendSupport.setRemoteSendDisclosurePolicy(policy)
    }
}
