package com.bytedance.zgx.solin

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.audit.RemoteSendAuditLog
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.ToolAuditLog
import com.bytedance.zgx.solin.background.BackgroundTaskScheduler
import com.bytedance.zgx.solin.background.BackgroundTaskUseCases
import com.bytedance.zgx.solin.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.data.BundledModelInstaller
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.GenerationParametersStore
import com.bytedance.zgx.solin.data.HuggingFaceAuthStore
import com.bytedance.zgx.solin.data.ModelDownloadSource
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.ModelSelectionState
import com.bytedance.zgx.solin.data.RemoteModelStore
import com.bytedance.zgx.solin.data.RemoteSendPendingStore
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.device.DeviceContextAuthorizationSnapshot
import com.bytedance.zgx.solin.download.ModelDownloadClient
import com.bytedance.zgx.solin.memory.LongTermMemoryControls
import com.bytedance.zgx.solin.memory.MemoryController
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeController
import com.bytedance.zgx.solin.multimodal.ShareIntentReader
import com.bytedance.zgx.solin.multimodal.SharedInput
import com.bytedance.zgx.solin.multimodal.SharedInputReadMode
import com.bytedance.zgx.solin.orchestration.AgentExternalOutcome
import com.bytedance.zgx.solin.orchestration.AgentRecoveryAction
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.presentation.AuditUiController
import com.bytedance.zgx.solin.presentation.AutoInferenceAuthorizationCoordinator
import com.bytedance.zgx.solin.presentation.BackgroundTaskController
import com.bytedance.zgx.solin.presentation.ChatController
import com.bytedance.zgx.solin.presentation.DefaultPresentationSessionHost
import com.bytedance.zgx.solin.presentation.InferenceModeController
import com.bytedance.zgx.solin.presentation.MemoryUiController
import com.bytedance.zgx.solin.presentation.PresentationSessionHost
import com.bytedance.zgx.solin.presentation.toSemanticMemoryStatus
import com.bytedance.zgx.solin.presentation.ChatPlacementRuntime
import com.bytedance.zgx.solin.presentation.ModelLoadController
import com.bytedance.zgx.solin.presentation.SessionController
import com.bytedance.zgx.solin.presentation.ToolExecutionController
import com.bytedance.zgx.solin.presentation.VoiceInputController
import com.bytedance.zgx.solin.presentation.cleanMessage
import com.bytedance.zgx.solin.presentation.recentRemoteSendSummaries
import com.bytedance.zgx.solin.presentation.localContextWindowTokens
import com.bytedance.zgx.solin.presentation.localPreferredBackends
import com.bytedance.zgx.solin.presentation.modelHealthForCurrentSelection
import com.bytedance.zgx.solin.presentation.toDeviceContextSnapshot
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.ModelOutputQualityGuard
import com.bytedance.zgx.solin.runtime.OkHttpRemoteModelConnectivityProbe
import com.bytedance.zgx.solin.runtime.RemoteChatRuntime
import com.bytedance.zgx.solin.tool.ToolRegistry
import com.bytedance.zgx.solin.tool.defaultBuiltInToolRegistry
import com.bytedance.zgx.solin.resource.StableResourceState
import com.bytedance.zgx.solin.runtime.RemoteConnectivityRefreshCoordinator
import com.bytedance.zgx.solin.runtime.RemoteModelConnectivityProbe
import com.bytedance.zgx.solin.tool.ToolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val STALE_AGENT_RUN_STARTUP_REASON =
    "App restarted before this Agent step completed."
private const val VIEW_MODEL_CLEARED_AGENT_RUN_REASON =
    "ViewModel cleared before this Agent run completed."
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

