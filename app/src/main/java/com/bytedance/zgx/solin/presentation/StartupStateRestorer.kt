package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AdaptiveInferenceRollout
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.NO_MODEL_READY_STATUS_TEXT
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.audit.RemoteSendAuditLog
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.GenerationParametersStore
import com.bytedance.zgx.solin.data.HuggingFaceAuthStore
import com.bytedance.zgx.solin.data.ModelDownloadSource
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.ModelSelectionState
import com.bytedance.zgx.solin.data.RemoteModelStore
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.memory.MemoryController
import com.bytedance.zgx.solin.memory.MemoryIndex
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val STALE_AGENT_RUN_STARTUP_REASON =
    "App restarted before this Agent step completed."

/**
 * Builds the initial [ChatUiState] from persisted stores. It is constructed before the
 * ViewModel's state flow exists, so it only reads stores.
 */
internal class InitialChatStateFactory(
    private val modelRepository: ModelRepositoryFacade,
    private val generationParametersRepository: GenerationParametersStore,
    private val firstRunSetupRepository: FirstRunSetupStore,
    private val remoteModelRepository: RemoteModelStore,
    private val adaptiveInferenceRollout: AdaptiveInferenceRollout,
    private val huggingFaceAuthStore: HuggingFaceAuthStore,
    private val sessionRepository: SessionStore,
    private val memoryRepository: MemoryIndex,
    private val memoryController: MemoryController,
    private val auditUiController: AuditUiController,
    private val backgroundTaskController: BackgroundTaskController,
    private val remoteSendAuditLog: RemoteSendAuditLog,
    private val isArm64Device: () -> Boolean
) {
    fun createInitialState(): ChatUiState {
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
        memoryController.syncTaskStateMemories(memoryEnabled = memoryEnabled)
        val semanticMemory = memoryController.semanticMemoryState().toSemanticMemoryStatus()
        val backgroundTasks = backgroundTaskController.loadBackgroundTaskSnapshot()
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
            longTermMemories = memoryController.loadLongTermMemories(),
            backgroundTasks = backgroundTasks.activeTasks,
            backgroundTaskHistory = backgroundTasks.history,
            periodicCheckPolicy = backgroundTasks.periodicCheckPolicy,
            auditEvents = auditUiController.loadAuditEvents(),
            remoteSendAuditEvents = remoteSendAuditLog.recentRemoteSendSummaries(),
            agentTraceRuns = auditUiController.loadAgentTraceRuns(),
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
            }
        )
    }

    private fun hasStartupModelEndpoint(
        modelState: ModelSelectionState,
        remoteConfig: RemoteModelConfig
    ): Boolean = modelState.activeModelPath != null || remoteConfig.isConfigured
}

/**
 * One-shot startup restore after the initial state is in place: fail-closed pending remote
 * sends, recover background tasks, verify models, fail stale agent runs, resume a pending
 * download or auto-load the local model, then restore pending confirmations / external outcomes.
 */
internal class StartupStateRestorer(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val modelRepository: ModelRepositoryFacade,
    private val assistantOrchestrator: AssistantRouter,
    private val chatController: ChatController,
    private val toolExecutionController: ToolExecutionController,
    private val modelLoadController: ModelLoadController,
    private val backgroundTaskController: BackgroundTaskController,
    private val memoryUiController: MemoryUiController,
    private val auditUiController: AuditUiController,
    private val isArm64Device: () -> Boolean
) {
    suspend fun restoreStartupStateAfterInitialization(skipModelRuntimeWork: Boolean) {
        chatController.failClosedPendingRemoteSendOnStartup()
        backgroundTaskController.recoverBackgroundTasksOnStartup()
        memoryUiController.syncTaskStateMemories()
        modelLoadController.refreshDeviceStatus()
        uiState.update { it.copy(longTermMemories = memoryUiController.loadLongTermMemories()) }
        modelLoadController.verifyLegacyModelsOnStartup(skipModelRuntimeWork)
        failStaleAgentRunsOnStartup()
        if (modelLoadController.startBundledModelInstallOnStartup(skipModelRuntimeWork)) {
            toolExecutionController.restorePendingAgentConfirmationIfAny()
            toolExecutionController.restorePendingExternalOutcomeIfAny()
            return
        }

        if (skipModelRuntimeWork) {
            if (uiState.value.inferenceMode == InferenceMode.Remote) {
                modelLoadController.updateRemoteReadiness("远程模型")
                // Ensure first-run setup is dismissed when remote is already
                // configured (mirrors updateRemoteModelConfig's behaviour so
                // pre-configured test/production states reach isReady=true).
                if (uiState.value.remoteModelConfig.isConfigured) {
                    uiState.update { it.copy(showFirstRunSetup = false) }
                }
            } else if (uiState.value.modelPath != null) {
                uiState.update {
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
                uiState.update {
                    it.copy(statusText = "下载目录不可用，请导入已有模型")
                }
            } else {
                modelLoadController.monitorDownload(pendingDownloadId, target, source)
            }
        } else if (uiState.value.inferenceMode == InferenceMode.Remote) {
            modelLoadController.updateRemoteReadiness("远程模型")
        } else if (!isArm64Device()) {
            uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
        } else {
            val activePath = modelRepository.currentState().activeModelPath
            if (activePath != null) {
                uiState.update {
                    it.copy(statusText = "已找到模型，正在加载")
                }
                modelLoadController.loadModel()
            }
        }
        toolExecutionController.restorePendingAgentConfirmationIfAny()
        toolExecutionController.restorePendingExternalOutcomeIfAny()
    }

    private fun failStaleAgentRunsOnStartup() {
        val failedCount = assistantOrchestrator.failStaleInFlightRuns(STALE_AGENT_RUN_STARTUP_REASON)
        if (failedCount <= 0) return
        uiState.update {
            it.copy(agentTraceRuns = auditUiController.loadAgentTraceRuns())
        }
    }
}
