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
import com.bytedance.zgx.pocketmind.isLocalDebugHost
import java.io.File
import java.net.URI
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
    val registerInstalledModel: Boolean = true,
    val requiresHuggingFaceAuthorization: Boolean = false,
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
                requiresHuggingFaceAuthorization = model.requiresHuggingFaceAuthorization,
            )

        fun recommendedBundle(model: RecommendedModel): List<ModelDownloadSource> =
            listOf(recommended(model)) +
                model.companionFiles.map { companion ->
                    ModelDownloadSource(
                        title = "${model.shortName} tokenizer",
                        fileName = companion.fileName,
                        downloadUrl = companion.downloadUrl,
                        expectedBytes = companion.byteSize,
                        expectedSha256 = companion.sha256Hex,
                        modelId = model.id,
                        registerInstalledModel = false,
                        requiresHuggingFaceAuthorization = companion.requiresHuggingFaceAuthorization,
                    )
                }
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
        modelId?.let { ModelCatalog.recommendedModelOrNull(it)?.shortName }
            ?: file.nameWithoutExtension
}

internal fun createCustomModelDownloadSource(downloadUrl: String): ModelDownloadSource? {
    val trimmedUrl = downloadUrl.trim()
    val uri = runCatching { URI(trimmedUrl) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    if (!uri.userInfo.isNullOrBlank()) return null
    when (scheme) {
        "https" -> Unit
        "http" -> if (!host.isLocalDebugHost()) return null
        else -> return null
    }
    val rawFileName = uri.path
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    if (!rawFileName.endsWith(MODEL_FILE_EXTENSION, ignoreCase = true)) return null
    val fileName = ModelCatalog.sanitizeModelName(rawFileName)
    return ModelDownloadSource(
        title = "自定义模型",
        fileName = fileName,
        downloadUrl = trimmedUrl,
        expectedBytes = null,
        expectedSha256 = null,
        modelId = null,
    )
}

data class TransferProgress(
    val percent: Int?,
    val transferredBytes: Long,
    val totalBytes: Long,
)

internal data class ImportedModelFile(
    val path: String,
    val displayName: String,
    val recommendedModelId: String? = null,
    val verificationStatus: ModelVerificationStatus = ModelVerificationStatus.UnverifiedCustom,
)

internal fun importModelFileToModelDir(
    modelDir: File,
    displayName: String,
    sourceSizeBytes: Long,
    usableSpaceBytes: Long = modelDir.usableSpace,
    copyToTemp: (File) -> Unit,
): ImportedModelFile {
    require(ModelCatalog.isAcceptedModelName(displayName)) {
        "请选择 $MODEL_FILE_EXTENSION 模型文件"
    }
    if (sourceSizeBytes > 0L && !ModelCatalog.hasEnoughSpace(usableSpaceBytes, sourceSizeBytes)) {
        error("存储空间不足，导入需要 ${ModelCatalog.formatBytes(sourceSizeBytes)}")
    }
    if (!modelDir.exists() && !modelDir.mkdirs()) {
        error("无法创建模型目录")
    }

    val target = File(modelDir, ModelCatalog.sanitizeModelName(displayName))
    val tempPrefix = target.nameWithoutExtension.takeIf { it.length >= 3 } ?: "model"
    val tempTarget = File.createTempFile(tempPrefix, ".tmp", modelDir)
    try {
        copyToTemp(tempTarget)
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
    return ImportedModelFile(
        path = target.absolutePath,
        displayName = target.nameWithoutExtension,
    )
}

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
) : ModelRepositoryFacade {
    constructor(context: Context) : this(
        context.applicationContext,
        PocketMindDatabase.get(context).modelDao(),
        PocketMindDatabase.get(context).downloadRecordDao(),
        PreferenceSettingsStore(context),
    )

    private val modelDir = File(appContext.filesDir, "models")
    private val currentFileVerificationCache = mutableMapOf<String, CurrentFileVerification>()

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

    override fun deleteInstalledModel(modelId: String): Boolean {
        val installed = modelDao.model(modelId) ?: return false
        val wasActive = settingsStore.activeInstalledModelId() == installed.id
        val file = File(installed.path)
        if (!canDeleteInstalledModelFile(file, managedModelRoots())) return false
        val fileDeleted = !file.exists() || file.delete()
        if (!fileDeleted) return false
        installed.recommendedModelId
            ?.let { ModelCatalog.recommendedModelOrNull(it) }
            ?.companionFiles
            .orEmpty()
            .forEach { companion ->
                val companionFile = ModelCatalog.recommendedModelCompanionFile(file, companion)
                if (canDeleteInstalledModelFile(companionFile, managedModelRoots())) {
                    companionFile.delete()
                }
            }
        currentFileVerificationCache.keys
            .filter { key -> key.startsWith("${installed.id}:") }
            .forEach(currentFileVerificationCache::remove)
        modelDao.delete(installed.id)
        if (wasActive) {
            val fallback = installedModels().firstOrNull { entity ->
                File(entity.path).exists() && entity.canBecomeActiveChatModel()
            }
            if (fallback != null) {
                activateInstalledModel(fallback)
            } else {
                settingsStore.saveActiveInstalledModelId(null)
            }
        }
        return true
    }

    override fun registerInstalledModel(
        path: String,
        displayName: String,
        recommendedModelId: String?,
        verifiedSha256: String?,
        verificationStatus: ModelVerificationStatus,
    ): InstalledModelSummary {
        val file = File(path)
        val model = recommendedModelId?.let { ModelCatalog.recommendedModelOrNull(it) }
        val status = when {
            recommendedModelId == null -> ModelVerificationStatus.UnverifiedCustom
            model == null -> ModelVerificationStatus.FailedVerification
            verificationStatus == ModelVerificationStatus.FailedVerification -> verificationStatus
            verifiedSha256 != null && verifiedSha256.equals(model.sha256Hex, ignoreCase = true) ->
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

    override fun importModel(
        uri: Uri,
        onProgress: (TransferProgress) -> Unit,
    ): String {
        clearPendingDownload()
        val displayName = resolveDisplayName(uri)
        val sourceSize = resolveFileSize(uri)
        val imported = importModelFileToModelDir(
            modelDir = modelDir,
            displayName = displayName,
            sourceSizeBytes = sourceSize,
        ) { tempTarget ->
            copyUriToFile(uri, tempTarget, onProgress)
        }
        registerInstalledModel(
            path = imported.path,
            displayName = imported.displayName,
            recommendedModelId = imported.recommendedModelId,
            verificationStatus = imported.verificationStatus,
        )
        return imported.path
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
                        registerInstalledModel = json.optBoolean("registerInstalledModel", true),
                        requiresHuggingFaceAuthorization = json.optBoolean("requiresHuggingFaceAuthorization", false),
                    )
                }.getOrNull()
            }

    override fun createCustomDownloadSource(downloadUrl: String): ModelDownloadSource? {
        return createCustomModelDownloadSource(downloadUrl)
    }

    override fun downloadedModelFile(fileName: String): File? =
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, fileName) }

    override fun resolveModelStorageBytes(): Long {
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

    override fun verifiedActionModelPath(): String? =
        verifiedRecommendedModelPath(
            models = installedModels(),
            capability = ModelCapability.MobileAction,
            currentFileVerifier = ::hasCurrentVerifiedRecommendedFile,
        )

    override fun verifiedMemoryEmbeddingModelPath(): String? =
        verifiedRecommendedModelPath(
            models = installedModels(),
            capability = ModelCapability.MemoryEmbedding,
            currentFileVerifier = ::hasCurrentVerifiedRecommendedFile,
        )

    override fun verifyLegacyRecommendedModels(): Boolean {
        var changed = false
        installedModels().forEach { entity ->
            val modelId = entity.recommendedModelId ?: return@forEach
            val model = catalogRecommendedModel(modelId) ?: return@forEach
            val wasVerifiedRecommended = entity.verificationStatus == ModelVerificationStatus.VerifiedRecommended.name
            if (entity.hasVerifiedRecommendedEvidence(model) && hasCurrentVerifiedRecommendedFile(entity, model)) {
                return@forEach
            }
            val file = File(entity.path)
            if (!file.exists() || !ModelCatalog.isCompleteRecommendedModel(file.length(), model)) {
                if (wasVerifiedRecommended) {
                    modelDao.upsert(
                        entity.copy(
                            fileBytes = file.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L,
                            verificationStatus = ModelVerificationStatus.FailedVerification.name,
                        ),
                    )
                    changed = true
                }
                return@forEach
            }
            if (!ModelCatalog.hasCompleteCompanionFiles(file, model)) {
                return@forEach
            }
            val verified = ModelCatalog.matchesExpectedSha256(file, model.sha256Hex)
            modelDao.upsert(
                entity.copy(
                    fileBytes = file.length().coerceAtLeast(0L),
                    sourceRevision = model.sourceRevision,
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
        val registeredPaths = modelDao.models()
            .map { File(it.path).absolutePath }
            .toSet()
        RECOMMENDED_MODELS.forEach { model ->
            val file = File(downloadDir, model.fileName)
            if (
                file.exists() &&
                modelDao.model(model.id) == null &&
                file.absolutePath !in registeredPaths
            ) {
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
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
            ?: ""
    }

    private fun cleanTemporaryModelFiles() {
        modelDir.listFiles { file ->
            file.isFile && file.extension == "tmp"
        }?.forEach { it.delete() }
    }

    private fun managedModelRoots(): List<File> =
        listOfNotNull(
            modelDir,
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        )

    private fun ModelDownloadSource.toJson(): JSONObject =
        JSONObject()
            .put("title", title)
            .put("fileName", fileName)
            .put("downloadUrl", downloadUrl)
            .put("expectedBytes", expectedBytes ?: JSONObject.NULL)
            .put("expectedSha256", expectedSha256 ?: JSONObject.NULL)
            .put("modelId", modelId ?: JSONObject.NULL)
            .put("registerInstalledModel", registerInstalledModel)
            .put("requiresHuggingFaceAuthorization", requiresHuggingFaceAuthorization)

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
            catalogRecommendedModel(recommendedModelId)?.capability == ModelCapability.Chat &&
                verificationStatus == ModelVerificationStatus.VerifiedRecommended.name
        }

    private fun hasCurrentVerifiedRecommendedFile(
        entity: InstalledModelEntity,
        model: RecommendedModel,
    ): Boolean {
        val file = File(entity.path)
        if (!file.exists() || file.length() != model.byteSize) return false
        if (!ModelCatalog.hasCompleteCompanionFiles(file, model)) return false
        val cacheKey = "${entity.id}:${file.absolutePath}:${model.id}"
        val current = CurrentFileVerification(
            length = file.length(),
            lastModifiedMillis = file.lastModified(),
            expectedSha256 = model.sha256Hex.lowercase(),
            verified = false,
        )
        val cached = currentFileVerificationCache[cacheKey]
        if (
            cached != null &&
            cached.length == current.length &&
            cached.lastModifiedMillis == current.lastModifiedMillis &&
            cached.expectedSha256 == current.expectedSha256
        ) {
            return cached.verified
        }
        val verified = ModelCatalog.matchesExpectedSha256(file, model.sha256Hex)
        currentFileVerificationCache[cacheKey] = current.copy(verified = verified)
        return verified
    }
}

internal fun verifiedRecommendedModelPath(
    models: List<InstalledModelEntity>,
    capability: ModelCapability,
    currentFileVerifier: (InstalledModelEntity, RecommendedModel) -> Boolean =
        ::currentFileMatchesVerifiedRecommendedModel,
): String? =
    models.firstOrNull { model ->
        val catalogModel = model.recommendedModelId?.let { catalogRecommendedModel(it) }
            ?: return@firstOrNull false
        catalogModel.capability == capability &&
            model.hasVerifiedRecommendedEvidence(catalogModel) &&
            currentFileVerifier(model, catalogModel)
    }?.path

private fun InstalledModelEntity.hasVerifiedRecommendedEvidence(model: RecommendedModel): Boolean =
    verificationStatus == ModelVerificationStatus.VerifiedRecommended.name &&
        fileBytes == model.byteSize &&
        sourceRevision == model.sourceRevision &&
        verifiedSha256?.equals(model.sha256Hex, ignoreCase = true) == true

private fun catalogRecommendedModel(modelId: String): RecommendedModel? =
    RECOMMENDED_MODELS.firstOrNull { it.id == modelId }

private fun currentFileMatchesVerifiedRecommendedModel(
    entity: InstalledModelEntity,
    model: RecommendedModel,
): Boolean {
    val file = File(entity.path)
    return file.exists() &&
        file.length() == model.byteSize &&
        ModelCatalog.matchesExpectedSha256(file, model.sha256Hex) &&
        ModelCatalog.hasCompleteCompanionFiles(file, model)
}

internal fun canDeleteInstalledModelFile(
    file: File,
    managedRoots: List<File>,
): Boolean {
    if (!ModelCatalog.isAcceptedModelName(file.name)) return false
    val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return false
    return managedRoots.any { root ->
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
        canonicalFile.toPath().startsWith(canonicalRoot.toPath()) &&
            canonicalFile != canonicalRoot
    }
}

private data class CurrentFileVerification(
    val length: Long,
    val lastModifiedMillis: Long,
    val expectedSha256: String,
    val verified: Boolean,
)
