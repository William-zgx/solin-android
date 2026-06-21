package com.bytedance.zgx.pocketmind

import com.bytedance.zgx.pocketmind.action.ActionDraft
import com.bytedance.zgx.pocketmind.background.PeriodicCheckPolicySummary
import com.bytedance.zgx.pocketmind.background.ScheduledTaskStatus
import com.bytedance.zgx.pocketmind.background.ScheduledTaskType
import com.bytedance.zgx.pocketmind.data.ModelVerificationStatus
import com.bytedance.zgx.pocketmind.evidence.EvidenceReceiptSummary
import com.bytedance.zgx.pocketmind.memory.MemoryHit
import com.bytedance.zgx.pocketmind.memory.MemoryRecordSensitivity
import com.bytedance.zgx.pocketmind.memory.MemoryRecordSource
import com.bytedance.zgx.pocketmind.memory.MemoryRecordType
import com.bytedance.zgx.pocketmind.memory.SemanticMemoryRuntimeStatus
import com.bytedance.zgx.pocketmind.orchestration.AgentRecoveryAction
import com.bytedance.zgx.pocketmind.orchestration.AgentRunState
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

data class ChatImageAttachment(
    val mimeType: String,
    val dataUrl: String,
)

data class LocalImageAttachment(
    val mimeType: String,
    val bytes: ByteArray,
    val sizeBytes: Long? = null,
) {
    init {
        require(mimeType.startsWith("image/")) { "Local image attachment must use an image MIME type" }
        require(bytes.isNotEmpty()) { "Local image attachment cannot be empty" }
    }
}

fun List<ChatMessage>.remoteEligibleMessages(): List<ChatMessage> =
    filter { it.privacy == MessagePrivacy.RemoteEligible }

data class GenerationStats(
    val tokenCount: Int,
    val tokensPerSecond: Double,
    val modelId: String? = null,
    val backend: BackendChoice? = null,
    val loadMs: Long? = null,
    val firstTokenMs: Long? = null,
    val usedFallbackBackend: Boolean = false,
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
    val source: MemoryRecordSource = MemoryRecordSource.LegacyImport,
    val sensitivity: MemoryRecordSensitivity = MemoryRecordSensitivity.Normal,
    val privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    val expiresAtMillis: Long? = null,
    val conflictKey: String? = null,
)

data class BackgroundTaskSummary(
    val id: String,
    val type: ScheduledTaskType,
    val title: String,
    val body: String,
    val triggerAtMillis: Long,
    val status: ScheduledTaskStatus,
)

data class AuditEventSummary(
    val id: String,
    val toolName: String?,
    val eventType: String,
    val status: String?,
    val riskLevel: String?,
    val permissions: List<String>,
    val summary: String,
    val createdAtMillis: Long,
)

/**
 * One row of the user-reviewable remote-send (egress) audit list. Carries only redacted,
 * already-aggregated fields — never raw prompt text — so it is safe to render in the privacy UI.
 */
data class RemoteSendAuditSummary(
    val id: String,
    val decisionLabel: String,
    val modelName: String?,
    val summary: String,
    val createdAtMillis: Long,
)

data class AgentTraceStepUiSummary(
    val type: String,
    val summary: String,
    val createdAtMillis: Long,
    val runDataReceipt: RunDataReceiptUiSummary? = null,
)

data class RunDataReceiptUiSummary(
    val destination: String,
    val currentPromptPrivacy: String,
    val remoteHistoryCount: Int,
    val localOnlyHistoryFilteredCount: Int,
    val memoryHitCount: Int,
    val semanticMemoryHitCount: Int = 0,
    val lexicalMemoryHitCount: Int = 0,
    val memoryContextIncluded: Boolean,
    val deviceContextIncluded: Boolean,
    val imageAttachmentCount: Int,
    val protectedSourceCount: Int,
    val rawContentPersisted: Boolean,
    val protectedContentTypes: List<String>,
    val deletableRecordTypes: List<String>,
    val outputQualityGuardTriggered: Boolean = false,
    val outputQualityIssue: String? = null,
    val outputQualityRule: String? = null,
    val outputQualityAction: String? = null,
    val outputQualityStopped: Boolean = false,
    val outputQualityKeptPrefix: Boolean = false,
    val evidenceCardCount: Int = 0,
    val localOnlyEvidenceCardCount: Int = 0,
    val truncatedEvidenceCardCount: Int = 0,
    val lowQualityEvidenceCardCount: Int = 0,
    val evidenceSourceTypes: List<String> = emptyList(),
)

data class AgentTraceRunUiSummary(
    val id: String,
    val state: AgentRunState,
    val updatedAtMillis: Long,
    val steps: List<AgentTraceStepUiSummary>,
    val runDataReceipt: RunDataReceiptUiSummary? = null,
)

data class VoiceInputDraft(
    val id: Long,
    val text: String,
)

