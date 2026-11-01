package com.bytedance.zgx.solin.presentation

import android.net.Uri
import android.app.DownloadManager
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL_BYTES
import com.bytedance.zgx.solin.data.ModelDownloadSource
import com.bytedance.zgx.solin.download.ModelDownloadClient
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.SemanticMemoryStatus
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.destinationHostLabel
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.modelProfile
import com.bytedance.zgx.solin.data.BundledModelInstaller
import com.bytedance.zgx.solin.data.FirstRunSetupStore
import com.bytedance.zgx.solin.data.GenerationParametersStore
import com.bytedance.zgx.solin.data.HuggingFaceAuthStore
import com.bytedance.zgx.solin.data.ModelRepositoryFacade
import com.bytedance.zgx.solin.data.ModelSelectionState
import com.bytedance.zgx.solin.data.ModelVerificationStatus
import com.bytedance.zgx.solin.data.RemoteModelStore
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.logging.solinE
import com.bytedance.zgx.solin.logging.solinI
import com.bytedance.zgx.solin.logging.SolinLogTags.TAG_MODEL
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeController
import com.bytedance.zgx.solin.memory.SemanticMemoryRuntimeStatus
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import com.bytedance.zgx.solin.runtime.RemoteConnectivityRefreshCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.bytedance.zgx.solin.logging.cleanMessage

/**
 * Owns local/remote model selection, download/import, HF auth, the first-run setup download
 * queue, bundled install, and model load readiness.
 */
