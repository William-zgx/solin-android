package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.AdaptiveInferenceRollout
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
import com.bytedance.zgx.solin.PendingRemoteSendDisclosure
import com.bytedance.zgx.solin.PublicWebEvidencePack
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteConnectivitySnapshot
import com.bytedance.zgx.solin.RunTimelineItemUiSummary
import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.StreamingAssistantUpdateCoalescer
import com.bytedance.zgx.solin.audit.RemoteSendAuditLog
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
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
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.InitialPlanningMode
import com.bytedance.zgx.solin.orchestration.ModelPlacementPolicy
import com.bytedance.zgx.solin.orchestration.PrepareChatRunRequest
import com.bytedance.zgx.solin.orchestration.PrepareChatRunResult
import com.bytedance.zgx.solin.orchestration.PreparedChatDispatcher
import com.bytedance.zgx.solin.orchestration.PreparedChatBindingStore
import com.bytedance.zgx.solin.orchestration.PreparedChatPlacementPolicy
import com.bytedance.zgx.solin.orchestration.PreparedChatRevisionValidator
import com.bytedance.zgx.solin.orchestration.PreparedChatRun
import com.bytedance.zgx.solin.orchestration.PreparedChatRunCoordinator
import com.bytedance.zgx.solin.orchestration.PreparedChatRunningCall
import com.bytedance.zgx.solin.orchestration.PlacementDecision
import com.bytedance.zgx.solin.orchestration.PlacementReasonCode
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.orchestration.RunDataDestination
import com.bytedance.zgx.solin.orchestration.RunPlacement
import com.bytedance.zgx.solin.orchestration.toRunDataDestination
import com.bytedance.zgx.solin.orchestration.requiresUserConfirmation
import com.bytedance.zgx.solin.runtime.GenerationQualityDecision
import com.bytedance.zgx.solin.runtime.GenerationRuntimeKind
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.ModelOutputQualityGuard
import com.bytedance.zgx.solin.runtime.RemoteChatEvent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val USER_STOPPED_AGENT_RUN_REASON =
    "User stopped this Agent run."

