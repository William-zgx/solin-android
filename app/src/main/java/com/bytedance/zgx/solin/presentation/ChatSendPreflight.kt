package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.memory.explicitUserFactFrom
import com.bytedance.zgx.solin.memory.explicitUserPreferenceForgetFrom
import com.bytedance.zgx.solin.memory.explicitUserPreferenceFrom

/** What to do with a fresh (not yet prepared) send before any routing happens. */
internal sealed interface InitialSendDecision {
    /** Nothing to send, or a send/generation is already running: drop silently. */
    data object Ignore : InitialSendDecision

    /** Another pending decision must be resolved first; only [statusText] is surfaced. */
    data class Blocked(val statusText: String) : InitialSendDecision

    data object ForgetMemoryCommand : InitialSendDecision

    data object UserFactCommand : InitialSendDecision

    data object PreferenceCommand : InitialSendDecision

    data object NotReady : InitialSendDecision

    data object Proceed : InitialSendDecision
}

/** What to do with a prepared execution headed to the remote model, before it is dispatched. */
internal sealed interface RemoteSendPreflightDecision {
    /** LocalOnly content never leaves the device: keep it local and explain. */
    data object KeepLocalOnlyContent : RemoteSendPreflightDecision

    /** The remote endpoint is known to be unreachable or rejecting credentials: block and audit. */
    data object BlockKnownConnectivityFailure : RemoteSendPreflightDecision

    /** Sensitive content to a configured remote: force the graded sensitive-content disclosure. */
    data object RequireSensitiveContentConsent : RemoteSendPreflightDecision

    /** Sensitive content with no configured remote: protect it locally and explain. */
    data object ProtectSensitiveContentWithoutRemote : RemoteSendPreflightDecision

    /** Ordinary content that the disclosure policy (or an image) says must be confirmed first. */
    data object RequireSendDisclosure : RemoteSendPreflightDecision

    data object Proceed : RemoteSendPreflightDecision
}

/**
 * Pure send preflight checks for `ChatController.sendMessageInternal`.
 *
 * The order of checks is the policy: privacy (LocalOnly) first, then known connectivity failure,
 * then sensitive-content consent, then the per-send disclosure. Changing the order changes which
 * protection wins, so keep it fail-closed.
 */
internal object ChatSendPreflight {
    fun initialSend(
        trimmedPrompt: String,
        state: ChatUiState,
        remoteSendConfirmed: Boolean,
        generationActive: Boolean,
    ): InitialSendDecision {
        val hasPrompt = trimmedPrompt.isNotEmpty()
        return when {
            hasPrompt && state.pendingConfirmation != null ->
                InitialSendDecision.Blocked("请先确认或取消待执行动作")
            hasPrompt && state.pendingRemoteModeDisclosure != null && !remoteSendConfirmed ->
                InitialSendDecision.Blocked("请先确认远程模式提醒")
            hasPrompt && state.pendingRemoteSendDisclosure != null && !remoteSendConfirmed ->
                InitialSendDecision.Blocked("请先确认或取消远程发送")
            hasPrompt && state.pendingExternalOutcome != null ->
                InitialSendDecision.Blocked("请先确认外部动作结果")
            !hasPrompt || state.isBusy || generationActive -> InitialSendDecision.Ignore
            explicitUserPreferenceForgetFrom(trimmedPrompt) != null -> InitialSendDecision.ForgetMemoryCommand
            explicitUserFactFrom(trimmedPrompt) != null -> InitialSendDecision.UserFactCommand
            explicitUserPreferenceFrom(trimmedPrompt) != null -> InitialSendDecision.PreferenceCommand
            !state.isReady -> InitialSendDecision.NotReady
            else -> InitialSendDecision.Proceed
        }
    }

    /**
     * [containsSensitiveContent] and [requiresSendDisclosure] are evaluated lazily, only for the
     * branches that need them, exactly as the inline checks did.
     */
    fun preparedRemoteSend(
        messagePrivacy: MessagePrivacy,
        remoteConfig: RemoteModelConfig,
        remoteSendConfirmed: Boolean,
        containsSensitiveContent: () -> Boolean,
        requiresSendDisclosure: () -> Boolean,
    ): RemoteSendPreflightDecision {
        if (messagePrivacy == MessagePrivacy.LocalOnly) return RemoteSendPreflightDecision.KeepLocalOnlyContent
        if (messagePrivacy != MessagePrivacy.RemoteEligible) return RemoteSendPreflightDecision.Proceed
        return when {
            remoteConfig.isConfigured && remoteConfig.hasKnownConnectivityFailure ->
                RemoteSendPreflightDecision.BlockKnownConnectivityFailure
            !remoteSendConfirmed && remoteConfig.isConfigured && containsSensitiveContent() ->
                RemoteSendPreflightDecision.RequireSensitiveContentConsent
            !remoteConfig.isConfigured && containsSensitiveContent() ->
                RemoteSendPreflightDecision.ProtectSensitiveContentWithoutRemote
            remoteConfig.isConfigured && !remoteSendConfirmed && requiresSendDisclosure() ->
                RemoteSendPreflightDecision.RequireSendDisclosure
            else -> RemoteSendPreflightDecision.Proceed
        }
    }
}