internal class ModelLoadController(
    private val modelRepository: ModelRepositoryFacade,
    private val downloadService: ModelDownloadClient,
    private val runtime: LiteRtRuntime,
    private val remoteModelRepository: RemoteModelStore,
    private val huggingFaceAuthStore: HuggingFaceAuthStore,
    private val firstRunSetupRepository: FirstRunSetupStore,
    private val generationParametersRepository: GenerationParametersStore,
    private val sessionRepository: SessionStore,
    private val bundledModelInstaller: BundledModelInstaller,
    private val remoteConnectivityRefreshCoordinator: RemoteConnectivityRefreshCoordinator,
    private val semanticMemoryRuntimeController: SemanticMemoryRuntimeController?,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val runtimeLock: Mutex,
    private val isArm64DeviceProvider: () -> Boolean,
    private val skipStartupModelRuntimeWork: Boolean,
    private val autoInferenceAuthorizationCoordinator: AutoInferenceAuthorizationCoordinator,
    private val clearPendingRemoteState: () -> Unit,
    private val resetRemoteSendDisclosureSuppression: () -> Unit,
    private val rebuildMemoryIndex: () -> Unit,
) {
    private var bundledModelInstallJob: Job? = null
    private var remoteConnectivityUiJob: Job? = null
    private var downloadMonitorJob: Job? = null
    private var downloadPreflightJob: Job? = null
    private var activeDownloadId: Long? = null
    /** The running download's source; kept so the user can resume it over cellular data. */
    private var activeDownloadSource: ModelDownloadSource? = null

    /** Companion files (and further setup models) still to download after the current one. */
    private val setupDownloadQueue = ArrayDeque<ModelDownloadSource>()
    private var setupDownloadInProgress = false

    fun close() {
        downloadMonitorJob?.cancel()
        downloadPreflightJob?.cancel()
        remoteConnectivityUiJob?.cancel()
        bundledModelInstallJob?.cancel()
    }

    // Same read as MemoryController.semanticMemoryState(), without rebuilding the index.
    private fun semanticMemoryStatus(): SemanticMemoryStatus =
        SemanticMemoryStatus(
            enabled = semanticMemoryRuntimeController?.semanticMemoryEnabled == true,
            runtimeStatus = semanticMemoryRuntimeController?.semanticMemoryRuntimeStatus
                ?: SemanticMemoryRuntimeStatus.RuntimeUnavailable,
            indexedRecordCount = semanticMemoryRuntimeController?.semanticMemoryIndexedRecordCount ?: 0,
            lastRebuiltAtMillis = semanticMemoryRuntimeController?.semanticMemoryLastRebuiltAtMillis,
        )

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
        autoInferenceAuthorizationCoordinator.mutateRemoteConfig(
            block = { remoteModelRepository.saveConfigWithoutApiKey(config) },
            project = { result ->
                result.fold(
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
            },
        )
    }

    fun startModelDownload() {
        startDownloadQueue(ModelDownloadSource.recommendedBundle(modelRepository.selectedRecommendedModel()))
    }

    fun startRecommendedModelDownload(modelId: String) {
        val model = ModelCatalog.recommendedModelById(modelId)
        if (model.capability == ModelCapability.Chat) {
            updateModelState(modelRepository.selectRecommendedModel(model.id).state)
        }
        startDownloadQueue(ModelDownloadSource.recommendedBundle(model))
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
        if (uiState.value.isBusy || uiState.value.isDownloading) return
        uiState.update { state ->
            state.copy(
                setupSelectedModelIds = if (selected) {
                    state.setupSelectedModelIds + modelId
                } else {
                    state.setupSelectedModelIds - modelId
                },
            )
        }
    }

    fun startSetupModelDownload() {
        val state = uiState.value
        if (state.isBusy || state.isDownloading) return
        val selectedSources = state.basicSetupModels
            .filter { it.id in state.setupSelectedModelIds && !state.isModelInstalled(it.id) }
            .flatMap { ModelDownloadSource.recommendedBundle(it) }
        if (selectedSources.isEmpty()) {
            firstRunSetupRepository.markSetupDismissed()
            uiState.update {
                it.copy(
                    showFirstRunSetup = false,
                    statusText = if (it.modelPath == null) "可稍后在模型管理安装模型" else it.statusText,
                )
            }
            return
        }
        selectedSources.firstOrNull { source ->
            source.requiresHuggingFaceAuthorization && !huggingFaceAuthStore.hasAccessToken()
        }?.let { missingAuthSource ->
            blockDownloadForMissingHuggingFaceAuthorization(missingAuthSource)
            return
        }
        startDownloadQueue(selectedSources, firstRunSetup = true)
    }

    /**
     * Downloads [sources] in order. A running download keeps its own queue, and the new queue is
     * dropped if its first file cannot start, so no stale companion file is fetched later.
     */
    private fun startDownloadQueue(sources: List<ModelDownloadSource>, firstRunSetup: Boolean = false) {
        if (uiState.value.isBusy || uiState.value.isDownloading) return
        setupDownloadQueue.clear()
        sources.drop(1).forEach(setupDownloadQueue::add)
        if (firstRunSetup) setupDownloadInProgress = true
        if (!beginModelDownload(sources.first())) {
            setupDownloadQueue.clear()
            if (firstRunSetup) setupDownloadInProgress = false
        }
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

    /**
     * The user's explicit choice to finish a Wi-Fi-only download over cellular data. The paused
     * task is cancelled and re-enqueued with metered data allowed; nothing changes while on Wi-Fi.
     */
    fun allowMeteredDownload() {
        val downloadId = activeDownloadId ?: return
        val source = activeDownloadSource ?: return
        if (!uiState.value.isWaitingForWifiDownload) return
        downloadMonitorJob?.cancel()
        downloadMonitorJob = null
        downloadService.cancel(downloadId)
        clearActiveDownload()
        modelRepository.clearPendingDownload()
        uiState.update { it.copy(isDownloading = false, isWaitingForWifiDownload = false) }
        // Re-running the normal preflight re-checks space, auth and the target file; the cancel
        // above already removed the partial file. A refused preflight drops the queue with it.
        if (!beginModelDownload(source, allowMetered = true)) {
            clearSetupQueue()
        }
    }

    fun cancelModelDownload() {
        val downloadId = activeDownloadId ?: modelRepository.pendingDownloadId()
        downloadPreflightJob?.cancel()
        downloadPreflightJob = null
        downloadMonitorJob?.cancel()
        downloadMonitorJob = null
        activeDownloadId = null
        activeDownloadSource = null
        clearSetupQueue()
        downloadService.cancelPreflight()
        downloadService.cancel(downloadId)
        modelRepository.clearPendingDownload()
        uiState.update { it.downloadFinished("下载已取消").copy(isPreparingDownload = false) }
    }

    private fun clearSetupQueue() {
        setupDownloadQueue.clear()
        setupDownloadInProgress = false
    }

    private fun beginModelDownload(
        source: ModelDownloadSource,
        allowMetered: Boolean = false,
    ): Boolean {
        refreshDeviceStatus()
        if (uiState.value.isBusy || uiState.value.isDownloading) return false
        if (blockDownloadForMissingHuggingFaceAuthorization(source)) return false
        if (!isArm64DeviceProvider()) {
            uiState.update { it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型") }
            return false
        }

        val target = modelRepository.downloadedModelFile(source.fileName)
        if (target == null) {
            uiState.update { it.copy(statusText = "下载目录不可用，请导入已有模型") }
            return false
        }
        if (source.expectedBytes != null && target.exists() && source.hasExpectedSize(target.length())) {
            verifyAndRegisterDownloadedModel(target, source)
            return true
        }
        if (target.exists() && !target.delete()) {
            uiState.update { it.copy(statusText = "无法清理未完成的下载") }
            return false
        }
        val modelParent = target.parentFile
        if (modelParent == null || (!modelParent.exists() && !modelParent.mkdirs())) {
            uiState.update { it.copy(statusText = "无法创建模型下载目录") }
            return false
        }
        val requiredBytes = source.expectedBytes ?: DEFAULT_CHAT_MODEL_BYTES
        if (!ModelCatalog.hasEnoughSpace(modelParent.usableSpace, requiredBytes)) {
            uiState.update {
                it.copy(statusText = "存储空间不足，至少需要约 ${ModelCatalog.formatBytes(requiredBytes)}")
            }
            return false
        }

        if (!setupDownloadInProgress) {
            firstRunSetupRepository.markSetupDismissed()
        }
        uiState.update {
            it.withDownloadProgressReset().copy(
                isPreparingDownload = true,
                isDownloading = false,
                isWaitingForWifiDownload = false,
                statusText = if (source.requiresHuggingFaceAuthorization) {
                    "正在验证 Hugging Face 授权"
                } else {
                    "正在准备模型下载"
                },
                isReady = false,
                showFirstRunSetup = false,
            )
        }
        enqueueModelDownload(source, target, allowMetered)
        return true
    }

    /**
     * Starts one download task. Downloading is a background activity: it sets the download flags
     * but not `isBusy`, which stays reserved for work that owns the model (loading, importing,
     * deleting, generating). Every guard that must still block during a download checks
     * `isDownloading` too.
     */
    private fun enqueueModelDownload(
        source: ModelDownloadSource,
        target: File,
        allowMetered: Boolean,
    ) {
        val preflightJob = scope.launch(ioDispatcher) {
            val downloadResult = downloadService.enqueue(source, target, allowMetered)
            if (!isActive) {
                downloadResult.getOrNull()?.let(downloadService::cancel)
                return@launch
            }
            downloadPreflightJob = null
            val downloadId = downloadResult.getOrElse { throwable ->
                clearSetupQueue()
                uiState.update {
                    it.downloadFinished("下载启动失败：${throwable.cleanMessage()}").copy(isPreparingDownload = false)
                }
                return@launch
            }
            activeDownloadId = downloadId
            activeDownloadSource = source
            modelRepository.savePendingDownload(downloadId, source)
            uiState.update {
                it.withDownloadProgressReset().copy(
                    isPreparingDownload = false,
                    isDownloading = true,
                    isWaitingForWifiDownload = false,
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
    }

    private fun blockDownloadForMissingHuggingFaceAuthorization(source: ModelDownloadSource): Boolean {
        if (!source.requiresHuggingFaceAuthorization || huggingFaceAuthStore.hasAccessToken()) return false
        clearSetupQueue()
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
        activeDownloadSource = source
        downloadMonitorJob = scope.launch(ioDispatcher) {
            while (isActive && activeDownloadId == downloadId) {
                val info = downloadService.query(downloadId)
                if (info == null) {
                    if (activeDownloadId == downloadId) {
                        clearActiveDownload()
                        clearSetupQueue()
                        modelRepository.clearPendingDownload()
                        uiState.update { it.downloadStopped("下载任务不存在") }
                    }
                    return@launch
                }
                if (!isActive || activeDownloadId != downloadId) {
                    return@launch
                }

                uiState.update {
                    it.copy(
                        isDownloading = true,
                        isWaitingForWifiDownload = info.isWaitingForWifi,
                        downloadProgressPercent = info.progressPercent,
                        downloadedBytes = info.downloadedBytes,
                        totalBytes = info.totalBytes,
                        statusText = if (info.isWaitingForWifi) {
                            "等待 Wi-Fi，可改用移动数据"
                        } else {
                            info.statusText
                        },
                    )
                }

                when (info.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        clearActiveDownload()
                        if (!targetFile.exists() || !source.hasExpectedSize(targetFile.length())) {
                            clearSetupQueue()
                            modelRepository.clearPendingDownload()
                            targetFile.delete()
                            uiState.update { it.downloadStopped("下载文件不可用，请重新下载") }
                            return@launch
                        }
                        // Still a download-owned state: the file is being hashed and registered.
                        uiState.update {
                            it.copy(
                                isDownloading = true,
                                isWaitingForWifiDownload = false,
                                downloadProgressPercent = null,
                                statusText = "正在校验模型文件",
                            )
                        }
                        modelRepository.clearPendingDownload()
                        if (!registerVerifiedModel(targetFile, source)) return@launch
                        updateModelState(modelRepository.currentState())
                        uiState.update { it.downloadFinished("模型下载完成") }
                        continueSetupDownloadOrLoad(source)
                        return@launch
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val restoreFirstRunSetup = setupDownloadInProgress &&
                            uiState.value.modelPath == null &&
                            !uiState.value.isReady
                        clearActiveDownload()
                        clearSetupQueue()
                        modelRepository.clearPendingDownload()
                        targetFile.delete()
                        uiState.update {
                            it.downloadFinished(downloadFailureStatusText(info.reasonText, source))
                                .copy(showFirstRunSetup = restoreFirstRunSetup)
                        }
                        return@launch
                    }
                }

                delay(1_000L)
            }
        }
    }

    private fun clearActiveDownload() {
        activeDownloadId = null
        activeDownloadSource = null
    }

    private fun verifyAndRegisterDownloadedModel(
        targetFile: File,
        source: ModelDownloadSource,
    ) {
        // Hashing a file already on disk is still download-owned work, like the post-download
        // check: it keeps isDownloading so model guards hold, but never takes over isBusy.
        uiState.update {
            it.copy(
                isDownloading = true,
                downloadProgressPercent = null,
                statusText = "正在校验模型文件",
                isReady = false,
            )
        }
        scope.launch(ioDispatcher) {
            if (!registerVerifiedModel(targetFile, source)) return@launch
            firstRunSetupRepository.markSetupDismissed()
            updateModelState(modelRepository.currentState())
            uiState.update { it.downloadFinished("模型校验通过").copy(showFirstRunSetup = false) }
            continueSetupDownloadOrLoad(source)
        }
    }

    /**
     * Checks [targetFile] against [source] and registers it when the source is an installable
     * model. On a failed check the file is deleted, the queue dropped, and false is returned.
     */
    private fun registerVerifiedModel(targetFile: File, source: ModelDownloadSource): Boolean {
        val verifiedSha256 = source.verifiedSha256(targetFile).getOrElse { throwable ->
            clearSetupQueue()
            targetFile.delete()
            uiState.update { it.downloadFinished(throwable.cleanMessage()) }
            return false
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
        return true
    }

    private fun continueSetupDownloadOrLoad(completedSource: ModelDownloadSource) {
        if (setupDownloadQueue.isNotEmpty()) {
            if (!beginModelDownload(setupDownloadQueue.removeFirst())) {
                clearSetupQueue()
            }
            return
        }

        if (setupDownloadInProgress) {
            setupDownloadInProgress = false
            uiState.update { it.copy(statusText = "基础能力包已准备") }
        }
        rebuildMemoryIndex()
        if (uiState.value.modelPath != null && !uiState.value.isReady) {
            loadModel()
        }
        val completedChatModel = completedSource.modelId
            ?.let { ModelCatalog.recommendedModelById(it).capability == ModelCapability.Chat }
            ?: true
        if (completedChatModel || uiState.value.modelPath != null) {
            firstRunSetupRepository.markSetupDismissed()
            uiState.update { it.copy(showFirstRunSetup = false) }
        }
    }

    private fun downloadFailureStatusText(reasonText: String, source: ModelDownloadSource): String =
        if (source.requiresHuggingFaceAuthorization) {
            "Hugging Face 下载失败：请确认已登录、已接受模型许可，且 read token 有效（$reasonText）"
        } else {
            "下载失败：$reasonText"
        }

    fun importModel(uri: Uri) {
        refreshDeviceStatus()
        if (uiState.value.isBusy || uiState.value.isDownloading) return
        if (!isArm64DeviceProvider()) {
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
                    // Import owns isBusy (it closed the runtime), so its failure ends it explicitly.
                    uiState.update { it.downloadFinished("导入失败：${throwable.cleanMessage()}").copy(isBusy = false) }
                },
            )
        }
    }

    fun selectBackend(choice: BackendChoice) {
        if (uiState.value.isBusy || uiState.value.isDownloading || uiState.value.backend == choice) return
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
        // Editing the remote endpoint does not touch the downloading local model, so it stays
        // allowed during a download; it is still blocked while a run owns isBusy.
        if (uiState.value.isBusy) return
        clearPendingRemoteState()
        remoteConnectivityUiJob?.cancel()
        // Trust boundary changed (remote destination/credential): never let a prior
        // session-scoped "don't ask again" carry over to a new remote endpoint.
        resetRemoteSendDisclosureSuppression()
        val previousConfig = uiState.value.remoteModelConfig
        val configToSave = config.normalized()
        autoInferenceAuthorizationCoordinator.mutateRemoteConfig(
            block = { remoteModelRepository.saveConfig(configToSave) },
            project = { result ->
                result.fold(
                    onSuccess = { normalized ->
                        val persistedMode = remoteModelRepository.loadMode()
                        uiState.update {
                            it.copy(
                                remoteModelConfig = normalized,
                                inferenceMode = persistedMode,
                                pendingRemoteModeDisclosure = null,
                                pendingRemoteSendDisclosure = null,
                                isReady = if (persistedMode == InferenceMode.Local) {
                                    runtime.isLoaded
                                } else {
                                    it.isReady
                                },
                                showFirstRunSetup = if (normalized.isConfigured) false else it.showFirstRunSetup,
                                statusText = if (
                                    it.inferenceMode == InferenceMode.Auto &&
                                    persistedMode == InferenceMode.Local
                                ) {
                                    "远程配置已更新，自动模式已关闭"
                                } else {
                                    it.statusText
                                },
                            )
                        }
                        if (normalized.isConfigured) {
                            firstRunSetupRepository.markSetupDismissed()
                            refreshRemoteConnectivityIfStale()
                        }
                        if (persistedMode == InferenceMode.Remote) {
                            updateRemoteReadiness("远程模型")
                        }
                    },
                    onFailure = { throwable ->
                        uiState.update {
                            it.copy(
                                remoteModelConfig = previousConfig,
                                pendingRemoteModeDisclosure = null,
                                pendingRemoteSendDisclosure = null,
                                statusText = "远程配置保存失败：${throwable.cleanMessage()}",
                            )
                        }
                    },
                )
            },
        )
    }

    fun testRemoteModelConnectivity() {
        if (uiState.value.isBusy) return
        val config = remoteModelRepository.loadConfig().normalized()
        if (!config.isConfigured) {
            uiState.update {
                it.copy(
                    remoteModelConfig = config.copy(connectivityStatus = RemoteModelConnectivityStatus.Unknown),
                    statusText = "请先填写有效远程配置",
                )
            }
            return
        }
        observeRemoteConnectivity(config, force = true, announce = true)
    }

    fun refreshRemoteConnectivityIfStale() {
        val config = remoteModelRepository.loadConfig().normalized()
        if (!config.isConfigured || config.profileRevision.isBlank()) return
        observeRemoteConnectivity(config, force = false, announce = false)
    }

    private fun observeRemoteConnectivity(
        config: RemoteModelConfig,
        force: Boolean,
        announce: Boolean,
    ) {
        val revision = config.profileRevision
        if (announce) {
            uiState.update {
                it.copy(
                    remoteModelConfig = config.copy(
                        connectivityStatus = RemoteModelConnectivityStatus.Checking,
                    ),
                    statusText = "正在测试远程连接",
                )
            }
        }
        val refresh = remoteConnectivityRefreshCoordinator.refresh(config, force)
        remoteConnectivityUiJob?.cancel()
        remoteConnectivityUiJob = scope.launch(ioDispatcher) {
            val snapshot = try {
                refresh.await()
            } catch (cancelled: CancellationException) {
                return@launch
            }
            val currentConfig = remoteModelRepository.loadConfig().normalized()
            if (currentConfig.profileRevision != revision) return@launch
            val status = snapshot?.status ?: RemoteModelConnectivityStatus.Unknown
            uiState.update {
                it.copy(
                    remoteModelConfig = currentConfig.copy(connectivityStatus = status),
                    statusText = if (announce) "远程连接${status.label}" else it.statusText,
                )
            }
        }
    }

    fun selectRecommendedModel(modelId: String) {
        if (uiState.value.isBusy || uiState.value.isDownloading || uiState.value.selectedModelId == modelId) return
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
        if (uiState.value.isBusy || uiState.value.isDownloading ||
            uiState.value.activeInstalledModelId == modelId
        ) {
            return
        }
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
        // Downloading must not be replaced by a load: the in-flight file is not installed yet.
        if (uiState.value.isBusy || uiState.value.isDownloading) return
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
                modelHealth = it.localModelHealth(ModelHealthState.Loading, backendChoice),
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
                        modelHealth = it.localModelHealth(
                            ModelHealthState.LoadFailed,
                            backendChoice,
                            failureReason = "模型文件校验失败",
                        ),
                    )
                }
                return@launch
            }

            uiState.update {
                it.loadSettled(
                    isReady = false,
                    statusText = "正在初始化 ${backendChoice.label()}",
                    modelHealth = it.localModelHealth(ModelHealthState.Loading, backendChoice),
                ).copy(
                    inferenceMode = InferenceMode.Local,
                    backend = backendChoice,
                    isBusy = true,
                )
            }

            val runtimeCapabilities = localModelRuntimeCapabilitiesFor(uiState.value)
            val loadOn: suspend (BackendChoice) -> Result<Unit> = { backend ->
                runCatching {
                    runtimeLock.withLock {
                        runtime.configureModelCapabilities(runtimeCapabilities)
                        runtime.load(
                            modelPath = path,
                            backend = backend,
                            history = sessionRepository.activeMessages(),
                            parameters = uiState.value.generationParameters,
                        )
                    }
                }
            }
            val loadStartMs = System.currentTimeMillis()
            val failure = loadOn(backendChoice).exceptionOrNull()
            if (failure == null) {
                val durationMs = System.currentTimeMillis() - loadStartMs
                solinI(TAG_MODEL, "loadModel: success in ${durationMs}ms backend=${backendChoice.name}")
                uiState.update {
                    it.loadSettled(
                        isReady = true,
                        statusText = "就绪 · ${it.backend.label()}",
                        modelHealth = it.localModelHealth(ModelHealthState.Loaded, backendChoice),
                    )
                }
                rebuildMemoryIndex()
                return@launch
            }
            solinE(TAG_MODEL, "loadModel: failed backend=${backendChoice.name}", failure)
            var cpuFailure: Throwable? = null
            if (backendChoice == BackendChoice.GPU &&
                backendAllowedForActiveModel(uiState.value, BackendChoice.CPU)
            ) {
                cpuFailure = loadOn(BackendChoice.CPU).exceptionOrNull()
                if (cpuFailure == null) {
                    uiState.update {
                        it.loadSettled(
                            isReady = true,
                            statusText = "GPU 不可用，已切到 CPU",
                            modelHealth = it.localModelHealth(
                                ModelHealthState.FallbackActive,
                                BackendChoice.CPU,
                                failureReason = "GPU 初始化失败：${failure.cleanMessage()}",
                            ).copy(fallbackBackend = BackendChoice.CPU),
                        ).copy(backend = BackendChoice.CPU)
                    }
                    rebuildMemoryIndex()
                    return@launch
                }
            }
            val failureReason = cpuFailure?.let { cpuThrowable ->
                "GPU: ${failure.cleanMessage()}；CPU: ${cpuThrowable.cleanMessage()}"
            } ?: failure.cleanMessage()
            uiState.update {
                it.loadSettled(
                    isReady = false,
                    statusText = "初始化失败：$failureReason",
                    modelHealth = it.localModelHealth(
                        ModelHealthState.LoadFailed,
                        backendChoice,
                        failureReason = failureReason,
                    ),
                )
            }
        }
    }

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
                semanticMemory = semanticMemoryStatus(),
                huggingFaceAccessTokenConfigured = huggingFaceAuthStore.hasAccessToken(),
            )
        }
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

    /**
     * Projects an already-committed Remote mode. A switch *to* Remote raises its disclosure in
     * `InferenceModeController` before this runs, so there is no unconfirmed Remote projection here.
     */
    fun updateRemoteReadiness(prefix: String) {
        val config = uiState.value.remoteModelConfig
        clearPendingRemoteState()
        uiState.update {
            it.copy(
                inferenceMode = InferenceMode.Remote,
                pendingRemoteModeDisclosure = null,
                pendingRemoteSendDisclosure = null,
                // Projecting remote readiness owns neither a run's busy state nor a background
                // download, so it leaves isBusy / isDownloading as they are.
                isReady = config.isConfigured,
                statusText = if (config.isConfigured) {
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

    fun remoteModeDisclosure(): PendingRemoteModeDisclosure =
        buildRemoteModeDisclosure(remoteModelRepository.loadConfig())

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
                isArm64Supported = isArm64DeviceProvider(),
                availableModelStorageBytes = modelRepository.resolveModelStorageBytes(),
            )
        }
    }
}

