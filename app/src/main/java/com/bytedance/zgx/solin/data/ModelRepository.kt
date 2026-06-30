package com.bytedance.zgx.solin.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL_BYTES
import com.bytedance.zgx.solin.DEFAULT_CHAT_MODEL_ID
import com.bytedance.zgx.solin.HIGH_QUALITY_CHAT_MODEL_ID
import com.bytedance.zgx.solin.InstalledModelSummary
import com.bytedance.zgx.solin.MODEL_FILE_EXTENSION
import com.bytedance.zgx.solin.MOBILE_ACTION_MODEL_ID
import com.bytedance.zgx.solin.ModelCapability
import com.bytedance.zgx.solin.ModelCatalog
import com.bytedance.zgx.solin.RECOMMENDED_MODELS
import com.bytedance.zgx.solin.RecommendedModel
import com.bytedance.zgx.solin.isLocalDebugHost
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
        SolinDatabase.get(context).modelDao(),
        SolinDatabase.get(context).downloadRecordDao(),
        PreferenceSettingsStore(context),
    )

    private val modelDir = File(appContext.filesDir, "models")

    init {
        cleanTemporaryModelFiles()
        discoverRecommendedDownloadedModels()
    }

    override fun currentState(): ModelSelectionState {
        refreshCompletedRecommendedBundles()
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
            it.recommendedModelId == selectedModelId && isCurrentActiveChatModel(it)
        }
        val activated = installed?.let { activateInstalledModel(it) }
        return ModelSelectionResult(currentState(), activated)
    }

    override fun selectInstalledModel(modelId: String): InstalledModelSummary? {
        val installed = modelDao.model(modelId)?.takeIf { File(it.path).exists() } ?: return null
        if (!isCurrentActiveChatModel(installed)) return null
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
        modelDao.delete(installed.id)
        if (wasActive) {
            val fallback = installedModels().firstOrNull(::isCurrentActiveChatModel)
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
            else -> recommendedRegistrationVerificationStatus(
                file = file,
                model = model,
                verifiedSha256 = verifiedSha256,
                requestedStatus = verificationStatus,
            )
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
        val currentEntity = if (isCurrentActiveChatModel(entity)) {
            activateInstalledModel(entity)
            entity
        } else {
            modelDao.model(entity.id) ?: entity
        }
        return currentEntity.toSummary()
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
                sourceJson = source.toPersistedJson().toString(),
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    override fun clearPendingDownload() {
        downloadRecordDao.delete()
    }

    override fun loadPendingDownloadSource(): ModelDownloadSource? =
        downloadRecordDao.record()?.sourceJson
            ?.let(::modelDownloadSourceFromPersistedJson)

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

    override fun verifiedActionModelPath(): String? {
        refreshCompletedRecommendedBundles()
        return verifiedRecommendedModelPath(
            models = installedModels(),
            capability = ModelCapability.MobileAction,
            currentFileVerifier = ::hasCurrentVerifiedRecommendedFile,
        )
    }

    override fun verifiedObservationActionModelPath(): String? {
        refreshCompletedRecommendedBundles()
        return verifiedObservationActionPlanningModelPath(
            models = installedModels(),
            activeInstalledModelId = settingsStore.activeInstalledModelId(),
            currentFileVerifier = ::hasCurrentVerifiedRecommendedFile,
        )
    }

    override fun verifiedMemoryEmbeddingModelPath(): String? {
        refreshCompletedRecommendedBundles()
        return verifiedRecommendedModelPath(
            models = installedModels(),
            capability = ModelCapability.MemoryEmbedding,
            currentFileVerifier = ::hasCurrentVerifiedRecommendedFile,
        )
    }

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
                if (wasVerifiedRecommended) {
                    modelDao.upsert(
                        entity.copy(
                            fileBytes = file.length().coerceAtLeast(0L),
                            verifiedSha256 = null,
                            verificationStatus = ModelVerificationStatus.FailedVerification.name,
                        ),
                    )
                    changed = true
                }
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
            saved != null && isCurrentActiveChatModel(saved) -> saved
            else -> installedModels().firstOrNull(::isCurrentActiveChatModel)
                ?.also { settingsStore.saveActiveInstalledModelId(it.id) }
        }
    }

    private fun activateInstalledModel(installed: InstalledModelEntity): InstalledModelSummary {
        check(isCurrentActiveChatModel(installed)) { "只有可加载的对话模型可以成为当前模型" }
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

    private fun isCurrentActiveChatModel(entity: InstalledModelEntity): Boolean {
        if (!File(entity.path).exists()) return false
        val canActivate = installedModelCanBecomeCurrentActiveChatModel(
            model = entity,
            currentFileVerifier = ::hasCurrentVerifiedRecommendedFile,
        )
        if (!canActivate && entity.verificationStatus == ModelVerificationStatus.VerifiedRecommended.name) {
            markRecommendedVerificationFailed(entity)
        }
        return canActivate
    }

    private fun refreshCompletedRecommendedBundles(): Boolean {
        var changed = false
        installedModels().forEach { entity ->
            if (entity.verificationStatus == ModelVerificationStatus.VerifiedRecommended.name) {
                return@forEach
            }
            val model = entity.recommendedModelId?.let { catalogRecommendedModel(it) } ?: return@forEach
            val file = File(entity.path)
            if (!currentFileMatchesRecommendedModel(file, model)) return@forEach
            modelDao.upsert(
                entity.copy(
                    fileBytes = file.length().coerceAtLeast(0L),
                    sourceRevision = model.sourceRevision,
                    verifiedSha256 = model.sha256Hex,
                    verificationStatus = ModelVerificationStatus.VerifiedRecommended.name,
                ),
            )
            changed = true
        }
        return changed
    }

    private fun markRecommendedVerificationFailed(entity: InstalledModelEntity) {
        val file = File(entity.path)
        modelDao.upsert(
            entity.copy(
                fileBytes = file.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L,
                verifiedSha256 = null,
                verificationStatus = ModelVerificationStatus.FailedVerification.name,
            ),
        )
    }

    private fun hasCurrentVerifiedRecommendedFile(
        entity: InstalledModelEntity,
        model: RecommendedModel,
    ): Boolean {
        val file = File(entity.path)
        return currentFileMatchesRecommendedModel(file, model)
    }
}

