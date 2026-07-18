package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.InferenceMode
import com.bytedance.zgx.solin.LocalImageAttachment
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.ModelHealth
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.PendingRemoteSendDisclosure
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import com.bytedance.zgx.solin.SharedInputDraft
import com.bytedance.zgx.solin.VoiceInputDraft
import com.bytedance.zgx.solin.evidence.EvidenceReceiptSummary
import com.bytedance.zgx.solin.modelProfile
import com.bytedance.zgx.solin.multimodal.SharedInput
import com.bytedance.zgx.solin.multimodal.toSharedEvidenceReceiptSummary
import com.bytedance.zgx.solin.orchestration.PromptPrivacyPlanner
import com.bytedance.zgx.solin.orchestration.toPromptPrivacySegments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns shared-input ingest / staging / protect / reject / send-pending draft flow.
 *
 * Extracted from [ChatController] (Wave 5 Track C5). [ChatController] keeps thin public forwards
 * and owns the send path that shared input ultimately resumes into.
 */
internal data class PendingSharedInputRemoteSendRestore(
    val draft: SharedInputDraft,
    val userInstruction: String,
    val combinedPrompt: String,
) {
    fun matches(pending: PendingRemoteSendDisclosure): Boolean =
        pending.kind == RemoteSendDisclosureKind.CurrentInput &&
            pending.prompt == combinedPrompt &&
            pending.imageAttachments == draft.imageAttachments
}