data class VoiceCaptureUiState(
    val isListening: Boolean = false,
    val isTranscribing: Boolean = false,
    val level: Float = 0f,
    val waveformLevels: List<Float> = emptyList(),
    val waveformFrame: Int = 0,
    val partialText: String = "",
) {
    val isActive: Boolean get() = isListening || isTranscribing
}

data class SharedInputDraft(
    val id: Long,
    val prompt: String,
    val summary: String,
    val imageAttachments: List<ChatImageAttachment> = emptyList(),
    val localImageAttachments: List<LocalImageAttachment> = emptyList(),
    val privacy: MessagePrivacy = MessagePrivacy.LocalOnly,
    val evidenceReceiptSummary: EvidenceReceiptSummary = EvidenceReceiptSummary(
        evidenceCardCount = 0,
        localOnlyEvidenceCardCount = 0,
        truncatedEvidenceCardCount = 0,
        lowQualityEvidenceCardCount = 0,
        sourceTypes = emptyList(),
    ),
)

data class PendingAgentConfirmation(
    val runId: String?,
    val draft: ActionDraft,
    val toolRequest: ToolRequest?,
    val skillId: String?,
    val plannedByModel: Boolean,
    val fallbackReason: String?,
)

enum class RemoteSendDisclosureKind {
    CurrentInput,
    ToolResultContinuation,
}

data class PendingRemoteModeDisclosure(
    val remoteHost: String,
    val remoteModelName: String,
    val apiKeyConfigured: Boolean,
    val connectivityStatus: RemoteModelConnectivityStatus = RemoteModelConnectivityStatus.Unknown,
    val supportsVisionInput: Boolean,
    val isConfigured: Boolean,
)

/**
 * Controls how often ordinary remote-send confirmations are shown.
 *
 * - [OnRemoteModeSwitch]: show the remote-mode disclosure once when entering remote mode.
 *   Ordinary sends are silent after that reminder; sensitive payloads still require consent.
 * - [EveryMessage]: confirm before every remote send.
 * - [OncePerSession]: confirm once per session; subsequent quiet sends are silent.
 * - [OnlyWhenSensitive]: stay silent for ordinary sends; only flagged-sensitive payloads
 *   require the audited sensitive confirmation.
 *
 * Note: sensitive sends and image attachments are ALWAYS confirmed (fail-closed). Image sends
 * include a per-send preview because their bytes cross the local/remote trust boundary.
 */
enum class RemoteSendDisclosurePolicy {
    OnRemoteModeSwitch,
    EveryMessage,
    OncePerSession,
    OnlyWhenSensitive,
}

data class PendingRemoteSendDisclosure(
    val kind: RemoteSendDisclosureKind = RemoteSendDisclosureKind.CurrentInput,
    val prompt: String,
    val messagePrivacy: MessagePrivacy,
    val remoteHost: String,
    val remoteModelName: String,
    val remoteHistoryCount: Int,
    val localOnlyHistoryFilteredCount: Int,
    val imageAttachmentCount: Int,
    val protectedSourceCount: Int,
    val apiKeyConfigured: Boolean,
    val connectivityStatus: RemoteModelConnectivityStatus = RemoteModelConnectivityStatus.Unknown,
    val imageAttachments: List<ChatImageAttachment> = emptyList(),
    /**
     * True when this confirmation was force-shown because the send carries sensitive content.
     * When true the UI must NOT offer the "don't ask again this session" affordance — these
     * sends always require explicit consent.
     */
    val forcedBySensitiveContent: Boolean = false,
    /**
     * A truncated preview of the actual prompt text that will be sent to the remote model, so
     * the user can verify what they are approving (P1 explainability). Empty for tool-result
     * continuations where there is no user-authored prompt.
     */
    val promptPreview: String = "",
    /**
     * Human-readable labels of the sensitive categories detected in this send (e.g.
     * "疑似手机号/电话"). Empty when nothing sensitive was flagged. Drives the "why this was
     * flagged" explanation in the disclosure sheet.
     */
    val sensitiveHitCategories: List<String> = emptyList(),
    val sensitiveHitSnippets: List<String> = emptyList(),
    /**
     * True when at least one detected sensitive span can be automatically masked. Sensitive
     * disclosures always require an audited choice; this flag only controls whether the
     * "mask & send" action is available in addition to "send anyway".
     */
    val allowMaskedSend: Boolean = false,
    /**
     * Preview of the prompt after sensitive spans are masked, shown next to the raw preview so
     * the user can see exactly what "mask & send" will transmit. Empty when [allowMaskedSend]
     * is false or nothing could be masked.
     */
    val maskedPromptPreview: String = "",
    /**
     * The prompt actually sent if the user chooses "mask & send". Empty unless [allowMaskedSend].
     */
    val maskedPrompt: String = "",
) {
    /**
     * True when the send contains sensitive categories and must use audited sensitive actions
     * instead of the ordinary remote-send confirmation.
     */
    val requiresSensitiveConsent: Boolean
        get() = sensitiveHitCategories.isNotEmpty()
}