private fun RemoteSendDecision.isSuccessfulRemoteSendDecision(): Boolean =
    this == RemoteSendDecision.Confirmed ||
        this == RemoteSendDecision.MaskedSend ||
        this == RemoteSendDecision.SentAnyway

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

    private val preparedChatExecutions = ConcurrentHashMap<String, PreparedChatExecution>()
    private val deferredRemoteSendAudits = ConcurrentHashMap<String, RemoteSendAuditEvent>()
    private val deferredAuditRunId = ThreadLocal<String?>()
    private val claimGatedRemoteSendAuditSink = object : RemoteSendAuditSink {
        override fun record(event: RemoteSendAuditEvent) {
            val runId = deferredAuditRunId.get()
            if (runId != null && event.decision.isSuccessfulRemoteSendDecision()) {
                deferredRemoteSendAudits[runId] = event
            } else {
                remoteSendAuditSink.record(event)
            }
        }
    }
    private val preparedChatRunCoordinator = PreparedChatRunCoordinator(
        policy = PreparedChatPlacementPolicy(ModelPlacementPolicy::decide),
        bindingStore = object : PreparedChatBindingStore {
            override fun bindAndReserve(binding: com.bytedance.zgx.solin.orchestration.RunPlacementBinding) =
                chatPlacementRuntime.bindAndReserve(binding)?.also {
                    uiState.update { state ->
                        state.copy(
                            activeRunPlacement = binding.placement,
                            activeRunPlacementReason = binding.primaryReason,
                            activePlacementPolicyVersion = binding.policyVersion,
                        )
                    }
                }

            override fun abort(permit: com.bytedance.zgx.solin.orchestration.ActiveRunPlacementPermit) {
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
        remoteSendAuditSink = claimGatedRemoteSendAuditSink,
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
        activeRunPlacement = chatPlacementRuntime::activeBinding,
    )

    val isGenerationJobActive: Boolean
        get() = generationJob?.isActive == true

    fun allocateVoiceInputDraftId(): Long = ++nextVoiceInputDraftId

    fun cancelGenerationForTeardown(): Set<String> {
        val runIds = activeRunIdsForTeardown()
        uiState.value.activeSessionId
            ?.takeIf(String::isNotBlank)
            ?.let(::teardownSession)
        preparedChatExecutions.keys.toList().forEach(preparedChatRunCoordinator::cancel)
        preparedChatExecutions.clear()
        runIds.forEach(deferredRemoteSendAudits::remove)
        activeGenerationRunId = null
        generationJob?.cancel()
        return runIds
    }

    fun teardownSession(sessionId: String) {
        buildSet {
            preparedChatExecutions
                .filterValues { execution -> execution.stateBeforeSend.activeSessionId == sessionId }
                .keys
                .let(::addAll)
            activeGenerationRunId?.let(::add)
            remoteSendSupport.pendingContinuationRunId?.let(::add)
            uiState.value.pendingConfirmation?.runId?.let(::add)
            uiState.value.pendingExternalOutcome?.runId?.let(::add)
        }.forEach { runId -> chatPlacementRuntime.stop(runId, AgentRunState.Cancelled) }
        preparedChatRunCoordinator.teardownSession(sessionId)
        preparedChatExecutions.entries.removeIf { (_, execution) ->
            execution.stateBeforeSend.activeSessionId == sessionId
        }
        deferredRemoteSendAudits.keys.removeAll { runId -> !preparedChatExecutions.containsKey(runId) }
    }

    private fun activeRunIdsForTeardown(): Set<String> =
        buildSet {
            activeGenerationRunId?.let(::add)
            addAll(preparedChatExecutions.keys)
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
    ) {
        if (
            preparedExecution == null && dispatchPersistenceWorkIfNeeded {
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
        val trimmed = preparedExecution?.promptForDispatch ?: prompt.trim()
        if (preparedExecution == null && trimmed.isNotEmpty() && uiState.value.pendingConfirmation != null) {
            uiState.update {
                it.copy(statusText = "请先确认或取消待执行动作")
            }
            return
        }
        if (preparedExecution == null && trimmed.isNotEmpty() &&
            uiState.value.pendingRemoteModeDisclosure != null &&
            !remoteSendConfirmed
        ) {
            uiState.update {
                it.copy(statusText = "请先确认远程模式提醒")
            }
            return
        }
        if (preparedExecution == null && trimmed.isNotEmpty() &&
            uiState.value.pendingRemoteSendDisclosure != null &&
            !remoteSendConfirmed
        ) {
            uiState.update {
                it.copy(statusText = "请先确认或取消远程发送")
            }
            return
        }
        if (preparedExecution == null && trimmed.isNotEmpty() && uiState.value.pendingExternalOutcome != null) {
            uiState.update {
                it.copy(statusText = "请先确认外部动作结果")
            }
            return
        }
        if (preparedExecution == null &&
            (trimmed.isEmpty() || uiState.value.isBusy || generationJob?.isActive == true)
        ) {
            return
        }
        if (preparedExecution == null && explicitUserPreferenceForgetFrom(trimmed) != null) {
            handleExplicitMemoryForgetCommand(trimmed)
            return
        }
        if (preparedExecution == null && explicitUserFactFrom(trimmed) != null) {
            handleExplicitUserFactCommand(trimmed)
            return
        }
        if (preparedExecution == null && explicitUserPreferenceFrom(trimmed) != null) {
            handleExplicitPreferenceCommand(trimmed)
            return
        }
        if (preparedExecution == null && !uiState.value.isReady) {
            handleNotReadySendAttempt()
            return
        }

        solinD(
            TAG_LIFECYCLE,
            "sendMessageInternal: len=${trimmed.length} " +
                "mode=${uiState.value.inferenceMode.name} " +
                "busy=${uiState.value.isBusy}",
        )

        if (preparedExecution == null) {
            syncTaskStateMemories()
            rebuildMemoryIndex()
            uiState.update { it.copy(longTermMemories = loadLongTermMemories()) }
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
        val agentRunOptions = preparedExecution?.agentRunOptions ?: if (useRemoteModel) {
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
        if (preparedExecution != null && useRemoteModel && effectiveMessagePrivacy == MessagePrivacy.LocalOnly) {
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
        if (preparedExecution != null && useRemoteModel &&
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
        if (preparedExecution != null && useRemoteModel &&
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
        if (preparedExecution != null && useRemoteModel &&
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
        if (preparedExecution != null && useRemoteModel &&
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
                    activePlacementPolicyVersion = null,
                    statusText = "处理中",
                )
            }
        }

        val generationBlock: suspend CoroutineScope.() -> Unit = generation@{
            var activeModelRunId: String? = null
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
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            activeRunPlacement = null,
                            activeRunPlacementReason = PlacementReasonCode.PLACEMENT_DECISION_MISSING,
                            activePlacementPolicyVersion = null,
                            statusText = "请求路由失败",
                        )
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
                            ModelPlacementPolicy.POLICY_VERSION,
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
                            return@generation
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
                        return@generation
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
                        return@generation
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
                            return@generation
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
                                return@generation
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
                                    parameters = stateBeforeSend.generationParameters,
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
                                                    parameters = stateBeforeSend.generationParameters,
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
                                parameters = stateBeforeSend.generationParameters,
                                imageAttachments = localImageAttachments,
                            ) { chunk ->
                                if (outputQualityDecision == null) {
                                    val decision = generationSupport.appendGuardedGenerationChunk(
                                        partial = partial,
                                        chunk = chunk,
                                        runtimeKind = GenerationRuntimeKind.Local,
                                        modelId = uiState.value.activeModelProfileId(),
                                        backend = uiState.value.backend,
                                        parameters = stateBeforeSend.generationParameters,
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
                            return@generation
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
                                return@generation
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
                                return@generation
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
                            return@generation
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
                                    return@generation
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
                                    return@generation
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
                                    return@generation
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
        generationJob = job
        job.invokeOnCompletion {
            if (generationJob == job) {
                generationJob = null
                activeGenerationRunId = null
            }
        }
        if (preparedJob == null) job.start()
    }

    private fun bindContinuationRun(
        runId: String,
        prompt: String,
        stateBeforeSend: ChatUiState,
        promptPrivacy: MessagePrivacy,
        remoteConfig: RemoteModelConfig,
    ): com.bytedance.zgx.solin.orchestration.ActiveRunPlacementPermit? {
        chatPlacementRuntime.activeBinding(runId)?.let { return it }
        val inputs = chatPlacementInputs(
            state = stateBeforeSend,
            promptForModel = prompt,
            history = stateBeforeSend.messages,
            remoteImageCount = 0,
            localImageCount = 0,
            localRuntimeLoaded = runtime.isLoaded,
            connectivity = remoteConnectivity(remoteConfig.normalized()),
            nowElapsedRealtimeMillis = elapsedRealtimeMillis(),
            autoRemoteAuthorized = adaptiveInferenceRollout.autoSelectable &&
                stateBeforeSend.inferenceMode == InferenceMode.Auto,
        )
        val privacy = initialChatPrivacyPlan(
            promptPrivacy = promptPrivacy,
            history = stateBeforeSend.messages,
            remoteImages = emptyList(),
            localImages = emptyList(),
            evidence = null,
        )
        val decision = ModelPlacementPolicy.decide(
            com.bytedance.zgx.solin.orchestration.ModelPlacementInput(
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
            com.bytedance.zgx.solin.orchestration.RunPlacementBinding.fromDecision(
                runId = runId,
                decision = decision,
                remoteProfileRevision = remoteConfig.normalized().profileRevision,
                bootCount = bootCountProvider(),
                boundAtElapsedRealtimeMillis = elapsedRealtimeMillis(),
            )
        }.getOrNull() ?: return null
        return chatPlacementRuntime.bindAndReserve(binding)?.also {
            uiState.update { state ->
                state.copy(
                    activeRunPlacement = binding.placement,
                    activeRunPlacementReason = binding.primaryReason,
                    activePlacementPolicyVersion = binding.policyVersion,
                )
            }
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
                policyVersion = null,
            )
            return
        }
        if (stateBeforeSend.inferenceMode == InferenceMode.Remote) {
            if (effectiveMessagePrivacy == MessagePrivacy.LocalOnly) {
                val userMessage = ChatMessage(
                    role = MessageRole.User,
                    text = userPrompt,
                    privacy = MessagePrivacy.LocalOnly,
                )
                assistantOrchestrator.failModelGeneration(runId, "LocalOnly input cannot use remote serving")
                persistMessagesAndRebuildMemory(
                    messages = stateBeforeSend.messages + userMessage + ChatMessage(
                        role = MessageRole.Assistant,
                        text = "这条内容已标记为仅本地使用。当前为远程模型模式，我不会把它发送到远程模型。",
                        privacy = MessagePrivacy.LocalOnly,
                    ),
                    memoryUserMessage = userMessage,
                )
                uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        activeRunPlacement = null,
                        activeRunPlacementReason = PlacementReasonCode.PRIVACY_REQUIRES_LOCAL,
                        activePlacementPolicyVersion = ModelPlacementPolicy.POLICY_VERSION,
                        statusText = "已保护本地内容",
                    )
                }
                return
            }
            remoteRouteBoundaryFailure(userInput = userPrompt, route = route)?.let { boundaryFailure ->
                val userMessage = ChatMessage(
                    role = MessageRole.User,
                    text = userPrompt,
                    privacy = MessagePrivacy.RemoteEligible,
                )
                assistantOrchestrator.failModelGeneration(runId, boundaryFailure)
                remoteSendSupport.recordRemoteSendAuditEvent(
                    decision = RemoteSendDecision.Blocked,
                    modelName = remoteConfig.normalized().modelName,
                    prompt = route.promptForModel,
                    imageCount = imageAttachments.size,
                    remoteHistoryCount = remoteHistory.size,
                )
                persistMessagesAndRebuildMemory(
                    messages = stateBeforeSend.messages + userMessage + ChatMessage(
                        role = MessageRole.Assistant,
                        text = boundaryFailure,
                        privacy = MessagePrivacy.LocalOnly,
                    ),
                    memoryUserMessage = userMessage,
                )
                uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        activeRunPlacement = null,
                        activeRunPlacementReason = PlacementReasonCode.PRIVACY_REQUIRES_LOCAL,
                        activePlacementPolicyVersion = ModelPlacementPolicy.POLICY_VERSION,
                        statusText = "已阻止远程发送",
                    )
                }
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
        val placementInputs = chatPlacementInputs(
            state = stateBeforeSend,
            promptForModel = route.promptForModel,
            history = stateBeforeSend.messages,
            remoteImageCount = imageAttachments.size,
            localImageCount = localImageAttachments.size,
            localRuntimeLoaded = runtime.isLoaded,
            connectivity = connectivity,
            nowElapsedRealtimeMillis = elapsedRealtimeMillis(),
            autoRemoteAuthorized = adaptiveInferenceRollout.autoSelectable &&
                stateBeforeSend.inferenceMode == InferenceMode.Auto,
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
            failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE, null)
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
                prompt = route.promptForModel,
                history = stateBeforeSend.messages,
                imageAttachments = imageAttachments,
                localImageAttachments = localImageAttachments,
                generationParameters = stateBeforeSend.generationParameters,
                requiresRemoteDisclosure = requiresDisclosure,
                bootCount = bootCountProvider(),
                boundAtElapsedRealtimeMillis = elapsedRealtimeMillis(),
            ),
        )
        when (result) {
            is PrepareChatRunResult.Blocked -> {
                preparedChatExecutions.remove(runId, execution)
                failInitialPlacement(runId, result.decision.primaryReason, result.decision.policyVersion)
            }
            PrepareChatRunResult.BindingRejected -> {
                preparedChatExecutions.remove(runId, execution)
                failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE, null)
            }
            PrepareChatRunResult.DispatchRejected -> {
                preparedChatExecutions.remove(runId, execution)
                failInitialPlacement(runId, PlacementReasonCode.MODEL_EXECUTION_FAILED, null)
            }
            is PrepareChatRunResult.Ready -> Unit
            is PrepareChatRunResult.AwaitingDisclosure -> {
                val disclosure = if (sensitive) {
                    remoteSendSupport.buildSensitiveRemoteSendDisclosure(
                        prompt = userPrompt,
                        messagePrivacy = effectiveMessagePrivacy,
                        remoteConfig = normalizedRemoteConfig,
                        remoteHistory = remoteHistory,
                        imageAttachments = imageAttachments,
                        stateBeforeSend = stateBeforeSend,
                    )
                } else {
                    remoteSendSupport.buildPendingRemoteSendDisclosure(
                        kind = RemoteSendDisclosureKind.CurrentInput,
                        prompt = userPrompt,
                        messagePrivacy = effectiveMessagePrivacy,
                        remoteConfig = normalizedRemoteConfig,
                        remoteHistory = remoteHistory,
                        imageAttachments = imageAttachments,
                        stateBeforeSend = stateBeforeSend,
                    )
                }.copy(
                    runId = result.runId,
                    remoteProfileRevision = result.expectedConfigRevision,
                )
                remoteSendSupport.savePendingRemoteSendMarker(disclosure)
                uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        pendingRemoteSendDisclosure = disclosure,
                        pendingExternalOutcome = null,
                        latestRecoveryAction = null,
                        statusText = if (sensitive) "敏感内容待确认" else "远程发送待确认",
                    )
                }
            }
        }
    }

    private fun resumePreparedChatAfterDisclosure(
        pending: PendingRemoteSendDisclosure,
        promptOverride: String?,
    ) {
        val runId = pending.runId
        val revision = pending.remoteProfileRevision
        if (runId.isNullOrBlank() || revision.isNullOrBlank()) {
            failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE, null)
            return
        }
        val execution = preparedChatExecutions[runId]
        if (execution == null) {
            failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE, null)
            return
        }
        if (uiState.value.activeSessionId != execution.stateBeforeSend.activeSessionId) {
            preparedChatRunCoordinator.cancel(runId)
            preparedChatExecutions.remove(runId, execution)
            deferredRemoteSendAudits.remove(runId)
            failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE, null)
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
        scope.launch(ioDispatcher) {
            if (!preparedChatRunCoordinator.confirmAndDispatch(runId, revision)) {
                preparedChatExecutions.remove(runId, execution)
                deferredRemoteSendAudits.remove(runId)
                failInitialPlacement(runId, PlacementReasonCode.PLACEMENT_NOT_RESTORABLE, null)
            }
        }
    }

    private fun discardPreparedChat(runId: String) {
        preparedChatRunCoordinator.cancel(runId)
        preparedChatExecutions.remove(runId)
        deferredRemoteSendAudits.remove(runId)
        assistantOrchestrator.failModelGeneration(runId, "Remote send disclosure cancelled")
    }

    private fun failInitialPlacement(
        runId: String?,
        reason: PlacementReasonCode,
        policyVersion: Int?,
    ) {
        runId?.let { assistantOrchestrator.failModelGeneration(it, reason.name) }
        uiState.update {
            it.copy(
                isBusy = false,
                isGenerating = false,
                activeRunPlacement = null,
                activeRunPlacementReason = reason,
                activePlacementPolicyVersion = policyVersion,
                statusText = "没有可用的模型执行目标",
            )
        }
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
    ): ChatServingCall = object : ChatServingCall {
        private val stopped = AtomicBoolean(false)
        private val job = AtomicReference<Deferred<Unit>?>(null)

        override suspend fun await() {
            if (stopped.get()) throw CancellationException("Prepared Chat serving was stopped")
            commitDeferredRemoteSendAudit(prepared.runId)
            sendMessageInternal(
                prompt = execution.promptForDispatch,
                explicitMessagePrivacy = execution.effectiveMessagePrivacy,
                imageAttachments = prepared.imageAttachments,
                localImageAttachments = prepared.localImageAttachments,
                remoteSendConfirmed = execution.remoteSendConfirmed,
                currentPromptEvidenceSummary = execution.currentPromptEvidenceSummary,
                preparedExecution = execution,
                preparedPlacement = placement,
                preparedJobLaunched = { launched ->
                    check(job.compareAndSet(null, launched)) { "Prepared Chat job was already assigned" }
                },
            )
            val launched = job.get() ?: error("Prepared Chat generation did not start")
            if (stopped.get()) {
                launched.cancel()
                throw CancellationException("Prepared Chat serving was stopped")
            }
            launched.start()
            launched.await()
        }

        override fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            val launched = job.get()
            if (launched?.isActive == true) {
                when (placement) {
                    RunPlacement.Local -> runtime.stop()
                    RunPlacement.Remote -> remoteRuntime.stop()
                }
            }
            launched?.cancel()
        }
    }

    fun confirmRemoteSendDisclosure(suppressForSession: Boolean = false) {
        deferSuccessfulRemoteSendAudit {
            remoteSendSupport.confirmRemoteSendDisclosure(suppressForSession)
        }
    }

    fun confirmRemoteSendWithMasking() {
        deferSuccessfulRemoteSendAudit(remoteSendSupport::confirmRemoteSendWithMasking)
    }

    fun confirmRemoteSendDespiteSensitive() {
        deferSuccessfulRemoteSendAudit(remoteSendSupport::confirmRemoteSendDespiteSensitive)
    }

    private inline fun deferSuccessfulRemoteSendAudit(confirm: () -> Unit) {
        val runId = uiState.value.pendingRemoteSendDisclosure?.runId
        if (runId.isNullOrBlank()) {
            confirm()
            return
        }
        val previous = deferredAuditRunId.get()
        deferredAuditRunId.set(runId)
        try {
            confirm()
        } finally {
            if (previous == null) deferredAuditRunId.remove() else deferredAuditRunId.set(previous)
        }
    }

    private fun commitDeferredRemoteSendAudit(runId: String) {
        val event = deferredRemoteSendAudits.remove(runId) ?: return
        remoteSendAuditSink.record(event)
        remoteSendSupport.refreshRemoteSendAuditEvents()
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
        if (runId != null) {
            chatPlacementRuntime.stop(runId, AgentRunState.Cancelled)
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
        uiState.value.activeSessionId
            ?.takeIf { it.isNotBlank() }
            ?.let(::teardownSession)
        remoteSendSupport.clearPendingRemoteChatState()
    }

    fun setRemoteSendDisclosurePolicy(policy: RemoteSendDisclosurePolicy) {
        remoteSendSupport.setRemoteSendDisclosurePolicy(policy)
    }
}