internal class ChatSharedInputSupport(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val replaceActiveSessionMessages: (messages: List<ChatMessage>, persistNow: Boolean) -> Unit,
    private val isGenerationActive: () -> Boolean,
    private val allocateVoiceInputDraftId: () -> Long,
    private val sendMessageInternal: (
        prompt: String,
        explicitMessagePrivacy: MessagePrivacy?,
        imageAttachments: List<ChatImageAttachment>,
        localImageAttachments: List<LocalImageAttachment>,
        currentPromptEvidenceSummary: EvidenceReceiptSummary?,
    ) -> Unit,
) {
    private var nextSharedInputDraftId = 0L
    private var pendingSharedInputRemoteSendRestore: PendingSharedInputRemoteSendRestore? = null

    fun clearPendingRemoteChatSharedState() {
        pendingSharedInputRemoteSendRestore = null
    }

    /**
     * Consumes and returns a matching shared-input restore for [pending], clearing the held restore.
     * Always clears the held restore (matching or not) so a confirm/dismiss does not leave stale state.
     */
    fun takeSharedInputRestoreMatching(pending: PendingRemoteSendDisclosure): PendingSharedInputRemoteSendRestore? {
        val restore = pendingSharedInputRemoteSendRestore?.takeIf { it.matches(pending) }
        pendingSharedInputRemoteSendRestore = null
        return restore
    }

    fun clearSharedInputRestore() {
        pendingSharedInputRemoteSendRestore = null
    }

    fun ingestSharedInput(sharedInput: SharedInput) {
        if (sharedInput.isEmpty) return
        if (uiState.value.inferenceMode == InferenceMode.Local &&
            sharedInput.hasLocalImageAttachment() &&
            !uiState.value.activeLocalModelSupportsVisionInput
        ) {
            rejectUnsupportedLocalVisionInput()
            return
        }
        if (uiState.value.inferenceMode == InferenceMode.Remote) {
            val remoteConfig = uiState.value.remoteModelConfig
            if (!remoteConfig.isConfigured) {
                protectUnconfiguredRemoteSharedInput(alreadyStaged = false)
                return
            }
            val remoteSupportsVisionInput = remoteConfig.modelProfile().supportsVisionInput
            if ((sharedInput.hasRemoteImageAttachment() || sharedInput.hasProtectedImageSource()) &&
                !remoteSupportsVisionInput
            ) {
                rejectUnsupportedRemoteVisionInput(sharedInput.protectedSourceCount)
                return
            }
            if (!sharedInput.isRemoteVisionSendable()) {
                protectRemoteSharedInput()
                return
            }
        }
        stageSharedInputDraft(sharedInput, statusText = "已接收分享内容")
    }

    fun stageSharedInput(sharedInput: SharedInput) {
        if (uiState.value.inferenceMode == InferenceMode.Local &&
            sharedInput.hasLocalImageAttachment() &&
            !uiState.value.activeLocalModelSupportsVisionInput
        ) {
            rejectUnsupportedLocalVisionInput()
            return
        }
        if (uiState.value.inferenceMode == InferenceMode.Remote) {
            val remoteConfig = uiState.value.remoteModelConfig
            if (!remoteConfig.isConfigured) {
                protectUnconfiguredRemoteSharedInput(alreadyStaged = false)
                return
            }
            val remoteSupportsVisionInput = remoteConfig.modelProfile().supportsVisionInput
            if ((sharedInput.hasRemoteImageAttachment() || sharedInput.hasProtectedImageSource()) &&
                !remoteSupportsVisionInput
            ) {
                rejectUnsupportedRemoteVisionInput(sharedInput.protectedSourceCount)
                return
            }
            if (!sharedInput.isRemoteVisionSendable()) {
                protectRemoteSharedInput()
                return
            }
        }
        stageSharedInputDraft(sharedInput, statusText = "已选择附件")
    }

    private fun stageSharedInputDraft(sharedInput: SharedInput, statusText: String) {
        if (sharedInput.isEmpty) return
        pendingSharedInputRemoteSendRestore = null
        val imageAttachments = sharedInput.remoteImageAttachments()
        val localImageAttachments = sharedInput.localImageAttachments()
        val privacyPlan = PromptPrivacyPlanner.build(sharedInput.toPromptPrivacySegments())
        val prompt = if (imageAttachments.isNotEmpty()) {
            sharedInput.toRemoteVisionPrompt()
        } else if (localImageAttachments.isNotEmpty()) {
            sharedInput.toLocalVisionPrompt()
        } else {
            sharedInput.toPrompt()
        }
        if (prompt.isBlank()) return
        uiState.update {
            it.copy(
                pendingSharedInputDraft = SharedInputDraft(
                    id = ++nextSharedInputDraftId,
                    prompt = prompt,
                    summary = sharedInput.composerSummary(),
                    imageAttachments = imageAttachments,
                    localImageAttachments = localImageAttachments,
                    privacy = privacyPlan.aggregatePrivacy,
                    requiresLocalModel = privacyPlan.requiresLocalModel,
                    optionalHistoryFilteredCount = privacyPlan.optionalHistoryFilteredCount,
                    evidenceReceiptSummary = sharedInput.toSharedEvidenceReceiptSummary(),
                ),
                statusText = statusText,
            )
        }
    }

    private fun protectRemoteSharedInput() {
        replaceActiveSessionMessages(
            uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = "已接收分享内容。当前已切换远程模型，主动选择的图片只会在逐次确认后发送给远程视觉模型，疑似敏感内容也会逐次确认；分享文本、RTF/PDF/Office 文档摘录、JSON/XML/YAML 文本摘录、OCR 摘录和非图片附件元数据只在本机处理，不会自动发送。",
                privacy = MessagePrivacy.LocalOnly,
            ),
            true,
        )
        uiState.update {
            it.copy(statusText = "已保护分享内容")
        }
    }

    private fun protectUnconfiguredRemoteSharedInput(alreadyStaged: Boolean) {
        val notice = buildString {
            append("请先在模型管理中配置远程模型地址和模型名；")
            if (alreadyStaged) {
                append("我没有发送这次分享内容，图片不会被自动 OCR，也不会发送到远程模型。")
            } else {
                append("我没有发送这次分享内容；本机读取的内容不会上传，图片不会被自动 OCR。")
            }
            append("远程模式只会在远程模型配置完成、切换到远程模型且你确认发送后，把主动选择的图片发送给远程视觉模型。")
        }
        replaceActiveSessionMessages(
            uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = notice,
                privacy = MessagePrivacy.LocalOnly,
            ),
            true,
        )
        uiState.update {
            it.copy(
                pendingSharedInputDraft = null,
                pendingRemoteSendDisclosure = null,
                statusText = "请配置远程模型",
                modelHealth = ModelHealth(
                    profileId = it.remoteModelConfig.modelProfile().id,
                    state = ModelHealthState.LoadFailed,
                    failureReason = "远程模型未配置",
                ),
            )
        }
    }

    fun rejectUnsupportedRemoteVisionInput(protectedSourceCount: Int = 0) {
        replaceActiveSessionMessages(
            uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = buildString {
                    append("当前远程模型未启用图片输入能力，未执行 OCR 或发送图片；请配置并切换支持视觉的远程模型后重新选择图片。")
                    if (protectedSourceCount > 0) {
                        append("本次分享中的其他内容也未读取或发送。")
                    }
                },
                privacy = MessagePrivacy.LocalOnly,
            ),
            true,
        )
        uiState.update {
            it.copy(
                pendingSharedInputDraft = null,
                statusText = "当前远程模型不支持图片输入",
            )
        }
    }

    fun rejectUnsupportedLocalVisionInput() {
        replaceActiveSessionMessages(
            uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = "当前本地模型不支持图片输入，未交给模型、OCR 或发送图片；请切换到已校验且支持视觉的本地模型后重新选择图片。",
                privacy = MessagePrivacy.LocalOnly,
            ),
            true,
        )
        uiState.update {
            it.copy(
                pendingSharedInputDraft = null,
                statusText = "当前本地模型不支持图片输入",
            )
        }
    }

    fun clearPendingSharedInputDraft(draftId: Long) {
        if (pendingSharedInputRemoteSendRestore?.draft?.id == draftId) {
            pendingSharedInputRemoteSendRestore = null
        }
        uiState.update {
            if (it.pendingSharedInputDraft?.id == draftId) {
                it.copy(
                    pendingSharedInputDraft = null,
                    statusText = "已移除附件",
                )
            } else {
                it
            }
        }
    }

    fun sendPendingSharedInput(userInstruction: String = "") {
        val draft = uiState.value.pendingSharedInputDraft ?: return
        val message = buildString {
            val cleanedInstruction = userInstruction.trim()
            if (cleanedInstruction.isNotBlank()) {
                append(cleanedInstruction)
                append("\n\n")
            }
            append(draft.prompt)
        }.trim()
        if (message.isBlank()) return
        val state = uiState.value
        if (state.pendingConfirmation != null) {
            uiState.update { it.copy(statusText = "请先确认或取消待执行动作") }
            return
        }
        if (state.pendingExternalOutcome != null) {
            uiState.update { it.copy(statusText = "请先确认外部动作结果") }
            return
        }
        if (state.pendingRemoteModeDisclosure != null) {
            uiState.update { it.copy(statusText = "请先确认远程模式提醒") }
            return
        }
        if (state.isBusy || isGenerationActive()) return
        if (draft.requiresLocalModel && state.inferenceMode == InferenceMode.Remote) {
            uiState.update { it.copy(statusText = "此内容仅限本地处理") }
            return
        }
        // An unconfigured remote model is a "no model" situation, not a
        // "vision unsupported" one. Surface the unconfigured guidance first so the
        // fail-closed supportsVisionInput=false default does not mask it.
        if (draft.imageAttachments.isNotEmpty() &&
            state.inferenceMode == InferenceMode.Remote &&
            state.remoteModelConfig.isConfigured &&
            !state.remoteModelConfig.modelProfile().supportsVisionInput
        ) {
            rejectUnsupportedRemoteVisionInput()
            return
        }
        if (draft.localImageAttachments.isNotEmpty() &&
            state.inferenceMode == InferenceMode.Local &&
            !state.activeLocalModelSupportsVisionInput
        ) {
            rejectUnsupportedLocalVisionInput()
            return
        }

        if (!uiState.value.isReady) {
            clearPendingSharedInputDraftIfActive(draft.id)
            if (uiState.value.inferenceMode == InferenceMode.Remote &&
                !uiState.value.remoteModelConfig.isConfigured
            ) {
                protectUnconfiguredRemoteSharedInput(alreadyStaged = true)
                return
            }
            replaceActiveSessionMessages(
                uiState.value.messages + ChatMessage(
                    role = MessageRole.User,
                    text = message,
                    privacy = MessagePrivacy.LocalOnly,
                ) + ChatMessage(
                    role = MessageRole.Assistant,
                    text = "已接收分享内容。请先准备模型后再发送；图片不会被自动 OCR，当前只会读取受限文本、JSON/XML/YAML/RTF/PDF/Office 文档摘录、PDF 扫描页 OCR 摘录和附件元数据。",
                    privacy = MessagePrivacy.LocalOnly,
                ),
                true,
            )
            uiState.update { it.copy(statusText = "已接收分享内容") }
            return
        }
        val cleanedInstruction = userInstruction.trim()
        val useRemoteModel = state.inferenceMode == InferenceMode.Remote
        sendMessageInternal(
            message,
            draft.privacy,
            if (useRemoteModel) draft.imageAttachments else emptyList(),
            if (useRemoteModel) emptyList() else draft.localImageAttachments,
            draft.evidenceReceiptSummary,
        )
        val pending = uiState.value.pendingRemoteSendDisclosure
        if (pending != null &&
            pending.kind == RemoteSendDisclosureKind.CurrentInput &&
            pending.prompt == message &&
            pending.imageAttachments == draft.imageAttachments
        ) {
            pendingSharedInputRemoteSendRestore = PendingSharedInputRemoteSendRestore(
                draft = draft,
                userInstruction = cleanedInstruction,
                combinedPrompt = message,
            )
        } else {
            pendingSharedInputRemoteSendRestore = null
            clearPendingSharedInputDraftIfActive(draft.id)
        }
    }

    private fun clearPendingSharedInputDraftIfActive(draftId: Long) {
        uiState.update {
            if (it.pendingSharedInputDraft?.id == draftId) {
                it.copy(pendingSharedInputDraft = null)
            } else {
                it
            }
        }
    }

    fun clearPendingSharedInputDraftForConfirmedRemoteSend(
        state: ChatUiState,
        restore: PendingSharedInputRemoteSendRestore?,
    ): ChatUiState {
        if (restore == null) return state
        return if (state.pendingSharedInputDraft?.id == restore.draft.id) {
            state.copy(pendingSharedInputDraft = null)
        } else {
            state
        }
    }

    fun restoreComposerDraftAfterRemoteSendCancel(
        state: ChatUiState,
        pending: PendingRemoteSendDisclosure?,
        sharedInputRestore: PendingSharedInputRemoteSendRestore?,
        restoreOrdinaryPrompt: Boolean,
    ): ChatUiState {
        if (pending == null || pending.kind != RemoteSendDisclosureKind.CurrentInput) return state
        if (sharedInputRestore != null) {
            val restoredSharedDraft = if (state.pendingSharedInputDraft == null) {
                state.copy(pendingSharedInputDraft = sharedInputRestore.draft)
            } else {
                state
            }
            return restoredSharedDraft.withRecoveredComposerInputDraft(sharedInputRestore.userInstruction)
        }
        return if (restoreOrdinaryPrompt) {
            state.withRecoveredComposerInputDraft(pending.prompt)
        } else {
            state
        }
    }

    private fun ChatUiState.withRecoveredComposerInputDraft(text: String): ChatUiState {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return this
        return copy(
            voiceInputDraft = VoiceInputDraft(
                id = allocateVoiceInputDraftId(),
                text = cleaned,
            ),
        )
    }
}
