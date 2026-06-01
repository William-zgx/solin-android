package com.bytedance.zgx.pocketmind

import android.app.DownloadManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionExecutor
import com.bytedance.zgx.pocketmind.action.MobileActionFunctions
import com.bytedance.zgx.pocketmind.audit.ToolAuditLog
import com.bytedance.zgx.pocketmind.background.BackgroundTaskScheduler
import com.bytedance.zgx.pocketmind.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.pocketmind.background.PeriodicCheckScheduleRequest
import com.bytedance.zgx.pocketmind.background.ScheduledTask
import com.bytedance.zgx.pocketmind.background.ScheduledTaskStatus
import com.bytedance.zgx.pocketmind.data.FirstRunSetupRepository
import com.bytedance.zgx.pocketmind.data.FirstRunSetupStore
import com.bytedance.zgx.pocketmind.data.ModelDownloadSource
import com.bytedance.zgx.pocketmind.data.GenerationParametersStore
import com.bytedance.zgx.pocketmind.data.ModelRepositoryFacade
import com.bytedance.zgx.pocketmind.data.ModelRepository
import com.bytedance.zgx.pocketmind.data.ModelSelectionState
import com.bytedance.zgx.pocketmind.data.ModelVerificationStatus
import com.bytedance.zgx.pocketmind.data.RemoteModelStore
import com.bytedance.zgx.pocketmind.data.RemoteModelRepository
import com.bytedance.zgx.pocketmind.data.SessionStore
import com.bytedance.zgx.pocketmind.device.DeviceContextSnapshot
import com.bytedance.zgx.pocketmind.download.ModelDownloadClient
import com.bytedance.zgx.pocketmind.download.ModelDownloadService
import com.bytedance.zgx.pocketmind.memory.LongTermMemoryControls
import com.bytedance.zgx.pocketmind.memory.MemoryIndex
import com.bytedance.zgx.pocketmind.memory.MemoryRecordType
import com.bytedance.zgx.pocketmind.memory.TASK_STATE_MEMORY_RECORD_PREFIX
import com.bytedance.zgx.pocketmind.memory.SemanticMemoryRuntimeController
import com.bytedance.zgx.pocketmind.memory.explicitUserPreferenceFrom
import com.bytedance.zgx.pocketmind.memory.explicitUserPreferenceRecordId
import com.bytedance.zgx.pocketmind.memory.taskStateMemoryRecordId
import com.bytedance.zgx.pocketmind.multimodal.SharedInput
import com.bytedance.zgx.pocketmind.orchestration.AgentObservationDecision
import com.bytedance.zgx.pocketmind.orchestration.AgentObservationResult
import com.bytedance.zgx.pocketmind.orchestration.AgentRecoveryAction
import com.bytedance.zgx.pocketmind.orchestration.AgentRunState
import com.bytedance.zgx.pocketmind.orchestration.AgentTraceRunSummary
import com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestrator
import com.bytedance.zgx.pocketmind.orchestration.AssistantRouter
import com.bytedance.zgx.pocketmind.orchestration.AssistantRoute
import com.bytedance.zgx.pocketmind.runtime.LiteRtRuntime
import com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntime
import com.bytedance.zgx.pocketmind.tool.ToolExecutor
import com.bytedance.zgx.pocketmind.tool.ToolErrorCode
import com.bytedance.zgx.pocketmind.tool.ToolRequest
import com.bytedance.zgx.pocketmind.tool.failed
import com.bytedance.zgx.pocketmind.tool.isUnverifiedExternalLaunch
import com.bytedance.zgx.pocketmind.tool.unverifiedExternalLaunchSummary
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

private const val MAX_VOICE_TRANSCRIPT_CHARS = 2_000
private const val STALE_AGENT_RUN_STARTUP_REASON =
    "App restarted before this Agent step completed."

