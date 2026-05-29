package com.bytedance.zgx.pocketmind.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.bytedance.zgx.pocketmind.DEFAULT_CHAT_MODEL_BYTES
import com.bytedance.zgx.pocketmind.DEFAULT_CHAT_MODEL_ID
import com.bytedance.zgx.pocketmind.InstalledModelSummary
import com.bytedance.zgx.pocketmind.MODEL_FILE_EXTENSION
import com.bytedance.zgx.pocketmind.ModelCapability
import com.bytedance.zgx.pocketmind.ModelCatalog
import com.bytedance.zgx.pocketmind.RECOMMENDED_MODELS
import com.bytedance.zgx.pocketmind.RecommendedModel
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

data class ModelDownloadSource(
    val title: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long?,
    val expectedSha256: String?,
    val modelId: String?,
) {
    companion object {
        fun recommended(model: RecommendedModel): ModelDownloadSource =
            ModelDownloadSource(
                title = model.shortName,
                fileName = model.fileName,
                downloadUrl = model.downloadUrl,
                expectedBytes = model.byteSize,
                expectedSha256 = model.sha256Hex,
                modelId = model.id,
            )
    }

    fun hasExpectedSize(fileBytes: Long): Boolean =
        expectedBytes?.let { fileBytes == it } ?: (fileBytes > 0L)

    fun verifiedSha256(file: File): Result<String?> =
        runCatching {
            check(hasExpectedSize(file.length())) { "模型文件大小不匹配" }
            val expected = expectedSha256?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val actual = ModelCatalog.sha256Hex(file)
            check(actual.equals(expected, ignoreCase = true)) { "模型校验失败，请重新下载" }
            actual.lowercase()
        }

    fun installedDisplayName(file: File): String =
        modelId?.let { ModelCatalog.recommendedModelById(it).shortName }
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

class ModelRepository(
    private val appContext: Context,
    private val modelDao: ModelDao,
    private val downloadRecordDao: DownloadRecordDao,
    private val settingsStore: PreferenceSettingsStore,
) : ModelStore, DownloadStore {
    constructor(context: Context) : this(
        context.applicationContext,
        PocketMindDatabase.get(context).modelDao(),
        PocketMindDatabase.get(context).downloadRecordDao(),
        PreferenceSettingsStore(context),
    )

    private val modelDir = File(appContext.filesDir, "models")

    init {
        cleanTemporaryModelFiles()
        discoverRecommendedDownloadedModels()
    }

    override fun currentState(): ModelSelectionState {
        val selectedModelId = settingsStore.selectedModelId() ?: DEFAULT_CHAT_MODEL_ID
        val activeModel = activeInstalledModel()
        return ModelSelectionState(
            selectedModelId = selectedModelId,
            activeInstalledModelId = activeModel?.id,
            activeModelPath = activeModel?.path,
            installedModels = installedModelSummaries(),
        )
    }

    override fun selectedRecommendedModel(): RecommendedModel =
        ModelCatalog.recommendedChatModelById(settingsStore.selectedModelId() ?: DEFAULT_CHAT_MODEL_ID)

    override fun selectRecommendedModel(modelId: String): ModelSelectionResult {
        val selectedModelId = ModelCatalog.recommendedChatModelById(modelId).id
        settingsStore.saveSelectedModelId(selectedModelId)
        val installed = installedModels().firstOrNull {
            it.recommendedModelId == selectedModelId && File(it.path).exists() && it.canBecomeActiveChatModel()
        }
        val activated = installed?.let { activateInstalledModel(it) }
        return ModelSelectionResult(currentState(), activated)
    }

    override fun selectInstalledModel(modelId: String): InstalledModelSummary? {
        val installed = modelDao.model(modelId)?.takeIf { File(it.path).exists() } ?: return null
        if (!installed.canBecomeActiveChatModel()) return null
        return activateInstalledModel(installed)
    }

    override fun registerInstalledModel(
        path: String,
        displayName: String,
        recommendedModelId: String?,
        verifiedSha256: String?,
        verificationStatus: ModelVerificationStatus,
    ): InstalledModelSummary {
        val file = File(path)
        val model = recommendedModelId?.let { ModelCatalog.recommendedModelById(it) }
        val status = when {
            recommendedModelId == null -> ModelVerificationStatus.UnverifiedCustom
            verificationStatus == ModelVerificationStatus.FailedVerification -> verificationStatus
            verifiedSha256 != null && model != null && verifiedSha256.equals(model.sha256Hex, ignoreCase = true) ->
                ModelVerificationStatus.VerifiedRecommended
            verificationStatus == ModelVerificationStatus.LegacyUnverified -> verificationStatus
            else -> ModelVerificationStatus.FailedVerification
        }
        val entity = InstalledModelEntity(
            id = recommendedModelId ?: "local-${Integer.toHexString(path.hashCode())}",
            displayName = displayName,
            path = path,
            fileBytes = file.length().coerceAtLeast(0L),
            recommendedModelId = recommendedModelId,
            sourceRevision = model?.sourceRevision,
            verifiedSha256 = verifiedSha256,
            verificationStatus = status.name,
        )
        modelDao.upsert(entity)
        if (entity.canBecomeActiveChatModel()) {
            activateInstalledModel(entity)
        }
        return entity.toSummary()
    }

    fun importModel(
        uri: Uri,
        onProgress: (TransferProgress) -> Unit,
    ): String {
        clearPendingDownload()
        modelDir.mkdirs()
        val displayName = resolveDisplayName(uri)
        require(ModelCatalog.isAcceptedModelName(displayName)) {
            "请选择 $MODEL_FILE_EXTENSION 模型文件"
        }
        val sourceSize = resolveFileSize(uri)
        if (sourceSize > 0L && !ModelCatalog.hasEnoughSpace(modelDir.usableSpace, sourceSize)) {
            error("存储空间不足，导入需要 ${ModelCatalog.formatBytes(sourceSize)}")
        }
        val target = File(modelDir, ModelCatalog.sanitizeModelName(displayName))
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
            if (moved.isFailure) error("无法保存模型文件")
        } catch (throwable: Throwable) {
            tempTarget.delete()
            throw throwable
        }
        registerInstalledModel(
            path = target.absolutePath,
            displayName = target.nameWithoutExtension,
            recommendedModelId = null,
            verificationStatus = ModelVerificationStatus.UnverifiedCustom,
        )
        return target.absolutePath
    }

    override fun pendingDownloadId(): Long =
        downloadRecordDao.record()?.downloadManagerId ?: -1L

    override fun savePendingDownload(downloadId: Long, source: ModelDownloadSource) {
        downloadRecordDao.upsert(
            DownloadRecordEntity(
                id = PENDING_DOWNLOAD_RECORD_ID,
                downloadManagerId = downloadId,
                sourceJson = source.toJson().toString(),
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    override fun clearPendingDownload() {
        downloadRecordDao.delete()
    }

    override fun loadPendingDownloadSource(): ModelDownloadSource? =
        downloadRecordDao.record()?.sourceJson
            ?.let { encoded ->
                runCatching {
                    val json = JSONObject(encoded)
                    ModelDownloadSource(
                        title = json.optString("title", "模型下载"),
                        fileName = ModelCatalog.sanitizeModelName(json.getString("fileName")),
                        downloadUrl = json.getString("downloadUrl"),
                        expectedBytes = json.optLongOrNull("expectedBytes"),
                        expectedSha256 = json.optStringOrNull("expectedSha256"),
                        modelId = json.optStringOrNull("modelId"),
                    )
                }.getOrNull()
            }

    fun createCustomDownloadSource(downloadUrl: String): ModelDownloadSource? {
        val trimmedUrl = downloadUrl.trim()
        val uri = runCatching { Uri.parse(trimmedUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        val fileName = ModelCatalog.sanitizeModelName(
            uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBefore('?')
                ?.takeIf { it.isNotBlank() }
                ?: "custom-model$MODEL_FILE_EXTENSION",
        )
        return ModelDownloadSource(
            title = "自定义模型",
            fileName = fileName,
            downloadUrl = trimmedUrl,
            expectedBytes = null,
            expectedSha256 = null,
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
        RECOMMENDED_MODELS.firstOrNull {
            it.fileName.equals(fileName, ignoreCase = true)
        }?.id

    fun verifiedActionModelPath(): String? =
        installedModels().firstOrNull {
            it.recommendedModelId != null &&
                ModelCatalog.recommendedModelById(it.recommendedModelId).capability == ModelCapability.MobileAction &&
                it.verificationStatus == ModelVerificationStatus.VerifiedRecommended.name &&
                File(it.path).exists()
        }?.path

    fun verifyLegacyRecommendedModels(): Boolean {
        var changed = false
        installedModels().forEach { entity ->
            val modelId = entity.recommendedModelId ?: return@forEach
            if (entity.verificationStatus == ModelVerificationStatus.VerifiedRecommended.name) return@forEach
            val model = ModelCatalog.recommendedModelById(modelId)
            val file = File(entity.path)
            if (!file.exists() || !ModelCatalog.isCompleteRecommendedModel(file.length(), model)) return@forEach
            val verified = ModelCatalog.matchesExpectedSha256(file, model.sha256Hex)
            modelDao.upsert(
                entity.copy(
                    verifiedSha256 = if (verified) model.sha256Hex else null,
                    verificationStatus = if (verified) {
                        ModelVerificationStatus.VerifiedRecommended.name
                    } else {
                        ModelVerificationStatus.FailedVerification.name
                    },
                ),
            )
            changed = true
        }
        return changed
    }

    private fun discoverRecommendedDownloadedModels() {
        val downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        RECOMMENDED_MODELS.forEach { model ->
            val file = File(downloadDir, model.fileName)
            if (file.exists() && modelDao.model(model.id) == null) {
                modelDao.upsert(
                    InstalledModelEntity(
                        id = model.id,
                        displayName = model.shortName,
                        path = file.absolutePath,
                        fileBytes = file.length().coerceAtLeast(0L),
                        recommendedModelId = model.id,
                        sourceRevision = model.sourceRevision,
                        verifiedSha256 = null,
                        verificationStatus = ModelVerificationStatus.LegacyUnverified.name,
                    ),
                )
            }
        }
    }

    private fun activeInstalledModel(): InstalledModelEntity? {
        val savedId = settingsStore.activeInstalledModelId()
        val saved = savedId?.let { modelDao.model(it) }
        return when {
            saved != null && File(saved.path).exists() && saved.canBecomeActiveChatModel() -> saved
            else -> installedModels().firstOrNull { File(it.path).exists() && it.canBecomeActiveChatModel() }
                ?.also { settingsStore.saveActiveInstalledModelId(it.id) }
        }
    }

    private fun activateInstalledModel(installed: InstalledModelEntity): InstalledModelSummary {
        check(installed.canBecomeActiveChatModel()) { "只有可加载的对话模型可以成为当前模型" }
        settingsStore.saveActiveInstalledModelId(installed.id)
        installed.recommendedModelId?.let { settingsStore.saveSelectedModelId(it) }
        return installed.toSummary()
    }

    private fun installedModels(): List<InstalledModelEntity> =
        modelDao.models().filter { File(it.path).exists() }

    private fun installedModelSummaries(): List<InstalledModelSummary> =
        installedModels().map { it.toSummary() }

    private fun copyUriToFile(
        uri: Uri,
        target: File,
        onProgress: (TransferProgress) -> Unit,
    ) {
        val total = resolveFileSize(uri)
        onProgress(TransferProgress(if (total > 0L) 0 else null, 0L, total))
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
                                percent = ModelCatalog.progressPercent(copied, total),
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

    private fun cleanTemporaryModelFiles() {
        modelDir.listFiles { file ->
            file.isFile && file.extension == "tmp"
        }?.forEach { it.delete() }
    }

    private fun ModelDownloadSource.toJson(): JSONObject =
        JSONObject()
            .put("title", title)
            .put("fileName", fileName)
            .put("downloadUrl", downloadUrl)
            .put("expectedBytes", expectedBytes ?: JSONObject.NULL)
            .put("expectedSha256", expectedSha256 ?: JSONObject.NULL)
            .put("modelId", modelId ?: JSONObject.NULL)

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (isNull(name)) null else optLong(name).takeIf { it > 0L }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

    private fun InstalledModelEntity.toSummary(): InstalledModelSummary =
        InstalledModelSummary(
            id = id,
            displayName = displayName,
            path = path,
            fileBytes = fileBytes,
            recommendedModelId = recommendedModelId,
            verifiedSha256 = verifiedSha256,
            verificationStatus = runCatching { ModelVerificationStatus.valueOf(verificationStatus) }
                .getOrDefault(ModelVerificationStatus.LegacyUnverified),
        )

    private fun InstalledModelEntity.canBecomeActiveChatModel(): Boolean =
        if (recommendedModelId == null) {
            true
        } else {
            ModelCatalog.recommendedModelById(recommendedModelId).capability == ModelCapability.Chat &&
                verificationStatus == ModelVerificationStatus.VerifiedRecommended.name
        }
}