class SolinViewModel internal constructor(
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
    private val chatPlacementRuntime: ChatPlacementRuntime,
    private val stableResourceStateProvider: () -> StableResourceState,
    private val bootCountProvider: () -> Long,
    private val elapsedRealtimeMillis: () -> Long,
    private val remoteConnectivityProbe: RemoteModelConnectivityProbe? = null,
    private val injectedRemoteConnectivityRefreshCoordinator: RemoteConnectivityRefreshCoordinator? = null,
    private val outputQualityGuard: ModelOutputQualityGuard = ModelOutputQualityGuard(),
    private val remoteSendAuditSink: RemoteSendAuditSink,
    private val remoteSendAuditLog: RemoteSendAuditLog,
    private val remoteSendPendingStore: RemoteSendPendingStore,
    private val bundledModelInstaller: BundledModelInstaller,
    private val skipStartupModelRuntimeWork: Boolean = false,
    private val deferPersistenceInitialization: Boolean = false,
    /**
     * Registry handed to [ToolExecutionController], and through it to the safety authorizer that
     * makes the final allow/reject call before a tool runs.
     *
     * Must be the module-aware registry in production: the authorizer rejects any tool its registry
     * does not know, so a built-in-only registry silently makes every module-contributed tool
     * (plan tools, MCP tools) unexecutable. Defaults to the built-in set so tests that only drive
     * built-in tools stay terse.
     */
    private val toolRegistry: ToolRegistry = defaultBuiltInToolRegistry,
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

    private val memoryUiController = MemoryUiController(
        memoryController = memoryController,
        firstRunSetupStore = firstRunSetupRepository,
        sessionStore = sessionRepository,
        uiState = _uiState,
        skipModelRuntimeWork = skipStartupModelRuntimeWork,
    )

    private val sessionHost: PresentationSessionHost = DefaultPresentationSessionHost(
        sessionStore = sessionRepository,
        uiState = _uiState,
        assistantOrchestrator = assistantOrchestrator,
        memoryUiController = memoryUiController,
        auditUiController = auditUiController,
        backgroundTaskController = backgroundTaskController,
        dispatchPersistenceWork = ::dispatchPersistenceWorkIfNeeded,
    )

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
        adaptiveInferenceRollout = adaptiveInferenceRollout,
        chatPlacementRuntime = chatPlacementRuntime,
        stableResourceStateProvider = stableResourceStateProvider,
        bootCountProvider = bootCountProvider,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        currentRemoteConfig = remoteModelRepository::loadConfig,
        remoteConnectivity = remoteModelRepository::currentConnectivity,
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
        sessionHost = sessionHost,
        deviceContextSnapshot = {
            _uiState.value.toDeviceContextSnapshot(deviceContextAuthorizationSnapshot)
        },
    )

    private val toolExecutionController: ToolExecutionController = ToolExecutionController(
        toolRegistry = toolRegistry,
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
        sessionHost = sessionHost,
        activeRunPlacement = chatPlacementRuntime::activeBinding,
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
        rebuildMemoryIndex = sessionHost::rebuildMemoryIndex,
    )

    private val inferenceModeController = InferenceModeController(
        uiState = _uiState,
        runtime = runtime,
        remoteModelRepository = remoteModelRepository,
        autoInferenceAuthorizationCoordinator = autoInferenceAuthorizationCoordinator,
        chatController = chatController,
        modelLoadController = modelLoadController,
        requireRemoteSendDisclosure = requireRemoteSendDisclosure,
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
                    val initialState = createInitialState()
                    // The Activity may already have synced the permission snapshot while the
                    // deferred initialization was pending; do not reset it to "nothing granted".
                    _uiState.update { current ->
                        initialState.copy(grantedSpecialAccessIds = current.grantedSpecialAccessIds)
                    }
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
        memoryUiController.updateMemoryEnabled(enabled)
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

    fun updateRemoteGuiAutomationEnabled(enabled: Boolean) {
        firstRunSetupRepository.setRemoteGuiAutomationEnabled(enabled)
        _uiState.update {
            it.copy(
                remoteGuiAutomationEnabled = enabled,
                statusText = if (enabled) {
                    "已开启远程视觉操作：远程模型可看屏幕截图并规划点击（首次外发仍需确认）"
                } else {
                    "已关闭远程视觉操作：屏幕截图不再外发，改由本地动作模型驱动"
                },
            )
        }
    }

    fun forgetLongTermMemory(memoryId: String) {
        memoryUiController.forgetLongTermMemory(memoryId)
    }

    fun clearLongTermMemory() {
        memoryUiController.clearLongTermMemory()
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
        inferenceModeController.selectInferenceMode(mode)
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
        inferenceModeController.confirmRemoteModeDisclosure(disclosure)
    }

    fun dismissRemoteModeDisclosure() {
        inferenceModeController.dismissRemoteModeDisclosure()
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
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            runIds.forEach { runId ->
                assistantOrchestrator.terminateRun(runId, VIEW_MODEL_CLEARED_AGENT_RUN_REASON)
            }
        }
        super.onCleared()
    }

    private fun createInitialState(): ChatUiState {
        val modelState = modelRepository.currentState()
        val backend = generationParametersRepository.loadBackend()
        val memoryEnabled = firstRunSetupRepository.isMemoryEnabled()
        val reduceDeviceActionConfirmations = firstRunSetupRepository.reduceDeviceActionConfirmations()
        val remoteGuiAutomationEnabled = firstRunSetupRepository.remoteGuiAutomationEnabled()
        val storedInferenceMode = remoteModelRepository.loadMode()
        val inferenceMode = adaptiveInferenceRollout.sanitizePreference(storedInferenceMode)
        if (inferenceMode != storedInferenceMode) remoteModelRepository.saveMode(inferenceMode)
        val remoteConfig = remoteModelRepository.loadConfig()
        val hasUsableEndpoint = hasStartupModelEndpoint(modelState, remoteConfig)
        val showFirstRunSetup = !firstRunSetupRepository.isSetupDismissed() && !hasUsableEndpoint
        memoryRepository.enabled = memoryEnabled
        syncTaskStateMemories(memoryEnabled = memoryEnabled)
        val semanticMemory = memoryController.semanticMemoryState().toSemanticMemoryStatus()
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
            remoteGuiAutomationEnabled = remoteGuiAutomationEnabled,
            semanticMemory = semanticMemory,
            huggingFaceAccessTokenConfigured = huggingFaceAuthStore.hasAccessToken(),
            longTermMemories = loadLongTermMemories(),
            backgroundTasks = loadBackgroundTasks(),
            backgroundTaskHistory = loadBackgroundTaskHistory(),
            periodicCheckPolicy = loadPeriodicCheckPolicy(),
            auditEvents = loadAuditEvents(),
            remoteSendAuditEvents = remoteSendAuditLog.recentRemoteSendSummaries(),
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

    private fun syncTaskStateMemories(memoryEnabled: Boolean = _uiState.value.memoryEnabled) {
        memoryController.syncTaskStateMemories(memoryEnabled = memoryEnabled)
    }

    private fun loadLongTermMemories(): List<LongTermMemorySummary> =
        memoryController.loadLongTermMemories()

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
}
