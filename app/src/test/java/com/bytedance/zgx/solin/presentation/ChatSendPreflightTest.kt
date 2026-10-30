package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.MessagePrivacy
import com.bytedance.zgx.solin.PendingAgentConfirmation
import com.bytedance.zgx.solin.PendingExternalOutcomeConfirmation
import com.bytedance.zgx.solin.PendingRemoteModeDisclosure
import com.bytedance.zgx.solin.PendingRemoteSendDisclosure
import com.bytedance.zgx.solin.RemoteModelConfig
import com.bytedance.zgx.solin.RemoteModelConnectivityStatus
import com.bytedance.zgx.solin.action.ActionDraft
import com.bytedance.zgx.solin.memory.explicitUserFactFrom
import com.bytedance.zgx.solin.memory.explicitUserPreferenceForgetFrom
import com.bytedance.zgx.solin.memory.explicitUserPreferenceFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChatSendPreflightTest {
    private val readyState = ChatUiState(isReady = true)

    private fun initial(
        prompt: String = "你好",
        state: ChatUiState = readyState,
        remoteSendConfirmed: Boolean = false,
        generationActive: Boolean = false,
    ) = ChatSendPreflight.initialSend(prompt, state, remoteSendConfirmed, generationActive)

    @Test
    fun pendingConfirmationBlocksEvenWhenRemoteSendWasConfirmed() {
        val state = readyState.copy(pendingConfirmation = confirmation())
        assertEquals(InitialSendDecision.Blocked("请先确认或取消待执行动作"), initial(state = state))
        assertEquals(
            InitialSendDecision.Blocked("请先确认或取消待执行动作"),
            initial(state = state, remoteSendConfirmed = true),
        )
    }

    @Test
    fun pendingRemoteModeDisclosureBlocksUnlessConfirmed() {
        val state = readyState.copy(pendingRemoteModeDisclosure = remoteModeDisclosure())
        assertEquals(InitialSendDecision.Blocked("请先确认远程模式提醒"), initial(state = state))
        assertEquals(InitialSendDecision.Proceed, initial(state = state, remoteSendConfirmed = true))
    }

    @Test
    fun pendingRemoteSendDisclosureBlocksUnlessConfirmed() {
        val state = readyState.copy(pendingRemoteSendDisclosure = remoteSendDisclosure())
        assertEquals(InitialSendDecision.Blocked("请先确认或取消远程发送"), initial(state = state))
        assertEquals(InitialSendDecision.Proceed, initial(state = state, remoteSendConfirmed = true))
    }

    @Test
    fun pendingExternalOutcomeBlocks() {
        val state = readyState.copy(
            pendingExternalOutcome = PendingExternalOutcomeConfirmation(
                runId = "run",
                requestId = "request",
                toolName = "open_app",
                title = "t",
                summary = "s",
            ),
        )
        assertEquals(InitialSendDecision.Blocked("请先确认外部动作结果"), initial(state = state))
    }

    @Test
    fun blankPromptBusyStateOrActiveGenerationAreIgnoredSilently() {
        assertEquals(InitialSendDecision.Ignore, initial(prompt = ""))
        // A blank prompt is ignored even while a confirmation is pending (no status nag).
        assertEquals(
            InitialSendDecision.Ignore,
            initial(prompt = "", state = readyState.copy(pendingConfirmation = confirmation())),
        )
        assertEquals(InitialSendDecision.Ignore, initial(state = readyState.copy(isBusy = true)))
        assertEquals(InitialSendDecision.Ignore, initial(generationActive = true))
    }

    @Test
    fun explicitMemoryCommandsRouteBeforeReadinessCheck() {
        val notReady = ChatUiState(isReady = false)
        val forget = "忘记我喜欢简洁的中文回答"
        val fact = "记住：我的工号是 xb83"
        val preference = "请记住：我喜欢简洁的中文回答"
        assertNotNull(explicitUserPreferenceForgetFrom(forget))
        assertNotNull(explicitUserFactFrom(fact))
        assertNotNull(explicitUserPreferenceFrom(preference))
        assertEquals(InitialSendDecision.ForgetMemoryCommand, initial(prompt = forget, state = notReady))
        assertEquals(InitialSendDecision.UserFactCommand, initial(prompt = fact, state = notReady))
        assertEquals(InitialSendDecision.PreferenceCommand, initial(prompt = preference, state = notReady))
    }

    @Test
    fun notReadyAndProceed() {
        assertEquals(InitialSendDecision.NotReady, initial(state = ChatUiState(isReady = false)))
        assertEquals(InitialSendDecision.Proceed, initial())
    }

    private val configured = RemoteModelConfig(baseUrl = "https://example.com/v1", modelName = "model-a")

    private fun remote(
        privacy: MessagePrivacy = MessagePrivacy.RemoteEligible,
        config: RemoteModelConfig = configured,
        confirmed: Boolean = false,
        sensitive: Boolean = false,
        disclosure: Boolean = false,
    ) = ChatSendPreflight.preparedRemoteSend(
        messagePrivacy = privacy,
        remoteConfig = config,
        remoteSendConfirmed = confirmed,
        containsSensitiveContent = { sensitive },
        requiresSendDisclosure = { disclosure },
    )

    @Test
    fun localOnlyContentNeverLeavesTheDeviceWhateverElseHolds() {
        assertEquals(
            RemoteSendPreflightDecision.KeepLocalOnlyContent,
            remote(privacy = MessagePrivacy.LocalOnly, confirmed = true, sensitive = true, disclosure = true),
        )
    }

    @Test
    fun knownConnectivityFailureBlocksEvenAConfirmedSend() {
        val unreachable = configured.copy(connectivityStatus = RemoteModelConnectivityStatus.Unreachable)
        val authFailed = configured.copy(connectivityStatus = RemoteModelConnectivityStatus.AuthenticationFailed)
        assertEquals(RemoteSendPreflightDecision.BlockKnownConnectivityFailure, remote(config = unreachable, confirmed = true))
        assertEquals(RemoteSendPreflightDecision.BlockKnownConnectivityFailure, remote(config = authFailed, sensitive = true))
    }

    @Test
    fun sensitiveContentToConfiguredRemoteRequiresConsentUntilConfirmed() {
        assertEquals(RemoteSendPreflightDecision.RequireSensitiveContentConsent, remote(sensitive = true, disclosure = true))
        assertEquals(RemoteSendPreflightDecision.Proceed, remote(sensitive = true, confirmed = true))
    }

    @Test
    fun sensitiveContentWithoutConfiguredRemoteIsProtectedEvenIfConfirmed() {
        val unconfigured = RemoteModelConfig()
        assertFalse(unconfigured.isConfigured)
        assertEquals(RemoteSendPreflightDecision.ProtectSensitiveContentWithoutRemote, remote(config = unconfigured, sensitive = true))
        assertEquals(
            RemoteSendPreflightDecision.ProtectSensitiveContentWithoutRemote,
            remote(config = unconfigured, sensitive = true, confirmed = true),
        )
    }

    @Test
    fun sendDisclosureRequiredOnlyForConfiguredUnconfirmedSends() {
        assertEquals(RemoteSendPreflightDecision.RequireSendDisclosure, remote(disclosure = true))
        assertEquals(RemoteSendPreflightDecision.Proceed, remote(disclosure = true, confirmed = true))
        assertEquals(RemoteSendPreflightDecision.Proceed, remote(config = RemoteModelConfig(), disclosure = true))
        assertEquals(RemoteSendPreflightDecision.Proceed, remote())
    }

    @Test
    fun lazyChecksOnlyRunWhenTheirBranchIsReached() {
        var sensitiveCalls = 0
        var disclosureCalls = 0
        ChatSendPreflight.preparedRemoteSend(
            messagePrivacy = MessagePrivacy.LocalOnly,
            remoteConfig = configured,
            remoteSendConfirmed = false,
            containsSensitiveContent = { sensitiveCalls++; true },
            requiresSendDisclosure = { disclosureCalls++; true },
        )
        assertEquals(0, sensitiveCalls)
        assertEquals(0, disclosureCalls)

        ChatSendPreflight.preparedRemoteSend(
            messagePrivacy = MessagePrivacy.RemoteEligible,
            remoteConfig = configured,
            remoteSendConfirmed = true,
            containsSensitiveContent = { sensitiveCalls++; true },
            requiresSendDisclosure = { disclosureCalls++; true },
        )
        // Confirmed + configured: the sensitive check is skipped and disclosure is not consulted.
        assertEquals(0, sensitiveCalls)
        assertEquals(0, disclosureCalls)
    }

    private fun confirmation() = PendingAgentConfirmation(
        runId = "run",
        draft = ActionDraft(functionName = "tool", title = "t", summary = "s", parameters = emptyMap()),
        toolRequest = null,
        skillId = null,
        plannedByModel = false,
        fallbackReason = null,
    )

    private fun remoteModeDisclosure() = PendingRemoteModeDisclosure(
        remoteHost = "example.com",
        remoteModelName = "model-a",
        apiKeyConfigured = true,
        supportsVisionInput = false,
        isConfigured = true,
    )

    private fun remoteSendDisclosure() = PendingRemoteSendDisclosure(
        prompt = "你好",
        messagePrivacy = MessagePrivacy.RemoteEligible,
        remoteHost = "example.com",
        remoteModelName = "model-a",
        remoteHistoryCount = 0,
        localOnlyHistoryFilteredCount = 0,
        imageAttachmentCount = 0,
        protectedSourceCount = 0,
        apiKeyConfigured = true,
    )
}
