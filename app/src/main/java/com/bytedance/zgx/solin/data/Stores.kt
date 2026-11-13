package com.bytedance.zgx.solin.data

import android.net.Uri
import com.bytedance.zgx.solin.BackendChoice
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatSessionSummary
import com.bytedance.zgx.solin.GenerationParameters
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.PendingRemoteSendMarker
import com.bytedance.zgx.solin.RemoteConnectivitySnapshot
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.orchestration.LocalPlanningStrategy
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

interface ModelRepositoryFacade {
    fun currentState(): ModelSelectionState
    fun verifyActiveModelPath(path: String): Boolean
    fun selectedRecommendedModel(): com.bytedance.zgx.solin.RecommendedModel
    fun selectRecommendedModel(modelId: String): ModelSelectionResult
    fun selectInstalledModel(modelId: String): com.bytedance.zgx.solin.InstalledModelSummary?
    fun deleteInstalledModel(modelId: String): Boolean
    fun registerInstalledModel(
        path: String,
        displayName: String,
        recommendedModelId: String?,
        verifiedSha256: String? = null,
        verificationStatus: ModelVerificationStatus = ModelVerificationStatus.UnverifiedCustom
    ): com.bytedance.zgx.solin.InstalledModelSummary
    fun createCustomDownloadSource(downloadUrl: String): ModelDownloadSource?
    fun downloadedModelFile(fileName: String): File?
    fun pendingDownloadId(): Long
    fun savePendingDownload(downloadId: Long, source: ModelDownloadSource)
    fun clearPendingDownload()
    fun loadPendingDownloadSource(): ModelDownloadSource?
    fun resolveModelStorageBytes(): Long
    fun verifiedActionModelPath(): String?
    fun verifiedObservationActionModelPath(): String?
    fun verifiedMemoryEmbeddingModelPath(): String?
    fun verifyLegacyRecommendedModels(): Boolean
    fun importModel(uri: Uri, onProgress: (TransferProgress) -> Unit): String

    /** Who proposes a local run's first tool; always [LocalPlanningStrategy.RuleFirst] outside debug builds. */
    fun localPlanningStrategy(): LocalPlanningStrategy = LocalPlanningStrategy.RuleFirst
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
    fun remoteGuiAutomationEnabled(): Boolean = false
    fun setRemoteGuiAutomationEnabled(enabled: Boolean) = Unit
}

interface RemoteModelStore {
    fun loadMode(): InferenceMode
    fun saveMode(mode: InferenceMode): InferenceMode
    fun loadConfig(): RemoteModelConfig
    fun saveConfig(config: RemoteModelConfig): Result<RemoteModelConfig>
    fun saveConfigWithoutApiKey(config: RemoteModelConfig): Result<RemoteModelConfig> = Result.failure(UnsupportedOperationException("Saving remote config without an API key is unsupported"))
    fun recordConnectivity(config: RemoteModelConfig, status: RemoteModelConnectivityStatus) = Unit
    fun currentConnectivity(config: RemoteModelConfig): RemoteConnectivitySnapshot? = null
    fun invalidateConnectivity() = Unit
}

interface RemoteSendPendingStore {
    fun savePendingRemoteSend(marker: PendingRemoteSendMarker)
    suspend fun consumePendingRemoteSend(): PendingRemoteSendMarker?
    fun clearPendingRemoteSend()
}

interface SettingsStore {
    fun isSetupDismissed(): Boolean
    fun markSetupDismissed()
    fun isMemoryEnabled(): Boolean
    fun setMemoryEnabled(enabled: Boolean)
    fun reduceDeviceActionConfirmations(): Boolean = false
    fun setReduceDeviceActionConfirmations(enabled: Boolean) = Unit
    fun remoteGuiAutomationEnabled(): Boolean = false
    fun setRemoteGuiAutomationEnabled(enabled: Boolean) = Unit
    fun loadGenerationParameters(): GenerationParameters
    fun saveGenerationParameters(parameters: GenerationParameters): GenerationParameters
    fun loadBackend(): BackendChoice
    fun saveBackend(backend: BackendChoice)
    fun loadInferenceMode(): InferenceMode
    fun saveInferenceMode(mode: InferenceMode): InferenceMode
    fun loadRemoteConfig(apiKey: String): RemoteModelConfig
    fun saveRemoteConfig(config: RemoteModelConfig): RemoteModelConfig
    fun selectedModelId(): String?
    fun saveSelectedModelId(modelId: String)
    fun activeInstalledModelId(): String?
    fun saveActiveInstalledModelId(modelId: String?)

    /** Developer setting `local_planning_strategy`; anything but an exact strategy name reads as RuleFirst. */
    fun localPlanningStrategy(): LocalPlanningStrategy = LocalPlanningStrategy.RuleFirst
    fun setLocalPlanningStrategy(strategy: LocalPlanningStrategy) = Unit
}

interface SecretStore {
    fun loadString(name: String): Result<String>
    fun saveString(name: String, value: String): Result<Unit>
}

enum class ModelVerificationStatus {
    VerifiedRecommended,
    UnverifiedCustom,
    LegacyUnverified,
    FailedVerification
}
