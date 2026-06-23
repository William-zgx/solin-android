package com.bytedance.zgx.pocketmind.data

import android.net.Uri
import com.bytedance.zgx.pocketmind.BackendChoice
import com.bytedance.zgx.pocketmind.ChatMessage
import com.bytedance.zgx.pocketmind.ChatSessionSummary
import com.bytedance.zgx.pocketmind.GenerationParameters
import com.bytedance.zgx.pocketmind.InferenceMode
import com.bytedance.zgx.pocketmind.PendingRemoteSendMarker
import com.bytedance.zgx.pocketmind.RemoteModelConfig
import java.io.File

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

interface ActiveSessionStore {
    fun activeSessionId(): String?
    fun saveActiveSessionId(sessionId: String)
}

interface ModelStore {
    fun currentState(): ModelSelectionState
    fun selectedRecommendedModel(): com.bytedance.zgx.pocketmind.RecommendedModel
    fun selectRecommendedModel(modelId: String): ModelSelectionResult
    fun selectInstalledModel(modelId: String): com.bytedance.zgx.pocketmind.InstalledModelSummary?
    fun deleteInstalledModel(modelId: String): Boolean
    fun registerInstalledModel(
        path: String,
        displayName: String,
        recommendedModelId: String?,
        verifiedSha256: String? = null,
        verificationStatus: ModelVerificationStatus = ModelVerificationStatus.UnverifiedCustom,
    ): com.bytedance.zgx.pocketmind.InstalledModelSummary
}

interface ModelRepositoryFacade : ModelStore, DownloadStore {
    fun createCustomDownloadSource(downloadUrl: String): ModelDownloadSource?
    fun downloadedModelFile(fileName: String): File?
    fun resolveModelStorageBytes(): Long
    fun verifiedActionModelPath(): String?
    fun verifiedMemoryEmbeddingModelPath(): String?
    fun verifyLegacyRecommendedModels(): Boolean
    fun importModel(uri: Uri, onProgress: (TransferProgress) -> Unit): String
}

interface GenerationParametersStore {
    fun load(): GenerationParameters
    fun save(parameters: GenerationParameters): GenerationParameters
    fun reset(): GenerationParameters
    fun loadBackend(): BackendChoice
    fun saveBackend(backend: BackendChoice)
}

interface FirstRunSetupStore {
    fun isSetupDismissed(): Boolean
    fun markSetupDismissed()
    fun isMemoryEnabled(): Boolean
    fun setMemoryEnabled(enabled: Boolean)
    fun reduceDeviceActionConfirmations(): Boolean = false
    fun setReduceDeviceActionConfirmations(enabled: Boolean) = Unit
}

interface RemoteModelStore {
    fun loadMode(): InferenceMode
    fun saveMode(mode: InferenceMode): InferenceMode
    fun loadConfig(): RemoteModelConfig
    fun saveConfig(config: RemoteModelConfig): Result<RemoteModelConfig>
    fun saveConfigWithoutApiKey(config: RemoteModelConfig): Result<RemoteModelConfig> =
        saveConfig(config.copy(apiKey = ""))
}

interface RemoteSendPendingStore {
    fun savePendingRemoteSend(marker: PendingRemoteSendMarker)
    fun consumePendingRemoteSend(): PendingRemoteSendMarker?
    fun clearPendingRemoteSend()
}

object NoOpRemoteSendPendingStore : RemoteSendPendingStore {
    override fun savePendingRemoteSend(marker: PendingRemoteSendMarker) = Unit
    override fun consumePendingRemoteSend(): PendingRemoteSendMarker? = null
    override fun clearPendingRemoteSend() = Unit
}

interface SettingsStore {
    fun isSetupDismissed(): Boolean
    fun markSetupDismissed()
    fun isMemoryEnabled(): Boolean
    fun setMemoryEnabled(enabled: Boolean)
    fun reduceDeviceActionConfirmations(): Boolean = false
    fun setReduceDeviceActionConfirmations(enabled: Boolean) = Unit
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
