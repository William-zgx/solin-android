package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.ChatImageAttachment
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.MessageRole
import com.bytedance.zgx.solin.PendingRemoteSendDisclosure
import com.bytedance.zgx.solin.PendingRemoteSendMarker
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteSendAuditSummary
import com.bytedance.zgx.solin.RemoteSendDisclosureKind
import com.bytedance.zgx.solin.RemoteSendDisclosurePolicy
import com.bytedance.zgx.solin.SolinConstants
import com.bytedance.zgx.solin.audit.RemoteSendAuditEvent
import com.bytedance.zgx.solin.audit.RemoteSendAuditLog
import com.bytedance.zgx.solin.audit.RemoteSendAuditSink
import com.bytedance.zgx.solin.audit.RemoteSendDecision
import com.bytedance.zgx.solin.data.RemoteSendPendingStore
import com.bytedance.zgx.solin.destinationHostLabel
import com.bytedance.zgx.solin.orchestration.RemoteToolScope
import com.bytedance.zgx.solin.remoteEligibleMessages
import com.bytedance.zgx.solin.safety.SafetyPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal const val REMOTE_SEND_RESTART_DISCARDED_TEXT =
    "上次远程发送确认因应用重启已失效，内容没有发送。请重新发起请求。"
internal const val REMOTE_TOOL_CONTINUATION_RESTART_DISCARDED_TEXT =
    "上次远程工具结果续写确认因应用重启已失效，工具结果没有发送到远程模型。请重新发起请求。"

internal data class PendingRemoteContinuation(
    val runId: String?,
    val promptForModel: String,
    val responsePrivacy: MessagePrivacy,
    val remoteToolScope: RemoteToolScope,
)

/**
 * Owns remote-send disclosure, pending-continuation markers, and egress audit recording.
 *
 * Extracted from [ChatController] (Wave 5 Track C5). Resume-send after confirm is injected via
 * callbacks so this collaborator does not form a circular type with [ChatController].
 */
