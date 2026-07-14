package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.GenerationStats
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.evidence.EvidenceCard
import com.bytedance.zgx.solin.evidence.EvidenceQuality
import com.bytedance.zgx.solin.evidence.EvidenceQualityLevel
import com.bytedance.zgx.solin.evidence.EvidenceReceiptSummary
import com.bytedance.zgx.solin.evidence.EvidenceSourceType
import com.bytedance.zgx.solin.evidence.toEvidenceReceiptSummary
import com.bytedance.zgx.solin.isUsable
import com.bytedance.zgx.solin.memory.MemoryRecallMode
import com.bytedance.zgx.solin.modelProfile
import com.bytedance.zgx.solin.orchestration.AgentPlan
import com.bytedance.zgx.solin.orchestration.AssistantRoute
import com.bytedance.zgx.solin.orchestration.RunDataDestination
import com.bytedance.zgx.solin.orchestration.RunDataReceipt
import com.bytedance.zgx.solin.runtime.GenerationQualityIssue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Package-level helpers shared by [ChatController] and its Wave 5 collaborators.
 * Behavior-preserving extract only — not a public API surface.
 */

internal fun MutableStateFlow<ChatUiState>.updateLastAssistant(text: String) {
    update { state ->
        val updatedMessages = state.messages.toMutableList()
        val index = updatedMessages.indexOfLast { it.role == MessageRole.Assistant }
        if (index >= 0) {
            updatedMessages[index] = updatedMessages[index].copy(
                text = text,
                generationStats = null,
            )
        }
        state.copy(messages = updatedMessages)
    }
}

internal fun MutableStateFlow<ChatUiState>.updateLastAssistantLocalOnly(text: String) {
    update { state ->
        val updatedMessages = state.messages.toMutableList()
        val index = updatedMessages.indexOfLast { it.role == MessageRole.Assistant }
        if (index >= 0) {
            updatedMessages[index] = updatedMessages[index].copy(
                text = text,
                generationStats = null,
                privacy = MessagePrivacy.LocalOnly,
            )
        }
        state.copy(messages = updatedMessages)
    }
}

internal fun MutableStateFlow<ChatUiState>.updateLastAssistantStats(stats: GenerationStats?) {
    if (stats == null) return
    update { state ->
        val updatedMessages = state.messages.toMutableList()
        val index = updatedMessages.indexOfLast { it.role == MessageRole.Assistant }
        if (index >= 0) {
            val enrichedStats = stats.copy(
                modelId = stats.modelId ?: state.activeInstalledModelId ?: state.selectedModelId,
                backend = stats.backend ?: state.backend,
            )
            updatedMessages[index] = updatedMessages[index].copy(generationStats = enrichedStats)
        }
        state.copy(
            messages = updatedMessages,
            modelHealth = state.modelHealth.copy(
                state = if (state.modelHealth.state == ModelHealthState.FallbackActive) {
                    ModelHealthState.FallbackActive
                } else {
                    ModelHealthState.Loaded
                },
                backend = stats.backend ?: state.backend,
                loadMs = stats.loadMs ?: state.modelHealth.loadMs,
                firstTokenMs = stats.firstTokenMs ?: state.modelHealth.firstTokenMs,
                tokenCount = stats.tokenCount,
                tokensPerSecond = stats.tokensPerSecond,
                fallbackBackend = if (stats.usedFallbackBackend) {
                    stats.backend ?: state.modelHealth.fallbackBackend
                } else {
                    state.modelHealth.fallbackBackend
                },
            ),
        )
    }
}

internal fun ChatUiState.failedGenerationModelHealth(
    useRemoteModel: Boolean,
    reason: String,
): ModelHealth =
    modelHealth.copy(
        profileId = if (useRemoteModel) remoteModelConfig.modelProfile().id else activeModelProfileId(),
        state = ModelHealthState.LoadFailed,
        backend = if (useRemoteModel) null else backend,
        failureReason = reason,
    )