internal fun ModelDownloadSource.toPersistedJson(): JSONObject =
    JSONObject()
        .put("title", title)
        .put("fileName", fileName)
        .put("downloadUrl", downloadUrlWithoutSensitiveQuery(downloadUrl))
        .put("expectedBytes", expectedBytes ?: JSONObject.NULL)
        .put("expectedSha256", expectedSha256 ?: JSONObject.NULL)
        .put("modelId", modelId ?: JSONObject.NULL)
        .put("registerInstalledModel", registerInstalledModel)
        .put("requiresHuggingFaceAuthorization", requiresHuggingFaceAuthorization)

internal fun modelDownloadSourceFromPersistedJson(encoded: String): ModelDownloadSource? =
    runCatching {
        val json = JSONObject(encoded)
        ModelDownloadSource(
            title = json.optString("title", "模型下载"),
            fileName = ModelCatalog.sanitizeModelName(json.getString("fileName")),
            downloadUrl = downloadUrlWithoutSensitiveQuery(json.getString("downloadUrl")),
            expectedBytes = json.optLongOrNull("expectedBytes"),
            expectedSha256 = json.optStringOrNull("expectedSha256"),
            modelId = json.optStringOrNull("modelId"),
            registerInstalledModel = json.optBoolean("registerInstalledModel", true),
            requiresHuggingFaceAuthorization = json.optBoolean("requiresHuggingFaceAuthorization", false),
        )
    }.getOrNull()

internal fun sanitizePersistedDownloadSourceJson(encoded: String): String? =
    modelDownloadSourceFromPersistedJson(encoded)?.toPersistedJson()?.toString()

internal fun downloadUrlWithoutSensitiveQuery(downloadUrl: String): String =
    runCatching {
        val uri = URI(downloadUrl)
        if (uri.scheme != null && uri.host != null) {
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null)
        } else {
            URI(uri.scheme, uri.authority, uri.path, null, null)
        }.toString()
    }.getOrDefault(downloadUrl.substringBefore('?').substringBefore('#'))

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (isNull(name)) null else optLong(name).takeIf { it > 0L }

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

internal fun verifiedRecommendedModelPath(
    models: List<InstalledModelEntity>,
    capability: ModelCapability,
    currentFileVerifier: (InstalledModelEntity, RecommendedModel) -> Boolean =
        ::currentFileMatchesVerifiedRecommendedModel,
): String? =
    verifiedRecommendedModelPath(
        models = models,
        currentFileVerifier = currentFileVerifier,
        modelPredicate = { model -> model.capability == capability },
    )

