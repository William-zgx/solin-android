package com.bytedance.zgx.pocketmind

import android.app.DownloadManager
import java.util.Locale

internal const val MODEL_FILE_EXTENSION = ".litertlm"

enum class ModelCapability {
    Chat,
    MemoryEmbedding,
    MobileAction,
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
    val repositoryUrl: String,
    val downloadUrl: String,
    val deviceHint: String,
    val capability: ModelCapability,
    val setupTier: SetupTier,
)

const val DEFAULT_CHAT_MODEL_ID = "chat-e2b"
const val MEMORY_EMBEDDING_MODEL_ID = "memory-embedding-300m"
const val MOBILE_ACTION_MODEL_ID = "mobile-action-270m"

val RECOMMENDED_MODELS = listOf(
    RecommendedModel(
        id = DEFAULT_CHAT_MODEL_ID,
        displayName = "基础对话模型 E2B",
        shortName = "基础对话 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        byteSize = 2_588_147_712L,
        repositoryUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
        deviceHint = "更适合大多数手机，下载约 2.4 GB",
        capability = ModelCapability.Chat,
        setupTier = SetupTier.BasicRecommended,
    ),
    RecommendedModel(
        id = MEMORY_EMBEDDING_MODEL_ID,
        displayName = "本地记忆模型 300M",
        shortName = "本地记忆模型",
        fileName = "embeddinggemma-300m.litertlm",
        byteSize = 179_159_040L,
        repositoryUrl = "https://huggingface.co/kontextdev/embeddinggemma-300m-litertlm",
        downloadUrl = "https://huggingface.co/kontextdev/embeddinggemma-300m-litertlm/resolve/main/embeddinggemma-300m.litertlm?download=true",
        deviceHint = "本地记忆与语义检索，下载约 171 MB",
        capability = ModelCapability.MemoryEmbedding,
        setupTier = SetupTier.BasicRecommended,
    ),
    RecommendedModel(
        id = MOBILE_ACTION_MODEL_ID,
        displayName = "设备动作模型 270M",
        shortName = "设备动作模型",
        fileName = "mobile-actions_q8_ekv1024.litertlm",
        byteSize = 284_426_240L,
        repositoryUrl = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm?download=true",
        deviceHint = "设备动作草稿与函数调用，下载约 271 MB",
        capability = ModelCapability.MobileAction,
        setupTier = SetupTier.BasicRecommended,
    ),
    RecommendedModel(
        id = "chat-e4b",
        displayName = "高质量对话模型 E4B",
        shortName = "高质量对话 E4B",
        fileName = "gemma-4-E4B-it.litertlm",
        byteSize = 3_659_530_240L,
        repositoryUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
        deviceHint = "质量更高但更吃内存，下载约 3.4 GB",
        capability = ModelCapability.Chat,
        setupTier = SetupTier.OptionalChat,
    ),
)

internal val DEFAULT_CHAT_MODEL: RecommendedModel =
    RECOMMENDED_MODELS.first { it.id == DEFAULT_CHAT_MODEL_ID }
internal val DEFAULT_CHAT_MODEL_BYTES: Long = DEFAULT_CHAT_MODEL.byteSize

internal object ModelCatalog {
    fun recommendedModelById(modelId: String?): RecommendedModel =
        RECOMMENDED_MODELS.firstOrNull { it.id == modelId } ?: DEFAULT_CHAT_MODEL

    fun recommendedChatModelById(modelId: String?): RecommendedModel =
        RECOMMENDED_MODELS.firstOrNull {
            it.id == modelId && it.capability == ModelCapability.Chat
        } ?: DEFAULT_CHAT_MODEL

    fun recommendedModelsFor(capability: ModelCapability): List<RecommendedModel> =
        RECOMMENDED_MODELS.filter { it.capability == capability }

    fun basicSetupModels(): List<RecommendedModel> =
        RECOMMENDED_MODELS.filter { it.setupTier == SetupTier.BasicRecommended }

    fun optionalChatModels(): List<RecommendedModel> =
        RECOMMENDED_MODELS.filter {
            it.capability == ModelCapability.Chat && it.setupTier == SetupTier.OptionalChat
        }

    fun defaultSetupModelIds(): Set<String> =
        basicSetupModels().map { it.id }.toSet()

    fun capabilityForModelId(modelId: String?): ModelCapability =
        recommendedModelById(modelId).capability

    fun isChatModel(modelId: String?): Boolean =
        modelId == null || recommendedModelById(modelId).capability == ModelCapability.Chat

    fun isAcceptedModelName(displayName: String): Boolean =
        displayName.endsWith(MODEL_FILE_EXTENSION, ignoreCase = true)

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
            else -> "代码 $reason"
        }
}
