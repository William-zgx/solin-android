package com.bytedance.zgx.pocketmind.data

import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.ChatSessionSummary
import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.RemoteModelConfig

interface SessionStore {
    val activeSessionId: String
    fun summaries(): List<ChatSessionSummary>
    fun activeMessages(): List<ChatMessage>
    fun allMessages(limit: Int = Int.MAX_VALUE): List<ChatMessage>
    fun createNewSession(): List<ChatMessage>
    fun selectSession(sessionId: String): List<ChatMessage>?
    fun deleteActiveSession(): List<ChatMessage>?
    fun replaceActiveSessionMessages(messages: List<ChatMessage>, persistNow: Boolean)
    fun persistActiveSessionFrom(messages: List<ChatMessage>)
}

interface ModelStore {
    fun currentState(): ModelSelectionState
    fun selectedRecommendedModel(): com.bytedance.zgx.pocketmind.RecommendedModel
    fun selectRecommendedModel(modelId: String): ModelSelectionResult
    fun selectInstalledModel(modelId: String): com.bytedance.zgx.pocketmind.InstalledModelSummary?
    fun registerInstalledModel(
        path: String,
        displayName: String,
        recommendedModelId: String?,
        verifiedSha256: String? = null,
        verificationStatus: ModelVerificationStatus = ModelVerificationStatus.UnverifiedCustom,
    ): com.bytedance.zgx.pocketmind.InstalledModelSummary
}

interface SettingsStore {
    fun isSetupDismissed(): Boolean
    fun markSetupDismissed()
    fun isMemoryEnabled(): Boolean
    fun setMemoryEnabled(enabled: Boolean)
    fun loadGenerationParameters(): GenerationParameters
    fun saveGenerationParameters(parameters: GenerationParameters): GenerationParameters
    fun loadBackend(): BackendChoice
    fun saveBackend(backend: BackendChoice)
    fun loadInferenceMode(): InferenceMode
    fun saveInferenceMode(mode: InferenceMode): InferenceMode
    fun loadRemoteConfig(apiKey: String): RemoteModelConfig
    fun saveRemoteConfig(config: RemoteModelConfig): RemoteModelConfig
}

interface SecretStore {
    fun loadString(name: String): Result<String>
    fun saveString(name: String, value: String): Result<Unit>
}

interface DownloadStore {
    fun pendingDownloadId(): Long
    fun savePendingDownload(downloadId: Long, source: ModelDownloadSource)
    fun clearPendingDownload()
    fun loadPendingDownloadSource(): ModelDownloadSource?
}

enum class ModelVerificationStatus {
    VerifiedRecommended,
    UnverifiedCustom,
    LegacyUnverified,
    FailedVerification,
}
