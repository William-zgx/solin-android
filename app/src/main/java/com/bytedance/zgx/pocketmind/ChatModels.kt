package com.bytedance.zgx.pocketmind

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.data.ModelVerificationStatus
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.memory.MemoryRecordType
import com.bytedance.zgx.pocketmind.tool.ToolRequest
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

enum class MessagePrivacy {
    RemoteEligible,
    LocalOnly,
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
    val privacy: MessagePrivacy = MessagePrivacy.RemoteEligible,
)

fun List<ChatMessage>.remoteEligibleMessages(): List<ChatMessage> =
    filter { it.privacy == MessagePrivacy.RemoteEligible }

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

data class LongTermMemorySummary(
    val id: String,
    val type: MemoryRecordType,
    val text: String,
)

data class PendingAgentConfirmation(
    val runId: String?,
    val draft: ActionDraft,
    val toolRequest: ToolRequest?,
    val skillId: String?,
    val plannedByModel: Boolean,
    val fallbackReason: String?,
)

data class InstalledModelSummary(
    val id: String,
    val displayName: String,
    val path: String,
    val fileBytes: Long,
    val recommendedModelId: String?,
    val verifiedSha256: String? = null,
    val verificationStatus: ModelVerificationStatus = ModelVerificationStatus.UnverifiedCustom,
) {
    val fileName: String
        get() = File(path).name

    val capability: ModelCapability
        get() = recommendedModelId
            ?.let { ModelCatalog.recommendedModelById(it).capability }
            ?: ModelCapability.Chat

    val isUsable: Boolean
        get() = recommendedModelId == null ||
            verificationStatus == ModelVerificationStatus.VerifiedRecommended
}

data class ChatUiState(
    val modelPath: String? = null,
    val activeInstalledModelId: String? = null,
    val installedModels: List<InstalledModelSummary> = emptyList(),
    val selectedModelId: String = DEFAULT_CHAT_MODEL_ID,
    val recommendedModels: List<RecommendedModel> = RECOMMENDED_MODELS,
    val setupSelectedModelIds: Set<String> = ModelCatalog.defaultSetupModelIds(),
    val showFirstRunSetup: Boolean = false,
    val memoryEnabled: Boolean = true,
    val memoryHits: List<MemoryHit> = emptyList(),
    val longTermMemories: List<LongTermMemorySummary> = emptyList(),
    val pendingConfirmation: PendingAgentConfirmation? = null,
    val inferenceMode: InferenceMode = InferenceMode.Local,
    val remoteModelConfig: RemoteModelConfig = RemoteModelConfig(),
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
        get() = ModelCatalog.recommendedChatModelById(selectedModelId)

    val basicSetupModels: List<RecommendedModel>
        get() = ModelCatalog.basicSetupModels()

    val chatModels: List<RecommendedModel>
        get() = ModelCatalog.recommendedModelsFor(ModelCapability.Chat)

    val optionalChatModels: List<RecommendedModel>
        get() = ModelCatalog.optionalChatModels()

    val installedCapabilities: Set<ModelCapability>
        get() = installedModels
            .filter { it.isUsable }
            .map { it.capability }
            .toSet()

    fun isModelInstalled(modelId: String): Boolean =
        installedModels.any { it.recommendedModelId == modelId && it.isUsable }
}

fun BackendChoice.label(): String =
    when (this) {
        BackendChoice.CPU -> "CPU"
        BackendChoice.GPU -> "GPU"
    }
