package com.bytedance.zgx.pocketmind

import android.app.DownloadManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.action.ActionExecutor
import com.bytedance.zgx.pocketmind.data.FirstRunSetupRepository
import com.bytedance.zgx.pocketmind.data.ModelDownloadSource
import com.bytedance.zgx.pocketmind.data.GenerationParametersRepository
import com.bytedance.zgx.pocketmind.data.ModelRepository
import com.bytedance.zgx.pocketmind.data.ModelSelectionState
import com.bytedance.zgx.pocketmind.data.ModelVerificationStatus
import com.bytedance.zgx.pocketmind.data.RemoteModelRepository
import com.bytedance.zgx.pocketmind.data.SessionRepository
import com.bytedance.zgx.pocketmind.download.ModelDownloadService
import com.bytedance.zgx.pocketmind.memory.MemoryRepository
import com.bytedance.zgx.pocketmind.orchestration.AssistantOrchestrator
import com.bytedance.zgx.pocketmind.orchestration.AssistantRoute
import com.bytedance.zgx.pocketmind.runtime.LiteRtRuntime
import com.bytedance.zgx.pocketmind.runtime.RealLiteRtRuntime
import com.bytedance.zgx.pocketmind.runtime.RemoteChatRuntime
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

class PocketMindViewModel(
    private val modelRepository: ModelRepository,
    private val sessionRepository: SessionRepository,
    private val generationParametersRepository: GenerationParametersRepository,
    private val remoteModelRepository: RemoteModelRepository,
    private val firstRunSetupRepository: FirstRunSetupRepository,
    private val downloadService: ModelDownloadService,
    private val runtime: LiteRtRuntime,
    private val remoteRuntime: RemoteChatRuntime,
    private val memoryRepository: MemoryRepository,
    private val actionExecutor: ActionExecutor,
    private val assistantOrchestrator: AssistantOrchestrator,
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

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        RealLiteRtRuntime.configureNativeLogging()
    }

    fun restoreStartupState(skipModelRuntimeWork: Boolean = false) {
        if (startupRestored) return
        startupRestored = true

        refreshDeviceStatus()
        rebuildMemoryIndex()
        verifyLegacyModelsOnStartup(skipModelRuntimeWork)

        if (skipModelRuntimeWork) {
            if (_uiState.value.inferenceMode == InferenceMode.Remote) {
                updateRemoteReadiness("远程模型")
            } else if (_uiState.value.modelPath != null) {
                _uiState.update {
                    it.copy(statusText = "已找到模型，点击加载模型")
                }
            }
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
                statusText = if (!runtime.isLoaded) "已删除会话" else "正在恢复会话",
            )
        }
        if (runtime.isLoaded) {
            recreateConversationForActiveSession("已删除会话")
        }
    }

    fun sendMessage(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || !_uiState.value.isReady || _uiState.value.isBusy || generationJob?.isActive == true) {
            return
        }

        val stateBeforeSend = _uiState.value
        val useRemoteModel = stateBeforeSend.inferenceMode == InferenceMode.Remote
        val remoteConfig = stateBeforeSend.remoteModelConfig
        val remoteHistory = stateBeforeSend.messages
        _uiState.update {
            it.copy(
                isBusy = true,
                isGenerating = false,
                statusText = "处理中",
            )
        }

        val job = viewModelScope.launch(ioDispatcher) {
            try {
                val route = assistantOrchestrator.route(
                    input = trimmed,
                    installedCapabilities = stateBeforeSend.installedCapabilities,
                    memoryEnabled = stateBeforeSend.memoryEnabled,
                    actionModelPath = modelRepository.verifiedActionModelPath(),
                )
                val userMessage = ChatMessage(MessageRole.User, trimmed)
                when (route) {
                    is AssistantRoute.Action -> {
                        val planningLabel = if (route.plannedByModel) {
                            "动作模型实验"
                        } else {
                            "规则回退"
                        }
                        val assistantMessage = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "已准备动作草稿（$planningLabel）：${route.draft.summary}\n请确认后再执行。",
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + userMessage + assistantMessage,
                            persistNow = true,
                        )
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isGenerating = false,
                                pendingConfirmation = PendingAgentConfirmation(
                                    runId = route.runId,
                                    draft = route.draft,
                                    toolRequest = route.toolRequest,
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
                        val assistantMessage = ChatMessage(
                            role = MessageRole.Assistant,
                            text = "无法准备这个动作：${route.summary}",
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + userMessage + assistantMessage,
                            persistNow = true,
                        )
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
                        )
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + userMessage + assistantMessage,
                            persistNow = true,
                        )
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
                        val assistantPlaceholder = ChatMessage(MessageRole.Assistant, "")
                        replaceActiveSessionMessages(
                            stateBeforeSend.messages + userMessage + assistantPlaceholder,
                            persistNow = true,
                        )
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

    fun confirmAgentConfirmation(confirmation: PendingAgentConfirmation) {
        val executed = actionExecutor.executeConfirmed(confirmation.draft)
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                statusText = if (executed) "已打开系统确认页" else "无法执行这个动作",
            )
        }
    }

    fun dismissAgentConfirmation() {
        _uiState.update {
            it.copy(
                pendingConfirmation = null,
                statusText = "已取消动作草稿",
            )
        }
    }

    fun confirmActionDraft(draft: ActionDraft) {
        confirmAgentConfirmation(
            PendingAgentConfirmation(
                runId = null,
                draft = draft,
                toolRequest = null,
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
            generationParameters = generationParametersRepository.load(),
            sessions = sessionRepository.summaries(),
            activeSessionId = sessionRepository.activeSessionId,
            messages = sessionRepository.activeMessages(),
            isArm64Supported = isArm64Device(),
            availableModelStorageBytes = modelRepository.resolveModelStorageBytes(),
        )
    }

    private fun updateModelState(modelState: ModelSelectionState) {
        _uiState.update {
            it.copy(
                modelPath = modelState.activeModelPath,
                activeInstalledModelId = modelState.activeInstalledModelId,
                installedModels = modelState.installedModels,
                selectedModelId = modelState.selectedModelId,
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
        memoryRepository.enabled = _uiState.value.memoryEnabled
        memoryRepository.rebuild(sessionRepository.allMessages(limit = 500))
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
}