class PocketMindViewModel(
    private val modelRepository: ModelRepositoryFacade,
    private val sessionRepository: SessionStore,
    private val generationParametersRepository: GenerationParametersStore,
    private val remoteModelRepository: RemoteModelStore,
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
) : ViewModel() {
    private val runtimeLock = Mutex()
    private var generationJob: Job? = null
    private var downloadMonitorJob: Job? = null
    private var activeDownloadId: Long? = null
    private val setupDownloadQueue = ArrayDeque<ModelDownloadSource>()
    private var setupDownloadInProgress = false
    private var startupRestored = false
    private var nextVoiceInputDraftId = 0L

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun restoreStartupState(skipModelRuntimeWork: Boolean = false) {
        if (startupRestored) return
        startupRestored = true

        recoverBackgroundTasksOnStartup()
        syncTaskStateMemories()
        refreshDeviceStatus()
        rebuildMemoryIndex()
        _uiState.update { it.copy(longTermMemories = loadLongTermMemories()) }
        verifyLegacyModelsOnStartup(skipModelRuntimeWork)
        failStaleAgentRunsOnStartup()

        if (skipModelRuntimeWork) {
            if (_uiState.value.inferenceMode == InferenceMode.Remote) {
                updateRemoteReadiness("远程模型")
            } else if (_uiState.value.modelPath != null) {
                _uiState.update {
                    it.copy(statusText = "已找到模型，点击加载模型")
                }
            }
            restorePendingAgentConfirmationIfAny()
            return
        }

        val pendingDownloadId = modelRepository.pendingDownloadId()
        val pendingDownloadSource = modelRepository.loadPendingDownloadSource()
        if (pendingDownloadId > 0L) {
            val source = pendingDownloadSource ?: ModelDownloadSource.recommended(modelRepository.selectedRecommendedModel())
            source.modelId?.let {
                updateModelState(modelRepository.selectRecommendedModel(it).state)
            }
            val target = modelRepository.downloadedModelFile(source.fileName)
            if (target == null) {
                modelRepository.clearPendingDownload()
                _uiState.update {
                    it.copy(statusText = "下载目录不可用，请导入已有模型")
                }
            } else {
                monitorDownload(pendingDownloadId, target, source)
            }
        } else if (_uiState.value.inferenceMode == InferenceMode.Remote) {
            updateRemoteReadiness("远程模型")
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
        restorePendingAgentConfirmationIfAny()
    }

    private fun recoverBackgroundTasksOnStartup() {
        backgroundTaskScheduler.rescheduleScheduledReminders()
        backgroundTaskScheduler.reconcilePeriodicCheckOnStartup()
        _uiState.update {
            it.copy(
                backgroundTasks = loadBackgroundTasks(),
                backgroundTaskHistory = loadBackgroundTaskHistory(),
                periodicCheckPolicy = loadPeriodicCheckPolicy(),
            )
        }
    }

    fun startModelDownload() {
        beginModelDownload(ModelDownloadSource.recommended(modelRepository.selectedRecommendedModel()))
    }

    fun startRecommendedModelDownload(modelId: String) {
        val model = ModelCatalog.recommendedModelById(modelId)
        if (model.capability == ModelCapability.Chat) {
            val result = modelRepository.selectRecommendedModel(model.id)
            updateModelState(result.state)
        }
        beginModelDownload(ModelDownloadSource.recommended(model))
    }

    fun toggleSetupModel(modelId: String, selected: Boolean) {
        if (_uiState.value.isBusy) return
        _uiState.update { state ->
            val next = if (selected) {
                state.setupSelectedModelIds + modelId
            } else {
                state.setupSelectedModelIds - modelId
            }
            state.copy(setupSelectedModelIds = next)
        }
    }

    fun startSetupModelDownload() {
        if (_uiState.value.isBusy || _uiState.value.isDownloading) return
        firstRunSetupRepository.markSetupDismissed()
        val selectedModels = _uiState.value.basicSetupModels
            .filter { it.id in _uiState.value.setupSelectedModelIds && !_uiState.value.isModelInstalled(it.id) }
        if (selectedModels.isEmpty()) {
            _uiState.update {
                it.copy(
                    showFirstRunSetup = false,
                    statusText = if (it.modelPath == null) "可稍后在模型管理安装模型" else it.statusText,
                )
            }
            return
        }
        setupDownloadQueue.clear()
        selectedModels.drop(1).forEach { setupDownloadQueue.add(ModelDownloadSource.recommended(it)) }
        setupDownloadInProgress = true
        _uiState.update { it.copy(showFirstRunSetup = false) }
        beginModelDownload(ModelDownloadSource.recommended(selectedModels.first()))
    }

    fun skipFirstRunSetup() {
        if (_uiState.value.isBusy) return
        firstRunSetupRepository.markSetupDismissed()
        _uiState.update {
            it.copy(
                showFirstRunSetup = false,
                statusText = if (it.modelPath == null) "已跳过模型准备，可稍后在模型管理安装" else it.statusText,
            )
        }
    }

    fun updateMemoryEnabled(enabled: Boolean) {
        firstRunSetupRepository.setMemoryEnabled(enabled)
        memoryRepository.enabled = enabled
        _uiState.update {
            it.copy(
                memoryEnabled = enabled,
                memoryHits = if (enabled) it.memoryHits else emptyList(),
                statusText = if (enabled) "本地记忆已开启" else "本地记忆已关闭",
            )
        }
    }

    fun forgetLongTermMemory(memoryId: String) {
        if (_uiState.value.isBusy) return
        val removed = longTermMemoryControls.forget(memoryId)
        if (memoryId.startsWith(TASK_STATE_MEMORY_RECORD_PREFIX)) {
            longTermMemoryControls.suppressAutoManagedTaskState(memoryId)
        }
        rebuildMemoryIndex()
        val records = loadLongTermMemories()
        _uiState.update {
            it.copy(
                longTermMemories = records,
                memoryHits = it.memoryHits.filterNot { hit -> hit.id == memoryId },
                statusText = if (removed) "已遗忘这条记忆" else "未找到这条记忆",
            )
        }
    }

    fun clearLongTermMemory() {
        if (_uiState.value.isBusy) return
        val activeTaskMemoryIds = activeTaskStateMemoryIds()
        longTermMemoryControls.clear()
        activeTaskMemoryIds.forEach { memoryId ->
            longTermMemoryControls.suppressAutoManagedTaskState(memoryId)
        }
        rebuildMemoryIndex()
        _uiState.update {
            it.copy(
                longTermMemories = emptyList(),
                memoryHits = emptyList(),
                statusText = "长期记忆已清空",
            )
        }
    }

    fun refreshBackgroundTasks() {
        syncTaskStateMemories()
        _uiState.update {
            it.copy(
                backgroundTasks = loadBackgroundTasks(),
                backgroundTaskHistory = loadBackgroundTaskHistory(),
                periodicCheckPolicy = loadPeriodicCheckPolicy(),
                longTermMemories = loadLongTermMemories(),
            )
        }
    }

    fun cancelBackgroundTask(taskId: String) {
        if (_uiState.value.isBusy) return
        backgroundTaskScheduler.cancelScheduledTask(taskId)
            .fold(
                onSuccess = {
                    syncTaskStateMemories()
                    _uiState.update { state ->
                        state.copy(
                            backgroundTasks = loadBackgroundTasks(),
                            backgroundTaskHistory = loadBackgroundTaskHistory(),
                            periodicCheckPolicy = loadPeriodicCheckPolicy(),
                            longTermMemories = loadLongTermMemories(),
                            statusText = "后台任务已取消",
                        )
                    }
                },
                onFailure = { throwable ->
                    syncTaskStateMemories()
                    _uiState.update { state ->
                        state.copy(
                            backgroundTasks = loadBackgroundTasks(),
                            backgroundTaskHistory = loadBackgroundTaskHistory(),
                            periodicCheckPolicy = loadPeriodicCheckPolicy(),
                            longTermMemories = loadLongTermMemories(),
                            statusText = "后台任务取消失败：${throwable.cleanMessage()}",
                        )
                    }
                },
            )
    }

    fun setPeriodicCheckPolicy(request: PeriodicCheckScheduleRequest) {
        if (_uiState.value.isBusy) return
        backgroundTaskScheduler.setPeriodicCheckPolicy(request)
            .fold(
                onSuccess = { policy ->
                    syncTaskStateMemories()
                    _uiState.update { state ->
                        state.copy(
                            backgroundTasks = loadBackgroundTasks(),
                            backgroundTaskHistory = loadBackgroundTaskHistory(),
                            periodicCheckPolicy = policy,
                            longTermMemories = loadLongTermMemories(),
                            statusText = "周期检查策略已保存",
                        )
                    }
                },
                onFailure = { throwable ->
                    syncTaskStateMemories()
                    _uiState.update {
                        it.copy(
                            backgroundTasks = loadBackgroundTasks(),
                            backgroundTaskHistory = loadBackgroundTaskHistory(),
                            periodicCheckPolicy = loadPeriodicCheckPolicy(),
                            longTermMemories = loadLongTermMemories(),
                            statusText = "周期检查策略保存失败：${throwable.cleanMessage()}",
                        )
                    }
                },
            )
    }

    fun disablePeriodicCheckPolicy() {
        if (_uiState.value.isBusy) return
        backgroundTaskScheduler.disablePeriodicCheckPolicy()
            .fold(
                onSuccess = { policy ->
                    syncTaskStateMemories()
                    _uiState.update { state ->
                        state.copy(
                            backgroundTasks = loadBackgroundTasks(),
                            backgroundTaskHistory = loadBackgroundTaskHistory(),
                            periodicCheckPolicy = policy,
                            longTermMemories = loadLongTermMemories(),
                            statusText = "周期检查已关闭",
                        )
                    }
                },
                onFailure = { throwable ->
                    syncTaskStateMemories()
                    _uiState.update {
                        it.copy(
                            backgroundTasks = loadBackgroundTasks(),
                            backgroundTaskHistory = loadBackgroundTaskHistory(),
                            periodicCheckPolicy = loadPeriodicCheckPolicy(),
                            longTermMemories = loadLongTermMemories(),
                            statusText = "周期检查关闭失败：${throwable.cleanMessage()}",
                        )
                    }
                },
            )
    }

    fun refreshAuditEvents() {
        _uiState.update {
            it.copy(
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
            )
        }
    }

    fun acceptVoiceTranscript(transcript: String) {
        val cleaned = transcript
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_VOICE_TRANSCRIPT_CHARS)
        if (cleaned.isBlank()) return
        _uiState.update {
            it.copy(
                voiceInputDraft = VoiceInputDraft(
                    id = ++nextVoiceInputDraftId,
                    text = cleaned,
                ),
                statusText = "语音已转写",
            )
        }
    }

    fun consumeVoiceInputDraft(draftId: Long) {
        _uiState.update {
            if (it.voiceInputDraft?.id == draftId) {
                it.copy(voiceInputDraft = null)
            } else {
                it
            }
        }
    }

    fun reportVoiceInputUnavailable(message: String = "语音输入不可用") {
        _uiState.update {
            it.copy(statusText = message)
        }
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
        val source = modelRepository.createCustomDownloadSource(downloadUrl)
        if (source == null) {
            _uiState.update {
                it.copy(statusText = "请输入有效的 http/https 模型下载链接")
            }
            return
        }
        beginModelDownload(source)
    }

    fun cancelModelDownload() {
        val downloadId = activeDownloadId ?: modelRepository.pendingDownloadId()
        downloadMonitorJob?.cancel()
        downloadMonitorJob = null
        activeDownloadId = null
        setupDownloadQueue.clear()
        setupDownloadInProgress = false
        downloadService.cancel(downloadId)
        modelRepository.clearPendingDownload()
        _uiState.update {
            it.copy(
                isBusy = false,
                isDownloading = false,
                downloadProgressPercent = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusText = "下载已取消",
            )
        }
    }

    fun importModel(uri: Uri) {
        refreshDeviceStatus()
        if (_uiState.value.isBusy) return
        if (!isArm64Device()) {
            _uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
            return
        }

        _uiState.update {
            it.copy(
                isBusy = true,
                isReady = false,
                statusText = "正在导入模型",
            )
        }

        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) {
                    runtimeLock.withLock {
                        runtime.close()
                        modelRepository.importModel(uri) { progress ->
                            _uiState.update {
                                it.copy(
                                    downloadProgressPercent = progress.percent,
                                    downloadedBytes = progress.transferredBytes,
                                    totalBytes = progress.totalBytes,
                                    statusText = "正在导入模型",
                                )
                            }
                        }
                    }
                }
            }

            result.fold(
                onSuccess = { path ->
                    updateModelState(modelRepository.currentState())
                    _uiState.update {
                        it.copy(
                            modelPath = path,
                            isBusy = false,
                            isDownloading = false,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "模型已导入",
                        )
                    }
                    loadModel()
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isDownloading = false,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "导入失败：${throwable.cleanMessage()}",
                        )
                    }
                },
            )
        }
    }

    fun selectBackend(choice: BackendChoice) {
        if (_uiState.value.isBusy || _uiState.value.backend == choice) return
        generationParametersRepository.saveBackend(choice)
        _uiState.update {
            it.copy(
                backend = choice,
                isReady = false,
                statusText = "已切换到 ${choice.label()}，点击加载模型",
            )
        }
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
            recreateConversationForActiveSession("参数已生效")
        }
    }

    fun resetGenerationParameters() {
        updateGenerationParameters(GenerationParameters())
    }

    fun selectInferenceMode(mode: InferenceMode) {
        if (_uiState.value.isBusy || _uiState.value.inferenceMode == mode) return
        remoteModelRepository.saveMode(mode)
        if (mode == InferenceMode.Remote) {
            runtime.close()
            updateRemoteReadiness("已切换到远程模型")
            return
        }

        _uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                isReady = runtime.isLoaded,
                statusText = if (runtime.isLoaded) {
                    "就绪 · ${it.backend.label()}"
                } else if (it.modelPath != null) {
                    "已切换到本地模型，点击加载模型"
                } else {
                    "请先下载或导入本地模型"
                },
            )
        }
    }

    fun updateRemoteModelConfig(config: RemoteModelConfig) {
        if (_uiState.value.isBusy) return
        remoteModelRepository.saveConfig(config)
            .fold(
                onSuccess = { normalized ->
                    _uiState.update {
                        it.copy(remoteModelConfig = normalized)
                    }
                    if (_uiState.value.inferenceMode == InferenceMode.Remote) {
                        updateRemoteReadiness("远程模型")
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(statusText = "API Key 加密保存失败：${throwable.cleanMessage()}")
                    }
                },
            )
    }

    fun selectRecommendedModel(modelId: String) {
        if (_uiState.value.isBusy || _uiState.value.selectedModelId == modelId) return
        val result = modelRepository.selectRecommendedModel(modelId)
        updateModelState(result.state)
        val activated = result.activatedModel
        _uiState.update {
            it.copy(
                isReady = if (activated == null) it.isReady else false,
                statusText = activated
                    ?.let { model -> "已切换到 ${model.displayName}，点击加载模型" }
                    ?: "已选择 ${modelRepository.selectedRecommendedModel().shortName}",
            )
        }
        refreshDeviceStatus()
    }

    fun selectInstalledModel(modelId: String) {
        if (_uiState.value.isBusy || _uiState.value.activeInstalledModelId == modelId) return
        val installed = modelRepository.selectInstalledModel(modelId) ?: return
        remoteModelRepository.saveMode(InferenceMode.Local)
        updateModelState(modelRepository.currentState())
        _uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                isReady = false,
                statusText = "已切换到 ${installed.displayName}，点击加载模型",
            )
        }
    }

    fun loadModel() {
        val path = _uiState.value.modelPath ?: return
        if (_uiState.value.isBusy) return
        val backendChoice = _uiState.value.backend
        remoteModelRepository.saveMode(InferenceMode.Local)

        _uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                isBusy = true,
                isDownloading = false,
                isReady = false,
                downloadProgressPercent = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusText = "正在初始化 ${backendChoice.label()}",
            )
        }

        viewModelScope.launch(ioDispatcher) {
            val result = runCatching {
                runtimeLock.withLock {
                    runtime.load(
                        modelPath = path,
                        backend = backendChoice,
                        history = sessionRepository.activeMessages(),
                        parameters = _uiState.value.generationParameters,
                    )
                }
            }

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isDownloading = false,
                            isReady = true,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "就绪 · ${it.backend.label()}",
                        )
                    }
                },
                onFailure = { throwable ->
                    if (backendChoice == BackendChoice.GPU) {
                        val cpuResult = runCatching {
                            runtimeLock.withLock {
                                runtime.load(
                                    modelPath = path,
                                    backend = BackendChoice.CPU,
                                    history = sessionRepository.activeMessages(),
                                    parameters = _uiState.value.generationParameters,
                                )
                            }
                        }
                        if (cpuResult.isSuccess) {
                            _uiState.update {
                                it.copy(
                                    backend = BackendChoice.CPU,
                                    isBusy = false,
                                    isDownloading = false,
                                    isReady = true,
                                    downloadProgressPercent = null,
                                    downloadedBytes = 0L,
                                    totalBytes = 0L,
                                    statusText = "GPU 不可用，已切到 CPU",
                                )
                            }
                            return@launch
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isDownloading = false,
                            isReady = false,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "初始化失败：${throwable.cleanMessage()}",
                        )
                    }
                },
            )
        }
    }

    fun resetConversation() {
        createNewSession()
    }

    fun createNewSession() {
        if (_uiState.value.isBusy) return
        sessionRepository.createNewSession()
        _uiState.update {
            it.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = sessionRepository.activeSessionId,
                messages = emptyList(),
                latestRecoveryAction = null,
                statusText = if (!runtime.isLoaded) "新会话" else "正在开启新会话",
            )
        }
        if (runtime.isLoaded) {
            recreateConversationForActiveSession("新会话")
        }
    }

    fun selectSession(sessionId: String) {
        if (_uiState.value.isBusy || _uiState.value.activeSessionId == sessionId) return
        val messages = sessionRepository.selectSession(sessionId) ?: return
        _uiState.update {
            it.copy(
                activeSessionId = sessionRepository.activeSessionId,
                messages = messages,
                latestRecoveryAction = null,
                statusText = if (!runtime.isLoaded) "已切换会话" else "正在恢复会话",
            )
        }
        if (runtime.isLoaded) {
            recreateConversationForActiveSession("已恢复会话")
        }
    }

    fun deleteActiveSession() {
        if (_uiState.value.isBusy) return
        val messages = sessionRepository.deleteActiveSession() ?: return
        _uiState.update {
            it.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = sessionRepository.activeSessionId,
                messages = messages,
                latestRecoveryAction = null,
                statusText = if (!runtime.isLoaded) "已删除会话" else "正在恢复会话",
            )
        }
        if (runtime.isLoaded) {
            recreateConversationForActiveSession("已删除会话")
        }
    }

    fun sendMessage(
        prompt: String,
        messagePrivacy: MessagePrivacy = MessagePrivacy.RemoteEligible,
    ) {
        val trimmed = prompt.trim()
        if (trimmed.isNotEmpty() && _uiState.value.pendingConfirmation != null) {
            _uiState.update {
                it.copy(statusText = "请先确认或取消待执行动作")
            }
            return
        }
        if (trimmed.isEmpty() || _uiState.value.isBusy || generationJob?.isActive == true) {
            return
        }
        if (explicitUserPreferenceFrom(trimmed) != null) {
            handleExplicitPreferenceCommand(trimmed)
            return
        }
        if (!_uiState.value.isReady) return

        syncTaskStateMemories()
        rebuildMemoryIndex()
        _uiState.update { it.copy(longTermMemories = loadLongTermMemories()) }
        val stateBeforeSend = _uiState.value
        val useRemoteModel = stateBeforeSend.inferenceMode == InferenceMode.Remote
        val remoteConfig = stateBeforeSend.remoteModelConfig
        val remoteHistory = stateBeforeSend.messages.remoteEligibleMessages()
        val includePrivateLocalContext = !useRemoteModel
        if (useRemoteModel && messagePrivacy == MessagePrivacy.LocalOnly) {
            val userMessage = ChatMessage(
                role = MessageRole.User,
                text = trimmed,
                privacy = MessagePrivacy.LocalOnly,
            )
            replaceActiveSessionMessages(
                stateBeforeSend.messages + userMessage + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "这条内容已标记为仅本地使用。当前为远程模型模式，我不会把它发送到远程模型。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                persistNow = true,
            )
            persistExplicitPreferenceMemory(userMessage)
            rebuildMemoryIndex()
            _uiState.update {
                it.copy(statusText = "已保护本地内容")
            }
            return
        }
        _uiState.update {
            it.copy(
                isBusy = true,
                isGenerating = false,
                latestRecoveryAction = null,
                statusText = "处理中",
            )
        }

        val job = viewModelScope.launch(ioDispatcher) {
            try {
                val userMessage = ChatMessage(
                    role = MessageRole.User,
                    text = trimmed,
                    privacy = messagePrivacy,
                )
                val route = assistantOrchestrator.route(
                    input = trimmed,
                    installedCapabilities = stateBeforeSend.installedCapabilities,
                    memoryEnabled = stateBeforeSend.memoryEnabled && includePrivateLocalContext,
                    actionModelPath = modelRepository.verifiedActionModelPath(),
                    deviceContext = stateBeforeSend.toDeviceContextSnapshot().takeIf { includePrivateLocalContext },
                )
                when (route) {
                    is AssistantRoute.Action -> {
                        val localUserMessage = userMessage.copy(privacy = MessagePrivacy.LocalOnly)
                        val planningLabel = if (route.plannedByModel) {
                            "动作模型实验"
                        } else {
                            "规则回退"
                        }
                        val assistantMessage = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "已准备本地动作草稿（$planningLabel）：${route.draft.summary}\n请确认后再执行。",
                            privacy = MessagePrivacy.LocalOnly,
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + localUserMessage + assistantMessage,
                            persistNow = true,
                        )
                        persistExplicitPreferenceMemory(localUserMessage)
                        _uiState.update {
                            it.copy(
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
                                memoryHits = emptyList(),
                                statusText = "动作草稿待确认 · $planningLabel",
                            )
                        }
                        rebuildMemoryIndex()
                        return@launch
                    }

                    is AssistantRoute.ToolRejected -> {
                        val localUserMessage = userMessage.copy(privacy = MessagePrivacy.LocalOnly)
                        val assistantMessage = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "无法准备这个动作：${route.summary}",
                            privacy = MessagePrivacy.LocalOnly,
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + localUserMessage + assistantMessage,
                            persistNow = true,
                        )
                        persistExplicitPreferenceMemory(localUserMessage)
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isGenerating = false,
                                memoryHits = emptyList(),
                                statusText = "动作不可执行",
                            )
                        }
                        rebuildMemoryIndex()
                        return@launch
                    }

                    is AssistantRoute.MissingModel -> {
                        val capabilityName = when (route.capability) {
                            ModelCapability.Chat -> "对话模型"
                            ModelCapability.MemoryEmbedding -> "记忆模型"
                            ModelCapability.MobileAction -> "动作模型"
                        }
                        val assistantMessage = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "需要先安装$capabilityName，才能完成这个请求。请到模型管理安装基础能力包。",
                            privacy = messagePrivacy,
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + userMessage + assistantMessage,
                            persistNow = true,
                        )
                        persistExplicitPreferenceMemory(userMessage)
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isGenerating = false,
                                memoryHits = emptyList(),
                                statusText = "缺少$capabilityName",
                            )
                        }
                        rebuildMemoryIndex()
                        return@launch
                    }

                    is AssistantRoute.Chat -> {
                        val responsePrivacy = if (
                            !useRemoteModel &&
                            (messagePrivacy == MessagePrivacy.LocalOnly || route.memoryHits.isNotEmpty())
                        ) {
                            MessagePrivacy.LocalOnly
                        } else {
                            messagePrivacy
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
                        _uiState.update {
                            it.copy(
                                isGenerating = true,
                                memoryHits = route.memoryHits,
                                statusText = "生成中",
                            )
                        }
                        if (!useRemoteModel && !runtime.isLoaded) {
                            _uiState.updateLastAssistant("模型尚未就绪")
                            persistActiveSessionFromUi()
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isGenerating = false,
                                    isReady = false,
                                    statusText = "未加载模型",
                                )
                            }
                            return@launch
                        }
                        if (useRemoteModel && !remoteConfig.isConfigured) {
                            _uiState.updateLastAssistant("请先在模型管理中配置远程模型地址和模型名")
                            persistActiveSessionFromUi()
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isGenerating = false,
                                    isReady = false,
                                    statusText = "请配置远程模型",
                                )
                            }
                            return@launch
                        }

                        val partial = StringBuilder()
                        val response = if (useRemoteModel) {
                            remoteRuntime.send(
                                prompt = route.promptForModel,
                                history = remoteHistory,
                                parameters = _uiState.value.generationParameters,
                                config = remoteConfig,
                            )
                        } else {
                            runtime.send(route.promptForModel)
                        }
                        response.collect { chunk ->
                            partial.append(chunk)
                            _uiState.updateLastAssistant(partial.toString())
                        }
                        if (partial.isBlank()) {
                            _uiState.updateLastAssistant("没有生成内容")
                        } else if (!useRemoteModel) {
                            _uiState.updateLastAssistantStats(
                                runCatching { runtime.lastGenerationStats() }.getOrNull(),
                            )
                        }
                        persistActiveSessionFromUi()
                        rebuildMemoryIndex()
                        route.runId?.let { runId ->
                            assistantOrchestrator.observeModelResult(runId, partial.toString())
                        }
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isGenerating = false,
                                isReady = true,
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
                if (_uiState.value.isGenerating) {
                    finishStoppedGeneration()
                } else if (_uiState.value.isBusy) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            statusText = "已停止",
                        )
                    }
                }
                throw cancellation
            } catch (throwable: Throwable) {
                if (_uiState.value.isGenerating) {
                    _uiState.updateLastAssistant("出错了：${throwable.cleanMessage()}")
                    persistActiveSessionFromUi()
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = useRemoteModel && remoteConfig.isConfigured,
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
            }
        }
    }

    fun ingestSharedInput(sharedInput: SharedInput) {
        if (sharedInput.isEmpty) return
        if (_uiState.value.inferenceMode == InferenceMode.Remote) {
            replaceActiveSessionMessages(
                _uiState.value.messages + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "已接收分享内容。当前为远程模型模式，为保护隐私，不会自动发送分享文本、RTF/Office 文档摘录、OCR 摘录或附件元数据。请手动粘贴你愿意发送的内容。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                persistNow = true,
            )
            _uiState.update {
                it.copy(statusText = "已保护分享内容")
            }
            return
        }
        val prompt = sharedInput.toPrompt()
        if (prompt.isBlank()) return
        if (_uiState.value.isReady && !_uiState.value.isBusy && generationJob?.isActive != true) {
            sendMessage(prompt, messagePrivacy = MessagePrivacy.LocalOnly)
            return
        }

        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.User,
                text = prompt,
                privacy = MessagePrivacy.LocalOnly,
            ) + ChatMessage(
                role = MessageRole.Assistant,
                text = "已接收分享内容。请先准备模型后再发送，当前只会读取受限文本、RTF/Office 文档摘录、OCR 摘录和附件元数据。",
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        rebuildMemoryIndex()
        _uiState.update {
            it.copy(statusText = "已接收分享内容")
        }
    }

    fun stopGeneration() {
        val job = generationJob ?: return
        if (_uiState.value.inferenceMode == InferenceMode.Local) {
            runtime.stop()
        } else {
            remoteRuntime.stop()
        }
        job.cancel()
        finishStoppedGeneration()
    }

    fun requestRecoveryActionConfirmation(action: AgentRecoveryAction) {
        val state = _uiState.value
        if (state.pendingConfirmation != null) {
            _uiState.update {
                it.copy(statusText = "请先确认或取消待执行动作")
            }
            return
        }
        if (state.isBusy || generationJob?.isActive == true) {
            _uiState.update {
                it.copy(statusText = "请稍后再撤销提醒")
            }
            return
        }
        if (state.latestRecoveryAction != action) {
            _uiState.update {
                it.copy(statusText = "撤销入口已过期")
            }
            return
        }
        when (val route = assistantOrchestrator.requestRecoveryAction(action)) {
            is AssistantRoute.Action -> {
                val runId = route.runId
                val request = route.toolRequest
                if (runId == null || request == null) {
                    _uiState.update {
                        it.copy(
                            latestRecoveryAction = null,
                            statusText = "撤销动作不可执行",
                        )
                    }
                    return
                }
                _uiState.update {
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
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        statusText = "撤销提醒待确认",
                    )
                }
            }

            is AssistantRoute.ToolRejected -> {
                _uiState.update {
                    it.copy(
                        latestRecoveryAction = null,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        statusText = "撤销动作不可执行：${route.summary}",
                    )
                }
            }

            else -> {
                _uiState.update {
                    it.copy(statusText = "撤销动作不可执行")
                }
            }
        }
    }

    fun confirmAgentConfirmation(confirmation: PendingAgentConfirmation) {
        val pendingConfirmation = _uiState.value.pendingConfirmation
        if (pendingConfirmation == null || !pendingConfirmation.matchesExecution(confirmation)) {
            _uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = confirmation.toolRequest ?: ToolRequest(
            toolName = confirmation.draft.functionName,
            arguments = confirmation.draft.parameters,
            reason = confirmation.draft.summary,
        )
        val confirmedRun = try {
            confirmation.runId?.let { runId ->
                assistantOrchestrator.confirmToolRequest(runId, request.id)
            }
        } catch (_: Exception) {
            _uiState.update {
                it.copy(
                    pendingConfirmation = pendingConfirmation,
                    isBusy = false,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    statusText = "工具确认失败，未执行动作。",
                )
            }
            return
        }
        if (confirmation.runId != null && confirmedRun?.state != AgentRunState.ExecutingTool) {
            replaceActiveSessionMessages(
                _uiState.value.messages + ChatMessage(
                    MessageRole.Assistant,
                    "工具确认失败，未执行动作。",
                ),
                persistNow = true,
            )
            _uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    isBusy = false,
                    isGenerating = false,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    statusText = "工具未执行",
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                isBusy = true,
                isGenerating = false,
                statusText = "工具执行中",
            )
        }
        var result = actionExecutor.execute(request)
        var observation = confirmation.runId?.let { runId ->
            assistantOrchestrator.observeToolResult(runId, result)
        }
        var assistantText = observation?.assistantMessage ?: result.statusSummaryForUi()
        var observationPrivacy = observation.privacyForObservation()
        var messagesWithObservation = _uiState.value.messages + ChatMessage(
            role = MessageRole.Assistant,
            text = assistantText,
            privacy = observationPrivacy,
        )
        replaceActiveSessionMessages(
            messagesWithObservation,
            persistNow = true,
        )
        var retryRequest = observation?.retryRequest
        while (retryRequest != null) {
            _uiState.update {
                it.copy(statusText = "工具重试中")
            }
            result = actionExecutor.execute(retryRequest)
            observation = confirmation.runId?.let { runId ->
                assistantOrchestrator.observeToolResult(runId, result)
            }
            assistantText = observation?.assistantMessage ?: result.statusSummaryForUi()
            observationPrivacy = observation.privacyForObservation()
            messagesWithObservation = _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = assistantText,
                privacy = observationPrivacy,
            )
            replaceActiveSessionMessages(
                messagesWithObservation,
                persistNow = true,
            )
            retryRequest = observation?.retryRequest
        }
        val nextToolPlan = (observation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
        if (nextToolPlan != null) {
            rebuildMemoryIndex()
            _uiState.update {
                it.copy(
                    pendingConfirmation = PendingAgentConfirmation(
                        runId = observation.run.id,
                        draft = nextToolPlan.draft,
                        toolRequest = nextToolPlan.request,
                        skillId = nextToolPlan.skillRequest?.skillId,
                        plannedByModel = nextToolPlan.plannedByModel,
                        fallbackReason = nextToolPlan.fallbackReason,
                    ),
                    latestRecoveryAction = null,
                    isBusy = false,
                    isGenerating = false,
                    statusText = "下一步动作待确认",
                )
            }
            return
        }
        observation?.continuationPromptForModel?.let { continuationPrompt ->
            if (observation.continuationRequiresLocalModel &&
                _uiState.value.inferenceMode == InferenceMode.Remote
            ) {
                val protectedContentName = request.protectedContinuationContentName()
                replaceActiveSessionMessages(
                    messagesWithObservation + ChatMessage(
                        role = MessageRole.Assistant,
                        text = "已读取${protectedContentName}。当前为远程模型模式，为保护隐私，我不会自动发送${protectedContentName}到远程模型。请切换到本地模型后重试，或手动粘贴你愿意发送的内容。",
                        privacy = MessagePrivacy.LocalOnly,
                    ),
                    persistNow = true,
                )
                rebuildMemoryIndex()
                _uiState.update {
                    it.copy(
                        pendingConfirmation = null,
                        isBusy = false,
                        isGenerating = false,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
                        statusText = "已保护${protectedContentName}",
                    )
                }
                return
            }
            replaceActiveSessionMessages(
                messagesWithObservation + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "",
                    privacy = observationPrivacy,
                ),
                persistNow = true,
            )
            _uiState.update {
                it.copy(
                    pendingConfirmation = null,
                    isBusy = true,
                    isGenerating = true,
                    auditEvents = loadAuditEvents(),
                    agentTraceRuns = loadAgentTraceRuns(),
                    statusText = "生成中",
                )
            }
            continueAfterToolObservation(
                runId = observation.run.id,
                promptForModel = continuationPrompt,
                responsePrivacy = observationPrivacy,
            )
            return
        }
        syncTaskStateMemories()
        rebuildMemoryIndex()
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                backgroundTasks = loadBackgroundTasks(),
                backgroundTaskHistory = loadBackgroundTaskHistory(),
                periodicCheckPolicy = loadPeriodicCheckPolicy(),
                longTermMemories = loadLongTermMemories(),
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                latestRecoveryAction = observation?.recoveryAction,
                isBusy = false,
                isGenerating = false,
                statusText = observation?.assistantMessage ?: result.statusSummaryForUi(),
            )
        }
    }

    private fun com.bytedance.zgx.pocketmind.tool.ToolResult.statusSummaryForUi(): String =
        if (isUnverifiedExternalLaunch()) unverifiedExternalLaunchSummary() else summary

    fun rejectAgentConfirmationForRuntimePermissionDenial(
        confirmation: PendingAgentConfirmation,
        deniedPermissions: List<String>,
    ) {
        val pendingConfirmation = _uiState.value.pendingConfirmation
        if (pendingConfirmation == null || !pendingConfirmation.matchesExecution(confirmation)) {
            _uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = confirmation.toolRequest ?: ToolRequest(
            toolName = confirmation.draft.functionName,
            arguments = confirmation.draft.parameters,
            reason = confirmation.draft.summary,
        )
        val deniedSummary = runtimePermissionDenialSummary(deniedPermissions)
        val deniedPermissionNames = deniedPermissions.distinct().joinToString()
        val result = request.failed(
            code = ToolErrorCode.PermissionDenied,
            summary = "用户拒绝了所需权限，工具未执行：$deniedSummary",
            retryable = false,
            data = mapOf(
                "toolName" to request.toolName,
                "deniedPermissions" to deniedPermissionNames,
                "deniedPermissionLabels" to deniedSummary,
            ),
        )
        val observation = confirmation.runId?.let { runId ->
            assistantOrchestrator.failPendingToolRequest(runId, request.id, result)
        }
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = observation?.assistantMessage ?: "工具执行失败：${result.summary}",
            ),
            persistNow = true,
        )
        rebuildMemoryIndex()
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                isBusy = false,
                isGenerating = false,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                statusText = "权限被拒，工具未执行",
            )
        }
    }

    fun rejectAgentConfirmationForSpecialAccessDenial(
        confirmation: PendingAgentConfirmation,
        deniedRequirements: List<SpecialAccessRequirement>,
    ) {
        val pendingConfirmation = _uiState.value.pendingConfirmation
        if (pendingConfirmation == null || !pendingConfirmation.matchesExecution(confirmation)) {
            _uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = confirmation.toolRequest ?: ToolRequest(
            toolName = confirmation.draft.functionName,
            arguments = confirmation.draft.parameters,
            reason = confirmation.draft.summary,
        )
        val deniedSummary = specialAccessDenialSummary(deniedRequirements)
        val result = request.failed(
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
        val observation = confirmation.runId?.let { runId ->
            assistantOrchestrator.failPendingToolRequest(runId, request.id, result)
        }
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = observation?.assistantMessage ?: "工具执行失败：${result.summary}",
            ),
            persistNow = true,
        )
        rebuildMemoryIndex()
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                isBusy = false,
                isGenerating = false,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                statusText = "特殊权限未开启，工具未执行",
            )
        }
    }

    private fun continueAfterToolObservation(
        runId: String?,
        promptForModel: String,
        responsePrivacy: MessagePrivacy,
    ) {
        val stateAtStart = _uiState.value
        val useRemoteModel = stateAtStart.inferenceMode == InferenceMode.Remote
        val remoteConfig = stateAtStart.remoteModelConfig
        val remoteHistory = stateAtStart.messages.dropLast(1).remoteEligibleMessages()
        val job = viewModelScope.launch(ioDispatcher) {
            try {
                if (useRemoteModel && responsePrivacy == MessagePrivacy.LocalOnly) {
                    _uiState.updateLastAssistant("工具结果包含仅本地内容。当前为远程模型模式，我不会把它发送到远程模型。")
                    persistActiveSessionFromUi()
                    rebuildMemoryIndex()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            isReady = remoteConfig.isConfigured,
                            statusText = "已保护工具结果",
                        )
                    }
                    return@launch
                }
                if (!useRemoteModel && !runtime.isLoaded) {
                    _uiState.updateLastAssistant("模型尚未就绪，无法继续处理工具结果")
                    persistActiveSessionFromUi()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            isReady = false,
                            statusText = "未加载模型",
                        )
                    }
                    return@launch
                }
                if (useRemoteModel && !remoteConfig.isConfigured) {
                    _uiState.updateLastAssistant("请先在模型管理中配置远程模型地址和模型名")
                    persistActiveSessionFromUi()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isGenerating = false,
                            isReady = false,
                            statusText = "请配置远程模型",
                        )
                    }
                    return@launch
                }

                val partial = StringBuilder()
                val response = if (useRemoteModel) {
                    remoteRuntime.send(
                        prompt = promptForModel,
                        history = remoteHistory,
                        parameters = _uiState.value.generationParameters,
                        config = remoteConfig,
                    )
                } else {
                    runtime.send(promptForModel)
                }
                response.collect { chunk ->
                    partial.append(chunk)
                    _uiState.updateLastAssistant(partial.toString())
                }
                if (partial.isBlank()) {
                    _uiState.updateLastAssistant("没有生成内容")
                } else if (!useRemoteModel) {
                    _uiState.updateLastAssistantStats(
                        runCatching { runtime.lastGenerationStats() }.getOrNull(),
                    )
                }
                persistActiveSessionFromUi()
                rebuildMemoryIndex()
                val modelObservation = runId?.let { id ->
                    assistantOrchestrator.observeModelResult(id, partial.toString())
                }
                val nextToolPlan = (modelObservation?.decision as? AgentObservationDecision.PlanNextTool)?.plan
                if (nextToolPlan != null) {
                    _uiState.update {
                        it.copy(
                            pendingConfirmation = PendingAgentConfirmation(
                                runId = modelObservation.run.id,
                                draft = nextToolPlan.draft,
                                toolRequest = nextToolPlan.request,
                                skillId = nextToolPlan.skillRequest?.skillId,
                                plannedByModel = nextToolPlan.plannedByModel,
                                fallbackReason = nextToolPlan.fallbackReason,
                            ),
                            isBusy = false,
                            isGenerating = false,
                            isReady = true,
                            auditEvents = loadAuditEvents(),
                            agentTraceRuns = loadAgentTraceRuns(),
                            statusText = "下一步动作待确认",
                        )
                    }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = true,
                        auditEvents = loadAuditEvents(),
                        agentTraceRuns = loadAgentTraceRuns(),
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
                finishStoppedGeneration()
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.updateLastAssistant("出错了：${throwable.cleanMessage()}")
                persistActiveSessionFromUi()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = useRemoteModel && remoteConfig.isConfigured,
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
            }
        }
    }

    fun dismissAgentConfirmation(confirmation: PendingAgentConfirmation? = _uiState.value.pendingConfirmation) {
        val pendingConfirmation = _uiState.value.pendingConfirmation
        if (confirmation == null || pendingConfirmation == null) {
            _uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        if (!pendingConfirmation.matchesExecution(confirmation)) {
            _uiState.update {
                it.copy(statusText = "工具确认已处理")
            }
            return
        }
        val request = confirmation.toolRequest ?: ToolRequest(
            toolName = confirmation.draft.functionName,
            arguments = confirmation.draft.parameters,
            reason = confirmation.draft.summary,
        )
        val observation = if (confirmation.runId != null) {
            assistantOrchestrator.cancelToolRequest(confirmation.runId, request.id)
        } else {
            null
        }
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                auditEvents = loadAuditEvents(),
                agentTraceRuns = loadAgentTraceRuns(),
                statusText = observation?.assistantMessage ?: "已取消动作草稿",
            )
        }
    }

    private fun AgentObservationResult?.privacyForObservation(): MessagePrivacy =
        if (this?.continuationRequiresLocalModel == true) {
            MessagePrivacy.LocalOnly
        } else {
            val declaredPrivacy = this?.result?.data?.get("privacy")
                ?: return MessagePrivacy.RemoteEligible
            runCatching { MessagePrivacy.valueOf(declaredPrivacy) }
                .getOrDefault(MessagePrivacy.LocalOnly)
        }

    private fun ToolRequest.protectedContinuationContentName(): String =
        when (toolName) {
            MobileActionFunctions.READ_CLIPBOARD -> "剪贴板内容"
            MobileActionFunctions.READ_RECENT_SCREENSHOT_OCR -> "截图 OCR 内容"
            MobileActionFunctions.READ_RECENT_IMAGE_OCR -> "图片 OCR 内容"
            MobileActionFunctions.READ_CURRENT_SCREEN_TEXT -> "当前屏幕文本"
            else -> "本地工具结果"
        }

    fun confirmActionDraft(draft: ActionDraft) {
        confirmAgentConfirmation(
            PendingAgentConfirmation(
                runId = null,
                draft = draft,
                toolRequest = null,
                skillId = null,
                plannedByModel = false,
                fallbackReason = null,
            ),
        )
    }

    fun dismissActionDraft() {
        dismissAgentConfirmation()
    }

    override fun onCleared() {
        downloadMonitorJob?.cancel()
        remoteRuntime.stop()
        runtime.close()
        assistantOrchestrator.close()
        super.onCleared()
    }

    private fun beginModelDownload(source: ModelDownloadSource) {
        refreshDeviceStatus()
        if (_uiState.value.isBusy || _uiState.value.isDownloading) return
        if (!isArm64Device()) {
            _uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
            return
        }

        val target = modelRepository.downloadedModelFile(source.fileName)
        if (target == null) {
            _uiState.update {
                it.copy(statusText = "下载目录不可用，请导入已有模型")
            }
            return
        }
        if (source.expectedBytes != null && target.exists() && source.hasExpectedSize(target.length())) {
            verifyAndRegisterDownloadedModel(target, source)
            return
        }
        if (target.exists() && !target.delete()) {
            _uiState.update {
                it.copy(statusText = "无法清理未完成的下载")
            }
            return
        }

        val modelParent = target.parentFile
        if (modelParent == null || (!modelParent.exists() && !modelParent.mkdirs())) {
            _uiState.update {
                it.copy(statusText = "无法创建模型下载目录")
            }
            return
        }
        val requiredBytes = source.expectedBytes ?: DEFAULT_CHAT_MODEL_BYTES
        if (!ModelCatalog.hasEnoughSpace(modelParent.usableSpace, requiredBytes)) {
            _uiState.update {
                it.copy(statusText = "存储空间不足，至少需要约 ${ModelCatalog.formatBytes(requiredBytes)}")
            }
            return
        }

        val downloadId = downloadService.enqueue(source, target).getOrElse { throwable ->
            _uiState.update {
                it.copy(statusText = "下载启动失败：${throwable.cleanMessage()}")
            }
            return
        }

        activeDownloadId = downloadId
        modelRepository.savePendingDownload(downloadId, source)
        _uiState.update {
            it.copy(
                isBusy = true,
                isDownloading = true,
                downloadProgressPercent = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusText = "模型下载中",
                isReady = false,
            )
        }
        monitorDownload(downloadId, target, source)
    }

    private fun monitorDownload(
        downloadId: Long,
        targetFile: File,
        source: ModelDownloadSource,
    ) {
        downloadMonitorJob?.cancel()
        activeDownloadId = downloadId
        downloadMonitorJob = viewModelScope.launch(ioDispatcher) {
            while (isActive && activeDownloadId == downloadId) {
                val info = downloadService.query(downloadId)
                if (info == null) {
                    if (activeDownloadId == downloadId) {
                        activeDownloadId = null
                        modelRepository.clearPendingDownload()
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isDownloading = false,
                                downloadProgressPercent = null,
                                statusText = "下载任务不存在",
                            )
                        }
                    }
                    return@launch
                }
                if (!isActive || activeDownloadId != downloadId) {
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isBusy = true,
                        isDownloading = true,
                        downloadProgressPercent = info.progressPercent,
                        downloadedBytes = info.downloadedBytes,
                        totalBytes = info.totalBytes,
                        statusText = info.statusText,
                    )
                }

                when (info.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        activeDownloadId = null
                        if (!targetFile.exists() || !source.hasExpectedSize(targetFile.length())) {
                            modelRepository.clearPendingDownload()
                            targetFile.delete()
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isDownloading = false,
                                    downloadProgressPercent = null,
                                    statusText = "下载文件不可用，请重新下载",
                                )
                            }
                            return@launch
                        }
                        _uiState.update {
                            it.copy(
                                isBusy = true,
                                isDownloading = false,
                                downloadProgressPercent = null,
                                statusText = "正在校验模型文件",
                            )
                        }
                        val verifiedSha256 = source.verifiedSha256(targetFile).getOrElse { throwable ->
                            modelRepository.clearPendingDownload()
                            setupDownloadQueue.clear()
                            setupDownloadInProgress = false
                            targetFile.delete()
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isDownloading = false,
                                    downloadProgressPercent = null,
                                    downloadedBytes = 0L,
                                    totalBytes = 0L,
                                    statusText = throwable.cleanMessage(),
                                )
                            }
                            return@launch
                        }
                        modelRepository.clearPendingDownload()
                        modelRepository.registerInstalledModel(
                            path = targetFile.absolutePath,
                            displayName = source.installedDisplayName(targetFile),
                            recommendedModelId = source.modelId,
                            verifiedSha256 = verifiedSha256,
                            verificationStatus = if (source.modelId == null) {
                                ModelVerificationStatus.UnverifiedCustom
                            } else {
                                ModelVerificationStatus.VerifiedRecommended
                            },
                        )
                        updateModelState(modelRepository.currentState())
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isDownloading = false,
                                downloadProgressPercent = null,
                                downloadedBytes = 0L,
                                totalBytes = 0L,
                                statusText = "模型下载完成",
                            )
                        }
                        continueSetupDownloadOrLoad(source)
                        return@launch
                    }

                    DownloadManager.STATUS_FAILED -> {
                        activeDownloadId = null
                        setupDownloadQueue.clear()
                        setupDownloadInProgress = false
                        modelRepository.clearPendingDownload()
                        targetFile.delete()
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isDownloading = false,
                                downloadProgressPercent = null,
                                statusText = "下载失败：${info.reasonText}",
                            )
                        }
                        return@launch
                    }
                }

                delay(1_000L)
            }
        }
    }

    private fun verifyAndRegisterDownloadedModel(
        targetFile: File,
        source: ModelDownloadSource,
    ) {
        _uiState.update {
            it.copy(
                isBusy = true,
                isDownloading = false,
                downloadProgressPercent = null,
                statusText = "正在校验模型文件",
                isReady = false,
            )
        }
        viewModelScope.launch(ioDispatcher) {
            val verifiedSha256 = source.verifiedSha256(targetFile).getOrElse { throwable ->
                targetFile.delete()
                setupDownloadQueue.clear()
                setupDownloadInProgress = false
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isDownloading = false,
                        downloadProgressPercent = null,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        statusText = throwable.cleanMessage(),
                    )
                }
                return@launch
            }
            modelRepository.registerInstalledModel(
                path = targetFile.absolutePath,
                displayName = source.installedDisplayName(targetFile),
                recommendedModelId = source.modelId,
                verifiedSha256 = verifiedSha256,
                verificationStatus = if (source.modelId == null) {
                    ModelVerificationStatus.UnverifiedCustom
                } else {
                    ModelVerificationStatus.VerifiedRecommended
                },
            )
            updateModelState(modelRepository.currentState())
            _uiState.update {
                it.copy(
                    isBusy = false,
                    isDownloading = false,
                    downloadProgressPercent = null,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusText = "模型校验通过",
                )
            }
            continueSetupDownloadOrLoad(source)
        }
    }

    private fun continueSetupDownloadOrLoad(completedSource: ModelDownloadSource) {
        if (setupDownloadQueue.isNotEmpty()) {
            val nextSource = setupDownloadQueue.removeFirst()
            beginModelDownload(nextSource)
            return
        }

        val completedCapability = completedSource.modelId
            ?.let { ModelCatalog.recommendedModelById(it).capability }
            ?: ModelCapability.Chat
        if (setupDownloadInProgress) {
            setupDownloadInProgress = false
            _uiState.update {
                it.copy(statusText = "基础能力包已准备")
            }
        }
        rebuildMemoryIndex()
        if (
            (completedCapability == ModelCapability.Chat || _uiState.value.modelPath != null) &&
            _uiState.value.modelPath != null &&
            !_uiState.value.isReady
        ) {
            loadModel()
        }
    }

    private fun recreateConversationForActiveSession(successPrefix: String) {
        _uiState.update {
            it.copy(
                isBusy = true,
                isReady = false,
                statusText = "正在恢复会话",
            )
        }
        viewModelScope.launch(ioDispatcher) {
            val result = runCatching {
                runtimeLock.withLock {
                    runtime.recreateConversation(
                        history = sessionRepository.activeMessages(),
                        parameters = _uiState.value.generationParameters,
                    )
                }
            }
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isReady = true,
                            statusText = "$successPrefix · ${it.backend.label()}",
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isReady = false,
                            statusText = "恢复会话失败：${throwable.cleanMessage()}",
                        )
                    }
                },
            )
        }
    }

    private fun createInitialState(): ChatUiState {
        val modelState = modelRepository.currentState()
        syncTaskStateMemories()
        syncSemanticMemoryRuntime()
        return ChatUiState(
            modelPath = modelState.activeModelPath,
            activeInstalledModelId = modelState.activeInstalledModelId,
            installedModels = modelState.installedModels,
            selectedModelId = modelState.selectedModelId,
            inferenceMode = remoteModelRepository.loadMode(),
            remoteModelConfig = remoteModelRepository.loadConfig(),
            backend = generationParametersRepository.loadBackend(),
            showFirstRunSetup = !firstRunSetupRepository.isSetupDismissed(),
            memoryEnabled = firstRunSetupRepository.isMemoryEnabled(),
            semanticMemoryEnabled = currentSemanticMemoryEnabled(),
            longTermMemories = loadLongTermMemories(),
            backgroundTasks = loadBackgroundTasks(),
            backgroundTaskHistory = loadBackgroundTaskHistory(),
            periodicCheckPolicy = loadPeriodicCheckPolicy(),
            auditEvents = loadAuditEvents(),
            agentTraceRuns = loadAgentTraceRuns(),
            generationParameters = generationParametersRepository.load(),
            sessions = sessionRepository.summaries(),
            activeSessionId = sessionRepository.activeSessionId,
            messages = sessionRepository.activeMessages(),
            isArm64Supported = isArm64Device(),
            availableModelStorageBytes = modelRepository.resolveModelStorageBytes(),
        )
    }

    private fun updateModelState(modelState: ModelSelectionState) {
        syncSemanticMemoryRuntime()
        _uiState.update {
            it.copy(
                modelPath = modelState.activeModelPath,
                activeInstalledModelId = modelState.activeInstalledModelId,
                installedModels = modelState.installedModels,
                selectedModelId = modelState.selectedModelId,
                semanticMemoryEnabled = currentSemanticMemoryEnabled(),
            )
        }
    }

    private fun verifyLegacyModelsOnStartup(skipModelRuntimeWork: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val changed = modelRepository.verifyLegacyRecommendedModels()
            if (!changed) return@launch
            val state = modelRepository.currentState()
            updateModelState(state)
            val localMode = _uiState.value.inferenceMode == InferenceMode.Local
            val hasFailedLegacy = state.installedModels.any {
                it.verificationStatus == ModelVerificationStatus.FailedVerification
            }
            when {
                !localMode || skipModelRuntimeWork -> Unit
                state.activeModelPath != null && !_uiState.value.isBusy && !_uiState.value.isReady -> {
                    _uiState.update { it.copy(statusText = "旧模型校验通过，正在加载") }
                    loadModel()
                }
                state.activeModelPath == null && hasFailedLegacy -> {
                    _uiState.update { it.copy(statusText = "旧模型校验失败，请重新下载或重新导入") }
                }
            }
        }
    }

    private fun updateRemoteReadiness(prefix: String) {
        val config = _uiState.value.remoteModelConfig
        _uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Remote,
                isBusy = false,
                isDownloading = false,
                isReady = config.isConfigured,
                statusText = if (config.isConfigured) {
                    "${prefix}已就绪"
                } else {
                    "请配置远程模型"
                },
            )
        }
    }

    private fun refreshDeviceStatus() {
        _uiState.update {
            it.copy(
                isArm64Supported = isArm64Device(),
                availableModelStorageBytes = modelRepository.resolveModelStorageBytes(),
            )
        }
    }

    private fun rebuildMemoryIndex() {
        runCatching {
            syncSemanticMemoryRuntime()
            memoryRepository.enabled = _uiState.value.memoryEnabled
            memoryRepository.rebuild(sessionRepository.allMessages(limit = 500))
            _uiState.update { state ->
                state.copy(semanticMemoryEnabled = currentSemanticMemoryEnabled())
            }
        }.onFailure {
            _uiState.update { state ->
                state.copy(
                    semanticMemoryEnabled = currentSemanticMemoryEnabled(),
                    memoryHits = emptyList(),
                    longTermMemories = emptyList(),
                    statusText = "本地记忆暂不可用",
                )
            }
        }
    }

    private fun syncSemanticMemoryRuntime() {
        val controller = semanticMemoryRuntimeController ?: return
        if (!controller.canLoadSemanticMemoryRuntime) {
            controller.useMemoryModel(null)
            return
        }
        controller.useMemoryModel(modelRepository.verifiedMemoryEmbeddingModelPath())
    }

    private fun currentSemanticMemoryEnabled(): Boolean =
        semanticMemoryRuntimeController?.semanticMemoryEnabled == true

    private fun syncTaskStateMemories() {
        runCatching {
            val activeTasks = backgroundTaskScheduler.scheduledTasks()
                .filter { task ->
                    task.status == ScheduledTaskStatus.Scheduled ||
                        task.status == ScheduledTaskStatus.Running
                }
            val activeMemoryIds = activeTasks
                .mapTo(mutableSetOf()) { task -> taskStateMemoryRecordId(task.id) }
            longTermMemoryControls.savedRecords()
                .filter { record ->
                    record.type == MemoryRecordType.TaskState &&
                        record.id.startsWith(TASK_STATE_MEMORY_RECORD_PREFIX) &&
                        record.id !in activeMemoryIds
                }
                .forEach { record -> longTermMemoryControls.forget(record.id) }
            activeTasks.forEach { task ->
                val memoryId = taskStateMemoryRecordId(task.id)
                if (longTermMemoryControls.isAutoManagedTaskStateSuppressed(memoryId)) return@forEach
                longTermMemoryControls.indexTaskState(
                    id = memoryId,
                    text = task.toTaskStateMemoryText(),
                )
            }
        }
    }

    private fun activeTaskStateMemoryIds(): Set<String> =
        runCatching {
            backgroundTaskScheduler.scheduledTasks()
                .filter { task ->
                    task.status == ScheduledTaskStatus.Scheduled ||
                        task.status == ScheduledTaskStatus.Running
                }
                .mapTo(mutableSetOf()) { task -> taskStateMemoryRecordId(task.id) }
        }.getOrDefault(emptySet())

    private fun loadLongTermMemories(): List<LongTermMemorySummary> =
        runCatching {
            longTermMemoryControls.savedRecords().map { record ->
                LongTermMemorySummary(
                    id = record.id,
                    type = record.type,
                    text = record.text,
                )
            }
        }.getOrDefault(emptyList())

    private fun persistExplicitPreferenceMemory(message: ChatMessage): Boolean {
        if (message.role != MessageRole.User) return false
        val preference = explicitUserPreferenceFrom(message.text) ?: return false
        return runCatching {
            longTermMemoryControls.indexPreference(explicitUserPreferenceRecordId(preference), preference)
            loadLongTermMemories()
        }.onSuccess { records ->
            _uiState.update { state ->
                state.copy(longTermMemories = records)
            }
        }.onFailure {
            _uiState.update { state ->
                state.copy(
                    longTermMemories = emptyList(),
                    statusText = "本地记忆暂不可用",
                )
            }
        }.isSuccess
    }

    private fun handleExplicitPreferenceCommand(trimmed: String) {
        syncTaskStateMemories()
        val userMessage = ChatMessage(
            role = MessageRole.User,
            text = trimmed,
            privacy = MessagePrivacy.LocalOnly,
        )
        replaceActiveSessionMessages(
            _uiState.value.messages + userMessage,
            persistNow = true,
        )
        val persisted = persistExplicitPreferenceMemory(userMessage)
        val assistantText = if (persisted) {
            "已记住这条本地偏好。你可以在长期记忆中查看或删除。"
        } else {
            "本地记忆暂不可用，未保存这条偏好。"
        }
        replaceActiveSessionMessages(
            _uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = assistantText,
                privacy = MessagePrivacy.LocalOnly,
            ),
            persistNow = true,
        )
        rebuildMemoryIndex()
        _uiState.update { state ->
            state.copy(
                memoryHits = emptyList(),
                longTermMemories = loadLongTermMemories(),
                statusText = if (persisted) "长期记忆已更新" else "本地记忆暂不可用",
            )
        }
    }

    private fun loadBackgroundTasks(): List<BackgroundTaskSummary> =
        backgroundTaskScheduler.scheduledTasks()
            .filter { task ->
                task.status == ScheduledTaskStatus.Scheduled ||
                    task.status == ScheduledTaskStatus.Running
            }
            .map { task -> task.toSummary() }

    private fun loadBackgroundTaskHistory(): List<BackgroundTaskSummary> =
        backgroundTaskScheduler.recentTasks()
            .filter { task ->
                task.status == ScheduledTaskStatus.Delivered ||
                    task.status == ScheduledTaskStatus.Cancelled ||
                    task.status == ScheduledTaskStatus.Deleted ||
                    task.status == ScheduledTaskStatus.Failed
            }
            .map { task -> task.toSummary() }

    private fun loadPeriodicCheckPolicy(): PeriodicCheckPolicySummary =
        runCatching {
            backgroundTaskScheduler.periodicCheckPolicy()
        }.getOrDefault(PeriodicCheckPolicySummary.disabled())

    private fun ScheduledTask.toSummary(): BackgroundTaskSummary =
        BackgroundTaskSummary(
            id = id,
            type = type,
            title = title,
            body = body,
            triggerAtMillis = triggerAtMillis,
            status = status,
        )

    private fun ScheduledTask.toTaskStateMemoryText(): String =
        listOf(
            "后台任务=${type.name}",
            "任务记录=${taskStateMemoryRecordId(id)}",
            "状态=${status.name}",
            "触发时间=$triggerAtMillis",
        ).joinToString(separator = "；")

    private fun loadAuditEvents(): List<AuditEventSummary> =
        toolAuditLog.recentAuditEvents().map { event ->
            AuditEventSummary(
                id = event.id,
                toolName = event.toolName,
                eventType = event.eventType,
                status = event.status,
                riskLevel = event.riskLevel,
                permissions = event.permissions,
                summary = event.summary,
                createdAtMillis = event.createdAtMillis,
            )
        }

    private fun loadAgentTraceRuns(): List<AgentTraceRunUiSummary> =
        runCatching {
            assistantOrchestrator.recentTraceRuns(limit = 5, stepLimit = 8)
                .map { run -> run.toUiSummary() }
        }.getOrDefault(emptyList())

    private fun AgentTraceRunSummary.toUiSummary(): AgentTraceRunUiSummary =
        AgentTraceRunUiSummary(
            id = run.id,
            state = run.state,
            updatedAtMillis = run.updatedAtMillis,
            steps = steps.map { step ->
                AgentTraceStepUiSummary(
                    type = step.type,
                    summary = step.summary,
                    createdAtMillis = step.createdAtMillis,
                )
            },
        )

    private fun restorePendingAgentConfirmationIfAny() {
        val route = assistantOrchestrator.restorePendingAction() ?: return
        _uiState.update {
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

    private fun failStaleAgentRunsOnStartup() {
        val failedCount = assistantOrchestrator.failStaleInFlightRuns(STALE_AGENT_RUN_STARTUP_REASON)
        if (failedCount <= 0) return
        _uiState.update {
            it.copy(agentTraceRuns = loadAgentTraceRuns())
        }
    }

    private fun isArm64Device(): Boolean =
        isArm64DeviceProvider()

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

    private fun finishStoppedGeneration() {
        val remoteMode = _uiState.value.inferenceMode == InferenceMode.Remote
        val currentMessages = _uiState.value.messages
        val messages = if (
            currentMessages.lastOrNull()?.role == MessageRole.Assistant &&
            currentMessages.last().text.isBlank()
        ) {
            currentMessages.dropLast(1)
        } else {
            currentMessages
        }
        replaceActiveSessionMessages(messages, persistNow = true)
        rebuildMemoryIndex()
        _uiState.update {
            it.copy(
                isBusy = false,
                isGenerating = false,
                isReady = if (remoteMode) it.remoteModelConfig.isConfigured else runtime.isLoaded,
                statusText = if (remoteMode) {
                    "已停止 · 远程"
                } else if (!runtime.isLoaded) {
                    "未加载模型"
                } else {
                    "已停止 · ${it.backend.label()}"
                },
            )
        }
    }

    private fun Throwable.cleanMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private fun MutableStateFlow<ChatUiState>.updateLastAssistant(text: String) {
        update { state ->
            val updatedMessages = state.messages.toMutableList()
            val index = updatedMessages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                updatedMessages[index] = updatedMessages[index].copy(
                    text = text,
                    generationStats = null,
                )
            }
            state.copy(messages = updatedMessages)
        }
    }

    private fun MutableStateFlow<ChatUiState>.updateLastAssistantStats(stats: GenerationStats?) {
        if (stats == null) return
        update { state ->
            val updatedMessages = state.messages.toMutableList()
            val index = updatedMessages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                updatedMessages[index] = updatedMessages[index].copy(generationStats = stats)
            }
            state.copy(messages = updatedMessages)
        }
    }

    private fun ChatUiState.toDeviceContextSnapshot(): DeviceContextSnapshot =
        DeviceContextSnapshot(
            isArm64Supported = isArm64Supported,
            inferenceMode = inferenceMode.name,
            installedCapabilities = installedCapabilities,
            memoryEnabled = memoryEnabled,
            availableStorageBytes = availableModelStorageBytes,
            activeSessionId = activeSessionId,
            hasPendingConfirmation = pendingConfirmation != null,
        )

    private fun PendingAgentConfirmation.matchesExecution(other: PendingAgentConfirmation): Boolean =
        runId == other.runId &&
            toolRequest?.id == other.toolRequest?.id &&
            draft.functionName == other.draft.functionName &&
            draft.parameters == other.draft.parameters
}
