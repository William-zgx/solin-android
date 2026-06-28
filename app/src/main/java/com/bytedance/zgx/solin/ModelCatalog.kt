package com.bytedance.zgx.solin

import android.app.DownloadManager
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal const val MODEL_FILE_EXTENSION = ".litertlm"
internal const val TFLITE_MODEL_FILE_EXTENSION = ".tflite"
internal const val TOKENIZER_MODEL_FILE_EXTENSION = ".model"
private val MODEL_FILE_EXTENSIONS = setOf(
    MODEL_FILE_EXTENSION,
    TFLITE_MODEL_FILE_EXTENSION,
    TOKENIZER_MODEL_FILE_EXTENSION,
)

enum class ModelCapability {
    Chat,
    MemoryEmbedding,
    MobileAction,
}

enum class ModelInputModality {
    Text,
    Vision,
}

enum class ModelFeature {
    TextGeneration,
    VisionInput,
    MemoryEmbedding,
    MobileActionPlanning,
}

enum class ModelBackendKind {
    LocalLiteRt,
    RemoteOpenAiCompatible,
}

enum class ModelHealthState {
    NotInstalled,
    InstalledUnverified,
    Verified,
    Loading,
    Loaded,
    LoadFailed,
    FallbackActive,
}

data class ModelProfile(
    val id: String,
    val displayName: String,
    val capability: ModelCapability,
    val backendKind: ModelBackendKind,
    val inputModalities: Set<ModelInputModality>,
    val features: Set<ModelFeature>,
    val byteSize: Long? = null,
    val sha256Hex: String? = null,
    val sourceRevision: String? = null,
    val tokenBudget: Int? = null,
    val preferredLocalBackends: Set<BackendChoice> = emptySet(),
    val experimental: Boolean = false,
) {
    val supportsChatGeneration: Boolean
        get() = ModelFeature.TextGeneration in features

    val supportsVisionInput: Boolean
        get() = ModelInputModality.Vision in inputModalities && ModelFeature.VisionInput in features

    val supportsMemoryEmbedding: Boolean
        get() = ModelFeature.MemoryEmbedding in features

    val supportsMobileActionPlanning: Boolean
        get() = ModelFeature.MobileActionPlanning in features

    val contextWindowTokens: Int?
        get() = tokenBudget

    val remoteEligible: Boolean
        get() = backendKind == ModelBackendKind.RemoteOpenAiCompatible

    val requiresRemoteSendConfirmation: Boolean
        get() = remoteEligible

    init {
        require(ModelInputModality.Text in inputModalities) { "Model profiles must declare text input support" }
        require(ModelInputModality.Vision !in inputModalities || capability == ModelCapability.Chat) {
            "Vision input modality is only valid for chat-capable profiles"
        }
        require(ModelFeature.VisionInput !in features || ModelInputModality.Vision in inputModalities) {
            "VisionInput feature requires Vision input modality"
        }
        require(ModelFeature.VisionInput !in features || capability == ModelCapability.Chat) {
            "Vision input is only valid for chat-capable profiles"
        }
        require(ModelFeature.TextGeneration !in features || capability == ModelCapability.Chat) {
            "Text generation is only valid for chat-capable profiles"
        }
        require(ModelFeature.MemoryEmbedding !in features || capability == ModelCapability.MemoryEmbedding) {
            "Memory embedding is only valid for embedding profiles"
        }
        require(ModelFeature.MobileActionPlanning !in features || capability == ModelCapability.MobileAction) {
            "Mobile action planning is only valid for action profiles"
        }
        require(backendKind != ModelBackendKind.RemoteOpenAiCompatible || capability == ModelCapability.Chat) {
            "Remote OpenAI-compatible profiles are only valid for chat capability"
        }
        require(preferredLocalBackends.isEmpty() || backendKind == ModelBackendKind.LocalLiteRt) {
            "Only local LiteRT profiles may declare local performance backends"
        }
        require(tokenBudget == null || tokenBudget > 0) {
            "Model context window must be positive when declared"
        }
        require(tokenBudget == null || capability == ModelCapability.Chat) {
            "Model context window is only valid for chat-capable profiles"
        }
    }
}

typealias ModelCapabilityProfile = ModelProfile

data class ModelHealth(
    val profileId: String,
    val state: ModelHealthState,
    val backend: BackendChoice? = null,
    val loadMs: Long? = null,
    val firstTokenMs: Long? = null,
    val tokenCount: Int? = null,
    val tokensPerSecond: Double? = null,
    val fallbackBackend: BackendChoice? = null,
    val failureReason: String? = null,
    val lastOutputQualityIssue: String? = null,
    val lastOutputQualityRule: String? = null,
    val lastOutputQualityAtMillis: Long? = null,
) {
    val usedFallbackBackend: Boolean
        get() = fallbackBackend != null
}