internal class ChatRemoteSendSupport(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val remoteSendAuditSink: RemoteSendAuditSink,
    private val remoteSendAuditLog: RemoteSendAuditLog,
    private val remoteSendPendingStore: RemoteSendPendingStore,
    private val requireRemoteSendDisclosure: Boolean,
    private val outboundSafetyPolicy: SafetyPolicy = SafetyPolicy(),
    private val replaceActiveSessionMessages: (messages: List<ChatMessage>, persistNow: Boolean) -> Unit,
    private val persistActiveSessionFromUi: () -> Unit,
    private val loadAgentTraceRuns: () -> List<AgentTraceRunUiSummary>,
    private val failModelGeneration: (runId: String, reason: String) -> Unit,
    private val takeSharedInputRestoreMatching: (PendingRemoteSendDisclosure) -> PendingSharedInputRemoteSendRestore?,
    private val clearSharedInputRestore: () -> Unit,
    private val applyConfirmedRemoteSendDraftClear: (
        ChatUiState,
        PendingSharedInputRemoteSendRestore?,
    ) -> ChatUiState,
    private val applyCancelRemoteSendDraftRestore: (
        ChatUiState,
        PendingRemoteSendDisclosure?,
        PendingSharedInputRemoteSendRestore?,
        Boolean,
    ) -> ChatUiState,
    private val onResumeSendAfterDisclosure: (
        pending: PendingRemoteSendDisclosure,
        promptOverride: String?,
    ) -> Unit,
    private val onDiscardPreparedSend: (runId: String) -> Unit,
    private val onResumeContinuationAfterDisclosure: (
        runId: String?,
        promptForModel: String,
        responsePrivacy: MessagePrivacy,
        remoteToolScope: RemoteToolScope,
    ) -> Unit,
) {
    private var pendingRemoteContinuation: PendingRemoteContinuation? = null

    /**
     * Session-scoped suppression of the remote-send disclosure sheet. Set when the user picks
     * "don't ask again this session" on a quiet, non-sensitive confirmation. Reset whenever the
     * trust boundary changes: session switch, inference-mode change, or remote config change.
     * A sensitive payload always re-forces confirmation regardless of this flag (fail-closed).
     */
    private var remoteSendDisclosureSuppressedForSession: Boolean = false

    val pendingContinuationRunId: String?
        get() = pendingRemoteContinuation?.runId

    fun setPendingRemoteContinuation(continuation: PendingRemoteContinuation) {
        pendingRemoteContinuation = continuation
    }

    fun clearPendingRemoteContinuation() {
        pendingRemoteContinuation = null
    }

    fun clearPendingRemoteChatState() {
        pendingRemoteContinuation = null
        clearSharedInputRestore()
        remoteSendPendingStore.clearPendingRemoteSend()
    }

    fun containsSensitivePersonalOrSecretContent(text: String): Boolean =
        outboundSafetyPolicy.containsSensitivePersonalOrSecretContent(text)

    fun confirmRemoteSendDisclosure(suppressForSession: Boolean = false) {
        val pending = uiState.value.pendingRemoteSendDisclosure ?: return
        if (pending.requiresSensitiveConsent) return
        // Only honor "don't ask again this session" for non-forced sends.
        // Sensitive disclosures must never be silenced — they re-prompt every time regardless.
        if (suppressForSession &&
            uiState.value.remoteSendDisclosurePolicy == RemoteSendDisclosurePolicy.OncePerSession &&
            !pending.forcedBySensitiveContent &&
            pending.imageAttachmentCount == 0
        ) {
            remoteSendDisclosureSuppressedForSession = true
        }
        recordRemoteSendDecision(RemoteSendDecision.Confirmed, pending)
        remoteSendPendingStore.clearPendingRemoteSend()
        val continuation = pendingRemoteContinuation
        val sharedInputRestore = takeSharedInputRestoreMatching(pending)
        uiState.update {
            if (it.pendingRemoteSendDisclosure == pending) {
                applyConfirmedRemoteSendDraftClear(it, sharedInputRestore).copy(
                    pendingRemoteSendDisclosure = null,
                    statusText = "处理中",
                )
            } else {
                it
            }
        }
        if (continuation != null) {
            pendingRemoteContinuation = null
            onResumeContinuationAfterDisclosure(
                continuation.runId,
                continuation.promptForModel,
                continuation.responsePrivacy,
                continuation.remoteToolScope,
            )
            return
        }
        onResumeSendAfterDisclosure(pending, null)
    }

    /**
     * "Mask & send": redacts the detected sensitive spans and sends the masked prompt to the
     * remote model. Records a LocalOnly audit note of what was masked so the egress is traceable.
     * Only valid for a sensitive disclosure that produced a non-empty masked form.
     */
    fun confirmRemoteSendWithMasking() {
        val pending = uiState.value.pendingRemoteSendDisclosure ?: return
        if (!pending.allowMaskedSend || pending.maskedPrompt.isBlank()) return
        // Masked sends are never applicable to tool-result continuations (no user prompt).
        if (pendingRemoteContinuation != null) return
        val maskedCategories = pending.sensitiveHitCategories.joinToString("、")
        appendRemoteSendAuditNote(
            "已对疑似敏感内容打码后发送到远程模型" +
                if (maskedCategories.isNotBlank()) "（$maskedCategories）。" else "。",
        )
        recordRemoteSendDecision(RemoteSendDecision.MaskedSend, pending)
        remoteSendPendingStore.clearPendingRemoteSend()
        val sharedInputRestore = takeSharedInputRestoreMatching(pending)
        uiState.update {
            if (it.pendingRemoteSendDisclosure == pending) {
                applyConfirmedRemoteSendDraftClear(it, sharedInputRestore)
                    .copy(pendingRemoteSendDisclosure = null, statusText = "处理中")
            } else {
                it
            }
        }
        onResumeSendAfterDisclosure(pending, pending.maskedPrompt)
    }

    /**
     * "Send anyway (audited)": sends the raw sensitive prompt unchanged after explicit consent.
     * Records a LocalOnly audit note flagging the override so the decision is traceable.
     */
    fun confirmRemoteSendDespiteSensitive() {
        val pending = uiState.value.pendingRemoteSendDisclosure ?: return
        if (!pending.requiresSensitiveConsent) return
        if (pendingRemoteContinuation != null) return
        val hitCategories = pending.sensitiveHitCategories.joinToString("、")
        appendRemoteSendAuditNote(
            "用户确认在含疑似敏感内容的情况下仍原样发送到远程模型" +
                if (hitCategories.isNotBlank()) "（$hitCategories）。" else "。",
        )
        recordRemoteSendDecision(RemoteSendDecision.SentAnyway, pending)
        remoteSendPendingStore.clearPendingRemoteSend()
        val sharedInputRestore = takeSharedInputRestoreMatching(pending)
        uiState.update {
            if (it.pendingRemoteSendDisclosure == pending) {
                applyConfirmedRemoteSendDraftClear(it, sharedInputRestore)
                    .copy(pendingRemoteSendDisclosure = null, statusText = "处理中")
            } else {
                it
            }
        }
        onResumeSendAfterDisclosure(pending, null)
    }

    /** Appends a LocalOnly assistant note recording a remote-send privacy decision (never sent). */
    private fun appendRemoteSendAuditNote(note: String) {
        replaceActiveSessionMessages(
            uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = note,
                privacy = MessagePrivacy.LocalOnly,
            ),
            true,
        )
    }

    /**
     * Records a structured, redacted egress event for a remote-send [decision] derived from the
     * [pending] disclosure. Feeds the user-reviewable "远程发送记录" list (P2 egress audit). Pure
     * counts/categories only — no raw prompt text is ever written to the audit store.
     */
    private fun recordRemoteSendDecision(
        decision: RemoteSendDecision,
        pending: PendingRemoteSendDisclosure,
    ) {
        recordRemoteSendAuditEvent(
            decision = decision,
            modelName = pending.remoteModelName,
            prompt = pending.prompt,
            imageCount = pending.imageAttachmentCount,
            remoteHistoryCount = pending.remoteHistoryCount,
        )
    }

    fun savePendingRemoteSendMarker(
        pending: PendingRemoteSendDisclosure,
        runId: String? = null,
    ) {
        remoteSendPendingStore.savePendingRemoteSend(
            PendingRemoteSendMarker(
                kind = pending.kind,
                remoteModelName = pending.remoteModelName,
                remoteHistoryCount = pending.remoteHistoryCount,
                localOnlyHistoryFilteredCount = pending.localOnlyHistoryFilteredCount,
                imageAttachmentCount = pending.imageAttachmentCount,
                protectedSourceCount = pending.protectedSourceCount,
                runId = runId ?: pending.runId,
                remoteProfileRevision = pending.remoteProfileRevision,
            ),
        )
    }

    fun failClosedPendingRemoteSendOnStartup() {
        val marker = remoteSendPendingStore.consumePendingRemoteSend() ?: return
        pendingRemoteContinuation = null
        val reason = when (marker.kind) {
            RemoteSendDisclosureKind.CurrentInput -> REMOTE_SEND_RESTART_DISCARDED_TEXT
            RemoteSendDisclosureKind.ToolResultContinuation -> REMOTE_TOOL_CONTINUATION_RESTART_DISCARDED_TEXT
        }
        marker.runId?.let { runId ->
            failModelGeneration(runId, reason)
        }
        replaceActiveSessionMessages(
            uiState.value.messages + ChatMessage(
                role = MessageRole.Assistant,
                text = reason,
                privacy = MessagePrivacy.LocalOnly,
            ),
            true,
        )
        uiState.update {
            it.copy(
                pendingRemoteSendDisclosure = null,
                isBusy = false,
                isGenerating = false,
                agentTraceRuns = loadAgentTraceRuns(),
                statusText = "远程发送确认已失效",
            )
        }
    }

    /**
     * Records a structured egress audit event for a remote-send decision. Feeds the
     * user-reviewable "远程发送记录" list (P2 egress audit).
     *
     * IMPORTANT: The [prompt] parameter is the raw user text. It is used ONLY for in-memory
     * sensitive-category detection via [outboundSafetyPolicy.detectSensitiveCategories].
     * The raw prompt text is NEVER persisted to the audit log — only the detected category
     * labels, decision label, and aggregate counts (images, history entries) are stored.
     */
    fun recordRemoteSendAuditEvent(
        decision: RemoteSendDecision,
        modelName: String?,
        prompt: String,
        imageCount: Int,
        remoteHistoryCount: Int,
    ) {
        // Raw prompt is used only for in-memory decision making and is NEVER persisted to the audit log.
        val sensitiveCategories = outboundSafetyPolicy.detectSensitiveCategories(prompt)
        val summaryParts = buildList {
            add(decision.label)
            if (imageCount > 0) add("图片 ${imageCount} 张")
            if (remoteHistoryCount > 0) add("历史 ${remoteHistoryCount} 条")
            if (sensitiveCategories.isNotEmpty()) {
                add("敏感类别：" + sensitiveCategories.joinToString("、") { it.label })
            }
        }
        remoteSendAuditSink.record(
            RemoteSendAuditEvent(
                decision = decision,
                modelName = modelName?.takeIf { it.isNotBlank() },
                sensitiveCategories = sensitiveCategories,
                imageCount = imageCount,
                remoteHistoryCount = remoteHistoryCount,
                summary = summaryParts.joinToString("；"),
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        uiState.update { it.copy(remoteSendAuditEvents = loadRemoteSendAuditEvents()) }
    }

    /** Read-only egress audit view surfaced to the privacy/settings UI (most-recent-first). */
    private fun loadRemoteSendAuditEvents(): List<RemoteSendAuditSummary> =
        remoteSendAuditLog.recentRemoteSends().map { event ->
            RemoteSendAuditSummary(
                id = event.id,
                decisionLabel = event.decision.label,
                modelName = event.modelName,
                summary = event.summary,
                createdAtMillis = event.createdAtMillis,
            )
        }

    fun refreshRemoteSendAuditEvents() {
        uiState.update { it.copy(remoteSendAuditEvents = loadRemoteSendAuditEvents()) }
    }

    fun dismissRemoteSendDisclosure() {
        val pending = uiState.value.pendingRemoteSendDisclosure
        pending?.let { disclosure ->
            recordRemoteSendDecision(RemoteSendDecision.Cancelled, disclosure)
        }
        val continuation = pendingRemoteContinuation
        pendingRemoteContinuation = null
        if (continuation == null && pending?.kind == RemoteSendDisclosureKind.CurrentInput) {
            pending.runId?.let(onDiscardPreparedSend)
        }
        remoteSendPendingStore.clearPendingRemoteSend()
        if (continuation != null) {
            val reason = "用户取消远程工具结果续写，工具结果未发送到远程模型。"
            continuation.runId?.let { runId ->
                failModelGeneration(runId, reason)
            }
            uiState.updateLastAssistantLocalOnly(reason)
            persistActiveSessionFromUi()
        }
        val sharedInputRestore = if (pending != null) {
            takeSharedInputRestoreMatching(pending)
        } else {
            clearSharedInputRestore()
            null
        }
        uiState.update {
            if (it.pendingRemoteSendDisclosure != null) {
                applyCancelRemoteSendDraftRestore(
                    it,
                    pending,
                    if (continuation == null) sharedInputRestore else null,
                    continuation == null,
                ).copy(
                    pendingRemoteSendDisclosure = null,
                    isBusy = false,
                    isGenerating = false,
                    agentTraceRuns = loadAgentTraceRuns(),
                    statusText = "已取消远程发送",
                )
            } else {
                it
            }
        }
    }

    fun remoteHistoryForRemoteSend(messages: List<ChatMessage>): List<ChatMessage> =
        messages.remoteEligibleMessages()
            .filterNot { message ->
                outboundSafetyPolicy.containsSensitivePersonalOrSecretContent(message.text)
            }

    /**
     * Decides whether the remote-send disclosure sheet must be shown before an ordinary,
     * non-sensitive send. Sensitive payloads are intercepted earlier in send and always
     * require an audited choice.
     */
    fun shouldRequireRemoteSendDisclosure(imageAttachmentCount: Int = 0): Boolean {
        if (imageAttachmentCount > 0) return true
        if (!requireRemoteSendDisclosure) return false
        return when (uiState.value.remoteSendDisclosurePolicy) {
            RemoteSendDisclosurePolicy.OnRemoteModeSwitch -> false
            RemoteSendDisclosurePolicy.OnlyWhenSensitive -> false
            RemoteSendDisclosurePolicy.OncePerSession ->
                !remoteSendDisclosureSuppressedForSession
            RemoteSendDisclosurePolicy.EveryMessage ->
                true
        }
    }

    /**
     * Resets the session-scoped disclosure suppression. Called whenever the remote trust
     * boundary changes (session switch, inference-mode change, remote config change) so a
     * previously granted "don't ask again this session" never silently carries over to a
     * different destination or context.
     */
    fun resetRemoteSendDisclosureSuppression() {
        remoteSendDisclosureSuppressedForSession = false
    }

    /** Updates the remote-send disclosure cadence policy and clears any session suppression. */
    fun setRemoteSendDisclosurePolicy(policy: RemoteSendDisclosurePolicy) {
        resetRemoteSendDisclosureSuppression()
        uiState.update { it.copy(remoteSendDisclosurePolicy = policy) }
    }

    fun buildPendingRemoteSendDisclosure(
        kind: RemoteSendDisclosureKind,
        prompt: String,
        messagePrivacy: MessagePrivacy,
        remoteConfig: RemoteModelConfig,
        remoteHistory: List<ChatMessage>,
        imageAttachments: List<ChatImageAttachment>,
        stateBeforeSend: ChatUiState,
    ): PendingRemoteSendDisclosure =
        PendingRemoteSendDisclosure(
            kind = kind,
            forcedBySensitiveContent = false,
            prompt = prompt,
            messagePrivacy = messagePrivacy,
            remoteHost = remoteConfig.destinationHostLabel(),
            remoteModelName = remoteConfig.normalized().modelName.ifBlank { "未命名远程模型" },
            remoteHistoryCount = remoteHistory.size,
            localOnlyHistoryFilteredCount = stateBeforeSend.messages.count { message ->
                message.privacy == MessagePrivacy.LocalOnly
            },
            imageAttachmentCount = imageAttachments.size,
            protectedSourceCount = stateBeforeSend.messages.count { message ->
                message.privacy == MessagePrivacy.LocalOnly
            },
            apiKeyConfigured = remoteConfig.apiKey.isNotBlank(),
            connectivityStatus = remoteConfig.connectivityStatus,
            imageAttachments = imageAttachments,
            promptPreview = prompt.toRemoteSendPromptPreview(),
            sensitiveHitCategories = outboundSafetyPolicy
                .detectSensitiveCategories(prompt)
                .map { it.label },
            sensitiveHitSnippets = outboundSafetyPolicy.detectSensitiveSnippets(prompt),
        )

    /**
     * Builds a forced disclosure for a sensitive send that offers graded handling (mask & send /
     * send anyway). Always [PendingRemoteSendDisclosure.forcedBySensitiveContent] so it can
     * never be silenced, and carries the masked preview so the user sees exactly what
     * "mask & send" would transmit.
     */
    fun buildSensitiveRemoteSendDisclosure(
        prompt: String,
        messagePrivacy: MessagePrivacy,
        remoteConfig: RemoteModelConfig,
        remoteHistory: List<ChatMessage>,
        imageAttachments: List<ChatImageAttachment>,
        stateBeforeSend: ChatUiState,
    ): PendingRemoteSendDisclosure {
        val base = buildPendingRemoteSendDisclosure(
            kind = RemoteSendDisclosureKind.CurrentInput,
            prompt = prompt,
            messagePrivacy = messagePrivacy,
            remoteConfig = remoteConfig,
            remoteHistory = remoteHistory,
            imageAttachments = imageAttachments,
            stateBeforeSend = stateBeforeSend,
        )
        val maskResult = outboundSafetyPolicy.maskSensitiveContent(prompt)
        return base.copy(
            forcedBySensitiveContent = true,
            allowMaskedSend = maskResult.didMask,
            maskedPrompt = if (maskResult.didMask) maskResult.maskedText else "",
            maskedPromptPreview = if (maskResult.didMask) {
                maskResult.maskedText.toRemoteSendPromptPreview()
            } else {
                ""
            },
        )
    }

    private fun String.toRemoteSendPromptPreview(): String {
        val collapsed = trim().replace(Regex("""\s+"""), " ")
        return if (collapsed.length <= SolinConstants.Ui.REMOTE_SEND_PROMPT_PREVIEW_MAX_CHARS) {
            collapsed
        } else {
            collapsed.take(SolinConstants.Ui.REMOTE_SEND_PROMPT_PREVIEW_MAX_CHARS).trimEnd() + "…"
        }
    }
}
