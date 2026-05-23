package com.bytedance.zgx.gemmalocalqa

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "gemma_local_qa"
private const val PREF_MODEL_PATH = "model_path"
private const val PREF_DOWNLOAD_ID = "download_id"
private const val PREF_DOWNLOAD_SOURCE = "download_source"
private const val PREF_SELECTED_MODEL_ID = "selected_model_id"
private const val PREF_INSTALLED_MODELS_JSON = "installed_models_json"
private const val PREF_ACTIVE_INSTALLED_MODEL_ID = "active_installed_model_id"
private const val PREF_SESSIONS_JSON = "sessions_json"
private const val PREF_ACTIVE_SESSION_ID = "active_session_id"
private val nextMessageId = AtomicLong(0L)

enum class BackendChoice {
    CPU,
    GPU,
}

enum class MessageRole {
    User,
    Assistant,
}

data class ChatMessage(
    val role: MessageRole,
    val text: String,
    val id: Long = nextMessageId.incrementAndGet(),
)

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val messageCount: Int,
)

data class InstalledModelSummary(
    val id: String,
    val displayName: String,
    val path: String,
    val fileBytes: Long,
    val recommendedModelId: String?,
) {
    val fileName: String
        get() = File(path).name
}

data class ChatUiState(
    val modelPath: String? = null,
    val activeInstalledModelId: String? = null,
    val installedModels: List<InstalledModelSummary> = emptyList(),
    val selectedModelId: String = GEMMA_DEFAULT_RECOMMENDED_MODEL_ID,
    val recommendedModels: List<RecommendedModel> = GEMMA_RECOMMENDED_MODELS,
    val backend: BackendChoice = BackendChoice.GPU,
    val statusText: String = "未加载模型",
    val isArm64Supported: Boolean = true,
    val availableModelStorageBytes: Long = 0L,
    val isBusy: Boolean = false,
    val isGenerating: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val isReady: Boolean = false,
    val sessions: List<ChatSessionSummary> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
) {
    val selectedRecommendedModel: RecommendedModel
        get() = GemmaModelRules.recommendedModelById(selectedModelId)
}

private data class ChatSession(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val messages: List<ChatMessage>,
)

private data class InstalledModel(
    val id: String,
    val displayName: String,
    val path: String,
    val fileBytes: Long,
    val recommendedModelId: String?,
)

private data class ModelDownloadSource(
    val title: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long?,
    val modelId: String?,
) {
    companion object {
        fun recommended(model: RecommendedModel): ModelDownloadSource =
            ModelDownloadSource(
                title = model.shortName,
                fileName = model.fileName,
                downloadUrl = model.downloadUrl,
                expectedBytes = model.byteSize,
                modelId = model.id,
            )
    }
}

class GemmaChatViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelDir = File(appContext.filesDir, "models")
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private var selectedModelId = prefs.getString(PREF_SELECTED_MODEL_ID, GEMMA_DEFAULT_RECOMMENDED_MODEL_ID)
        ?: GEMMA_DEFAULT_RECOMMENDED_MODEL_ID
    private var installedModels: List<InstalledModel> = loadInstalledModels()
    private var activeInstalledModelId: String? = resolveActiveInstalledModelId(installedModels)
    private var sessions: List<ChatSession> = loadSessions()
    private var activeSessionId: String = resolveActiveSessionId(sessions)

    private val _uiState = MutableStateFlow(
        ChatUiState(
            modelPath = activeInstalledModel()?.path,
            activeInstalledModelId = activeInstalledModelId,
            installedModels = installedModelSummaries(),
            selectedModelId = selectedModelId,
            sessions = sessionSummaries(),
            activeSessionId = activeSessionId,
            messages = activeSessionMessages(),
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val runtimeLock = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var generationJob: Job? = null

    init {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        cleanTemporaryModelFiles()
        refreshDeviceStatus()
        if (installedModels.isNotEmpty()) {
            persistInstalledModels()
        }
        val pendingDownloadId = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        val pendingDownloadSource = loadPendingDownloadSource()
        if (!isArm64Device()) {
            _uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
        } else if (pendingDownloadId > 0L) {
            val source = pendingDownloadSource ?: ModelDownloadSource.recommended(selectedRecommendedModel())
            source.modelId?.let { selectedModelId = it }
            val target = downloadedModelFile(source.fileName)
            if (target == null) {
                prefs.edit().remove(PREF_DOWNLOAD_ID).remove(PREF_DOWNLOAD_SOURCE).apply()
                _uiState.update {
                    it.copy(statusText = "下载目录不可用，请导入已有模型")
                }
            } else {
                _uiState.update { it.copy(selectedModelId = selectedModelId) }
                monitorDownload(pendingDownloadId, target, source)
            }
        } else {
            val activeModel = activeInstalledModel()
            activeModel?.recommendedModelId?.let { selectedModelId = it }
            _uiState.update {
                it.copy(
                    modelPath = activeModel?.path,
                    activeInstalledModelId = activeInstalledModelId,
                    installedModels = installedModelSummaries(),
                    selectedModelId = selectedModelId,
                    statusText = if (activeModel == null) it.statusText else "已找到模型，正在加载",
                )
            }
            if (activeModel != null) {
                loadModel()
            }
        }
    }

    fun startModelDownload() {
        beginModelDownload(ModelDownloadSource.recommended(selectedRecommendedModel()))
    }

    fun startCustomModelDownload(downloadUrl: String) {
        val source = createCustomDownloadSource(downloadUrl)
        if (source == null) {
            _uiState.update {
                it.copy(statusText = "请输入有效的 http/https 模型下载链接")
            }
            return
        }
        beginModelDownload(source)
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

        val target = downloadedModelFile(source.fileName)
        if (target == null) {
            _uiState.update {
                it.copy(statusText = "下载目录不可用，请导入已有模型")
            }
            return
        }
        if (source.expectedBytes != null && target.exists() && source.isCompleteFile(target.length())) {
            registerInstalledModel(
                path = target.absolutePath,
                displayName = source.installedDisplayName(target),
                recommendedModelId = source.modelId ?: inferRecommendedModelId(target.name),
            )
            loadModel()
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
        val requiredBytes = source.expectedBytes ?: GEMMA_RECOMMENDED_MODEL_BYTES
        if (!GemmaModelRules.hasEnoughSpace(modelParent.usableSpace, requiredBytes)) {
            _uiState.update {
                it.copy(statusText = "存储空间不足，至少需要约 ${GemmaModelRules.formatBytes(requiredBytes)}")
            }
            return
        }
        val request = DownloadManager.Request(Uri.parse(source.downloadUrl))
            .setTitle(source.title)
            .setDescription("正在下载本地模型")
            .setMimeType("application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                source.fileName,
            )

        val downloadId = runCatching { downloadManager.enqueue(request) }.getOrElse { throwable ->
            _uiState.update {
                it.copy(statusText = "下载启动失败：${throwable.cleanMessage()}")
            }
            return
        }

        prefs.edit()
            .putLong(PREF_DOWNLOAD_ID, downloadId)
            .putString(PREF_DOWNLOAD_SOURCE, source.toJson().toString())
            .apply()
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

    fun cancelModelDownload() {
        val downloadId = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (downloadId > 0L) {
            downloadManager.remove(downloadId)
        }
        prefs.edit().remove(PREF_DOWNLOAD_ID).remove(PREF_DOWNLOAD_SOURCE).apply()
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
                withContext(Dispatchers.IO) {
                    closeRuntime()
                    prefs.edit().remove(PREF_DOWNLOAD_ID).remove(PREF_DOWNLOAD_SOURCE).apply()
                    modelDir.mkdirs()
                    val displayName = resolveDisplayName(uri)
                    require(GemmaModelRules.isAcceptedModelName(displayName)) {
                        "请选择 $GEMMA_MODEL_EXTENSION 模型文件"
                    }
                    val sourceSize = resolveFileSize(uri)
                    if (sourceSize > 0L && !GemmaModelRules.hasEnoughSpace(modelDir.usableSpace, sourceSize)) {
                        error("存储空间不足，导入需要 ${GemmaModelRules.formatBytes(sourceSize)}")
                    }
                    val target = File(modelDir, GemmaModelRules.sanitizeModelName(displayName))
                    val tempPrefix = target.nameWithoutExtension.takeIf { it.length >= 3 } ?: "model"
                    val tempTarget = File.createTempFile(tempPrefix, ".tmp", modelDir)
                    try {
                        copyUriToFile(uri, tempTarget)
                        check(tempTarget.length() > 0L) { "模型文件为空" }
                        val moved = runCatching {
                            Files.move(
                                tempTarget.toPath(),
                                target.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }.recoverCatching {
                            Files.move(
                                tempTarget.toPath(),
                                target.toPath(),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                        if (moved.isFailure) {
                            error("无法保存模型文件")
                        }
                    } catch (throwable: Throwable) {
                        tempTarget.delete()
                        throw throwable
                    }
                    val recommendedId = inferRecommendedModelId(target.name)
                    registerInstalledModel(
                        path = target.absolutePath,
                        displayName = recommendedId
                            ?.let { GemmaModelRules.recommendedModelById(it).shortName }
                            ?: target.nameWithoutExtension,
                        recommendedModelId = recommendedId,
                    )
                    target.absolutePath
                }
            }

            result.fold(
                onSuccess = { path ->
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
        _uiState.update {
            it.copy(
                backend = choice,
                isReady = false,
                statusText = "已切换到 ${choice.label()}，点击加载模型",
            )
        }
    }

    fun selectRecommendedModel(modelId: String) {
        if (_uiState.value.isBusy || selectedModelId == modelId) return
        selectedModelId = GemmaModelRules.recommendedModelById(modelId).id
        prefs.edit().putString(PREF_SELECTED_MODEL_ID, selectedModelId).apply()
        val installed = installedModels.firstOrNull {
            it.recommendedModelId == selectedModelId && File(it.path).exists()
        }
        if (installed != null) {
            activateInstalledModel(installed, "已切换到 ${installed.displayName}，点击加载模型")
        } else {
            _uiState.update {
                it.copy(
                    selectedModelId = selectedModelId,
                    statusText = "已选择 ${selectedRecommendedModel().shortName}",
                )
            }
        }
        refreshDeviceStatus()
    }

    fun selectInstalledModel(modelId: String) {
        if (_uiState.value.isBusy || activeInstalledModelId == modelId) return
        val installed = installedModels.firstOrNull { it.id == modelId && File(it.path).exists() } ?: return
        activateInstalledModel(installed, "已切换到 ${installed.displayName}，点击加载模型")
    }

    fun loadModel() {
        val path = _uiState.value.modelPath ?: return
        if (_uiState.value.isBusy) return
        val backendChoice = _uiState.value.backend

        _uiState.update {
            it.copy(
                isBusy = true,
                isDownloading = false,
                isReady = false,
                downloadProgressPercent = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusText = "正在初始化 ${backendChoice.label()}",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                runtimeLock.withLock {
                    initializeRuntime(path, backendChoice)
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
                                initializeRuntime(path, BackendChoice.CPU)
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
        val created = newChatSession()
        sessions = listOf(created) + sessions
        activeSessionId = created.id
        persistSessions()
        _uiState.update {
            it.copy(
                sessions = sessionSummaries(),
                activeSessionId = activeSessionId,
                messages = emptyList(),
                statusText = if (engine == null) "新会话" else "正在开启新会话",
            )
        }
        if (engine != null) {
            recreateConversationForActiveSession("新会话")
        }
    }

    fun selectSession(sessionId: String) {
        if (_uiState.value.isBusy || activeSessionId == sessionId) return
        val session = sessions.firstOrNull { it.id == sessionId } ?: return
        activeSessionId = session.id
        prefs.edit().putString(PREF_ACTIVE_SESSION_ID, activeSessionId).apply()
        _uiState.update {
            it.copy(
                activeSessionId = activeSessionId,
                messages = session.messages,
                statusText = if (engine == null) "已切换会话" else "正在恢复会话",
            )
        }
        if (engine != null) {
            recreateConversationForActiveSession("已恢复会话")
        }
    }

    fun deleteActiveSession() {
        if (_uiState.value.isBusy || sessions.size <= 1) return
        sessions = sessions.filterNot { it.id == activeSessionId }
        activeSessionId = resolveActiveSessionId(sessions)
        persistSessions()
        _uiState.update {
            it.copy(
                sessions = sessionSummaries(),
                activeSessionId = activeSessionId,
                messages = activeSessionMessages(),
                statusText = if (engine == null) "已删除会话" else "正在恢复会话",
            )
        }
        if (engine != null) {
            recreateConversationForActiveSession("已删除会话")
        }
    }

    fun sendMessage(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || !_uiState.value.isReady || _uiState.value.isBusy || generationJob?.isActive == true) {
            return
        }

        val userMessage = ChatMessage(MessageRole.User, trimmed)
        val assistantPlaceholder = ChatMessage(MessageRole.Assistant, "")
        val nextMessages = _uiState.value.messages + userMessage + assistantPlaceholder
        replaceActiveSessionMessages(nextMessages, persistNow = true)
        _uiState.update {
            it.copy(
                isBusy = true,
                isGenerating = true,
                statusText = "生成中",
            )
        }

        val job = viewModelScope.launch(Dispatchers.IO) {
            val activeConversation = conversation
            if (activeConversation == null) {
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

            try {
                val partial = StringBuilder()
                activeConversation.sendMessageAsync(trimmed).collect { chunk ->
                    partial.append(chunk.textContent().ifBlank { chunk.toString() })
                    _uiState.updateLastAssistant(partial.toString())
                }
                if (partial.isBlank()) {
                    _uiState.updateLastAssistant("没有生成内容")
                }
                persistActiveSessionFromUi()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = true,
                        statusText = "就绪 · ${it.backend.label()}",
                    )
                }
            } catch (cancellation: CancellationException) {
                if (_uiState.value.isGenerating) {
                    finishStoppedGeneration()
                }
                throw cancellation
            } catch (throwable: Throwable) {
                _uiState.updateLastAssistant("出错了：${throwable.cleanMessage()}")
                persistActiveSessionFromUi()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isGenerating = false,
                        isReady = false,
                        statusText = "生成失败，建议重新加载",
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
        job.cancel()
        finishStoppedGeneration()
    }

    override fun onCleared() {
        closeRuntime()
        super.onCleared()
    }

    private fun defaultConversationConfig(messages: List<ChatMessage> = activeSessionMessages()): ConversationConfig =
        ConversationConfig(
            systemInstruction = Contents.of("你是一个简洁、可靠的中文问答助手。回答要直接，必要时说明不确定性。"),
            initialMessages = messages.toLiteRtInitialMessages(),
            samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = 0.7,
            ),
        )

    private fun initializeRuntime(path: String, backendChoice: BackendChoice) {
        closeRuntime()
        val createdEngine = Engine(
            EngineConfig(
                modelPath = path,
                backend = backendChoice.toLiteRtBackend(),
                cacheDir = appContext.cacheDir.absolutePath,
            ),
        )
        try {
            createdEngine.initialize()
            val createdConversation = createdEngine.createConversation(defaultConversationConfig())
            engine = createdEngine
            conversation = createdConversation
        } catch (throwable: Throwable) {
            runCatching { createdEngine.close() }
            throw throwable
        }
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        val total = resolveFileSize(uri)
        _uiState.update {
            it.copy(
                downloadProgressPercent = if (total > 0L) 0 else null,
                downloadedBytes = 0L,
                totalBytes = total,
                statusText = "正在导入模型",
            )
        }
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                var lastReported = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (copied - lastReported >= 1_048_576L || copied == total) {
                        lastReported = copied
                        val progress = GemmaModelRules.progressPercent(copied, total)
                        _uiState.update {
                            it.copy(
                                downloadProgressPercent = progress,
                                downloadedBytes = copied,
                                totalBytes = total,
                            )
                        }
                    }
                }
            }
        } ?: error("无法读取模型文件")
    }

    private fun resolveFileSize(uri: Uri): Long {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            val columnIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (columnIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getLong(columnIndex).coerceAtLeast(0L)
            }
        }
        return 0L
    }

    private fun monitorDownload(
        downloadId: Long,
        targetFile: File,
        source: ModelDownloadSource,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val info = queryDownload(downloadId)
                if (info == null) {
                    prefs.edit().remove(PREF_DOWNLOAD_ID).remove(PREF_DOWNLOAD_SOURCE).apply()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isDownloading = false,
                            downloadProgressPercent = null,
                            statusText = "下载任务不存在",
                        )
                    }
                    return@launch
                }

                val progress = GemmaModelRules.progressPercent(info.downloadedBytes, info.totalBytes)

                _uiState.update {
                    it.copy(
                        isBusy = true,
                        isDownloading = true,
                        downloadProgressPercent = progress,
                        downloadedBytes = info.downloadedBytes,
                        totalBytes = info.totalBytes,
                        statusText = info.statusText,
                    )
                }

                when (info.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if (!targetFile.exists() || !source.isCompleteFile(targetFile.length())) {
                            prefs.edit().remove(PREF_DOWNLOAD_ID).remove(PREF_DOWNLOAD_SOURCE).apply()
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
                        prefs.edit()
                            .remove(PREF_DOWNLOAD_ID)
                            .remove(PREF_DOWNLOAD_SOURCE)
                            .apply()
                        registerInstalledModel(
                            path = targetFile.absolutePath,
                            displayName = source.installedDisplayName(targetFile),
                            recommendedModelId = source.modelId ?: inferRecommendedModelId(targetFile.name),
                        )
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                isDownloading = false,
                                downloadProgressPercent = 100,
                                statusText = "模型下载完成",
                            )
                        }
                        loadModel()
                        return@launch
                    }

                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove(PREF_DOWNLOAD_ID).remove(PREF_DOWNLOAD_SOURCE).apply()
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

    private fun queryDownload(downloadId: Long): DownloadInfo? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return DownloadInfo(
                status = status,
                reason = reason,
                downloadedBytes = downloaded,
                totalBytes = total,
            )
        }
        return null
    }

    private fun defaultDownloadedModelFile(): File? =
        downloadedModelFile(selectedRecommendedModel().fileName)

    private fun downloadedModelFile(fileName: String): File? =
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, fileName) }

    private fun refreshDeviceStatus() {
        _uiState.update {
            it.copy(
                isArm64Supported = isArm64Device(),
                availableModelStorageBytes = resolveModelStorageBytes(),
            )
        }
    }

    private fun resolveModelStorageBytes(): Long {
        val downloadParent = defaultDownloadedModelFile()?.parentFile
        return when {
            downloadParent == null -> appContext.filesDir.usableSpace
            downloadParent.exists() -> downloadParent.usableSpace
            downloadParent.mkdirs() -> downloadParent.usableSpace
            else -> appContext.filesDir.usableSpace
        }
    }

    private fun cleanTemporaryModelFiles() {
        modelDir.listFiles { file ->
            file.isFile && file.extension == "tmp"
        }?.forEach { it.delete() }
    }

    private fun isArm64Device(): Boolean =
        Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }

    private fun resolveDisplayName(uri: Uri): String {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (columnIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(columnIndex)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
        return selectedRecommendedModel().fileName
    }

    private fun selectedRecommendedModel(): RecommendedModel =
        GemmaModelRules.recommendedModelById(selectedModelId)

    private fun createCustomDownloadSource(downloadUrl: String): ModelDownloadSource? {
        val trimmedUrl = downloadUrl.trim()
        val uri = runCatching { Uri.parse(trimmedUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host
        if (host.isNullOrBlank()) return null
        val fileName = GemmaModelRules.sanitizeModelName(
            uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBefore('?')
                ?.takeIf { it.isNotBlank() }
                ?: "custom-model$GEMMA_MODEL_EXTENSION",
        )
        return ModelDownloadSource(
            title = "自定义模型",
            fileName = fileName,
            downloadUrl = trimmedUrl,
            expectedBytes = null,
            modelId = null,
        )
    }

    private fun loadPendingDownloadSource(): ModelDownloadSource? =
        prefs.getString(PREF_DOWNLOAD_SOURCE, null)
            ?.let { encoded ->
                runCatching {
                    val json = JSONObject(encoded)
                    ModelDownloadSource(
                        title = json.optString("title", "模型下载"),
                        fileName = GemmaModelRules.sanitizeModelName(json.getString("fileName")),
                        downloadUrl = json.getString("downloadUrl"),
                        expectedBytes = json.optLongOrNull("expectedBytes"),
                        modelId = json.optString("modelId").takeIf { it.isNotBlank() },
                    )
                }.getOrNull()
            }

    private fun ModelDownloadSource.toJson(): JSONObject =
        JSONObject()
            .put("title", title)
            .put("fileName", fileName)
            .put("downloadUrl", downloadUrl)
            .put("expectedBytes", expectedBytes ?: JSONObject.NULL)
            .put("modelId", modelId ?: JSONObject.NULL)

    private fun ModelDownloadSource.isCompleteFile(fileBytes: Long): Boolean =
        if (expectedBytes == null) {
            fileBytes > 0L
        } else {
            fileBytes == expectedBytes
        }

    private fun ModelDownloadSource.installedDisplayName(file: File): String =
        modelId?.let { GemmaModelRules.recommendedModelById(it).shortName }
            ?: file.nameWithoutExtension

    private fun inferRecommendedModelId(fileName: String): String? =
        GEMMA_RECOMMENDED_MODELS.firstOrNull {
            it.fileName.equals(fileName, ignoreCase = true)
        }?.id

    private fun registerInstalledModel(
        path: String,
        displayName: String,
        recommendedModelId: String?,
    ): InstalledModel {
        val file = File(path)
        val existing = installedModels.firstOrNull {
            it.path == path || (recommendedModelId != null && it.recommendedModelId == recommendedModelId)
        }
        val installed = InstalledModel(
            id = existing?.id ?: installedModelId(path, recommendedModelId),
            displayName = displayName,
            path = path,
            fileBytes = file.length().coerceAtLeast(0L),
            recommendedModelId = recommendedModelId,
        )
        installedModels = (listOf(installed) + installedModels)
            .distinctBy { it.id }
            .filter { File(it.path).exists() }
        activateInstalledModel(installed, "已选择 ${installed.displayName}", persistNow = true)
        return installed
    }

    private fun activateInstalledModel(
        installed: InstalledModel,
        statusText: String,
        persistNow: Boolean = true,
    ) {
        activeInstalledModelId = installed.id
        installed.recommendedModelId?.let {
            selectedModelId = it
        }
        if (persistNow) {
            persistInstalledModels()
        }
        _uiState.update {
            it.copy(
                modelPath = installed.path,
                activeInstalledModelId = activeInstalledModelId,
                installedModels = installedModelSummaries(),
                selectedModelId = selectedModelId,
                isReady = false,
                statusText = statusText,
            )
        }
    }

    private fun loadInstalledModels(): List<InstalledModel> {
        val persisted = prefs.getString(PREF_INSTALLED_MODELS_JSON, null)
            ?.let { encoded ->
                runCatching {
                    val array = JSONArray(encoded)
                    buildList {
                        for (index in 0 until array.length()) {
                            val json = array.getJSONObject(index)
                            val path = json.optString("path").takeIf { it.isNotBlank() } ?: continue
                            val file = File(path)
                            if (!file.exists()) continue
                            val recommendedId = json.optString("recommendedModelId").takeIf { it.isNotBlank() }
                                ?: inferRecommendedModelId(file.name)
                            add(
                                InstalledModel(
                                    id = json.optString("id").takeIf { it.isNotBlank() }
                                        ?: installedModelId(path, recommendedId),
                                    displayName = json.optString("displayName").takeIf { it.isNotBlank() }
                                        ?: installedModelDisplayName(file, recommendedId),
                                    path = path,
                                    fileBytes = file.length().coerceAtLeast(0L),
                                    recommendedModelId = recommendedId,
                                ),
                            )
                        }
                    }
                }.getOrNull()
            }
            .orEmpty()

        val legacy = prefs.getString(PREF_MODEL_PATH, null)
            ?.takeIf { File(it).exists() }
            ?.let { path ->
                val file = File(path)
                val recommendedId = inferRecommendedModelId(file.name)
                InstalledModel(
                    id = installedModelId(path, recommendedId),
                    displayName = installedModelDisplayName(file, recommendedId),
                    path = path,
                    fileBytes = file.length().coerceAtLeast(0L),
                    recommendedModelId = recommendedId,
                )
            }

        val discovered = discoverRecommendedDownloadedModels()
        return (persisted + listOfNotNull(legacy) + discovered)
            .filter { File(it.path).exists() }
            .distinctBy { it.id }
    }

    private fun discoverRecommendedDownloadedModels(): List<InstalledModel> {
        val downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList()
        return GEMMA_RECOMMENDED_MODELS.mapNotNull { model ->
            val file = File(downloadDir, model.fileName)
            if (file.exists() && GemmaModelRules.isCompleteRecommendedModel(file.length(), model)) {
                InstalledModel(
                    id = model.id,
                    displayName = model.shortName,
                    path = file.absolutePath,
                    fileBytes = file.length(),
                    recommendedModelId = model.id,
                )
            } else {
                null
            }
        }
    }

    private fun resolveActiveInstalledModelId(models: List<InstalledModel>): String? {
        val savedId = prefs.getString(PREF_ACTIVE_INSTALLED_MODEL_ID, null)
        val savedPath = prefs.getString(PREF_MODEL_PATH, null)
        return models.firstOrNull { it.id == savedId }?.id
            ?: models.firstOrNull { it.path == savedPath }?.id
            ?: models.firstOrNull()?.id
    }

    private fun activeInstalledModel(): InstalledModel? =
        installedModels.firstOrNull { it.id == activeInstalledModelId && File(it.path).exists() }

    private fun installedModelSummaries(): List<InstalledModelSummary> =
        installedModels
            .filter { File(it.path).exists() }
            .map { installed ->
                InstalledModelSummary(
                    id = installed.id,
                    displayName = installed.displayName,
                    path = installed.path,
                    fileBytes = installed.fileBytes,
                    recommendedModelId = installed.recommendedModelId,
                )
            }

    private fun persistInstalledModels() {
        val active = activeInstalledModel()
        prefs.edit()
            .putString(PREF_INSTALLED_MODELS_JSON, installedModels.toInstalledModelsJson().toString())
            .putString(PREF_ACTIVE_INSTALLED_MODEL_ID, activeInstalledModelId)
            .apply {
                if (active == null) {
                    remove(PREF_MODEL_PATH)
                } else {
                    putString(PREF_MODEL_PATH, active.path)
                }
            }
            .apply()
    }

    private fun List<InstalledModel>.toInstalledModelsJson(): JSONArray =
        JSONArray().also { array ->
            forEach { model ->
                array.put(
                    JSONObject()
                        .put("id", model.id)
                        .put("displayName", model.displayName)
                        .put("path", model.path)
                        .put("fileBytes", model.fileBytes)
                        .put("recommendedModelId", model.recommendedModelId ?: JSONObject.NULL),
                )
            }
        }

    private fun installedModelDisplayName(file: File, recommendedModelId: String?): String =
        recommendedModelId?.let { GemmaModelRules.recommendedModelById(it).shortName }
            ?: file.nameWithoutExtension

    private fun installedModelId(path: String, recommendedModelId: String?): String =
        recommendedModelId ?: "local-${Integer.toHexString(path.hashCode())}"

    private fun loadSessions(): List<ChatSession> {
        val decoded = prefs.getString(PREF_SESSIONS_JSON, null)
            ?.let { encoded ->
                runCatching {
                    val array = JSONArray(encoded)
                    buildList {
                        for (index in 0 until array.length()) {
                            val json = array.getJSONObject(index)
                            val messages = json.optJSONArray("messages").orEmptyMessages()
                            add(
                                ChatSession(
                                    id = json.optString("id").takeIf { it.isNotBlank() }
                                        ?: UUID.randomUUID().toString(),
                                    title = json.optString("title").takeIf { it.isNotBlank() }
                                        ?: deriveSessionTitle(messages),
                                    createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis()),
                                    updatedAtMillis = json.optLong("updatedAtMillis", System.currentTimeMillis()),
                                    messages = messages,
                                ),
                            )
                        }
                    }
                }.getOrNull()
            }
            .orEmpty()
            .sortedByDescending { it.updatedAtMillis }
            .take(20)

        return decoded.ifEmpty { listOf(newChatSession()) }
    }

    private fun resolveActiveSessionId(availableSessions: List<ChatSession>): String {
        val saved = prefs.getString(PREF_ACTIVE_SESSION_ID, null)
        return availableSessions.firstOrNull { it.id == saved }?.id
            ?: availableSessions.firstOrNull()?.id
            ?: newChatSession().id
    }

    private fun newChatSession(): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            createdAtMillis = now,
            updatedAtMillis = now,
            messages = emptyList(),
        )
    }

    private fun activeSessionMessages(): List<ChatMessage> =
        sessions.firstOrNull { it.id == activeSessionId }?.messages.orEmpty()

    private fun sessionSummaries(): List<ChatSessionSummary> =
        sessions
            .sortedByDescending { it.updatedAtMillis }
            .map { session ->
                ChatSessionSummary(
                    id = session.id,
                    title = session.title,
                    updatedAtMillis = session.updatedAtMillis,
                    messageCount = session.messages.size,
                )
            }

    private fun replaceActiveSessionMessages(
        messages: List<ChatMessage>,
        persistNow: Boolean,
    ) {
        val now = System.currentTimeMillis()
        sessions = sessions.map { session ->
            if (session.id == activeSessionId) {
                session.copy(
                    title = deriveSessionTitle(messages),
                    updatedAtMillis = max(session.updatedAtMillis, now),
                    messages = messages,
                )
            } else {
                session
            }
        }.sortedByDescending { it.updatedAtMillis }
        if (persistNow) {
            persistSessions()
        }
        _uiState.update {
            it.copy(
                sessions = sessionSummaries(),
                activeSessionId = activeSessionId,
                messages = messages,
            )
        }
    }

    private fun persistActiveSessionFromUi() {
        replaceActiveSessionMessages(_uiState.value.messages, persistNow = true)
    }

    private fun finishStoppedGeneration() {
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
        _uiState.update {
            it.copy(
                isBusy = false,
                isGenerating = false,
                isReady = engine != null && conversation != null,
                statusText = if (engine == null || conversation == null) {
                    "未加载模型"
                } else {
                    "已停止 · ${it.backend.label()}"
                },
            )
        }
    }

    private fun persistSessions() {
        prefs.edit()
            .putString(PREF_SESSIONS_JSON, sessions.toSessionsJson().toString())
            .putString(PREF_ACTIVE_SESSION_ID, activeSessionId)
            .apply()
    }

    private fun List<ChatSession>.toSessionsJson(): JSONArray =
        JSONArray().also { array ->
            forEach { session ->
                array.put(
                    JSONObject()
                        .put("id", session.id)
                        .put("title", session.title)
                        .put("createdAtMillis", session.createdAtMillis)
                        .put("updatedAtMillis", session.updatedAtMillis)
                        .put("messages", session.messages.toMessagesJson()),
                )
            }
        }

    private fun List<ChatMessage>.toMessagesJson(): JSONArray =
        JSONArray().also { array ->
            forEach { message ->
                array.put(
                    JSONObject()
                        .put("role", message.role.name)
                        .put("text", message.text),
                )
            }
        }

    private fun JSONArray?.orEmptyMessages(): List<ChatMessage> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val role = runCatching { MessageRole.valueOf(json.optString("role")) }
                    .getOrDefault(MessageRole.Assistant)
                val text = json.optString("text")
                if (text.isNotBlank()) {
                    add(ChatMessage(role = role, text = text))
                }
            }
        }
    }

    private fun deriveSessionTitle(messages: List<ChatMessage>): String =
        messages
            .firstOrNull { it.role == MessageRole.User && it.text.isNotBlank() }
            ?.text
            ?.replace(Regex("\\s+"), " ")
            ?.take(28)
            ?: "新会话"

    private fun List<ChatMessage>.toLiteRtInitialMessages(): List<LiteRtMessage> =
        mapNotNull { message ->
            val text = message.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when (message.role) {
                MessageRole.User -> LiteRtMessage.user(text)
                MessageRole.Assistant -> LiteRtMessage.model(text)
            }
        }

    private fun recreateConversationForActiveSession(successPrefix: String) {
        val currentEngine = engine ?: return
        _uiState.update {
            it.copy(
                isBusy = true,
                isReady = false,
                statusText = "正在恢复会话",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                runtimeLock.withLock {
                    conversation?.close()
                    conversation = currentEngine.createConversation(defaultConversationConfig(activeSessionMessages()))
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

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (isNull(name)) null else optLong(name).takeIf { it > 0L }

    private fun closeRuntime() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    private fun BackendChoice.toLiteRtBackend(): Backend =
        when (this) {
            BackendChoice.CPU -> Backend.CPU()
            BackendChoice.GPU -> Backend.GPU()
        }

    private fun BackendChoice.label(): String =
        when (this) {
            BackendChoice.CPU -> "CPU"
            BackendChoice.GPU -> "GPU"
        }

    private fun Throwable.cleanMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private fun com.google.ai.edge.litertlm.Message.textContent(): String =
        contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }

    private fun MutableStateFlow<ChatUiState>.updateLastAssistant(text: String) {
        update { state ->
            val updatedMessages = state.messages.toMutableList()
            val index = updatedMessages.indexOfLast { it.role == MessageRole.Assistant }
            if (index >= 0) {
                updatedMessages[index] = updatedMessages[index].copy(text = text)
            }
            state.copy(messages = updatedMessages)
        }
    }
}

private data class DownloadInfo(
    val status: Int,
    val reason: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val statusText: String
        get() = GemmaModelRules.downloadStatusText(status, reason)

    val reasonText: String
        get() = GemmaModelRules.downloadReasonText(reason)
}