enum class SetupTier {
    BasicRecommended,
    OptionalChat,
}

data class RecommendedModel(
    val id: String,
    val displayName: String,
    val shortName: String,
    val fileName: String,
    val byteSize: Long,
    val sourceRevision: String,
    val sha256Hex: String,
    val repositoryUrl: String,
    val downloadUrl: String,
    val deviceHint: String,
    val capability: ModelCapability,
    val setupTier: SetupTier,
    val requiresHuggingFaceAuthorization: Boolean = false,
    val companionFiles: List<RecommendedModelCompanionFile> = emptyList(),
)

data class RecommendedModelCompanionFile(
    val fileName: String,
    val byteSize: Long,
    val sha256Hex: String,
    val downloadUrl: String,
    val requiresHuggingFaceAuthorization: Boolean = false,
)

const val DEFAULT_CHAT_MODEL_ID = "chat-e2b"
const val MEMORY_EMBEDDING_MODEL_ID = "memory-embedding-gemma-300m"
const val MOBILE_ACTION_MODEL_ID = "mobile-action-270m"
const val CUSTOM_LOCAL_CHAT_PROFILE_ID = "custom-local-chat"
const val HUGGING_FACE_TOKEN_SETTINGS_URL = "https://huggingface.co/settings/tokens"
private const val HIGH_QUALITY_CHAT_MODEL_ID = "chat-e4b"

private val LOCAL_VISION_INPUT_MODEL_IDS = setOf(
    DEFAULT_CHAT_MODEL_ID,
    HIGH_QUALITY_CHAT_MODEL_ID,
)

val RECOMMENDED_MODELS = listOf(
    RecommendedModel(
        id = DEFAULT_CHAT_MODEL_ID,
        displayName = "基础对话模型 E2B",
        shortName = "基础对话 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        byteSize = 2_588_147_712L,
        sourceRevision = "a4a831c060880f3733135ad22f10e0e9f758f45d",
        sha256Hex = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        repositoryUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/a4a831c060880f3733135ad22f10e0e9f758f45d/gemma-4-E2B-it.litertlm?download=true",
        deviceHint = "更适合大多数手机，下载约 2.4 GB",
        capability = ModelCapability.Chat,
        setupTier = SetupTier.BasicRecommended,
    ),
    RecommendedModel(
        id = MEMORY_EMBEDDING_MODEL_ID,
        displayName = "本地记忆模型 EmbeddingGemma 300M",
        shortName = "本地记忆模型",
        fileName = "embeddinggemma-300M_seq256_mixed-precision.tflite",
        byteSize = 179_131_736L,
        sourceRevision = "870cbe05ef460385363c6b574c851ae5d8989ce3",
        sha256Hex = "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5",
        repositoryUrl = "https://huggingface.co/litert-community/embeddinggemma-300m",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/870cbe05ef460385363c6b574c851ae5d8989ce3/embeddinggemma-300M_seq256_mixed-precision.tflite?download=true",
        deviceHint = "真实端侧语义召回，需要 Hugging Face 授权，下载约 175 MB",
        capability = ModelCapability.MemoryEmbedding,
        setupTier = SetupTier.BasicRecommended,
        requiresHuggingFaceAuthorization = true,
        companionFiles = listOf(
            RecommendedModelCompanionFile(
                fileName = "sentencepiece.model",
                byteSize = 4_683_319L,
                sha256Hex = "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7",
                downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/870cbe05ef460385363c6b574c851ae5d8989ce3/sentencepiece.model?download=true",
                requiresHuggingFaceAuthorization = true,
            ),
        ),
    ),
    RecommendedModel(
        id = MOBILE_ACTION_MODEL_ID,
        displayName = "设备动作模型 270M",
        shortName = "设备动作模型",
        fileName = "mobile-actions_q8_ekv1024.litertlm",
        byteSize = 284_426_240L,
        sourceRevision = "82d0f654a6270c518d16c600edce3136221b3347",
        sha256Hex = "92109695f911d1872fa8ae07c1e3ff0ed70f2c3d1690d410ec6db8587c2ab409",
        repositoryUrl = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/82d0f654a6270c518d16c600edce3136221b3347/mobile-actions_q8_ekv1024.litertlm?download=true",
        deviceHint = "实验资产；当前动作使用本地规则草稿，下载约 271 MB",
        capability = ModelCapability.MobileAction,
        setupTier = SetupTier.BasicRecommended,
    ),
    RecommendedModel(
        id = HIGH_QUALITY_CHAT_MODEL_ID,
        displayName = "高质量对话模型 E4B",
        shortName = "高质量对话 E4B",
        fileName = "gemma-4-E4B-it.litertlm",
        byteSize = 3_659_530_240L,
        sourceRevision = "65ce5ba80d8790d66ef11d82d7d079a06f3fef97",
        sha256Hex = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        repositoryUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/65ce5ba80d8790d66ef11d82d7d079a06f3fef97/gemma-4-E4B-it.litertlm?download=true",
        deviceHint = "质量更高但更吃内存，下载约 3.4 GB",
        capability = ModelCapability.Chat,
        setupTier = SetupTier.OptionalChat,
    ),
)

