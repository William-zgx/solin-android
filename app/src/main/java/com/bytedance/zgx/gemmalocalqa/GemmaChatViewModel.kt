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
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "gemma_local_qa"
private const val PREF_MODEL_PATH = "model_path"
private const val PREF_DOWNLOAD_ID = "download_id"
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

data class ChatUiState(
    val modelPath: String? = null,
    val backend: BackendChoice = BackendChoice.GPU,
    val statusText: String = "未加载模型",
    val isArm64Supported: Boolean = true,
    val availableModelStorageBytes: Long = 0L,
    val isBusy: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val isReady: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
)

class GemmaChatViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelDir = File(appContext.filesDir, "models")
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val runtimeLock = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    init {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        cleanTemporaryModelFiles()
        refreshDeviceStatus()
        val savedPath = prefs.getString(PREF_MODEL_PATH, null)
            ?.takeIf { File(it).exists() }
        val pendingDownloadId = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (!isArm64Device()) {
            _uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
        } else if (pendingDownloadId > 0L) {
            val target = defaultDownloadedModelFile()
            if (target == null) {
                prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
                _uiState.update {
                    it.copy(statusText = "下载目录不可用，请导入已有模型")
                }
            } else {
                monitorDownload(pendingDownloadId, target)
            }
        } else if (savedPath != null) {
            _uiState.update {
                it.copy(
                    modelPath = savedPath,
                    statusText = "已找到模型，正在加载",
                )
            }
            loadModel()
        }
    }

    fun startModelDownload() {
        refreshDeviceStatus()
        if (_uiState.value.isBusy || _uiState.value.isDownloading) return
        if (!isArm64Device()) {
            _uiState.update {
                it.copy(statusText = "当前设备不是 64 位 ARM，无法运行此模型")
            }
            return
        }

        val target = defaultDownloadedModelFile()
        if (target == null) {
            _uiState.update {
                it.copy(statusText = "下载目录不可用，请导入已有模型")
            }
            return
        }
        if (target.exists() && GemmaModelRules.isCompleteRecommendedModel(target.length())) {
            prefs.edit().putString(PREF_MODEL_PATH, target.absolutePath).apply()
            _uiState.update {
                it.copy(
                    modelPath = target.absolutePath,
                    statusText = "已找到已下载模型",
                    messages = emptyList(),
                )
            }
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
        if (!GemmaModelRules.hasEnoughSpace(modelParent.usableSpace)) {
            _uiState.update {
                it.copy(statusText = "存储空间不足，至少需要约 2.6 GB")
            }
            return
        }
        val request = DownloadManager.Request(Uri.parse(GEMMA_MODEL_DOWNLOAD_URL))
            .setTitle("Gemma 4 E2B")
            .setDescription("正在下载本地问答模型")
            .setMimeType("application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                GEMMA_MODEL_FILE_NAME,
            )

        val downloadId = runCatching { downloadManager.enqueue(request) }.getOrElse { throwable ->
            _uiState.update {
                it.copy(statusText = "下载启动失败：${throwable.cleanMessage()}")
            }
            return
        }

        prefs.edit().putLong(PREF_DOWNLOAD_ID, downloadId).apply()
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
        monitorDownload(downloadId, target)
    }

    fun cancelModelDownload() {
        val downloadId = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (downloadId > 0L) {
            downloadManager.remove(downloadId)
        }
        prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
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
                    prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
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
                    prefs.edit().putString(PREF_MODEL_PATH, target.absolutePath).apply()
                    target.absolutePath
                }
            }

            result.fold(
                onSuccess = { path ->
                    _uiState.update {
                        it.copy(
                            modelPath = path,
                            messages = emptyList(),
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
                messages = emptyList(),
                statusText = "已切换到 ${choice.label()}",
            )
        }
        if (_uiState.value.modelPath != null) {
            loadModel()
        }
    }

    fun loadModel() {
        val path = _uiState.value.modelPath ?: return
        if (_uiState.value.isBusy) return
        val backendChoice = _uiState.value.backend

        _uiState.update {
            it.copy(
                isBusy = true,
                isReady = false,
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
                            isReady = true,
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
                                    isReady = true,
                                    statusText = "GPU 不可用，已切到 CPU",
                                )
                            }
                            return@launch
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isReady = false,
                            statusText = "初始化失败：${throwable.cleanMessage()}",
                        )
                    }
                },
            )
        }
    }

    fun resetConversation() {
        if (_uiState.value.isBusy || engine == null) return

        _uiState.update {
            it.copy(
                isBusy = true,
                isReady = false,
                statusText = "正在开启新会话",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                conversation?.close()
                conversation = engine?.createConversation(defaultConversationConfig())
            }
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            messages = emptyList(),
                            isBusy = false,
                            isReady = true,
                            statusText = "新会话 · ${it.backend.label()}",
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isReady = false,
                            statusText = "新会话失败：${throwable.cleanMessage()}",
                        )
                    }
                },
            )
        }
    }

    fun sendMessage(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || !_uiState.value.isReady || _uiState.value.isBusy) return

        val assistantPlaceholder = ChatMessage(MessageRole.Assistant, "")
        _uiState.update {
            it.copy(
                isBusy = true,
                messages = it.messages +
                    ChatMessage(MessageRole.User, trimmed) +
                    assistantPlaceholder,
                statusText = "生成中",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val activeConversation = conversation
            if (activeConversation == null) {
                _uiState.updateLastAssistant("模型尚未就绪")
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isReady = false,
                        statusText = "未加载模型",
                    )
                }
                return@launch
            }

            val result = runCatching {
                val partial = StringBuilder()
                activeConversation.sendMessageAsync(trimmed).collect { chunk ->
                    partial.append(chunk.textContent().ifBlank { chunk.toString() })
                    _uiState.updateLastAssistant(partial.toString())
                }
                if (partial.isBlank()) {
                    _uiState.updateLastAssistant("没有生成内容")
                }
            }

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isReady = true,
                            statusText = "就绪 · ${it.backend.label()}",
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.updateLastAssistant("出错了：${throwable.cleanMessage()}")
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isReady = false,
                            statusText = "生成失败，建议重新加载",
                        )
                    }
                },
            )
        }
    }

    override fun onCleared() {
        closeRuntime()
        super.onCleared()
    }

    private fun defaultConversationConfig(): ConversationConfig =
        ConversationConfig(
            systemInstruction = Contents.of("你是一个简洁、可靠的中文问答助手。回答要直接，必要时说明不确定性。"),
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

    private fun monitorDownload(downloadId: Long, targetFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val info = queryDownload(downloadId)
                if (info == null) {
                    prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
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
                        if (!targetFile.exists() || !GemmaModelRules.isCompleteRecommendedModel(targetFile.length())) {
                            prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
                            targetFile.delete()
                            _uiState.update {
                                it.copy(
                                    isBusy = false,
                                    isDownloading = false,
                                    downloadProgressPercent = null,
                                    statusText = "下载文件不完整，请重新下载",
                                )
                            }
                            return@launch
                        }
                        prefs.edit()
                            .remove(PREF_DOWNLOAD_ID)
                            .putString(PREF_MODEL_PATH, targetFile.absolutePath)
                            .apply()
                        _uiState.update {
                            it.copy(
                                modelPath = targetFile.absolutePath,
                                messages = emptyList(),
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
                        prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
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
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, GEMMA_MODEL_FILE_NAME) }

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
        return "gemma-4-e2b$GEMMA_MODEL_EXTENSION"
    }

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