private fun ChatUiState.localModelHealth(
    state: ModelHealthState,
    backend: BackendChoice,
    failureReason: String? = null,
): ModelHealth =
    ModelHealth(
        profileId = activeModelProfileId(),
        state = state,
        backend = backend,
        failureReason = failureReason,
    )

/** A local load step finished: not busy, no download progress, with the given readiness. */
private fun ChatUiState.loadSettled(
    isReady: Boolean,
    statusText: String,
    modelHealth: ModelHealth,
): ChatUiState =
    copy(
        isBusy = false,
        isDownloading = false,
        isReady = isReady,
        downloadProgressPercent = null,
        downloadedBytes = 0L,
        totalBytes = 0L,
        statusText = statusText,
        modelHealth = modelHealth,
    )

private fun ChatUiState.withDownloadProgressReset(): ChatUiState =
    copy(downloadProgressPercent = null, downloadedBytes = 0L, totalBytes = 0L)

/**
 * Ends the download state but keeps the last byte counts on screen. A download never owns
 * `isBusy` (a chat may be generating meanwhile), so ending it must not clear `isBusy` either.
 */
private fun ChatUiState.downloadStopped(statusText: String): ChatUiState =
    copy(
        isDownloading = false,
        isWaitingForWifiDownload = false,
        downloadProgressPercent = null,
        statusText = statusText,
    )

/** Clears all download progress and ends the download state. */
internal fun ChatUiState.downloadFinished(statusText: String): ChatUiState =
    withDownloadProgressReset().downloadStopped(statusText)
