package com.bytedance.zgx.gemmalocalqa

import java.io.File
import java.util.concurrent.atomic.AtomicLong

private val nextMessageId = AtomicLong(0L)

enum class BackendChoice {
    CPU,
    GPU,
}

enum class MessageRole {
    User,
    Assistant,
}

data class GenerationParameters(
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topP: Float = DEFAULT_TOP_P,
    val topK: Int = DEFAULT_TOP_K,
) {
    companion object {
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_P = 0.95f
        const val DEFAULT_TOP_K = 40

        const val MIN_TEMPERATURE = 0.0f
        const val MAX_TEMPERATURE = 1.2f
        const val MIN_TOP_P = 0.1f
        const val MAX_TOP_P = 1.0f
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 100
    }
}

data class ChatMessage(
    val role: MessageRole,
    val text: String,
    val generationStats: GenerationStats? = null,
    val id: Long = nextMessageId.incrementAndGet(),
)

data class GenerationStats(
    val tokenCount: Int,
    val tokensPerSecond: Double,
)

fun GenerationStats.isUsable(): Boolean =
    tokenCount > 0 &&
        tokensPerSecond > 0.0 &&
        !tokensPerSecond.isNaN() &&
        !tokensPerSecond.isInfinite()

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
    val generationParameters: GenerationParameters = GenerationParameters(),
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

fun BackendChoice.label(): String =
    when (this) {
        BackendChoice.CPU -> "CPU"
        BackendChoice.GPU -> "GPU"
    }