internal fun verifiedObservationActionPlanningModelPath(
    models: List<InstalledModelEntity>,
    activeInstalledModelId: String?,
    currentFileVerifier: (InstalledModelEntity, RecommendedModel) -> Boolean =
        ::currentFileMatchesVerifiedRecommendedModel,
): String? =
    verifiedRecommendedModelPath(
        models = models.sortedWith(
            compareBy { model ->
                observationActionPlanningModelPriority(
                    recommendedModelId = model.recommendedModelId,
                    isActiveInstalledModel = model.id == activeInstalledModelId,
                )
            },
        ),
        currentFileVerifier = currentFileVerifier,
        modelPredicate = { model -> ModelCatalog.profileFor(model).supportsMobileActionPlanning },
    )

private fun observationActionPlanningModelPriority(
    recommendedModelId: String?,
    isActiveInstalledModel: Boolean,
): Int =
    when {
        isActiveInstalledModel && recommendedModelId in CHAT_ACTION_PLANNING_MODEL_IDS -> 0
        recommendedModelId == DEFAULT_CHAT_MODEL_ID -> 1
        recommendedModelId == HIGH_QUALITY_CHAT_MODEL_ID -> 2
        recommendedModelId == MOBILE_ACTION_MODEL_ID -> 3
        else -> 4
    }

private val CHAT_ACTION_PLANNING_MODEL_IDS = setOf(
    DEFAULT_CHAT_MODEL_ID,
    HIGH_QUALITY_CHAT_MODEL_ID,
)

private fun verifiedRecommendedModelPath(
    models: List<InstalledModelEntity>,
    currentFileVerifier: (InstalledModelEntity, RecommendedModel) -> Boolean,
    modelPredicate: (RecommendedModel) -> Boolean,
): String? =
    models.firstOrNull { model ->
        val catalogModel = model.recommendedModelId?.let { catalogRecommendedModel(it) }
            ?: return@firstOrNull false
        modelPredicate(catalogModel) &&
            model.hasVerifiedRecommendedEvidence(catalogModel) &&
            currentFileVerifier(model, catalogModel)
    }?.path

internal fun installedModelCanBecomeActiveChatModel(model: InstalledModelEntity): Boolean =
    if (model.recommendedModelId == null) {
        true
    } else {
        ModelCatalog.profileForModelIdOrNull(model.recommendedModelId)?.supportsChatGeneration == true &&
            model.verificationStatus == ModelVerificationStatus.VerifiedRecommended.name
    }

internal fun installedModelCanBecomeCurrentActiveChatModel(
    model: InstalledModelEntity,
    currentFileVerifier: (InstalledModelEntity, RecommendedModel) -> Boolean =
        ::currentFileMatchesVerifiedRecommendedModel,
): Boolean {
    if (model.recommendedModelId == null) return true
    val catalogModel = catalogRecommendedModel(model.recommendedModelId) ?: return false
    return ModelCatalog.profileFor(catalogModel).supportsChatGeneration &&
        model.hasVerifiedRecommendedEvidence(catalogModel) &&
        currentFileVerifier(model, catalogModel)
}

internal fun recommendedRegistrationVerificationStatus(
    file: File,
    model: RecommendedModel,
    verifiedSha256: String?,
    requestedStatus: ModelVerificationStatus,
): ModelVerificationStatus =
    when {
        requestedStatus == ModelVerificationStatus.FailedVerification ->
            ModelVerificationStatus.FailedVerification
        verifiedSha256 != null &&
            verifiedSha256.equals(model.sha256Hex, ignoreCase = true) &&
            currentFileMatchesRecommendedModel(file, model) ->
            ModelVerificationStatus.VerifiedRecommended
        requestedStatus == ModelVerificationStatus.LegacyUnverified ->
            ModelVerificationStatus.LegacyUnverified
        else -> ModelVerificationStatus.FailedVerification
    }

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
    return currentFileMatchesRecommendedModel(File(entity.path), model)
}

internal fun currentFileMatchesRecommendedModel(
    file: File,
    model: RecommendedModel,
): Boolean =
    file.isFile &&
        file.length() == model.byteSize &&
        ModelCatalog.hasCompleteCompanionFiles(file, model) &&
        ModelCatalog.matchesExpectedSha256(file, model.sha256Hex)

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