internal fun ChatUiState.clearedEvidence(): ChatUiState =
    copy(
        memoryHits = emptyList(),
        activeRunTimeline = emptyList(),
        activeMemoryEvidence = emptyList(),
        activePublicWebEvidence = emptyList(),
    )

internal fun remoteRouteBoundaryFailure(userInput: String, route: AssistantRoute.Chat): String? =
    when {
        route.memoryHits.isNotEmpty() ->
            "远程发送已阻止：本次路由包含本地记忆上下文。请切换到本地模型，或重试不带本地记忆的请求。"

        route.deviceContext != null ->
            "远程发送已阻止：本次路由包含设备上下文。请切换到本地模型，或重试不带设备上下文的请求。"

        route.promptForModel != userInput ->
            "远程发送已阻止：本次路由修改了远程 prompt。请切换到本地模型，或重新发送原始请求。"

        else -> null
    }

internal fun ChatUiState.lastGenerationStatsForAdaptivePolicy(): GenerationStats? =
    messages
        .asReversed()
        .firstNotNullOfOrNull { message -> message.generationStats?.takeIf { it.isUsable() } }
        ?: modelHealth.takeIf { health ->
            health.tokenCount != null && health.tokensPerSecond != null
        }?.let { health ->
            GenerationStats(
                tokenCount = health.tokenCount ?: 0,
                tokensPerSecond = health.tokensPerSecond ?: 0.0,
                backend = health.backend ?: backend,
                loadMs = health.loadMs,
                firstTokenMs = health.firstTokenMs,
                usedFallbackBackend = health.fallbackBackend != null,
            )
        }

internal fun ChatUiState.lastOutputQualityIssueForAdaptivePolicy(): GenerationQualityIssue? =
    modelHealth.lastOutputQualityIssue
        ?.let { value -> runCatching { GenerationQualityIssue.valueOf(value) }.getOrNull() }

internal fun AssistantRoute.runIdOrNull(): String? =
    when (this) {
        is AssistantRoute.Chat -> runId
        is AssistantRoute.Action -> runId
        is AssistantRoute.ToolRejected,
        is AssistantRoute.MissingModel -> null
    }

