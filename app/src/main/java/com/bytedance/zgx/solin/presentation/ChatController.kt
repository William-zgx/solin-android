package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AdaptiveInferenceRollout
import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PendingRemoteSendDisclosure
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteConnectivitySnapshot
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
import com.bytedance.zgx.solin.multimodal.SharedInput
import com.bytedance.zgx.solin.orchestration.ActiveRunPlacementPermit
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AgentRunOptions
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.ModelPlacementInput
import com.bytedance.zgx.solin.orchestration.ModelPlacementPolicy
import com.bytedance.zgx.solin.orchestration.PrepareChatRunRequest
import com.bytedance.zgx.solin.orchestration.PrepareChatRunResult
import com.bytedance.zgx.solin.orchestration.PreparedChatDispatcher
import com.bytedance.zgx.solin.orchestration.PreparedChatBindingStore
import com.bytedance.zgx.solin.orchestration.PreparedChatPlacementPolicy
import com.bytedance.zgx.solin.orchestration.DecisionModelPlacementPolicy
import com.bytedance.zgx.solin.orchestration.PreparedChatRevisionValidator
import com.bytedance.zgx.solin.orchestration.PreparedChatRun
import com.bytedance.zgx.solin.orchestration.PreparedChatRunCoordinator
import com.bytedance.zgx.solin.orchestration.PreparedChatRunningCall
import com.bytedance.zgx.solin.orchestration.PlacementDecision
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.orchestration.RunDataDestination
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.orchestration.RunPlacementBinding
import com.bytedance.zgx.solin.orchestration.toRunDataDestination
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.ModelOutputQualityGuard
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import com.bytedance.zgx.solin.resource.StableResourceState
import com.bytedance.zgx.solin.tool.ToolRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val USER_STOPPED_AGENT_RUN_REASON =
    "User stopped this Agent run."

private const val LOCAL_ONLY_INPUT_KEPT_LOCAL_REPLY =
    "这条内容已标记为仅本地使用。当前为远程模型模式，我不会把它发送到远程模型。"

/**
 * Owns chat send/stop and generation job lifecycle.
 *
 * Collaborators: remote-send disclosure/audit in [ChatRemoteSendSupport], shared-input staging in
 * [ChatSharedInputSupport], tool-observation continuation in [ChatToolContinuationSupport], and
 * generation streaming/quality in [ChatGenerationSupport]. Tool execution, session and model-load
 * coupling are injected.
 */
