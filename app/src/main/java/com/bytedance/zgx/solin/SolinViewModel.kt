package com.bytedance.zgx.solin

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.action.ActionExecutor
import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditLog
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.audit.ToolAuditLog
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.background.BackgroundTaskUseCases
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.data.BundledModelInstaller
import com.bytedance.zgx.solin.data.FirstRunSetupRepository
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.GenerationParametersStore
import com.bytedance.zgx.solin.data.HuggingFaceAuthStore
import com.bytedance.zgx.solin.data.ModelDownloadSource
import com.bytedance.zgx.solin.data.ModelRepository
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.ModelSelectionState
import com.bytedance.zgx.solin.data.ModelVerificationStatus
import com.bytedance.zgx.solin.data.RemoteModelRepository
import com.bytedance.zgx.solin.data.RemoteModelStore
import com.bytedance.zgx.solin.data.RemoteSendPendingStore
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.device.DeviceContextAuthorizationSnapshot
import com.bytedance.zgx.solin.download.ModelDownloadClient
import com.bytedance.zgx.solin.download.ModelDownloadService
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceQuality
import com.bytedance.zgx.solin.evidence.EvidenceQualityLevel
import com.bytedance.zgx.solin.evidence.EvidenceReceiptSummary
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.evidence.toEvidenceReceiptSummary
import com.bytedance.zgx.solin.logging.solinD
import com.bytedance.zgx.solin.logging.solinE
import com.bytedance.zgx.solin.logging.solinI
import com.bytedance.zgx.solin.logging.solinW
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_AUDIT
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_LIFECYCLE
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_MEMORY
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_MODEL
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_REMOTE
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_TOOL
import com.bytedance.zgx.solin.memory.LongTermMemoryControls
import com.bytedance.zgx.solin.memory.MemoryController
import com.bytedance.zgx.solin.memory.MemoryControllerResult
import com.bytedance.zgx.solin.memory.MemoryCommandResult
import com.bytedance.zgx.solin.memory.MemoryHit
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.memory.MemoryRecallMode
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeController
import com.bytedance.zgx.solin.memory.explicitUserFactFrom
import com.bytedance.zgx.solin.memory.explicitUserPreferenceFrom
import com.bytedance.zgx.solin.memory.explicitUserPreferenceForgetFrom
import com.bytedance.zgx.solin.multimodal.ShareIntentReader
import com.bytedance.zgx.solin.multimodal.SharedAttachment
import com.bytedance.zgx.solin.multimodal.SharedAttachmentKind
import com.bytedance.zgx.solin.multimodal.SharedInput
import com.bytedance.zgx.solin.multimodal.SharedInputReadMode
import com.bytedance.zgx.solin.multimodal.toSharedEvidenceReceiptSummary
import com.bytedance.zgx.solin.orchestration.AgentModelObservationResult
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import com.bytedance.zgx.solin.orchestration.AgentObservationDecision
import com.bytedance.zgx.solin.orchestration.AgentObservationResult
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.orchestration.AgentRunOptions
import com.bytedance.zgx.solin.orchestration.AgentRunState
import com.bytedance.zgx.solin.orchestration.AssistantOrchestrator
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.InitialPlanningMode
import com.bytedance.zgx.solin.orchestration.ModelOutputQualityTrace
import com.bytedance.zgx.solin.orchestration.PendingExternalOutcomeSnapshot
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.orchestration.RunDataDestination
import com.bytedance.zgx.solin.orchestration.RunDataReceipt
import com.bytedance.zgx.solin.presentation.AuditUiController
import com.bytedance.zgx.solin.presentation.AutoInferenceAuthorizationCoordinator
import com.bytedance.zgx.solin.presentation.BackgroundTaskController
import com.bytedance.zgx.solin.presentation.ChatController
import com.bytedance.zgx.solin.presentation.ModelLoadController
import com.bytedance.zgx.solin.presentation.SessionController
import com.bytedance.zgx.solin.presentation.ToolExecutionController
import com.bytedance.zgx.solin.presentation.VoiceInputController
import com.bytedance.zgx.solin.presentation.activeModelProfileId
import com.bytedance.zgx.solin.presentation.localContextWindowTokens
import com.bytedance.zgx.solin.presentation.localPreferredBackends
import com.bytedance.zgx.solin.presentation.actionName
import com.bytedance.zgx.solin.presentation.backendAllowedForActiveModel
import com.bytedance.zgx.solin.presentation.composerSummary
import com.bytedance.zgx.solin.presentation.hasLocalImageAttachment
import com.bytedance.zgx.solin.presentation.hasProtectedImageSource
import com.bytedance.zgx.solin.presentation.hasRemoteImageAttachment
import com.bytedance.zgx.solin.presentation.isRemoteVisionSendable
import com.bytedance.zgx.solin.presentation.localImageAttachments
import com.bytedance.zgx.solin.presentation.localModelRuntimeCapabilitiesFor
import com.bytedance.zgx.solin.presentation.modelHealthForCurrentSelection
import com.bytedance.zgx.solin.presentation.preferredBackendForActiveModel
import com.bytedance.zgx.solin.presentation.remoteImageAttachments
import com.bytedance.zgx.solin.presentation.reportOrNull
import com.bytedance.zgx.solin.presentation.toDeviceContextSnapshot
import com.bytedance.zgx.solin.presentation.toTrace
import com.bytedance.zgx.solin.presentation.visibleAssistantText
import com.bytedance.zgx.solin.presentation.withOutputQualityDecision
import com.bytedance.zgx.solin.runtime.GenerationQualityDecision
import com.bytedance.zgx.solin.runtime.GenerationQualityIssue
import com.bytedance.zgx.solin.runtime.GenerationQualityReport
import com.bytedance.zgx.solin.runtime.GenerationRuntimeKind
import com.bytedance.zgx.solin.runtime.AdaptiveGenerationPolicy
import com.bytedance.zgx.solin.runtime.AdaptiveGenerationPolicyInput
import com.bytedance.zgx.solin.runtime.LocalModelRequest
import com.bytedance.zgx.solin.runtime.LocalModelRuntimeCapabilities
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.ModelOutputQualityGuard
import com.bytedance.zgx.solin.runtime.OkHttpRemoteModelConnectivityProbe
import com.bytedance.zgx.solin.runtime.RemoteChatEvent
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import com.bytedance.zgx.solin.runtime.RemoteConnectivityRefreshCoordinator
import com.bytedance.zgx.solin.runtime.RemoteModelConnectivityProbe
import com.bytedance.zgx.solin.safety.SafetyOutcome
import com.bytedance.zgx.solin.safety.SafetyPolicy
import com.bytedance.zgx.solin.tool.ToolExecutor
import com.bytedance.zgx.solin.tool.ToolRequest
import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val STALE_AGENT_RUN_STARTUP_REASON =
    "App restarted before this Agent step completed."
private const val VIEW_MODEL_CLEARED_AGENT_RUN_REASON =
    "ViewModel cleared before this Agent run completed."