internal val DEFAULT_CHAT_MODEL: RecommendedModel =
    RECOMMENDED_MODELS.first { it.id == DEFAULT_CHAT_MODEL_ID }
internal val DEFAULT_CHAT_MODEL_BYTES: Long = DEFAULT_CHAT_MODEL.byteSize

internal object ModelCatalog {
    val remoteVisionProfile: ModelProfile =
        ModelProfile(
            id = "remote-openai-compatible-vision",
            displayName = "远程视觉模型",
            capability = ModelCapability.Chat,
            backendKind = ModelBackendKind.RemoteOpenAiCompatible,
            inputModalities = setOf(ModelInputModality.Text, ModelInputModality.Vision),
            features = setOf(ModelFeature.TextGeneration, ModelFeature.VisionInput),
        )

    fun recommendedModelById(modelId: String?): RecommendedModel =
        RECOMMENDED_MODELS.firstOrNull { it.id == modelId } ?: DEFAULT_CHAT_MODEL

    fun recommendedModelOrNull(modelId: String?): RecommendedModel? =
        modelId?.let { id -> RECOMMENDED_MODELS.firstOrNull { it.id == id } }

    fun recommendedChatModelById(modelId: String?): RecommendedModel =
        RECOMMENDED_MODELS.firstOrNull {
            it.id == modelId && profileFor(it).supportsChatGeneration
        } ?: DEFAULT_CHAT_MODEL

    fun recommendedModelsFor(capability: ModelCapability): List<RecommendedModel> =
        RECOMMENDED_MODELS.filter { it.capability == capability }

    fun profileFor(model: RecommendedModel): ModelProfile =
        ModelProfile(
            id = model.id,
            displayName = model.displayName,
            capability = model.capability,
            backendKind = ModelBackendKind.LocalLiteRt,
            inputModalities = model.inputModalities(),
            features = model.features(),
            byteSize = model.byteSize,
            sha256Hex = model.sha256Hex,
            sourceRevision = model.sourceRevision,
            tokenBudget = if (model.capability == ModelCapability.Chat) {
                LocalModelTokenLimits.MAX_TOTAL_TOKENS
            } else {
                null
            },
            preferredLocalBackends = model.defaultPreferredLocalBackends(),
            experimental = model.capability != ModelCapability.Chat,
        )

    fun recommendedProfiles(): List<ModelProfile> =
        RECOMMENDED_MODELS.map(::profileFor)

    fun customLocalChatProfile(displayName: String = "自定义本地模型"): ModelProfile =
        ModelProfile(
            id = CUSTOM_LOCAL_CHAT_PROFILE_ID,
            displayName = displayName.ifBlank { "自定义本地模型" },
            capability = ModelCapability.Chat,
            backendKind = ModelBackendKind.LocalLiteRt,
            inputModalities = setOf(ModelInputModality.Text),
            features = setOf(ModelFeature.TextGeneration),
        )

    fun profileForModelId(modelId: String?): ModelProfile =
        profileFor(recommendedModelById(modelId))

    fun profileForModelIdOrNull(modelId: String?): ModelProfile? =
        recommendedModelOrNull(modelId)?.let(::profileFor)

    fun basicSetupModels(): List<RecommendedModel> =
        RECOMMENDED_MODELS.filter { it.setupTier == SetupTier.BasicRecommended }

    fun optionalChatModels(): List<RecommendedModel> =
        RECOMMENDED_MODELS.filter {
            it.capability == ModelCapability.Chat && it.setupTier == SetupTier.OptionalChat
        }

    fun defaultSetupModelIds(): Set<String> =
        setOf(DEFAULT_CHAT_MODEL_ID)

    fun capabilityForModelId(modelId: String?): ModelCapability =
        recommendedModelById(modelId).capability

    fun isChatModel(modelId: String?): Boolean =
        modelId == null || (profileForModelIdOrNull(modelId)?.supportsChatGeneration == true)

    fun isAcceptedModelName(displayName: String): Boolean =
        MODEL_FILE_EXTENSIONS.any { extension ->
            displayName.endsWith(extension, ignoreCase = true)
        }

    fun sanitizeModelName(displayName: String): String {
        val safeName = displayName
            .trim()
            .replace(Regex("""[^\w.\-]+"""), "_")
            .ifBlank { "chat-model$MODEL_FILE_EXTENSION" }
        return if (isAcceptedModelName(safeName)) {
            safeName
        } else {
            "$safeName$MODEL_FILE_EXTENSION"
        }
    }

