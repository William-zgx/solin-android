package com.bytedance.zgx.solin.presentation

import android.app.DownloadManager
import android.net.Uri
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL_BYTES
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.RecommendedModel
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.destinationHostLabel
import com.bytedance.zgx.solin.hasSameConnectivityTarget
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.modelProfile
import com.bytedance.zgx.solin.data.BundledModelInstaller
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.GenerationParametersStore
import com.bytedance.zgx.solin.data.HuggingFaceAuthStore
import com.bytedance.zgx.solin.data.ModelDownloadSource
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.ModelSelectionState
import com.bytedance.zgx.solin.data.ModelVerificationStatus
import com.bytedance.zgx.solin.data.RemoteModelStore
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.download.ModelDownloadClient
import com.bytedance.zgx.solin.logging.solinE
import com.bytedance.zgx.solin.logging.solinI
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_MODEL
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeController
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeStatus
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.RemoteModelConnectivityProbe
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns local/remote model selection, download/import, HF auth, first-run setup download queue,
 * bundled install, and model load readiness.
 *
 * Extracted from [com.bytedance.zgx.solin.SolinViewModel] (Wave 2 Track C2). The ViewModel keeps
 * thin public wrappers and owns chat-side pending-remote state machines via callbacks.
 */
class ModelLoadController(
    private val modelRepository: ModelRepositoryFacade,
    private val downloadService: ModelDownloadClient,
    private val runtime: LiteRtRuntime,
    private val remoteModelRepository: RemoteModelStore,
    private val huggingFaceAuthStore: HuggingFaceAuthStore,
    private val firstRunSetupRepository: FirstRunSetupStore,
    private val generationParametersRepository: GenerationParametersStore,
    private val sessionRepository: SessionStore,
    private val bundledModelInstaller: BundledModelInstaller,
    private val remoteConnectivityProbe: RemoteModelConnectivityProbe,
    private val semanticMemoryRuntimeController: SemanticMemoryRuntimeController?,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val runtimeLock: Mutex,
    private val isArm64DeviceProvider: () -> Boolean,
    private val skipStartupModelRuntimeWork: Boolean,
    private val clearPendingRemoteState: () -> Unit,
    private val resetRemoteSendDisclosureSuppression: () -> Unit,
    private val rebuildMemoryIndex: () -> Unit,
) {
    private var bundledModelInstallJob: Job? = null
    private var downloadMonitorJob: Job? = null
    private var downloadPreflightJob: Job? = null
    private var activeDownloadId: Long? = null
    private val setupDownloadQueue = ArrayDeque<ModelDownloadSource>()
    private var setupDownloadInProgress = false
    private var remoteConnectivityProbeJob: Job? = null

    fun close() {
        downloadMonitorJob?.cancel()
        downloadPreflightJob?.cancel()
        remoteConnectivityProbeJob?.cancel()
        bundledModelInstallJob?.cancel()
    }

    private fun isArm64Device(): Boolean = isArm64DeviceProvider()

    private fun semanticMemoryEnabled(): Boolean =
        semanticMemoryRuntimeController?.semanticMemoryEnabled == true

    private fun semanticMemoryRuntimeStatus(): SemanticMemoryRuntimeStatus =
        semanticMemoryRuntimeController?.semanticMemoryRuntimeStatus
            ?: SemanticMemoryRuntimeStatus.RuntimeUnavailable

    private fun semanticMemoryIndexedRecordCount(): Int =
        semanticMemoryRuntimeController?.semanticMemoryIndexedRecordCount ?: 0

    private fun semanticMemoryLastRebuiltAtMillis(): Long? =
        semanticMemoryRuntimeController?.semanticMemoryLastRebuiltAtMillis

    fun startBundledModelInstallOnStartup(skipModelRuntimeWork: Boolean): Boolean {
        if (skipModelRuntimeWork || !bundledModelInstaller.isEnabled || bundledModelInstallJob != null) {
            return false
        }
        bundledModelInstallJob = scope.launch(ioDispatcher) {
            uiState.update {
                it.copy(
                    showFirstRunSetup = false,
                    statusText = "正在准备内置模型",
                )
            }
            val result = runCatching { bundledModelInstaller.install() }
                .getOrElse { error ->
                    uiState.update {
                        it.copy(statusText = "内置模型准备失败：${error.cleanMessage()}")
                    }
                    return@launch
                }
            if (!result.available) {
                return@launch
            }

            if (result.installedModelCount > 0 || modelRepository.currentState().activeModelPath != null) {
                firstRunSetupRepository.markSetupDismissed()
            }
            val modelState = modelRepository.currentState()
            updateModelState(modelState)
            uiState.update {
                it.copy(
                    showFirstRunSetup = false,
                    statusText = when {
                        result.hasFailures ->
                            "内置模型部分准备失败：${result.failedModelIds.joinToString()}"
                        modelState.activeModelPath != null ->
                            "内置模型已准备好，正在加载"
                        else ->
                            "内置模型已准备好"
                    },
                )
            }
            if (
                uiState.value.inferenceMode == InferenceMode.Local &&
                modelState.activeModelPath != null &&
                !skipStartupModelRuntimeWork
            ) {
                loadModel()
            }
        }
        return true
    }


    fun configureDebugRemoteModelForScreenshotEvidence(
        baseUrl: String,
        modelName: String,
        supportsVisionInput: Boolean = false,
    ) {
        val config = RemoteModelConfig(
            baseUrl = baseUrl,
            modelName = modelName,
            apiKey = "",
            supportsVisionInput = supportsVisionInput,
        ).normalized()
        if (!config.isConfigured) {
            uiState.update { it.copy(statusText = "截图验证远程配置无效") }
            return
        }
        remoteModelRepository.saveConfigWithoutApiKey(config)
            .fold(
                onSuccess = { normalized ->
                    firstRunSetupRepository.markSetupDismissed()
                    remoteModelRepository.saveMode(InferenceMode.Remote)
                    uiState.update {
                        it.copy(
                            remoteModelConfig = normalized,
                            inferenceMode = InferenceMode.Remote,
                            showFirstRunSetup = false,
                        )
                    }
                    updateRemoteReadiness("远程模型")
                },
                onFailure = { throwable ->
                    uiState.update {
                        it.copy(statusText = "截图验证远程配置保存失败：${throwable.cleanMessage()}")
                    }
                },
            )
    }


    fun startModelDownload() {
        beginRecommendedModelDownload(modelRepository.selectedRecommendedModel())
    }

    fun startRecommendedModelDownload(modelId: String) {
        val model = ModelCatalog.recommendedModelById(modelId)
        if (model.capability == ModelCapability.Chat) {
            val result = modelRepository.selectRecommendedModel(model.id)
            updateModelState(result.state)
        }
        beginRecommendedModelDownload(model)
    }

    fun saveHuggingFaceAccessToken(token: String) {
        huggingFaceAuthStore.saveAccessToken(token)
            .onSuccess {
                uiState.update {
                    it.copy(
                        huggingFaceAccessTokenConfigured = huggingFaceAuthStore.hasAccessToken(),
                        pendingHuggingFaceAuthorizationModelId = null,
                        statusText = "Hugging Face 授权已保存，可以下载原始记忆模型",
                    )
                }
            }
            .onFailure { throwable ->
                uiState.update {
                    it.copy(statusText = "Hugging Face 授权保存失败：${throwable.cleanMessage()}")
                }
            }
    }

    fun clearHuggingFaceAccessToken() {
        huggingFaceAuthStore.clearAccessToken()
            .onSuccess {
                uiState.update {
                    it.copy(
                        huggingFaceAccessTokenConfigured = false,
                        statusText = "Hugging Face 授权已清除",
                    )
                }
            }
            .onFailure { throwable ->
                uiState.update {
                    it.copy(statusText = "Hugging Face 授权清除失败：${throwable.cleanMessage()}")
                }
            }
    }

    fun toggleSetupModel(modelId: String, selected: Boolean) {
        if (uiState.value.isBusy) return
        uiState.update { state ->
            val next = if (selected) {
                state.setupSelectedModelIds + modelId
            } else {
                state.setupSelectedModelIds - modelId
            }
            state.copy(setupSelectedModelIds = next)
        }
    }

    fun startSetupModelDownload() {
        if (uiState.value.isBusy || uiState.value.isDownloading) return
        val selectedModels = uiState.value.basicSetupModels
            .filter { it.id in uiState.value.setupSelectedModelIds && !uiState.value.isModelInstalled(it.id) }
        if (selectedModels.isEmpty()) {
            firstRunSetupRepository.markSetupDismissed()
            uiState.update {
                it.copy(
                    showFirstRunSetup = false,
                    statusText = if (it.modelPath == null) "可稍后在模型管理安装模型" else it.statusText,
                )
            }
            return
        }
        val selectedSources = selectedModels.flatMap { ModelDownloadSource.recommendedBundle(it) }
        selectedSources.firstOrNull { source ->
            source.requiresHuggingFaceAuthorization && !huggingFaceAuthStore.hasAccessToken()
        }?.let { missingAuthSource ->
            blockDownloadForMissingHuggingFaceAuthorization(missingAuthSource)
            return
        }
        setupDownloadQueue.clear()
        selectedSources.drop(1).forEach(setupDownloadQueue::add)
        setupDownloadInProgress = true
        if (!beginModelDownload(selectedSources.first())) {
            setupDownloadQueue.clear()
            setupDownloadInProgress = false
        }
    }

    private fun beginRecommendedModelDownload(model: RecommendedModel): Boolean {
        val sources = ModelDownloadSource.recommendedBundle(model)
        setupDownloadQueue.clear()
        sources.drop(1).forEach(setupDownloadQueue::add)
        return beginModelDownload(sources.first())
    }


    fun skipFirstRunSetup() {
        if (uiState.value.isBusy) return
        firstRunSetupRepository.markSetupDismissed()
        uiState.update {
            it.copy(
                showFirstRunSetup = false,
                statusText = if (it.modelPath == null) "已跳过模型准备，可稍后在模型管理安装" else it.statusText,
            )
        }
    }


    fun startCustomModelDownload(downloadUrl: String) {
        val source = modelRepository.createCustomDownloadSource(downloadUrl)
        if (source == null) {
            uiState.update {
                it.copy(statusText = "请输入有效的 HTTPS .litertlm 模型下载链接；HTTP 仅支持本地调试地址")
            }
            return
        }
        beginModelDownload(source)
    }

    fun cancelModelDownload() {
        val downloadId = activeDownloadId ?: modelRepository.pendingDownloadId()
        downloadPreflightJob?.cancel()
        downloadPreflightJob = null
        downloadMonitorJob?.cancel()
        downloadMonitorJob = null
        activeDownloadId = null
        setupDownloadQueue.clear()
        setupDownloadInProgress = false
        downloadService.cancelPreflight()
        downloadService.cancel(downloadId)
        modelRepository.clearPendingDownload()
        uiState.update {
            it.copy(
                isBusy = false,
                isPreparingDownload = false,
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
        if (uiState.value.isBusy) return
        if (!isArm64Device()) {
            uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
            return
        }

        uiState.update {
            it.copy(
                isBusy = true,
                isReady = false,
                statusText = "正在导入模型",
            )
        }

        scope.launch {
            val result = runCatching {
                withContext(ioDispatcher) {
                    runtimeLock.withLock {
                        runtime.close()
                        modelRepository.importModel(uri) { progress ->
                            uiState.update {
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
                    firstRunSetupRepository.markSetupDismissed()
                    updateModelState(modelRepository.currentState())
                    uiState.update {
                        it.copy(
                            modelPath = path,
                            isBusy = false,
                            isDownloading = false,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "模型已导入",
                            showFirstRunSetup = false,
                        )
                    }
                    loadModel()
                },
                onFailure = { throwable ->
                    uiState.update {
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
        if (uiState.value.isBusy || uiState.value.backend == choice) return
        if (!backendAllowedForActiveModel(uiState.value, choice)) {
            uiState.update {
                it.copy(statusText = "当前模型不支持 ${choice.label()}，请使用可用后端")
            }
            return
        }
        generationParametersRepository.saveBackend(choice)
        uiState.update {
            it.copy(
                backend = choice,
                isReady = false,
                statusText = "已切换到 ${choice.label()}，点击加载模型",
            )
        }
    }


    fun updateRemoteModelConfig(config: RemoteModelConfig) {
        if (uiState.value.isBusy) return
        clearPendingRemoteState()
        remoteConnectivityProbeJob?.cancel()
        // Trust boundary changed (remote destination/credential): never let a prior
        // session-scoped "don't ask again" carry over to a new remote endpoint.
        resetRemoteSendDisclosureSuppression()
        val previousConfig = uiState.value.remoteModelConfig
        val requestedConfig = config.normalized()
        val configToSave = requestedConfig.copy(
            connectivityStatus = if (requestedConfig.hasSameConnectivityTarget(previousConfig)) {
                requestedConfig.connectivityStatus
            } else {
                RemoteModelConnectivityStatus.Unknown
            },
        )
        remoteModelRepository.saveConfig(configToSave)
            .fold(
                onSuccess = { normalized ->
                    uiState.update {
                        it.copy(
                            remoteModelConfig = normalized,
                            pendingRemoteModeDisclosure = null,
                            pendingRemoteSendDisclosure = null,
                            showFirstRunSetup = if (normalized.isConfigured) false else it.showFirstRunSetup,
                        )
                    }
                    if (normalized.isConfigured) {
                        firstRunSetupRepository.markSetupDismissed()
                    }
                    if (uiState.value.inferenceMode == InferenceMode.Remote) {
                        updateRemoteReadiness("远程模型")
                    }
                },
                onFailure = { throwable ->
                    uiState.update {
                        it.copy(
                            remoteModelConfig = configToSave,
                            statusText = "远程配置保存失败（已临时生效）：${throwable.cleanMessage()}",
                        )
                    }
                    if (uiState.value.inferenceMode == InferenceMode.Remote) {
                        updateRemoteReadiness("远程模型")
                    }
                },
            )
    }

    fun testRemoteModelConnectivity() {
        if (uiState.value.isBusy) return
        val config = uiState.value.remoteModelConfig.normalized()
        if (!config.isConfigured) {
            uiState.update {
                it.copy(
                    remoteModelConfig = config.copy(connectivityStatus = RemoteModelConnectivityStatus.Unknown),
                    statusText = "请先填写有效远程配置",
                )
            }
            return
        }
        remoteConnectivityProbeJob?.cancel()
        val checkingConfig = config.copy(connectivityStatus = RemoteModelConnectivityStatus.Checking)
        uiState.update {
            it.copy(
                remoteModelConfig = checkingConfig,
                statusText = "正在测试远程连接",
            )
        }
        remoteConnectivityProbeJob = scope.launch(ioDispatcher) {
            val status = remoteConnectivityProbe.check(config)
            val currentConfig = uiState.value.remoteModelConfig
            if (!currentConfig.hasSameConnectivityTarget(config)) return@launch
            val updatedConfig = currentConfig.copy(connectivityStatus = status)
            remoteModelRepository.saveConfig(updatedConfig)
                .fold(
                    onSuccess = { normalized ->
                        uiState.update {
                            it.copy(
                                remoteModelConfig = normalized,
                                statusText = "远程连接${status.label}",
                            )
                        }
                    },
                    onFailure = { throwable ->
                        uiState.update {
                            it.copy(statusText = "远程连接状态保存失败：${throwable.cleanMessage()}")
                        }
                    },
                )
        }
    }


    fun selectRecommendedModel(modelId: String) {
        if (uiState.value.isBusy || uiState.value.selectedModelId == modelId) return
        val result = modelRepository.selectRecommendedModel(modelId)
        updateModelState(result.state)
        val activated = result.activatedModel
        uiState.update {
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
        if (uiState.value.isBusy || uiState.value.activeInstalledModelId == modelId) return
        val installed = modelRepository.selectInstalledModel(modelId) ?: return
        remoteModelRepository.saveMode(InferenceMode.Local)
        clearPendingRemoteState()
        resetRemoteSendDisclosureSuppression()
        updateModelState(modelRepository.currentState())
        uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                pendingRemoteModeDisclosure = null,
                pendingRemoteSendDisclosure = null,
                isReady = false,
                statusText = "已切换到 ${installed.displayName}，点击加载模型",
            )
        }
    }

    fun deleteInstalledModel(modelId: String) {
        val stateBeforeDelete = uiState.value
        if (stateBeforeDelete.isBusy || stateBeforeDelete.isDownloading || stateBeforeDelete.isGenerating) return
        val target = stateBeforeDelete.installedModels.firstOrNull { it.id == modelId } ?: return
        val wasActive = target.id == stateBeforeDelete.activeInstalledModelId
        uiState.update {
            it.copy(
                isBusy = true,
                statusText = "正在删除 ${target.displayName}",
            )
        }
        scope.launch(ioDispatcher) {
            if (wasActive) {
                runtimeLock.withLock {
                    runtime.close()
                }
            }
            val deleted = modelRepository.deleteInstalledModel(modelId)
            if (deleted && target.capability == ModelCapability.MemoryEmbedding) {
                target.recommendedModelId?.let { memoryModelId ->
                    semanticMemoryRuntimeController?.clearSemanticMemoryForModel(memoryModelId)
                }
            }
            val modelState = modelRepository.currentState()
            updateModelState(modelState)
            uiState.update { current ->
                if (deleted) {
                    val fallback = modelState.installedModels.firstOrNull {
                        it.id == modelState.activeInstalledModelId
                    }
                    current.copy(
                        isReady = if (wasActive) false else current.isReady,
                        isBusy = false,
                        isGenerating = false,
                        statusText = if (wasActive) {
                            fallback?.let { "已删除 ${target.displayName}，已切换到 ${it.displayName}，点击加载模型" }
                                ?: "已删除 ${target.displayName}，请下载或导入本地模型"
                        } else {
                            "已删除 ${target.displayName}"
                        },
                    )
                } else {
                    current.copy(
                        isBusy = false,
                        isReady = if (wasActive) false else current.isReady,
                        isGenerating = if (wasActive) false else current.isGenerating,
                        statusText = "删除 ${target.displayName} 失败",
                    )
                }
            }
            refreshDeviceStatus()
        }
    }

    fun loadModel() {
        val path = uiState.value.modelPath ?: return
        if (uiState.value.isBusy) return
        val backendChoice = preferredBackendForActiveModel(uiState.value, uiState.value.backend)
        solinI(TAG_MODEL, "loadModel: path=$path backend=${backendChoice.name}")
        remoteModelRepository.saveMode(InferenceMode.Local)
        clearPendingRemoteState()
        if (backendChoice != uiState.value.backend) {
            generationParametersRepository.saveBackend(backendChoice)
        }

        uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                isBusy = true,
                isDownloading = false,
                isReady = false,
                statusText = "正在校验本地模型",
                modelHealth = ModelHealth(
                    profileId = it.activeModelProfileId(),
                    state = ModelHealthState.Loading,
                    backend = backendChoice,
                ),
            )
        }

        scope.launch(ioDispatcher) {
            val verified = runCatching { modelRepository.verifyActiveModelPath(path) }
                .getOrDefault(false)
            if (!verified) {
                uiState.update {
                    it.copy(
                        isBusy = false,
                        isReady = false,
                        statusText = "模型校验失败，请重新下载或重新导入",
                        modelHealth = ModelHealth(
                            profileId = it.activeModelProfileId(),
                            state = ModelHealthState.LoadFailed,
                            backend = backendChoice,
                            failureReason = "模型文件校验失败",
                        ),
                    )
                }
                return@launch
            }

        uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Local,
                backend = backendChoice,
                isBusy = true,
                isDownloading = false,
                isReady = false,
                downloadProgressPercent = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusText = "正在初始化 ${backendChoice.label()}",
                modelHealth = ModelHealth(
                    profileId = it.activeModelProfileId(),
                    state = ModelHealthState.Loading,
                    backend = backendChoice,
                ),
            )
        }

            val runtimeCapabilities = localModelRuntimeCapabilitiesFor(uiState.value)
            val loadStartMs = System.currentTimeMillis()
            val result = runCatching {
                runtimeLock.withLock {
                    runtime.configureModelCapabilities(runtimeCapabilities)
                    runtime.load(
                        modelPath = path,
                        backend = backendChoice,
                        history = sessionRepository.activeMessages(),
                        parameters = uiState.value.generationParameters,
                    )
                }
            }

            result.fold(
                onSuccess = {
                    val durationMs = System.currentTimeMillis() - loadStartMs
                    solinI(TAG_MODEL, "loadModel: success in ${durationMs}ms backend=${backendChoice.name}")
                    uiState.update {
                        it.copy(
                            isBusy = false,
                            isDownloading = false,
                            isReady = true,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "就绪 · ${it.backend.label()}",
                            modelHealth = ModelHealth(
                                profileId = it.activeModelProfileId(),
                                state = ModelHealthState.Loaded,
                                backend = backendChoice,
                            ),
                        )
                    }
                    rebuildMemoryIndex()
                },
                onFailure = { throwable ->
                    solinE(TAG_MODEL, "loadModel: failed backend=${backendChoice.name}", throwable)
                    var fallbackFailure: Throwable? = null
                    if (backendChoice == BackendChoice.GPU &&
                        backendAllowedForActiveModel(uiState.value, BackendChoice.CPU)
                    ) {
                        val cpuResult = runCatching {
                            runtimeLock.withLock {
                                runtime.configureModelCapabilities(runtimeCapabilities)
                                runtime.load(
                                    modelPath = path,
                                    backend = BackendChoice.CPU,
                                    history = sessionRepository.activeMessages(),
                                    parameters = uiState.value.generationParameters,
                                )
                            }
                        }
                        if (cpuResult.isSuccess) {
                            uiState.update {
                                it.copy(
                                    backend = BackendChoice.CPU,
                                    isBusy = false,
                                    isDownloading = false,
                                    isReady = true,
                                    downloadProgressPercent = null,
                                    downloadedBytes = 0L,
                                    totalBytes = 0L,
                                    statusText = "GPU 不可用，已切到 CPU",
                                    modelHealth = ModelHealth(
                                        profileId = it.activeModelProfileId(),
                                        state = ModelHealthState.FallbackActive,
                                        backend = BackendChoice.CPU,
                                        fallbackBackend = BackendChoice.CPU,
                                        failureReason = "GPU 初始化失败：${throwable.cleanMessage()}",
                                    ),
                                )
                            }
                        rebuildMemoryIndex()
                            return@launch
                        }
                        fallbackFailure = cpuResult.exceptionOrNull()
                    }
                    val failureReason = fallbackFailure?.let { cpuThrowable ->
                        "GPU: ${throwable.cleanMessage()}；CPU: ${cpuThrowable.cleanMessage()}"
                    } ?: throwable.cleanMessage()
                    uiState.update {
                        it.copy(
                            isBusy = false,
                            isDownloading = false,
                            isReady = false,
                            downloadProgressPercent = null,
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            statusText = "初始化失败：$failureReason",
                            modelHealth = ModelHealth(
                                profileId = it.activeModelProfileId(),
                                state = ModelHealthState.LoadFailed,
                                backend = backendChoice,
                                failureReason = failureReason,
                            ),
                        )
                    }
                },
            )
        }
    }


    private fun beginModelDownload(source: ModelDownloadSource): Boolean {
        refreshDeviceStatus()
        if (uiState.value.isBusy || uiState.value.isDownloading) return false
        if (blockDownloadForMissingHuggingFaceAuthorization(source)) return false
        if (!isArm64Device()) {
            uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
            return false
        }

        val target = modelRepository.downloadedModelFile(source.fileName)
        if (target == null) {
            uiState.update {
                it.copy(statusText = "下载目录不可用，请导入已有模型")
            }
            return false
        }
        if (source.expectedBytes != null && target.exists() && source.hasExpectedSize(target.length())) {
            verifyAndRegisterDownloadedModel(target, source)
            return true
        }
        if (target.exists() && !target.delete()) {
            uiState.update {
                it.copy(statusText = "无法清理未完成的下载")
            }
            return false
        }

        val modelParent = target.parentFile
        if (modelParent == null || (!modelParent.exists() && !modelParent.mkdirs())) {
            uiState.update {
                it.copy(statusText = "无法创建模型下载目录")
            }
            return false
        }
        val requiredBytes = source.expectedBytes ?: DEFAULT_CHAT_MODEL_BYTES
        if (!ModelCatalog.hasEnoughSpace(modelParent.usableSpace, requiredBytes)) {
            uiState.update {
                it.copy(statusText = "存储空间不足，至少需要约 ${ModelCatalog.formatBytes(requiredBytes)}")
            }
            return false
        }

        val isFirstRunSetupDownload = setupDownloadInProgress
        if (!isFirstRunSetupDownload) {
            firstRunSetupRepository.markSetupDismissed()
        }
        uiState.update {
            it.copy(
                isBusy = true,
                isPreparingDownload = true,
                isDownloading = false,
                downloadProgressPercent = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusText = if (source.requiresHuggingFaceAuthorization) {
                    "正在验证 Hugging Face 授权"
                } else {
                    "正在准备模型下载"
                },
                isReady = false,
                showFirstRunSetup = false,
            )
        }
        val preflightJob = scope.launch(ioDispatcher) {
            val downloadResult = downloadService.enqueue(source, target)
            if (!isActive) {
                downloadResult.getOrNull()?.let(downloadService::cancel)
                return@launch
            }
            downloadPreflightJob = null
            if (downloadResult.isFailure) {
                val throwable = downloadResult.exceptionOrNull()
                setupDownloadQueue.clear()
                setupDownloadInProgress = false
                uiState.update {
                    it.copy(
                        isBusy = false,
                        isPreparingDownload = false,
                        isDownloading = false,
                        downloadProgressPercent = null,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        statusText = "下载启动失败：${throwable?.cleanMessage() ?: "未知错误"}",
                    )
                }
                return@launch
            }
            val downloadId = downloadResult.getOrThrow()
            activeDownloadId = downloadId
            modelRepository.savePendingDownload(downloadId, source)
            uiState.update {
                it.copy(
                    isBusy = true,
                    isPreparingDownload = false,
                    isDownloading = true,
                    downloadProgressPercent = null,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusText = "模型下载中",
                    isReady = false,
                    showFirstRunSetup = false,
                )
            }
            monitorDownload(downloadId, target, source)
        }
        downloadPreflightJob = preflightJob
        preflightJob.invokeOnCompletion {
            if (downloadPreflightJob == preflightJob) {
                downloadPreflightJob = null
            }
        }
        return true
    }

    private fun blockDownloadForMissingHuggingFaceAuthorization(source: ModelDownloadSource): Boolean {
        if (!source.requiresHuggingFaceAuthorization || huggingFaceAuthStore.hasAccessToken()) return false
        setupDownloadQueue.clear()
        setupDownloadInProgress = false
        uiState.update {
            it.copy(
                pendingHuggingFaceAuthorizationModelId = source.modelId,
                huggingFaceAccessTokenConfigured = false,
                statusText = "需要先登录 Hugging Face、接受模型许可，并保存 read token 后再下载",
            )
        }
        return true
    }

    fun monitorDownload(
        downloadId: Long,
        targetFile: File,
        source: ModelDownloadSource,
    ) {
        downloadMonitorJob?.cancel()
        activeDownloadId = downloadId
        downloadMonitorJob = scope.launch(ioDispatcher) {
            while (isActive && activeDownloadId == downloadId) {
                val info = downloadService.query(downloadId)
                if (info == null) {
                    if (activeDownloadId == downloadId) {
                        activeDownloadId = null
                        modelRepository.clearPendingDownload()
                        uiState.update {
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

                uiState.update {
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
                            uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isDownloading = false,
                                    downloadProgressPercent = null,
                                    statusText = "下载文件不可用，请重新下载",
                                )
                            }
                            return@launch
                        }
                        uiState.update {
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
                            uiState.update {
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
                        if (source.registerInstalledModel) {
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
                        }
                        updateModelState(modelRepository.currentState())
                        uiState.update {
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
                        val restoreFirstRunSetup = shouldRestoreFirstRunSetupAfterDownloadFailure()
                        activeDownloadId = null
                        setupDownloadQueue.clear()
                        setupDownloadInProgress = false
                        modelRepository.clearPendingDownload()
                        targetFile.delete()
                        uiState.update {
                            it.copy(
                                isBusy = false,
                                isDownloading = false,
                                downloadProgressPercent = null,
                                downloadedBytes = 0L,
                                totalBytes = 0L,
                                statusText = downloadFailureStatusText(info.reasonText, source),
                                showFirstRunSetup = restoreFirstRunSetup,
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
        uiState.update {
            it.copy(
                isBusy = true,
                isDownloading = false,
                downloadProgressPercent = null,
                statusText = "正在校验模型文件",
                isReady = false,
            )
        }
        scope.launch(ioDispatcher) {
            val verifiedSha256 = source.verifiedSha256(targetFile).getOrElse { throwable ->
                targetFile.delete()
                setupDownloadQueue.clear()
                setupDownloadInProgress = false
                uiState.update {
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
            if (source.registerInstalledModel) {
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
            }
            firstRunSetupRepository.markSetupDismissed()
            updateModelState(modelRepository.currentState())
            uiState.update {
                it.copy(
                    isBusy = false,
                    isDownloading = false,
                    downloadProgressPercent = null,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusText = "模型校验通过",
                    showFirstRunSetup = false,
                )
            }
            continueSetupDownloadOrLoad(source)
        }
    }

    private fun continueSetupDownloadOrLoad(completedSource: ModelDownloadSource) {
        if (setupDownloadQueue.isNotEmpty()) {
            val nextSource = setupDownloadQueue.removeFirst()
            if (!beginModelDownload(nextSource)) {
                setupDownloadQueue.clear()
                setupDownloadInProgress = false
            }
            return
        }

        val completedCapability = completedSource.modelId
            ?.let { ModelCatalog.recommendedModelById(it).capability }
            ?: ModelCapability.Chat
        if (setupDownloadInProgress) {
            setupDownloadInProgress = false
            uiState.update {
                it.copy(statusText = "基础能力包已准备")
            }
        }
        rebuildMemoryIndex()
        if (
            (completedCapability == ModelCapability.Chat || uiState.value.modelPath != null) &&
            uiState.value.modelPath != null &&
            !uiState.value.isReady
        ) {
            loadModel()
        }
        if (completedCapability == ModelCapability.Chat || uiState.value.modelPath != null) {
            firstRunSetupRepository.markSetupDismissed()
            uiState.update {
                it.copy(showFirstRunSetup = false)
            }
        }
    }

    private fun shouldRestoreFirstRunSetupAfterDownloadFailure(): Boolean =
        setupDownloadInProgress && uiState.value.modelPath == null && !uiState.value.isReady


    fun updateModelState(modelState: ModelSelectionState) {
        uiState.update {
            it.copy(
                modelPath = modelState.activeModelPath,
                activeInstalledModelId = modelState.activeInstalledModelId,
                installedModels = modelState.installedModels,
                selectedModelId = modelState.selectedModelId,
                localMaxTotalTokens = modelState.localContextWindowTokens(),
                localPreferredBackends = modelState.localPreferredBackends(),
                modelHealth = modelState.modelHealthForCurrentSelection(it.backend),
                semanticMemoryEnabled = semanticMemoryEnabled(),
                semanticMemoryRuntimeStatus = semanticMemoryRuntimeStatus(),
                semanticMemoryIndexedRecordCount = semanticMemoryIndexedRecordCount(),
                semanticMemoryLastRebuiltAtMillis = semanticMemoryLastRebuiltAtMillis(),
                huggingFaceAccessTokenConfigured = huggingFaceAuthStore.hasAccessToken(),
            )
        }
    }

    private fun downloadFailureStatusText(reasonText: String, source: ModelDownloadSource): String =
        if (source.requiresHuggingFaceAuthorization) {
            "Hugging Face 下载失败：请确认已登录、已接受模型许可，且 read token 有效（$reasonText）"
        } else {
            "下载失败：$reasonText"
        }

    fun verifyLegacyModelsOnStartup(skipModelRuntimeWork: Boolean) {
        scope.launch(ioDispatcher) {
            val changed = modelRepository.verifyLegacyRecommendedModels()
            if (!changed) return@launch
            val state = modelRepository.currentState()
            updateModelState(state)
            val localMode = uiState.value.inferenceMode == InferenceMode.Local
            val hasFailedLegacy = state.installedModels.any {
                it.verificationStatus == ModelVerificationStatus.FailedVerification
            }
            when {
                !localMode || skipModelRuntimeWork -> Unit
                state.activeModelPath != null && !uiState.value.isBusy && !uiState.value.isReady -> {
                    uiState.update { it.copy(statusText = "旧模型校验通过，正在加载") }
                    loadModel()
                }
                state.activeModelPath == null && hasFailedLegacy -> {
                    uiState.update { it.copy(statusText = "旧模型校验失败，请重新下载或重新导入") }
                }
            }
        }
    }

    fun updateRemoteReadiness(
        prefix: String,
        showModeDisclosure: Boolean = false,
    ) {
        val config = uiState.value.remoteModelConfig
        clearPendingRemoteState()
        val modeDisclosure = if (showModeDisclosure) {
            buildRemoteModeDisclosure(config)
        } else {
            null
        }
        uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Remote,
                pendingRemoteModeDisclosure = modeDisclosure,
                pendingRemoteSendDisclosure = null,
                isBusy = false,
                isDownloading = false,
                isReady = config.isConfigured,
                statusText = if (modeDisclosure != null) {
                    "远程模式待确认"
                } else if (config.isConfigured) {
                    if (prefix.endsWith("远程模型")) "${prefix}已就绪" else prefix
                } else {
                    "请配置远程模型"
                },
                modelHealth = ModelHealth(
                    profileId = config.modelProfile().id,
                    state = if (config.isConfigured) ModelHealthState.Loaded else ModelHealthState.LoadFailed,
                    failureReason = if (config.isConfigured) null else "远程模型未配置",
                ),
            )
        }
    }

    private fun buildRemoteModeDisclosure(config: RemoteModelConfig): PendingRemoteModeDisclosure {
        val normalized = config.normalized()
        return PendingRemoteModeDisclosure(
            remoteHost = normalized.destinationHostLabel(),
            remoteModelName = normalized.modelName.ifBlank { "未命名远程模型" },
            apiKeyConfigured = normalized.apiKey.isNotBlank(),
            connectivityStatus = normalized.connectivityStatus,
            supportsVisionInput = normalized.modelProfile().supportsVisionInput,
            isConfigured = normalized.isConfigured,
        )
    }

    fun refreshDeviceStatus() {
        uiState.update {
            it.copy(
                isArm64Supported = isArm64Device(),
                availableModelStorageBytes = modelRepository.resolveModelStorageBytes(),
            )
        }
    }

    private fun Throwable.cleanMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