data class PendingRemoteSendMarker(
    val kind: RemoteSendDisclosureKind,
    val remoteModelName: String,
    val remoteHistoryCount: Int,
    val localOnlyHistoryFilteredCount: Int,
    val imageAttachmentCount: Int,
    val protectedSourceCount: Int,
    val runId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

data class PendingExternalOutcomeConfirmation(
    val runId: String,
    val requestId: String,
    val toolName: String,
    val title: String,
    val summary: String,
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
            ?.let { ModelCatalog.recommendedModelOrNull(it)?.capability }
            ?: ModelCapability.Chat

    val isUsable: Boolean
        get() = recommendedModelId == null ||
            (ModelCatalog.recommendedModelOrNull(recommendedModelId) != null &&
                verificationStatus == ModelVerificationStatus.VerifiedRecommended)

    val capabilityProfile: ModelCapabilityProfile?
        get() = if (!isUsable) {
            null
        } else {
            recommendedModelId
                ?.let { modelId -> ModelCatalog.profileForModelIdOrNull(modelId) }
                ?: ModelCatalog.customLocalChatProfile(displayName)
        }
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
    val reduceDeviceActionConfirmations: Boolean = false,
    val semanticMemoryEnabled: Boolean = false,
    val semanticMemoryRuntimeStatus: SemanticMemoryRuntimeStatus =
        SemanticMemoryRuntimeStatus.NoVerifiedModel,
    val semanticMemoryIndexedRecordCount: Int = 0,
    val semanticMemoryLastRebuiltAtMillis: Long? = null,
    val huggingFaceAccessTokenConfigured: Boolean = false,
    val pendingHuggingFaceAuthorizationModelId: String? = null,
    val memoryHits: List<MemoryHit> = emptyList(),
    val longTermMemories: List<LongTermMemorySummary> = emptyList(),
    val backgroundTasks: List<BackgroundTaskSummary> = emptyList(),
    val backgroundTaskHistory: List<BackgroundTaskSummary> = emptyList(),
    val periodicCheckPolicy: PeriodicCheckPolicySummary = PeriodicCheckPolicySummary.disabled(),
    val auditEvents: List<AuditEventSummary> = emptyList(),
    val remoteSendAuditEvents: List<RemoteSendAuditSummary> = emptyList(),
    val agentTraceRuns: List<AgentTraceRunUiSummary> = emptyList(),
    val pendingConfirmation: PendingAgentConfirmation? = null,
    val pendingRemoteModeDisclosure: PendingRemoteModeDisclosure? = null,
    val pendingRemoteSendDisclosure: PendingRemoteSendDisclosure? = null,
    val remoteSendDisclosurePolicy: RemoteSendDisclosurePolicy =
        RemoteSendDisclosurePolicy.OnRemoteModeSwitch,
    val pendingExternalOutcome: PendingExternalOutcomeConfirmation? = null,
    val latestRecoveryAction: AgentRecoveryAction? = null,
    val inferenceMode: InferenceMode = InferenceMode.Local,
    val remoteModelConfig: RemoteModelConfig = RemoteModelConfig(),
    val backend: BackendChoice = BackendChoice.GPU,
    val generationParameters: GenerationParameters = GenerationParameters(),
    val localMaxTotalTokens: Int = LocalModelTokenLimits.MAX_TOTAL_TOKENS,
    val localPreferredBackends: Set<BackendChoice> = emptySet(),
    val modelHealth: ModelHealth = ModelHealth(
        profileId = DEFAULT_CHAT_MODEL_ID,
        state = ModelHealthState.NotInstalled,
    ),
    val statusText: String = "未加载模型",
    val isArm64Supported: Boolean = true,
    val availableModelStorageBytes: Long = 0L,
    val isBusy: Boolean = false,
    val isGenerating: Boolean = false,
    val isPreparingDownload: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val isReady: Boolean = false,
    val sessions: List<ChatSessionSummary> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val voiceInputDraft: VoiceInputDraft? = null,
    val voiceCapture: VoiceCaptureUiState = VoiceCaptureUiState(),
    val pendingSharedInputDraft: SharedInputDraft? = null,
) {
    val selectedRecommendedModel: RecommendedModel
        get() = ModelCatalog.recommendedChatModelById(selectedModelId)

    val basicSetupModels: List<RecommendedModel>
        get() = ModelCatalog.basicSetupModels()

    val chatModels: List<RecommendedModel>
        get() = ModelCatalog.recommendedModelsFor(ModelCapability.Chat)

    val optionalChatModels: List<RecommendedModel>
        get() = ModelCatalog.optionalChatModels()

    val activeLocalModelSupportsVisionInput: Boolean
        get() = activeLocalCapabilityProfile?.supportsVisionInput == true

    val activeLocalCapabilityProfile: ModelCapabilityProfile?
        get() = installedModels
            .firstOrNull { it.id == activeInstalledModelId }
            ?.capabilityProfile

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