class ChatController internal constructor(
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
    private val adaptiveInferenceRollout: AdaptiveInferenceRollout,
    /** Cloud/edge router for Auto runs; null keeps the rules alone. */
    private val decisionModelPlacementPolicy: DecisionModelPlacementPolicy? = null,
    private val chatPlacementRuntime: ChatPlacementRuntime,
    private val stableResourceStateProvider: () -> StableResourceState,
    private val bootCountProvider: () -> Long,
    private val elapsedRealtimeMillis: () -> Long,
    private val currentRemoteConfig: () -> RemoteModelConfig,
    private val remoteConnectivity: (RemoteModelConfig) -> RemoteConnectivitySnapshot?,
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
    private val sessionHost: PresentationSessionHost,
    private val deviceContextSnapshot: () -> DeviceContextSnapshot,
) {
    private var generationJob: Job? = null
    private var activeGenerationRunId: String? = null
    private val generationStreams = GenerationStreamCoordinator()
    private var nextVoiceInputDraftId = 0L
    private val midRunSteering = ChatMidRunSteering(router = assistantOrchestrator, uiState = uiState)

    private val generationSupport = ChatGenerationSupport(
        uiState = uiState,
        runtime = runtime,
        remoteRuntime = remoteRuntime,
        assistantOrchestrator = assistantOrchestrator,
        outputQualityGuard = outputQualityGuard,
        runtimeLock = runtimeLock,
        persistActiveSessionFromUi = { sessionHost.persistActiveSessionFromUi() },
        persistMessagesAndRebuildMemory = { messages, memoryUserMessage ->
            sessionHost.persistMessagesAndRebuildMemory(messages, memoryUserMessage)
        },
        loadAgentTraceRuns = { sessionHost.loadAgentTraceRuns() },
        activeRunTimelineFor = { runId -> sessionHost.activeRunTimelineFor(runId) },
    )

    private val preparedChatExecutions = ConcurrentHashMap<String, PreparedChatExecution>()
    private val deferredAudit: DeferredRemoteSendAudit = DeferredRemoteSendAudit(
        auditSink = remoteSendAuditSink,
        uiState = uiState,
        replaceActiveSessionMessages = { messages, persistNow ->
            sessionHost.replaceActiveSessionMessages(messages, persistNow)
        },
        refreshAuditEvents = { remoteSendSupport.refreshRemoteSendAuditEvents() },
    )
    private val preparedChatRunCoordinator = PreparedChatRunCoordinator(
        policy = PreparedChatPlacementPolicy { input, routingText ->
            decisionModelPlacementPolicy?.decide(input, routingText) ?: ModelPlacementPolicy.decide(input)
        },
        bindingStore = object : PreparedChatBindingStore {
            override fun bindAndReserve(binding: RunPlacementBinding) = bindAndShowPlacement(binding)

            override fun abort(permit: ActiveRunPlacementPermit) {
                chatPlacementRuntime.abort(permit)
            }
        },
        dispatcher = PreparedChatDispatcher { prepared ->
            val execution = preparedChatExecutions[prepared.runId]
                ?: error("Prepared Chat execution is missing for ${prepared.runId}")
            object : PreparedChatRunningCall {
                override suspend fun awaitCompletion() {
                    try {
                        dispatchPreparedExecution(prepared, execution)
                    } finally {
                        preparedChatExecutions.remove(prepared.runId, execution)
                        deferredAudit.discard(prepared.runId)
                    }
                }

                override fun stop() {
                    chatPlacementRuntime.stop(prepared.runId)
                }
            }
        },
        revisionValidator = PreparedChatRevisionValidator { expected ->
            currentRemoteConfig().normalized().profileRevision == expected
        },
    )

    private val sharedInputSupport = ChatSharedInputSupport(
        uiState = uiState,
        replaceActiveSessionMessages = { messages, persistNow ->
            sessionHost.replaceActiveSessionMessages(messages, persistNow)
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

    private val remoteSendSupport: ChatRemoteSendSupport = ChatRemoteSendSupport(
        uiState = uiState,
        remoteSendAuditSink = deferredAudit.claimGatedSink,
        remoteSendAuditLog = remoteSendAuditLog,
        remoteSendPendingStore = remoteSendPendingStore,
        requireRemoteSendDisclosure = requireRemoteSendDisclosure,
        replaceActiveSessionMessages = { messages, persistNow ->
            sessionHost.replaceActiveSessionMessages(messages, persistNow)
        },
        persistActiveSessionFromUi = { sessionHost.persistActiveSessionFromUi() },
        loadAgentTraceRuns = { sessionHost.loadAgentTraceRuns() },
        failModelGeneration = { runId, reason ->
            assistantOrchestrator.failModelGeneration(runId, reason)
        },
        takeSharedInputRestoreMatching = sharedInputSupport::takeSharedInputRestoreMatching,
        clearSharedInputRestore = sharedInputSupport::clearSharedInputRestore,
        applyConfirmedRemoteSendDraftClear = sharedInputSupport::clearPendingSharedInputDraftForConfirmedRemoteSend,
        applyCancelRemoteSendDraftRestore = sharedInputSupport::restoreComposerDraftAfterRemoteSendCancel,
        onResumeSendAfterDisclosure = { pending, promptOverride ->
            resumePreparedChatAfterDisclosure(pending, promptOverride)
        },
        onDiscardPreparedSend = { runId -> discardPreparedChat(runId) },
        onResumeContinuationAfterDisclosure = { runId, promptForModel, responsePrivacy, remoteToolScope ->
            continueAfterToolObservation(
                runId = runId,
                promptForModel = promptForModel,
                responsePrivacy = responsePrivacy,
                remoteToolScope = remoteToolScope,
                remoteSendConfirmed = true,
            )
        },
        deferRemoteSendAuditNote = deferredAudit::deferNote,
    )

    private val toolContinuationSupport = ChatToolContinuationSupport(
        uiState = uiState,
        runtime = runtime,
        assistantOrchestrator = assistantOrchestrator,
        generationSupport = generationSupport,
        remoteSendSupport = remoteSendSupport,
        launchGenerationJob = { runId, block -> launchModelCallGenerationJob(runId, block) },
        cancelActiveGenerationRun = { runId -> cancelActiveGenerationRun(runId) },
        executePublicEvidenceToolBatchAfterRunIsExecuting = executePublicEvidenceToolBatchAfterRunIsExecutingCallback,
        handleNextToolPlan = handleNextToolPlanCallback,
        persistActiveSessionFromUi = { sessionHost.persistActiveSessionFromUi() },
        rebuildMemoryIndex = { sessionHost.rebuildMemoryIndex() },
        loadAuditEvents = { sessionHost.loadAuditEvents() },
        loadAgentTraceRuns = { sessionHost.loadAgentTraceRuns() },
        activeRunTimelineFor = { runId -> sessionHost.activeRunTimelineFor(runId) },
        activePublicWebEvidenceFor = { runId -> sessionHost.activePublicWebEvidenceFor(runId) },
        activeRunPlacement = chatPlacementRuntime::activeBinding,
        onRemoteServingStarted = { runId -> runId?.let(deferredAudit::commit) },
        discardDeferredRemoteSend = { runId -> runId?.let(deferredAudit::discard) },
    )

    private val generationCompletion = ChatGenerationCompletion(
        uiState = uiState,
        sessionHost = sessionHost,
        assistantOrchestrator = assistantOrchestrator,
        runtime = runtime,
        executePublicEvidenceToolBatchAfterRunIsExecuting = executePublicEvidenceToolBatchAfterRunIsExecutingCallback,
        handleNextToolPlan = handleNextToolPlanCallback,
        continueAfterToolObservation = { runId, promptForModel, responsePrivacy, remoteToolScope ->
            continueAfterToolObservation(
                runId = runId,
                promptForModel = promptForModel,
                responsePrivacy = responsePrivacy,
                remoteToolScope = remoteToolScope,
            )
        },
    )

    private val routeOutcomes = ChatRouteOutcomeHandler(
        uiState = uiState,
        sessionHost = sessionHost,
        executeToolRequestAfterRunIsExecuting = executeToolRequestAfterRunIsExecutingCallback,
    )

    val isGenerationJobActive: Boolean
        get() = generationJob?.isActive == true

    fun allocateVoiceInputDraftId(): Long = ++nextVoiceInputDraftId

    fun cancelGenerationForTeardown(): Set<String> {
        val runIds = ownedRunIds(preparedChatExecutions.keys)
        uiState.value.activeSessionId
            ?.takeIf(String::isNotBlank)
            ?.let(::teardownSession)
        preparedChatExecutions.keys.toList().forEach(preparedChatRunCoordinator::cancel)
        preparedChatExecutions.clear()
        runIds.forEach(deferredAudit::discard)
        activeGenerationRunId = null
        midRunSteering.clear()
        generationJob?.cancel()
        return runIds
    }

    fun teardownSession(sessionId: String) {
        ownedRunIds(
            preparedChatExecutions
                .filterValues { execution -> execution.stateBeforeSend.activeSessionId == sessionId }
                .keys,
        ).forEach { runId -> chatPlacementRuntime.stop(runId, AgentRunState.Cancelled) }
        preparedChatRunCoordinator.teardownSession(sessionId)
        midRunSteering.clearSession(sessionId)
        preparedChatExecutions.entries.removeIf { (_, execution) ->
            execution.stateBeforeSend.activeSessionId == sessionId
        }
        deferredAudit.retainOnly(preparedChatExecutions::containsKey)
    }

    /** [preparedRunIds] plus every run this controller is generating for or waiting on the user for. */
    private fun ownedRunIds(preparedRunIds: Collection<String>): Set<String> =
        buildSet {
            activeGenerationRunId?.let(::add)
            addAll(preparedRunIds)
            remoteSendSupport.pendingContinuationRunId?.let(::add)
            uiState.value.pendingConfirmation?.runId?.let(::add)
            uiState.value.pendingExternalOutcome?.runId?.let(::add)
        }

    fun sendMessage(prompt: String) {
        sendMessageInternal(prompt = prompt, explicitMessagePrivacy = null)
    }

    fun sendMessage(prompt: String, messagePrivacy: MessagePrivacy) {
        sendMessageInternal(prompt = prompt, explicitMessagePrivacy = messagePrivacy)
    }

    private fun sendMessageInternal(
        prompt: String,
        explicitMessagePrivacy: MessagePrivacy?,
        imageAttachments: List<ChatImageAttachment> = emptyList(),
        localImageAttachments: List<LocalImageAttachment> = emptyList(),
        remoteSendConfirmed: Boolean = false,
        currentPromptEvidenceSummary: EvidenceReceiptSummary? = null,
        preparedExecution: PreparedChatExecution? = null,
        preparedPlacement: RunPlacement? = null,
        preparedJobLaunched: ((Deferred<Unit>) -> Unit)? = null,
        onRemoteServingStarted: (() -> Unit)? = null,
        heldMessagesOnly: Boolean = false,
    ) {
        if (
            preparedExecution == null &&
            !heldMessagesOnly &&
            holdMidRunMessageIfGenerating(
                prompt = prompt,
                explicitMessagePrivacy = explicitMessagePrivacy,
                remoteSendConfirmed = remoteSendConfirmed,
                hasAttachmentsOrEvidence = imageAttachments.isNotEmpty() ||
                    localImageAttachments.isNotEmpty() ||
                    currentPromptEvidenceSummary != null,
            )
        ) {
            return
        }
        if (
            preparedExecution == null && sessionHost.dispatchPersistenceWorkIfNeeded {
                sendMessageInternal(
                    prompt = prompt,
                    explicitMessagePrivacy = explicitMessagePrivacy,
                    imageAttachments = imageAttachments,
                    localImageAttachments = localImageAttachments,
                    remoteSendConfirmed = remoteSendConfirmed,
                    currentPromptEvidenceSummary = currentPromptEvidenceSummary,
                    heldMessagesOnly = heldMessagesOnly,
                )
            }
        ) {
            return
        }
        val trimmed = preparedExecution?.promptForDispatch ?: prompt.trim()
        if (preparedExecution == null) {
            when (
                val decision = ChatSendPreflight.initialSend(
                    trimmedPrompt = trimmed,
                    state = uiState.value,
                    remoteSendConfirmed = remoteSendConfirmed,
                    generationActive = generationJob?.isActive == true,
                )
            ) {
                InitialSendDecision.Ignore -> return
                is InitialSendDecision.Blocked -> {
                    uiState.update { it.copy(statusText = decision.statusText) }
                    return
                }
                InitialSendDecision.ForgetMemoryCommand -> {
                    if (heldMessagesOnly) midRunSteering.clear()
                    sessionHost.handleExplicitMemoryForgetCommand(trimmed)
                    return
                }
                InitialSendDecision.UserFactCommand -> {
                    if (heldMessagesOnly) midRunSteering.clear()
                    sessionHost.handleExplicitUserFactCommand(trimmed)
                    return
                }
                InitialSendDecision.PreferenceCommand -> {
                    if (heldMessagesOnly) midRunSteering.clear()
                    sessionHost.handleExplicitPreferenceCommand(trimmed)
                    return
                }
                InitialSendDecision.NotReady -> {
                    routeOutcomes.handleNotReady()
                    return
                }
                InitialSendDecision.Proceed -> Unit
            }
            if (!remoteSendConfirmed) {
                // Mid-run messages held during the previous run are sent now as one NORMAL send, so
                // they get the full privacy classification / disclosure / audit pipeline.
                midRunSteering.takeMerged(
                    sessionId = uiState.value.activeSessionId,
                    prompt = trimmed.takeUnless { heldMessagesOnly },
                    explicitMessagePrivacy = explicitMessagePrivacy.takeUnless { heldMessagesOnly },
                )?.let { merged ->
                    sendMessageInternal(
                        prompt = merged.prompt,
                        explicitMessagePrivacy = merged.explicitMessagePrivacy,
                        imageAttachments = imageAttachments,
                        localImageAttachments = localImageAttachments,
                        currentPromptEvidenceSummary = currentPromptEvidenceSummary,
                    )
                    return
                }
                if (heldMessagesOnly) return
            }
        }

        solinD(
            TAG_LIFECYCLE,
            "sendMessageInternal: len=${trimmed.length} " +
                "mode=${uiState.value.inferenceMode.name} " +
                "busy=${uiState.value.isBusy}",
        )

        if (preparedExecution == null) {
            sessionHost.syncTaskStateMemories()
            sessionHost.rebuildMemoryIndex()
            uiState.update { it.copy(longTermMemories = sessionHost.loadLongTermMemories()) }
        }
        val stateBeforeSend = preparedExecution?.stateBeforeDispatch ?: uiState.value
        val useRemoteModel = preparedPlacement?.let { it == RunPlacement.Remote }
            ?: (stateBeforeSend.inferenceMode == InferenceMode.Remote)
        val effectiveMessagePrivacy =
            preparedExecution?.effectiveMessagePrivacy ?: explicitMessagePrivacy ?: MessagePrivacy.RemoteEligible
        val remoteConfig = preparedExecution?.remoteConfig ?: stateBeforeSend.remoteModelConfig
        var remoteHistory: List<ChatMessage> = preparedExecution?.remoteHistory
            ?: remoteSendSupport.remoteHistoryForRemoteSend(stateBeforeSend.messages)
        val localImageAttachmentCount = if (useRemoteModel) 0 else localImageAttachments.size
        if (preparedExecution != null && !useRemoteModel &&
            localImageAttachments.isNotEmpty() &&
            !stateBeforeSend.activeLocalModelSupportsVisionInput
        ) {
            sharedInputSupport.rejectUnsupportedLocalVisionInput()
            return
        }
        val includePrivateLocalContext = preparedExecution?.includePrivateLocalContext
            ?: (stateBeforeSend.inferenceMode == InferenceMode.Local)
        // Lazy: the planning-strategy and model-path reads hit settings and Room, so they must
        // first run inside the generation block on ioDispatcher.
        val agentRunOptions by lazy {
            preparedExecution?.agentRunOptions ?: initialAgentRunOptions(
                stateBeforeSend,
                useRemoteModel,
                remoteConfig,
                modelRepository::localPlanningStrategy,
                modelRepository::verifiedObservationActionModelPath,
            )
        }
        if (preparedExecution != null && useRemoteModel) {
            val remoteDecision = ChatSendPreflight.preparedRemoteSend(
                messagePrivacy = effectiveMessagePrivacy,
                remoteConfig = remoteConfig,
                remoteSendConfirmed = remoteSendConfirmed,
                containsSensitiveContent = {
                    remoteSendSupport.containsSensitivePersonalOrSecretContent(trimmed)
                },
                requiresSendDisclosure = {
                    remoteSendSupport.shouldRequireRemoteSendDisclosure(imageAttachmentCount = imageAttachments.size)
                },
            )
            when (remoteDecision) {
                RemoteSendPreflightDecision.KeepLocalOnlyContent -> {
                    persistLocalReply(stateBeforeSend.messages, trimmed, MessagePrivacy.LocalOnly, LOCAL_ONLY_INPUT_KEPT_LOCAL_REPLY)
                    uiState.update {
                        it.copy(statusText = "已保护本地内容")
                    }
                    return
                }
                RemoteSendPreflightDecision.BlockKnownConnectivityFailure -> {
                    remoteSendSupport.recordRemoteSendAuditEvent(
                        decision = RemoteSendDecision.Blocked,
                        modelName = remoteConfig.normalized().modelName,
                        prompt = trimmed,
                        imageCount = imageAttachments.size,
                        remoteHistoryCount = remoteHistory.size,
                    )
                    persistLocalReply(
                        history = stateBeforeSend.messages,
                        userText = trimmed,
                        userPrivacy = MessagePrivacy.RemoteEligible,
                        reply = "远程连接状态为${remoteConfig.connectivityStatus.label}，本次没有发送。请在模型管理中测试连接或更新远程配置。",
                    )
                    uiState.update {
                        it.copy(statusText = "远程连接不可用")
                    }
                    return
                }
                RemoteSendPreflightDecision.RequireSensitiveContentConsent,
                RemoteSendPreflightDecision.RequireSendDisclosure,
                -> {
                    // Sensitive content gets a forced disclosure with graded choices (mask & send,
                    // send anyway with audit, or cancel); the session-suppression flag never hides it.
                    val sensitive = remoteDecision == RemoteSendPreflightDecision.RequireSensitiveContentConsent
                    showRemoteSendDisclosure(
                        remoteSendSupport.buildCurrentInputDisclosure(
                            sensitive = sensitive,
                            prompt = trimmed,
                            messagePrivacy = effectiveMessagePrivacy,
                            remoteConfig = remoteConfig,
                            remoteHistory = remoteHistory,
                            imageAttachments = imageAttachments,
                            stateBeforeSend = stateBeforeSend,
                        ),
                        statusText = if (sensitive) "敏感内容待确认" else "远程发送待确认",
                    )
                    return
                }
                RemoteSendPreflightDecision.ProtectSensitiveContentWithoutRemote -> {
                    // Remote not configured: keep the original protect-and-explain behavior since there
                    // is no destination to send to anyway. Sensitive input is kept out of memory.
                    persistLocalReply(
                        history = stateBeforeSend.messages,
                        userText = trimmed,
                        userPrivacy = MessagePrivacy.LocalOnly,
                        reply = "这条内容疑似包含个人信息或密钥。当前为远程模型模式，我不会把它发送到远程模型；请切换到本地模型，或删去敏感内容后再发送。",
                        rememberUser = false,
                    )
                    uiState.update {
                        it.copy(statusText = "已保护敏感内容")
                    }
                    return
                }
                RemoteSendPreflightDecision.Proceed -> Unit
            }
        }
        if (preparedExecution == null) {
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
                    activeRunPlacement = null,
                    activeRunPlacementReason = null,
                    statusText = "处理中",
                )
            }
        }

        val generationBlock: suspend CoroutineScope.() -> Unit = generation@{
            var activeModelRunId: String? = null
            var activeStreamKey: GenerationStreamKey? = null
            var streamingAssistantUpdates: StreamingAssistantUpdateCoalescer? = null
            try {
                val userMessage = ChatMessage(
                    role = MessageRole.User,
                    text = trimmed,
                    privacy = effectiveMessagePrivacy,
                )
                val route = preparedExecution?.routeForDispatch ?: try {
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
                } catch (throwable: Throwable) {
                    solinE(TAG_MODEL, "initial chat route failed", throwable)
                    uiState.update {
                        it.idleWithoutPlacement(PlacementReasonCode.PLACEMENT_DECISION_MISSING, "请求路由失败")
                    }
                    return@generation
                }
                if (preparedExecution == null && route is AssistantRoute.Action && route.runId != null) {
                    if (
                        bindContinuationRun(
                            runId = route.runId,
                            prompt = trimmed,
                            stateBeforeSend = stateBeforeSend,
                            promptPrivacy = effectiveMessagePrivacy,
                            remoteConfig = remoteConfig,
                        ) == null
                    ) {
                        failInitialPlacement(
                            route.runId,
                            PlacementReasonCode.PLACEMENT_NOT_RESTORABLE,
                        )
                        return@generation
                    }
                }
                val routeReceipt = route.runDataReceipt(
                    stateBeforeSend = stateBeforeSend,
                    destination = preparedPlacement?.toRunDataDestination()
                        ?: if (useRemoteModel) RunDataDestination.Remote else RunDataDestination.Local,
                    currentPromptPrivacy = effectiveMessagePrivacy,
                    remoteHistoryCount = remoteHistory.size,
                    imageAttachmentCount = imageAttachments.size + localImageAttachmentCount,
                    currentPromptEvidenceSummary = currentPromptEvidenceSummary,
                )
                if (preparedExecution == null && route !is AssistantRoute.Chat) route.runIdOrNull()?.let { runId ->
                    assistantOrchestrator.recordRunDataReceipt(
                        runId = runId,
                        receipt = routeReceipt,
                    )
                }
                when (route) {
                    is AssistantRoute.Action -> {
                        routeOutcomes.handleAction(route, userMessage, stateBeforeSend)
                        return@generation
                    }

                    is AssistantRoute.ToolRejected -> {
                        routeOutcomes.handleToolRejected(route, userMessage, stateBeforeSend)
                        return@generation
                    }

                    is AssistantRoute.MissingModel -> {
                        routeOutcomes.handleMissingModel(route, userMessage, stateBeforeSend, effectiveMessagePrivacy)
                        return@generation
                    }

                    is AssistantRoute.Chat -> {
                        if (preparedExecution == null) {
                            prepareInitialChatRun(
                                userPrompt = trimmed,
                                route = route,
                                stateBeforeSend = stateBeforeSend,
                                effectiveMessagePrivacy = effectiveMessagePrivacy,
                                remoteConfig = remoteConfig,
                                remoteHistory = remoteHistory,
                                imageAttachments = imageAttachments,
                                localImageAttachments = localImageAttachments,
                                includePrivateLocalContext = includePrivateLocalContext,
                                agentRunOptions = agentRunOptions,
                                currentPromptEvidenceSummary = currentPromptEvidenceSummary,
                            )
                            return@generation
                        }
                        activeModelRunId = route.runId
                        activeGenerationRunId = route.runId
                        val streamKey = generationStreams.start(
                            sessionId = stateBeforeSend.activeSessionId.orEmpty(),
                            runId = route.runId,
                        )
                        activeStreamKey = streamKey
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
                        sessionHost.replaceActiveSessionMessages(
                            stateBeforeSend.messages + chatUserMessage + assistantPlaceholder,
                            persistNow = true,
                        )
                        sessionHost.persistExplicitPreferenceMemory(chatUserMessage)
                        uiState.update {
                            it.copy(
                                isGenerating = true,
                                memoryHits = route.memoryHits,
                                activeRunTimeline = sessionHost.activeRunTimelineFor(route.runId),
                                activeMemoryEvidence = sessionHost.activeMemoryEvidenceFor(
                                    memoryHits = route.memoryHits,
                                    includePrivateLocalContext = includePrivateLocalContext,
                                ),
                                statusText = "生成中",
                            )
                        }
                        if (generationSupport.abortIfServingUnavailable(
                                runId = route.runId,
                                useRemoteModel = useRemoteModel,
                                remoteConfigured = remoteConfig.isConfigured,
                                localNotReadyText = "模型尚未就绪",
                            )
                        ) {
                            return@generation
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
                                sessionHost.persistActiveSessionFromUi()
                                uiState.update {
                                    sessionHost.finishedRun(it, runId = route.runId, statusText = "已阻止远程发送").copy(
                                        isReady = remoteConfig.isConfigured,
                                        pendingConfirmation = null,
                                        memoryHits = emptyList(),
                                        activeMemoryEvidence = emptyList(),
                                    )
                                }
                                return@generation
                            }
                        }

                        val streamingUpdates = StreamingAssistantUpdateCoalescer(
                            publish = { text ->
                                if (generationStreams.isActive(streamKey)) {
                                    uiState.updateLastAssistant(text)
                                }
                            },
                        )
                        streamingAssistantUpdates = streamingUpdates
                        val turn = GuardedTurn(streamingUpdates)
                        val acceptDelta = { chunk: String -> generationStreams.acceptDelta(streamKey, chunk) }
                        val modelCallJob = currentCoroutineContext()[Job]
                        route.runId?.let { runId -> modelCallJob?.let { job -> midRunSteering.onModelCallStarted(runId, job) } }
                        try {
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
                                remoteHistory = generationSupport.streamRemoteTurn(
                                    turn = turn,
                                    runId = route.runId,
                                    prompt = route.promptForModel,
                                    history = remoteHistory,
                                    parameters = stateBeforeSend.generationParameters,
                                    config = remoteConfig,
                                    tools = remoteTools,
                                    imageAttachments = imageAttachments,
                                    acceptDelta = acceptDelta,
                                    onServingStarted = { onRemoteServingStarted?.invoke() },
                                )
                            } else {
                                generationSupport.streamLocalTurn(
                                    turn = turn,
                                    prompt = route.promptForModel,
                                    history = stateBeforeSend.messages,
                                    parameters = stateBeforeSend.generationParameters,
                                    imageAttachments = localImageAttachments,
                                    acceptDelta = acceptDelta,
                                )
                            }
                        } finally {
                            route.runId?.let { runId -> modelCallJob?.let { job -> midRunSteering.onModelCallFinished(runId, job) } }
                        }
                        generationSupport.guardCompletedTurn(turn, useRemoteModel, remoteConfig)
                        val outputQualityDecision = turn.qualityDecision
                        outputQualityDecision?.let { decision ->
                            generationSupport.finishOutputQualityGuardedGeneration(
                                runId = route.runId,
                                decision = decision,
                                receipt = routeReceipt,
                                useRemoteModel = useRemoteModel,
                            )
                            return@generation
                        }
                        streamingUpdates.flush()
                        generationStreams.complete(streamKey)
                        generationCompletion.complete(
                            route = route,
                            observation = turn.observation,
                            partial = turn.partial,
                            useRemoteModel = useRemoteModel,
                            includePrivateLocalContext = includePrivateLocalContext,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                activeStreamKey?.let(generationStreams::cancel)
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
                activeStreamKey?.let(generationStreams::fail)
                solinE(TAG_MODEL, "generation: failed useRemoteModel=$useRemoteModel", throwable)
                streamingAssistantUpdates?.flush()
                generationSupport.failGeneration(
                    runId = activeModelRunId,
                    throwable = throwable,
                    useRemoteModel = useRemoteModel,
                    remoteConfigured = remoteConfig.isConfigured,
                    showErrorOnlyWhileGenerating = true,
                )
            }
        }
        val preparedJob = if (preparedExecution != null) {
            scope.async(ioDispatcher, start = CoroutineStart.LAZY, block = generationBlock)
        } else {
            null
        }
        val job: Job = preparedJob
            ?: scope.launch(ioDispatcher, start = CoroutineStart.LAZY, block = generationBlock)
        preparedJob?.let { launched ->
            requireNotNull(preparedJobLaunched) { "Prepared Chat job owner is missing" }(launched)
        }
        trackGenerationJob(job)
        if (preparedJob == null) job.start()
    }

    private fun bindContinuationRun(
        runId: String,
        prompt: String,
        stateBeforeSend: ChatUiState,
        promptPrivacy: MessagePrivacy,
        remoteConfig: RemoteModelConfig,
    ): ActiveRunPlacementPermit? {
        chatPlacementRuntime.activeBinding(runId)?.let { return it }
        val inputs = placementInputsFor(
            state = stateBeforeSend,
            prompt = prompt,
            connectivity = remoteConnectivity(remoteConfig.normalized()),
        )
        val privacy = initialChatPrivacyPlan(
            promptPrivacy = promptPrivacy,
            history = stateBeforeSend.messages,
            remoteImages = emptyList(),
            localImages = emptyList(),
            evidence = null,
        )
        val decision = ModelPlacementPolicy.decide(
            ModelPlacementInput(
                preference = stateBeforeSend.inferenceMode,
                privacy = privacy.aggregatePrivacy,
                requiresLocalModel = privacy.requiresLocalModel,
                requirements = inputs.requirements,
                local = inputs.localCandidate,
                remote = inputs.remoteCandidate,
                resources = stableResourceStateProvider(),
                complexity = inputs.complexity,
            ),
        ) as? PlacementDecision.Chosen ?: return null
        val binding = runCatching {
            RunPlacementBinding.fromDecision(
                runId = runId,
                decision = decision,
                remoteProfileRevision = remoteConfig.normalized().profileRevision,
                bootCount = bootCountProvider(),
                boundAtElapsedRealtimeMillis = elapsedRealtimeMillis(),
            )
        }.getOrNull() ?: return null
        return bindAndShowPlacement(binding)
    }

    private fun bindAndShowPlacement(binding: RunPlacementBinding): ActiveRunPlacementPermit? =
        chatPlacementRuntime.bindAndReserve(binding)?.also {
            uiState.update { state ->
                state.copy(
                    activeRunPlacement = binding.placement,
                    activeRunPlacementReason = binding.primaryReason,
                )
            }
        }

    private fun placementInputsFor(
        state: ChatUiState,
        prompt: String,
        connectivity: RemoteConnectivitySnapshot?,
        remoteImageCount: Int = 0,
        localImageCount: Int = 0,
    ): ChatPlacementInputs =
        chatPlacementInputs(
            state = state,
            promptForModel = prompt,
            history = state.messages,
            remoteImageCount = remoteImageCount,
            localImageCount = localImageCount,
            localRuntimeLoaded = runtime.isLoaded,
            connectivity = connectivity,
            nowElapsedRealtimeMillis = elapsedRealtimeMillis(),
            autoRemoteAuthorized = adaptiveInferenceRollout.autoSelectable &&
                state.inferenceMode == InferenceMode.Auto,
        )

    /** Keeps the user's turn plus a local-only explanation in the session; nothing is sent remotely. */
    private fun persistLocalReply(
        history: List<ChatMessage>,
        userText: String,
        userPrivacy: MessagePrivacy,
        reply: String,
        rememberUser: Boolean = true,
    ) {
        val userMessage = ChatMessage(role = MessageRole.User, text = userText, privacy = userPrivacy)
        sessionHost.persistMessagesAndRebuildMemory(
            messages = history + userMessage +
                ChatMessage(role = MessageRole.Assistant, text = reply, privacy = MessagePrivacy.LocalOnly),
            memoryUserMessage = userMessage.takeIf { rememberUser },
        )
    }

    private fun showRemoteSendDisclosure(
        disclosure: PendingRemoteSendDisclosure,
        statusText: String,
        endRun: Boolean = false,
    ) {
        remoteSendSupport.savePendingRemoteSendMarker(disclosure)
        uiState.update {
            (if (endRun) it.copy(isBusy = false, isGenerating = false) else it).copy(
                pendingRemoteSendDisclosure = disclosure,
                pendingExternalOutcome = null,
                latestRecoveryAction = null,
                statusText = statusText,
            )
        }
    }

    private suspend fun prepareInitialChatRun(
        userPrompt: String,
        route: AssistantRoute.Chat,
        stateBeforeSend: ChatUiState,
        effectiveMessagePrivacy: MessagePrivacy,
        remoteConfig: RemoteModelConfig,
        remoteHistory: List<ChatMessage>,
        imageAttachments: List<ChatImageAttachment>,
        localImageAttachments: List<LocalImageAttachment>,
        includePrivateLocalContext: Boolean,
        agentRunOptions: AgentRunOptions,
        currentPromptEvidenceSummary: EvidenceReceiptSummary?,
    ) {
        val runId = route.runId
        val sessionId = stateBeforeSend.activeSessionId
        if (runId.isNullOrBlank() || sessionId.isNullOrBlank()) {
            failInitialPlacement(
                runId = runId,
                reason = PlacementReasonCode.PLACEMENT_DECISION_MISSING,
            )
            return
        }
        if (stateBeforeSend.inferenceMode == InferenceMode.Remote) {
            if (effectiveMessagePrivacy == MessagePrivacy.LocalOnly) {
                assistantOrchestrator.failModelGeneration(runId, "LocalOnly input cannot use remote serving")
                persistLocalReply(stateBeforeSend.messages, userPrompt, MessagePrivacy.LocalOnly, LOCAL_ONLY_INPUT_KEPT_LOCAL_REPLY)
                uiState.update { it.idleWithoutPlacement(PlacementReasonCode.PRIVACY_REQUIRES_LOCAL, "已保护本地内容") }
                return
            }
            remoteRouteBoundaryFailure(userInput = userPrompt, route = route)?.let { boundaryFailure ->
                assistantOrchestrator.failModelGeneration(runId, boundaryFailure)
                remoteSendSupport.recordRemoteSendAuditEvent(
                    decision = RemoteSendDecision.Blocked,
                    modelName = remoteConfig.normalized().modelName,
                    prompt = route.promptForModel,
                    imageCount = imageAttachments.size,
                    remoteHistoryCount = remoteHistory.size,
                )
                persistLocalReply(stateBeforeSend.messages, userPrompt, MessagePrivacy.RemoteEligible, boundaryFailure)
                uiState.update { it.idleWithoutPlacement(PlacementReasonCode.PRIVACY_REQUIRES_LOCAL, "已阻止远程发送") }
                return
            }
        }
        val normalizedRemoteConfig = remoteConfig.normalized()
        val connectivity = remoteConnectivity(normalizedRemoteConfig)
        val privacyPlan = initialChatPrivacyPlan(
            promptPrivacy = effectiveMessagePrivacy,
            history = stateBeforeSend.messages,
            remoteImages = imageAttachments,
            localImages = localImageAttachments,
            evidence = currentPromptEvidenceSummary,
        ).withRouteContext(route)
        val placementInputs = placementInputsFor(
            state = stateBeforeSend,
            prompt = route.promptForModel,
            connectivity = connectivity,
            remoteImageCount = imageAttachments.size,
            localImageCount = localImageAttachments.size,
        )
        val sensitive = remoteSendSupport.containsSensitivePersonalOrSecretContent(userPrompt)
        val imageAttachmentCount = maxOf(imageAttachments.size, localImageAttachments.size)
        val requiresDisclosure = normalizedRemoteConfig.isConfigured &&
            (sensitive || remoteSendSupport.shouldRequireRemoteSendDisclosure(imageAttachmentCount))
        val execution = PreparedChatExecution(
            userPrompt = userPrompt,
            route = route,
            stateBeforeSend = stateBeforeSend,
            effectiveMessagePrivacy = effectiveMessagePrivacy,
            remoteConfig = normalizedRemoteConfig,
            remoteHistory = remoteHistory.toList(),
            imageAttachments = imageAttachments.toList(),
            localImageAttachments = localImageAttachments.map { attachment ->
                attachment.copy(bytes = attachment.bytes.copyOf())
            },
            includePrivateLocalContext = includePrivateLocalContext,
            agentRunOptions = agentRunOptions,
            currentPromptEvidenceSummary = currentPromptEvidenceSummary,
            remoteSendConfirmed = requiresDisclosure,
        )
        if (preparedChatExecutions.putIfAbsent(runId, execution) != null) {
            failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE)
            return
        }
        val result = preparedChatRunCoordinator.prepare(
            PrepareChatRunRequest(
                runId = runId,
                sessionId = sessionId,
                preference = stateBeforeSend.inferenceMode,
                privacyPlan = privacyPlan,
                requirements = placementInputs.requirements,
                complexity = placementInputs.complexity,
                resources = stableResourceStateProvider(),
                localCandidate = placementInputs.localCandidate,
                remoteCandidate = placementInputs.remoteCandidate,
                remoteConfigRevision = normalizedRemoteConfig.profileRevision,
                history = stateBeforeSend.messages,
                imageAttachments = imageAttachments,
                localImageAttachments = localImageAttachments,
                generationParameters = stateBeforeSend.generationParameters,
                requiresRemoteDisclosure = requiresDisclosure,
                routingText = userPrompt,
                bootCount = bootCountProvider(),
                boundAtElapsedRealtimeMillis = elapsedRealtimeMillis(),
            ),
        )
        when (result) {
            is PrepareChatRunResult.Blocked -> {
                preparedChatExecutions.remove(runId, execution)
                failInitialPlacement(runId, result.decision.primaryReason)
            }
            PrepareChatRunResult.BindingRejected -> {
                preparedChatExecutions.remove(runId, execution)
                failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE)
            }
            PrepareChatRunResult.DispatchRejected -> {
                preparedChatExecutions.remove(runId, execution)
                failInitialPlacement(runId, PlacementReasonCode.MODEL_EXECUTION_FAILED)
            }
            is PrepareChatRunResult.Ready -> Unit
            is PrepareChatRunResult.AwaitingDisclosure -> showRemoteSendDisclosure(
                remoteSendSupport.buildCurrentInputDisclosure(
                    sensitive = sensitive,
                    prompt = userPrompt,
                    messagePrivacy = effectiveMessagePrivacy,
                    remoteConfig = normalizedRemoteConfig,
                    remoteHistory = remoteHistory,
                    imageAttachments = imageAttachments,
                    stateBeforeSend = stateBeforeSend,
                ).copy(
                    runId = result.runId,
                    remoteProfileRevision = result.expectedConfigRevision,
                ),
                statusText = if (sensitive) "敏感内容待确认" else "远程发送待确认",
                endRun = true,
            )
        }
    }

    private fun resumePreparedChatAfterDisclosure(
        pending: PendingRemoteSendDisclosure,
        promptOverride: String?,
    ) {
        val runId = pending.runId
        val revision = pending.remoteProfileRevision
        if (runId.isNullOrBlank() || revision.isNullOrBlank()) {
            failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE)
            return
        }
        val execution = preparedChatExecutions[runId]
        if (execution == null) {
            failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE)
            return
        }
        if (uiState.value.activeSessionId != execution.stateBeforeSend.activeSessionId) {
            preparedChatRunCoordinator.cancel(runId)
            preparedChatExecutions.remove(runId, execution)
            deferredAudit.discard(runId)
            failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE)
            return
        }
        execution.preserveConfirmedLocalMessages(
            uiState.value.messages.drop(execution.stateBeforeSend.messages.size),
        )
        promptOverride?.let(execution::overridePrompt)
        uiState.update {
            it.copy(
                isBusy = true,
                isGenerating = false,
                statusText = "处理中",
            )
        }
        launchGenerationJob(runId) {
            if (!preparedChatRunCoordinator.confirmAndDispatch(runId, revision)) {
                preparedChatExecutions.remove(runId, execution)
                deferredAudit.discard(runId)
                failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE)
            }
        }
    }

    private fun discardPreparedChat(runId: String) {
        preparedChatRunCoordinator.cancel(runId)
        preparedChatExecutions.remove(runId)
        deferredAudit.discard(runId)
        assistantOrchestrator.failModelGeneration(runId, "Remote send disclosure cancelled")
    }

    private fun failInitialPlacement(
        runId: String?,
        reason: PlacementReasonCode,
    ) {
        runId?.let { assistantOrchestrator.failModelGeneration(it, reason.name) }
        uiState.update { it.idleWithoutPlacement(reason, "没有可用的模型执行目标") }
    }

    private suspend fun dispatchPreparedExecution(
        prepared: PreparedChatRun,
        execution: PreparedChatExecution,
    ) {
        val receipt = execution.route.runDataReceipt(
            stateBeforeSend = execution.stateBeforeSend,
            destination = prepared.placement.toRunDataDestination(),
            currentPromptPrivacy = execution.effectiveMessagePrivacy,
            remoteHistoryCount = execution.remoteHistory.size,
            imageAttachmentCount = if (prepared.placement == RunPlacement.Remote) {
                prepared.imageAttachments.size
            } else {
                prepared.localImageAttachments.size
            },
            currentPromptEvidenceSummary = execution.currentPromptEvidenceSummary,
        )
        chatPlacementRuntime.dispatch(
            prepared = prepared,
            receipt = receipt,
            local = preparedServingCall(prepared, execution, RunPlacement.Local),
            remote = preparedServingCall(prepared, execution, RunPlacement.Remote),
        )
    }

    private fun preparedServingCall(
        prepared: PreparedChatRun,
        execution: PreparedChatExecution,
        placement: RunPlacement,
    ): ChatServingCall = PreparedServingCall(
        launch = { onLaunched ->
            sendMessageInternal(
                prompt = execution.promptForDispatch,
                explicitMessagePrivacy = execution.effectiveMessagePrivacy,
                imageAttachments = prepared.imageAttachments,
                localImageAttachments = prepared.localImageAttachments,
                remoteSendConfirmed = execution.remoteSendConfirmed,
                currentPromptEvidenceSummary = execution.currentPromptEvidenceSummary,
                preparedExecution = execution,
                preparedPlacement = placement,
                preparedJobLaunched = onLaunched,
                onRemoteServingStarted = { deferredAudit.commit(prepared.runId) },
            )
        },
        stopRuntime = {
            when (placement) {
                RunPlacement.Local -> runtime.stop()
                RunPlacement.Remote -> remoteRuntime.stop()
            }
        },
    )

    fun confirmRemoteSendDisclosure(suppressForSession: Boolean = false) {
        deferredAudit.deferSuccessfulAudit {
            remoteSendSupport.confirmRemoteSendDisclosure(suppressForSession)
        }
    }

    fun confirmRemoteSendWithMasking() {
        deferredAudit.deferSuccessfulAudit(remoteSendSupport::confirmRemoteSendWithMasking)
    }

    fun confirmRemoteSendDespiteSensitive() {
        deferredAudit.deferSuccessfulAudit(remoteSendSupport::confirmRemoteSendDespiteSensitive)
    }

    fun failClosedPendingRemoteSendOnStartup() {
        remoteSendSupport.failClosedPendingRemoteSendOnStartup()
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

    private fun cancelActiveGenerationRun(runId: String?) {
        val id = runId ?: return
        if (activeGenerationRunId != id) return
        assistantOrchestrator.cancelRun(id, USER_STOPPED_AGENT_RUN_REASON)
        activeGenerationRunId = null
    }

    fun stopGeneration() {
        val job = generationJob
        val runId = activeGenerationRunId ?: preparedChatExecutions.keys.singleOrNull()
        // An explicit stop discards input sent mid-run: the user asked the run to end, not to adjust.
        val discardedSteerCount = uiState.value.pendingSteerMessageCount
        midRunSteering.clear()
        val discardNotice = if (discardedSteerCount > 0) {
            "已停止，丢弃 $discardedSteerCount 条插话"
        } else {
            null
        }
        if (job == null && runId == null) {
            discardNotice?.let { notice -> uiState.update { it.copy(statusText = notice) } }
            return
        }
        if (runId != null) {
            chatPlacementRuntime.stop(runId, AgentRunState.Cancelled)
            preparedChatRunCoordinator.cancel(runId)
            preparedChatExecutions.remove(runId)
            deferredAudit.discard(runId)
        }
        cancelActiveGenerationRun(runId)
        // The job's own cancellation handlers write their "已停止" status; announce what the stop
        // threw away after they have all run, so the notice is what the user ends up seeing.
        discardNotice?.let { notice ->
            job?.invokeOnCompletion { uiState.update { it.copy(statusText = notice) } }
        }
        job?.cancel()
        generationSupport.finishStoppedGeneration(runId)
        // Synchronous (unconfined) cancellation completes the job inside cancel(); re-apply last.
        discardNotice?.let { notice -> uiState.update { it.copy(statusText = notice) } }
    }

    /**
     * Launches a job that streams a model turn (tool continuation). Only such jobs are registered for
     * steer cancellation, and only until [block] calls its `endModelCall` argument (right after the
     * stream) or returns. Tool execution afterwards is never steer-cancellable.
     */
    private fun launchModelCallGenerationJob(runId: String?, block: suspend (endModelCall: () -> Unit) -> Unit) {
        launchGenerationJob(runId) {
            val modelCallJob = currentCoroutineContext()[Job].takeIf { runId != null }
            val registered = AtomicBoolean(modelCallJob != null)
            val endModelCall = {
                if (registered.compareAndSet(true, false)) {
                    modelCallJob?.let { job -> midRunSteering.onModelCallFinished(requireNotNull(runId), job) }
                }
            }
            modelCallJob?.let { job -> midRunSteering.onModelCallStarted(requireNotNull(runId), job) }
            try {
                block(endModelCall)
            } finally {
                endModelCall()
            }
        }
    }

    fun launchGenerationJob(runId: String?, block: suspend () -> Unit) {
        activeGenerationRunId = runId
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) { block() }
        trackGenerationJob(job)
        job.start()
    }

    private fun trackGenerationJob(job: Job) {
        generationJob = job
        uiState.update { it.copy(midRunSteeringAvailable = true) }
        job.invokeOnCompletion {
            if (generationJob == job) {
                generationJob = null
                activeGenerationRunId = null
                uiState.update { it.copy(midRunSteeringAvailable = false) }
                dispatchHeldMidRunMessagesWhenIdle()
            }
        }
    }

    /**
     * Mid-run send: while a generation job is in flight, hold the message (steer the model stream or
     * queue behind the running tool) instead of dropping it. Sends that carry attachments/evidence or
     * a confirmed remote send keep the old busy semantics.
     */
    private fun holdMidRunMessageIfGenerating(
        prompt: String,
        explicitMessagePrivacy: MessagePrivacy?,
        remoteSendConfirmed: Boolean,
        hasAttachmentsOrEvidence: Boolean,
    ): Boolean {
        if (remoteSendConfirmed || hasAttachmentsOrEvidence || generationJob?.isActive != true) return false
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return false
        val action = midRunSteering.offer(trimmed, explicitMessagePrivacy, activeGenerationRunId)
        solinD(TAG_LIFECYCLE, "midRunSteering: held len=${trimmed.length} action=${action.name}")
        return true
    }

    /** Once the run yields (job done, nothing pending on the user), send held input normally. */
    private fun dispatchHeldMidRunMessagesWhenIdle() {
        if (!midRunSteering.hasHeldMessages()) return
        scope.launch {
            if (generationJob?.isActive == true) return@launch
            val preview = midRunSteering.heldPromptPreview() ?: return@launch
            sendMessageInternal(prompt = preview, explicitMessagePrivacy = null, heldMessagesOnly = true)
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
        uiState.value.activeSessionId
            ?.takeIf { it.isNotBlank() }
            ?.let(::teardownSession)
        remoteSendSupport.clearPendingRemoteChatState()
    }

    fun setRemoteSendDisclosurePolicy(policy: RemoteSendDisclosurePolicy) {
        remoteSendSupport.setRemoteSendDisclosurePolicy(policy)
    }
}