internal fun AssistantRoute.runDataReceipt(
    stateBeforeSend: ChatUiState,
    destination: RunDataDestination,
    currentPromptPrivacy: MessagePrivacy,
    remoteHistoryCount: Int,
    imageAttachmentCount: Int,
    protectedSourceCount: Int = 0,
    currentPromptEvidenceSummary: EvidenceReceiptSummary? = null,
): RunDataReceipt {
    val memoryHits = (this as? AssistantRoute.Chat)?.memoryHits.orEmpty()
    val deviceContext = (this as? AssistantRoute.Chat)?.deviceContext
    val isRemote = destination == RunDataDestination.Remote
    val localOnlyHistoryFilteredCount = if (isRemote) {
        stateBeforeSend.messages.count { message -> message.privacy == MessagePrivacy.LocalOnly }
    } else {
        0
    }
    val memoryContextIncluded = !isRemote && memoryHits.isNotEmpty()
    val deviceContextIncluded = !isRemote && deviceContext != null
    val semanticMemoryHitCount = memoryHits.count { hit -> hit.recallMode == MemoryRecallMode.Semantic }
    val lexicalMemoryHitCount = memoryHits.count { hit -> hit.recallMode == MemoryRecallMode.Lexical }
    val evidenceReceiptSummary = buildList<EvidenceCard> {
        if (memoryContextIncluded) {
            memoryHits.forEach { hit ->
                add(
                    EvidenceCard(
                        id = "memory:${hit.id}",
                        sourceType = EvidenceSourceType.Memory,
                        privacy = MessagePrivacy.LocalOnly,
                        requiresLocalModel = true,
                        text = hit.text,
                        quality = EvidenceQuality(EvidenceQualityLevel.High),
                    ),
                )
            }
        }
        if (deviceContextIncluded) {
            add(
                EvidenceCard(
                    id = "device-context",
                    sourceType = EvidenceSourceType.DeviceContext,
                    privacy = MessagePrivacy.LocalOnly,
                    requiresLocalModel = true,
                    text = "",
                    quality = EvidenceQuality(EvidenceQualityLevel.Unknown),
                ),
            )
        }
        if (isRemote && imageAttachmentCount > 0) {
            repeat(imageAttachmentCount) { index ->
                add(
                    EvidenceCard(
                        id = "remote-image:$index",
                        sourceType = EvidenceSourceType.ImageAttachment,
                        privacy = MessagePrivacy.RemoteEligible,
                        requiresLocalModel = false,
                        text = "",
                        quality = EvidenceQuality(EvidenceQualityLevel.Unknown),
                    ),
                )
            }
        }
    }.toEvidenceReceiptSummary()
    val evidenceCardCount = evidenceReceiptSummary.evidenceCardCount +
        (currentPromptEvidenceSummary?.evidenceCardCount ?: 0)
    val localOnlyEvidenceCardCount = evidenceReceiptSummary.localOnlyEvidenceCardCount +
        (currentPromptEvidenceSummary?.localOnlyEvidenceCardCount ?: 0)
    val truncatedEvidenceCardCount = evidenceReceiptSummary.truncatedEvidenceCardCount +
        (currentPromptEvidenceSummary?.truncatedEvidenceCardCount ?: 0)
    val lowQualityEvidenceCardCount = evidenceReceiptSummary.lowQualityEvidenceCardCount +
        (currentPromptEvidenceSummary?.lowQualityEvidenceCardCount ?: 0)
    val evidenceSourceTypes = (
        evidenceReceiptSummary.sourceTypes.map { it.name } +
            currentPromptEvidenceSummary.orEmptyEvidenceSourceTypeNames()
        ).distinct()
    return RunDataReceipt(
        destination = destination,
        currentPromptPrivacy = currentPromptPrivacy.name,
        remoteHistoryCount = if (isRemote) remoteHistoryCount else 0,
        localOnlyHistoryFilteredCount = localOnlyHistoryFilteredCount,
        memoryHitCount = memoryHits.size,
        semanticMemoryHitCount = semanticMemoryHitCount,
        lexicalMemoryHitCount = lexicalMemoryHitCount,
        memoryContextIncluded = memoryContextIncluded,
        deviceContextIncluded = deviceContextIncluded,
        imageAttachmentCount = if (isRemote) imageAttachmentCount else 0,
        protectedSourceCount = protectedSourceCount,
        evidenceCardCount = evidenceCardCount,
        localOnlyEvidenceCardCount = localOnlyEvidenceCardCount,
        truncatedEvidenceCardCount = truncatedEvidenceCardCount,
        lowQualityEvidenceCardCount = lowQualityEvidenceCardCount,
        evidenceSourceTypes = evidenceSourceTypes,
        rawContentPersisted = false,
        protectedContentTypes = buildList {
            if (isRemote) {
                add("本地记忆")
                add("设备上下文")
            }
            if (localOnlyHistoryFilteredCount > 0) add("LocalOnly 历史")
            if (protectedSourceCount > 0) add("受保护分享源")
            if (isRemote && imageAttachmentCount == 0) add("非图片附件")
            if ((currentPromptEvidenceSummary?.localOnlyEvidenceCardCount ?: 0) > 0) {
                add("LocalOnly 输入证据")
            }
            if ((currentPromptEvidenceSummary?.truncatedEvidenceCardCount ?: 0) > 0) {
                add("截断输入证据")
            }
        },
        deletableRecordTypes = buildList {
            add("对话消息")
            add("Agent 轨迹")
            if (memoryHits.isNotEmpty()) add("显式记忆")
        },
    )
}

internal fun EvidenceReceiptSummary?.orEmptyEvidenceSourceTypeNames(): List<String> =
    this?.sourceTypes?.map { sourceType -> sourceType.name }.orEmpty()

internal fun List<AgentPlan.UseTool>.batchToolTitle(): String =
    groupingBy { plan -> plan.draft.title.ifBlank { plan.request.toolName } }
        .eachCount()
        .entries
        .joinToString(separator = "、") { (title, count) ->
            if (count > 1) "$title x$count" else title
        }