    fun hasEnoughSpace(usableBytes: Long, requiredBytes: Long = DEFAULT_CHAT_MODEL_BYTES): Boolean =
        usableBytes >= requiredBytes

    fun isCompleteRecommendedModel(
        fileBytes: Long,
        model: RecommendedModel = DEFAULT_CHAT_MODEL,
    ): Boolean = fileBytes == model.byteSize

    fun recommendedModelCompanionFile(
        primaryFile: File,
        companion: RecommendedModelCompanionFile,
    ): File =
        File(
            primaryFile.parentFile
                ?: primaryFile.absoluteFile.parentFile
                ?: File("."),
            companion.fileName,
        )

    fun hasCompleteCompanionFiles(primaryFile: File, model: RecommendedModel): Boolean =
        model.companionFiles.all { companion ->
            val file = recommendedModelCompanionFile(primaryFile, companion)
            file.exists() &&
                file.length() == companion.byteSize &&
                matchesExpectedSha256(file, companion.sha256Hex)
        }

    fun hasCompleteRecommendedBundle(primaryFile: File, model: RecommendedModel): Boolean =
        isVerifiedRecommendedModel(primaryFile, model) &&
            hasCompleteCompanionFiles(primaryFile, model)

    fun isVerifiedRecommendedModel(
        file: File,
        model: RecommendedModel = DEFAULT_CHAT_MODEL,
    ): Boolean =
        isCompleteRecommendedModel(file.length(), model) &&
            matchesExpectedSha256(file, model.sha256Hex)

    fun matchesExpectedSha256(file: File, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrBlank()) return file.length() > 0L
        return sha256Hex(file).equals(expectedSha256, ignoreCase = true)
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xff)
        }
    }

    fun progressPercent(doneBytes: Long, totalBytes: Long): Int? {
        if (totalBytes <= 0L) return null
        return ((doneBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    fun downloadStatusText(status: Int, reason: Int): String =
        when (status) {
            DownloadManager.STATUS_PENDING -> "等待下载"
            DownloadManager.STATUS_PAUSED -> "下载暂停：${downloadReasonText(reason)}"
            DownloadManager.STATUS_RUNNING -> "模型下载中"
            DownloadManager.STATUS_SUCCESSFUL -> "模型下载完成"
            DownloadManager.STATUS_FAILED -> "下载失败：${downloadReasonText(reason)}"
            else -> "下载状态未知"
        }

    fun downloadReasonText(reason: Int): String =
        when (reason) {
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "等待 Wi-Fi"
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "等待网络"
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "等待重试"
            DownloadManager.ERROR_CANNOT_RESUME -> "无法继续"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "存储不可用"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "文件已存在"
            DownloadManager.ERROR_FILE_ERROR -> "文件写入失败"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络数据错误"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载重定向过多"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器响应异常"
            DownloadManager.ERROR_UNKNOWN -> "未知错误"
            401 -> "授权无效"
            403 -> "无权访问"
            404 -> "文件不存在"
            else -> "代码 $reason"
        }
}

private fun ModelCapability.defaultFeatures(): Set<ModelFeature> =
    when (this) {
        ModelCapability.Chat -> setOf(ModelFeature.TextGeneration)
        ModelCapability.MemoryEmbedding -> setOf(ModelFeature.MemoryEmbedding)
        ModelCapability.MobileAction -> setOf(ModelFeature.MobileActionPlanning)
    }

private fun ModelCapability.defaultInputModalities(): Set<ModelInputModality> =
    when (this) {
        ModelCapability.Chat -> setOf(ModelInputModality.Text)
        ModelCapability.MemoryEmbedding,
        ModelCapability.MobileAction -> setOf(ModelInputModality.Text)
    }

private fun RecommendedModel.inputModalities(): Set<ModelInputModality> =
    if (capability == ModelCapability.Chat && id in LOCAL_VISION_INPUT_MODEL_IDS) {
        setOf(ModelInputModality.Text, ModelInputModality.Vision)
    } else {
        capability.defaultInputModalities()
    }

private fun RecommendedModel.features(): Set<ModelFeature> =
    if (capability == ModelCapability.Chat && id in LOCAL_VISION_INPUT_MODEL_IDS) {
        setOf(ModelFeature.TextGeneration, ModelFeature.VisionInput)
    } else {
        capability.defaultFeatures()
    }

private fun RecommendedModel.defaultPreferredLocalBackends(): Set<BackendChoice> =
    when (capability) {
        ModelCapability.Chat -> setOf(BackendChoice.GPU, BackendChoice.CPU)
        ModelCapability.MemoryEmbedding,
        ModelCapability.MobileAction -> setOf(BackendChoice.CPU)
    }
