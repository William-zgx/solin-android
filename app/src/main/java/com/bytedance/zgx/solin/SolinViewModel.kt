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
import com.bytedance.zgx.solin.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.solin.data.BundledModelInstaller
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.GenerationParametersStore
import com.bytedance.zgx.solin.data.HuggingFaceAuthStore
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
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
import com.bytedance.zgx.solin.orchestration.DecisionModelPlacementPolicy
import com.bytedance.zgx.solin.presentation.AuditUiController
import com.bytedance.zgx.solin.presentation.AutoInferenceAuthorizationCoordinator
import com.bytedance.zgx.solin.presentation.BackgroundTaskController
import com.bytedance.zgx.solin.presentation.ChatController
import com.bytedance.zgx.solin.presentation.DefaultPresentationSessionHost
import com.bytedance.zgx.solin.presentation.InferenceModeController
import com.bytedance.zgx.solin.presentation.InitialChatStateFactory
import com.bytedance.zgx.solin.presentation.StartupStateRestorer
import com.bytedance.zgx.solin.presentation.MemoryPanelState
import com.bytedance.zgx.solin.presentation.MemoryUiController
import com.bytedance.zgx.solin.presentation.PresentationSessionHost
import com.bytedance.zgx.solin.presentation.ChatPlacementRuntime
import com.bytedance.zgx.solin.presentation.ModelLoadController
import com.bytedance.zgx.solin.presentation.SessionController
import com.bytedance.zgx.solin.presentation.SharedIntentIngestionGate
import com.bytedance.zgx.solin.presentation.ToolExecutionController
import com.bytedance.zgx.solin.presentation.VoiceInputController
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
import com.bytedance.zgx.solin.logging.cleanMessage

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
    private val decisionModelPlacementPolicy: DecisionModelPlacementPolicy? = null,
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
    private val sharedIntentIngestionGate = SharedIntentIngestionGate()
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
        syncTaskStateMemories = {
            memoryController.syncTaskStateMemories(memoryEnabled = _uiState.value.memoryEnabled)
        },
        loadLongTermMemories = memoryController::loadLongTermMemories,
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

    private val initialChatStateFactory = InitialChatStateFactory(
        modelRepository = modelRepository,
        generationParametersRepository = generationParametersRepository,
        firstRunSetupRepository = firstRunSetupRepository,
        remoteModelRepository = remoteModelRepository,
        adaptiveInferenceRollout = adaptiveInferenceRollout,
        huggingFaceAuthStore = huggingFaceAuthStore,
        sessionRepository = sessionRepository,
        memoryRepository = memoryRepository,
        memoryController = memoryController,
        auditUiController = auditUiController,
        backgroundTaskController = backgroundTaskController,
        remoteSendAuditLog = remoteSendAuditLog,
        isArm64Device = isArm64DeviceProvider,
    )

    private val _uiState: MutableStateFlow<ChatUiState> = MutableStateFlow(
        if (deferPersistenceInitialization) {
            ChatUiState(
                statusText = "正在初始化本地数据",
                isArm64Supported = isArm64DeviceProvider(),
            )
        } else {
            initialChatStateFactory.createInitialState()
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
        decisionModelPlacementPolicy = decisionModelPlacementPolicy,
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
        loadAgentTraceRuns = auditUiController::loadAgentTraceRuns,
    )

    private val startupStateRestorer = StartupStateRestorer(
        uiState = _uiState,
        modelRepository = modelRepository,
        assistantOrchestrator = assistantOrchestrator,
        chatController = chatController,
        toolExecutionController = toolExecutionController,
        modelLoadController = modelLoadController,
        backgroundTaskController = backgroundTaskController,
        memoryUiController = memoryUiController,
        auditUiController = auditUiController,
        isArm64Device = isArm64DeviceProvider,
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
        val generation = sharedIntentIngestionGate.tryStart(consumptionKey, allowPreviouslyConsumed)
            ?: return
        val copiedIntent = Intent(intent)
        viewModelScope.launch(ioDispatcher) {
            try {
                val sharedInput = reader.read(copiedIntent, mode = readMode)
                if (!sharedIntentIngestionGate.isCurrent(consumptionKey, generation)) return@launch
                persistenceActionMutex.withLock {
                    if (!sharedIntentIngestionGate.isCurrent(consumptionKey, generation)) {
                        return@withLock
                    }
                    if (sharedInput == null) {
                        sharedIntentIngestionGate.clear(consumptionKey, generation)
                    } else {
                        chatController.ingestSharedInput(sharedInput)
                        sharedIntentIngestionGate.markCompleted(consumptionKey, generation)
                    }
                }
            } catch (error: CancellationException) {
                sharedIntentIngestionGate.clear(consumptionKey, generation)
                throw error
            } catch (_: Throwable) {
                sharedIntentIngestionGate.clear(consumptionKey, generation)
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
        assistantOrchestrator.memoryRetrievalOutcome?.let { outcomes ->
            viewModelScope.launch(ioDispatcher) { outcomes.collect(memoryUiController::onMemoryRetrievalOutcome) }
        }
    }

    /** Memory-panel state (retrieval health, "remember this?" suggestions). */
    val memoryPanelState: StateFlow<MemoryPanelState> get() = memoryUiController.memoryPanelState

    fun confirmMemorySuggestion(suggestionId: String) = memoryUiController.confirmMemorySuggestion(suggestionId)

    fun dismissMemorySuggestion(suggestionId: String) = memoryUiController.dismissMemorySuggestion(suggestionId)

    fun restoreStartupState(skipModelRuntimeWork: Boolean = false) {
        if (startupRestored) return
        startupRestored = true
        if (deferPersistenceInitialization) {
            viewModelScope.launch(ioDispatcher) {
                runCatching {
                    val initialState = initialChatStateFactory.createInitialState()
                    // The Activity may already have synced the permission snapshot while the
                    // deferred initialization was pending; do not reset it to "nothing granted".
                    _uiState.update { current ->
                        initialState.copy(grantedSpecialAccessIds = current.grantedSpecialAccessIds)
                    }
                    startupStateRestorer.restoreStartupStateAfterInitialization(skipModelRuntimeWork)
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
        startupStateRestorer.restoreStartupStateAfterInitialization(skipModelRuntimeWork)
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