private const val PUBLIC_EVIDENCE_BATCH_TOOL_NAME = "public_evidence_batch"
internal const val STREAMING_UI_UPDATE_INTERVAL_MILLIS = 75L
internal const val NO_MODEL_READY_STATUS_TEXT =
    "选择远程模型或下载本地模型后即可开始"


class StreamingAssistantUpdateCoalescer(
    private val publish: (String) -> Unit,
    private val intervalMillis: Long = STREAMING_UI_UPDATE_INTERVAL_MILLIS,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var pendingText: String? = null
    private var lastPublishedText: String? = null
    private var lastPublishedAtMillis: Long? = null

    fun update(text: String) {
        pendingText = text
        val now = nowMillis()
        val lastPublishedAt = lastPublishedAtMillis
        if (lastPublishedAt == null || now - lastPublishedAt >= intervalMillis) {
            publishPending(now)
        }
    }

    fun flush() {
        publishPending(nowMillis())
    }

    fun discard() {
        pendingText = null
    }

    private fun publishPending(now: Long) {
        val text = pendingText ?: return
        pendingText = null
        if (text == lastPublishedText) return
        publish(text)
        lastPublishedText = text
        lastPublishedAtMillis = now
    }
}

class SolinViewModel(
    private val modelRepository: ModelRepositoryFacade,
    private val sessionRepository: SessionStore,
    private val generationParametersRepository: GenerationParametersStore,
    private val remoteModelRepository: RemoteModelStore,
    private val huggingFaceAuthStore: HuggingFaceAuthStore,
    private val firstRunSetupRepository: FirstRunSetupStore,
    private val downloadService: ModelDownloadClient,
    private val runtime: LiteRtRuntime,
    private val remoteRuntime: RemoteChatRuntime,
    private val memoryRepository: MemoryIndex,
    private val longTermMemoryControls: LongTermMemoryControls,
    private val semanticMemoryRuntimeController: SemanticMemoryRuntimeController? =
        memoryRepository as? SemanticMemoryRuntimeController,
    private val backgroundTaskScheduler: BackgroundTaskScheduler,
    private val toolAuditLog: ToolAuditLog,
    private val actionExecutor: ToolExecutor,
    private val assistantOrchestrator: AssistantRouter,
    private val isArm64DeviceProvider: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requireRemoteSendDisclosure: Boolean = true,
    private val adaptiveInferenceRollout: AdaptiveInferenceRollout = AdaptiveInferenceRollout.Off,
    private val remoteConnectivityProbe: RemoteModelConnectivityProbe? = null,
    private val injectedRemoteConnectivityRefreshCoordinator: RemoteConnectivityRefreshCoordinator? = null,
    private val outputQualityGuard: ModelOutputQualityGuard = ModelOutputQualityGuard(),
    private val remoteSendAuditSink: RemoteSendAuditSink,
    private val remoteSendAuditLog: RemoteSendAuditLog,
    private val remoteSendPendingStore: RemoteSendPendingStore,
    private val bundledModelInstaller: BundledModelInstaller,
    private val skipStartupModelRuntimeWork: Boolean = false,
    private val deferPersistenceInitialization: Boolean = false,
) : ViewModel() {
    private val runtimeLock = Mutex()
    private val persistenceActionMutex = Mutex()
    private val persistenceInitialization = CompletableDeferred<Unit>()
    private val sharedIntentIngestionLock = Any()
    private val completedSharedIntentKeys = mutableSetOf<String>()
    private val inFlightSharedIntentGenerations = mutableMapOf<String, Long>()
    private var nextSharedIntentGeneration = 0L
    private var hasCleared = false
    private var startupRestored = false
    private var deviceContextAuthorizationSnapshot = DeviceContextAuthorizationSnapshot()
    private val backgroundTaskUseCases = BackgroundTaskUseCases(backgroundTaskScheduler)
    private val memoryController = MemoryController(
        memoryIndex = memoryRepository,
        longTermMemoryControls = longTermMemoryControls,
        sessionStore = sessionRepository,
        modelRepository = modelRepository,
        backgroundTaskScheduler = backgroundTaskScheduler,
        semanticMemoryRuntimeController = semanticMemoryRuntimeController,
        ioDispatcher = ioDispatcher,
    )

    // Constructed before `_uiState` so createInitialState() can load task/audit snapshots.
    private val auditUiController = AuditUiController(
        toolAuditLog = toolAuditLog,
        assistantOrchestrator = assistantOrchestrator,
    )

    private val backgroundTaskController = BackgroundTaskController(
        backgroundTaskScheduler = backgroundTaskScheduler,
        longTermMemoryControls = longTermMemoryControls,
        backgroundTaskUseCases = backgroundTaskUseCases,
        syncTaskStateMemories = { syncTaskStateMemories() },
        loadLongTermMemories = ::loadLongTermMemories,
    )

    private val autoInferenceAuthorizationCoordinator = AutoInferenceAuthorizationCoordinator(
        rollout = adaptiveInferenceRollout,
        remoteModelStore = remoteModelRepository,
    )
    private val remoteConnectivityRefreshCoordinator =
        injectedRemoteConnectivityRefreshCoordinator ?: RemoteConnectivityRefreshCoordinator(
            remoteModelStore = remoteModelRepository,
            probe = remoteConnectivityProbe ?: OkHttpRemoteModelConnectivityProbe(),
            scope = viewModelScope,
            dispatcher = ioDispatcher,
        )

    private val _uiState = MutableStateFlow(
        if (deferPersistenceInitialization) {
            ChatUiState(
                statusText = "正在初始化本地数据",
                isArm64Supported = isArm64Device(),
            )
        } else {
            createInitialState()
        },
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // ChatController is constructed before ToolExecutionController; tool methods are late-bound
    // lambdas so both sides can call each other after initialization completes.
    private val chatController: ChatController = ChatController(
        modelRepository = modelRepository,
        runtime = runtime,
        remoteRuntime = remoteRuntime,
        assistantOrchestrator = assistantOrchestrator,
        outputQualityGuard = outputQualityGuard,
        remoteSendAuditSink = remoteSendAuditSink,
        remoteSendAuditLog = remoteSendAuditLog,
        remoteSendPendingStore = remoteSendPendingStore,
        uiState = _uiState,
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        runtimeLock = runtimeLock,
        requireRemoteSendDisclosure = requireRemoteSendDisclosure,
        executeToolRequestAfterRunIsExecutingCallback = { confirmation, request ->
            toolExecutionController.executeToolRequestAfterRunIsExecuting(confirmation, request)
        },
        executePublicEvidenceToolBatchAfterRunIsExecutingCallback = { runId, plans ->
            toolExecutionController.executePublicEvidenceToolBatchAfterRunIsExecuting(runId, plans)
        },
        handleNextToolPlanCallback = { runId, plan, pendingStatusText ->
            toolExecutionController.handleNextToolPlan(
                runId = runId,
                plan = plan,
                pendingStatusText = pendingStatusText,
            )
        },
        dispatchPersistenceWorkIfNeededCallback = ::dispatchPersistenceWorkIfNeeded,
        replaceActiveSessionMessagesCallback = ::replaceActiveSessionMessages,
        persistActiveSessionFromUiCallback = ::persistActiveSessionFromUi,
        persistMessagesAndRebuildMemoryImpl = { messages, memoryUserMessage ->
            persistMessagesAndRebuildMemory(messages, memoryUserMessage)
        },
        rebuildMemoryIndexCallback = ::rebuildMemoryIndex,
        syncTaskStateMemoriesCallback = { syncTaskStateMemories() },
        persistExplicitPreferenceMemoryCallback = ::persistExplicitPreferenceMemory,
        handleExplicitPreferenceCommandCallback = ::handleExplicitPreferenceCommand,
        handleExplicitUserFactCommandCallback = ::handleExplicitUserFactCommand,
        handleExplicitMemoryForgetCommandCallback = ::handleExplicitMemoryForgetCommand,
        loadAuditEventsCallback = ::loadAuditEvents,
        loadAgentTraceRunsCallback = ::loadAgentTraceRuns,
        loadLongTermMemoriesCallback = ::loadLongTermMemories,
        activeRunTimelineForCallback = ::activeRunTimelineFor,
        activePublicWebEvidenceForCallback = ::activePublicWebEvidenceFor,
        activeMemoryEvidenceForCallback = ::activeMemoryEvidenceFor,
        deviceContextSnapshot = {
            _uiState.value.toDeviceContextSnapshot(deviceContextAuthorizationSnapshot)
        },
    )

    private val toolExecutionController: ToolExecutionController = ToolExecutionController(
        assistantOrchestrator = assistantOrchestrator,
        actionExecutor = actionExecutor,
        uiState = _uiState,
        ioDispatcher = ioDispatcher,
        isGenerationJobActive = { chatController.isGenerationJobActive },
        launchToolGenerationJob = chatController::launchToolGenerationJob,
        continueAfterToolObservation = { runId, promptForModel, responsePrivacy, remoteToolScope, remoteSendConfirmed ->
            chatController.continueAfterToolObservation(
                runId = runId,
                promptForModel = promptForModel,
                responsePrivacy = responsePrivacy,
                remoteToolScope = remoteToolScope,
                remoteSendConfirmed = remoteSendConfirmed,
            )
        },
        dispatchPersistenceWorkIfNeeded = ::dispatchPersistenceWorkIfNeeded,
        replaceActiveSessionMessages = ::replaceActiveSessionMessages,
        persistActiveSessionFromUi = ::persistActiveSessionFromUi,
        rebuildMemoryIndex = ::rebuildMemoryIndex,
        syncTaskStateMemories = { syncTaskStateMemories() },
        loadAuditEvents = ::loadAuditEvents,
        loadAgentTraceRuns = ::loadAgentTraceRuns,
        activeRunTimelineFor = ::activeRunTimelineFor,
        activePublicWebEvidenceFor = ::activePublicWebEvidenceFor,
        loadBackgroundTasks = ::loadBackgroundTasks,
        loadBackgroundTaskHistory = ::loadBackgroundTaskHistory,
        loadPeriodicCheckPolicy = ::loadPeriodicCheckPolicy,
        loadLongTermMemories = ::loadLongTermMemories,
        activeSessionId = { sessionRepository.activeSessionId },
    )

    private val modelLoadController = ModelLoadController(
        modelRepository = modelRepository,
        downloadService = downloadService,
        runtime = runtime,
        remoteModelRepository = remoteModelRepository,
        huggingFaceAuthStore = huggingFaceAuthStore,
        firstRunSetupRepository = firstRunSetupRepository,
        generationParametersRepository = generationParametersRepository,
        sessionRepository = sessionRepository,
        bundledModelInstaller = bundledModelInstaller,
        remoteConnectivityRefreshCoordinator = remoteConnectivityRefreshCoordinator,
        semanticMemoryRuntimeController = semanticMemoryRuntimeController,
        ioDispatcher = ioDispatcher,
        scope = viewModelScope,
        uiState = _uiState,
        runtimeLock = runtimeLock,
        isArm64DeviceProvider = isArm64DeviceProvider,
        skipStartupModelRuntimeWork = skipStartupModelRuntimeWork,
        autoInferenceAuthorizationCoordinator = autoInferenceAuthorizationCoordinator,
        clearPendingRemoteState = {
            autoInferenceAuthorizationCoordinator.cancel()
            chatController.clearPendingRemoteChatState()
        },
        resetRemoteSendDisclosureSuppression = chatController::resetRemoteSendDisclosureSuppression,
        rebuildMemoryIndex = ::rebuildMemoryIndex,
    )

    private val sessionController = SessionController(
        sessionRepository = sessionRepository,
        assistantOrchestrator = assistantOrchestrator,
        runtime = runtime,
        uiState = _uiState,
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        runtimeLock = runtimeLock,
        clearPendingRemoteChatState = chatController::clearPendingRemoteChatState,
        resetRemoteSendDisclosureSuppression = chatController::resetRemoteSendDisclosureSuppression,
        restorePendingAgentConfirmationIfAny = { clearMissing ->
            toolExecutionController.restorePendingAgentConfirmationIfAny(clearMissing)
        },
        restorePendingExternalOutcomeIfAny = { clearMissing ->
            toolExecutionController.restorePendingExternalOutcomeIfAny(clearMissing)
        },
        loadAgentTraceRuns = ::loadAgentTraceRuns,
    )

    private val voiceInputController = VoiceInputController(
        uiState = _uiState,
        allocateVoiceInputDraftId = chatController::allocateVoiceInputDraftId,
    )

    init {
        auditUiController.bindUiState(_uiState)
        backgroundTaskController.bindUiState(_uiState)
    }

    fun launchPersistenceWork(action: () -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            persistenceActionMutex.withLock { action() }
        }
    }

    fun enqueueSharedIntent(
        consumptionKey: String,
        intent: Intent,
        readMode: SharedInputReadMode,
        reader: ShareIntentReader,
        allowPreviouslyConsumed: Boolean,
    ) {
        val generation = synchronized(sharedIntentIngestionLock) {
            if (consumptionKey in inFlightSharedIntentGenerations) {
                return
            }
            if (!allowPreviouslyConsumed && consumptionKey in completedSharedIntentKeys) {
                return
            }
            if (allowPreviouslyConsumed) {
                completedSharedIntentKeys.remove(consumptionKey)
            }
            (++nextSharedIntentGeneration).also { value ->
                inFlightSharedIntentGenerations[consumptionKey] = value
            }
        }
        val copiedIntent = Intent(intent)
        viewModelScope.launch(ioDispatcher) {
            try {
                val sharedInput = reader.read(copiedIntent, mode = readMode)
                if (!isCurrentSharedIntentIngestion(consumptionKey, generation)) return@launch
                persistenceActionMutex.withLock {
                    if (!isCurrentSharedIntentIngestion(consumptionKey, generation)) {
                        return@withLock
                    }
                    if (sharedInput == null) {
                        clearSharedIntentIngestion(consumptionKey, generation)
                    } else {
                        chatController.ingestSharedInput(sharedInput)
                        markSharedIntentIngestionCompleted(consumptionKey, generation)
                    }
                }
            } catch (error: CancellationException) {
                clearSharedIntentIngestion(consumptionKey, generation)
                throw error
            } catch (_: Throwable) {
                clearSharedIntentIngestion(consumptionKey, generation)
            }
        }
    }

    suspend fun awaitPersistenceInitialization() {
        persistenceInitialization.await()
    }

    init {
        if (!deferPersistenceInitialization) {
            persistenceInitialization.complete(Unit)
        }
    }

    fun restoreStartupState(skipModelRuntimeWork: Boolean = false) {
        if (startupRestored) return
        startupRestored = true
        if (deferPersistenceInitialization) {
            viewModelScope.launch(ioDispatcher) {
                runCatching {
                    _uiState.value = createInitialState()
                    restoreStartupStateAfterInitialization(skipModelRuntimeWork)
                    persistenceInitialization.complete(Unit)
                }.onFailure { error ->
                    persistenceInitialization.completeExceptionally(error)
                    _uiState.update {
                        it.copy(
                            statusText = "本地数据初始化失败：${error.cleanMessage()}",
                            isReady = false,
                        )
                    }
                }
            }
            return
        }
        restoreStartupStateAfterInitialization(skipModelRuntimeWork)
    }

    private fun restoreStartupStateAfterInitialization(skipModelRuntimeWork: Boolean) {
        chatController.failClosedPendingRemoteSendOnStartup()
        recoverBackgroundTasksOnStartup()
        syncTaskStateMemories()
        modelLoadController.refreshDeviceStatus()
        _uiState.update { it.copy(longTermMemories = loadLongTermMemories()) }
        modelLoadController.verifyLegacyModelsOnStartup(skipModelRuntimeWork)
        failStaleAgentRunsOnStartup()
        if (modelLoadController.startBundledModelInstallOnStartup(skipModelRuntimeWork)) {
            toolExecutionController.restorePendingAgentConfirmationIfAny()
            toolExecutionController.restorePendingExternalOutcomeIfAny()
            return
        }

        if (skipModelRuntimeWork) {
            if (_uiState.value.inferenceMode == InferenceMode.Remote) {
                modelLoadController.updateRemoteReadiness("远程模型")
                // Ensure first-run setup is dismissed when remote is already
                // configured (mirrors updateRemoteModelConfig's behaviour so
                // pre-configured test/production states reach isReady=true).
                if (_uiState.value.remoteModelConfig.isConfigured) {
                    _uiState.update { it.copy(showFirstRunSetup = false) }
                }
            } else if (_uiState.value.modelPath != null) {
                _uiState.update {
                    it.copy(statusText = "已找到模型，点击加载模型")
                }
            }
            toolExecutionController.restorePendingAgentConfirmationIfAny()
            toolExecutionController.restorePendingExternalOutcomeIfAny()
            return
        }

        val pendingDownloadId = modelRepository.pendingDownloadId()
        val pendingDownloadSource = modelRepository.loadPendingDownloadSource()
        if (pendingDownloadId > 0L) {
            val source = pendingDownloadSource ?: ModelDownloadSource.recommended(modelRepository.selectedRecommendedModel())
            source.modelId?.let {
                modelLoadController.updateModelState(modelRepository.selectRecommendedModel(it).state)
            }
            val target = modelRepository.downloadedModelFile(source.fileName)
            if (target == null) {
                modelRepository.clearPendingDownload()
                _uiState.update {
                    it.copy(statusText = "下载目录不可用，请导入已有模型")
                }
            } else {
                modelLoadController.monitorDownload(pendingDownloadId, target, source)
            }
        } else if (_uiState.value.inferenceMode == InferenceMode.Remote) {
            modelLoadController.updateRemoteReadiness("远程模型")
        } else if (!isArm64Device()) {
            _uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
        } else {
            val activePath = modelRepository.currentState().activeModelPath
            if (activePath != null) {
                _uiState.update {
                    it.copy(statusText = "已找到模型，正在加载")
                }
                loadModel()
            }
        }
        toolExecutionController.restorePendingAgentConfirmationIfAny()
        toolExecutionController.restorePendingExternalOutcomeIfAny()
    }


    fun configureDebugRemoteModelForScreenshotEvidence(
        baseUrl: String,
        modelName: String,
        supportsVisionInput: Boolean = false,
    ) {
        modelLoadController.configureDebugRemoteModelForScreenshotEvidence(
            baseUrl = baseUrl,
            modelName = modelName,
            supportsVisionInput = supportsVisionInput,
        )
    }


    private fun recoverBackgroundTasksOnStartup() {
        backgroundTaskController.recoverBackgroundTasksOnStartup()
    }

    fun startModelDownload() {
        modelLoadController.startModelDownload()
    }


    fun startRecommendedModelDownload(modelId: String) {
        modelLoadController.startRecommendedModelDownload(modelId)
    }


    fun saveHuggingFaceAccessToken(token: String) {
        modelLoadController.saveHuggingFaceAccessToken(token)
    }


    fun clearHuggingFaceAccessToken() {
        modelLoadController.clearHuggingFaceAccessToken()
    }


    fun toggleSetupModel(modelId: String, selected: Boolean) {
        modelLoadController.toggleSetupModel(modelId, selected)
    }


    fun startSetupModelDownload() {
        modelLoadController.startSetupModelDownload()
    }



    fun skipFirstRunSetup() {
        modelLoadController.skipFirstRunSetup()
    }


    fun updateMemoryEnabled(enabled: Boolean) {
        firstRunSetupRepository.setMemoryEnabled(enabled)
        val result = memoryController.setMemoryEnabled(
            enabled = enabled,
            skipModelRuntimeWork = skipStartupModelRuntimeWork,
        )
        val rebuild = result.rebuildResult
        _uiState.update { state ->
            state.copy(
                memoryEnabled = result.enabled,
                memoryHits = when {
                    rebuild.memoryHitsCleared -> emptyList()
                    result.clearMemoryHits -> emptyList()
                    else -> state.memoryHits
                },
                longTermMemories = if (rebuild.memoryHitsCleared) {
                    rebuild.longTermMemories
                } else {
                    result.longTermMemories
                },
                statusText = rebuild.statusText ?: result.statusText,
                semanticMemoryEnabled = rebuild.semanticMemoryEnabled,
                semanticMemoryRuntimeStatus = rebuild.semanticMemoryRuntimeStatus,
                semanticMemoryIndexedRecordCount = rebuild.semanticMemoryIndexedRecordCount,
                semanticMemoryLastRebuiltAtMillis = rebuild.semanticMemoryLastRebuiltAtMillis,
            )
        }
    }

    fun updateReduceDeviceActionConfirmations(enabled: Boolean) {
        firstRunSetupRepository.setReduceDeviceActionConfirmations(enabled)
        _uiState.update {
            it.copy(
                reduceDeviceActionConfirmations = enabled,
                statusText = if (enabled) {
                    "低风险手机操作将减少确认"
                } else {
                    "手机操作确认已恢复为保守模式"
                },
            )
        }
    }

    fun forgetLongTermMemory(memoryId: String) {
        if (_uiState.value.isBusy) return
        val result = memoryController.forgetLongTermMemory(
            memoryId = memoryId,
            memoryEnabled = _uiState.value.memoryEnabled,
            skipModelRuntimeWork = skipStartupModelRuntimeWork,
        )
        val rebuild = result.rebuildResult
        _uiState.update { state ->
            state.copy(
                longTermMemories = result.longTermMemories,
                memoryHits = if (rebuild.memoryHitsCleared) {
                    emptyList()
                } else {
                    state.memoryHits.filterNot { hit -> hit.id == memoryId }
                },
                // Match prior behavior: forget status overwrites rebuild failure text.
                statusText = result.statusText,
                semanticMemoryEnabled = rebuild.semanticMemoryEnabled,
                semanticMemoryRuntimeStatus = rebuild.semanticMemoryRuntimeStatus,
                semanticMemoryIndexedRecordCount = rebuild.semanticMemoryIndexedRecordCount,
                semanticMemoryLastRebuiltAtMillis = rebuild.semanticMemoryLastRebuiltAtMillis,
            )
        }
    }

    fun clearLongTermMemory() {
        if (_uiState.value.isBusy) return
        val result = memoryController.clearLongTermMemory(
            memoryEnabled = _uiState.value.memoryEnabled,
            skipModelRuntimeWork = skipStartupModelRuntimeWork,
        )
        val rebuild = result.rebuildResult
        _uiState.update { state ->
            state.copy(
                longTermMemories = emptyList(),
                memoryHits = emptyList(),
                // Match prior behavior: clear status overwrites rebuild failure text.
                statusText = result.statusText,
                semanticMemoryEnabled = rebuild.semanticMemoryEnabled,
                semanticMemoryRuntimeStatus = rebuild.semanticMemoryRuntimeStatus,
                semanticMemoryIndexedRecordCount = rebuild.semanticMemoryIndexedRecordCount,
                semanticMemoryLastRebuiltAtMillis = rebuild.semanticMemoryLastRebuiltAtMillis,
            )
        }
    }

    fun refreshBackgroundTasks() {
        backgroundTaskController.refreshBackgroundTasks()
    }

    fun updateDeviceContextAuthorizationSnapshot(snapshot: DeviceContextAuthorizationSnapshot) {
        deviceContextAuthorizationSnapshot = snapshot
        _uiState.update { it.copy(grantedSpecialAccessIds = snapshot.grantedSpecialAccessIds) }
    }

    fun cancelBackgroundTask(taskId: String) {
        backgroundTaskController.cancelBackgroundTask(taskId)
    }

    fun setPeriodicCheckPolicy(request: PeriodicCheckScheduleRequest) {
        backgroundTaskController.setPeriodicCheckPolicy(request)
    }

    fun disablePeriodicCheckPolicy() {
        backgroundTaskController.disablePeriodicCheckPolicy()
    }

    fun refreshAuditEvents() {
        auditUiController.refreshAuditEvents()
    }

    fun acceptVoiceTranscript(transcript: String) {
        voiceInputController.acceptVoiceTranscript(transcript)
    }

    fun consumeVoiceInputDraft(draftId: Long) {
        voiceInputController.consumeVoiceInputDraft(draftId)
    }

    fun reportVoiceInputUnavailable(message: String = "语音输入不可用") {
        voiceInputController.reportVoiceInputUnavailable(message)
    }

    fun startVoiceInputCapture() {
        voiceInputController.startVoiceInputCapture()
    }

    fun updateVoiceInputLevel(rmsDb: Float) {
        voiceInputController.updateVoiceInputLevel(rmsDb)
    }

    fun updateVoiceInputPartialTranscript(transcript: String) {
        voiceInputController.updateVoiceInputPartialTranscript(transcript)
    }

    fun finishVoiceInputCapture(message: String = "正在转写") {
        voiceInputController.finishVoiceInputCapture(message)
    }

    fun reportSystemSettingsUnavailable(message: String = "系统设置不可用") {
        _uiState.update {
            it.copy(statusText = message)
        }
    }

    fun reportSpecialAccessResult(
        requirement: SpecialAccessRequirement,
        granted: Boolean,
    ) {
        _uiState.update {
            it.copy(
                statusText = if (granted) {
                    "${requirement.title}已开启"
                } else {
                    "返回后仍未开启${requirement.title}"
                },
            )
        }
    }

    fun startCustomModelDownload(downloadUrl: String) {
        modelLoadController.startCustomModelDownload(downloadUrl)
    }


    fun cancelModelDownload() {
        modelLoadController.cancelModelDownload()
    }


    fun importModel(uri: Uri) {
        modelLoadController.importModel(uri)
    }


    fun selectBackend(choice: BackendChoice) {
        modelLoadController.selectBackend(choice)
    }


    fun updateGenerationParameters(parameters: GenerationParameters) {
        if (_uiState.value.isBusy) return
        val normalized = generationParametersRepository.save(parameters)
        _uiState.update {
            it.copy(
                generationParameters = normalized,
                statusText = if (runtime.isLoaded) "参数已更新，正在应用" else "参数已更新",
            )
        }
        if (runtime.isLoaded) {
            sessionController.recreateConversationForActiveSession("参数已生效")
        }
    }

    fun resetGenerationParameters() {
        updateGenerationParameters(GenerationParameters())
    }

    fun selectInferenceMode(mode: InferenceMode) {
        if (_uiState.value.isBusy) return
        if (mode == InferenceMode.Auto) {
            if (!autoInferenceAuthorizationCoordinator.request()) {
                _uiState.update { it.copy(statusText = "自动模式当前未开放") }
                return
            }
            chatController.clearPendingRemoteChatState()
            chatController.resetRemoteSendDisclosureSuppression()
            val disclosure = modelLoadController.remoteModeDisclosure()
            _uiState.update {
                it.copy(
                    pendingSharedInputDraft = null,
                    pendingRemoteModeDisclosure = disclosure,
                    pendingRemoteSendDisclosure = null,
                    statusText = "自动模式待确认",
                )
            }
            return
        }

        val cancelledAutoSelection = autoInferenceAuthorizationCoordinator.hasPending
        autoInferenceAuthorizationCoordinator.cancel()
        if (cancelledAutoSelection) {
            _uiState.update { it.copy(pendingRemoteModeDisclosure = null) }
        }
        if (_uiState.value.inferenceMode == mode) return
        // Trust boundary changed (inference mode switch): a prior session-scoped
        // "don't ask again" must not silently carry into the new destination.
        chatController.clearPendingRemoteChatState()
        chatController.resetRemoteSendDisclosureSuppression()
        remoteModelRepository.saveMode(mode)
        if (mode == InferenceMode.Remote) {
            runtime.close()
            _uiState.update { it.copy(pendingSharedInputDraft = null) }
            modelLoadController.updateRemoteReadiness(
                prefix = "已切换到远程模型",
                showModeDisclosure = requireRemoteSendDisclosure,
            )
            return
        }

        _uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                pendingSharedInputDraft = null,
                pendingRemoteModeDisclosure = null,
                pendingRemoteSendDisclosure = null,
                isReady = runtime.isLoaded,
                statusText = if (runtime.isLoaded) {
                    "就绪 · ${it.backend.label()}"
                } else if (it.modelPath != null) {
                    "已切换到本地模型，点击加载模型"
                } else {
                    "请先下载或导入本地模型"
                },
                modelHealth = ModelHealth(
                    profileId = it.activeModelProfileId(),
                    state = if (runtime.isLoaded) {
                        ModelHealthState.Loaded
                    } else if (it.modelPath != null) {
                        it.modelHealth.state.takeIf { state ->
                            state == ModelHealthState.Verified || state == ModelHealthState.InstalledUnverified
                        } ?: ModelHealthState.Verified
                    } else {
                        ModelHealthState.NotInstalled
                    },
                    backend = it.backend.takeIf { _ -> it.modelPath != null },
                ),
            )
        }
    }

    fun updateRemoteModelConfig(config: RemoteModelConfig) {
        modelLoadController.updateRemoteModelConfig(config)
    }


    fun testRemoteModelConnectivity() {
        modelLoadController.testRemoteModelConnectivity()
    }

    fun refreshRemoteModelConnectivityIfStale() {
        modelLoadController.refreshRemoteConnectivityIfStale()
    }


    fun selectRecommendedModel(modelId: String) {
        modelLoadController.selectRecommendedModel(modelId)
    }


    fun selectInstalledModel(modelId: String) {
        modelLoadController.selectInstalledModel(modelId)
    }


    fun deleteInstalledModel(modelId: String) {
        modelLoadController.deleteInstalledModel(modelId)
    }


    fun loadModel() {
        modelLoadController.loadModel()
    }


    fun resetConversation() {
        sessionController.resetConversation()
    }

    fun createNewSession() {
        sessionController.createNewSession()
    }

    fun selectSession(sessionId: String) {
        sessionController.selectSession(sessionId)
    }

    fun deleteActiveSession() {
        sessionController.deleteActiveSession()
    }


    fun confirmRemoteModeDisclosure(disclosure: PendingRemoteModeDisclosure) {
        var refreshConnectivity = false
        autoInferenceAuthorizationCoordinator.serialized {
            if (_uiState.value.pendingRemoteModeDisclosure !== disclosure) return@serialized
            if (!autoInferenceAuthorizationCoordinator.hasPending) {
                dismissRemoteModeDisclosure()
                return@serialized
            }
            val confirmed = autoInferenceAuthorizationCoordinator.confirm()
            val currentConfig = remoteModelRepository.loadConfig()
            _uiState.update {
                it.copy(
                    inferenceMode = if (confirmed) InferenceMode.Auto else it.inferenceMode,
                    remoteModelConfig = currentConfig,
                    pendingRemoteModeDisclosure = null,
                    pendingRemoteSendDisclosure = null,
                    isReady = if (confirmed) runtime.isLoaded || currentConfig.isConfigured else it.isReady,
                    statusText = if (confirmed) {
                        "已启用自动模式"
                    } else {
                        "远程配置已变化，请重新选择自动模式"
                    },
                )
            }
            refreshConnectivity = confirmed
        }
        if (refreshConnectivity) modelLoadController.refreshRemoteConnectivityIfStale()
    }

    fun dismissRemoteModeDisclosure() {
        val cancelledAutoSelection = autoInferenceAuthorizationCoordinator.hasPending
        autoInferenceAuthorizationCoordinator.cancel()
        _uiState.update {
            if (it.pendingRemoteModeDisclosure == null) {
                it
            } else {
                it.copy(
                    pendingRemoteModeDisclosure = null,
                    statusText = when {
                        cancelledAutoSelection && it.inferenceMode == InferenceMode.Local ->
                            if (runtime.isLoaded) "就绪 · ${it.backend.label()}" else it.statusText
                        cancelledAutoSelection -> it.statusText
                        it.inferenceMode == InferenceMode.Remote ->
                            if (it.remoteModelConfig.isConfigured) "就绪 · 远程" else "请配置远程模型"
                        else -> it.statusText
                    },
                )
            }
        }
    }

    fun sendMessage(prompt: String) {
        chatController.sendMessage(prompt)
    }

    fun sendMessage(prompt: String, messagePrivacy: MessagePrivacy) {
        chatController.sendMessage(prompt, messagePrivacy)
    }

    fun stopGeneration() {
        chatController.stopGeneration()
    }

    fun confirmRemoteSendDisclosure(suppressForSession: Boolean = false) {
        chatController.confirmRemoteSendDisclosure(suppressForSession)
    }

    fun confirmRemoteSendWithMasking() {
        chatController.confirmRemoteSendWithMasking()
    }

    fun confirmRemoteSendDespiteSensitive() {
        chatController.confirmRemoteSendDespiteSensitive()
    }

    fun dismissRemoteSendDisclosure() {
        chatController.dismissRemoteSendDisclosure()
    }

    fun setRemoteSendDisclosurePolicy(policy: RemoteSendDisclosurePolicy) {
        chatController.setRemoteSendDisclosurePolicy(policy)
    }

    fun refreshRemoteSendAuditEvents() {
        chatController.refreshRemoteSendAuditEvents()
    }

    fun ingestSharedInput(sharedInput: SharedInput) {
        chatController.ingestSharedInput(sharedInput)
    }

    fun stageSharedInput(sharedInput: SharedInput) {
        chatController.stageSharedInput(sharedInput)
    }

    fun clearPendingSharedInputDraft(draftId: Long) {
        chatController.clearPendingSharedInputDraft(draftId)
    }

    fun sendPendingSharedInput(userInstruction: String = "") {
        chatController.sendPendingSharedInput(userInstruction)
    }

    fun requestRecoveryActionConfirmation(action: AgentRecoveryAction) {
        toolExecutionController.requestRecoveryActionConfirmation(action)
    }

    fun confirmAgentConfirmation(confirmation: PendingAgentConfirmation) {
        toolExecutionController.confirmAgentConfirmation(confirmation)
    }

    fun dismissAgentConfirmation(confirmation: PendingAgentConfirmation? = _uiState.value.pendingConfirmation) {
        toolExecutionController.dismissAgentConfirmation(confirmation)
    }

    fun rejectAgentConfirmationForRuntimePermissionDenial(
        confirmation: PendingAgentConfirmation,
        deniedPermissions: List<String>,
    ) {
        toolExecutionController.rejectAgentConfirmationForRuntimePermissionDenial(
            confirmation = confirmation,
            deniedPermissions = deniedPermissions,
        )
    }

    fun rejectAgentConfirmationForSpecialAccessDenial(
        confirmation: PendingAgentConfirmation,
        deniedRequirements: List<SpecialAccessRequirement>,
    ) {
        toolExecutionController.rejectAgentConfirmationForSpecialAccessDenial(
            confirmation = confirmation,
            deniedRequirements = deniedRequirements,
        )
    }

    fun rejectAgentConfirmationForMediaProjectionDenial(
        confirmation: PendingAgentConfirmation,
    ) {
        toolExecutionController.rejectAgentConfirmationForMediaProjectionDenial(confirmation)
    }

    fun recordExternalOutcome(
        pending: PendingExternalOutcomeConfirmation,
        outcome: AgentExternalOutcome,
    ) {
        toolExecutionController.recordExternalOutcome(pending, outcome)
    }

    fun confirmActionDraft(draft: ActionDraft) {
        toolExecutionController.confirmActionDraft(draft)
    }

    fun dismissActionDraft() {
        toolExecutionController.dismissActionDraft()
    }


    override fun onCleared() {
        if (hasCleared) return
        hasCleared = true
        val runIds = chatController.cancelGenerationForTeardown()
        modelLoadController.close()
        sessionController.close()
        remoteRuntime.stop()
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            runIds.forEach { runId ->
                assistantOrchestrator.terminateRun(runId, VIEW_MODEL_CLEARED_AGENT_RUN_REASON)
            }
        }
        super.onCleared()
    }

    private fun loadRemoteSendAuditEvents(): List<RemoteSendAuditSummary> =
        remoteSendAuditLog.recentRemoteSends().map { event ->
            RemoteSendAuditSummary(
                id = event.id,
                decisionLabel = event.decision.label,
                modelName = event.modelName,
                summary = event.summary,
                createdAtMillis = event.createdAtMillis,
            )
        }

    private fun Throwable.cleanMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName








    private fun createInitialState(): ChatUiState {
        val modelState = modelRepository.currentState()
        val backend = generationParametersRepository.loadBackend()
        val memoryEnabled = firstRunSetupRepository.isMemoryEnabled()
        val reduceDeviceActionConfirmations = firstRunSetupRepository.reduceDeviceActionConfirmations()
        val storedInferenceMode = remoteModelRepository.loadMode()
        val inferenceMode = adaptiveInferenceRollout.sanitizePreference(storedInferenceMode)
        if (inferenceMode != storedInferenceMode) remoteModelRepository.saveMode(inferenceMode)
        val remoteConfig = remoteModelRepository.loadConfig()
        val hasUsableEndpoint = hasStartupModelEndpoint(modelState, remoteConfig)
        val showFirstRunSetup = !firstRunSetupRepository.isSetupDismissed() && !hasUsableEndpoint
        memoryRepository.enabled = memoryEnabled
        syncTaskStateMemories(memoryEnabled = memoryEnabled)
        val semanticMemory = memoryController.semanticMemoryState()
        return ChatUiState(
            modelPath = modelState.activeModelPath,
            activeInstalledModelId = modelState.activeInstalledModelId,
            installedModels = modelState.installedModels,
            selectedModelId = modelState.selectedModelId,
            inferenceMode = inferenceMode,
            remoteModelConfig = remoteConfig,
            backend = backend,
            localMaxTotalTokens = modelState.localContextWindowTokens(),
            localPreferredBackends = modelState.localPreferredBackends(),
            modelHealth = modelState.modelHealthForCurrentSelection(backend),
            showFirstRunSetup = showFirstRunSetup,
            memoryEnabled = memoryEnabled,
            reduceDeviceActionConfirmations = reduceDeviceActionConfirmations,
            semanticMemoryEnabled = semanticMemory.semanticMemoryEnabled,
            semanticMemoryRuntimeStatus = semanticMemory.semanticMemoryRuntimeStatus,
            semanticMemoryIndexedRecordCount = semanticMemory.semanticMemoryIndexedRecordCount,
            semanticMemoryLastRebuiltAtMillis = semanticMemory.semanticMemoryLastRebuiltAtMillis,
            huggingFaceAccessTokenConfigured = huggingFaceAuthStore.hasAccessToken(),
            longTermMemories = loadLongTermMemories(),
            backgroundTasks = loadBackgroundTasks(),
            backgroundTaskHistory = loadBackgroundTaskHistory(),
            periodicCheckPolicy = loadPeriodicCheckPolicy(),
            auditEvents = loadAuditEvents(),
            remoteSendAuditEvents = loadRemoteSendAuditEvents(),
            agentTraceRuns = loadAgentTraceRuns(),
            generationParameters = generationParametersRepository.load(),
            sessions = sessionRepository.summaries(),
            activeSessionId = sessionRepository.activeSessionId,
            messages = sessionRepository.activeMessages(),
            isArm64Supported = isArm64Device(),
            availableModelStorageBytes = modelRepository.resolveModelStorageBytes(),
            statusText = if (!hasUsableEndpoint) {
                NO_MODEL_READY_STATUS_TEXT
            } else {
                "未加载模型"
            },
        )
    }

    private fun hasStartupModelEndpoint(
        modelState: ModelSelectionState,
        remoteConfig: RemoteModelConfig,
    ): Boolean =
        modelState.activeModelPath != null || remoteConfig.isConfigured






    // Memory paths: thin UI wrappers over MemoryController (Wave 2 Track C2b).
    private fun rebuildMemoryIndex() {
        val startMs = System.currentTimeMillis()
        val result = memoryController.rebuildMemoryIndex(
            memoryEnabled = _uiState.value.memoryEnabled,
            skipModelRuntimeWork = skipStartupModelRuntimeWork,
        )
        val durationMs = System.currentTimeMillis() - startMs
        applyMemoryControllerResult(result, logRebuild = true, durationMs = durationMs)
    }

    private fun syncTaskStateMemories(memoryEnabled: Boolean = _uiState.value.memoryEnabled) {
        memoryController.syncTaskStateMemories(memoryEnabled = memoryEnabled)
    }

    private fun loadLongTermMemories(): List<LongTermMemorySummary> =
        memoryController.loadLongTermMemories()

    private fun persistExplicitPreferenceMemory(message: ChatMessage): Boolean =
        runCatching {
            memoryController.persistExplicitPreferenceMemory(message).also { persisted ->
                if (persisted) {
                    _uiState.update { state ->
                        state.copy(longTermMemories = loadLongTermMemories())
                    }
                }
            }
        }.onFailure {
            _uiState.update { state ->
                state.copy(
                    longTermMemories = emptyList(),
                    statusText = "本地记忆暂不可用",
                )
            }
        }.getOrDefault(false)

    private fun handleExplicitPreferenceCommand(trimmed: String) {
        applyMemoryCommandResult(
            memoryController.handleExplicitPreferenceCommand(
                trimmed = trimmed,
                memoryEnabled = _uiState.value.memoryEnabled,
                skipModelRuntimeWork = skipStartupModelRuntimeWork,
            ),
        )
    }

    private fun handleExplicitUserFactCommand(trimmed: String) {
        applyMemoryCommandResult(
            memoryController.handleExplicitUserFactCommand(
                trimmed = trimmed,
                memoryEnabled = _uiState.value.memoryEnabled,
                skipModelRuntimeWork = skipStartupModelRuntimeWork,
            ),
        )
    }

    private fun handleExplicitMemoryForgetCommand(trimmed: String) {
        applyMemoryCommandResult(
            memoryController.handleExplicitMemoryForgetCommand(
                trimmed = trimmed,
                memoryEnabled = _uiState.value.memoryEnabled,
                skipModelRuntimeWork = skipStartupModelRuntimeWork,
            ),
        )
    }

    private fun applyMemoryCommandResult(result: MemoryCommandResult) {
        val rebuild = result.rebuildResult
        _uiState.update { state ->
            state.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = sessionRepository.activeSessionId,
                messages = sessionRepository.activeMessages(),
                memoryHits = emptyList(),
                longTermMemories = if (rebuild.memoryHitsCleared) {
                    rebuild.longTermMemories
                } else {
                    result.longTermMemories
                },
                // Match prior behavior: command status overwrites rebuild failure text.
                statusText = result.statusText,
                semanticMemoryEnabled = rebuild.semanticMemoryEnabled,
                semanticMemoryRuntimeStatus = rebuild.semanticMemoryRuntimeStatus,
                semanticMemoryIndexedRecordCount = rebuild.semanticMemoryIndexedRecordCount,
                semanticMemoryLastRebuiltAtMillis = rebuild.semanticMemoryLastRebuiltAtMillis,
            )
        }
    }

    private fun applyMemoryControllerResult(
        result: MemoryControllerResult,
        logRebuild: Boolean = false,
        durationMs: Long = 0L,
    ) {
        if (logRebuild) {
            if (result.memoryHitsCleared || result.statusText != null) {
                solinW(TAG_MEMORY, "rebuildMemoryIndex: failed status=${result.statusText}")
            } else {
                solinD(
                    TAG_MEMORY,
                    "rebuildMemoryIndex: success hits=${result.semanticMemoryIndexedRecordCount} " +
                        "durationMs=$durationMs",
                )
            }
        }
        _uiState.update { state ->
            state.copy(
                semanticMemoryEnabled = result.semanticMemoryEnabled,
                semanticMemoryRuntimeStatus = result.semanticMemoryRuntimeStatus,
                semanticMemoryIndexedRecordCount = result.semanticMemoryIndexedRecordCount,
                semanticMemoryLastRebuiltAtMillis = result.semanticMemoryLastRebuiltAtMillis,
                memoryHits = if (result.memoryHitsCleared) emptyList() else state.memoryHits,
                longTermMemories = if (result.memoryHitsCleared) {
                    result.longTermMemories
                } else {
                    state.longTermMemories
                },
                statusText = result.statusText ?: state.statusText,
            )
        }
    }

    private fun loadBackgroundTasks(): List<BackgroundTaskSummary> =
        backgroundTaskController.loadBackgroundTasks()

    private fun loadBackgroundTaskHistory(): List<BackgroundTaskSummary> =
        backgroundTaskController.loadBackgroundTaskHistory()

    private fun loadPeriodicCheckPolicy(): PeriodicCheckPolicySummary =
        backgroundTaskController.loadPeriodicCheckPolicy()

    private fun loadAuditEvents(): List<AuditEventSummary> =
        auditUiController.loadAuditEvents()

    private fun loadAgentTraceRuns(): List<AgentTraceRunUiSummary> =
        auditUiController.loadAgentTraceRuns()

    private fun activeRunTimelineFor(runId: String?): List<RunTimelineItemUiSummary> =
        runId
            ?.let { id -> runCatching { assistantOrchestrator.runEvents(id) }.getOrDefault(emptyList()) }
            ?.let(::runTimelineSummariesFor)
            .orEmpty()

    private fun activePublicWebEvidenceFor(runId: String?): List<PublicWebEvidencePack> =
        runId
            ?.let { id -> runCatching { assistantOrchestrator.publicWebEvidence(id) }.getOrDefault(emptyList()) }
            .orEmpty()

    private fun activeMemoryEvidenceFor(
        memoryHits: List<MemoryHit>,
        includePrivateLocalContext: Boolean,
    ): List<MemoryEvidenceUiSummary> =
        if (includePrivateLocalContext) memoryEvidenceSummariesFor(memoryHits) else emptyList()

    private fun failStaleAgentRunsOnStartup() {
        val failedCount = assistantOrchestrator.failStaleInFlightRuns(STALE_AGENT_RUN_STARTUP_REASON)
        if (failedCount <= 0) return
        _uiState.update {
            it.copy(agentTraceRuns = loadAgentTraceRuns())
        }
    }

    private fun isArm64Device(): Boolean =
        isArm64DeviceProvider()

    private fun isCurrentSharedIntentIngestion(
        consumptionKey: String,
        generation: Long,
    ): Boolean =
        synchronized(sharedIntentIngestionLock) {
            inFlightSharedIntentGenerations[consumptionKey] == generation
        }

    private fun markSharedIntentIngestionCompleted(
        consumptionKey: String,
        generation: Long,
    ) {
        synchronized(sharedIntentIngestionLock) {
            if (inFlightSharedIntentGenerations[consumptionKey] != generation) return
            inFlightSharedIntentGenerations.remove(consumptionKey)
            completedSharedIntentKeys += consumptionKey
        }
    }

    private fun clearSharedIntentIngestion(
        consumptionKey: String,
        generation: Long,
    ) {
        synchronized(sharedIntentIngestionLock) {
            if (inFlightSharedIntentGenerations[consumptionKey] == generation) {
                inFlightSharedIntentGenerations.remove(consumptionKey)
            }
        }
    }

    private fun dispatchPersistenceWorkIfNeeded(work: () -> Unit): Boolean {
        if (
            !deferPersistenceInitialization ||
            Looper.myLooper() != Looper.getMainLooper()
        ) {
            return false
        }
        launchPersistenceWork(work)
        return true
    }

    private fun replaceActiveSessionMessages(
        messages: List<ChatMessage>,
        persistNow: Boolean,
    ) {
        sessionRepository.replaceActiveSessionMessages(messages, persistNow)
        _uiState.update {
            it.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = sessionRepository.activeSessionId,
                messages = messages,
            )
        }
    }

    private fun persistActiveSessionFromUi() {
        val messages = _uiState.value.messages
        sessionRepository.persistActiveSessionFrom(messages)
        _uiState.update {
            it.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = sessionRepository.activeSessionId,
                messages = messages,
            )
        }
    }

    /**
     * Combined "persist messages + optionally persist explicit preference memory +
     * rebuild memory index" helper.  Extracts the repeated sequence that appears
     * throughout send-message / tool-execution / command-handling paths.
     *
     * @param messages Final message list to write to the active session.
     * @param memoryUserMessage When non-null, [persistExplicitPreferenceMemory] is
     *   invoked for this message before the index rebuild.
     */
    private fun persistMessagesAndRebuildMemory(
        messages: List<ChatMessage>,
        memoryUserMessage: ChatMessage? = null,
    ) {
        replaceActiveSessionMessages(messages, persistNow = true)
        if (memoryUserMessage != null) {
            persistExplicitPreferenceMemory(memoryUserMessage)
        }
        rebuildMemoryIndex()
    }


}
