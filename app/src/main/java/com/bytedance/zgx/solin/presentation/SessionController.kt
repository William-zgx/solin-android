package com.bytedance.zgx.solin.presentation

import com.bytedance.zgx.solin.AgentTraceRunUiSummary
import com.bytedance.zgx.solin.ChatMessage
import com.bytedance.zgx.solin.ChatUiState
import com.bytedance.zgx.solin.ModelHealthState
import com.bytedance.zgx.solin.data.SessionStore
import com.bytedance.zgx.solin.label
import com.bytedance.zgx.solin.orchestration.AssistantRouter
import com.bytedance.zgx.solin.runtime.LiteRtRuntime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns chat-session lifecycle: create / select / delete, and local runtime conversation restore.
 *
 * Extracted from [com.bytedance.zgx.solin.SolinViewModel] (Wave 3 Track C3). The ViewModel keeps
 * thin public wrappers and owns cross-cutting remote-pending / confirmation restore via callbacks.
 */
class SessionController(
    private val sessionRepository: SessionStore,
    private val assistantOrchestrator: AssistantRouter,
    private val runtime: LiteRtRuntime,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val runtimeLock: Mutex,
    private val clearPendingRemoteChatState: () -> Unit,
    private val resetRemoteSendDisclosureSuppression: () -> Unit,
    private val restorePendingAgentConfirmationIfAny: (clearMissing: Boolean) -> Unit,
    private val restorePendingExternalOutcomeIfAny: (clearMissing: Boolean) -> Unit,
    private val loadAgentTraceRuns: () -> List<AgentTraceRunUiSummary>,
) {
    private var sessionRestoreJob: Job? = null
    private var sessionRestoreGeneration: Long = 0L

    fun close() {
        sessionRestoreJob?.cancel()
        sessionRestoreJob = null
    }

    fun resetConversation() {
        createNewSession()
    }

    fun createNewSession() {
        if (uiState.value.isBusy) return
        clearPendingRemoteChatState()
        // New session is a fresh trust context; drop any session-scoped disclosure suppression.
        resetRemoteSendDisclosureSuppression()
        val messages = sessionRepository.createNewSession()
        val activeSessionId = sessionRepository.activeSessionId
        val restoreGeneration = nextSessionRestoreGeneration()
        uiState.update {
            it.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = activeSessionId,
                messages = messages,
                pendingConfirmation = null,
                pendingRemoteSendDisclosure = null,
                pendingExternalOutcome = null,
                pendingSharedInputDraft = null,
                latestRecoveryAction = null,
                agentTraceRuns = loadAgentTraceRuns(),
                isReady = if (runtime.isLoaded) false else it.isReady,
                statusText = if (!runtime.isLoaded) "新会话" else "正在开启新会话",
            )
        }
        restorePendingAgentConfirmationIfAny(true)
        restorePendingExternalOutcomeIfAny(true)
        if (runtime.isLoaded) {
            recreateConversationForMessages(
                successPrefix = "新会话",
                sessionId = activeSessionId,
                messages = messages,
                restoreGeneration = restoreGeneration,
            )
        }
    }

    fun selectSession(sessionId: String) {
        if (uiState.value.isBusy || uiState.value.activeSessionId == sessionId) return
        clearPendingRemoteChatState()
        // Switching sessions is a trust-context change; drop session-scoped disclosure suppression.
        resetRemoteSendDisclosureSuppression()
        val messages = sessionRepository.selectSession(sessionId) ?: return
        val activeSessionId = sessionRepository.activeSessionId
        val restoreGeneration = nextSessionRestoreGeneration()
        uiState.update {
            it.copy(
                activeSessionId = activeSessionId,
                messages = messages,
                pendingConfirmation = null,
                pendingRemoteSendDisclosure = null,
                pendingExternalOutcome = null,
                pendingSharedInputDraft = null,
                latestRecoveryAction = null,
                agentTraceRuns = loadAgentTraceRuns(),
                isReady = if (runtime.isLoaded) false else it.isReady,
                statusText = if (!runtime.isLoaded) "已切换会话" else "正在恢复会话",
            )
        }
        restorePendingAgentConfirmationIfAny(true)
        restorePendingExternalOutcomeIfAny(true)
        if (runtime.isLoaded) {
            recreateConversationForMessages(
                successPrefix = "已恢复会话",
                sessionId = activeSessionId,
                messages = messages,
                restoreGeneration = restoreGeneration,
            )
        }
    }

    fun deleteActiveSession() {
        if (uiState.value.isBusy) return
        clearPendingRemoteChatState()
        // Deleting the current session changes the remote trust context even when a replacement
        // empty session is created immediately.
        resetRemoteSendDisclosureSuppression()
        val deletedSessionId = sessionRepository.activeSessionId
        assistantOrchestrator.deleteRunsForSession(deletedSessionId)
        val messages = sessionRepository.deleteActiveSession() ?: return
        val activeSessionId = sessionRepository.activeSessionId
        val restoreGeneration = nextSessionRestoreGeneration()
        uiState.update {
            it.copy(
                sessions = sessionRepository.summaries(),
                activeSessionId = activeSessionId,
                messages = messages,
                pendingConfirmation = null,
                pendingRemoteSendDisclosure = null,
                pendingExternalOutcome = null,
                pendingSharedInputDraft = null,
                latestRecoveryAction = null,
                agentTraceRuns = loadAgentTraceRuns(),
                isReady = if (runtime.isLoaded) false else it.isReady,
                statusText = if (!runtime.isLoaded) "已删除会话" else "正在恢复会话",
            )
        }
        restorePendingAgentConfirmationIfAny(true)
        restorePendingExternalOutcomeIfAny(true)
        if (runtime.isLoaded) {
            recreateConversationForMessages(
                successPrefix = "已删除会话",
                sessionId = activeSessionId,
                messages = messages,
                restoreGeneration = restoreGeneration,
            )
        }
    }

    /**
     * Rebuilds the local runtime conversation for the current active session
     * (e.g. after generation-parameter changes).
     */
    fun recreateConversationForActiveSession(successPrefix: String) {
        val sessionId = sessionRepository.activeSessionId
        val history = sessionRepository.activeMessages()
        val restoreGeneration = nextSessionRestoreGeneration()
        recreateConversationForMessages(
            successPrefix = successPrefix,
            sessionId = sessionId,
            messages = history,
            restoreGeneration = restoreGeneration,
        )
    }

    private fun recreateConversationForMessages(
        successPrefix: String,
        sessionId: String,
        messages: List<ChatMessage>,
        restoreGeneration: Long,
    ) {
        sessionRestoreJob?.cancel()
        uiState.update {
            if (it.activeSessionId == sessionId) {
                it.copy(
                    isReady = false,
                    statusText = "正在恢复会话",
                    modelHealth = it.modelHealth.copy(
                        state = ModelHealthState.Loading,
                        backend = it.backend,
                    ),
                )
            } else {
                it
            }
        }
        sessionRestoreJob = scope.launch(ioDispatcher) {
            val result = runCatching {
                runtimeLock.withLock {
                    runtime.recreateConversation(
                        history = messages,
                        parameters = uiState.value.generationParameters,
                    )
                }
            }
            result.fold(
                onSuccess = {
                    uiState.update { current ->
                        if (current.activeSessionId == sessionId && restoreGeneration == sessionRestoreGeneration) {
                            current.copy(
                                isReady = true,
                                statusText = "$successPrefix · ${current.backend.label()}",
                                modelHealth = current.modelHealth.copy(
                                    state = ModelHealthState.Loaded,
                                    backend = current.backend,
                                    failureReason = null,
                                ),
                            )
                        } else {
                            current
                        }
                    }
                },
                onFailure = { throwable ->
                    uiState.update { current ->
                        if (current.activeSessionId == sessionId && restoreGeneration == sessionRestoreGeneration) {
                            current.copy(
                                isReady = false,
                                statusText = "恢复会话失败：${throwable.cleanMessage()}",
                                modelHealth = current.modelHealth.copy(
                                    state = ModelHealthState.LoadFailed,
                                    backend = current.backend,
                                    failureReason = throwable.cleanMessage(),
                                ),
                            )
                        } else {
                            current
                        }
                    }
                },
            )
        }
    }

    private fun nextSessionRestoreGeneration(): Long {
        sessionRestoreGeneration += 1
        return sessionRestoreGeneration
    }

    private fun Throwable.cleanMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
}
