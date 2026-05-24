package com.bytedance.zgx.gemmalocalqa.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.bytedance.zgx.gemmalocalqa.GEMMA_DEFAULT_RECOMMENDED_MODEL
import com.bytedance.zgx.gemmalocalqa.GEMMA_DEFAULT_RECOMMENDED_MODEL_ID
import com.bytedance.zgx.gemmalocalqa.GEMMA_MODEL_EXTENSION
import com.bytedance.zgx.gemmalocalqa.GEMMA_RECOMMENDED_MODELS
import com.bytedance.zgx.gemmalocalqa.GEMMA_RECOMMENDED_MODEL_BYTES
import com.bytedance.zgx.gemmalocalqa.GemmaModelRules
import com.bytedance.zgx.gemmalocalqa.InstalledModelSummary
import com.bytedance.zgx.gemmalocalqa.RecommendedModel
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "gemma_local_qa"
private const val PREF_MODEL_PATH = "model_path"
private const val PREF_DOWNLOAD_ID = "download_id"
private const val PREF_DOWNLOAD_SOURCE = "download_source"
private const val PREF_SELECTED_MODEL_ID = "selected_model_id"
private const val PREF_INSTALLED_MODELS_JSON = "installed_models_json"
private const val PREF_ACTIVE_INSTALLED_MODEL_ID = "active_installed_model_id"

data class ModelDownloadSource(
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

    fun isCompleteFile(fileBytes: Long): Boolean =
        if (expectedBytes == null) {
            fileBytes > 0L
        } else {
            fileBytes == expectedBytes
        }

    fun installedDisplayName(file: File): String =
        modelId?.let { GemmaModelRules.recommendedModelById(it).shortName }
            ?: file.nameWithoutExtension
}

data class TransferProgress(
    val percent: Int?,
    val transferredBytes: Long,
    val totalBytes: Long,
)

data class ModelSelectionState(
    val selectedModelId: String,
    val activeInstalledModelId: String?,
    val activeModelPath: String?,
    val installedModels: List<InstalledModelSummary>,
)

data class ModelSelectionResult(
    val state: ModelSelectionState,
    val activatedModel: InstalledModelSummary?,
)

class ModelRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelDir = File(appContext.filesDir, "models")

    private var selectedModelId = prefs.getString(PREF_SELECTED_MODEL_ID, GEMMA_DEFAULT_RECOMMENDED_MODEL_ID)
        ?: GEMMA_DEFAULT_RECOMMENDED_MODEL_ID
    private var installedModels: List<InstalledModel> = loadInstalledModels()
    private var activeInstalledModelId: String? = resolveActiveInstalledModelId(installedModels)

    init {
        cleanTemporaryModelFiles()
        if (installedModels.isNotEmpty()) {
            persistInstalledModels()
        }
    }

    fun currentState(): ModelSelectionState =
        ModelSelectionState(
            selectedModelId = selectedModelId,
            activeInstalledModelId = activeInstalledModelId,
            activeModelPath = activeInstalledModel()?.path,
            installedModels = installedModelSummaries(),
        )

    fun selectedRecommendedModel(): RecommendedModel =
        GemmaModelRules.recommendedModelById(selectedModelId)

    fun selectRecommendedModel(modelId: String): ModelSelectionResult {
        selectedModelId = GemmaModelRules.recommendedModelById(modelId).id
        prefs.edit().putString(PREF_SELECTED_MODEL_ID, selectedModelId).apply()
        val installed = installedModels.firstOrNull {
            it.recommendedModelId == selectedModelId && File(it.path).exists()
        }
        val activated = installed?.let { activateInstalledModel(it, persistNow = true) }
        return ModelSelectionResult(
            state = currentState(),
            activatedModel = activated,
        )
    }

    fun selectInstalledModel(modelId: String): InstalledModelSummary? {
        val installed = installedModels.firstOrNull { it.id == modelId && File(it.path).exists() } ?: return null
        return activateInstalledModel(installed, persistNow = true)
    }

    fun registerInstalledModel(
        path: String,
        displayName: String,
        recommendedModelId: String?,
    ): InstalledModelSummary {
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
        return activateInstalledModel(installed, persistNow = true)
    }

    fun importModel(
        uri: Uri,
        onProgress: (TransferProgress) -> Unit,
    ): String {
        clearPendingDownload()
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
            copyUriToFile(uri, tempTarget, onProgress)
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
        return target.absolutePath
    }

    fun pendingDownloadId(): Long =
        prefs.getLong(PREF_DOWNLOAD_ID, -1L)

    fun savePendingDownload(downloadId: Long, source: ModelDownloadSource) {
        prefs.edit()
            .putLong(PREF_DOWNLOAD_ID, downloadId)
            .putString(PREF_DOWNLOAD_SOURCE, source.toJson().toString())
            .apply()
    }

    fun clearPendingDownload() {
        prefs.edit().remove(PREF_DOWNLOAD_ID).remove(PREF_DOWNLOAD_SOURCE).apply()
    }

    fun loadPendingDownloadSource(): ModelDownloadSource? =
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

    fun createCustomDownloadSource(downloadUrl: String): ModelDownloadSource? {
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

    fun downloadedModelFile(fileName: String): File? =
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, fileName) }

    fun resolveModelStorageBytes(): Long {
        val downloadParent = downloadedModelFile(selectedRecommendedModel().fileName)?.parentFile
        return when {
            downloadParent == null -> appContext.filesDir.usableSpace
            downloadParent.exists() -> downloadParent.usableSpace
            downloadParent.mkdirs() -> downloadParent.usableSpace
            else -> appContext.filesDir.usableSpace
        }
    }

    fun inferRecommendedModelId(fileName: String): String? =
        GEMMA_RECOMMENDED_MODELS.firstOrNull {
            it.fileName.equals(fileName, ignoreCase = true)
        }?.id

    private fun copyUriToFile(
        uri: Uri,
        target: File,
        onProgress: (TransferProgress) -> Unit,
    ) {
        val total = resolveFileSize(uri)
        onProgress(
            TransferProgress(
                percent = if (total > 0L) 0 else null,
                transferredBytes = 0L,
                totalBytes = total,
            ),
        )
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
                        onProgress(
                            TransferProgress(
                                percent = GemmaModelRules.progressPercent(copied, total),
                                transferredBytes = copied,
                                totalBytes = total,
                            ),
                        )
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

    private fun activateInstalledModel(
        installed: InstalledModel,
        persistNow: Boolean,
    ): InstalledModelSummary {
        activeInstalledModelId = installed.id
        installed.recommendedModelId?.let {
            selectedModelId = it
        }
        if (persistNow) {
            persistInstalledModels()
        }
        return installed.toSummary()
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
            .map { it.toSummary() }

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

    private fun ModelDownloadSource.toJson(): JSONObject =
        JSONObject()
            .put("title", title)
            .put("fileName", fileName)
            .put("downloadUrl", downloadUrl)
            .put("expectedBytes", expectedBytes ?: JSONObject.NULL)
            .put("modelId", modelId ?: JSONObject.NULL)

    private fun installedModelDisplayName(file: File, recommendedModelId: String?): String =
        recommendedModelId?.let { GemmaModelRules.recommendedModelById(it).shortName }
            ?: file.nameWithoutExtension

    private fun installedModelId(path: String, recommendedModelId: String?): String =
        recommendedModelId ?: "local-${Integer.toHexString(path.hashCode())}"

    private fun cleanTemporaryModelFiles() {
        modelDir.listFiles { file ->
            file.isFile && file.extension == "tmp"
        }?.forEach { it.delete() }
    }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (isNull(name)) null else optLong(name).takeIf { it > 0L }

    private fun InstalledModel.toSummary(): InstalledModelSummary =
        InstalledModelSummary(
            id = id,
            displayName = displayName,
            path = path,
            fileBytes = fileBytes,
            recommendedModelId = recommendedModelId,
        )
}

private data class InstalledModel(
    val id: String,
    val displayName: String,
    val path: String,
    val fileBytes: Long,
    val recommendedModelId: String?,
)
